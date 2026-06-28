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

package org.mintjams.rt.cms.internal.graphql.resolver;

/**
 * Marks an exception as having originated in an application-defined (Groovy)
 * resolver, as opposed to a platform Java resolver.
 *
 * <p>{@link GroovyDataFetcher} wraps any throwable a resolver script raises in
 * this type so the unified engine's {@code DataFetcherExceptionHandler} can tell
 * the two apart. Application resolvers are author-trusted code (the same trust
 * model as BPM {@code CmsDelegate} and EIP {@code cms:} scripts), so their thrown
 * messages are surfaced to the client verbatim — preserving the dynamic GraphQL
 * developer experience the application engine had with graphql-java's default
 * handler. Platform Java resolver exceptions, by contrast, are classified and
 * sanitized so server internals never reach the wire.
 *
 * <p>{@link #getMessage()} returns the wrapped cause's message (not the usual
 * {@code "ClassName: message"} form), so a handler that does not unwrap still
 * reports something useful.
 */
public class AppResolverException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public AppResolverException(Throwable cause) {
		super(cause);
	}

	@Override
	public String getMessage() {
		Throwable cause = getCause();
		return (cause == null) ? null : cause.getMessage();
	}
}
