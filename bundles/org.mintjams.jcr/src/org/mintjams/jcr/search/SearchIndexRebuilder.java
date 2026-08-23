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

package org.mintjams.jcr.search;

import java.io.IOException;

import javax.jcr.RepositoryException;

import org.mintjams.searchindex.SearchIndex;

/**
 * <p>Rebuilds a workspace's search index from the repository content while the
 * workspace stays fully operational. Obtain it by adapting a session (or the
 * workspace provider) — e.g.
 * {@code Adaptables.getAdapter(session, SearchIndexRebuilder.class)}.</p>
 *
 * <p>{@link #rebuild} builds a complete replacement index in a staging area,
 * catches up on the changes that were committed while the traversal ran, and
 * atomically swaps the new index in as the live index (deleting the old one).
 * Queries and incremental index updates keep working throughout; they block
 * only for the short duration of the final swap. The rebuild is idempotent
 * and crash-safe: until the swap, the live index is untouched, so an aborted
 * or crashed rebuild can simply be run again.</p>
 */
public interface SearchIndexRebuilder {

	/**
	 * <p>Counts the indexable items ({@code nt:file} nodes outside the system
	 * space) by traversing the repository content. Intended for progress
	 * reporting (the total of a progress bar) before {@link #rebuild} runs; it
	 * deliberately does not consult the search index itself.</p>
	 */
	long countIndexableItems() throws RepositoryException, IOException;

	/**
	 * <p>Rebuilds the search index as described in the class comment.
	 * {@code monitor.getPathConsumer()} receives the path of every indexed
	 * item; {@code monitor.isCancelled()} is polled throughout and cancels the
	 * rebuild, discarding the staging area and leaving the live index as it
	 * was.</p>
	 */
	void rebuild(SearchIndex.UpdateMonitor monitor) throws RepositoryException, IOException;

}
