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

package org.mintjams.rt.cms.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.stream.Stream;

public class WorkspaceDelegatingClassLoader extends ClassLoader {

	private final String fWorkspaceName;

	public WorkspaceDelegatingClassLoader(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	private ClassLoader getClassLoader() {
		return CmsService.getWorkspaceClassLoaderProvider(fWorkspaceName).getClassLoader();
	}

	@Override
	public Class<?> loadClass(String name) throws ClassNotFoundException {
		return getClassLoader().loadClass(name);
	}

	@Override
	public URL getResource(String name) {
		return getClassLoader().getResource(name);
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		return getClassLoader().getResources(name);
	}

	@Override
	public Stream<URL> resources(String name) {
		return getClassLoader().resources(name);
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		return getClassLoader().getResourceAsStream(name);
	}

	@Override
	public void setDefaultAssertionStatus(boolean enabled) {
		getClassLoader().setDefaultAssertionStatus(enabled);
	}

	@Override
	public void setPackageAssertionStatus(String packageName, boolean enabled) {
		getClassLoader().setPackageAssertionStatus(packageName, enabled);
	}

	@Override
	public void setClassAssertionStatus(String className, boolean enabled) {
		getClassLoader().setClassAssertionStatus(className, enabled);
	}

	@Override
	public void clearAssertionStatus() {
		getClassLoader().clearAssertionStatus();
	}

}
