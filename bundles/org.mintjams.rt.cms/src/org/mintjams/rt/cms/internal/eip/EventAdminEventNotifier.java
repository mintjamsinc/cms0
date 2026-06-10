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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.Service;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.commons.lang3.StringUtils;
import org.mintjams.rt.cms.internal.CmsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Camel {@link org.apache.camel.spi.EventNotifier} that bridges the entire
 * integration engine's runtime lifecycle to the OSGi <strong>EventAdmin</strong>
 * service for a single workspace &mdash; without any route authoring.
 *
 * <p>This is the Camel counterpart of the BPM (Camunda) EventAdmin bridge
 * ({@code org.mintjams.rt.cms.internal.bpm.event.BpmEventDispatcher}). Where the
 * BPM bridge republishes process-engine activity, this notifier republishes
 * {@link CamelEvent}s &mdash; CamelContext, Route, Exchange, Step and Service
 * lifecycle &mdash; so other bundles, EIP routes, GraphQL subscriptions and
 * server-side scripts can observe the engine through one consistent event
 * vocabulary.
 *
 * <h3>Why an EventNotifier (and not the Route DSL)</h3>
 * Camel fires lifecycle events internally and exposes them through the
 * {@code ManagementStrategy}'s notifier SPI. Registering a single notifier on
 * the context therefore gives <em>engine-wide, cross-cutting</em> coverage that
 * no route has to opt into &mdash; routes added or reloaded later are covered
 * automatically. The bridge is installed once in {@link WorkspaceCamelContext}.
 *
 * <h3>Topics</h3>
 * Topics follow the convention shared with the rest of the CMS: the
 * fully-qualified name of a stable Camel public-API type with {@code .} replaced
 * by {@code /}, suffixed with an upper-case action. The four roots are:
 * <pre>
 * org/apache/camel/CamelContext/&lt;ACTION&gt;
 * org/apache/camel/Route/&lt;ACTION&gt;
 * org/apache/camel/Exchange/&lt;ACTION&gt;   (also carries Step actions)
 * org/apache/camel/Service/&lt;ACTION&gt;
 * </pre>
 *
 * <h3>Properties</h3>
 * Every event carries {@code workspace} (the originating workspace name) and
 * {@code timestamp} (epoch millis). Optional attributes are <em>omitted when
 * null</em> rather than published as {@code null}, mirroring the BPM bridge.
 *
 * <h3>Delivery</h3>
 * Delivery uses EventAdmin's asynchronous {@code postEvent}, so it never blocks
 * the routes. Any delivery failure is isolated and logged so it can never
 * disturb the engine.
 *
 * <h3>What is published by default</h3>
 * CamelContext, Route and Service events are low-frequency and enabled by
 * default. Exchange and Step events fire <em>per message</em> and can be very
 * high volume, so they are disabled by default and must be switched on
 * explicitly (see {@link #setPublishExchangeEvents(boolean)} /
 * {@link #setPublishStepEvents(boolean)}). The translation itself is complete
 * for every event type; only the default delivery gate differs. Finer-grained
 * control (for example only {@code ExchangeFailed}) remains available through
 * the inherited {@code setIgnore*Event} setters of {@link EventNotifierSupport}.
 */
public class EventAdminEventNotifier extends EventNotifierSupport {

	private static final Logger LOG = LoggerFactory.getLogger(EventAdminEventNotifier.class);

	/** Property carrying the originating workspace name on every event. */
	public static final String PROPERTY_WORKSPACE = "workspace";
	/** Property carrying the event timestamp (epoch millis) on every event. */
	public static final String PROPERTY_TIMESTAMP = "timestamp";

	// -- Topic roots (stable Camel public-API types) --
	private static final String TOPIC_CAMEL_CONTEXT = CamelContext.class.getName().replace(".", "/");
	private static final String TOPIC_ROUTE = Route.class.getName().replace(".", "/");
	private static final String TOPIC_EXCHANGE = Exchange.class.getName().replace(".", "/");
	private static final String TOPIC_SERVICE = Service.class.getName().replace(".", "/");

	// -- Correlation header shared with ExchangeHistoryEventNotifier --
	private static final String HEADER_BUSINESS_KEY = "mi:history.businessKey";

	private static final int MESSAGE_MAX_LENGTH = 512;

	private final String fWorkspaceName;

	public EventAdminEventNotifier(String workspaceName) {
		fWorkspaceName = workspaceName;

		// Engine lifecycle (context / routes / services) is low-frequency and
		// valuable, so it is published out of the box. Per-message exchange and
		// step events can be extremely high volume; they are fully supported but
		// gated off by default and switched on explicitly when needed.
		setIgnoreExchangeEvents(true);
		setIgnoreStepEvents(true);
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	// ------------------------------------------------------------------
	// Configuration
	// ------------------------------------------------------------------

	/**
	 * Enables or disables publication of per-message exchange events
	 * (created/completed/failed/sending/sent/redelivery/failure-handling/...).
	 * Disabled by default. Must be set before the context starts.
	 */
	public EventAdminEventNotifier setPublishExchangeEvents(boolean enabled) {
		setIgnoreExchangeEvents(!enabled);
		return this;
	}

	/**
	 * Enables or disables publication of per-message step events
	 * (step started/completed/failed). Disabled by default. Must be set before
	 * the context starts.
	 */
	public EventAdminEventNotifier setPublishStepEvents(boolean enabled) {
		setIgnoreStepEvents(!enabled);
		return this;
	}

	// ------------------------------------------------------------------
	// EventNotifier SPI
	// ------------------------------------------------------------------

	@Override
	public void notify(CamelEvent event) throws Exception {
		if (event == null) {
			return;
		}

		Class<?> type;
		String action;
		Map<String, Object> properties = newProperties(event);

		if (event instanceof CamelEvent.ExchangeEvent) {
			// StepEvent is an ExchangeEvent too — disambiguate first.
			if (event instanceof CamelEvent.StepEvent) {
				type = Exchange.class;
				action = stepAction(event);
				populateStep((CamelEvent.StepEvent) event, properties);
			} else {
				type = Exchange.class;
				action = exchangeAction(event);
				populateExchange((CamelEvent.ExchangeEvent) event, properties);
			}
		} else if (event instanceof CamelEvent.RouteEvent) {
			type = Route.class;
			action = routeAction(event);
			populateRoute((CamelEvent.RouteEvent) event, properties);
		} else if (event instanceof CamelEvent.CamelContextEvent) {
			type = CamelContext.class;
			action = contextAction(event);
			populateContext((CamelEvent.CamelContextEvent) event, properties);
		} else if (event instanceof CamelEvent.ServiceEvent) {
			type = Service.class;
			action = serviceAction(event);
			populateService((CamelEvent.ServiceEvent) event, properties);
		} else {
			// Custom / unknown events: still bridge them generically so the
			// vocabulary stays open rather than silently dropping anything.
			type = CamelEvent.class;
			action = upper(event.getType());
		}

		if (action == null) {
			return;
		}
		post(topic(type, action), properties);
	}

	// ------------------------------------------------------------------
	// Action resolution (CamelEvent.Type -> UPPER_SNAKE action)
	// ------------------------------------------------------------------

	private String contextAction(CamelEvent event) {
		switch (event.getType()) {
		case CamelContextInitializing: return "INITIALIZING";
		case CamelContextInitialized: return "INITIALIZED";
		case CamelContextStarting: return "STARTING";
		case CamelContextStarted: return "STARTED";
		case CamelContextStartupFailure: return "STARTUP_FAILURE";
		case CamelContextStopping: return "STOPPING";
		case CamelContextStopped: return "STOPPED";
		case CamelContextStopFailure: return "STOP_FAILURE";
		case CamelContextSuspending: return "SUSPENDING";
		case CamelContextSuspended: return "SUSPENDED";
		case CamelContextResuming: return "RESUMING";
		case CamelContextResumed: return "RESUMED";
		case CamelContextResumeFailure: return "RESUME_FAILURE";
		case CamelContextReloading: return "RELOADING";
		case CamelContextReloaded: return "RELOADED";
		case CamelContextReloadFailure: return "RELOAD_FAILURE";
		case RoutesStarting: return "ROUTES_STARTING";
		case RoutesStarted: return "ROUTES_STARTED";
		case RoutesStopping: return "ROUTES_STOPPING";
		case RoutesStopped: return "ROUTES_STOPPED";
		default: return upper(event.getType());
		}
	}

	private String routeAction(CamelEvent event) {
		switch (event.getType()) {
		case RouteAdded: return "ADDED";
		case RouteRemoved: return "REMOVED";
		case RouteStarting: return "STARTING";
		case RouteStarted: return "STARTED";
		case RouteStopping: return "STOPPING";
		case RouteStopped: return "STOPPED";
		case RouteReloaded: return "RELOADED";
		case RouteRestarting: return "RESTARTING";
		case RouteRestartingFailure: return "RESTARTING_FAILURE";
		default: return upper(event.getType());
		}
	}

	private String exchangeAction(CamelEvent event) {
		switch (event.getType()) {
		case ExchangeCreated: return "CREATED";
		case ExchangeCompleted: return "COMPLETED";
		case ExchangeFailed: return "FAILED";
		case ExchangeFailureHandling: return "FAILURE_HANDLING";
		case ExchangeFailureHandled: return "FAILURE_HANDLED";
		case ExchangeRedelivery: return "REDELIVERY";
		case ExchangeSending: return "SENDING";
		case ExchangeSent: return "SENT";
		case ExchangeAsyncProcessingStarted: return "ASYNC_PROCESSING_STARTED";
		default: return upper(event.getType());
		}
	}

	private String stepAction(CamelEvent event) {
		switch (event.getType()) {
		case StepStarted: return "STEP_STARTED";
		case StepCompleted: return "STEP_COMPLETED";
		case StepFailed: return "STEP_FAILED";
		default: return upper(event.getType());
		}
	}

	private String serviceAction(CamelEvent event) {
		switch (event.getType()) {
		case ServiceStartupFailure: return "STARTUP_FAILURE";
		case ServiceStopFailure: return "STOP_FAILURE";
		default: return upper(event.getType());
		}
	}

	// ------------------------------------------------------------------
	// Property population
	// ------------------------------------------------------------------

	private void populateContext(CamelEvent.CamelContextEvent event, Map<String, Object> properties) {
		CamelContext context = event.getContext();
		if (context != null) {
			put(properties, "camelContextName", context.getName());
			put(properties, "camelContextManagementName", context.getManagementName());
		}
		putCause(properties, cause(event));
	}

	private void populateRoute(CamelEvent.RouteEvent event, Map<String, Object> properties) {
		Route route = event.getRoute();
		if (route != null) {
			put(properties, "routeId", route.getRouteId());
			put(properties, "routeDescription", route.getDescription());
			Endpoint endpoint = route.getEndpoint();
			if (endpoint != null) {
				put(properties, "endpointUri", endpoint.getEndpointUri());
			}
			if (route.getCamelContext() != null) {
				put(properties, "camelContextName", route.getCamelContext().getName());
			}
		}

		if (event instanceof CamelEvent.RouteReloadedEvent) {
			CamelEvent.RouteReloadedEvent reloaded = (CamelEvent.RouteReloadedEvent) event;
			put(properties, "index", reloaded.getIndex());
			put(properties, "total", reloaded.getTotal());
		} else if (event instanceof CamelEvent.RouteRestartingFailureEvent) {
			CamelEvent.RouteRestartingFailureEvent failure = (CamelEvent.RouteRestartingFailureEvent) event;
			put(properties, "attempt", failure.getAttempt());
			put(properties, "exhausted", failure.isExhausted());
		}
		putCause(properties, cause(event));
	}

	private void populateExchange(CamelEvent.ExchangeEvent event, Map<String, Object> properties) {
		Exchange exchange = event.getExchange();
		populateExchangeCommon(exchange, properties);

		if (event instanceof CamelEvent.ExchangeSendingEvent) {
			Endpoint endpoint = ((CamelEvent.ExchangeSendingEvent) event).getEndpoint();
			if (endpoint != null) {
				put(properties, "endpointUri", endpoint.getEndpointUri());
			}
		} else if (event instanceof CamelEvent.ExchangeSentEvent) {
			CamelEvent.ExchangeSentEvent sent = (CamelEvent.ExchangeSentEvent) event;
			if (sent.getEndpoint() != null) {
				put(properties, "endpointUri", sent.getEndpoint().getEndpointUri());
			}
			put(properties, "timeTaken", sent.getTimeTaken());
		} else if (event instanceof CamelEvent.ExchangeRedeliveryEvent) {
			put(properties, "attempt", ((CamelEvent.ExchangeRedeliveryEvent) event).getAttempt());
		}

		// Failure details: for ExchangeFailed the cause rides the event; for the
		// failure-handling/handled pair it lives on the exchange properties.
		Throwable cause = cause(event);
		if (cause == null && exchange != null) {
			cause = exchange.getException();
			if (cause == null) {
				cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
			}
		}
		putCause(properties, cause);

		if (exchange != null) {
			put(properties, "failureRouteId", exchange.getProperty(Exchange.FAILURE_ROUTE_ID, String.class));
			put(properties, "failureEndpoint", exchange.getProperty(Exchange.FAILURE_ENDPOINT, String.class));
		}
	}

	private void populateStep(CamelEvent.StepEvent event, Map<String, Object> properties) {
		populateExchangeCommon(event.getExchange(), properties);
		put(properties, "stepId", event.getStepId());
		putCause(properties, cause(event));
	}

	private void populateExchangeCommon(Exchange exchange, Map<String, Object> properties) {
		if (exchange == null) {
			return;
		}
		put(properties, "exchangeId", exchange.getExchangeId());
		put(properties, "routeId", exchange.getFromRouteId());
		if (exchange.getFromEndpoint() != null) {
			put(properties, "fromEndpoint", exchange.getFromEndpoint().getEndpointUri());
		}
		if (exchange.getContext() != null) {
			put(properties, "camelContextName", exchange.getContext().getName());
		}
		put(properties, "businessKey", exchange.getIn().getHeader(HEADER_BUSINESS_KEY, String.class));
	}

	private void populateService(CamelEvent.ServiceEvent event, Map<String, Object> properties) {
		Object service = event.getService();
		if (service != null) {
			put(properties, "serviceType", service.getClass().getName());
		}
		putCause(properties, cause(event));
	}

	// ------------------------------------------------------------------
	// Helpers (mirroring BpmEventDispatcher conventions)
	// ------------------------------------------------------------------

	/**
	 * Builds the EventAdmin topic for the given type and action, mirroring the
	 * {@code Class.getName().replace(".", "/") + "/" + action} convention used by
	 * the BPM bridge and the existing CMS deployment events.
	 */
	private String topic(Class<?> type, String action) {
		return type.getName().replace(".", "/") + "/" + action;
	}

	/**
	 * Returns a fresh, mutable property map pre-populated with the workspace name
	 * and the event timestamp.
	 */
	private Map<String, Object> newProperties(CamelEvent event) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(PROPERTY_WORKSPACE, fWorkspaceName);
		long ts = event.getTimestamp();
		properties.put(PROPERTY_TIMESTAMP, ts > 0 ? ts : System.currentTimeMillis());
		return properties;
	}

	/**
	 * Adds a property only when the value is non-null, so optional attributes are
	 * simply absent rather than stored as {@code null}.
	 */
	private static void put(Map<String, Object> properties, String key, Object value) {
		if (value != null) {
			properties.put(key, value);
		}
	}

	private void putCause(Map<String, Object> properties, Throwable cause) {
		if (cause != null) {
			put(properties, "causeType", cause.getClass().getName());
			put(properties, "causeMessage", StringUtils.truncate(cause.getMessage(), MESSAGE_MAX_LENGTH));
		}
	}

	private Throwable cause(CamelEvent event) {
		return (event instanceof CamelEvent.FailureEvent) ? ((CamelEvent.FailureEvent) event).getCause() : null;
	}

	private String upper(CamelEvent.Type type) {
		return (type != null) ? type.name().toUpperCase() : null;
	}

	/**
	 * Posts the event to EventAdmin, isolating any delivery failure so it can
	 * never disturb the engine.
	 */
	private void post(String topic, Map<String, Object> properties) {
		try {
			CmsService.postEvent(topic, properties);
		} catch (Throwable ex) {
			LOG.error("Failed to post Camel event to topic: " + topic, ex);
		}
	}

}
