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

import org.apache.camel.CamelContext;
import org.apache.camel.RouteConfigurationsBuilder;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteConfigurationDefinition;
import org.apache.camel.dsl.groovy.GroovyRoutesBuilderLoader;
import org.apache.camel.dsl.java.joor.JavaRoutesBuilderLoader;
import org.apache.camel.dsl.xml.io.XmlRoutesBuilderLoader;
import org.apache.camel.dsl.yaml.YamlRoutesBuilderLoader;
import org.apache.camel.spi.RoutesBuilderLoader;
import org.mintjams.jcr.nodetype.NodeType;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.tools.collections.AdaptableList;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.osgi.Registration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

public class WorkspaceIntegrationEngineProvider implements Closeable {

	private final WorkspaceIntegrationEngineProviderConfiguration fConfig;
	private final Closer fCloser = Closer.create();
	private final Map<String, List<String>> fDeployments = new HashMap<>();
	private final Map<String, List<String>> fRouteConfigDeployments = new HashMap<>();
	private WorkspaceCamelContext fCamelContext;

	public WorkspaceIntegrationEngineProvider(String workspaceName) {
		fConfig = new WorkspaceIntegrationEngineProviderConfiguration(workspaceName);
	}

	public synchronized void open() throws IOException, RepositoryException {
		fConfig.load();

		fCamelContext = new WorkspaceCamelContext(fConfig);
		fCloser.register(new Closeable() {
			@Override
			public void close() throws IOException {
				try {
					fCamelContext.close();
				} catch (Throwable ignore) {}
			}
		});
		fCamelContext.start();

		Deployer deployer = fCloser.register(new Deployer());
		deployer.open();
	}

	@Override
	public synchronized void close() throws IOException {
		fCloser.close();
	}

	public String getWorkspaceName() {
		return fConfig.getWorkspaceName();
	}

	public CamelContext getCamelContext() {
		return fCamelContext;
	}

	public Map<String, List<String>> getDeployments() {
		synchronized (fDeployments) {
			return new HashMap<>(fDeployments);
		}
	}

	private RoutesBuilderLoader getRoutesBuilderLoader(Node item) throws RepositoryException {
		String mimeType = JCRs.getMimeType(item);

		if (AdaptableList.<String>newBuilder()
				.add("text/x-java")
				.add("text/x-java-source")
				.build().contains(mimeType)) {
			return new JavaRoutesBuilderLoader();
		}

		if (AdaptableList.<String>newBuilder()
				.add("text/xml")
				.add("application/xml")
				.build().contains(mimeType)) {
			return new XmlRoutesBuilderLoader();
		}

		if (AdaptableList.<String>newBuilder()
				.add("text/x-yaml")
				.add("text/yaml")
				.add("application/x-yaml")
				.add("application/yaml")
				.build().contains(mimeType)) {
			return new YamlRoutesBuilderLoader();
		}

		if (AdaptableList.<String>newBuilder()
				.add("text/x-groovy")
				.add("text/groovy")
				.add("application/x-groovy")
				.add("application/groovy")
				.build().contains(mimeType)) {
			return new GroovyRoutesBuilderLoader();
		}

		throw new IllegalArgumentException("Could not obtain RoutesBuilderLoader: " + mimeType);
	}

	private void deploy(Node item) throws IOException, RepositoryException {
		if (item.getPrimaryNodeType().getName().equals(NodeType.NT_FILE_NAME)) {
			synchronized (fDeployments) {
				String itemPath = item.getPath();
				try (RoutesBuilderLoader loader = getRoutesBuilderLoader(item)) {
					ModelCamelContext modelContext = (ModelCamelContext) fCamelContext;

					// Snapshot of current route IDs and route configuration IDs before loading
					Set<String> routeIdsBefore = fCamelContext.getRoutes().stream()
							.map(route -> route.getRouteId())
							.collect(Collectors.toSet());
					Set<String> configIdsBefore = modelContext.getRouteConfigurationDefinitions().stream()
							.map(RouteConfigurationDefinition::getId)
							.filter(id -> id != null)
							.collect(Collectors.toSet());

					// Stop and remove previously deployed routes for this path
					List<String> previousRouteIds = fDeployments.get(itemPath);
					if (previousRouteIds != null) {
						for (String routeId : previousRouteIds) {
							try {
								fCamelContext.getRouteController().stopRoute(routeId);
								fCamelContext.removeRoute(routeId);
							} catch (Throwable ex) {
								CmsService.getLogger(getClass()).warn("Failed to remove route: " + routeId, ex);
							}
						}
					}

					// Remove previously deployed route configurations for this path
					List<String> previousConfigIds = fRouteConfigDeployments.get(itemPath);
					if (previousConfigIds != null) {
						modelContext.getRouteConfigurationDefinitions()
								.removeIf(def -> previousConfigIds.contains(def.getId()));
					}

					// Load and add new routes (and route configurations)
					loader.setCamelContext(fCamelContext);
					loader.build();
					RoutesBuilder routesBuilder = loader.loadRoutesBuilder(new CamelResource(item));
					if (routesBuilder instanceof RouteConfigurationsBuilder) {
						((RouteConfigurationsBuilder) routesBuilder).addRouteConfigurationsToCamelContext(fCamelContext);
					}
					routesBuilder.addRoutesToCamelContext(fCamelContext);

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
				deploy(i.nextNode());
			}
			return;
		}
	}

	private void removeRouteConfigurations(List<String> configIds) {
		if (configIds == null || configIds.isEmpty()) {
			return;
		}
		try {
			ModelCamelContext modelContext = (ModelCamelContext) fCamelContext;
			modelContext.getRouteConfigurationDefinitions()
					.removeIf(def -> configIds.contains(def.getId()));
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Failed to remove route configurations: " + configIds, ex);
		}
	}

	private void undeploy(String itemPath, Event event) throws IOException, RepositoryException {
		String nodeType = event.getProperty("type").toString();

		if (nodeType.equals(NodeType.NT_FILE_NAME)) {
			synchronized (fDeployments) {
				List<String> routeIds = fDeployments.remove(itemPath);
				if (routeIds != null) {
					for (String routeId : routeIds) {
						try {
							fCamelContext.stopRoute(routeId);
							fCamelContext.removeRoute(routeId);
						} catch (Throwable ex) {
							CmsService.getLogger(getClass()).error("An error occurred while removing route: " + routeId, ex);
						}
					}
				}

				// Remove route configurations deployed from this file
				removeRouteConfigurations(fRouteConfigDeployments.remove(itemPath));

				if (routeIds != null) {
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
									fCamelContext.getRouteController().stopRoute(routeId);
									fCamelContext.removeRoute(routeId);
								} catch (Throwable ex) {
									CmsService.getLogger(getClass()).error("An error occurred while removing route: " + routeId, ex);
								}
							}
						}

						// Remove route configurations deployed from this file
						removeRouteConfigurations(fRouteConfigDeployments.remove(path));

						if (routeIds != null) {
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

	private class Deployer implements EventHandler, Closeable {
		private Thread fThread;
		private boolean fCloseRequested;
		private final List<Event> fEvents = new ArrayList<>();
		private final List<String> fPaths = new ArrayList<>();
		private Registration<EventHandler> fEventHandlerRegistration;

		private Deployer() {
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

		private Deployer open() throws IOException, RepositoryException {
			if (fThread != null) {
				return this;
			}

			Session session = null;
			try {
				session = CmsService.getRepository().login(new CmsServiceCredentials(), getWorkspaceName());
				for (String e : fPaths) {
					try {
						deploy(session.getNode(e));
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
									undeploy(srcPath, event);
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
								deploy(session.getNodeByIdentifier(event.getProperty("identifier").toString()));
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

							undeploy(path, event);
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

}
