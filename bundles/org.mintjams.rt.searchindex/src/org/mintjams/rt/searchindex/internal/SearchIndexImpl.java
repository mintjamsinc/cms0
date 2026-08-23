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

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.mintjams.rt.searchindex.internal.query.JcrXPathQuery;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.searchindex.SearchIndexConfiguration;
import org.mintjams.searchindex.query.InvalidQuerySyntaxException;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.io.IOs;

public class SearchIndexImpl implements SearchIndex, Adaptable {

	/**
	 * How long the previous core (readers and directories) of a swapped index
	 * lingers before it is closed. Query results fetch rows lazily, so an
	 * in-flight result may still read from the pre-swap readers; this is the
	 * same contract (and the same duration) as DocumentReader's refresh
	 * linger. The superseded writers are closed at swap time — nothing may
	 * write to the old index once the swap begins.
	 */
	private static final long RETIRED_CORE_LINGER_MILLIS = 10L * 60 * 1000;

	private final SearchIndexConfigurationImpl fConfig;

	// The live core. Replaced as a unit by a rebuild swap: every field is
	// assigned in openCore() under the swap write lock (or the constructor)
	// and read either under the read lock or from within the core itself.
	private volatile Directory fDocumentIndexDirectory;
	private volatile Directory fDocumentTaxonomyDirectory;
	private volatile Directory fSuggestionIndexDirectory;
	private volatile FacetsConfig fFacetsConfig;
	private volatile DocumentWriterImpl fDocumentWriter;
	private volatile SuggestionWriterImpl fSuggestionWriter;
	private volatile DocumentReader fDocumentReader;
	private volatile SuggestionReader fSuggestionReader;
	private volatile List<String> fMultiValuedDimensions;

	// Serializes queries and incremental writes (read side) against the swap
	// of a rebuilt index (write side): a swap waits for in-flight operations
	// and blocks new ones for its (short) duration.
	private final ReentrantReadWriteLock fSwapLock = new ReentrantReadWriteLock();
	private final Object fMultiValuedLock = new Object();
	private final List<RetiredCore> fRetiredCores = new ArrayList<>();
	private volatile RebuildSessionImpl fRebuildSession;
	private FieldTypeProvider fFieldTypeProvider;
	private boolean fHasMultiValuedDimensionsChanges;
	private volatile boolean fCloseRequested;

	private SearchIndexImpl(SearchIndexConfigurationImpl config) throws IOException {
		fConfig = config;
		repairAtOpen();
		openCore();
	}

	public static SearchIndexImpl create(SearchIndexConfigurationImpl config) throws IOException {
		return new SearchIndexImpl(config);
	}

	/**
	 * Finishes what an interrupted rebuild left behind before the index opens.
	 * A present swap.state means a commit was interrupted after the staged
	 * index was complete: the swap is rolled forward (never rolled back).
	 * Anything else under rebuild/ or *.old is a leftover of an interrupted
	 * rebuild or cleanup and is discarded — the live directories are
	 * authoritative.
	 */
	private void repairAtOpen() throws IOException {
		if (Files.exists(fConfig.getSwapStatePath())) {
			Activator.getLogger(getClass()).warn("Rolling an interrupted search index swap forward: " + fConfig.getDataPath());
			completeSwapMoves();
		}
		try {
			IOs.deleteIfExists(fConfig.getRebuildPath());
			IOs.deleteIfExists(fConfig.getDocumentBackupPath());
			IOs.deleteIfExists(fConfig.getSuggestionBackupPath());
			Files.deleteIfExists(fConfig.getSwapStatePath());
		} catch (Throwable ex) {
			Activator.getLogger(getClass()).warn("Could not clean up search index leftovers: " + fConfig.getDataPath(), ex);
		}
	}

	private void openCore() throws IOException {
		try {
			List<String> multiValuedDimensions;
			if (Files.exists(fConfig.getMultiValuedPath())) {
				multiValuedDimensions = new ArrayList<>(Files.readAllLines(fConfig.getMultiValuedPath(), StandardCharsets.UTF_8));
			} else {
				multiValuedDimensions = new ArrayList<>();
			}
			FacetsConfig facetsConfig = new FacetsConfig();
			for (String dimension : multiValuedDimensions) {
				facetsConfig.setMultiValued(dimension, true);
			}
			synchronized (fMultiValuedLock) {
				fMultiValuedDimensions = multiValuedDimensions;
				fFacetsConfig = facetsConfig;
				fHasMultiValuedDimensionsChanges = false;
			}

			fDocumentIndexDirectory = MMapDirectory.open(fConfig.getDocumentIndexPath());
			fDocumentTaxonomyDirectory = MMapDirectory.open(fConfig.getDocumentTaxonomyPath());
			fSuggestionIndexDirectory = MMapDirectory.open(fConfig.getSuggestionIndexPath());

			fDocumentWriter = new DocumentWriterImpl(this);
			fSuggestionWriter = new SuggestionWriterImpl(this);
			fDocumentReader = new DocumentReader(this);
			fSuggestionReader = new SuggestionReader(this);
		} catch (Throwable ex) {
			closeCoreQuietly();
			if (ex instanceof IOException) {
				throw (IOException) ex;
			}
			throw new IOException(ex);
		}
	}

	/** Closes whatever part of the core is open — readers first, then writers, then directories. */
	private void closeCoreQuietly() {
		for (Closeable closeable : new Closeable[] {
				fDocumentReader, fSuggestionReader, fDocumentWriter, fSuggestionWriter,
				fDocumentIndexDirectory, fDocumentTaxonomyDirectory, fSuggestionIndexDirectory }) {
			if (closeable == null) {
				continue;
			}
			try {
				closeable.close();
			} catch (Throwable ignore) {}
		}
		fDocumentReader = null;
		fSuggestionReader = null;
		fDocumentWriter = null;
		fSuggestionWriter = null;
		fDocumentIndexDirectory = null;
		fDocumentTaxonomyDirectory = null;
		fSuggestionIndexDirectory = null;
	}

	@Override
	public SearchIndex.Query createQuery(String statement, String language) throws IOException {
		if ("jcr:xpath".equals(language)) {
			if (!(statement.startsWith(JcrXPathQuery.JCR_ROOT_PATH + "/") || statement.startsWith("//"))) {
				throw new InvalidQuerySyntaxException(statement);
			}
			return new JcrXPathQuery(statement, this);
		}

		if ("native".equals(language)) {
			if (!statement.startsWith("[")) {
				throw new InvalidQuerySyntaxException(statement);
			}
			return new JcrXPathQuery("//*" + statement, this);
		}

		throw new IllegalArgumentException("Unsupported query language: " + language);
	}

	@Override
	public DocumentWriter getDocumentWriter() throws IOException {
		Lock lock = fSwapLock.readLock();
		lock.lock();
		try {
			if (fCloseRequested) {
				throw new IOException("SearchIndex has been closed.");
			}
			sweepRetiredCores(false);
			return fDocumentWriter;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public SuggestionWriter getSuggestionWriter() throws IOException {
		Lock lock = fSwapLock.readLock();
		lock.lock();
		try {
			if (fCloseRequested) {
				throw new IOException("SearchIndex has been closed.");
			}
			sweepRetiredCores(false);
			return fSuggestionWriter;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public SearchIndex with(FieldTypeProvider fieldTypeProvider) throws IOException {
		fFieldTypeProvider = fieldTypeProvider;
		return this;
	}

	@Override
	public RebuildSession createRebuildSession() throws IOException {
		synchronized (this) {
			if (fCloseRequested) {
				throw new IOException("SearchIndex has been closed.");
			}
			if (fRebuildSession != null) {
				throw new IOException("A rebuild session is already in progress.");
			}
			// Discard the staging area of a previously interrupted rebuild.
			IOs.deleteIfExists(fConfig.getRebuildPath());

			// The staging index is a full SearchIndexImpl of its own, rooted at
			// rebuild/ and sharing the live index's configuration (analyzers,
			// dictionaries), so a rebuild produces exactly what a fresh open
			// would.
			SearchIndexConfigurationImpl stagingConfig = new SearchIndexConfigurationImpl();
			stagingConfig.setDataPath(fConfig.getRebuildPath());
			stagingConfig.setConfigPath(fConfig.getConfigPath());
			SearchIndexImpl staging = (SearchIndexImpl) stagingConfig.createSearchIndex();
			fRebuildSession = new RebuildSessionImpl(this, staging);
			return fRebuildSession;
		}
	}

	synchronized void rebuildSessionClosed(RebuildSessionImpl session) {
		if (fRebuildSession == session) {
			fRebuildSession = null;
		}
	}

	/**
	 * Makes the staged index live. Called by the rebuild session's commit after
	 * the staging writers are committed and every staging file handle is
	 * released. Queries and incremental writes block for the duration; the
	 * superseded readers linger for in-flight query results (see
	 * {@link #RETIRED_CORE_LINGER_MILLIS}).
	 */
	void swapFromRebuild() throws IOException {
		fSwapLock.writeLock().lock();
		try {
			if (fCloseRequested) {
				throw new IOException("SearchIndex has been closed.");
			}
			Activator.getLogger(getClass()).info("Swapping in the rebuilt search index: " + fConfig.getDataPath());

			// From here the swap only rolls forward: the staged index is
			// complete, so an interruption is finished at the next open, never
			// undone.
			Files.writeString(fConfig.getSwapStatePath(), "swap", StandardCharsets.UTF_8);

			// Nothing may write to the old index anymore; its readers are
			// parked because in-flight query results may still fetch rows from
			// them.
			try {
				fDocumentWriter.close();
			} catch (Throwable ignore) {}
			try {
				fSuggestionWriter.close();
			} catch (Throwable ignore) {}
			synchronized (fRetiredCores) {
				fRetiredCores.add(new RetiredCore(fDocumentReader, fSuggestionReader,
						fDocumentIndexDirectory, fDocumentTaxonomyDirectory, fSuggestionIndexDirectory));
			}
			fDocumentWriter = null;
			fSuggestionWriter = null;
			fDocumentReader = null;
			fSuggestionReader = null;
			fDocumentIndexDirectory = null;
			fDocumentTaxonomyDirectory = null;
			fSuggestionIndexDirectory = null;

			completeSwapMoves();
			openCore();
			Files.deleteIfExists(fConfig.getSwapStatePath());
		} finally {
			fSwapLock.writeLock().unlock();
		}

		// Best-effort cleanup; anything left over is removed at the next open.
		try {
			IOs.deleteIfExists(fConfig.getDocumentBackupPath());
			IOs.deleteIfExists(fConfig.getSuggestionBackupPath());
			IOs.deleteIfExists(fConfig.getRebuildPath());
		} catch (Throwable ex) {
			Activator.getLogger(getClass()).warn("Could not clean up after the search index swap: " + fConfig.getDataPath(), ex);
		}
		Activator.getLogger(getClass()).info("Search index swap completed: " + fConfig.getDataPath());
	}

	/**
	 * The directory dance of a swap, idempotent so an interrupted run can be
	 * repeated at the next open: each step is an atomic same-volume rename,
	 * and a pair whose staged directory is gone has already been swapped.
	 */
	private void completeSwapMoves() throws IOException {
		swapDirectory(fConfig.getDocumentPath(), fConfig.getDocumentBackupPath(), fConfig.getRebuildPath().resolve("documents"));
		swapDirectory(fConfig.getSuggestionPath(), fConfig.getSuggestionBackupPath(), fConfig.getRebuildPath().resolve("suggestions"));
	}

	private void swapDirectory(Path live, Path backup, Path staged) throws IOException {
		if (!Files.exists(staged)) {
			if (!Files.exists(live) && Files.exists(backup)) {
				// Defensive: never leave the index missing while a parked copy exists.
				Files.move(backup, live, StandardCopyOption.ATOMIC_MOVE);
			}
			return;
		}
		if (Files.exists(live)) {
			IOs.deleteIfExists(backup);
			Files.move(live, backup, StandardCopyOption.ATOMIC_MOVE);
		}
		Files.move(staged, live, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * The read side of the swap lock. Query execution holds this for its whole
	 * run so a swap never closes an index out from under a running search.
	 */
	public Lock getQueryLock() {
		return fSwapLock.readLock();
	}

	private void sweepRetiredCores(boolean force) {
		synchronized (fRetiredCores) {
			if (fRetiredCores.isEmpty()) {
				return;
			}
			long cutoff = System.currentTimeMillis() - RETIRED_CORE_LINGER_MILLIS;
			for (Iterator<RetiredCore> i = fRetiredCores.iterator(); i.hasNext();) {
				RetiredCore core = i.next();
				if (!force && core.retiredAt > cutoff) {
					continue;
				}
				core.close();
				i.remove();
			}
		}
	}

	SearchIndexConfigurationImpl getConfiguration() {
		return fConfig;
	}

	public Directory getDocumentIndexDirectory() {
		return fDocumentIndexDirectory;
	}

	public Directory getDocumentTaxonomyDirectory() {
		return fDocumentTaxonomyDirectory;
	}

	public Directory getSuggestionIndexDirectory() {
		return fSuggestionIndexDirectory;
	}

	public FacetsConfig getFacetsConfig() {
		return fFacetsConfig;
	}

	public DocumentReader getDocumentReader() throws IOException {
		Lock lock = fSwapLock.readLock();
		lock.lock();
		try {
			if (fCloseRequested) {
				throw new IOException("SearchIndex has been closed.");
			}
			return fDocumentReader;
		} finally {
			lock.unlock();
		}
	}

	public SuggestionReader getSuggestionReader() throws IOException {
		Lock lock = fSwapLock.readLock();
		lock.lock();
		try {
			if (fCloseRequested) {
				throw new IOException("SearchIndex has been closed.");
			}
			return fSuggestionReader;
		} finally {
			lock.unlock();
		}
	}

	public boolean setMultiValuedDimensions(String dimension, boolean multiValued) {
		synchronized (fMultiValuedLock) {
			boolean updated = false;
			if (multiValued) {
				if (!fMultiValuedDimensions.contains(dimension)) {
					fMultiValuedDimensions.add(dimension);
					fFacetsConfig.setMultiValued(dimension, multiValued);
					updated = true;
				}
			} else {
				if (fMultiValuedDimensions.contains(dimension)) {
					fMultiValuedDimensions.remove(dimension);
					fFacetsConfig.setMultiValued(dimension, multiValued);
					updated = true;
				}
			}

			if (updated) {
				fHasMultiValuedDimensionsChanges = true;
			}

			return updated;
		}
	}

	public void save() throws IOException {
		synchronized (fMultiValuedLock) {
			if (fHasMultiValuedDimensionsChanges) {
				Files.writeString(fConfig.getMultiValuedPath(), String.join("\n", fMultiValuedDimensions), StandardCharsets.UTF_8);
				fHasMultiValuedDimensionsChanges = false;
			}
		}
	}

	public boolean isCloseRequested() {
		return fCloseRequested;
	}

	@Override
	public void close() throws IOException {
		RebuildSessionImpl session = fRebuildSession;
		if (session != null) {
			try {
				session.close();
			} catch (Throwable ignore) {}
		}

		// Only the flag flip happens under the write lock (it fences a
		// concurrent swap and waits out in-flight queries). The actual closing
		// runs after the lock is released: DocumentReader's methods are
		// synchronized and may block on this lock's read side, so closing them
		// while holding the write side could deadlock against a lazy result
		// iteration.
		fSwapLock.writeLock().lock();
		try {
			if (fCloseRequested) {
				return;
			}
			fCloseRequested = true;
		} finally {
			fSwapLock.writeLock().unlock();
		}

		closeCoreQuietly();
		sweepRetiredCores(true);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(FieldTypeProvider.class)) {
			return (AdapterType) fFieldTypeProvider;
		}

		if (adapterType.equals(SearchIndexConfiguration.class) || adapterType.equals(SearchIndexConfigurationImpl.class)) {
			return (AdapterType) fConfig;
		}

		return null;
	}

	private static class RetiredCore {
		private final Closeable[] fCloseables;
		private final long retiredAt;

		RetiredCore(Closeable... closeables) {
			fCloseables = closeables;
			retiredAt = System.currentTimeMillis();
		}

		void close() {
			for (Closeable closeable : fCloseables) {
				if (closeable == null) {
					continue;
				}
				try {
					closeable.close();
				} catch (Throwable ignore) {}
			}
		}
	}

}
