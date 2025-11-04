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

/**
 * Main class for executing GraphQL requests
 */
public class GraphQLExecutor {

	private final Session session;
	private final QueryExecutor queryExecutor;
	private final MutationExecutor mutationExecutor;

	public GraphQLExecutor(Session session) {
		this.session = session;
		this.queryExecutor = new QueryExecutor(session);
		this.mutationExecutor = new MutationExecutor(session);
	}

	/**
	 * Execute GraphQL request
	 */
	public GraphQLResponse execute(GraphQLRequest request) {
		GraphQLResponse response = new GraphQLResponse();

		try {
			if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
				response.addError("Query must not be empty");
				return response;
			}

			if (request.isQuery()) {
				executeQuery(request, response);
			} else if (request.isMutation()) {
				executeMutation(request, response);
			} else {
				response.addError("Unsupported operation type");
			}

		} catch (Exception e) {
			response.addError("Execution failed: " + e.getMessage(), e);
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
			} else {
				response.addError("Unknown query operation");
			}

		} catch (Exception e) {
			response.addError("Query execution failed: " + e.getMessage(), e);
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
			} else {
				response.addError("Unknown mutation operation");
			}

		} catch (Exception e) {
			response.addError("Mutation execution failed: " + e.getMessage(), e);
		}
	}

	public Session getSession() {
		return session;
	}
}
