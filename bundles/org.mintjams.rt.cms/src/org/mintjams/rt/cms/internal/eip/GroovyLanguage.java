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

package org.mintjams.rt.cms.internal.eip;

import java.io.IOException;
import java.io.StringReader;

import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Predicate;
import org.apache.camel.spi.Language;
import org.apache.camel.support.ExpressionAdapter;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.ScriptingContext;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class GroovyLanguage implements Language {

	public static final String NAME = "groovy";
	public static final String REGISTRY_ALIAS = "groovy-language";
	private final String fWorkspaceName;

	public GroovyLanguage(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	@Override
	public Predicate createPredicate(String expression) {
		return compile(expression);
	}

	@Override
	public Expression createExpression(String expression) {
		return compile(expression);
	}

	private ExpressionAdapter compile(String expression) {
		if ((expression == null) || expression.trim().isEmpty()) {
			throw new IllegalArgumentException("A groovy expression must not be empty.");
		}

		return new ExpressionAdapter() {
			@Override
			public Object evaluate(Exchange exchange) {
				ScriptingContext context = (ScriptingContext) getEnv("mi:cms.context", exchange);
				WorkspaceScriptContext scriptingContext = null;
				if (!(context instanceof WorkspaceScriptContext)) {
					scriptingContext = new WorkspaceScriptContext(fWorkspaceName);
					try {
						Scripts.prepareAPIs(scriptingContext);
					} catch (IOException ex) {
						throw Cause.create(ex).wrap(IllegalStateException.class, "Failed to prepare APIs for groovy expression: " + expression);
					}
					context = scriptingContext;
				}

				try (ScriptReader scriptReader = new ScriptReader(new StringReader(expression))) {
					context.setAttribute("exchange", exchange);
					return scriptReader
							.setScriptName("inline")
							.setExtension("groovy")
							.setScriptEngineManager(Scripts.getScriptEngineManager(context))
							.setClassLoader(Scripts.getClassLoader(context))
							.setScriptContext(context)
							.eval();
				} catch (Throwable ex) {
					throw Cause.create(ex).wrap(IllegalStateException.class, "Failed to evaluate groovy expression: " + expression);
				} finally {
					context.removeAttribute("exchange");
					if (scriptingContext != null) {
						try {
							scriptingContext.close();
						} catch (Throwable ignore) {}
					}
				}
			}

			@Override
			public String toString() {
				return "groovy[" + expression + "]";
			}
		};
	}

	private Object getEnv(String name, Exchange exchange) {
		name = name.trim();
		if (Strings.isEmpty(name)) {
			throw new IllegalArgumentException("The name of the environment variable must not be empty.");
		}

		Object value = exchange.getProperty(name);
		if (value instanceof String key) {
			if (key.startsWith("@property.")) {
				return exchange.getProperty(key.substring("@property.".length()));
			}
			if (key.startsWith("@header.")) {
				return exchange.getIn().getHeader(key.substring("@header.".length()));
			}
			if (key.equalsIgnoreCase("@body")) {
				return exchange.getIn().getBody();
			}
		}
		return value;
	}

}
