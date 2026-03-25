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

package org.mintjams.rt.cms.internal.graphql;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.Session;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.cms.event.CmsEvent;
import org.mintjams.rt.cms.internal.cms.event.CmsEventHandler;
import org.mintjams.rt.cms.internal.cms.event.WorkspaceCmsEventManager;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Handles SSE (Server-Sent Events) streaming for GraphQL subscriptions.
 *
 * Connects to the CMS event system and forwards matching events to the client
 * as SSE messages in a format compatible with the Webtop SubscriptionClient.
 */
public class GraphQLStreamHandler {

	private static final Gson GSON = new GsonBuilder().create();
	private static final long HEARTBEAT_INTERVAL_MS = 30000; // 30 seconds
	private static final long ASYNC_TIMEOUT_MS = 0; // No timeout (infinite)

	private final String workspaceName;

	public GraphQLStreamHandler(String workspaceName) {
		this.workspaceName = workspaceName;
	}

	/**
	 * Start SSE streaming for the given subscriptions.
	 */
	public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
		// Parse subscription strings from query parameter
		String subscriptionsParam = request.getParameter("subscriptions");
		if (subscriptionsParam == null || subscriptionsParam.isEmpty()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.setContentType("application/json");
			response.getWriter().write("{\"error\":\"subscriptions parameter is required\"}");
			return;
		}

		List<String> subscriptionStrings;
		try {
			subscriptionStrings = GSON.fromJson(subscriptionsParam, new TypeToken<List<String>>() {}.getType());
		} catch (Exception e) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.setContentType("application/json");
			response.getWriter().write("{\"error\":\"Invalid subscriptions format\"}");
			return;
		}

		if (subscriptionStrings == null || subscriptionStrings.isEmpty()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.setContentType("application/json");
			response.getWriter().write("{\"error\":\"At least one subscription is required\"}");
			return;
		}

		// Parse subscription matchers
		List<SubscriptionMatcher> matchers;
		try {
			matchers = SubscriptionMatcher.parseAll(subscriptionStrings);
		} catch (IllegalArgumentException e) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.setContentType("application/json");
			response.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
			return;
		}

		// Set SSE headers
		response.setContentType("text/event-stream");
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("Connection", "keep-alive");
		response.setHeader("X-Accel-Buffering", "no"); // Disable nginx buffering
		response.setStatus(HttpServletResponse.SC_OK);
		response.flushBuffer();

		// Start async context
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(ASYNC_TIMEOUT_MS);

		// Get workspace event manager
		WorkspaceCmsEventManager eventManager = CmsService.getWorkspaceCmsEventManager(workspaceName);
		if (eventManager == null) {
			try {
				PrintWriter writer = response.getWriter();
				writer.write("data: {\"error\":\"Workspace not found: " + workspaceName + "\"}\n\n");
				writer.flush();
			} catch (IOException ignore) {}
			asyncContext.complete();
			return;
		}

		// Create event handler that forwards events to SSE
		StreamContext streamContext = new StreamContext(asyncContext, matchers, eventManager);
		streamContext.start();
	}

	/**
	 * Manages the lifecycle of an SSE stream connection.
	 */
	private class StreamContext implements CmsEventHandler {
		private final AsyncContext asyncContext;
		private final List<SubscriptionMatcher> matchers;
		private final WorkspaceCmsEventManager eventManager;
		private final Timer heartbeatTimer;
		private volatile boolean closed = false;

		StreamContext(AsyncContext asyncContext, List<SubscriptionMatcher> matchers,
				WorkspaceCmsEventManager eventManager) {
			this.asyncContext = asyncContext;
			this.matchers = matchers;
			this.eventManager = eventManager;
			this.heartbeatTimer = new Timer("sse-heartbeat-" + workspaceName, true);
		}

		void start() {
			// Register async listener for cleanup
			asyncContext.addListener(new AsyncListener() {
				@Override
				public void onComplete(AsyncEvent event) {
					cleanup();
				}

				@Override
				public void onTimeout(AsyncEvent event) {
					cleanup();
				}

				@Override
				public void onError(AsyncEvent event) {
					cleanup();
				}

				@Override
				public void onStartAsync(AsyncEvent event) {
					// No-op
				}
			});

			// Register as CMS event handler
			eventManager.addEventHandler(this);

			// Start heartbeat timer
			heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
				@Override
				public void run() {
					sendHeartbeat();
				}
			}, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS);

			CmsService.getLogger(getClass()).info(
					"SSE stream started for workspace: " + workspaceName
							+ ", subscriptions: " + matchers.size());
		}

		@Override
		public void handleEvent(CmsEvent event) {
			if (closed) return;

			for (SubscriptionMatcher matcher : matchers) {
				if (matcher.matches(event)) {
					if ("preferenceChanged".equals(matcher.getType())) {
						sendPreferenceChangedEvent(matcher, event);
					} else {
						sendEvent(matcher.getSubscriptionString(), event);
					}
				}
			}
		}

		/**
		 * Send a preferenceChanged SSE event.
		 * Opens a system JCR session to read the current property values from
		 * /home/users/{userId}/preferences/{category}/jcr:content and includes
		 * them directly in the payload so the client needs no follow-up query.
		 */
		private void sendPreferenceChangedEvent(SubscriptionMatcher matcher, CmsEvent event) {
			if (closed) return;

			String userId = matcher.getParams().get("userId");
			String eventPath = event.getPath();

			// Extract category: first path segment after /home/users/{userId}/preferences/
			String preferencesPrefix = "/home/users/" + userId + "/preferences/";
			String relative = eventPath.substring(preferencesPrefix.length());
			String category = relative.split("/")[0];

			Session jcrSession = null;
			try {
				jcrSession = CmsService.getRepository()
						.login(new CmsServiceCredentials(), "system");

				String contentNodePath = preferencesPrefix + category + "/jcr:content";
				Map<String, Object> data = new LinkedHashMap<>();

				if (jcrSession.nodeExists(contentNodePath)) {
					Node contentNode = jcrSession.getNode(contentNodePath);
					PropertyIterator props = contentNode.getProperties();
					while (props.hasNext()) {
						Property prop = props.nextProperty();
						String propName = prop.getName();
						// Skip internal JCR system properties
						if (propName.startsWith("jcr:")) continue;
						if (!prop.isMultiple() && prop.getType() == PropertyType.STRING) {
							data.put(propName, prop.getString());
						}
					}
				}

				Map<String, Object> eventData = new LinkedHashMap<>();
				eventData.put("category", category);
				eventData.put("data", data);
				eventData.put("timestamp", Instant.now().toString());
				eventData.put("userId", userId);

				Map<String, Object> message = new LinkedHashMap<>();
				message.put("subscription", matcher.getSubscriptionString());
				message.put("data", eventData);

				String json = GSON.toJson(message);

				PrintWriter writer = asyncContext.getResponse().getWriter();
				synchronized (writer) {
					writer.write("data: " + json + "\n\n");
					writer.flush();
				}
			} catch (IOException e) {
				CmsService.getLogger(getClass()).debug("SSE write failed, closing stream", e);
				cleanup();
				try {
					asyncContext.complete();
				} catch (Exception ignore) {}
			} catch (Exception e) {
				CmsService.getLogger(getClass()).warn(
						"Failed to read preferences node for subscription", e);
			} finally {
				if (jcrSession != null) {
					try { jcrSession.logout(); } catch (Exception ignore) {}
				}
			}
		}

		private void sendEvent(String subscriptionString, CmsEvent event) {
			if (closed) return;

			try {
				Map<String, Object> eventData = new LinkedHashMap<>();
				// Extract event type from topic (last segment)
				String topic = event.getTopic();
				String eventType = topic.substring(topic.lastIndexOf('/') + 1);
				eventData.put("eventType", eventType);
				eventData.put("path", event.getPath());
				eventData.put("timestamp", Instant.now().toString());
				eventData.put("userId", event.getUserID());

				if (event.getIdentifier() != null) {
					eventData.put("identifier", event.getIdentifier());
				}
				if (event.getType() != null) {
					eventData.put("nodeType", event.getType());
				}
				if (event.getSourcePath() != null) {
					eventData.put("sourcePath", event.getSourcePath());
				}

				Map<String, Object> message = new LinkedHashMap<>();
				message.put("subscription", subscriptionString);
				message.put("data", eventData);

				String json = GSON.toJson(message);

				PrintWriter writer = asyncContext.getResponse().getWriter();
				synchronized (writer) {
					writer.write("data: " + json + "\n\n");
					writer.flush();
				}
			} catch (IOException e) {
				// Client disconnected
				CmsService.getLogger(getClass()).debug("SSE write failed, closing stream", e);
				cleanup();
				try {
					asyncContext.complete();
				} catch (Exception ignore) {}
			}
		}

		private void sendHeartbeat() {
			if (closed) return;

			try {
				PrintWriter writer = asyncContext.getResponse().getWriter();
				synchronized (writer) {
					writer.write(": heartbeat\n\n");
					writer.flush();
				}
			} catch (IOException e) {
				// Client disconnected
				cleanup();
				try {
					asyncContext.complete();
				} catch (Exception ignore) {}
			}
		}

		private synchronized void cleanup() {
			if (closed) return;
			closed = true;

			heartbeatTimer.cancel();
			eventManager.removeEventHandler(this);

			CmsService.getLogger(getClass()).info(
					"SSE stream closed for workspace: " + workspaceName);
		}
	}
}
