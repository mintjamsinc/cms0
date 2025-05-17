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
		return "xs:decimal(" + value("" + value) + ")";
	}

	public String value(long value) {
		return "xs:decimal(" + value("" + value) + ")";
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
		return value.replaceAll("'", "\\'");
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

}
