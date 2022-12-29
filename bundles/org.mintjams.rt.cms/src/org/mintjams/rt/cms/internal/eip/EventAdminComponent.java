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

import org.apache.camel.CamelContext;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
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

	@Override
	protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
		EventAdminEndpoint endpoint = new EventAdminEndpoint(uri, remaining);
		setProperties(endpoint, parameters);
		return endpoint;
	}

	public class EventAdminEndpoint extends DefaultEndpoint {
		private static final String TOPIC = "topic";
		private static final String FILTER = "filter";

		private final String fTopic;
		private final Map<String, Object> fParameters = new HashMap<>();

		private EventAdminEndpoint(String endpointUri, String remaining) {
			super(endpointUri, EventAdminComponent.this);
			fTopic = remaining;
		}

		public void setFilter(String value) {
			fParameters.put(FILTER, value);
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

			public EventAdminConsumer(Processor processor) {
				super(EventAdminEndpoint.this, processor);
			}

			@Override
			public void handleEvent(org.osgi.service.event.Event event) {
				Exchange exchange = getEndpoint().createExchange();
				exchange.getIn().setBody(event);
				try {
					getProcessor().process(exchange);
					if (exchange.getException() != null) {
						getExceptionHandler().handleException("An error occurred while processing the exchange", exchange, exchange.getException());
					}
				} catch (Throwable ex) {
					getExceptionHandler().handleException("An error occurred while processing the exchange", exchange, ex);
				}
			}

			@Override
			protected void doStart() throws Exception {
				super.doStart();

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
				fCloser.close();
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
