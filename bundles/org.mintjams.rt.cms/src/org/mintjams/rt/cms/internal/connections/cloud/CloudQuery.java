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

package org.mintjams.rt.cms.internal.connections.cloud;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a search asks for: words, the kinds of entry, a modification window,
 * and (Google) only what the user owns. A provider sends what its API can
 * take and applies the rest to the answers itself ({@link #matches}).
 */
public final class CloudQuery {

	/** The coarse kinds a search can be narrowed to; a facet without counts. */
	public enum Kind {
		IMAGE, VIDEO, AUDIO, DOCUMENT, FOLDER;

		/** The kind of an entry: a folder, a media type by its prefix, or a document for everything else. */
		public static Kind of(CloudEntry entry) {
			if (entry.isFolder()) {
				return FOLDER;
			}
			String type = (entry.getMimeType() == null) ? "" : entry.getMimeType();
			if (type.startsWith("image/")) {
				return IMAGE;
			}
			if (type.startsWith("video/")) {
				return VIDEO;
			}
			if (type.startsWith("audio/")) {
				return AUDIO;
			}
			return DOCUMENT;
		}
	}

	private final String fText;
	private final Set<Kind> fKinds;
	private final Instant fModifiedAfter;
	private final Instant fModifiedBefore;
	private final boolean fOwnedByMe;

	public CloudQuery(String text, Set<Kind> kinds, Instant modifiedAfter, Instant modifiedBefore, boolean ownedByMe) {
		fText = (text == null) ? "" : text.trim();
		fKinds = (kinds == null || kinds.isEmpty()) ? Collections.emptySet() : EnumSet.copyOf(kinds);
		fModifiedAfter = modifiedAfter;
		fModifiedBefore = modifiedBefore;
		fOwnedByMe = ownedByMe;
	}

	/** The words looked for; empty when the search is by facets alone. */
	public String getText() {
		return fText;
	}

	/** The kinds wanted; empty means every kind. */
	public Set<Kind> getKinds() {
		return fKinds;
	}

	public Instant getModifiedAfter() {
		return fModifiedAfter;
	}

	public Instant getModifiedBefore() {
		return fModifiedBefore;
	}

	public boolean isOwnedByMe() {
		return fOwnedByMe;
	}

	public boolean hasKinds() {
		return !fKinds.isEmpty();
	}

	public boolean hasModified() {
		return fModifiedAfter != null || fModifiedBefore != null;
	}

	/** Whether the entry satisfies the kind and date conditions (what a provider could not ask for). */
	public boolean matches(CloudEntry entry) {
		if (hasKinds() && !fKinds.contains(Kind.of(entry))) {
			return false;
		}
		if (fModifiedAfter != null && (entry.getModified() == null || entry.getModified().isBefore(fModifiedAfter))) {
			return false;
		}
		if (fModifiedBefore != null && (entry.getModified() == null || !entry.getModified().isBefore(fModifiedBefore))) {
			return false;
		}
		return true;
	}

}
