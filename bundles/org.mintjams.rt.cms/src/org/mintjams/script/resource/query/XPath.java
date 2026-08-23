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

package org.mintjams.script.resource.query;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.ScriptingContext;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.tools.collections.AdaptableList;

public class XPath {

	private WorkspaceScriptContext fContext;

	public XPath(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static XPath get(ScriptingContext context) {
		return (XPath) context.getAttribute(XPath.class.getSimpleName());
	}

	@SuppressWarnings("deprecation")
	public Query createQuery(String statement) throws ResourceException {
		return fContext.getSession().getWorkspace().getQueryManager().createQuery(statement, javax.jcr.query.Query.XPATH);
	}

	public String value(java.util.Date value) {
		return "xs:dateTime(" + value(AdaptableList.<Object>newBuilder().add(value).build().getString(0)) + ")";
	}

	public String value(boolean value) {
		return "xs:boolean(" + value(value ? "1" : "0") + ")";
	}

	public String value(int value) {
		return "xs:long(" + value("" + value) + ")";
	}

	public String value(long value) {
		return "xs:long(" + value("" + value) + ")";
	}

	public String value(double value) {
		return value(BigDecimal.valueOf(value));
	}

	public String value(BigDecimal value) {
		return "xs:decimal(" + value(value.toPlainString()) + ")";
	}

	public String value(String value) {
		return "'" + escape(value) + "'";
	}

	public String term(String value) {
		return value;
	}

	public String escape(String value) {
		return value.replace("'", "\\'");
	}

	public String encodeJCRComponent(String name) {
		return name;
	}

	public String encodeJCR(String path) {
		return path;
	}

	public String encodeXMLComponent(String name) {
		return name;
	}

	public String encodeXML(String path) {
		return path;
	}

	public Builder newBuilder() {
		return new Builder(fContext);
	}

	public static class Builder {
		private final WorkspaceScriptContext fContext;
		private StringBuilder fStatement = new StringBuilder();
		private Map<String, Object> fVariabales = new HashMap<>();

		private Builder(WorkspaceScriptContext context) {
			fContext = context;
		}

		public Builder append(String statement) {
			fStatement.append(statement);
			return this;
		}

		public Builder variable(String name, Object value) {
			fVariabales.put(name, value);
			return this;
		}

		public Builder variables(Map<String, Object> variables) {
			fVariabales.putAll(variables);
			return this;
		}

		public Query build() throws ResourceException {
			XPath xpath = new XPath(fContext);

			Map<String, String> literals = new HashMap<>();
			for (Map.Entry<String, Object> entry : fVariabales.entrySet()) {
				Object value = entry.getValue();
				if (value == null) {
					throw new IllegalArgumentException("Variable value is null: " + entry.getKey());
				}

				String literal;
				if (value instanceof java.util.Date) {
					literal = xpath.value((java.util.Date) value);
				} else if (value instanceof Boolean) {
					literal = xpath.value((Boolean) value);
				} else if (value instanceof Integer) {
					literal = xpath.value((Integer) value);
				} else if (value instanceof Long) {
					literal = xpath.value((Long) value);
				} else if (value instanceof Double) {
					literal = xpath.value((Double) value);
				} else if (value instanceof BigDecimal) {
					literal = xpath.value((BigDecimal) value);
				} else if (value instanceof String) {
					literal = xpath.value((String) value);
				} else {
					throw new IllegalArgumentException("Unsupported variable type: " + value.getClass().getName());
				}
				literals.put(entry.getKey(), literal);
			}

			String source = fStatement.toString();
			StringBuilder buffer = new StringBuilder();
			char quote = 0;
			for (int i = 0; i < source.length(); i++) {
				char c = source.charAt(i);

				if (quote != 0) {
					buffer.append(c);
					if (c == '\\' && (i + 1) < source.length()) {
						buffer.append(source.charAt(++i));
					} else if (c == quote) {
						quote = 0;
					}
					continue;
				}

				if (c == '\'' || c == '"') {
					quote = c;
					buffer.append(c);
					continue;
				}

				if (c == '$') {
					int end = i + 1;
					while (end < source.length() && isVariableNameChar(source.charAt(end))) {
						end++;
					}
					if (end > (i + 1)) {
						String name = source.substring(i + 1, end);
						String literal = literals.get(name);
						buffer.append((literal != null) ? literal : source.substring(i, end));
						i = end - 1;
						continue;
					}
				}

				buffer.append(c);
			}
			String statement = buffer.toString();

			if (statement.trim().isEmpty()) {
				throw new IllegalArgumentException("Statement is empty.");
			}
			if (!statement.startsWith("/")) {
				throw new IllegalArgumentException("Statement must start with '/': " + statement);
			}
			if (!statement.startsWith("/jcr:root/") && !statement.startsWith("//")) {
				statement = "/jcr:root" + statement;
			}
			return xpath.createQuery(statement);
		}

		private static boolean isVariableNameChar(char c) {
			return Character.isLetterOrDigit(c) || (c == '_');
		}
	}

}