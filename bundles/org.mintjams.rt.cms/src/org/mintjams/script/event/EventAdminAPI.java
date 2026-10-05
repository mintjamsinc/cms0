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

package org.mintjams.script.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.pubsub.TopicMessages;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.ScriptingContext;
import org.osgi.service.event.Event;

public class EventAdminAPI {

	private WorkspaceScriptContext fContext;

	public EventAdminAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static EventAdminAPI get(ScriptingContext context) {
		return (EventAdminAPI) context.getAttribute(EventAdminAPI.class.getSimpleName());
	}

	public void postEvent(String topic, Map<String, ?> properties) {
		CmsService.postEvent(new Event(topic, properties));
	}

	/**
	 * Publishes a topic message to the subscribers of this workspace
	 * ({@code Subscription.topicMessage}), on every node of the cluster.
	 * The payload is anything that can be written as JSON. Who publishes it
	 * is the user of this script's session.
	 *
	 * @see #publish(String, Object, Map)
	 */
	public String publish(String topic, Object payload) {
		return publish(topic, payload, null);
	}

	/**
	 * Publishes a topic message with options:
	 * <ul>
	 *   <li>{@code recipients} — a collection of user ids; only they receive
	 *       the message;</li>
	 *   <li>{@code path} — a node of this workspace; only those who can read
	 *       it receive the message;</li>
	 *   <li>{@code cluster} — {@code false} to post on this node only (the
	 *       default is the whole cluster).</li>
	 * </ul>
	 * With neither {@code recipients} nor {@code path}, every subscriber of
	 * the workspace receives it.
	 *
	 * @return the id of the message
	 */
	@SuppressWarnings("unchecked")
	public String publish(String topic, Object payload, Map<String, ?> options) {
		Collection<String> recipients = null;
		String path = null;
		boolean cluster = true;
		if (options != null) {
			Object value = options.get("recipients");
			if (value instanceof Collection) {
				recipients = new ArrayList<>();
				for (Object id : (Collection<Object>) value) {
					if (id != null) {
						recipients.add(id.toString());
					}
				}
			} else if (value != null) {
				recipients = List.of(value.toString());
			}
			Object pathValue = options.get("path");
			if (pathValue != null) {
				path = pathValue.toString();
			}
			Object clusterValue = options.get("cluster");
			if (clusterValue != null) {
				cluster = Boolean.parseBoolean(clusterValue.toString());
			}
		}
		String publisher;
		try {
			publisher = fContext.getSession().getUserID();
		} catch (Throwable ex) {
			throw new IllegalStateException(ex.getMessage(), ex);
		}
		return TopicMessages.publish(fContext.getWorkspaceName(), topic, payload, publisher, recipients, path, cluster);
	}

	public ResourceEventHandlerRegistration.Builder beginResourceEventHandlerRegistration() {
		return ResourceEventHandlerRegistration.newBuilder(fContext);
	}

}
