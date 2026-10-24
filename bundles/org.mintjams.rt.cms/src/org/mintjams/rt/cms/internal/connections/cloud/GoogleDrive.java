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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Google Drive through the Drive API v3, with the {@code drive.file} scope:
 * the app sees the files it created and the files the user picked for it in
 * the Google Picker, and nothing else. A listing is therefore short; the
 * picker is where the user chooses.
 *
 * <p>A Google native document (Docs, Sheets, Slides, Drawings, ...) has no
 * bytes of its own: it is exported as an Office file, or as PDF when there is
 * no Office counterpart. Uploading with {@code convert} does the reverse:
 * Drive turns HTML, text or an Office file into a native document.
 */
public final class GoogleDrive extends CloudProvider {

	public static final String ID = "google";

	/** The base the Drive API and its upload endpoint hang off; clients.yml may override it. */
	public static final String DEFAULT_ENDPOINT = "https://www.googleapis.com";

	static final String FOLDER_TYPE = "application/vnd.google-apps.folder";
	static final String NATIVE_PREFIX = "application/vnd.google-apps.";

	private static final String FIELDS = "id,name,mimeType,size,webViewLink,modifiedTime";
	/** Files a listing returns at most, over its pages. */
	private static final int LIST_LIMIT = 1000;
	/** Content this size and below goes up in one multipart request; larger content in a resumable session. */
	private static final long MULTIPART_LIMIT = 5L * 1024 * 1024;

	/** How a native document kind is exported when the caller does not say. */
	private static final Map<String, String> DEFAULT_EXPORTS = Map.of(
			"document", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
			"spreadsheet", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
			"presentation", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
			"drawing", "image/png",
			"script", "application/vnd.google-apps.script+json");

	/** What an uploaded type is converted into, when conversion is asked for. */
	private static final Map<String, String> CONVERSIONS = Map.ofEntries(
			Map.entry("text/html", NATIVE_PREFIX + "document"),
			Map.entry("text/plain", NATIVE_PREFIX + "document"),
			Map.entry("text/markdown", NATIVE_PREFIX + "document"),
			Map.entry("application/rtf", NATIVE_PREFIX + "document"),
			Map.entry("application/msword", NATIVE_PREFIX + "document"),
			Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
					NATIVE_PREFIX + "document"),
			Map.entry("application/vnd.oasis.opendocument.text", NATIVE_PREFIX + "document"),
			Map.entry("application/pdf", NATIVE_PREFIX + "document"),
			Map.entry("text/csv", NATIVE_PREFIX + "spreadsheet"),
			Map.entry("text/tab-separated-values", NATIVE_PREFIX + "spreadsheet"),
			Map.entry("application/vnd.ms-excel", NATIVE_PREFIX + "spreadsheet"),
			Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
					NATIVE_PREFIX + "spreadsheet"),
			Map.entry("application/vnd.oasis.opendocument.spreadsheet", NATIVE_PREFIX + "spreadsheet"),
			Map.entry("application/vnd.ms-powerpoint", NATIVE_PREFIX + "presentation"),
			Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation",
					NATIVE_PREFIX + "presentation"),
			Map.entry("application/vnd.oasis.opendocument.presentation", NATIVE_PREFIX + "presentation"));

	private final String fApi;
	private final String fUpload;

	public GoogleDrive(String endpoint) {
		String base = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT : endpoint.trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		fApi = base + "/drive/v3";
		fUpload = base + "/upload/drive/v3";
	}

	@Override
	public String getId() {
		return ID;
	}

	/** The type a native document kind is exported as when the caller does not choose. */
	public static String defaultExportType(String nativeType) {
		return DEFAULT_EXPORTS.getOrDefault(nativeType, "application/pdf");
	}

	/** The native type an uploaded file of this type is converted into, or null when Drive cannot. */
	public static String conversionOf(String mimeType) {
		if (mimeType == null) {
			return null;
		}
		int semicolon = mimeType.indexOf(';');
		return CONVERSIONS.get((semicolon > 0 ? mimeType.substring(0, semicolon) : mimeType).trim().toLowerCase());
	}

	@Override
	public List<CloudEntry> children(String token, String folderId) throws IOException {
		String parent = (folderId == null || folderId.isBlank()) ? "root" : folderId;
		String q = "'" + parent.replace("\\", "\\\\").replace("'", "\\'") + "' in parents and trashed = false";
		List<CloudEntry> entries = new ArrayList<>();
		String pageToken = null;
		do {
			StringBuilder url = new StringBuilder(fApi).append("/files?q=").append(encode(q))
					.append("&fields=").append(encode("nextPageToken,files(" + FIELDS + ")"))
					.append("&orderBy=").append(encode("folder,name")).append("&pageSize=200")
					.append("&supportsAllDrives=true&includeItemsFromAllDrives=true");
			if (pageToken != null) {
				url.append("&pageToken=").append(encode(pageToken));
			}
			JsonObject page = json(request(url.toString(), token).GET().build());
			JsonArray files = page.getAsJsonArray("files");
			if (files != null) {
				for (JsonElement e : files) {
					entries.add(entry(e.getAsJsonObject()));
				}
			}
			pageToken = string(page, "nextPageToken");
		} while (pageToken != null && entries.size() < LIST_LIMIT);
		return entries;
	}

	@Override
	public CloudEntry get(String token, String id) throws IOException {
		try {
			return entry(json(request(fApi + "/files/" + segment(id) + "?fields=" + encode(FIELDS)
					+ "&supportsAllDrives=true", token).GET().build()));
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
		HttpRequest request;
		if (entry.isNative()) {
			String type = (exportMimeType == null || exportMimeType.isBlank())
					? defaultExportType(entry.getNativeType()) : exportMimeType;
			request = request(fApi + "/files/" + segment(entry.getId()) + "/export?mimeType=" + encode(type), token)
					.header("Accept", "*/*").GET().build();
		} else {
			request = request(fApi + "/files/" + segment(entry.getId()) + "?alt=media&supportsAllDrives=true", token)
					.header("Accept", "*/*").GET().build();
		}
		HttpResponse<InputStream> response = stream(request);
		String type = response.headers().firstValue("Content-Type").orElse(entry.getMimeType());
		long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
		return new Download(response.body(), type, length);
	}

	@Override
	public CloudEntry upload(String token, String folderId, String name, String mimeType, InputStream content,
			long length, boolean convert) throws IOException {
		JsonObject metadata = new JsonObject();
		metadata.addProperty("name", name);
		if (folderId != null && !folderId.isBlank()) {
			JsonArray parents = new JsonArray();
			parents.add(folderId);
			metadata.add("parents", parents);
		}
		if (convert) {
			String target = conversionOf(mimeType);
			if (target == null) {
				throw new IllegalArgumentException("Google Drive cannot turn " + mimeType + " into a Google document.");
			}
			metadata.addProperty("mimeType", target);
		}
		String contentType = (mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType;
		String fields = "&fields=" + encode(FIELDS) + "&supportsAllDrives=true";

		if (length >= 0 && length <= MULTIPART_LIMIT) {
			String boundary = "cms0-" + Long.toHexString(System.nanoTime());
			byte[] head = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + metadata
					+ "\r\n--" + boundary + "\r\nContent-Type: " + contentType + "\r\n\r\n")
					.getBytes(StandardCharsets.UTF_8);
			byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
			InputStream body = new SequenceInputStream(Collections.enumeration(List.of(
					new ByteArrayInputStream(head), content, new ByteArrayInputStream(tail))));
			long total = head.length + length + tail.length;
			HttpRequest request = request(fUpload + "/files?uploadType=multipart" + fields, token)
					.header("Content-Type", "multipart/related; boundary=" + boundary)
					.POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> body),
							total))
					.build();
			return entry(json(request));
		}

		// A resumable session: the metadata first, then the content in one PUT.
		HttpRequest open = request(fUpload + "/files?uploadType=resumable" + fields, token)
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("X-Upload-Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofString(metadata.toString(), StandardCharsets.UTF_8)).build();
		HttpResponse<String> opened = send(open);
		if (opened.statusCode() / 100 != 2) {
			throw refused(opened.statusCode(), opened.body());
		}
		Optional<String> location = opened.headers().firstValue("Location");
		if (location.isEmpty()) {
			throw new IOException("Google Drive opened no upload session.");
		}
		HttpRequest.BodyPublisher publisher = (length >= 0)
				? HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> content), length)
				: HttpRequest.BodyPublishers.ofInputStream(() -> content);
		HttpRequest put = HttpRequest.newBuilder(java.net.URI.create(location.get())).timeout(TIMEOUT)
				.header("Authorization", "Bearer " + token).header("Content-Type", contentType).PUT(publisher).build();
		return entry(json(put));
	}

	private CloudEntry entry(JsonObject file) {
		String mimeType = string(file, "mimeType");
		boolean folder = FOLDER_TYPE.equals(mimeType);
		String nativeType = (!folder && mimeType != null && mimeType.startsWith(NATIVE_PREFIX))
				? mimeType.substring(NATIVE_PREFIX.length()) : null;
		Instant modified = null;
		String modifiedTime = string(file, "modifiedTime");
		if (modifiedTime != null) {
			try {
				modified = Instant.parse(modifiedTime);
			} catch (Throwable ignore) {
				// Left unknown.
			}
		}
		return new CloudEntry(ID, string(file, "id"), string(file, "name"), mimeType, longOf(file, "size"), folder,
				string(file, "webViewLink"), modified, nativeType);
	}

}
