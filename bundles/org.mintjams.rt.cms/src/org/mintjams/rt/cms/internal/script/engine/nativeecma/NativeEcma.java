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

public class NativeEcma implements Closeable {

	private BundleContext getBundleContext() {
		return CmsService.getDefault().getBundleContext();
	}

	private Bundle getBundle() {
		return getBundleContext().getBundle();
	}

	private Path fLibPath;
	public Path getLibPath() throws IOException {
		if (fLibPath == null) {
			Path tmp = CmsService.getRepositoryPath().resolve("tmp");
			fLibPath = Files.createTempDirectory(tmp, "native-").toAbsolutePath();
		}
		return fLibPath;
	}

	public void load() throws IOException {
		for (Enumeration<URL> e = getBundle().findEntries("/native/" + Systems.getOSName() + "/" + Systems.getOSArch(), "*", true); e.hasMoreElements();) {
			URL entry = e.nextElement();
			if (entry.getPath().endsWith("/")) {
				continue;
			}

			Path f = getLibPath().resolve(Path.of(entry.getPath().substring(1)).getFileName());
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
			System.load(getLibPath().resolve("libnativeecma.so").toAbsolutePath().toString());
		} else if (Systems.isWindows()) {
			System.load(getLibPath().resolve("libnativeecma.dll").toAbsolutePath().toString());
		} else {
			throw new IllegalStateException(System.getProperty("os.name"));
		}

		nativeLoad(getLibPath().toAbsolutePath().toString(), 2); // FIXME
	}

	@Override
	public void close() throws IOException {
		try {
			nativeUnload();
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).error(ex.getMessage(), ex);
		}
	}

	public String eval(List<String> sources) throws IOException {
		return nativeEval(sources.toArray(String[]::new));
	}

	public String eval(String... sources) throws IOException {
		return nativeEval(sources);
	}

	protected native void nativeLoad(String directoryPath, int poolSize);

	protected native void nativeUnload();

	protected native String nativeEval(String[] sources);

}
