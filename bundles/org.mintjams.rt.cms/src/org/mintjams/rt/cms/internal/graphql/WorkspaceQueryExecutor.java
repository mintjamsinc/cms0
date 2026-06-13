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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProvider;
import org.mintjams.rt.cms.internal.eip.WorkspaceIntegrationEngineProvider;
import org.mintjams.rt.cms.internal.workspace.WorkspaceSettings;

/**
 * GraphQL Query executor for repository workspaces. Workspace names are not
 * sensitive — every authenticated user needs them to switch the desktop
 * between workspaces — so the listing carries no admin restriction; the
 * lifecycle state it reports is no more than what the console apps already
 * reveal per workspace ("engine not available").
 */
public class WorkspaceQueryExecutor {

	private static final String SYSTEM_WORKSPACE_NAME = "system";

	private final Session session;

	public WorkspaceQueryExecutor(Session session) {
		this.session = session;
	}

	/**
	 * Returns the workspaces available in the repository, the system
	 * workspace first, the rest in alphabetical order. Each entry reports
	 * whether it is the workspace this session is bound to, whether it is
	 * the system workspace (the identity store, which can never be
	 * deleted), its lifecycle state, and the state of its per-workspace
	 * engines:
	 *
	 * <ul>
	 * <li>{@code state} — {@code ONLINE} when the workspace's CMS services
	 * are running, {@code STARTING} when the JCR workspace is open but the
	 * services are not (yet): either startup is still in progress (e.g. a
	 * workspace just discovered on another cluster node) or the start
	 * failed — see the server log.</li>
	 * <li>{@code processEngine} / {@code integrationEngine} —
	 * {@code enabled} is the configuration switch ({@code bpm.yml} /
	 * {@code eip.yml}), {@code running} is the actual state. Enabled but
	 * not running means the engine failed to start; {@code enabled} is
	 * {@code null} while the workspace's services are not running (the
	 * configuration has not been read).</li>
	 * </ul>
	 */
	public Map<String, Object> executeWorkspacesQuery(GraphQLRequest request) throws Exception {
		String currentName = session.getWorkspace().getName();

		List<String> names = new ArrayList<>(Arrays.asList(session.getWorkspace().getAccessibleWorkspaceNames()));
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

		Map<String, Object> data = new HashMap<>();
		data.put("workspaces", workspaces);
		return data;
	}

	/**
	 * Builds the GraphQL representation of one workspace. Shared with the
	 * workspace mutations so a freshly created workspace is reported with
	 * the same shape (including lifecycle state) as a listed one.
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
			CmsService.getLogger(WorkspaceQueryExecutor.class)
					.warn("Could not read workspace settings for: " + name, ex);
		}
		workspace.put("displayName", settings.getDisplayName());
		// The system workspace always starts; auto-start does not apply to it.
		workspace.put("autoStart", SYSTEM_WORKSPACE_NAME.equals(name) ? true : settings.isAutoStart());

		// The servlet provider is created at the end of the service start
		// sequence and is CmsService's own idempotency marker, so its
		// presence is the platform's definition of "online". When it is
		// absent, the workspace is either STOPPED (deliberately idle — the
		// operator must start it), FAILED (the start threw — the user should
		// stop waiting), or still STARTING. The error text rides along so the
		// UI can show why a FAILED workspace failed without a trip to the
		// server log.
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
		// `enabled` is the persisted configuration intent, read from bpm.yml so
		// it is available (and editable) even while the workspace is stopped;
		// `running` is the live runtime state. Enabled but not running means the
		// switch is on but the workspace's services are down (stopped, or the
		// engine failed to start).
		state.put("enabled", org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProviderConfiguration.isEnabledOnDisk(workspaceName));
		state.put("running", (provider != null && provider.isAvailable()));
		return state;
	}

	private static Map<String, Object> integrationEngineState(String workspaceName) {
		WorkspaceIntegrationEngineProvider provider = CmsService.getWorkspaceIntegrationEngineProvider(workspaceName);
		Map<String, Object> state = new HashMap<>();
		state.put("enabled", org.mintjams.rt.cms.internal.eip.WorkspaceIntegrationEngineProviderConfiguration.isEnabledOnDisk(workspaceName));
		state.put("running", (provider != null && provider.isAvailable()));
		return state;
	}

}
