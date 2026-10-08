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

package org.mintjams.rt.cms.internal.pkg;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.input.CloseShieldInputStream;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Session;
import org.mintjams.tools.lang.Cause;

/**
 * A package file stored in the repository, read as a stream.
 *
 * <p>A package is a ZIP with this layout:</p>
 * <pre>
 * package.yml              the manifest ({@link PackageManifest})
 * deploy/...               files mirrored into the workspace at their path
 * provisioning/*.yml       descriptors applied at installation
 * </pre>
 *
 * <p>The ZIP is never unpacked to the file system: every pass reads the
 * repository binary from the start and visits the entries in order, so the
 * size of a package is bounded by the repository, not by the temporary
 * directory of the node.</p>
 */
public final class PackageSource {

	public static final String MANIFEST_ENTRY = "package.yml";
	public static final String DEPLOY_PREFIX = "deploy/";
	public static final String PROVISIONING_PREFIX = "provisioning/";

	/** Texts (the manifest, descriptors) longer than this are refused. */
	private static final long TEXT_ENTRY_LIMIT = 1024L * 1024L;

	private final Session fSession;
	private final String fPath;

	public PackageSource(Session session, String path) {
		fSession = session;
		fPath = path;
	}

	public String getPath() {
		return fPath;
	}

	/** The file name of the package in the repository. */
	public String getFileName() throws ResourceException {
		return fSession.getResource(fPath).getName();
	}

	public boolean exists() throws ResourceException {
		Resource resource = fSession.getResource(fPath);
		return resource.exists() && !resource.isCollection();
	}

	/** Visits every entry of the ZIP in order. */
	public interface EntryVisitor {
		/**
		 * @param entry the entry
		 * @param in    the entry's bytes; closing it is harmless and does not
		 *              close the package
		 */
		void visit(ZipEntry entry, InputStream in) throws Exception;
	}

	/**
	 * Reads the package from the start and hands every entry to the visitor.
	 */
	public void walk(EntryVisitor visitor) throws IOException {
		Resource resource;
		try {
			resource = fSession.getResource(fPath);
			if (!resource.exists() || resource.isCollection()) {
				throw new IOException("The package does not exist: " + fPath);
			}
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}

		try (InputStream raw = resource.getContentAsStream();
				ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				try {
					visitor.visit(entry, CloseShieldInputStream.wrap(zip));
				} catch (IOException | RuntimeException ex) {
					throw ex;
				} catch (Exception ex) {
					throw Cause.create(ex).wrap(IOException.class);
				} finally {
					zip.closeEntry();
				}
			}
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	/**
	 * Reads the package once and returns its entries, manifest and descriptors.
	 * Sizes are counted from the data, because a streamed ZIP does not always
	 * carry them in the entry header.
	 */
	public PackageContents read() throws IOException {
		PackageContents contents = new PackageContents();
		walk((entry, in) -> {
			String name = entry.getName();
			if (entry.isDirectory()) {
				contents.addEntry(name, 0L, true);
				return;
			}
			if (name.equals(MANIFEST_ENTRY)) {
				String text = readText(in, name);
				contents.setManifestText(text);
				contents.addEntry(name, text.getBytes(StandardCharsets.UTF_8).length, false);
				return;
			}
			if (name.startsWith(PROVISIONING_PREFIX) && isYaml(name)) {
				String text = readText(in, name);
				contents.addProvisioning(name, text);
				contents.addEntry(name, text.getBytes(StandardCharsets.UTF_8).length, false);
				return;
			}
			contents.addEntry(name, drain(in), false);
		});
		return contents;
	}

	static boolean isYaml(String name) {
		String lower = name.toLowerCase();
		return lower.endsWith(".yml") || lower.endsWith(".yaml");
	}

	private static String readText(InputStream in, String name) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		long total = 0;
		int n;
		while ((n = in.read(buffer)) >= 0) {
			total += n;
			if (total > TEXT_ENTRY_LIMIT) {
				throw new IOException("The entry is too large to be a descriptor: " + name);
			}
			out.write(buffer, 0, n);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static long drain(InputStream in) throws IOException {
		byte[] buffer = new byte[8192];
		long total = 0;
		int n;
		while ((n = in.read(buffer)) >= 0) {
			total += n;
		}
		return total;
	}

}
