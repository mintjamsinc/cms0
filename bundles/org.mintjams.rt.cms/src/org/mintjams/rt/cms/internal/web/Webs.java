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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.lang3.StringUtils;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.script.YAML;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.util.ActionContext;

public class Webs {

	public static final String CONTENT_PATH = "/content";
	public static final String DEFAULT_WEB_TEMPLATE_PATH = "/content/WEB-INF/templates";
	public static final String WEB_TEMPLATE = "web.template";

	private Webs() {}

	public static WorkspaceScriptContext newActionScriptContext(String workspaceName, HttpServletRequest request) {
		WorkspaceScriptContext context = new WorkspaceScriptContext(workspaceName);
		request.setAttribute(WorkspaceScriptContext.class.getName(), context);
		return context;
	}

	public static WorkspaceScriptContext getActionScriptContext(HttpServletRequest request) {
		return (WorkspaceScriptContext) request.getAttribute(WorkspaceScriptContext.class.getName());
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> getWebConfig(ActionContext context) {
		WorkspaceScriptContext ctx = Scripts.getWorkspaceScriptContext(context);
		Map<String, Object> webConfig = (Map<String, Object>) ctx.getAttribute("webConfig");
		if (webConfig == null) {
			try {
				webConfig = (Map<String, Object>) ctx.adaptTo(YAML.class).parse(ctx.getSession().getResource("/content/WEB-INF/web.yml"));
				ctx.setAttribute("webConfig", webConfig);
			} catch (ResourceException | IOException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
		return webConfig;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> getDefaultResponseHeaderConfig(ActionContext context) {
		return  (Map<String, Object>) getWebConfig(context).get("defaultResponseHeaders");
	}

	public static String[] getWelcomeFiles(ActionContext context) {
		String[] a = Scripts.asStringArray(getWebConfig(context).get("welcomeFiles"));
		if (a.length == 0) {
			return new String[] { "index.html" };
		}
		return a;
	}

	@SuppressWarnings("unchecked")
	public static Collection<Map<String, Object>> getErrorPageConfig(ActionContext context) {
		return (Collection<Map<String, Object>>) getWebConfig(context).get("errorPages");
	}

	@SuppressWarnings("unchecked")
	public static Collection<Map<String, Object>> getFilterConfig(ActionContext context) {
		return (Collection<Map<String, Object>>) getWebConfig(context).get("filters");
	}

	public static String getEffectivePathInfo(HttpServletRequest request) {
		DispatcherType type = request.getDispatcherType();
		if (type == DispatcherType.INCLUDE) {
			return (String) request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
		} else if (type == DispatcherType.FORWARD) {
			return (String) request.getAttribute(RequestDispatcher.FORWARD_PATH_INFO);
		} else {
			return request.getPathInfo();
		}
	}

	public static String getWorkspacePath(ActionContext context) {
		return CmsConfiguration.CMS_CGI_PATH + "/" + Scripts.getWorkspaceScriptContext(context).getWorkspaceName();
	}

	public static String getResourcePath(ActionContext context) {
		String resourcePath = Scripts.getWorkspaceScriptContext(context).getResourcePath();
		if (resourcePath != null) {
			return resourcePath;
		}

		try {
			return getRelativePathWithinWorkspace(JCRs.normalizePath(getEffectivePathInfo(getRequest(context))));
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	private static String getRelativePathWithinWorkspace(String path) {
		if (path.length() > 1) {
			path = StringUtils.substring(path, StringUtils.indexOf(path, '/', 1));
		}
		return path;
	}

	public static String getCollectionPath(ActionContext context) {
		String path = getResourcePath(context);
		if (Strings.isEmpty(path) || path.equals("/")) {
			return "/";
		}
		return path.substring(0, path.lastIndexOf("/" + 1));
	}

	public static int getResponseStatus(ActionContext context) {
		return getResponse(context).getStatus();
	}

	public static HttpServletRequest getRequest(ActionContext context) {
		return (HttpServletRequest) Scripts.getWorkspaceScriptContext(context).getAttribute("request");
	}

	public static HttpServletResponse getResponse(ActionContext context) {
		return (HttpServletResponse) Scripts.getWorkspaceScriptContext(context).getAttribute("response");
	}

	public static boolean isNormalRequest(HttpServletRequest request) {
		return !isForwardRequest(request) && !isIncludeRequest(request) && !isErrorRequest(request);
	}

	public static boolean isNormalRequest(ActionContext context) {
		return isNormalRequest(getRequest(context));
	}

	public static boolean isForwardRequest(HttpServletRequest request) {
		return (request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI) != null);
	}

	public static boolean isForwardRequest(ActionContext context) {
		return isForwardRequest(getRequest(context));
	}

	public static boolean isIncludeRequest(HttpServletRequest request) {
		return (request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null);
	}

	public static boolean isIncludeRequest(ActionContext context) {
		return isIncludeRequest(getRequest(context));
	}

	public static boolean isErrorRequest(HttpServletRequest request) {
		return (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) != null);
	}

	public static boolean isErrorRequest(ActionContext context) {
		return isErrorRequest(getRequest(context));
	}

	public static String encode(String text) throws IOException {
		try {
			return new URLCodec(StandardCharsets.UTF_8.name()).encode(text);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	public static String encodePath(String path) throws IOException {
		try {
			return String.join("/", Arrays.stream(path.split("/")).map(e -> {
				try {
					return encode(e);
				} catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}).toArray(String[]::new));
		} catch (UncheckedIOException ex) {
			throw ex.getCause();
		}
	}

	public static String decode(String text) throws IOException {
		try {
			return new URLCodec(StandardCharsets.UTF_8.name()).decode(text);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

}
