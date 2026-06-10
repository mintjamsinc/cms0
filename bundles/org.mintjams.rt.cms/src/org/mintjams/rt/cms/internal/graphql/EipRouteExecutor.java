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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.jcr.Session;

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
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.eip.WorkspaceIntegrationEngineProvider;

/**
 * Executor for EIP {@code routes} Relay-style cursor connection query and the
 * Route control mutations ({@code startRoute} / {@code stopRoute} /
 * {@code suspendRoute} / {@code resumeRoute}) using the Input Object Pattern.
 *
 * <p>Routes are sourced from the deployed Camel context for the current
 * workspace via {@link WorkspaceIntegrationEngineProvider#getDeployments()}.
 * Pagination is forward/backward cursor-based; the cursor encodes the route
 * id (Base64-URL) so the result is stable even if routes are added or removed
 * between pages.
 */
public class EipRouteExecutor {

	private final Session session;

	private static final int DEFAULT_PAGE_SIZE = 50;
	private static final int MAX_PAGE_SIZE = 500;

	// Per-request Health Check snapshot (L2). Computed lazily once per executor
	// instance so a single routes query invokes the checks at most once.
	private boolean healthComputed;
	private Map<String, String> healthByRoute = new HashMap<>();
	private Map<String, String> healthByUri = new HashMap<>();

	public EipRouteExecutor(Session session) {
		this.session = session;
	}

	// =========================================================================
	// routes (Relay cursor connection)
	// =========================================================================

	public Map<String, Object> executeRoutesQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> vars = request.getVariables();
		Integer first = optBoxedInt(vars, "first");
		Integer last = optBoxedInt(vars, "last");
		String after = optString(vars, "after");
		String before = optString(vars, "before");
		String statusFilter = optString(vars, "status");
		String groupFilter = optString(vars, "group");
		String search = optString(vars, "search");

		boolean backward = (last != null) || (before != null && first == null);
		int pageSize = clampPageSize(backward ? last : first);

		// Collect all routes (id, sourceFile/group). They are sorted alphabetically
		// by routeId so the cursor encoding is stable.
		List<RouteRef> all = collectRoutes();
		all.sort(Comparator.comparing(r -> r.routeId));

		// Apply non-cursor filters.
		List<RouteRef> filtered = new ArrayList<>();
		String search1 = search == null ? null : search.toLowerCase();
		WorkspaceIntegrationEngineProvider provider =
				CmsService.getWorkspaceIntegrationEngineProvider(session.getWorkspace().getName());
		CamelContext camelContext = provider == null ? null : provider.getCamelContext();
		for (RouteRef ref : all) {
			if (groupFilter != null && !groupFilter.isEmpty() && !groupFilter.equals(ref.group)) {
				continue;
			}
			if (search1 != null && !search1.isEmpty()
					&& !ref.routeId.toLowerCase().contains(search1)) {
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

		// Apply cursor window.
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

		// Slice page. For backward pagination we take the last `pageSize` entries.
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

		ManagedCamelContext managedContext = camelContext == null ? null
				: camelContext.getCamelContextExtension().getContextPlugin(ManagedCamelContext.class);

		List<Map<String, Object>> edges = new ArrayList<>(page.size());
		for (RouteRef ref : page) {
			Map<String, Object> node = buildRouteNode(ref, camelContext, managedContext, false);
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

		Map<String, Object> data = new HashMap<>();
		data.put("routes", connection);
		return data;
	}

	// =========================================================================
	// route (single by id)
	// =========================================================================

	public Map<String, Object> executeRouteQuery(GraphQLRequest request) throws Exception {
		Map<String, Object> vars = request.getVariables();
		String id = optString(vars, "id");

		WorkspaceIntegrationEngineProvider provider =
				CmsService.getWorkspaceIntegrationEngineProvider(session.getWorkspace().getName());
		Map<String, Object> data = new HashMap<>();
		if (id == null || id.isEmpty() || provider == null) {
			data.put("route", null);
			return data;
		}

		CamelContext camelContext = provider.getCamelContext();
		ManagedCamelContext managedContext = camelContext == null ? null
				: camelContext.getCamelContextExtension().getContextPlugin(ManagedCamelContext.class);

		RouteRef ref = findRoute(provider, id);
		if (ref == null) {
			data.put("route", null);
			return data;
		}

		data.put("route", buildRouteNode(ref, camelContext, managedContext, true));
		return data;
	}

	// =========================================================================
	// Route control mutations — Input Object Pattern
	// =========================================================================

	public Map<String, Object> executeStartRoute(GraphQLRequest request) throws Exception {
		Map<String, Object> input = requireInput(request);
		String id = requireString(input, "id");
		WorkspaceIntegrationEngineProvider provider = requireProvider();
		provider.getCamelContext().getRouteController().startRoute(id);
		return Map.of("startRoute", buildRouteNode(requireRouteRef(provider, id),
				provider.getCamelContext(), managedContext(provider), true));
	}

	public Map<String, Object> executeStopRoute(GraphQLRequest request) throws Exception {
		Map<String, Object> input = requireInput(request);
		String id = requireString(input, "id");
		Integer timeout = optBoxedInt(input, "timeout");
		WorkspaceIntegrationEngineProvider provider = requireProvider();
		if (timeout != null) {
			provider.getCamelContext().getRouteController().stopRoute(id,
					timeout.longValue(), TimeUnit.SECONDS);
		} else {
			provider.getCamelContext().getRouteController().stopRoute(id);
		}
		return Map.of("stopRoute", buildRouteNode(requireRouteRef(provider, id),
				provider.getCamelContext(), managedContext(provider), true));
	}

	public Map<String, Object> executeSuspendRoute(GraphQLRequest request) throws Exception {
		Map<String, Object> input = requireInput(request);
		String id = requireString(input, "id");
		Integer timeout = optBoxedInt(input, "timeout");
		WorkspaceIntegrationEngineProvider provider = requireProvider();
		if (timeout != null) {
			provider.getCamelContext().getRouteController().suspendRoute(id,
					timeout.longValue(), TimeUnit.SECONDS);
		} else {
			provider.getCamelContext().getRouteController().suspendRoute(id);
		}
		return Map.of("suspendRoute", buildRouteNode(requireRouteRef(provider, id),
				provider.getCamelContext(), managedContext(provider), true));
	}

	public Map<String, Object> executeResumeRoute(GraphQLRequest request) throws Exception {
		Map<String, Object> input = requireInput(request);
		String id = requireString(input, "id");
		WorkspaceIntegrationEngineProvider provider = requireProvider();
		provider.getCamelContext().getRouteController().resumeRoute(id);
		return Map.of("resumeRoute", buildRouteNode(requireRouteRef(provider, id),
				provider.getCamelContext(), managedContext(provider), true));
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private List<RouteRef> collectRoutes() {
		WorkspaceIntegrationEngineProvider provider =
				CmsService.getWorkspaceIntegrationEngineProvider(session.getWorkspace().getName());
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

	private RouteRef findRoute(WorkspaceIntegrationEngineProvider provider, String id) {
		for (Map.Entry<String, List<String>> e : provider.getDeployments().entrySet()) {
			if (e.getValue().contains(id)) {
				return new RouteRef(id, e.getKey());
			}
		}
		return null;
	}

	private RouteRef requireRouteRef(WorkspaceIntegrationEngineProvider provider, String id) {
		RouteRef ref = findRoute(provider, id);
		if (ref == null) {
			throw new IllegalArgumentException("Unknown route: " + id);
		}
		return ref;
	}

	private WorkspaceIntegrationEngineProvider requireProvider() {
		WorkspaceIntegrationEngineProvider provider =
				CmsService.getWorkspaceIntegrationEngineProvider(session.getWorkspace().getName());
		if (provider == null) {
			throw new IllegalStateException("No Camel engine deployed for workspace "
					+ session.getWorkspace().getName());
		}
		return provider;
	}

	private ManagedCamelContext managedContext(WorkspaceIntegrationEngineProvider provider) {
		CamelContext ctx = provider.getCamelContext();
		if (ctx == null) {
			return null;
		}
		return ctx.getCamelContextExtension().getContextPlugin(ManagedCamelContext.class);
	}

	private Map<String, Object> buildRouteNode(RouteRef ref, CamelContext context,
			ManagedCamelContext managedContext, boolean includeDefinition) {
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
		ensureHealth(context);
		node.put("health", healthByRoute.getOrDefault(ref.routeId, "UNKNOWN"));
		node.put("endpoints", buildEndpoints(context, ref.routeId));
		// Dumping the model to XML/YAML is comparatively expensive, so it is only
		// done for single-route reads (Route.definition), never for the list — the
		// routes connection deliberately doesn't select it.
		if (includeDefinition) {
			node.put("definition", buildDefinition(context, ref.routeId));
		}
		return node;
	}

	// =========================================================================
	// Route.definition — the route's structured model, sourced from the live
	// engine.
	//
	// The runtime CamelContext is the single source of truth: we look up the
	// in-memory RouteDefinition for the route id and dump it back to its DSL
	// representations via the engine's own dumpers. Both Camel DSL interchange
	// formats are exposed so consumers can pick whichever they need —
	//   - xml  : Camel XML DSL (used by the EIP modeler / read-only canvas to
	//            render a faithful, auto-laid-out diagram)
	//   - yaml : Camel YAML DSL (human-friendly export)
	// They always reflect what is actually deployed, not any on-disk source
	// that may have drifted since deployment.
	// =========================================================================
	private Map<String, Object> buildDefinition(CamelContext context, String routeId) {
		if (context == null || !(context instanceof ModelCamelContext)) {
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

	// Dump a single route's model to XML (asXml=true) or YAML (asXml=false)
	// using the engine's registered dumper. Returns null on failure so a single
	// unrenderable route never fails the whole query.
	private String dumpModel(CamelContext context, RouteDefinition def, boolean asXml) {
		try {
			if (asXml) {
				return PluginHelper.getModelToXMLDumper(context).dumpModelAsXml(context, def);
			}
			return PluginHelper.getModelToYAMLDumper(context).dumpModelAsYaml(context, def);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn(
					"Failed to dump route model as " + (asXml ? "XML" : "YAML") + ": " + def.getRouteId(), ex);
			return null;
		}
	}

	// =========================================================================
	// Endpoint connectivity (Route.endpoints)
	//
	// Surfaces the endpoints a route actually talks to — used by the Dashboard's
	// "External connections" panel. This is L1: which endpoints are wired, their
	// component, whether they are remote (an external system) and their
	// lifecycle state. True reachability (UP/DOWN via Camel Health Checks) is a
	// deliberate follow-up and is NOT computed here.
	// =========================================================================

	private List<Map<String, Object>> buildEndpoints(CamelContext context, String routeId) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (context == null) {
			return out;
		}

		// Collect the endpoints this route uses (consumers + producers). Camel's
		// runtime registry tracks them per route but only learns an endpoint once
		// it has been used, so also fold in the route's input (consumer) endpoint
		// — that way a freshly-started, still-idle route reports at least its
		// source instead of looking like it talks to nothing.
		LinkedHashSet<String> uris = new LinkedHashSet<>();
		try {
			RuntimeEndpointRegistry registry = context.getRuntimeEndpointRegistry();
			if (registry != null && registry.isEnabled()) {
				// getEndpointsPerRoute(routeId, includeInputs) -> List<String> of URIs.
				for (String uri : registry.getEndpointsPerRoute(routeId, true)) {
					if (uri != null) {
						uris.add(uri);
					}
				}
			}
		} catch (Exception ignore) {
			// Registry unavailable (management off) — fall back to the input below.
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
				// Unresolvable URI — fall back to scheme-based heuristics below.
			}
			String scheme = schemeOf(uri);
			String sanitized = sanitizeUri(uri);
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("uri", sanitized);
			node.put("component", scheme);
			// Prefer the component's own declaration; only guess from the scheme
			// when the endpoint object cannot be resolved.
			node.put("remote", endpoint != null ? endpoint.isRemote() : isRemoteScheme(scheme));
			node.put("state", endpointState(endpoint));
			node.put("singleton", endpoint == null || endpoint.isSingleton());
			// L2 reachability: an endpoint-specific health result if the check
			// carried its URI, otherwise the owning route's health.
			String health = healthByUri.get(sanitized);
			if (health == null) {
				health = healthByRoute.get(routeId);
			}
			node.put("health", health == null ? "UNKNOWN" : health);
			out.add(node);
		}
		return out;
	}

	private static String endpointState(Endpoint endpoint) {
		if (endpoint instanceof StatefulService) {
			return serviceStatusName(((StatefulService) endpoint).getStatus());
		}
		// Endpoints without their own lifecycle are effectively available once
		// the route referencing them is started.
		return endpoint == null ? "Unknown" : "Started";
	}

	private static String schemeOf(String uri) {
		if (uri == null) {
			return "";
		}
		int i = uri.indexOf(':');
		return i > 0 ? uri.substring(0, i) : uri;
	}

	// In-VM / internal component schemes. Used only when Endpoint#isRemote()
	// cannot be consulted (the endpoint object is not resolvable); everything
	// not listed here is then treated as a remote integration.
	private static final Set<String> LOCAL_SCHEMES = Set.of(
			"direct", "direct-vm", "seda", "vm", "log", "mock", "stub", "dataset",
			"timer", "scheduler", "quartz", "bean", "class", "language", "ref",
			"controlbus", "browse", "dataformat", "validator", "stream",
			// CMS-internal components (see WorkspaceCamelContext).
			"cms", "bpm", "eventadmin", "transform");

	private static boolean isRemoteScheme(String scheme) {
		if (scheme == null || scheme.isEmpty()) {
			return false;
		}
		return !LOCAL_SCHEMES.contains(scheme.toLowerCase());
	}

	// Strip credentials so the dashboard never surfaces secrets embedded in an
	// endpoint URI: drop any "user:pass@" userinfo and mask obvious secret query
	// parameters.
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

	// =========================================================================
	// Health (L2 reachability) — UP / DOWN / UNKNOWN per route and endpoint
	// =========================================================================

	// Invoke the readiness health checks once per request and index the results
	// by route id and (when the check carries one) by endpoint URI. Route /
	// consumer / producer checks come from camel-health; components that ship
	// their own connectivity checks fold in here too. Entirely best-effort: any
	// failure (health disabled, no registry) leaves the maps empty so callers
	// fall back to "UNKNOWN".
	private void ensureHealth(CamelContext context) {
		if (healthComputed) {
			return;
		}
		healthComputed = true;
		if (context == null) {
			return;
		}
		try {
			// uri -> routeId, so a check that only reports endpoint.uri can still
			// be attributed to its route.
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
					mergeHealth(healthByUri, sanitizedUri, state);
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
					mergeHealth(healthByRoute, routeId, state);
				}
			}
		} catch (Throwable t) {
			// Health unavailable — leave maps empty (callers default to UNKNOWN).
		}
	}

	// DOWN wins over UP wins over UNKNOWN, so an endpoint/route reachable by one
	// check but failing another is reported as the more severe state.
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

	private static String encodeCursor(String routeId) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(routeId.getBytes(StandardCharsets.UTF_8));
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

	@SuppressWarnings("unchecked")
	private static Map<String, Object> requireInput(GraphQLRequest request) {
		Map<String, Object> vars = request.getVariables();
		if (vars == null) {
			throw new IllegalArgumentException("Missing input");
		}
		Object input = vars.get("input");
		if (!(input instanceof Map)) {
			throw new IllegalArgumentException("Missing input");
		}
		return (Map<String, Object>) input;
	}

	private static String optString(Map<String, Object> vars, String key) {
		if (vars == null) {
			return null;
		}
		Object v = vars.get(key);
		return v == null ? null : v.toString();
	}

	private static String requireString(Map<String, Object> vars, String key) {
		String v = optString(vars, key);
		if (v == null || v.isEmpty()) {
			throw new IllegalArgumentException("Missing required variable: " + key);
		}
		return v;
	}

	private static Integer optBoxedInt(Map<String, Object> vars, String key) {
		if (vars == null) {
			return null;
		}
		Object v = vars.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof Number) {
			return ((Number) v).intValue();
		}
		try {
			return Integer.parseInt(v.toString());
		} catch (NumberFormatException ex) {
			return null;
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
}
