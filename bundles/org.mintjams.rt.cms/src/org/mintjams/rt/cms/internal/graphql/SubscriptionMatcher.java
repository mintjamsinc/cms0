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

/**
 * Parses GraphQL subscription strings and matches CMS events.
 *
 * Supported subscription formats:
 * - nodeChanged(path: "/content", deep: true)
 * - nodeChanged(path: "/content")
 * - nodeChanged
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
		// Other subscription types can be added here
		return false;
	}

	/**
	 * Match nodeChanged subscription against a CMS event.
	 *
	 * Parameters:
	 * - path: The directory path to watch (default: "/")
	 * - deep: Whether to watch descendants (default: false)
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
	 */
	private boolean matchesPath(String eventPath, String watchPath, boolean deep) {
		if (deep) {
			// Match if event path starts with watch path or equals it
			return eventPath.equals(watchPath) || eventPath.startsWith(watchPath + "/");
		} else {
			// Match if event path is a direct child of watch path
			String parentPath = getParentPath(eventPath);
			return parentPath.equals(watchPath);
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
