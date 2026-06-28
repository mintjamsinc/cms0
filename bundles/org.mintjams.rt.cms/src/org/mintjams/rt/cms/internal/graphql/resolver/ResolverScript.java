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

package org.mintjams.rt.cms.internal.graphql.resolver;

import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.ScriptReader;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.security.ServiceUserCredentials;
import org.mintjams.tools.lang.ClassLoaders;

/**
 * One compiled, reusable resolver (or type-resolver) script. Captured once when
 * the schema is compiled and shared across requests for the schema's lifetime.
 *
 * <h2>Compilation cache</h2>
 * The script source is compiled to a {@link CompiledScript} on first use and
 * reused on every subsequent invocation, instead of recompiling the Groovy
 * source per request. The cache is keyed to the workspace class loader instance
 * the script was compiled against: when that loader is rebuilt (a jar/class
 * under {@code /content/WEB-INF/classes|lib} changed, or the periodic refresh
 * fired), the next invocation transparently recompiles against the new loader,
 * so resolvers always see current deployed classes. A non-{@link Compilable}
 * engine (e.g. a {@code .gsp} resolver) falls back to per-call evaluation.
 *
 * <h2>Execution identity</h2>
 * By default the script runs in the per-request {@link WorkspaceScriptContext}
 * (the caller's JCR session). When {@code runAs} is set it runs in its own
 * short-lived context bound to {@link ServiceUserCredentials} for that
 * principal — the impersonation mechanism used by the BPM ({@code CmsDelegate})
 * and EIP ({@code CmsComponent}) script bridges.
 *
 * <p>The cached {@link CompiledScript} is evaluated with a per-call context, so
 * concurrent requests are safe: each evaluation runs a fresh script instance
 * bound to its own context.
 */
public final class ResolverScript {

	private final String fWorkspaceName;
	private final String fScriptPath;
	private final String fExtension;
	private final String fSource;
	private final String fRunAs;

	private final Object fCompileLock = new Object();
	private volatile Compiled fCompiled;

	public ResolverScript(String workspaceName, String scriptPath, String extension, String source, String runAs) {
		fWorkspaceName = workspaceName;
		fScriptPath = scriptPath;
		fExtension = extension;
		fSource = source;
		fRunAs = runAs;
	}

	/**
	 * Evaluates the script with {@code bindings} applied as attributes and
	 * returns its value (the script's last expression).
	 *
	 * @param sharedContext the per-request context, used when {@code runAs} is
	 *                       blank; ignored (a private context is created and
	 *                       closed) when {@code runAs} is set.
	 */
	public Object eval(WorkspaceScriptContext sharedContext, Map<String, Object> bindings)
			throws ScriptException, IOException {
		boolean impersonate = (fRunAs != null && !fRunAs.trim().isEmpty());
		WorkspaceScriptContext context = impersonate ? new WorkspaceScriptContext(fWorkspaceName) : sharedContext;
		if (context == null) {
			throw new IllegalStateException("No script context is available for resolver: " + fScriptPath);
		}

		try {
			if (impersonate) {
				context.setCredentials(new ServiceUserCredentials(fRunAs.trim()));
				Scripts.prepareAPIs(context);
			}

			for (Map.Entry<String, Object> binding : bindings.entrySet()) {
				context.setAttribute(binding.getKey(), binding.getValue());
			}

			ClassLoader classLoader = CmsService.getWorkspaceClassLoaderProvider(fWorkspaceName).getClassLoader();
			Compiled compiled = compiled(classLoader);
			if (compiled.script != null) {
				try (Closeable c = ClassLoaders.withClassLoader(classLoader)) {
					return compiled.script.eval(context);
				}
			}
			// Engine is not Compilable: evaluate the source directly (uncached).
			return evalUncached(context, classLoader);
		} finally {
			if (impersonate) {
				try {
					context.close();
				} catch (Throwable ignore) {}
			}
		}
	}

	/**
	 * Returns the compiled script for {@code classLoader}, compiling (or
	 * recompiling, when the loader changed) under lock. Published through a
	 * single volatile holder so readers never observe a half-updated cache.
	 */
	private Compiled compiled(ClassLoader classLoader) throws ScriptException {
		Compiled current = fCompiled;
		if (current != null && current.classLoader == classLoader) {
			return current;
		}

		synchronized (fCompileLock) {
			current = fCompiled;
			if (current != null && current.classLoader == classLoader) {
				return current;
			}

			ScriptEngine engine = CmsService.getWorkspaceScriptEngineManager(fWorkspaceName).getEngineByExtension(fExtension);
			if (engine == null) {
				throw new ScriptException("No script engine for extension '" + fExtension + "': " + fScriptPath);
			}

			Compiled compiled;
			if (engine instanceof Compilable) {
				try (Closeable c = ClassLoaders.withClassLoader(classLoader)) {
					compiled = new Compiled(((Compilable) engine).compile(fSource), classLoader);
				} catch (IOException ex) {
					throw new ScriptException(ex);
				}
			} else {
				compiled = new Compiled(null, classLoader);
			}
			fCompiled = compiled;
			return compiled;
		}
	}

	private Object evalUncached(WorkspaceScriptContext context, ClassLoader classLoader)
			throws ScriptException, IOException {
		try (ScriptReader reader = new ScriptReader(new StringReader(fSource))) {
			reader
				.setScriptName("jcr://" + fScriptPath)
				.setExtension(fExtension)
				.setScriptContext(context)
				.setScriptEngineManager(CmsService.getWorkspaceScriptEngineManager(fWorkspaceName))
				.setClassLoader(classLoader);
			return reader.eval();
		}
	}

	/** Immutable cache entry: a compiled script (or {@code null}) bound to a class loader. */
	private static final class Compiled {
		private final CompiledScript script;
		private final ClassLoader classLoader;

		private Compiled(CompiledScript script, ClassLoader classLoader) {
			this.script = script;
			this.classLoader = classLoader;
		}
	}

}
