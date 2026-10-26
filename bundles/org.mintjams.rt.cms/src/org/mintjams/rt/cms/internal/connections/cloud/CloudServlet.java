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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.jcr.Credentials;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.connections.ConnectionProviders;
import org.mintjams.rt.cms.internal.connections.LinkedAccounts;
import org.mintjams.rt.cms.internal.web.Webs;
import org.mintjams.script.ConsentRequiredException;
import org.mintjams.tools.io.IOs;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * The bytes of the files on a user's drives, at a same-origin URL, so that an
 * {@code <img>}, a {@code <video>}, a preview pane or an app's
 * {@code fetch(downloadURL)} reaches a cloud file the way it reaches a
 * repository file through {@code download.cgi}. The user is the signed-in
 * one; the token is theirs ({@link CloudFiles#token}).
 *
 * <pre>
 * GET  {@value CloudEntry#CONTENT_PATH}/&lt;provider&gt;/&lt;driveId&gt;/&lt;id&gt;[/&lt;name&gt;]
 *        the content (Range honoured; a Google native document exported as
 *        ?export=&lt;mime&gt; or the usual type of its kind); ?attachment to download;
 *        ?thumbnail=small|medium|large for a thumbnail (404 when there is none)
 * PUT  {@value CloudEntry#CONTENT_PATH}/&lt;provider&gt;/&lt;driveId&gt;/&lt;id&gt;
 *        replaces the content; If-Match (the entry's etag) is required, 412 when stale
 * POST {@value CloudEntry#CONTENT_PATH}/&lt;provider&gt;/&lt;driveId&gt;/&lt;folderId&gt;/children
 *        multipart: one or more `file` parts, `conflict` = FAIL | REPLACE | RENAME (default)
 * </pre>
 *
 * <p>The name after the id is for the URL's looks and the download's file
 * name; the id is what is served. {@code ETag} is the provider's change tag,
 * {@code Cache-Control: private, no-cache}: a browser revalidates, and an
 * unchanged file costs one metadata request (304).
 */
@Component(service = Servlet.class, property = {
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=" + CloudEntry.CONTENT_PATH + "/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=("
				+ HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=org.osgi.service.http)",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_ASYNC_SUPPORTED + "=true" })
public class CloudServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final Gson GSON = new Gson();

	/** The parsed path: provider, drive, item and whether `/children` follows. */
	private static final class Target {
		final String provider;
		final String driveId;
		final String id;
		final boolean children;

		Target(String provider, String driveId, String id, boolean children) {
			this.provider = provider;
			this.driveId = driveId;
			this.id = id;
			this.children = children;
		}
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		handle(request, response, "GET");
	}

	@Override
	protected void doHead(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		handle(request, response, "HEAD");
	}

	@Override
	protected void doPut(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		handle(request, response, "PUT");
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		handle(request, response, "POST");
	}

	private void handle(HttpServletRequest request, HttpServletResponse response, String method) throws IOException {
		response.setHeader("X-Content-Type-Options", "nosniff");
		Target target = target(request);
		if (target == null) {
			error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "No such file.");
			return;
		}
		if (ConnectionProviders.get(target.provider) == null) {
			error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Unknown provider.");
			return;
		}
		String userId = userId(request);
		if (userId == null) {
			response.setHeader("Cache-Control", "no-store");
			error(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "Sign in first.");
			return;
		}
		try {
			CloudProvider provider = CloudFiles.requireProvider(target.provider);
			if ("POST".equals(method)) {
				if (!target.children) {
					error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method_not_allowed",
							"POST is for /children.");
					return;
				}
				upload(request, response, userId, provider, target);
			} else if ("PUT".equals(method)) {
				if (target.children) {
					error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method_not_allowed",
							"PUT replaces a file; POST to /children creates one.");
					return;
				}
				update(request, response, userId, provider, target);
			} else {
				if (target.children) {
					error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "No such file.");
					return;
				}
				read(request, response, userId, provider, target, "HEAD".equals(method));
			}
		} catch (ConsentRequiredException ex) {
			response.setHeader("Cache-Control", "no-store");
			JsonObject body = new JsonObject();
			body.addProperty("error", "consent_required");
			body.addProperty("provider", ex.getProvider());
			body.addProperty("bundle", ex.getBundle());
			body.addProperty("reason", ex.getReason());
			json(response, HttpServletResponse.SC_UNAUTHORIZED, body);
		} catch (CloudProvider.RateLimitedException ex) {
			response.setHeader("Cache-Control", "no-store");
			response.setHeader("Retry-After", String.valueOf(Math.max(1, ex.getRetryAfterMillis() / 1000)));
			error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "rate_limited",
					CloudFiles.displayName(target.provider) + " asks to wait: " + ex.getMessage());
		} catch (CloudProvider.NotFoundException ex) {
			error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", ex.getMessage());
		} catch (CloudProvider.ConflictException ex) {
			error(response, HttpServletResponse.SC_PRECONDITION_FAILED, "conflict", ex.getMessage());
		} catch (CloudProvider.CloudException ex) {
			int status = (ex.getStatus() == 401 || ex.getStatus() == 403) ? HttpServletResponse.SC_FORBIDDEN
					: (ex.getStatus() >= 500) ? HttpServletResponse.SC_BAD_GATEWAY : ex.getStatus();
			error(response, status, "refused", CloudFiles.displayName(target.provider) + " refused: " + ex.getMessage());
		} catch (IllegalArgumentException ex) {
			error(response, HttpServletResponse.SC_BAD_REQUEST, "bad_request", ex.getMessage());
		} catch (Throwable ex) {
			if (isClientAbort(ex, response)) {
				CmsService.getLogger(getClass()).debug("Cloud download aborted by client", ex);
				return;
			}
			CmsService.getLogger(getClass()).error("The cloud file request failed", ex);
			if (!response.isCommitted()) {
				error(response, HttpServletResponse.SC_BAD_GATEWAY, "unavailable",
						CloudFiles.displayName(target.provider) + " could not be reached.");
			}
		}
	}

	// ---- GET ---------------------------------------------------------------------------

	private void read(HttpServletRequest request, HttpServletResponse response, String userId, CloudProvider provider,
			Target target, boolean headOnly) throws IOException, RepositoryException {
		CloudEntry entry = CloudFiles.call(userId, target.provider,
				token -> provider.get(token, target.driveId, target.id));
		if (entry == null) {
			error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "No such file.");
			return;
		}
		if (entry.isFolder()) {
			error(response, HttpServletResponse.SC_BAD_REQUEST, "bad_request", entry.getName() + " is a folder.");
			return;
		}
		String thumbnail = request.getParameter("thumbnail");
		if (thumbnail != null) {
			thumbnail(response, userId, provider, entry, thumbnail.isBlank() ? "medium" : thumbnail, headOnly);
			return;
		}

		String etag = (entry.getEtag() != null) ? "\"" + entry.getEtag().replace("\"", "") + "\"" : null;
		response.setHeader("Cache-Control", "private, no-cache");
		if (etag != null) {
			response.setHeader("ETag", etag);
			String ifNoneMatch = request.getHeader("If-None-Match");
			if (ifNoneMatch != null && matches(ifNoneMatch, etag)) {
				response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
				return;
			}
		}
		if (entry.getModified() != null) {
			response.setDateHeader("Last-Modified", entry.getModified().toEpochMilli());
		}

		String export = request.getParameter("export");
		String exportType = (entry.isNative()) ? ((export == null || export.isBlank())
				? GoogleDrive.defaultExportType(entry.getNativeType()) : export) : null;
		String fileName = entry.getName();
		if (entry.isNative()) {
			String extension = CloudProvider.extensionOf(exportType);
			if (!extension.isEmpty() && !fileName.toLowerCase().endsWith(extension)) {
				fileName += extension;
			}
		}
		boolean attachment = request.getParameterMap().containsKey("attachment")
				&& !"false".equalsIgnoreCase(request.getParameter("attachment"));
		response.setHeader("Content-Disposition", disposition(fileName, attachment));

		String range = request.getHeader("Range");
		String ifRange = request.getHeader("If-Range");
		if (range != null && ifRange != null && etag != null && !ifRange.equals(etag)) {
			range = null;
		}
		if (headOnly) {
			response.setContentType(entry.isNative() ? exportType
					: (entry.getMimeType() != null ? entry.getMimeType() : "application/octet-stream"));
			if (entry.getSize() >= 0) {
				response.setContentLengthLong(entry.getSize());
			}
			if (!entry.isNative()) {
				response.setHeader("Accept-Ranges", "bytes");
			}
			return;
		}
		final String rangeToSend = range;
		try (CloudProvider.Download download = CloudFiles.call(userId, target.provider,
				token -> provider.download(token, entry, exportType, rangeToSend))) {
			String type = download.getMimeType();
			response.setContentType((type != null && !type.isBlank()) ? type : "application/octet-stream");
			if (!entry.isNative()) {
				response.setHeader("Accept-Ranges", "bytes");
			}
			if (download.isPartial()) {
				response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
				response.setHeader("Content-Range", download.getContentRange());
			}
			if (download.getLength() >= 0) {
				response.setContentLengthLong(download.getLength());
			}
			IOs.copy(download.getStream(), response.getOutputStream());
		}
	}

	private void thumbnail(HttpServletResponse response, String userId, CloudProvider provider, CloudEntry entry,
			String size, boolean headOnly) throws IOException, RepositoryException {
		response.setHeader("Cache-Control", "private, max-age=600");
		CloudProvider.Download download = CloudFiles.call(userId, entry.getProvider(),
				token -> provider.thumbnail(token, entry, size));
		if (download == null) {
			error(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "The file has no thumbnail.");
			return;
		}
		try (CloudProvider.Download d = download) {
			String type = d.getMimeType();
			response.setContentType((type != null && !type.isBlank()) ? type : "image/jpeg");
			if (d.getLength() >= 0) {
				response.setContentLengthLong(d.getLength());
			}
			if (!headOnly) {
				IOs.copy(d.getStream(), response.getOutputStream());
			}
		}
	}

	private static boolean matches(String ifNoneMatch, String etag) {
		if ("*".equals(ifNoneMatch.trim())) {
			return true;
		}
		for (String candidate : ifNoneMatch.split(",")) {
			String c = candidate.trim();
			if (c.startsWith("W/")) {
				c = c.substring(2);
			}
			if (c.equals(etag)) {
				return true;
			}
		}
		return false;
	}

	// ---- PUT ---------------------------------------------------------------------------

	private void update(HttpServletRequest request, HttpServletResponse response, String userId,
			CloudProvider provider, Target target) throws IOException, RepositoryException {
		response.setHeader("Cache-Control", "no-store");
		String ifMatch = request.getHeader("If-Match");
		if (ifMatch == null || ifMatch.isBlank()) {
			error(response, 428, "precondition_required", "If-Match (the file's etag) is required.");
			return;
		}
		ifMatch = ifMatch.trim();
		if (ifMatch.startsWith("W/")) {
			ifMatch = ifMatch.substring(2);
		}
		if (ifMatch.length() >= 2 && ifMatch.startsWith("\"") && ifMatch.endsWith("\"")) {
			ifMatch = ifMatch.substring(1, ifMatch.length() - 1);
		}
		String mimeType = request.getContentType();
		if (mimeType != null && mimeType.indexOf(';') > 0) {
			mimeType = mimeType.substring(0, mimeType.indexOf(';')).trim();
		}
		long length = request.getContentLengthLong();
		final String etag = ifMatch;
		final String type = mimeType;
		// A fresh token is taken before the body is read: the body cannot be
		// sent twice, so the one retry CloudFiles.call offers would not help here.
		CloudFiles.call(userId, target.provider, token -> provider.get(token, target.driveId, target.id));
		String token = CloudFiles.token(userId, target.provider);
		try (InputStream in = request.getInputStream()) {
			CloudEntry updated = provider.update(token, target.driveId, target.id, type, in, length, etag);
			CmsService.getLogger(getClass()).info("{} file updated: user={} id={}", target.provider, userId,
					target.id);
			json(response, HttpServletResponse.SC_OK, GSON.toJsonTree(updated.toMap()).getAsJsonObject());
		}
	}

	// ---- POST /children ------------------------------------------------------------------

	private void upload(HttpServletRequest request, HttpServletResponse response, String userId,
			CloudProvider provider, Target target) throws IOException, RepositoryException {
		response.setHeader("Cache-Control", "no-store");
		if (!ServletFileUpload.isMultipartContent(request)) {
			error(response, HttpServletResponse.SC_BAD_REQUEST, "bad_request", "A multipart body is expected.");
			return;
		}
		DiskFileItemFactory factory = new DiskFileItemFactory();
		File tempDir = (File) request.getServletContext().getAttribute(ServletContext.TEMPDIR);
		if (tempDir != null) {
			factory.setRepository(tempDir);
		}
		List<FileItem> items;
		try {
			items = new ServletFileUpload(factory).parseRequest(request);
		} catch (Throwable ex) {
			throw new IOException("The upload could not be read: " + ex.getMessage(), ex);
		}
		try {
			CloudFiles.Conflict conflict = CloudFiles.Conflict.RENAME;
			List<FileItem> files = new ArrayList<>();
			for (FileItem item : items) {
				if (item.isFormField()) {
					if ("conflict".equals(item.getFieldName())) {
						try {
							conflict = CloudFiles.Conflict.valueOf(item.getString(StandardCharsets.UTF_8.name()).trim()
									.toUpperCase());
						} catch (Throwable ex) {
							throw new IllegalArgumentException("conflict must be FAIL, REPLACE or RENAME.");
						}
					}
				} else if (item.getName() != null && !item.getName().isBlank()) {
					files.add(item);
				}
			}
			if (files.isEmpty()) {
				throw new IllegalArgumentException("No file was sent.");
			}
			String folderId = (target.id == null || target.id.isBlank() || "root".equals(target.id)) ? null : target.id;
			// A fresh token first: an upload's body cannot be sent twice.
			CloudFiles.call(userId, target.provider, token -> provider.get(token, target.driveId, folderId));
			String token = CloudFiles.token(userId, target.provider);
			List<Map<String, Object>> uploaded = new ArrayList<>();
			for (FileItem file : files) {
				String name = file.getName();
				int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
				if (slash >= 0) {
					name = name.substring(slash + 1);
				}
				String type = file.getContentType();
				if (type == null || type.isBlank()) {
					type = "application/octet-stream";
				}
				try (InputStream in = file.getInputStream()) {
					CloudEntry entry = CloudFiles.upload(token, provider, target.driveId, folderId, name, type, in,
							file.getSize(), false, conflict);
					uploaded.add(entry.toMap());
				}
			}
			CmsService.getLogger(getClass()).info("{} files uploaded: user={} folder={} count={}", target.provider,
					userId, (folderId == null) ? "root" : folderId, uploaded.size());
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("application/json; charset=UTF-8");
			response.getWriter().write(GSON.toJson(uploaded));
		} finally {
			for (FileItem item : items) {
				try {
					item.delete();
				} catch (Throwable ignore) {
					// A temporary file that could not be removed.
				}
			}
		}
	}

	// ---- helpers -----------------------------------------------------------------------

	/** The provider, drive and item named by the path, or null when the path is not of that shape. */
	private static Target target(HttpServletRequest request) throws IOException {
		String pathInfo = Webs.getEffectivePathInfo(request);
		if (pathInfo == null || pathInfo.isBlank()) {
			return null;
		}
		List<String> segments = new ArrayList<>();
		for (String s : pathInfo.split("/")) {
			if (!s.isEmpty()) {
				segments.add(URLDecoder.decode(s, StandardCharsets.UTF_8));
			}
		}
		if (segments.size() < 3 || segments.size() > 4) {
			return null;
		}
		boolean children = segments.size() == 4 && "children".equals(segments.get(3))
				&& "POST".equalsIgnoreCase(request.getMethod());
		return new Target(segments.get(0), segments.get(1), segments.get(2), children);
	}

	/** The signed-in user, or null for a guest or anyone without a session. */
	private static String userId(HttpServletRequest request) {
		Credentials credentials = Webs.getCredentials(request);
		if (credentials == null) {
			return null;
		}
		Session session = null;
		try {
			session = CmsService.getRepository().login(credentials, LinkedAccounts.SYSTEM_WORKSPACE);
			org.mintjams.jcr.Session jcrSession = org.mintjams.jcr.Session.class.cast(session);
			if (jcrSession.isGuest() || jcrSession.isAnonymous() || jcrSession.isSystem() || jcrSession.isService()) {
				return null;
			}
			return session.getUserID();
		} catch (Throwable ex) {
			return null;
		} finally {
			if (session != null) {
				session.logout();
			}
		}
	}

	private static String disposition(String fileName, boolean attachment) throws IOException {
		return (attachment ? "attachment" : "inline") + "; filename*=UTF-8''" + Webs.encode(fileName);
	}

	private static void error(HttpServletResponse response, int status, String code, String message)
			throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("error", code);
		body.addProperty("message", message);
		json(response, status, body);
	}

	private static void json(HttpServletResponse response, int status, JsonObject body) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		response.setStatus(status);
		response.setContentType("application/json; charset=UTF-8");
		response.getWriter().write(body.toString());
	}

	private static boolean isClientAbort(Throwable ex, HttpServletResponse response) {
		if (!response.isCommitted()) {
			return false;
		}
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof IOException) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

}
