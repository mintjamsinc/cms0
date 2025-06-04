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

package org.mintjams.rt.cms.internal.script;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.jcr.nodetype.NodeType;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.osgi.Registration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class WorkspaceFacetProvider implements Closeable, Adaptable {

	private final String fWorkspaceName;
	private final Map<String, Map<String, Object>> fCachedFacets = new HashMap<>();
	private final Map<String, String> fFacetPaths = new HashMap<>();
	private final Closer fCloser = Closer.create();
	private final FieldTypeProviderImpl fFieldTypeProvider = new FieldTypeProviderImpl();

	public WorkspaceFacetProvider(String workspaceName) throws IOException {
		fWorkspaceName = workspaceName;
	}

	public synchronized void open() throws IOException, RepositoryException {
		Session session = null;
		try {
			session = CmsService.getRepository().login(new CmsServiceCredentials(), getWorkspaceName());
			Adaptables.getAdapter(session, SearchIndex.class).with(fFieldTypeProvider);
		} finally {
			try {
				session.logout();
			} catch (Throwable ignore) {}
		}

		Deployer deployer = fCloser.register(new Deployer());
		deployer.open();
	}

	@Override
	public synchronized void close() throws IOException {
		fCloser.close();
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return null;
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public Map<String, Object> getFacet(String key) {
		return fCachedFacets.get(key);
	}

	public Map<String, Map<String, Object>> getFacets(String... keys) {
		Map<String, Map<String, Object>> facets = new HashMap<>();
		for (String key : keys) {
			Map<String, Object> facet = fCachedFacets.get(key);
			if (facet != null) {
				facets.put(key, facet);
			}
		}
		return Collections.unmodifiableMap(facets);
	}

	public Map<String, Map<String, Object>> getAllFacets() {
		return Collections.unmodifiableMap(fCachedFacets);
	}

	private void deploy(Node item) throws IOException, RepositoryException {
		if (item.getPrimaryNodeType().getName().equals(NodeType.NT_FILE_NAME)) {
			synchronized (fCachedFacets) {
				Map<String, Object> yaml;
				try (InputStream in = JCRs.getContentAsStream(item)) {
					yaml = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
				}
				String key = (String) yaml.get("key");
				fCachedFacets.put(key, yaml);
				fFacetPaths.put(item.getPath(), key);
				CmsService.getLogger(getClass()).info("Deployed: " + key);
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

	private void undeploy(String itemPath, Event event) throws IOException, RepositoryException {
		String nodeType = event.getProperty("type").toString();

		if (nodeType.equals(NodeType.NT_FILE_NAME)) {
			synchronized (fCachedFacets) {
				String key = fFacetPaths.remove(itemPath);
				if (key != null) {
					fCachedFacets.remove(key);
					CmsService.getLogger(getClass()).info("Undeployed: " + key);
				}
			}
			return;
		}

		if (nodeType.equals(NodeType.NT_FOLDER_NAME)) {
			synchronized (fCachedFacets) {
				for (String path : fFacetPaths.keySet().toArray(String[]::new)) {
					if (path.startsWith(itemPath + "/")) {
						String key = fFacetPaths.remove(path);
						if (key != null) {
							fCachedFacets.remove(key);
							CmsService.getLogger(getClass()).info("Undeployed: " + key);
						}
					}
				}
			}
			return;
		}
	}

	private class FieldTypeProviderImpl implements SearchIndex.FieldTypeProvider {
		@Override
		public Class<?> getFieldType(String fieldName) {
			Map<String, Object> definition = fCachedFacets.get(fieldName);
			if (definition != null) {
				String type = (String) definition.get("type");
				if (type.equalsIgnoreCase("number") || type.equalsIgnoreCase("currency")) {
					return BigDecimal.class;
				}
				if (type.equalsIgnoreCase("datetime")) {
					return java.util.Date.class;
				}
				if (type.equalsIgnoreCase("boolean")) {
					return Boolean.class;
				}
				return String.class;
			}
			return null;
		}
	}

	private class Deployer implements EventHandler, Closeable {
		private Thread fThread;
		private boolean fCloseRequested;
		private final List<Event> fEvents = new ArrayList<>();
		private final List<String> fPaths = new ArrayList<>();
		private Registration<EventHandler> fEventHandlerRegistration;

		private Deployer() {
			fPaths.add("/content/WEB-INF/facets");
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
					} catch (PathNotFoundException ignore) {}
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
					} catch (Throwable ex) {}
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
