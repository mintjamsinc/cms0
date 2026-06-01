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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

public class YAML {

	private WorkspaceScriptContext fContext;

	public YAML(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static YAML get(ScriptingContext context) {
		return (YAML) context.getAttribute(YAML.class.getSimpleName());
	}

	public Object parse(Object value) throws IOException {
		if (value == null) {
			return null;
		}

		if (value instanceof String s) {
			return new Load(LoadSettings.builder().build()).loadFromString(s);
		}

		if (value instanceof InputStream in) {
			try (in) {
				return parse(Strings.readAll(in, StandardCharsets.UTF_8));
			}
		}

		if (value instanceof Reader in) {
			try (in) {
				return parse(Strings.readAll(in));
			}
		}

		if (value instanceof Resource resource) {
			try (Reader in = resource.getContentAsReader()) {
				return parse(Strings.readAll(in));
			} catch (ResourceException ex) {
				throw Cause.create(ex).wrap(IllegalArgumentException.class);
			}
		}

		if (value instanceof File file) {
			return parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
		}

		throw new IllegalArgumentException(value.toString());
	}

	public String stringify(Object value) throws IOException {
		if (value == null) {
			return null;
		}

		return new Dump(DumpSettings.builder()
				.setIndent(4)
				.setIndicatorIndent(2)
				.setDefaultFlowStyle(FlowStyle.BLOCK)
				.build()).dumpToString(value);
	}

}
