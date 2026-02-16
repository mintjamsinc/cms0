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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;

import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;
import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.web.WebResourceResolver;

public class CmsComponent extends DefaultComponent {

	public static final String COMPONENT_NAME = "cms";

	private final String fWorkspaceName;

	public CmsComponent(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@Override
	protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
		// Parse operation type from remaining (e.g., "store", "setProperties", "move")
		String operation = remaining;

		CmsEndpoint endpoint = new CmsEndpoint(uri, operation, parameters);
		setProperties(endpoint, parameters);
		return endpoint;
	}

	public class CmsEndpoint extends DefaultEndpoint {
		private final String fOperation;
		private final Map<String, Object> fParameters;

		private CmsEndpoint(String endpointUri, String operation, Map<String, Object> parameters) {
			super(endpointUri, CmsComponent.this);
			fOperation = operation;
			fParameters = new HashMap<>(parameters);
		}

		@Override
		public Consumer createConsumer(Processor processor) throws Exception {
			// Not supported
			return null;
		}

		@Override
		public Producer createProducer() throws Exception {
			// Determine operation type
			if ("store".equals(fOperation)) {
				return new StoreProducer();
			} else if ("setProperties".equals(fOperation)) {
				return new SetPropertiesProducer();
			} else if ("move".equals(fOperation)) {
				return new MoveProducer();
			} else {
				// Default: script execution (backward compatibility)
				return new ScriptProducer(fOperation);
			}
		}

		/**
		 * Producer for script execution (backward compatibility)
		 */
		private class ScriptProducer extends DefaultProducer {
			private final String fPath;

			private ScriptProducer(String path) {
				super(CmsEndpoint.this);
				fPath = path;
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

		/**
		 * Ensure parent path exists, creating intermediate folders if necessary
		 */
		private void ensureParentExists(Session session, String parentPath, boolean createParents) throws Exception {
			JcrPath jcrParentPath = JcrPath.valueOf(parentPath);
			if (jcrParentPath.isRoot()) {
				return; // Root always exists
			}

			if (JCRs.isFolder(jcrParentPath, session)) {
				return; // Already exists
			}
			if (JCRs.exists(JcrPath.valueOf(parentPath), session)) {
				throw new IllegalArgumentException("Parent path exists but is not a folder: " + parentPath);
			}

			if (!createParents) {
				throw new PathNotFoundException("Parent path does not exist: " + parentPath);
			}

			// Create parent path recursively
			JCRs.getOrCreateFolder(JcrPath.valueOf(parentPath), session);
		}

		/**
		 * Producer for storing files to JCR
		 * URI format: cms:store?path=/content/file.txt&mimeType=application/json&createParents=true
		 */
		private class StoreProducer extends DefaultProducer {
			private StoreProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					Session session = Scripts.getJcrSession(context);

					// Get parameters from endpoint parameters or exchange headers
					String path = getParameter(exchange, "path");
					String mimeType = getParameter(exchange, "mimeType");
					String createParentsStr = getParameter(exchange, "createParents");

					// Default createParents to true
					boolean createParents = true;
					if (createParentsStr != null && !createParentsStr.trim().isEmpty()) {
						createParents = Boolean.parseBoolean(createParentsStr);
					}

					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}
					if (mimeType == null || mimeType.trim().isEmpty()) {
						mimeType = "application/octet-stream"; // Default MIME type
					}

					// Get content from exchange body
					byte[] content = exchange.getIn().getBody(byte[].class);
					if (content == null) {
						throw new IllegalArgumentException("Exchange body is empty");
					}

					// Ensure parent path exists
					String parentPath = path.substring(0, path.lastIndexOf('/'));
					if (parentPath.isEmpty()) {
						parentPath = "/";
					}
					String fileName = path.substring(path.lastIndexOf('/') + 1);

					// Ensure parent path exists (create if necessary)
					ensureParentExists(session, parentPath, createParents);

					Node parentNode = session.getNode(parentPath);

					// Create or update file node
					Node fileNode;
					if (session.nodeExists(path)) {
						// Update existing file
						fileNode = session.getNode(path);
					} else {
						// Create new file
						fileNode = parentNode.addNode(fileName, "nt:file");
					}

					// Create or update jcr:content node
					Node contentNode;
					if (fileNode.hasNode("jcr:content")) {
						contentNode = fileNode.getNode("jcr:content");
					} else {
						contentNode = fileNode.addNode("jcr:content", "nt:resource");
					}

					// Set content properties
					Calendar now = Calendar.getInstance();
					try (InputStream in = new ByteArrayInputStream(content)) {
						JCRs.write(fileNode, in);
					}
					contentNode.setProperty("jcr:mimeType", mimeType);
					contentNode.setProperty("jcr:lastModified", now);
					contentNode.setProperty("jcr:lastModifiedBy", session.getUserID());

					session.save();

					// Set result path in exchange header
					exchange.getIn().setHeader("CmsStoredPath", fileNode.getPath());
				}
			}
		}

		/**
		 * Producer for setting properties on JCR nodes
		 * URI format: cms:setProperties?path=/content/file.txt&headerPrefix=commerce_
		 */
		private class SetPropertiesProducer extends DefaultProducer {
			private SetPropertiesProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					Session session = Scripts.getJcrSession(context);
					ValueFactory vf = session.getValueFactory();

					// Get path from endpoint parameters or exchange headers
					String path = getParameter(exchange, "path");
					// Get header prefix (default: cms_)
					String prefix = getParameter(exchange, "headerPrefix");
					if (prefix == null || prefix.trim().isEmpty()) {
						prefix = "cms_";
					}

					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					Node node = session.getNode(path);
					Node targetNode = getTargetNode(node);

					// Set properties from exchange headers
					Map<String, Object> headers = exchange.getIn().getHeaders();
					for (Map.Entry<String, Object> entry : headers.entrySet()) {
						String headerName = entry.getKey();
						if (headerName.startsWith(prefix)) {
							String propertyName = headerName.substring(prefix.length());
							Object value = entry.getValue();

							if (value == null) {
								// Remove property if header value is null
								if (targetNode.hasProperty(propertyName)) {
									targetNode.getProperty(propertyName).remove();
								}
								continue;
							}

							// Handle single/multi-value conversion
							try {
								// Check if we need to remove existing property of different type
								if (targetNode.hasProperty(propertyName)) {
									boolean currentIsMultiple = targetNode.getProperty(propertyName).isMultiple();
									boolean newIsMultiple = (value instanceof Collection) || value.getClass().isArray();

									// If type changed (single ↔ multiple), remove old property first
									if (currentIsMultiple != newIsMultiple) {
										targetNode.getProperty(propertyName).remove();
									}
								}

								// Set property based on type
								if (value instanceof Collection || value.getClass().isArray()) {
									targetNode.setProperty(propertyName, toJcrValues(value, vf));
								} else {
									targetNode.setProperty(propertyName, toJcrValue(value, vf));
								}
							} catch (Exception e) {
								// If setting property fails, log and continue
								// (or you can throw the exception to fail the entire operation)
								throw new RepositoryException("Failed to set property '" + propertyName + "': " + e.getMessage(), e);
							}
						}
					}

					session.save();
				}
			}

			/**
			 * Convert single value to JCR Value
			 */
			private Value toJcrValue(Object value, ValueFactory vf) throws RepositoryException {
				// Calendar types
				if (value instanceof Calendar) {
					return vf.createValue((Calendar) value);
				}
				if (value instanceof Date) {
					Calendar cal = Calendar.getInstance();
					cal.setTime((Date) value);
					return vf.createValue(cal);
				}
				if (value instanceof ZonedDateTime) {
					return vf.createValue(GregorianCalendar.from((ZonedDateTime) value));
				}
				if (value instanceof OffsetDateTime) {
					return vf.createValue(GregorianCalendar.from(((OffsetDateTime) value).toZonedDateTime()));
				}
				if (value instanceof LocalDateTime) {
					return vf.createValue(GregorianCalendar.from(((LocalDateTime) value).atZone(ZoneId.systemDefault())));
				}
				if (value instanceof Instant) {
					return vf.createValue(GregorianCalendar.from(((Instant) value).atZone(ZoneId.systemDefault())));
				}

				// Numeric types
				if (value instanceof Long || value instanceof Integer) {
					return vf.createValue(((Number) value).longValue());
				}
				if (value instanceof Double || value instanceof Float) {
					return vf.createValue(((Number) value).doubleValue());
				}
				if (value instanceof BigDecimal) {
					return vf.createValue((BigDecimal) value);
				}

				// Boolean type
				if (value instanceof Boolean) {
					return vf.createValue((Boolean) value);
				}

				// Default: String
				return vf.createValue(value.toString());
			}

			/**
			 * Convert array/collection to JCR Value array
			 */
			private Value[] toJcrValues(Object value, ValueFactory vf) throws RepositoryException {
				List<Value> values = new ArrayList<>();

				if (value instanceof Collection) {
					for (Object item : (Collection<?>) value) {
						if (item != null) {
							values.add(toJcrValue(item, vf));
						}
					}
				} else if (value.getClass().isArray()) {
					int length = Array.getLength(value);
					for (int i = 0; i < length; i++) {
						Object item = Array.get(value, i);
						if (item != null) {
							values.add(toJcrValue(item, vf));
						}
					}
				}

				return values.toArray(new Value[0]);
			}

			/**
			 * Get target node for property setting
			 */
			private Node getTargetNode(Node node) throws RepositoryException {
				if (node.isNodeType("nt:file") && node.hasNode("jcr:content")) {
					return node.getNode("jcr:content");
				}
				return node;
			}
		}

		/**
		 * Producer for moving nodes in JCR
		 * URI format: cms:move?sourcePath=/content/old&destPath=/content/new
		 */
		private class MoveProducer extends DefaultProducer {
			private MoveProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					Session session = Scripts.getJcrSession(context);

					// Get parameters from endpoint parameters or exchange headers
					String sourcePath = getParameter(exchange, "sourcePath");
					String destPath = getParameter(exchange, "destPath");
					String newName = getParameter(exchange, "name"); // Optional

					if (sourcePath == null || sourcePath.trim().isEmpty()) {
						throw new IllegalArgumentException("sourcePath parameter is required");
					}
					if (destPath == null || destPath.trim().isEmpty()) {
						throw new IllegalArgumentException("destPath parameter is required");
					}

					if (!session.nodeExists(sourcePath)) {
						throw new PathNotFoundException("Source node not found: " + sourcePath);
					}
					if (!session.nodeExists(destPath)) {
						throw new PathNotFoundException("Destination node not found: " + destPath);
					}

					Node sourceNode = session.getNode(sourcePath);
					Node destParentNode = session.getNode(destPath);

					// Check if destination is a folder-like node
					if (!destParentNode.isNodeType("nt:folder") && !destParentNode.isNodeType("nt:unstructured")) {
						throw new IllegalArgumentException("Destination must be a folder node: " + destPath);
					}

					// Determine the name to use
					String nodeName = (newName != null && !newName.trim().isEmpty()) ? newName : sourceNode.getName();

					// Build target path
					String targetPath;
					if ("/".equals(destPath)) {
						targetPath = "/" + nodeName;
					} else {
						targetPath = destPath + "/" + nodeName;
					}

					// Check if target path already exists
					if (session.nodeExists(targetPath)) {
						throw new IllegalArgumentException("Node already exists at destination: " + targetPath);
					}

					// Move node
					session.move(sourcePath, targetPath);
					session.save();

					// Set result path in exchange header
					exchange.getIn().setHeader("CmsMovedPath", targetPath);
				}
			}
		}

		/**
		 * Get parameter value from endpoint parameters or exchange headers
		 */
		private String getParameter(Exchange exchange, String key) {
			// First check endpoint parameters
			if (fParameters.containsKey(key)) {
				Object value = fParameters.get(key);
				return value != null ? value.toString() : null;
			}

			// Then check exchange headers
			Object headerValue = exchange.getIn().getHeader(key);
			return headerValue != null ? headerValue.toString() : null;
		}
	}
}
