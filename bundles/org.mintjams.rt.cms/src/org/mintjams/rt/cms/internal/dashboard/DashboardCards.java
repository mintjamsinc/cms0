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

package org.mintjams.rt.cms.internal.dashboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The sections and cards an app adds to the Dashboard, as its {@code app.yml}
 * declares them:
 *
 * <pre>
 * dashboard:
 *   sections:
 *     commerce:
 *       title: Commerce
 *       icon: bi-bag
 *       order: 300
 *   cards:
 *     orders:
 *       section: commerce
 *       title: Orders
 *       icon: bi-receipt
 *       order: 10
 *       size: 1                 # 1, 2 (twice as wide) or full
 *       scope: shared           # shared (one snapshot for everyone) | user
 *       roles: [commerce-staff] # who sees it; without it, whoever is offered the app
 *       summary: false          # true: the summary of its section
 *       detail: false           # true: the card can be maximized
 *       collect: node           # each node contributes a part
 *       provider: commerce.dashboard.Orders
 *       runAs: commerce-service-user
 *       options: { ... }        # handed to the provider as it is
 *       refresh:
 *         topics: [jp/example/commerce/order/*]
 *         paths: [/var/lib/commerce/orders]
 *         cards: [commerce/stock]
 *         interval: 300
 *         users: [assignee]     # user cards: the event properties naming who is concerned
 *         groups: [candidateGroups]
 *       open:
 *         options: { view: orders }
 * </pre>
 *
 * A card is named {@code <app relPath>/<name>}. What a card shows is made on
 * the server by its provider and pushed to the Dashboards that are open
 * ({@link DashboardWorker}); nothing here runs code.
 */
public final class DashboardCards {

	public static final String SCOPE_SHARED = "shared";
	public static final String SCOPE_USER = "user";

	private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
	/** A section is named by the platform (no dot) or under the maker's reversed domain. */
	private static final Pattern SECTION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*(\\.[A-Za-z0-9][A-Za-z0-9_-]*)*");
	private static final Pattern CLASS_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
	private static final Pattern ICON_PATTERN = Pattern.compile("bi-[a-z0-9-]+");
	private static final Pattern TOPIC_PATTERN = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*(/\\*)?");
	private static final Pattern CARD_ID_PATTERN = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]*/)+[A-Za-z0-9][A-Za-z0-9_-]*");

	private static final int MAX_SECTION_LENGTH = 128;
	private static final int MAX_ITEMS = 16;
	/** The shortest interval a card may ask for, in seconds. */
	private static final int MIN_INTERVAL = 5;

	private DashboardCards() {}

	/** A section of the Dashboard. */
	public static final class Section {
		public final String identifier;
		public final String app;
		public final String title;
		public final String icon;
		public final int order;

		Section(String identifier, String app, String title, String icon, int order) {
			this.identifier = identifier;
			this.app = app;
			this.title = title;
			this.icon = icon;
			this.order = order;
		}

		/** The section as the {@code App} type of the GraphQL schema lists it. */
		public Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("identifier", identifier);
			map.put("title", title);
			map.put("icon", icon);
			map.put("order", order);
			return map;
		}
	}

	/** A card of the Dashboard. */
	public static final class Card {
		/** {@code <app relPath>/<name>}. */
		public final String id;
		public final String app;
		public final String name;
		public final String section;
		public final String title;
		public final String icon;
		public final int order;
		public final String size;
		public final String scope;
		public final boolean summary;
		public final boolean detail;
		public final boolean collectByNode;
		/** The {@code roles} value that decides who sees the card, or {@code null} for everyone. */
		public final Object roles;
		public final String provider;
		public final String runAs;
		public final Map<String, Object> options;
		public final List<String> topics;
		public final List<String> paths;
		public final List<String> cards;
		public final List<String> users;
		public final List<String> groups;
		/** Seconds between two snapshots made without any event, or 0. */
		public final int interval;
		public final Map<String, Object> open;

		private Card(String app, String name, Map<String, Object> value, Object appRoles) {
			this.app = app;
			this.name = name;
			this.id = app + "/" + name;
			this.section = text(value.get("section"), SECTION_PATTERN, MAX_SECTION_LENGTH);
			this.title = (value.get("title") instanceof String) ? (String) value.get("title") : null;
			this.icon = text(value.get("icon"), ICON_PATTERN, 64);
			this.order = integer(value.get("order"), 1000);
			Object sizeObj = value.get("size");
			this.size = "full".equals(sizeObj) ? "full" : (integer(sizeObj, 1) >= 2) ? "2" : "1";
			this.scope = SCOPE_USER.equals(value.get("scope")) ? SCOPE_USER : SCOPE_SHARED;
			this.summary = Boolean.TRUE.equals(value.get("summary"));
			this.detail = Boolean.TRUE.equals(value.get("detail"));
			this.collectByNode = "node".equals(value.get("collect"));
			this.roles = value.containsKey("roles") ? orEmpty(value.get("roles")) : appRoles;
			this.provider = text(value.get("provider"), CLASS_PATTERN, 255);
			this.runAs = (value.get("runAs") instanceof String && !((String) value.get("runAs")).trim().isEmpty())
					? ((String) value.get("runAs")).trim()
					: null;
			this.options = map(value.get("options"));
			Map<String, Object> refresh = map(value.get("refresh"));
			this.topics = strings(refresh.get("topics"), TOPIC_PATTERN);
			this.paths = paths(refresh.get("paths"));
			this.cards = strings(refresh.get("cards"), CARD_ID_PATTERN);
			this.users = strings(refresh.get("users"), NAME_PATTERN);
			this.groups = strings(refresh.get("groups"), NAME_PATTERN);
			int seconds = integer(refresh.get("interval"), 0);
			this.interval = (seconds <= 0) ? 0 : Math.max(MIN_INTERVAL, seconds);
			this.open = map(value.get("open"));
		}

		public boolean isShared() {
			return SCOPE_SHARED.equals(scope);
		}

		/** Whether events decide which users a user card is made anew for. */
		public boolean namesUsers() {
			return !users.isEmpty() || !groups.isEmpty();
		}

		/** The card as the {@code App} type of the GraphQL schema lists it. */
		public Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("identifier", name);
			map.put("section", section);
			map.put("title", title);
			map.put("icon", icon);
			map.put("order", order);
			map.put("size", size);
			map.put("scope", scope);
			map.put("summary", summary);
			map.put("detail", detail);
			map.put("open", open.isEmpty() ? null : open);
			return map;
		}
	}

	/** What one app declares. */
	public static final class Declaration {
		public static final Declaration EMPTY = new Declaration(Collections.emptyList(), Collections.emptyList());

		public final List<Section> sections;
		public final List<Card> cards;

		Declaration(List<Section> sections, List<Card> cards) {
			this.sections = sections;
			this.cards = cards;
		}
	}

	/**
	 * Reads the {@code dashboard} map of an {@code app.yml}. A section or a
	 * card that is not well-formed is dropped; a card needs a section and a
	 * provider.
	 *
	 * @param app      the app's folder relative to the apps root
	 * @param appRoles the {@code roles} of the app, which a card without its
	 *                 own falls back to; {@code null} when the app has none
	 */
	@SuppressWarnings("unchecked")
	public static Declaration fromDescriptor(String app, Object dashboardObj, Object appRoles) {
		if (app == null || !(dashboardObj instanceof Map)) {
			return Declaration.EMPTY;
		}
		Map<String, Object> dashboard = (Map<String, Object>) dashboardObj;

		List<Section> sections = new ArrayList<>();
		if (dashboard.get("sections") instanceof Map) {
			for (Map.Entry<Object, Object> e : ((Map<Object, Object>) dashboard.get("sections")).entrySet()) {
				String identifier = text(e.getKey(), SECTION_PATTERN, MAX_SECTION_LENGTH);
				if (identifier == null) {
					continue;
				}
				Map<String, Object> value = map(e.getValue());
				sections.add(new Section(identifier, app,
						(value.get("title") instanceof String) ? (String) value.get("title") : null,
						text(value.get("icon"), ICON_PATTERN, 64), integer(value.get("order"), 1000)));
			}
		}

		List<Card> cards = new ArrayList<>();
		if (dashboard.get("cards") instanceof Map) {
			for (Map.Entry<Object, Object> e : ((Map<Object, Object>) dashboard.get("cards")).entrySet()) {
				String name = text(e.getKey(), NAME_PATTERN, 64);
				if (name == null || !(e.getValue() instanceof Map)) {
					continue;
				}
				Card card = new Card(app, name, map(e.getValue()), appRoles);
				if (card.section == null || card.provider == null) {
					continue;
				}
				cards.add(card);
			}
		}
		return new Declaration(Collections.unmodifiableList(sections), Collections.unmodifiableList(cards));
	}

	/**
	 * What the app adds to the Dashboard of the session's user, as the
	 * {@code App} type of the GraphQL schema lists it: every section, and the
	 * cards the user may see.
	 */
	public static Map<String, Object> forApp(javax.jcr.Session session, String app, Map<String, Object> descriptor) {
		Declaration declaration = fromDescriptor(app, descriptor.get("dashboard"), descriptor.get("roles"));
		List<Map<String, Object>> sections = new ArrayList<>();
		for (Section section : declaration.sections) {
			sections.add(section.toMap());
		}
		List<Map<String, Object>> cards = new ArrayList<>();
		for (Card card : declaration.cards) {
			if (org.mintjams.rt.cms.internal.graphql.AppRoles.isOffered(session, card.roles)) {
				cards.add(card.toMap());
			}
		}
		Map<String, Object> dashboard = new LinkedHashMap<>();
		dashboard.put("sections", sections);
		dashboard.put("cards", cards);
		return dashboard;
	}

	// ---------------------------------------------------------------------

	private static Object orEmpty(Object roles) {
		// "roles:" with nothing after it reads as null and means what an empty
		// list means: administrators only.
		return (roles == null) ? Collections.emptyList() : roles;
	}

	private static String text(Object value, Pattern pattern, int maxLength) {
		if (!(value instanceof String)) {
			return null;
		}
		String text = ((String) value).trim();
		return (!text.isEmpty() && text.length() <= maxLength && pattern.matcher(text).matches()) ? text : null;
	}

	private static int integer(Object value, int defaultValue) {
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		if (value instanceof String) {
			try {
				return Integer.parseInt(((String) value).trim());
			} catch (NumberFormatException ignore) {}
		}
		return defaultValue;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		if (!(value instanceof Map)) {
			return Collections.emptyMap();
		}
		Map<String, Object> map = new LinkedHashMap<>();
		for (Map.Entry<Object, Object> e : ((Map<Object, Object>) value).entrySet()) {
			if (e.getKey() != null) {
				map.put(String.valueOf(e.getKey()), e.getValue());
			}
		}
		return Collections.unmodifiableMap(map);
	}

	private static List<String> strings(Object value, Pattern pattern) {
		List<String> list = new ArrayList<>();
		if (value instanceof String) {
			value = Collections.singletonList(value);
		}
		if (value instanceof Iterable) {
			for (Object item : (Iterable<?>) value) {
				String text = text(item, pattern, 255);
				if (text != null && !list.contains(text) && list.size() < MAX_ITEMS) {
					list.add(text);
				}
			}
		}
		return Collections.unmodifiableList(list);
	}

	private static List<String> paths(Object value) {
		List<String> list = new ArrayList<>();
		if (value instanceof String) {
			value = Collections.singletonList(value);
		}
		if (value instanceof Iterable) {
			for (Object item : (Iterable<?>) value) {
				if (!(item instanceof String)) {
					continue;
				}
				String path = ((String) item).trim();
				while (path.length() > 1 && path.endsWith("/")) {
					path = path.substring(0, path.length() - 1);
				}
				if (path.startsWith("/") && path.length() > 1 && !path.contains("..") && !list.contains(path)
						&& list.size() < MAX_ITEMS) {
					list.add(path);
				}
			}
		}
		return Collections.unmodifiableList(list);
	}

}
