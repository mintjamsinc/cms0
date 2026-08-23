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

import java.util.Collections;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Predicate;
import org.apache.camel.spi.Language;
import org.apache.camel.support.ExpressionAdapter;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;

/**
 * The {@code jexl} language, for the predicates on {@code <when>} and
 * {@code <filter>}.
 * <p>
 * Camel's Simple language has no parenthesis grouping, which is why a route that
 * needs {@code (a || b) && (c || d)} writes it as a flag header and two nested
 * {@code <choice>} elements — three nodes and a header for one condition. It also
 * cannot dereference a header whose name contains a colon, so a route carrying
 * {@code commerce:status} keeps a second, colon-free copy of it.
 * <p>
 * Both go away here. Nothing is written from scratch: JEXL is on the classpath
 * already and the platform has been using it for expressions elsewhere.
 *
 * <h2>What a predicate can see</h2>
 *
 * {@code header}, {@code property} and {@code body}, and nothing else. The
 * exchange itself is deliberately not exposed: from it a predicate could reach
 * the route, the context and the registry, which would make the condition on a
 * canvas a place to hide behaviour.
 *
 * <h2>Bracket notation is the convention</h2>
 *
 * Write {@code header['commerce:status']}, never {@code header.status}. It reads
 * a colon-bearing name, and it is what keeps {@code strict} mode usable: a map
 * access to an absent key is null, while a property access to an absent property
 * throws. Being able to say "false when the header is missing" without loosening
 * the engine is worth a convention.
 *
 * <h2>No method calls</h2>
 *
 * {@code header['email'].toLowerCase()} does not parse. That closes
 * {@code ''.getClass().forName(...)} at the same time as it closes the smaller
 * problem: a predicate that lower-cases before comparing has a transformation
 * hidden inside a condition, and the whole point of the node graph is that
 * transformations are nodes. Use {@code transform:lowerCase} in front.
 * <p>
 * Operators, {@code empty()}, {@code size()} and map access are evaluated by the
 * interpreter itself, so refusing every method leaves the useful language intact.
 */
public class JexlLanguage implements Language {

	/** How this language is looked up. */
	public static final String NAME = "jexl";

	/**
	 * Camel resolves a language by name and then by name + "-language", and which
	 * one it uses depends on how the route was assembled. Registering both is the
	 * difference between a predicate that works and one that fails at startup in
	 * some deployments and not others.
	 */
	public static final String REGISTRY_ALIAS = "jexl-language";

	private final JexlEngine fEngine;

	public JexlLanguage() {
		fEngine = new JexlBuilder().cache(512).strict(true).silent(false).create();
	}

	@Override
	public Predicate createPredicate(String expression) {
		return compile(expression);
	}

	@Override
	public Expression createExpression(String expression) {
		return compile(expression);
	}

	/**
	 * One compiled form for both, since ExpressionAdapter is a Predicate as well:
	 * the same text on a &lt;when&gt; and in a &lt;setHeader&gt; means the same
	 * thing, and evaluating it twice as two objects would be two chances to differ.
	 */
	private ExpressionAdapter compile(String expression) {
		if ((expression == null) || expression.trim().isEmpty()) {
			throw new IllegalArgumentException("A jexl expression must not be empty.");
		}
		JexlExpression compiled = fEngine.createExpression(expression);
		return new ExpressionAdapter() {
			@Override
			public Object evaluate(Exchange exchange) {
				return compiled.evaluate(contextOf(exchange));
			}

			@Override
			public String toString() {
				return "jexl[" + expression + "]";
			}
		};
	}

	/**
	 * The variables a predicate is evaluated against.
	 * <p>
	 * {@code header} and {@code property} are always defined, even when empty:
	 * under {@code strict} an undefined <em>variable</em> throws, while a missing
	 * <em>key</em> is simply null. Setting them unconditionally is what makes
	 * {@code header['absent'] == null} a usable way to say "not set".
	 */
	private JexlContext contextOf(Exchange exchange) {
		JexlContext context = new MapContext();
		Map<String, Object> headers = exchange.getIn().getHeaders();
		context.set("header", (headers != null) ? Collections.unmodifiableMap(headers) : Map.of());
		Map<String, Object> properties = exchange.getProperties();
		context.set("property", (properties != null) ? Collections.unmodifiableMap(properties) : Map.of());
		context.set("body", exchange.getIn().getBody());
		return context;
	}

}
