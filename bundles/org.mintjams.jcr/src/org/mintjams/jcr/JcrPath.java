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

package org.mintjams.jcr;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class JcrPath implements Serializable {

	private static final long serialVersionUID = 1L;

	private final JcrName[] fNames;
	private NamespaceProvider fNamespaceProvider;

	private JcrPath(String path) {
		if (Strings.isBlank(path)) {
			throw new IllegalArgumentException("Invalid path: " + path);
		}

		List<JcrName> l = new ArrayList<>();
		for (String name : JCRs.normalizeAndSplitPath(path)) {
			l.add(JcrName.valueOf(name));
		}
		fNames = l.toArray(JcrName[]::new);
	}

	public boolean isAbsolute() {
		return (fNames.length == 0 || Strings.isEmpty(fNames[0].getLocalPart()));
	}

	public boolean isRoot() {
		return toString().equals("/");
	}

	public boolean isParentOf(JcrPath other) {
		if (other == null) {
			throw new IllegalArgumentException("other is null.");
		}

		JcrPath parent = other.getParent();
		return parent != null && this.equals(parent);
	}

	public boolean isAncestorOf(JcrPath other) {
		if (other == null) {
			throw new IllegalArgumentException("other is null.");
		}

		if (this.fNames.length >= other.fNames.length) {
			return false;
		}

		for (int i = 0; i < this.fNames.length; i++) {
			if (!this.fNames[i].equals(other.fNames[i])) {
				return false;
			}
		}

		return true;
	}

	public boolean isDescendantOf(JcrPath other) {
		if (other == null) {
			throw new IllegalArgumentException("other is null.");
		}

		return other.isAncestorOf(this);
	}

	public JcrPath resolve(String relPath) {
		if (relPath.startsWith("/")) {
			return valueOf(relPath);
		}

		String path = toString();
		if (!path.endsWith("/")) {
			path += "/";
		}
		return valueOf(path + relPath).with(fNamespaceProvider);
	}

	public JcrPath getParent() {
		String path = toString();
		if (Strings.isEmpty(path) || path.equals("/") || path.indexOf("/") == -1) {
			return null;
		}

		String absPath = path.substring(0, path.lastIndexOf("/"));
		if (Strings.isEmpty(absPath)) {
			absPath = "/";
		}
		JcrPath parentPath = new JcrPath(absPath);
		if (fNamespaceProvider != null) {
			try {
				parentPath.with(fNamespaceProvider);
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
		return parentPath;
	}

	public JcrName getName() {
		if (fNames.length == 0) {
			return JcrName.valueOf("/").with(fNamespaceProvider);
		}

		return fNames[fNames.length - 1];
	}

	public JcrPath with(NamespaceProvider namespaceProvider) {
		fNamespaceProvider = namespaceProvider;

		for (JcrName name : fNames) {
			name.with(namespaceProvider);
		}

		return this;
	}

	@Override
	public int hashCode() {
		return (JcrPath.class.getSimpleName() + "|" + toString()).hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		return (hashCode() == obj.hashCode());
	}

	@Override
	public String toString() {
		if (fNames.length == 0 || (fNames.length == 1 && Strings.isEmpty(fNames[0].getLocalPart()))) {
			return "/";
		}

		List<String> l = new ArrayList<>();
		for (JcrName name : fNames) {
			l.add(name.toString());
		}
		return String.join("/", l);
	}

	public static JcrPath valueOf(String path) {
		return new JcrPath(path);
	}

}
