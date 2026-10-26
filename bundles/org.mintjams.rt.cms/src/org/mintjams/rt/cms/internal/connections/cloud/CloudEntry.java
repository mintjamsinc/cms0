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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mintjams.rt.cms.internal.util.ISO8601;

/**
 * A file or folder of a drive the user reaches through a linked account, as
 * the two providers' answers are read into one shape. The drive is named by
 * {@link #getDriveId()}: {@value #MY_DRIVE} for the user's own drive, else
 * the provider's id of a shared drive (Google) or a document library
 * (Microsoft).
 */
public final class CloudEntry {

	/** The drive id of the user's own drive (My Drive, OneDrive). */
	public static final String MY_DRIVE = "me";

	/** The servlet that serves a file's bytes: {@code /bin/cloud.cgi/<provider>/<driveId>/<id>/<name>}. */
	public static final String CONTENT_PATH = "/bin/cloud.cgi";

	private final String fProvider;
	private final String fDriveId;
	private final String fId;
	private final String fName;
	private final String fMimeType;
	private final long fSize;
	private final boolean fFolder;
	private final String fWebUrl;
	private final Instant fModified;
	private final Instant fCreated;
	private final String fNativeType;
	private final String fParentId;
	private final String fEtag;
	private final String fOwner;
	private final boolean fShared;
	private final boolean fHasThumbnail;
	private final String fPath;

	private CloudEntry(Builder b) {
		fProvider = b.provider;
		fDriveId = (b.driveId == null || b.driveId.isBlank()) ? MY_DRIVE : b.driveId;
		fId = b.id;
		fName = b.name;
		fMimeType = b.mimeType;
		fSize = b.size;
		fFolder = b.folder;
		fWebUrl = b.webUrl;
		fModified = b.modified;
		fCreated = b.created;
		fNativeType = b.nativeType;
		fParentId = b.parentId;
		fEtag = b.etag;
		fOwner = b.owner;
		fShared = b.shared;
		fHasThumbnail = b.hasThumbnail;
		fPath = b.path;
	}

	public static Builder builder(String provider, String driveId) {
		return new Builder(provider, driveId);
	}

	public String getProvider() {
		return fProvider;
	}

	/** {@value #MY_DRIVE}, or the id of the shared drive / library the entry is in. */
	public String getDriveId() {
		return fDriveId;
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

	/** Bytes, or -1 when the provider does not say (a folder, a Google native document). */
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

	public Instant getCreated() {
		return fCreated;
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

	/** The id of the folder the entry is in; null at the root of the drive, or when unknown. */
	public String getParentId() {
		return fParentId;
	}

	/**
	 * A value that changes whenever the content does (Google's {@code version},
	 * Graph's {@code cTag}); what a write is conditioned on. Null when unknown.
	 */
	public String getEtag() {
		return fEtag;
	}

	/** The owner's display name, when the provider says. */
	public String getOwner() {
		return fOwner;
	}

	/** Whether the provider reports the entry as shared with others. */
	public boolean isShared() {
		return fShared;
	}

	/** Whether the provider can hand out a thumbnail of the entry. */
	public boolean hasThumbnail() {
		return fHasThumbnail;
	}

	/** A display path of the entry within its drive ({@code /a/b/name}), when the provider gives one. */
	public String getPath() {
		return fPath;
	}

	/** The same-origin URL of the entry's bytes (see {@link CloudServlet}). */
	public String getContentUrl() {
		return contentUrl(fProvider, fDriveId, fId, fName);
	}

	public static String contentUrl(String provider, String driveId, String id, String name) {
		StringBuilder url = new StringBuilder(CONTENT_PATH).append('/').append(encode(provider)).append('/')
				.append(encode(driveId)).append('/').append(encode(id));
		if (name != null && !name.isBlank()) {
			url.append('/').append(encode(name));
		}
		return url.toString();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("provider", fProvider);
		m.put("driveId", fDriveId);
		m.put("id", fId);
		m.put("name", fName);
		m.put("mimeType", fMimeType);
		m.put("size", (fSize >= 0) ? fSize : null);
		m.put("folder", fFolder);
		m.put("webUrl", fWebUrl);
		m.put("modified", (fModified != null) ? ISO8601.format(fModified) : null);
		m.put("created", (fCreated != null) ? ISO8601.format(fCreated) : null);
		m.put("nativeType", fNativeType);
		m.put("parentId", fParentId);
		m.put("etag", fEtag);
		m.put("owner", fOwner);
		m.put("shared", fShared);
		m.put("hasThumbnail", fHasThumbnail);
		m.put("path", fPath);
		m.put("contentUrl", getContentUrl());
		return m;
	}

	public Builder toBuilder() {
		Builder b = new Builder(fProvider, fDriveId);
		b.id = fId;
		b.name = fName;
		b.mimeType = fMimeType;
		b.size = fSize;
		b.folder = fFolder;
		b.webUrl = fWebUrl;
		b.modified = fModified;
		b.created = fCreated;
		b.nativeType = fNativeType;
		b.parentId = fParentId;
		b.etag = fEtag;
		b.owner = fOwner;
		b.shared = fShared;
		b.hasThumbnail = fHasThumbnail;
		b.path = fPath;
		return b;
	}

	public static final class Builder {
		private final String provider;
		private final String driveId;
		private String id;
		private String name;
		private String mimeType;
		private long size = -1;
		private boolean folder;
		private String webUrl;
		private Instant modified;
		private Instant created;
		private String nativeType;
		private String parentId;
		private String etag;
		private String owner;
		private boolean shared;
		private boolean hasThumbnail;
		private String path;

		private Builder(String provider, String driveId) {
			this.provider = provider;
			this.driveId = driveId;
		}

		public Builder id(String value) { id = value; return this; }
		public Builder name(String value) { name = value; return this; }
		public Builder mimeType(String value) { mimeType = value; return this; }
		public Builder size(long value) { size = value; return this; }
		public Builder folder(boolean value) { folder = value; return this; }
		public Builder webUrl(String value) { webUrl = value; return this; }
		public Builder modified(Instant value) { modified = value; return this; }
		public Builder created(Instant value) { created = value; return this; }
		public Builder nativeType(String value) { nativeType = value; return this; }
		public Builder parentId(String value) { parentId = value; return this; }
		public Builder etag(String value) { etag = value; return this; }
		public Builder owner(String value) { owner = value; return this; }
		public Builder shared(boolean value) { shared = value; return this; }
		public Builder hasThumbnail(boolean value) { hasThumbnail = value; return this; }
		public Builder path(String value) { path = value; return this; }

		public CloudEntry build() {
			return new CloudEntry(this);
		}
	}

}
