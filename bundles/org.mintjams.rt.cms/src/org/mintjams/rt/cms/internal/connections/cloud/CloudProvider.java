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
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What the files bundle does with a provider's drives, as one shape over
 * Google Drive and OneDrive. Every call takes the user's access token for the
 * files bundle (the caller gets it from ConnectionTokens) and a drive id:
 * {@value CloudEntry#MY_DRIVE} for the user's own drive, else a shared drive
 * (Google) or a document library (Microsoft) the user is a member of.
 *
 * <p>A request the provider refuses is reported as {@link CloudException}
 * with the provider's status and words; a missing item as
 * {@link NotFoundException}; being throttled as {@link RateLimitedException},
 * after one wait for the time the provider asked for. A 401 is left to the
 * caller, who throws the cached token away and tries once more
 * ({@link CloudFiles#call}).
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

	/** The wait when the provider asks for one without saying how long. */
	private static final long DEFAULT_RETRY_AFTER_MILLIS = 2_000;
	/** The longest wait honoured before giving up as rate limited. */
	private static final long MAX_RETRY_AFTER_MILLIS = 30_000;

	/** How a listing is ordered: the two orders both providers can produce. */
	public enum Order {
		NAME, MODIFIED_DESC
	}

	/** What a search covers: the folder being browsed, or the whole drive it is in. */
	public enum SearchScope {
		FOLDER, DRIVE
	}

	/** The conditions a search can be narrowed by. */
	public enum Facet {
		KIND, MODIFIED, OWNER
	}

	/** The provider's answer to a request it refused. */
	public static class CloudException extends IOException {
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

	/** The item does not exist on the drive (or the user cannot see it). */
	public static final class NotFoundException extends CloudException {
		private static final long serialVersionUID = 1L;

		public NotFoundException(String message) {
			super(404, message);
		}
	}

	/** The provider is throttling the user or the app; try again later. */
	public static final class RateLimitedException extends CloudException {
		private static final long serialVersionUID = 1L;
		private final long fRetryAfterMillis;

		public RateLimitedException(String message, long retryAfterMillis) {
			super(429, message);
			fRetryAfterMillis = retryAfterMillis;
		}

		public long getRetryAfterMillis() {
			return fRetryAfterMillis;
		}
	}

	/** The content was changed by someone else since the caller read it ({@code If-Match} failed). */
	public static final class ConflictException extends CloudException {
		private static final long serialVersionUID = 1L;

		public ConflictException(String message) {
			super(412, message);
		}
	}

	/**
	 * What a download hands back: the stream, its type and length when known,
	 * and, when a range was asked for and honoured, the range served.
	 */
	public static final class Download implements AutoCloseable {
		private final InputStream fStream;
		private final String fMimeType;
		private final long fLength;
		private final String fContentRange;

		public Download(InputStream stream, String mimeType, long length, String contentRange) {
			fStream = stream;
			fMimeType = mimeType;
			fLength = length;
			fContentRange = contentRange;
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

		/** The {@code Content-Range} the provider answered with, or null for the whole content. */
		public String getContentRange() {
			return fContentRange;
		}

		public boolean isPartial() {
			return fContentRange != null;
		}

		@Override
		public void close() throws IOException {
			fStream.close();
		}
	}

	/** A cache of entries by drive and id, for walking up a hierarchy one parent at a time. */
	public interface EntryCache {
		/** The cached entry, or the loader's, which is then cached. The loader returns null for a missing entry. */
		CloudEntry get(String driveId, String id, EntryLoader loader) throws IOException;
	}

	public interface EntryLoader {
		CloudEntry load() throws IOException;
	}

	public abstract String getId();

	// ---- capabilities ------------------------------------------------------------

	/** Whether a search runs over the folder being browsed or over the whole drive. */
	public abstract SearchScope getSearchScope();

	/** The conditions a search can be narrowed by, in display order. */
	public abstract List<Facet> getFacets();

	/** Whether {@link #previewUrl} can answer. */
	public abstract boolean canPreview();

	/** Whether {@link #copy} takes a folder (and copies what is in it). */
	public abstract boolean canCopyFolder();

	// ---- drives ------------------------------------------------------------------

	/** The user's own drive. */
	public abstract CloudDrive myDrive(String token) throws IOException;

	/** The shared drives / libraries the user is a member of, by name. */
	public abstract List<CloudDrive> sharedDrives(String token) throws IOException;

	/** One drive by id ({@value CloudEntry#MY_DRIVE} for the user's own), or null when there is none. */
	public abstract CloudDrive drive(String token, String driveId) throws IOException;

	// ---- reading -----------------------------------------------------------------

	/**
	 * One page of the files and folders in a folder (the drive's root when
	 * {@code folderId} is null), in the provider's order. {@code cursor} is the
	 * previous page's {@link CloudPage#getNextCursor()}, null for the first.
	 */
	public abstract CloudPage children(String token, String driveId, String folderId, String cursor, int pageSize,
			Order order) throws IOException;

	/** One entry by id, or null when there is none. */
	public abstract CloudEntry get(String token, String driveId, String id) throws IOException;

	/** The entry of this name in a folder (the root when {@code folderId} is null), or null. */
	public abstract CloudEntry child(String token, String driveId, String folderId, String name) throws IOException;

	/**
	 * The folders above an entry, from the first below the drive's root down
	 * to the entry's parent; empty at the root. The root itself is the drive.
	 * Entries read on the way are kept in the cache.
	 */
	public abstract List<CloudEntry> ancestors(String token, String driveId, String id, EntryCache cache)
			throws IOException;

	/**
	 * The content of a file. A Google native document is exported as
	 * {@code exportMimeType} (or the usual Office type for its kind when null);
	 * {@code range}, a {@code Range} header value, is passed on to the provider
	 * for an ordinary file.
	 */
	public abstract Download download(String token, CloudEntry entry, String exportMimeType, String range)
			throws IOException;

	/** A thumbnail of the entry ({@code small}, {@code medium} or {@code large}), or null when the provider has none. */
	public abstract Download thumbnail(String token, CloudEntry entry, String size) throws IOException;

	/**
	 * One page of a search in the folder (the whole drive when
	 * {@link #getSearchScope()} is {@link SearchScope#DRIVE}).
	 */
	public abstract CloudPage search(String token, String driveId, String folderId, CloudQuery query, String cursor,
			int pageSize) throws IOException;

	/** A URL that embeds a viewer of the file, or null when the provider has none. */
	public abstract String previewUrl(String token, String driveId, String id) throws IOException;

	// ---- writing -----------------------------------------------------------------

	public abstract CloudEntry createFolder(String token, String driveId, String parentId, String name)
			throws IOException;

	public abstract CloudEntry rename(String token, String driveId, String id, String name) throws IOException;

	/** Moves the entry to the provider's trash. */
	public abstract void delete(String token, String driveId, String id) throws IOException;

	/** Moves the entry to another folder of the same drive (the root when {@code toFolderId} is null). */
	public abstract CloudEntry move(String token, String driveId, String id, String toFolderId) throws IOException;

	/**
	 * Copies a file into a folder of a drive of the same provider, under
	 * {@code name} or its own. A folder only when {@link #canCopyFolder()}.
	 */
	public abstract CloudEntry copy(String token, String driveId, String id, String toDriveId, String toFolderId,
			String name) throws IOException;

	/**
	 * Uploads content as a new file in a folder (the root when {@code folderId}
	 * is null). With {@code convert}, Google turns an HTML, text or Office file
	 * into a native document; OneDrive stores the bytes as they are. A file of
	 * the name may already exist: the caller resolves that first
	 * ({@link CloudFiles#upload}).
	 */
	public abstract CloudEntry upload(String token, String driveId, String folderId, String name, String mimeType,
			InputStream content, long length, boolean convert) throws IOException;

	/**
	 * Replaces the content of a file. {@code ifMatch} is the
	 * {@link CloudEntry#getEtag() etag} the caller read; when the file changed
	 * since, nothing is written and {@link ConflictException} is thrown.
	 */
	public abstract CloudEntry update(String token, String driveId, String id, String mimeType, InputStream content,
			long length, String ifMatch) throws IOException;

	// ---- helpers shared by the providers ----------------------------------------

	protected static HttpRequest.Builder request(String url, String token) {
		return HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).header("Authorization", "Bearer " + token)
				.header("Accept", "application/json");
	}

	/** Sends and reads a JSON answer; a throttled request is sent once more after the wait asked for. */
	protected static JsonObject json(HttpRequest request) throws IOException {
		return parse(text(request, true));
	}

	/** As {@link #json}, for a request whose body cannot be sent twice (an upload). */
	protected static JsonObject jsonOnce(HttpRequest request) throws IOException {
		return parse(text(request, false));
	}

	private static JsonObject parse(String body) throws IOException {
		if (body == null || body.isBlank()) {
			return new JsonObject();
		}
		try {
			return JsonParser.parseString(body).getAsJsonObject();
		} catch (Throwable ex) {
			throw new IOException("The provider answered without JSON.");
		}
	}

	/** Sends and returns the body of a 2xx answer as text; anything else is thrown. */
	protected static String text(HttpRequest request, boolean retryable) throws IOException {
		HttpResponse<String> response = exchange(request, retryable);
		if (response.statusCode() / 100 != 2) {
			throw refused(response.statusCode(), response.body(), response.headers());
		}
		return response.body();
	}

	/**
	 * Sends and returns the answer whatever its status, except that a
	 * throttled request is sent once more after the wait, and thrown as
	 * {@link RateLimitedException} when throttled again.
	 */
	protected static HttpResponse<String> exchange(HttpRequest request, boolean retryable) throws IOException {
		HttpResponse<String> response = send(request);
		if (retryable && isRateLimited(response.statusCode(), response.body())) {
			pause(retryAfterMillis(response.headers()));
			response = send(request);
		}
		if (isRateLimited(response.statusCode(), response.body())) {
			throw refused(response.statusCode(), response.body(), response.headers());
		}
		return response;
	}

	protected static HttpResponse<InputStream> stream(HttpRequest request) throws IOException {
		return stream(HTTP, request);
	}

	/** Sends and returns a 2xx or 3xx answer as a stream; anything else is thrown. */
	protected static HttpResponse<InputStream> stream(HttpClient client, HttpRequest request) throws IOException {
		for (int attempt = 0;; attempt++) {
			HttpResponse<InputStream> response;
			try {
				response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while calling " + request.uri().getHost());
			}
			if (response.statusCode() / 100 == 2 || response.statusCode() / 100 == 3) {
				return response;
			}
			String body;
			try (InputStream in = response.body()) {
				body = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
			}
			if (attempt == 0 && isRateLimited(response.statusCode(), body)) {
				pause(retryAfterMillis(response.headers()));
				continue;
			}
			throw refused(response.statusCode(), body, response.headers());
		}
	}

	/** Sends a request and returns whatever came back; nothing is thrown for a refusal. */
	protected static HttpResponse<String> send(HttpRequest request) throws IOException {
		try {
			return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while calling " + request.uri().getHost());
		}
	}

	/** A request without the bearer token: for a pre-authorized URL a provider redirected to. */
	protected static HttpRequest.Builder plain(String url) {
		return HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT);
	}

	private static void pause(long millis) throws IOException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the provider.");
		}
	}

	/** Whether the provider is throttling: 429, or Google's 403 with a rate-limit reason. */
	protected static boolean isRateLimited(int status, String body) {
		if (status == 429) {
			return true;
		}
		if (status != 403 || body == null) {
			return false;
		}
		return body.contains("RateLimitExceeded") || body.contains("rateLimitExceeded")
				|| body.contains("userRateLimitExceeded") || body.contains("quotaExceeded");
	}

	/** The wait the provider asked for ({@code Retry-After}), within reason. */
	protected static long retryAfterMillis(HttpHeaders headers) {
		String value = headers.firstValue("Retry-After").orElse(null);
		if (value == null || value.isBlank()) {
			return DEFAULT_RETRY_AFTER_MILLIS;
		}
		try {
			long seconds = Long.parseLong(value.trim());
			return Math.max(0, Math.min(seconds * 1000, MAX_RETRY_AFTER_MILLIS));
		} catch (NumberFormatException notSeconds) {
			try {
				long at = java.time.ZonedDateTime.parse(value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
						.toInstant().toEpochMilli();
				return Math.max(0, Math.min(at - System.currentTimeMillis(), MAX_RETRY_AFTER_MILLIS));
			} catch (Throwable ignore) {
				return DEFAULT_RETRY_AFTER_MILLIS;
			}
		}
	}

	/** The usual file name extension of a type, with the dot, or an empty string. */
	public static String extensionOf(String mimeType) {
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

	/** Reads the provider's error message out of its body, when there is one, into the matching exception. */
	protected static CloudException refused(int status, String body, HttpHeaders headers) {
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
					: (status == 404) ? "the file was not found"
					: (status == 412) ? "the file was changed elsewhere"
					: (status == 429) ? "too many requests" : "HTTP " + status;
		}
		if (isRateLimited(status, body)) {
			return new RateLimitedException(message, (headers != null) ? retryAfterMillis(headers) : DEFAULT_RETRY_AFTER_MILLIS);
		}
		if (status == 404) {
			return new NotFoundException(message);
		}
		if (status == 412) {
			return new ConflictException(message);
		}
		return new CloudException(status, message);
	}

	protected static String string(JsonObject json, String name) {
		JsonElement e = json.get(name);
		return (e == null || e.isJsonNull()) ? null : e.getAsString();
	}

	protected static boolean bool(JsonObject json, String name) {
		JsonElement e = json.get(name);
		try {
			return e != null && !e.isJsonNull() && e.getAsBoolean();
		} catch (Throwable ex) {
			return false;
		}
	}

	protected static JsonObject object(JsonObject json, String name) {
		JsonElement e = json.get(name);
		return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
	}

	protected static JsonArray array(JsonObject json, String name) {
		JsonElement e = json.get(name);
		return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
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

	protected static Instant instant(JsonObject json, String name) {
		String text = string(json, name);
		if (text == null) {
			return null;
		}
		try {
			return Instant.parse(text);
		} catch (Throwable ignore) {
			try {
				return java.time.OffsetDateTime.parse(text).toInstant();
			} catch (Throwable ignore2) {
				return null;
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

	/** Whether a page size asked for is usable; the providers take up to 1,000 (Drive) / 999 (Graph). */
	protected static int pageSize(int wanted, int max) {
		return (wanted <= 0) ? Math.min(200, max) : Math.min(wanted, max);
	}

}
