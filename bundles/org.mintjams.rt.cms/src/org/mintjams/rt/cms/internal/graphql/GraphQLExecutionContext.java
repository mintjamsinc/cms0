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

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.dataloader.DataLoaderRegistry;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.security.ServiceUserCredentials;

import graphql.schema.DataFetchingEnvironment;

/**
 * Per-request context for the platform (built-in) GraphQL engine, shared with
 * Java {@link graphql.schema.DataFetcher}s through the graphql-java
 * {@code GraphQLContext} (key {@link #CTX_EXECUTION_CONTEXT}).
 *
 * <p>It carries the caller's JCR {@link Session} — so repository ACLs govern
 * what each resolver can read, exactly as the handmade {@code /bin/graphql.cgi}
 * executors operate — together with the request's {@link DataLoaderRegistry}
 * (for batching N+1 lookups) and helpers to open privileged/service sessions
 * for the few resolvers that legitimately need them.
 *
 * <h2>Threading</h2>
 * The caller {@link Session} is a single, mutable, non-thread-safe object shared
 * across all field resolutions of the request. Field resolution must therefore
 * stay single-threaded for a given request (synchronous data fetchers, and a
 * serial execution strategy if asynchronous fetchers are later introduced).
 */
public final class GraphQLExecutionContext {

	/** GraphQLContext key under which this context is shared with data fetchers. */
	public static final String CTX_EXECUTION_CONTEXT = "org.mintjams.graphql.executionContext";

	private final String fWorkspaceName;
	private final Session fCallerSession;
	private final DataLoaderRegistry fDataLoaderRegistry;

	public GraphQLExecutionContext(String workspaceName, Session callerSession, DataLoaderRegistry dataLoaderRegistry) {
		fWorkspaceName = workspaceName;
		fCallerSession = callerSession;
		fDataLoaderRegistry = dataLoaderRegistry;
	}

	/**
	 * Resolves the context from a field's {@link DataFetchingEnvironment}, or
	 * {@code null} when invoked outside the platform engine.
	 */
	public static GraphQLExecutionContext from(DataFetchingEnvironment environment) {
		return environment.getGraphQlContext().get(CTX_EXECUTION_CONTEXT);
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	/** The caller's JCR session; repository ACLs govern what resolvers may read. */
	public Session getCallerSession() {
		return fCallerSession;
	}

	public DataLoaderRegistry getDataLoaderRegistry() {
		return fDataLoaderRegistry;
	}

	/**
	 * Opens a privileged service session for this workspace. The caller owns the
	 * returned session and must {@code logout()} it (used only by resolvers that
	 * legitimately read beyond the caller's ACLs, e.g. system metadata).
	 */
	public Session openServiceSession() throws RepositoryException {
		return CmsService.getRepository().login(new CmsServiceCredentials(), fWorkspaceName);
	}

	/**
	 * Opens a session impersonating {@code runAsId} for this workspace. The caller
	 * owns the returned session and must {@code logout()} it.
	 */
	public Session openServiceSession(String runAsId) throws RepositoryException {
		return CmsService.getRepository().login(new ServiceUserCredentials(runAsId), fWorkspaceName);
	}

}
