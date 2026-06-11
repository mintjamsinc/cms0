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
import java.sql.Connection;

import org.mintjams.rt.jcr.internal.blob.BlobStore;
import org.mintjams.rt.jcr.internal.cluster.ClusterController;
import org.mintjams.rt.jcr.internal.cluster.ClusterJournal;
import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public class WorkspaceGarbageCollection implements Closeable, Adaptable {

	/**
	 * How long consumed cluster commit markers are kept before being purged.
	 * A node that stays offline longer than this can no longer catch up by
	 * replaying the journal and rebuilds its search index instead.
	 */
	private static final long COMMIT_MARKER_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;

	private final JcrWorkspaceProvider fWorkspaceProvider;
	private Thread fThread;
	private boolean fCloseRequested;
	private final Object fLock = new Object();

	private WorkspaceGarbageCollection(JcrWorkspaceProvider workspaceProvider) {
		fWorkspaceProvider = workspaceProvider;
	}

	public static WorkspaceGarbageCollection create(JcrWorkspaceProvider workspaceProvider) {
		return new WorkspaceGarbageCollection(workspaceProvider);
	}

	public WorkspaceGarbageCollection open() {
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
						fLock.wait(86400000);
					} catch (InterruptedException ignore) {}
				}

				if (fCloseRequested) {
					continue;
				}

				try {
					// All cluster nodes share the blob store, so only one node
					// may collect at a time; the others simply skip this run.
					ClusterController.Lease lease = adaptTo(ClusterController.class)
							.tryLock("blob-garbage-collection", 3600000L);
					if (lease == null) {
						continue;
					}

					try (lease; JcrWorkspace workspace = fWorkspaceProvider.createSession(new SystemPrincipal())) {
						WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);
						try {
							adaptTo(BlobStore.class).collectGarbage(new BlobStore.GarbageCollectionContext() {
								@Override
								public boolean isCancelled() {
									if (Thread.currentThread().isInterrupted()) {
										fCloseRequested = true;
									}
									return fCloseRequested;
								}

								@Override
								public boolean isReferenced(String id) throws IOException {
									try {
										return workspaceQuery.files().exists(id);
									} catch (java.sql.SQLException ex) {
										throw new IOException(ex);
									}
								}
							});

							if (adaptTo(ClusterController.class).isClusterEnabled() && !fCloseRequested) {
								ClusterJournal.purgeCommitMarkers(
										Adaptables.getAdapter(workspace, Connection.class),
										COMMIT_MARKER_RETENTION_MILLIS);
							}

							workspaceQuery.commit();
						} catch (Throwable ex) {
							try {
								workspaceQuery.rollback();
							} catch (Throwable ignore) {}
							throw ex;
						}
					}
				} catch (Throwable ex) {
					Activator.getDefault().getLogger(WorkspaceGarbageCollection.class).error("An error occurred while performing garbage collection.", ex);
				}
			}
		}
	}

}
