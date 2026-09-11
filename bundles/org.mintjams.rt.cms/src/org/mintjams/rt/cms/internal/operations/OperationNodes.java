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

package org.mintjams.rt.cms.internal.operations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.util.JCRs;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * JCR persistence of the cluster-wide operations state, kept in the system
 * workspace under {@code /var/operations/workspaces}.
 *
 * <pre>
 * &lt;workspace&gt;/desired                the desired state, written by operators
 * &lt;workspace&gt;/routes/&lt;route&gt;         a desired route state (absent: follow the route definition)
 * &lt;workspace&gt;/nodes/&lt;node&gt;/request    a request addressed to one node (retry generation)
 * &lt;workspace&gt;/nodes/&lt;node&gt;/state      the node's actual state, written by that node only
 * &lt;workspace&gt;/nodes/&lt;node&gt;/routes     the node's route statuses, written by that node only
 * </pre>
 *
 * <p>Every record is an {@code nt:file} whose {@code jcr:content} carries the
 * properties, like the {@code /var/jobs} records. The system workspace runs on
 * every node and, in a cluster, lives in the shared database, so a write here
 * reaches every node through the cluster journal as a local node event: the
 * write is both the durable state and the notification. Operators and nodes
 * never write the same record, so their saves do not conflict.
 *
 * <p>All methods operate on the caller's session and leave saving to the
 * caller.
 */
public final class OperationNodes {

	public static final String WORKSPACES_ROOT = "/var/operations/workspaces";

	public static final String PROP_PRESENCE = "operationPresence";
	public static final String PROP_RUN = "operationRun";
	public static final String PROP_RESTART_GENERATION = "operationRestartGeneration";
	public static final String PROP_RETRY_GENERATION = "operationRetryGeneration";
	public static final String PROP_NODE_ID = "operationNodeId";
	public static final String PROP_STATE = "operationState";
	public static final String PROP_STATE_MESSAGE = "operationStateMessage";
	public static final String PROP_PROCESS_ENGINE_ENABLED = "operationProcessEngineEnabled";
	public static final String PROP_PROCESS_ENGINE_RUNNING = "operationProcessEngineRunning";
	public static final String PROP_INTEGRATION_ENGINE_ENABLED = "operationIntegrationEngineEnabled";
	public static final String PROP_INTEGRATION_ENGINE_RUNNING = "operationIntegrationEngineRunning";
	public static final String PROP_APPLIED_RESTART_GENERATION = "operationAppliedRestartGeneration";
	public static final String PROP_APPLIED_RETRY_GENERATION = "operationAppliedRetryGeneration";
	public static final String PROP_UPDATED = "operationUpdated";
	public static final String PROP_ROUTE_ID = "operationRouteId";
	public static final String PROP_ROUTE_STATE = "operationRouteState";

	private static final String DESIRED = "desired";
	private static final String ROUTES = "routes";
	private static final String NODES = "nodes";
	private static final String REQUEST = "request";
	private static final String STATE = "state";

	private static final Gson GSON = new Gson();
	private static final Type ROUTE_REPORT_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();

	/** Whether the workspace should exist on the nodes. */
	public enum Presence {
		PRESENT,
		ABSENT;
	}

	/** Whether the workspace's CMS services should run. */
	public enum Run {
		RUNNING,
		STOPPED;
	}

	/** A desired route state; a route without one follows its definition's auto-startup. */
	public enum RouteState {
		STARTED,
		STOPPED,
		SUSPENDED;
	}

	/** The state a node reports for a workspace. */
	public enum NodeState {
		ONLINE,
		STARTING,
		STOPPED,
		FAILED,
		/** The workspace is not open on the node (deleted, or not yet opened). */
		CLOSED;
	}

	/** The cluster-wide desired state of one workspace. */
	public static final class DesiredState {
		public final Presence presence;
		public final Run run;
		public final long restartGeneration;

		public DesiredState(Presence presence, Run run, long restartGeneration) {
			this.presence = presence;
			this.run = run;
			this.restartGeneration = restartGeneration;
		}

		public DesiredState withPresence(Presence presence) {
			return new DesiredState(presence, run, restartGeneration);
		}

		public DesiredState withRun(Run run) {
			return new DesiredState(presence, run, restartGeneration);
		}

		public DesiredState withRestartGeneration(long restartGeneration) {
			return new DesiredState(presence, run, restartGeneration);
		}
	}

	/** The actual state one node reports for one workspace. */
	public static final class NodeReport {
		public String nodeId;
		public NodeState state;
		public String message;
		/** The engine switch the running services were started with; null when the services are not running. */
		public Boolean processEngineEnabled;
		public boolean processEngineRunning;
		public Boolean integrationEngineEnabled;
		public boolean integrationEngineRunning;
		public long appliedRestartGeneration;
		public long appliedRetryGeneration;
		public Calendar updated;

		/** Everything but the timestamp, to tell a changed report from a repeated one. */
		String fingerprint() {
			return String.join("|", Objects.toString(nodeId), Objects.toString(state), Objects.toString(message),
					Objects.toString(processEngineEnabled), Boolean.toString(processEngineRunning),
					Objects.toString(integrationEngineEnabled), Boolean.toString(integrationEngineRunning),
					Long.toString(appliedRestartGeneration), Long.toString(appliedRetryGeneration));
		}
	}

	private OperationNodes() {}

	public static String workspacePath(String workspaceName) {
		return WORKSPACES_ROOT + "/" + workspaceName;
	}

	private static String desiredPath(String workspaceName) {
		return workspacePath(workspaceName) + "/" + DESIRED;
	}

	private static String routesPath(String workspaceName) {
		return workspacePath(workspaceName) + "/" + ROUTES;
	}

	private static String nodesPath(String workspaceName) {
		return workspacePath(workspaceName) + "/" + NODES;
	}

	private static String nodePath(String workspaceName, String nodeId) {
		return nodesPath(workspaceName) + "/" + encodeName(nodeId);
	}

	/**
	 * The workspace an event path under {@link #WORKSPACES_ROOT} belongs to, or
	 * {@code null} for any other path.
	 */
	public static String workspaceOf(String path) {
		if (path == null || !path.startsWith(WORKSPACES_ROOT + "/")) {
			return null;
		}
		String rest = path.substring(WORKSPACES_ROOT.length() + 1);
		int slash = rest.indexOf('/');
		String name = (slash < 0) ? rest : rest.substring(0, slash);
		return name.isEmpty() ? null : name;
	}

	/** Whether an event path is a node's state report. */
	public static boolean isStateReportPath(String path) {
		return STATE.equals(nodeRecordOf(path));
	}

	/** Whether an event path is a node's route status report. */
	public static boolean isRouteReportPath(String path) {
		return ROUTES.equals(nodeRecordOf(path));
	}

	/** The record name ({@code request}, {@code state}, {@code routes}) under {@code nodes/<node>/}, or null. */
	private static String nodeRecordOf(String path) {
		String workspaceName = workspaceOf(path);
		if (workspaceName == null) {
			return null;
		}
		String prefix = nodesPath(workspaceName) + "/";
		if (!path.startsWith(prefix)) {
			return null;
		}
		String[] segments = path.substring(prefix.length()).split("/");
		return (segments.length < 2) ? null : segments[1];
	}

	/**
	 * Encodes a route or node identifier as a JCR name: letters, digits,
	 * hyphens and non-leading dots are kept, everything else becomes
	 * {@code _xHHHH_}. The original identifier is stored on the record, so the
	 * name never has to be decoded.
	 */
	static String encodeName(String value) {
		StringBuilder buffer = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| c == '-' || (c == '.' && i > 0);
			if (plain) {
				buffer.append(c);
			} else {
				buffer.append(String.format("_x%04X_", (int) c));
			}
		}
		return buffer.toString();
	}

	// ---- workspaces --------------------------------------------------------

	/** The workspaces that have a record, whatever their presence. */
	public static List<String> listWorkspaces(Session session) throws RepositoryException {
		List<String> names = new ArrayList<>();
		if (!session.nodeExists(WORKSPACES_ROOT)) {
			return names;
		}
		for (NodeIterator i = session.getNode(WORKSPACES_ROOT).getNodes(); i.hasNext();) {
			names.add(i.nextNode().getName());
		}
		return names;
	}

	public static DesiredState readDesired(Session session, String workspaceName) throws RepositoryException {
		Node content = getContent(session, desiredPath(workspaceName));
		if (content == null) {
			return null;
		}
		return new DesiredState(
				enumValue(Presence.class, getString(content, PROP_PRESENCE), Presence.PRESENT),
				enumValue(Run.class, getString(content, PROP_RUN), Run.RUNNING),
				getLong(content, PROP_RESTART_GENERATION, 0L));
	}

	public static void writeDesired(Session session, String workspaceName, DesiredState desired)
			throws RepositoryException {
		Node content = getOrCreateContent(session, desiredPath(workspaceName));
		content.setProperty(PROP_PRESENCE, desired.presence.name());
		content.setProperty(PROP_RUN, desired.run.name());
		content.setProperty(PROP_RESTART_GENERATION, desired.restartGeneration);
		touch(content, session);
	}

	/** Removes a workspace's whole record: desired state, route states, requests and reports. */
	public static void removeWorkspace(Session session, String workspaceName) throws RepositoryException {
		if (session.nodeExists(workspacePath(workspaceName))) {
			session.getNode(workspacePath(workspaceName)).remove();
		}
	}

	// ---- per-node requests and reports -------------------------------------

	public static long readRetryGeneration(Session session, String workspaceName, String nodeId)
			throws RepositoryException {
		Node content = getContent(session, nodePath(workspaceName, nodeId) + "/" + REQUEST);
		return (content == null) ? 0L : getLong(content, PROP_RETRY_GENERATION, 0L);
	}

	public static void writeRetryGeneration(Session session, String workspaceName, String nodeId, long generation)
			throws RepositoryException {
		Node content = getOrCreateContent(session, nodePath(workspaceName, nodeId) + "/" + REQUEST);
		content.setProperty(PROP_NODE_ID, nodeId);
		content.setProperty(PROP_RETRY_GENERATION, generation);
		touch(content, session);
	}

	public static NodeReport readReport(Session session, String workspaceName, String nodeId)
			throws RepositoryException {
		Node content = getContent(session, nodePath(workspaceName, nodeId) + "/" + STATE);
		return (content == null) ? null : toReport(content);
	}

	/** Every node's state report for a workspace, keyed by node identifier. */
	public static Map<String, NodeReport> readReports(Session session, String workspaceName)
			throws RepositoryException {
		Map<String, NodeReport> reports = new LinkedHashMap<>();
		if (!session.nodeExists(nodesPath(workspaceName))) {
			return reports;
		}
		for (NodeIterator i = session.getNode(nodesPath(workspaceName)).getNodes(); i.hasNext();) {
			Node nodeFolder = i.nextNode();
			if (!nodeFolder.hasNode(STATE) || !nodeFolder.getNode(STATE).hasNode(Node.JCR_CONTENT)) {
				continue;
			}
			NodeReport report = toReport(nodeFolder.getNode(STATE).getNode(Node.JCR_CONTENT));
			if (report.nodeId != null) {
				reports.put(report.nodeId, report);
			}
		}
		return reports;
	}

	public static void writeReport(Session session, String workspaceName, NodeReport report)
			throws RepositoryException {
		Node content = getOrCreateContent(session, nodePath(workspaceName, report.nodeId) + "/" + STATE);
		content.setProperty(PROP_NODE_ID, report.nodeId);
		content.setProperty(PROP_STATE, report.state.name());
		content.setProperty(PROP_STATE_MESSAGE, report.message);
		setBoolean(content, PROP_PROCESS_ENGINE_ENABLED, report.processEngineEnabled);
		content.setProperty(PROP_PROCESS_ENGINE_RUNNING, report.processEngineRunning);
		setBoolean(content, PROP_INTEGRATION_ENGINE_ENABLED, report.integrationEngineEnabled);
		content.setProperty(PROP_INTEGRATION_ENGINE_RUNNING, report.integrationEngineRunning);
		content.setProperty(PROP_APPLIED_RESTART_GENERATION, report.appliedRestartGeneration);
		content.setProperty(PROP_APPLIED_RETRY_GENERATION, report.appliedRetryGeneration);
		content.setProperty(PROP_UPDATED, (report.updated != null) ? report.updated : Calendar.getInstance());
		touch(content, session);
	}

	/** Removes everything recorded for one node under a workspace: its request and its reports. */
	public static void removeNode(Session session, String workspaceName, String nodeId) throws RepositoryException {
		if (session.nodeExists(nodePath(workspaceName, nodeId))) {
			session.getNode(nodePath(workspaceName, nodeId)).remove();
		}
	}

	private static NodeReport toReport(Node content) throws RepositoryException {
		NodeReport report = new NodeReport();
		report.nodeId = getString(content, PROP_NODE_ID);
		report.state = enumValue(NodeState.class, getString(content, PROP_STATE), null);
		report.message = getString(content, PROP_STATE_MESSAGE);
		report.processEngineEnabled = getBoolean(content, PROP_PROCESS_ENGINE_ENABLED);
		report.processEngineRunning = Boolean.TRUE.equals(getBoolean(content, PROP_PROCESS_ENGINE_RUNNING));
		report.integrationEngineEnabled = getBoolean(content, PROP_INTEGRATION_ENGINE_ENABLED);
		report.integrationEngineRunning = Boolean.TRUE.equals(getBoolean(content, PROP_INTEGRATION_ENGINE_RUNNING));
		report.appliedRestartGeneration = getLong(content, PROP_APPLIED_RESTART_GENERATION, 0L);
		report.appliedRetryGeneration = getLong(content, PROP_APPLIED_RETRY_GENERATION, 0L);
		report.updated = content.hasProperty(PROP_UPDATED) ? content.getProperty(PROP_UPDATED).getDate() : null;
		return report;
	}

	// ---- routes ------------------------------------------------------------

	/** The desired route states of a workspace, keyed by route id. */
	public static Map<String, RouteState> readRouteStates(Session session, String workspaceName)
			throws RepositoryException {
		Map<String, RouteState> states = new LinkedHashMap<>();
		if (!session.nodeExists(routesPath(workspaceName))) {
			return states;
		}
		for (NodeIterator i = session.getNode(routesPath(workspaceName)).getNodes(); i.hasNext();) {
			Node file = i.nextNode();
			if (!file.hasNode(Node.JCR_CONTENT)) {
				continue;
			}
			Node content = file.getNode(Node.JCR_CONTENT);
			String routeId = getString(content, PROP_ROUTE_ID);
			RouteState state = enumValue(RouteState.class, getString(content, PROP_ROUTE_STATE), null);
			if (routeId != null && state != null) {
				states.put(routeId, state);
			}
		}
		return states;
	}

	/** Records a desired route state; {@code null} removes it, so the route follows its definition again. */
	public static void writeRouteState(Session session, String workspaceName, String routeId, RouteState state)
			throws RepositoryException {
		String path = routesPath(workspaceName) + "/" + encodeName(routeId);
		if (state == null) {
			if (session.nodeExists(path)) {
				session.getNode(path).remove();
			}
			return;
		}
		Node content = getOrCreateContent(session, path);
		content.setProperty(PROP_ROUTE_ID, routeId);
		content.setProperty(PROP_ROUTE_STATE, state.name());
		touch(content, session);
	}

	/** Every node's route statuses for a workspace: node identifier to (route id to Camel status). */
	public static Map<String, Map<String, String>> readRouteReports(Session session, String workspaceName)
			throws RepositoryException, IOException {
		Map<String, Map<String, String>> reports = new LinkedHashMap<>();
		if (!session.nodeExists(nodesPath(workspaceName))) {
			return reports;
		}
		for (NodeIterator i = session.getNode(nodesPath(workspaceName)).getNodes(); i.hasNext();) {
			Node nodeFolder = i.nextNode();
			if (!nodeFolder.hasNode(ROUTES)) {
				continue;
			}
			Node file = nodeFolder.getNode(ROUTES);
			String nodeId = getString(file.getNode(Node.JCR_CONTENT), PROP_NODE_ID);
			if (nodeId == null) {
				continue;
			}
			Map<String, String> statuses = GSON.fromJson(JCRs.getContentAsString(file), ROUTE_REPORT_TYPE);
			reports.put(nodeId, (statuses != null) ? statuses : new LinkedHashMap<>());
		}
		return reports;
	}

	public static void writeRouteReport(Session session, String workspaceName, String nodeId,
			Map<String, String> statuses) throws RepositoryException {
		Node file = getOrCreateFile(session, nodePath(workspaceName, nodeId) + "/" + ROUTES);
		try (InputStream in = new ByteArrayInputStream(GSON.toJson(statuses).getBytes(StandardCharsets.UTF_8))) {
			JCRs.write(file, in);
		} catch (IOException ex) {
			throw new RepositoryException(ex);
		}
		Node content = file.getNode(Node.JCR_CONTENT);
		content.setProperty("jcr:mimeType", "application/json");
		content.setProperty(PROP_NODE_ID, nodeId);
		content.setProperty(PROP_UPDATED, Calendar.getInstance());
	}

	// ---- helpers -----------------------------------------------------------

	private static Node getContent(Session session, String filePath) throws RepositoryException {
		if (!session.nodeExists(filePath)) {
			return null;
		}
		Node file = session.getNode(filePath);
		return file.hasNode(Node.JCR_CONTENT) ? file.getNode(Node.JCR_CONTENT) : null;
	}

	private static Node getOrCreateFile(Session session, String filePath) throws RepositoryException {
		if (session.nodeExists(filePath)) {
			return session.getNode(filePath);
		}
		int slash = filePath.lastIndexOf('/');
		Node parent = JCRs.getOrCreateFolder(JcrPath.valueOf(filePath.substring(0, slash)), session);
		Node file = JCRs.createFile(parent, filePath.substring(slash + 1));
		Node content = file.getNode(Node.JCR_CONTENT);
		content.setProperty("jcr:mimeType", "text/plain");
		content.setProperty("jcr:encoding", "UTF-8");
		return file;
	}

	private static Node getOrCreateContent(Session session, String filePath) throws RepositoryException {
		return getOrCreateFile(session, filePath).getNode(Node.JCR_CONTENT);
	}

	private static void touch(Node content, Session session) throws RepositoryException {
		content.setProperty("jcr:lastModified", Calendar.getInstance());
		content.setProperty("jcr:lastModifiedBy", session.getUserID());
	}

	private static String getString(Node content, String name) throws RepositoryException {
		return content.hasProperty(name) ? content.getProperty(name).getString() : null;
	}

	private static long getLong(Node content, String name, long defaultValue) throws RepositoryException {
		return content.hasProperty(name) ? content.getProperty(name).getLong() : defaultValue;
	}

	private static Boolean getBoolean(Node content, String name) throws RepositoryException {
		return content.hasProperty(name) ? content.getProperty(name).getBoolean() : null;
	}

	private static void setBoolean(Node content, String name, Boolean value) throws RepositoryException {
		if (value != null) {
			content.setProperty(name, value.booleanValue());
		} else if (content.hasProperty(name)) {
			content.getProperty(name).remove();
		}
	}

	private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException ex) {
			return defaultValue;
		}
	}

}
