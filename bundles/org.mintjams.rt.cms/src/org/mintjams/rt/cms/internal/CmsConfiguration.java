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

package org.mintjams.rt.cms.internal;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Strings;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class CmsConfiguration {

	public static final String CMS_CGI_PATH = "/bin/cms.cgi";
	public static final String DEFAULT_START_WEB_URI = CMS_CGI_PATH + "/system/webtop/index.html";
	public static final int DEFAULT_MAX_SCRIPT_CACHE_PER_SCRIPT_ENGINE = 32;
	public static final int DEFAULT_CLASS_LOADER_REFRESH_INTERVAL = 8;

	private Map<String, Object> fConfig;

	@SuppressWarnings("unchecked")
	private Map<String, Object> getConfig() throws IOException {
		if (fConfig == null) {
			Path configPath = getConfigPath();
			if (!Files.exists(configPath)) {
				Files.createDirectories(configPath);
			}
			Path cmsPath = configPath.resolve("cms.yml");
			if (!Files.exists(cmsPath)) {
				try (Writer out = Files.newBufferedWriter(cmsPath)) {
					String yamlString = new Dump(DumpSettings.builder().build()).dumpToString(AdaptableMap.<String, Object>newBuilder()
							.put("startWebURI", DEFAULT_START_WEB_URI)
							.put("maxScriptCachePerScriptEngine", DEFAULT_MAX_SCRIPT_CACHE_PER_SCRIPT_ENGINE)
							.build());
					out.append(yamlString);
				}
			}

			try (InputStream in = new BufferedInputStream(Files.newInputStream(cmsPath))) {
				fConfig = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
			}
		}
		return fConfig;
	}

	public HttpServlet createStartServlet() throws IOException {
		return new StartServlet(this);
	}

	public Path getConfigPath() {
		return CmsService.getRepositoryPath().resolve("etc").normalize();
	}

	public String getStartWebURI() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.defaultIfEmpty("config.startWebURI", DEFAULT_START_WEB_URI);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("The startWebURI parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_START_WEB_URI;
	}

	public int getMaxScriptCachePerScriptEngine() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.getInt("config.maxScriptCachePerScriptEngine", DEFAULT_MAX_SCRIPT_CACHE_PER_SCRIPT_ENGINE);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("The maxScriptCachePerScriptEngine parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_MAX_SCRIPT_CACHE_PER_SCRIPT_ENGINE;
	}

	public int getClassLoaderRefreshInterval() {
		try {
			return ExpressionContext.create()
					.setVariable("config", getConfig())
					.getInt("config.classLoaderRefreshInterval", DEFAULT_CLASS_LOADER_REFRESH_INTERVAL);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("The classLoaderRefreshInterval parameter is invalid. Default values will be used instead.");
		}
		return DEFAULT_CLASS_LOADER_REFRESH_INTERVAL;
	}

	private static class StartServlet extends HttpServlet {
		private static final long serialVersionUID = 1L;

		private final CmsConfiguration fConfig;

		protected StartServlet(CmsConfiguration config) {
			fConfig = config;
		}

		@Override
		protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
			if (Strings.isEmpty(request.getRequestURI()) || request.getRequestURI().equals("/")) {
				response.sendRedirect(fConfig.getStartWebURI());
			}

			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		}
	}

}
