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

package org.mintjams.rt.cms.internal.eip;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;
import java.util.Arrays;

import org.apache.camel.CamelContext;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.MultipleConsumersSupport;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.builder.ThreadPoolBuilder;
import org.apache.camel.support.CamelContextHelper;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.osgi.BundleLocalization;
import org.mintjams.tools.osgi.Registration;
import org.osgi.framework.Constants;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

public class EventAdminComponent extends DefaultComponent {

	public static final String COMPONENT_NAME = "eventadmin";

	private final String fWorkspaceName;

	public EventAdminComponent(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@Override
	protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
		EventAdminEndpoint endpoint = new EventAdminEndpoint(uri, remaining);
		setProperties(endpoint, parameters);
		return endpoint;
	}

	private enum RejectedPolicy {
		CallerRuns(ThreadPoolExecutor.CallerRunsPolicy::new),
		Abort(ThreadPoolExecutor.AbortPolicy::new),
		Discard(ThreadPoolExecutor.DiscardPolicy::new),
		DiscardOldest(ThreadPoolExecutor.DiscardOldestPolicy::new);

		private final Supplier<RejectedExecutionHandler> fHandler;

		private RejectedPolicy(Supplier<RejectedExecutionHandler> handler) {
			fHandler = handler;
		}

		private RejectedExecutionHandler newHandler() {
			return fHandler.get();
		}
	}

	public class EventAdminEndpoint extends DefaultEndpoint implements MultipleConsumersSupport {
		private static final String TOPIC = "topic";
		private static final String FILTER = "filter";

		private final String fTopic;
		private final Map<String, Object> fParameters = new HashMap<>();
		private int fPoolSize = 1;
		private Integer fMaxPoolSize;
		private int fMaxQueueSize = 1000;
		private RejectedPolicy fRejectedPolicy = RejectedPolicy.CallerRuns;
		private String fWorkspace;

		private EventAdminEndpoint(String endpointUri, String remaining) {
			super(endpointUri, EventAdminComponent.this);
			fTopic = remaining;
		}

		public void setFilter(String value) {
			fParameters.put(FILTER, value);
		}

		public void setPoolSize(int value) {
			fPoolSize = value;
		}

		public void setMaxPoolSize(int value) {
			fMaxPoolSize = value;
		}

		public void setMaxQueueSize(int value) {
			fMaxQueueSize = value;
		}

		public void setRejectedPolicy(String value) {
			String name = (value == null) ? "" : value.trim();
			for (RejectedPolicy policy : RejectedPolicy.values()) {
				if (policy.name().equalsIgnoreCase(name)) {
					fRejectedPolicy = policy;
					return;
				}
			}
			throw new IllegalArgumentException("Unknown rejectedPolicy: " + value
					+ ". Available policies are: CallerRuns, Abort, Discard, DiscardOldest.");
		}

		public void setWorkspace(String workspace) {
			fWorkspace = workspace;
		}

		/**
		 * EventAdmin delivers every event to every handler registered for its
		 * topic, and each consumer registers a handler and an executor of its own,
		 * so any number of routes can subscribe to the same topic and filter.
		 * Camel refuses a second consumer on one endpoint unless the endpoint says
		 * so here; without it, two routes reacting to the same event (for example
		 * org/mintjams/cms/Workspace/STARTED) could not both start.
		 */
		@Override
		public boolean isMultipleConsumersSupported() {
			return true;
		}

		@Override
		public Consumer createConsumer(Processor processor) throws Exception {
			EventAdminConsumer consumer = new EventAdminConsumer(processor);
			configureConsumer(consumer);
			return consumer;
		}

		@Override
		public Producer createProducer() throws Exception {
			return new EventAdminProducer();
		}

		private class EventAdminConsumer extends DefaultConsumer implements EventHandler {
			private final Closer fCloser = Closer.create();
			private ExecutorService fExecutorService;

			public EventAdminConsumer(Processor processor) {
				super(EventAdminEndpoint.this, processor);
			}

			@Override
			public void handleEvent(org.osgi.service.event.Event event) {
				if (event.getProperty("workspace") != null) {
					String ws = ((String) event.getProperty("workspace")).trim();
					if (fWorkspace == null) {
						if (!fWorkspaceName.equals(ws)) {
							// Ignore events from other workspaces
							return;
						}
					} else {
						String fw = fWorkspace.trim();
						if (fw.equals("*")) {
							// Accept all workspaces
						} else {
							// Treat fWorkspace as comma-separated list; if workspace not included, ignore
							boolean matched = Arrays.stream(fw.split(","))
								.map(String::trim)
								.anyMatch(s -> s.equals(ws));
							if (!matched) {
								return;
							}
						}
					}
				}

				fExecutorService.submit(() -> {
					if (!isRunAllowed()) {
						return;
					}

					Exchange exchange = getEndpoint().createExchange();
					exchange.getIn().setBody(event);
					try {
						getProcessor().process(exchange);
						if (exchange.getException() != null) {
							getExceptionHandler().handleException("An error occurred while processing the exchange", exchange, exchange.getException());
						}
					} catch (Throwable ex) {
						exchange.setException(ex);
						getExceptionHandler().handleException("An error occurred while processing the exchange", exchange, ex);
					}
				});
			}

			@Override
			protected void doStart() throws Exception {
				super.doStart();
				fExecutorService = new ThreadPoolBuilder(getEndpoint().getCamelContext())
						.poolSize(fPoolSize)
						// An unset maxPoolSize follows poolSize, so poolSize alone is enough to size the pool.
						.maxPoolSize((fMaxPoolSize != null) ? fMaxPoolSize : fPoolSize)
						.maxQueueSize(fMaxQueueSize)
						.build(this, EventAdminConsumer.class.getSimpleName());
				// ThreadPoolBuilder only takes Camel's ThreadPoolRejectedPolicy, which in Camel 4 has
				// no Discard or DiscardOldest, so the handler goes on the executor Camel built.
				((ThreadPoolExecutor) fExecutorService).setRejectedExecutionHandler(fRejectedPolicy.newHandler());

				Registration.Builder<EventHandler> builder = Registration.newBuilder(EventHandler.class)
						.setService(this)
						.setProperty(EventConstants.EVENT_TOPIC, fTopic)
						.setProperty(Constants.SERVICE_DESCRIPTION, "eip:" + getEndpointUri())
						.setProperty(Constants.SERVICE_VENDOR, BundleLocalization.create(CmsService.getDefault().getBundle()).getVendor())
						.setBundleContext(CmsService.getDefault().getBundleContext());
				if (fParameters.containsKey(FILTER)) {
					builder.setProperty(EventConstants.EVENT_FILTER, (String) fParameters.get(FILTER));
				}
				fCloser.add(builder.build());
			}

			@Override
			protected void doStop() throws Exception {
				try {
					fCloser.close();
				} finally {
					// Camel shuts pools down on its own only when the whole context stops,
					// and doStart creates a new one on every start of the route.
					getEndpoint().getCamelContext().getExecutorServiceManager().shutdown(fExecutorService);
				}
				super.doStop();
			}
		}

		private class EventAdminProducer extends DefaultProducer {
			private EventAdminProducer() {
				super(EventAdminEndpoint.this);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				CmsService.postEvent(getEvent(exchange));
			}

			private org.osgi.service.event.Event getEvent(Exchange exchange) {
				Message in = exchange.getIn();
				CamelContext context = getEndpoint().getCamelContext();
				org.osgi.service.event.Event event = context.getTypeConverter().convertTo(org.osgi.service.event.Event.class, exchange, in.getBody());
				if (event == null) {
					event = new org.osgi.service.event.Event(getTopic(exchange), getProperties(exchange));
				}
				return event;
			}

			private String getTopic(Exchange exchange) {
				Message in = exchange.getIn();
				String topic = in.getHeader(TOPIC, String.class);
				if (topic == null) {
					topic = fTopic;
				}
				return topic;
			}

			private Map<String, Object> getProperties(Exchange exchange) {
				Message in = exchange.getIn();
				CamelContext context = getEndpoint().getCamelContext();
				Map<?, ?> bodyAsMap = context.getTypeConverter().convertTo(Map.class, exchange, in.getBody());
				Map<String, Object> properties = new HashMap<>();
				if (bodyAsMap == null) {
					return properties;
				}

				for (Entry<?, ?> entry : bodyAsMap.entrySet()) {
					String key = CamelContextHelper.convertTo(context, String.class, entry.getKey());
					if (key == null) {
						continue;
					}
					properties.put(key, entry.getValue());
				}
				return properties;
			}
		}
	}
}