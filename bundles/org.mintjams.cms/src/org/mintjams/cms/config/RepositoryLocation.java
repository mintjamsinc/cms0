/*
 * Copyright (c) 2026 MintJams Inc.
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

package org.mintjams.cms.config;

import java.nio.file.Path;

/**
 * Where the repository directory is, for the classes of this bundle that
 * read files under {@code <repository>/etc} before, or without, an OSGi
 * context: the secret key ({@code etc/secret.key}) and the secrets file
 * ({@code etc/secrets.yml}).
 *
 * <p>The repository runtime knows the directory from the framework property
 * {@code org.mintjams.jcr.repository.rootdir} and hands it over with
 * {@link #set(Path)} when it starts, before it resolves its first
 * configuration value. Until then, and in a plain JVM (the {@code cms-encrypt}
 * tool), the same name is read as a system property, and {@code repository}
 * under the working directory is the default — the runtime's default too.
 */
public final class RepositoryLocation {

	/** The framework and system property naming the repository directory. */
	public static final String SYSTEM_PROPERTY = "org.mintjams.jcr.repository.rootdir";

	private static final String DEFAULT_DIRECTORY = "repository";

	private static volatile Path fPath;

	private RepositoryLocation() {}

	/** Sets the repository directory; called by the repository runtime at start. */
	public static void set(Path path) {
		fPath = (path == null) ? null : path.normalize();
	}

	/** The repository directory. */
	public static Path get() {
		Path path = fPath;
		if (path != null) {
			return path;
		}
		String value = System.getProperty(SYSTEM_PROPERTY);
		return Path.of((value == null || value.isBlank()) ? DEFAULT_DIRECTORY : value.trim()).normalize();
	}

	/** {@code <repository>/etc}: the shared configuration and identity files. */
	public static Path getEtcPath() {
		return get().resolve("etc");
	}

}
