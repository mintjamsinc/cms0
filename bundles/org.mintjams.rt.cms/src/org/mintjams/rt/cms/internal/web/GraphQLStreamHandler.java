/*
 * Copyright (c) 2026 MintJams Inc.
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

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.jcr.Credentials;
import javax.jcr.Session;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.jcr.cluster.ClusterTransport;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider;
import org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider.SubscriptionStream;
import org.mintjams.rt.cms.internal.graphql.GraphQLRequest;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import graphql.ExecutionResult;

/**
 * Server-Sent-Events transport for the unified engine's subscriptions, at
 * {@code /bin/graphql.cgi/{workspace}/stream}, implementing the
 * <a href="https://github.com/enisdenjo/graphql-sse">graphql-sse</a> protocol in
 * <em>single connection mode</em>: one SSE connection per client multiplexes
 * <em>all</em> of that client's subscription operations, so the client holds a
 * single HTTP/2 stream regardless of how many subscriptions it runs. (Distinct
 * connections mode — one connection per subscription — opened a dozen+ streams per
 * client, churning HTTP/2 and DB connections.)
 *
 * <p>Wire (one workspace-scoped {@code …/stream} endpoint):
 * <ul>
 *   <li><b>{@code PUT}</b> — reserve a connection; responds {@code 201} with a
 *       single-use token (text/plain). The reservation expires if no stream opens.</li>
 *   <li><b>{@code GET ?token=…}</b> — open the one SSE stream for the token
 *       ({@code Accept: text/event-stream}; sent by {@code EventSource}). Held open
 *       for the client's lifetime; events for every operation arrive here.</li>
 *   <li><b>{@code POST ?token=…}</b> — add one operation; body is a standard GraphQL
 *       request {@code {query, variables, operationName, extensions:{operationId}}}.
 *       Results stream back on the SSE as {@code event: next} /
 *       {@code event: complete} frames whose {@code data} is
 *       {@code {"id":"<operationId>","payload":<execution result>}}.</li>
 *   <li><b>{@code DELETE ?token=…&operationId=…}</b> — stop one operation.</li>
 * </ul>
 *
 * <p>Each operation runs through {@link WorkspaceGraphQLEngineProvider#executeSubscription}
 * (which no longer holds a JCR session — payload mappers read JCR on demand), so a
 * long-lived stream pins no DB connection. Closing the SSE stops every operation on
 * it and drops the token.
 *
 * <p>In a cluster the four requests need not reach the same node. The stream stays
 * in the memory of the node that holds it; that node records (token → node, user) in
 * the cluster's replicated table. A {@code GET} on another node than the
 * {@code PUT} takes the reservation over when the signed-in user is the same, and a
 * {@code POST} or {@code DELETE} on another node is forwarded to the stream's node
 * and answered with its result, so the order of the client's changes is kept in one
 * place and a {@code 202} means the operation is running. When the stream's node
 * cannot be reached the answer is {@code 503}, and the client reconnects.
 */
public final class GraphQLStreamHandler {

	private static final Gson GSON = new GsonBuilder().serializeNulls().create();
	/**
	 * Comment frames keep a quiet stream alive. Well under the HTTP connector's
	 * idle timeout ({@code org.apache.felix.http.timeout}, 30 seconds in the
	 * shipped configuration): at the same value the heartbeat raced the idle
	 * timeout every period and a share of the idle streams was cut each time.
	 */
	private static final long HEARTBEAT_INTERVAL_MS = 15000L;
	/** A reserved token is dropped if its SSE stream is not opened within this window. */
	private static final long RESERVATION_TIMEOUT_MS = 30000L;
	/** An in-flight write that has not completed within this window means a dead client. */
	private static final long WRITE_STALL_TIMEOUT_MS = 60000L;
	/** How often the watchdog scans connections for stalled writes. */
	private static final long WRITE_STALL_SCAN_INTERVAL_MS = 15000L;
	/** Outbound frames buffered past this depth mean the client cannot keep up. */
	private static final int MAX_PENDING_FRAMES = 256;

	/** The replicated table of stream owners: token → {node, user, workspace}. */
	static final String STREAMS_TABLE = "graphql-sse/streams";
	/** The cluster request that applies a forwarded POST or DELETE on the stream's node. */
	static final String OPERATION_REQUEST = "graphql-sse/operation";
	/** How long a forwarded POST or DELETE waits for the stream's node. */
	private static final long FORWARD_TIMEOUT_MS = 5000L;
	/** The pause before the owner is looked up again (a table write may be in flight). */
	private static final long OWNER_RETRY_DELAY_MS = 50L;
	/** An open stream's entry outlives a few missed heartbeats, then expires with a dead node. */
	private static final long STREAM_OWNER_TTL_MS = 90000L;

	/** token -> connection. One per open client SSE stream. */
	private static final ConcurrentMap<String, StreamConnection> CONNECTIONS = new ConcurrentHashMap<>();

	/**
	 * Timer thread only — heartbeats, reservation timeouts and the stall watchdog.
	 * It must never touch the servlet response: a write to one dead client can block
	 * for as long as its TCP send buffer lasts, and with the writes previously done
	 * here a single stalled connection froze every other connection's heartbeat and
	 * every reservation timeout, letting dead connections (and their subscription
	 * buffers) pile up until the heap filled.
	 */
	private static final ScheduledExecutorService SCHEDULER = createScheduler();

	/** Blocking SSE writes run here, one drain task per connection at a time. */
	private static final ExecutorService WRITER_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
		private final AtomicLong fCount = new AtomicLong();

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "graphql-sse-writer-" + fCount.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	});

	private static ScheduledExecutorService createScheduler() {
		ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
			private final AtomicLong fCount = new AtomicLong();

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "graphql-sse-" + fCount.incrementAndGet());
				t.setDaemon(true);
				return t;
			}
		});
		// Cancelled heartbeat/reservation tasks must leave the queue immediately;
		// the default keeps them queued until their next fire time.
		scheduler.setRemoveOnCancelPolicy(true);
		scheduler.scheduleAtFixedRate(GraphQLStreamHandler::terminateStalledConnections,
				WRITE_STALL_SCAN_INTERVAL_MS, WRITE_STALL_SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
		return scheduler;
	}

	/** Reaps connections whose write has been blocked on a dead client's socket. */
	private static void terminateStalledConnections() {
		long now = System.currentTimeMillis();
		for (StreamConnection connection : CONNECTIONS.values()) {
			connection.terminateIfStalled(now);
		}
	}

	private final String fWorkspaceName;

	public GraphQLStreamHandler(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	/** Routes the graphql-sse single-connection verbs (PUT/GET/POST/DELETE) for {@code …/stream}. */
	public void handle(HttpServletRequest request, HttpServletResponse response, Credentials credentials)
			throws IOException {
		WorkspaceGraphQLEngineProvider engine = CmsService.getWorkspaceGraphQLEngineProvider(fWorkspaceName);
		if (engine == null || !engine.isAvailable()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
					"The GraphQL schema is not available for the workspace: " + fWorkspaceName);
			return;
		}

		String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase();
		switch (method) {
		case "PUT":
			reserve(response, credentials);
			break;
		case "GET":
			openStream(request, response, credentials);
			break;
		case "POST":
			addOperation(request, response, credentials);
			break;
		case "DELETE":
			stopOperation(request, response, credentials);
			break;
		default:
			response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
					"Unsupported method for the subscription stream");
		}
	}

	/** {@code PUT} — reserve a single-use connection token. */
	private void reserve(HttpServletResponse response, Credentials credentials) throws IOException {
		String token = UUID.randomUUID().toString();
		StreamConnection connection = new StreamConnection(fWorkspaceName, credentials, token);
		ClusterTransport transport = CmsService.getClusterTransport();
		if (transport != null) {
			connection.fUserId = userIdOf(fWorkspaceName, credentials);
		}
		CONNECTIONS.put(token, connection);
		connection.scheduleReservationTimeout();
		// The stream may be opened on another node; it finds the reservation here.
		connection.announce(RESERVATION_TIMEOUT_MS * 2);
		response.setStatus(HttpServletResponse.SC_CREATED);
		response.setContentType("text/plain; charset=UTF-8");
		PrintWriter writer = response.getWriter();
		writer.write(token);
		writer.flush();
	}

	/** {@code GET ?token} — open the one SSE stream for the token. */
	private void openStream(HttpServletRequest request, HttpServletResponse response, Credentials credentials)
			throws IOException {
		String token = request.getParameter("token");
		StreamConnection connection = (token == null) ? null : CONNECTIONS.get(token);
		if (connection == null && token != null) {
			connection = takeOver(token, credentials);
		}
		if (connection == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown or expired stream token");
			return;
		}

		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/event-stream");
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-cache");
		// No "Connection" header: it is a hop-by-hop header forbidden under HTTP/2.
		response.setHeader("X-Accel-Buffering", "no");

		// startAsync() must precede committing the response; bind before flushBuffer so
		// the connection is ready before the client's EventSource onopen fires (and
		// before it POSTs its first operation).
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(0);
		connection.bind(asyncContext);
		response.flushBuffer();
	}

	/**
	 * Opens on this node a stream that another node reserved, when the
	 * {@code GET}'s own sign-in is the reserving user's. The stream runs with
	 * the {@code GET}'s credentials; the other node's reservation simply
	 * expires. The client sends the {@code GET} the moment the {@code PUT} is
	 * answered, so the reserving node's table write may still be on its way:
	 * a token that is not in the table is looked up once more after a short
	 * pause before it is called unknown.
	 */
	private StreamConnection takeOver(String token, Credentials credentials) {
		ClusterTransport transport = CmsService.getClusterTransport();
		if (transport == null) {
			return null;
		}
		Owner owner = Owner.of(transport, token);
		if (owner == null) {
			try {
				Thread.sleep(OWNER_RETRY_DELAY_MS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return null;
			}
			owner = Owner.of(transport, token);
		}
		if (owner == null || !fWorkspaceName.equals(owner.workspace)) {
			return null;
		}
		String userId = userIdOf(fWorkspaceName, credentials);
		if (userId == null || !userId.equals(owner.user)) {
			return null;
		}
		StreamConnection connection = new StreamConnection(fWorkspaceName, credentials, token);
		connection.fUserId = userId;
		StreamConnection raced = CONNECTIONS.putIfAbsent(token, connection);
		return (raced != null) ? raced : connection;
	}

	/** {@code POST ?token} — add one subscription operation to the token's stream. */
	private void addOperation(HttpServletRequest request, HttpServletResponse response, Credentials credentials)
			throws IOException {
		String token = request.getParameter("token");
		StreamConnection connection = (token == null) ? null : CONNECTIONS.get(token);
		ClusterTransport transport = CmsService.getClusterTransport();
		boolean local = (connection != null && (connection.isBound() || transport == null));
		if (!local && (token == null || transport == null)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown or expired stream token");
			return;
		}

		Map<String, Object> body;
		try (InputStreamReader reader = new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8)) {
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = GSON.fromJson(reader, Map.class);
			body = parsed;
		} catch (Exception ex) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid GraphQL request body");
			return;
		}
		if (body == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "A GraphQL request body is required");
			return;
		}

		String query = asString(body.get("query"));
		String operationName = asString(body.get("operationName"));
		@SuppressWarnings("unchecked")
		Map<String, Object> variables = (body.get("variables") instanceof Map)
				? (Map<String, Object>) body.get("variables") : null;
		String operationId = operationId(body, request);
		if (query == null || query.trim().isEmpty() || operationId == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "query and operationId are required");
			return;
		}

		if (!local) {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("action", "add");
			payload.put("operationId", operationId);
			payload.put("query", query);
			payload.put("operationName", operationName);
			payload.put("variables", variables);
			forward(transport, token, payload, credentials, response);
			return;
		}

		connection.addOperation(operationId, new GraphQLRequest(query, operationName, variables));
		response.setStatus(HttpServletResponse.SC_ACCEPTED);
	}

	/** {@code DELETE ?token&operationId} — stop one operation. */
	private void stopOperation(HttpServletRequest request, HttpServletResponse response, Credentials credentials)
			throws IOException {
		String token = request.getParameter("token");
		String operationId = request.getParameter("operationId");
		StreamConnection connection = (token == null) ? null : CONNECTIONS.get(token);
		ClusterTransport transport = CmsService.getClusterTransport();
		if (connection != null && (connection.isBound() || transport == null)) {
			if (operationId != null) {
				connection.stopOperation(operationId);
			}
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
			return;
		}
		if (transport == null || token == null || operationId == null) {
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
			return;
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("action", "stop");
		payload.put("operationId", operationId);
		forward(transport, token, payload, credentials, response);
	}

	/**
	 * Forwards a {@code POST} or {@code DELETE} to the node that holds the
	 * token's stream and answers with that node's result. A stream that was
	 * opened a moment ago may not be in this node's table yet, so a miss is
	 * looked up once more after a short pause.
	 */
	private void forward(ClusterTransport transport, String token, Map<String, Object> payload,
			Credentials credentials, HttpServletResponse response) throws IOException {
		payload.put("workspace", fWorkspaceName);
		payload.put("token", token);
		payload.put("userId", userIdOf(fWorkspaceName, credentials));

		int status = HttpServletResponse.SC_NOT_FOUND;
		String forwardedTo = null;
		for (int attempt = 0; attempt < 2; attempt++) {
			if (attempt > 0) {
				try {
					Thread.sleep(OWNER_RETRY_DELAY_MS);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					break;
				}
			}

			Owner owner = Owner.of(transport, token);
			if (owner == null) {
				continue;
			}
			if (owner.node.equals(transport.getNodeId())) {
				// Ours, but not open here yet: the GET may still be on its way.
				StreamConnection connection = CONNECTIONS.get(token);
				if (connection != null && connection.isBound()) {
					status = intOf(applyForwarded(payload).get("status"));
					break;
				}
				continue;
			}
			if (owner.node.equals(forwardedTo)) {
				break;
			}

			forwardedTo = owner.node;
			try {
				status = intOf(transport.request(owner.node, OPERATION_REQUEST, payload, FORWARD_TIMEOUT_MS).get("status"));
			} catch (IOException ex) {
				CmsService.getLogger(GraphQLStreamHandler.class).debug("Could not forward a subscription "
						+ payload.get("action") + " to cluster node '" + owner.node + "': " + ex.getMessage());
				status = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
				break;
			}
			if (status != HttpServletResponse.SC_NOT_FOUND) {
				break;
			}
		}

		if (status == HttpServletResponse.SC_NOT_FOUND) {
			response.sendError(status, "Unknown or expired stream token");
		} else if (status == HttpServletResponse.SC_SERVICE_UNAVAILABLE) {
			response.sendError(status, "The node holding the stream cannot be reached");
		} else if (status == HttpServletResponse.SC_BAD_REQUEST) {
			response.sendError(status, "Invalid subscription request");
		} else {
			response.setStatus(status);
		}
	}

	/**
	 * Registers the handler that applies the {@code POST}s and {@code DELETE}s
	 * other nodes forward to the streams held here. Closing the returned handle
	 * removes it.
	 */
	public static AutoCloseable registerClusterHandler(ClusterTransport transport) {
		return transport.setRequestHandler(OPERATION_REQUEST, (fromNodeId, payload) -> applyForwarded(payload));
	}

	/**
	 * Applies a forwarded change, when this node holds the token's open stream
	 * and the user who sent the change is the stream's user.
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> applyForwarded(Map<String, Object> payload) {
		String token = asString(payload.get("token"));
		String userId = asString(payload.get("userId"));
		String operationId = asString(payload.get("operationId"));
		StreamConnection connection = (token == null) ? null : CONNECTIONS.get(token);
		if (connection == null || !connection.isBound() || userId == null || !userId.equals(connection.fUserId)
				|| !connection.fWorkspaceName.equals(payload.get("workspace")) || operationId == null) {
			return Map.of("status", HttpServletResponse.SC_NOT_FOUND);
		}

		String action = asString(payload.get("action"));
		if ("add".equals(action)) {
			String query = asString(payload.get("query"));
			if (query == null || query.trim().isEmpty()) {
				return Map.of("status", HttpServletResponse.SC_BAD_REQUEST);
			}
			Map<String, Object> variables = (payload.get("variables") instanceof Map)
					? (Map<String, Object>) payload.get("variables") : null;
			connection.addOperation(operationId,
					new GraphQLRequest(query, asString(payload.get("operationName")), variables));
			return Map.of("status", HttpServletResponse.SC_ACCEPTED);
		}
		if ("stop".equals(action)) {
			connection.stopOperation(operationId);
			return Map.of("status", HttpServletResponse.SC_NO_CONTENT);
		}
		return Map.of("status", HttpServletResponse.SC_BAD_REQUEST);
	}

	/**
	 * The user the credentials sign in as on the workspace, or {@code null}
	 * when they do not sign in. Independent of the credentials' class, so a
	 * stream opened with a SAML login and a request carrying the
	 * authentication token compare equal.
	 */
	private static String userIdOf(String workspaceName, Credentials credentials) {
		Session session = null;
		try {
			session = CmsService.getRepository().login(credentials, workspaceName);
			return session.getUserID();
		} catch (Throwable ex) {
			return null;
		} finally {
			if (session != null) {
				try {
					session.logout();
				} catch (Throwable ignore) {}
			}
		}
	}

	private static int intOf(Object value) {
		return (value instanceof Number) ? ((Number) value).intValue() : HttpServletResponse.SC_NOT_FOUND;
	}

	/** A stream's entry in the replicated table. */
	private static final class Owner {
		String node;
		String user;
		String workspace;

		static Owner of(ClusterTransport transport, String token) {
			String value = transport.getTable(STREAMS_TABLE).get(token);
			if (value == null) {
				return null;
			}
			try {
				Owner owner = GSON.fromJson(value, Owner.class);
				return (owner == null || owner.node == null) ? null : owner;
			} catch (Exception ex) {
				return null;
			}
		}
	}

	/** Reads the operationId from the request's {@code extensions.operationId} (or {@code ?operationId}). */
	private static String operationId(Map<String, Object> body, HttpServletRequest request) {
		Object extensions = body.get("extensions");
		if (extensions instanceof Map) {
			Object id = ((Map<?, ?>) extensions).get("operationId");
			if (id != null) {
				return id.toString();
			}
		}
		return request.getParameter("operationId");
	}

	private static String asString(Object value) {
		return (value == null) ? null : value.toString();
	}

	/**
	 * One client SSE stream and the set of operations multiplexed onto it. Holds no
	 * JCR session of its own — each operation's payload mapper reads JCR on demand.
	 * All frames (events and heartbeats) go through a bounded outbound queue drained
	 * by the writer pool; producers never block, and a client that stops reading is
	 * detected by the write-error flag, the stall watchdog, or queue overflow —
	 * whichever fires first — and torn down.
	 */
	private static final class StreamConnection {
		private final String fWorkspaceName;
		private final Credentials fCredentials;
		private final String fToken;
		private final ConcurrentMap<String, Operation> fOperations = new ConcurrentHashMap<>();
		private final AtomicBoolean fClosed = new AtomicBoolean();
		private final BlockingQueue<String> fOutbound = new LinkedBlockingQueue<>(MAX_PENDING_FRAMES);
		private final AtomicBoolean fWriting = new AtomicBoolean();
		/** Start of the in-flight blocking write, or 0 when none (watchdog input). */
		private volatile long fWriteStartedAt;
		private volatile AsyncContext fAsyncContext;
		private volatile ScheduledFuture<?> fHeartbeat;
		private volatile ScheduledFuture<?> fReservationTimeout;
		/** The signed-in user, recorded in a cluster only (forwarded changes must be theirs). */
		private volatile String fUserId;

		StreamConnection(String workspaceName, Credentials credentials, String token) {
			fWorkspaceName = workspaceName;
			fCredentials = credentials;
			fToken = token;
		}

		/** Whether the SSE stream is open on this node (not just reserved). */
		boolean isBound() {
			return fAsyncContext != null;
		}

		/** Records in the cluster's table that this node holds the token. */
		void announce(long ttlMillis) {
			ClusterTransport transport = CmsService.getClusterTransport();
			if (transport == null || fClosed.get()) {
				return;
			}
			Map<String, Object> owner = new LinkedHashMap<>();
			owner.put("node", transport.getNodeId());
			owner.put("user", fUserId);
			owner.put("workspace", fWorkspaceName);
			try {
				transport.getTable(STREAMS_TABLE).put(fToken, GSON.toJson(owner), ttlMillis);
			} catch (Throwable ex) {
				// A stream the table does not know is answered 404 by every
				// other node, so the client keeps reconnecting: worth a warning.
				CmsService.getLogger(getClass()).warn("The subscription stream of user '" + fUserId + "' on workspace '"
						+ fWorkspaceName + "' could not be recorded in the cluster table; a change of it that reaches"
						+ " another node is answered 404: " + ex.getMessage());
			}
		}

		/** Removes the token from the cluster's table, unless another node has taken it over. */
		private void withdraw() {
			ClusterTransport transport = CmsService.getClusterTransport();
			if (transport == null) {
				return;
			}
			try {
				Owner owner = Owner.of(transport, fToken);
				if (owner != null && transport.getNodeId().equals(owner.node)) {
					transport.getTable(STREAMS_TABLE).remove(fToken);
				}
			} catch (Throwable ex) {
				CmsService.getLogger(getClass()).debug("Could not remove the stream from the cluster table.", ex);
			}
		}

		/** Drop the reservation if the SSE stream is never opened. */
		void scheduleReservationTimeout() {
			fReservationTimeout = SCHEDULER.schedule(() -> {
				if (fAsyncContext == null) {
					CONNECTIONS.remove(fToken);
					close();
				}
			}, RESERVATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		}

		/** Attach the opened SSE stream and start heartbeats. */
		void bind(AsyncContext asyncContext) {
			fAsyncContext = asyncContext;
			ScheduledFuture<?> reservation = fReservationTimeout;
			if (reservation != null) {
				reservation.cancel(false);
			}
			asyncContext.addListener(new AsyncListener() {
				@Override
				public void onComplete(AsyncEvent event) {
					terminate();
				}

				@Override
				public void onTimeout(AsyncEvent event) {
					terminate();
				}

				@Override
				public void onError(AsyncEvent event) {
					terminate();
				}

				@Override
				public void onStartAsync(AsyncEvent event) {}
			});
			fHeartbeat = SCHEDULER.scheduleAtFixedRate(this::sendHeartbeat,
					HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
			// The stream is here now, whichever node reserved it.
			announce(STREAM_OWNER_TTL_MS);
		}

		void addOperation(String operationId, GraphQLRequest request) {
			if (fClosed.get()) {
				return;
			}
			WorkspaceGraphQLEngineProvider engine = CmsService.getWorkspaceGraphQLEngineProvider(fWorkspaceName);
			if (engine == null || !engine.isAvailable()) {
				writeNext(operationId, errorResult("The GraphQL schema is not available for the workspace: " + fWorkspaceName));
				writeComplete(operationId);
				return;
			}
			SubscriptionStream stream;
			try {
				stream = engine.executeSubscription(request, fCredentials);
			} catch (Exception ex) {
				CmsService.getLogger(getClass()).warn("Failed to start subscription operation " + operationId, ex);
				writeNext(operationId, errorResult(ex.getMessage()));
				writeComplete(operationId);
				return;
			}
			if (!stream.isStarted()) {
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("errors", stream.getErrors());
				writeNext(operationId, result);
				writeComplete(operationId);
				return;
			}
			Operation operation = new Operation(operationId);
			Operation previous = fOperations.put(operationId, operation);
			if (previous != null) {
				previous.cancel();
			}
			stream.getPublisher().subscribe(new OperationSubscriber(this, operation));
		}

		void stopOperation(String operationId) {
			Operation operation = fOperations.remove(operationId);
			if (operation != null) {
				operation.cancel();
			}
		}

		private void sendHeartbeat() {
			write(": heartbeat\n\n");
			// Keeps the entry alive; it expires on its own when this node dies.
			announce(STREAM_OWNER_TTL_MS);
		}

		/** {@code event: next} carrying {@code {id, payload}} for one operation. */
		private void writeNext(String operationId, Map<String, Object> payload) {
			Map<String, Object> message = new LinkedHashMap<>();
			message.put("id", operationId);
			message.put("payload", payload);
			writeEvent("next", GSON.toJson(message));
		}

		/** {@code event: complete} carrying {@code {id}} for one operation. */
		private void writeComplete(String operationId) {
			Map<String, Object> message = new LinkedHashMap<>();
			message.put("id", operationId);
			writeEvent("complete", GSON.toJson(message));
		}

		private void writeEvent(String event, String data) {
			write("event: " + event + "\ndata: " + data + "\n\n");
		}

		/**
		 * Queues one SSE frame for the writer pool. Never blocks the caller, so the
		 * scheduler and event-delivery threads cannot be held up by one stalled
		 * client. A full queue means the client has not kept up for a long time —
		 * the connection is torn down rather than buffered without bound.
		 */
		private void write(String frame) {
			if (fClosed.get() || fAsyncContext == null) {
				return;
			}
			if (!fOutbound.offer(frame)) {
				terminate();
				return;
			}
			if (fWriting.compareAndSet(false, true)) {
				try {
					WRITER_EXECUTOR.execute(this::drainOutbound);
				} catch (Throwable ex) {
					fWriting.set(false);
				}
			}
		}

		/** Drains queued frames onto the servlet response. Runs on the writer pool. */
		private void drainOutbound() {
			for (;;) {
				String frame = fOutbound.poll();
				if (frame == null) {
					fWriting.set(false);
					// Recheck: a frame may have arrived between poll() and the reset.
					if (fOutbound.isEmpty() || !fWriting.compareAndSet(false, true)) {
						return;
					}
					continue;
				}
				AsyncContext asyncContext = fAsyncContext;
				if (fClosed.get() || asyncContext == null) {
					fWriting.set(false);
					return;
				}
				try {
					PrintWriter writer = asyncContext.getResponse().getWriter();
					fWriteStartedAt = System.currentTimeMillis();
					try {
						writer.write(frame);
						writer.flush();
					} finally {
						fWriteStartedAt = 0L;
					}
					// PrintWriter swallows IOExceptions — a dead client only ever
					// shows up on the error flag, never as a thrown exception.
					if (writer.checkError()) {
						terminate();
						return;
					}
				} catch (Exception ex) {
					// Client gone or write failed — tear the whole connection down.
					terminate();
					return;
				}
			}
		}

		/**
		 * Watchdog hook: a write blocked past the stall timeout means the client is
		 * dead but its socket still absorbs nothing — completing the async context
		 * aborts the exchange and unblocks the writer thread.
		 */
		void terminateIfStalled(long now) {
			long startedAt = fWriteStartedAt;
			if (startedAt != 0L && (now - startedAt) >= WRITE_STALL_TIMEOUT_MS) {
				terminate();
			}
		}

		/** Completes the async response and releases everything. */
		private void terminate() {
			AsyncContext asyncContext = fAsyncContext;
			close();
			if (asyncContext != null) {
				try {
					asyncContext.complete();
				} catch (Exception ignore) {}
			}
		}

		/** Cancels every operation and drops the token. Idempotent. */
		private void close() {
			if (!fClosed.compareAndSet(false, true)) {
				return;
			}
			CONNECTIONS.remove(fToken, this);
			withdraw();
			ScheduledFuture<?> heartbeat = fHeartbeat;
			if (heartbeat != null) {
				heartbeat.cancel(false);
			}
			ScheduledFuture<?> reservation = fReservationTimeout;
			if (reservation != null) {
				reservation.cancel(false);
			}
			for (Operation operation : fOperations.values()) {
				operation.cancel();
			}
			fOperations.clear();
			fOutbound.clear();
		}

		private static Map<String, Object> errorResult(String message) {
			Map<String, Object> error = new LinkedHashMap<>();
			error.put("message", (message != null) ? message : "Subscription failed to start");
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("errors", java.util.List.of(error));
			return result;
		}
	}

	/** One multiplexed subscription operation; holds its reactive {@link Subscription} for cancellation. */
	private static final class Operation {
		private final String fId;
		private volatile Subscription fSubscription;
		private final AtomicBoolean fCancelled = new AtomicBoolean();

		Operation(String id) {
			fId = id;
		}

		void cancel() {
			fCancelled.set(true);
			Subscription subscription = fSubscription;
			if (subscription != null) {
				try {
					subscription.cancel();
				} catch (Throwable ignore) {}
			}
		}
	}

	/** Bridges one operation's {@code Publisher<ExecutionResult>} to tagged SSE frames. */
	private static final class OperationSubscriber implements Subscriber<ExecutionResult> {
		private final StreamConnection fConnection;
		private final Operation fOperation;

		OperationSubscriber(StreamConnection connection, Operation operation) {
			fConnection = connection;
			fOperation = operation;
		}

		@Override
		public void onSubscribe(Subscription subscription) {
			fOperation.fSubscription = subscription;
			if (fConnection.fClosed.get() || fOperation.fCancelled.get()) {
				subscription.cancel();
				return;
			}
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(ExecutionResult result) {
			fConnection.writeNext(fOperation.fId, result.toSpecification());
		}

		@Override
		public void onError(Throwable t) {
			CmsService.getLogger(getClass()).warn("Subscription stream error for operation " + fOperation.fId, t);
			fConnection.fOperations.remove(fOperation.fId);
			fConnection.writeComplete(fOperation.fId);
		}

		@Override
		public void onComplete() {
			fConnection.fOperations.remove(fOperation.fId);
			fConnection.writeComplete(fOperation.fId);
		}
	}
}
