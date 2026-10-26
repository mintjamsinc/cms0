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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Google Drive through the Drive API v3, with the {@code drive} scope: the
 * user's My Drive ({@value CloudEntry#MY_DRIVE}) and the shared drives the
 * user is a member of (their drive id is also the id of their root folder).
 */
public final class GoogleDrive extends CloudProvider {

	public static final String ID = "google";

	/** The base the Drive API and its upload endpoint hang off; clients.yml may override it. */
	public static final String DEFAULT_ENDPOINT = "https://www.googleapis.com";

	static final String FOLDER_TYPE = "application/vnd.google-apps.folder";
	static final String NATIVE_PREFIX = "application/vnd.google-apps.";

	private static final String FIELDS = "id,name,mimeType,size,webViewLink,modifiedTime,createdTime,parents,version,"
			+ "owners(displayName,me),shared,hasThumbnail,driveId,trashed";
	private static final String ALL_DRIVES = "&supportsAllDrives=true";
	private static final int MAX_PAGE = 1000;
	/** Content this size and below goes up in one multipart request; larger content in a resumable session. */
	private static final long MULTIPART_LIMIT = 5L * 1024 * 1024;
	/** The pixel size of a thumbnail, by the size name a client asks for. */
	private static final Map<String, Integer> THUMBNAIL_SIZES = Map.of("small", 200, "medium", 400, "large", 800);

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

	@Override
	public SearchScope getSearchScope() {
		// Drive has no "below this folder" condition ('in parents' is the direct
		// children only), so a search covers the drive being browsed.
		return SearchScope.DRIVE;
	}

	@Override
	public List<Facet> getFacets() {
		return List.of(Facet.KIND, Facet.MODIFIED, Facet.OWNER);
	}

	@Override
	public boolean canPreview() {
		return false;
	}

	@Override
	public boolean canCopyFolder() {
		return false;
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

	private static boolean isMine(String driveId) {
		return driveId == null || driveId.isBlank() || CloudEntry.MY_DRIVE.equals(driveId);
	}

	/** The id of a drive's root folder: {@code root} for My Drive, the drive id for a shared drive. */
	private static String rootOf(String driveId) {
		return isMine(driveId) ? "root" : driveId;
	}

	private static String folderOrRoot(String driveId, String folderId) {
		return (folderId == null || folderId.isBlank()) ? rootOf(driveId) : folderId;
	}

	/** The corpora parameters that confine a listing or a search to one drive. */
	private static String corpora(String driveId) {
		if (isMine(driveId)) {
			return "&corpora=user" + ALL_DRIVES;
		}
		return "&corpora=drive&driveId=" + encode(driveId) + "&includeItemsFromAllDrives=true" + ALL_DRIVES;
	}

	private static String quote(String value) {
		return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
	}

	// ---- drives ------------------------------------------------------------------

	@Override
	public CloudDrive myDrive(String token) {
		return new CloudDrive(ID, CloudEntry.MY_DRIVE, "My Drive", CloudDrive.Kind.MINE,
				"https://drive.google.com/drive/my-drive");
	}

	@Override
	public List<CloudDrive> sharedDrives(String token) throws IOException {
		List<CloudDrive> drives = new ArrayList<>();
		String pageToken = null;
		do {
			StringBuilder url = new StringBuilder(fApi).append("/drives?pageSize=100&fields=")
					.append(encode("nextPageToken,drives(id,name)"));
			if (pageToken != null) {
				url.append("&pageToken=").append(encode(pageToken));
			}
			JsonObject page = json(request(url.toString(), token).GET().build());
			JsonArray list = array(page, "drives");
			if (list != null) {
				for (JsonElement e : list) {
					drives.add(drive(e.getAsJsonObject()));
				}
			}
			pageToken = string(page, "nextPageToken");
		} while (pageToken != null && drives.size() < MAX_PAGE);
		drives.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()));
		return drives;
	}

	@Override
	public CloudDrive drive(String token, String driveId) throws IOException {
		if (isMine(driveId)) {
			return myDrive(token);
		}
		try {
			return drive(json(request(fApi + "/drives/" + segment(driveId) + "?fields=" + encode("id,name"), token)
					.GET().build()));
		} catch (NotFoundException ex) {
			return null;
		}
	}

	private static CloudDrive drive(JsonObject drive) {
		String id = string(drive, "id");
		return new CloudDrive(ID, id, string(drive, "name"), CloudDrive.Kind.SHARED,
				"https://drive.google.com/drive/folders/" + id);
	}

	// ---- reading -----------------------------------------------------------------

	@Override
	public CloudPage children(String token, String driveId, String folderId, String cursor, int pageSize, Order order)
			throws IOException {
		String q = quote(folderOrRoot(driveId, folderId)) + " in parents and trashed = false";
		String orderBy = (order == Order.MODIFIED_DESC) ? "folder,modifiedTime desc" : "folder,name";
		return list(token, driveId, q, orderBy, cursor, pageSize);
	}

	private CloudPage list(String token, String driveId, String q, String orderBy, String cursor, int pageSize)
			throws IOException {
		StringBuilder url = new StringBuilder(fApi).append("/files?q=").append(encode(q))
				.append("&fields=").append(encode("nextPageToken,files(" + FIELDS + ")"))
				.append("&pageSize=").append(pageSize(pageSize, MAX_PAGE)).append(corpora(driveId));
		if (orderBy != null) {
			url.append("&orderBy=").append(encode(orderBy));
		}
		if (cursor != null && !cursor.isBlank()) {
			url.append("&pageToken=").append(encode(cursor));
		}
		JsonObject page = json(request(url.toString(), token).GET().build());
		List<CloudEntry> entries = new ArrayList<>();
		JsonArray files = array(page, "files");
		if (files != null) {
			for (JsonElement e : files) {
				entries.add(entry(e.getAsJsonObject(), driveId));
			}
		}
		return new CloudPage(entries, string(page, "nextPageToken"));
	}

	@Override
	public CloudEntry get(String token, String driveId, String id) throws IOException {
		// A null id is the drive's root, as it is for folderId everywhere else.
		String target = (id == null || id.isBlank()) ? rootOf(driveId) : id;
		try {
			return entry(json(request(fApi + "/files/" + segment(target) + "?fields=" + encode(FIELDS) + ALL_DRIVES, token)
					.GET().build()), driveId);
		} catch (NotFoundException ex) {
			return null;
		}
	}

	@Override
	public CloudEntry child(String token, String driveId, String folderId, String name) throws IOException {
		String q = "name = " + quote(name) + " and " + quote(folderOrRoot(driveId, folderId))
				+ " in parents and trashed = false";
		List<CloudEntry> found = list(token, driveId, q, null, null, 1).getEntries();
		return found.isEmpty() ? null : found.get(0);
	}

	@Override
	public List<CloudEntry> ancestors(String token, String driveId, String id, EntryCache cache) throws IOException {
		// The root of My Drive has an id of its own; "root" is an alias the API
		// takes, never what 'parents' reports.
		String rootId;
		if (isMine(driveId)) {
			CloudEntry root = cache.get(driveId, "root", () -> get(token, driveId, "root"));
			rootId = (root != null) ? root.getId() : null;
		} else {
			rootId = driveId;
		}
		CloudEntry entry = cache.get(driveId, id, () -> get(token, driveId, id));
		if (entry == null) {
			throw new NotFoundException("the file was not found");
		}
		List<CloudEntry> ancestors = new ArrayList<>();
		String parentId = entry.getParentId();
		for (int depth = 0; parentId != null && !parentId.equals(rootId) && depth < 64; depth++) {
			String wanted = parentId;
			CloudEntry parent = cache.get(driveId, wanted, () -> get(token, driveId, wanted));
			if (parent == null) {
				break;
			}
			ancestors.add(0, parent);
			parentId = parent.getParentId();
		}
		return ancestors;
	}

	@Override
	public Download download(String token, CloudEntry entry, String exportMimeType, String range)
			throws IOException {
		if (entry.isFolder()) {
			throw new IllegalArgumentException(entry.getName() + " is a folder.");
		}
		HttpRequest.Builder builder;
		if (entry.isNative()) {
			String type = (exportMimeType == null || exportMimeType.isBlank())
					? defaultExportType(entry.getNativeType()) : exportMimeType;
			builder = request(fApi + "/files/" + segment(entry.getId()) + "/export?mimeType=" + encode(type), token);
		} else {
			builder = request(fApi + "/files/" + segment(entry.getId()) + "?alt=media" + ALL_DRIVES, token);
			if (range != null && !range.isBlank()) {
				builder.header("Range", range);
			}
		}
		HttpResponse<InputStream> response = stream(builder.header("Accept", "*/*").GET().build());
		return download(response, entry.getMimeType());
	}

	private static Download download(HttpResponse<InputStream> response, String fallbackType) {
		String type = response.headers().firstValue("Content-Type").orElse(fallbackType);
		long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
		String contentRange = (response.statusCode() == 206)
				? response.headers().firstValue("Content-Range").orElse(null) : null;
		return new Download(response.body(), type, length, contentRange);
	}

	@Override
	public Download thumbnail(String token, CloudEntry entry, String size) throws IOException {
		if (entry.isFolder() || !entry.hasThumbnail()) {
			return null;
		}
		JsonObject file;
		try {
			file = json(request(fApi + "/files/" + segment(entry.getId()) + "?fields=thumbnailLink" + ALL_DRIVES, token)
					.GET().build());
		} catch (NotFoundException ex) {
			return null;
		}
		String link = string(file, "thumbnailLink");
		if (link == null || link.isBlank()) {
			return null;
		}
		// The link ends in the size it was made for (=s220); ask for the one wanted.
		int px = THUMBNAIL_SIZES.getOrDefault((size == null) ? "medium" : size.toLowerCase(), 400);
		link = link.replaceAll("=s\\d+(-c)?$", "=s" + px);
		try {
			HttpResponse<InputStream> response = stream(request(link, token).header("Accept", "*/*").GET().build());
			return download(response, "image/png");
		} catch (NotFoundException ex) {
			return null;
		}
	}

	@Override
	public CloudPage search(String token, String driveId, String folderId, CloudQuery query, String cursor,
			int pageSize) throws IOException {
		List<String> terms = new ArrayList<>();
		terms.add("trashed = false");
		if (!query.getText().isEmpty()) {
			terms.add("fullText contains " + quote(query.getText()));
		}
		if (query.hasKinds()) {
			List<String> kinds = new ArrayList<>();
			for (CloudQuery.Kind kind : query.getKinds()) {
				switch (kind) {
				case IMAGE:
					kinds.add("mimeType contains 'image/'");
					break;
				case VIDEO:
					kinds.add("mimeType contains 'video/'");
					break;
				case AUDIO:
					kinds.add("mimeType contains 'audio/'");
					break;
				case FOLDER:
					kinds.add("mimeType = '" + FOLDER_TYPE + "'");
					break;
				case DOCUMENT:
				default:
					kinds.add("(not mimeType contains 'image/' and not mimeType contains 'video/'"
							+ " and not mimeType contains 'audio/' and mimeType != '" + FOLDER_TYPE + "')");
					break;
				}
			}
			terms.add("(" + String.join(" or ", kinds) + ")");
		}
		if (query.getModifiedAfter() != null) {
			terms.add("modifiedTime > '" + query.getModifiedAfter() + "'");
		}
		if (query.getModifiedBefore() != null) {
			terms.add("modifiedTime < '" + query.getModifiedBefore() + "'");
		}
		if (query.isOwnedByMe()) {
			terms.add("'me' in owners");
		}
		// A full-text search is ranked by the API; an order is asked for only
		// when there are no words to rank by.
		String orderBy = query.getText().isEmpty() ? "folder,modifiedTime desc" : null;
		return list(token, driveId, String.join(" and ", terms), orderBy, cursor, pageSize);
	}

	@Override
	public String previewUrl(String token, String driveId, String id) {
		return null;
	}

	// ---- writing -----------------------------------------------------------------

	private String files(String path) {
		return fApi + "/files" + path + (path.contains("?") ? "&" : "?") + "fields=" + encode(FIELDS) + ALL_DRIVES;
	}

	@Override
	public CloudEntry createFolder(String token, String driveId, String parentId, String name) throws IOException {
		JsonObject metadata = new JsonObject();
		metadata.addProperty("name", name);
		metadata.addProperty("mimeType", FOLDER_TYPE);
		JsonArray parents = new JsonArray();
		parents.add(folderOrRoot(driveId, parentId));
		metadata.add("parents", parents);
		return entry(json(request(files(""), token).header("Content-Type", "application/json; charset=UTF-8")
				.POST(HttpRequest.BodyPublishers.ofString(metadata.toString(), StandardCharsets.UTF_8)).build()),
				driveId);
	}

	@Override
	public CloudEntry rename(String token, String driveId, String id, String name) throws IOException {
		JsonObject metadata = new JsonObject();
		metadata.addProperty("name", name);
		return patch(token, driveId, files("/" + segment(id)), metadata);
	}

	@Override
	public void delete(String token, String driveId, String id) throws IOException {
		JsonObject metadata = new JsonObject();
		metadata.addProperty("trashed", true);
		patch(token, driveId, files("/" + segment(id)), metadata);
	}

	@Override
	public CloudEntry move(String token, String driveId, String id, String toFolderId) throws IOException {
		CloudEntry current = get(token, driveId, id);
		if (current == null) {
			throw new NotFoundException("the file was not found");
		}
		String url = files("/" + segment(id)) + "&addParents=" + encode(folderOrRoot(driveId, toFolderId));
		if (current.getParentId() != null) {
			url += "&removeParents=" + encode(current.getParentId());
		}
		return patch(token, driveId, url, new JsonObject());
	}

	@Override
	public CloudEntry copy(String token, String driveId, String id, String toDriveId, String toFolderId, String name)
			throws IOException {
		JsonObject metadata = new JsonObject();
		if (name != null && !name.isBlank()) {
			metadata.addProperty("name", name);
		}
		JsonArray parents = new JsonArray();
		parents.add(folderOrRoot(toDriveId, toFolderId));
		metadata.add("parents", parents);
		return entry(json(request(files("/" + segment(id) + "/copy"), token)
				.header("Content-Type", "application/json; charset=UTF-8")
				.POST(HttpRequest.BodyPublishers.ofString(metadata.toString(), StandardCharsets.UTF_8)).build()),
				toDriveId);
	}

	private CloudEntry patch(String token, String driveId, String url, JsonObject metadata) throws IOException {
		return entry(json(request(url, token).header("Content-Type", "application/json; charset=UTF-8")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(metadata.toString(), StandardCharsets.UTF_8))
				.build()), driveId);
	}

	@Override
	public CloudEntry upload(String token, String driveId, String folderId, String name, String mimeType,
			InputStream content, long length, boolean convert) throws IOException {
		JsonObject metadata = new JsonObject();
		metadata.addProperty("name", name);
		JsonArray parents = new JsonArray();
		parents.add(folderOrRoot(driveId, folderId));
		metadata.add("parents", parents);
		if (convert) {
			String target = conversionOf(mimeType);
			if (target == null) {
				throw new IllegalArgumentException("Google Drive cannot turn " + mimeType + " into a Google document.");
			}
			metadata.addProperty("mimeType", target);
		}
		String contentType = (mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType;
		String fields = "&fields=" + encode(FIELDS) + ALL_DRIVES;

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
			return entry(jsonOnce(request), driveId);
		}

		// A resumable session: the metadata first, then the content in one PUT.
		HttpRequest open = request(fUpload + "/files?uploadType=resumable" + fields, token)
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("X-Upload-Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofString(metadata.toString(), StandardCharsets.UTF_8)).build();
		return entry(resumable(token, open, contentType, content, length), driveId);
	}

	private JsonObject resumable(String token, HttpRequest open, String contentType, InputStream content, long length)
			throws IOException {
		HttpResponse<String> opened = exchange(open, true);
		if (opened.statusCode() / 100 != 2) {
			throw refused(opened.statusCode(), opened.body(), opened.headers());
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
		return jsonOnce(put);
	}

	@Override
	public CloudEntry update(String token, String driveId, String id, String mimeType, InputStream content,
			long length, String ifMatch) throws IOException {
		// Drive ignores If-Match, so the version is compared before writing. A
		// change that lands between the two requests is not caught; nothing
		// better is on offer without Drive's own support.
		CloudEntry current = get(token, driveId, id);
		if (current == null) {
			throw new NotFoundException("the file was not found");
		}
		if (ifMatch != null && !ifMatch.isBlank() && current.getEtag() != null && !ifMatch.equals(current.getEtag())) {
			throw new ConflictException("the file was changed elsewhere");
		}
		String contentType = (mimeType == null || mimeType.isBlank())
				? (current.getMimeType() != null ? current.getMimeType() : "application/octet-stream") : mimeType;
		String fields = "&fields=" + encode(FIELDS) + ALL_DRIVES;
		if (length >= 0 && length <= MULTIPART_LIMIT) {
			HttpRequest request = request(fUpload + "/files/" + segment(id) + "?uploadType=media" + fields, token)
					.header("Content-Type", contentType)
					.method("PATCH", HttpRequest.BodyPublishers.fromPublisher(
							HttpRequest.BodyPublishers.ofInputStream(() -> content), length))
					.build();
			return entry(jsonOnce(request), driveId);
		}
		HttpRequest open = request(fUpload + "/files/" + segment(id) + "?uploadType=resumable" + fields, token)
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("X-Upload-Content-Type", contentType)
				.method("PATCH", HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build();
		return entry(resumable(token, open, contentType, content, length), driveId);
	}

	// ---- reading an answer -------------------------------------------------------

	private CloudEntry entry(JsonObject file, String contextDriveId) {
		String mimeType = string(file, "mimeType");
		boolean folder = FOLDER_TYPE.equals(mimeType);
		String nativeType = (!folder && mimeType != null && mimeType.startsWith(NATIVE_PREFIX))
				? mimeType.substring(NATIVE_PREFIX.length()) : null;
		String driveId = string(file, "driveId");
		if (driveId == null || driveId.isBlank()) {
			driveId = isMine(contextDriveId) ? CloudEntry.MY_DRIVE : contextDriveId;
		}
		String parentId = null;
		JsonArray parents = array(file, "parents");
		if (parents != null && parents.size() > 0) {
			parentId = parents.get(0).getAsString();
		}
		String owner = null;
		JsonArray owners = array(file, "owners");
		if (owners != null && owners.size() > 0 && owners.get(0).isJsonObject()) {
			owner = string(owners.get(0).getAsJsonObject(), "displayName");
		}
		String version = string(file, "version");
		return CloudEntry.builder(ID, driveId)
				.id(string(file, "id"))
				.name(string(file, "name"))
				.mimeType(mimeType)
				.size(folder ? -1 : longOf(file, "size"))
				.folder(folder)
				.webUrl(string(file, "webViewLink"))
				.modified(instant(file, "modifiedTime"))
				.created(instant(file, "createdTime"))
				.nativeType(nativeType)
				.parentId(parentId)
				.etag(version)
				.owner(owner)
				.shared(bool(file, "shared"))
				.hasThumbnail(!folder && bool(file, "hasThumbnail"))
				.build();
	}

}
