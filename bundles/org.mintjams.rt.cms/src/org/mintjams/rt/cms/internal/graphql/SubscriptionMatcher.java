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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mintjams.rt.cms.internal.cms.event.CmsEvent;
import org.mintjams.rt.cms.internal.job.JobNodes;

/**
 * Parses GraphQL subscription strings and matches CMS events.
 *
 * Supported subscription formats:
 * - nodeChanged(path: "/content", deep: true)
 * - nodeChanged(path: "/content")
 * - nodeChanged
 * - jobProgress(jobId: "...")
 */
public class SubscriptionMatcher {

	private static final Pattern SUBSCRIPTION_PATTERN = Pattern.compile(
			"^(\\w+)(?:\\((.+)\\))?$", Pattern.DOTALL);

	private static final Pattern PARAM_PATTERN = Pattern.compile(
			"(\\w+)\\s*:\\s*(?:\"([^\"]*)\"|([a-zA-Z0-9]+))");

	private final String subscriptionString;
	private final String type;
	private final Map<String, String> params;

	private SubscriptionMatcher(String subscriptionString, String type, Map<String, String> params) {
		this.subscriptionString = subscriptionString;
		this.type = type;
		this.params = Collections.unmodifiableMap(params);
	}

	/**
	 * Parse a subscription string into a matcher.
	 *
	 * @param subscription e.g. "nodeChanged(path: \"/content\", deep: true)"
	 * @return parsed matcher
	 * @throws IllegalArgumentException if the subscription string is invalid
	 */
	public static SubscriptionMatcher parse(String subscription) {
		String trimmed = subscription.trim();
		Matcher m = SUBSCRIPTION_PATTERN.matcher(trimmed);
		if (!m.matches()) {
			throw new IllegalArgumentException("Invalid subscription format: " + subscription);
		}

		String type = m.group(1);
		Map<String, String> params = new LinkedHashMap<>();

		String paramStr = m.group(2);
		if (paramStr != null) {
			Matcher pm = PARAM_PATTERN.matcher(paramStr);
			while (pm.find()) {
				String key = pm.group(1);
				String value = pm.group(2) != null ? pm.group(2) : pm.group(3);
				params.put(key, value);
			}
		}

		return new SubscriptionMatcher(trimmed, type, params);
	}

	/**
	 * Parse multiple subscription strings.
	 */
	public static List<SubscriptionMatcher> parseAll(List<String> subscriptions) {
		List<SubscriptionMatcher> matchers = new ArrayList<>();
		for (String sub : subscriptions) {
			matchers.add(parse(sub));
		}
		return matchers;
	}

	/**
	 * Check if this subscription matches the given CMS event.
	 */
	public boolean matches(CmsEvent event) {
		if ("nodeChanged".equals(type)) {
			return matchesNodeChanged(event);
		}
		if ("preferenceChanged".equals(type)) {
			return matchesPreferenceChanged(event);
		}
		if ("wallpaperChanged".equals(type)) {
			return matchesWallpaperChanged(event);
		}
		if ("avatarChanged".equals(type)) {
			return matchesAvatarChanged(event);
		}
		if ("jobProgress".equals(type)) {
			return matchesJobProgress(event);
		}
		if ("workspaceChanged".equals(type)) {
			return matchesWorkspaceChanged(event);
		}
		// Other subscription types can be added here
		return false;
	}

	/**
	 * Match the parameterless {@code workspaceChanged} subscription against the
	 * repository-wide workspace-changed signal. Unlike the node-keyed
	 * subscriptions this matches on the event topic alone — the signal carries
	 * no path — so any workspace starting, stopping, or having its settings
	 * edited notifies every subscriber regardless of which workspace its stream
	 * is bound to.
	 */
	private boolean matchesWorkspaceChanged(CmsEvent event) {
		return org.mintjams.rt.cms.internal.CmsService.TOPIC_WORKSPACE_CHANGED.equals(event.getTopic());
	}

	/**
	 * Match jobProgress subscription against any node-change event whose path
	 * identifies the persisted job record. The job record lives at
	 * {@code /var/jobs/YYYY/MM/job-<jobId>}; both the {@code job-} file node
	 * and its {@code jcr:content} child are considered relevant.
	 *
	 * Parameters:
	 * - jobId: required
	 */
	private boolean matchesJobProgress(CmsEvent event) {
		String jobId = params.get("jobId");
		if (jobId == null || jobId.isEmpty()) {
			return false;
		}
		return JobNodes.isJobPath(event.getPath(), jobId);
	}

	/**
	 * Match nodeChanged subscription against a CMS event.
	 *
	 * Parameters:
	 * - path: The node path to watch (default: "/")
	 * - deep: Whether to also watch descendants beyond direct children
	 *         (default: false). The watch path itself and its direct children
	 *         always match; deep additionally matches deeper descendants.
	 */
	private boolean matchesNodeChanged(CmsEvent event) {
		String eventPath = event.getPath();
		if (eventPath == null) {
			return false;
		}

		String watchPath = params.getOrDefault("path", "/");
		boolean deep = "true".equalsIgnoreCase(params.getOrDefault("deep", "false"));

		// Match against event path (destination for MOVED events)
		if (matchesPath(eventPath, watchPath, deep)) {
			return true;
		}

		// For MOVED events, also match against source path
		String sourcePath = event.getSourcePath();
		if (sourcePath != null) {
			if (matchesPath(sourcePath, watchPath, deep)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Check if the event path matches the watch path based on deep flag.
	 *
	 * In this event model a change to a node (including changes to that node's
	 * own properties) is reported as an event whose path is the node itself; the
	 * affected property names are carried separately via
	 * {@link CmsEvent#getPropertyNames()}. Both depths therefore include the
	 * watch path itself, so that a subscriber watching a node is notified when
	 * that node's own properties change (or when it is added/moved/removed),
	 * not only when its descendants change:
	 * - deep == true:  the watch path itself or any descendant of it
	 * - deep == false: the watch path itself or a direct child of it
	 */
	private boolean matchesPath(String eventPath, String watchPath, boolean deep) {
		// Both depths match the watch path itself.
		if (eventPath.equals(watchPath)) {
			return true;
		}

		if (deep) {
			// Match any descendant of the watch path.
			return eventPath.startsWith(watchPath + "/");
		} else {
			// Match a direct child of the watch path.
			return getParentPath(eventPath).equals(watchPath);
		}
	}

	/**
	 * Match preferenceChanged subscription against a CMS event.
	 *
	 * Parameters:
	 * - userId: The user whose preferences to watch (required)
	 *
	 * Matches any node change under /home/users/{userId}/preferences/.
	 */
	private boolean matchesPreferenceChanged(CmsEvent event) {
		String eventPath = event.getPath();
		if (eventPath == null) return false;

		String userId = params.get("userId");
		if (userId == null || userId.isEmpty()) return false;

		String preferencesPrefix = "/home/users/" + userId + "/preferences/";
		return eventPath.startsWith(preferencesPrefix);
	}

	/**
	 * Match avatarChanged subscription against a CMS event.
	 *
	 * Parameters:
	 * - userId: The user whose avatar to watch (required)
	 *
	 * Matches any node change on /home/users/{userId}/avatar or its children.
	 */
	private boolean matchesAvatarChanged(CmsEvent event) {
		String eventPath = event.getPath();
		if (eventPath == null) return false;

		String userId = params.get("userId");
		if (userId == null || userId.isEmpty()) return false;

		String avatarPath = "/home/users/" + userId + "/avatar";
		return eventPath.equals(avatarPath) || eventPath.startsWith(avatarPath + "/");
	}

	/**
	 * Match wallpaperChanged subscription against a CMS event.
	 *
	 * Parameters:
	 * - userId: The user whose wallpapers to watch (required)
	 *
	 * Matches any node change under /home/users/{userId}/wallpapers/.
	 */
	private boolean matchesWallpaperChanged(CmsEvent event) {
		String eventPath = event.getPath();
		if (eventPath == null) return false;

		String userId = params.get("userId");
		if (userId == null || userId.isEmpty()) return false;

		String wallpapersPrefix = "/home/users/" + userId + "/wallpapers/";
		return eventPath.startsWith(wallpapersPrefix);
	}

	/**
	 * Get the parent path of a JCR path.
	 */
	private static String getParentPath(String path) {
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash <= 0) {
			return "/";
		}
		return path.substring(0, lastSlash);
	}

	/** Returns the original subscription string */
	public String getSubscriptionString() {
		return subscriptionString;
	}

	/** Returns the subscription type (e.g., "nodeChanged") */
	public String getType() {
		return type;
	}

	/** Returns the subscription parameters */
	public Map<String, String> getParams() {
		return params;
	}
}
