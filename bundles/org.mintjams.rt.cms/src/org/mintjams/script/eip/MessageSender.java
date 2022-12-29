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

import java.util.HashMap;
import java.util.Map;

import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Strings;

public class MessageSender implements Adaptable {

	private final IntegrationAPI fIntegrationAPI;
	private String fEndpointURI;
	private Object fBody;
	private Map<String, Object> fHeaders;
	private Map<String, Object> fProperties;

	protected MessageSender(IntegrationAPI integrationAPI) {
		fIntegrationAPI = integrationAPI;
	}

	public MessageSender setEndpointURI(String uri) {
		fEndpointURI = uri;
		return this;
	}

	public String getEndpointURI() {
		return fEndpointURI;
	}

	public MessageSender setBody(Object body) {
		fBody = body;
		return this;
	}

	public Object getBody() {
		return fBody;
	}

	public MessageSender setHeader(String name, Object value) {
		if (Strings.isEmpty(name)) {
			throw new IllegalArgumentException("Name must not be null or empty.");
		}

		if (fHeaders == null) {
			fHeaders = new HashMap<>();
		}
		fHeaders.put(name, value);
		return this;
	}

	public MessageSender setHeaders(Map<String, Object> headers) {
		if (headers == null) {
			fHeaders = null;
			return this;
		}

		if (fHeaders == null) {
			fHeaders = new HashMap<>();
		}
		fHeaders.putAll(headers);
		return this;
	}

	public Map<String, Object> getHeaders() {
		return fHeaders;
	}

	public MessageSender setProperty(String name, Object value) {
		if (Strings.isEmpty(name)) {
			throw new IllegalArgumentException("Name must not be null or empty.");
		}

		if (fProperties == null) {
			fProperties = new HashMap<>();
		}
		fProperties.put(name, value);
		return this;
	}

	public MessageSender setProperties(Map<String, Object> properties) {
		if (properties == null) {
			fProperties = null;
			return this;
		}

		if (fProperties == null) {
			fProperties = new HashMap<>();
		}
		fProperties.putAll(properties);
		return this;
	}

	public Map<String, Object> getProperties() {
		return fProperties;
	}

	public Reply send() throws Exception {
		return new Reply(this);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fIntegrationAPI, adapterType);
	}

}
