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

package org.mintjams.rt.cms.internal.pubsub;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.jcr.Session;

import org.mintjams.jcr.security.GuestPrincipal;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.graphql.event.OsgiEventPublisher;
import org.mintjams.rt.cms.internal.security.ServiceUserCredentials;
import org.mintjams.rt.cms.internal.util.ISO8601;
import org.osgi.service.event.Event;
import org.reactivestreams.Publisher;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The topic messages of a workspace: a general-purpose publish/subscribe
 * channel for applications, carried by the platform so that no application
 * has to build its own transport.
 *
 * <p>A message is published to a <em>topic</em>, a slash-separated name such as
 * {@code game/reversi/rooms/4f2a}, with a JSON payload. It is delivered to the
 * {@code Subscription.topicMessage} subscribers of the same workspace whose
 * topic pattern matches and who are in its <em>audience</em>:
 *
 * <ul>
 *   <li>{@code recipients} — the user ids the message is for; nobody else sees
 *       it, however they subscribe;</li>
 *   <li>{@code path} — whoever can read that node of the workspace, checked
 *       for each subscriber in a session of their own (so the repository's
 *       access control decides, as for {@code nodeChanged});</li>
 *   <li>neither — every subscriber of the workspace.</li>
 * </ul>
 *
 * <p>A message is a notification, not a record: nothing is stored, a subscriber
 * that is not connected when it is published never sees it, and a slow one may
 * miss some (the stream drops on lag). An application keeps its state in the
 * repository and uses a message to tell its clients to read again, or to carry
 * a small, self-contained event. The payload is data from its publisher:
 * {@code userId} names who published it, and a receiver that trusts a message
 * checks that.
 *
 * <p>Every message is posted locally as one OSGi event on {@link #TOPIC} and,
 * in a cluster, published on the signal bus so the other nodes re-emit it for
 * their own subscribers. The signal bus carries JSON scalars only, which is why
 * the payload and the recipients travel as strings.
 */
public final class TopicMessages {

	/** The one OSGi topic every message is posted on; the message's own topic is a property. */
	public static final String TOPIC = "org/mintjams/rt/cms/pubsub/MESSAGE";

	public static final String PROP_WORKSPACE = "workspace";
	public static final String PROP_TOPIC = "topic";
	public static final String PROP_PAYLOAD = "payload";
	public static final String PROP_PUBLISHER = "publisher";
	public static final String PROP_RECIPIENTS = "recipients";
	public static final String PROP_PATH = "path";
	public static final String PROP_MESSAGE_ID = "messageId";
	public static final String PROP_TIMESTAMP = "timestamp";

	/** How long a serialized payload may be, in characters. */
	public static final int MAX_PAYLOAD_LENGTH = 16 * 1024;
	public static final int MAX_TOPIC_LENGTH = 255;
	public static final int MAX_RECIPIENTS = 100;

	private static final Pattern TOPIC_PATTERN =
			Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.\\-]*(/[A-Za-z0-9][A-Za-z0-9_.\\-]*)*");
	private static final Pattern SUBSCRIPTION_PATTERN =
			Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.\\-]*(/[A-Za-z0-9][A-Za-z0-9_.\\-]*)*(/\\*)?|\\*");
	private static final Pattern USER_ID_PATTERN = Pattern.compile("[^/:\\[\\]|*\\p{Cntrl},]{1,255}");

	private static final ObjectMapper JSON = new ObjectMapper();

	private TopicMessages() {}

	// ---- publishing ----------------------------------------------------------

	/**
	 * Publishes a message to the subscribers of the workspace, on this node
	 * and, when {@code clusterWide}, on every other node of the cluster.
	 *
	 * @param workspaceName the workspace whose subscribers receive it
	 * @param topic         the topic, see {@link #checkTopic(String)}
	 * @param payload       anything Jackson serializes: a Map, a List, a scalar
	 *                      or {@code null}
	 * @param publisher     the user id of whoever publishes it
	 * @param recipients    the user ids the message is for, or null/empty for
	 *                      no such restriction
	 * @param path          the node whose readers the message is for, or null
	 * @param clusterWide   whether to publish on the cluster signal bus as well
	 * @return the id of the message
	 * @throws IllegalArgumentException when the topic, the payload or the
	 *                                  recipients are not acceptable
	 */
	public static String publish(String workspaceName, String topic, Object payload, String publisher,
			Collection<String> recipients, String path, boolean clusterWide) {
		if (workspaceName == null || workspaceName.isEmpty()) {
			throw new IllegalArgumentException("A workspace is required.");
		}
		String checkedTopic = checkTopic(topic);
		String json = serialize(payload);
		List<String> checkedRecipients = checkRecipients(recipients);
		String checkedPath = checkPath(path);

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(PROP_WORKSPACE, workspaceName);
		properties.put(PROP_TOPIC, checkedTopic);
		if (json != null) {
			properties.put(PROP_PAYLOAD, json);
		}
		if (publisher != null && !publisher.isEmpty()) {
			properties.put(PROP_PUBLISHER, publisher);
		}
		if (!checkedRecipients.isEmpty()) {
			properties.put(PROP_RECIPIENTS, String.join(",", checkedRecipients));
		}
		if (checkedPath != null) {
			properties.put(PROP_PATH, checkedPath);
		}
		String messageId = UUID.randomUUID().toString();
		properties.put(PROP_MESSAGE_ID, messageId);
		properties.put(PROP_TIMESTAMP, ISO8601.now());

		CmsService.postEvent(TOPIC, properties);
		if (clusterWide) {
			CmsService.broadcast(TOPIC, workspaceName, properties);
		}
		return messageId;
	}

	/** A topic: slash-separated names of letters, digits, {@code _ . -}; up to 255 characters. */
	public static String checkTopic(String topic) {
		String value = (topic == null) ? "" : topic.trim();
		if (value.isEmpty()) {
			throw new IllegalArgumentException("A topic is required.");
		}
		if (value.length() > MAX_TOPIC_LENGTH || !TOPIC_PATTERN.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid topic: " + topic);
		}
		return value;
	}

	/**
	 * What a subscriber may subscribe to: a topic, a topic followed by
	 * {@code /*} (that topic and everything under it), or {@code *} alone.
	 */
	public static String checkSubscriptionTopic(String topic) {
		String value = (topic == null) ? "" : topic.trim();
		if (value.isEmpty()) {
			throw new IllegalArgumentException("A topic is required.");
		}
		if (value.length() > MAX_TOPIC_LENGTH + 2 || !SUBSCRIPTION_PATTERN.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid topic: " + topic);
		}
		return value;
	}

	private static String serialize(Object payload) {
		if (payload == null) {
			return null;
		}
		String json;
		try {
			json = JSON.writeValueAsString(payload);
		} catch (Throwable ex) {
			throw new IllegalArgumentException("The payload cannot be written as JSON: " + ex.getMessage(), ex);
		}
		if (json.length() > MAX_PAYLOAD_LENGTH) {
			throw new IllegalArgumentException("The payload is too large: " + json.length() + " characters, at most "
					+ MAX_PAYLOAD_LENGTH + ".");
		}
		return json;
	}

	private static List<String> checkRecipients(Collection<String> recipients) {
		if (recipients == null || recipients.isEmpty()) {
			return Collections.emptyList();
		}
		Set<String> ids = new HashSet<>();
		List<String> checked = new ArrayList<>();
		for (String id : recipients) {
			String value = (id == null) ? "" : id.trim();
			if (value.isEmpty()) {
				continue;
			}
			if (!USER_ID_PATTERN.matcher(value).matches()) {
				throw new IllegalArgumentException("Invalid recipient: " + id);
			}
			if (ids.add(value)) {
				checked.add(value);
			}
		}
		if (checked.size() > MAX_RECIPIENTS) {
			throw new IllegalArgumentException("At most " + MAX_RECIPIENTS + " recipients.");
		}
		return checked;
	}

	private static String checkPath(String path) {
		if (path == null) {
			return null;
		}
		String value = path.trim();
		if (value.isEmpty()) {
			return null;
		}
		if (!value.startsWith("/")) {
			throw new IllegalArgumentException("The path must be absolute: " + path);
		}
		return value;
	}

	// ---- subscribing ---------------------------------------------------------

	/**
	 * The messages of the workspace that match the topic pattern and are for
	 * the subscriber, as a reactive-streams publisher for one GraphQL
	 * subscription. Each value is a map of {@code topic}, {@code payload},
	 * {@code userId}, {@code messageId} and {@code timestamp}.
	 *
	 * @param workspaceName the subscriber's workspace
	 * @param subscriberId  the subscriber's user id ({@code anonymous} when not
	 *                      signed in)
	 * @param topic         see {@link #checkSubscriptionTopic(String)}
	 */
	public static Publisher<Object> subscribe(String workspaceName, String subscriberId, String topic) {
		String pattern = checkSubscriptionTopic(topic);
		String subscriber = (subscriberId == null || subscriberId.isEmpty()) ? GuestPrincipal.NAME : subscriberId;
		return new OsgiEventPublisher(new String[] { TOPIC },
				event -> workspaceName.equals(event.getProperty(PROP_WORKSPACE))
						&& topicMatches(pattern, (String) event.getProperty(PROP_TOPIC))
						&& isRecipient(event, subscriber),
				event -> {
					if (!isReader(event, workspaceName, subscriber)) {
						return null;
					}
					return toMessage(event);
				});
	}

	/** Whether the subscription pattern covers the topic. */
	public static boolean topicMatches(String pattern, String topic) {
		if (topic == null) {
			return false;
		}
		if ("*".equals(pattern)) {
			return true;
		}
		if (pattern.endsWith("/*")) {
			String prefix = pattern.substring(0, pattern.length() - 2);
			return topic.equals(prefix) || topic.startsWith(prefix + "/");
		}
		return pattern.equals(topic);
	}

	/** The cheap part of the audience: the recipients named on the message. */
	private static boolean isRecipient(Event event, String subscriber) {
		Object recipients = event.getProperty(PROP_RECIPIENTS);
		if (recipients == null) {
			return true;
		}
		for (String id : recipients.toString().split(",")) {
			if (id.equals(subscriber)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The costly part of the audience, run as the event is dispatched: whether the
	 * subscriber can read the node the message is about. The subscriber is
	 * impersonated in a short-lived session so that the repository's access
	 * control decides.
	 */
	private static boolean isReader(Event event, String workspaceName, String subscriber) {
		Object path = event.getProperty(PROP_PATH);
		if (path == null) {
			return true;
		}
		Session probe = null;
		try {
			probe = CmsService.getRepository().login(new ServiceUserCredentials(subscriber), workspaceName);
			probe.getNode(path.toString());
			return true;
		} catch (Throwable notVisible) {
			return false;
		} finally {
			if (probe != null) {
				try {
					probe.logout();
				} catch (Throwable ignore) {}
			}
		}
	}

	private static Map<String, Object> toMessage(Event event) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("topic", event.getProperty(PROP_TOPIC));
		message.put("payload", parse(event.getProperty(PROP_PAYLOAD)));
		message.put("userId", event.getProperty(PROP_PUBLISHER));
		message.put("messageId", event.getProperty(PROP_MESSAGE_ID));
		Object timestamp = event.getProperty(PROP_TIMESTAMP);
		message.put("timestamp", (timestamp != null) ? timestamp : ISO8601.now());
		return message;
	}

	private static Object parse(Object payload) {
		if (payload == null) {
			return null;
		}
		try {
			return JSON.readValue(payload.toString(), Object.class);
		} catch (Throwable ex) {
			return null;
		}
	}

}
