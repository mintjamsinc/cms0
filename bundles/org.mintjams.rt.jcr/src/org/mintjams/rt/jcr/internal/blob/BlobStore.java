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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Stores the binary values of a JCR workspace, keyed by the blob identifier
 * recorded in the {@code jcr_files} table. The store holds the bytes only;
 * which blobs exist and which are deleted is tracked in the database.
 *
 * <p>Implementations must be safe for concurrent use by multiple sessions,
 * and — because a clustered deployment points every node at the same store —
 * for concurrent use by multiple repository nodes. Identifiers are random
 * UUIDs and are never reused, so concurrent writers never contend for the
 * same blob.
 */
public interface BlobStore extends Closeable {

	/**
	 * Returns the symbolic type of this store (e.g. {@code "fs"}).
	 */
	String getType();

	/**
	 * Stores the contents of the stream under the given identifier and
	 * returns the number of bytes written. The stream is consumed but not
	 * closed.
	 */
	long write(String id, InputStream in) throws IOException;

	/**
	 * Opens the blob for reading.
	 */
	InputStream read(String id) throws IOException;

	/**
	 * Returns a readable filesystem path for the blob. Stores that do not
	 * keep blobs on a filesystem satisfy this with a locally cached copy;
	 * callers must treat the path as read-only and must not rely on it for
	 * the lifetime of the blob.
	 */
	Path getPath(String id) throws IOException;

	/**
	 * Returns whether the blob exists in this store.
	 */
	boolean exists(String id);

	/**
	 * Deletes the blob if it exists.
	 */
	void delete(String id) throws IOException;

	/**
	 * Removes blobs that are no longer referenced by the workspace. The
	 * context decides whether an identifier is still referenced;
	 * implementations decide how stored blobs are enumerated and must leave
	 * recently written blobs alone, since a blob is written before the row
	 * that references it is committed.
	 */
	void collectGarbage(GarbageCollectionContext context) throws IOException;

	interface GarbageCollectionContext {

		boolean isCancelled();

		boolean isReferenced(String id) throws IOException;
	}

}
