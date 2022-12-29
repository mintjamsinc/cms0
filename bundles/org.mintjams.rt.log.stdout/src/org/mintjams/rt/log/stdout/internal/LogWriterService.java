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

package org.mintjams.rt.log.stdout.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

import org.mintjams.jcr.service.Bootstrap;
import org.mintjams.jcr.service.ServiceMonitor;
import org.mintjams.tools.io.Closer;
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

	public static final String COMPONENT_NAME = "org.mintjams.rt.log.stdout.LogWriterService";

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

		@Override
		public void logged(LogEntry logEntry) {
			System.out.print(logEntry.toString());
		}

		private LogWriter open() {
			if (fConfiguration != null) {
				return this;
			}

			fConfiguration = LogWriterConfiguration.create(fProperties);

			fLogReaderService.addLogListener(this);
			return this;
		}

		@Override
		public void close() throws IOException {
			fLogReaderService.removeLogListener(this);
			fConfiguration = null;
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
