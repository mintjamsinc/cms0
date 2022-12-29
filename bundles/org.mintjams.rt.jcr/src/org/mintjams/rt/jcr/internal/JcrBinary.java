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

import java.io.IOException;
import java.io.InputStream;

import javax.jcr.RepositoryException;

import org.mintjams.jcr.util.FileCache;
import org.mintjams.tools.lang.Cause;

public class JcrBinary implements org.mintjams.jcr.Binary {

	private final FileCache fCache;

	private JcrBinary(FileCache cache) {
		fCache = cache;
	}

	public static JcrBinary create(InputStream value) throws IOException {
		return new JcrBinary(FileCache.create(value, Activator.getDefault().getTemporaryDirectoryPath()));
	}

	public static JcrBinary create(byte[] value) throws IOException {
		return new JcrBinary(FileCache.create(value, Activator.getDefault().getTemporaryDirectoryPath()));
	}

	@Override
	public void dispose() {
		try {
			fCache.close();
		} catch (IOException ignore) {}
	}

	@Override
	public long getSize() throws RepositoryException {
		try {
			return fCache.getSize();
		} catch (IOException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public InputStream getStream() throws RepositoryException {
		try {
			return fCache.getInputStream(true);
		} catch (IOException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public int read(byte[] b, long position) throws IOException, RepositoryException {
		if (position < 0) {
			throw new IllegalArgumentException("Invalid position: " + position);
		}

		try (InputStream in = fCache.getInputStream()) {
			in.skip(position);
			return in.read(b, 0, b.length);
		}
	}

	@Override
	public void close() throws IOException {
		dispose();
	}

}
