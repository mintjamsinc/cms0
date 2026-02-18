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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.apache.camel.CamelContext;
import org.apache.camel.ServiceStatus;
import org.apache.camel.api.management.ManagedCamelContext;
import org.apache.camel.api.management.mbean.ManagedRouteMBean;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.eip.WorkspaceIntegrationEngineProvider;

/**
 * Executor for camelContext GraphQL queries.
 * Returns status information about all deployed Apache Camel contexts
 * in the current workspace.
 */
public class CamelQueryExecutor {

	private final Session session;

	public CamelQueryExecutor(Session session) {
		this.session = session;
	}

	/**
	 * Execute camelContext query.
	 * Returns a list of all deployed CamelContext instances with their route status.
	 *
	 * Example query:
	 * <pre>
	 * query CheckCamelStatus {
	 *   camelContext {
	 *     name
	 *     state
	 *     routes {
	 *       id
	 *       status
	 *       exchangesTotal
	 *     }
	 *   }
	 * }
	 * </pre>
	 */
	public Map<String, Object> executeCamelContextQuery(GraphQLRequest request) throws Exception {
		String workspaceName = session.getWorkspace().getName();

		WorkspaceIntegrationEngineProvider provider =
				CmsService.getWorkspaceIntegrationEngineProvider(workspaceName);

		List<Map<String, Object>> contextList = new ArrayList<>();

		if (provider != null) {
			CamelContext camelContext = provider.getCamelContext();
			Map<String, List<String>> deployments = provider.getDeployments();
			for (Map.Entry<String, List<String>> entry : deployments.entrySet()) {
				String sourceFile = entry.getKey();
				List<String> routeIds = entry.getValue();
				contextList.add(buildContextMap(sourceFile, camelContext, routeIds));
			}
		}

		Map<String, Object> data = new HashMap<>();
		data.put("camelContext", contextList);
		return data;
	}

	private Map<String, Object> buildContextMap(String sourceFile, CamelContext context, List<String> routeIds) {
		Map<String, Object> contextMap = new HashMap<>();
		contextMap.put("name", context.getName());
		contextMap.put("state", getStatusName(context.getStatus()));
		contextMap.put("sourceFile", sourceFile);
		contextMap.put("routes", buildRouteList(context, routeIds));
		return contextMap;
	}

	private List<Map<String, Object>> buildRouteList(CamelContext context, List<String> routeIds) {
		List<Map<String, Object>> routeList = new ArrayList<>();

		ManagedCamelContext managedContext = context.getExtension(ManagedCamelContext.class);

		for (String routeId : routeIds) {
			Map<String, Object> routeMap = new HashMap<>();
			routeMap.put("id", routeId);

			ServiceStatus status = context.getRouteController().getRouteStatus(routeId);
			routeMap.put("status", status != null ? getStatusName(status) : "Unknown");

			routeMap.put("exchangesTotal", getExchangesTotal(managedContext, routeId));

			routeList.add(routeMap);
		}

		return routeList;
	}

	private long getExchangesTotal(ManagedCamelContext managedContext, String routeId) {
		if (managedContext == null) {
			return 0;
		}
		try {
			ManagedRouteMBean routeMBean =
					managedContext.getManagedRoute(routeId, ManagedRouteMBean.class);
			if (routeMBean != null) {
				return routeMBean.getExchangesTotal();
			}
		} catch (Exception ignore) {
			// JMX may not be available
		}
		return 0;
	}

	private String getStatusName(ServiceStatus status) {
		if (status == null) {
			return "Unknown";
		}
		return status.name();
	}
}
