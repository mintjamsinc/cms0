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
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.mintjams.rt.cms.internal.CmsService;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * OneDrive and SharePoint document libraries through Microsoft Graph v1.0:
 * the user's OneDrive ({@value CloudEntry#MY_DRIVE}, {@code /me/drive}) and
 * the libraries of the sites the user follows ({@code /drives/{id}}).
 */
public final class OneDrive extends CloudProvider {

	public static final String ID = "microsoft";

	public static final String DEFAULT_ENDPOINT = "https://graph.microsoft.com/v1.0";

	private static final String SELECT = "id,name,size,file,folder,package,webUrl,lastModifiedDateTime,createdDateTime,"
			+ "parentReference,cTag,createdBy,shared";
	private static final int MAX_PAGE = 999;
	/** Content this size and below goes up in one PUT. */
	private static final long SIMPLE_UPLOAD_LIMIT = 4L * 1024 * 1024;
	/** The size of a piece of an upload session: a multiple of 320 KiB, as Graph requires. */
	private static final int CHUNK_SIZE = 10 * 1024 * 1024;
	/** How long a copy is waited for. */
	private static final long COPY_TIMEOUT_MILLIS = 5L * 60 * 1000;
	/** How many pages a search reads to fill one page of results, at most. */
	private static final int SEARCH_FILL_PAGES = 10;
	/** The library names Graph gives a site's default library, in the languages this product speaks. */
	private static final List<String> DEFAULT_LIBRARY_NAMES = List.of("Documents", "Shared Documents", "ドキュメント");

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

	@Override
	public SearchScope getSearchScope() {
		return SearchScope.FOLDER;
	}

	@Override
	public List<Facet> getFacets() {
		return List.of(Facet.KIND, Facet.MODIFIED);
	}

	@Override
	public boolean canPreview() {
		return true;
	}

	@Override
	public boolean canCopyFolder() {
		return true;
	}

	private static boolean isMine(String driveId) {
		return driveId == null || driveId.isBlank() || CloudEntry.MY_DRIVE.equals(driveId);
	}

	private String drive(String driveId) {
		return isMine(driveId) ? fApi + "/me/drive" : fApi + "/drives/" + segment(driveId);
	}

	private String item(String driveId, String id) {
		return (id == null || id.isBlank()) ? drive(driveId) + "/root" : drive(driveId) + "/items/" + segment(id);
	}

	private static String select() {
		return "$select=" + encode(SELECT);
	}

	// ---- drives ------------------------------------------------------------------

	@Override
	public CloudDrive myDrive(String token) throws IOException {
		String webUrl = null;
		try {
			webUrl = string(json(request(fApi + "/me/drive?$select=" + encode("id,webUrl"), token).GET().build()),
					"webUrl");
		} catch (CloudException ex) {
			// The drive is still there to browse; only the link is missing.
		}
		return new CloudDrive(ID, CloudEntry.MY_DRIVE, "OneDrive", CloudDrive.Kind.MINE, webUrl);
	}

	@Override
	public List<CloudDrive> sharedDrives(String token) throws IOException {
		List<CloudDrive> drives = new ArrayList<>();
		String url = fApi + "/me/followedSites?$select=" + encode("id,displayName,webUrl");
		while (url != null) {
			JsonObject page = json(request(url, token).GET().build());
			JsonArray sites = array(page, "value");
			if (sites != null) {
				for (JsonElement e : sites) {
					JsonObject site = e.getAsJsonObject();
					String siteId = string(site, "id");
					String siteName = string(site, "displayName");
					List<JsonObject> libraries = new ArrayList<>();
					try {
						JsonObject list = json(request(fApi + "/sites/" + segment(siteId) + "/drives?$select="
								+ encode("id,name,webUrl"), token).GET().build());
						JsonArray value = array(list, "value");
						if (value != null) {
							for (JsonElement d : value) {
								libraries.add(d.getAsJsonObject());
							}
						}
					} catch (CloudException refused) {
						// A site the user follows but may not read: left out.
						CmsService.getLogger(OneDrive.class).debug("The libraries of site {} were not listed: {}",
								siteId, refused.getMessage());
						continue;
					}
					for (JsonObject library : libraries) {
						drives.add(new CloudDrive(ID, string(library, "id"),
								libraryName(siteName, string(library, "name"), libraries.size() == 1),
								CloudDrive.Kind.SHARED, string(library, "webUrl")));
					}
				}
			}
			url = string(page, "@odata.nextLink");
		}
		drives.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()));
		return drives;
	}

	/** "Site › Library", or the site's name alone for its one default library. */
	private static String libraryName(String siteName, String libraryName, boolean onlyOne) {
		if (siteName == null || siteName.isBlank()) {
			return libraryName;
		}
		if (libraryName == null || libraryName.isBlank() || (onlyOne && DEFAULT_LIBRARY_NAMES.contains(libraryName))) {
			return siteName;
		}
		return siteName + " › " + libraryName;
	}

	@Override
	public CloudDrive drive(String token, String driveId) throws IOException {
		if (isMine(driveId)) {
			return myDrive(token);
		}
		JsonObject drive;
		try {
			drive = json(request(fApi + "/drives/" + segment(driveId) + "?$select=" + encode("id,name,webUrl,sharePointIds"),
					token).GET().build());
		} catch (NotFoundException ex) {
			return null;
		}
		String siteName = null;
		JsonObject ids = object(drive, "sharePointIds");
		String siteId = (ids != null) ? string(ids, "siteId") : null;
		if (siteId != null) {
			try {
				siteName = string(json(request(fApi + "/sites/" + segment(siteId) + "?$select=displayName", token)
						.GET().build()), "displayName");
			} catch (CloudException ex) {
				// The library's own name has to do.
			}
		}
		return new CloudDrive(ID, string(drive, "id"), libraryName(siteName, string(drive, "name"), false),
				CloudDrive.Kind.SHARED, string(drive, "webUrl"));
	}

	// ---- reading -----------------------------------------------------------------

	@Override
	public CloudPage children(String token, String driveId, String folderId, String cursor, int pageSize, Order order)
			throws IOException {
		String url = (cursor != null && !cursor.isBlank()) ? cursor
				: item(driveId, folderId) + "/children?" + select() + "&$top=" + pageSize(pageSize, MAX_PAGE)
						+ "&$orderby=" + encode(order == Order.MODIFIED_DESC ? "lastModifiedDateTime desc" : "name");
		return page(json(request(url, token).GET().build()), driveId);
	}

	private CloudPage page(JsonObject page, String driveId) {
		List<CloudEntry> entries = new ArrayList<>();
		JsonArray value = array(page, "value");
		if (value != null) {
			for (JsonElement e : value) {
				entries.add(entry(e.getAsJsonObject(), driveId));
			}
		}
		return new CloudPage(entries, string(page, "@odata.nextLink"));
	}

	@Override
	public CloudEntry get(String token, String driveId, String id) throws IOException {
		try {
			return entry(json(request(item(driveId, id) + "?" + select(), token).GET().build()), driveId);
		} catch (NotFoundException ex) {
			return null;
		}
	}

	@Override
	public CloudEntry child(String token, String driveId, String folderId, String name) throws IOException {
		try {
			return entry(json(request(item(driveId, folderId) + ":/" + segment(name) + ":?" + select(), token).GET()
					.build()), driveId);
		} catch (NotFoundException ex) {
			return null;
		}
	}

	@Override
	public List<CloudEntry> ancestors(String token, String driveId, String id, EntryCache cache) throws IOException {
		CloudEntry entry = cache.get(driveId, id, () -> get(token, driveId, id));
		if (entry == null) {
			throw new NotFoundException("the file was not found");
		}
		// parentReference.path names every folder above; each one's id is read
		// by path, once, and kept in the cache.
		List<CloudEntry> ancestors = new ArrayList<>();
		String path = entry.getPath();
		if (path == null) {
			return ancestors;
		}
		int slash = path.lastIndexOf('/');
		String parentPath = (slash > 0) ? path.substring(0, slash) : "";
		StringBuilder prefix = new StringBuilder();
		for (String name : parentPath.split("/")) {
			if (name.isEmpty()) {
				continue;
			}
			prefix.append('/').append(name);
			String byPath = prefix.toString();
			CloudEntry folder = cache.get(driveId, "path:" + byPath, () -> {
				try {
					return entry(json(request(drive(driveId) + "/root:" + encodePath(byPath) + ":?" + select(), token)
							.GET().build()), driveId);
				} catch (NotFoundException ex) {
					return null;
				}
			});
			if (folder == null) {
				break;
			}
			ancestors.add(folder);
		}
		return ancestors;
	}

	private static String encodePath(String path) {
		StringBuilder sb = new StringBuilder();
		for (String name : path.split("/")) {
			if (!name.isEmpty()) {
				sb.append('/').append(segment(name));
			}
		}
		return sb.toString();
	}

	@Override
	public Download download(String token, CloudEntry entry, String exportMimeType, String range)
			throws IOException {
		if (entry.isFolder()) {
			throw new IllegalArgumentException(entry.getName() + " is a folder.");
		}
		HttpResponse<InputStream> response = redirected(
				request(item(entry.getDriveId(), entry.getId()) + "/content", token), range);
		return download(response, entry.getMimeType());
	}

	/**
	 * Sends a request whose answer may be a redirect to a pre-authorized URL
	 * (OneDrive's content and thumbnails): the redirect is followed without
	 * the bearer token, which the URL carries in its own way.
	 */
	private static HttpResponse<InputStream> redirected(HttpRequest.Builder builder, String range) throws IOException {
		builder.header("Accept", "*/*");
		if (range != null && !range.isBlank()) {
			builder.header("Range", range);
		}
		HttpResponse<InputStream> response = stream(HTTP_DIRECT, builder.GET().build());
		if (response.statusCode() / 100 == 3) {
			response.body().close();
			Optional<String> location = response.headers().firstValue("Location");
			if (location.isEmpty()) {
				throw new IOException("OneDrive redirected the download nowhere.");
			}
			HttpRequest.Builder next = plain(location.get()).header("Accept", "*/*");
			if (range != null && !range.isBlank()) {
				next.header("Range", range);
			}
			response = stream(next.GET().build());
		}
		return response;
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
		String name = (size == null) ? "medium" : size.toLowerCase();
		if (!name.equals("small") && !name.equals("medium") && !name.equals("large")) {
			name = "medium";
		}
		try {
			HttpResponse<InputStream> response = redirected(
					request(item(entry.getDriveId(), entry.getId()) + "/thumbnails/0/" + name + "/content", token), null);
			return download(response, "image/jpeg");
		} catch (NotFoundException ex) {
			return null;
		}
	}

	@Override
	public CloudPage search(String token, String driveId, String folderId, CloudQuery query, String cursor,
			int pageSize) throws IOException {
		// Graph's search takes words only ($filter cannot be combined with it);
		// kinds and dates are applied here, reading on until the page is full.
		int wanted = pageSize(pageSize, MAX_PAGE);
		String url = (cursor != null && !cursor.isBlank()) ? cursor
				: item(driveId, folderId) + "/search(q=" + encode("'" + query.getText().replace("'", "''") + "'") + ")?"
						+ select() + "&$top=" + wanted;
		List<CloudEntry> entries = new ArrayList<>();
		String next = url;
		for (int pages = 0; next != null && entries.size() < wanted && pages < SEARCH_FILL_PAGES; pages++) {
			CloudPage page = page(json(request(next, token).GET().build()), driveId);
			for (CloudEntry entry : page.getEntries()) {
				if (query.matches(entry)) {
					entries.add(entry);
				}
			}
			next = page.getNextCursor();
		}
		return new CloudPage(entries, next);
	}

	@Override
	public String previewUrl(String token, String driveId, String id) throws IOException {
		JsonObject preview = json(request(item(driveId, id) + "/preview", token)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build());
		return string(preview, "getUrl");
	}

	// ---- writing -----------------------------------------------------------------

	@Override
	public CloudEntry createFolder(String token, String driveId, String parentId, String name) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		body.add("folder", new JsonObject());
		body.addProperty("@microsoft.graph.conflictBehavior", "rename");
		return entry(json(request(item(driveId, parentId) + "/children", token)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build()), driveId);
	}

	@Override
	public CloudEntry rename(String token, String driveId, String id, String name) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		return patch(token, driveId, id, body);
	}

	private CloudEntry patch(String token, String driveId, String id, JsonObject body) throws IOException {
		return entry(json(request(item(driveId, id) + "?" + select(), token).header("Content-Type", "application/json")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build()),
				driveId);
	}

	@Override
	public void delete(String token, String driveId, String id) throws IOException {
		text(request(item(driveId, id), token).DELETE().build(), true);
	}

	@Override
	public CloudEntry move(String token, String driveId, String id, String toFolderId) throws IOException {
		JsonObject parent = new JsonObject();
		parent.addProperty("id", folderIdOrRoot(token, driveId, toFolderId));
		JsonObject body = new JsonObject();
		body.add("parentReference", parent);
		return patch(token, driveId, id, body);
	}

	/** The item id of a folder, or of the drive's root when {@code folderId} is null. */
	private String folderIdOrRoot(String token, String driveId, String folderId) throws IOException {
		if (folderId != null && !folderId.isBlank()) {
			return folderId;
		}
		CloudEntry root = get(token, driveId, null);
		if (root == null) {
			throw new NotFoundException("the drive was not found");
		}
		return root.getId();
	}

	/** The provider's id of a drive: {@code /me/drive} resolved for the user's own. */
	private String realDriveId(String token, String driveId) throws IOException {
		if (!isMine(driveId)) {
			return driveId;
		}
		return string(json(request(fApi + "/me/drive?$select=id", token).GET().build()), "id");
	}

	@Override
	public CloudEntry copy(String token, String driveId, String id, String toDriveId, String toFolderId, String name)
			throws IOException {
		JsonObject parent = new JsonObject();
		parent.addProperty("driveId", realDriveId(token, toDriveId));
		parent.addProperty("id", folderIdOrRoot(token, toDriveId, toFolderId));
		JsonObject body = new JsonObject();
		body.add("parentReference", parent);
		if (name != null && !name.isBlank()) {
			body.addProperty("name", name);
		}
		body.addProperty("@microsoft.graph.conflictBehavior", "rename");
		HttpResponse<String> response = exchange(request(item(driveId, id) + "/copy", token)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(), true);
		if (response.statusCode() / 100 != 2) {
			throw refused(response.statusCode(), response.body(), response.headers());
		}
		if (response.statusCode() == 202) {
			// The copy runs on the provider's side; its monitor URL is
			// pre-authenticated and says when it is done.
			Optional<String> monitor = response.headers().firstValue("Location");
			if (monitor.isEmpty()) {
				throw new IOException("OneDrive did not say where to watch the copy.");
			}
			String resourceId = waitForCopy(monitor.get());
			CloudEntry copied = get(token, toDriveId, resourceId);
			if (copied == null) {
				throw new IOException("OneDrive reported the copy done, but the copy was not found.");
			}
			return copied;
		}
		String text = response.body();
		if (text != null && !text.isBlank()) {
			JsonObject item = JsonParser.parseString(text).getAsJsonObject();
			if (item.has("id")) {
				return entry(item, toDriveId);
			}
		}
		throw new IOException("OneDrive did not confirm the copy.");
	}

	private static String waitForCopy(String monitor) throws IOException {
		long deadline = System.currentTimeMillis() + COPY_TIMEOUT_MILLIS;
		while (System.currentTimeMillis() < deadline) {
			HttpResponse<String> response = send(plain(monitor).header("Accept", "application/json").GET().build());
			if (response.statusCode() / 100 == 3) {
				// Done: the monitor points at the new item, which its id names.
				String location = response.headers().firstValue("Location").orElse("");
				int slash = location.lastIndexOf('/');
				if (slash >= 0 && slash < location.length() - 1) {
					return location.substring(slash + 1);
				}
			}
			JsonObject status = new JsonObject();
			try {
				if (response.body() != null && !response.body().isBlank()) {
					status = JsonParser.parseString(response.body()).getAsJsonObject();
				}
			} catch (Throwable ignore) {
				// Not JSON: looked at again.
			}
			String state = string(status, "status");
			if ("completed".equalsIgnoreCase(state)) {
				String resourceId = string(status, "resourceId");
				if (resourceId != null) {
					return resourceId;
				}
			}
			if ("failed".equalsIgnoreCase(state) || "cancelled".equalsIgnoreCase(state)
					|| "deleteFailed".equalsIgnoreCase(state)) {
				JsonObject error = object(status, "error");
				throw new CloudException(502, "the copy failed"
						+ ((error != null && error.has("message")) ? ": " + error.get("message").getAsString() : ""));
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for the copy.");
			}
		}
		throw new IOException("OneDrive did not finish the copy in time.");
	}

	@Override
	public CloudEntry upload(String token, String driveId, String folderId, String name, String mimeType,
			InputStream content, long length, boolean convert) throws IOException {
		String contentType = (mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType;
		String target = item(driveId, folderId) + ":/" + segment(name) + ":";

		if (length >= 0 && length <= SIMPLE_UPLOAD_LIMIT) {
			HttpRequest request = request(target + "/content?@microsoft.graph.conflictBehavior=rename", token)
					.header("Content-Type", contentType)
					.PUT(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> content),
							length))
					.build();
			return entry(jsonOnce(request), driveId);
		}
		if (length < 0) {
			throw new IllegalArgumentException("The size of the content has to be known to upload it to OneDrive.");
		}

		JsonObject session = new JsonObject();
		JsonObject item = new JsonObject();
		item.addProperty("@microsoft.graph.conflictBehavior", "rename");
		item.addProperty("name", name);
		session.add("item", item);
		return entry(uploadSession(token, target + "/createUploadSession", session, content, length), driveId);
	}

	private JsonObject uploadSession(String token, String url, JsonObject session, InputStream content, long length)
			throws IOException {
		JsonObject opened = json(request(url, token).header("Content-Type", "application/json")
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
				throw refused(response.statusCode(), response.body(), response.headers());
			}
			offset += read;
			if (response.statusCode() == 200 || response.statusCode() == 201) {
				result = JsonParser.parseString(response.body()).getAsJsonObject();
			}
		}
		if (result == null || !result.has("id")) {
			throw new IOException("OneDrive did not confirm the upload.");
		}
		return result;
	}

	@Override
	public CloudEntry update(String token, String driveId, String id, String mimeType, InputStream content,
			long length, String ifMatch) throws IOException {
		String contentType = (mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType;
		if (length >= 0 && length <= SIMPLE_UPLOAD_LIMIT) {
			HttpRequest.Builder builder = request(item(driveId, id) + "/content", token).header("Content-Type", contentType);
			if (ifMatch != null && !ifMatch.isBlank()) {
				builder.header("If-Match", ifMatch);
			}
			return entry(jsonOnce(builder.PUT(HttpRequest.BodyPublishers.fromPublisher(
					HttpRequest.BodyPublishers.ofInputStream(() -> content), length)).build()), driveId);
		}
		if (length < 0) {
			throw new IllegalArgumentException("The size of the content has to be known to upload it to OneDrive.");
		}
		// An upload session takes no If-Match: the tag is compared first.
		if (ifMatch != null && !ifMatch.isBlank()) {
			CloudEntry current = get(token, driveId, id);
			if (current == null) {
				throw new NotFoundException("the file was not found");
			}
			if (current.getEtag() != null && !ifMatch.equals(current.getEtag())) {
				throw new ConflictException("the file was changed elsewhere");
			}
		}
		JsonObject session = new JsonObject();
		JsonObject item = new JsonObject();
		item.addProperty("@microsoft.graph.conflictBehavior", "replace");
		session.add("item", item);
		return entry(uploadSession(token, item(driveId, id) + "/createUploadSession", session, content, length), driveId);
	}

	// ---- reading an answer -------------------------------------------------------

	private CloudEntry entry(JsonObject item, String contextDriveId) {
		boolean folder = (item.has("folder") && !item.get("folder").isJsonNull())
				|| (item.has("package") && !item.get("package").isJsonNull());
		String mimeType = null;
		JsonObject file = object(item, "file");
		if (file != null) {
			mimeType = string(file, "mimeType");
		}
		String parentId = null;
		String path = null;
		JsonObject parent = object(item, "parentReference");
		if (parent != null) {
			parentId = string(parent, "id");
			String parentPath = string(parent, "path");
			if (parentPath != null) {
				int colon = parentPath.indexOf("root:");
				String below = (colon >= 0) ? parentPath.substring(colon + "root:".length()) : "";
				path = decodePath(below) + "/" + string(item, "name");
			}
		}
		String owner = null;
		JsonObject createdBy = object(item, "createdBy");
		if (createdBy != null) {
			JsonObject user = object(createdBy, "user");
			if (user != null) {
				owner = string(user, "displayName");
			}
		}
		String driveId = isMine(contextDriveId) ? CloudEntry.MY_DRIVE : contextDriveId;
		return CloudEntry.builder(ID, driveId)
				.id(string(item, "id"))
				.name(string(item, "name"))
				.mimeType(mimeType)
				.size(folder ? -1 : longOf(item, "size"))
				.folder(folder)
				.webUrl(string(item, "webUrl"))
				.modified(instant(item, "lastModifiedDateTime"))
				.created(instant(item, "createdDateTime"))
				.parentId(parentId)
				.etag(string(item, "cTag"))
				.owner(owner)
				.shared(item.has("shared") && !item.get("shared").isJsonNull())
				.hasThumbnail(!folder && hasThumbnail(mimeType))
				.path(path)
				.build();
	}

	/** The types Graph renders thumbnails for, near enough; a miss costs the client one 404. */
	private static boolean hasThumbnail(String mimeType) {
		if (mimeType == null) {
			return false;
		}
		return mimeType.startsWith("image/") || mimeType.startsWith("video/") || mimeType.equals("application/pdf")
				|| mimeType.contains("officedocument") || mimeType.contains("msword") || mimeType.contains("ms-excel")
				|| mimeType.contains("ms-powerpoint");
	}

	/** Graph percent-encodes the path; names are wanted as they are. */
	private static String decodePath(String path) {
		if (path == null || path.indexOf('%') < 0) {
			return (path == null) ? "" : path;
		}
		try {
			return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (Throwable ex) {
			return path;
		}
	}

}
