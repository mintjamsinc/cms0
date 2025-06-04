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

package org.mintjams.jcr.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.lang.ref.Cleaner;

import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Strings;

public class FileCache implements Cache, Adaptable {

	private static final Cleaner CLEANER = Cleaner.create();

	private static class State implements Runnable {
		private final Path path;
		State(Path path) { this.path = path; }
		@Override
		public void run() {
			try {
				Files.deleteIfExists(path);
			} catch (IOException ignore) {}
		}
	}

	private final Path fPath;
	private final Closer fCloser = Closer.create();
	private final Cleaner.Cleanable fCleanable;

	private FileCache(Path path) {
		fPath = path;
		fCleanable = CLEANER.register(this, new State(path));
	}

	public static FileCache create(InputStream in, Path tempDir) throws IOException {
		try (in) {
			Path path = Files.createTempFile(tempDir, "cache-", null);

			try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
				IOs.copy(in, out);
			}

			return new FileCache(path);
		}
	}

	public static FileCache create(byte[] value, Path tempDir) throws IOException {
		return create(new ByteArrayInputStream(value), tempDir);
	}

	@Override
	public void close() throws IOException {
		fCloser.close();
		fCleanable.clean();
	}

	public static Builder newBuilder(Path tempDir) throws IOException {
		return new Builder(tempDir);
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return getInputStream(false);
	}

	public InputStream getInputStream(boolean deleteOnClose) throws IOException {
		return fCloser.register(new BufferedInputStream(Files.newInputStream(fPath)) {
			@Override
			public void close() throws IOException {
				super.close();
				fCloser.remove(this);
				if (deleteOnClose) {
					FileCache.this.close();
				}
			}
		});
	}

	@Override
	public long getSize() throws IOException {
		return Files.size(fPath);
	}

	@Override
	public String toString(String encoding) throws IOException {
		return Strings.readAll(getInputStream(), encoding);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(Path.class)) {
			return (AdapterType) fPath;
		}

		return null;
	}

        public static class Builder implements AutoCloseable {
                private Path fTempDir;

		private Builder(Path tempDir) {
			fTempDir = tempDir;
		}

                private static class BuilderState implements Runnable {
                        private final Path path;
                        private volatile boolean built;
                        BuilderState(Path path) { this.path = path; }
                        void built() { built = true; }
                        @Override
                        public void run() {
                                if (!built) {
                                        try {
                                                Files.deleteIfExists(path);
                                        } catch (IOException ignore) {}
                                }
                        }
                }

                private Path _path;
                private Cleaner.Cleanable _cleanable;
                private BuilderState _state;

                private Path getPath() throws IOException {
                        if (_path == null) {
                                _path = Files.createTempFile(fTempDir, "cache-", null);
                                _state = new BuilderState(_path);
                                _cleanable = CLEANER.register(this, _state);
                        }
                        return _path;
                }

                private OutputStream getAppendStream() throws IOException {
                        return Files.newOutputStream(getPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }

		public Builder write(String value) throws IOException {
			return write(value.getBytes(StandardCharsets.UTF_8));
		}

		public Builder write(byte[] value) throws IOException {
			return write(new ByteArrayInputStream(value));
		}

		public Builder write(InputStream value) throws IOException {
			try (OutputStream out = getAppendStream()) {
				try (value) {
					IOs.copy(value, out);
				}
			}
			return this;
		}

                public FileCache build() throws IOException {
                        Path path = getPath();
                        if (_cleanable != null) {
                                _state.built();
                                _cleanable.clean();
                                _cleanable = null;
                        }
                        return new FileCache(path);
                }

                @Override
                public void close() throws IOException {
                        if (_cleanable != null) {
                                _cleanable.clean();
                                _cleanable = null;
                        }
                        _state = null;
                        _path = null;
                }
        }

}
