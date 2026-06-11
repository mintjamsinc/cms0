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

package org.mintjams.rt.jcr.internal.blob;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.mintjams.tools.io.IOs;

/**
 * Stores blobs as files under a root directory, fanned out over a four-level
 * directory hierarchy derived from the identifier
 * ({@code XX/XX/XX/XX/&lt;id&gt;}). The root defaults to the workspace's
 * {@code var/jcr/bin} directory and may be redirected to shared storage for
 * clustered deployments.
 */
public class FileSystemBlobStore implements BlobStore {

	public static final String TYPE = "fs";

	/**
	 * Blobs younger than this are never garbage collected: a blob is written
	 * before the {@code jcr_files} row that references it is committed, so a
	 * young unreferenced file may simply belong to an open transaction.
	 */
	private static final long GC_MINIMUM_AGE_MILLIS = 86400000L;

	private final Path fRootPath;
	private final Consumer<Throwable> fErrorHandler;

	private FileSystemBlobStore(Path rootPath, Consumer<Throwable> errorHandler) {
		fRootPath = rootPath;
		fErrorHandler = errorHandler;
	}

	public static FileSystemBlobStore create(Path rootPath, Consumer<Throwable> errorHandler) {
		return new FileSystemBlobStore(rootPath, errorHandler);
	}

	@Override
	public String getType() {
		return TYPE;
	}

	@Override
	public long write(String id, InputStream in) throws IOException {
		Path path = getPath(id);
		Files.createDirectories(path.getParent());
		try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
			IOs.copy(in, out);
		}
		return Files.size(path);
	}

	@Override
	public InputStream read(String id) throws IOException {
		return Files.newInputStream(getPath(id));
	}

	@Override
	public Path getPath(String id) {
		return fRootPath.resolve(id.substring(0, 2)).resolve(id.substring(2, 4)).resolve(id.substring(4, 6))
				.resolve(id.substring(6, 8)).resolve(id).toAbsolutePath();
	}

	@Override
	public boolean exists(String id) {
		return Files.exists(getPath(id));
	}

	@Override
	public void delete(String id) throws IOException {
		Files.deleteIfExists(getPath(id));
	}

	@Override
	public void collectGarbage(GarbageCollectionContext context) throws IOException {
		if (!Files.exists(fRootPath)) {
			return;
		}

		collectGarbage(fRootPath, context);
	}

	private void collectGarbage(Path parentPath, GarbageCollectionContext context) throws IOException {
		if (context.isCancelled()) {
			return;
		}

		try (Stream<Path> stream = Files.list(parentPath)) {
			stream.forEach(path -> {
				if (context.isCancelled()) {
					return;
				}

				try {
					if (Files.isDirectory(path)) {
						collectGarbage(path, context);
						return;
					}

					if (Files.getLastModifiedTime(path).toMillis() >= (System.currentTimeMillis() - GC_MINIMUM_AGE_MILLIS)) {
						return;
					}

					if (context.isReferenced(path.getFileName().toString())) {
						return;
					}

					Files.deleteIfExists(path);
				} catch (Throwable ex) {
					fErrorHandler.accept(ex);
				}
			});
		}
	}

	@Override
	public void close() throws IOException {}

}
