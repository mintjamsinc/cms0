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

package org.mintjams.rt.cms.internal.script.engine.nativeecma;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.util.Systems;
import org.mintjams.tools.io.IOs;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * JNI bridge to the native V8-based ECMAScript engine.
 *
 * <p>
 * The V8 platform lifecycle ({@code InitializePlatform}/{@code Initialize}/
 * {@code Dispose}/{@code DisposePlatform}) is <em>process-global and
 * irreversible</em>. In particular, once {@code DisposePlatform} has been
 * called, V8's startup state becomes {@code kPlatformDisposed} and any
 * subsequent {@code InitializePlatform} aborts the whole JVM with:
 * </p>
 *
 * <pre>
 * Check failed: current_state != V8StartupState::kPlatformDisposed.
 * </pre>
 *
 * <p>
 * Because the native library is built as a V8 <em>component (shared)</em> build,
 * {@code libv8.so} is loaded once per process (SONAME sharing), so this global
 * state is shared across every workspace. Therefore the native library load and
 * the V8 platform initialization are performed <strong>exactly once per
 * process</strong> here and are <strong>never</strong> torn down on workspace
 * removal.
 * </p>
 *
 * <p>
 * Per-workspace isolation is provided by an {@code IsolatePool} on the native
 * side: each {@code NativeEcma} instance (one per workspace script engine
 * factory) owns its own pool, referenced by an opaque handle. Closing an
 * instance destroys only that pool; the shared platform stays alive.
 * </p>
 */
public class NativeEcma implements Closeable {

	/** Guards the one-time native library load and platform initialization. */
	private static final Object PLATFORM_LOCK = new Object();
	/** Shared directory the native libraries are extracted into (process-wide). */
	private static Path sLibPath;
	/** Whether {@link #nativeInitPlatform(String)} has been completed. */
	private static boolean sPlatformInitialized = false;

	/** Opaque handle to this workspace's native isolate pool (0 = none). */
	private long fPoolHandle = 0;
	/** Guards {@link #fPoolHandle} transitions (create/destroy). */
	private final Object fPoolLock = new Object();

	private BundleContext getBundleContext() {
		return CmsService.getDefault().getBundleContext();
	}

	private Bundle getBundle() {
		return getBundleContext().getBundle();
	}

	/**
	 * Extracts the native libraries (once) and initializes the V8 platform (once)
	 * for the whole process.
	 */
	private void ensurePlatform() throws IOException {
		synchronized (PLATFORM_LOCK) {
			if (sLibPath == null) {
				Path tmp = CmsService.getTemporaryDirectoryPath();
				Path libPath = Files.createTempDirectory(tmp, "native-").toAbsolutePath();

				for (Enumeration<URL> e = getBundle().findEntries("/native/" + Systems.getOSName() + "/" + Systems.getOSArch(), "*", true); e.hasMoreElements();) {
					URL entry = e.nextElement();
					if (entry.getPath().endsWith("/")) {
						continue;
					}

					Path f = libPath.resolve(Path.of(entry.getPath().substring(1)).getFileName());
					if (Files.exists(f)) {
						continue;
					}

					try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(f))) {
						try (InputStream in = new BufferedInputStream(entry.openStream())) {
							IOs.copy(in, out);
						}
					}
				}

				if (Systems.isLinux()) {
					System.load(libPath.resolve("libnativeecma.so").toAbsolutePath().toString());
				} else if (Systems.isWindows()) {
					System.load(libPath.resolve("libnativeecma.dll").toAbsolutePath().toString());
				} else {
					throw new IllegalStateException(System.getProperty("os.name"));
				}

				sLibPath = libPath;
			}

			if (!sPlatformInitialized) {
				nativeInitPlatform(sLibPath.toAbsolutePath().toString());
				sPlatformInitialized = true;
			}
		}
	}

	/**
	 * Creates this workspace's native isolate pool. Initializes the shared V8
	 * platform first if it has not been initialized yet.
	 *
	 * @param poolSize number of isolates (and worker threads); when {@code <= 0}
	 *                 the number of available processors is used.
	 */
	public void load(int poolSize) throws IOException {
		ensurePlatform();

		if (poolSize <= 0) {
			poolSize = Runtime.getRuntime().availableProcessors();
		}

		synchronized (fPoolLock) {
			if (fPoolHandle == 0) {
				fPoolHandle = nativeCreatePool(poolSize);
			}
		}
	}

	/**
	 * Destroys this workspace's isolate pool only. The process-global V8 platform
	 * is intentionally left intact so that other workspaces keep working and a
	 * later workspace can be added without re-initializing (which would abort the
	 * JVM).
	 */
	@Override
	public void close() throws IOException {
		long handle;
		synchronized (fPoolLock) {
			handle = fPoolHandle;
			fPoolHandle = 0;
		}
		if (handle == 0) {
			return;
		}
		try {
			nativeDestroyPool(handle);
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).error(ex.getMessage(), ex);
		}
	}

	public String eval(List<String> sources) throws IOException {
		return eval(sources.toArray(String[]::new));
	}

	public String eval(String... sources) throws IOException {
		long handle;
		synchronized (fPoolLock) {
			handle = fPoolHandle;
		}
		if (handle == 0) {
			throw new IOException("The native ECMA isolate pool is not initialized.");
		}
		return nativeEval(handle, sources);
	}

	/** Initializes the process-global V8 platform exactly once (idempotent). */
	protected static native void nativeInitPlatform(String directoryPath);

	/** Creates a workspace-scoped isolate pool and returns its handle. */
	protected native long nativeCreatePool(int poolSize);

	/** Destroys the isolate pool identified by {@code handle}. */
	protected native void nativeDestroyPool(long handle);

	/** Evaluates the given sources on the isolate pool identified by {@code handle}. */
	protected native String nativeEval(long handle, String[] sources);

}
