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

package org.mintjams.rt.cms.internal.bpm.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.impl.cfg.TransactionListener;
import org.camunda.bpm.engine.impl.cfg.TransactionState;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.mintjams.rt.cms.internal.CmsService;

/**
 * Central translation point between the Camunda process engine and the OSGi
 * EventAdmin service for a single workspace.
 *
 * <p>The engine hooks (BPMN parse listener, execution/task listeners and the
 * history event handler) all run <em>inside</em> the engine command and its
 * surrounding transaction. Posting an EventAdmin event directly from those
 * callbacks would publish notifications for work that may still be rolled back
 * (for example when a later command in the same transaction fails, or an
 * optimistic-locking conflict forces a retry). To give consumers a predictable,
 * &quot;only what actually happened&quot; contract, every event is deferred and
 * dispatched on {@link TransactionState#COMMITTED}. Events for a rolled-back
 * transaction are therefore never published.
 *
 * <p>EventAdmin delivery itself is asynchronous ({@code postEvent}), so it never
 * blocks the engine and never participates in the engine transaction.
 *
 * <p>Topic names follow the convention already established for CMS events:
 * the fully-qualified name of a stable Camunda public-API type with dots
 * replaced by slashes, suffixed with an upper-case action (for example
 * {@code org/camunda/bpm/engine/runtime/ProcessInstance/STARTED}). Every event
 * carries at least the {@code workspace} property so consumers can tell which
 * workspace engine emitted it.
 *
 * <p>Those events are posted on the node that committed the transaction only,
 * so whatever acts on them (a route sending a notice, a script) acts once per
 * cluster. What a screen shows must change on every node, though, so task,
 * process instance and incident events are announced a second time under
 * {@link #VIEW_TOPIC_PREFIX}: posted on this node and sent to the others,
 * which post them too. A view event carries the same properties as JSON
 * values (a date is an ISO 8601 string). It reaches each node at most once
 * and may be lost while a node is disconnected, so it is a hint that
 * something changed, never a record to act on.
 */
public class BpmEventDispatcher {

	/** Property carrying the originating workspace name on every event. */
	public static final String PROPERTY_WORKSPACE = "workspace";

	public static final String ACTION_STARTED = "STARTED";
	public static final String ACTION_ENDED = "ENDED";
	public static final String ACTION_TAKEN = "TAKEN";
	public static final String ACTION_CREATED = "CREATED";
	public static final String ACTION_ASSIGNED = "ASSIGNED";
	public static final String ACTION_COMPLETED = "COMPLETED";
	public static final String ACTION_UPDATED = "UPDATED";
	public static final String ACTION_DELETED = "DELETED";
	public static final String ACTION_RESOLVED = "RESOLVED";
	public static final String ACTION_MIGRATED = "MIGRATED";

	/**
	 * The root of the topics announced on every node of the cluster:
	 * {@code org/mintjams/rt/cms/bpm/<Type>/<ACTION>}, where the type is the
	 * simple name of the engine type ({@code Task}, {@code ProcessInstance},
	 * {@code Incident}).
	 */
	public static final String VIEW_TOPIC_PREFIX = "org/mintjams/rt/cms/bpm/";

	/** The longest text a view event carries in one property. */
	private static final int MAX_VIEW_TEXT_LENGTH = 500;

	private final String fWorkspaceName;

	public BpmEventDispatcher(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	/**
	 * Builds the EventAdmin topic for the given type and action, mirroring the
	 * {@code Class.getName().replace(".", "/") + "/" + action} convention used by
	 * the existing CMS deployment events.
	 */
	public String topic(Class<?> type, String action) {
		return type.getName().replace(".", "/") + "/" + action;
	}

	/**
	 * Returns a fresh, mutable property map pre-populated with the workspace
	 * name. Callers add event-specific properties with {@link #put}.
	 */
	public Map<String, Object> newProperties() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(PROPERTY_WORKSPACE, fWorkspaceName);
		return properties;
	}

	/**
	 * Adds a property only when the value is non-null. OSGi event properties are
	 * expected to be present, so optional engine attributes (tenant id, business
	 * key, assignee, ...) are simply omitted when absent rather than stored as
	 * {@code null}.
	 */
	public static void put(Map<String, Object> properties, String key, Object value) {
		if (value != null) {
			properties.put(key, value);
		}
	}

	/**
	 * Schedules the event for publication when the current engine transaction
	 * commits. When no command context is active (which should not happen from
	 * within the engine callbacks, but is handled defensively), the event is
	 * posted immediately.
	 */
	public void dispatchOnCommit(String topic, Map<String, Object> properties) {
		final Map<String, Object> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(properties));

		CommandContext commandContext = Context.getCommandContext();
		if (commandContext == null) {
			post(topic, snapshot);
			return;
		}

		commandContext.getTransactionContext().addTransactionListener(TransactionState.COMMITTED, new TransactionListener() {
			@Override
			public void execute(CommandContext commandContext) {
				post(topic, snapshot);
			}
		});
	}

	/**
	 * Builds the topic under which an event of the given type and action is
	 * announced on every node.
	 */
	public static String viewTopic(Class<?> type, String action) {
		return VIEW_TOPIC_PREFIX + type.getSimpleName() + "/" + action;
	}

	/**
	 * Schedules the event for publication when the current engine transaction
	 * commits, on this node under the engine topic and on every node under the
	 * view topic.
	 */
	public void dispatchOnCommit(Class<?> type, String action, Map<String, Object> properties) {
		dispatchOnCommit(topic(type, action), properties);

		final String viewTopic = viewTopic(type, action);
		final Map<String, Object> view = viewProperties(properties);
		CommandContext commandContext = Context.getCommandContext();
		if (commandContext == null) {
			announce(viewTopic, view);
			return;
		}

		commandContext.getTransactionContext().addTransactionListener(TransactionState.COMMITTED, new TransactionListener() {
			@Override
			public void execute(CommandContext commandContext) {
				announce(viewTopic, view);
			}
		});
	}

	/**
	 * Returns the properties as JSON values, which is what the other nodes
	 * receive, so that every node sees the same event.
	 */
	private static Map<String, Object> viewProperties(Map<String, Object> properties) {
		Map<String, Object> view = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : properties.entrySet()) {
			Object value = e.getValue();
			if (value instanceof Date) {
				value = ((Date) value).toInstant().toString();
			} else if (value instanceof Collection) {
				List<Object> list = new ArrayList<>();
				for (Object item : (Collection<?>) value) {
					list.add((item instanceof Number || item instanceof Boolean) ? item : String.valueOf(item));
				}
				value = list;
			} else if (value instanceof String) {
				String text = (String) value;
				if (text.length() > MAX_VIEW_TEXT_LENGTH) {
					value = text.substring(0, MAX_VIEW_TEXT_LENGTH);
				}
			} else if (!(value instanceof Number) && !(value instanceof Boolean)) {
				value = String.valueOf(value);
			}
			view.put(e.getKey(), value);
		}
		return Collections.unmodifiableMap(view);
	}

	/**
	 * Posts the view event on this node and sends it to the others.
	 */
	private void announce(String topic, Map<String, Object> properties) {
		post(topic, properties);
		try {
			CmsService.broadcast(topic, fWorkspaceName, properties);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Failed to send BPM event to the cluster: " + topic, ex);
		}
	}

	/**
	 * Posts the event to EventAdmin, isolating any delivery failure so it can
	 * never disturb the engine's post-commit processing.
	 */
	private void post(String topic, Map<String, Object> properties) {
		try {
			CmsService.postEvent(topic, properties);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).error("Failed to post BPM event to topic: " + topic, ex);
		}
	}

}
