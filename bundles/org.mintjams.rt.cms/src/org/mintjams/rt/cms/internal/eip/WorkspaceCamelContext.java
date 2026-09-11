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

package org.mintjams.rt.cms.internal.eip;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.health.HealthCheckRegistry;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.health.DefaultHealthCheckRegistry;
import org.apache.camel.spi.BeanRepository;
import org.apache.camel.support.DefaultRegistry;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.WorkspaceDelegatingClassLoader;

public class WorkspaceCamelContext extends DefaultCamelContext {

	@SuppressWarnings("resource")
	public WorkspaceCamelContext(WorkspaceIntegrationEngineProviderConfiguration config) {
		setApplicationContextClassLoader(new WorkspaceDelegatingClassLoader(config.getWorkspaceName()));

		addComponent(EventAdminComponent.COMPONENT_NAME, new EventAdminComponent(config.getWorkspaceName()));
		addComponent(BpmComponent.COMPONENT_NAME, new BpmComponent(config.getWorkspaceName()));
		addComponent(CmsComponent.COMPONENT_NAME, new CmsComponent(config.getWorkspaceName()));

		// The predicate language for <when> and <filter>. Simple has no parenthesis
		// grouping and cannot dereference a header whose name contains a colon, so a
		// condition like (a || b) && (c || d) over commerce:* headers becomes a flag
		// header and two nested <choice> elements. Registered under both names Camel
		// looks a language up by: which one it uses depends on how the route was
		// assembled, and registering one leaves the other failing at startup.
		// aggregationStrategy="#commerceTally" resolves to a script under
		// /etc/eip/aggregators, so how a <split> counts what it did is deployed and
		// edited the same way the route is - by uploading a file. The platform
		// supplies the adapter and no aggregation of its own; the alternative was a
		// platform release every time an application wanted another column in a
		// summary line. See ScriptAggregatorRepository.
		//
		// Set before anything binds into the registry: replacing it afterwards would
		// discard what was already there.
//		getCamelContextExtension().setRegistry(
//				new DefaultRegistry(new ScriptAggregatorRepository(config.getWorkspaceName())));
		getCamelContextExtension().setRegistry(new DefaultRegistry(new BeanRepositoryImpl(config.getWorkspaceName())));

		JexlLanguage jexl = new JexlLanguage();
		getCamelContextExtension().getRegistry().bind(JexlLanguage.NAME, jexl);
		getCamelContextExtension().getRegistry().bind(JexlLanguage.REGISTRY_ALIAS, jexl);
		GroovyLanguage groovy = new GroovyLanguage(config.getWorkspaceName());
		getCamelContextExtension().getRegistry().bind(GroovyLanguage.NAME, groovy);
		getCamelContextExtension().getRegistry().bind(GroovyLanguage.REGISTRY_ALIAS, groovy);

		setMessageHistory(true);
		getManagementStrategy().addEventNotifier(
				new ExchangeHistoryEventNotifier(config.getWorkspaceName()));

		// Engine-wide bridge from Camel lifecycle events to the OSGi EventAdmin
		// service, mirroring the BPM (Camunda) EventAdmin bridge. Cross-cutting:
		// every route (including ones added or reloaded later) is covered without
		// any route authoring. See documents/eip-eventadmin.md.
		getManagementStrategy().addEventNotifier(
				new EventAdminEventNotifier(config.getWorkspaceName()));

		enableHealthChecks();
	}

	// Bootstrap Camel's Health Check registry so the Dashboard can report true
	// readiness (UP/DOWN) for each route and the external systems it talks to.
	// loadHealthChecks() pulls in the route / consumer / producer health-check
	// repositories shipped with camel-health, and components that provide their
	// own connectivity checks register into the same registry. Purely
	// observability — wrapped so a wiring problem can never stop the integration
	// engine from starting.
	private void enableHealthChecks() {
		try {
			HealthCheckRegistry registry = HealthCheckRegistry.get(this);
			if (registry == null) {
				registry = new DefaultHealthCheckRegistry(this);
				getCamelContextExtension().addContextPlugin(HealthCheckRegistry.class, registry);
			}
			registry.setEnabled(true);
			registry.loadHealthChecks();
		} catch (Throwable t) {
			// Best-effort: health checks are optional observability.
		}
	}

	private class BeanRepositoryImpl implements BeanRepository {
		private final String fWorkspaceName;

		private BeanRepositoryImpl(String workspaceName) {
			fWorkspaceName = workspaceName;
		}

		@Override
		public <T> T lookupByNameAndType(String name, Class<T> type) {
			Object bean = lookupByName(name);
			if (bean == null) {
				return null;
			}

			if (type == null || type == Object.class) {
				return type.cast(bean);
			}

			if (type.isInstance(bean)) {
				return type.cast(bean);
			}

			return null;
		}

		@Override
		public Object lookupByName(String name) {
			AggregationStrategy strategy = CmsService.getWorkspaceIntegrationEngineProvider(fWorkspaceName).getAggregationStrategy(name);
			if (strategy != null) {
				return strategy;
			}
			return null;
		}

		@Override
		public <T> Map<String, T> findByTypeWithName(Class<T> type) {
			return Collections.emptyMap();
		}

		@Override
		public <T> Set<T> findByType(Class<T> type) {
			return Collections.emptySet();
		}
	}

}
