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

package org.mintjams.rt.log.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.osgi.service.log.LogListener;

public class LogQueue implements Closeable {

	private Thread fThread;
	private boolean fCloseRequested;
	private final Object fLock = new Object();
	private final List<LogEntryImpl> fLogEntries = new ArrayList<>();

	public LogQueue() {}

	public LogQueue open() {
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

	public LogQueue add(LogEntryImpl logEntry) {
		synchronized (fLock) {
			fLogEntries.add(logEntry);
			fLock.notifyAll();
		}

		return this;
	}

	@Override
	public void close() throws IOException {
		if (fThread == null) {
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
		fCloseRequested = true;
	}

	private class Task implements Runnable {
		@Override
		public void run() {
			Activator.getDefault().waitForReady();

			while (!fCloseRequested) {
				if (Thread.interrupted()) {
					fCloseRequested = true;
					break;
				}
				LogEntryImpl logEntry;
				synchronized (fLock) {
					if (fLogEntries.isEmpty()) {
						try {
							fLock.wait();
						} catch (InterruptedException ignore) {}
						continue;
					}

					logEntry = fLogEntries.remove(0);
				}

				if (fCloseRequested) {
					continue;
				}

				for (LogListener e : Activator.getLogListeners()) {
					try {
						e.logged(logEntry);
					} catch (Throwable ignore) {}
				}
			}
		}
	}

}
