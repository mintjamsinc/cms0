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

package org.mintjams.rt.cms.internal.graphql.wiring;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProvider;
import org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProviderConfiguration;
import org.mintjams.rt.cms.internal.eip.WorkspaceIntegrationEngineProvider;
import org.mintjams.rt.cms.internal.eip.WorkspaceIntegrationEngineProviderConfiguration;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.job.workspace.WorkspaceLifecycleJob;
import org.mintjams.rt.cms.internal.workspace.WorkspaceSettings;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;

/**
 * Contributes the platform's workspace-management GraphQL schema — the
 * graphql-java migration target of the handmade {@code WorkspaceQueryExecutor} /
 * {@code WorkspaceMutationExecutor} — to the unified per-workspace
 * {@link org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider}.
 *
 * <p>It is a side-by-side reimplementation: its SDL ({@code workspace-schema.graphqls})
 * {@code extend}s the core Query/Mutation roots, and its {@link DataFetcher}s
 * project workspaces into the same flat maps the handmade engine produced.
 *
 * <p>Coverage: the {@code workspaces} read; the {@code createWorkspace},
 * {@code deleteWorkspace}, {@code startWorkspace}, {@code stopWorkspace},
 * {@code restartWorkspace} (asynchronous {@code WorkspaceLifecycleJob}s) and
 * {@code updateWorkspace} (synchronous) mutations. Mutations are reserved for
 * administrators and service accounts and report validation failures in-band as
 * an {@code errors} list, exactly as the handmade engine did.
 */
public final class PlatformWorkspaceWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/workspace-schema.graphqls";
	private static final String SYSTEM_WORKSPACE_NAME = "system";

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "workspaces",
						(DataFetcher<Object>) PlatformWorkspaceWiringContributor::workspaces)
				.dataFetcher("Mutation", "createWorkspace",
						(DataFetcher<Object>) PlatformWorkspaceWiringContributor::createWorkspace)
				.dataFetcher("Mutation", "deleteWorkspace",
						(DataFetcher<Object>) PlatformWorkspaceWiringContributor::deleteWorkspace)
				.dataFetcher("Mutation", "startWorkspace",
						(DataFetcher<Object>) PlatformWorkspaceWiringContributor::startWorkspace)
				.dataFetcher("Mutation", "stopWorkspace",
						(DataFetcher<Object>) PlatformWorkspaceWiringContributor::stopWorkspace)
				.dataFetcher("Mutation", "restartWorkspace",
						(DataFetcher<Object>) PlatformWorkspaceWiringContributor::restartWorkspace)
				.dataFetcher("Mutation", "updateWorkspace",
						(DataFetcher<Object>) PlatformWorkspaceWiringContributor::updateWorkspace);
	}

	// ---- queries (mirror WorkspaceQueryExecutor) ---------------------------

	/**
	 * Returns the accessible workspaces, the system workspace first then the rest
	 * alphabetically, each described with its lifecycle and engine state. Workspace
	 * names are not sensitive (every user needs them to switch desktops), so the
	 * listing carries no admin restriction.
	 */
	private static Object workspaces(DataFetchingEnvironment environment) {
		Session session = GraphQLExecutionContext.from(environment).getCallerSession();
		String currentName;
		List<String> names;
		try {
			currentName = session.getWorkspace().getName();
			names = new ArrayList<>(Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()));
		} catch (Exception ex) {
			throw new RuntimeException("Failed to list accessible workspaces", ex);
		}
		names.sort((a, b) -> {
			if (SYSTEM_WORKSPACE_NAME.equals(a)) {
				return SYSTEM_WORKSPACE_NAME.equals(b) ? 0 : -1;
			}
			if (SYSTEM_WORKSPACE_NAME.equals(b)) {
				return 1;
			}
			return a.compareTo(b);
		});

		List<Map<String, Object>> workspaces = new ArrayList<>();
		for (String name : names) {
			workspaces.add(describeWorkspace(name, currentName));
		}
		return workspaces;
	}

	/**
	 * Builds the GraphQL representation of one workspace (shared with the workspace
	 * mutations so a freshly changed workspace reports the same shape as a listed
	 * one). Mirrors {@code WorkspaceQueryExecutor.describeWorkspace}.
	 */
	static Map<String, Object> describeWorkspace(String name, String currentName) {
		Map<String, Object> workspace = new HashMap<>();
		workspace.put("name", name);
		workspace.put("current", name.equals(currentName));
		workspace.put("system", SYSTEM_WORKSPACE_NAME.equals(name));

		WorkspaceSettings settings = new WorkspaceSettings(name);
		try {
			settings.load();
		} catch (Exception ex) {
			CmsService.getLogger(PlatformWorkspaceWiringContributor.class)
					.warn("Could not read workspace settings for: " + name, ex);
		}
		workspace.put("displayName", settings.getDisplayName());
		// The system workspace always starts; auto-start does not apply to it.
		workspace.put("autoStart", SYSTEM_WORKSPACE_NAME.equals(name) ? true : settings.isAutoStart());

		// The servlet provider is CmsService's own "online" idempotency marker; its
		// absence means STOPPED (deliberately idle), FAILED (start threw — error text
		// rides along), or still STARTING.
		boolean online = (CmsService.getWorkspaceServletProvider(name) != null);
		String startError = online ? null : CmsService.getWorkspaceStartError(name);
		String state;
		if (online) {
			state = "ONLINE";
		} else if (startError != null) {
			state = "FAILED";
		} else if (CmsService.isWorkspaceStopped(name)) {
			state = "STOPPED";
		} else {
			state = "STARTING";
		}
		workspace.put("state", state);
		workspace.put("stateMessage", startError);
		workspace.put("processEngine", processEngineState(name));
		workspace.put("integrationEngine", integrationEngineState(name));
		return workspace;
	}

	private static Map<String, Object> processEngineState(String workspaceName) {
		WorkspaceProcessEngineProvider provider = CmsService.getWorkspaceProcessEngineProvider(workspaceName);
		Map<String, Object> state = new HashMap<>();
		// `enabled` is the persisted configuration intent (bpm.yml), readable even
		// while the workspace is stopped; `running` is the live runtime state.
		state.put("enabled", WorkspaceProcessEngineProviderConfiguration.isEnabledOnDisk(workspaceName));
		state.put("running", (provider != null && provider.isAvailable()));
		return state;
	}

	private static Map<String, Object> integrationEngineState(String workspaceName) {
		WorkspaceIntegrationEngineProvider provider = CmsService.getWorkspaceIntegrationEngineProvider(workspaceName);
		Map<String, Object> state = new HashMap<>();
		state.put("enabled", WorkspaceIntegrationEngineProviderConfiguration.isEnabledOnDisk(workspaceName));
		state.put("running", (provider != null && provider.isAvailable()));
		return state;
	}

	// ---- mutations (mirror WorkspaceMutationExecutor) ----------------------

	/** Starts an async workspace creation; validation (privilege/name/exists) is synchronous. */
	private static Object createWorkspace(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> input = inputArg(environment);
		String name = (String) input.get("name");
		Session session = callerSession(environment);
		if (!isWorkspaceManager(session)) {
			return errorResult("Workspace management requires administrative privileges", "ACCESS_DENIED");
		}
		if (name == null || name.isEmpty()) {
			return errorResult("name is required", "INVALID_INPUT");
		}
		if (Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()).contains(name)) {
			return errorResult("Workspace already exists: " + name, "ALREADY_EXISTS");
		}
		String jobId = submitWorkspaceJob(environment, WorkspaceLifecycleJob.Operation.CREATE,
				WorkspaceLifecycleJob.TYPE_CREATE, name);
		return jobAcceptedResult(jobId, name);
	}

	/** Starts an async workspace deletion; the system and bound workspaces are refused. */
	private static Object deleteWorkspace(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> input = inputArg(environment);
		String name = (String) input.get("name");
		Session session = callerSession(environment);
		if (!isWorkspaceManager(session)) {
			return errorResult("Workspace management requires administrative privileges", "ACCESS_DENIED");
		}
		if (name == null || name.isEmpty()) {
			return errorResult("name is required", "INVALID_INPUT");
		}
		if (SYSTEM_WORKSPACE_NAME.equals(name)) {
			return errorResult("The system workspace cannot be deleted", "INVALID_INPUT");
		}
		if (name.equals(session.getWorkspace().getName())) {
			return errorResult("The workspace this session is bound to cannot be deleted", "INVALID_INPUT");
		}
		if (!Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()).contains(name)) {
			return errorResult("Workspace not found: " + name, "NOT_FOUND");
		}
		String jobId = submitWorkspaceJob(environment, WorkspaceLifecycleJob.Operation.DELETE,
				WorkspaceLifecycleJob.TYPE_DELETE, name);
		return jobAcceptedResult(jobId, name);
	}

	/** Starts an async start of a stopped workspace. */
	private static Object startWorkspace(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> input = inputArg(environment);
		String name = (String) input.get("name");
		Session session = callerSession(environment);
		if (!isWorkspaceManager(session)) {
			return errorResult("Workspace management requires administrative privileges", "ACCESS_DENIED");
		}
		if (name == null || name.isEmpty()) {
			return errorResult("name is required", "INVALID_INPUT");
		}
		if (!Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()).contains(name)) {
			return errorResult("Workspace not found: " + name, "NOT_FOUND");
		}
		String jobId = submitWorkspaceJob(environment, WorkspaceLifecycleJob.Operation.START,
				WorkspaceLifecycleJob.TYPE_START, name);
		return jobAcceptedResult(jobId, name);
	}

	/** Starts an async stop of a running workspace; the system and bound workspaces are refused. */
	private static Object stopWorkspace(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> guard = guardStartedWorkspaceMutation(environment);
		if (guard != null) {
			return guard;
		}
		String name = (String) inputArg(environment).get("name");
		String jobId = submitWorkspaceJob(environment, WorkspaceLifecycleJob.Operation.STOP,
				WorkspaceLifecycleJob.TYPE_STOP, name);
		return jobAcceptedResult(jobId, name);
	}

	/** Starts an async restart of a workspace (applies BPM/EIP switches); same guard as stop. */
	private static Object restartWorkspace(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> guard = guardStartedWorkspaceMutation(environment);
		if (guard != null) {
			return guard;
		}
		String name = (String) inputArg(environment).get("name");
		String jobId = submitWorkspaceJob(environment, WorkspaceLifecycleJob.Operation.RESTART,
				WorkspaceLifecycleJob.TYPE_RESTART, name);
		return jobAcceptedResult(jobId, name);
	}

	/** Synchronous settings update (displayName/autoStart + bpm/eip switches); returns the refreshed workspace. */
	private static Object updateWorkspace(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> input = inputArg(environment);
		String name = (String) input.get("name");
		Session session = callerSession(environment);
		if (!isWorkspaceManager(session)) {
			return errorResult("Workspace management requires administrative privileges", "ACCESS_DENIED");
		}
		if (name == null || name.isEmpty()) {
			return errorResult("name is required", "INVALID_INPUT");
		}
		if (!Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()).contains(name)) {
			return errorResult("Workspace not found: " + name, "NOT_FOUND");
		}

		boolean isSystem = SYSTEM_WORKSPACE_NAME.equals(name);
		boolean changed = false;

		// Workspace metadata (display name, auto-start).
		if (input.containsKey("displayName") || input.containsKey("autoStart")) {
			WorkspaceSettings settings = new WorkspaceSettings(name).load();
			if (input.containsKey("displayName")) {
				settings.setDisplayName((String) input.get("displayName"));
			}
			if (input.containsKey("autoStart") && !isSystem) {
				settings.setAutoStart(toBoolean(input.get("autoStart")));
			}
			settings.save();
			changed = true;
		}
		// Engine switches (applied on the next workspace start).
		if (input.containsKey("bpmEnabled")) {
			WorkspaceProcessEngineProviderConfiguration.setEnabled(name, toBoolean(input.get("bpmEnabled")));
			changed = true;
		}
		if (input.containsKey("eipEnabled")) {
			WorkspaceIntegrationEngineProviderConfiguration.setEnabled(name, toBoolean(input.get("eipEnabled")));
			changed = true;
		}
		// Announce so every connected desktop refreshes its switcher/dashboard live.
		if (changed) {
			CmsService.postWorkspaceChanged(name);
		}

		Map<String, Object> result = new HashMap<>();
		result.put("workspace", describeWorkspace(name, session.getWorkspace().getName()));
		result.put("errors", null);
		return result;
	}

	/**
	 * Shared guard for stop/restart: requires admin/service, an existing target, and
	 * refuses the system workspace and the workspace this session is bound to. Returns
	 * an error result to short-circuit on, or {@code null} when the mutation may proceed.
	 * (Messages say "stopped" for both, mirroring the handmade guard.)
	 */
	private static Map<String, Object> guardStartedWorkspaceMutation(DataFetchingEnvironment environment)
			throws Exception {
		Map<String, Object> input = inputArg(environment);
		String name = (String) input.get("name");
		Session session = callerSession(environment);
		if (!isWorkspaceManager(session)) {
			return errorResult("Workspace management requires administrative privileges", "ACCESS_DENIED");
		}
		if (name == null || name.isEmpty()) {
			return errorResult("name is required", "INVALID_INPUT");
		}
		if (SYSTEM_WORKSPACE_NAME.equals(name)) {
			return errorResult("The system workspace cannot be stopped", "INVALID_INPUT");
		}
		if (name.equals(session.getWorkspace().getName())) {
			return errorResult("The workspace this session is bound to cannot be stopped", "INVALID_INPUT");
		}
		if (!Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()).contains(name)) {
			return errorResult("Workspace not found: " + name, "NOT_FOUND");
		}
		return null;
	}

	/**
	 * Allocates the {@code /var/jobs} record for a workspace lifecycle job in the
	 * requester's own workspace (never the target, whose record area disappears with
	 * it on delete), records the target and queues it, then submits the job. A
	 * service session running as the caller writes {@code /var/jobs} regardless of
	 * that area's ACLs, with the caller's id recorded as the modifier.
	 */
	private static String submitWorkspaceJob(DataFetchingEnvironment environment,
			WorkspaceLifecycleJob.Operation operation, String jobType, String targetWorkspace) throws Exception {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		String jobWorkspace = context.getWorkspaceName();
		String userId = context.getCallerSession().getUserID();
		int priority = 0;

		String jobId = JobNodes.newJobId();
		Session mgmt = context.openServiceSession(userId);
		try {
			Node jobNode = JobNodes.createJobNode(mgmt, jobId, jobType, userId, priority);
			Node content = JobNodes.getContent(jobNode);
			content.setProperty(JobNodes.PROP_TARGET_WORKSPACE, targetWorkspace);
			JobNodes.setNodeId(mgmt, content);
			JobNodes.setStatus(content, JobStatus.QUEUED);
			mgmt.save();
		} catch (Exception ex) {
			try {
				mgmt.refresh(false);
			} catch (Throwable ignore) {}
			throw ex;
		} finally {
			try {
				mgmt.logout();
			} catch (Throwable ignore) {}
		}

		CmsService.getJobManager().submit(
				new WorkspaceLifecycleJob(jobId, jobWorkspace, userId, priority, operation, targetWorkspace));
		return jobId;
	}

	private static boolean isWorkspaceManager(Session session) {
		org.mintjams.jcr.Session jcrSession = org.mintjams.jcr.Session.class.cast(session);
		return jcrSession.isAdmin() || jcrSession.isService();
	}

	/** Job-accepted result {jobId, status: queued, name, errors: null} (CreateWorkspacePayload ignores `name`). */
	private static Map<String, Object> jobAcceptedResult(String jobId, String name) {
		Map<String, Object> result = new HashMap<>();
		result.put("jobId", jobId);
		result.put("status", JobStatus.QUEUED.toExternalString());
		result.put("name", name);
		result.put("errors", null);
		return result;
	}

	/**
	 * In-band validation error result. Carries jobId/status/name/workspace as null
	 * plus a single error; each payload type resolves only its declared fields, so
	 * the same shape serves every workspace mutation.
	 */
	private static Map<String, Object> errorResult(String message, String code) {
		Map<String, Object> error = new HashMap<>();
		error.put("field", null);
		error.put("message", message);
		error.put("code", code);

		Map<String, Object> result = new HashMap<>();
		result.put("jobId", null);
		result.put("status", null);
		result.put("name", null);
		result.put("workspace", null);
		result.put("errors", List.of(error));
		return result;
	}

	private static boolean toBoolean(Object value) {
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof String) {
			return Boolean.parseBoolean(((String) value).trim());
		}
		return false;
	}

	private static Session callerSession(DataFetchingEnvironment environment) {
		return GraphQLExecutionContext.from(environment).getCallerSession();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> inputArg(DataFetchingEnvironment environment) {
		Object input = environment.getArgument("input");
		if (input instanceof Map) {
			return (Map<String, Object>) input;
		}
		return new HashMap<>();
	}

	private static String loadSchema() throws Exception {
		try (InputStream in = PlatformWorkspaceWiringContributor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Workspace GraphQL schema resource not found: " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
