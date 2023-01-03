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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.mintjams.jcr.service.Bootstrap;
import org.mintjams.jcr.service.ServiceMonitor;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.osgi.Registration;
import org.mintjams.tools.osgi.Tracker;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.log.LogListener;
import org.osgi.service.log.LogReaderService;
import org.osgi.service.log.LogService;
import org.osgi.service.log.LoggerFactory;
import org.osgi.service.log.admin.LoggerAdmin;

public class Activator implements BundleActivator {

	private final String[] STANDARD_LOG_LISTENERS = new String[] {
			"org.mintjams.rt.log.stdout.",
			"org.mintjams.rt.log.file."
	};

	private static Activator fActivator;
	private BundleContext fBundleContext;
	private final Closer fCloser = Closer.create();
	private Tracker<Bootstrap> fBootstrapTracker;
	private Registration<LogService> fLogServiceRegistration;
	private static final LoggerAdminImpl fLoggerAdmin = new LoggerAdminImpl();
	private static final AtomicLong fLogEntrySequence = new AtomicLong();
	private static final LogQueue fLogQueue = new LogQueue();
	private LogReaderServiceFactory fLogReaderServiceFactory;
	private final List<String> fWaitings = new ArrayList<>(Arrays.asList(STANDARD_LOG_LISTENERS));
	private final ServiceMonitorImpl fServiceMonitor = new ServiceMonitorImpl();

	private Tracker.Listener<Object> fTrackerListener = new Tracker.Listener<Object>() {
		@Override
		public void on(Tracker.Event<Object> event) {
			if (event instanceof Tracker.ServiceAddingEvent) {
				if (event.getService() instanceof Bootstrap) {
					fBootstrapTracker.getService().setServiceMonitor(fServiceMonitor);
				}
				return;
			}
		}
	};

	@Override
	public void start(BundleContext bc) throws Exception {
		fBundleContext = bc;
		fActivator = this;
		open();
	}

	@Override
	public void stop(BundleContext bc) throws Exception {
		close();
		fActivator = null;
		fBundleContext = null;
	}

	private synchronized void open() throws Exception {
		fBootstrapTracker = fCloser.register(Tracker.newBuilder(Bootstrap.class)
				.setBundleContext(fBundleContext)
				.setListener(fTrackerListener)
				.build());
		fBootstrapTracker.open();

		fCloser.register(fLogQueue).open();

		fLogReaderServiceFactory = new LogReaderServiceFactory();
		fCloser.register(Registration.newBuilder(LogReaderService.class)
				.setServiceFactory(fLogReaderServiceFactory)
				.setBundleContext(fBundleContext)
				.build());

		fCloser.add(Registration.newBuilder(LoggerFactory.class)
				.setServiceFactory(new LoggerFactoryFactory())
				.setBundleContext(fBundleContext)
				.build());

		fLogServiceRegistration = fCloser.register(Registration.newBuilder(LogService.class)
				.setServiceFactory(new LogServiceFactory())
				.setBundleContext(fBundleContext)
				.build());

		fCloser.add(Registration.newBuilder(LoggerAdmin.class)
				.setService(fLoggerAdmin)
				.setProperty(LoggerAdmin.LOG_SERVICE_ID, fLogServiceRegistration.getReference().getProperty(Constants.SERVICE_ID))
				.setBundleContext(fBundleContext)
				.build());

		fServiceMonitor.doStatusChanged();
	}

	private synchronized void close() throws IOException {
		fCloser.close();
	}

	public void onLogListenerAdded(LogListener logListener) {
		synchronized (fWaitings) {
			if (fWaitings.isEmpty()) {
				return;
			}

			for (String prefix : fWaitings.toArray(String[]::new)) {
				if (logListener.getClass().getName().startsWith(prefix)) {
					fWaitings.remove(prefix);
					break;
				}
			}

			if (fWaitings.isEmpty()) {
				fWaitings.notifyAll();
			}
		}
	}

	public void waitForReady() {
		synchronized (fWaitings) {
			if (!fWaitings.isEmpty()) {
				try {
					fWaitings.wait(20000);
				} catch (Throwable ignore) {}
			}
		}

		if (!fWaitings.isEmpty()) {
			throw new IllegalStateException("Failed to start the log module.");
		}
	}

	public static Activator getDefault() {
		return fActivator;
	}

	public BundleContext getBundleContext() {
		return fBundleContext;
	}

	public static LoggerAdminImpl getLoggerAdmin() {
		return fLoggerAdmin;
	}

	public static long nextSequence() {
		return fLogEntrySequence.incrementAndGet();
	}

	public static LogQueue getLogQueue() {
		return fLogQueue;
	}

	public static LogListener[] getLogListeners() {
		return getDefault().fLogReaderServiceFactory.getLogListeners();
	}

	private static class LogServiceFactory implements ServiceFactory<LogService> {
		@Override
		public LogService getService(Bundle bundle, ServiceRegistration<LogService> registration) {
			return new LogServiceImpl(bundle);
		}

		@Override
		public void ungetService(Bundle bundle, ServiceRegistration<LogService> registration, LogService service) {
			// do nothing
		}
	}

	private static class LoggerFactoryFactory implements ServiceFactory<LoggerFactory> {
		@Override
		public LoggerFactory getService(Bundle bundle, ServiceRegistration<LoggerFactory> registration) {
			return new LogServiceImpl(bundle);
		}

		@Override
		public void ungetService(Bundle bundle, ServiceRegistration<LoggerFactory> registration, LoggerFactory service) {
			// do nothing
		}
	}

	private static class LogReaderServiceFactory implements ServiceFactory<LogReaderService>, Closeable {
		private final Closer fCloser = Closer.create();
		private final List<LogReaderServiceImpl> fLogReaderServices = new ArrayList<>();

		@Override
		public LogReaderService getService(Bundle bundle, ServiceRegistration<LogReaderService> registration) {
			LogReaderServiceImpl logReaderService = new LogReaderServiceImpl();
			synchronized (fLogReaderServices) {
				fLogReaderServices.add(fCloser.register(logReaderService));
			}
			return logReaderService;
		}

		@Override
		public void ungetService(Bundle bundle, ServiceRegistration<LogReaderService> registration, LogReaderService service) {
			synchronized (fLogReaderServices) {
				fLogReaderServices.remove(service);
			}
			if (service instanceof Closeable) {
				fCloser.unregister((Closeable) service);
			}
		}

		public LogListener[] getLogListeners() {
			List<LogListener> logListeners = new ArrayList<>();
			synchronized (fLogReaderServices) {
				for (LogReaderServiceImpl e : fLogReaderServices) {
					logListeners.addAll(Arrays.asList(e.getLogListeners()));
				}
			}
			return logListeners.toArray(LogListener[]::new);
		}

		@Override
		public void close() throws IOException {
			synchronized (fLogReaderServices) {
				fLogReaderServices.clear();
			}
			fCloser.close();
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
			if (fLogServiceRegistration == null) {
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
