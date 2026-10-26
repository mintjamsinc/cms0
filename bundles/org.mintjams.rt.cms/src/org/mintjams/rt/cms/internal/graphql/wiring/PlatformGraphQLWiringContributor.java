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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.AccessDeniedException;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;
import org.mintjams.rt.cms.internal.graphql.engine.GraphQLSchemaDeployment;
import org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider;
import org.mintjams.rt.cms.internal.util.ISO8601;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;

/**
 * Wires {@code graphql-schema.graphqls}: how the last deployment of the
 * workspace's application schema went, so that an author sees what is served
 * and why a file did not deploy without reading the server log. Like every
 * platform field it answers for the workspace the call came to. The paths and
 * messages describe the workspace's code, so a guest is refused.
 */
public final class PlatformGraphQLWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/graphql-schema.graphqls";

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "graphqlSchemaDeployment",
						(DataFetcher<Object>) PlatformGraphQLWiringContributor::graphqlSchemaDeployment);
	}

	private static Object graphqlSchemaDeployment(DataFetchingEnvironment environment) throws Exception {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		org.mintjams.jcr.Session session = org.mintjams.jcr.Session.class.cast(context.getCallerSession());
		if (session.isGuest() || session.isAnonymous()) {
			throw new AccessDeniedException("Sign in to see the GraphQL schema deployment.");
		}
		WorkspaceGraphQLEngineProvider engine = CmsService.getWorkspaceGraphQLEngineProvider(context.getWorkspaceName());
		GraphQLSchemaDeployment deployment = engine.getSchemaDeployment();

		Map<String, Object> map = new LinkedHashMap<>();
		map.put("status", deployment.getStatus().name());
		map.put("attemptedAt", format(deployment.getAttemptedAtMillis()));
		map.put("servedSince", format(deployment.getServedSinceMillis()));
		List<Map<String, Object>> files = new ArrayList<>();
		for (GraphQLSchemaDeployment.FileEntry file : deployment.getFiles()) {
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("path", file.getPath());
			f.put("kind", file.getKind());
			f.put("target", file.getTarget());
			files.add(f);
		}
		map.put("files", files);
		map.put("errors", problems(deployment.getErrors()));
		map.put("warnings", problems(deployment.getWarnings()));
		return map;
	}

	private static List<Map<String, Object>> problems(List<GraphQLSchemaDeployment.Problem> problems) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (GraphQLSchemaDeployment.Problem problem : problems) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("path", problem.getPath());
			p.put("message", problem.getMessage());
			list.add(p);
		}
		return list;
	}

	private static String format(long millis) {
		return millis > 0 ? ISO8601.format(Instant.ofEpochMilli(millis)) : null;
	}

	private static String loadSchema() throws Exception {
		try (InputStream in = PlatformGraphQLWiringContributor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("GraphQL deployment schema resource not found: " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
