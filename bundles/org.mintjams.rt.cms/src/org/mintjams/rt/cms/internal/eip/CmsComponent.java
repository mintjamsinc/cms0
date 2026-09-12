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

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.StringReader;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.lock.Lock;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import javax.jcr.version.Version;

import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.spi.Synchronization;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.security.ServiceUserCredentials;
import org.mintjams.script.resource.Resource;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Strings;

/**
 * Repository operations as route nodes: {@code cms:<operation>?<options>}.
 * <p>
 * The operation is whatever follows the scheme, and an operation this component
 * does not know is read as the path of a script to evaluate — which is what the
 * component originally did, and what keeps every route written before the
 * operations existed working unchanged. See {@code CmsEndpoint#createProducer()}
 * for the full list; in outline:
 * <ul>
 * <li>session lifecycle — {@code login}, {@code commit}, {@code rollback},
 * {@code logout}</li>
 * <li>reading — {@code load} (deprecated), {@code loadAsString},
 * {@code loadAsYaml}, {@code loadAsJson}, {@code getProperties},
 * {@code exists}, {@code query}, {@code list}</li>
 * <li>writing — {@code createFolder}, {@code store}, {@code setProperties},
 * {@code move}, {@code remove}</li>
 * <li>versioning — {@code addVersionControl}, {@code checkout},
 * {@code checkin}, {@code uncheckout}, {@code checkpoint}</li>
 * <li>locking — {@code lock}, {@code unlock}</li>
 * <li>scripting — {@code script}, whose {@code path} option names the script, for
 * the route that computes which one to run</li>
 * <li>anything else — the script at that path</li>
 * </ul>
 * <p>
 * Two things are common to every operation.
 * <p>
 * <strong>Which session the node works in.</strong> A node either joins the
 * session {@code cms:login} opened — in which case it neither saves nor closes,
 * and {@code cms:commit} decides when its work becomes durable — or it opens a
 * session of its own, saves through it and closes it when the node is done,
 * exactly as every node did before the lifecycle operations existed. Which of
 * the two happens is decided by {@link #CMS_CONTEXT}, and the fallback to
 * owning a session is what lets a route be converted one node at a time.
 * <p>
 * <strong>How results leave the node.</strong> Nothing is written to a fixed
 * header. Each operation publishes named result sources and the route says
 * where each one goes — {@code @body=path}, {@code @header.staleCount=count},
 * {@code @property.hasMore=hasMore} — so an operation whose result nobody binds
 * produces no output at all. The exceptions are {@code cms:getProperties} and
 * {@code cms:script}, which map repository properties and script variables onto
 * headers by their own filter syntax.
 * <p>
 * Sessions opened by {@code cms:login} are tracked for the lifetime of the
 * component, because a JCR session holds one of a hard-capped pool of slots and
 * a leaked one is never returned — see {@link CmsSessionReaper}.
 * 
 * @deprecated Use <groovy/> script in EIP routes instead. The component is not going away, but the operations are not being maintained and will eventually be removed.
 */
public class CmsComponent extends DefaultComponent {

	public static final String COMPONENT_NAME = "cms";

	/**
	 * The option name every operation reads to join the session {@code cms:login}
	 * opened, instead of opening one of its own. Its value is the <em>name of the
	 * header</em> holding that session, not the session itself.
	 */
	public static final String CMS_CONTEXT = "context";

	/**
	 * The header the option names when it is not written.
	 * <p>
	 * Almost every route publishes the session as {@code @header.cmsContext=context}
	 * and then joins it from every node, so writing the option out said the same
	 * thing 126 times in one application. The default carries that convention, and
	 * naming the option is for the route that juggles two sessions at once.
	 * <p>
	 * The default is deliberately soft: a node whose default header holds no session
	 * owns its session exactly as before. Only a node that <em>names</em> a header is
	 * refused when the header holds no session, because there the author said which
	 * session to join and was wrong. Were the default hard, every node that means to
	 * own its session — a health counter that must survive the caller's rollback, for
	 * one — would start failing the moment a session existed elsewhere on the route.
	 */
	public static final String CMS_CONTEXT_DEFAULT = "cmsContext";

	/** Grace period before the reaper closes a session whose unit of work has finished. */
	private static final long REAPER_GRACE_MILLIS = 30_000L;

	/** How often the reaper looks for abandoned sessions. */
	private static final long REAPER_PERIOD_SECONDS = 10L;

	private final String fWorkspaceName;

	/**
	 * Every session opened by {@code cms:login} that has not yet been closed.
	 * <p>
	 * A JCR session holds one of a hard-capped pool of slots, so a leak eventually
	 * takes the whole workspace down. Ownership is recorded here rather than on the
	 * session object so that the reaper can tell an abandoned session from a
	 * long-running batch — see {@link CmsSessionReaper}.
	 */
	private final Map<WorkspaceScriptContext, CmsSessionHandle> fSessions = new ConcurrentHashMap<>();

	/** Timer thread the session reaper runs on, one per workspace. */
	private ScheduledExecutorService fScheduler;
	private ScheduledFuture<?> fReaper;

	public CmsComponent(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@Override
	protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
		// Parse operation type from remaining (e.g., "store", "setProperties", "move")
		String operation = remaining;

		CmsEndpoint endpoint = new CmsEndpoint(uri, operation, parameters);
		// Taken: everything here is consumed internally by CmsEndpoint, and leaving the map
		// non-empty would make Camel reject all of it.
		parameters.clear();
		return endpoint;
	}

	@Override
	protected void doStart() throws Exception {
		super.doStart();
		fScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "cms-eip-session-reaper-" + fWorkspaceName);
			thread.setDaemon(true);
			return thread;
		});
		fReaper = fScheduler.scheduleWithFixedDelay(new CmsSessionReaper(), REAPER_PERIOD_SECONDS,
				REAPER_PERIOD_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	protected void doStop() throws Exception {
		if (fReaper != null) {
			fReaper.cancel(false);
			fReaper = null;
		}
		if (fScheduler != null) {
			fScheduler.shutdownNow();
			fScheduler = null;
		}

		// E3: the component is going away, so nothing can reach these sessions any
		// more. Whatever they still hold is unreachable work, not work in progress.
		for (CmsSessionHandle handle : new ArrayList<>(fSessions.values())) {
			closeSession(handle, "the cms: component is stopping");
		}
		fSessions.clear();

		super.doStop();
	}

	/**
	 * Closes a route-owned session and removes it from the registry. Idempotent:
	 * {@code WorkspaceScriptContext.close()} discards anything unsaved and then
	 * guards on {@code isLive()}, so the normal close in {@code doFinally}, the
	 * {@code onCompletion} backstop and the reaper may all run without harm.
	 */
	private void closeSession(CmsSessionHandle handle, String reason) {
		if (handle == null) {
			return;
		}
		fSessions.remove(handle.getContext());
		if (handle.isClosed()) {
			return;
		}
		handle.markClosed();
		try {
			handle.getContext().close();
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Failed to close the JCR session opened by cms:login (" + reason
					+ "): " + handle.describe(), ex);
		}
	}

	/**
	 * Bookkeeping for one session opened by {@code cms:login}.
	 * <p>
	 * A JCR session is bound to the thread that opened it and the workspace holds a
	 * single JDBC connection, so the owning thread is recorded in order to refuse a
	 * cross-thread join loudly rather than corrupt the transaction. The unit-of-work
	 * flag is the only observable evidence that the route has left the scope:
	 * pooled threads stay alive and move on to the next exchange, so thread
	 * liveness alone says nothing.
	 */
	private static final class CmsSessionHandle {
		private final WorkspaceScriptContext fContext;
		private final Thread fOwnerThread;
		private final String fRunAs;
		private final String fRouteId;
		private volatile boolean fUnitOfWorkDone;
		private volatile long fUnitOfWorkDoneAt;
		private volatile boolean fClosed;

		private CmsSessionHandle(WorkspaceScriptContext context, String runAs, String routeId) {
			fContext = context;
			fOwnerThread = Thread.currentThread();
			fRunAs = runAs;
			fRouteId = routeId;
		}

		private WorkspaceScriptContext getContext() {
			return fContext;
		}

		private Thread getOwnerThread() {
			return fOwnerThread;
		}

		private boolean isClosed() {
			return fClosed;
		}

		private void markClosed() {
			fClosed = true;
		}

		private void markUnitOfWorkDone() {
			fUnitOfWorkDoneAt = System.currentTimeMillis();
			fUnitOfWorkDone = true;
		}

		private boolean isUnitOfWorkDone() {
			return fUnitOfWorkDone;
		}

		private long getUnitOfWorkDoneAt() {
			return fUnitOfWorkDoneAt;
		}

		private String describe() {
			return "runAs=" + fRunAs + ", route=" + Strings.defaultIfEmpty(fRouteId, "(unknown)") + ", thread="
					+ fOwnerThread.getName();
		}
	}

	/**
	 * Closes a route-owned session when its exchange finishes, whatever route the
	 * exchange took to get there.
	 * <p>
	 * This is the close that does not depend on the route author remembering
	 * anything: {@code CamelInternalProcessor} completes the unit of work
	 * unconditionally, including after {@code <stop/>}, and the callback runs on the
	 * owning thread. It is a backstop rather than the normal path — a route should
	 * still close in {@code doFinally}, so that the session is released the moment
	 * the work is done rather than whenever the exchange happens to complete.
	 * <p>
	 * {@code UnitOfWorkHelper} swallows exceptions thrown here, so a failure would
	 * otherwise be invisible. Recording that the unit of work finished lets the
	 * reaper pick up what this could not close.
	 */
	private final class CmsSessionSynchronization implements Synchronization {
		private final CmsSessionHandle fHandle;

		private CmsSessionSynchronization(CmsSessionHandle handle) {
			fHandle = handle;
		}

		@Override
		public void onComplete(Exchange exchange) {
			done();
		}

		@Override
		public void onFailure(Exchange exchange) {
			done();
		}

		private void done() {
			fHandle.markUnitOfWorkDone();
			if (fHandle.isClosed()) {
				return;
			}
			closeSession(fHandle, "its unit of work completed");
		}
	}

	/**
	 * Recovers sessions that were abandoned.
	 * <p>
	 * <strong>Elapsed time is never evidence of abandonment.</strong> A
	 * {@code <split>} over five thousand records is supposed to take minutes, and
	 * closing a session from this thread while its owner is still using it would
	 * cause exactly the corruption the ownership rules exist to prevent. So a
	 * session is closed only on evidence:
	 * <ul>
	 * <li>E1 — its unit of work finished but nothing closed it. This really
	 * happens: {@code UnitOfWorkHelper.doneUow} swallows exceptions thrown by
	 * synchronizations, so a failed close leaves no other trace.</li>
	 * <li>E2 — the owning thread is dead, so no close will ever come.</li>
	 * <li>E3 — the component is stopping (handled in {@code doStop}).</li>
	 * </ul>
	 * A session whose owner is alive and whose unit of work is still running is
	 * left strictly alone, however long it has been open.
	 */
	private final class CmsSessionReaper implements Runnable {
		@Override
		public void run() {
			long now = System.currentTimeMillis();

			for (CmsSessionHandle handle : new ArrayList<>(fSessions.values())) {
				try {
					if (handle.isClosed()) {
						fSessions.remove(handle.getContext());
						continue;
					}

					// E2: nobody is left to close this one.
					if (!handle.getOwnerThread().isAlive()) {
						CmsService.getLogger(CmsComponent.class)
								.warn("Closing a JCR session whose owning thread has died. This session was opened by"
										+ " cms:login and never reached cms:logout: " + handle.describe());
						closeSession(handle, "the owning thread died");
						continue;
					}

					// E1: the exchange is finished, so no further node can join.
					// The grace period keeps this from racing the normal close.
					if (handle.isUnitOfWorkDone()
							&& (now - handle.getUnitOfWorkDoneAt()) > REAPER_GRACE_MILLIS) {
						CmsService.getLogger(CmsComponent.class)
								.warn("Closing a JCR session that outlived its exchange. Add cms:logout to doFinally"
										+ " and to an <onCompletion> backstop: " + handle.describe());
						closeSession(handle, "the unit of work had already finished");
						continue;
					}
				} catch (Throwable ex) {
					CmsService.getLogger(CmsComponent.class).warn("Failed to reap a cms: session.", ex);
				}
			}
		}
	}

	public class CmsEndpoint extends DefaultEndpoint {
		private final String fOperation;
		private final Map<String, Object> fParameters;

		private CmsEndpoint(String endpointUri, String operation, Map<String, Object> parameters) {
			super(endpointUri, CmsComponent.this);
			fOperation = operation;
			fParameters = new HashMap<>(parameters);
		}

		@Override
		public Consumer createConsumer(Processor processor) throws Exception {
			// Not supported
			return null;
		}

		@Override
		public Producer createProducer() throws Exception {
			// Session lifecycle. These four make the transaction boundary a row of
			// nodes on the canvas instead of an implicit property of each node.
			if ("login".equals(fOperation)) {
				return new LoginProducer();
			}
			if ("commit".equals(fOperation)) {
				return new CommitProducer();
			}
			if ("rollback".equals(fOperation)) {
				return new RollbackProducer();
			}
			if ("logout".equals(fOperation)) {
				return new LogoutProducer();
			}

			// Determine operation type
			if ("createFolder".equals(fOperation)) {
				return new CreateFolderProducer();
			}
			if ("createFile".equals(fOperation)) {
				return new CreateFileProducer();
			}
			if ("getMimeType".equals(fOperation)) {
				return new GetMimeTypeProducer();
			}
			if ("setMimeType".equals(fOperation)) {
				return new SetMimeTypeProducer();
			}
			if ("GetEncoding".equals(fOperation)) {
				return new GetEncodingProducer();
			}
			if ("SetEncoding".equals(fOperation)) {
				return new SetEncodingProducer();
			}
			if ("load".equals(fOperation)) {
				return new LoadProducer();
			}
			if ("getContent".equals(fOperation)) {
				return new GetContentProducer();
			}
			if ("store".equals(fOperation)) {
				return new StoreProducer();
			}
			if ("getProperty".equals(fOperation)) {
				return new GetPropertyProducer();
			}
			if ("setProperty".equals(fOperation)) {
				return new SetPropertyProducer();
			}
			if ("getProperties".equals(fOperation)) {
				return new GetPropertiesProducer();
			}
			if ("setProperties".equals(fOperation)) {
				return new SetPropertiesProducer();
			}
			if ("move".equals(fOperation)) {
				return new MoveProducer();
			}
			if ("exists".equals(fOperation)) {
				return new ExistsProducer();
			}
			if ("query".equals(fOperation)) {
				return new QueryProducer();
			}
			if ("list".equals(fOperation)) {
				return new ListProducer();
			}
			if ("remove".equals(fOperation)) {
				return new RemoveProducer();
			}
			if ("addVersionControl".equals(fOperation)) {
				return new AddVersionControlProducer();
			}
			if ("checkout".equals(fOperation)) {
				return new CheckoutProducer();
			}
			if ("checkin".equals(fOperation)) {
				return new CheckinProducer();
			}
			if ("uncheckout".equals(fOperation)) {
				return new UncheckoutProducer();
			}
			if ("checkpoint".equals(fOperation)) {
				return new CheckpointProducer();
			}
			if ("lock".equals(fOperation)) {
				return new LockProducer();
			}
			if ("unlock".equals(fOperation)) {
				return new UnlockProducer();
			}
			// The script to run is named by the path option rather than by the URI
			// itself, which is what lets a route dispatch to a script it computes.
			if ("script".equals(fOperation)) {
				return new ScriptProducer(null);
			}

			// Default: script execution (backward compatibility)
			return new ScriptProducer(fOperation);
		}

		/**
		 * Convert single value to JCR Value
		 */
		protected Value toJcrValue(Object value, ValueFactory vf) throws RepositoryException {
			// Calendar types
			if (value instanceof Calendar) {
				return vf.createValue((Calendar) value);
			}
			if (value instanceof Date) {
				Calendar cal = Calendar.getInstance();
				cal.setTime((Date) value);
				return vf.createValue(cal);
			}
			if (value instanceof ZonedDateTime) {
				return vf.createValue(GregorianCalendar.from((ZonedDateTime) value));
			}
			if (value instanceof OffsetDateTime) {
				return vf.createValue(GregorianCalendar.from(((OffsetDateTime) value).toZonedDateTime()));
			}
			if (value instanceof LocalDateTime) {
				return vf.createValue(GregorianCalendar.from(((LocalDateTime) value).atZone(ZoneId.systemDefault())));
			}
			if (value instanceof Instant) {
				return vf.createValue(GregorianCalendar.from(((Instant) value).atZone(ZoneId.systemDefault())));
			}

			// Numeric types
			if (value instanceof Long || value instanceof Integer) {
				return vf.createValue(((Number) value).longValue());
			}
			if (value instanceof Double || value instanceof Float) {
				return vf.createValue(((Number) value).doubleValue());
			}
			if (value instanceof BigDecimal) {
				return vf.createValue((BigDecimal) value);
			}

			// Boolean type
			if (value instanceof Boolean) {
				return vf.createValue((Boolean) value);
			}

			// Default: String
			return vf.createValue(value.toString());
		}

		/**
		 * Convert array/collection to JCR Value array
		 */
		protected Value[] toJcrValues(Object value, ValueFactory vf) throws RepositoryException {
			List<Value> values = new ArrayList<>();

			if (value instanceof Collection) {
				for (Object item : (Collection<?>) value) {
					if (item != null) {
						values.add(toJcrValue(item, vf));
					}
				}
			} else if (value.getClass().isArray()) {
				int length = Array.getLength(value);
				for (int i = 0; i < length; i++) {
					Object item = Array.get(value, i);
					if (item != null) {
						values.add(toJcrValue(item, vf));
					}
				}
			}

			return values.toArray(new Value[0]);
		}

		/**
		 * Unwraps a gzip stream, by default only when it actually is one.
		 * <p>
		 * Whether a stored export is compressed is a property of the file rather than
		 * of the route, and it changes: the same bulk result arrives gzipped or not
		 * depending on its size. Four places in commerce read the first bytes by hand
		 * and branch on them, which is four copies of a decision the loader can make.
		 *
		 * @param mode {@code auto} (default) sniffs the magic bytes, {@code gzip}
		 *        insists, {@code none} passes the stream through.
		 */
		private InputStream decompress(InputStream in, String mode) throws IOException {
			String requested = Strings.defaultIfEmpty(mode, "auto").trim();
			if ("none".equals(requested)) {
				return in;
			}
			if ("gzip".equals(requested)) {
				return new GZIPInputStream(in);
			}
			if (!"auto".equals(requested)) {
				throw new IllegalArgumentException("Unknown decompress mode: " + requested
						+ ". Available modes are: auto, gzip, none.");
			}

			// A PushbackInputStream so the sniffed bytes go back where they were: the
			// stream is the file's content and the caller gets all of it either way.
			PushbackInputStream sniffable = new PushbackInputStream(in, 2);
			byte[] magic = new byte[2];
			int read = sniffable.read(magic);
			if (read > 0) {
				sniffable.unread(magic, 0, read);
			}
			boolean gzipped = (read == 2)
					&& ((magic[0] & 0xff) == 0x1f) && ((magic[1] & 0xff) == 0x8b);
			return gzipped ? new GZIPInputStream(sniffable) : sniffable;
		}

		private abstract class CmsProducer extends DefaultProducer {
			protected CmsProducer(Endpoint endpoint) {
				super(endpoint);
			}

			@Override
			public void process(Exchange exchange) throws Exception {
				try (ProcessContext context = new ProcessContext(exchange)) {
					doProcess(context);
				}
			}

			protected abstract void doProcess(ProcessContext context) throws Exception;

			/**
			 * ProcessContext provides convenient access to endpoint parameters and exchange data for producers.
			 */
			protected class ProcessContext implements Closeable {
				private final Exchange fExchange;

				/**
				 * The script context this node opened for itself, or {@code null} when
				 * the node joined a session the route already owns. Ownership decides
				 * who closes and who saves, and it is the whole of the difference
				 * between the legacy one-session-per-node behaviour and a route-owned
				 * transaction.
				 */
				private WorkspaceScriptContext fOwnedContext;

				protected ProcessContext(Exchange exchange) {
					fExchange = exchange;
				}

				public Exchange getExchange() {
					return fExchange;
				}

				/**
				 * Resolves the route-owned session this node should join, or
				 * {@code null} when it is to own its session as before.
				 * <p>
				 * When {@code context} names a session opened earlier in the route by
				 * {@code cms:login}, that session is reused: the node neither closes it
				 * nor saves through it, so a run of {@code cms:} nodes shares one JCR
				 * session and one transaction, and {@code cms:commit} decides when any of
				 * it becomes durable. When no session is reachable the node opens and
				 * closes its own session exactly as it always has, which is what lets a
				 * route be converted one node at a time.
				 * <p>
				 * The session travels as an exchange header, because it is an object and
				 * a URI is text. That matters for how the option is written: a
				 * {@code <toD>} builds its URI by evaluating it against the exchange, so
				 * {@code context=${header.cmsContext}} would arrive here as the session's
				 * {@code toString()} — and a node that quietly opened a session of its
				 * own on seeing that would drop its writes out of the route's transaction
				 * without a word. So the option carries the <em>name</em> of the header
				 * instead, and a header named explicitly but holding no session is an
				 * error rather than a fallback.
				 * <p>
				 * Most routes need not write the option at all: the default header name
				 * ({@value #CMS_CONTEXT_DEFAULT}) is the one {@code cms:login} publishes
				 * to by convention. Naming it is for the route that juggles two sessions
				 * at once.
				 */
				private WorkspaceScriptContext resolveJoinedContext() {
					Object joined = getParameter(CMS_CONTEXT);

					// Whether the route said which session to join. It decides what an
					// empty header means: a wiring mistake when the author named one, and
					// "this node owns its session" when the default did the naming.
					boolean named = (joined != null);
					if (!named) {
						joined = CMS_CONTEXT_DEFAULT;
					}

					if (joined instanceof WorkspaceScriptContext) {
						return (WorkspaceScriptContext) joined;
					}

					String headerName = joined.toString().trim();
					if (headerName.isEmpty()) {
						return null;
					}

					Object referenced = null;
					if (fExchange.getProperties().containsKey(headerName)) {
						referenced = fExchange.getProperty(headerName);
					}
					if (fExchange.getIn().getHeaders().containsKey(headerName)) {
						referenced = fExchange.getIn().getHeader(headerName);
					}
					if (referenced instanceof WorkspaceScriptContext) {
						return (WorkspaceScriptContext) referenced;
					}

					// Nothing was published under the default name, so no route-owned
					// session exists for this node to join and it owns one as before.
					// A header that exists and holds something else still fails: the
					// default decides which header to read, not whether a wrong value
					// in it may pass unreported.
					if (!named && referenced == null) {
						return null;
					}

					throw new IllegalStateException("The " + CMS_CONTEXT + " option must name the header holding the"
							+ " session opened by cms:login, but header \"" + headerName + "\" holds "
							+ ((referenced == null) ? "nothing" : referenced.getClass().getName()) + ". Write"
							+ " " + CMS_CONTEXT + "=" + CMS_CONTEXT_DEFAULT + " (the header name), not "
							+ CMS_CONTEXT + "=${header." + CMS_CONTEXT_DEFAULT + "} — a <toD> resolves its URI to"
							+ " text, so an expression would arrive here as the session's toString(). A node that"
							+ " should own its session must omit the option and publish no session under \""
							+ CMS_CONTEXT_DEFAULT + "\".");
				}

				public WorkspaceScriptContext openContext() throws Exception {
					WorkspaceScriptContext joined = resolveJoinedContext();
					if (joined != null) {
						WorkspaceScriptContext context = joined;
						CmsSessionHandle handle = fSessions.get(context);

						// A JCR session is bound to the thread that opened it and the
						// workspace holds a single JDBC connection, so two threads sharing
						// one session do not fail — they corrupt the transaction. Refuse
						// loudly instead, and name the fix.
						if (handle != null && handle.getOwnerThread() != Thread.currentThread()) {
							throw new IllegalStateException("The session was opened on thread \""
									+ handle.getOwnerThread().getName() + "\" but is being used on \""
									+ Thread.currentThread().getName()
									+ "\". A JCR session belongs to one thread and the workspace holds a single JDBC"
									+ " connection, so move the seda:/wireTap/threads hop outside the"
									+ " cms:login..cms:logout span, or open a separate context on the other thread.");
						}

						// A joined node runs as whoever cms:login opened the session as. A
						// runAs written on the node itself is not applied; identity is
						// fixed at login.

						return context;
					}

					WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName);
					String runAs = (String) getParameter("runAs");
					if (Strings.isNotEmpty(runAs)) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}
					Scripts.prepareAPIs(context);
					fOwnedContext = context;
					return context;
				}

				/**
				 * Persists this node's work, but only when the node owns its session.
				 * A joined node leaves its changes transient so that {@code cms:commit}
				 * remains the single durability point of the route's transaction.
				 */
				public void save(Session session) throws RepositoryException {
					if (fOwnedContext != null) {
						session.save();
					}
				}

				/**
				 * Returns the route-owned session named by {@code context}, or throws.
				 * <p>
				 * A commit or rollback whose session cannot be found is a wiring mistake,
				 * and reporting it as success is how silently discarded work happens. So
				 * there is no way to pass over it: the node either has the session it
				 * names, or it fails.
				 * <p>
				 * {@code cms:logout} is the exception and uses {@link #findContext()}
				 * instead: closing is the one operation with nothing to get wrong when
				 * there is nothing to close.
				 */
				public WorkspaceScriptContext requireContext(String operation) {
					WorkspaceScriptContext joined = resolveJoinedContext();
					if (joined != null) {
						return joined;
					}

					throw new IllegalStateException("cms:" + operation + " found no session under \""
							+ CMS_CONTEXT_DEFAULT + "\". Open one with cms:login, or name the header holding it"
							+ " with " + CMS_CONTEXT + "=<header name>.");
				}

				/**
				 * The session this node names, or {@code null} when there is none to act
				 * on — including a named header that holds something else.
				 * <p>
				 * For {@code cms:logout}, and for {@code cms:logout} only. An
				 * {@code <onCompletion>} backstop runs on every path the exchange can
				 * take, including the ones that failed before {@code cms:login} — which
				 * on a route whose transaction opens late is most of them. Nothing is
				 * open there, so there is nothing to report either.
				 */
				public WorkspaceScriptContext findContext() {
					try {
						return resolveJoinedContext();
					} catch (IllegalStateException ex) {
						return null;
					}
				}

				/**
				 * Get parameter value from endpoint parameters or exchange headers.
				 * <p>
				 * A String value beginning with {@code @} names where the value lives
				 * rather than being the value: {@code @body} is the message body,
				 * {@code @header.name} that header, {@code @property.name} that
				 * exchange property. This is the input-side mirror of the output
				 * bindings — there the {@code @} sits on the key
				 * ({@code @header.stalePaths=paths}), here on the value
				 * ({@code statement=@header.pruneQuery}) — so the two never collide
				 * on one URI. The typed getters ({@code getParameterAsBoolean} and
				 * friends) resolve references the same way, since they read through
				 * this method.
				 * <p>
				 * A literal value that must begin with {@code @} is written with the
				 * {@code @} doubled ({@code @@...}); the first one is stripped. An
				 * unrecognised {@code @}-form is returned as written, so {@code @none}
				 * can stay a marker the producer that defines it looks for.
				 */
				public Object getParameter(String name, boolean raw) {
					if (Strings.isBlank(name)) {
						throw new IllegalArgumentException("Parameter name must not be null");
					}

					name = name.trim();

					if (!fParameters.containsKey(name)) {
						if (fExchange.getProperties().containsKey(name)) {
							return fExchange.getProperty(name);
						}
						if (fExchange.getIn().getHeaders().containsKey(name)) {
							return fExchange.getIn().getHeader(name);
						}
						return null;
					}

					Object v = fParameters.get(name);
					if (raw) {
						return v;
					}
					if (!(v instanceof String)) {
						return v;
					}
					name = ((String) v).trim();
					if (name.equalsIgnoreCase("@body")) {
						return fExchange.getIn().getBody();
					}
					if (name.startsWith("@header.")) {
						return fExchange.getIn().getHeader(name.substring("@header.".length()));
					}
					if (name.startsWith("@property.")) {
						return fExchange.getProperty(name.substring("@property.".length()));
					}
					return v;
				}
				public Object getParameter(String name) {
					return getParameter(name, false);
				}

				/**
				 * Get a parameter value interpreted as a boolean.
				 * Accepts {@link Boolean} values as well as their string representations ("true"/"false").
				 * Returns the supplied default value when the parameter is missing or blank.
				 */
				public boolean getParameterAsBoolean(String name, boolean defaultValue) {
					Object value = getParameter(name);
					if (value == null) {
						return defaultValue;
					}
					if (value instanceof Boolean) {
						return ((Boolean) value).booleanValue();
					}
					String text = value.toString().trim();
					if (text.isEmpty()) {
						return defaultValue;
					}
					return Boolean.parseBoolean(text);
				}

				/**
				 * Get a parameter value interpreted as a {@code long}. Accepts {@link Number} values as well
				 * as their string representations. Returns the supplied default when the parameter is missing
				 * or blank.
				 */
				public long getParameterAsLong(String name, long defaultValue) {
					Object value = getParameter(name);
					if (value == null) {
						return defaultValue;
					}
					if (value instanceof Number) {
						return ((Number) value).longValue();
					}
					String text = value.toString().trim();
					if (text.isEmpty()) {
						return defaultValue;
					}
					return Long.parseLong(text);
				}

				/**
				 * Get a parameter value interpreted as an {@code int}. Accepts {@link Number} values as well
				 * as their string representations. Returns the supplied default when the parameter is missing
				 * or blank.
				 */
				public int getParameterAsInteger(String name, int defaultValue) {
					Object value = getParameter(name);
					if (value == null) {
						return defaultValue;
					}
					if (value instanceof Number) {
						return ((Number) value).intValue();
					}
					String text = value.toString().trim();
					if (text.isEmpty()) {
						return defaultValue;
					}
					return Integer.parseInt(text);
				}

				/**
				 * Get all parameter names from endpoint parameters and exchange headers.
				 */
				public List<String> getParameterNames() {
					List<String> names = new ArrayList<>(fParameters.keySet());
					for (String headerName : fExchange.getIn().getHeaders().keySet()) {
						if (!names.contains(headerName)) {
							names.add(headerName);
						}
					}
					return names;
				}

				/**
				 * Parse a filter parameter into a list of strings.
				 * Supports comma-separated strings, lists, and collections.
				 */
				public List<String> parseFilterList(Object filter) {
					if (filter == null) {
						return Collections.emptyList();
					}
					if (filter instanceof List) {
						return ((List<?>) filter).stream()
								.map(Object::toString)
								.map(String::trim)
								.collect(Collectors.toList());
					}
					if (filter instanceof String) {
						return List.of(((String) filter).split("\\s*,\\s*"));
					}
					if (filter instanceof Collection<?>) {
						return ((Collection<?>) filter).stream()
								.map(Object::toString)
								.map(String::trim)
								.collect(Collectors.toList());
					}
					return List.of(filter.toString().trim());
				}

				/**
				 * Set a header in the exchange for downstream processing.
				 * This method can be used by producers to set headers based on their processing logic, which can then be consumed by other processors or components in the route.
				 */
				public void setHeader(String key, Object value) {
					fExchange.getIn().setHeader(key, value);
				}

				/**
				 * Set the message body in the exchange for downstream processing.
				 */
				public void setBody(Object value) {
					fExchange.getIn().setBody(value);
				}

				/**
				 * Set an exchange property for downstream processing.
				 */
				public void setProperty(String key, Object value) {
					fExchange.setProperty(key, value);
				}

				/**
				 * A single output binding describing how a named result source should be placed
				 * back into the exchange (body, a header, or an exchange property).
				 */
				private final class ResultBinding {
					private final String kind; // "body", "header" or "property"
					private final String target; // header/property name (null for body)
					private final String sourceName;

					private ResultBinding(String kind, String target, String sourceName) {
						this.kind = kind;
						this.target = target;
						this.sourceName = sourceName;
					}
				}

				/**
				 * Collect output bindings declared either as endpoint (URL) parameters or as exchange headers.
				 *
				 * Binding syntax:
				 *   "@body=sourceName"          — set the message body to the named source value
				 *   "@header.headerName=source" — set the header "headerName" to the named source value
				 *   "@property.propName=source" — set the exchange property "propName" to the named source value
				 *
				 * Endpoint parameters take precedence over exchange headers for the same binding key,
				 * so a binding may be supplied through either channel (URL parameter first, header as fallback).
				 */
				private List<ResultBinding> collectBindings() {
					Map<String, Object> declared = new LinkedHashMap<>();
					for (Map.Entry<String, Object> entry : fParameters.entrySet()) {
						if (isBindingKey(entry.getKey())) {
							declared.put(entry.getKey(), entry.getValue());
						}
					}
					for (Map.Entry<String, Object> entry : fExchange.getIn().getHeaders().entrySet()) {
						if (isBindingKey(entry.getKey()) && !declared.containsKey(entry.getKey())) {
							declared.put(entry.getKey(), entry.getValue());
						}
					}

					List<ResultBinding> bindings = new ArrayList<>();
					for (Map.Entry<String, Object> entry : declared.entrySet()) {
						String key = entry.getKey();
						String sourceName = (entry.getValue() == null) ? null : entry.getValue().toString().trim();
						if (key.equals("@body")) {
							bindings.add(new ResultBinding("body", null, sourceName));
						} else if (key.startsWith("@header.")) {
							bindings.add(new ResultBinding("header", key.substring("@header.".length()), sourceName));
						} else if (key.startsWith("@property.")) {
							bindings.add(new ResultBinding("property", key.substring("@property.".length()), sourceName));
						}
					}
					return bindings;
				}

				/**
				 * Whether the given key declares an output binding (@body / @header.x / @property.x).
				 */
				private boolean isBindingKey(String key) {
					if (Strings.isEmpty(key)) {
						return false;
					}
					return key.equals("@body") || key.startsWith("@header.") || key.startsWith("@property.");
				}

				/**
				 * The set of result source names referenced by the declared output bindings.
				 * Callers use this to compute only the sources that are actually needed.
				 * <p>
				 * Because bindings may be declared as exchange headers, this set can include names left
				 * over from a binding set for a previously invoked producer in the same route. Callers must
				 * therefore test for the specific source names they support (rather than treating a
				 * non-empty set as "something was requested"), so that a foreign leftover binding never
				 * changes this producer's behaviour.
				 */
				public Set<String> getBoundSourceNames() {
					Set<String> names = new HashSet<>();
					for (ResultBinding binding : collectBindings()) {
						names.add(binding.sourceName);
					}
					return names;
				}

				/**
				 * Bind named result values to the exchange according to the declared output bindings.
				 * Only bindings whose source name is present in {@code sources} take effect; bindings that
				 * reference an unknown source (for example, a binding left over from a previously invoked
				 * producer) are ignored. Output is produced solely through these bindings — there is no
				 * implicit default such as placing the result in a fixed header.
				 */
				public List<String> applyResultBindings(Map<String, Object> sources) {
					List<String> boundNames = new ArrayList<>();
					for (ResultBinding binding : collectBindings()) {
						if (!sources.containsKey(binding.sourceName)) {
							continue;
						}

						Object value = sources.get(binding.sourceName);
						if ("body".equals(binding.kind)) {
							setBody(value);
						} else if ("header".equals(binding.kind)) {
							setHeader(binding.target, value);
						} else {
							setProperty(binding.target, value);
						}
						boundNames.add(binding.sourceName);
					}
					return boundNames;
				}

				/**
				 * Closes the session this node opened for itself. A session the route
				 * owns is deliberately left open: closing it here would log the rest of
				 * the route out from under itself, and would roll back everything not yet
				 * committed.
				 */
				@Override
				public void close() throws IOException {
					if (fOwnedContext != null) {
						fOwnedContext.close();
						fOwnedContext = null;
					}
				}
			}
		}

		/**
		 * Producer that opens the JCR session a route's transaction runs in.
		 *
		 * Every {@code cms:} node downstream that reaches this session works
		 * through this one session, so a sequence of writes becomes one transaction
		 * whose durability point is a visible {@code cms:commit} node rather than an
		 * implicit save at the end of each node.
		 *
		 * The session is handed to the route as a result binding, exactly like every
		 * other producer's output — and when none is written it is published under
		 * the header the {@code context} option defaults to, so the common case
		 * needs neither the binding nor the option:
		 *
		 * URI format: cms:login?runAs=commerce-service-user
		 * Parameters:
		 *   - runAs: User to run as (required — see below)
		 *   - Output binding: &#64;header.headerName=context
		 *                     (optional, default: &#64;property.cmsContext=context)
		 *
		 * {@code runAs} is required of the route author, though nothing here enforces
		 * it — and that is exactly why it has to be written every time. An unset or
		 * misspelled credential does not fail: {@code WorkspaceScriptContext} falls
		 * back to guest, and a guest can still read public content, so the typo turns
		 * into a session that reads a little, writes nothing, and reports success.
		 * Identity is fixed here for the whole span: a {@code runAs} written on a node
		 * that joins this session is ignored.
		 *
		 * There is deliberately no lifetime option. A session is never closed on a
		 * timer, because elapsed time cannot distinguish a leak from a legitimate
		 * long-running batch. It is closed by {@code doFinally}, by an
		 * {@code <onCompletion>} backstop, by the unit-of-work synchronization this
		 * producer registers, or — only on evidence of abandonment — by the reaper.
		 */
		private class LoginProducer extends CmsProducer {
			private LoginProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				String runAs = (String) pc.getParameter("runAs");

				WorkspaceScriptContext context = new WorkspaceScriptContext(fWorkspaceName);
				try {
					if (!Strings.isEmpty(runAs)) {
						context.setCredentials(new ServiceUserCredentials(runAs));
					}

					Scripts.prepareAPIs(context);

					CmsSessionHandle handle = new CmsSessionHandle(context, runAs,
							pc.getExchange().getFromRouteId());
					fSessions.put(context, handle);

					// Close layer 3. doFinally and the onCompletion backstop are the
					// route's own doing and may simply be missing; this one always runs,
					// on the owning thread, when the exchange is done.
					if (pc.getExchange().getUnitOfWork() != null) {
						pc.getExchange().getUnitOfWork().addSynchronization(new CmsSessionSynchronization(handle));
					}

					pc.applyResultBindings(Map.of("context", context));

					// No binding written, so publish where every other node looks by
					// default. Writing @header.cmsContext=context is then the explicit
					// spelling of what this does, and naming another header is for the
					// route that holds two sessions at once.
					if (!pc.getBoundSourceNames().contains("context")) {
						pc.setProperty(CMS_CONTEXT_DEFAULT, context);
					}
				} catch (Throwable ex) {
					fSessions.remove(context);
					try {
						context.close();
					} catch (Throwable ignore) {}
					throw ex;
				}
			}
		}

		/**
		 * Producer that makes the route's pending work durable.
		 *
		 * This is the single point at which a route-owned transaction becomes
		 * visible to anyone else. Several {@code cms:commit} nodes in one login span
		 * are legal and independent, so a long route may checkpoint more than once.
		 *
		 * URI format: cms:commit?context=cmsContext
		 * Parameters:
		 *   - context: Name of the header holding the session opened by cms:login
		 *              (optional, default: cmsContext)
		 *
		 * A commit with nothing pending is a quiet no-op. A commit whose scope
		 * cannot be resolved is not: that is always a route bug, and a commit that
		 * commits nothing while reporting success is the worst possible outcome.
		 */
		private class CommitProducer extends CmsProducer {
			private CommitProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.requireContext("commit");
				context.getSession().commit();
			}
		}

		/**
		 * Producer that discards the route's pending work.
		 *
		 * Writing it out is worth the node even though {@code cms:logout} would also
		 * drop an unsaved session: a rollback that leaves no trace in message history
		 * is not a rollback anyone can review afterwards.
		 *
		 * Locks are unaffected. Lock rows are written through their own session and
		 * their tokens are held in memory, so a critical section survives a rollback
		 * and is released at {@code cms:logout} — which is what makes
		 * {@code doCatch → rollback → doFinally → logout} correct.
		 *
		 * URI format: cms:rollback?context=cmsContext
		 * Parameters:
		 *   - context: Name of the header holding the session opened by cms:login
		 *              (optional, default: cmsContext)
		 */
		private class RollbackProducer extends CmsProducer {
			private RollbackProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.requireContext("rollback");
				context.getSession().rollback();
			}
		}

		/**
		 * Producer that closes the route's session and releases its session-scoped
		 * locks.
		 *
		 * Idempotent, which is what makes it safe to write in both {@code doFinally}
		 * and an {@code <onCompletion>} backstop: the normal path closes in
		 * {@code doFinally}, {@code <stop/>} skips that and the backstop catches it,
		 * and when both run the second is a no-op.
		 *
		 * Idempotent about the session itself, too: when no session can be reached
		 * this node does nothing rather than failing. A backstop runs on every path
		 * the exchange can take, including the ones that failed before
		 * {@code cms:login} — which on a route whose transaction opens late is most
		 * of them — and reporting "nothing to close" as an error there would put a
		 * stack trace in the log on the ordinary failure path. Unlike
		 * {@code cms:commit}, closing nothing loses nothing.
		 *
		 * URI format: cms:logout?context=cmsContext
		 * Parameters:
		 *   - context: Name of the header holding the session opened by cms:login
		 *              (optional, default: cmsContext)
		 *
		 * Anything not committed is discarded, not saved. Closing rolls back first
		 * and then logs out, so a route that forgets {@code cms:commit} loses its
		 * work quietly — which is the failure mode to review for, and the reason the
		 * commit node is never optional.
		 */
		private class LogoutProducer extends CmsProducer {
			private LogoutProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.findContext();
				if (context == null) {
					return;
				}

				CmsSessionHandle handle = fSessions.get(context);
				if (handle != null) {
					closeSession(handle, "cms:logout");
					return;
				}

				// A context that reached this node without ever being registered can
				// still be closed; it simply has no bookkeeping to clean up.
				context.close();
			}
		}

		/**
		 * Producer for creating a folder, and for making sure one is there.
		 *
		 * Exists so that creating content and locking it are separate steps. A lock
		 * target has to be a node that already exists and already carries
		 * {@code mix:lockable}, so something must create it. Doing that inside
		 * {@code cms:lock} would mean {@code cms:lock} writes to the route's session,
		 * and JCR refuses to lock a node whose session has unsaved changes beneath the
		 * lock target.
		 *
		 * URI format: cms:createFolder?path=/var/locks/sweep&amp;&#64;header.lockPath=path
		 * Parameters:
		 *   - path: Target folder path (required)
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *   - Output binding: &#64;header.headerName=path / =created
		 *
		 * Idempotent, and says which of the two happened: {@code created} is true only
		 * when this call made the folder, so a preparation step that runs on every tick
		 * is silent about the ticks that found their folder already there. A path that
		 * exists but is not a folder is an error rather than a false — the route asked
		 * for a folder and there is something else in its place.
		 *
		 * Intermediate folders are created as needed, and a folder created this way is
		 * lockable: the resource layer adds {@code mix:lockable} to every folder it
		 * creates, so no mixin has to be named here. An existing folder is left exactly
		 * as it is, which is why a preparation step never re-types content somebody
		 * else owns.
		 *
		 * When preparing a lock node, call this <em>outside</em> the route's session.
		 * The lock node is infrastructure rather than part of the business transaction,
		 * and JCR's unsaved-changes check would otherwise refuse the lock that follows —
		 * a folder created through the route's own session is still unsaved when
		 * {@code cms:lock} runs.
		 */
		private class CreateFolderProducer extends CmsProducer {
			private CreateFolderProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				if (Strings.isEmpty(path)) {
					throw new IllegalArgumentException("path parameter is required");
				}

				Resource resource = context.getSession().getResource(path);
				if (resource.isRoot()) {
					throw new IllegalArgumentException("Cannot create root folder: " + path);
				}

				boolean created = false;
				if (resource.exists()) {
					if (!resource.isCollection()) {
						throw new IllegalArgumentException("Path exists but is not a folder: " + path);
					}
				} else {
					resource.createFolder();
					pc.save(session);
					created = true;
				}

				pc.applyResultBindings(Map.of("path", resource.getPath(), "created", created));
			}
		}

		private class CreateFileProducer extends CmsProducer {
			private CreateFileProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				if (Strings.isEmpty(path)) {
					throw new IllegalArgumentException("path parameter is required");
				}

				Resource resource = context.getSession().getResource(path);
				if (resource.isRoot()) {
					throw new IllegalArgumentException("Cannot create root file: " + path);
				}

				boolean created = false;
				if (resource.exists()) {
					if (resource.isCollection()) {
						throw new IllegalArgumentException("Path exists but is not a file: " + path);
					}
				} else {
					resource.createFile();
					pc.save(session);
					created = true;
				}

				pc.applyResultBindings(Map.of("path", resource.getPath(), "created", created));
			}
		}

		private class GetMimeTypeProducer extends CmsProducer {
			private GetMimeTypeProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();

				String path = (String) pc.getParameter("path");
				if (Strings.isBlank(path)) {
					throw new IllegalArgumentException("path parameter is required");
				}

				Resource resource = context.getSession().getResource(path);

				String value = resource.getContentType();

				List<String> boundNames = pc.applyResultBindings(Map.of("value", value));
				if (!boundNames.contains("value")) {
					pc.setBody(value);
				}
			}
		}

		private class SetMimeTypeProducer extends CmsProducer {
			private SetMimeTypeProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				String mimeType = (String) pc.getParameter("value");

				if (Strings.isBlank(path)) {
					throw new IllegalArgumentException("path parameter is required");
				}
				if (Strings.isBlank(mimeType)) {
					mimeType = "application/octet-stream";
				}

				Resource resource = context.getSession().getResource(path);

				resource.setContentType(mimeType);

				pc.save(session);
			}
		}

		private class GetEncodingProducer extends CmsProducer {
			private GetEncodingProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();

				String path = (String) pc.getParameter("path");
				if (Strings.isBlank(path)) {
					throw new IllegalArgumentException("path parameter is required");
				}

				Resource resource = context.getSession().getResource(path);

				String value = resource.getContentEncoding();

				List<String> boundNames = pc.applyResultBindings(Map.of("value", value));
				if (!boundNames.contains("value")) {
					pc.setBody(value);
				}
			}
		}

		private class SetEncodingProducer extends CmsProducer {
			private SetEncodingProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				String encoding = (String) pc.getParameter("value");

				if (Strings.isBlank(path)) {
					throw new IllegalArgumentException("path parameter is required");
				}
				if (Strings.isBlank(encoding)) {
					encoding = null;
				}

				Resource resource = context.getSession().getResource(path);

				resource.setContentEncoding(encoding);

				pc.save(session);

				// Expose the stored path through the declared output bindings (source name: "path")
				pc.applyResultBindings(Map.of("path", resource.getPath()));
			}
		}

		/**
		 * Evaluates a script stored in the repository.
		 *
		 * This is what {@code cms:} was before it had operations, and what an
		 * unrecognised operation still falls through to: in
		 * {@code cms:/foo/bar.groovy} the operation <em>is</em> the path of the
		 * script. The engine is chosen by the file's extension against the engines
		 * the workspace has registered; an extension nothing claims is an error, as
		 * are a missing script and one the session may not read.
		 *
		 * URI format: cms:/lib/commerce/price.groovy?inputs=commerce_~,order=&#64;body&amp;outputs=~Price
		 *             cms:script?path=&#64;header.scriptPath&amp;inputs=commerce_~
		 * Parameters:
		 *   - path: the script to evaluate — for {@code cms:script} only, where it is
		 *     required; the URI carries the path in every other spelling
		 *   - inputs: which exchange headers become script variables
		 *   - outputs: which script variables become exchange headers
		 *
		 * {@code cms:script?path=...} exists for the route that decides which script
		 * to run rather than knowing it. Because {@code path} is an option it reads
		 * {@code @header.name} like any other, so the choice can be made by an earlier
		 * node without building the URI as text in a {@code <toD>} — which would also
		 * mean a new endpoint, and a new script engine cache entry, per distinct value.
		 *
		 * Both filters are comma-separated and share one syntax, read as
		 * {@code target=source} when it contains {@code =} and otherwise as a pattern
		 * over names:
		 *   - {@code name} — that one, under the same name
		 *   - {@code prefix*} / {@code *suffix} — those, under their own names
		 *   - {@code prefix~} / {@code ~suffix} — those, with the affix stripped off
		 *   - {@code variable=header} on inputs ({@code header} may be {@code @body}),
		 *     {@code header=variable} on outputs
		 *
		 * A wildcard in {@code outputs} means <em>what this script produced</em>, not
		 * what the context happens to hold. Names already present when the script
		 * starts are excluded, which matters on a route-owned session: the scripting
		 * facades live in the context, and inside a {@code <split>} so does whatever
		 * the previous part left behind. Naming a variable outright still reads it
		 * whether or not this invocation created it.
		 *
		 * The script sees the current {@code exchange} and the script {@code resource},
		 * and both are removed again afterwards. Per-invocation state that outlived the
		 * invocation would show the next part of a split the first part's exchange.
		 */
		private class ScriptProducer extends CmsProducer {
			/**
			 * The script path the URI itself carries, or {@code null} for
			 * {@code cms:script} — where the path is an option instead, and may
			 * therefore differ from one exchange to the next.
			 */
			private final String fPath;

			private ScriptProducer(String path) {
				super(CmsEndpoint.this);
				fPath = path;
			}

			/**
			 * Which script this invocation runs.
			 * <p>
			 * For {@code cms:script} the {@code path} option says, and is required:
			 * there is no path in the URI to fall back to, and a missing one must not
			 * become a request for {@code /script}.
			 * <p>
			 * For every other spelling the URI says, and the option is refused rather
			 * than merged. Two reasons. A URI that names a script and an option that
			 * names another are a contradiction one of which would win silently — the
			 * route would run a script its own URI does not mention. And
			 * {@code getParameter} falls back to the exchange when an option is not
			 * written, so honouring {@code path} here would let a stray {@code path}
			 * header left by an earlier node — {@code cms:createFolder} publishes one
			 * by that very name — redirect a script node that was never asking to be
			 * dynamic.
			 */
			private String resolveScriptPath(ProcessContext pc) {
				if (fPath != null) {
					if (fParameters.containsKey("path")) {
						throw new IllegalArgumentException("cms:" + fPath + " already names the script to run, so the"
								+ " path option must not be written as well. Remove it, or write cms:script?path="
								+ fParameters.get("path") + " to let the option decide.");
					}
					return fPath;
				}

				Object path = pc.getParameter("path");
				String scriptPath = (path == null) ? null : path.toString().trim();
				if (Strings.isEmpty(scriptPath)) {
					throw new IllegalArgumentException("cms:script requires the path option to name the script to"
							+ " evaluate, for example cms:script?path=/lib/commerce/price.groovy or"
							+ " cms:script?path=@header.scriptPath. A script known when the route is written is"
							+ " better spelled cms:/lib/commerce/price.groovy.");
				}
				return scriptPath;
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				// Before the session: a node that cannot say which script it runs is a
				// wiring mistake, and there is no reason to take a session slot to
				// discover it.
				String scriptPath = resolveScriptPath(pc);

				WorkspaceScriptContext context = pc.openContext();
				context.setAttribute("exchange", pc.getExchange());

				String source = (String) pc.getParameter("source");
				if (!Strings.isEmpty(source)) {
					if (!Strings.isEmpty(scriptPath)) {
						throw new IllegalArgumentException("cms:script cannot be given both a path and a source. Use"
								+ " cms:script?path=... to run a script in the repository, or cms:script?source=... to"
								+ " run an inline script.");
					}
				}

				Resource resource = null;
				String scriptExtension = null;
				if (!Strings.isEmpty(scriptPath)) {
					String resourcePath = scriptPath.startsWith("/") ? scriptPath : "/" + scriptPath;
					resource = context.getSession().getResource(resourcePath);

					// Check if resource exists and is readable
					if (!resource.exists()) {
						throw new PathNotFoundException("Resource not found: " + resourcePath);
					}
					if (!resource.canRead()) {
						throw new AccessDeniedException("Cannot read resource: " + resourcePath);
					}

					// Determine script engine by resource name
					String resourceName = resource.getName();
					for (String extension : Scripts.getScriptExtensions(context)) {
						if (resourceName.endsWith("." + extension)) {
							scriptExtension = extension;
							break;
						}
					}
					if (scriptExtension == null) {
						throw new IllegalStateException("No script engine found for resource: " + resourcePath);
					}
				}
				if (!Strings.isEmpty(source)) {
					// Extension for inline source is always groovy, as it is the only supported inline script language.
					scriptExtension = "groovy";
				}

				// Set script context attributes based on input filters
				Map<String, Object> headers = pc.getExchange().getIn().getHeaders();
				for (String filter : pc.parseFilterList(pc.getParameter("inputs", true))) {
					if (filter.indexOf("=") > 0) {
						// Support inline key=value pairs in inputs parameter
						String[] parts = filter.split("=", 2);
						String attributeName = parts[0].trim();
						String headerName = parts[1].trim();
						if (Objects.equals(headerName.toLowerCase(), "@body")) {
							context.setAttribute(attributeName, pc.getExchange().getIn().getBody());
							continue;
						}
						if (headers.containsKey(headerName)) {
							context.setAttribute(attributeName, headers.get(headerName));
						}
						continue;
					}

					if (filter.endsWith("*")) {
						String prefix = filter.substring(0, filter.length() - 1);
						headers.entrySet().stream()
								.filter(entry -> entry.getKey().startsWith(prefix))
								.forEach(entry -> context.setAttribute(entry.getKey(), entry.getValue()));
					} else if (filter.startsWith("*")) {
						String suffix = filter.substring(1);
						headers.entrySet().stream()
								.filter(entry -> entry.getKey().endsWith(suffix))
								.forEach(entry -> context.setAttribute(entry.getKey(), entry.getValue()));
					} else if (filter.endsWith("~")) {
						String prefix = filter.substring(0, filter.length() - 1);
						headers.entrySet().stream()
								.filter(entry -> entry.getKey().startsWith(prefix))
								.forEach(entry -> context.setAttribute(entry.getKey().substring(prefix.length()), entry.getValue()));
					} else if (filter.startsWith("~")) {
						String suffix = filter.substring(1);
						headers.entrySet().stream()
								.filter(entry -> entry.getKey().endsWith(suffix))
								.forEach(entry -> context.setAttribute(entry.getKey().substring(0, entry.getKey().length() - suffix.length()), entry.getValue()));
					} else {
						Object value = headers.get(filter);
						if (value != null) {
							context.setAttribute(filter, value);
						}
					}
				}

				if (resource != null) {
					// Also set the resource itself in the context for direct access
					context.setAttribute("resource", resource);
				}

				// Everything the context already holds belongs to somebody else: the
				// facades, the inputs just bound for this call, and — when several parts
				// share one route-owned session — whatever the previous part left behind.
				// A wildcard in outputs= must mean "what THIS part produced", so the
				// names present now are the ones it does not get to claim.
				Set<String> inheritedAttributeNames = new HashSet<>(context.getAttributeNames());

				// Evaluate the script
				try (ScriptReader scriptReader = new ScriptReader((resource != null) ? resource.getContentAsReader() : new StringReader(source))) {
					scriptReader
							.setScriptName((resource != null) ? ("jcr://" + resource.getPath()) : "inline")
							.setExtension(scriptExtension)
							.setLastModified((resource != null) ? resource.getLastModified() : null)
							.setScriptEngineManager(Scripts.getScriptEngineManager(context))
							.setClassLoader(Scripts.getClassLoader(context))
							.setScriptContext(context)
							.eval();
				}

				List<String> producedAttributeNames = context.getAttributeNames().stream()
						.filter(name -> !inheritedAttributeNames.contains(name))
						.collect(Collectors.toList());

				// Set headers based on output filters
				for (String filter : pc.parseFilterList(pc.getParameter("outputs"))) {
					if (filter.indexOf("=") > 0) {
						// Support inline key=value pairs in outputs parameter
						String[] parts = filter.split("=", 2);
						String headerName = parts[0].trim();
						String attributeName = parts[1].trim();
						if (context.hasAttribute(attributeName)) {
							pc.setHeader(headerName, context.getAttribute(attributeName));
						}
						continue;
					}

					if (filter.endsWith("*")) {
						String prefix = filter.substring(0, filter.length() - 1);
						producedAttributeNames.stream()
								.filter(name -> name.startsWith(prefix))
								.forEach(name -> pc.setHeader(name, context.getAttribute(name)));
					} else if (filter.startsWith("*")) {
						String suffix = filter.substring(1);
						producedAttributeNames.stream()
								.filter(name -> name.endsWith(suffix))
								.forEach(name -> pc.setHeader(name, context.getAttribute(name)));
					} else if (filter.endsWith("~")) {
						String prefix = filter.substring(0, filter.length() - 1);
						producedAttributeNames.stream()
								.filter(name -> name.startsWith(prefix))
								.forEach(name -> pc.setHeader(name.substring(prefix.length()), context.getAttribute(name)));
					} else if (filter.startsWith("~")) {
						String suffix = filter.substring(1);
						producedAttributeNames.stream()
								.filter(name -> name.endsWith(suffix))
								.forEach(name -> pc.setHeader(name.substring(0, name.length() - suffix.length()), context.getAttribute(name)));
					} else {
						Object value = context.getAttribute(filter);
						if (value != null) {
							pc.setHeader(filter, value);
						}
					}
				}

				// Per-invocation state must not outlive the invocation. On a session the
				// route owns, leaving these behind would show the next part this part's
				// resource, and — inside a split — the first sub-exchange rather than its
				// own.
				context.removeAttribute("resource");
				context.removeAttribute("exchange");
			}
		}

		/**
		 * Producer for loading files from JCR as byte array
		 *
		 * Exposes the loaded content (byte[]) through the declared output bindings (source name: "content"),
		 * for example {@code @body=content}, {@code @header.headerName=content} or
		 * {@code @property.propName=content}. Output is produced solely through these bindings.
		 *
		 * URI format: cms:load?path=/content/file.txt&decompress=auto&@body=content
		 * Parameters:
		 *   - path: Source file path (required)
		 *   - decompress: auto (default) | gzip | none. auto unwraps the content when
		 *     it starts with the gzip magic bytes, so a route need not know whether the
		 *     file it is reading was compressed
		 *   - runAs: User to impersonate (optional)
		 *   - Output binding: @body=content, @header.headerName=content, or @property.propName=content
		 *
		 * The whole file is buffered in memory before anything is bound, so this is for
		 * content a route can afford to hold entire.
		 *
		 * @deprecated superseded by the typed readers — {@code cms:loadAsString},
		 *             {@code cms:loadAsJson}, {@code cms:loadAsYaml} — which say what
		 *             the route is going to do with the file. It remains the only
		 *             operation that yields raw bytes, and the only one that unwraps
		 *             gzip.
		 */
		@Deprecated
		private class LoadProducer extends CmsProducer {
			private LoadProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();

				// Get parameters from endpoint parameters or exchange headers
				String path = (String) pc.getParameter("path");
				if (path == null || path.trim().isEmpty()) {
					throw new IllegalArgumentException("path parameter is required");
				}

				ByteArrayOutputStream out = new ByteArrayOutputStream();
				try (InputStream in = decompress(context.getSession().getResource(path).getContentAsStream(),
						(String) pc.getParameter("decompress"))) {
					IOs.copy(in, out);
				}

				// Expose the loaded content through the declared output bindings (source name: "content")
				pc.applyResultBindings(Map.of("content", out.toByteArray()));
			}
		}

		private class GetContentProducer extends CmsProducer {
			private GetContentProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();

				// Get parameters from endpoint parameters or exchange headers
				String path = (String) pc.getParameter("path");

				if (path == null || path.trim().isEmpty()) {
					throw new IllegalArgumentException("path parameter is required");
				}

				String content = context.getSession().getResource(path).getContent();

				// Expose the loaded content through the declared output bindings (source name: "content")
				List<String> boundNames = pc.applyResultBindings(Map.of("content", content));
				if (!boundNames.contains("content")) {
					pc.setBody(content);
				}
			}
		}

		/**
		 * Producer for storing files to JCR
		 *
		 * Exposes the stored path through the declared output bindings (source name: "path"),
		 * for example {@code @header.cmsStoredPath=path}, {@code @body=path} or
		 * {@code @property.storedPath=path}. Output is produced solely through these bindings.
		 *
		 * URI format: cms:store?path=/content/file.txt&mimeType=application/json&@header.cmsStoredPath=path
		 * Parameters:
		 *   - path: Target file path (required)
		 *   - mimeType: MIME type (default: application/octet-stream). A value
		 *     containing "+" (e.g. a "+json" structured-syntax suffix) MUST be
		 *     wrapped as RAW(...): Camel normalizes a %2B escape back to "+" and
		 *     then form-decodes the query, so both a literal and an encoded plus
		 *     otherwise reach this endpoint as a space.
		 *   - encoding: Optional content encoding recorded on the file (e.g., "UTF-8")
		 *   - content: Where the file's content comes from — @body (the default when
		 *     the option is not written), @header.name, @property.name, or @none to
		 *     write no content at all: the file is created (empty) and its metadata
		 *     set, which is the store for a marker whose meaning is its properties.
		 *     Naming a source that resolves to nothing is an error, not a fallback —
		 *     the author said where the content is and was wrong.
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *   - Output binding: @body=path, @header.headerName=path, or @property.propName=path
		 *
		 * The source option this replaced is refused by name, so a route still
		 * carrying it fails loudly with the new spelling in the message instead of
		 * quietly storing the body.
		 *
		 * Missing parent folders are created, always — there is no option to refuse.
		 * The content is written as byte[] or String; anything else goes through the
		 * exchange's type converter (an InputStream body arrives that way), and a
		 * value nothing can convert is an error rather than a toString(), because
		 * writing a file whose content is an object's identity hash is not something
		 * to discover later.
		 *
		 * Writing an existing path replaces its content — except under @none, which
		 * writes none and therefore leaves an existing file's content as it was.
		 * On a session the route owns the write is saved here; on a joined session
		 * it waits for cms:commit.
		 */
		private class StoreProducer extends CmsProducer {
			private StoreProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				// Get parameters from endpoint parameters or exchange headers
				String path = (String) pc.getParameter("path");
				String mimeType = (String) pc.getParameter("mimeType");
				String encoding = (String) pc.getParameter("encoding");
				Object content = pc.getParameter("content");

				if (path == null || path.trim().isEmpty()) {
					throw new IllegalArgumentException("path parameter is required");
				}
				if (mimeType == null || mimeType.trim().isEmpty()) {
					mimeType = "application/octet-stream"; // Default MIME type
				}

				Resource resource = context.getSession().getResource(path);
				resource = resource.getParent().createFolder().createFile(resource.getName());

				if (content != null) {
					resource.write(content);
				}
				resource.setContentType(mimeType);
				if (!Strings.isEmpty(encoding)) {
					resource.setContentEncoding(encoding);
				}

				pc.save(session);

				// Expose the stored path through the declared output bindings (source name: "path")
				pc.applyResultBindings(Map.of("path", resource.getPath()));
			}
		}

		private class GetPropertyProducer extends CmsProducer {
			private GetPropertyProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();

				String path = (String) pc.getParameter("path");
				String name = (String) pc.getParameter("name");
				if (Strings.isEmpty(path)) {
					throw new IllegalArgumentException("path parameter is required");
				}
				if (Strings.isEmpty(name)) {
					throw new IllegalArgumentException("name parameter is required");
				}

				Resource resource = context.getSession().getResource(path);

				Object value = resource.getProperty(name);

				List<String> boundNames = pc.applyResultBindings(Map.of("value", value));
				if (!boundNames.contains("value")) {
					pc.setBody(value);
				}
			}
		}

		private class SetPropertyProducer extends CmsProducer {
			private SetPropertyProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();

				String path = (String) pc.getParameter("path");
				String name = (String) pc.getParameter("name");
				Object value = pc.getParameter("value");
				if (Strings.isEmpty(path)) {
					throw new IllegalArgumentException("path parameter is required");
				}
				if (Strings.isEmpty(name)) {
					throw new IllegalArgumentException("name parameter is required");
				}

				Resource resource = context.getSession().getResource(path);

				resource.setProperty(name, value);

				List<String> boundNames = pc.applyResultBindings(Map.of("value", value));
				if (!boundNames.contains("value")) {
					pc.setBody(value);
				}
			}
		}

		/**
		 * Producer for retrieving properties from a JCR node and setting them as exchange headers.
		 *
		 * Properties are read from the node's content node — {@code jcr:content} for an
		 * {@code nt:file}, the node itself otherwise — so a route names the file and
		 * gets the file's properties.
		 *
		 * URI format: cms:getProperties?path=/content/file.txt&includes=commerce:*&excludes=commerce:secret*
		 * Parameters:
		 *   - path: Source node path (required)
		 *   - includes: Comma-separated property names to copy to headers of the same
		 *     name. Patterns: {@code name}, {@code prefix*}, {@code *suffix}
		 *   - excludes: Comma-separated names to skip, same patterns, applied to
		 *     includes and to the @header mappings alike (default: exclude none)
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *   - Header mapping: &#64;header.headerName=propertyName maps one property to
		 *     one header (&#64;header.headerName alone maps the property of that name);
		 *     &#64;header.*=prop1,prop2 maps several under their own names, and
		 *     &#64;header.*=* maps every property; &#64;header.prefix*=... and
		 *     &#64;header.*suffix=... do the same with an affix on the header name.
		 *     &#64;body=propertyName sets the body from one property.
		 *
		 * Nothing is copied by default. With neither {@code includes} nor an
		 * {@code @header} mapping this node reads the properties and sets no header at
		 * all, because "every property of the node onto the exchange" is a decision
		 * worth writing down: it puts names on the exchange that the route never asked
		 * for and that downstream nodes may match on.
		 *
		 * Values arrive typed — String, Boolean, Calendar, Double, Long, BigDecimal,
		 * a List for a multi-valued property, and the string form for anything else. A
		 * property that does not exist binds null.
		 */
		private class GetPropertiesProducer extends CmsProducer {
			private GetPropertiesProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				// Get parameters from endpoint parameters or exchange headers
				String path = (String) pc.getParameter("path");
				// Get include/exclude filters from endpoint parameters or exchange headers
				List<String> includes = pc.parseFilterList(pc.getParameter("includes"));
				List<String> excludes = pc.parseFilterList(pc.getParameter("excludes"));

				if (path == null || path.trim().isEmpty()) {
					throw new IllegalArgumentException("path parameter is required");
				}

				if (!session.nodeExists(path)) {
					throw new PathNotFoundException("Node not found: " + path);
				}

				Node node = session.getNode(path);
				Node contentNode = JCRs.getContentNode(node);

				// Apply include filters first to determine which properties to set, then apply exclude filters to skip any excluded properties.
				// This allows for flexible combinations of includes and excludes.
				for (PropertyIterator i = contentNode.getProperties(); i.hasNext();) {
					Property property = i.nextProperty();
					String propertyName = property.getName();
					if (!matches(propertyName, includes) || matches(propertyName, excludes)) {
						continue; // Skip excluded properties
					}
					pc.setHeader(propertyName, getPropertyValue(contentNode, propertyName));
				}

				// Support "@header." prefix for mapping properties to headers, and "@body" for setting the exchange body from a property.
				for (String name : pc.getParameterNames()) {
					if (name.toLowerCase().startsWith("@header.")) {
						String headerName = name.substring(8); // Remove "@header." prefix
						if (headerName.equals("")) {
							continue; // Skip if no header name is specified
						} else if (headerName.equals("*")) {
							// @header.*=propertyName1,propertyName2 syntax for mapping multiple properties with same name
							// @header.*=* syntax for mapping all properties with same name
							// @header.* syntax for mapping all properties with same name (fallback if parameter value is empty)
							String filter = Strings.defaultIfEmpty((String) pc.getParameter(name), "*").trim();
							if (filter.equals("*")) {
								for (PropertyIterator i = contentNode.getProperties(); i.hasNext();) {
									Property property = i.nextProperty();
									String propertyName = property.getName();
									if (matches(propertyName, excludes)) {
										continue; // Skip excluded properties
									}
									pc.setHeader(propertyName, getPropertyValue(contentNode, propertyName));
								}
							} else {
								for (String propertyName : pc.parseFilterList(filter)) {
									if (matches(propertyName, excludes)) {
										continue; // Skip excluded properties
									}
									pc.setHeader(propertyName, getPropertyValue(contentNode, propertyName));
								}
							}
						} else if (headerName.endsWith("*")) {
							// @header.headerName*=propertyName1,propertyName2 syntax for mapping multiple properties with common prefix
							// @header.headerName*=* syntax for mapping all properties with common prefix
							// @header.headerName* syntax for mapping all properties with common prefix (fallback if parameter value is empty)
							String filter = Strings.defaultIfEmpty((String) pc.getParameter(name), "*").trim();
							String prefix = headerName.substring(0, headerName.length() - 1);
							if (filter.equals("*")) {
								for (PropertyIterator i = contentNode.getProperties(); i.hasNext();) {
									Property property = i.nextProperty();
									String propertyName = property.getName();
									if (matches(propertyName, excludes)) {
										continue; // Skip excluded properties
									}
									pc.setHeader(prefix + propertyName, getPropertyValue(contentNode, propertyName));
								}
							} else {
								for (String propertyName : pc.parseFilterList(filter)) {
									if (matches(propertyName, excludes)) {
										continue; // Skip excluded properties
									}
									pc.setHeader(prefix + propertyName, getPropertyValue(contentNode, propertyName));
								}
							}
						} else if (headerName.startsWith("*")) {
							// @header.*headerName=propertyName1,propertyName2 syntax for mapping multiple properties with common suffix
							// @header.*headerName=* syntax for mapping all properties with common suffix
							// @header.*headerName syntax for mapping all properties with common suffix (fallback if parameter value is empty)
							String filter = Strings.defaultIfEmpty((String) pc.getParameter(name), "*").trim();
							String suffix = headerName.substring(1);
							if (filter.equals("*")) {
								for (PropertyIterator i = contentNode.getProperties(); i.hasNext();) {
									Property property = i.nextProperty();
									String propertyName = property.getName();
									if (matches(propertyName, excludes)) {
										continue; // Skip excluded properties
									}
									pc.setHeader(propertyName + suffix, getPropertyValue(contentNode, propertyName));
								}
							} else {
								for (String propertyName : pc.parseFilterList(pc.getParameter(name))) {
									if (matches(propertyName, excludes)) {
										continue; // Skip excluded properties
									}
									pc.setHeader(propertyName + suffix, getPropertyValue(contentNode, propertyName));
								}
							}
						} else {
							// @header.headerName=propertyName syntax for direct mapping
							// @header.headerName syntax for same name mapping
							String propertyName = (String) pc.getParameter(name);
							if (propertyName == null || propertyName.trim().isEmpty()) {
								propertyName = headerName; // Fallback to header name if parameter value is empty
							}
							if (matches(propertyName, excludes)) {
								continue; // Skip excluded properties
							}
							pc.setHeader(headerName, getPropertyValue(contentNode, propertyName));
						}
					} else if (name.equalsIgnoreCase("@body")) {
						String propertyName = (String) pc.getParameter(name);
						Object value = getPropertyValue(contentNode, propertyName);
						pc.getExchange().getIn().setBody(value);
					}
				}
			}

			/**
			 * Check if a property name matches any of the provided filters.
			 */
			private boolean matches(String propertyName, List<String> filters) {
				for (String filter : filters) {
					if (filter.endsWith("*")) {
						String prefix = filter.substring(0, filter.length() - 1);
						if (propertyName.startsWith(prefix)) {
							return true;
						}
					} else if (filter.startsWith("*")) {
						String suffix = filter.substring(1);
						if (propertyName.endsWith(suffix)) {
							return true;
						}
					} else if (filter.endsWith("~")) {
						String prefix = filter.substring(0, filter.length() - 1);
						if (propertyName.startsWith(prefix)) {
							return true;
						}
					} else if (filter.startsWith("~")) {
						String suffix = filter.substring(1);
						if (propertyName.endsWith(suffix)) {
							return true;
						}
					} else {
						if (propertyName.equals(filter)) {
							return true;
						}
					}
				}
				return false;
			}

			/**
			 * Get property value from a JCR node with proper type handling.
			 * Returns null if the property does not exist.
			 */
			private Object getPropertyValue(Node node, String propertyName) throws RepositoryException {
				if (!node.hasProperty(propertyName)) {
					return null;
				}

				Property property = node.getProperty(propertyName);
				if (property.isMultiple()) {
					// Handle multi-value properties as lists
					List<Object> values = new ArrayList<>();
					for (Value v : property.getValues()) {
						values.add(getSingleValue(v));
					}
					return values;
				} else {
					return getSingleValue(property.getValue());
				}
			}

			/**
			 * Convert a single JCR Value to an appropriate Java type.
			 */
			private Object getSingleValue(Value value) throws RepositoryException {
				switch (value.getType()) {
					case PropertyType.STRING:
						return value.getString();
					case PropertyType.BOOLEAN:
						return value.getBoolean();
					case PropertyType.DATE:
						return value.getDate();
					case PropertyType.DOUBLE:
						return value.getDouble();
					case PropertyType.LONG:
						return value.getLong();
					case PropertyType.DECIMAL:
						return value.getDecimal();
					default:
						return value.getString(); // Fallback to string representation for unsupported types
				}
			}
		}

		/**
		 * Producer for setting properties on JCR nodes
		 *
		 * Exchange headers starting with the specified prefix are converted to JCR properties.
		 * For nt:file nodes, properties are set on the jcr:content child node.
		 *
		 * Supported types:
		 *   - Date/Time: Calendar, Date, ZonedDateTime, OffsetDateTime, LocalDateTime, Instant
		 *   - Numeric: Long, Integer, Double, Float, BigDecimal
		 *   - Boolean, String
		 *   - Arrays and Collections (multi-value properties)
		 *
		 * Null header values remove the corresponding property.
		 *
		 * URI format: cms:setProperties?path=/content/file.txt&includes=commerce_~&excludes=commerce_secret*
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - includes: Comma-separated patterns over header names, each written as
		 *       {@code property=header}, {@code name}, {@code prefix*}, {@code *suffix},
		 *       {@code prefix~} or {@code ~suffix}. {@code *} keeps the header's name,
		 *       {@code ~} strips the affix off it. A name or pattern may carry
		 *       {@code @header.} (the default, written out) or {@code @property.} to
		 *       read exchange properties instead
		 *   - excludes: Same patterns, matched against the name the source yielded, and
		 *       applied to every branch of includes. No {@code @} prefix here: a header
		 *       and an exchange property of the same name are the same name
		 *   - delimiter: Separator for nested properties when a header value is a map
		 *       (default: dot)
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *
		 * Example:
		 *   - includes=commerce_~ will set all headers starting with "commerce_" as properties without the prefix
		 *   - includes=customHeader will set the "customHeader" header as a property with the same name
		 *   - includes=customProperty=customHeader will set the "customProperty" JCR property from the "customHeader" exchange header
		 *   - includes=customProperty=@body will set the "customProperty" JCR property from the exchange body
		 *   - includes=customProperty=@property.orderId will set the "customProperty" JCR property from the "orderId" exchange property
		 *   - includes=@property.commerce_~ will set all exchange properties starting with "commerce_" as properties without the prefix
		 *   - excludes=commerce_secret* will exclude any headers starting with "commerce_secret" from being set as properties
		 *
		 * Nothing is written without includes: a header is a property because the route
		 * said so, never because it happened to be on the exchange. Exchange properties
		 * are read only where the filter says {@code @property.}, and a wildcard there
		 * sweeps Camel's own properties along with the route's — name them, or give them
		 * a prefix of their own.
		 *
		 * A map value is expanded into one property per entry
		 * ({@code name} + delimiter + key) when the filter names the header outright.
		 * Under a wildcard the map is passed to the value conversion as it stands,
		 * because a pattern says which headers to write, not how to take them apart.
		 *
		 * A property that changes between single- and multi-valued is removed and
		 * written again, JCR having no way to change the cardinality in place.
		 */
		private class SetPropertiesProducer extends CmsProducer {
			private SetPropertiesProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);
				ValueFactory vf = session.getValueFactory();

				// Get path from endpoint parameters or exchange headers
				String path = (String) pc.getParameter("path");
				// Get include/exclude filters from endpoint parameters or exchange headers
				List<String> includes = pc.parseFilterList(pc.getParameter("includes", true));
				List<String> excludes = pc.parseFilterList(pc.getParameter("excludes"));
				// Optional delimiter for nested properties when header value is a map (default: dot)
				String delimiter = (String) pc.getParameter("delimiter");
				if (delimiter == null || delimiter.trim().isEmpty()) {
					delimiter = ".";
				}

				if (path == null || path.trim().isEmpty()) {
					throw new IllegalArgumentException("path parameter is required");
				}

				if (!session.nodeExists(path)) {
					throw new PathNotFoundException("Node not found: " + path);
				}

				Node node = session.getNode(path);
				Node contentNode = JCRs.getContentNode(node);

				// Set properties from exchange headers, or from exchange properties where the filter says so
				for (String filter : includes) {
					if (filter.indexOf("=") > 0) {
						// Support inline key=value pairs in includes parameter
						String[] parts = filter.split("=", 2);
						String propertyName = parts[0].trim();
						String sourceName = parts[1].trim();
						if (propertyName.startsWith("@")) {
							// The left of the = is the JCR property being written, not a binding.
							// @header./@property. belong on the right, where the value is read from.
							throw new IllegalArgumentException("cms:setProperties includes=" + filter
									+ " reads right to left: <property>=<source>. Write"
									+ " includes=<property>=" + propertyName + " to take the value from there.");
						}
						if (Objects.equals(sourceName.toLowerCase(), "@body")) {
							setProperty(contentNode, propertyName, pc.getExchange().getIn().getBody(), vf);
							continue;
						}
						Map<String, Object> source = sourceMap(pc, sourceName);
						String name = sourceName(filter, sourceName);
						if (source.containsKey(name) && !matches(name, excludes)) {
							setProperty(contentNode, propertyName, source.get(name), vf);
						}
						continue;
					}

					Map<String, Object> source = sourceMap(pc, filter);
					String pattern = sourceName(filter, filter);

					if (pattern.endsWith("*")) {
						String prefix = pattern.substring(0, pattern.length() - 1);
						for (Map.Entry<String, Object> entry : source.entrySet()) {
							if (entry.getKey().startsWith(prefix) && !matches(entry.getKey(), excludes)) {
								setProperty(contentNode, entry.getKey(), entry.getValue(), vf);
							}
						}
					} else if (pattern.startsWith("*")) {
						String suffix = pattern.substring(1);
						for (Map.Entry<String, Object> entry : source.entrySet()) {
							if (entry.getKey().endsWith(suffix) && !matches(entry.getKey(), excludes)) {
								setProperty(contentNode, entry.getKey(), entry.getValue(), vf);
							}
						}
					} else if (pattern.endsWith("~")) {
						String prefix = pattern.substring(0, pattern.length() - 1);
						for (Map.Entry<String, Object> entry : source.entrySet()) {
							if (entry.getKey().startsWith(prefix) && !matches(entry.getKey(), excludes)) {
								setProperty(contentNode, entry.getKey().substring(prefix.length()), entry.getValue(), vf);
							}
						}
					} else if (pattern.startsWith("~")) {
						String suffix = pattern.substring(1);
						for (Map.Entry<String, Object> entry : source.entrySet()) {
							if (entry.getKey().endsWith(suffix) && !matches(entry.getKey(), excludes)) {
								setProperty(contentNode, entry.getKey().substring(0, entry.getKey().length() - suffix.length()), entry.getValue(), vf);
							}
						}
					} else {
						if (matches(pattern, excludes)) {
							continue; // Skip if explicitly excluded
						}

						if (!source.containsKey(pattern)) {
							continue; // Skip if the header or exchange property is not present
						}

						Object value = source.get(pattern);
						if (value instanceof Map) {
							// Support nested properties for map values (e.g., "commerce_product" header with value {"name": "Product A", "price": 10} sets "commerce_product.name" and "commerce_product.price" properties)
							// delimiter is dot (.) to match common conventions, but you can choose a different one if needed
							Map<?, ?> mapValue = (Map<?, ?>) value;
							for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
								String propertyName = pattern + delimiter + entry.getKey().toString();
								setProperty(contentNode, propertyName, entry.getValue(), vf);
							}
						} else {
							setProperty(contentNode, pattern, value, vf);
						}
					}
				}

				pc.save(session);
			}

			/**
			 * Where an {@code includes} entry reads from: the exchange properties when
			 * it is written {@code @property.x}, the in-message headers otherwise.
			 * <p>
			 * The prefix sits on the name being read — on the whole entry for a pattern
			 * ({@code @property.commerce:~}), on the right of the {@code =} for a
			 * mapping ({@code commerce:orderId=@property.orderId}). {@code excludes}
			 * takes no prefix: it is matched against the name a source yielded, and a
			 * header and an exchange property of the same name are the same name.
			 */
			private Map<String, Object> sourceMap(ProcessContext pc, String name) {
				if (name.toLowerCase().startsWith("@property.")) {
					return pc.getExchange().getProperties();
				}
				return pc.getExchange().getIn().getHeaders();
			}

			/**
			 * The name to read, with any {@code @header.} / {@code @property.} prefix
			 * taken off — a header name, an exchange property name, or a pattern over
			 * either.
			 *
			 * @param filter the whole includes entry, for the message when nothing follows the prefix
			 * @param name the part of it that names the source
			 */
			private String sourceName(String filter, String name) {
				String lower = name.toLowerCase();
				String bare = name;
				if (lower.startsWith("@property.")) {
					bare = name.substring("@property.".length()).trim();
				} else if (lower.startsWith("@header.")) {
					bare = name.substring("@header.".length()).trim();
				}
				if (bare.isEmpty()) {
					throw new IllegalArgumentException("cms:setProperties includes=" + filter
							+ " names nothing after the prefix. Write @header.<name> or @property.<name>,"
							+ " a pattern such as @property.commerce:*, or a bare header name.");
				}
				return bare;
			}

			/**
			 * Check if a property name matches any of the provided filters.
			 */
			private boolean matches(String propertyName, List<String> filters) {
				for (String filter : filters) {
					if (filter.endsWith("*")) {
						String prefix = filter.substring(0, filter.length() - 1);
						if (propertyName.startsWith(prefix)) {
							return true;
						}
					} else if (filter.startsWith("*")) {
						String suffix = filter.substring(1);
						if (propertyName.endsWith(suffix)) {
							return true;
						}
					} else if (filter.endsWith("~")) {
						String prefix = filter.substring(0, filter.length() - 1);
						if (propertyName.startsWith(prefix)) {
							return true;
						}
					} else if (filter.startsWith("~")) {
						String suffix = filter.substring(1);
						if (propertyName.endsWith(suffix)) {
							return true;
						}
					} else {
						if (propertyName.equals(filter)) {
							return true;
						}
					}
				}
				return false;
			}

			/**
			 * Set a single property on the target node, handling type conversion and multi-value properties.
			 *
			 * @param targetNode The node to set the property on (e.g., jcr:content for nt:file)
			 * @param propertyName The name of the JCR property to set
			 * @param value The value to set (can be single value or collection/array for multi-value properties)
			 * @param vf The JCR ValueFactory for creating Value instances
			 */
			private void setProperty(Node targetNode, String propertyName, Object value, ValueFactory vf) throws RepositoryException {
				if (value == null) {
					// Remove property if header value is null
					if (targetNode.hasProperty(propertyName)) {
						targetNode.getProperty(propertyName).remove();
					}
					return;
				}

				// Handle single/multi-value conversion
				try {
					// Check if we need to remove existing property of different type
					if (targetNode.hasProperty(propertyName)) {
						boolean currentIsMultiple = targetNode.getProperty(propertyName).isMultiple();
						boolean newIsMultiple = (value instanceof Collection) || value.getClass().isArray();

						// If type changed (single ↔ multiple), remove old property first
						if (currentIsMultiple != newIsMultiple) {
							targetNode.getProperty(propertyName).remove();
						}
					}

					// Set property based on type
					if (value instanceof Collection || value.getClass().isArray()) {
						targetNode.setProperty(propertyName, toJcrValues(value, vf));
					} else {
						targetNode.setProperty(propertyName, toJcrValue(value, vf));
					}
				} catch (Exception e) {
					// If setting property fails, log and continue
					// (or you can throw the exception to fail the entire operation)
					throw new RepositoryException("Failed to set property '" + propertyName + "': " + e.getMessage(), e);
				}
			}

		}


		/**
		 * Producer for checking node existence
		 *
		 * URI format: cms:exists?path=/content/file.txt
		 */
		private class ExistsProducer extends CmsProducer {
			private ExistsProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();

				String path = (String) pc.getParameter("path");
				if (Strings.isEmpty(path)) {
					throw new IllegalArgumentException("path parameter is required");
				}

				Resource resource = context.getSession().getResource(path);

				boolean exists = resource.exists();

				pc.applyResultBindings(Map.of("exists", exists));
			}
		}

		/**
		 * Finds nodes by XPath, and hands the route their paths.
		 *
		 * <h2>Why searching had to become a node</h2>
		 *
		 * The routes whose logic drifted furthest into Groovy were the ones that had
		 * to <em>find</em> their work: prune what is older than the retention window,
		 * redact everything belonging to a customer, recompute the facts that are
		 * stale. A {@code <split>} can iterate a list, but until now nothing on a
		 * route could produce one, so "what are we iterating" stayed in a script and
		 * the loop on the canvas was decorative.
		 *
		 * <h2>Write the statement as a header</h2>
		 *
		 * {@code statement} is the XPath itself, and like every option it is read from
		 * the URI or, failing that, from the exchange header of that name. Take the
		 * second route. XPath is full of {@code < > [ ] ( )}, and written into a URI it
		 * needs escaping twice — once for XML, once for the query string — so the node
		 * that carries it becomes unreadable on a canvas. A {@code <setHeader>} holding
		 * the statement is one visible node saying what the route looks for.
		 *
		 * <h2>Values are part of the statement</h2>
		 *
		 * There are no bind variables. A statement that depends on a value is built
		 * where that value is — in the {@code <setHeader>}, with a {@code <simple>}
		 * expression — which also means the escaping is the route author's to get
		 * right. Quote and apostrophe are the ones that bite; a value that may contain
		 * either is worth normalising before it reaches the statement.
		 *
		 * <h2>The ceiling is always there, and truncation is visible</h2>
		 *
		 * {@code limit} defaults to 100 and must be positive, so a search always has a
		 * ceiling whether or not the route wrote one down — and a sweep that means to
		 * cover thousands must say so. Four sources come back: {@code paths} is this
		 * page, {@code count} its size, {@code total} how many matched in all, and
		 * {@code hasMore} whether anything was left beyond this page. A sweep that saw
		 * only part of its work can therefore report it instead of looking complete.
		 * On very large result sets {@code total} degrades to a lower bound, which is
		 * the one thing not to build an exact assertion on.
		 *
		 * <h2>Paging</h2>
		 *
		 * {@code offset} (default 0) skips that many matches, so a drain loop advances
		 * by pages. Bear in mind that the index is updated asynchronously and the work
		 * usually changes what it matched on: paging over a live result set can skip
		 * entries. Where the route deletes or re-marks what it processed, re-running
		 * from offset 0 until {@code hasMore} is false is the honest loop —
		 * {@code cms:list} is the other answer, since it reads through the repository
		 * and sees what the route itself just wrote.
		 *
		 * <pre>
		 * &lt;setHeader id="prune-query" name="pruneQuery"&gt;
		 *   &lt;simple&gt;/jcr:root/content/commerce/events//element(*, nt:file)[@commerce:received_at &amp;lt;= '${header.cutoff}']&lt;/simple&gt;
		 * &lt;/setHeader&gt;
		 * &lt;toD id="prune-find"
		 *      uri="cms:query?context=cmsContext&amp;amp;statement=pruneQuery&amp;amp;limit=5000&amp;amp;@header.stalePaths=paths&amp;amp;@header.staleTotal=total&amp;amp;@header.staleTruncated=hasMore"/&gt;
		 * </pre>
		 */
		private class QueryProducer extends CmsProducer {
			private QueryProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String statement = (String) pc.getParameter("statement");
				if (Strings.isEmpty(statement)) {
					throw new IllegalArgumentException("cms:query: statement is required.");
				}

				int offset = pc.getParameterAsInteger("offset", 0);

				int limit = pc.getParameterAsInteger("limit", 100);
				if (limit <= 0) {
					throw new IllegalArgumentException("cms:query: limit is " + limit
							+ ". A query that may return nothing is a node that does nothing.");
				}

				@SuppressWarnings("deprecation")
				Query query = session.getWorkspace().getQueryManager().createQuery(statement, Query.XPATH);
				query.setOffset(offset);
				query.setLimit(limit);
				QueryResult result = query.execute();

				long total = result.getNodes().getSize();
				List<String> paths = new ArrayList<>();
				for (NodeIterator i = result.getNodes(); i.hasNext();) {
					paths.add(i.nextNode().getPath());
				}

				Map<String, Object> sources = new LinkedHashMap<>();
				sources.put("paths", paths);
				sources.put("count", (long) paths.size());
				sources.put("total", total);
				sources.put("hasMore", total > (offset + paths.size()));
				pc.applyResultBindings(sources);
			}
		}

		/**
		 * Lists a node's children, and hands the route their paths.
		 *
		 * <p>Not a small {@code cms:query}: the search index is updated
		 * asynchronously, so a query can miss a node written a moment ago by the
		 * same route. Reading children goes through the repository itself and sees
		 * what is there now. A route that writes and then walks what it wrote needs
		 * this one; a route that searches a store nobody just touched wants the
		 * other.
		 *
		 * <p>Immediate children only, in repository order. There is no depth option:
		 * the difference between "the children" and "everything below" is the
		 * difference between a node and a walk of the repository, and a route that
		 * wants the second one should say so with {@code cms:query}.
		 *
		 * <p>Parameters:
		 * <ul>
		 *   <li>path: the parent (required; a path that does not exist is an error)</li>
		 *   <li>limit: ceiling on the number of children returned (default 100, must
		 *       be positive)</li>
		 *   <li>runAs: user to impersonate (optional; ignored on a joined session)</li>
		 *   <li>Output bindings: {@code paths}, {@code count}, {@code hasMore}</li>
		 * </ul>
		 *
		 * <p>The ceiling is always there, for the same reason it is on
		 * {@code cms:query}: a listing with no ceiling is a cost nobody wrote down.
		 * That was not obvious until a route needed it — the pending-marker folder
		 * this was built for holds a handful of nodes most of the time and the whole
		 * order history for the hour after a backfill seeds it. {@code count} and
		 * {@code hasMore} say when the answer was cut short, so a drain that left work
		 * behind can report it instead of looking complete. There is no {@code total}
		 * here: counting the children in full is the walk this node exists to avoid.
		 */
		private class ListProducer extends CmsProducer {
			private ListProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				if (Strings.isEmpty(path)) {
					throw new IllegalArgumentException("cms:list: path is required.");
				}
				int limit = pc.getParameterAsInteger("limit", 100);
				if (limit <= 0) {
					throw new IllegalArgumentException("cms:list: limit is " + limit
							+ ". A listing that may return nothing is a node that does nothing.");
				}

				Node node = session.getNode(path);
				List<String> paths = new ArrayList<>();
				for (NodeIterator i = node.getNodes(); i.hasNext();) {
					Node child = i.nextNode();
					paths.add(child.getPath());
					if (paths.size() > limit) {
						break;
					}
				}
				boolean hasMore = paths.size() > limit;
				if (hasMore) {
					paths = paths.subList(0, limit);
				}

				Map<String, Object> sources = new LinkedHashMap<>();
				sources.put("paths", paths);
				sources.put("count", (long) paths.size());
				sources.put("hasMore", hasMore);
				pc.applyResultBindings(sources);
			}
		}

		/**
		 * Removes a node and everything under it.
		 *
		 * <p>The other half of searching. A route that can find what is stale and
		 * cannot delete it has moved half a loop onto the canvas and left the half
		 * that matters in Groovy, so this arrives with {@code cms:query} rather than
		 * with the other change operations.
		 *
		 * <p>Parameters:
		 * <ul>
		 *   <li>path: the node to remove (required; the root is refused outright)</li>
		 *   <li>runAs: user to impersonate (optional; ignored on a joined session)</li>
		 * </ul>
		 *
		 * <p>A path that is not there is a no-op, not an error. Removal is the one
		 * operation whose goal is already met when the target is missing, and a sweep
		 * that runs twice over the same list — or races another node draining the same
		 * folder — should not fail the second time.
		 *
		 * <p>{@code removeTree()} where the repository offers it — it deletes a
		 * subtree in one statement rather than walking it — falling back to
		 * {@code remove()} otherwise. Both leave the deletion in the session, so it
		 * lands when the route commits and is discarded when the route rolls back:
		 * a deletion is part of the transaction that decided on it. On a session this
		 * node owns, it is saved before the node returns.
		 */
		private class RemoveProducer extends CmsProducer {
			private RemoveProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				if (Strings.isEmpty(path)) {
					throw new IllegalArgumentException("cms:remove: path is required.");
				}
				if ("/".equals(path.trim())) {
					throw new IllegalArgumentException("cms:remove: refusing to remove the root.");
				}

				if (session.nodeExists(path)) {
					Node node = session.getNode(path);
					if (node instanceof org.mintjams.jcr.Node) {
						// One statement rather than a walk of the subtree. Both leave the
						// deletion in the session, so it lands at cms:commit and is
						// discarded by cms:rollback - a deletion belongs to the
						// transaction that decided on it.
						((org.mintjams.jcr.Node) node).removeTree();
					} else {
						node.remove();
					}

					pc.save(session);
				}
			}
		}

		/**
		 * Producer for adding version control to a node
		 *
		 * Adds the mix:versionable mixin and creates the initial version.
		 *
		 * URI format: cms:addVersionControl?path=/content/file.txt
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *
		 * Whether a node that is already versionable is accepted or refused is the
		 * version manager's to decide; this node passes the path straight through and
		 * lets whatever it throws reach the route.
		 *
		 * Reads through the route's session, so it sees the node the route just
		 * created, and writes nothing through it: the version manager does the work
		 * in a system session of its own. Like the other version operations it is
		 * therefore durable immediately and not undone by cms:rollback, and like
		 * them it requires the route's session to have no unsaved changes — so
		 * cms:commit belongs before it.
		 */
		private class AddVersionControlProducer extends CmsProducer {
			private AddVersionControlProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				if (path == null || path.trim().isEmpty()) {
					throw new IllegalArgumentException("path parameter is required");
				}

				Adaptables.getAdapter(
						session.getWorkspace().getVersionManager(),
						org.mintjams.jcr.version.VersionManager.class
						).addVersionControl(path);
			}
		}

		/**
		 * Producer for checking out a versionable node
		 *
		 * URI format: cms:checkout?path=/content/file.txt
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *
		 * The version manager decides what an already checked-out node means; this
		 * node adds no handling of its own. Like the other version operations it works
		 * through a system session, so it is durable immediately and untouched by
		 * cms:rollback, and it requires the route's session to have no unsaved changes
		 * — cms:commit belongs before it.
		 */
		private class CheckoutProducer extends CmsProducer {
			private CheckoutProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				if (path == null || path.trim().isEmpty()) {
					throw new IllegalArgumentException("path parameter is required");
				}

				session.getWorkspace().getVersionManager().checkout(path);
			}
		}

		/**
		 * Producer for checking in a versionable node
		 *
		 * Creates a new version. The node must be checked out; if it is not, the
		 * version manager says so and the route fails — a check-in that quietly did
		 * nothing would leave the route believing a version exists.
		 *
		 * Exposes the created version name through the declared output bindings (source name: "version"),
		 * for example {@code @header.cmsVersionName=version}, {@code @body=version} or
		 * {@code @property.version=version}. Output is produced solely through these bindings.
		 *
		 * URI format: cms:checkin?path=/content/file.txt&@header.cmsVersionName=version
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *   - Output binding: @body=version, @header.headerName=version, or @property.propName=version
		 */
		private class CheckinProducer extends CmsProducer {
			private CheckinProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				if (path == null || path.trim().isEmpty()) {
					throw new IllegalArgumentException("path parameter is required");
				}

				Version version = session.getWorkspace().getVersionManager().checkin(path);

				// Expose the created version name through the declared output bindings (source name: "version")
				pc.applyResultBindings(Map.of("version", version.getName()));
			}
		}

		/**
		 * Producer for cancelling a checkout (uncheckout)
		 *
		 * Discards changes made since the last checkin and reverts to the base version.
		 *
		 * URI format: cms:uncheckout?path=/content/file.txt
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *
		 * The revert is the version manager's work and is durable immediately, so this
		 * is not the node to undo a route's own transaction with — that is
		 * cms:rollback.
		 */
		private class UncheckoutProducer extends CmsProducer {
			private UncheckoutProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				if (path == null || path.trim().isEmpty()) {
					throw new IllegalArgumentException("path parameter is required");
				}

				Adaptables.getAdapter(
						session.getWorkspace().getVersionManager(),
						org.mintjams.jcr.version.VersionManager.class
						).uncheckout(path);
			}
		}

		/**
		 * Producer for creating a checkpoint (checkin + checkout)
		 *
		 * Creates a new version and keeps the node checked out for continued editing.
		 * Requires that the node is versionable and currently checked out, and fails
		 * when it is not.
		 *
		 * Exposes the created version name through the declared output bindings (source name: "version"),
		 * for example {@code @header.cmsVersionName=version}, {@code @body=version} or
		 * {@code @property.version=version}. Output is produced solely through these bindings.
		 *
		 * URI format: cms:checkpoint?path=/content/file.txt&@header.cmsVersionName=version
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *   - Output binding: @body=version, @header.headerName=version, or @property.propName=version
		 */
		private class CheckpointProducer extends CmsProducer {
			private CheckpointProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				if (path == null || path.trim().isEmpty()) {
					throw new IllegalArgumentException("path parameter is required");
				}

				Version version = session.getWorkspace().getVersionManager().checkpoint(path);

				// Expose the created version name through the declared output bindings (source name: "version")
				pc.applyResultBindings(Map.of("version", version.getName()));
			}
		}

		/**
		 * Producer for locking a node.
		 *
		 * Acquires a lock on an existing lockable node. The lock row lives in the
		 * workspace database under a primary key on the item, so every node in the
		 * cluster and every overlapping timer tick in this JVM contend on the same
		 * insert and exactly one succeeds — which is what makes this usable as a
		 * single-execution guard, not merely as content protection.
		 *
		 * URI format: cms:lock?path=/var/locks/sweep&amp;isSessionScoped=true&amp;timeoutSeconds=240&amp;&#64;header.sweepLock=lock
		 * Parameters:
		 *   - path: Target node path (required, must already be mix:lockable)
		 *   - isDeep: Also refuse a lock on any descendant (default: false)
		 *   - isSessionScoped: Release when the session closes (default: false,
		 *     i.e. the lock persists until cms:unlock)
		 *   - timeoutSeconds: Expiry, session-scoped locks only (default: none)
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *   - Output binding: &#64;header.headerName=lock — the {@link javax.jcr.lock.Lock},
		 *     from which the route reads {@code getLockToken()} and the rest
		 *
		 * <strong>Losing the race throws.</strong> A node already locked raises a
		 * {@code LockException}, so a route using this as a guard wraps it in
		 * {@code <doTry>} and treats the {@code LockException} branch as "someone else
		 * has it" — a named step in message history, not a failure of the run. Nothing
		 * is bound in that case, so a downstream {@code <filter>} on the lock header
		 * being set works too.
		 *
		 * {@code isSessionScoped} decides the lifecycle and is worth writing out even
		 * where the default is what you want, because the two modes fail in opposite
		 * directions. A session-scoped lock is released when the session closes — by
		 * {@code cms:logout} on every exit path — and is therefore the guard mode; it
		 * only means anything inside a {@code cms:login} span, since a node that owns
		 * its session closes it before the next node runs and takes the lock with it.
		 * A persistent lock survives until {@code cms:unlock}, and one left behind by
		 * accident blocks the resource indefinitely.
		 *
		 * {@code timeoutSeconds} is how long the claim stands if nothing releases it:
		 * an expired row may be reclaimed by the next contender, so the timeout is the
		 * window in which a dead node's work is picked up again. Nothing renews it
		 * while the work runs, which makes it a bound on the work as much as on the
		 * failover — set it above the longest run you expect, not at it. It is refused
		 * on a persistent lock, which has no expiry by definition.
		 *
		 * This producer never writes to the route's session. The lock row is written
		 * by the lock manager through a session of its own, which is exactly why a
		 * critical section survives {@code cms:rollback} and is released by
		 * {@code cms:logout}. Making the target exist is a separate step —
		 * {@code cms:createFolder}, whose folders are lockable already — because JCR
		 * refuses to lock a node whose session has unsaved changes beneath it.
		 */
		private class LockProducer extends CmsProducer {
			private LockProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				if (Strings.isEmpty(path)) {
					throw new IllegalArgumentException("path parameter is required");
				}

				boolean isDeep = pc.getParameterAsBoolean("isDeep", false);
				boolean isSessionScoped = pc.getParameterAsBoolean("isSessionScoped", false);
				long timeoutSeconds = pc.getParameterAsLong("timeoutSeconds", Long.MAX_VALUE);
				if (!isSessionScoped && !Strings.isEmpty((String) pc.getParameter("timeoutSeconds"))) {
					throw new IllegalArgumentException("timeoutSeconds is only valid for session-scoped locks. For persistent locks, the lock lives until explicitly released by cms:unlock.");
				}

				Lock lock = session.getWorkspace().getLockManager()
						.lock(path, isDeep, isSessionScoped, timeoutSeconds, session.getUserID());

				Map<String, Object> results = new LinkedHashMap<>();
				results.put("lock", lock);
				pc.applyResultBindings(results);
			}
		}

		/**
		 * Producer for unlocking a node
		 *
		 * Releases the lock on the specified node.
		 *
		 * Mandatory for a persistent lock, which {@code cms:logout} deliberately does
		 * not release. For a session-scoped lock it is optional and usually left out:
		 * {@code cms:logout} releases those on every exit path, so writing an unlock
		 * as well adds a node that can throw in front of the one node that must never
		 * be skipped. Write it deliberately when the release point means something —
		 * to shorten the critical section before a long non-exclusive tail, or to
		 * take a second lock in the same route — and place it after
		 * {@code cms:commit}, so no one else can enter and read state that is still
		 * only transient here.
		 *
		 * URI format: cms:unlock?path=/var/locks/sweep&amp;context=cmsContext
		 * Parameters:
		 *   - path: Target node path (required)
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *
		 * <strong>Unlock from the session that locked.</strong> The token is not a
		 * parameter and does not need to be: the lock manager refuses to release a
		 * lock whose token the calling session does not hold, so a claim taken by
		 * another exchange — including one taken after this one's lock expired — is
		 * safe from this node. What that costs is a rule to follow: this node must
		 * join the same session {@code cms:lock} used, so a lock taken inside a
		 * {@code cms:login} span is released by a node in that same span.
		 *
		 * A node that is not locked is a {@code LockException}, not a quiet success,
		 * so an unlock written speculatively belongs in a {@code <doTry>}.
		 */
		private class UnlockProducer extends CmsProducer {
			private UnlockProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				String path = (String) pc.getParameter("path");
				if (Strings.isEmpty(path)) {
					throw new IllegalArgumentException("path parameter is required");
				}

				session.getWorkspace().getLockManager().unlock(path);
			}
		}

		/**
		 * Producer for moving nodes in JCR
		 *
		 * {@code session.move()} straight through, so JCR's rules are the node's rules:
		 * destPath is the <em>full</em> path the node is to have, name included. There
		 * is no Unix-mv convenience — naming an existing folder as the destination is
		 * an error, not a move into it — and a route that means "into this folder"
		 * builds the destination itself:
		 *
		 * URI format: cms:move?context=cmsContext&sourcePath=/content/in/x.json&destPath=/content/done/x.json
		 * Parameters:
		 *   - sourcePath: Node to move (required)
		 *   - destPath: Full destination path (required)
		 *   - runAs: User to impersonate (optional; ignored on a joined session)
		 *
		 * The repository refuses a destination that already exists, a destination whose
		 * parent does not, the root as a source, and a move of a node into its own
		 * subtree.
		 *
		 * On a session this node owns, the move is saved before the node returns. On a
		 * joined session it stays in the session, so it lands at {@code cms:commit}
		 * and is undone by {@code cms:rollback} — a move belongs to the transaction
		 * that decided on it.
		 */
		private class MoveProducer extends CmsProducer {
			private MoveProducer() {
				super(CmsEndpoint.this);
			}

			@Override
			protected void doProcess(ProcessContext pc) throws Exception {
				WorkspaceScriptContext context = pc.openContext();
				Session session = Scripts.getJcrSession(context);

				// Get parameters from endpoint parameters or exchange headers
				String sourcePath = (String) pc.getParameter("sourcePath");
				String destPath = (String) pc.getParameter("destPath");

				// Perform JCR standard move
				session.move(sourcePath, destPath);

				pc.save(session);
			}
		}
	}
}
