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

package org.mintjams.rt.log.file.internal;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.mintjams.jcr.service.Bootstrap;
import org.mintjams.jcr.service.ServiceMonitor;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.osgi.Properties;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogListener;
import org.osgi.service.log.LogReaderService;

@Component(name = LogWriterService.COMPONENT_NAME, configurationPolicy = ConfigurationPolicy.OPTIONAL, enabled = true, immediate = true)
public class LogWriterService {

	public static final String COMPONENT_NAME = "org.mintjams.rt.log.file.LogWriterService";

	@Reference(cardinality = ReferenceCardinality.MANDATORY, policy = ReferencePolicy.STATIC)
	private Bootstrap fBootstrap;

	@Reference(cardinality = ReferenceCardinality.MANDATORY, policy = ReferencePolicy.STATIC)
	private LogReaderService fLogReaderService;

	private BundleContext fBundleContext;
	private Properties fProperties;
	private final Closer fCloser = Closer.create();
	private LogWriter fLogWriter;
	private final ServiceMonitorImpl fServiceMonitor = new ServiceMonitorImpl();

	@Activate
	void activate(ComponentContext cc, BundleContext bc, Map<String, Object> config) {
		fBundleContext = bc;
		try {
			fProperties = Properties.create(config);
			open();
		} catch (Throwable ex) {}
	}

	@Deactivate
	void deactivate(ComponentContext cc, BundleContext bc) {
		try {
			close();
		} catch (Throwable ex) {}
		fBundleContext = null;
	}

	private synchronized void open() throws IOException {
		fBootstrap.setServiceMonitor(fServiceMonitor);

		fLogWriter = fCloser.register(new LogWriter());
		fLogWriter.open();

		fServiceMonitor.doStatusChanged();
	}

	private synchronized void close() throws IOException {
		fCloser.close();
	}

	private class LogWriter implements LogListener, Closeable {
		private LogWriterConfiguration fConfiguration;
		private Thread fRotationThread;
		private Thread fWriterThread;
		private boolean fCloseRequested;
		private final List<LogEntry> fLogEntries = new ArrayList<>();
		private Path fPath;
		private final Object fWriteLock = new Object();
		private Writer fWriter;

		@Override
		public void logged(LogEntry logEntry) {
			synchronized (fLogEntries) {
				fLogEntries.add(logEntry);
				fLogEntries.notifyAll();
			}
		}

		private LogWriter open() throws IOException {
			if (fConfiguration != null) {
				return this;
			}

			fConfiguration = LogWriterConfiguration.create(fProperties);

			fRotationThread = new Thread(new RotationTask());
			fRotationThread.setDaemon(true);
			fRotationThread.start();

			fWriterThread = new Thread(new WriterTask());
			fWriterThread.setDaemon(true);
			fWriterThread.start();

			fLogReaderService.addLogListener(this);
			return this;
		}

		@Override
		public void close() throws IOException {
			if (fCloseRequested) {
				return;
			}

			fCloseRequested = true;
			fLogReaderService.removeLogListener(this);
			synchronized (fLogEntries) {
				fLogEntries.notifyAll();
			}
			try {
				fWriterThread.interrupt();
				fWriterThread.join(10000);
			} catch (InterruptedException ignore) {}
			try {
				fRotationThread.interrupt();
				fRotationThread.join(10000);
			} catch (InterruptedException ignore) {}
			fWriterThread = null;
			fRotationThread = null;
			fConfiguration = null;
			fCloseRequested = false;
		}

		private class RotationTask implements Runnable {
			private String fDateString;

			private RotationTask() throws IOException {
				rotate();
			}

			private void rotate() throws IOException {
				String currentString = new SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
				if (fDateString != null && fDateString.equals(currentString)) {
					return;
				}
				fDateString = currentString;

				synchronized (fWriteLock) {
					if (fWriter != null) {
						fWriter.close();
						fWriter = null;
					}

					String filename = "ROOT." + fDateString + ".log";
					fPath = getLogPath().resolve(filename);
					if (!Files.exists(fPath.getParent())) {
						Files.createDirectories(fPath.getParent());
					}
					fWriter = Files.newBufferedWriter(fPath, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				}
			}

			private Path getLogPath() {
				return Path.of(Strings.defaultIfEmpty(fBundleContext.getProperty("org.mintjams.log.rootdir"), "log")).normalize();
			}

			@Override
			public void run() {
				while (!fCloseRequested) {
					try {
						Thread.sleep(1000);
					} catch (InterruptedException ignore) {}

					if (fCloseRequested) {
						continue;
					}

					try {
						rotate();
					} catch (Throwable ex) {}
				}
			}
		}

		private class WriterTask implements Runnable {
			@Override
			public void run() {
				while (!fCloseRequested) {
					LogEntry logEntry;
					synchronized (fLogEntries) {
						if (fLogEntries.isEmpty()) {
							try {
								fLogEntries.wait();
							} catch (InterruptedException ignore) {}
							continue;
						}

						logEntry = fLogEntries.remove(0);
					}

					synchronized (fWriteLock) {
						try {
							fWriter.append(logEntry.toString());
							fWriter.flush();
						} catch (Throwable ex) {}
					}
				}
			}
		}
	}

	private class ServiceMonitorImpl implements ServiceMonitor {
		private Listener fListener;

		@Override
		public String getName() {
			return fBundleContext.getBundle().getSymbolicName();
		}

		@Override
		public String getStatus() {
			if (fLogWriter == null) {
				return STATUS_LAUNCHING;
			}
			return STATUS_RUNNING;
		}

		@Override
		public void setListener(Listener listener) {
			fListener = listener;
		}

		private void doStatusChanged() {
			if (fListener == null) {
				return;
			}

			fListener.onStatusChanged(this);
		}
	}

}
