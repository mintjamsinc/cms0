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

package org.mintjams.rt.cms.internal.bpm;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.Deployment;
import org.mintjams.jcr.nodetype.NodeType;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.osgi.Registration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

public class WorkspaceProcessEngineProvider implements Closeable {

	private final WorkspaceProcessEngineProviderConfiguration fConfig;
	private final Closer fCloser = Closer.create();
	private ProcessEngine fProcessEngine;

	public WorkspaceProcessEngineProvider(String workspaceName) {
		fConfig = new WorkspaceProcessEngineProviderConfiguration(workspaceName);
	}

	public synchronized void open() throws IOException, RepositoryException {
		fConfig.load();

		fProcessEngine = fConfig.createProcessEngine();
		fCloser.add(new Closeable() {
			@Override
			public void close() throws IOException {
				try {
					fProcessEngine.close();
				} catch (Throwable ignore) {}
			}
		});

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

	public ProcessEngine getProcessEngine() {
		return fProcessEngine;
	}

	private void deploy(Node item) throws IOException, RepositoryException {
		if (item.getPrimaryNodeType().getName().equals(NodeType.NT_FILE_NAME)) {
			String itemPath = item.getPath();
			RepositoryService repositoryService = getProcessEngine().getRepositoryService();
			try (InputStream in = new BufferedInputStream(item.getNode(Node.JCR_CONTENT).getProperty(Property.JCR_DATA).getBinary().getStream())) {
				repositoryService.createDeployment().name(itemPath).addInputStream(itemPath, in).deploy();
			}

			CmsService.postEvent(Deployment.class.getName().replace(".", "/") + "/DEPLOYED", AdaptableMap.<String, Object>newBuilder()
					.put("path", itemPath)
					.put("type", item.getPrimaryNodeType().getName())
					.put("workspace", getWorkspaceName())
					.build());
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
			RepositoryService repositoryService = getProcessEngine().getRepositoryService();
			for (Deployment deployment : repositoryService.createDeploymentQuery().deploymentName(itemPath).list()) {
				repositoryService.deleteDeployment(deployment.getId());

				CmsService.postEvent(Deployment.class.getName().replace(".", "/") + "/UNDEPLOYED", AdaptableMap.<String, Object>newBuilder()
						.put("path", itemPath)
						.put("type", nodeType)
						.put("workspace", getWorkspaceName())
						.build());
			}
			return;
		}

		if (nodeType.equals(NodeType.NT_FOLDER_NAME)) {
			RepositoryService repositoryService = getProcessEngine().getRepositoryService();
			for (Deployment deployment : repositoryService.createDeploymentQuery().deploymentNameLike(itemPath + "/%").list()) {
				repositoryService.deleteDeployment(deployment.getId());

				CmsService.postEvent(Deployment.class.getName().replace(".", "/") + "/UNDEPLOYED", AdaptableMap.<String, Object>newBuilder()
						.put("path", deployment.getName())
						.put("type", NodeType.NT_FILE_NAME)
						.put("workspace", getWorkspaceName())
						.build());
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
			fPaths.add("/etc/bpm/processes");
			fPaths.add("/content/WEB-INF/processes");
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
