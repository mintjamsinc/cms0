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

import java.util.Collections;
import java.util.List;

/**
 * One page of a listing or a search: the entries and the provider's own
 * token for the next page (a Drive {@code pageToken}, a Graph
 * {@code @odata.nextLink}), null on the last page. The token is the
 * provider's and means nothing outside the request that produced it; the
 * GraphQL layer seals it before it reaches a client.
 */
public final class CloudPage {

	private final List<CloudEntry> fEntries;
	private final String fNextCursor;

	public CloudPage(List<CloudEntry> entries, String nextCursor) {
		fEntries = Collections.unmodifiableList(entries);
		fNextCursor = (nextCursor == null || nextCursor.isBlank()) ? null : nextCursor;
	}

	public List<CloudEntry> getEntries() {
		return fEntries;
	}

	public String getNextCursor() {
		return fNextCursor;
	}

	public boolean hasMore() {
		return fNextCursor != null;
	}

}
