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

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.cms.event.CmsEvent;
import org.mintjams.rt.cms.internal.cms.event.CmsEventHandler;
import org.mintjams.rt.cms.internal.cms.event.WorkspaceCmsEventManager;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Bridges the workspace {@code CmsEvent} feed (the JCR-node / workspace event
 * stream behind {@link WorkspaceCmsEventManager}) to a reactive-streams
 * {@link Publisher} for one GraphQL subscription. A {@code Subscription} field's
 * {@code DataFetcher} returns one of these; graphql-java's
 * {@code SubscriptionExecutionStrategy} subscribes once and maps each emitted
 * payload through the field's selection set.
 *
 * <p>Design (mirrors the handmade {@code GraphQLStreamHandler}'s transport, with
 * a reactive backbone):
 * <ul>
 *   <li><b>Filter then map.</b> Each incoming {@code CmsEvent} is tested by a
 *       cheap predicate (e.g. {@code JobNodes.isJobPath}); matches are turned
 *       into a payload by {@link EventMapper} (which may read JCR). A {@code null}
 *       payload (or a predicate miss) skips the event.</li>
 *   <li><b>Dedicated executor.</b> Delivery runs on a daemon pool, never on the
 *       OSGi EventAdmin dispatch thread that calls {@code handleEvent}.</li>
 *   <li><b>Bounded buffer, drop-on-lag.</b> A slow subscriber causes the newest
 *       event to be dropped rather than blocking the producer — progress-style
 *       events conflate naturally (the next snapshot supersedes).</li>
 *   <li><b>Self-cleaning.</b> A downstream {@code cancel()} / {@code onComplete} /
 *       {@code onError} deregisters the {@code CmsEventHandler} and closes the
 *       sink, so a closed SSE stream leaves no listener behind.</li>
 * </ul>
 */
public final class CmsEventPublisher implements Publisher<Object> {

	/** Maps a matched {@link CmsEvent} to the per-event subscription value; {@code null} skips it. */
	@FunctionalInterface
	public interface EventMapper {
		Object map(CmsEvent event) throws Exception;
	}

	/** Newest event is dropped past this depth (events are conflatable). */
	private static final int BUFFER_CAPACITY = 256;

	private static final Executor DELIVERY_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
		private final AtomicLong fCount = new AtomicLong();

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "graphql-subscription-" + fCount.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	});

	private final String fWorkspaceName;
	private final Predicate<CmsEvent> fFilter;
	private final EventMapper fMapper;
	private final Callable<Object> fInitialPayload;

	public CmsEventPublisher(String workspaceName, Predicate<CmsEvent> filter, EventMapper mapper) {
		this(workspaceName, filter, mapper, null);
	}

	/**
	 * @param initialPayload evaluated once per subscriber right after the live
	 *        feed is attached, and emitted as the stream's first item ({@code null}
	 *        result skips it). Use this for state-snapshot subscriptions (e.g. job
	 *        progress): a worker that finished before the subscribe handshake
	 *        completed has already emitted its events into the void, and without a
	 *        snapshot the subscriber would wait forever for a terminal event.
	 */
	public CmsEventPublisher(String workspaceName, Predicate<CmsEvent> filter, EventMapper mapper,
			Callable<Object> initialPayload) {
		fWorkspaceName = workspaceName;
		fFilter = filter;
		fMapper = mapper;
		fInitialPayload = initialPayload;
	}

	@Override
	public void subscribe(Subscriber<? super Object> subscriber) {
		WorkspaceCmsEventManager eventManager = CmsService.getWorkspaceCmsEventManager(fWorkspaceName);
		if (eventManager == null) {
			subscriber.onSubscribe(new Subscription() {
				@Override
				public void request(long n) {}

				@Override
				public void cancel() {}
			});
			subscriber.onError(new IllegalStateException("No event manager for workspace: " + fWorkspaceName));
			return;
		}

		SubmissionPublisher<Object> sink = new SubmissionPublisher<>(DELIVERY_EXECUTOR, BUFFER_CAPACITY);
		AtomicBoolean cleaned = new AtomicBoolean();

		CmsEventHandler handler = new CmsEventHandler() {
			@Override
			public void handleEvent(CmsEvent event) {
				if (sink.isClosed()) {
					return;
				}
				try {
					if (fFilter != null && !fFilter.test(event)) {
						return;
					}
					Object payload = fMapper.map(event);
					if (payload != null) {
						// Non-blocking: drop the newest item when the subscriber lags.
						sink.offer(payload, (sub, item) -> false);
					}
				} catch (Throwable ignore) {
					// A single bad event must not tear the stream down.
				}
			}
		};
		eventManager.addEventHandler(handler);

		Runnable cleanup = () -> {
			if (cleaned.compareAndSet(false, true)) {
				try {
					eventManager.removeEventHandler(handler);
				} catch (Throwable ignore) {}
				try {
					sink.close();
				} catch (Throwable ignore) {}
			}
		};

		// Bridge the JDK Flow sink to reactive-streams and wrap the Subscription so
		// any terminal downstream signal releases the CmsEventHandler and sink.
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
						try {
							s.cancel();
						} finally {
							cleanup.run();
						}
					}
				});
			}

			@Override
			public void onNext(Object item) {
				subscriber.onNext(item);
			}

			@Override
			public void onError(Throwable t) {
				try {
					cleanup.run();
				} finally {
					subscriber.onError(t);
				}
			}

			@Override
			public void onComplete() {
				try {
					cleanup.run();
				} finally {
					subscriber.onComplete();
				}
			}
		});

		// Emit the initial snapshot only after the sink has its subscriber — a
		// SubmissionPublisher discards items offered while nobody is attached.
		// Snapshot payloads are absolute state, so an overlap with a concurrent
		// live event is harmless whichever lands first.
		if (fInitialPayload != null && !sink.isClosed()) {
			try {
				Object payload = fInitialPayload.call();
				if (payload != null) {
					sink.offer(payload, (sub, item) -> false);
				}
			} catch (Throwable ignore) {
				// The live feed still serves the subscriber.
			}
		}
	}
}
