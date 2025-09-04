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

package org.mintjams.rt.cms.internal.eip;

import java.util.HashMap;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.PathNotFoundException;

import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.web.WebResourceResolver;

public class CmsComponent extends DefaultComponent {

	public static final String COMPONENT_NAME = "cms";

	private final String fWorkspaceName;

	public CmsComponent(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@Override
	protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
		CmsEndpoint endpoint = new CmsEndpoint(uri, remaining);
		setProperties(endpoint, parameters);
		return endpoint;
	}

	public class CmsEndpoint extends DefaultEndpoint {
		private final String fPath;
		private final Map<String, Object> fParameters = new HashMap<>();

		private CmsEndpoint(String endpointUri, String remaining) {
			super(endpointUri, CmsComponent.this);
			fPath = remaining;
		}

		@Override
		public Consumer createConsumer(Processor processor) throws Exception {
			// Not supported
			return null;
		}

		@Override
		public Producer createProducer() throws Exception {
			return new CmsProducer();
		}

		private class CmsProducer extends DefaultProducer {
			private CmsProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				evaluate(exchange);
				return;
			}

			private Object evaluate(Exchange exchange) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					context.setAttribute("exchange", exchange);
					Scripts.prepareAPIs(context);

					String resourcePath = fPath.startsWith("/") ? fPath : "/" + fPath;
					WebResourceResolver.ResolveResult result = new WebResourceResolver(context).resolve(resourcePath);
					if (result.isNotFound()) {
						throw new PathNotFoundException(resourcePath);
					}
					if (!result.isScriptable()) {
						return null;
					}
					if (result.isAccessDenied()) {
						throw new AccessDeniedException(resourcePath);
					}

					context.setAttribute("resource", context.getResourceResolver().toResource(result.getNode()));

					try (ScriptReader scriptReader = new ScriptReader(result.getContentAsReader())) {
						return scriptReader
								.setScriptName("jcr://" + result.getPath())
								.setExtension(result.getScriptExtension())
								.setLastModified(result.getLastModified())
								.setScriptEngineManager(Scripts.getScriptEngineManager(context))
								.setClassLoader(Scripts.getClassLoader(context))
								.setScriptContext(Scripts.getWorkspaceScriptContext(context))
								.eval();
					}
				}
			}
		}
	}
}
