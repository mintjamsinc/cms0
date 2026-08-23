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

package org.mintjams.rt.searchindex.internal;

import java.io.IOException;

import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.io.IOs;

/**
 * A staged full rebuild of a {@link SearchIndexImpl}. The staging index is a
 * complete SearchIndexImpl of its own rooted at the live index's rebuild/
 * directory, so the writers handed out here behave exactly like the live ones.
 * The live index keeps serving queries and incremental writes until
 * {@link #commit()} swaps the staged directories in.
 */
public class RebuildSessionImpl implements SearchIndex.RebuildSession {

	private final SearchIndexImpl fSearchIndex;
	private final SearchIndexImpl fStaging;
	private boolean fClosed;

	RebuildSessionImpl(SearchIndexImpl searchIndex, SearchIndexImpl staging) {
		fSearchIndex = searchIndex;
		fStaging = staging;
	}

	@Override
	public synchronized SearchIndex.DocumentWriter getDocumentWriter() throws IOException {
		assertOpen();
		return fStaging.getDocumentWriter();
	}

	@Override
	public synchronized SearchIndex.SuggestionWriter getSuggestionWriter() throws IOException {
		assertOpen();
		return fStaging.getSuggestionWriter();
	}

	@Override
	public synchronized void commit() throws IOException {
		assertOpen();
		fStaging.getDocumentWriter().commit();
		fStaging.getSuggestionWriter().commit();
		// Release every file handle of the staged index before the directory
		// moves (required once Windows support arrives; harmless on POSIX).
		fStaging.close();
		fClosed = true;
		try {
			fSearchIndex.swapFromRebuild();
		} finally {
			fSearchIndex.rebuildSessionClosed(this);
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if (fClosed) {
			return;
		}
		fClosed = true;
		try {
			fStaging.close();
		} catch (Throwable ignore) {}
		try {
			IOs.deleteIfExists(fSearchIndex.getConfiguration().getRebuildPath());
		} catch (Throwable ignore) {}
		fSearchIndex.rebuildSessionClosed(this);
	}

	private void assertOpen() throws IOException {
		if (fClosed) {
			throw new IOException("Rebuild session has been closed.");
		}
	}

}
