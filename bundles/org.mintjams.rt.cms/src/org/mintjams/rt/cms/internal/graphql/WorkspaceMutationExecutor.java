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

package org.mintjams.rt.cms.internal.graphql;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProviderConfiguration;
import org.mintjams.rt.cms.internal.eip.WorkspaceIntegrationEngineProviderConfiguration;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.job.workspace.WorkspaceLifecycleJob;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.workspace.WorkspaceSettings;

/**
 * GraphQL Mutation executor for repository workspace management. Creating
 * and deleting workspaces is a repository-wide operation reserved for
 * administrators and service accounts (the same trust level the JCR layer
 * itself enforces in {@code Workspace#createWorkspace}).
 */
public class WorkspaceMutationExecutor {

	private static final String SYSTEM_WORKSPACE_NAME = "system";

	private final Session session;

	public WorkspaceMutationExecutor(Session session) {
		this.session = session;
	}

	/**
	 * Starts an asynchronous workspace creation and returns its {@code jobId}.
	 * Creating the JCR workspace and bringing its CMS services online —
	 * provisioning plus content deployment — can take minutes (far beyond an
	 * HTTP idle timeout) and either step can fail, so the work is handed to a
	 * {@link WorkspaceLifecycleJob} on the JobManager. The caller watches
	 * {@code jobProgress(jobId)} for the {@code creating}/{@code starting}
	 * phases and the terminal COMPLETED/FAILED event, the latter carrying the
	 * reason so the user can dismiss it rather than wait forever. Validation
	 * (privileges, name, "already exists") is still performed synchronously so
	 * obvious mistakes fail the mutation immediately.
	 */
	public Map<String, Object> executeCreateWorkspace(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String name = (String) input.get("name");

		if (!isWorkspaceManager()) {
			return errorPayload("createWorkspace", "Workspace management requires administrative privileges", "ACCESS_DENIED");
		}
		if (name == null || name.isEmpty()) {
			return errorPayload("createWorkspace", "name is required", "INVALID_INPUT");
		}
		if (Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()).contains(name)) {
			return errorPayload("createWorkspace", "Workspace already exists: " + name, "ALREADY_EXISTS");
		}

		String jobId = submitWorkspaceJob(WorkspaceLifecycleJob.Operation.CREATE, WorkspaceLifecycleJob.TYPE_CREATE, name);

		Map<String, Object> result = new HashMap<>();
		result.put("jobId", jobId);
		result.put("status", JobStatus.QUEUED.toExternalString());
		result.put("errors", null);
		return wrap("createWorkspace", result);
	}

	/**
	 * Starts an asynchronous workspace deletion and returns its {@code jobId}.
	 * Deletion first stops the workspace's services and waits for them to come
	 * fully down before removing the directory (the {@code stopping} then
	 * {@code deleting} phases) — removing files while the workspace is still
	 * starting or running is what leaves debris behind — and like creation it
	 * may run long, so it is handed to a {@link WorkspaceLifecycleJob}. The
	 * system workspace — the identity store — and the workspace this session
	 * is bound to cannot be deleted.
	 */
	public Map<String, Object> executeDeleteWorkspace(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String name = (String) input.get("name");

		if (!isWorkspaceManager()) {
			return errorPayload("deleteWorkspace", "Workspace management requires administrative privileges", "ACCESS_DENIED");
		}
		if (name == null || name.isEmpty()) {
			return errorPayload("deleteWorkspace", "name is required", "INVALID_INPUT");
		}
		if (SYSTEM_WORKSPACE_NAME.equals(name)) {
			return errorPayload("deleteWorkspace", "The system workspace cannot be deleted", "INVALID_INPUT");
		}
		if (name.equals(session.getWorkspace().getName())) {
			return errorPayload("deleteWorkspace", "The workspace this session is bound to cannot be deleted", "INVALID_INPUT");
		}
		if (!Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()).contains(name)) {
			return errorPayload("deleteWorkspace", "Workspace not found: " + name, "NOT_FOUND");
		}

		String jobId = submitWorkspaceJob(WorkspaceLifecycleJob.Operation.DELETE, WorkspaceLifecycleJob.TYPE_DELETE, name);

		Map<String, Object> result = new HashMap<>();
		result.put("jobId", jobId);
		result.put("status", JobStatus.QUEUED.toExternalString());
		result.put("name", name);
		result.put("errors", null);
		return wrap("deleteWorkspace", result);
	}

	/**
	 * Starts an asynchronous start of a stopped workspace and returns its
	 * {@code jobId}. Bringing a workspace online runs provisioning and content
	 * deployment and can take minutes, so — like create — it is handed to a
	 * {@link WorkspaceLifecycleJob} and watched through {@code jobProgress}.
	 */
	public Map<String, Object> executeStartWorkspace(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String name = (String) input.get("name");

		if (!isWorkspaceManager()) {
			return errorPayload("startWorkspace", "Workspace management requires administrative privileges", "ACCESS_DENIED");
		}
		if (name == null || name.isEmpty()) {
			return errorPayload("startWorkspace", "name is required", "INVALID_INPUT");
		}
		if (!Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()).contains(name)) {
			return errorPayload("startWorkspace", "Workspace not found: " + name, "NOT_FOUND");
		}

		String jobId = submitWorkspaceJob(WorkspaceLifecycleJob.Operation.START, WorkspaceLifecycleJob.TYPE_START, name);
		return jobAcceptedPayload("startWorkspace", jobId, name);
	}

	/**
	 * Starts an asynchronous stop of a running workspace and returns its
	 * {@code jobId}. Stopping is synchronous on the server but serialised
	 * against any in-flight start, so it runs as a {@link WorkspaceLifecycleJob}
	 * for a consistent, watchable UX. The system workspace — the identity store
	 * — and the workspace this session is bound to cannot be stopped (stopping
	 * the latter would tear down the desktop serving the request).
	 */
	public Map<String, Object> executeStopWorkspace(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String name = (String) input.get("name");

		Map<String, Object> guard = guardStartedWorkspaceMutation("stopWorkspace", name);
		if (guard != null) {
			return guard;
		}

		String jobId = submitWorkspaceJob(WorkspaceLifecycleJob.Operation.STOP, WorkspaceLifecycleJob.TYPE_STOP, name);
		return jobAcceptedPayload("stopWorkspace", jobId, name);
	}

	/**
	 * Starts an asynchronous restart of a workspace and returns its
	 * {@code jobId}. A restart is how the BPM/EIP engine switches — read only
	 * at start time — are applied to a running workspace. Like stop, it cannot
	 * target the system workspace or the workspace this session is bound to.
	 */
	public Map<String, Object> executeRestartWorkspace(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String name = (String) input.get("name");

		Map<String, Object> guard = guardStartedWorkspaceMutation("restartWorkspace", name);
		if (guard != null) {
			return guard;
		}

		String jobId = submitWorkspaceJob(WorkspaceLifecycleJob.Operation.RESTART, WorkspaceLifecycleJob.TYPE_RESTART, name);
		return jobAcceptedPayload("restartWorkspace", jobId, name);
	}

	/**
	 * Updates a workspace's editable settings and returns the refreshed
	 * workspace. This is a synchronous configuration edit — no job — covering:
	 *
	 * <ul>
	 * <li>{@code displayName} and {@code autoStart} in
	 * {@code etc/workspace.yml} (take effect immediately; auto-start governs
	 * the next boot).</li>
	 * <li>{@code bpmEnabled} / {@code eipEnabled} in {@code etc/bpm/bpm.yml} /
	 * {@code etc/eip/eip.yml} — read only when the workspace's services start,
	 * so the caller restarts the workspace to apply them.</li>
	 * </ul>
	 *
	 * Each field is optional; only the ones present in the input are written.
	 * Auto-start is never written for the system workspace, which always
	 * starts.
	 */
	public Map<String, Object> executeUpdateWorkspace(GraphQLRequest request) throws Exception {
		Map<String, Object> input = extractInput(request);
		String name = (String) input.get("name");

		if (!isWorkspaceManager()) {
			return errorPayload("updateWorkspace", "Workspace management requires administrative privileges", "ACCESS_DENIED");
		}
		if (name == null || name.isEmpty()) {
			return errorPayload("updateWorkspace", "name is required", "INVALID_INPUT");
		}
		if (!Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()).contains(name)) {
			return errorPayload("updateWorkspace", "Workspace not found: " + name, "NOT_FOUND");
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

		// Announce the settings change so every connected desktop refreshes its
		// workspace switcher and dashboard live, mirroring what start/stop do for
		// runtime-state changes. The display name takes effect immediately, so a
		// label edit must propagate without waiting for a restart or page reload.
		if (changed) {
			CmsService.postWorkspaceChanged(name);
		}

		Map<String, Object> result = new HashMap<>();
		result.put("workspace", WorkspaceQueryExecutor.describeWorkspace(name, session.getWorkspace().getName()));
		result.put("errors", null);
		return wrap("updateWorkspace", result);
	}

	/**
	 * Shared guard for the stop/restart mutations: requires administrative
	 * privileges, an existing target, and refuses the system workspace and the
	 * workspace this session is bound to. Returns an error payload to short-
	 * circuit on, or {@code null} when the mutation may proceed.
	 */
	private Map<String, Object> guardStartedWorkspaceMutation(String mutationName, String name) throws Exception {
		if (!isWorkspaceManager()) {
			return errorPayload(mutationName, "Workspace management requires administrative privileges", "ACCESS_DENIED");
		}
		if (name == null || name.isEmpty()) {
			return errorPayload(mutationName, "name is required", "INVALID_INPUT");
		}
		if (SYSTEM_WORKSPACE_NAME.equals(name)) {
			return errorPayload(mutationName, "The system workspace cannot be stopped", "INVALID_INPUT");
		}
		if (name.equals(session.getWorkspace().getName())) {
			return errorPayload(mutationName, "The workspace this session is bound to cannot be stopped", "INVALID_INPUT");
		}
		if (!Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()).contains(name)) {
			return errorPayload(mutationName, "Workspace not found: " + name, "NOT_FOUND");
		}
		return null;
	}

	private Map<String, Object> jobAcceptedPayload(String mutationName, String jobId, String name) {
		Map<String, Object> result = new HashMap<>();
		result.put("jobId", jobId);
		result.put("status", JobStatus.QUEUED.toExternalString());
		result.put("name", name);
		result.put("errors", null);
		return wrap(mutationName, result);
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

	/**
	 * Allocates the {@code /var/jobs} record for a workspace lifecycle job in
	 * the requester's own workspace — never the target, whose own record area
	 * disappears with it on delete — records the target and queues it, then
	 * hands the work to the JobManager. The job node is created with a
	 * privileged session so the write to {@code /var/jobs} succeeds regardless
	 * of how that area's ACLs are configured; the requester's id is still
	 * recorded as the modifier.
	 */
	private String submitWorkspaceJob(WorkspaceLifecycleJob.Operation operation, String jobType, String targetWorkspace)
			throws Exception {
		String jobWorkspace = session.getWorkspace().getName();
		String userId = session.getUserID();
		int priority = 0;

		String jobId = JobNodes.newJobId();
		Session mgmt = CmsService.getRepository().login(new CmsServiceCredentials(userId), jobWorkspace);
		try {
			Node jobNode = JobNodes.createJobNode(mgmt, jobId, jobType, userId, priority);
			Node content = JobNodes.getContent(jobNode);
			content.setProperty(JobNodes.PROP_TARGET_WORKSPACE, targetWorkspace);
			JobNodes.setNodeId(mgmt, content);
			JobNodes.setStatus(content, JobStatus.QUEUED);
			mgmt.save();
		} catch (Exception ex) {
			try { mgmt.refresh(false); } catch (Throwable ignore) {}
			throw ex;
		} finally {
			try { mgmt.logout(); } catch (Throwable ignore) {}
		}

		CmsService.getJobManager().submit(
				new WorkspaceLifecycleJob(jobId, jobWorkspace, userId, priority, operation, targetWorkspace));
		return jobId;
	}

	private boolean isWorkspaceManager() {
		org.mintjams.jcr.Session jcrSession = org.mintjams.jcr.Session.class.cast(session);
		return jcrSession.isAdmin() || jcrSession.isService();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> extractInput(GraphQLRequest request) {
		Map<String, Object> variables = request.getVariables();
		if (variables != null && variables.containsKey("input")) {
			Object inp = variables.get("input");
			if (inp instanceof Map) {
				return (Map<String, Object>) inp;
			}
		}
		return new HashMap<>();
	}

	private Map<String, Object> errorPayload(String mutationName, String message, String code) {
		Map<String, Object> error = new HashMap<>();
		error.put("field", null);
		error.put("message", message);
		error.put("code", code);

		Map<String, Object> result = new HashMap<>();
		result.put("jobId", null);
		result.put("status", null);
		result.put("name", null);
		result.put("errors", List.of(error));
		return wrap(mutationName, result);
	}

	private Map<String, Object> wrap(String mutationName, Map<String, Object> result) {
		Map<String, Object> payload = new HashMap<>();
		payload.put(mutationName, result);
		return payload;
	}

}
