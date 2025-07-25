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
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.jcr.nodetype.NodeType;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.WorkspaceDelegatingClassLoader;
import org.mintjams.rt.cms.internal.script.engine.ScriptCacheManager;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.osgi.Registration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

import groovy.lang.GroovyClassLoader;

public class WorkspaceClassLoaderProvider implements Closeable, Adaptable {

	private final String fWorkspaceName;
	private final Path fCachePath;
	private final Map<Path, String> fCachedFiles = new HashMap<>();
	private final Closer fCloser = Closer.create();
	private WorkspaceClassLoader fWorkspaceClassLoader;
	private ScriptCacheManager fScriptCacheManager;
	private GroovyClassLoader fGroovyClassLoader;
	private boolean fHasChanges;

	public WorkspaceClassLoaderProvider(String workspaceName) throws IOException {
		fWorkspaceName = workspaceName;
		fCachePath = Files.createTempDirectory(CmsService.getRepositoryPath().resolve("tmp"), "cld-");
	}

	public synchronized void open() throws IOException, RepositoryException {
		reload(true);
		Deployer deployer = fCloser.register(new Deployer());
		deployer.open();
		Reloader reloader = fCloser.register(new Reloader());
		reloader.open();
	}

	@Override
	public synchronized void close() throws IOException {
		synchronized (fCachedFiles) {
			closeClassLoader();
		}
		fCloser.close();
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return null;
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public Path getCachePath() {
		return fCachePath;
	}

	public ClassLoader getClassLoader() {
		return fWorkspaceClassLoader;
	}

	private void closeClassLoader() {
		if (fGroovyClassLoader != null) {
			fGroovyClassLoader.clearCache();
			fGroovyClassLoader = null;
		}
		if (fWorkspaceClassLoader != null) {
			IOs.closeQuietly(fWorkspaceClassLoader);
			fWorkspaceClassLoader = null;
		}
		if (fScriptCacheManager != null) {
			fScriptCacheManager.clearCache();
			fScriptCacheManager = null;
		}
	}

	private void openClassLoader() {
		fScriptCacheManager = new ScriptCacheManager();
		try {
			List<URL> urls = new ArrayList<>();
			collectFiles("lib", urls);
			addIfExists("usr/local/classes", urls);
			collectFiles("usr/local/lib", urls);
			addIfExists("content/WEB-INF/classes", urls);
			collectFiles("content/WEB-INF/lib", urls);
			fWorkspaceClassLoader = new WorkspaceClassLoader(urls.toArray(URL[]::new));
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class, "An error occurred while creating the class loader: " + fWorkspaceName);
		}
		fGroovyClassLoader = new GroovyClassLoader(new WorkspaceDelegatingClassLoader(fWorkspaceName));
	}

	private void reload(boolean force) {
		synchronized (fCachedFiles) {
			if (!force) {
				if (!fHasChanges) {
					return;
				}
			}

			fHasChanges = false;
			closeClassLoader();
			openClassLoader();
			CmsService.getLogger(getClass()).info("The class loader has been reloaded successfully.");
		}
	}

	private void reload() {
		reload(false);
	}

	private void addIfExists(String directoryPath, List<URL> urls) throws IOException {
		addIfExists(fCachePath.resolve(directoryPath), urls);
	}

	private void addIfExists(Path directoryPath, List<URL> urls) throws IOException {
		if (!Files.exists(directoryPath) || !Files.isDirectory(directoryPath)) {
			return;
		}

		urls.add(directoryPath.toUri().toURL());
	}

	private void collectFiles(String directoryPath, List<URL> urls) throws IOException {
		collectFiles(fCachePath.resolve(directoryPath), urls);
	}

	private void collectFiles(Path directoryPath, List<URL> urls) throws IOException {
		if (!Files.exists(directoryPath) || !Files.isDirectory(directoryPath)) {
			return;
		}

		try (Stream<Path> stream = Files.list(directoryPath)) {
			stream.forEach(path -> {
				try {
					if (Files.isDirectory(path)) {
						collectFiles(path, urls);
						return;
					}

					urls.add(path.toUri().toURL());
				} catch (IOException ex) {
					throw (IllegalStateException) new IllegalStateException(ex.getMessage()).initCause(ex);
				}
			});
		}
	}

	public class WorkspaceClassLoader extends URLClassLoader implements Adaptable {
		private WorkspaceClassLoader(URL[] urls) {
			super(WorkspaceClassLoader.class.getSimpleName() + "/" + getWorkspaceName(), urls, CmsService.getDefault().getBundleClassLoader());
		}

		@SuppressWarnings("unchecked")
		@Override
		public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
			if (adapterType.equals(GroovyClassLoader.class)) {
				return (AdapterType) fGroovyClassLoader;
			}

			if (adapterType.equals(ScriptCacheManager.class)) {
				return (AdapterType) fScriptCacheManager;
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
			fPaths.add("/lib");
			fPaths.add("/usr/local/classes");
			fPaths.add("/usr/local/lib");
			fPaths.add("/content/WEB-INF/classes");
			fPaths.add("/content/WEB-INF/lib");
			if (getWorkspaceName().equals("system")) {
				fPaths.add("/usr/share/classes");
				fPaths.add("/usr/share/lib");
			}
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

		private void deploy(Node item) throws IOException, RepositoryException {
			if (item.getPrimaryNodeType().getName().equals(NodeType.NT_FILE_NAME)) {
				synchronized (fCachedFiles) {
					String itemPath = item.getPath();
					Path path = fCachePath.resolve(itemPath.substring(1));
					Files.createDirectories(path.getParent());
					try (OutputStream out = Files.newOutputStream(path)) {
						try (InputStream in = JCRs.getContentAsStream(item)) {
							IOs.copy(in, out);
						}
					}
					fCachedFiles.put(path, itemPath);
					fHasChanges = true;
					CmsService.getLogger(getClass()).info("Deployed: " + itemPath);
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
				synchronized (fCachedFiles) {
					Path path = fCachePath.resolve(itemPath.substring(1));
					fCachedFiles.remove(path);
					Files.deleteIfExists(path);
					fHasChanges = true;
					CmsService.getLogger(getClass()).info("Undeployed: " + itemPath);
				}
				return;
			}

			if (nodeType.equals(NodeType.NT_FOLDER_NAME)) {
				synchronized (fCachedFiles) {
					Path parentPath = fCachePath.resolve(itemPath.substring(1));
					for (Path path : fCachedFiles.keySet().toArray(Path[]::new)) {
						if (path.startsWith(parentPath)) {
							String removedItemPath = fCachedFiles.remove(path);
							if (removedItemPath != null) {
								fHasChanges = true;
								CmsService.getLogger(getClass()).info("Undeployed: " + removedItemPath);
							}
						}
					}
					IOs.deleteIfExists(parentPath);
				}
				return;
			}
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
							reload();
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

	private class Reloader implements Closeable {
		private Thread fThread;
		private boolean fCloseRequested;
		private final Object fLock = new Object();

		private Reloader open() {
			if (fThread != null) {
				return this;
			}

			fThread = new Thread(new Task());
			fThread.setDaemon(true);
			fThread.start();

			return this;
		}

		@Override
		public void close() throws IOException {
			if (fCloseRequested) {
				return;
			}

			fCloseRequested = true;
			synchronized (fLock) {
				fLock.notifyAll();
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
					synchronized (fLock) {
						try {
							fLock.wait(CmsService.getConfiguration().getClassLoaderRefreshInterval() * 3600000);
						} catch (InterruptedException ignore) {}
					}

					if (fCloseRequested) {
						continue;
					}

					reload(true);
				}
			}
		}
	}

}
