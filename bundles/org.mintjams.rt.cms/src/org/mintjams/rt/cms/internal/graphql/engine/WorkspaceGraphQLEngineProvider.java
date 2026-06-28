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

package org.mintjams.rt.cms.internal.graphql.engine;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.jcr.Credentials;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.dataloader.DataLoaderRegistry;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;
import org.mintjams.rt.cms.internal.graphql.GraphQLRequest;
import org.mintjams.rt.cms.internal.graphql.resolver.GroovyDataFetcher;
import org.mintjams.rt.cms.internal.graphql.wiring.PlatformBpmWiringContributor;
import org.mintjams.rt.cms.internal.graphql.wiring.PlatformEipWiringContributor;
import org.mintjams.rt.cms.internal.graphql.wiring.PlatformIdpWiringContributor;
import org.mintjams.rt.cms.internal.graphql.wiring.PlatformWiringContributor;
import org.mintjams.rt.cms.internal.graphql.wiring.PlatformWorkspaceWiringContributor;
import org.mintjams.rt.cms.internal.graphql.wiring.WiringContributor;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.osgi.Registration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.reactivestreams.Publisher;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.language.Definition;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;

/**
 * The per-workspace GraphQL engine that serves the unified {@code /bin/graphql.cgi}
 * API: one graphql-java schema per workspace combining the platform's built-in
 * schema with the workspace's application-defined schema.
 *
 * <ul>
 *   <li><b>Platform (built-in)</b> — SDL bundle resources wired to Java
 *       {@link graphql.schema.DataFetcher}s by the {@code Platform*WiringContributor}s
 *       (content/JCR, Workspace, IDP, BPM, EIP, and the {@code …/stream}
 *       subscriptions). Always contributed, for every workspace.</li>
 *   <li><b>Application (dynamic)</b> — SDL and Groovy resolvers dropped into the
 *       workspace's GraphQL folders ({@code /etc/graphql} and
 *       {@code /content/WEB-INF/graphql}), merged in only when application GraphQL
 *       is enabled ({@code graphql.yml#enabled}). Application SDL may
 *       {@code extend} the platform's base types.</li>
 * </ul>
 *
 * <p>It follows the same shape as {@code WorkspaceProcessEngineProvider} (BPM)
 * and {@code WorkspaceIntegrationEngineProvider} (EIP): one instance per
 * workspace, created and opened from {@code CmsService.prepareServices(...)},
 * with an inner {@link Deployer} that does an initial compile on {@link #open()}
 * and then watches the application folders through the OSGi {@code EventAdmin},
 * rebuilding the schema whenever a file changes — so uploading a file is all it
 * takes for the API to evolve. Because schema and resolver scripts are compiled
 * and executed under the workspace's own service session, script-engine manager
 * and class loader, each workspace gets exactly the application it deployed.
 *
 * <p>A GraphQL schema is a single coherent unit, so any change triggers a full
 * recompile and an atomic swap of the live {@link GraphQL} instance. A failed
 * recompile (invalid SDL, a resolver referencing an undefined type, ...) is
 * logged and the previous good schema is kept, so one bad edit cannot take a
 * working API offline; a failure on the very first compile leaves the engine
 * {@link #isAvailable() unavailable} but never aborts workspace startup.
 *
 * <p>Because the built-in platform schema (which serves login/{@code me} and the
 * rest of the core API) and the workspace's application schema share one
 * graphql-java instance, an invalid <em>application</em> schema must not be able
 * to disable the platform API. {@link #rebuildSchema()} therefore falls back to a
 * platform-only compile when the combined compile fails: the broken application
 * schema is logged and not served, but the platform API (including login) stays
 * available, and a later edit recompiles and picks the application schema up once
 * it is fixed.
 *
 * <p>Resolver errors are sanitized by {@link GraphQLExceptionHandler} so
 * platform internals never reach the wire, while application (Groovy) resolver
 * messages — author-trusted code — are surfaced verbatim.
 */
public class WorkspaceGraphQLEngineProvider implements Closeable {

	/** Repository folders scanned for application schema and resolver files. */
	static final List<String> WATCHED_PATHS = List.of("/etc/graphql", "/content/WEB-INF/graphql");

	private final WorkspaceGraphQLEngineProviderConfiguration fConfig;
	private final Closer fCloser = Closer.create();
	private volatile GraphQL fGraphQL;

	public WorkspaceGraphQLEngineProvider(String workspaceName) {
		fConfig = new WorkspaceGraphQLEngineProviderConfiguration(workspaceName);
	}

	public synchronized void open() throws IOException, RepositoryException {
		fConfig.load();

		// Always open: the platform schema is served for every workspace. The
		// Deployer does the initial compile and (only when application GraphQL is
		// enabled) watches the application folders for hot-reload.
		Deployer deployer = fCloser.register(new Deployer());
		deployer.open();
	}

	@Override
	public synchronized void close() throws IOException {
		fCloser.close();
		fGraphQL = null;
	}

	public String getWorkspaceName() {
		return fConfig.getWorkspaceName();
	}

	/**
	 * Returns whether application (dynamic) GraphQL is switched on for this
	 * workspace ({@code graphql.yml#enabled}). This gates only the application
	 * folder scan; the platform schema is served regardless.
	 */
	public boolean isEnabled() {
		return fConfig.isEnabled();
	}

	/**
	 * Returns whether a schema is currently deployed and ready to serve requests.
	 * False only when the most recent compile failed and no prior schema existed
	 * (the platform contributors always provide SDL, so a successful compile is
	 * always available).
	 */
	public boolean isAvailable() {
		return fGraphQL != null;
	}

	/**
	 * Executes a query/mutation request against the deployed schema and returns the
	 * GraphQL-spec response map ({@code data}/{@code errors}). A single caller JCR
	 * session is opened for the request and shared by both resolver kinds — platform
	 * Java fetchers (through {@link GraphQLExecutionContext}) and application Groovy
	 * resolvers (through the same {@link WorkspaceScriptContext}) — so repository
	 * ACLs govern what every resolver can reach. The session is released when the
	 * request completes.
	 */
	public Map<String, Object> execute(GraphQLRequest request, Credentials credentials) {
		GraphQL graphQL = fGraphQL;
		if (graphQL == null) {
			return errorSpecification("The GraphQL schema is not available for the workspace: " + getWorkspaceName());
		}
		if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
			return errorSpecification("Query must not be empty");
		}

		WorkspaceScriptContext scriptContext = new WorkspaceScriptContext(getWorkspaceName());
		try {
			ExecutionInput input = newExecutionInput(request, credentials, scriptContext);
			ExecutionResult result = graphQL.execute(input);
			return result.toSpecification();
		} catch (Throwable ex) {
			CmsService.getLogger(getClass())
					.error("GraphQL execution failed for the workspace: " + getWorkspaceName(), ex);
			return errorSpecification("Execution failed: " + ex.getMessage());
		} finally {
			// Closing the script context logs out the shared caller session.
			try {
				scriptContext.close();
			} catch (Throwable ignore) {}
		}
	}

	/**
	 * Starts a subscription operation against the deployed schema under
	 * {@code credentials}. Unlike {@link #execute}, the result's top-level field is
	 * a {@link Publisher} of per-event {@link ExecutionResult}s; the returned
	 * {@link SubscriptionStream} carries it (or the start-up errors).
	 *
	 * <p>The caller session is <em>not</em> held for the lifetime of the stream:
	 * subscription fetchers capture the subscriber's identity synchronously during
	 * execution, and each event's payload mapper logs in on demand later, so the
	 * shared session is released as soon as the Publisher is built (closing the
	 * script context in the finally). A long-lived stream therefore pins no DB
	 * connection.
	 */
	public SubscriptionStream executeSubscription(GraphQLRequest request, Credentials credentials) {
		GraphQL graphQL = fGraphQL;
		if (graphQL == null) {
			return SubscriptionStream
					.failed(errorList("The GraphQL schema is not available for the workspace: " + getWorkspaceName()));
		}
		if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
			return SubscriptionStream.failed(errorList("Query must not be empty"));
		}

		WorkspaceScriptContext scriptContext = new WorkspaceScriptContext(getWorkspaceName());
		try {
			ExecutionInput input = newExecutionInput(request, credentials, scriptContext);
			ExecutionResult result = graphQL.execute(input);
			if (!result.getErrors().isEmpty()) {
				return SubscriptionStream.failed(toErrorMaps(result.getErrors()));
			}
			Object data = result.getData();
			if (!(data instanceof Publisher)) {
				return SubscriptionStream.failed(errorList("The operation is not a subscription"));
			}
			@SuppressWarnings("unchecked")
			Publisher<ExecutionResult> publisher = (Publisher<ExecutionResult>) data;
			return SubscriptionStream.started(publisher);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass())
					.error("GraphQL subscription failed for the workspace: " + getWorkspaceName(), ex);
			return SubscriptionStream.failed(errorList("Subscription failed: " + ex.getMessage()));
		} finally {
			// Release the shared caller session right after the Publisher is built
			// (see the method javadoc): it is needed only to authenticate the
			// subscriber and let fetchers capture their identity during execute().
			try {
				scriptContext.close();
			} catch (Throwable ignore) {}
		}
	}

	/**
	 * Builds the {@link ExecutionInput} for one request, wiring both resolver
	 * contexts onto the graphql-java {@code GraphQLContext}: a
	 * {@link GraphQLExecutionContext} for platform Java fetchers and the
	 * {@link WorkspaceScriptContext} for application Groovy resolvers. Both share
	 * the one caller JCR session that the script context owns (and later closes).
	 */
	private ExecutionInput newExecutionInput(GraphQLRequest request, Credentials credentials,
			WorkspaceScriptContext scriptContext) throws IOException {
		scriptContext.setCredentials(credentials);
		Scripts.prepareAPIs(scriptContext);

		// One caller session shared by platform (Java) and application (Groovy)
		// resolvers; the script context owns and closes it.
		Session callerSession = scriptContext.adaptTo(Session.class);
		DataLoaderRegistry dataLoaderRegistry = new DataLoaderRegistry();
		GraphQLExecutionContext executionContext = new GraphQLExecutionContext(getWorkspaceName(), callerSession,
				dataLoaderRegistry);

		return ExecutionInput.newExecutionInput()
				.query(request.getQuery())
				.operationName(request.getOperationName())
				.variables(request.getVariables() == null ? Map.of() : request.getVariables())
				.dataLoaderRegistry(dataLoaderRegistry)
				.graphQLContext(Map.<String, Object>of(
						GraphQLExecutionContext.CTX_EXECUTION_CONTEXT, executionContext,
						GroovyDataFetcher.CTX_SCRIPT_CONTEXT, scriptContext))
				.build();
	}

	/**
	 * Whether the request's selected operation is a mutation. Parses the document
	 * and honours {@code operationName}, so it is not fooled by a query whose first
	 * field happens to be named "mutation…" nor by a mutation that is the second of
	 * several operations. Returns {@code false} when the operation cannot be
	 * determined (unparseable, or an ambiguous multi-operation document with no
	 * operationName) — the normal execution path then surfaces the real
	 * parse/validation error. Used to reject mutations over HTTP GET.
	 */
	public boolean isMutationOperation(GraphQLRequest request) {
		if (request == null || request.getQuery() == null) {
			return false;
		}
		try {
			String operationName = request.getOperationName();
			OperationDefinition selected = null;
			int operationCount = 0;
			for (Definition<?> definition : Parser.parse(request.getQuery()).getDefinitions()) {
				if (!(definition instanceof OperationDefinition)) {
					continue;
				}
				OperationDefinition operation = (OperationDefinition) definition;
				operationCount++;
				if (operationName != null && !operationName.isEmpty()) {
					if (operationName.equals(operation.getName())) {
						selected = operation;
						break;
					}
				} else if (selected == null) {
					selected = operation;
				}
			}
			if (selected == null) {
				return false;
			}
			// Multiple operations with no operationName is invalid per the spec; let
			// execution reject it rather than guessing (and never run the mutation).
			if ((operationName == null || operationName.isEmpty()) && operationCount > 1) {
				return false;
			}
			return selected.getOperation() == OperationDefinition.Operation.MUTATION;
		} catch (Exception ex) {
			return false;
		}
	}

	private static List<Map<String, Object>> toErrorMaps(List<GraphQLError> errors) {
		List<Map<String, Object>> maps = new ArrayList<>();
		for (GraphQLError error : errors) {
			maps.add(error.toSpecification());
		}
		return maps;
	}

	private static List<Map<String, Object>> errorList(String message) {
		List<Map<String, Object>> errors = new ArrayList<>();
		errors.add(Map.<String, Object>of("message", message));
		return errors;
	}

	private static Map<String, Object> errorSpecification(String message) {
		return Map.<String, Object>of("errors", List.of(Map.of("message", message)));
	}

	/** The platform contributors merged into every workspace's schema. */
	private static List<WiringContributor> platformContributors() {
		return List.of(new PlatformWiringContributor(), new PlatformBpmWiringContributor(),
				new PlatformWorkspaceWiringContributor(), new PlatformIdpWiringContributor(),
				new PlatformEipWiringContributor());
	}

	/**
	 * Recompiles the schema and atomically swaps the live instance. The platform
	 * contributors are always applied; the application folders are scanned (with a
	 * service session) only when enabled for this workspace. Keeps the previous
	 * schema on failure.
	 */
	private void rebuildSchema() {
		Session session = null;
		try {
			session = CmsService.getRepository().login(new CmsServiceCredentials(), getWorkspaceName());
			List<String> roots = fConfig.isEnabled() ? WATCHED_PATHS : List.of();
			GraphQL compiled;
			try {
				compiled = GraphQLSchemaCompiler.compile(session, getWorkspaceName(), roots,
						platformContributors(), new GraphQLExceptionHandler());
			} catch (Throwable appEx) {
				// The platform's built-in schema (which serves login/`me` and the rest of
				// the core API) and the workspace's application schema are compiled into a
				// single graphql-java instance. A GraphQL schema is all-or-nothing, so one
				// invalid application SDL file — a type that redefines a platform base type
				// or scalar, an interface/union with no type resolver, a dangling type
				// reference — would otherwise take the WHOLE endpoint offline, including
				// login. Guard against that: if the combined compile fails, fall back to a
				// platform-only schema so the built-in API stays available. The broken
				// application schema is logged and simply not served; a later edit triggers
				// a recompile that picks it up once fixed.
				if (roots.isEmpty()) {
					throw appEx; // no application schema in play — this is a platform fault
				}
				CmsService.getLogger(getClass()).error("Failed to compile the application GraphQL schema for the"
						+ " workspace: " + getWorkspaceName() + " — serving the platform schema only until the"
						+ " application schema is fixed", appEx);
				compiled = GraphQLSchemaCompiler.compile(session, getWorkspaceName(), List.of(),
						platformContributors(), new GraphQLExceptionHandler());
			}
			fGraphQL = compiled;
			if (compiled == null) {
				CmsService.getLogger(getClass())
						.info("No GraphQL schema present for the workspace: " + getWorkspaceName());
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).error("Failed to compile the GraphQL schema for the workspace: "
					+ getWorkspaceName() + " (keeping the previous schema)", ex);
		} finally {
			if (session != null) {
				try {
					session.logout();
				} catch (Throwable ignore) {}
			}
		}
	}

	/**
	 * A started subscription: the {@link Publisher} of per-event
	 * {@link ExecutionResult}s. The stream holds <em>no</em> JCR session — each
	 * payload mapper logs in on demand when it needs JCR and releases it right away,
	 * so a long-lived stream pins no DB connection. When the operation failed to
	 * start, {@link #isStarted()} is {@code false} and {@link #getErrors()} carries
	 * the GraphQL-spec error maps.
	 */
	public static final class SubscriptionStream {
		private final Publisher<ExecutionResult> fPublisher;
		private final List<Map<String, Object>> fErrors;

		private SubscriptionStream(Publisher<ExecutionResult> publisher, List<Map<String, Object>> errors) {
			fPublisher = publisher;
			fErrors = errors;
		}

		static SubscriptionStream started(Publisher<ExecutionResult> publisher) {
			return new SubscriptionStream(publisher, null);
		}

		static SubscriptionStream failed(List<Map<String, Object>> errors) {
			return new SubscriptionStream(null, errors);
		}

		public boolean isStarted() {
			return fPublisher != null;
		}

		public Publisher<ExecutionResult> getPublisher() {
			return fPublisher;
		}

		public List<Map<String, Object>> getErrors() {
			return fErrors;
		}

		/**
		 * No-op: the stream owns no JCR session (subscriptions read JCR on demand per
		 * event, not through a held session). Kept for the transport's cleanup call;
		 * the reactive {@code Subscription.cancel()} releases the event handler and sink.
		 */
		public void close() {
		}
	}

	private class Deployer implements EventHandler, Closeable {
		private Thread fThread;
		private boolean fCloseRequested;
		private final List<Event> fEvents = new ArrayList<>();
		private Registration<EventHandler> fEventHandlerRegistration;

		@Override
		public void handleEvent(Event event) {
			synchronized (fEvents) {
				fEvents.add(event);
				fEvents.notifyAll();
			}
		}

		private Deployer open() throws IOException, RepositoryException {
			if (fThread != null) {
				return this;
			}

			// Initial compile: always (the platform schema is served regardless of
			// the application enabled flag).
			rebuildSchema();

			// Watch the application GraphQL folders for hot-reload only when
			// application GraphQL is enabled — the platform schema is static and
			// never changes, so a disabled workspace needs no watcher.
			if (!fConfig.isEnabled()) {
				CmsService.getLogger(getClass()).info("Application GraphQL is disabled for the workspace: "
						+ getWorkspaceName() + " (serving the platform schema only)");
				return this;
			}

			fThread = new Thread(new Task());
			fThread.setDaemon(true);
			fThread.start();

			fEventHandlerRegistration = fCloser.register(Registration.newBuilder(EventHandler.class)
					.setService(this)
					.setProperty(EventConstants.EVENT_TOPIC, new String[] { Node.class.getName().replace(".", "/") + "/*" })
					.setProperty(EventConstants.EVENT_FILTER, "(workspace=" + getWorkspaceName() + ")")
					.setBundleContext(CmsService.getDefault().getBundleContext())
					.build());

			return this;
		}

		@Override
		public void close() throws IOException {
			if (fCloseRequested) {
				return;
			}

			fCloseRequested = true;
			IOs.closeQuietly(fEventHandlerRegistration);
			synchronized (fEvents) {
				fEvents.notifyAll();
			}
			// fThread is null when application GraphQL is disabled (no watcher started).
			if (fThread != null) {
				try {
					fThread.interrupt();
					fThread.join(10000);
				} catch (InterruptedException ignore) {}
				fThread = null;
			}
			fCloseRequested = false;
		}

		private class Task implements Runnable {
			@Override
			public void run() {
				while (!fCloseRequested) {
					if (Thread.interrupted()) {
						fCloseRequested = true;
						break;
					}

					List<Event> batch = new ArrayList<>();
					synchronized (fEvents) {
						if (fEvents.isEmpty()) {
							try {
								fEvents.wait();
							} catch (InterruptedException ignore) {}
							continue;
						}
						// Coalesce a burst of edits into a single recompile.
						batch.addAll(fEvents);
						fEvents.clear();
					}

					if (Thread.interrupted()) {
						fCloseRequested = true;
						break;
					}

					boolean relevant = false;
					for (Event event : batch) {
						if (isRelevant(event)) {
							relevant = true;
							break;
						}
					}

					if (relevant) {
						try {
							rebuildSchema();
						} catch (Throwable ex) {
							CmsService.getLogger(getClass())
									.error("An error occurred while rebuilding the GraphQL schema", ex);
						}
					}
				}
			}

			private boolean isRelevant(Event event) {
				String topic = event.getTopic();
				if (!(topic.endsWith("/ADDED") || topic.endsWith("/CHANGED")
						|| topic.endsWith("/MOVED") || topic.endsWith("/REMOVED"))) {
					return false;
				}
				Object path = event.getProperty("path");
				if (path != null && pathMatches(path.toString())) {
					return true;
				}
				Object sourcePath = event.getProperty("source_path");
				return sourcePath != null && pathMatches(sourcePath.toString());
			}

			private boolean pathMatches(String path) {
				for (String e : WATCHED_PATHS) {
					if (path.equals(e) || path.startsWith(e + "/")) {
						return true;
					}
				}
				return false;
			}
		}
	}

}
