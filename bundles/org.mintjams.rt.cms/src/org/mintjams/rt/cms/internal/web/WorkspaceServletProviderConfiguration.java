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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.servlet.http.HttpServlet;

import org.mintjams.jcr.util.ExpressionContext;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.CmsConfiguration;
import org.mintjams.tools.collections.AdaptableMap;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class WorkspaceServletProviderConfiguration {

	private final String fWorkspaceName;
	private Map<String, Object> fConfig;

	public WorkspaceServletProviderConfiguration(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	public HttpServlet createServlet() throws IOException {
		Path configPath = getConfigPath();
		if (!Files.exists(configPath)) {
			Files.createDirectories(configPath);
		}
		Path webPath = configPath.resolve("web.yml");
		if (!Files.exists(webPath)) {
			try (Writer out = Files.newBufferedWriter(webPath)) {
				String yamlString = new Dump(DumpSettings.builder().build()).dumpToString(AdaptableMap.<String, Object>newBuilder()
						.put("contextPath", CmsConfiguration.CMS_CGI_PATH + "/" + getWorkspaceName())
						.build());
				out.append(yamlString);
			}
		}

		try (InputStream in = new BufferedInputStream(Files.newInputStream(webPath))) {
			fConfig = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
		}

		return new WorkspaceServlet(this);
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public Path getWorkspacePath() {
		return CmsService.getRepositoryPath().resolve("workspaces").resolve(getWorkspaceName()).normalize();
	}

	public Path getConfigPath() {
		return getWorkspacePath().resolve("etc").normalize();
	}

	public String getContextPath() {
		return ExpressionContext.create()
				.setVariable("config", fConfig)
				.defaultIfEmpty("config.contextPath", CmsConfiguration.CMS_CGI_PATH + "/" + getWorkspaceName());
	}

}
