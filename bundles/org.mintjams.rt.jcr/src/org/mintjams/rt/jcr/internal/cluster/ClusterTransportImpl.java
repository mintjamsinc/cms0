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

package org.mintjams.rt.jcr.internal.cluster;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.mintjams.jcr.cluster.ClusterCoordinator;
import org.mintjams.jcr.cluster.ClusterTransport;
import org.mintjams.rt.jcr.internal.Activator;
import org.mintjams.rt.jcr.internal.JcrRepositoryConfiguration;
import org.mintjams.tools.lang.Strings;

/**
 * The connections between the cluster nodes: one listening socket on a
 * dedicated port (never the public HTTP port), and one outbound connection
 * to every other live node. A node only writes to the connections it opened
 * and only reads from the ones it accepted, except that an answer to a
 * request goes back on the connection the request came in on. Who is alive,
 * and where it listens, is decided by the heartbeat in
 * {@code jcr_cluster_nodes}; a connection is only a means.
 *
 * <p>Everything sent here is a shortcut: a wake-up only shortens the journal
 * poll, a signal reaches only the nodes connected at that moment, a table
 * write that does not arrive is covered by the full copy a node receives when
 * it connects. Sending never blocks the caller — frames are queued per node
 * and dropped when the node is not connected or does not keep up.
 *
 * <p>Every frame is signed with the key derived from
 * {@code cluster-secret-key.yml}; a connection that sends a frame with a
 * wrong signature, or a time more than 30 seconds off, is dropped, and a
 * connection from an address that is not a live member's is dropped before
 * anything is read. The content is not encrypted: the port belongs on the
 * management network.
 */
public final class ClusterTransportImpl implements ClusterTransport, Closeable {

	private static final int CONNECT_TIMEOUT_MILLIS = 3000;
	private static final long MIN_BACKOFF_MILLIS = 1000L;
	private static final long MAX_BACKOFF_MILLIS = 30000L;
	private static final long MAINTENANCE_INTERVAL_MILLIS = 1000L;
	private static final long MEMBERS_CACHE_MILLIS = 5000L;
	private static final long LINKS_REPORT_INTERVAL_MILLIS = 10000L;
	private static final long LINKS_REPORT_TTL_MILLIS = 60000L;
	private static final long MAX_CLOCK_SKEW_MILLIS = 30000L;
	private static final int MAX_QUEUED_FRAMES = 10000;
	private static final int BATCH_BODY_BYTES = 48 * 1024;
	private static final int MAX_REQUEST_THREADS = 16;
	private static final int MAX_QUEUED_REQUESTS = 256;

	/** Each node's report of its own connections, keyed by node id. */
	static final String LINKS_TABLE = "cluster/links";

	/**
	 * Supplies the registered cluster members; the heartbeat table is the
	 * authority on who is alive and where it listens.
	 */
	public interface MemberSource {
		List<ClusterCoordinator.Member> listMembers() throws IOException;
	}

	private final JcrRepositoryConfiguration fConfiguration;
	private final String fNodeId;
	private final MemberSource fMemberSource;
	private final Consumer<String> fWakeListener;

	private byte[] fKey;
	private String fAdvertisedAddress;
	private ServerSocket fServerSocket;
	private Thread fAcceptor;
	private Thread fMaintenance;
	private ThreadPoolExecutor fRequestExecutor;
	private volatile boolean fClosed;
	private final Object fMaintenanceLock = new Object();

	private final ConcurrentMap<String, Peer> fPeers = new ConcurrentHashMap<>();
	private final Set<Inbound> fInbounds = ConcurrentHashMap.newKeySet();
	private final ConcurrentMap<String, LinkStats> fStats = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, ReplicatedTable> fTables = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, RequestHandler> fHandlers = new ConcurrentHashMap<>();
	private final AtomicLong fVersion = new AtomicLong();
	private final AtomicInteger fRequestIds = new AtomicInteger();
	private final Set<String> fWarned = ConcurrentHashMap.newKeySet();

	private volatile List<ClusterCoordinator.Member> fMembers = Collections.emptyList();
	private volatile long fMembersLoaded;
	private volatile boolean fLinksChanged;
	private long fLinksReported;

	private ClusterTransportImpl(JcrRepositoryConfiguration configuration, MemberSource memberSource,
			Consumer<String> wakeListener) {
		fConfiguration = configuration;
		fNodeId = configuration.getClusterNodeId();
		fMemberSource = memberSource;
		fWakeListener = wakeListener;
	}

	public static ClusterTransportImpl create(JcrRepositoryConfiguration configuration, MemberSource memberSource,
			Consumer<String> wakeListener) {
		return new ClusterTransportImpl(configuration, memberSource, wakeListener);
	}

	/**
	 * Reads the cluster key and starts listening. A node that cannot read the
	 * key, or cannot determine or bind the address at which the other nodes
	 * reach it, does not start at all: it would be half a cluster node — its
	 * journal would follow through the database polling, but its sessions and
	 * subscription streams would never reach the other nodes, and behind a
	 * load balancer without sticky routing its users would fail at random.
	 * The exception names the setting to fix.
	 */
	public synchronized ClusterTransportImpl open() throws IOException {
		fRequestExecutor = new ThreadPoolExecutor(0, MAX_REQUEST_THREADS, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(MAX_QUEUED_REQUESTS), r -> {
					Thread t = new Thread(r, "ClusterTransport-Request");
					t.setDaemon(true);
					return t;
				});

		try {
			fKey = ClusterSecretKey.derive(fConfiguration.getClusterSecretKeyPath(), "transport/v1");
			listen();
		} catch (IOException ex) {
			closeServerSocket();
			fRequestExecutor.shutdownNow();
			throw new IOException("The cluster connections could not be opened: " + ex.getMessage(), ex);
		} catch (RuntimeException ex) {
			closeServerSocket();
			fRequestExecutor.shutdownNow();
			throw new IOException("The cluster connections could not be opened: " + ex, ex);
		}

		fMaintenance = new Thread(this::maintain, "ClusterTransport-Maintenance");
		fMaintenance.setDaemon(true);
		fMaintenance.start();
		return this;
	}

	/**
	 * Decides where to listen and where to be reached, then listens. The bind
	 * address (where the port is opened) and the advertise address (what the
	 * other nodes are told) are separate settings; without a bind address the
	 * port is opened on the advertised address itself — never on every
	 * interface, which would expose it on the public network of a host with
	 * several — and without either the node's only site-local address is used
	 * for both. Anything that does not resolve to one address of this host is
	 * an error.
	 */
	private void listen() throws IOException {
		int port = fConfiguration.getClusterPort();
		String bind = fConfiguration.getClusterBindAddress();
		String advertise = fConfiguration.getClusterAdvertiseAddress();
		String settings = "org.mintjams.jcr.cluster.bindAddress (CMS_CLUSTER_BIND_ADDRESS) and"
				+ " org.mintjams.jcr.cluster.advertiseAddress (CMS_CLUSTER_ADVERTISE_ADDRESS)";

		InetAddress bindAddress = null;
		if (bind != null) {
			try {
				bindAddress = InetAddress.getByName(bind);
			} catch (IOException ex) {
				throw new IOException("The cluster bind address '" + bind + "' cannot be resolved; see " + settings + ".", ex);
			}
		}

		String advertisedHost;
		int advertisedPort = port;
		if (advertise != null) {
			String[] hostPort = splitHostPort(advertise);
			advertisedHost = hostPort[0];
			if (hostPort[1] != null) {
				advertisedPort = Integer.parseInt(hostPort[1]);
			}
			if (bindAddress == null) {
				bindAddress = localAddressOf(advertisedHost);
				if (bindAddress == null) {
					throw new IOException("The cluster advertise address '" + advertise + "' does not name an address of"
							+ " this host, so there is no interface to listen on. Set the bind address to the management"
							+ " interface; see " + settings + ".");
				}
			}
		} else if (bindAddress != null && !bindAddress.isAnyLocalAddress()) {
			advertisedHost = bindAddress.getHostAddress();
		} else {
			List<InetAddress> candidates = listSiteLocalAddresses();
			if (candidates.size() != 1) {
				throw new IOException("The address under which the other cluster nodes reach this node could not be"
						+ " determined (site-local addresses: " + candidates + "). Set the advertise address and, to"
						+ " keep the port off the public network, the bind address; see " + settings + ".");
			}
			advertisedHost = candidates.get(0).getHostAddress();
			if (bindAddress == null) {
				bindAddress = candidates.get(0);
			}
		}

		ServerSocket serverSocket = new ServerSocket();
		serverSocket.setReuseAddress(true);
		try {
			serverSocket.bind(new InetSocketAddress(bindAddress, port), 50);
		} catch (IOException ex) {
			closeQuietly(serverSocket);
			throw new IOException("The cluster port could not be opened at " + bindAddress.getHostAddress() + ":" + port
					+ " (" + ex.getMessage() + "); see org.mintjams.jcr.cluster.port (CMS_CLUSTER_PORT) and " + settings + ".", ex);
		}
		fServerSocket = serverSocket;
		fAdvertisedAddress = joinHostPort(advertisedHost, advertisedPort);

		fAcceptor = new Thread(this::accept, "ClusterTransport-Acceptor");
		fAcceptor.setDaemon(true);
		fAcceptor.start();

		Activator.getDefault().getLogger(getClass()).info("Cluster node '" + fNodeId + "' accepts cluster connections on "
				+ serverSocket.getLocalSocketAddress() + " and is reached at " + fAdvertisedAddress + ".");
	}

	/**
	 * Returns the address of this host that the name resolves to (the
	 * loopback counts, for several nodes on one host), or {@code null} when
	 * none of the resolved addresses belongs to this host.
	 */
	private static InetAddress localAddressOf(String host) throws IOException {
		InetAddress[] resolved;
		try {
			resolved = InetAddress.getAllByName(host);
		} catch (IOException ex) {
			throw new IOException("The cluster advertise address '" + host + "' cannot be resolved.", ex);
		}
		for (InetAddress address : resolved) {
			if (address.isAnyLocalAddress()) {
				continue;
			}
			if (address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null) {
				return address;
			}
		}
		return null;
	}

	private static List<InetAddress> listSiteLocalAddresses() throws IOException {
		List<InetAddress> addresses = new ArrayList<>();
		Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
		while (interfaces != null && interfaces.hasMoreElements()) {
			NetworkInterface networkInterface = interfaces.nextElement();
			if (!networkInterface.isUp() || networkInterface.isLoopback()) {
				continue;
			}
			for (Enumeration<InetAddress> e = networkInterface.getInetAddresses(); e.hasMoreElements();) {
				InetAddress address = e.nextElement();
				if (address.isSiteLocalAddress() && !address.isLoopbackAddress()) {
					addresses.add(address);
				}
			}
		}
		return addresses;
	}

	static String[] splitHostPort(String value) {
		value = value.trim();
		if (value.startsWith("[")) {
			int end = value.indexOf(']');
			String host = value.substring(1, end);
			String port = (end + 1 < value.length() && value.charAt(end + 1) == ':') ? value.substring(end + 2) : null;
			return new String[] { host, Strings.isEmpty(port) ? null : port };
		}
		int colon = value.lastIndexOf(':');
		if (colon > 0 && value.indexOf(':') == colon) {
			return new String[] { value.substring(0, colon), value.substring(colon + 1) };
		}
		return new String[] { value, null };
	}

	private static String joinHostPort(String host, int port) {
		return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
	}

	/**
	 * Returns the address ({@code host:port}) under which the other nodes reach
	 * this one, or {@code null} when this node does not listen.
	 */
	public String getAdvertisedAddress() {
		return fAdvertisedAddress;
	}

	@Override
	public String getNodeId() {
		return fNodeId;
	}

	// ---------------------------------------------------------------------
	// Sending
	// ---------------------------------------------------------------------

	/**
	 * Wakes the journal poller of the workspace on every other node. Called
	 * after a commit that wrote a journal marker; never blocks.
	 */
	public void wake(String workspaceName) {
		for (Peer peer : fPeers.values()) {
			peer.wake(workspaceName);
		}
	}

	/**
	 * Sends an event to every connected node, which posts it locally. Never
	 * blocks; a node that is not connected does not receive it.
	 */
	public void signal(String topic, Map<String, Object> properties) {
		Map<String, Object> body = newBody();
		body.put("topic", topic);
		body.put("properties", (properties == null) ? Collections.emptyMap() : properties);
		byte[] bytes;
		try {
			bytes = encode(body);
		} catch (IOException ex) {
			Activator.getDefault().getLogger(getClass()).warn("The cluster signal '" + topic + "' could not be encoded.", ex);
			return;
		}
		if (bytes.length > ClusterFrame.MAX_BODY_BYTES) {
			Activator.getDefault().getLogger(getClass()).warn("The cluster signal '" + topic + "' is too large to send ("
					+ bytes.length + " bytes).");
			return;
		}
		for (Peer peer : fPeers.values()) {
			if (!peer.enqueue(new ClusterFrame(ClusterFrame.SIGNAL, 0, bytes))) {
				Activator.getDefault().getLogger(getClass()).debug("The cluster signal '" + topic
						+ "' was not sent to node '" + peer.fNodeId + "' (not connected).");
			}
		}
	}

	@Override
	public Map<String, Object> request(String nodeId, String type, Map<String, Object> payload, long timeoutMillis)
			throws IOException {
		Peer peer = fPeers.get(nodeId);
		if (peer == null || !peer.isConnected()) {
			throw new IOException("Cluster node '" + nodeId + "' is not connected.");
		}

		Map<String, Object> body = newBody();
		body.put("type", type);
		body.put("payload", (payload == null) ? Collections.emptyMap() : payload);
		byte[] bytes = encode(body);
		if (bytes.length > ClusterFrame.MAX_BODY_BYTES) {
			throw new IOException("The request is too large to send (" + bytes.length + " bytes).");
		}

		int requestId = fRequestIds.incrementAndGet();
		CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
		peer.fPendingRequests.put(requestId, answer);
		try {
			if (!peer.enqueue(new ClusterFrame(ClusterFrame.REQUEST, requestId, bytes))) {
				throw new IOException("Cluster node '" + nodeId + "' is not connected.");
			}
			return answer.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (TimeoutException ex) {
			throw new IOException("Cluster node '" + nodeId + "' did not answer within " + timeoutMillis + " ms.");
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for cluster node '" + nodeId + "'.");
		} catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			throw (cause instanceof IOException) ? (IOException) cause : new IOException(cause);
		} finally {
			peer.fPendingRequests.remove(requestId);
		}
	}

	@Override
	public AutoCloseable setRequestHandler(String type, RequestHandler handler) {
		fHandlers.put(type, handler);
		return () -> fHandlers.remove(type, handler);
	}

	@Override
	public ReplicatedTable getTable(String name) {
		return fTables.computeIfAbsent(name, k -> new ReplicatedTable(this, k));
	}

	/** Sends one table write to every connected node. */
	void replicate(byte kind, ReplicatedTable table, String key, ReplicatedTable.Entry entry) {
		if (fPeers.isEmpty()) {
			return;
		}
		Map<String, Object> body = newBody();
		body.put("table", table.getName());
		body.put("entries", List.of(entry.toMap(key)));
		byte[] bytes;
		try {
			bytes = encode(body);
		} catch (IOException ex) {
			Activator.getDefault().getLogger(getClass()).warn("A value of the replicated table '" + table.getName()
					+ "' could not be encoded.", ex);
			return;
		}
		if (bytes.length > ClusterFrame.MAX_BODY_BYTES) {
			// Not expected: the table refuses values past its limit. Said out
			// loud all the same, since a value that stays here is a session or
			// a stream that the other nodes do not know.
			Activator.getDefault().getLogger(getClass()).warn("The value of the key '" + key + "' in the replicated table '"
					+ table.getName() + "' is too large to send (" + bytes.length + " bytes; the limit is "
					+ ClusterFrame.MAX_BODY_BYTES + "); it stays on this node and the other nodes do not see it.");
			return;
		}
		for (Peer peer : fPeers.values()) {
			peer.enqueue(new ClusterFrame(kind, 0, bytes));
		}
	}

	long nextVersion() {
		for (;;) {
			long last = fVersion.get();
			long next = Math.max(System.currentTimeMillis(), last + 1);
			if (fVersion.compareAndSet(last, next)) {
				return next;
			}
		}
	}

	void observeVersion(long version) {
		fVersion.accumulateAndGet(version, Math::max);
	}

	private Map<String, Object> newBody() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("node", fNodeId);
		body.put("time", System.currentTimeMillis());
		return body;
	}

	private static byte[] encode(Map<String, Object> body) throws IOException {
		return Activator.getDefault().toJSON(body).getBytes(StandardCharsets.UTF_8);
	}

	private static Map<String, Object> decode(byte[] body) throws IOException {
		Map<String, Object> map = Activator.getDefault().parseJSON(new String(body, StandardCharsets.UTF_8));
		if (map == null) {
			throw new IOException("An empty frame body.");
		}
		return map;
	}

	// ---------------------------------------------------------------------
	// Links
	// ---------------------------------------------------------------------

	private LinkStats stats(String nodeId) {
		return fStats.computeIfAbsent(nodeId, k -> new LinkStats());
	}

	/**
	 * Returns this node's view of its connection to the given node, or
	 * {@code null} for this node itself.
	 */
	public ClusterCoordinator.Link getLink(String nodeId) {
		if (nodeId == null || nodeId.equals(fNodeId)) {
			return null;
		}
		Peer peer = fPeers.get(nodeId);
		LinkStats stats = stats(nodeId);
		String outbound = (peer == null) ? "down" : peer.fState;
		long connectedSince = (peer != null && "connected".equals(outbound)) ? peer.fConnectedSince : 0L;
		return new LinkImpl(nodeId, outbound, connectedSince, (stats.fInbound.get() > 0) ? "connected" : "none",
				stats.fLastWakeSent, stats.fLastWakeReceived, stats.fRejected.get(),
				stats.fDroppedWhileDisconnected.get(), stats.fDroppedOnOverflow.get());
	}

	/**
	 * Returns the given node's connections as it last reported them through
	 * the replicated table.
	 */
	public List<ClusterCoordinator.Link> getReportedLinks(String nodeId) {
		String report = getTable(LINKS_TABLE).get(nodeId);
		if (report == null) {
			return Collections.emptyList();
		}
		List<ClusterCoordinator.Link> links = new ArrayList<>();
		try {
			Map<String, Object> map = Activator.getDefault().parseJSON(report);
			Object list = (map == null) ? null : map.get("links");
			if (list instanceof List) {
				for (Object e : (List<?>) list) {
					if (e instanceof Map) {
						links.add(LinkImpl.fromMap((Map<?, ?>) e));
					}
				}
			}
		} catch (Throwable ex) {
			Activator.getDefault().getLogger(getClass()).debug("Could not read the link report of node '" + nodeId + "'.", ex);
		}
		return links;
	}

	private void reportLinks(long now) {
		if (!fLinksChanged && now - fLinksReported < LINKS_REPORT_INTERVAL_MILLIS) {
			return;
		}
		fLinksChanged = false;
		fLinksReported = now;

		Set<String> nodeIds = new HashSet<>(fPeers.keySet());
		for (ClusterCoordinator.Member member : fMembers) {
			if (member.isAlive() && !fNodeId.equals(member.getNodeId())) {
				nodeIds.add(member.getNodeId());
			}
		}
		List<Map<String, Object>> links = new ArrayList<>();
		for (String nodeId : nodeIds) {
			links.add(((LinkImpl) getLink(nodeId)).toMap());
		}
		try {
			getTable(LINKS_TABLE).put(fNodeId, Activator.getDefault().toJSON(Map.of("links", links)),
					LINKS_REPORT_TTL_MILLIS);
		} catch (Throwable ex) {
			Activator.getDefault().getLogger(getClass()).debug("Could not report this node's links.", ex);
		}
	}

	// ---------------------------------------------------------------------
	// Membership and maintenance
	// ---------------------------------------------------------------------

	private void maintain() {
		while (!fClosed) {
			synchronized (fMaintenanceLock) {
				try {
					fMaintenanceLock.wait(MAINTENANCE_INTERVAL_MILLIS);
				} catch (InterruptedException ignore) {}
			}
			if (fClosed) {
				break;
			}

			long now = System.currentTimeMillis();
			try {
				if (now - fMembersLoaded >= MEMBERS_CACHE_MILLIS) {
					loadMembers();
				}
				if (fServerSocket != null) {
					syncPeers();
				}
			} catch (Throwable ex) {
				if (fWarned.add("members")) {
					Activator.getDefault().getLogger(getClass()).warn("The cluster members could not be read.", ex);
				}
			}
			try {
				for (ReplicatedTable table : fTables.values()) {
					table.purge(now);
				}
				reportLinks(now);
			} catch (Throwable ex) {
				Activator.getDefault().getLogger(getClass()).warn("An error occurred while maintaining the cluster tables.", ex);
			}
		}
	}

	private void loadMembers() throws IOException {
		List<ClusterCoordinator.Member> members = fMemberSource.listMembers();
		if (members != null) {
			fMembers = members;
			fMembersLoaded = System.currentTimeMillis();
			fWarned.remove("members");
		}
	}

	/** Opens a connection to every live member and closes the others. */
	private void syncPeers() {
		Map<String, String> wanted = new HashMap<>();
		for (ClusterCoordinator.Member member : fMembers) {
			if (fNodeId.equals(member.getNodeId()) || !member.isAlive() || Strings.isEmpty(member.getAddress())) {
				continue;
			}
			wanted.put(member.getNodeId(), member.getAddress());
		}

		for (Map.Entry<String, String> e : wanted.entrySet()) {
			Peer peer = fPeers.get(e.getKey());
			if (peer == null) {
				peer = new Peer(e.getKey(), e.getValue());
				fPeers.put(e.getKey(), peer);
				peer.start();
				fLinksChanged = true;
			} else if (!e.getValue().equals(peer.fAddress)) {
				peer.fAddress = e.getValue();
				peer.disconnect();
			}
		}
		for (Peer peer : new ArrayList<>(fPeers.values())) {
			if (!wanted.containsKey(peer.fNodeId)) {
				fPeers.remove(peer.fNodeId, peer);
				peer.stop();
				fLinksChanged = true;
			}
		}
	}

	/**
	 * Whether a connection from the given address may be a member's: the
	 * loopback (several nodes on one host) or the host of a live member's
	 * address. A miss rereads the members once, so a node that has just
	 * joined is not refused.
	 */
	private boolean isMemberAddress(InetAddress address) {
		if (address.isLoopbackAddress()) {
			return true;
		}
		if (matchesMember(address)) {
			return true;
		}
		try {
			loadMembers();
		} catch (Throwable ignore) {}
		return matchesMember(address);
	}

	private boolean matchesMember(InetAddress address) {
		return !membersAt(address).isEmpty();
	}

	private List<String> membersAt(InetAddress address) {
		List<String> nodeIds = new ArrayList<>();
		for (ClusterCoordinator.Member member : fMembers) {
			if (!member.isAlive() || Strings.isEmpty(member.getAddress()) || fNodeId.equals(member.getNodeId())) {
				continue;
			}
			try {
				for (InetAddress a : InetAddress.getAllByName(splitHostPort(member.getAddress())[0])) {
					if (a.equals(address)) {
						nodeIds.add(member.getNodeId());
						break;
					}
				}
			} catch (Throwable ignore) {}
		}
		return nodeIds;
	}

	// ---------------------------------------------------------------------
	// Receiving
	// ---------------------------------------------------------------------

	private void accept() {
		while (!fClosed) {
			Socket socket;
			try {
				socket = fServerSocket.accept();
			} catch (Throwable ex) {
				if (!fClosed) {
					Activator.getDefault().getLogger(getClass()).warn("An error occurred while accepting a cluster connection.", ex);
					try {
						Thread.sleep(1000);
					} catch (InterruptedException ignore) {}
				}
				continue;
			}

			InetAddress address = socket.getInetAddress();
			if (!isMemberAddress(address)) {
				if (fWarned.add("refused:" + address.getHostAddress())) {
					Activator.getDefault().getLogger(getClass()).warn("Refused a cluster connection from "
							+ address.getHostAddress() + ", which is not the address of a live cluster member.");
				}
				closeQuietly(socket);
				continue;
			}

			Inbound inbound = new Inbound(socket);
			fInbounds.add(inbound);
			Thread t = new Thread(inbound, "ClusterTransport-Inbound-" + address.getHostAddress());
			t.setDaemon(true);
			t.start();
		}
	}

	/**
	 * Checks a received frame's sender and time; returns the sender's node id.
	 */
	private String checkSender(Map<String, Object> body) throws ClusterFrame.SignatureException {
		Object node = body.get("node");
		Object time = body.get("time");
		if (!(node instanceof String) || !(time instanceof Number)) {
			throw new ClusterFrame.SignatureException("The frame carries no sender or time.");
		}
		long skew = Math.abs(System.currentTimeMillis() - ((Number) time).longValue());
		if (skew > MAX_CLOCK_SKEW_MILLIS) {
			throw new ClusterFrame.SignatureException("The frame was sent " + skew + " ms away from this node's clock"
					+ " (the cluster nodes must run with synchronized clocks).");
		}
		return (String) node;
	}

	private void rejected(String nodeId, InetAddress address, IOException ex) {
		if (nodeId == null) {
			List<String> nodeIds = membersAt(address);
			if (nodeIds.size() == 1) {
				nodeId = nodeIds.get(0);
			}
		}
		if (nodeId != null) {
			stats(nodeId).fRejected.incrementAndGet();
			fLinksChanged = true;
		}
		String who = (nodeId != null) ? "node '" + nodeId + "' (" + address.getHostAddress() + ")"
				: address.getHostAddress();
		if (fWarned.add("rejected:" + ((nodeId != null) ? nodeId : address.getHostAddress()))) {
			Activator.getDefault().getLogger(getClass()).warn("Dropped the cluster connection from " + who + ": "
					+ ex.getMessage() + " Check that every node uses the same cluster-secret-key.yml."
					+ " Until then this node relies on the database polling for that node, and without sticky"
					+ " routing the sessions and subscription streams of the two nodes are not shared.");
		}
	}

	/**
	 * A frame from the node verified: when its connection had been refused,
	 * says that it is accepted again, so that the log shows the fix worked.
	 */
	private void accepted(String nodeId, InetAddress address) {
		boolean warned = fWarned.remove("rejected:" + nodeId);
		if (address != null) {
			warned |= fWarned.remove("rejected:" + address.getHostAddress());
		}
		if (warned) {
			Activator.getDefault().getLogger(getClass()).info("The cluster connection from node '" + nodeId
					+ "' is accepted again: its frames are correctly signed.");
		}
	}

	@SuppressWarnings("unchecked")
	private void dispatch(ClusterFrame frame, String fromNodeId, Map<String, Object> body, Inbound inbound) {
		switch (frame.fKind) {
		case ClusterFrame.HELLO:
			break;
		case ClusterFrame.WAKE: {
			stats(fromNodeId).fLastWakeReceived = System.currentTimeMillis();
			Object workspace = body.get("workspace");
			if (workspace instanceof String) {
				try {
					fWakeListener.accept((String) workspace);
				} catch (Throwable ex) {
					Activator.getDefault().getLogger(getClass()).debug("Could not wake the workspace '" + workspace + "'.", ex);
				}
			}
			break;
		}
		case ClusterFrame.SIGNAL: {
			Object topic = body.get("topic");
			Object properties = body.get("properties");
			if (topic instanceof String && !((String) topic).isEmpty()) {
				try {
					Activator.getDefault().postEvent((String) topic,
							(properties instanceof Map) ? (Map<String, Object>) properties : Collections.emptyMap());
				} catch (Throwable ex) {
					Activator.getDefault().getLogger(getClass()).warn("Could not post the cluster signal '" + topic + "'.", ex);
				}
			}
			break;
		}
		case ClusterFrame.PUT:
		case ClusterFrame.REMOVE: {
			Object table = body.get("table");
			Object entries = body.get("entries");
			if (table instanceof String && entries instanceof List) {
				ReplicatedTable t = getTable((String) table);
				for (Object e : (List<?>) entries) {
					if (e instanceof Map) {
						Map<?, ?> m = (Map<?, ?>) e;
						t.merge((String) m.get("k"), ReplicatedTable.Entry.fromMap(m, frame.fKind == ClusterFrame.REMOVE));
					}
				}
			}
			break;
		}
		case ClusterFrame.REQUEST: {
			try {
				fRequestExecutor.execute(() -> answer(frame.fRequestId, fromNodeId, body, inbound));
			} catch (RejectedExecutionException ex) {
				inbound.respond(frame.fRequestId, null, "Cluster node '" + fNodeId + "' is busy.");
			}
			break;
		}
		default:
			Activator.getDefault().getLogger(getClass()).debug("Ignored a cluster frame of kind "
					+ ClusterFrame.kindName(frame.fKind) + " from node '" + fromNodeId + "'.");
		}
	}

	@SuppressWarnings("unchecked")
	private void answer(int requestId, String fromNodeId, Map<String, Object> body, Inbound inbound) {
		Object type = body.get("type");
		RequestHandler handler = (type instanceof String) ? fHandlers.get(type) : null;
		if (handler == null) {
			inbound.respond(requestId, null, "Cluster node '" + fNodeId + "' does not handle requests of type '" + type + "'.");
			return;
		}
		try {
			Object payload = body.get("payload");
			Map<String, Object> result = handler.handle(fromNodeId,
					(payload instanceof Map) ? (Map<String, Object>) payload : Collections.emptyMap());
			inbound.respond(requestId, (result == null) ? Collections.emptyMap() : result, null);
		} catch (Throwable ex) {
			Activator.getDefault().getLogger(getClass()).warn("An error occurred while answering a cluster request of type '"
					+ type + "' from node '" + fromNodeId + "'.", ex);
			inbound.respond(requestId, null, String.valueOf(ex.getMessage()));
		}
	}

	// ---------------------------------------------------------------------
	// Closing
	// ---------------------------------------------------------------------

	@Override
	public synchronized void close() throws IOException {
		if (fClosed) {
			return;
		}
		fClosed = true;
		synchronized (fMaintenanceLock) {
			fMaintenanceLock.notifyAll();
		}
		closeServerSocket();
		for (Peer peer : fPeers.values()) {
			peer.stop();
		}
		fPeers.clear();
		for (Inbound inbound : fInbounds) {
			inbound.close();
		}
		fInbounds.clear();
		if (fRequestExecutor != null) {
			fRequestExecutor.shutdownNow();
		}
		for (Thread t : new Thread[] { fAcceptor, fMaintenance }) {
			if (t != null) {
				try {
					t.interrupt();
					t.join(5000);
				} catch (InterruptedException ignore) {}
			}
		}
	}

	private void closeServerSocket() {
		ServerSocket serverSocket = fServerSocket;
		if (serverSocket != null) {
			try {
				serverSocket.close();
			} catch (Throwable ignore) {}
		}
	}

	private static void closeQuietly(Socket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (Throwable ignore) {}
		}
	}

	private static void closeQuietly(ServerSocket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (Throwable ignore) {}
		}
	}

	// ---------------------------------------------------------------------
	// Connections
	// ---------------------------------------------------------------------

	/** Counters of the connections with one other node. */
	private static final class LinkStats {
		final AtomicInteger fInbound = new AtomicInteger();
		final AtomicLong fRejected = new AtomicLong();
		/** Messages for the node that were dropped because there was no connection to it. */
		final AtomicLong fDroppedWhileDisconnected = new AtomicLong();
		/** Messages dropped because the connection's queue was full (the node did not keep up). */
		final AtomicLong fDroppedOnOverflow = new AtomicLong();
		volatile long fLastWakeSent;
		volatile long fLastWakeReceived;
	}

	/**
	 * The connection this node opens to one other node. A writer thread
	 * (re)connects with exponential back-off while the node is alive, says
	 * hello, sends every table, then sends the queued frames; a reader thread
	 * receives the answers to requests.
	 */
	private final class Peer implements Runnable {
		final String fNodeId;
		volatile String fAddress;
		volatile String fState = "down";
		volatile long fConnectedSince;
		final ConcurrentMap<Integer, CompletableFuture<Map<String, Object>>> fPendingRequests = new ConcurrentHashMap<>();

		// Guarded by this.
		private final Set<String> fPendingWakes = new LinkedHashSet<>();
		private final Deque<ClusterFrame> fQueue = new ArrayDeque<>();
		private Socket fSocket;
		private boolean fStopped;
		private Thread fThread;

		Peer(String nodeId, String address) {
			fNodeId = nodeId;
			fAddress = address;
		}

		void start() {
			fThread = new Thread(this, "ClusterTransport-Peer-" + fNodeId);
			fThread.setDaemon(true);
			fThread.start();
		}

		boolean isConnected() {
			return "connected".equals(fState);
		}

		/**
		 * Queues a wake-up for the workspace. A wake-up that is already
		 * waiting absorbs this one, so a burst of commits costs one frame per
		 * send cycle.
		 */
		void wake(String workspaceName) {
			synchronized (this) {
				if (!isConnected()) {
					stats(fNodeId).fDroppedWhileDisconnected.incrementAndGet();
					return;
				}
				if (fPendingWakes.add(workspaceName)) {
					notifyAll();
				}
			}
		}

		boolean enqueue(ClusterFrame frame) {
			synchronized (this) {
				if (!isConnected()) {
					stats(fNodeId).fDroppedWhileDisconnected.incrementAndGet();
					return false;
				}
				if (fQueue.size() >= MAX_QUEUED_FRAMES) {
					stats(fNodeId).fDroppedOnOverflow.incrementAndGet();
					return false;
				}
				fQueue.add(frame);
				notifyAll();
				return true;
			}
		}

		/** Drops the current connection; the writer reconnects. */
		void disconnect() {
			Socket socket;
			synchronized (this) {
				socket = fSocket;
				notifyAll();
			}
			closeQuietly(socket);
		}

		void stop() {
			synchronized (this) {
				fStopped = true;
				notifyAll();
			}
			disconnect();
			if (fThread != null) {
				fThread.interrupt();
			}
		}

		private boolean isStopped() {
			synchronized (this) {
				return fStopped || fClosed;
			}
		}

		@Override
		public void run() {
			long backoff = MIN_BACKOFF_MILLIS;
			while (!isStopped()) {
				String address = fAddress;
				Socket socket = new Socket();
				fState = "connecting";
				try {
					String[] hostPort = splitHostPort(address);
					int port = (hostPort[1] == null) ? JcrRepositoryConfiguration.DEFAULT_CLUSTER_PORT
							: Integer.parseInt(hostPort[1]);
					socket.connect(new InetSocketAddress(hostPort[0], port), CONNECT_TIMEOUT_MILLIS);
					socket.setTcpNoDelay(true);
					socket.setKeepAlive(true);
				} catch (Throwable ex) {
					closeQuietly(socket);
					fState = "down";
					fLinksChanged = true;
					if (fWarned.add("connect:" + fNodeId)) {
						Activator.getDefault().getLogger(ClusterTransportImpl.class).warn("Could not connect to cluster node '"
								+ fNodeId + "' at " + address + " (" + ex.getMessage() + "). Check the cluster port and"
								+ " the firewall; until the connection is up this node relies on the database polling"
								+ " for that node.");
					}
					pause(backoff);
					backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
					continue;
				}

				backoff = MIN_BACKOFF_MILLIS;
				if (fWarned.remove("connect:" + fNodeId)) {
					Activator.getDefault().getLogger(ClusterTransportImpl.class).info("Connected to cluster node '"
							+ fNodeId + "' at " + address + ".");
				}
				try {
					serve(socket);
				} catch (Throwable ex) {
					if (!isStopped()) {
						Activator.getDefault().getLogger(ClusterTransportImpl.class).debug("The connection to cluster node '"
								+ fNodeId + "' was closed.", ex);
					}
				} finally {
					synchronized (this) {
						fSocket = null;
						fState = "down";
						fConnectedSince = 0L;
						fPendingWakes.clear();
						fQueue.clear();
					}
					closeQuietly(socket);
					fLinksChanged = true;
					for (CompletableFuture<Map<String, Object>> answer : fPendingRequests.values()) {
						answer.completeExceptionally(new IOException("The connection to cluster node '" + fNodeId + "' was closed."));
					}
				}
				if (!isStopped()) {
					pause(MIN_BACKOFF_MILLIS);
				}
			}
			fState = "down";
		}

		private void pause(long millis) {
			synchronized (this) {
				if (fStopped || fClosed) {
					return;
				}
				try {
					wait(millis);
				} catch (InterruptedException ignore) {}
			}
		}

		private void serve(Socket socket) throws IOException {
			ClusterFrame.Codec codec = new ClusterFrame.Codec(fKey);
			DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

			Thread reader = new Thread(() -> readAnswers(socket), "ClusterTransport-Answers-" + fNodeId);
			reader.setDaemon(true);

			synchronized (this) {
				if (fStopped || fClosed) {
					return;
				}
				fSocket = socket;
				// Connected before the tables are copied, so that a write made
				// while the copy is sent is queued behind it rather than lost.
				fState = "connected";
				fConnectedSince = System.currentTimeMillis();
			}
			fLinksChanged = true;
			reader.start();

			codec.write(out, new ClusterFrame(ClusterFrame.HELLO, 0, encode(newBody())));
			sendTables(codec, out);

			List<String> wakes = new ArrayList<>();
			List<ClusterFrame> frames = new ArrayList<>();
			for (;;) {
				synchronized (this) {
					while (fPendingWakes.isEmpty() && fQueue.isEmpty() && !fStopped && !fClosed && !socket.isClosed()) {
						try {
							wait(5000);
						} catch (InterruptedException ignore) {}
					}
					if (fStopped || fClosed || socket.isClosed()) {
						return;
					}
					wakes.addAll(fPendingWakes);
					fPendingWakes.clear();
					while (!fQueue.isEmpty() && frames.size() < 256) {
						frames.add(fQueue.poll());
					}
				}

				for (String workspaceName : wakes) {
					Map<String, Object> body = newBody();
					body.put("workspace", workspaceName);
					codec.write(out, new ClusterFrame(ClusterFrame.WAKE, 0, encode(body)));
					stats(fNodeId).fLastWakeSent = System.currentTimeMillis();
				}
				wakes.clear();
				for (ClusterFrame frame : frames) {
					codec.write(out, frame);
				}
				frames.clear();
			}
		}

		/** Sends every table, in frames of a bounded size. */
		private void sendTables(ClusterFrame.Codec codec, DataOutputStream out) throws IOException {
			for (ReplicatedTable table : fTables.values()) {
				for (boolean removed : new boolean[] { false, true }) {
					List<Map<String, Object>> batch = new ArrayList<>();
					int size = 0;
					for (Map.Entry<String, ReplicatedTable.Entry> e : table.snapshot().entrySet()) {
						if (e.getValue().isRemoved() != removed) {
							continue;
						}
						Map<String, Object> entry = e.getValue().toMap(e.getKey());
						int entrySize = 64 + e.getKey().length() * 2 + ((e.getValue().fValue == null) ? 0 : e.getValue().fValue.length() * 2);
						if (!batch.isEmpty() && size + entrySize > BATCH_BODY_BYTES) {
							sendBatch(codec, out, table, batch, removed);
							batch.clear();
							size = 0;
						}
						batch.add(entry);
						size += entrySize;
					}
					if (!batch.isEmpty()) {
						sendBatch(codec, out, table, batch, removed);
					}
				}
			}
		}

		private void sendBatch(ClusterFrame.Codec codec, DataOutputStream out, ReplicatedTable table,
				List<Map<String, Object>> batch, boolean removed) throws IOException {
			Map<String, Object> body = newBody();
			body.put("table", table.getName());
			body.put("entries", batch);
			byte[] bytes = encode(body);
			if (bytes.length > ClusterFrame.MAX_BODY_BYTES) {
				List<Object> keys = new ArrayList<>();
				for (Map<String, Object> entry : batch) {
					keys.add(entry.get("k"));
				}
				Activator.getDefault().getLogger(ClusterTransportImpl.class).warn("The values of the keys " + keys
						+ " in the replicated table '" + table.getName() + "' are too large to send to node '" + fNodeId
						+ "' (" + bytes.length + " bytes; the limit is " + ClusterFrame.MAX_BODY_BYTES
						+ "); they stay on this node and that node does not see them.");
				return;
			}
			codec.write(out, new ClusterFrame(removed ? ClusterFrame.REMOVE : ClusterFrame.PUT, 0, bytes));
		}

		/** Receives the answers to this node's requests. */
		@SuppressWarnings("unchecked")
		private void readAnswers(Socket socket) {
			try {
				ClusterFrame.Codec codec = new ClusterFrame.Codec(fKey);
				DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
				boolean verified = false;
				for (;;) {
					ClusterFrame frame = codec.read(in);
					if (frame == null) {
						break;
					}
					Map<String, Object> body = decode(frame.fBody);
					checkSender(body);
					if (!verified) {
						verified = true;
						accepted(fNodeId, socket.getInetAddress());
					}
					if (frame.fKind != ClusterFrame.RESPONSE) {
						continue;
					}
					CompletableFuture<Map<String, Object>> answer = fPendingRequests.get(frame.fRequestId);
					if (answer == null) {
						continue;
					}
					Object error = body.get("error");
					if (error != null) {
						answer.completeExceptionally(new IOException(error.toString()));
					} else {
						Object payload = body.get("payload");
						answer.complete((payload instanceof Map) ? (Map<String, Object>) payload : Collections.emptyMap());
					}
				}
			} catch (ClusterFrame.SignatureException ex) {
				rejected(fNodeId, socket.getInetAddress(), ex);
			} catch (Throwable ex) {
				// The connection is gone; the writer notices and reconnects.
			} finally {
				closeQuietly(socket);
				synchronized (this) {
					notifyAll();
				}
			}
		}
	}

	/**
	 * A connection another node opened to this one: frames are read and
	 * handled in order; answers to requests are written back on it.
	 */
	private final class Inbound implements Runnable {
		private final Socket fSocket;
		private final ClusterFrame.Codec fWriteCodec;
		private DataOutputStream fOut;
		private String fFromNodeId;

		Inbound(Socket socket) {
			fSocket = socket;
			ClusterFrame.Codec codec = null;
			try {
				codec = new ClusterFrame.Codec(fKey);
			} catch (IOException ignore) {}
			fWriteCodec = codec;
		}

		@Override
		public void run() {
			try {
				fSocket.setTcpNoDelay(true);
				fSocket.setKeepAlive(true);
				fOut = new DataOutputStream(new BufferedOutputStream(fSocket.getOutputStream()));
				ClusterFrame.Codec codec = new ClusterFrame.Codec(fKey);
				DataInputStream in = new DataInputStream(new BufferedInputStream(fSocket.getInputStream()));
				for (;;) {
					ClusterFrame frame = codec.read(in);
					if (frame == null) {
						break;
					}
					Map<String, Object> body;
					String nodeId;
					try {
						body = decode(frame.fBody);
						nodeId = checkSender(body);
					} catch (ClusterFrame.SignatureException ex) {
						throw ex;
					} catch (IOException ex) {
						throw new ClusterFrame.SignatureException("The frame body could not be read: " + ex.getMessage());
					}
					if (fFromNodeId == null) {
						fFromNodeId = nodeId;
						stats(nodeId).fInbound.incrementAndGet();
						fLinksChanged = true;
						accepted(nodeId, fSocket.getInetAddress());
					} else if (!fFromNodeId.equals(nodeId)) {
						throw new ClusterFrame.SignatureException("The connection changed its sender.");
					}
					dispatch(frame, nodeId, body, this);
				}
			} catch (ClusterFrame.SignatureException ex) {
				rejected(fFromNodeId, fSocket.getInetAddress(), ex);
			} catch (Throwable ex) {
				if (!fClosed) {
					Activator.getDefault().getLogger(ClusterTransportImpl.class).debug("A cluster connection from "
							+ fSocket.getInetAddress().getHostAddress() + " was closed.", ex);
				}
			} finally {
				close();
				fInbounds.remove(this);
				if (fFromNodeId != null) {
					stats(fFromNodeId).fInbound.decrementAndGet();
					fLinksChanged = true;
				}
			}
		}

		void respond(int requestId, Map<String, Object> payload, String error) {
			Map<String, Object> body = newBody();
			if (error != null) {
				body.put("error", error);
			} else {
				body.put("payload", payload);
			}
			try {
				byte[] bytes = encode(body);
				if (bytes.length > ClusterFrame.MAX_BODY_BYTES) {
					body.remove("payload");
					body.put("error", "The answer is too large to send (" + bytes.length + " bytes).");
					bytes = encode(body);
				}
				synchronized (this) {
					if (fOut == null || fWriteCodec == null) {
						return;
					}
					fWriteCodec.write(fOut, new ClusterFrame(ClusterFrame.RESPONSE, requestId, bytes));
				}
			} catch (Throwable ex) {
				close();
			}
		}

		void close() {
			closeQuietly(fSocket);
		}
	}

	/** {@link ClusterCoordinator.Link} as a value. */
	static final class LinkImpl implements ClusterCoordinator.Link {
		private final String fNodeId;
		private final String fOutbound;
		private final long fConnectedSince;
		private final String fInbound;
		private final long fLastWakeSent;
		private final long fLastWakeReceived;
		private final long fRejected;
		private final long fDroppedWhileDisconnected;
		private final long fDroppedOnOverflow;

		LinkImpl(String nodeId, String outbound, long connectedSince, String inbound, long lastWakeSent,
				long lastWakeReceived, long rejected, long droppedWhileDisconnected, long droppedOnOverflow) {
			fNodeId = nodeId;
			fOutbound = outbound;
			fConnectedSince = connectedSince;
			fInbound = inbound;
			fLastWakeSent = lastWakeSent;
			fLastWakeReceived = lastWakeReceived;
			fRejected = rejected;
			fDroppedWhileDisconnected = droppedWhileDisconnected;
			fDroppedOnOverflow = droppedOnOverflow;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("nodeId", fNodeId);
			m.put("outbound", fOutbound);
			m.put("connectedSince", fConnectedSince);
			m.put("inbound", fInbound);
			m.put("lastWakeSent", fLastWakeSent);
			m.put("lastWakeReceived", fLastWakeReceived);
			m.put("rejected", fRejected);
			m.put("droppedWhileDisconnected", fDroppedWhileDisconnected);
			m.put("droppedOnOverflow", fDroppedOnOverflow);
			return m;
		}

		static LinkImpl fromMap(Map<?, ?> m) {
			return new LinkImpl(String.valueOf(m.get("nodeId")), String.valueOf(m.get("outbound")),
					longOf(m.get("connectedSince")), String.valueOf(m.get("inbound")), longOf(m.get("lastWakeSent")),
					longOf(m.get("lastWakeReceived")), longOf(m.get("rejected")),
					longOf(m.get("droppedWhileDisconnected")), longOf(m.get("droppedOnOverflow")));
		}

		private static long longOf(Object value) {
			return (value instanceof Number) ? ((Number) value).longValue() : 0L;
		}

		@Override
		public String getNodeId() {
			return fNodeId;
		}

		@Override
		public String getOutbound() {
			return fOutbound;
		}

		@Override
		public long getConnectedSince() {
			return fConnectedSince;
		}

		@Override
		public String getInbound() {
			return fInbound;
		}

		@Override
		public long getLastWakeSent() {
			return fLastWakeSent;
		}

		@Override
		public long getLastWakeReceived() {
			return fLastWakeReceived;
		}

		@Override
		public long getRejected() {
			return fRejected;
		}

		@Override
		public long getDroppedWhileDisconnected() {
			return fDroppedWhileDisconnected;
		}

		@Override
		public long getDroppedOnOverflow() {
			return fDroppedOnOverflow;
		}
	}

}
