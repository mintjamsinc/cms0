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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public class WorkspaceGarbageCollection implements Closeable, Adaptable {

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
		private WorkspaceQuery fWorkspaceQuery;

		@Override
		public void run() {
			while (!fCloseRequested) {
				synchronized (fLock) {
					try {
						fLock.wait(86400000);
					} catch (InterruptedException ignore) {}
				}

				if (fCloseRequested) {
					continue;
				}

				try (JcrWorkspace workspace = fWorkspaceProvider.createSession(new SystemPrincipal())) {
					fWorkspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);
					try {
						perform(adaptTo(JcrWorkspaceProvider.class).getJcrBinPath());
						fWorkspaceQuery.commit();
					} catch (Throwable ex) {
						try {
							fWorkspaceQuery.rollback();
						} catch (Throwable ignore) {}
						throw ex;
					} finally {
						fWorkspaceQuery = null;
					}
				} catch (Throwable ex) {
					Activator.getDefault().getLogger(WorkspaceGarbageCollection.class).error("An error occurred while performing garbage collection.", ex);
				}
			}
		}

		private void perform(Path parentPath) throws IOException {
			if (fCloseRequested) {
				return;
			}

			try (Stream<Path> stream = Files.list(parentPath)) {
				stream.forEach(path -> {
					if (fCloseRequested) {
						return;
					}

					try {
						if (Files.isDirectory(path)) {
							perform(path);
							return;
						}

						if (Files.getLastModifiedTime(path).toMillis() >= (System.currentTimeMillis() - 86400000)) {
							return;
						}

						if (fWorkspaceQuery.files().exists(path.getFileName().toString())) {
							return;
						}

						Files.deleteIfExists(path);
					} catch (Throwable ex) {
						Activator.getDefault().getLogger(WorkspaceGarbageCollection.class).error("An error occurred while performing garbage collection.", ex);
					}
				});
			}
		}
	}

}
