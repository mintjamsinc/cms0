/*
 * Copyright (c) 2026 MintJams Inc.
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

package org.mintjams.rt.cms.internal.graphql.event;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.searchindex.SearchIndex;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Computes the total content size of a list of paths for the
 * {@code contentLengths} subscription and emits one payload per path as each is
 * computed, then completes. Each total is a single {@code stats} facet query
 * against the search index over the {@code nt:file} documents under a folder,
 * or over the one document of a file, restricted to the subscriber's
 * principals, so it counts exactly the files the subscriber can read.
 *
 * <p>Nothing is persisted: the computation lives only as long as the
 * subscription. It is a request whose answer happens to be streamed, not a job
 * — a lost result is recomputed by subscribing again, so there is no record to
 * recover or audit.
 *
 * <p>Throughput is bounded by a small fixed worker pool shared by every
 * subscription. A subscription occupies at most one slot of the pool's queue:
 * a worker computes one path, then puts the subscription back at the tail of
 * the queue, so concurrent subscriptions take turns instead of the first large
 * listing starving the rest. When the queue is full the subscription fails
 * immediately rather than waiting; the client shows the sizes as unknown.
 */
public final class ContentLengthPublisher implements Publisher<Object> {

	/** The most paths one subscription may ask for. */
	public static final int MAX_PATHS = 1000;

	/** Workers computing content totals, shared by every subscription. */
	private static final int WORKERS = 2;

	/** Subscriptions waiting for a worker; each occupies at most one slot. */
	private static final int QUEUE_CAPACITY = 64;

	private static final ThreadPoolExecutor COMPUTE_EXECUTOR;
	static {
		COMPUTE_EXECUTOR = new ThreadPoolExecutor(WORKERS, WORKERS, 60L, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(QUEUE_CAPACITY), newThreadFactory("graphql-content-length-"));
		COMPUTE_EXECUTOR.allowCoreThreadTimeOut(true);
	}

	private static final Executor DELIVERY_EXECUTOR = Executors
			.newCachedThreadPool(newThreadFactory("graphql-content-length-delivery-"));

	private static final String JCR_ROOT = "/jcr:root";

	private static final String SIZE_PROPERTY = "@jcr:contentLength";

	private final SearchIndex fSearchIndex;
	private final Principal[] fAuthorizables;
	private final List<String> fPaths;
	private final Set<String> fUnreadablePaths;
	private final Set<String> fFilePaths;

	/**
	 * @param searchIndex the workspace search index, or {@code null} when it is
	 *        unavailable (every size is then emitted as unknown)
	 * @param authorizables the principals the subscriber's documents are
	 *        restricted to; empty for sessions that see everything
	 * @param paths the folders or files to total, computed in this order
	 * @param unreadablePaths the subset of {@code paths} the subscriber cannot
	 *        read; emitted as unknown without querying the index
	 * @param filePaths the subset of {@code paths} that are {@code nt:file}
	 *        nodes; totalled as the file itself rather than its descendants
	 */
	public ContentLengthPublisher(SearchIndex searchIndex, Principal[] authorizables, List<String> paths,
			Set<String> unreadablePaths, Set<String> filePaths) {
		fSearchIndex = searchIndex;
		fAuthorizables = authorizables.clone();
		fPaths = Collections.unmodifiableList(new ArrayList<>(paths));
		fUnreadablePaths = Set.copyOf(unreadablePaths);
		fFilePaths = Set.copyOf(filePaths);
	}

	@Override
	public void subscribe(Subscriber<? super Object> subscriber) {
		// Capacity for every result, so offer() never drops one.
		SubmissionPublisher<Object> sink = new SubmissionPublisher<>(DELIVERY_EXECUTOR,
				Math.max(1, fPaths.size()));
		AtomicBoolean cancelled = new AtomicBoolean();

		Publisher<Object> bridged = FlowAdapters.toPublisher(sink);
		bridged.subscribe(new Subscriber<Object>() {
			@Override
			public void onSubscribe(Subscription s) {
				subscriber.onSubscribe(new Subscription() {
					@Override
					public void request(long n) {
						s.request(n);
					}

					@Override
					public void cancel() {
						cancelled.set(true);
						s.cancel();
					}
				});
			}

			@Override
			public void onNext(Object item) {
				subscriber.onNext(item);
			}

			@Override
			public void onError(Throwable t) {
				subscriber.onError(t);
			}

			@Override
			public void onComplete() {
				subscriber.onComplete();
			}
		});

		// Submit only after the sink has its subscriber: a SubmissionPublisher
		// discards items offered while nobody is attached.
		Worker worker = new Worker(sink, cancelled);
		try {
			COMPUTE_EXECUTOR.execute(worker);
		} catch (RejectedExecutionException ex) {
			sink.closeExceptionally(new IllegalStateException("Too many content length requests; try again later"));
		}
	}

	/** Computes one path per run and re-queues itself until every path is done. */
	private final class Worker implements Runnable {
		private final SubmissionPublisher<Object> fSink;
		private final AtomicBoolean fCancelled;
		private int fNext;

		Worker(SubmissionPublisher<Object> sink, AtomicBoolean cancelled) {
			fSink = sink;
			fCancelled = cancelled;
		}

		@Override
		public void run() {
			while (true) {
				if (fCancelled.get() || fSink.isClosed()) {
					fSink.close();
					return;
				}
				if (fNext >= fPaths.size()) {
					fSink.close();
					return;
				}

				String path = fPaths.get(fNext++);
				fSink.offer(computeContentLength(path), (sub, item) -> false);

				if (fNext >= fPaths.size()) {
					fSink.close();
					return;
				}
				try {
					// Back to the tail of the queue so other subscriptions get a turn.
					COMPUTE_EXECUTOR.execute(this);
					return;
				} catch (RejectedExecutionException ex) {
					// The queue is full of other subscriptions; keep this worker
					// rather than drop a subscription that has already started.
				}
			}
		}
	}

	private Map<String, Object> computeContentLength(String path) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("path", path);
		data.put("size", null);
		data.put("fileCount", null);

		if (fSearchIndex == null || fUnreadablePaths.contains(path)) {
			return data;
		}
		try {
			SearchIndex.Query query = fSearchIndex.createQuery(toStatement(path), "jcr:xpath").setOffset(0).setLimit(0);
			if (fAuthorizables.length > 0) {
				query.setAuthorizables(fAuthorizables);
			}
			SearchIndex.QueryResult.FacetResult facetResult = query.execute().getFacetResult();
			if (facetResult == null) {
				return data;
			}
			// The statement declares one facet, so it is the only dimension.
			for (String dimension : facetResult.getDimensions()) {
				SearchIndex.QueryResult.FacetResult.Facet facet = facetResult.getFacet(dimension);
				if (facet == null) {
					continue;
				}
				Number sum = facet.getNumber("sum");
				Number count = facet.getNumber("count");
				if (sum == null || count == null || !Double.isFinite(sum.doubleValue())) {
					continue;
				}
				data.put("size", Long.valueOf(sum.longValue()));
				data.put("fileCount", Long.valueOf(count.longValue()));
				break;
			}
		} catch (Throwable ex) {
			CmsService.getLogger(ContentLengthPublisher.class).warn("Could not compute the content length of " + path, ex);
		}
		return data;
	}

	/** The index query totalling the files under {@code path}, or the file itself. */
	private String toStatement(String path) {
		String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
		String scope = trimmed.isEmpty() ? "" : JCR_ROOT + escapePath(trimmed);
		String nodes = fFilePaths.contains(path) ? "" : "//element(*, nt:file)";
		return scope + nodes + " facet accumulate stats(" + SIZE_PROPERTY + ")";
	}

	/**
	 * Backslash-escapes every ASCII character of {@code path} other than a
	 * letter, a digit or the '/' separator, so a name is always read as a name:
	 * braces are not expanded, and quotes, brackets, '|', '*' or words such as
	 * "order by" in a name take no meaning in the statement.
	 */
	private static String escapePath(String path) {
		StringBuilder buf = new StringBuilder();
		for (char c : path.toCharArray()) {
			if (c < 0x80 && c != '/' && !Character.isLetterOrDigit(c)) {
				buf.append('\\');
			}
			buf.append(c);
		}
		return buf.toString();
	}

	private static ThreadFactory newThreadFactory(String prefix) {
		return new ThreadFactory() {
			private final AtomicLong fCount = new AtomicLong();

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, prefix + fCount.incrementAndGet());
				t.setDaemon(true);
				return t;
			}
		};
	}
}
