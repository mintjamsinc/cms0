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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What the files bundle does with a provider's drive, as one shape over
 * Google Drive and OneDrive. Every call takes the user's access token for the
 * files bundle; the caller gets it from ConnectionTokens.
 */
public abstract class CloudProvider {

	/** Follows redirects (OneDrive's /content answers with one to a pre-authorized URL). */
	protected static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	/**
	 * Does not follow redirects: for a download whose redirect leads to a
	 * pre-authorized URL that must be fetched without the bearer token.
	 */
	protected static final HttpClient HTTP_DIRECT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NEVER).build();

	protected static final Duration TIMEOUT = Duration.ofMinutes(5);

	/** The provider's answer to a request it refused. */
	public static final class CloudException extends IOException {
		private static final long serialVersionUID = 1L;
		private final int fStatus;

		public CloudException(int status, String message) {
			super(message);
			fStatus = status;
		}

		public int getStatus() {
			return fStatus;
		}
	}

	/** What a download hands back: the stream, its type and length when known. */
	public static final class Download implements AutoCloseable {
		private final InputStream fStream;
		private final String fMimeType;
		private final long fLength;

		public Download(InputStream stream, String mimeType, long length) {
			fStream = stream;
			fMimeType = mimeType;
			fLength = length;
		}

		public InputStream getStream() {
			return fStream;
		}

		public String getMimeType() {
			return fMimeType;
		}

		/** Bytes, or -1 when unknown. */
		public long getLength() {
			return fLength;
		}

		@Override
		public void close() throws IOException {
			fStream.close();
		}
	}

	public abstract String getId();

	/** The files and folders in a folder; the drive's root when {@code folderId} is null. */
	public abstract List<CloudEntry> children(String token, String folderId) throws IOException;

	/** One entry by id, or null when there is none. */
	public abstract CloudEntry get(String token, String id) throws IOException;

	/**
	 * The content of a file. A Google native document is exported as
	 * {@code exportMimeType} (or the usual Office type for its kind when null).
	 */
	public abstract Download download(String token, CloudEntry entry, String exportMimeType) throws IOException;

	/**
	 * Uploads content as a new file in a folder (the root when {@code folderId}
	 * is null). With {@code convert}, Google turns an HTML, text or Office file
	 * into a native document; OneDrive stores the bytes as they are.
	 */
	public abstract CloudEntry upload(String token, String folderId, String name, String mimeType, InputStream content,
			long length, boolean convert) throws IOException;

	// ---- helpers shared by the providers ----------------------------------------

	protected static HttpRequest.Builder request(String url, String token) {
		return HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).header("Authorization", "Bearer " + token)
				.header("Accept", "application/json");
	}

	protected static JsonObject json(HttpRequest request) throws IOException {
		HttpResponse<String> response;
		try {
			response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while calling " + request.uri().getHost());
		}
		if (response.statusCode() / 100 != 2) {
			throw refused(response.statusCode(), response.body());
		}
		String body = response.body();
		if (body == null || body.isBlank()) {
			return new JsonObject();
		}
		try {
			return JsonParser.parseString(body).getAsJsonObject();
		} catch (Throwable ex) {
			throw new IOException("The provider answered without JSON.");
		}
	}

	protected static HttpResponse<InputStream> stream(HttpRequest request) throws IOException {
		return stream(HTTP, request);
	}

	protected static HttpResponse<InputStream> stream(HttpClient client, HttpRequest request) throws IOException {
		HttpResponse<InputStream> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while calling " + request.uri().getHost());
		}
		if (response.statusCode() / 100 != 2 && response.statusCode() / 100 != 3) {
			String body;
			try (InputStream in = response.body()) {
				body = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
			}
			throw refused(response.statusCode(), body);
		}
		return response;
	}

	/** Sends a request whose answer is of no interest beyond its status; the body is read and dropped. */
	protected static HttpResponse<String> send(HttpRequest request) throws IOException {
		try {
			return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while calling " + request.uri().getHost());
		}
	}

	/** The usual file name extension of a type, with the dot, or an empty string. */
	protected static String extensionOf(String mimeType) {
		if (mimeType == null) {
			return "";
		}
		switch (mimeType) {
		case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
			return ".docx";
		case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
			return ".xlsx";
		case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
			return ".pptx";
		case "application/vnd.oasis.opendocument.text":
			return ".odt";
		case "application/vnd.oasis.opendocument.spreadsheet":
			return ".ods";
		case "application/vnd.oasis.opendocument.presentation":
			return ".odp";
		case "application/pdf":
			return ".pdf";
		case "text/plain":
			return ".txt";
		case "text/csv":
			return ".csv";
		case "text/html":
			return ".html";
		case "text/markdown":
			return ".md";
		case "application/rtf":
			return ".rtf";
		case "application/epub+zip":
			return ".epub";
		case "image/png":
			return ".png";
		case "image/jpeg":
			return ".jpg";
		case "image/svg+xml":
			return ".svg";
		case "application/zip":
			return ".zip";
		case "application/json":
			return ".json";
		default:
			return "";
		}
	}

	/** Reads the provider's error message out of its body, when there is one. */
	protected static CloudException refused(int status, String body) {
		String message = null;
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			JsonElement error = json.get("error");
			if (error != null && error.isJsonObject()) {
				JsonObject e = error.getAsJsonObject();
				if (e.has("message")) {
					message = e.get("message").getAsString();
				} else if (e.has("code")) {
					message = e.get("code").getAsString();
				}
			} else if (error != null && error.isJsonPrimitive()) {
				message = error.getAsString();
			}
		} catch (Throwable ignore) {
			// Not JSON.
		}
		if (message == null || message.isBlank()) {
			message = (status == 401) ? "the token was not accepted"
					: (status == 403) ? "access to the file was refused"
					: (status == 404) ? "the file was not found" : "HTTP " + status;
		}
		return new CloudException(status, message);
	}

	protected static String string(JsonObject json, String name) {
		JsonElement e = json.get(name);
		return (e == null || e.isJsonNull()) ? null : e.getAsString();
	}

	protected static long longOf(JsonObject json, String name) {
		JsonElement e = json.get(name);
		if (e == null || e.isJsonNull()) {
			return -1;
		}
		try {
			return e.getAsLong();
		} catch (Throwable ex) {
			try {
				return Long.parseLong(e.getAsString().trim());
			} catch (Throwable ignore) {
				return -1;
			}
		}
	}

	protected static String encode(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/** The last segment of a path-like name, with the characters a URL segment cannot hold escaped. */
	protected static String segment(String name) {
		return encode(name);
	}

}
