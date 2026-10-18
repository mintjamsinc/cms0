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

package org.mintjams.rt.cms.internal.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Java serialization of session attribute values that can cross bundles and
 * nodes. Every class in the stream is annotated with the symbolic name of the
 * bundle that defines it, and is resolved through that bundle's class loader
 * when read back, so a value holding classes of several bundles (the IdP's
 * pending user, a credentials object with its principals) is restored even
 * though no single class loader sees them all.
 *
 * <p>Only classes of the JDK and of OSGi bundles are accepted. A class that
 * belongs to neither — a Groovy script's class, which the workspace class
 * loader redefines on every redeployment — makes the value fail to serialize,
 * so the attribute is left out instead of breaking later on another node.
 */
final class SessionAttributeCodec {

	private final BundleContext fBundleContext;
	private final Map<String, Bundle> fBundles = new ConcurrentHashMap<>();

	SessionAttributeCodec(BundleContext bundleContext) {
		fBundleContext = bundleContext;
	}

	byte[] serialize(Object value) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new AnnotatingOutputStream(bytes)) {
			out.writeObject(value);
		}
		return bytes.toByteArray();
	}

	Object deserialize(byte[] data) throws IOException, ClassNotFoundException {
		try (ObjectInputStream in = new ResolvingInputStream(new ByteArrayInputStream(data))) {
			return in.readObject();
		}
	}

	/** The bundle name written for a class, or "" for a JDK class. */
	private static String bundleOf(Class<?> type) throws InvalidClassException {
		while (type.isArray()) {
			type = type.getComponentType();
		}
		if (type.isPrimitive()) {
			return "";
		}
		ClassLoader loader = type.getClassLoader();
		if (loader == null || loader == ClassLoader.getPlatformClassLoader()) {
			return "";
		}
		Bundle bundle = FrameworkUtil.getBundle(type);
		if (bundle == null) {
			throw new InvalidClassException(type.getName(),
					"Only JDK and OSGi bundle classes can be kept in a session (store a string instead)");
		}
		return bundle.getSymbolicName();
	}

	private Bundle findBundle(String symbolicName) {
		Bundle bundle = fBundles.get(symbolicName);
		if (bundle != null && bundle.getState() != Bundle.UNINSTALLED) {
			return bundle;
		}
		for (Bundle b : fBundleContext.getBundles()) {
			if (symbolicName.equals(b.getSymbolicName()) && b.getState() != Bundle.UNINSTALLED) {
				fBundles.put(symbolicName, b);
				return b;
			}
		}
		return null;
	}

	private static final class AnnotatingOutputStream extends ObjectOutputStream {
		AnnotatingOutputStream(OutputStream out) throws IOException {
			super(out);
		}

		@Override
		protected void annotateClass(Class<?> type) throws IOException {
			writeUTF(bundleOf(type));
		}

		@Override
		protected void annotateProxyClass(Class<?> type) throws IOException {
			throw new InvalidClassException(type.getName(), "Proxies cannot be kept in a session");
		}
	}

	private final class ResolvingInputStream extends ObjectInputStream {
		ResolvingInputStream(InputStream in) throws IOException {
			super(in);
		}

		@Override
		protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
			String symbolicName = readUTF();
			if (symbolicName.isEmpty()) {
				return super.resolveClass(desc);
			}
			Bundle bundle = findBundle(symbolicName);
			if (bundle == null) {
				throw new ClassNotFoundException(desc.getName() + " (bundle " + symbolicName + " is not installed)");
			}
			BundleWiring wiring = bundle.adapt(BundleWiring.class);
			if (wiring == null || wiring.getClassLoader() == null) {
				throw new ClassNotFoundException(desc.getName() + " (bundle " + symbolicName + " is not resolved)");
			}
			return Class.forName(desc.getName(), false, wiring.getClassLoader());
		}

		@Override
		protected Class<?> resolveProxyClass(String[] interfaces) throws IOException, ClassNotFoundException {
			throw new InvalidClassException("Proxies cannot be kept in a session");
		}
	}

}
