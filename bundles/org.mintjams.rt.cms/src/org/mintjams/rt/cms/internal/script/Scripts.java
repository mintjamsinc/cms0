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

package org.mintjams.rt.cms.internal.script;

import java.io.IOException;
import java.nio.file.spi.FileTypeDetector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.script.JSON;
import org.mintjams.script.LoggerAPI;
import org.mintjams.script.MimeTypeAPI;
import org.mintjams.script.ScriptAPI;
import org.mintjams.script.YAML;
import org.mintjams.script.bpm.ProcessAPI;
import org.mintjams.script.eip.IntegrationAPI;
import org.mintjams.script.event.EventAdminAPI;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.SessionAPI;
import org.mintjams.script.resource.query.XPath;
import org.mintjams.script.web.WebAPI;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.util.ActionContext;

public class Scripts {

	private Scripts() {}

	public static WorkspaceScriptContext getWorkspaceScriptContext(ActionContext context) {
		return Adaptables.getAdapter(context, WorkspaceScriptContext.class);
	}

	public static String getWorkspaceName(ActionContext context) {
		return getWorkspaceScriptContext(context).getWorkspaceName();
	}

	public static ClassLoader getClassLoader(ActionContext context) {
		return CmsService.getWorkspaceClassLoaderProvider(getWorkspaceName(context)).getClassLoader();
	}

	public static ScriptEngineManager getScriptEngineManager(ActionContext context) {
		return CmsService.getWorkspaceScriptEngineManager(getWorkspaceName(context));
	}

	public static String[] getScriptExtensions(ActionContext context) {
		List<String> l = new ArrayList<>();
		for (ScriptEngineFactory factory : getScriptEngineManager(context).getEngineFactories()) {
			for (String e : factory.getExtensions()) {
				if (!l.contains(e)) {
					l.add(e);
				}
			}
		}
		return l.toArray(String[]::new);
	}

	public static FileTypeDetector getFileTypeDetector(ActionContext context) {
		return Adaptables.getAdapter(CmsService.getRepository(), FileTypeDetector.class);
	}

	public static void prepareAPIs(ActionContext context) throws IOException {
		WorkspaceScriptContext ctx = getWorkspaceScriptContext(context);
		LoggerAPI log = new LoggerAPI(ctx);
		ctx.setAttribute("log", log);
		ctx.setAttribute(LoggerAPI.class.getSimpleName(), log);
		ctx.setAttribute(MimeTypeAPI.class.getSimpleName(), new MimeTypeAPI(ctx));
		ctx.setAttribute(EventAdminAPI.class.getSimpleName(), new EventAdminAPI(ctx));
		ctx.setAttribute(CryptoAPI.class.getSimpleName(), new CryptoAPI());
		ctx.setAttribute(SessionAPI.class.getSimpleName(), new SessionAPI(ctx));
		if (ctx.getAttribute("request") != null) {
			ctx.setAttribute(WebAPI.class.getSimpleName(), new WebAPI(ctx));
		}
		ctx.setAttribute(ScriptAPI.class.getSimpleName(), new ScriptAPI(ctx));
		ctx.setAttribute(ProcessAPI.class.getSimpleName(), new ProcessAPI(ctx));
		ctx.setAttribute(IntegrationAPI.class.getSimpleName(), new IntegrationAPI(ctx));
		ctx.setAttribute(XPath.class.getSimpleName(), new XPath(ctx));
		ctx.setAttribute(JSON.class.getSimpleName(), new JSON(ctx));
		ctx.setAttribute(YAML.class.getSimpleName(), new YAML(ctx));
		try {
			ctx.setAttribute("repositorySession", ctx.getResourceResolver().getSession());
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	public static Session getJcrSession(ActionContext context) throws RepositoryException {
		return getWorkspaceScriptContext(context).adaptTo(Session.class);
	}

	public static String[] asStringArray(Object o) {
		if (o == null) {
			return new String[0];
		}
		if (o instanceof Collection) {
			return ((Collection<?>) o).stream().map(e -> e.toString().trim()).toArray(String[]::new);
		}
		if (o instanceof String) {
			return ((String) o).split("\\s*,\\s*");
		}
		return new String[] { o.toString() };
	}

}
