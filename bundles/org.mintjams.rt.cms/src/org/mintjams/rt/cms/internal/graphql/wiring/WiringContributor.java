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

package org.mintjams.rt.cms.internal.graphql.wiring;

import org.mintjams.rt.cms.internal.graphql.engine.GraphQLSchemaCompiler;

/**
 * A programmatic source of GraphQL wiring for a workspace's schema.
 * Implementations contribute built-in SDL together with Java resolvers (data
 * fetchers, type resolvers and custom scalars) that {@link GraphQLSchemaCompiler}
 * merges with the folder-based SDL and Groovy resolvers it discovers in the
 * workspace.
 *
 * <p>This is the migration seam for moving the platform's handmade
 * {@code /bin/graphql.cgi} API onto graphql-java: a contributor exposes the
 * built-in schema (Workspace/JCR, IDP, BPM, EIP, ...) and wires the existing
 * executors as Java {@link graphql.schema.DataFetcher}s, while
 * application-defined SDL and Groovy resolvers continue to work unchanged. When
 * no contributor is supplied, compilation uses folder-based (application) SDL and
 * Groovy resolvers only.
 *
 * <p>{@link #contribute(String)} is invoked on every schema (re)compile, so it
 * must be inexpensive and free of side effects. The resolver instances held by
 * the returned {@link SchemaContribution} are reused for the lifetime of the
 * compiled schema (until the next recompile), mirroring how the folder-based
 * {@code GroovyDataFetcher}s are captured once at compile time.
 */
@FunctionalInterface
public interface WiringContributor {

	/**
	 * Produces the wiring this contributor adds for {@code workspaceName}, or
	 * {@code null} to contribute nothing for that workspace.
	 *
	 * @throws Exception to fail the compile; the engine then keeps the previously
	 *                   compiled schema (see {@link GraphQLSchemaCompiler}).
	 */
	SchemaContribution contribute(String workspaceName) throws Exception;

}
