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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.jcr.Credentials;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;
import javax.jcr.query.Query;

import org.mintjams.jcr.cluster.ClusterTransport;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.cms.event.CmsEvent;
import org.mintjams.rt.cms.internal.cms.event.CmsEventHandler;
import org.mintjams.rt.cms.internal.cms.event.WorkspaceCmsEventManager;
import org.mintjams.rt.cms.internal.graphql.AppRoles;
import org.mintjams.rt.cms.internal.graphql.GraphQLRequest;
import org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.security.ServiceUserCredentials;
import org.mintjams.script.ScriptAPI;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.ClassLoaders;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import com.fasterxml.jackson.databind.ObjectMapper;

import groovy.lang.GroovyClassLoader;

/**
 * Makes what the Dashboard cards of a workspace show, and pushes it to the
 * Dashboards that are open on this node.
 *
 * <p>A card ({@link DashboardCards}) names a provider, a class of the
 * workspace. The worker decides <em>when</em> the provider runs — an event of
 * a topic the card names, a change under a path it names, another card it
 * reads having changed, an interval — and never more often than once in
 * {@link #MIN_GAP_MILLIS}; a card nobody looks at is not made at all. What
 * the provider returns is the card's snapshot, sent to every subscriber that
 * may see the card ({@link #subscribe}): the current snapshots first, then
 * each one as it changes. No client queries anything.
 *
 * <p>A <b>shared</b> card has one snapshot for everyone. In a cluster one node,
 * the owner, makes it and puts it in a replicated table; every node then
 * tells its own subscribers. The owner is the node whose id stands in the
 * owner table, a value it renews and another node takes over when it
 * expires. Which cards are looked at anywhere is in a table as well, written
 * by each node for its own subscribers. Nothing of this is durable: when
 * every node has stopped the snapshots are gone, and they are made again for
 * whoever opens the Dashboard next.
 *
 * <p>A <b>user</b> card has a snapshot per user, made on the node that holds
 * the user's stream and kept in memory there. An event may name the users it
 * concerns (the card's {@code refresh.users} and {@code refresh.groups}); the
 * card is then made again for those among the subscribers only.
 *
 * <p>A card that <b>collects by node</b> shows what every node knows on its
 * own (the statistics of the routes it runs): each node puts its part in a
 * table, and the provider is handed the parts of all nodes.
 *
 * <p>A standalone deployment runs the same code with maps in place of the
 * tables.
 */
public final class DashboardWorker implements Closeable {

	/** The root of the topics the worker posts on every node. */
	public static final String TOPIC_PREFIX = "org/mintjams/rt/cms/dashboard/";
	/** A shared snapshot changed: {@code workspace}, {@code card} (the key in the table). */
	public static final String TOPIC_CARD_UPDATED = TOPIC_PREFIX + "CARD_UPDATED";
	/** A node's part of a card changed: {@code workspace}, {@code card}. */
	public static final String TOPIC_PART_UPDATED = TOPIC_PREFIX + "PART_UPDATED";

	private static final String TABLE_CARDS = "dashboard/cards";
	private static final String TABLE_PARTS = "dashboard/parts";
	private static final String TABLE_WATCHERS = "dashboard/watchers";
	private static final String TABLE_OWNER = "dashboard/owner";

	private static final String DETAIL_SUFFIX = "#detail";

	private static final long TICK_MILLIS = 1000L;
	/** The shortest time between two snapshots of one card. */
	static final long MIN_GAP_MILLIS = 2000L;
	private static final long PROVIDER_TIMEOUT_MILLIS = 10000L;
	private static final int MAX_SNAPSHOT_LENGTH = 32 * 1024;
	private static final int MAX_DETAIL_LENGTH = 48 * 1024;
	private static final int MAX_PART_LENGTH = 16 * 1024;
	private static final long OWNER_TTL_MILLIS = 30000L;
	private static final long OWNER_RENEW_MILLIS = 10000L;
	private static final long WATCHERS_TTL_MILLIS = 60000L;
	private static final long WATCHERS_RENEW_MILLIS = 20000L;
	/** How long a snapshot of a card without an interval stays in the table. */
	private static final long DEFAULT_SNAPSHOT_TTL_MILLIS = 3600000L;
	private static final int DEFAULT_PART_INTERVAL = 30;
	/** The declarations are read again this long after an app.yml changed (the search index follows the commit). */
	private static final long REGISTRY_SETTLE_MILLIS = 3000L;
	private static final long REGISTRY_MAX_AGE_MILLIS = 300000L;
	private static final int MAX_FEEDS = 1000;
	private static final int MAX_ROOTS = 8;
	private static final int FEED_BUFFER = 256;
	private static final int MAX_DEPTH = 16;

	private static final ObjectMapper JSON = new ObjectMapper();

	/** Runs the providers of every workspace: few threads, and what does not fit waits for the next tick. */
	private static final ThreadPoolExecutor PROVIDERS = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(256), new NamedThreads("dashboard-provider-"));
	static {
		PROVIDERS.allowCoreThreadTimeOut(true);
	}

	private static final Executor DELIVERY = Executors.newCachedThreadPool(new NamedThreads("dashboard-feed-"));

	private final String fWorkspaceName;
	private final String fNodeId;
	private final Store fCards;
	private final Store fParts;
	private final Store fWatchers;
	private final Store fOwner;
	private final ScheduledExecutorService fScheduler;

	private final Set<String> fRoots = Collections.synchronizedSet(new LinkedHashSet<>());
	private volatile Registry fRegistry = Registry.EMPTY;
	private volatile long fRegistryStaleAt = Long.MAX_VALUE;
	private volatile long fRegistryLoadedAt;
	private final Object fRegistryLock = new Object();

	private final Set<Feed> fFeeds = ConcurrentHashMap.newKeySet();
	private final Map<String, State> fShared = new ConcurrentHashMap<>();
	private final Map<String, State> fUser = new ConcurrentHashMap<>();
	private final Map<String, State> fPartStates = new ConcurrentHashMap<>();

	private volatile boolean fIsOwner;
	private long fOwnerRenewedAt;
	private String fWatchersValue;
	private long fWatchersPutAt;

	private ServiceRegistration<EventHandler> fEventRegistration;
	private Set<String> fRegisteredTopics = Collections.emptySet();
	private final CmsEventHandler fCmsEventHandler = this::onRepositoryEvent;
	private boolean fCmsEventHandlerAdded;
	private final AtomicBoolean fClosed = new AtomicBoolean();

	public DashboardWorker(String workspaceName) {
		fWorkspaceName = workspaceName;
		ClusterTransport transport = CmsService.getClusterTransport();
		if (transport != null) {
			fNodeId = transport.getNodeId();
			fCards = new TableStore(transport.getTable(TABLE_CARDS));
			fParts = new TableStore(transport.getTable(TABLE_PARTS));
			fWatchers = new TableStore(transport.getTable(TABLE_WATCHERS));
			fOwner = new TableStore(transport.getTable(TABLE_OWNER));
		} else {
			fNodeId = "local";
			fCards = new LocalStore();
			fParts = new LocalStore();
			fWatchers = new LocalStore();
			fOwner = new LocalStore();
		}
		fScheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreads("dashboard-" + workspaceName + "-"));
	}

	public void open() {
		fScheduler.scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
	}

	@Override
	public void close() throws IOException {
		if (!fClosed.compareAndSet(false, true)) {
			return;
		}
		fScheduler.shutdownNow();
		synchronized (fRegistryLock) {
			unregisterEvents();
		}
		for (Feed feed : fFeeds) {
			try {
				feed.sink.close();
			} catch (Throwable ignore) {}
		}
		fFeeds.clear();
		try {
			fWatchers.remove(fWorkspaceName + "/" + fNodeId);
		} catch (Throwable ignore) {}
	}

	// =========================================================================
	// Subscribing
	// =========================================================================

	/**
	 * The snapshots of the cards the session's user may see among those the
	 * apps under {@code appsPath} declare: the current ones, then each as it
	 * changes. With {@code detailCard}, the detail of that one card instead.
	 * The items are maps of {@code card}, {@code detail}, {@code version},
	 * {@code at} and {@code data}.
	 */
	public Publisher<Object> subscribe(Session callerSession, String appsPath, String timeZone, String detailCard) {
		if (!(callerSession instanceof org.mintjams.jcr.Session) || ((org.mintjams.jcr.Session) callerSession).isAnonymous()) {
			throw new IllegalStateException("Sign in to open the Dashboard.");
		}
		org.mintjams.jcr.Session session = (org.mintjams.jcr.Session) callerSession;
		if (fFeeds.size() >= MAX_FEEDS) {
			throw new IllegalStateException("Too many Dashboards are open on this node.");
		}
		String root = normalizeRoot(appsPath);
		if (fRoots.add(root)) {
			if (fRoots.size() > MAX_ROOTS) {
				fRoots.remove(root);
				throw new IllegalArgumentException("Too many apps folders.");
			}
			loadRegistry();
		} else if (fRegistry == Registry.EMPTY) {
			loadRegistry();
		}
		Registry registry = fRegistry;

		Set<String> keys = new LinkedHashSet<>();
		if (detailCard != null) {
			CardEntry card = registry.cards.get(detailCard);
			if (card == null || !card.detail || !card.root.equals(root) || !AppRoles.isOffered(session, card.roles)) {
				throw new IllegalArgumentException("No such card: " + detailCard);
			}
			keys.add(card.id + DETAIL_SUFFIX);
		} else {
			for (CardEntry card : registry.cards.values()) {
				if (card.root.equals(root) && AppRoles.isOffered(session, card.roles)) {
					keys.add(card.id);
				}
			}
		}

		List<String> groups = new ArrayList<>();
		List<String> roles = new ArrayList<>();
		try {
			session.getGroups().forEach(p -> groups.add(p.getName()));
			session.getRoles().forEach(p -> roles.add(p.getName()));
		} catch (Throwable ignore) {}

		final Feed feed = new Feed(root, session.getUserID(),(timeZone == null || timeZone.trim().isEmpty()) ? null : timeZone.trim(),
				groups, roles, keys);
		return subscriber -> {
			if (fClosed.get()) {
				subscriber.onSubscribe(NO_SUBSCRIPTION);
				subscriber.onError(new IllegalStateException("The workspace is stopping."));
				return;
			}
			fFeeds.add(feed);
			Runnable cleanup = () -> {
				if (feed.cleaned.compareAndSet(false, true)) {
					fFeeds.remove(feed);
					try {
						feed.sink.close();
					} catch (Throwable ignore) {}
				}
			};
			FlowAdapters.toPublisher(feed.sink).subscribe(new Subscriber<Object>() {
				@Override
				public void onSubscribe(Subscription s) {
					subscriber.onSubscribe(new Subscription() {
						@Override
						public void request(long n) {
							s.request(n);
						}

						@Override
						public void cancel() {
							try {
								s.cancel();
							} finally {
								cleanup.run();
							}
						}
					});
				}

				@Override
				public void onNext(Object item) {
					subscriber.onNext(item);
				}

				@Override
				public void onError(Throwable t) {
					try {
						cleanup.run();
					} finally {
						subscriber.onError(t);
					}
				}

				@Override
				public void onComplete() {
					try {
						cleanup.run();
					} finally {
						subscriber.onComplete();
					}
				}
			});
			try {
				fScheduler.execute(this::tick);
			} catch (Throwable ignore) {}
		};
	}

	private static final Subscription NO_SUBSCRIPTION = new Subscription() {
		@Override
		public void request(long n) {}

		@Override
		public void cancel() {}
	};

	private static String normalizeRoot(String appsPath) {
		String root = (appsPath == null) ? "" : appsPath.trim();
		while (root.length() > 1 && root.endsWith("/")) {
			root = root.substring(0, root.length() - 1);
		}
		if (!root.startsWith("/") || root.length() < 2 || root.contains("..") || root.contains("'") || root.contains("\"")
				|| root.length() > 1024) {
			throw new IllegalArgumentException("Invalid apps folder: " + appsPath);
		}
		return root;
	}

	// =========================================================================
	// The declarations
	// =========================================================================

	/** A card and the apps folder it was found under. */
	private static final class CardEntry extends CardRef {
		CardEntry(DashboardCards.Card card, String root) {
			super(card, root);
		}
	}

	/** Gives the fields of the card under the names used here. */
	private static class CardRef {
		final DashboardCards.Card card;
		final String root;
		final String id;
		final boolean detail;
		final Object roles;

		CardRef(DashboardCards.Card card, String root) {
			this.card = card;
			this.root = root;
			this.id = card.id;
			this.detail = card.detail;
			this.roles = card.roles;
		}
	}

	private static final class Registry {
		static final Registry EMPTY = new Registry(Collections.emptyMap());

		final Map<String, CardEntry> cards;
		final Set<String> topics;

		Registry(Map<String, CardEntry> cards) {
			this.cards = cards;
			Set<String> topics = new TreeSet<>();
			for (CardEntry entry : cards.values()) {
				topics.addAll(entry.card.topics);
			}
			this.topics = topics;
		}
	}

	@SuppressWarnings("unchecked")
	private void loadRegistry() {
		synchronized (fRegistryLock) {
			if (fClosed.get()) {
				return;
			}
			List<String> roots;
			synchronized (fRoots) {
				roots = new ArrayList<>(fRoots);
			}
			Map<String, CardEntry> cards = new LinkedHashMap<>();
			Session session = null;
			try {
				session = CmsService.getRepository().login(new CmsServiceCredentials(), fWorkspaceName);
				for (String root : roots) {
					if (!session.nodeExists(root)) {
						continue;
					}
					NodeIterator i = session.getWorkspace().getQueryManager()
							.createQuery("/jcr:root" + root + "//element(app.yml,nt:file)", Query.XPATH).execute().getNodes();
					while (i.hasNext()) {
						Node descriptor = i.nextNode();
						String home = descriptor.getParent().getPath();
						if (!home.startsWith(root + "/")) {
							continue;
						}
						Map<String, Object> data = parse(descriptor);
						if (data == null || !(data.get("dashboard") instanceof Map)) {
							continue;
						}
						DashboardCards.Declaration declaration = DashboardCards.fromDescriptor(
								home.substring(root.length() + 1), data.get("dashboard"), data.get("roles"));
						for (DashboardCards.Card card : declaration.cards) {
							cards.putIfAbsent(card.id, new CardEntry(card, root));
						}
					}
				}
			} catch (Throwable ex) {
				CmsService.getLogger(getClass()).warn("Could not read the Dashboard cards of the workspace: " + fWorkspaceName, ex);
				return;
			} finally {
				if (session != null) {
					try {
						session.logout();
					} catch (Throwable ignore) {}
				}
			}
			Registry registry = new Registry(Collections.unmodifiableMap(cards));
			fRegistry = registry;
			fRegistryLoadedAt = System.currentTimeMillis();
			fRegistryStaleAt = Long.MAX_VALUE;
			registerEvents(registry);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parse(Node descriptor) {
		try {
			Node content = descriptor.getNode("jcr:content");
			try (InputStream in = content.getProperty("jcr:data").getBinary().getStream()) {
				Object parsed = new Load(LoadSettings.builder().build()).loadFromInputStream(in);
				return (parsed instanceof Map) ? (Map<String, Object>) parsed : null;
			}
		} catch (Throwable ex) {
			return null;
		}
	}

	private void registerEvents(Registry registry) {
		Set<String> topics = new TreeSet<>(registry.topics);
		topics.add(TOPIC_PREFIX + "*");
		if (!topics.equals(fRegisteredTopics) || fEventRegistration == null) {
			BundleContext bundleContext = CmsService.getDefault().getBundleContext();
			if (bundleContext == null) {
				return;
			}
			ServiceRegistration<EventHandler> previous = fEventRegistration;
			Dictionary<String, Object> properties = new Hashtable<>();
			properties.put(EventConstants.EVENT_TOPIC, topics.toArray(String[]::new));
			fEventRegistration = bundleContext.registerService(EventHandler.class, this::onEvent, properties);
			fRegisteredTopics = topics;
			if (previous != null) {
				try {
					previous.unregister();
				} catch (Throwable ignore) {}
			}
		}
		if (!fCmsEventHandlerAdded) {
			WorkspaceCmsEventManager manager = CmsService.getWorkspaceCmsEventManager(fWorkspaceName);
			if (manager != null) {
				manager.addEventHandler(fCmsEventHandler);
				fCmsEventHandlerAdded = true;
			}
		}
	}

	private void unregisterEvents() {
		if (fEventRegistration != null) {
			try {
				fEventRegistration.unregister();
			} catch (Throwable ignore) {}
			fEventRegistration = null;
		}
		if (fCmsEventHandlerAdded) {
			try {
				WorkspaceCmsEventManager manager = CmsService.getWorkspaceCmsEventManager(fWorkspaceName);
				if (manager != null) {
					manager.removeEventHandler(fCmsEventHandler);
				}
			} catch (Throwable ignore) {}
			fCmsEventHandlerAdded = false;
		}
	}

	// =========================================================================
	// Events
	// =========================================================================

	private void onEvent(Event event) {
		try {
			Object workspace = event.getProperty("workspace");
			if (workspace != null && !fWorkspaceName.equals(workspace)) {
				return;
			}
			String topic = event.getTopic();
			if (TOPIC_CARD_UPDATED.equals(topic)) {
				if (workspace != null && event.getProperty("card") instanceof String) {
					sharedChanged((String) event.getProperty("card"));
				}
				return;
			}
			if (TOPIC_PART_UPDATED.equals(topic)) {
				if (workspace != null && event.getProperty("card") instanceof String) {
					CardEntry entry = fRegistry.cards.get(event.getProperty("card"));
					if (entry != null) {
						markDirty(entry, null);
					}
				}
				return;
			}
			if (topic.startsWith(TOPIC_PREFIX)) {
				return;
			}
			for (CardEntry entry : fRegistry.cards.values()) {
				for (String pattern : entry.card.topics) {
					if (topicMatches(pattern, topic)) {
						markDirty(entry, event);
						break;
					}
				}
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).debug("A Dashboard event was not handled.", ex);
		}
	}

	private static boolean topicMatches(String pattern, String topic) {
		if (pattern.endsWith("/*")) {
			return topic.startsWith(pattern.substring(0, pattern.length() - 1));
		}
		return pattern.equals(topic);
	}

	private void onRepositoryEvent(CmsEvent event) {
		try {
			String path = event.getPath();
			if (path == null) {
				return;
			}
			if (path.endsWith("/app.yml") || path.contains("/app.yml/")) {
				synchronized (fRoots) {
					for (String root : fRoots) {
						if (path.startsWith(root + "/")) {
							fRegistryStaleAt = System.currentTimeMillis() + REGISTRY_SETTLE_MILLIS;
							break;
						}
					}
				}
			}
			for (CardEntry entry : fRegistry.cards.values()) {
				for (String watched : entry.card.paths) {
					if (path.equals(watched) || path.startsWith(watched + "/")) {
						markDirty(entry, null);
						break;
					}
				}
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).debug("A repository event was not handled by the Dashboard.", ex);
		}
	}

	/**
	 * Notes that the card must be made again. For a user card, an event that
	 * names who it concerns limits that to those users.
	 */
	private void markDirty(CardEntry entry, Event event) {
		DashboardCards.Card card = entry.card;
		if (card.collectByNode) {
			State part = fPartStates.get(card.id);
			if (part != null) {
				part.dirty = true;
			}
		}
		if (card.isShared()) {
			State summary = fShared.get(card.id);
			if (summary != null) {
				summary.dirty = true;
			}
			State detail = fShared.get(card.id + DETAIL_SUFFIX);
			if (detail != null) {
				detail.dirty = true;
			}
			return;
		}

		Set<String> users = null;
		Set<String> groups = null;
		if (event != null && card.namesUsers()) {
			users = names(event, card.users);
			groups = names(event, card.groups);
		}
		for (State state : fUser.values()) {
			if (!state.cardId.equals(card.id)) {
				continue;
			}
			if (users == null || users.contains(state.userId) || intersects(groups, state.groups)
					|| intersects(groups, state.roles)) {
				state.dirty = true;
			}
		}
	}

	private static Set<String> names(Event event, List<String> properties) {
		Set<String> names = new HashSet<>();
		for (String property : properties) {
			Object value = event.getProperty(property);
			if (value instanceof String) {
				names.add((String) value);
			} else if (value instanceof Collection) {
				for (Object item : (Collection<?>) value) {
					if (item != null) {
						names.add(item.toString());
					}
				}
			} else if (value instanceof Object[]) {
				for (Object item : (Object[]) value) {
					if (item != null) {
						names.add(item.toString());
					}
				}
			}
		}
		return names;
	}

	private static boolean intersects(Set<String> names, List<String> held) {
		if (names == null || names.isEmpty()) {
			return false;
		}
		for (String name : held) {
			if (names.contains(name)) {
				return true;
			}
		}
		return false;
	}

	/** A shared snapshot is new in the table: tell who looks at it, and make again what reads it. */
	private void sharedChanged(String key) {
		Map<String, Object> item = sharedItem(key);
		if (item != null) {
			for (Feed feed : fFeeds) {
				if (feed.keys.contains(key)) {
					feed.send(key, item);
				}
			}
		}
		if (!key.endsWith(DETAIL_SUFFIX)) {
			for (CardEntry entry : fRegistry.cards.values()) {
				if (entry.card.cards.contains(key)) {
					markDirty(entry, null);
				}
			}
		}
	}

	// =========================================================================
	// The loop
	// =========================================================================

	private void tick() {
		if (fClosed.get()) {
			return;
		}
		try {
			long now = System.currentTimeMillis();
			Set<String> local = localKeys(fRegistry);
			publishWatchers(local, now);
			Set<String> watched = watchedKeys(local);
			// Nothing is read, and nothing made, while nobody looks at a Dashboard on any node.
			if (!watched.isEmpty() && (now >= fRegistryStaleAt || now - fRegistryLoadedAt >= REGISTRY_MAX_AGE_MILLIS)) {
				loadRegistry();
			}
			Registry registry = fRegistry;
			electOwner(!watched.isEmpty(), now);

			if (fIsOwner) {
				runShared(registry, watched, now);
			}
			runParts(registry, watched, now);
			runUser(registry, now);
			resyncFeeds(registry);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("The Dashboard worker of the workspace '" + fWorkspaceName + "' failed a round.", ex);
		}
	}

	/**
	 * The cards the subscribers of this node look at, and the shared cards
	 * those read; with them the apps folders the cards are declared under (the
	 * entries that start with a slash), which a node without a subscriber of
	 * its own learns from here.
	 */
	private Set<String> localKeys(Registry registry) {
		Set<String> keys = new TreeSet<>();
		for (Feed feed : fFeeds) {
			keys.add(feed.root);
			keys.addAll(feed.keys);
		}
		for (String key : new ArrayList<>(keys)) {
			CardEntry entry = registry.cards.get(cardIdOf(key));
			if (entry != null) {
				keys.addAll(entry.card.cards);
			}
		}
		return keys;
	}

	private void publishWatchers(Set<String> local, long now) {
		String key = fWorkspaceName + "/" + fNodeId;
		if (local.isEmpty()) {
			if (fWatchersValue != null) {
				fWatchers.remove(key);
				fWatchersValue = null;
			}
			return;
		}
		String value = String.join("\n", local);
		if (value.length() > ClusterTransport.Table.MAX_VALUE_LENGTH) {
			value = value.substring(0, value.lastIndexOf('\n', ClusterTransport.Table.MAX_VALUE_LENGTH));
		}
		if (!value.equals(fWatchersValue) || now - fWatchersPutAt >= WATCHERS_RENEW_MILLIS) {
			fWatchers.put(key, value, WATCHERS_TTL_MILLIS);
			fWatchersValue = value;
			fWatchersPutAt = now;
		}
	}

	/** The cards looked at on any node. */
	private Set<String> watchedKeys(Set<String> local) {
		Set<String> keys = new TreeSet<>(local);
		String prefix = fWorkspaceName + "/";
		for (String key : fWatchers.keySet()) {
			if (!key.startsWith(prefix) || key.equals(prefix + fNodeId)) {
				continue;
			}
			String value = fWatchers.get(key);
			if (value != null && !value.isEmpty()) {
				Collections.addAll(keys, value.split("\n"));
			}
		}
		for (String key : keys) {
			// An apps folder the subscribers of another node look through: its declarations are read here too.
			if (key.startsWith("/") && fRoots.size() < MAX_ROOTS && fRoots.add(key)) {
				fRegistryStaleAt = 0L;
			}
		}
		return keys;
	}

	/**
	 * Whether this node makes the shared snapshots. The owner renews its
	 * claim; a node takes an expired one and counts as the owner from the next
	 * round, when its claim is still the one that stands. Two nodes that both
	 * count as the owner for a moment make the same snapshot twice, no more.
	 */
	private void electOwner(boolean needed, long now) {
		String owner = fOwner.get(fWorkspaceName);
		if (fNodeId.equals(owner)) {
			if (!fIsOwner) {
				fIsOwner = true;
				for (State state : fShared.values()) {
					state.dirty = true;
				}
			}
			if (needed && now - fOwnerRenewedAt >= OWNER_RENEW_MILLIS) {
				fOwner.put(fWorkspaceName, fNodeId, OWNER_TTL_MILLIS);
				fOwnerRenewedAt = now;
			}
			return;
		}
		fIsOwner = false;
		if (owner == null && needed) {
			fOwner.put(fWorkspaceName, fNodeId, OWNER_TTL_MILLIS);
			fOwnerRenewedAt = now;
		}
	}

	private void runShared(Registry registry, Set<String> watched, long now) {
		for (String key : watched) {
			boolean detail = key.endsWith(DETAIL_SUFFIX);
			CardEntry entry = registry.cards.get(cardIdOf(key));
			if (entry == null || !entry.card.isShared() || (detail && !entry.card.detail)) {
				continue;
			}
			State state = fShared.computeIfAbsent(key, k -> new State(entry.card.id, detail, null));
			boolean missing = fCards.get(tableKey(key)) == null;
			if (isDue(state, entry.card, missing, now)) {
				start(state, now, () -> makeShared(entry, state, key));
			}
		}
		fShared.keySet().removeIf(key -> !registry.cards.containsKey(cardIdOf(key)));
	}

	private void runParts(Registry registry, Set<String> watched, long now) {
		Set<String> cardIds = new HashSet<>();
		for (String key : watched) {
			cardIds.add(cardIdOf(key));
		}
		for (String cardId : cardIds) {
			CardEntry entry = registry.cards.get(cardId);
			if (entry == null || !entry.card.collectByNode) {
				continue;
			}
			State state = fPartStates.computeIfAbsent(cardId, k -> new State(cardId, false, null));
			long interval = partInterval(entry.card) * 1000L;
			boolean due = state.dirty || now - state.startedAt >= interval;
			if (due && !state.running && now - state.startedAt >= MIN_GAP_MILLIS) {
				start(state, now, () -> makePart(entry, state));
			}
		}
		fPartStates.keySet().removeIf(cardId -> !cardIds.contains(cardId));
	}

	private void runUser(Registry registry, long now) {
		Set<String> alive = new HashSet<>();
		for (Feed feed : fFeeds) {
			for (String key : feed.keys) {
				boolean detail = key.endsWith(DETAIL_SUFFIX);
				CardEntry entry = registry.cards.get(cardIdOf(key));
				if (entry == null || entry.card.isShared() || (detail && !entry.card.detail)) {
					continue;
				}
				String stateKey = feed.viewer + "\n" + key;
				alive.add(stateKey);
				State state = fUser.computeIfAbsent(stateKey, k -> new State(entry.card.id, detail, feed));
				if (isDue(state, entry.card, false, now)) {
					start(state, now, () -> makeUser(entry, state, key));
				}
			}
		}
		fUser.keySet().removeIf(key -> !alive.contains(key));
	}

	private static boolean isDue(State state, DashboardCards.Card card, boolean missing, long now) {
		if (state.running) {
			if (state.future != null && now - state.startedAt > PROVIDER_TIMEOUT_MILLIS) {
				state.future.cancel(true);
			}
			return false;
		}
		if (now - state.startedAt < MIN_GAP_MILLIS) {
			return false;
		}
		return state.dirty || missing || (card.interval > 0 && now - state.startedAt >= card.interval * 1000L);
	}

	private void start(State state, long now, Runnable work) {
		state.running = true;
		state.dirty = false;
		state.startedAt = now;
		try {
			state.future = PROVIDERS.submit(() -> {
				try {
					work.run();
				} finally {
					state.future = null;
					state.running = false;
				}
			});
		} catch (Throwable busy) {
			// Every thread is taken and the queue is full: the card waits.
			state.running = false;
			state.dirty = true;
		}
	}

	private void resyncFeeds(Registry registry) {
		for (Feed feed : fFeeds) {
			if (!feed.resync) {
				continue;
			}
			feed.resync = false;
			for (String key : feed.keys) {
				CardEntry entry = registry.cards.get(cardIdOf(key));
				if (entry == null) {
					continue;
				}
				if (entry.card.isShared()) {
					Map<String, Object> item = sharedItem(key);
					if (item != null) {
						feed.send(key, item);
					}
				} else {
					State state = fUser.get(feed.viewer + "\n" + key);
					if (state != null && state.item != null) {
						feed.send(key, state.item);
					}
				}
			}
		}
	}

	// =========================================================================
	// Making the snapshots
	// =========================================================================

	private void makeShared(CardEntry entry, State state, String key) {
		DashboardCards.Card card = entry.card;
		try {
			Map<String, Object> context = newContext(card, state.detail);
			if (card.collectByNode) {
				context.put("parts", parts(card));
			}
			context.put("snapshots", snapshots(card, null));
			Object result = invoke(card, state.detail ? "detail" : "snapshot", context, null);
			String json = toJson(result, state.detail ? MAX_DETAIL_LENGTH : MAX_SNAPSHOT_LENGTH);

			long now = System.currentTimeMillis();
			long ttl = snapshotTtl(card);
			String tableKey = tableKey(key);
			boolean missing = fCards.get(tableKey) == null;
			if (!json.equals(state.json) || state.envelope == null) {
				state.json = json;
				state.envelope = "{\"v\":\"" + Long.toString(now, 36) + "\",\"at\":\"" + Instant.ofEpochMilli(now)
						+ "\",\"data\":" + json + "}";
				fCards.put(tableKey, state.envelope, ttl);
				state.putAt = now;
				announce(TOPIC_CARD_UPDATED, key);
			} else if (missing || now - state.putAt >= ttl / 3) {
				fCards.put(tableKey, state.envelope, ttl);
				state.putAt = now;
				if (missing) {
					announce(TOPIC_CARD_UPDATED, key);
				}
			}
			state.failed = false;
		} catch (Throwable ex) {
			failed(state, card, ex);
		}
	}

	private void makePart(CardEntry entry, State state) {
		DashboardCards.Card card = entry.card;
		try {
			Object result = invoke(card, "part", newContext(card, false), null);
			String json = toJson(result, MAX_PART_LENGTH);
			long now = System.currentTimeMillis();
			long ttl = partInterval(card) * 3000L;
			String tableKey = fWorkspaceName + "/" + card.id + "/" + fNodeId;
			boolean changed = !json.equals(state.json);
			if (changed || now - state.putAt >= ttl / 3) {
				state.json = json;
				fParts.put(tableKey, json, ttl);
				state.putAt = now;
				if (changed) {
					announce(TOPIC_PART_UPDATED, card.id);
				}
			}
			state.failed = false;
		} catch (Throwable ex) {
			failed(state, card, ex);
		}
	}

	private void makeUser(CardEntry entry, State state, String key) {
		DashboardCards.Card card = entry.card;
		try {
			Map<String, Object> context = newContext(card, state.detail);
			context.put("userId", state.userId);
			context.put("timeZone", state.timeZone);
			context.put("groups", state.groups);
			context.put("roles", state.roles);
			context.put("snapshots", snapshots(card, state));
			Object result = invoke(card, state.detail ? "detail" : "snapshot", context, state.userId);
			String json = toJson(result, state.detail ? MAX_DETAIL_LENGTH : MAX_SNAPSHOT_LENGTH);
			if (!json.equals(state.json) || state.item == null) {
				long now = System.currentTimeMillis();
				state.json = json;
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("card", card.id);
				item.put("detail", state.detail);
				item.put("version", Long.toString(now, 36));
				item.put("at", Instant.ofEpochMilli(now).toString());
				item.put("data", JSON.readValue(json, Map.class));
				state.item = item;
				for (Feed feed : fFeeds) {
					if (feed.viewer.equals(state.viewer) && feed.keys.contains(key)) {
						feed.send(key, item);
					}
				}
				if (!state.detail) {
					// What reads this card is made again for the same viewer.
					for (CardEntry other : fRegistry.cards.values()) {
						if (!other.card.isShared() && other.card.cards.contains(card.id)) {
							State dependent = fUser.get(state.viewer + "\n" + other.card.id);
							if (dependent != null) {
								dependent.dirty = true;
							}
						}
					}
				}
			}
			state.failed = false;
		} catch (Throwable ex) {
			failed(state, card, ex);
		}
	}

	private void failed(State state, DashboardCards.Card card, Throwable ex) {
		Throwable cause = (ex instanceof InvocationTargetException && ex.getCause() != null) ? ex.getCause() : ex;
		if (!state.failed) {
			// Once, until the card is made again: a provider that keeps failing
			// would otherwise fill the log at every round.
			state.failed = true;
			CmsService.getLogger(getClass()).warn("The Dashboard card '" + card.id + "' of the workspace '" + fWorkspaceName
					+ "' could not be made: " + cause, cause);
		}
	}

	private void announce(String topic, String card) {
		Map<String, Object> properties = new HashMap<>();
		properties.put("workspace", fWorkspaceName);
		properties.put("card", card);
		try {
			CmsService.postEvent(topic, properties);
		} catch (Throwable ignore) {}
		CmsService.broadcast(topic, fWorkspaceName, properties);
	}

	private Map<String, Object> newContext(DashboardCards.Card card, boolean detail) {
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("id", card.id);
		context.put("name", card.name);
		context.put("app", card.app);
		context.put("section", card.section);
		context.put("scope", card.scope);
		context.put("detail", detail);
		context.put("options", card.options);
		context.put("workspace", fWorkspaceName);
		context.put("node", fNodeId);
		return context;
	}

	/** The parts the nodes put for the card, by node. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> parts(DashboardCards.Card card) {
		Map<String, Object> parts = new LinkedHashMap<>();
		String prefix = fWorkspaceName + "/" + card.id + "/";
		for (String key : new TreeSet<>(fParts.keySet())) {
			if (!key.startsWith(prefix)) {
				continue;
			}
			String value = fParts.get(key);
			if (value == null) {
				continue;
			}
			try {
				parts.put(key.substring(prefix.length()), JSON.readValue(value, Map.class));
			} catch (Throwable ignore) {}
		}
		return parts;
	}

	/** The current snapshots of the cards this one reads. */
	private Map<String, Object> snapshots(DashboardCards.Card card, State viewer) {
		Map<String, Object> snapshots = new LinkedHashMap<>();
		Registry registry = fRegistry;
		for (String id : card.cards) {
			CardEntry other = registry.cards.get(id);
			if (other == null) {
				continue;
			}
			if (other.card.isShared()) {
				Map<String, Object> item = sharedItem(id);
				if (item != null) {
					snapshots.put(id, item.get("data"));
				}
			} else if (viewer != null) {
				State state = fUser.get(viewer.viewer + "\n" + id);
				if (state != null && state.item != null) {
					snapshots.put(id, state.item.get("data"));
				}
			}
		}
		return snapshots;
	}

	/** The item sent for a shared card, from the table; null when there is no snapshot. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> sharedItem(String key) {
		String envelope = fCards.get(tableKey(key));
		if (envelope == null) {
			return null;
		}
		try {
			Map<String, Object> parsed = JSON.readValue(envelope, Map.class);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("card", cardIdOf(key));
			item.put("detail", key.endsWith(DETAIL_SUFFIX));
			item.put("version", parsed.get("v"));
			item.put("at", parsed.get("at"));
			item.put("data", parsed.get("data"));
			return item;
		} catch (Throwable ex) {
			return null;
		}
	}

	private String tableKey(String key) {
		return fWorkspaceName + "/" + key;
	}

	private static String cardIdOf(String key) {
		return key.endsWith(DETAIL_SUFFIX) ? key.substring(0, key.length() - DETAIL_SUFFIX.length()) : key;
	}

	private static long snapshotTtl(DashboardCards.Card card) {
		return (card.interval > 0) ? Math.max(60000L, card.interval * 3000L) : DEFAULT_SNAPSHOT_TTL_MILLIS;
	}

	private static int partInterval(DashboardCards.Card card) {
		return (card.interval > 0) ? card.interval : DEFAULT_PART_INTERVAL;
	}

	// =========================================================================
	// Running a provider
	// =========================================================================

	/**
	 * Calls the static method of the card's provider: {@code (ScriptAPI, Map)}
	 * for a shared card and for a part, {@code (ScriptAPI, Map, String)} with
	 * the user id for a user card.
	 */
	private Object invoke(DashboardCards.Card card, String methodName, Map<String, Object> context, String userId)
			throws Exception {
		Credentials credentials = (card.runAs != null) ? new ServiceUserCredentials(card.runAs) : new CmsServiceCredentials();
		context.put("graphql", new Queries(fWorkspaceName, credentials));
		WorkspaceScriptContext scriptContext = new WorkspaceScriptContext(fWorkspaceName);
		try {
			scriptContext.setCredentials(credentials);
			Scripts.prepareAPIs(scriptContext);
			Object api = scriptContext.getAttribute(ScriptAPI.class.getSimpleName());
			// The providers are sources of the workspace, which the Groovy loader of the workspace compiles.
			ClassLoader workspaceLoader = CmsService.getWorkspaceClassLoaderProvider(fWorkspaceName).getClassLoader();
			ClassLoader groovyLoader = Adaptables.getAdapter(workspaceLoader, GroovyClassLoader.class);
			ClassLoader classLoader = (groovyLoader != null) ? groovyLoader : workspaceLoader;
			try (Closeable c = ClassLoaders.withClassLoader(classLoader)) {
				Class<?> type = classLoader.loadClass(card.provider);
				int parameters = (userId != null) ? 3 : 2;
				for (Method method : type.getMethods()) {
					if (method.getName().equals(methodName) && Modifier.isStatic(method.getModifiers())
							&& method.getParameterCount() == parameters) {
						try {
							return (userId != null) ? method.invoke(null, api, context, userId) : method.invoke(null, api, context);
						} catch (InvocationTargetException ex) {
							if (ex.getCause() instanceof Exception) {
								throw (Exception) ex.getCause();
							}
							throw ex;
						}
					}
				}
				throw new NoSuchMethodException(card.provider + "." + methodName + " with " + parameters + " parameters");
			}
		} finally {
			try {
				scriptContext.close();
			} catch (Throwable ignore) {}
		}
	}

	/** Lets a provider ask what the GraphQL schema of the workspace answers, as the provider's own account. */
	public static final class Queries {
		private final String fWorkspaceName;
		private final Credentials fCredentials;

		Queries(String workspaceName, Credentials credentials) {
			fWorkspaceName = workspaceName;
			fCredentials = credentials;
		}

		/**
		 * Runs the query and returns its {@code data}.
		 *
		 * @throws IllegalStateException when the answer carries errors
		 */
		@SuppressWarnings("unchecked")
		public Map<String, Object> query(String query, Map<String, Object> variables) {
			WorkspaceGraphQLEngineProvider engine = CmsService.getWorkspaceGraphQLEngineProvider(fWorkspaceName);
			if (engine == null) {
				throw new IllegalStateException("GraphQL is not available for the workspace: " + fWorkspaceName);
			}
			GraphQLRequest request = new GraphQLRequest(query, null,
					(variables == null) ? new HashMap<>() : new HashMap<>(variables));
			if (!request.isQuery()) {
				throw new IllegalArgumentException("A card reads; only queries are run.");
			}
			Map<String, Object> result = engine.execute(request, fCredentials);
			Object errors = result.get("errors");
			if (errors instanceof Collection && !((Collection<?>) errors).isEmpty()) {
				Object first = ((Collection<?>) errors).iterator().next();
				throw new IllegalStateException(String.valueOf((first instanceof Map) ? ((Map<?, ?>) first).get("message") : first));
			}
			Object data = result.get("data");
			return (data instanceof Map) ? (Map<String, Object>) data : Collections.emptyMap();
		}

		public Map<String, Object> query(String query) {
			return query(query, null);
		}
	}

	/** What a provider returned, as JSON: plain maps, lists, strings, numbers and booleans. */
	private static String toJson(Object result, int maxLength) throws IOException {
		if (!(result instanceof Map)) {
			throw new IllegalStateException("A provider returns a map.");
		}
		String json = JSON.writeValueAsString(plain(result, 0));
		if (json.length() > maxLength) {
			throw new IllegalStateException("The snapshot is too large (" + json.length() + " characters, at most " + maxLength + ").");
		}
		return json;
	}

	private static Object plain(Object value, int depth) {
		if (value == null || value instanceof Boolean || value instanceof Number || value instanceof String) {
			return value;
		}
		if (depth > MAX_DEPTH) {
			throw new IllegalStateException("The snapshot is nested too deeply.");
		}
		if (value instanceof CharSequence) {
			return value.toString();
		}
		if (value instanceof Date) {
			return ((Date) value).toInstant().toString();
		}
		if (value instanceof Map) {
			Map<String, Object> map = new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
				if (e.getKey() != null && e.getValue() != null) {
					map.put(e.getKey().toString(), plain(e.getValue(), depth + 1));
				}
			}
			return map;
		}
		if (value instanceof Iterable) {
			List<Object> list = new ArrayList<>();
			for (Object item : (Iterable<?>) value) {
				list.add(plain(item, depth + 1));
			}
			return list;
		}
		if (value instanceof Object[]) {
			List<Object> list = new ArrayList<>();
			for (Object item : (Object[]) value) {
				list.add(plain(item, depth + 1));
			}
			return list;
		}
		return value.toString();
	}

	// =========================================================================
	// State
	// =========================================================================

	/** One snapshot being kept up to date: a shared card, a node's part, or a user card of one viewer. */
	private static final class State {
		final String cardId;
		final boolean detail;
		final String viewer;
		final String userId;
		final String timeZone;
		final List<String> groups;
		final List<String> roles;

		volatile boolean dirty = true;
		volatile boolean running;
		volatile boolean failed;
		volatile long startedAt;
		volatile Future<?> future;
		volatile String json;
		volatile String envelope;
		volatile long putAt;
		volatile Map<String, Object> item;

		State(String cardId, boolean detail, Feed feed) {
			this.cardId = cardId;
			this.detail = detail;
			this.viewer = (feed == null) ? null : feed.viewer;
			this.userId = (feed == null) ? null : feed.userId;
			this.timeZone = (feed == null) ? null : feed.timeZone;
			this.groups = (feed == null) ? Collections.emptyList() : feed.groups;
			this.roles = (feed == null) ? Collections.emptyList() : feed.roles;
		}
	}

	/** One subscription: a Dashboard that is open, or a card maximized in one. */
	private static final class Feed {
		/** The apps folder whose cards the subscriber looks at. */
		final String root;
		final String userId;
		final String timeZone;
		/** The user and the time zone: what a user card is made for. */
		final String viewer;
		final List<String> groups;
		final List<String> roles;
		final Set<String> keys;
		final SubmissionPublisher<Object> sink = new SubmissionPublisher<>(DELIVERY, FEED_BUFFER);
		final Map<String, Object> sent = new ConcurrentHashMap<>();
		final AtomicBoolean cleaned = new AtomicBoolean();
		volatile boolean resync = true;

		Feed(String root, String userId, String timeZone, List<String> groups, List<String> roles, Set<String> keys) {
			this.root = root;
			this.userId = userId;
			this.timeZone = timeZone;
			this.viewer = userId + "\n" + ((timeZone == null) ? "" : timeZone);
			this.groups = Collections.unmodifiableList(groups);
			this.roles = Collections.unmodifiableList(roles);
			this.keys = Collections.unmodifiableSet(keys);
		}

		/** Sends the item unless this very version was sent; a subscriber that lags gets everything again later. */
		void send(String key, Map<String, Object> item) {
			Object version = item.get("version");
			if (version != null && version.equals(sent.put(key, version))) {
				return;
			}
			if (sink.isClosed()) {
				return;
			}
			sink.offer(item, (subscriber, dropped) -> {
				sent.clear();
				resync = true;
				return false;
			});
		}
	}

	// =========================================================================
	// Where the snapshots are kept
	// =========================================================================

	private interface Store {
		String get(String key);

		void put(String key, String value, long ttlMillis);

		void remove(String key);

		Set<String> keySet();
	}

	private static final class TableStore implements Store {
		private final ClusterTransport.Table fTable;

		TableStore(ClusterTransport.Table table) {
			fTable = table;
		}

		@Override
		public String get(String key) {
			return fTable.get(key);
		}

		@Override
		public void put(String key, String value, long ttlMillis) {
			fTable.put(key, value, ttlMillis);
		}

		@Override
		public void remove(String key) {
			fTable.remove(key);
		}

		@Override
		public Set<String> keySet() {
			return fTable.keySet();
		}
	}

	private static final class LocalStore implements Store {
		private final Map<String, Object[]> fValues = new ConcurrentHashMap<>();

		@Override
		public String get(String key) {
			Object[] value = fValues.get(key);
			if (value == null) {
				return null;
			}
			if ((Long) value[1] > 0 && System.currentTimeMillis() >= (Long) value[1]) {
				fValues.remove(key, value);
				return null;
			}
			return (String) value[0];
		}

		@Override
		public void put(String key, String value, long ttlMillis) {
			fValues.put(key, new Object[] { value, (ttlMillis > 0) ? System.currentTimeMillis() + ttlMillis : 0L });
		}

		@Override
		public void remove(String key) {
			fValues.remove(key);
		}

		@Override
		public Set<String> keySet() {
			Set<String> keys = new HashSet<>();
			for (String key : fValues.keySet()) {
				if (get(key) != null) {
					keys.add(key);
				}
			}
			return keys;
		}
	}

	private static final class NamedThreads implements ThreadFactory {
		private final String fPrefix;
		private final AtomicLong fCount = new AtomicLong();

		NamedThreads(String prefix) {
			fPrefix = prefix;
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread thread = new Thread(r, fPrefix + fCount.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
	}

}
