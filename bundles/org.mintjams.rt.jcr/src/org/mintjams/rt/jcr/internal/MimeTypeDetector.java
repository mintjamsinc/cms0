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

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileTypeDetector;

import org.mintjams.searchindex.SearchIndex;
import org.mintjams.searchindex.query.QueryStatements;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Strings;

public class MimeTypeDetector extends FileTypeDetector implements Closeable, Adaptable {

	private final JcrRepository fRepository;
	private final Closer fCloser = Closer.create();
	private SearchIndex fSearchIndex;

	private MimeTypeDetector(JcrRepository repository) {
		fRepository = repository;
	}

	public static MimeTypeDetector create(JcrRepository repository) {
		return new MimeTypeDetector(repository);
	}

	@Override
	public synchronized String probeContentType(Path path) throws IOException {
		String mimeType = null;
		String ext = path.getFileName().toString();
		for (int i = ext.indexOf("."); i != -1; i = ext.indexOf(".")) {
			ext = ext.substring(i + 1);
			try {
				mimeType = fSearchIndex.createQuery("[@extension = '" + QueryStatements.escape(ext) + "']", "native")
						.setOffset(0).setLimit(1).execute().iterator().next().getIdentifier();
				if (!Strings.isEmpty(mimeType)) {
					return mimeType;
				}
			} catch (Throwable ignore) {}
		}

		if (Strings.isEmpty(mimeType)) {
			mimeType = Files.probeContentType(path);
			if (!Strings.isEmpty(mimeType)) {
				return mimeType;
			}
		}

		return JcrProperty.DEFAULT_MIMETYPE;
	}

	public synchronized MimeTypeDetector open() throws IOException {
		if (fSearchIndex != null) {
			return this;
		}

		IOs.deleteIfExists(getIndexPath());

		fSearchIndex = fCloser.register(Activator.getDefault().getSearchIndexFactory().createSearchIndexConfiguration()
				.setDataPath(getIndexPath()).createSearchIndex());

		Path mimeTypesPath = getMimeTypesPath();
		if (!Files.exists(mimeTypesPath)) {
			Files.createFile(mimeTypesPath);
		}

		try (BufferedReader in = Files.newBufferedReader(mimeTypesPath, StandardCharsets.UTF_8)) {
			for (;;) {
				String line = in.readLine();
				if (line == null) {
					break;
				}

				line = line.trim();
				if (Strings.isEmpty(line)) {
					continue;
				}

				SearchIndex.DocumentWriter indexWriter = fSearchIndex.getDocumentWriter();
				try {
					String[] s = line.split("\\s+");
					if (s.length < 2) {
						continue;
					}

					indexWriter.delete(s[0]);
					indexWriter.update(document -> {
						document.setIdentifier(s[0]);
						document.setMimeType(s[0]);
						for (int i = 1; i < s.length; i++) {
							document.addProperty("extension", s[i]);
						}
						return document;
					});
					indexWriter.commit();
				} catch (Throwable ex) {
					try {
						indexWriter.rollback();
					} catch (Throwable ignore) {}
					Activator.getDefault().getLogger(getClass()).warn("An error occurred while creating the index.", ex);
				}
			}
		}

		return this;
	}

	public synchronized MimeTypeDetector reopen() throws IOException {
		close();
		return open();
	}

       private Path fIndexPath;
       public Path getIndexPath() throws IOException {
               if (fIndexPath == null) {
                       fIndexPath = Files.createTempDirectory(fRepository.getConfiguration().getTmpPath(), "mime-").toAbsolutePath();
               }
               return fIndexPath;
       }

	public Path getMimeTypesPath() {
		return fRepository.getConfiguration().getEtcPath().resolve("mime.types").normalize();
	}

	@Override
	public synchronized void close() throws IOException {
		if (fSearchIndex == null) {
			return;
		}

		fCloser.close();
		IOs.deleteIfExists(getIndexPath());
		fSearchIndex = null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(SearchIndex.class)) {
			return (AdapterType) fSearchIndex;
		}

		return Adaptables.getAdapter(fRepository, adapterType);
	}

}
