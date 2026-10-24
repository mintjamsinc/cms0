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
import java.util.LinkedHashMap;
import java.util.Map;

import org.mintjams.rt.cms.internal.util.ISO8601;

/**
 * A file or folder of a user's Google Drive or OneDrive, as the two
 * providers' answers are read into one shape.
 */
public final class CloudEntry {

	private final String fProvider;
	private final String fId;
	private final String fName;
	private final String fMimeType;
	private final long fSize;
	private final boolean fFolder;
	private final String fWebUrl;
	private final Instant fModified;
	private final String fNativeType;

	public CloudEntry(String provider, String id, String name, String mimeType, long size, boolean folder,
			String webUrl, Instant modified, String nativeType) {
		fProvider = provider;
		fId = id;
		fName = name;
		fMimeType = mimeType;
		fSize = size;
		fFolder = folder;
		fWebUrl = webUrl;
		fModified = modified;
		fNativeType = nativeType;
	}

	public String getProvider() {
		return fProvider;
	}

	public String getId() {
		return fId;
	}

	public String getName() {
		return fName;
	}

	/** The content type; for a Google native document, the application/vnd.google-apps.* type. */
	public String getMimeType() {
		return fMimeType;
	}

	/** Bytes, or -1 when the provider does not say (a Google native document). */
	public long getSize() {
		return fSize;
	}

	public boolean isFolder() {
		return fFolder;
	}

	/** Where the file opens in the provider's own editor or viewer; null when unknown. */
	public String getWebUrl() {
		return fWebUrl;
	}

	public Instant getModified() {
		return fModified;
	}

	/**
	 * For a Google native document, what it is (document, spreadsheet,
	 * presentation, drawing, ...): it has to be exported to be downloaded.
	 * Null for an ordinary file.
	 */
	public String getNativeType() {
		return fNativeType;
	}

	public boolean isNative() {
		return fNativeType != null;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("provider", fProvider);
		m.put("id", fId);
		m.put("name", fName);
		m.put("mimeType", fMimeType);
		m.put("size", (fSize >= 0) ? fSize : null);
		m.put("folder", fFolder);
		m.put("webUrl", fWebUrl);
		m.put("modified", (fModified != null) ? ISO8601.format(fModified) : null);
		m.put("nativeType", fNativeType);
		return m;
	}

}
