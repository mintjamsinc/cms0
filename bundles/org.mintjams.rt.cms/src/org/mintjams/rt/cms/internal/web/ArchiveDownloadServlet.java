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

import javax.jcr.Credentials;
import javax.jcr.Node;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.tools.io.IOs;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

/**
 * Streams the ZIP produced by an {@code ArchiveJob} back to its owner.
 *
 * URL: {@code /bin/download-archive.cgi/{workspace}/{jobId}}
 *
 * The archive artifact lives under {@code /var/jobs/...}, an area ordinary
 * users cannot read, so the servlet reads it with a privileged session — but
 * only after confirming the authenticated requester is the user who created the
 * job ({@link JobNodes#PROP_JOB_USER_ID}). That ownership check is what gates
 * access here, since the JCR ACL on the job area cannot.
 */
@Component(service = Servlet.class, property = {
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=" + CmsConfiguration.DOWNLOAD_ARCHIVE_CGI_PATH + "/*",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=("
				+ HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=org.osgi.service.http)",
		HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_ASYNC_SUPPORTED + "=true" })
public class ArchiveDownloadServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		Session privilegedSession = null;
		Session authSession = null;
		try {
			String[] segments = pathSegments(request);
			if (segments.length < 2) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Workspace and job id must be specified");
				return;
			}
			String workspaceName = segments[0];
			String jobId = segments[1];

			// Authenticate the requester and resolve their user id.
			String userId;
			try {
				authSession = CmsService.getRepository().login(getCredentials(request), workspaceName);
				userId = authSession.getUserID();
			} catch (Throwable ex) {
				CmsService.getLogger(getClass()).error("Failed to authenticate archive download", ex);
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication failed");
				return;
			}

			// Read the job + archive with a privileged session: ordinary users
			// cannot read /var/jobs, so ownership is enforced here instead of by ACL.
			privilegedSession = CmsService.getRepository().login(new CmsServiceCredentials(), workspaceName);

			Node jobNode = JobNodes.getJobNode(privilegedSession, jobId);
			if (jobNode == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown job");
				return;
			}
			Node jobContent = JobNodes.getContent(jobNode);

			String owner = JobNodes.getString(jobContent, JobNodes.PROP_JOB_USER_ID, null);
			if (owner == null || !owner.equals(userId)) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not the owner of this job");
				return;
			}

			JobStatus status = JobNodes.getStatus(jobContent);

			// A third "report" segment requests the import job's CSV outcome report
			// instead of the archive ZIP. A report is meaningful for a finished job
			// whatever its terminal status (a failed/aborted import still reports up
			// to the failure point), so it is not gated on COMPLETED.
			boolean reportMode = segments.length >= 3 && "report".equals(segments[2]);
			if (reportMode) {
				if (status != JobStatus.COMPLETED && status != JobStatus.FAILED && status != JobStatus.ABORTED) {
					response.sendError(HttpServletResponse.SC_NOT_FOUND, "Report is not ready");
					return;
				}
				String reportPath = JobNodes.reportNodePath(jobId);
				if (!privilegedSession.nodeExists(reportPath)) {
					response.sendError(HttpServletResponse.SC_NOT_FOUND, "Report not found");
					return;
				}
				Node reportNode = privilegedSession.getNode(reportPath);

				response.setContentType("text/csv; charset=UTF-8");
				response.setContentLengthLong(JCRs.getContentLength(reportNode));
				response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
				response.setHeader("Expires", "0");
				response.setHeader("Pragma", "no-cache");
				response.setHeader("Content-Disposition",
						createContentDisposition("import-report-" + jobId + ".csv", request.getHeader("User-Agent")));

				try (InputStream in = JCRs.getContentAsStream(reportNode)) {
					IOs.copy(in, response.getOutputStream());
				}
				return;
			}

			if (status != JobStatus.COMPLETED) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND, "Archive is not ready");
				return;
			}

			String archivePath = JobNodes.archiveNodePath(jobId);
			if (!privilegedSession.nodeExists(archivePath)) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND, "Archive not found");
				return;
			}
			Node archiveNode = privilegedSession.getNode(archivePath);

			String fileName = JobNodes.getString(jobContent, JobNodes.PROP_ARCHIVE_FILENAME, "archive.zip");

			response.setContentType("application/zip");
			response.setContentLengthLong(JCRs.getContentLength(archiveNode));
			response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
			response.setHeader("Expires", "0");
			response.setHeader("Pragma", "no-cache");
			response.setHeader("Content-Disposition", createContentDisposition(fileName, request.getHeader("User-Agent")));

			try (InputStream in = JCRs.getContentAsStream(archiveNode)) {
				IOs.copy(in, response.getOutputStream());
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).error("Archive download failed", ex);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error: " + ex.getMessage());
		} finally {
			if (authSession != null && authSession.isLive()) {
				try { authSession.logout(); } catch (Throwable ignore) {}
			}
			if (privilegedSession != null && privilegedSession.isLive()) {
				try { privilegedSession.refresh(false); } catch (Throwable ignore) {}
				try { privilegedSession.logout(); } catch (Throwable ignore) {}
			}
		}
	}

	/**
	 * Split the path info into its non-empty segments.
	 * Example: {@code /system/1716800000000-1a2b} → {@code ["system", "1716800000000-1a2b"]}.
	 */
	private String[] pathSegments(HttpServletRequest request) {
		String pathInfo = Webs.getEffectivePathInfo(request);
		if (pathInfo == null || pathInfo.isEmpty()) {
			return new String[0];
		}
		if (pathInfo.startsWith("/")) {
			pathInfo = pathInfo.substring(1);
		}
		return pathInfo.split("/");
	}

	private Credentials getCredentials(HttpServletRequest request) {
		Object credentials = request.getAttribute(Credentials.class.getName());
		if (credentials instanceof Credentials) {
			return (Credentials) credentials;
		}
		credentials = Webs.getCredentials(request);
		if (credentials instanceof Credentials) {
			return (Credentials) credentials;
		}
		return new javax.jcr.GuestCredentials();
	}

	private String createContentDisposition(String fileName, String userAgent) throws IOException {
		String encodedName = Webs.encode(fileName);
		if (userAgent != null && (userAgent.contains("MSIE") || userAgent.contains("Trident"))) {
			return "attachment; filename=\"" + encodedName + "\"";
		}
		return "attachment; filename*=UTF-8''" + encodedName;
	}
}
