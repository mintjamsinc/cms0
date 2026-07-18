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
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockException;
import javax.jcr.lock.LockManager;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;

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
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.security.ServiceUserCredentials;
import org.mintjams.script.resource.Resource;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Strings;

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
		parameters.clear(); // All parameters are consumed internally by CmsEndpoint
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
			if ("load".equals(fOperation)) {
				return new LoadProducer();
			}
			if ("loadAsString".equals(fOperation)) {
				return new LoadAsStringProducer();
			}
			if ("store".equals(fOperation)) {
				return new StoreProducer();
			}
			if ("getProperties".equals(fOperation)) {
				return new GetPropertiesProducer();
			}
			if ("setProperties".equals(fOperation)) {
				return new SetPropertiesProducer();
			}
			if ("move".equals(fOperation)) {
				return new MoveProducer();
			}
			if ("exists".equals(fOperation)) {
				return new ExistsProducer();
			}
			if ("addVersionControl".equals(fOperation)) {
				return new AddVersionControlProducer();
			}
			if ("checkout".equals(fOperation)) {
				return new CheckoutProducer();
			}
			if ("checkin".equals(fOperation)) {
				return new CheckinProducer();
			}
			if ("uncheckout".equals(fOperation)) {
				return new UncheckoutProducer();
			}
			if ("checkpoint".equals(fOperation)) {
				return new CheckpointProducer();
			}
			if ("lock".equals(fOperation)) {
				return new LockProducer();
			}
			if ("unlock".equals(fOperation)) {
				return new UnlockProducer();
			}
			if ("script".equals(fOperation)) {
				return new ScriptProducer(fOperation);
			}

			// Default: script execution (backward compatibility)
			return new ScriptProducer(fOperation);
		}

		private abstract class CmsProducer extends DefaultProducer {
			protected CmsProducer(Endpoint endpoint) {
				super(endpoint);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				try (ProcessContext context = new ProcessContext(exchange)) {
					doProcess(context);
				}
			}

			protected abstract void doProcess(ProcessContext context) throws Exception;

			/**
			 * Handle a conflict according to the given behavior.
			 */
			protected boolean handleConflict(CmsConflictBehavior behavior, String message) {
				switch (behavior) {
					case IGNORE:
						return true;
					case WARN:
						CmsService.getLogger(getClass()).warn(message);
						return true;
					case FAIL:
					default:
						throw new IllegalStateException(message);
				}
			}

			/**
			 * ProcessContext provides convenient access to endpoint parameters and exchange data for producers.
			 */
			protected class ProcessContext implements Closeable {
				private final Exchange fExchange;

				protected ProcessContext(Exchange exchange) {
					fExchange = exchange;
				}

				public Exchange getExchange() {
					return fExchange;
				}

				/**
				 * Get parameter value from endpoint parameters or exchange headers.
				 */
				public Object getParameter(String name) {
					if (fParameters.containsKey(name)) {
						return fParameters.get(name);
					}

					return fExchange.getIn().getHeader(name);
				}

				/**
				 * Get all parameter names from endpoint parameters and exchange headers.
				 */
				public List<String> getParameterNames() {
					List<String> names = new ArrayList<>(fParameters.keySet());
					for (String headerName : fExchange.getIn().getHeaders().keySet()) {
						if (!names.contains(headerName)) {
							names.add(headerName);
						}
					}
					return names;
				}

				/**
				 * Resolve the CmsConflictBehavior parameter.
				 */
				public CmsConflictBehavior getConflictBehavior(CmsConflictBehavior defaultValue) {
					String value = (String) getParameter("conflictBehavior");
					return CmsConflictBehavior.of(value, defaultValue);
				}

				/**
				 * Parse a filter parameter into a list of strings.
				 * Supports comma-separated strings, lists, and collections.
				 */
				public List<String> parseFilterList(Object filter) {
					if (filter == null) {
						return Collections.emptyList();
					}
					if (filter instanceof List) {
						return ((List<?>) filter).stream()
								.map(Object::toString)
								.map(String::trim)
								.collect(Collectors.toList());
					}
					if (filter instanceof String) {
						return List.of(((String) filter).split("\\s*,\\s*"));
					}
					if (filter instanceof Collection<?>) {
						return ((Collection<?>) filter).stream()
								.map(Object::toString)
								.map(String::trim)
								.collect(Collectors.toList());
					}
					return List.of(filter.toString().trim());
				}

				/**
				 * Set a header in the exchange for downstream processing.
				 * This method can be used by producers to set headers based on their processing logic, which can then be consumed by other processors or components in the route.
				 */
				public void setHeader(String key, Object value) {
					fExchange.getIn().setHeader(key, value);
				}

				/**
				 * Set the message body in the exchange for downstream processing.
				 */
				public void setBody(Object value) {
					fExchange.getIn().setBody(value);
				}

				/**
				 * Set an exchange property for downstream processing.
				 */
				public void setProperty(String key, Object value) {
					fExchange.setProperty(key, value);
				}

				/**
				 * A single output binding describing how a named result source should be placed
				 * back into the exchange (body, a header, or an exchange property).
				 */
				private final class ResultBinding {
					private final String kind; // "body", "header" or "property"
					private final String target; // header/property name (null for body)
					private final String sourceName;

					private ResultBinding(String kind, String target, String sourceName) {
						this.kind = kind;
						this.target = target;
						this.sourceName = sourceName;
					}
				}

				/**
				 * Collect output bindings declared either as endpoint (URL) parameters or as exchange headers.
				 *
				 * Binding syntax:
				 *   "@body=sourceName"          — set the message body to the named source value
				 *   "@header.headerName=source" — set the header "headerName" to the named source value
				 *   "@property.propName=source" — set the exchange property "propName" to the named source value
				 *
				 * Endpoint parameters take precedence over exchange headers for the same binding key,
				 * so a binding may be supplied through either channel (URL parameter first, header as fallback).
				 */
				private List<ResultBinding> collectBindings() {
					Map<String, Object> declared = new LinkedHashMap<>();
					for (Map.Entry<String, Object> entry : fParameters.entrySet()) {
						if (isBindingKey(entry.getKey())) {
							declared.put(entry.getKey(), entry.getValue());
						}
					}
					for (Map.Entry<String, Object> entry : fExchange.getIn().getHeaders().entrySet()) {
						if (isBindingKey(entry.getKey()) && !declared.containsKey(entry.getKey())) {
							declared.put(entry.getKey(), entry.getValue());
						}
					}

					List<ResultBinding> bindings = new ArrayList<>();
					for (Map.Entry<String, Object> entry : declared.entrySet()) {
						String key = entry.getKey();
						String sourceName = (entry.getValue() == null) ? null : entry.getValue().toString().trim();
						if (key.equals("@body")) {
							bindings.add(new ResultBinding("body", null, sourceName));
						} else if (key.startsWith("@header.")) {
							bindings.add(new ResultBinding("header", key.substring("@header.".length()), sourceName));
						} else if (key.startsWith("@property.")) {
							bindings.add(new ResultBinding("property", key.substring("@property.".length()), sourceName));
						}
					}
					return bindings;
				}

				/**
				 * Whether the given key declares an output binding (@body / @header.x / @property.x).
				 */
				private boolean isBindingKey(String key) {
					if (Strings.isEmpty(key)) {
						return false;
					}
					return key.equals("@body") || key.startsWith("@header.") || key.startsWith("@property.");
				}

				/**
				 * The set of result source names referenced by the declared output bindings.
				 * Callers use this to compute only the sources that are actually needed.
				 * <p>
				 * Because bindings may be declared as exchange headers, this set can include names left
				 * over from a binding set for a previously invoked producer in the same route. Callers must
				 * therefore test for the specific source names they support (rather than treating a
				 * non-empty set as "something was requested"), so that a foreign leftover binding never
				 * changes this producer's behaviour.
				 */
				public Set<String> getBoundSourceNames() {
					Set<String> names = new HashSet<>();
					for (ResultBinding binding : collectBindings()) {
						names.add(binding.sourceName);
					}
					return names;
				}

				/**
				 * Bind named result values to the exchange according to the declared output bindings.
				 * Only bindings whose source name is present in {@code sources} take effect; bindings that
				 * reference an unknown source (for example, a binding left over from a previously invoked
				 * producer) are ignored. Output is produced solely through these bindings — there is no
				 * implicit default such as placing the result in a fixed header.
				 */
				public void applyResultBindings(Map<String, Object> sources) {
					for (ResultBinding binding : collectBindings()) {
						if (!sources.containsKey(binding.sourceName)) {
							continue;
						}

						Object value = sources.get(binding.sourceName);
						if ("body".equals(binding.kind)) {
							setBody(value);
						} else if ("header".equals(binding.kind)) {
							setHeader(binding.target, value);
						} else {
							setProperty(binding.target, value);
						}
					}
				}

				@Override
				public void close() throws IOException {
					// No resources to close in this implementation, but this is where you would clean up any per-invocation state if needed.
				}
			}
		}

		/**
		 * Producer for script execution (backward compatibility)
		 */
		private class ScriptProducer extends CmsProducer {
			private final String fPath;

			private ScriptProducer(String path) {
				super(CmsEndpoint.this);
				fPath = path;
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					context.setAttribute("exchange", pc.getExchange());
					Scripts.prepareAPIs(context);

					String resourcePath = fPath.startsWith("/") ? fPath : "/" + fPath;
					Resource resource = context.getRepositorySession().getResource(resourcePath);

					// Check if resource exists and is readable
					if (!resource.exists()) {
						throw new PathNotFoundException("Resource not found: " + resourcePath);
					}
					if (!resource.canRead()) {
						throw new AccessDeniedException("Cannot read resource: " + resourcePath);
					}

					// Determine script engine by resource name
					String resourceName = resource.getName();
					String scriptExtension = null;
					for (String extension : Scripts.getScriptExtensions(context)) {
						if (resourceName.endsWith("." + extension)) {
							scriptExtension = extension;
							break;
						}
					}
					if (scriptExtension == null) {
						throw new IllegalStateException("No script engine found for resource: " + resourcePath);
					}

					// Set script context attributes based on input filters
					Map<String, Object> headers = pc.getExchange().getIn().getHeaders();
					for (String filter : pc.parseFilterList(pc.getParameter("inputs"))) {
						if (filter.indexOf("=") > 0) {
							// Support inline key=value pairs in inputs parameter
							String[] parts = filter.split("=", 2);
							String attributeName = parts[0].trim();
							String headerName = parts[1].trim();
							if (Objects.equals(headerName.toLowerCase(), "@body")) {
								context.setAttribute(attributeName, pc.getExchange().getIn().getBody());
								continue;
							}
							if (headers.containsKey(headerName)) {
								context.setAttribute(attributeName, headers.get(headerName));
							}
							continue;
						}

						if (filter.endsWith("*")) {
							String prefix = filter.substring(0, filter.length() - 1);
							headers.entrySet().stream()
									.filter(entry -> entry.getKey().startsWith(prefix))
									.forEach(entry -> context.setAttribute(entry.getKey(), entry.getValue()));
						} else if (filter.startsWith("*")) {
							String suffix = filter.substring(1);
							headers.entrySet().stream()
									.filter(entry -> entry.getKey().endsWith(suffix))
									.forEach(entry -> context.setAttribute(entry.getKey(), entry.getValue()));
						} else if (filter.endsWith("~")) {
							String prefix = filter.substring(0, filter.length() - 1);
							headers.entrySet().stream()
									.filter(entry -> entry.getKey().startsWith(prefix))
									.forEach(entry -> context.setAttribute(entry.getKey().substring(prefix.length()), entry.getValue()));
						} else if (filter.startsWith("~")) {
							String suffix = filter.substring(1);
							headers.entrySet().stream()
									.filter(entry -> entry.getKey().endsWith(suffix))
									.forEach(entry -> context.setAttribute(entry.getKey().substring(0, entry.getKey().length() - suffix.length()), entry.getValue()));
						} else {
							Object value = headers.get(filter);
							if (value != null) {
								context.setAttribute(filter, value);
							}
						}
					}

					// Also set the resource itself in the context for direct access
					context.setAttribute("resource", resource);

					// Evaluate the script
					try (ScriptReader scriptReader = new ScriptReader(resource.getContentAsReader())) {
						scriptReader
								.setScriptName("jcr://" + resource.getPath())
								.setExtension(scriptExtension)
								.setLastModified(resource.getLastModified())
								.setScriptEngineManager(Scripts.getScriptEngineManager(context))
								.setClassLoader(Scripts.getClassLoader(context))
								.setScriptContext(context)
								.eval();
					}

					// Set headers based on output filters
					for (String filter : pc.parseFilterList(pc.getParameter("outputs"))) {
						if (filter.indexOf("=") > 0) {
							// Support inline key=value pairs in outputs parameter
							String[] parts = filter.split("=", 2);
							String headerName = parts[0].trim();
							String attributeName = parts[1].trim();
							if (context.hasAttribute(attributeName)) {
								pc.setHeader(headerName, context.getAttribute(attributeName));
							}
							continue;
						}

						if (filter.endsWith("*")) {
							String prefix = filter.substring(0, filter.length() - 1);
							context.getAttributeNames().stream()
									.filter(name -> name.startsWith(prefix))
									.forEach(name -> pc.setHeader(name, context.getAttribute(name)));
						} else if (filter.startsWith("*")) {
							String suffix = filter.substring(1);
							context.getAttributeNames().stream()
									.filter(name -> name.endsWith(suffix))
									.forEach(name -> pc.setHeader(name, context.getAttribute(name)));
						} else if (filter.endsWith("~")) {
							String prefix = filter.substring(0, filter.length() - 1);
							context.getAttributeNames().stream()
									.filter(name -> name.startsWith(prefix))
									.forEach(name -> pc.setHeader(name.substring(prefix.length()), context.getAttribute(name)));
						} else if (filter.startsWith("~")) {
							String suffix = filter.substring(1);
							context.getAttributeNames().stream()
									.filter(name -> name.endsWith(suffix))
									.forEach(name -> pc.setHeader(name.substring(0, name.length() - suffix.length()), context.getAttribute(name)));
						} else {
							Object value = context.getAttribute(filter);
							if (value != null) {
								pc.setHeader(filter, value);
							}
						}
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
		 * Producer for loading files from JCR as byte array
		 *
		 * Exposes the loaded content (byte[]) through the declared output bindings (source name: "content"),
		 * for example {@code @body=content}, {@code @header.headerName=content} or
		 * {@code @property.propName=content}. Output is produced solely through these bindings.
		 *
		 * URI format: cms:load?path=/content/file.txt&@body=content
		 * Parameters:
		 *   - path: Source file path (required)
		 *   - runAs: User to impersonate (optional)
		 *   - Output binding: @body=content, @header.headerName=content, or @property.propName=content
		 */
		private class LoadProducer extends CmsProducer {
			private LoadProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					// Get parameters from endpoint parameters or exchange headers
					String path = (String) pc.getParameter("path");

					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					Node node = session.getNode(path);
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					try(InputStream in = JCRs.getContentAsStream(node)) {
						IOs.copy(in, out);
					}

					// Expose the loaded content through the declared output bindings (source name: "content")
					pc.applyResultBindings(Map.of("content", out.toByteArray()));
				}
			}
		}

		/**
		 * Producer for loading files from JCR as string (with optional encoding)
		 *
		 * Exposes the loaded content (String) through the declared output bindings (source name: "content"),
		 * for example {@code @body=content}, {@code @header.headerName=content} or
		 * {@code @property.propName=content}. Output is produced solely through these bindings.
		 *
		 * URI format: cms:loadAsString?path=/content/file.txt&@body=content
		 * Parameters:
		 *   - path: Source file path (required)
		 *   - runAs: User to impersonate (optional)
		 *   - Output binding: @body=content, @header.headerName=content, or @property.propName=content
		 */
		private class LoadAsStringProducer extends CmsProducer {
			private LoadAsStringProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					// Get parameters from endpoint parameters or exchange headers
					String path = (String) pc.getParameter("path");

					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					Node node = session.getNode(path);
					String content = JCRs.getContentAsString(node);

					// Expose the loaded content through the declared output bindings (source name: "content")
					pc.applyResultBindings(Map.of("content", content));
				}
			}
		}

		/**
		 * Producer for storing files to JCR
		 *
		 * Exposes the stored path through the declared output bindings (source name: "path"),
		 * for example {@code @header.cmsStoredPath=path}, {@code @body=path} or
		 * {@code @property.storedPath=path}. Output is produced solely through these bindings.
		 *
		 * URI format: cms:store?path=/content/file.txt&mimeType=application/json&createParents=true&@header.cmsStoredPath=path
		 * Parameters:
		 *   - path: Target file path (required)
		 *   - mimeType: MIME type (default: application/octet-stream). A value
		 *     containing "+" (e.g. a "+json" structured-syntax suffix) MUST be
		 *     wrapped as RAW(...): Camel normalizes a %2B escape back to "+" and
		 *     then form-decodes the query, so both a literal and an encoded plus
		 *     otherwise reach this endpoint as a space.
		 *   - encoding: Optional encoding for string content (e.g., "UTF-8")
		 *   - createParents: Auto-create parent folders (default: true)
		 *   - source: Header name to get content from (default: @body for exchange body)
		 *   - Output binding: @body=path, @header.headerName=path, or @property.propName=path
		 */
		private class StoreProducer extends CmsProducer {
			private StoreProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					// Get parameters from endpoint parameters or exchange headers
					String path = (String) pc.getParameter("path");
					String mimeType = (String) pc.getParameter("mimeType");
					String encoding = (String) pc.getParameter("encoding");
					String createParentsStr = (String) pc.getParameter("createParents");
					String source = (String) pc.getParameter("source");

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
					if (source == null || source.trim().isEmpty()) {
						source = "@body"; // Default to exchange body if source parameter is not provided
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
						fileNode = JCRs.createFile(parentNode, fileName);
					}

					// Write content to jcr:content node
					if (source.equalsIgnoreCase("@body")) {
						// Get content from exchange body
						byte[] content = pc.getExchange().getIn().getBody(byte[].class);
						if (content == null) {
							throw new IllegalArgumentException("Exchange body is empty");
						}

						try (InputStream in = new ByteArrayInputStream(content)) {
							JCRs.write(fileNode, in);
						}
					} else {
						// Get content from specified header
						Object headerValue = pc.getExchange().getIn().getHeader(source);
						if (headerValue == null) {
							throw new IllegalArgumentException("Source header '" + source + "' is empty");
						}

						byte[] content;
						if (headerValue instanceof byte[]) {
							content = (byte[]) headerValue;
						} else if (headerValue instanceof String) {
							content = ((String) headerValue).getBytes(encoding != null ? encoding : StandardCharsets.UTF_8.name());
						} else {
							throw new IllegalArgumentException("Unsupported source header type: " + headerValue.getClass().getName());
						}

						try (InputStream in = new ByteArrayInputStream(content)) {
							JCRs.write(fileNode, in);
						}
					}

					// Set content properties
					Calendar now = Calendar.getInstance();
					JCRs.setProperty(fileNode, "jcr:mimeType", mimeType);
					if (encoding != null) {
						JCRs.setProperty(fileNode, "jcr:encoding", encoding);
					}
					JCRs.setProperty(fileNode, "jcr:lastModified", now);
					JCRs.setProperty(fileNode, "jcr:lastModifiedBy", session.getUserID());

					session.save();

					// Expose the stored path through the declared output bindings (source name: "path")
					pc.applyResultBindings(Map.of("path", fileNode.getPath()));
				}
			}
		}

		/**
		 * Producer for retrieving properties from a JCR node and setting them as exchange headers.
		 *
		 * URI format: cms:getProperties?path=/content/file.txt&includes=prop*&excludes=prop3
		 * Parameters:
		 *   - path: Source node path (required)
		 *   - includes: Comma-separated list of property names to include (supports wildcard patterns, default: include all)
		 *   - excludes: Comma-separated list of property names to exclude (supports wildcard patterns, default: exclude none)
		 *   - runAs: User to impersonate (optional)
		 *   - Header mapping: Use @header.propertyName=propertyName to map a property to a specific header, or @header.*=prop1,prop2 for multiple properties with same name, or @header.prefix*=prop1,prop2 for common prefix, or @header.*suffix=prop1,prop2 for common suffix. Use @body=propertyName to set the exchange body from a property.
		 */
		private class GetPropertiesProducer extends CmsProducer {
			private GetPropertiesProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					// Get parameters from endpoint parameters or exchange headers
					String path = (String) pc.getParameter("path");
					// Get include/exclude filters from endpoint parameters or exchange headers
					List<String> includes = pc.parseFilterList(pc.getParameter("includes"));
					List<String> excludes = pc.parseFilterList(pc.getParameter("excludes"));

					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					Node node = session.getNode(path);
					Node contentNode = JCRs.getContentNode(node);

					// Apply include filters first to determine which properties to set, then apply exclude filters to skip any excluded properties.
					// This allows for flexible combinations of includes and excludes.
					for (PropertyIterator i = contentNode.getProperties(); i.hasNext();) {
						Property property = i.nextProperty();
						String propertyName = property.getName();
						if (!matches(propertyName, includes) || matches(propertyName, excludes)) {
							continue; // Skip excluded properties
						}
						pc.setHeader(propertyName, getPropertyValue(contentNode, propertyName));
					}

					// Support "@header." prefix for mapping properties to headers, and "@body" for setting the exchange body from a property.
					for (String name : pc.getParameterNames()) {
						if (name.toLowerCase().startsWith("@header.")) {
							String headerName = name.substring(8); // Remove "@header." prefix
							if (headerName.equals("")) {
								continue; // Skip if no header name is specified
							} else if (headerName.equals("*")) {
								// @header.*=propertyName1,propertyName2 syntax for mapping multiple properties with same name
								// @header.*=* syntax for mapping all properties with same name
								// @header.* syntax for mapping all properties with same name (fallback if parameter value is empty)
								String filter = Strings.defaultIfEmpty((String) pc.getParameter(name), "*").trim();
								if (filter.equals("*")) {
									for (PropertyIterator i = contentNode.getProperties(); i.hasNext();) {
										Property property = i.nextProperty();
										String propertyName = property.getName();
										if (matches(propertyName, excludes)) {
											continue; // Skip excluded properties
										}
										pc.setHeader(propertyName, getPropertyValue(contentNode, propertyName));
									}
								} else {
									for (String propertyName : pc.parseFilterList(filter)) {
										if (matches(propertyName, excludes)) {
											continue; // Skip excluded properties
										}
										pc.setHeader(propertyName, getPropertyValue(contentNode, propertyName));
									}
								}
							} else if (headerName.endsWith("*")) {
								// @header.headerName*=propertyName1,propertyName2 syntax for mapping multiple properties with common prefix
								// @header.headerName*=* syntax for mapping all properties with common prefix
								// @header.headerName* syntax for mapping all properties with common prefix (fallback if parameter value is empty)
								String filter = Strings.defaultIfEmpty((String) pc.getParameter(name), "*").trim();
								String prefix = headerName.substring(0, headerName.length() - 1);
								if (filter.equals("*")) {
									for (PropertyIterator i = contentNode.getProperties(); i.hasNext();) {
										Property property = i.nextProperty();
										String propertyName = property.getName();
										if (matches(propertyName, excludes)) {
											continue; // Skip excluded properties
										}
										pc.setHeader(prefix + propertyName, getPropertyValue(contentNode, propertyName));
									}
								} else {
									for (String propertyName : pc.parseFilterList(filter)) {
										if (matches(propertyName, excludes)) {
											continue; // Skip excluded properties
										}
										pc.setHeader(prefix + propertyName, getPropertyValue(contentNode, propertyName));
									}
								}
							} else if (headerName.startsWith("*")) {
								// @header.*headerName=propertyName1,propertyName2 syntax for mapping multiple properties with common suffix
								// @header.*headerName=* syntax for mapping all properties with common suffix
								// @header.*headerName syntax for mapping all properties with common suffix (fallback if parameter value is empty)
								String filter = Strings.defaultIfEmpty((String) pc.getParameter(name), "*").trim();
								String suffix = headerName.substring(1);
								if (filter.equals("*")) {
									for (PropertyIterator i = contentNode.getProperties(); i.hasNext();) {
										Property property = i.nextProperty();
										String propertyName = property.getName();
										if (matches(propertyName, excludes)) {
											continue; // Skip excluded properties
										}
										pc.setHeader(propertyName + suffix, getPropertyValue(contentNode, propertyName));
									}
								} else {
									for (String propertyName : pc.parseFilterList(pc.getParameter(name))) {
										if (matches(propertyName, excludes)) {
											continue; // Skip excluded properties
										}
										pc.setHeader(propertyName + suffix, getPropertyValue(contentNode, propertyName));
									}
								}
							} else {
								// @header.headerName=propertyName syntax for direct mapping
								// @header.headerName syntax for same name mapping
								String propertyName = (String) pc.getParameter(name);
								if (propertyName == null || propertyName.trim().isEmpty()) {
									propertyName = headerName; // Fallback to header name if parameter value is empty
								}
								if (matches(propertyName, excludes)) {
									continue; // Skip excluded properties
								}
								pc.setHeader(headerName, getPropertyValue(contentNode, propertyName));
							}
						} else if (name.equalsIgnoreCase("@body")) {
							String propertyName = (String) pc.getParameter(name);
							Object value = getPropertyValue(contentNode, propertyName);
							pc.getExchange().getIn().setBody(value);
						}
					}
				}
			}

			/**
			 * Check if a property name matches any of the provided filters.
			 */
			private boolean matches(String propertyName, List<String> filters) {
				for (String filter : filters) {
					if (filter.endsWith("*")) {
						String prefix = filter.substring(0, filter.length() - 1);
						if (propertyName.startsWith(prefix)) {
							return true;
						}
					} else if (filter.startsWith("*")) {
						String suffix = filter.substring(1);
						if (propertyName.endsWith(suffix)) {
							return true;
						}
					} else if (filter.endsWith("~")) {
						String prefix = filter.substring(0, filter.length() - 1);
						if (propertyName.startsWith(prefix)) {
							return true;
						}
					} else if (filter.startsWith("~")) {
						String suffix = filter.substring(1);
						if (propertyName.endsWith(suffix)) {
							return true;
						}
					} else {
						if (propertyName.equals(filter)) {
							return true;
						}
					}
				}
				return false;
			}

			/**
			 * Get property value from a JCR node with proper type handling.
			 * Returns null if the property does not exist.
			 */
			private Object getPropertyValue(Node node, String propertyName) throws RepositoryException {
				if (!node.hasProperty(propertyName)) {
					return null;
				}

				Property property = node.getProperty(propertyName);
				if (property.isMultiple()) {
					// Handle multi-value properties as lists
					List<Object> values = new ArrayList<>();
					for (Value v : property.getValues()) {
						values.add(getSingleValue(v));
					}
					return values;
				} else {
					return getSingleValue(property.getValue());
				}
			}

			/**
			 * Convert a single JCR Value to an appropriate Java type.
			 */
			private Object getSingleValue(Value value) throws RepositoryException {
				switch (value.getType()) {
					case PropertyType.STRING:
						return value.getString();
					case PropertyType.BOOLEAN:
						return value.getBoolean();
					case PropertyType.DATE:
						return value.getDate();
					case PropertyType.DOUBLE:
						return value.getDouble();
					case PropertyType.LONG:
						return value.getLong();
					case PropertyType.DECIMAL:
						return value.getDecimal();
					default:
						return value.getString(); // Fallback to string representation for unsupported types
				}
			}
		}

		/**
		 * Producer for setting properties on JCR nodes
		 *
		 * Exchange headers starting with the specified prefix are converted to JCR properties.
		 * For nt:file nodes, properties are set on the jcr:content child node.
		 *
		 * Supported types:
		 *   - Date/Time: Calendar, Date, ZonedDateTime, OffsetDateTime, LocalDateTime, Instant
		 *   - Numeric: Long, Integer, Double, Float, BigDecimal
		 *   - Boolean, String
		 *   - Arrays and Collections (multi-value properties)
		 *
		 * Null header values remove the corresponding property.
		 *
		 * URI format: cms:setProperties?path=/content/file.txt&includes=commerce_~
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - includes: Header prefix to filter. Resolved in order:
		 *       1. Endpoint parameter (URI query)
		 *       2. Exchange header
		 *    - excludes: Header prefix to exclude (same resolution order as includes)
		 *    - delimiter: Delimiter for nested properties when header value is a map (default: dot)
		 *   - runAs: User to impersonate (optional)
		 *
		 * Example:
		 *   - includes=commerce_~ will set all headers starting with "commerce_" as properties without the prefix
		 *   - includes=customHeader will set the "customHeader" header as a property with the same name
		 *   - includes=customProperty=customHeader will set the "customProperty" JCR property from the "customHeader" exchange header
		 *   - excludes=commerce_secret* will exclude any headers starting with "commerce_secret" from being set as properties
		 */
		private class SetPropertiesProducer extends CmsProducer {
			private SetPropertiesProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);
					ValueFactory vf = session.getValueFactory();

					// Get path from endpoint parameters or exchange headers
					String path = (String) pc.getParameter("path");
					// Get include/exclude filters from endpoint parameters or exchange headers
					List<String> includes = pc.parseFilterList(pc.getParameter("includes"));
					List<String> excludes = pc.parseFilterList(pc.getParameter("excludes"));
					// Optional delimiter for nested properties when header value is a map (default: dot)
					String delimiter = (String) pc.getParameter("delimiter");
					if (delimiter == null || delimiter.trim().isEmpty()) {
						delimiter = ".";
					}

					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					Node node = session.getNode(path);
					Node contentNode = JCRs.getContentNode(node);

					// Set properties from exchange headers
					Map<String, Object> headers = pc.getExchange().getIn().getHeaders();
					for (String filter : includes) {
						if (filter.indexOf("=") > 0) {
							// Support inline key=value pairs in includes parameter
							String[] parts = filter.split("=", 2);
							String propertyName = parts[0].trim();
							String headerName = parts[1].trim();
							if (Objects.equals(headerName.toLowerCase(), "@body")) {
								setProperty(contentNode, propertyName, pc.getExchange().getIn().getBody(), vf);
								continue;
							}
							if (headers.containsKey(headerName) && !matches(headerName, excludes)) {
								setProperty(contentNode, propertyName, headers.get(headerName), vf);
							}
							continue;
						}

						if (filter.endsWith("*")) {
							String prefix = filter.substring(0, filter.length() - 1);
							for (Map.Entry<String, Object> entry : headers.entrySet()) {
								if (entry.getKey().startsWith(prefix) && !matches(entry.getKey(), excludes)) {
									setProperty(contentNode, entry.getKey(), entry.getValue(), vf);
								}
							}
						} else if (filter.startsWith("*")) {
							String suffix = filter.substring(1);
							for (Map.Entry<String, Object> entry : headers.entrySet()) {
								if (entry.getKey().endsWith(suffix) && !matches(entry.getKey(), excludes)) {
									setProperty(contentNode, entry.getKey(), entry.getValue(), vf);
								}
							}
						} else if (filter.endsWith("~")) {
							String prefix = filter.substring(0, filter.length() - 1);
							for (Map.Entry<String, Object> entry : headers.entrySet()) {
								if (entry.getKey().startsWith(prefix) && !matches(entry.getKey(), excludes)) {
									setProperty(contentNode, entry.getKey().substring(prefix.length()), entry.getValue(), vf);
								}
							}
						} else if (filter.startsWith("~")) {
							String suffix = filter.substring(1);
							for (Map.Entry<String, Object> entry : headers.entrySet()) {
								if (entry.getKey().endsWith(suffix) && !matches(entry.getKey(), excludes)) {
									setProperty(contentNode, entry.getKey().substring(0, entry.getKey().length() - suffix.length()), entry.getValue(), vf);
								}
							}
						} else {
							if (matches(filter, excludes)) {
								continue; // Skip if explicitly excluded
							}

							if (!headers.containsKey(filter)) {
								continue; // Skip if header not present
							}

							Object value = headers.get(filter);
							if (value instanceof Map) {
								// Support nested properties for map values (e.g., "commerce_product" header with value {"name": "Product A", "price": 10} sets "commerce_product.name" and "commerce_product.price" properties)
								// delimiter is dot (.) to match common conventions, but you can choose a different one if needed
								Map<?, ?> mapValue = (Map<?, ?>) value;
								for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
									String propertyName = filter + delimiter + entry.getKey().toString();
									setProperty(contentNode, propertyName, entry.getValue(), vf);
								}
							} else {
								setProperty(contentNode, filter, value, vf);
							}
						}
					}

					session.save();
				}
			}

			/**
			 * Check if a property name matches any of the provided filters.
			 */
			private boolean matches(String propertyName, List<String> filters) {
				for (String filter : filters) {
					if (filter.endsWith("*")) {
						String prefix = filter.substring(0, filter.length() - 1);
						if (propertyName.startsWith(prefix)) {
							return true;
						}
					} else if (filter.startsWith("*")) {
						String suffix = filter.substring(1);
						if (propertyName.endsWith(suffix)) {
							return true;
						}
					} else if (filter.endsWith("~")) {
						String prefix = filter.substring(0, filter.length() - 1);
						if (propertyName.startsWith(prefix)) {
							return true;
						}
					} else if (filter.startsWith("~")) {
						String suffix = filter.substring(1);
						if (propertyName.endsWith(suffix)) {
							return true;
						}
					} else {
						if (propertyName.equals(filter)) {
							return true;
						}
					}
				}
				return false;
			}

			/**
			 * Set a single property on the target node, handling type conversion and multi-value properties.
			 *
			 * @param targetNode The node to set the property on (e.g., jcr:content for nt:file)
			 * @param propertyName The name of the JCR property to set
			 * @param value The value to set (can be single value or collection/array for multi-value properties)
			 * @param vf The JCR ValueFactory for creating Value instances
			 */
			private void setProperty(Node targetNode, String propertyName, Object value, ValueFactory vf) throws RepositoryException {
				if (value == null) {
					// Remove property if header value is null
					if (targetNode.hasProperty(propertyName)) {
						targetNode.getProperty(propertyName).remove();
					}
					return;
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
		}

		/**
		 * Producer for checking node existence via XPath query
		 *
		 * Executes the given XPath query and determines whether any matching node exists.
		 * Exposes the boolean result through the declared output bindings (source name: "exists"),
		 * for example {@code @body=exists}, {@code @header.cmsExists=exists} or
		 * {@code @property.exists=exists}. Output is produced solely through these bindings.
		 *
		 * URI format: cms:exists?query=/jcr:root/content//element(*, nt:file)&@body=exists
		 * Parameters:
		 *   - query: XPath expression (required)
		 *   - runAs: User to impersonate (optional)
		 *   - Output binding: @body=exists, @header.headerName=exists, or @property.propName=exists
		 */
		private class ExistsProducer extends CmsProducer {
			private ExistsProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					String query = (String) pc.getParameter("query");
					if (query == null || query.trim().isEmpty()) {
						throw new IllegalArgumentException("query parameter is required");
					}

					QueryManager qm = session.getWorkspace().getQueryManager();
					@SuppressWarnings("deprecation")
					Query q = qm.createQuery(query, Query.XPATH);
					q.setLimit(1); // We only need to check for existence
					QueryResult result = q.execute();
					boolean exists = result.getNodes().hasNext();

					// Expose the existence result through the declared output bindings (source name: "exists")
					pc.applyResultBindings(Map.of("exists", exists));
				}
			}
		}

		/**
		 * Producer for adding version control to a node
		 *
		 * Adds mix:versionable mixin and creates the initial version.
		 * Default conflict behavior: IGNORE (idempotent — already versionable is fine)
		 *
		 * URI format: cms:addVersionControl?path=/content/file.txt&conflictBehavior=IGNORE
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - conflictBehavior: FAIL, IGNORE, or WARN (default: IGNORE)
		 */
		private class AddVersionControlProducer extends CmsProducer {
			private AddVersionControlProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					String path = (String) pc.getParameter("path");
					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					Node node = session.getNode(path);

					// Already versionable — handle as conflict
					if (node.isNodeType("mix:versionable")) {
						CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.IGNORE);
						handleConflict(behavior, "Node is already versionable: " + path);
						return;
					}

					// Add mixin and create initial version
					node.addMixin("mix:versionable");
					session.save();

					VersionManager versionManager = session.getWorkspace().getVersionManager();
					versionManager.checkin(path);
				}
			}
		}

		/**
		 * Producer for checking out a versionable node
		 *
		 * If the node is already checked out, behavior depends on who checked it out:
		 *   - Current user (or no lock): default IGNORE (idempotent)
		 *   - Another user: default FAIL (real conflict)
		 *
		 * URI format: cms:checkout?path=/content/file.txt&conflictBehavior=FAIL
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - conflictBehavior: FAIL, IGNORE, or WARN (default: see above)
		 */
		private class CheckoutProducer extends CmsProducer {
			private CheckoutProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					String path = (String) pc.getParameter("path");
					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					Node node = session.getNode(path);
					if (!node.isNodeType("mix:versionable")) {
						throw new IllegalArgumentException("Node is not versionable: " + path);
					}

					VersionManager versionManager = session.getWorkspace().getVersionManager();

					// Already checked out — determine conflict type
					if (versionManager.isCheckedOut(path)) {
						LockManager lockManager = session.getWorkspace().getLockManager();
						boolean lockedByOther = false;
						try {
							Lock lock = lockManager.getLock(path);
							String lockOwner = lock.getLockOwner();
							String currentUser = session.getUserID();
							if (lockOwner != null && !lockOwner.equals(currentUser)) {
								lockedByOther = true;
							}
						} catch (LockException e) {
							// Not locked — treat as checked out by self
						}

						if (lockedByOther) {
							// Another user holds the lock — default FAIL
							CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.FAIL);
							handleConflict(behavior, "Node is checked out by another user: " + path);
						} else {
							// Self checkout — default IGNORE
							CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.IGNORE);
							handleConflict(behavior, "Node is already checked out: " + path);
						}
						return;
					}

					versionManager.checkout(path);
				}
			}
		}

		/**
		 * Producer for checking in a versionable node
		 *
		 * Creates a new version. The node must be checked out.
		 * Default conflict behavior: FAIL (silent skip is dangerous)
		 *
		 * Exposes the created version name through the declared output bindings (source name: "version"),
		 * for example {@code @header.cmsVersionName=version}, {@code @body=version} or
		 * {@code @property.version=version}. Output is produced solely through these bindings.
		 *
		 * URI format: cms:checkin?path=/content/file.txt&conflictBehavior=FAIL&@header.cmsVersionName=version
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - conflictBehavior: FAIL, IGNORE, or WARN (default: FAIL)
		 *   - Output binding: @body=version, @header.headerName=version, or @property.propName=version
		 */
		private class CheckinProducer extends CmsProducer {
			private CheckinProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					String path = (String) pc.getParameter("path");
					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					Node node = session.getNode(path);
					if (!node.isNodeType("mix:versionable")) {
						throw new IllegalArgumentException("Node is not versionable: " + path);
					}

					VersionManager versionManager = session.getWorkspace().getVersionManager();

					// Not checked out — handle as conflict
					if (!versionManager.isCheckedOut(path)) {
						CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.FAIL);
						handleConflict(behavior, "Node is not checked out: " + path);
						return;
					}

					Version version = versionManager.checkin(path);

					// Expose the created version name through the declared output bindings (source name: "version")
					pc.applyResultBindings(Map.of("version", version.getName()));
				}
			}
		}

		/**
		 * Producer for cancelling a checkout (uncheckout)
		 *
		 * Discards changes made since the last checkin and reverts to the base version.
		 * Default conflict behavior: IGNORE (idempotent — not checked out is fine)
		 *
		 * URI format: cms:uncheckout?path=/content/file.txt&conflictBehavior=IGNORE
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - conflictBehavior: FAIL, IGNORE, or WARN (default: IGNORE)
		 */
		private class UncheckoutProducer extends CmsProducer {
			private UncheckoutProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					String path = (String) pc.getParameter("path");
					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					Node node = session.getNode(path);
					if (!node.isNodeType("mix:versionable")) {
						throw new IllegalArgumentException("Node is not versionable: " + path);
					}

					VersionManager versionManager = session.getWorkspace().getVersionManager();

					// Not checked out — handle as conflict
					if (!versionManager.isCheckedOut(path)) {
						CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.IGNORE);
						handleConflict(behavior, "Node is not checked out: " + path);
						return;
					}

					((org.mintjams.jcr.version.VersionManager) versionManager).uncheckout(path);
				}
			}
		}

		/**
		 * Producer for creating a checkpoint (checkin + checkout)
		 *
		 * Creates a new version and keeps the node checked out for continued editing.
		 * Requires that the node is versionable and currently checked out.
		 * Default conflict behavior: FAIL (precondition not met is dangerous to skip)
		 *
		 * Exposes the created version name through the declared output bindings (source name: "version"),
		 * for example {@code @header.cmsVersionName=version}, {@code @body=version} or
		 * {@code @property.version=version}. Output is produced solely through these bindings.
		 *
		 * URI format: cms:checkpoint?path=/content/file.txt&conflictBehavior=FAIL&@header.cmsVersionName=version
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - conflictBehavior: FAIL, IGNORE, or WARN (default: FAIL)
		 *   - Output binding: @body=version, @header.headerName=version, or @property.propName=version
		 */
		private class CheckpointProducer extends CmsProducer {
			private CheckpointProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					String path = (String) pc.getParameter("path");
					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					Node node = session.getNode(path);

					// Not versionable — handle as conflict
					if (!node.isNodeType("mix:versionable")) {
						CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.FAIL);
						handleConflict(behavior, "Node is not versionable: " + path);
						return;
					}

					VersionManager versionManager = session.getWorkspace().getVersionManager();

					// Not checked out — handle as conflict
					if (!versionManager.isCheckedOut(path)) {
						CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.FAIL);
						handleConflict(behavior, "Node is not checked out: " + path);
						return;
					}

					Version version = versionManager.checkpoint(path);

					// Expose the created version name through the declared output bindings (source name: "version")
					pc.applyResultBindings(Map.of("version", version.getName()));
				}
			}
		}

		/**
		 * Producer for locking a node
		 *
		 * Acquires a lock on the specified node. Adds mix:lockable mixin if not present.
		 * If the node is already locked by the current user, default is IGNORE (idempotent).
		 * If locked by another user, default is FAIL (real conflict).
		 *
		 * URI format: cms:lock?path=/content/file.txt&isDeep=false&isSessionScoped=true
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - isDeep: Lock descendants (default: false)
		 *   - isSessionScoped: Session-scoped lock (default: false)
		 *   - conflictBehavior: FAIL, IGNORE, or WARN (default: see above)
		 */
		private class LockProducer extends CmsProducer {
			private LockProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					String path = (String) pc.getParameter("path");
					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					String isDeepStr = (String) pc.getParameter("isDeep");
					boolean isDeep = (isDeepStr != null) && Boolean.parseBoolean(isDeepStr);

					String isSessionScopedStr = (String) pc.getParameter("isSessionScoped");
					boolean isSessionScoped = (isSessionScopedStr != null) && Boolean.parseBoolean(isSessionScopedStr);

					Node node = session.getNode(path);

					// Add mix:lockable if not present
					if (!node.isNodeType("mix:lockable")) {
						node.addMixin("mix:lockable");
						session.save();
					}

					LockManager lockManager = session.getWorkspace().getLockManager();

					// Check if already locked
					if (node.isLocked()) {
						try {
							Lock existingLock = lockManager.getLock(path);
							String lockOwner = existingLock.getLockOwner();
							String currentUser = session.getUserID();
							if (lockOwner != null && !lockOwner.equals(currentUser)) {
								// Locked by another user — default FAIL
								CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.FAIL);
								handleConflict(behavior, "Node is locked by another user: " + path);
							} else {
								// Locked by self — default IGNORE
								CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.IGNORE);
								handleConflict(behavior, "Node is already locked: " + path);
							}
						} catch (LockException e) {
							// Should not happen since node.isLocked() was true
							CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.IGNORE);
							handleConflict(behavior, "Node is already locked: " + path);
						}
						return;
					}

					lockManager.lock(path, isDeep, isSessionScoped, Long.MAX_VALUE, session.getUserID());
				}
			}
		}

		/**
		 * Producer for unlocking a node
		 *
		 * Releases the lock on the specified node.
		 * If the node is not locked, default is IGNORE (idempotent).
		 * If locked by another user, default is FAIL (unauthorized).
		 *
		 * URI format: cms:unlock?path=/content/file.txt
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - conflictBehavior: FAIL, IGNORE, or WARN (default: see above)
		 */
		private class UnlockProducer extends CmsProducer {
			private UnlockProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					String path = (String) pc.getParameter("path");
					if (path == null || path.trim().isEmpty()) {
						throw new IllegalArgumentException("path parameter is required");
					}

					if (!session.nodeExists(path)) {
						throw new PathNotFoundException("Node not found: " + path);
					}

					Node node = session.getNode(path);
					LockManager lockManager = session.getWorkspace().getLockManager();

					// Not locked — handle as conflict
					if (!node.isLocked()) {
						CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.IGNORE);
						handleConflict(behavior, "Node is not locked: " + path);
						return;
					}

					// Check if locked by another user
					try {
						Lock existingLock = lockManager.getLock(path);
						String lockOwner = existingLock.getLockOwner();
						String currentUser = session.getUserID();
						if (lockOwner != null && !lockOwner.equals(currentUser)) {
							CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.FAIL);
							handleConflict(behavior, "Node is locked by another user: " + path);
							return;
						}
					} catch (LockException e) {
						// Lock not found — treat as not locked
						CmsConflictBehavior behavior = pc.getConflictBehavior(CmsConflictBehavior.IGNORE);
						handleConflict(behavior, "Node is not locked: " + path);
						return;
					}

					lockManager.unlock(path);
				}
			}
		}

		/**
		 * Producer for moving nodes in JCR
		 *
		 * Behavior aligns with JCR session.move() API:
		 * - If destPath is an existing folder: file is moved inside (Unix mv style)
		 * - If destPath does not exist: treated as full destination path (JCR style)
		 * - Optional 'name' parameter overrides the destination filename
		 *
		 * Exposes the moved path through the declared output bindings (source name: "path"),
		 * for example {@code @header.cmsMovedPath=path}, {@code @body=path} or
		 * {@code @property.movedPath=path}. Output is produced solely through these bindings.
		 *
		 * URI format: cms:move?sourcePath=/content/old&destPath=/content/new&@header.cmsMovedPath=path
		 * Examples:
		 *   destPath=/content/folder (existing) → /content/folder/oldname
		 *   destPath=/content/newfile → /content/newfile
		 *   destPath=/content/folder&name=custom → /content/folder/custom
		 *   Output binding: @body=path, @header.headerName=path, or @property.propName=path
		 */
		private class MoveProducer extends CmsProducer {
			private MoveProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName)) {
					String runAs = (String) pc.getParameter("runAs");
					if (runAs != null && !runAs.trim().isEmpty()) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Session session = Scripts.getJcrSession(context);

					// Get parameters from endpoint parameters or exchange headers
					String sourcePath = (String) pc.getParameter("sourcePath");
					String destPath = (String) pc.getParameter("destPath");
					String newName = (String) pc.getParameter("name"); // Optional

					if (sourcePath == null || sourcePath.trim().isEmpty()) {
						throw new IllegalArgumentException("sourcePath parameter is required");
					}
					if (destPath == null || destPath.trim().isEmpty()) {
						throw new IllegalArgumentException("destPath parameter is required");
					}

					if (!session.nodeExists(sourcePath)) {
						throw new PathNotFoundException("Source node not found: " + sourcePath);
					}

					Node sourceNode = session.getNode(sourcePath);

					// Determine final destination path (JCR-compliant with Unix mv convenience)
					String finalDestPath = destPath;
					if (session.nodeExists(destPath)) {
						Node destNode = session.getNode(destPath);
						// If destination is an existing folder, place the file inside it (Unix mv style)
						if (destNode.isNodeType("nt:folder") || destNode.isNodeType("nt:unstructured")) {
							String fileName = (newName != null && !newName.trim().isEmpty()) ? newName : sourceNode.getName();
							finalDestPath = destPath.endsWith("/") ? destPath + fileName : destPath + "/" + fileName;
						}
					} else {
						// Destination doesn't exist - treat as full path (JCR standard style)
						// If newName is specified, replace the last segment
						if (newName != null && !newName.trim().isEmpty()) {
							int lastSlash = destPath.lastIndexOf('/');
							if (lastSlash >= 0) {
								finalDestPath = destPath.substring(0, lastSlash + 1) + newName;
							} else {
								finalDestPath = newName;
							}
						}
					}

					// Check if target path already exists
					if (session.nodeExists(finalDestPath)) {
						throw new IllegalArgumentException("Node already exists at destination: " + finalDestPath);
					}

					// Perform JCR standard move
					session.move(sourcePath, finalDestPath);
					session.save();

					// Expose the moved path through the declared output bindings (source name: "path")
					pc.applyResultBindings(Map.of("path", finalDestPath));
				}
			}
		}
	}
}
