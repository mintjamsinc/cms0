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

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.mintjams.rt.cms.internal.CmsService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Bridges raw OSGi EventAdmin topics to a reactive-streams {@link Publisher} for
 * one GraphQL subscription. This is the sibling of {@link CmsEventPublisher} for
 * event sources that publish to EventAdmin <em>topics</em> rather than through
 * the JCR-scoped {@code WorkspaceCmsEventManager} — e.g. the BPM (Camunda) task /
 * process lifecycle events on {@code org/camunda/bpm/engine/...} topics.
 *
 * <p>On subscribe it registers a short-lived {@link EventHandler} service for the
 * given topic patterns, filters each {@link Event} (cheaply, by workspace and the
 * subscription's arguments) and maps matches to a payload; a downstream
 * cancel/complete/error unregisters the service and releases the sink. Delivery
 * uses a dedicated daemon executor with a bounded buffer that drops on lag — the
 * same backpressure backbone as {@link CmsEventPublisher}.
 */
public final class OsgiEventPublisher implements Publisher<Object> {

	/** Maps a matched {@link Event} to the per-event subscription value; {@code null} skips it. */
	@FunctionalInterface
	public interface EventMapper {
		Object map(Event event) throws Exception;
	}

	private static final int BUFFER_CAPACITY = 256;

	private static final Executor DELIVERY_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
		private final AtomicLong fCount = new AtomicLong();

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "pgraphql-osgi-subscription-" + fCount.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	});

	private final String[] fTopics;
	private final Predicate<Event> fFilter;
	private final EventMapper fMapper;

	public OsgiEventPublisher(String[] topics, Predicate<Event> filter, EventMapper mapper) {
		fTopics = topics;
		fFilter = filter;
		fMapper = mapper;
	}

	@Override
	public void subscribe(Subscriber<? super Object> subscriber) {
		BundleContext bundleContext = CmsService.getDefault().getBundleContext();
		if (bundleContext == null) {
			subscriber.onSubscribe(new Subscription() {
				@Override
				public void request(long n) {}

				@Override
				public void cancel() {}
			});
			subscriber.onError(new IllegalStateException("No bundle context is available"));
			return;
		}

		SubmissionPublisher<Object> sink = new SubmissionPublisher<>(DELIVERY_EXECUTOR, BUFFER_CAPACITY);
		AtomicBoolean cleaned = new AtomicBoolean();

		EventHandler handler = new EventHandler() {
			@Override
			public void handleEvent(Event event) {
				if (sink.isClosed()) {
					return;
				}
				try {
					if (fFilter != null && !fFilter.test(event)) {
						return;
					}
					Object payload = fMapper.map(event);
					if (payload != null) {
						sink.offer(payload, (sub, item) -> false);
					}
				} catch (Throwable ignore) {
					// A single bad event must not tear the stream down.
				}
			}
		};

		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put(EventConstants.EVENT_TOPIC, fTopics);
		ServiceRegistration<EventHandler> registration =
				bundleContext.registerService(EventHandler.class, handler, properties);

		Runnable cleanup = () -> {
			if (cleaned.compareAndSet(false, true)) {
				try {
					registration.unregister();
				} catch (Throwable ignore) {}
				try {
					sink.close();
				} catch (Throwable ignore) {}
			}
		};

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
	}
}
