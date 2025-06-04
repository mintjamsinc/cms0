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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

public abstract class AbstractScriptEngineFactory implements ScriptEngineFactory {

       private String fEngineName;
       private String fEngineVersion;
       private List<String> fExtensions;
       private List<String> fMimeTypes;
       private List<String> fNames;

	protected AbstractScriptEngineFactory() {
		setEngineName(getEngineName());
		setEngineVersion(getEngineVersion());

		setExtensions((String[]) null);
		setMimeTypes((String[]) null);
		setNames((String[]) null);
	}

       public String getEngineName() {
               return fEngineName;
       }

       protected void setEngineName(String engineName) {
               fEngineName = engineName;
       }

	@Override
	public String getEngineVersion() {
               return fEngineVersion;
       }

       protected void setEngineVersion(String engineVersion) {
               fEngineVersion = engineVersion;
       }

	@Override
       public List<String> getExtensions() {
               return fExtensions;
       }

       protected void setExtensions(String... extensions) {
               if (extensions == null) {
                       fExtensions = Collections.emptyList();
               } else {
                       fExtensions = Arrays.asList(extensions);
               }
       }

	@Override
       public List<String> getMimeTypes() {
               return fMimeTypes;
       }

       protected void setMimeTypes(String... mimeTypes) {
               if (mimeTypes == null) {
                       fMimeTypes = Collections.emptyList();
               } else {
                       fMimeTypes = Arrays.asList(mimeTypes);
               }
       }

	@Override
       public List<String> getNames() {
               return fNames;
       }

       protected void setNames(String... names) {
               if (names == null) {
                       fNames = Collections.emptyList();
               } else {
                       fNames = Arrays.asList(names);
               }
       }

	@Override
	public String getMethodCallSyntax(String obj, String methodName, String... args) {
		StringBuilder syntax = new StringBuilder();
		syntax.append(obj).append('.').append(methodName).append('(');
		for (int i = 0; args != null && i < args.length; i++) {
			if (i > 0) {
				syntax.append(',');
			}
			syntax.append(args[i]);
		}
		syntax.append(')');
		return syntax.toString();
	}

	@Override
	public String getOutputStatement(String value) {
		return "out.print(" + value + ")";
	}

	@Override
	public Object getParameter(String name) {
		if (ScriptEngine.ENGINE.equals(name)) {
			return getEngineName();
		}
		if (ScriptEngine.ENGINE_VERSION.equals(name)) {
			return getEngineVersion();
		}
		if (ScriptEngine.NAME.equals(name)) {
			return getNames();
		}
		if (ScriptEngine.LANGUAGE.equals(name)) {
			return getLanguageName();
		}
		if (ScriptEngine.LANGUAGE_VERSION.equals(name)) {
			return getLanguageVersion();
		}
		return null;
	}

	@Override
	public String getProgram(String... statements) {
		return null;
	}

}
