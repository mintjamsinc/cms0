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

import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.Route;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteConfigurationDefinition;
import org.apache.camel.spi.RoutesLoader;
import org.apache.camel.support.PluginHelper;
import org.mintjams.jcr.nodetype.NodeType;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.script.ScriptingContext;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.osgi.Registration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

public class WorkspaceIntegrationEngineProvider implements Closeable {

	private final WorkspaceIntegrationEngineProviderConfiguration fConfig;
	private final Closer fCloser = Closer.create();
	private final Map<String, List<String>> fDeployments = new HashMap<>();
	private final Map<String, List<String>> fRouteConfigDeployments = new HashMap<>();
	private final Map<String, AggregationStrategyImpl> fAggregationStrategies = new HashMap<>();
	private WorkspaceCamelContext fCamelContext;
	private ProducerTemplate fProducerTemplate;

	public WorkspaceIntegrationEngineProvider(String workspaceName) {
		fConfig = new WorkspaceIntegrationEngineProviderConfiguration(workspaceName);
	}

	public synchronized void open() throws IOException, RepositoryException {
		fConfig.load();

		if (!fConfig.isEnabled()) {
			CmsService.getLogger(getClass())
					.info("The integration engine is disabled for the workspace: " + getWorkspaceName());
			return;
		}

		fCamelContext = new WorkspaceCamelContext(fConfig);
		fCloser.register(new Closeable() {
			@Override
			public void close() throws IOException {
				try {
					fCamelContext.close();
				} catch (Throwable ignore) {}
				fCamelContext = null;
			}
		});
		fCamelContext.start();

		fProducerTemplate = fCamelContext.createProducerTemplate();
		fCloser.register(new Closeable() {
			@Override
			public void close() throws IOException {
				try {
					fProducerTemplate.close();
				} catch (Throwable ignore) {}
				fProducerTemplate = null;
			}
		});

		AggregationStrategyDeployer aggregationStrategyDeployer = fCloser.register(new AggregationStrategyDeployer());
		aggregationStrategyDeployer.open();
		RouteDeployer routeDeployer = fCloser.register(new RouteDeployer());
		routeDeployer.open();
	}

	@Override
	public synchronized void close() throws IOException {
		fCloser.close();
	}

	public String getWorkspaceName() {
		return fConfig.getWorkspaceName();
	}

	/**
	 * Returns whether the integration engine is switched on for this
	 * workspace ({@code eip.yml#enabled}). Together with
	 * {@link #isAvailable()} this distinguishes a deliberately disabled
	 * engine (a normal configuration choice) from one that is enabled but
	 * failed to start (an operational problem).
	 */
	public boolean isEnabled() {
		return fConfig.isEnabled();
	}

	/**
	 * Returns whether the integration engine is running for this workspace.
	 * The engine may be absent because it is disabled
	 * ({@code eip.yml#enabled}) or because it failed to start.
	 */
	public boolean isAvailable() {
		return fCamelContext != null;
	}

	public CamelContext getCamelContext() {
		if (fCamelContext == null) {
			throw new IllegalStateException("The integration engine is not available for the workspace: " + getWorkspaceName());
		}
		return fCamelContext;
	}

	public ProducerTemplate getProducerTemplate() {
		if (fProducerTemplate == null) {
			throw new IllegalStateException("The integration engine is not available for the workspace: " + getWorkspaceName());
		}
		return fProducerTemplate;
	}

	public Map<String, List<String>> getDeployments() {
		synchronized (fDeployments) {
			return new HashMap<>(fDeployments);
		}
	}

	public AggregationStrategy getAggregationStrategy(String beanName) {
		if (Strings.isBlank(beanName)) {
			throw new IllegalArgumentException("beanName is blank.");
		}

		synchronized (fAggregationStrategies) {
			for (AggregationStrategyImpl strategy : fAggregationStrategies.values()) {
				if (strategy.matches(beanName)) {
					return strategy;
				}
			}
			return null;
		}
	}

	private void deployRoute(Node item) throws IOException, RepositoryException {
		if (item.getPrimaryNodeType().getName().equals(NodeType.NT_FILE_NAME)) {
			synchronized (fDeployments) {
				String itemPath = item.getPath();
				try {
					ModelCamelContext modelContext = (ModelCamelContext) fCamelContext;

					// Stop and remove previously deployed routes for this path.
					// This must happen before snapshotting, otherwise re-added routes
					// (XML DSL keeps the same route id) get filtered out of the diff.
					List<String> previousRouteIds = fDeployments.get(itemPath);
					if (previousRouteIds != null) {
						for (String routeId : previousRouteIds) {
							try {
								removeDeployedRoute(routeId);
							} catch (Throwable ex) {
								CmsService.getLogger(getClass()).warn("Failed to remove route: " + routeId, ex);
							}
						}
					}

					// Remove previously deployed route configurations for this path
					removeRouteConfigurations(fRouteConfigDeployments.get(itemPath));

					// Snapshot of current routes and route configuration IDs after
					// removal of the previous deployment and before loading the new one.
					// The route instances are kept so that a route another file deployed,
					// and this load replaced under the same id, can be told apart.
					Map<String, Route> routesBefore = fCamelContext.getRoutes().stream()
							.collect(Collectors.toMap(Route::getRouteId, route -> route));
					Set<String> routeIdsBefore = routesBefore.keySet();
					Set<String> configIdsBefore = modelContext.getRouteConfigurationDefinitions().stream()
							.map(RouteConfigurationDefinition::getId)
							.filter(id -> id != null)
							.collect(Collectors.toSet());

					// Load and add new routes (and route configurations) via RoutesLoader
					RoutesLoader loader = PluginHelper.getRoutesLoader(fCamelContext);
					try {
						loader.loadRoutes(new CamelResource(item));
					} catch (Throwable loadFailure) {
						// A failed load can leave part of the item registered - the XML
						// DSL, for example, adds the route configuration before the route
						// itself, so a route that fails to build (e.g. a class that could
						// not be loaded) leaves an orphan configuration behind. It was
						// never recorded in fRouteConfigDeployments (that happens only on
						// success below), so a later redeploy could neither remove nor
						// re-add it and would fail forever with "... already exists".
						// Roll back everything this load added - routes and route
						// configurations absent from the pre-load snapshot - so the item
						// can be retried from a clean state.
						rollbackRoutes(routeIdsBefore);
						rollbackRouteConfigurations(configIdsBefore);
						throw loadFailure;
					}

					// Track newly added route IDs (post-snapshot minus pre-snapshot)
					List<String> newRouteIds = fCamelContext.getRoutes().stream()
							.map(route -> route.getRouteId())
							.filter(id -> !routeIdsBefore.contains(id))
							.collect(Collectors.toList());
					fDeployments.put(itemPath, newRouteIds);

					// Track newly added route configuration IDs
					List<String> newConfigIds = modelContext.getRouteConfigurationDefinitions().stream()
							.map(RouteConfigurationDefinition::getId)
							.filter(id -> id != null && !configIdsBefore.contains(id))
							.collect(Collectors.toList());
					fRouteConfigDeployments.put(itemPath, newConfigIds);

					// Camel replaces a route whose id is already taken without a word, so a
					// file declaring another file's route id takes that route over unseen.
					List<String> displaced = new ArrayList<>();
					for (Map.Entry<String, Route> entry : routesBefore.entrySet()) {
						if (fCamelContext.getRoute(entry.getKey()) != entry.getValue()) {
							displaced.add(entry.getKey() + " (deployed from " + findDeployedPath(entry.getKey(), itemPath) + ")");
						}
					}
					if (!displaced.isEmpty()) {
						CmsService.getLogger(getClass()).warn("Deploying the route file " + itemPath
								+ " replaced or removed routes deployed from other files: " + displaced);
					}

					if (newRouteIds.isEmpty() && newConfigIds.isEmpty()) {
						// Camel loads a document it cannot read as XML routes as nothing at all,
						// logging only "Invalid XML document", so an empty result is the one
						// trace such a file leaves here.
						CmsService.getLogger(getClass()).warn("Deployed the route file with no routes: " + itemPath
								+ ". If it is meant to define routes, check that it is a valid Camel XML document.");
					} else {
						CmsService.getLogger(getClass()).info(((previousRouteIds != null) ? "Redeployed" : "Deployed")
								+ " the route file: " + itemPath + " routes=" + newRouteIds
								+ (newConfigIds.isEmpty() ? "" : " routeConfigurations=" + newConfigIds));
					}
				} catch (Throwable cause) {
					throw Cause.create(cause).wrap(IOException.class);
				}

				CmsService.postEvent(CamelContext.class.getName().replace(".", "/") + "/DEPLOYED", AdaptableMap.<String, Object>newBuilder()
						.put("path", itemPath)
						.put("type", item.getPrimaryNodeType().getName())
						.put("workspace", getWorkspaceName())
						.build());
			}
			return;
		}

		if (item.getPrimaryNodeType().getName().equals(NodeType.NT_FOLDER_NAME)) {
			NodeIterator i = item.getNodes();
			while (i.hasNext()) {
				Node child = i.nextNode();
				String childPath = child.getPath();
				// One file that fails to load must not keep the files after it from
				// being deployed, and only the failing file is named in the log.
				try {
					deployRoute(child);
				} catch (Throwable ex) {
					CmsService.getLogger(getClass()).error("Failed to deploy the route file: " + childPath, ex);
				}
			}
			return;
		}
	}

	private void rollbackRoutes(Set<String> routeIdsBefore) {
		try {
			List<String> addedRouteIds = fCamelContext.getRoutes().stream()
					.map(route -> route.getRouteId())
					.filter(id -> !routeIdsBefore.contains(id))
					.collect(Collectors.toList());
			for (String routeId : addedRouteIds) {
				try {
					fCamelContext.getRouteController().stopRoute(routeId);
					fCamelContext.removeRoute(routeId);
				} catch (Throwable ignore) {}
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Failed to roll back partially deployed routes.", ex);
		}
	}

	private void rollbackRouteConfigurations(Set<String> configIdsBefore) {
		try {
			ModelCamelContext modelContext = (ModelCamelContext) fCamelContext;
			for (RouteConfigurationDefinition def : new ArrayList<>(modelContext.getRouteConfigurationDefinitions())) {
				String id = def.getId();
				if (id != null && !configIdsBefore.contains(id)) {
					try {
						modelContext.removeRouteConfiguration(def);
					} catch (Throwable ignore) {}
				}
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Failed to roll back partially deployed route configurations.", ex);
		}
	}

	private void removeRouteConfigurations(List<String> configIds) {
		if (configIds == null || configIds.isEmpty()) {
			return;
		}
		try {
			ModelCamelContext modelContext = (ModelCamelContext) fCamelContext;
			for (String configId : configIds) {
				RouteConfigurationDefinition def = modelContext.getRouteConfigurationDefinition(configId);
				if (def != null) {
					modelContext.removeRouteConfiguration(def);
				}
			}
		} catch (Exception ex) {
			CmsService.getLogger(getClass()).warn("Failed to remove route configurations: " + configIds, ex);
		}
	}

	/**
	 * Stops and removes a route deployed from a route file, and says so when that
	 * does not happen: Camel reports a route it could not remove only through the
	 * return value of {@code removeRoute}.
	 */
	private void removeDeployedRoute(String routeId) throws Exception {
		if (fCamelContext.getRoute(routeId) == null) {
			CmsService.getLogger(getClass()).warn("The route to remove does not exist in the engine: " + routeId);
			return;
		}

		fCamelContext.getRouteController().stopRoute(routeId);
		if (!fCamelContext.removeRoute(routeId)) {
			CmsService.getLogger(getClass()).warn("The route did not stop and was not removed: " + routeId);
		}
	}

	private String findDeployedPath(String routeId, String excludedPath) {
		for (Map.Entry<String, List<String>> entry : fDeployments.entrySet()) {
			if (!entry.getKey().equals(excludedPath) && entry.getValue().contains(routeId)) {
				return entry.getKey();
			}
		}
		return "an unknown file";
	}

	private void undeployRoute(String itemPath, Event event) throws IOException, RepositoryException {
		String nodeType = event.getProperty("type").toString();

		if (nodeType.equals(NodeType.NT_FILE_NAME)) {
			synchronized (fDeployments) {
				List<String> routeIds = fDeployments.remove(itemPath);
				if (routeIds != null) {
					for (String routeId : routeIds) {
						try {
							removeDeployedRoute(routeId);
						} catch (Throwable ex) {
							CmsService.getLogger(getClass()).error("An error occurred while removing route: " + routeId, ex);
						}
					}
				}

				// Remove route configurations deployed from this file
				removeRouteConfigurations(fRouteConfigDeployments.remove(itemPath));

				if (routeIds != null) {
					CmsService.getLogger(getClass()).info("Undeployed the route file: " + itemPath + " routes=" + routeIds);
					CmsService.postEvent(CamelContext.class.getName().replace(".", "/") + "/UNDEPLOYED", AdaptableMap.<String, Object>newBuilder()
							.put("path", itemPath)
							.put("type", nodeType)
							.put("workspace", getWorkspaceName())
							.build());
				}
			}
			return;
		}

		if (nodeType.equals(NodeType.NT_FOLDER_NAME)) {
			synchronized (fDeployments) {
				for (String path : fDeployments.keySet().toArray(String[]::new)) {
					if (path.startsWith(itemPath + "/")) {
						List<String> routeIds = fDeployments.remove(path);
						if (routeIds != null) {
							for (String routeId : routeIds) {
								try {
									removeDeployedRoute(routeId);
								} catch (Throwable ex) {
									CmsService.getLogger(getClass()).error("An error occurred while removing route: " + routeId, ex);
								}
							}
						}

						// Remove route configurations deployed from this file
						removeRouteConfigurations(fRouteConfigDeployments.remove(path));

						if (routeIds != null) {
							CmsService.getLogger(getClass()).info("Undeployed the route file: " + path + " routes=" + routeIds);
							CmsService.postEvent(CamelContext.class.getName().replace(".", "/") + "/UNDEPLOYED", AdaptableMap.<String, Object>newBuilder()
									.put("path", path)
									.put("type", NodeType.NT_FILE_NAME)
									.put("workspace", getWorkspaceName())
									.build());
						}
					}
				}
			}
			return;
		}
	}

	private void deployAggregationStrategy(Node item, List<String> rootPaths) throws IOException, RepositoryException {
		if (item.getPrimaryNodeType().getName().equals(NodeType.NT_FILE_NAME)) {
			synchronized (fAggregationStrategies) {
				String itemPath = item.getPath();
				try {
					AggregationStrategyImpl strategy = new AggregationStrategyImpl(item, rootPaths);

					fAggregationStrategies.put(itemPath, strategy);
				} catch (Throwable cause) {
					throw Cause.create(cause).wrap(IOException.class);
				}

				CmsService.postEvent(CamelContext.class.getName().replace(".", "/") + "/DEPLOYED", AdaptableMap.<String, Object>newBuilder()
						.put("path", itemPath)
						.put("type", item.getPrimaryNodeType().getName())
						.put("workspace", getWorkspaceName())
						.build());
			}
			return;
		}

		if (item.getPrimaryNodeType().getName().equals(NodeType.NT_FOLDER_NAME)) {
			NodeIterator i = item.getNodes();
			while (i.hasNext()) {
				deployAggregationStrategy(i.nextNode(), rootPaths);
			}
			return;
		}
	}

	private void undeployAggregationStrategy(String itemPath, Event event) throws IOException, RepositoryException {
		String nodeType = event.getProperty("type").toString();

		if (nodeType.equals(NodeType.NT_FILE_NAME)) {
			synchronized (fAggregationStrategies) {
				AggregationStrategyImpl strategy = fAggregationStrategies.remove(itemPath);

				if (strategy != null) {
					CmsService.postEvent(CamelContext.class.getName().replace(".", "/") + "/UNDEPLOYED", AdaptableMap.<String, Object>newBuilder()
							.put("path", itemPath)
							.put("type", nodeType)
							.put("workspace", getWorkspaceName())
							.build());
				}
			}
			return;
		}

		if (nodeType.equals(NodeType.NT_FOLDER_NAME)) {
			synchronized (fAggregationStrategies) {
				for (String path : fAggregationStrategies.keySet().toArray(String[]::new)) {
					if (path.startsWith(itemPath + "/")) {
						AggregationStrategyImpl strategy = fAggregationStrategies.remove(path);

						if (strategy != null) {
							CmsService.postEvent(CamelContext.class.getName().replace(".", "/") + "/UNDEPLOYED", AdaptableMap.<String, Object>newBuilder()
									.put("path", path)
									.put("type", NodeType.NT_FILE_NAME)
									.put("workspace", getWorkspaceName())
									.build());
						}
					}
				}
			}
			return;
		}
	}

	private class RouteDeployer implements EventHandler, Closeable {
		private Thread fThread;
		private boolean fCloseRequested;
		private final List<Event> fEvents = new ArrayList<>();
		private final List<String> fPaths = new ArrayList<>();
		private Registration<EventHandler> fEventHandlerRegistration;

		private RouteDeployer() {
			fPaths.add("/etc/eip/routes");
			fPaths.add("/content/WEB-INF/routes");
		}

		@Override
		public void handleEvent(Event event) {
			synchronized (fEvents) {
				fEvents.add(event);
				fEvents.notifyAll();
			}
		}

		private RouteDeployer open() throws IOException, RepositoryException {
			if (fThread != null) {
				return this;
			}

			Session session = null;
			try {
				session = CmsService.getRepository().login(new CmsServiceCredentials(), getWorkspaceName());
				for (String e : fPaths) {
					try {
						deployRoute(session.getNode(e));
					} catch (PathNotFoundException ignore) {
						// Ignore if the path does not exist
					} catch (Throwable ex) {
						CmsService.getLogger(getClass()).error("Failed to deploy items under " + e, ex);
					}
				}
			} finally {
				try {
					session.logout();
				} catch (Throwable ignore) {}
			}

			fThread = new Thread(new Task());
			fThread.setDaemon(true);
			fThread.start();

			fEventHandlerRegistration = fCloser.register(Registration.newBuilder(EventHandler.class)
					.setService(this)
					.setProperty(EventConstants.EVENT_TOPIC, new String[] { Node.class.getName().replace(".", "/") + "/*" })
					.setProperty(EventConstants.EVENT_FILTER, "(workspace=" + getWorkspaceName() + ")")
					.setBundleContext(CmsService.getDefault().getBundleContext())
					.build());

			return this;
		}

		@Override
		public void close() throws IOException {
			if (fCloseRequested) {
				return;
			}

			fCloseRequested = true;
			IOs.closeQuietly(fEventHandlerRegistration);
			synchronized (fEvents) {
				fEvents.notifyAll();
			}
			try {
				fThread.interrupt();
				fThread.join(10000);
			} catch (InterruptedException ignore) {}
			fThread = null;
			fCloseRequested = false;
		}

		private class Task implements Runnable {
			@Override
			public void run() {
				while (!fCloseRequested) {
					if (Thread.interrupted()) {
						fCloseRequested = true;
						break;
					}
					Event event;
					synchronized (fEvents) {
						if (fEvents.isEmpty()) {
							try {
								fEvents.wait();
							} catch (InterruptedException ignore) {}
							continue;
						}

						event = fEvents.remove(0);
						if (Thread.interrupted()) {
							fCloseRequested = true;
							break;
						}
					}

					try {
						String topic = event.getTopic();
						if (topic.endsWith("/ADDED") || topic.endsWith("/CHANGED") || topic.endsWith("/MOVED")) {
							if (topic.endsWith("/MOVED")) {
								String srcPath = event.getProperty("source_path").toString();
								if (pathMatches(srcPath)) {
									undeployRoute(srcPath, event);
								}
							}

							String path = event.getProperty("path").toString();
							if (!pathMatches(path)) {
								continue;
							}

							String type = event.getProperty("type").toString();
							// A moved folder is reported alone, not file by file, so the files
							// under it are deployed from here or not at all.
							boolean movedFolder = topic.endsWith("/MOVED") && type.equals(NodeType.NT_FOLDER_NAME);
							if (!type.equals(NodeType.NT_FILE_NAME) && !movedFolder) {
								continue;
							}
							if (movedFolder) {
								CmsService.getLogger(getClass()).info("Deploying the route files under the moved folder: " + path
										+ " (moved from " + event.getProperty("source_path") + ")");
							}

							Session session = null;
							try {
								session = CmsService.getRepository().login(new CmsServiceCredentials(), getWorkspaceName());
								deployRoute(session.getNodeByIdentifier(event.getProperty("identifier").toString()));
							} finally {
								try {
									session.logout();
								} catch (Throwable ignore) {}
							}
						} else if (topic.endsWith("/REMOVED")) {
							String path = event.getProperty("path").toString();
							if (!pathMatches(path)) {
								continue;
							}

							undeployRoute(path, event);
						}
					} catch (Throwable ex) {
						CmsService.getLogger(getClass()).error("An error occurred while processing the event: " + event, ex);
					}
				}
			}

			private boolean pathMatches(String path) {
				for (String e : fPaths) {
					if (path.startsWith(e + "/")) {
						return true;
					}
				}
				return false;
			}
		}
	}

	private class AggregationStrategyDeployer implements EventHandler, Closeable {
		private Thread fThread;
		private boolean fCloseRequested;
		private final List<Event> fEvents = new ArrayList<>();
		private final List<String> fPaths = new ArrayList<>();
		private Registration<EventHandler> fEventHandlerRegistration;

		private AggregationStrategyDeployer() {
			fPaths.add("/etc/eip/strategies/aggregation");
		}

		@Override
		public void handleEvent(Event event) {
			synchronized (fEvents) {
				fEvents.add(event);
				fEvents.notifyAll();
			}
		}

		private AggregationStrategyDeployer open() throws IOException, RepositoryException {
			if (fThread != null) {
				return this;
			}

			Session session = null;
			try {
				session = CmsService.getRepository().login(new CmsServiceCredentials(), getWorkspaceName());
				for (String e : fPaths) {
					try {
						deployAggregationStrategy(session.getNode(e), fPaths);
					} catch (PathNotFoundException ignore) {
						// Ignore if the path does not exist
					} catch (Throwable ex) {
						CmsService.getLogger(getClass()).error("Failed to deploy items under " + e, ex);
					}
				}
			} finally {
				try {
					session.logout();
				} catch (Throwable ignore) {}
			}

			fThread = new Thread(new Task());
			fThread.setDaemon(true);
			fThread.start();

			fEventHandlerRegistration = fCloser.register(Registration.newBuilder(EventHandler.class)
					.setService(this)
					.setProperty(EventConstants.EVENT_TOPIC, new String[] { Node.class.getName().replace(".", "/") + "/*" })
					.setProperty(EventConstants.EVENT_FILTER, "(workspace=" + getWorkspaceName() + ")")
					.setBundleContext(CmsService.getDefault().getBundleContext())
					.build());

			return this;
		}

		@Override
		public void close() throws IOException {
			if (fCloseRequested) {
				return;
			}

			fCloseRequested = true;
			IOs.closeQuietly(fEventHandlerRegistration);
			synchronized (fEvents) {
				fEvents.notifyAll();
			}
			try {
				fThread.interrupt();
				fThread.join(10000);
			} catch (InterruptedException ignore) {}
			fThread = null;
			fCloseRequested = false;
		}

		private class Task implements Runnable {
			@Override
			public void run() {
				while (!fCloseRequested) {
					if (Thread.interrupted()) {
						fCloseRequested = true;
						break;
					}
					Event event;
					synchronized (fEvents) {
						if (fEvents.isEmpty()) {
							try {
								fEvents.wait();
							} catch (InterruptedException ignore) {}
							continue;
						}

						event = fEvents.remove(0);
						if (Thread.interrupted()) {
							fCloseRequested = true;
							break;
						}
					}

					try {
						String topic = event.getTopic();
						if (topic.endsWith("/ADDED") || topic.endsWith("/CHANGED") || topic.endsWith("/MOVED")) {
							if (topic.endsWith("/MOVED")) {
								String srcPath = event.getProperty("source_path").toString();
								if (pathMatches(srcPath)) {
									undeployAggregationStrategy(srcPath, event);
								}
							}

							String path = event.getProperty("path").toString();
							if (!pathMatches(path)) {
								continue;
							}

							String type = event.getProperty("type").toString();
							if (!type.equals(NodeType.NT_FILE_NAME)) {
								continue;
							}

							Session session = null;
							try {
								session = CmsService.getRepository().login(new CmsServiceCredentials(), getWorkspaceName());
								deployAggregationStrategy(session.getNodeByIdentifier(event.getProperty("identifier").toString()), fPaths);
							} finally {
								try {
									session.logout();
								} catch (Throwable ignore) {}
							}
						} else if (topic.endsWith("/REMOVED")) {
							String path = event.getProperty("path").toString();
							if (!pathMatches(path)) {
								continue;
							}

							undeployAggregationStrategy(path, event);
						}
					} catch (Throwable ex) {
						CmsService.getLogger(getClass()).error("An error occurred while processing the event: " + event, ex);
					}
				}
			}

			private boolean pathMatches(String path) {
				for (String e : fPaths) {
					if (path.startsWith(e + "/")) {
						return true;
					}
				}
				return false;
			}
		}
	}

	private class AggregationStrategyImpl implements AggregationStrategy {
		private final String fRootPath;
		private final String fRelPath;
		private final String fBeanName;
		private final String fSource;

		private AggregationStrategyImpl(Node item, List<String> rootPaths) throws RepositoryException, IOException {
			String rootPath = null;
			for (String path : rootPaths) {
				if (path.endsWith("/")) {
					path = path.substring(0, path.length() - 1);
				}
				if (!item.getPath().startsWith(path + "/")) {
					continue;
				}
				if (!JCRs.isFile(item)) {
					throw new IllegalArgumentException("The aggregation strategy must be a file node: " + item.getPath());
				}
				if (!item.getName().endsWith(".groovy")) {
					throw new IllegalArgumentException("The aggregation strategy must be a Groovy script file: " + item.getPath());
				}
				rootPath = path;
				break;
			}
			if (rootPath == null) {
				new IllegalArgumentException("The aggregation strategy must be deployed under one of the following paths: " + rootPaths);
			}
			fRootPath = rootPath;
			String relPath = item.getPath().substring(rootPath.length());
			relPath = relPath.startsWith("/") ? relPath.substring(1) : relPath;
			fRelPath = relPath;
			String beanName = relPath.substring(0, relPath.length() - ".groovy".length());
			beanName = beanName.replace("/", ".");
			fBeanName = beanName;
			fSource = JCRs.getContentAsString(item);
		}

		public boolean matches(String beanName) {
			return (fBeanName.equals(beanName) || fBeanName.equals(beanName.replace("/", ".")));
		}

		@Override
		public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
			Map<String, Object> bindings = new HashMap<>();
			bindings.put("oldExchange", oldExchange);
			bindings.put("newExchange", newExchange);
			return eval(bindings, newExchange);
		}

		@Override
		public Exchange aggregate(Exchange oldExchange, Exchange newExchange, Exchange inputExchange) {
			Map<String, Object> bindings = new HashMap<>();
			bindings.put("oldExchange", oldExchange);
			bindings.put("newExchange", newExchange);
			bindings.put("inputExchange", inputExchange);
			return eval(bindings, newExchange);
		}

		private Exchange eval(Map<String, Object> env, Exchange exchange) {
			ScriptingContext context = (ScriptingContext) getEnv("mi:cms.context", exchange);
			WorkspaceScriptContext scriptingContext = null;
			if (!(context instanceof WorkspaceScriptContext)) {
				scriptingContext = new WorkspaceScriptContext(getWorkspaceName());
				try {
					Scripts.prepareAPIs(scriptingContext);
				} catch (IOException ex) {
					throw Cause.create(ex).wrap(IllegalStateException.class, "Failed to prepare APIs for the scripting context.");
				}
				context = scriptingContext;
			}

			try (ScriptReader scriptReader = new ScriptReader(new StringReader(fSource))) {
				for (Map.Entry<String, Object> entry : env.entrySet()) {
					context.setAttribute(entry.getKey(), entry.getValue());
				}
				return (Exchange) scriptReader
						.setScriptName("inline")
						.setExtension("groovy")
						.setScriptEngineManager(Scripts.getScriptEngineManager(context))
						.setClassLoader(Scripts.getClassLoader(context))
						.setScriptContext(context)
						.eval();
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class, "Failed to evaluate the aggregation strategy script.");
			} finally {
				for (String key : env.keySet()) {
					context.removeAttribute(key);
				}
				if (scriptingContext != null) {
					try {
						scriptingContext.close();
					} catch (Throwable ignore) {}
				}
			}
		}

		private Object getEnv(String name, Exchange exchange) {
			name = name.trim();
			if (Strings.isEmpty(name)) {
				throw new IllegalArgumentException("The name of the environment variable must not be empty.");
			}

			Object value = exchange.getProperty(name);
			if (value instanceof String key) {
				if (key.startsWith("@property.")) {
					return exchange.getProperty(key.substring("@property.".length()));
				}
				if (key.startsWith("@header.")) {
					return exchange.getIn().getHeader(key.substring("@header.".length()));
				}
				if (key.equalsIgnoreCase("@body")) {
					return exchange.getIn().getBody();
				}
			}
			return value;
		}
	}

}
