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

	/** Lower bound for the heap-derived default index-writer RAM buffer, in MB. */
	private static final double MIN_DEFAULT_RAM_BUFFER_MB = 32;

	/** Upper bound for the heap-derived default index-writer RAM buffer, in MB. */
	private static final double MAX_DEFAULT_RAM_BUFFER_MB = 128;

	/** Fraction of the JVM max heap used for the RAM buffer when unconfigured. */
	private static final double DEFAULT_RAM_BUFFER_HEAP_PERCENT = 8;

	/**
	 * On a small heap the {@link #MIN_DEFAULT_RAM_BUFFER_MB} floor can dominate,
	 * so the default is additionally capped at this fraction of the JVM max heap
	 * so one writer's buffer never claims an unsafe share of a tiny heap.
	 */
	private static final double MAX_RAM_BUFFER_HEAP_FRACTION = 0.15;

	/**
	 * Returns the RAM buffer size, in <strong>megabytes</strong>, for the Lucene
	 * document index writer ({@code org.mintjams.searchindex.ramBufferSizeMB}).
	 * <p>
	 * A larger buffer lets Lucene accumulate more documents before flushing a
	 * segment, which is what makes a deferred-commit bulk rebuild fast: fewer,
	 * larger segments mean less merge churn. The buffer is filled lazily as
	 * documents are indexed and is released on commit, so raising the ceiling
	 * costs nothing during normal, commit-per-transaction operation. When the
	 * property is not set the default is derived from the JVM max heap
	 * ({@code -Xmx}): {@value #DEFAULT_RAM_BUFFER_HEAP_PERCENT}% of the heap,
	 * clamped to [{@value #MIN_DEFAULT_RAM_BUFFER_MB},
	 * {@value #MAX_DEFAULT_RAM_BUFFER_MB}] MB and additionally never more than
	 * {@value #MAX_RAM_BUFFER_HEAP_FRACTION} of the heap, so small deployments
	 * stay modest while large ones get a genuinely useful buffer. The return is
	 * always strictly positive (Lucene requires it).
	 */
	public static double getIndexWriterRAMBufferSizeMB() {
		String configured = getDefault().getBundleContext().getProperty("org.mintjams.searchindex.ramBufferSizeMB");
		if (Strings.isNotEmpty(configured)) {
			try {
				double value = Double.parseDouble(configured.trim());
				// Lucene requires a strictly positive, finite RAM buffer; fall
				// through to the default when a nonsensical value is configured.
				if (value > 0 && !Double.isInfinite(value)) {
					return value;
				}
			} catch (NumberFormatException ignore) {}
		}

		double maxHeapMB = Runtime.getRuntime().maxMemory() / (1024D * 1024D);
		double scaled = maxHeapMB * DEFAULT_RAM_BUFFER_HEAP_PERCENT / 100D;
		double value = Math.min(Math.max(scaled, MIN_DEFAULT_RAM_BUFFER_MB), MAX_DEFAULT_RAM_BUFFER_MB);
		// Never let the floor push one buffer past a safe fraction of a tiny heap.
		double heapCeiling = Math.max(8, maxHeapMB * MAX_RAM_BUFFER_HEAP_FRACTION);
		return Math.min(value, heapCeiling);
	}

	/** Default upper bound, in characters, on the extracted text indexed per document. */
	private static final int DEFAULT_MAX_CONTENT_LENGTH = 1_000_000;

	/** Never index fewer than this many characters, even when misconfigured. */
	private static final int MIN_MAX_CONTENT_LENGTH = 1_000;

	/**
	 * Returns the maximum number of characters of extracted content indexed into a
	 * single document's {@code _fulltext} field, configured via
	 * {@code org.mintjams.searchindex.maxContentLength} (default
	 * {@value #DEFAULT_MAX_CONTENT_LENGTH}).
	 * <p>
	 * Full-text indexing pulls the document body into memory - the raw text for a
	 * {@code text/*} resource, Tika-extracted text otherwise - appends it to a
	 * {@code StringBuilder} and hands the result to Lucene, which buffers the
	 * tokenised terms. Every stage holds its own copy, so without a bound a single
	 * very large resource (a minified JavaScript bundle, a source map, a
	 * multi-megabyte log) materialises several multi-megabyte copies of itself, and
	 * a parallel rebuild holds one such document per worker at once - enough to
	 * exhaust the heap. Capping the extracted text bounds that cost while still
	 * indexing far more than any human-readable document needs.
	 * <p>
	 * The Tika path was already implicitly bounded (Tika truncates extracted text
	 * at 100,000 characters by default); the {@code text/*} path, which previously
	 * read the whole resource into a {@code String}, now honours this same
	 * explicit, tunable limit. Raising the limit trades heap during indexing for
	 * recall on oversized documents; it is clamped to at least
	 * {@value #MIN_MAX_CONTENT_LENGTH} characters.
	 */
	public static int getMaxContentLength() {
		String configured = getDefault().getBundleContext().getProperty("org.mintjams.searchindex.maxContentLength");
		if (Strings.isNotEmpty(configured)) {
			try {
				return Math.max(Integer.parseInt(configured.trim()), MIN_MAX_CONTENT_LENGTH);
			} catch (NumberFormatException ignore) {}
		}
		return DEFAULT_MAX_CONTENT_LENGTH;
	}

	/** Default number of values a percentile aggregation keeps for exact computation. */
	private static final int DEFAULT_PERCENTILE_EXACT_LIMIT = 1_000_000;

	/** Never switch to the approximate estimator below this many values. */
	private static final int MIN_PERCENTILE_EXACT_LIMIT = 100;

	/**
	 * Returns the maximum number of values a percentile/median aggregation of
	 * the {@code facet accumulate} clause buffers for exact computation,
	 * configured via {@code org.mintjams.searchindex.percentileExactLimit}
	 * (default {@value #DEFAULT_PERCENTILE_EXACT_LIMIT}).
	 * <p>
	 * Exact percentiles hold every aggregated value in memory (8 bytes per
	 * value per accumulator, so per group bucket when grouped). Up to the
	 * limit that is exact and cheap; beyond it the accumulator migrates to a
	 * t-digest sketch with bounded memory and a small, quantile-dependent
	 * rank error (tightest at the tails). Raising the limit trades heap for
	 * exactness on very large result sets; it is clamped to at least
	 * {@value #MIN_PERCENTILE_EXACT_LIMIT} values.
	 */
	public static int getPercentileExactLimit() {
		String configured = getDefault().getBundleContext().getProperty("org.mintjams.searchindex.percentileExactLimit");
		if (Strings.isNotEmpty(configured)) {
			try {
				return Math.max(Integer.parseInt(configured.trim()), MIN_PERCENTILE_EXACT_LIMIT);
			} catch (NumberFormatException ignore) {}
		}
		return DEFAULT_PERCENTILE_EXACT_LIMIT;
	}

}
