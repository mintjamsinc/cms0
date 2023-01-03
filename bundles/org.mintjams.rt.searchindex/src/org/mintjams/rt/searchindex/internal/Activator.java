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

package org.mintjams.rt.searchindex.internal;

import java.io.IOException;
import java.nio.file.Path;

import org.mintjams.searchindex.SearchIndexFactory;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.osgi.Registration;
import org.mintjams.tools.osgi.Tracker;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerFactory;

public class Activator implements BundleActivator {

	private static Activator fActivator;
	private BundleContext fBundleContext;
	private final Closer fCloser = Closer.create();
	private Tracker<LoggerFactory> fLoggerFactoryTracker;

	private Tracker.Listener<Object> fTrackerListener = new Tracker.Listener<Object>() {
		@Override
		public void on(Tracker.Event<Object> event) {
			if (event instanceof Tracker.ServiceAddingEvent) {
				try {
					open();
				} catch (Throwable ignore) {}
				return;
			}

			if (event instanceof Tracker.ServiceRemovedEvent) {
				try {
					close();
				} catch (Throwable ignore) {}
				return;
			}
		}
	};

	@Override
	public void start(BundleContext bc) throws Exception {
		fBundleContext = bc;
		fActivator = this;

		fLoggerFactoryTracker = fCloser.register(Tracker.newBuilder(LoggerFactory.class)
				.setBundleContext(fBundleContext)
				.setListener(fTrackerListener)
				.build());
		fLoggerFactoryTracker.open();
	}

	@Override
	public void stop(BundleContext bc) throws Exception {
		close();
		fActivator = null;
		fBundleContext = null;
	}

	private synchronized void open() throws IOException {
		if (fLoggerFactoryTracker.getTrackingCount() == 0) {
			return;
		}

		fCloser.add(Registration.newBuilder(SearchIndexFactory.class)
				.setService(new SearchIndexFactoryImpl())
				.setProperty("type", "local")
				.setBundleContext(fBundleContext)
				.build());
	}

	private synchronized void close() throws IOException {
		fCloser.close();
	}

	public static Activator getDefault() {
		return fActivator;
	}

	public ClassLoader getBundleClassLoader() {
		return fBundleContext.getBundle().adapt(BundleWiring.class).getClassLoader();
	}

	public BundleContext getBundleContext() {
		return fBundleContext;
	}

	public static Logger getLogger(Class<?> type) {
		return getDefault().fLoggerFactoryTracker.getService().getLogger(type);
	}

	public static Path getRepositoryPath() {
		return Path.of(Strings.defaultIfEmpty(getDefault().getBundleContext().getProperty("org.mintjams.jcr.repository.rootdir"), "repository"));
	}

}
