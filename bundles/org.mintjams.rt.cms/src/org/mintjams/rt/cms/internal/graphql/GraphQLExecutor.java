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

package org.mintjams.rt.cms.internal.graphql;

import javax.jcr.Session;

import org.mintjams.tools.adapter.Adaptables;

/**
 * Main class for executing GraphQL requests
 */
public class GraphQLExecutor {

	private final Session session;
	private final QueryExecutor queryExecutor;
	private final MutationExecutor mutationExecutor;
	private final CamelQueryExecutor camelQueryExecutor;
	private final IdpQueryExecutor idpQueryExecutor;
	private final IdpMutationExecutor idpMutationExecutor;
	private final BpmQueryExecutor bpmQueryExecutor;
	private final BpmMutationExecutor bpmMutationExecutor;

	public GraphQLExecutor(Session session) {
		this.session = session;
		this.queryExecutor = new QueryExecutor(session);
		this.mutationExecutor = new MutationExecutor(session);
		this.camelQueryExecutor = new CamelQueryExecutor(session);
		this.idpQueryExecutor = new IdpQueryExecutor(session);
		this.idpMutationExecutor = new IdpMutationExecutor(session);
		this.bpmQueryExecutor = new BpmQueryExecutor(session);
		this.bpmMutationExecutor = new BpmMutationExecutor(session);
	}

	/**
	 * Execute GraphQL request
	 */
	public GraphQLResponse execute(GraphQLRequest request) {
		GraphQLResponse response = new GraphQLResponse();

		try {
			// Authentication check
			if (Adaptables.getAdapter(session, org.mintjams.jcr.Session.class).isGuest()) {
				response.addError("Authentication required");
				return response;
			}

			// Validate request
			if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
				response.addError("Query must not be empty");
				return response;
			}

			// Execute request
			if (request.isQuery()) {
				executeQuery(request, response);
			} else if (request.isMutation()) {
				executeMutation(request, response);
			} else {
				response.addError("Unsupported operation type");
			}
		} catch (Throwable ex) {
			response.addError("Execution failed: " + ex.getMessage(), ex);
		}

		return response;
	}

	/**
	 * Execute query operation
	 */
	private void executeQuery(GraphQLRequest request, GraphQLResponse response) {
		try {
			String query = request.getQuery().trim();

			// Simple query parsing (Phase 1: basic functionality only)
			if (query.contains("node(")) {
				response.setData(queryExecutor.executeNodeQuery(request));
			} else if (query.contains("children(")) {
				response.setData(queryExecutor.executeChildrenQuery(request));
			} else if (query.contains("references(")) {
				response.setData(queryExecutor.executeReferencesQuery(request));
			} else if (query.contains("effectiveAccessControl(")) {
				response.setData(queryExecutor.executeEffectiveAccessControlQuery(request));
			} else if (query.contains("searchPrincipals(")) {
				response.setData(queryExecutor.executeSearchPrincipalsQuery(request));
			} else if (query.contains("accessControl(")) {
				response.setData(queryExecutor.executeAccessControlQuery(request));
			} else if (query.contains("versionHistory(")) {
				response.setData(queryExecutor.executeVersionHistoryQuery(request));
			} else if (query.contains("xpath(")) {
				response.setData(queryExecutor.executeXPathQuery(request));
			} else if (query.contains("search(")) {
				response.setData(queryExecutor.executeSearchQuery(request));
			} else if (query.contains("query(") && query.contains("statement:")) {
				response.setData(queryExecutor.executeGenericQuery(request));
			} else if (query.contains("camelContext")) {
				response.setData(camelQueryExecutor.executeCamelContextQuery(request));
			// IdP queries — check multi-word names before single-word names
			} else if (query.contains("groupTree(")) {
				response.setData(idpQueryExecutor.executeGroupTreeQuery(request));
			} else if (query.contains("groups(")) {
				response.setData(idpQueryExecutor.executeGroupsQuery(request));
			} else if (query.contains("group(")) {
				response.setData(idpQueryExecutor.executeGroupQuery(request));
			} else if (query.contains("roleTree(")) {
				response.setData(idpQueryExecutor.executeRoleTreeQuery(request));
			} else if (query.contains("roles(")) {
				response.setData(idpQueryExecutor.executeRolesQuery(request));
			} else if (query.contains("role(")) {
				response.setData(idpQueryExecutor.executeRoleQuery(request));
			} else if (query.contains("users(")) {
				response.setData(idpQueryExecutor.executeUsersQuery(request));
			} else if (query.contains("user(")) {
				response.setData(idpQueryExecutor.executeUserQuery(request));
			} else if (query.contains("me {") || query.contains("me{")) {
				response.setData(idpQueryExecutor.executeMeQuery(request));
			// BPM queries — check multi-word names before single-word names
			} else if (query.contains("processDefinitionXml(")) {
				response.setData(bpmQueryExecutor.executeProcessDefinitionXmlQuery(request));
			} else if (query.contains("processDefinitions(")) {
				response.setData(bpmQueryExecutor.executeProcessDefinitionsQuery(request));
			} else if (query.contains("processDefinition(")) {
				response.setData(bpmQueryExecutor.executeProcessDefinitionQuery(request));
			} else if (query.contains("processInstances(")) {
				response.setData(bpmQueryExecutor.executeProcessInstancesQuery(request));
			} else if (query.contains("processInstance(")) {
				response.setData(bpmQueryExecutor.executeProcessInstanceQuery(request));
			} else if (query.contains("activityHistory(")) {
				response.setData(bpmQueryExecutor.executeActivityHistoryQuery(request));
			} else if (query.contains("taskCounts(")) {
				response.setData(bpmQueryExecutor.executeTaskCountsQuery(request));
			} else if (query.contains("tasks(")) {
				response.setData(bpmQueryExecutor.executeTasksQuery(request));
			} else if (query.contains("task(")) {
				response.setData(bpmQueryExecutor.executeTaskQuery(request));
			} else {
				response.addError("Unknown query operation");
			}

		} catch (Throwable ex) {
			response.addError("Query execution failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Execute mutation operation
	 */
	private void executeMutation(GraphQLRequest request, GraphQLResponse response) {
		try {
			String query = request.getQuery().trim();

			// Simple mutation parsing (Phase 1: basic functionality only)
			if (query.contains("createFolder(")) {
				response.setData(mutationExecutor.executeCreateFolder(request));
			} else if (query.contains("createFile(")) {
				response.setData(mutationExecutor.executeCreateFile(request));
			} else if (query.contains("deleteNode(")) {
				response.setData(mutationExecutor.executeDeleteNode(request));
			} else if (query.contains("unlockNode(")) {
				response.setData(mutationExecutor.executeUnlockNode(request));
			} else if (query.contains("lockNode(")) {
				response.setData(mutationExecutor.executeLockNode(request));
			} else if (query.contains("deleteAccessControl(")) {
				response.setData(mutationExecutor.executeDeleteAccessControl(request));
			} else if (query.contains("setAccessControl(")) {
				response.setData(mutationExecutor.executeSetAccessControl(request));
			} else if (query.contains("setProperties(")) {
				response.setData(mutationExecutor.executeSetProperties(request));
			} else if (query.contains("deleteMixin(")) {
				response.setData(mutationExecutor.executeDeleteMixin(request));
			} else if (query.contains("addMixin(")) {
				response.setData(mutationExecutor.executeAddMixin(request));
			} else if (query.contains("restoreVersion(")) {
				response.setData(mutationExecutor.executeRestoreVersion(request));
			} else if (query.contains("addVersionControl(")) {
				response.setData(mutationExecutor.executeAddVersionControl(request));
			} else if (query.contains("checkpoint(")) {
				response.setData(mutationExecutor.executeCheckpoint(request));
			} else if (query.contains("uncheckout(")) {
				response.setData(mutationExecutor.executeUncheckout(request));
			} else if (query.contains("checkout(")) {
				response.setData(mutationExecutor.executeCheckout(request));
			} else if (query.contains("checkin(")) {
				response.setData(mutationExecutor.executeCheckin(request));
			} else if (query.contains("renameNode(")) {
				response.setData(mutationExecutor.executeRenameNode(request));
			} else if (query.contains("copyNode(")) {
				response.setData(mutationExecutor.executeCopyNode(request));
			} else if (query.contains("moveNode(")) {
				response.setData(mutationExecutor.executeMoveNode(request));
			// Multipart Upload mutations
			} else if (query.contains("initiateMultipartUpload")) {
				response.setData(mutationExecutor.executeInitiateMultipartUpload(request));
			} else if (query.contains("appendMultipartUploadChunk(")) {
				response.setData(mutationExecutor.executeAppendMultipartUploadChunk(request));
			} else if (query.contains("completeMultipartUpload(")) {
				response.setData(mutationExecutor.executeCompleteMultipartUpload(request));
			} else if (query.contains("abortMultipartUpload(")) {
				response.setData(mutationExecutor.executeAbortMultipartUpload(request));
			// IdP user mutations
			} else if (query.contains("changePassword(")) {
				response.setData(idpMutationExecutor.executeChangePassword(request));
			} else if (query.contains("resetPassword(")) {
				response.setData(idpMutationExecutor.executeResetPassword(request));
			} else if (query.contains("assignRoles(")) {
				response.setData(idpMutationExecutor.executeAssignRoles(request));
			} else if (query.contains("revokeRoles(")) {
				response.setData(idpMutationExecutor.executeRevokeRoles(request));
			} else if (query.contains("createUser(")) {
				response.setData(idpMutationExecutor.executeCreateUser(request));
			} else if (query.contains("updateUser(")) {
				response.setData(idpMutationExecutor.executeUpdateUser(request));
			} else if (query.contains("deleteUser(")) {
				response.setData(idpMutationExecutor.executeDeleteUser(request));
			// IdP role mutations
			} else if (query.contains("createRole(")) {
				response.setData(idpMutationExecutor.executeCreateRole(request));
			} else if (query.contains("updateRole(")) {
				response.setData(idpMutationExecutor.executeUpdateRole(request));
			} else if (query.contains("deleteRole(")) {
				response.setData(idpMutationExecutor.executeDeleteRole(request));
			// IdP group mutations
			} else if (query.contains("addGroupMembers(")) {
				response.setData(idpMutationExecutor.executeAddGroupMembers(request));
			} else if (query.contains("removeGroupMembers(")) {
				response.setData(idpMutationExecutor.executeRemoveGroupMembers(request));
			} else if (query.contains("createGroup(")) {
				response.setData(idpMutationExecutor.executeCreateGroup(request));
			} else if (query.contains("updateGroup(")) {
				response.setData(idpMutationExecutor.executeUpdateGroup(request));
			} else if (query.contains("deleteGroup(")) {
				response.setData(idpMutationExecutor.executeDeleteGroup(request));
			} else if (query.contains("moveGroup(")) {
				response.setData(idpMutationExecutor.executeMoveGroup(request));
			// Preference mutations
			} else if (query.contains("updatePreferences(")) {
				response.setData(idpMutationExecutor.executeUpdatePreferences(request));
			// BPM mutations — check multi-word names before single-word names
			} else if (query.contains("startProcess(")) {
				response.setData(bpmMutationExecutor.executeStartProcess(request));
			} else if (query.contains("suspendProcessInstance(")) {
				response.setData(bpmMutationExecutor.executeSuspendProcessInstance(request));
			} else if (query.contains("activateProcessInstance(")) {
				response.setData(bpmMutationExecutor.executeActivateProcessInstance(request));
			} else if (query.contains("cancelProcessInstance(")) {
				response.setData(bpmMutationExecutor.executeCancelProcessInstance(request));
			} else if (query.contains("deleteProcessInstance(")) {
				response.setData(bpmMutationExecutor.executeDeleteProcessInstance(request));
			} else if (query.contains("setProcessVariables(")) {
				response.setData(bpmMutationExecutor.executeSetProcessVariables(request));
			} else if (query.contains("claimTask(")) {
				response.setData(bpmMutationExecutor.executeClaimTask(request));
			} else if (query.contains("unclaimTask(")) {
				response.setData(bpmMutationExecutor.executeUnclaimTask(request));
			} else if (query.contains("setTaskAssignee(")) {
				response.setData(bpmMutationExecutor.executeSetTaskAssignee(request));
			} else if (query.contains("assignTask(")) {
				response.setData(bpmMutationExecutor.executeAssignTask(request));
			} else if (query.contains("delegateTask(")) {
				response.setData(bpmMutationExecutor.executeDelegateTask(request));
			} else if (query.contains("completeTask(")) {
				response.setData(bpmMutationExecutor.executeCompleteTask(request));
			} else if (query.contains("setTaskVariables(")) {
				response.setData(bpmMutationExecutor.executeSetTaskVariables(request));
			} else if (query.contains("addTaskComment(")) {
				response.setData(bpmMutationExecutor.executeAddTaskComment(request));
			} else if (query.contains("deployProcess(")) {
				response.setData(bpmMutationExecutor.executeDeployProcess(request));
			} else if (query.contains("deleteDeployment(")) {
				response.setData(bpmMutationExecutor.executeDeleteDeployment(request));
			} else if (query.contains("suspendProcessDefinition(")) {
				response.setData(bpmMutationExecutor.executeSuspendProcessDefinition(request));
			} else if (query.contains("activateProcessDefinition(")) {
				response.setData(bpmMutationExecutor.executeActivateProcessDefinition(request));
			} else {
				response.addError("Unknown mutation operation");
			}

		} catch (Throwable ex) {
			response.addError("Mutation execution failed: " + ex.getMessage(), ex);
		}
	}

	public Session getSession() {
		return session;
	}
}
