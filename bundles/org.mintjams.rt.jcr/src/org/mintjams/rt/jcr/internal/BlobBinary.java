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

package org.mintjams.rt.jcr.internal;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.jcr.RepositoryException;

import org.mintjams.rt.jcr.internal.blob.BlobStore;

/**
 * A binary value that reads a stored blob in place. Unlike {@link JcrBinary},
 * which owns a temporary copy of its content, this binary holds nothing but
 * the blob's id: every {@link #getStream()} opens the blob store directly, so
 * a large file is served without first being copied in full, and the stream
 * can be opened as often as needed. The blob belongs to the workspace, so
 * {@link #dispose()} has nothing to release.
 */
public class BlobBinary implements org.mintjams.jcr.Binary {

	private final BlobStore fStore;
	private final String fId;
	private final long fSize;

	private BlobBinary(BlobStore store, String id, long size) {
		fStore = store;
		fId = id;
		fSize = size;
	}

	public static BlobBinary create(BlobStore store, String id, long size) {
		return new BlobBinary(store, id, size);
	}

	/**
	 * The id of the blob this binary reads.
	 */
	public String getId() {
		return fId;
	}

	@Override
	public InputStream getStream() throws RepositoryException {
		try {
			return new BufferedInputStream(fStore.read(fId));
		} catch (IOException ex) {
			throw (RepositoryException) new RepositoryException(ex.getMessage()).initCause(ex);
		}
	}

	@Override
	public int read(byte[] b, long position) throws IOException, RepositoryException {
		if (position < 0) {
			throw new IllegalArgumentException("Invalid position: " + position);
		}
		if (position >= fSize) {
			return -1;
		}

		try (InputStream in = fStore.read(fId)) {
			// skip() may stop short of the request; keep going until the
			// position is reached or the stream ends.
			for (long remaining = position; remaining > 0;) {
				long skipped = in.skip(remaining);
				if (skipped > 0) {
					remaining -= skipped;
					continue;
				}
				if (in.read() == -1) {
					return -1;
				}
				remaining--;
			}
			return in.read(b, 0, b.length);
		}
	}

	@Override
	public long getSize() {
		return fSize;
	}

	@Override
	public void dispose() {
		// The blob is owned by the workspace; nothing is held here.
	}

	@Override
	public void close() {
		dispose();
	}

}
