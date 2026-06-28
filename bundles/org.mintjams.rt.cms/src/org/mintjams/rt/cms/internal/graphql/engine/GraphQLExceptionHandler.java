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

package org.mintjams.rt.cms.internal.graphql.engine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.LoginException;
import javax.jcr.PathNotFoundException;
import javax.script.ScriptException;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.graphql.resolver.AppResolverException;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;

/**
 * Maps a resolver exception to a clean, structured GraphQL error for the
 * platform engine, so the wire response never carries Java internals.
 *
 * <p>graphql-java's default handler echoes the raw exception message into the
 * error {@code message} ("Exception while fetching data (/x) : &lt;msg&gt;"),
 * which leaks internals (paths, SQL, class details) for unexpected failures.
 * Instead this handler classifies the cause into a stable {@code code}
 * ({@code BAD_REQUEST} / {@code FORBIDDEN} / {@code NOT_FOUND} /
 * {@code UNAVAILABLE} / {@code INTERNAL_ERROR}) and exposes a safe
 * {@code reason}: for client-facing errors the exception message (our resolvers
 * throw these deliberately), for unexpected ones a generic message while the
 * real exception is logged server-side. The Webtop's GraphQL client already
 * reads {@code extensions.code}/{@code reason}, so this needs no client change.
 *
 * <p>Exception class and stack trace are added to {@code extensions} only when
 * the {@code org.mintjams.cms.graphql.devErrors} system property is set, so a
 * production deployment never leaks them.
 *
 * <p>Application (Groovy) resolvers are treated differently from platform Java
 * resolvers. The unified engine merges both into one schema, so this single
 * handler sees both; an {@link AppResolverException} (raised by
 * {@code GroovyDataFetcher} for any throwable a resolver script raises) marks an
 * application resolver. Those are author-trusted code, so their thrown message is
 * always surfaced (never collapsed to "Internal server error"), preserving the
 * dynamic GraphQL developer experience; only the {@code code} is classified, with
 * an otherwise-unclassified failure reported as {@code RESOLVER_ERROR}.
 */
final class GraphQLExceptionHandler implements DataFetcherExceptionHandler {

	private static final boolean DEV_ERRORS = Boolean.getBoolean("org.mintjams.cms.graphql.devErrors");

	@Override
	public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(
			DataFetcherExceptionHandlerParameters parameters) {
		boolean appResolver = isAppResolver(parameters.getException());
		Throwable exception = unwrap(parameters.getException());
		String code;
		boolean clientFacing;
		if (appResolver) {
			// Application (Groovy) resolver: trusted author code — always surface its
			// own message; report an otherwise-unclassified failure as RESOLVER_ERROR
			// (not the internal-error code, which hides the message).
			code = classify(exception);
			if ("INTERNAL_ERROR".equals(code)) {
				code = "RESOLVER_ERROR";
			}
			clientFacing = true;
		} else {
			code = classify(exception);
			clientFacing = !"INTERNAL_ERROR".equals(code);
		}
		String reason = clientFacing ? messageOf(exception) : "Internal server error";

		// Unexpected platform failures are logged with the real cause; the client only
		// sees the generic reason above. Application resolver errors are surfaced to
		// their author through the response, so they are not logged here.
		if (!clientFacing) {
			CmsService.getLogger(getClass()).error("Unhandled platform GraphQL resolver exception", exception);
		}

		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("code", code);
		extensions.put("reason", reason);
		if (DEV_ERRORS) {
			extensions.put("exceptionType", exception.getClass().getName());
			extensions.put("stackTrace", stackTraceOf(exception));
		}

		GraphQLError error = GraphqlErrorBuilder.newError()
				.message(reason)
				.path(parameters.getPath())
				.location(parameters.getSourceLocation())
				.extensions(extensions)
				.build();
		return CompletableFuture.completedFuture(
				DataFetcherExceptionHandlerResult.newResult().error(error).build());
	}

	// graphql-java (and reflective dispatch) can wrap the resolver's exception;
	// classify and report the original cause. Groovy resolvers add two more layers:
	// our AppResolverException marker and the script engine's ScriptException.
	private static Throwable unwrap(Throwable exception) {
		Throwable t = exception;
		while ((t instanceof CompletionException || t instanceof InvocationTargetException
				|| t instanceof AppResolverException || t instanceof ScriptException)
				&& t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t;
	}

	/** Whether the failure originated in an application (Groovy) resolver. */
	private static boolean isAppResolver(Throwable exception) {
		for (Throwable t = exception; t != null && t.getCause() != t; t = t.getCause()) {
			if (t instanceof AppResolverException) {
				return true;
			}
		}
		return false;
	}

	private static String classify(Throwable exception) {
		if (exception instanceof IllegalArgumentException) {
			return "BAD_REQUEST";
		}
		if (exception instanceof LoginException || exception instanceof AccessDeniedException
				|| exception instanceof javax.jcr.security.AccessControlException) {
			return "FORBIDDEN";
		}
		if (exception instanceof PathNotFoundException || exception instanceof ItemNotFoundException) {
			return "NOT_FOUND";
		}
		if (exception instanceof IllegalStateException) {
			return "UNAVAILABLE";
		}
		return "INTERNAL_ERROR";
	}

	private static String messageOf(Throwable exception) {
		String message = exception.getMessage();
		return (message == null || message.isEmpty()) ? exception.getClass().getSimpleName() : message;
	}

	private static String stackTraceOf(Throwable exception) {
		StringWriter writer = new StringWriter();
		exception.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}
}
