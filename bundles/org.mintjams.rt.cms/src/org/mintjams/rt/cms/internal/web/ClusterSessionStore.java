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

package org.mintjams.rt.cms.internal.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.NullSessionCacheFactory;
import org.eclipse.jetty.server.session.SessionCacheFactory;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionHandler;
import org.mintjams.jcr.cluster.ClusterTransport;
import org.mintjams.rt.cms.internal.CmsService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Keeps Jetty's HTTP sessions in the cluster's replicated table, so that a
 * session is the same on every node and the load balancer need not route a
 * user to one node. Registered (one object under both factory interfaces, so
 * that Jetty starts or restarts once) only in a clustered deployment; the
 * patched Felix Jetty bundle picks the factories up before it starts Jetty.
 *
 * <p>Sessions are not cached: every request reads the session from the
 * table (a local memory read) and a changed session is written back before
 * the response is committed. An unchanged session is written again at most
 * once a minute, which keeps its expiry current on the other nodes. A session
 * that never expires is dropped after {@value #NO_EXPIRY_TTL_HOURS} idle hours.
 *
 * <p>Attribute values travel as Java serialization through
 * {@link SessionAttributeCodec}; a value that cannot — a script class — is
 * left out with a warning, so it is gone on the next request.
 */
public final class ClusterSessionStore {

	static final String TABLE = "jetty/sessions";
	private static final int SAVE_PERIOD_SEC = 60;
	private static final long EXPIRY_GRACE_MILLIS = 60000L;
	private static final int NO_EXPIRY_TTL_HOURS = 12;
	private static final byte FORMAT = 1;

	private ClusterSessionStore() {}

	/**
	 * Registers the session store factories. The returned handle unregisters
	 * them.
	 */
	public static AutoCloseable register(BundleContext bundleContext, ClusterTransport transport) {
		Factory factory = new Factory(transport.getTable(TABLE), new SessionAttributeCodec(bundleContext));
		ServiceRegistration<?> registration = bundleContext.registerService(
				new String[] { SessionDataStoreFactory.class.getName(), SessionCacheFactory.class.getName() },
				factory, null);
		CmsService.getLogger(ClusterSessionStore.class).info("HTTP sessions are kept in the cluster's replicated table.");
		return () -> {
			try {
				registration.unregister();
			} catch (IllegalStateException ignore) {}
		};
	}

	/** Both factories in one: no session cache, the replicated table as the store. */
	static final class Factory extends NullSessionCacheFactory implements SessionDataStoreFactory {
		private final ClusterTransport.Table fTable;
		private final SessionAttributeCodec fCodec;

		Factory(ClusterTransport.Table table, SessionAttributeCodec codec) {
			fTable = table;
			fCodec = codec;
			// Written before the response is committed, so that the request the
			// browser makes next — possibly to another node — finds it.
			setFlushOnResponseCommit(true);
			setSaveOnCreate(true);
		}

		@Override
		public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception {
			Store store = new Store(fTable, fCodec);
			store.setSavePeriodSec(SAVE_PERIOD_SEC);
			return store;
		}
	}

	static final class Store extends AbstractSessionDataStore {
		private final ClusterTransport.Table fTable;
		private final SessionAttributeCodec fCodec;
		private final Set<String> fReported = ConcurrentHashMap.newKeySet();

		Store(ClusterTransport.Table table, SessionAttributeCodec codec) {
			fTable = table;
			fCodec = codec;
		}

		private String prefix() {
			return _context.getCanonicalContextPath() + "|" + _context.getVhost() + "|";
		}

		private String key(String id) {
			return prefix() + id;
		}

		@Override
		public boolean isPassivating() {
			return true;
		}

		@Override
		public boolean doExists(String id) throws Exception {
			long expiry = expiryOf(fTable.get(key(id)));
			return expiry >= 0 && (expiry == 0 || expiry > System.currentTimeMillis());
		}

		@Override
		public void doStore(String id, SessionData data, long lastSaveTime) throws Exception {
			long now = System.currentTimeMillis();
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(bytes)) {
				out.writeByte(FORMAT);
				out.writeUTF(data.getId());
				out.writeUTF(nonNull(data.getLastNode()));
				out.writeLong(data.getCreated());
				out.writeLong(data.getAccessed());
				out.writeLong(data.getLastAccessed());
				out.writeLong(data.getCookieSet());
				out.writeLong(data.getExpiry());
				out.writeLong(data.getMaxInactiveMs());
				out.writeLong(now);

				Map<String, byte[]> attributes = new HashMap<>();
				for (String name : data.getKeys()) {
					Object value = data.getAttribute(name);
					if (value == null) {
						continue;
					}
					try {
						attributes.put(name, fCodec.serialize(value));
					} catch (IOException ex) {
						if (fReported.add(name + "|" + value.getClass().getName())) {
							CmsService.getLogger(ClusterSessionStore.class).warn("The session attribute '" + name
									+ "' (" + value.getClass().getName() + ") cannot be shared across the cluster"
									+ " and is left out of the session: " + ex.getMessage());
						}
					}
				}
				out.writeInt(attributes.size());
				for (Map.Entry<String, byte[]> e : attributes.entrySet()) {
					out.writeUTF(e.getKey());
					out.writeInt(e.getValue().length);
					out.write(e.getValue());
				}
			}

			long expiry = data.getExpiry();
			long ttl = (expiry > 0) ? Math.max(expiry - now, 0L) + EXPIRY_GRACE_MILLIS
					: NO_EXPIRY_TTL_HOURS * 3600000L;
			fTable.put(key(id), expiry + "|" + Base64.getEncoder().encodeToString(bytes.toByteArray()), ttl);
		}

		@Override
		public SessionData doLoad(String id) throws Exception {
			String value = fTable.get(key(id));
			if (value == null) {
				return null;
			}
			byte[] bytes = Base64.getDecoder().decode(value.substring(value.indexOf('|') + 1));
			try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
				if (in.readByte() != FORMAT) {
					return null;
				}
				String storedId = in.readUTF();
				String lastNode = in.readUTF();
				long created = in.readLong();
				long accessed = in.readLong();
				long lastAccessed = in.readLong();
				long cookieSet = in.readLong();
				long expiry = in.readLong();
				long maxInactiveMs = in.readLong();
				long lastSaved = in.readLong();

				SessionData data = newSessionData(storedId, created, accessed, lastAccessed, maxInactiveMs);
				data.setLastNode(lastNode.isEmpty() ? null : lastNode);
				data.setCookieSet(cookieSet);
				data.setExpiry(expiry);
				data.setLastSaved(lastSaved);

				Map<String, Object> attributes = new HashMap<>();
				int count = in.readInt();
				for (int i = 0; i < count; i++) {
					String name = in.readUTF();
					byte[] serialized = new byte[in.readInt()];
					in.readFully(serialized);
					try {
						attributes.put(name, fCodec.deserialize(serialized));
					} catch (Exception ex) {
						if (fReported.add("load|" + name + "|" + ex.getMessage())) {
							CmsService.getLogger(ClusterSessionStore.class).warn("The session attribute '" + name
									+ "' could not be restored and is left out of the session: " + ex.getMessage());
						}
					}
				}
				data.putAllAttributes(attributes);
				data.setDirty(false);
				data.setMetaDataDirty(false);
				return data;
			}
		}

		@Override
		public boolean delete(String id) throws Exception {
			String key = key(id);
			boolean existed = fTable.get(key) != null;
			fTable.remove(key);
			return existed;
		}

		@Override
		public Set<String> doCheckExpired(Set<String> candidates, long time) {
			Set<String> expired = new HashSet<>();
			for (String id : candidates) {
				long expiry = expiryOf(fTable.get(key(id)));
				// Gone from the table: invalidated or expired on another node.
				if (expiry < 0 || (expiry > 0 && expiry <= time)) {
					expired.add(id);
				}
			}
			return expired;
		}

		@Override
		public Set<String> doGetExpired(long time) {
			Set<String> expired = new HashSet<>();
			String prefix = prefix();
			for (String key : fTable.keySet()) {
				if (!key.startsWith(prefix)) {
					continue;
				}
				long expiry = expiryOf(fTable.get(key));
				if (expiry > 0 && expiry <= time) {
					expired.add(key.substring(prefix.length()));
				}
			}
			return expired;
		}

		@Override
		public void doCleanOrphans(long time) {
			String prefix = prefix();
			for (String key : fTable.keySet()) {
				if (!key.startsWith(prefix)) {
					continue;
				}
				long expiry = expiryOf(fTable.get(key));
				if (expiry > 0 && expiry <= time) {
					fTable.remove(key);
				}
			}
		}

		/** The expiry recorded with a stored session, 0 for never, -1 when absent. */
		private static long expiryOf(String value) {
			if (value == null) {
				return -1L;
			}
			try {
				return Long.parseLong(value.substring(0, value.indexOf('|')));
			} catch (RuntimeException ex) {
				return -1L;
			}
		}

		private static String nonNull(String value) {
			return (value == null) ? "" : value;
		}
	}

}
