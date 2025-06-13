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

package org.mintjams.jcr.util;

import java.util.Collection;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.MapContext;
import org.mintjams.tools.lang.Strings;

public class ExpressionContext {

	private final JexlEngine fJexlEngine;
	private final JexlContext fJexlContext;

	private ExpressionContext() {
		fJexlEngine = new JexlBuilder().cache(512).strict(true).silent(false).create();
		fJexlContext = new MapContext();
	}

	public static ExpressionContext create() {
		return new ExpressionContext();
	}

	public ExpressionContext setVariable(String name, Object value) {
		fJexlContext.set(name, value);
		return this;
	}

	public Object evaluate(String expression) {
		return fJexlEngine.createExpression(expression).evaluate(fJexlContext);
	}

	public String getString(String expression) {
		return (String) evaluate(expression);
	}

	public String defaultString(String expression) {
		return Strings.defaultString(getString(expression));
	}

	public String defaultString(String expression, String defaultValue) {
		return Strings.defaultString(getString(expression), defaultValue);
	}

	public String defaultIfEmpty(String expression, String defaultValue) {
		return Strings.defaultIfEmpty(getString(expression), defaultValue);
	}

	public String[] getStringArray(String expression) {
		try {
			Object o = evaluate(expression);
			if (o instanceof String[]) {
				return (String[]) o;
			}
			if (o instanceof Collection) {
				return ((Collection<?>) o).stream().map(e -> e.toString()).toArray(String[]::new);
			}
			if (o instanceof String) {
				return ((String) o).split("\\s*,\\s*");
			}
		} catch (Throwable ignore) {}
		return new String[0];
	}

	public boolean getBoolean(String expression) {
		return getBoolean(expression, false);
	}

	public boolean getBoolean(String expression, boolean defaultValue) {
		Object value = evaluate(expression);
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof String) {
			return Boolean.valueOf((String) value);
		}
		return Boolean.valueOf(value.toString());
	}

	public int getInt(String expression) {
		return getInt(expression, 0);
	}

	public int getInt(String expression, int defaultValue) {
		Object value = evaluate(expression);
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		if (value instanceof String) {
			return Integer.parseInt((String) value);
		}
		return Integer.parseInt(value.toString());
	}

}
