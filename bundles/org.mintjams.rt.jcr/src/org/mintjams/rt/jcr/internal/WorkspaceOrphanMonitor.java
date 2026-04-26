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

package org.mintjams.rt.jcr.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.sql.Query;

public class WorkspaceOrphanMonitor implements Closeable, Adaptable {

	private final JcrWorkspaceProvider fWorkspaceProvider;
	private Thread fThread;
	private boolean fCloseRequested;
	private final Object fLock = new Object();

	private WorkspaceOrphanMonitor(JcrWorkspaceProvider workspaceProvider) {
		fWorkspaceProvider = workspaceProvider;
	}

	public static WorkspaceOrphanMonitor create(JcrWorkspaceProvider workspaceProvider) {
		return new WorkspaceOrphanMonitor(workspaceProvider);
	}

	public WorkspaceOrphanMonitor open() {
		if (fThread != null) {
			return this;
		}

		fThread = new Thread(new Task());
		fThread.setDaemon(true);
		fThread.start();

		return this;
	}

	public boolean isLive() {
		return (fThread != null && !fCloseRequested);
	}

	public WorkspaceOrphanMonitor comitted() {
		synchronized (fLock) {
			fLock.notifyAll();
		}

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

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspaceProvider, adapterType);
	}

	private class OrphanMonitorImpl implements WorkspaceQuery.OrphanMonitor {
		private final List<String> fOrphanIDs = new ArrayList<>();

		private final Function<Query, Query> fQueryCustomizer = new Function<>() {
			@Override
			public Query apply(Query query) {
				try {
					return query.setOffset(0).setLimit(10);
				} catch (Throwable ex) {
					throw Cause.create(ex).wrap(IllegalStateException.class);
				}
			}
		};

		private final Consumer<AdaptableMap<String, Object>> fNodeConsumer = new Consumer<>() {
			@Override
			public void accept(AdaptableMap<String, Object> r) {
				if (fOrphanIDs.contains(r.getString("item_id"))) {
					return;
				}

				String log = """
						An orphan node has been detected:
						  * Workspace: %s
						  * Identifier: %s
						  * Path: %s
						  * IsDeleted: %s
						  * IsSystem: %s
						""".formatted(
								fWorkspaceProvider.getWorkspaceName(),
								r.getString("item_id"),
								r.getString("item_path"),
								r.getBoolean("is_deleted"),
								r.getBoolean("is_system"));
				Activator.getDefault().getLogger(WorkspaceOrphanMonitor.class).warn(log);

				fOrphanIDs.add(r.getString("item_id"));
			}
		};

		@Override
		public boolean isCancelled() {
			return fCloseRequested;
		}

		@Override
		public Function<Query, Query> getQueryCustomizer() {
			return fQueryCustomizer;
		}

		@Override
		public Consumer<AdaptableMap<String, Object>> getNodeConsumer() {
			return fNodeConsumer;
		}
	};

	private class Task implements Runnable {
		private final WorkspaceQuery.OrphanMonitor fOrphanMonitor = new OrphanMonitorImpl();

		@Override
		public void run() {
			while (!fCloseRequested) {
				if (Thread.interrupted()) {
					fCloseRequested = true;
					break;
				}
				synchronized (fLock) {
					try {
						fLock.wait(3600000);
					} catch (InterruptedException ignore) {}
				}

				if (fCloseRequested) {
					continue;
				}

				try (JcrWorkspace workspace = fWorkspaceProvider.createSession(new SystemPrincipal())) {
					WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);
					try {
						workspaceQuery.items().checkOrphanNodes(fOrphanMonitor);
					} catch (Throwable ex) {
						throw ex;
					}
				} catch (Throwable ex) {
					Activator.getDefault().getLogger(WorkspaceOrphanMonitor.class).error("An error occurred while processing the cleaning task.", ex);
				}
			}
		}
	}

}
