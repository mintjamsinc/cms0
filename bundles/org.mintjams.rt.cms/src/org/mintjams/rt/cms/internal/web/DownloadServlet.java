/*
 * Copyright (c) 2022 MintJams Inc.
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

package org.mintjams.rt.cms.internal.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.jcr.Binary;
import javax.jcr.Credentials;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;

import org.apache.tika.Tika;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Strings;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

/**
 * Servlet for GraphQL API Endpoint: /bin/download.cgi/{workspace}
 */
@Component(service = Servlet.class, property = {
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=" + CmsConfiguration.DOWNLOAD_CGI_PATH + "/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=("
				+ HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=org.osgi.service.http)",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_ASYNC_SUPPORTED + "=true" })
public class DownloadServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final Tika TIKA = new Tika();

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		handleRequest(request, response);
	}

	private void handleRequest(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		Session jcrSession = null;

		try {
			// Get workspace name
			String workspaceName = getWorkspaceName(request);
			if (workspaceName == null || workspaceName.isEmpty()) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Workspace name must be specified");
				return;
			}

			// Get credentials
			Credentials credentials = getCredentials(request);

			// Get JCR session
			try {
				jcrSession = CmsService.getRepository().login(credentials, workspaceName);
			} catch (Throwable ex) {
				CmsService.getLogger(getClass()).error("Failed to create JCR session", ex);
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication failed: " + ex.getMessage());
				return;
			}

			// Get path
			String path = getPath(request);
			if (path == null || path.isEmpty()) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND, "Path must be specified");
				return;
			}

			// Prevent directory traversal attack
			if (path.contains("..")) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN);
				return;
			}

			// Get node
			Node node;
			try {
				node = jcrSession.getNode(path);
			} catch (PathNotFoundException ex) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND, "Node not found: " + path);
				return;
			}

			// Check for property download mode
			String propertyName = request.getParameter("property");
			if (propertyName != null && !propertyName.isEmpty()) {
				handlePropertyDownload(node, propertyName, request, response);
				return;
			}

			// Check node type
			if (!node.isNodeType(NodeType.NT_FILE)) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Target node is not a file: " + path);
				return;
			}

			RangeHeader rangeHeader = null;
			if (Webs.isNormalRequest(request)) {
				long lastModified = getLastModified(node).getTime();
				String eTag = "" + lastModified;
				response.addDateHeader("Last-Modified", lastModified);
				response.setHeader("ETag", eTag);
				String contentType = JCRs.getMimeType(node);
				if (StringUtils.isNotEmpty(contentType)) {
					response.setContentType(contentType);
					if (contentType.startsWith("text/")) {
						String encoding = StringUtils.defaultIfEmpty(JCRs.getEncoding(node), StandardCharsets.UTF_8.name());
						response.setCharacterEncoding(encoding);
					}
				}
				response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
				response.setHeader("Expires", "0");
				response.setHeader("Pragma", "no-cache");
				boolean isAttachment;
				if (request.getParameterMap().containsKey("attachment")) {
					isAttachment = Boolean.parseBoolean(StringUtils.defaultIfEmpty(
							request.getParameter("attachment"), Boolean.TRUE.toString()));
				} else {
					isAttachment = false;
				}
				response.setHeader("Content-Disposition", createContentDisposition(
						node.getName(),
						request.getHeader("User-Agent"),
						isAttachment));

				if (RangeHeader.isRangeRequest(request)) {
					try {
						rangeHeader = RangeHeader.create(request);
					} catch (Throwable ignore) {
						response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid Range Header");
						return;
					}

					String ifRange = request.getHeader("If-Range");
					if (!Strings.isEmpty(ifRange) && !ifRange.equals(eTag)) {
						rangeHeader = null;
					}
				}
			}

			if (rangeHeader != null) {
				long[] range = prepareRange(node, rangeHeader, response);
				if (range == null) {
					return;
				}

				response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
				response.setHeader("Accept-Ranges", "bytes");
				long contentLength = range[0];
				long start = range[1];
				long end = range[2];
				long length = range[3];
				response.setContentLengthLong(length);
				response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + contentLength);
			}

			try (InputStream in = JCRs.getContentAsStream(node)) {
				if (rangeHeader == null) {
					IOs.copy(in, response.getOutputStream());
				} else {
					IOs.copy(in, response.getOutputStream(), rangeHeader.getStart(), rangeHeader.getLength());
				}
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).error("Download execution failed", ex);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error: " + ex.getMessage());
		} finally {
			if (jcrSession != null && jcrSession.isLive()) {
				try {
					jcrSession.refresh(false);
				} catch (Throwable ignore) {}
				jcrSession.logout();
			}
		}
	}

	/**
	 * Handle download of a binary property value from a node's jcr:content child.
	 * URL: /bin/download.cgi/{workspace}/{path}?property={propertyName}
	 */
	private void handlePropertyDownload(Node node, String propertyName, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		try {
			// Validate property name to prevent access to system properties
			if (propertyName.startsWith("jcr:") || propertyName.startsWith("rep:") || propertyName.startsWith("oak:")) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access to system properties is not allowed");
				return;
			}

			// Get the content node (properties are stored on jcr:content)
			Node targetNode = node;
			if (node.hasNode("jcr:content")) {
				targetNode = node.getNode("jcr:content");
			}

			if (!targetNode.hasProperty(propertyName)) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND, "Property not found: " + propertyName);
				return;
			}

			Property prop = targetNode.getProperty(propertyName);
			if (prop.getType() != PropertyType.BINARY) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Property is not a binary type: " + propertyName);
				return;
			}

			Binary binary = prop.getBinary();
			try {
				long size = binary.getSize();

				// Detect MIME type from content
				String contentType = "application/octet-stream";
				try (InputStream detectStream = binary.getStream()) {
					byte[] header = new byte[2048];
					int bytesRead = 0;
					int read;
					while (bytesRead < header.length && (read = detectStream.read(header, bytesRead, header.length - bytesRead)) != -1) {
						bytesRead += read;
					}
					if (bytesRead > 0) {
						byte[] buf = (bytesRead == header.length) ? header : java.util.Arrays.copyOf(header, bytesRead);
						contentType = TIKA.detect(buf);
					}
				}

				response.setContentType(contentType);
				response.setContentLengthLong(size);
				response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
				response.setHeader("Expires", "0");
				response.setHeader("Pragma", "no-cache");

				boolean isAttachment;
				if (request.getParameterMap().containsKey("attachment")) {
					isAttachment = Boolean.parseBoolean(StringUtils.defaultIfEmpty(
							request.getParameter("attachment"), Boolean.TRUE.toString()));
				} else {
					isAttachment = false;
				}
				String fileName = propertyName + getExtensionForMimeType(contentType);
				response.setHeader("Content-Disposition", createContentDisposition(
						fileName,
						request.getHeader("User-Agent"),
						isAttachment));

				// Re-acquire stream for actual download (previous stream was used for detection)
				Binary downloadBinary = prop.getBinary();
				try {
					try (InputStream in = downloadBinary.getStream()) {
						IOs.copy(in, response.getOutputStream());
					}
				} finally {
					downloadBinary.dispose();
				}
			} finally {
				binary.dispose();
			}
		} catch (RepositoryException ex) {
			CmsService.getLogger(getClass()).error("Property download failed", ex);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error: " + ex.getMessage());
		}
	}

	/**
	 * Extract workspace name from URL Example: /bin/download.cgi/system → "system"
	 */
	private String getWorkspaceName(HttpServletRequest request) {
		String pathInfo = Webs.getEffectivePathInfo(request);
		if (pathInfo == null || pathInfo.isEmpty()) {
			return null;
		}

		if (pathInfo.startsWith("/")) {
			pathInfo = pathInfo.substring(1);
		}

		String[] segments = pathInfo.split("/");
		return segments.length > 0 ? segments[0] : null;
	}

	/**
	 * Extract path from URL Example: /bin/download.cgi/system/path/to/node → "/path/to/node"
	 */
	private String getPath(HttpServletRequest request) {
		String fullPath = Webs.getEffectivePathInfo(request);
		if (fullPath == null || fullPath.isEmpty()) {
			return null;
		}

		int secondSlashIndex = fullPath.indexOf("/", 1);
		if (secondSlashIndex != -1) {
			return fullPath.substring(secondSlashIndex);
		}

		return null;
	}

	/**
	 * Get file extension for a MIME type using Tika's registry.
	 * Returns an empty string if no extension is found.
	 */
	private static String getExtensionForMimeType(String mimeType) {
		if (mimeType == null || mimeType.isEmpty() || "application/octet-stream".equals(mimeType)) {
			return "";
		}
		try {
			return MimeTypes.getDefaultMimeTypes().forName(mimeType).getExtension();
		} catch (MimeTypeException ex) {
			return "";
		}
	}

	/**
	 * Get authentication credentials
	 */
	private Credentials getCredentials(HttpServletRequest request) {
		// Get credentials from HttpServletRequest
		// Use cms0's existing authentication mechanism
		Object credentials = request.getAttribute(Credentials.class.getName());
		if (credentials instanceof Credentials) {
			return (Credentials) credentials;
		}

		// Get credentials from session
		if (request.getSession(false) != null) {
			credentials = request.getSession().getAttribute(Credentials.class.getName());
			if (credentials instanceof Credentials) {
				return (Credentials) credentials;
			}
		}

		// Default is guest authentication
		return new javax.jcr.GuestCredentials();
	}

	/**
	 * Get last modified date of the node
	 */
	private java.util.Date getLastModified(Node node) throws RepositoryException {
		try {
			return node.getNode(Node.JCR_CONTENT).getProperty(Property.JCR_LAST_MODIFIED).getDate().getTime();
		} catch (PathNotFoundException ignore) {}
		return new java.util.Date();
	}

	/**
	 * Get content length of the node
	 */
	private long getContentLength(Node node) throws RepositoryException, IOException {
		try (org.mintjams.jcr.Binary value = (org.mintjams.jcr.Binary) node.getNode(Node.JCR_CONTENT)
				.getProperty(Property.JCR_DATA).getBinary()) {
			return value.getSize();
		}
	}

	/**
	 * Prepare range request
	 */
	private long[] prepareRange(Node node, RangeHeader rangeHeader, HttpServletResponse response)
			throws RepositoryException, IOException {
		long contentLength = getContentLength(node);
		long start = rangeHeader.getStart();
		long end = rangeHeader.getEnd();

		// Check start position
		if (start < 0) {
			response.setHeader("Content-Range", "bytes */" + contentLength);
			response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE, "Invalid Range");
			return null;
		}

		// Check start position against content length
		if (start >= contentLength) {
			response.setHeader("Content-Range", "bytes */" + contentLength);
			response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
			return null;
		}

		long length;
		if (end >= 0) {
			// Check end position
			if (end < start) {
				response.setHeader("Content-Range", "bytes */" + contentLength);
				response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE, "Invalid Range");
				return null;
			}
			// Adjust end position against content length
			if (end >= contentLength) {
				end = contentLength - 1;
			}
			length = end - start + 1;
		} else {
			// If end position is not specified, set it to the last byte
			end = contentLength - 1;
			length = contentLength - start;
		}

		return new long[] { contentLength, start, end, length };
	}

	/**
	 * Create Content-Disposition header value
	 */
	private String createContentDisposition(String fileName, String userAgent, boolean isAttachment) throws IOException {
		String encodedName = Webs.encode(fileName);
		if (userAgent != null && (userAgent.contains("MSIE") || userAgent.contains("Trident"))) {
			// IE
			return (isAttachment ? "attachment": "inline") + "; filename=\"" + encodedName + "\"";
		}
		// Modern Browsers (RFC 5987)
		return (isAttachment ? "attachment": "inline") + "; filename*=UTF-8''" + encodedName;
	}

}
