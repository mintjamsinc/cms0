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

package org.mintjams.script;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.tools.lang.Cause;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JSON {

	private WorkspaceScriptContext fContext;

	public JSON(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static JSON get(ScriptingContext context) {
		return (JSON) context.getAttribute(JSON.class.getSimpleName());
	}

	public Object parse(Object value) throws IOException {
		if (value == null) {
			return null;
		}

		if (value instanceof String) {
			return new ObjectMapper().readValue((String) value, new TypeReference<>() {});
		}

		if (value instanceof InputStream) {
			try (InputStream in = (InputStream) value) {
				return new ObjectMapper().readValue(in, new TypeReference<>() {});
			}
		}

		if (value instanceof Reader) {
			try (Reader in = (Reader) value) {
				return new ObjectMapper().readValue(in, new TypeReference<>() {});
			}
		}

		if (value instanceof Resource) {
			try (Reader in = ((Resource) value).getContentAsReader()) {
				return new ObjectMapper().readValue(in, new TypeReference<>() {});
			} catch (ResourceException ex) {
				throw Cause.create(ex).wrap(IllegalArgumentException.class);
			}
		}

		throw new IllegalArgumentException(value.toString());
	}

	public String stringify(Object value) throws IOException {
		if (value == null) {
			return null;
		}

		return new ObjectMapper().writeValueAsString(value);
	}

}
