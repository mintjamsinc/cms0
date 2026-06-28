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

package org.mintjams.rt.cms.internal.graphql.wiring;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.apache.camel.StatefulService;
import org.apache.camel.api.management.ManagedCamelContext;
import org.apache.camel.api.management.mbean.ManagedRouteMBean;
import org.apache.camel.health.HealthCheck;
import org.apache.camel.health.HealthCheckHelper;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.RuntimeEndpointRegistry;
import org.apache.camel.support.PluginHelper;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.eip.WorkspaceIntegrationEngineProvider;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.adapter.Adaptables;
import org.osgi.service.event.Event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;
import org.mintjams.rt.cms.internal.graphql.event.OsgiEventPublisher;

/**
 * Contributes the platform's EIP (Apache Camel) GraphQL schema — the graphql-java
 * migration target of the handmade {@code EipRouteExecutor} (and, in later steps,
 * {@code EipStatsQueryExecutor} / {@code CamelQueryExecutor}) — to the unified
 * per-workspace {@link org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider}.
 *
 * <p>It is a side-by-side reimplementation: its SDL ({@code eip-schema.graphqls})
 * {@code extend}s the core Query/Mutation roots, and its {@link DataFetcher}s
 * source routes from the deployed Camel context for the request's workspace
 * ({@code CmsService.getWorkspaceIntegrationEngineProvider(ws)}), projecting them
 * into the same flat maps the handmade engine produced.
 *
 * <p>Coverage: the {@code routes} Relay cursor connection and the single
 * {@code route} read, with the live runtime projection (status, managed-MBean
 * statistics, health-check readiness, endpoint connectivity and — for the single
 * read — the route's dumped XML/YAML model) (EIP-1a); {@code camelContext}, the
 * deployed contexts grouped per source file (EIP-1b); {@code historyExchanges} /
 * {@code historyExchange}, the exchange-history list and detail (EIP-1c);
 * {@code routeStats}, the banded exchange-count time series (EIP-1d); and the
 * route-control mutations {@code startRoute} / {@code stopRoute} /
 * {@code suspendRoute} / {@code resumeRoute} (EIP-2). (The {@code cluster}
 * topology read is served by the core platform contributor, not here.)
 *
 * <p>Unlike the handmade executor (one instance per request, so its health
 * snapshot is an instance field), these fetchers are static, so the per-request
 * health snapshot is computed once per fetcher call ({@link #computeHealth}) and
 * threaded through to {@link #buildRouteNode}/{@link #buildEndpoints}.
 */
public final class PlatformEipWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/eip-schema.graphqls";

	private static final int DEFAULT_PAGE_SIZE = 50;
	private static final int MAX_PAGE_SIZE = 500;

	/** Subtree holding the exchange-history records (one nt:file per route record). */
	private static final String HISTORY_BASE = "/var/eip/history";
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
			new TypeReference<LinkedHashMap<String, Object>>() {};

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "route", (DataFetcher<Object>) PlatformEipWiringContributor::route)
				.dataFetcher("Query", "routes", (DataFetcher<Object>) PlatformEipWiringContributor::routes)
				.dataFetcher("Query", "camelContext", (DataFetcher<Object>) PlatformEipWiringContributor::camelContext)
				.dataFetcher("Query", "historyExchanges",
						(DataFetcher<Object>) PlatformEipWiringContributor::historyExchanges)
				.dataFetcher("Query", "historyExchange",
						(DataFetcher<Object>) PlatformEipWiringContributor::historyExchange)
				.dataFetcher("Query", "routeStats", (DataFetcher<Object>) PlatformEipWiringContributor::routeStats)
				.dataFetcher("Mutation", "startRoute", (DataFetcher<Object>) PlatformEipWiringContributor::startRoute)
				.dataFetcher("Mutation", "stopRoute", (DataFetcher<Object>) PlatformEipWiringContributor::stopRoute)
				.dataFetcher("Mutation", "suspendRoute",
						(DataFetcher<Object>) PlatformEipWiringContributor::suspendRoute)
				.dataFetcher("Mutation", "resumeRoute",
						(DataFetcher<Object>) PlatformEipWiringContributor::resumeRoute)
				.dataFetcher("Subscription", "routeStateChanged",
						(DataFetcher<Object>) PlatformEipWiringContributor::routeStateChanged);
	}

	// ---- queries (mirror EipRouteExecutor) ---------------------------------

	private static Object routes(DataFetchingEnvironment environment) {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		Integer first = environment.getArgument("first");
		Integer last = environment.getArgument("last");
		String after = environment.getArgument("after");
		String before = environment.getArgument("before");
		String statusFilter = environment.getArgument("status");
		String groupFilter = environment.getArgument("group");
		String search = environment.getArgument("search");

		boolean backward = (last != null) || (before != null && first == null);
		int pageSize = clampPageSize(backward ? last : first);

		WorkspaceIntegrationEngineProvider provider = CmsService.getWorkspaceIntegrationEngineProvider(workspaceName);
		List<RouteRef> all = collectRoutes(provider);
		all.sort(Comparator.comparing(r -> r.routeId));

		CamelContext camelContext = (provider == null) ? null : provider.getCamelContext();
		String search1 = (search == null) ? null : search.toLowerCase();
		List<RouteRef> filtered = new ArrayList<>();
		for (RouteRef ref : all) {
			if (groupFilter != null && !groupFilter.isEmpty() && !groupFilter.equals(ref.group)) {
				continue;
			}
			if (search1 != null && !search1.isEmpty() && !ref.routeId.toLowerCase().contains(search1)) {
				continue;
			}
			if (statusFilter != null && !statusFilter.isEmpty() && camelContext != null) {
				String s = serviceStatusName(camelContext.getRouteController().getRouteStatus(ref.routeId));
				if (!statusFilter.equalsIgnoreCase(s)) {
					continue;
				}
			}
			filtered.add(ref);
		}

		String afterId = decodeCursor(after);
		String beforeId = decodeCursor(before);
		List<RouteRef> windowed = new ArrayList<>();
		for (RouteRef ref : filtered) {
			if (afterId != null && ref.routeId.compareTo(afterId) <= 0) {
				continue;
			}
			if (beforeId != null && ref.routeId.compareTo(beforeId) >= 0) {
				continue;
			}
			windowed.add(ref);
		}

		boolean hasMore;
		List<RouteRef> page;
		if (backward) {
			if (windowed.size() > pageSize) {
				hasMore = true;
				page = windowed.subList(windowed.size() - pageSize, windowed.size());
			} else {
				hasMore = false;
				page = windowed;
			}
		} else {
			if (windowed.size() > pageSize) {
				hasMore = true;
				page = windowed.subList(0, pageSize);
			} else {
				hasMore = false;
				page = windowed;
			}
		}

		ManagedCamelContext managedContext = managedContext(camelContext);
		RouteHealth health = computeHealth(camelContext);

		List<Map<String, Object>> edges = new ArrayList<>(page.size());
		for (RouteRef ref : page) {
			Map<String, Object> node = buildRouteNode(ref, camelContext, managedContext, false, health);
			Map<String, Object> edge = new LinkedHashMap<>();
			edge.put("node", node);
			edge.put("cursor", encodeCursor(ref.routeId));
			edges.add(edge);
		}

		Map<String, Object> pageInfo = new LinkedHashMap<>();
		boolean hasNextPage = backward ? (beforeId != null) : hasMore;
		boolean hasPreviousPage = backward ? hasMore : (afterId != null);
		pageInfo.put("hasNextPage", hasNextPage);
		pageInfo.put("hasPreviousPage", hasPreviousPage);
		pageInfo.put("startCursor", edges.isEmpty() ? null : edges.get(0).get("cursor"));
		pageInfo.put("endCursor", edges.isEmpty() ? null : edges.get(edges.size() - 1).get("cursor"));

		Map<String, Object> connection = new LinkedHashMap<>();
		connection.put("edges", edges);
		connection.put("pageInfo", pageInfo);
		connection.put("totalCount", filtered.size());
		return connection;
	}

	private static Object route(DataFetchingEnvironment environment) {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		String id = environment.getArgument("id");
		WorkspaceIntegrationEngineProvider provider = CmsService.getWorkspaceIntegrationEngineProvider(workspaceName);
		if (id == null || id.isEmpty() || provider == null) {
			return null;
		}
		CamelContext camelContext = provider.getCamelContext();
		ManagedCamelContext managedContext = managedContext(camelContext);
		RouteRef ref = findRoute(provider, id);
		if (ref == null) {
			return null;
		}
		RouteHealth health = computeHealth(camelContext);
		return buildRouteNode(ref, camelContext, managedContext, true, health);
	}

	// ---- camelContext (mirror CamelQueryExecutor) --------------------------

	/**
	 * Deployed Camel contexts grouped per integration source file. Mirrors the
	 * handmade {@code CamelQueryExecutor}: one entry per deployed source file,
	 * each carrying the (shared) context name/state and the routes that file
	 * contributed (id + live status + total throughput). Returns an empty list
	 * when no integration engine is deployed for the workspace.
	 */
	private static Object camelContext(DataFetchingEnvironment environment) {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		WorkspaceIntegrationEngineProvider provider = CmsService.getWorkspaceIntegrationEngineProvider(workspaceName);
		List<Map<String, Object>> contexts = new ArrayList<>();
		if (provider == null) {
			return contexts;
		}
		CamelContext camelContext = provider.getCamelContext();
		if (camelContext == null) {
			return contexts;
		}
		ManagedCamelContext managedContext = managedContext(camelContext);
		String name = camelContext.getName();
		String state = serviceStatusName(camelContext.getStatus());
		for (Map.Entry<String, List<String>> entry : provider.getDeployments().entrySet()) {
			Map<String, Object> contextNode = new LinkedHashMap<>();
			contextNode.put("name", name);
			contextNode.put("state", state);
			contextNode.put("sourceFile", entry.getKey());
			List<Map<String, Object>> routes = new ArrayList<>();
			for (String routeId : entry.getValue()) {
				Map<String, Object> routeNode = new LinkedHashMap<>();
				routeNode.put("id", routeId);
				routeNode.put("status", serviceStatusName(camelContext.getRouteController().getRouteStatus(routeId)));
				routeNode.put("exchangesTotal", exchangesTotal(managedContext, routeId));
				routes.add(routeNode);
			}
			contextNode.put("routes", routes);
			contexts.add(contextNode);
		}
		return contexts;
	}

	// ---- route projection --------------------------------------------------

	private static List<RouteRef> collectRoutes(WorkspaceIntegrationEngineProvider provider) {
		List<RouteRef> out = new ArrayList<>();
		if (provider == null) {
			return out;
		}
		for (Map.Entry<String, List<String>> e : provider.getDeployments().entrySet()) {
			String group = e.getKey();
			for (String routeId : e.getValue()) {
				out.add(new RouteRef(routeId, group));
			}
		}
		return out;
	}

	private static RouteRef findRoute(WorkspaceIntegrationEngineProvider provider, String id) {
		for (Map.Entry<String, List<String>> e : provider.getDeployments().entrySet()) {
			if (e.getValue().contains(id)) {
				return new RouteRef(id, e.getKey());
			}
		}
		return null;
	}

	private static ManagedCamelContext managedContext(CamelContext context) {
		if (context == null) {
			return null;
		}
		return context.getCamelContextExtension().getContextPlugin(ManagedCamelContext.class);
	}

	// Total exchanges for one route via the managed MBean; 0 when JMX/managed
	// statistics are unavailable (matches the handmade CamelQueryExecutor).
	private static long exchangesTotal(ManagedCamelContext managedContext, String routeId) {
		if (managedContext == null) {
			return 0L;
		}
		try {
			ManagedRouteMBean mbean = managedContext.getManagedRoute(routeId, ManagedRouteMBean.class);
			if (mbean != null) {
				return mbean.getExchangesTotal();
			}
		} catch (Exception ignore) {
			// JMX may be unavailable.
		}
		return 0L;
	}

	private static Map<String, Object> buildRouteNode(RouteRef ref, CamelContext context,
			ManagedCamelContext managedContext, boolean includeDefinition, RouteHealth health) {
		Map<String, Object> node = new LinkedHashMap<>();
		node.put("id", ref.routeId);
		node.put("routeId", ref.routeId);
		node.put("description", null);
		node.put("group", ref.group);

		String status = "Unknown";
		String uptime = null;
		Long uptimeMillis = null;
		long total = 0L, completed = 0L, failed = 0L, inflight = 0L;
		Double meanTime = null;
		Long lastTime = null;

		if (context != null) {
			ServiceStatus svc = context.getRouteController().getRouteStatus(ref.routeId);
			if (svc != null) {
				status = svc.name();
			}
		}

		if (managedContext != null) {
			try {
				ManagedRouteMBean mbean = managedContext.getManagedRoute(ref.routeId, ManagedRouteMBean.class);
				if (mbean != null) {
					uptime = mbean.getUptime();
					uptimeMillis = mbean.getUptimeMillis();
					total = mbean.getExchangesTotal();
					completed = mbean.getExchangesCompleted();
					failed = mbean.getExchangesFailed();
					inflight = mbean.getExchangesInflight();
					meanTime = (double) mbean.getMeanProcessingTime();
					lastTime = mbean.getLastProcessingTime();
				}
			} catch (Exception ignore) {
				// JMX may be unavailable — fall back to defaults.
			}
		}

		node.put("status", status);
		node.put("uptime", uptime);
		node.put("uptimeMillis", uptimeMillis);
		node.put("exchangesTotal", total);
		node.put("exchangesCompleted", completed);
		node.put("exchangesFailed", failed);
		node.put("exchangesInflight", inflight);
		node.put("meanProcessingTime", meanTime);
		node.put("lastProcessingTime", lastTime);
		node.put("health", health.byRoute.getOrDefault(ref.routeId, "UNKNOWN"));
		node.put("endpoints", buildEndpoints(context, ref.routeId, health));
		// Dumping the model is comparatively expensive, so it is only done for
		// single-route reads (Route.definition), never for the list.
		if (includeDefinition) {
			node.put("definition", buildDefinition(context, ref.routeId));
		}
		return node;
	}

	// ---- Route.definition --------------------------------------------------

	private static Map<String, Object> buildDefinition(CamelContext context, String routeId) {
		if (!(context instanceof ModelCamelContext)) {
			return null;
		}
		ModelCamelContext model = (ModelCamelContext) context;
		RouteDefinition def = model.getRouteDefinition(routeId);
		if (def == null) {
			return null;
		}
		Map<String, Object> definition = new LinkedHashMap<>();
		definition.put("id", routeId);
		definition.put("xml", dumpModel(context, def, true));
		definition.put("yaml", dumpModel(context, def, false));
		return definition;
	}

	// camel-yaml-io (ModelToYAMLDumper) may be absent at runtime. The lazy plugin
	// init then throws on every YAML dump attempt — and route control mutations dump
	// the model on every call — so once we see it missing we stop retrying (and stop
	// logging a stack trace per mutation). definition.yaml is simply null then.
	private static volatile boolean YAML_DUMPER_UNAVAILABLE = false;

	private static String dumpModel(CamelContext context, RouteDefinition def, boolean asXml) {
		if (!asXml && YAML_DUMPER_UNAVAILABLE) {
			return null;
		}
		try {
			if (asXml) {
				return PluginHelper.getModelToXMLDumper(context).dumpModelAsXml(context, def);
			}
			return PluginHelper.getModelToYAMLDumper(context).dumpModelAsYaml(context, def);
		} catch (Throwable ex) {
			if (!asXml) {
				YAML_DUMPER_UNAVAILABLE = true;
			}
			CmsService.getLogger(PlatformEipWiringContributor.class).warn(
					"Failed to dump route model as " + (asXml ? "XML" : "YAML") + " (" + def.getRouteId()
							+ "); definition." + (asXml ? "xml" : "yaml") + " will be null"
							+ (asXml ? "" : " — add camel-yaml-io to enable YAML dumps; further failures suppressed"),
					ex);
			return null;
		}
	}

	// ---- Route.endpoints ---------------------------------------------------

	private static List<Map<String, Object>> buildEndpoints(CamelContext context, String routeId, RouteHealth health) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (context == null) {
			return out;
		}

		LinkedHashSet<String> uris = new LinkedHashSet<>();
		try {
			RuntimeEndpointRegistry registry = context.getRuntimeEndpointRegistry();
			if (registry != null && registry.isEnabled()) {
				for (String uri : registry.getEndpointsPerRoute(routeId, true)) {
					if (uri != null) {
						uris.add(uri);
					}
				}
			}
		} catch (Exception ignore) {
			// Registry unavailable — fall back to the route input below.
		}
		try {
			Route route = context.getRoute(routeId);
			if (route != null && route.getEndpoint() != null) {
				uris.add(route.getEndpoint().getEndpointUri());
			}
		} catch (Exception ignore) {
			// Route not resolvable — leave whatever the registry provided.
		}

		for (String uri : uris) {
			Endpoint endpoint = null;
			try {
				endpoint = context.hasEndpoint(uri);
			} catch (Exception ignore) {
				// Unresolvable URI — fall back to scheme heuristics below.
			}
			String scheme = schemeOf(uri);
			String sanitized = sanitizeUri(uri);
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("uri", sanitized);
			node.put("component", scheme);
			node.put("remote", endpoint != null ? endpoint.isRemote() : isRemoteScheme(scheme));
			node.put("state", endpointState(endpoint));
			node.put("singleton", endpoint == null || endpoint.isSingleton());
			String h = health.byUri.get(sanitized);
			if (h == null) {
				h = health.byRoute.get(routeId);
			}
			node.put("health", h == null ? "UNKNOWN" : h);
			out.add(node);
		}
		return out;
	}

	private static String endpointState(Endpoint endpoint) {
		if (endpoint instanceof StatefulService) {
			return serviceStatusName(((StatefulService) endpoint).getStatus());
		}
		return endpoint == null ? "Unknown" : "Started";
	}

	private static String schemeOf(String uri) {
		if (uri == null) {
			return "";
		}
		int i = uri.indexOf(':');
		return i > 0 ? uri.substring(0, i) : uri;
	}

	// In-VM / internal component schemes (used only when Endpoint#isRemote() can't be consulted).
	private static final Set<String> LOCAL_SCHEMES = Set.of(
			"direct", "direct-vm", "seda", "vm", "log", "mock", "stub", "dataset",
			"timer", "scheduler", "quartz", "bean", "class", "language", "ref",
			"controlbus", "browse", "dataformat", "validator", "stream",
			"cms", "bpm", "eventadmin", "transform");

	private static boolean isRemoteScheme(String scheme) {
		if (scheme == null || scheme.isEmpty()) {
			return false;
		}
		return !LOCAL_SCHEMES.contains(scheme.toLowerCase());
	}

	// Strip credentials so the dashboard never surfaces secrets embedded in an endpoint URI.
	private static String sanitizeUri(String uri) {
		if (uri == null) {
			return "";
		}
		String out = uri.replaceFirst("://[^/@?#]*@", "://");
		out = out.replaceAll(
				"(?i)([?&](?:password|passphrase|secret|secretkey|accesskey|accesstoken|token|apikey|sas|sig)=)[^&]*",
				"$1***");
		return out;
	}

	private static String serviceStatusName(ServiceStatus status) {
		return status == null ? "Unknown" : status.name();
	}

	// ---- Health (L2 readiness) — UP / DOWN / UNKNOWN per route and endpoint ----

	/**
	 * Invokes the readiness health checks once and indexes the results by route id
	 * and (when a check carries one) by endpoint URI. Best-effort: any failure
	 * leaves the maps empty so callers fall back to "UNKNOWN". Computed once per
	 * fetcher call and threaded through buildRouteNode/buildEndpoints (the handmade
	 * executor caches it in an instance field per request).
	 */
	private static RouteHealth computeHealth(CamelContext context) {
		RouteHealth health = new RouteHealth();
		if (context == null) {
			return health;
		}
		try {
			Map<String, String> routeByUri = new HashMap<>();
			Set<String> knownRouteIds = new HashSet<>();
			RuntimeEndpointRegistry registry = context.getRuntimeEndpointRegistry();
			for (Route route : context.getRoutes()) {
				String routeId = route.getRouteId();
				knownRouteIds.add(routeId);
				try {
					if (registry != null && registry.isEnabled()) {
						for (String u : registry.getEndpointsPerRoute(routeId, true)) {
							if (u != null) {
								routeByUri.putIfAbsent(sanitizeUri(u), routeId);
							}
						}
					}
					if (route.getEndpoint() != null) {
						routeByUri.putIfAbsent(sanitizeUri(route.getEndpoint().getEndpointUri()), routeId);
					}
				} catch (Exception ignore) {
					// skip this route's endpoint indexing
				}
			}

			for (HealthCheck.Result result : HealthCheckHelper.invokeReadiness(context)) {
				if (result == null) {
					continue;
				}
				String state = result.getState() == null ? "UNKNOWN" : result.getState().name();
				Map<String, Object> details = result.getDetails();
				String uri = (details == null) ? null : asString(details.get(HealthCheck.ENDPOINT_URI));
				String sanitizedUri = (uri == null) ? null : sanitizeUri(uri);
				if (sanitizedUri != null) {
					mergeHealth(health.byUri, sanitizedUri, state);
				}

				String routeId = (sanitizedUri == null) ? null : routeByUri.get(sanitizedUri);
				if (routeId == null && result.getCheck() != null) {
					String checkId = result.getCheck().getId();
					if (checkId != null) {
						if (knownRouteIds.contains(checkId)) {
							routeId = checkId;
						} else {
							String stripped = stripPrefix(checkId);
							if (knownRouteIds.contains(stripped)) {
								routeId = stripped;
							}
						}
					}
				}
				if (routeId != null) {
					mergeHealth(health.byRoute, routeId, state);
				}
			}
		} catch (Throwable t) {
			// Health unavailable — leave maps empty (callers default to UNKNOWN).
		}
		return health;
	}

	// DOWN wins over UP wins over UNKNOWN.
	private static void mergeHealth(Map<String, String> map, String key, String state) {
		String current = map.get(key);
		if ("DOWN".equals(current) || "DOWN".equals(state)) {
			map.put(key, "DOWN");
		} else if ("UP".equals(current) || "UP".equals(state)) {
			map.put(key, "UP");
		} else {
			map.put(key, current != null ? current : (state != null ? state : "UNKNOWN"));
		}
	}

	private static String stripPrefix(String id) {
		int i = id.indexOf(':');
		return (i > 0 && i + 1 < id.length()) ? id.substring(i + 1) : id;
	}

	private static String asString(Object o) {
		return o == null ? null : o.toString();
	}

	// ---- cursor / paging helpers -------------------------------------------

	private static String encodeCursor(String routeId) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(routeId.getBytes(StandardCharsets.UTF_8));
	}

	private static String decodeCursor(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return null;
		}
		try {
			return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static int clampPageSize(Integer requested) {
		if (requested == null || requested <= 0) {
			return DEFAULT_PAGE_SIZE;
		}
		return Math.min(requested, MAX_PAGE_SIZE);
	}

	// =========================================================================
	// Exchange history (mirror EipStatsQueryExecutor: list + detail). Runs under
	// the caller's JCR session over /var/eip/history for the request's workspace.
	// =========================================================================

	private static final long BAND_1S_MS = 1_000L;
	private static final long BAND_5S_MS = 5_000L;

	/**
	 * Relay cursor connection over the workspace's exchange-history records,
	 * newest-first (or oldest-first for backward paging). Mirrors the handmade
	 * {@code historyExchanges}: keyset (seek) pagination on the
	 * (createdAt, exchangeId, routeId) total order, with a separate base
	 * predicate so {@code totalCount} stays stable across pages.
	 */
	private static Object historyExchanges(DataFetchingEnvironment environment) throws Exception {
		Session session = GraphQLExecutionContext.from(environment).getCallerSession();
		List<String> routes = environment.getArgument("routes");
		String status = environment.getArgument("status");
		String from = environment.getArgument("from");
		String to = environment.getArgument("to");
		String filterText = environment.getArgument("filter");
		List<String> elapsedBands = environment.getArgument("elapsedBands");

		Integer first = environment.getArgument("first");
		Integer last = environment.getArgument("last");
		String after = environment.getArgument("after");
		String before = environment.getArgument("before");

		boolean backward = (last != null) || (before != null && first == null);
		int pageSize = clampPageSize(backward ? last : first);

		// Base predicate (shared by the page query and the count query). Anchored
		// on @mi:exchangeId so it matches only real history records / is never empty.
		StringBuilder basePred = new StringBuilder("@mi:exchangeId");
		appendRouteFilter(basePred, routes);
		appendStatusFilter(basePred, status);
		if (from != null && !from.isEmpty()) {
			basePred.append(" and @mi:createdAt >= xs:dateTime('").append(escapeLiteral(from)).append("')");
		}
		if (to != null && !to.isEmpty()) {
			basePred.append(" and @mi:createdAt < xs:dateTime('").append(escapeLiteral(to)).append("')");
		}
		appendFilterText(basePred, filterText);
		appendElapsedBandsFilter(basePred, elapsedBands);

		// Cursor predicate, kept separate so totalCount ignores the page window.
		HistoryCursor afterCursor = decodeHistoryCursor(after);
		HistoryCursor beforeCursor = decodeHistoryCursor(before);
		StringBuilder cursorPred = new StringBuilder();
		if (afterCursor != null) {
			appendKeysetPredicate(cursorPred, afterCursor, "<");
		}
		if (beforeCursor != null) {
			appendKeysetPredicate(cursorPred, beforeCursor, ">");
		}

		String order = backward ? "ascending" : "descending";
		// @mi:createdAt is a DATE property; wrap it in xs:dateTime(...) so the
		// engine sorts numerically (chronologically) rather than degrading to a
		// STRING/insertion-order sort. exchangeId/routeId are strings (sort as-is).
		String xpath = "/jcr:root" + HISTORY_BASE + "//element(*, nt:file)["
				+ basePred + cursorPred + "]"
				+ " order by xs:dateTime(@mi:createdAt) " + order
				+ ", @mi:exchangeId " + order
				+ ", @mi:routeId " + order;

		QueryManager qm = session.getWorkspace().getQueryManager();
		Query q = qm.createQuery(xpath, Query.XPATH);
		q.setLimit(pageSize + 1L); // one extra row to detect a further page
		QueryResult result = q.execute();

		List<EdgeRow> rows = new ArrayList<>();
		for (NodeIterator it = result.getNodes(); it.hasNext();) {
			Node fileNode = it.nextNode();
			Map<String, Object> node = buildExchangeSummary(fileNode);
			rows.add(new EdgeRow(node, (String) node.get("createdAt"), (String) node.get("exchangeId"),
					(String) node.get("routeId")));
		}

		boolean hasMore = rows.size() > pageSize;
		if (hasMore) {
			rows = rows.subList(0, pageSize);
		}
		if (backward) {
			Collections.reverse(rows);
		}

		List<Map<String, Object>> edges = new ArrayList<>(rows.size());
		for (EdgeRow r : rows) {
			Map<String, Object> edge = new LinkedHashMap<>();
			edge.put("node", r.node);
			edge.put("cursor", encodeHistoryCursor(r.createdAt, r.exchangeId, r.routeId));
			edges.add(edge);
		}

		Map<String, Object> pageInfo = new LinkedHashMap<>();
		boolean hasNextPage = backward ? (beforeCursor != null) : hasMore;
		boolean hasPreviousPage = backward ? hasMore : (afterCursor != null);
		pageInfo.put("hasNextPage", hasNextPage);
		pageInfo.put("hasPreviousPage", hasPreviousPage);
		pageInfo.put("startCursor", edges.isEmpty() ? null : edges.get(0).get("cursor"));
		pageInfo.put("endCursor", edges.isEmpty() ? null : edges.get(edges.size() - 1).get("cursor"));

		String countXpath = "/jcr:root" + HISTORY_BASE + "//element(*, nt:file)[" + basePred + "]";
		long total = countQuery(session, countXpath);

		Map<String, Object> connection = new LinkedHashMap<>();
		connection.put("edges", edges);
		connection.put("pageInfo", pageInfo);
		connection.put("totalCount", total);
		return connection;
	}

	/**
	 * Full detail for a single history record. Addressed by node {@code path}
	 * (preferred — a single exchange can have one record per route it completed
	 * in, so exchangeId is ambiguous) or by {@code exchangeId} (back-compat,
	 * first match). Returns {@code null} when no record matches.
	 */
	private static Object historyExchange(DataFetchingEnvironment environment) throws Exception {
		Session session = GraphQLExecutionContext.from(environment).getCallerSession();
		String path = environment.getArgument("path");

		Node fileNode;
		if (path != null && !path.isEmpty()) {
			// Constrain to the history subtree so the argument cannot read arbitrary nodes.
			if (!path.equals(HISTORY_BASE) && !path.startsWith(HISTORY_BASE + "/")) {
				throw new IllegalArgumentException("path must be under " + HISTORY_BASE);
			}
			if (!session.nodeExists(path)) {
				return null;
			}
			fileNode = session.getNode(path);
		} else {
			String exchangeId = environment.getArgument("exchangeId");
			if (exchangeId == null || exchangeId.isEmpty()) {
				throw new IllegalArgumentException("Either path or exchangeId is required");
			}
			String xpath = "/jcr:root" + HISTORY_BASE + "//element(*, nt:file)["
					+ "@mi:exchangeId = '" + escapeLiteral(exchangeId) + "']";
			QueryManager qm = session.getWorkspace().getQueryManager();
			Query q = qm.createQuery(xpath, Query.XPATH);
			q.setLimit(1L);
			QueryResult result = q.execute();
			NodeIterator it = result.getNodes();
			if (!it.hasNext()) {
				return null;
			}
			fileNode = it.nextNode();
		}
		return buildExchangeDetail(fileNode);
	}

	// ---- history predicates & XPath fragments ------------------------------

	private static void appendRouteFilter(StringBuilder pred, List<String> routes) {
		if (routes == null || routes.isEmpty()) {
			return;
		}
		if (routes.size() == 1) {
			pred.append(" and @mi:routeId = '").append(escapeLiteral(routes.get(0))).append("'");
			return;
		}
		pred.append(" and (");
		for (int i = 0; i < routes.size(); i++) {
			if (i > 0) {
				pred.append(" or ");
			}
			pred.append("@mi:routeId = '").append(escapeLiteral(routes.get(i))).append("'");
		}
		pred.append(")");
	}

	private static void appendStatusFilter(StringBuilder pred, String status) {
		if (status == null || status.isEmpty() || "all".equalsIgnoreCase(status)) {
			return;
		}
		pred.append(" and @mi:status = '").append(escapeLiteral(status)).append("'");
	}

	/** Substring match against businessKey / exchangeId / routeId (OR'd). */
	private static void appendFilterText(StringBuilder pred, String filter) {
		if (filter == null || filter.isBlank()) {
			return;
		}
		String escaped = escapeLiteral(filter.trim());
		pred.append(" and (")
				.append("jcr:contains(@mi:businessKey, '").append(escaped).append("')")
				.append(" or jcr:contains(@mi:exchangeId, '").append(escaped).append("')")
				.append(" or jcr:contains(@mi:routeId, '").append(escaped).append("')")
				.append(")");
	}

	/**
	 * Restrict to one or more elapsed bands (under1s / under5s / over5s),
	 * mirroring the handmade. Unknown names are ignored; an all-bands (or empty)
	 * selection is a no-op; a selection of only-unknown names matches nothing.
	 */
	private static void appendElapsedBandsFilter(StringBuilder pred, List<String> bands) {
		if (bands == null || bands.isEmpty()) {
			return;
		}
		boolean under1s = false, under5s = false, over5s = false;
		for (String b : bands) {
			if (b == null) {
				continue;
			}
			switch (b) {
				case "under1s": under1s = true; break;
				case "under5s": under5s = true; break;
				case "over5s":  over5s  = true; break;
				default: break; // unknown — ignore
			}
		}
		if (!under1s && !under5s && !over5s) {
			pred.append(" and @mi:elapsed < 0"); // only-unknown names → match nothing
			return;
		}
		if (under1s && under5s && over5s) {
			return; // full domain → skip
		}
		List<String> clauses = new ArrayList<>(3);
		if (under1s) {
			clauses.add("@mi:elapsed < " + BAND_1S_MS);
		}
		if (under5s) {
			clauses.add("(@mi:elapsed >= " + BAND_1S_MS + " and @mi:elapsed < " + BAND_5S_MS + ")");
		}
		if (over5s) {
			clauses.add("@mi:elapsed >= " + BAND_5S_MS);
		}
		pred.append(" and (");
		for (int i = 0; i < clauses.size(); i++) {
			if (i > 0) {
				pred.append(" or ");
			}
			pred.append(clauses.get(i));
		}
		pred.append(")");
	}

	// Escape single quotes for an XPath string literal (handmade parity).
	private static String escapeLiteral(String v) {
		return v.replace("'", "\\'");
	}

	/**
	 * Exact match count for {@code totalCount}, executed with {@code limit=0} so
	 * the query materialises no nodes; the count comes from the index via
	 * {@link NodeIterator#getSize()} (uncapped, effectively constant-time).
	 * Returns -1 on failure (handmade parity).
	 */
	private static long countQuery(Session session, String xpath) {
		try {
			QueryManager qm = session.getWorkspace().getQueryManager();
			Query q = qm.createQuery(xpath, Query.XPATH);
			q.setLimit(0L);
			QueryResult r = q.execute();
			return r.getNodes().getSize();
		} catch (Exception ex) {
			return -1L;
		}
	}

	// ---- history cursor (createdAt | exchangeId | routeId keyset) ----------

	/**
	 * Append a keyset (seek) predicate for the (createdAt, exchangeId, routeId)
	 * sort key, comparing strictly in {@code cmp} ({@code "<"} for a descending
	 * "after" page, {@code ">"} for "before"). Expands the lexicographic tuple
	 * comparison; the routeId tier is emitted only when the cursor carries one.
	 */
	private static void appendKeysetPredicate(StringBuilder pred, HistoryCursor cursor, String cmp) {
		String c = escapeLiteral(cursor.createdAt);
		String x = escapeLiteral(cursor.exchangeId);
		pred.append(" and (@mi:createdAt ").append(cmp).append(" xs:dateTime('").append(c).append("')")
				.append(" or (@mi:createdAt = xs:dateTime('").append(c).append("') and @mi:exchangeId ")
				.append(cmp).append(" '").append(x).append("')");
		if (cursor.routeId != null) {
			String r = escapeLiteral(cursor.routeId);
			pred.append(" or (@mi:createdAt = xs:dateTime('").append(c).append("') and @mi:exchangeId = '")
					.append(x).append("' and @mi:routeId ").append(cmp).append(" '").append(r).append("')");
		}
		pred.append(")");
	}

	private static String encodeHistoryCursor(String createdAt, String exchangeId, String routeId) {
		if (createdAt == null || exchangeId == null) {
			return null;
		}
		String raw = createdAt + "|" + exchangeId + "|" + (routeId == null ? "" : routeId);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static HistoryCursor decodeHistoryCursor(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return null;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			// createdAt (ISO) and exchangeId never contain '|'; a routeId might, so
			// the third component is the unsplit remainder.
			int sep1 = raw.indexOf('|');
			if (sep1 <= 0 || sep1 >= raw.length() - 1) {
				return null;
			}
			int sep2 = raw.indexOf('|', sep1 + 1);
			if (sep2 < 0) {
				return new HistoryCursor(raw.substring(0, sep1), raw.substring(sep1 + 1), null);
			}
			return new HistoryCursor(raw.substring(0, sep1), raw.substring(sep1 + 1, sep2), raw.substring(sep2 + 1));
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	// ---- history node projection -------------------------------------------

	private static Map<String, Object> buildExchangeSummary(Node fileNode) throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		// The JCR node path is the only stable, globally-unique identity: one
		// record per route an exchange completed in (same exchangeId).
		m.put("path", fileNode.getPath());
		m.put("exchangeId", propString(fileNode, "mi:exchangeId"));
		m.put("routeId", propString(fileNode, "mi:routeId"));
		m.put("status", propString(fileNode, "mi:status"));
		m.put("elapsed", propLong(fileNode, "mi:elapsed"));
		m.put("createdAt", propIsoDate(fileNode, "mi:createdAt"));
		m.put("businessKey", propString(fileNode, "mi:businessKey"));
		return m;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> buildExchangeDetail(Node fileNode) throws Exception {
		Map<String, Object> detail = new LinkedHashMap<>();
		String json = JCRs.getContentAsString(fileNode);
		Map<String, Object> record = (json == null || json.isEmpty())
				? new LinkedHashMap<>() : MAPPER.readValue(json, MAP_TYPE);

		detail.put("path", fileNode.getPath());
		detail.put("exchangeId", record.get("exchangeId"));
		detail.put("routeId", record.get("routeId"));
		detail.put("status", record.get("status"));
		detail.put("elapsed", record.get("elapsed"));
		detail.put("createdAt", record.get("createdAt"));
		detail.put("completedAt", record.get("completedAt"));
		detail.put("exceptionType", record.get("exceptionType"));
		detail.put("exceptionMessage", record.get("exceptionMessage"));
		detail.put("businessKey", record.get("businessKey"));
		detail.put("bodyType", record.get("bodyType"));
		detail.put("bodySize", record.get("bodySize"));
		detail.put("headers", record.get("headers"));

		Object stepsObj = record.get("steps");
		List<Map<String, Object>> steps = new ArrayList<>();
		if (stepsObj instanceof List) {
			for (Object o : (List<Object>) stepsObj) {
				if (o instanceof Map) {
					Map<String, Object> s = (Map<String, Object>) o;
					Map<String, Object> step = new LinkedHashMap<>();
					step.put("id", s.get("id"));
					step.put("endpointUri", s.get("endpointUri"));
					step.put("timeTaken", s.get("timeTaken"));
					step.put("offsetFromStart", s.get("offsetFromStart"));
					step.put("order", s.get("order"));
					steps.add(step);
				}
			}
		}
		detail.put("steps", steps);
		return detail;
	}

	private static String propString(Node fileNode, String name) throws Exception {
		Node content = JCRs.getContentNode(fileNode);
		if (!content.hasProperty(name)) {
			return null;
		}
		return content.getProperty(name).getString();
	}

	private static Long propLong(Node fileNode, String name) throws Exception {
		Node content = JCRs.getContentNode(fileNode);
		if (!content.hasProperty(name)) {
			return null;
		}
		return content.getProperty(name).getLong();
	}

	private static String propIsoDate(Node fileNode, String name) throws Exception {
		Node content = JCRs.getContentNode(fileNode);
		if (!content.hasProperty(name)) {
			return null;
		}
		return Instant.ofEpochMilli(content.getProperty(name).getDate().getTimeInMillis()).toString();
	}

	private static final class HistoryCursor {
		final String createdAt;
		final String exchangeId;
		final String routeId;

		HistoryCursor(String createdAt, String exchangeId, String routeId) {
			this.createdAt = createdAt;
			this.exchangeId = exchangeId;
			this.routeId = routeId;
		}
	}

	private static final class EdgeRow {
		final Map<String, Object> node;
		final String createdAt;
		final String exchangeId;
		final String routeId;

		EdgeRow(Map<String, Object> node, String createdAt, String exchangeId, String routeId) {
			this.node = node;
			this.createdAt = createdAt;
			this.exchangeId = exchangeId;
			this.routeId = routeId;
		}
	}

	// =========================================================================
	// routeStats (mirror EipStatsQueryExecutor): a banded exchange-count time
	// series. Each elapsed band is one facet-accumulate query over @mi:createdAt
	// bucketed into N time ranges, scoped by the band's elapsed predicate; the
	// Lucene facet counts are mapped back onto the buckets. Runs under the
	// caller's JCR session via the SearchIndex adapter (zero counts if absent).
	// =========================================================================

	private static Object routeStats(DataFetchingEnvironment environment) throws Exception {
		Session session = GraphQLExecutionContext.from(environment).getCallerSession();
		List<String> routes = environment.getArgument("routes");
		String status = environment.getArgument("status");
		String intervalLabel = environment.getArgument("interval");

		// Window resolution. anchor mode: the fixed wall-clock right-edge bucket
		// containing `at`, then `buckets` whole intervals back (the live console's
		// sliding window). window mode: explicit [from, to) with an auto interval.
		Instant anchor;
		Instant from;
		Instant to;
		Interval interval;
		Integer bucketCount = environment.getArgument("buckets");
		if (bucketCount != null && bucketCount > 0) {
			String at = environment.getArgument("at");
			anchor = (at != null && !at.isEmpty()) ? Instant.parse(at) : Instant.now();
			interval = Interval.forLabel(intervalLabel);
			if (interval == null) {
				interval = Interval.FIVE_MINUTES;
			}
			Instant rightStart = interval.truncate(anchor);
			to = interval.endOf(rightStart);
			from = rightStart.minus((long) (bucketCount - 1) * interval.amount, interval.unit);
		} else {
			String fromArg = environment.getArgument("from");
			String toArg = environment.getArgument("to");
			if (fromArg == null || fromArg.isEmpty() || toArg == null || toArg.isEmpty()) {
				throw new IllegalArgumentException("Either buckets (with at) or both from and to are required");
			}
			from = Instant.parse(fromArg);
			to = Instant.parse(toArg);
			interval = resolveInterval(intervalLabel, from, to);
			anchor = to;
		}

		List<Bucket> buckets = buildBuckets(from, to, interval);

		// Elapsed band boundaries (ms, ascending). N boundaries → N+1 bands.
		// Default {1000, 5000} reproduces the historical three-band view.
		List<Long> boundaries = sanitizeBoundaries(environment.getArgument("elapsedBoundaries"));
		int bandCount = boundaries.size() + 1;

		StringBuilder basePred = new StringBuilder("@mi:exchangeId");
		appendRangeFilter(basePred, from, to);
		appendRouteFilter(basePred, routes);
		appendStatusFilter(basePred, status);

		long[][] bandCounts = new long[bandCount][];
		for (int b = 0; b < bandCount; b++) {
			bandCounts[b] = runBandFacet(session, basePred, elapsedBandPredicate(boundaries, b), buckets);
		}

		List<Map<String, Object>> points = new ArrayList<>(buckets.size());
		for (int i = 0; i < buckets.size(); i++) {
			Map<String, Object> point = new LinkedHashMap<>();
			point.put("bucket", buckets.get(i).start.toString());
			List<Long> bands = new ArrayList<>(bandCount);
			for (int b = 0; b < bandCount; b++) {
				bands.add(bandCounts[b][i]);
			}
			point.put("bands", bands);
			points.add(point);
		}

		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("anchor", anchor.toString());
		stats.put("from", from.toString());
		stats.put("to", to.toString());
		stats.put("interval", interval.label);
		stats.put("boundaries", boundaries);
		stats.put("points", points);
		return stats;
	}

	/** Drop non-positive / duplicate boundaries and sort ascending; default {1000, 5000}. */
	private static List<Long> sanitizeBoundaries(List<Integer> raw) {
		TreeSet<Long> set = new TreeSet<>();
		if (raw != null) {
			for (Integer v : raw) {
				if (v != null && v > 0) {
					set.add(v.longValue());
				}
			}
		}
		if (set.isEmpty()) {
			set.add(BAND_1S_MS);
			set.add(BAND_5S_MS);
		}
		return new ArrayList<>(set);
	}

	/**
	 * Elapsed predicate for band {@code b}: band 0 is {@code elapsed < boundaries[0]},
	 * the last is {@code elapsed >= boundaries[last]}, interior bands are half-open
	 * {@code [boundaries[b-1], boundaries[b])}.
	 */
	private static String elapsedBandPredicate(List<Long> boundaries, int b) {
		int n = boundaries.size();
		if (b == 0) {
			return "@mi:elapsed < " + boundaries.get(0);
		}
		if (b >= n) {
			return "@mi:elapsed >= " + boundaries.get(n - 1);
		}
		return "@mi:elapsed >= " + boundaries.get(b - 1) + " and @mi:elapsed < " + boundaries.get(b);
	}

	/**
	 * One facet-accumulate query over {@code @mi:createdAt} bucketed into the time
	 * ranges, scoped by {@code elapsedPred}; returns per-bucket counts. The JCR
	 * Query is adapted to {@link SearchIndex} so only facet counts are fetched
	 * ({@code limit=0}, no node materialisation). Zeros if the index is absent.
	 */
	private static long[] runBandFacet(Session session, StringBuilder basePred, String elapsedPred,
			List<Bucket> buckets) throws Exception {
		long[] counts = new long[buckets.size()];
		if (buckets.isEmpty()) {
			return counts;
		}

		StringBuilder xpath = new StringBuilder("/jcr:root").append(HISTORY_BASE)
				.append("//element(*, nt:file)[")
				.append(basePred);
		if (elapsedPred != null && !elapsedPred.isEmpty()) {
			xpath.append(" and ").append(elapsedPred);
		}
		xpath.append("] facet accumulate ");
		for (int i = 0; i < buckets.size(); i++) {
			if (i > 0) {
				xpath.append(", ");
			}
			Bucket b = buckets.get(i);
			xpath.append("range('").append(i).append("', ")
					.append("xs:dateTime('").append(b.start.toString()).append("') <= @mi:createdAt < ")
					.append("xs:dateTime('").append(b.end.toString()).append("'))");
		}

		SearchIndex searchIndex = Adaptables.getAdapter(session, SearchIndex.class);
		if (searchIndex == null) {
			return counts; // index unavailable → zero counts (handmade parity)
		}

		SearchIndex.QueryResult.FacetResult result = searchIndex
				.createQuery(xpath.toString(), "jcr:xpath")
				.setOffset(0)
				.setLimit(0)
				.execute()
				.getFacetResult();
		SearchIndex.QueryResult.FacetResult.Facet facet = result.getFacet("mi:createdAt");
		if (facet == null) {
			return counts;
		}
		for (String label : facet.getLabels()) {
			int idx;
			try {
				idx = Integer.parseInt(label);
			} catch (NumberFormatException ex) {
				continue;
			}
			if (idx < 0 || idx >= counts.length) {
				continue;
			}
			counts[idx] = facet.getValue(label);
		}
		return counts;
	}

	private static void appendRangeFilter(StringBuilder pred, Instant from, Instant to) {
		pred.append(" and @mi:createdAt >= xs:dateTime('").append(from.toString()).append("')");
		pred.append(" and @mi:createdAt < xs:dateTime('").append(to.toString()).append("')");
	}

	private static Interval resolveInterval(String label, Instant from, Instant to) {
		Interval explicit = Interval.forLabel(label);
		if (explicit != null) {
			return explicit;
		}
		Duration span = Duration.between(from, to);
		if (span.compareTo(Duration.ofHours(2)) <= 0) {
			return Interval.FIVE_MINUTES;
		}
		if (span.compareTo(Duration.ofDays(2)) <= 0) {
			return Interval.ONE_HOUR;
		}
		return Interval.ONE_DAY;
	}

	private static List<Bucket> buildBuckets(Instant from, Instant to, Interval interval) {
		List<Bucket> buckets = new ArrayList<>();
		Instant cursor = interval.truncate(from);
		while (cursor.isBefore(to)) {
			Instant next = interval.endOf(cursor);
			buckets.add(new Bucket(cursor, next));
			cursor = next;
		}
		return buckets;
	}

	/** Bucket interval for the time series: a label plus its ChronoUnit step. */
	private enum Interval {
		FIVE_MINUTES("5min", ChronoUnit.MINUTES, 5),
		ONE_HOUR("1h", ChronoUnit.HOURS, 1),
		ONE_DAY("1d", ChronoUnit.DAYS, 1);

		final String label;
		final ChronoUnit unit;
		final int amount;

		Interval(String label, ChronoUnit unit, int amount) {
			this.label = label;
			this.unit = unit;
			this.amount = amount;
		}

		Instant truncate(Instant t) {
			if (unit == ChronoUnit.DAYS) {
				return t.truncatedTo(ChronoUnit.DAYS);
			}
			Instant truncated = t.truncatedTo(unit);
			if (unit == ChronoUnit.MINUTES && amount > 1) {
				long m = truncated.atZone(ZoneOffset.UTC).getMinute();
				long aligned = (m / amount) * amount;
				return truncated.minus(m - aligned, ChronoUnit.MINUTES);
			}
			return truncated;
		}

		Instant endOf(Instant start) {
			return start.plus(amount, unit);
		}

		static Interval forLabel(String label) {
			if (label == null) {
				return null;
			}
			for (Interval i : values()) {
				if (i.label.equalsIgnoreCase(label)) {
					return i;
				}
			}
			return null;
		}
	}

	private static final class Bucket {
		final Instant start;
		final Instant end;

		Bucket(Instant start, Instant end) {
			this.start = start;
			this.end = end;
		}
	}

	// =========================================================================
	// Route control mutations (mirror EipRouteExecutor): Input Object Pattern,
	// operating on the deployed route controller for the request's workspace (no
	// JCR writes). Each returns the route's full projection after the operation.
	// Handmade parity: no extra authorization beyond the authenticated endpoint.
	// =========================================================================

	private static Object startRoute(DataFetchingEnvironment environment) throws Exception {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		String id = requireId(requireInput(environment));
		WorkspaceIntegrationEngineProvider provider = requireProvider(workspaceName);
		provider.getCamelContext().getRouteController().startRoute(id);
		return routeNodeAfter(provider, id);
	}

	private static Object stopRoute(DataFetchingEnvironment environment) throws Exception {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		Map<String, Object> input = requireInput(environment);
		String id = requireId(input);
		Integer timeout = (Integer) input.get("timeout");
		WorkspaceIntegrationEngineProvider provider = requireProvider(workspaceName);
		if (timeout != null) {
			provider.getCamelContext().getRouteController().stopRoute(id, timeout.longValue(), TimeUnit.SECONDS);
		} else {
			provider.getCamelContext().getRouteController().stopRoute(id);
		}
		return routeNodeAfter(provider, id);
	}

	private static Object suspendRoute(DataFetchingEnvironment environment) throws Exception {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		Map<String, Object> input = requireInput(environment);
		String id = requireId(input);
		Integer timeout = (Integer) input.get("timeout");
		WorkspaceIntegrationEngineProvider provider = requireProvider(workspaceName);
		if (timeout != null) {
			provider.getCamelContext().getRouteController().suspendRoute(id, timeout.longValue(), TimeUnit.SECONDS);
		} else {
			provider.getCamelContext().getRouteController().suspendRoute(id);
		}
		return routeNodeAfter(provider, id);
	}

	private static Object resumeRoute(DataFetchingEnvironment environment) throws Exception {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		String id = requireId(requireInput(environment));
		WorkspaceIntegrationEngineProvider provider = requireProvider(workspaceName);
		provider.getCamelContext().getRouteController().resumeRoute(id);
		return routeNodeAfter(provider, id);
	}

	/** Full route projection after a control operation (mirrors the handmade return). */
	private static Map<String, Object> routeNodeAfter(WorkspaceIntegrationEngineProvider provider, String id) {
		RouteRef ref = requireRouteRef(provider, id);
		CamelContext context = provider.getCamelContext();
		ManagedCamelContext managedContext = managedContext(context);
		RouteHealth health = computeHealth(context);
		return buildRouteNode(ref, context, managedContext, true, health);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> requireInput(DataFetchingEnvironment environment) {
		Object input = environment.getArgument("input");
		if (!(input instanceof Map)) {
			throw new IllegalArgumentException("Missing input");
		}
		return (Map<String, Object>) input;
	}

	private static String requireId(Map<String, Object> input) {
		Object id = input.get("id");
		if (id == null || id.toString().isEmpty()) {
			throw new IllegalArgumentException("Missing required field: id");
		}
		return id.toString();
	}

	private static WorkspaceIntegrationEngineProvider requireProvider(String workspaceName) {
		WorkspaceIntegrationEngineProvider provider = CmsService.getWorkspaceIntegrationEngineProvider(workspaceName);
		if (provider == null) {
			throw new IllegalStateException("No Camel engine deployed for workspace " + workspaceName);
		}
		return provider;
	}

	private static RouteRef requireRouteRef(WorkspaceIntegrationEngineProvider provider, String id) {
		RouteRef ref = findRoute(provider, id);
		if (ref == null) {
			throw new IllegalArgumentException("Unknown route: " + id);
		}
		return ref;
	}

	private static String loadSchema() throws Exception {
		try (InputStream in = PlatformEipWiringContributor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("EIP GraphQL schema resource not found: " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static final class RouteRef {
		final String routeId;
		final String group;

		RouteRef(String routeId, String group) {
			this.routeId = routeId;
			this.group = group;
		}
	}

	/** Per-request health snapshot: route id → state and sanitized endpoint URI → state. */
	private static final class RouteHealth {
		final Map<String, String> byRoute = new HashMap<>();
		final Map<String, String> byUri = new HashMap<>();
	}

	// ------------------------------------------------------------------------
	// Subscription: routeStateChanged
	//
	// Camel route lifecycle is already bridged to the OSGi EventAdmin by
	// EventAdminEventNotifier (topics org/apache/camel/Route/<ACTION>, with a
	// "workspace", "routeId" and "timestamp" property on every event). The
	// subscription simply re-publishes the state-change actions as RouteStateEvent,
	// reusing the same OsgiEventPublisher backbone as the BPM subscriptions.
	// ------------------------------------------------------------------------

	private static final String[] ROUTE_TOPICS = { "org/apache/camel/Route/*" };

	/**
	 * {@code Subscription.routeStateChanged(routeId)} — a route's lifecycle state
	 * change, bridged from Camel {@code org/apache/camel/Route/*} EventAdmin events,
	 * scoped to this workspace and (optionally) one route.
	 */
	private static Object routeStateChanged(DataFetchingEnvironment environment) {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		String routeId = environment.getArgument("routeId");
		return new OsgiEventPublisher(ROUTE_TOPICS,
				event -> eipWorkspaceMatches(event, workspaceName)
						&& routeStateOf(topicAction(event)) != null
						&& (routeId == null || routeId.equals(event.getProperty("routeId"))),
				PlatformEipWiringContributor::routeStateEventPayload);
	}

	/** The "workspace" property EventAdminEventNotifier stamps on every Camel event. */
	private static boolean eipWorkspaceMatches(Event event, String workspaceName) {
		return workspaceName != null && workspaceName.equals(event.getProperty("workspace"));
	}

	/** The trailing topic segment — the Camel route action (STARTED/STOPPED/...). */
	private static String topicAction(Event event) {
		String topic = event.getTopic();
		return (topic == null) ? null : topic.substring(topic.lastIndexOf('/') + 1);
	}

	/**
	 * Maps a Camel Route lifecycle action to a {@code RouteState} enum name, or null
	 * for actions that are not a state transition (ADDED/REMOVED/RELOADED/RESTARTING),
	 * which are filtered out so {@code currentState} (non-null) is always valid.
	 */
	private static String routeStateOf(String action) {
		if (action == null) {
			return null;
		}
		switch (action) {
		case "STARTED": return "Started";
		case "STOPPED": return "Stopped";
		case "STARTING": return "Starting";
		case "STOPPING": return "Stopping";
		case "SUSPENDED": return "Suspended";
		case "SUSPENDING": return "Suspending";
		default: return null;
		}
	}

	private static Map<String, Object> routeStateEventPayload(Event event) {
		String state = routeStateOf(topicAction(event));
		if (state == null) {
			return null;
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("routeId", event.getProperty("routeId"));
		// The Camel event does not carry the prior state; the schema permits null.
		data.put("previousState", null);
		data.put("currentState", state);
		Object ts = event.getProperty("timestamp");
		data.put("timestamp", (ts instanceof Number)
				? Instant.ofEpochMilli(((Number) ts).longValue()).toString()
				: Instant.now().toString());
		Object cause = event.getProperty("causeMessage");
		if (cause != null) {
			data.put("error", cause.toString());
		}
		return data;
	}

}
