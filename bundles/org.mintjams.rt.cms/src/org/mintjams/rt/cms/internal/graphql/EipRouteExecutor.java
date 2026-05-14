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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.jcr.Session;

import org.apache.camel.CamelContext;
import org.apache.camel.ServiceStatus;
import org.apache.camel.api.management.ManagedCamelContext;
import org.apache.camel.api.management.mbean.ManagedRouteMBean;
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
			Map<String, Object> node = buildRouteNode(ref, camelContext, managedContext);
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

		data.put("route", buildRouteNode(ref, camelContext, managedContext));
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
				provider.getCamelContext(), managedContext(provider)));
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
				provider.getCamelContext(), managedContext(provider)));
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
				provider.getCamelContext(), managedContext(provider)));
	}

	public Map<String, Object> executeResumeRoute(GraphQLRequest request) throws Exception {
		Map<String, Object> input = requireInput(request);
		String id = requireString(input, "id");
		WorkspaceIntegrationEngineProvider provider = requireProvider();
		provider.getCamelContext().getRouteController().resumeRoute(id);
		return Map.of("resumeRoute", buildRouteNode(requireRouteRef(provider, id),
				provider.getCamelContext(), managedContext(provider)));
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
			ManagedCamelContext managedContext) {
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
		return node;
	}

	private static String serviceStatusName(ServiceStatus status) {
		return status == null ? "Unknown" : status.name();
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
