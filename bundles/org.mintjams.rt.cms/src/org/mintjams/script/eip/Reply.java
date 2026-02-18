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

package org.mintjams.script.eip;

import java.util.Map;
import java.util.Map.Entry;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.support.DefaultExchange;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public class Reply implements Adaptable {

	private final MessageSender fMessageSender;
	private final Object fBody;
	private final Map<String, Object> fHeaders;
	private final Map<String, Object> fProperties;

	protected Reply(MessageSender messageSender) throws Exception {
		fMessageSender = messageSender;
		CamelContext camelContext = CmsService
				.getWorkspaceIntegrationEngineProvider(adaptTo(WorkspaceScriptContext.class).getWorkspaceName())
				.getCamelContext();
		Exchange exchange = new DefaultExchange(camelContext);
		exchange.getIn().setBody(fMessageSender.getBody());
		if (fMessageSender.getHeaders() != null) {
			for (Entry<String, Object> e : fMessageSender.getHeaders().entrySet()) {
				exchange.getIn().setHeader(e.getKey(), e.getValue());
			}
		}
		if (fMessageSender.getProperties() != null) {
			for (Entry<String, Object> e : fMessageSender.getProperties().entrySet()) {
				exchange.setProperty(e.getKey(), e.getValue());
			}
		}
		exchange.setPattern(ExchangePattern.InOut);
		Exchange reply = camelContext.createProducerTemplate().send(fMessageSender.getEndpointURI(), exchange);
		fBody = reply.getMessage().getBody();
		fHeaders = reply.getMessage().getHeaders();
		fProperties = reply.getProperties();
	}

	public Object getBody() {
		return fBody;
	}

	public Object setHeader(String name) {
		return fHeaders.get(name);
	}

	public Map<String, Object> getHeaders() {
		return fHeaders;
	}

	public Object getProperty(String name) {
		return fProperties.get(name);
	}

	public Map<String, Object> getProperties() {
		return fProperties;
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fMessageSender, adapterType);
	}

}
