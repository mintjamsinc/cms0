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

package org.mintjams.rt.cms.internal.script.engine;

import java.io.Reader;

import javax.script.CompiledScript;

import org.mintjams.rt.cms.internal.script.ScriptReader;

public abstract class ResourceScript extends CompiledScript {

	public static final String NO_SCRIPT_NAME = "NO_SCRIPT_NAME";

	public abstract String getScriptName();

	public abstract long getLastModified();

	public static String getScriptName(Reader reader) {
		if (reader instanceof ScriptReader) {
			return ((ScriptReader) reader).getScriptName();
		}
		return ResourceScript.NO_SCRIPT_NAME;
	}

	public static long getLastModified(Reader reader) {
		if (reader instanceof ScriptReader) {
			return ((ScriptReader) reader).getLastModified().getTime();
		}
		return 0;
	}

}
