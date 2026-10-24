/*
 * Copyright (c) 2026 MintJams Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.mintjams.rt.cms.internal.connections.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * OneDrive through Microsoft Graph v1.0, with {@code Files.ReadWrite}: the
 * user's whole drive is in reach, so a listing is the way to choose.
 *
 * <p>A download is a redirect to a pre-authorized URL, which is fetched
 * without the bearer token. An upload of 4 MB or less is one PUT; a larger
 * one goes through an upload session in 10 MiB pieces. OneDrive stores the
 * bytes as they are: {@code convert} has no meaning here and is ignored.
 */
public final class OneDrive extends CloudProvider {

	public static final String ID = "microsoft";

	public static final String DEFAULT_ENDPOINT = "https://graph.microsoft.com/v1.0";

	private static final String SELECT = "id,name,size,file,folder,webUrl,lastModifiedDateTime";
	private static final int LIST_LIMIT = 1000;
	/** Content this size and below goes up in one PUT. */
	private static final long SIMPLE_UPLOAD_LIMIT = 4L * 1024 * 1024;
	/** The size of a piece of an upload session: a multiple of 320 KiB, as Graph requires. */
	private static final int CHUNK_SIZE = 10 * 1024 * 1024;

	private final String fApi;

	public OneDrive(String endpoint) {
		String base = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT : endpoint.trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		fApi = base;
	}

	@Override
	public String getId() {
		return ID;
	}

	private String item(String id) {
		return (id == null || id.isBlank()) ? fApi + "/me/drive/root" : fApi + "/me/drive/items/" + segment(id);
	}

	@Override
	public List<CloudEntry> children(String token, String folderId) throws IOException {
		List<CloudEntry> entries = new ArrayList<>();
		String url = item(folderId) + "/children?$select=" + encode(SELECT) + "&$top=200&$orderby=name";
		while (url != null && entries.size() < LIST_LIMIT) {
			JsonObject page = json(request(url, token).GET().build());
			JsonArray value = page.getAsJsonArray("value");
			if (value != null) {
				for (JsonElement e : value) {
					entries.add(entry(e.getAsJsonObject()));
				}
			}
			url = string(page, "@odata.nextLink");
		}
		// Folders first, as a file browser lists them.
		entries.sort((a, b) -> {
			if (a.isFolder() != b.isFolder()) {
				return a.isFolder() ? -1 : 1;
			}
			return String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
		});
		return entries;
	}

	@Override
	public CloudEntry get(String token, String id) throws IOException {
		try {
			return entry(json(request(item(id) + "?$select=" + encode(SELECT), token).GET().build()));
		} catch (CloudException ex) {
			if (ex.getStatus() == 404) {
				return null;
			}
			throw ex;
		}
	}

	@Override
	public Download download(String token, CloudEntry entry, String exportMimeType) throws IOException {
		if (entry.isFolder()) {
			throw new IllegalArgumentException(entry.getName() + " is a folder.");
		}
		HttpRequest request = request(item(entry.getId()) + "/content", token).header("Accept", "*/*").GET().build();
		HttpResponse<InputStream> response = stream(HTTP_DIRECT, request);
		if (response.statusCode() / 100 == 3) {
			response.body().close();
			Optional<String> location = response.headers().firstValue("Location");
			if (location.isEmpty()) {
				throw new IOException("OneDrive redirected the download nowhere.");
			}
			// The pre-authorized URL carries its own credentials: no bearer token.
			response = stream(HttpRequest.newBuilder(URI.create(location.get())).timeout(TIMEOUT).GET().build());
		}
		String type = response.headers().firstValue("Content-Type").orElse(entry.getMimeType());
		long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
		return new Download(response.body(), type, length);
	}

	@Override
	public CloudEntry upload(String token, String folderId, String name, String mimeType, InputStream content,
			long length, boolean convert) throws IOException {
		String contentType = (mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType;
		String target = item(folderId) + ":/" + segment(name) + ":";

		if (length >= 0 && length <= SIMPLE_UPLOAD_LIMIT) {
			HttpRequest request = request(target + "/content?@microsoft.graph.conflictBehavior=rename", token)
					.header("Content-Type", contentType)
					.PUT(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> content),
							length))
					.build();
			return entry(json(request));
		}
		if (length < 0) {
			throw new IllegalArgumentException("The size of the content has to be known to upload it to OneDrive.");
		}

		JsonObject session = new JsonObject();
		JsonObject item = new JsonObject();
		item.addProperty("@microsoft.graph.conflictBehavior", "rename");
		item.addProperty("name", name);
		session.add("item", item);
		JsonObject opened = json(request(target + "/createUploadSession", token)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(session.toString(), StandardCharsets.UTF_8)).build());
		String uploadUrl = string(opened, "uploadUrl");
		if (uploadUrl == null) {
			throw new IOException("OneDrive opened no upload session.");
		}

		byte[] buffer = new byte[CHUNK_SIZE];
		long offset = 0;
		JsonObject result = null;
		while (offset < length) {
			int want = (int) Math.min(buffer.length, length - offset);
			int read = 0;
			while (read < want) {
				int n = content.read(buffer, read, want - read);
				if (n < 0) {
					throw new IOException("The content ended after " + (offset + read) + " of " + length + " bytes.");
				}
				read += n;
			}
			HttpRequest put = HttpRequest.newBuilder(URI.create(uploadUrl)).timeout(Duration.ofMinutes(10))
					.header("Content-Range", "bytes " + offset + "-" + (offset + read - 1) + "/" + length)
					.PUT(HttpRequest.BodyPublishers.ofByteArray(buffer, 0, read)).build();
			HttpResponse<String> response = send(put);
			if (response.statusCode() / 100 != 2) {
				throw refused(response.statusCode(), response.body());
			}
			offset += read;
			if (response.statusCode() == 200 || response.statusCode() == 201) {
				result = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
			}
		}
		if (result == null || !result.has("id")) {
			throw new IOException("OneDrive did not confirm the upload.");
		}
		return entry(result);
	}

	private CloudEntry entry(JsonObject item) {
		boolean folder = item.has("folder") && !item.get("folder").isJsonNull();
		String mimeType = null;
		if (item.has("file") && item.get("file").isJsonObject()) {
			mimeType = string(item.getAsJsonObject("file"), "mimeType");
		}
		Instant modified = null;
		String time = string(item, "lastModifiedDateTime");
		if (time != null) {
			try {
				modified = Instant.parse(time);
			} catch (Throwable ignore) {
				// Left unknown.
			}
		}
		return new CloudEntry(ID, string(item, "id"), string(item, "name"), mimeType,
				folder ? -1 : longOf(item, "size"), folder, string(item, "webUrl"), modified, null);
	}

}
