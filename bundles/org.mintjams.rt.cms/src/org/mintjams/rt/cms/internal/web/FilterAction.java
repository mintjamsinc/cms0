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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.jcr.Node;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.util.Action;
import org.mintjams.tools.util.ActionChain;
import org.mintjams.tools.util.ActionContext;
import org.mintjams.tools.util.ActionException;
import org.mintjams.tools.util.SimpleActionChain;

public class FilterAction implements Action {

	public void doAction(ActionContext context, ActionChain chain) throws ActionException {
		List<Map<String, Object>> filters = getFilters(context);
		if (filters.isEmpty()) {
			chain.doAction(context);
			return;
		}

		try {
			List<Action> actions = new ArrayList<>();
			for (Map<String, Object> config : filters) {
				actions.add(new ScriptFilterAction(config, context));
			}
			actions.add(new Action() {
				@Override
				public void doAction(ActionContext context, ActionChain filterChain) throws ActionException {
					WorkspaceScriptContext ctx = Scripts.getWorkspaceScriptContext(context);
					Object fc = ctx.removeAttribute("chain");
					try {
						chain.doAction(context);
					} finally {
						ctx.setAttribute("chain", fc);
					}
				}
			});

			WorkspaceScriptContext ctx = Scripts.getWorkspaceScriptContext(context);
			try {
				ActionChain fc = new SimpleActionChain(actions);
				ctx.setAttribute("chain", fc);
				fc.doAction(context);
			} finally {
				ctx.removeAttribute("chain");
			}
		} catch (ActionException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw (ActionException) new ActionException(ex.getMessage()).initCause(ex);
		}
	}

	private List<Map<String, Object>> getFilters(ActionContext context) {
		Collection<Map<String, Object>> filters = Webs.getFilterConfig(context);
		if (filters == null || filters.isEmpty()) {
			return new ArrayList<>();
		}

		String resourcePath = Webs.getResourcePath(context);
		String dispatcher;
		if (Webs.isErrorRequest(context)) {
			dispatcher = "ERROR";
		} else if (Webs.isForwardRequest(context)) {
			dispatcher = "FORWARD";
		} else if (Webs.isIncludeRequest(context)) {
			dispatcher = "INCLUDE";
		} else {
			dispatcher = "REQUEST";
		}

		List<Map<String, Object>> results = new ArrayList<>();
		for (Map<String, Object> mapping : filters) {
			String[] urlPatterns = Scripts.asStringArray(mapping.get("urlPattern"));
			if (urlPatterns.length == 0) {
				urlPatterns = new String[] { "/*" };
			}

			List<String> dispatcers;
			{
				String[] a = Scripts.asStringArray(mapping.get("dispatcer"));
				if (a.length == 0) {
					a = new String[] { "REQUEST" };
				}
				dispatcers = Arrays.stream(a).map(e -> e.toUpperCase()).collect(Collectors.toList());
			}

			String filter = null;
			for (String urlPattern : urlPatterns) {
				if (urlPattern.startsWith("regexp:")) {
					if (resourcePath.matches(urlPattern.substring("regexp:".length()))) {
						filter = (String) mapping.get("filter");
					}
				} else {
					do {
						if (urlPattern.endsWith("/*")) {
							if (resourcePath.startsWith(urlPattern.substring(0, urlPattern.length() - 1))) {
								filter = (String) mapping.get("filter");
							}
							break;
						}

						if (urlPattern.startsWith("*.")) {
							if (resourcePath.endsWith(urlPattern.substring(1))) {
								filter = (String) mapping.get("filter");
							}
							break;
						}

						if (resourcePath.equals(urlPattern)) {
							filter = (String) mapping.get("filter");
							break;
						}
					} while (false);
				}

				if (filter != null) {
					break;
				}
			}
			if (filter == null) {
				continue;
			}

			if (!dispatcers.contains(dispatcher)) {
				continue;
			}

			results.add(mapping);
		}
		return results;
	}

	private class ScriptFilterAction implements Action {
		private final Map<String, Object> fConfig;

		private ScriptFilterAction(Map<String, Object> config, ActionContext context) {
			fConfig = config;
		}

		@Override
		public void doAction(ActionContext context, ActionChain chain) throws ActionException {
			try {
				Node node = Scripts.getJcrSession(context).getNode(Webs.CONTENT_PATH + (String) fConfig.get("filter"));

				try (ScriptReader scriptReader = new ScriptReader(JCRs.getContentAsReader(node))) {
					scriptReader
							.setScriptName(node.getPath())
							.setLastModified(JCRs.getLastModified(node))
							.setScriptEngineManager(Scripts.getScriptEngineManager(context))
							.setClassLoader(Scripts.getClassLoader(context))
							.setScriptContext(Scripts.getWorkspaceScriptContext(context))
							.eval();
				}
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ActionException.class, "An error occurred while processing filter: " + fConfig.get("filter"));
			}
		}
	}
}
