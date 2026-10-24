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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.connections.ConnectionClients;
import org.mintjams.rt.cms.internal.connections.ConnectionProviders;
import org.mintjams.rt.cms.internal.connections.ConnectionTokens;
import org.mintjams.rt.cms.internal.script.resource.JcrAction;
import org.mintjams.script.ConsentRequiredException;

import com.google.gson.JsonObject;

/**
 * The files bundle at work: copying a file of the user's Google Drive or
 * OneDrive into the repository, a repository file or some content out to it,
 * and a reference to a cloud file kept as a small file in the repository.
 *
 * <p>Every repository read and write here runs in the session handed in, the
 * caller's: what the caller cannot read is not exported, where the caller
 * cannot write nothing is imported. The token is the caller's too
 * ({@link ConnectionTokens}); an account that is not linked, or has not
 * granted the files bundle, is reported as {@link ConsentRequiredException}
 * for the app to open the grant page.
 */
public final class CloudFiles {

	/** The type of a reference to a cloud file: {@code {provider, id, name, mimeType, webUrl}}. */
	public static final String LINK_TYPE = "application/vnd.mintjams.webtop.cloud-link+json";

	/** What to do when the folder already has a file of the name. */
	public enum Conflict {
		/** Refuse. */
		FAIL,
		/** Replace the content of the existing file. */
		REPLACE,
		/** Write under a name with a number appended. */
		RENAME
	}

	/** An entry written into the repository: the node and what it was made from. */
	public static final class Imported {
		private final String fPath;
		private final String fName;
		private final String fMimeType;
		private final long fSize;
		private final CloudEntry fEntry;

		Imported(String path, String name, String mimeType, long size, CloudEntry entry) {
			fPath = path;
			fName = name;
			fMimeType = mimeType;
			fSize = size;
			fEntry = entry;
		}

		public String getPath() {
			return fPath;
		}

		public String getName() {
			return fName;
		}

		public String getMimeType() {
			return fMimeType;
		}

		public long getSize() {
			return fSize;
		}

		public CloudEntry getEntry() {
			return fEntry;
		}
	}

	private CloudFiles() {}

	/** The provider's client, as configured; null when the administrator did not configure it. */
	public static CloudProvider provider(String providerId) {
		ConnectionClients.Client client = ConnectionClients.get().get(providerId);
		if (client == null) {
			return null;
		}
		switch (providerId) {
		case GoogleDrive.ID:
			return new GoogleDrive(client.getEndpoint("drive", GoogleDrive.DEFAULT_ENDPOINT));
		case OneDrive.ID:
			return new OneDrive(client.getEndpoint("graph", OneDrive.DEFAULT_ENDPOINT));
		default:
			return null;
		}
	}

	/** The provider, or a consent-required report when it is not configured. */
	public static CloudProvider requireProvider(String providerId) {
		if (ConnectionProviders.get(providerId) == null) {
			throw new IllegalArgumentException("Unknown provider: " + providerId);
		}
		CloudProvider provider = provider(providerId);
		if (provider == null) {
			throw new ConsentRequiredException(providerId, ConnectionProviders.BUNDLE_FILES,
					ConsentRequiredException.NOT_CONFIGURED);
		}
		return provider;
	}

	/** The user's access token for the files bundle of the provider. */
	public static String token(String userId, String providerId) throws IOException, RepositoryException {
		return ConnectionTokens.accessToken(userId, providerId, ConnectionProviders.BUNDLE_FILES);
	}

	/**
	 * Copies a cloud file into a folder of the repository. A Google native
	 * document is exported ({@code exportMimeType}, or the usual Office type of
	 * its kind) and named with the matching extension. Returns what was written.
	 */
	public static Imported importFile(Session session, String providerId, String fileId, String folderPath,
			String name, String exportMimeType, Conflict conflict) throws IOException, RepositoryException {
		CloudProvider provider = requireProvider(providerId);
		String token = token(session.getUserID(), providerId);
		CloudEntry entry = provider.get(token, fileId);
		if (entry == null) {
			throw new IllegalArgumentException("The file was not found on " + displayName(providerId) + ".");
		}
		if (entry.isFolder()) {
			throw new IllegalArgumentException(entry.getName() + " is a folder; only files are imported.");
		}
		Node folder = folder(session, folderPath);
		try (CloudProvider.Download download = provider.download(token, entry, exportMimeType)) {
			String mimeType = download.getMimeType();
			if (mimeType != null && mimeType.indexOf(';') > 0) {
				mimeType = mimeType.substring(0, mimeType.indexOf(';')).trim();
			}
			if (mimeType == null || mimeType.isBlank()) {
				mimeType = entry.isNative() ? GoogleDrive.defaultExportType(entry.getNativeType())
						: (entry.getMimeType() != null ? entry.getMimeType() : "application/octet-stream");
			}
			String fileName = (name == null || name.isBlank()) ? entry.getName() : name;
			if (entry.isNative() && (name == null || name.isBlank())) {
				String extension = CloudProvider.extensionOf(mimeType);
				if (!extension.isEmpty() && !fileName.toLowerCase().endsWith(extension)) {
					fileName += extension;
				}
			}
			Node file = write(session, folder, safeName(fileName), mimeType, null, download.getStream(), conflict);
			return new Imported(file.getPath(), file.getName(), mimeType, JCRs.getContentLength(file), entry);
		}
	}

	/**
	 * Uploads a repository file to a folder of the user's drive (the drive's
	 * root when {@code folderId} is null), under its own name unless
	 * {@code name} says otherwise. With {@code convert}, Google turns it into a
	 * native document.
	 */
	public static CloudEntry exportFile(Session session, String providerId, String path, String folderId, String name,
			boolean convert) throws IOException, RepositoryException {
		CloudProvider provider = requireProvider(providerId);
		if (path == null || !path.startsWith("/") || !session.nodeExists(path)) {
			throw new IllegalArgumentException("The file " + path + " does not exist.");
		}
		Node file = session.getNode(path);
		if (!JCRs.isFile(file)) {
			throw new IllegalArgumentException(path + " is not a file.");
		}
		String token = token(session.getUserID(), providerId);
		String mimeType = JCRs.getMimeType(file);
		String uploadName = (name == null || name.isBlank()) ? file.getName() : name;
		if (convert && providerId.equals(GoogleDrive.ID)) {
			// A Google document has no extension of its own.
			uploadName = stripExtension(uploadName);
		}
		try (InputStream in = JCRs.getContentAsStream(file)) {
			return provider.upload(token, folderId, uploadName, mimeType, in, JCRs.getContentLength(file), convert);
		}
	}

	/** Uploads content handed in directly (a memo's HTML, say) as a new file of the user's drive. */
	public static CloudEntry exportContent(Session session, String providerId, String folderId, String name,
			String mimeType, byte[] content, boolean convert) throws IOException, RepositoryException {
		CloudProvider provider = requireProvider(providerId);
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name is required.");
		}
		String token = token(session.getUserID(), providerId);
		String uploadName = (convert && providerId.equals(GoogleDrive.ID)) ? stripExtension(name) : name;
		return provider.upload(token, folderId, uploadName, mimeType, new ByteArrayInputStream(content),
				content.length, convert);
	}

	/**
	 * Writes a reference to a cloud file into a folder: a small JSON file of
	 * type {@link #LINK_TYPE} that an app opens by following its {@code webUrl}.
	 */
	public static Imported createLink(Session session, String providerId, String fileId, String folderPath,
			String name, Conflict conflict) throws IOException, RepositoryException {
		CloudProvider provider = requireProvider(providerId);
		String token = token(session.getUserID(), providerId);
		CloudEntry entry = provider.get(token, fileId);
		if (entry == null) {
			throw new IllegalArgumentException("The file was not found on " + displayName(providerId) + ".");
		}
		Node folder = folder(session, folderPath);
		JsonObject link = new JsonObject();
		link.addProperty("provider", providerId);
		link.addProperty("id", entry.getId());
		link.addProperty("name", entry.getName());
		link.addProperty("mimeType", entry.getMimeType());
		link.addProperty("folder", entry.isFolder());
		link.addProperty("webUrl", entry.getWebUrl());
		byte[] bytes = link.toString().getBytes(StandardCharsets.UTF_8);
		String fileName = (name == null || name.isBlank()) ? entry.getName() : name;
		Node file = write(session, folder, safeName(fileName), LINK_TYPE, "UTF-8", new ByteArrayInputStream(bytes),
				conflict);
		return new Imported(file.getPath(), file.getName(), LINK_TYPE, bytes.length, entry);
	}

	// ---- repository helpers ------------------------------------------------------

	private static Node folder(Session session, String path) throws RepositoryException {
		if (path == null || !path.startsWith("/")) {
			throw new IllegalArgumentException("folder must be an absolute repository path.");
		}
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("The folder " + path + " does not exist.");
		}
		Node node = session.getNode(path);
		if (!JCRs.isFolder(node)) {
			throw new IllegalArgumentException(path + " is not a folder.");
		}
		return node;
	}

	/**
	 * Writes the content as a file of the folder, under the name or, on a
	 * conflict, as {@code conflict} says. The folder's and the file's locks are
	 * honoured the way every other write honours them.
	 */
	private static Node write(Session session, Node folder, String name, String mimeType, String encoding,
			InputStream content, Conflict conflict) throws RepositoryException {
		Node file;
		if (JCRs.exists(folder, name)) {
			Node existing = folder.getNode(name);
			if (conflict == Conflict.REPLACE) {
				if (!JCRs.isFile(existing)) {
					throw new IllegalArgumentException(existing.getPath() + " is not a file and cannot be replaced.");
				}
				JcrAction.create(existing).addLockToken().checkLock();
				file = existing;
			} else if (conflict == Conflict.RENAME) {
				file = JCRs.createFile(folder, freeName(folder, name));
			} else {
				throw new IllegalArgumentException(folder.getPath() + " already has " + name + ".");
			}
		} else {
			JcrAction.create(folder).addLockToken().checkLock();
			file = JCRs.createFile(folder, name);
		}
		try {
			JCRs.write(file, content);
			JCRs.setProperty(file, "jcr:mimeType", mimeType);
			if (encoding != null) {
				JCRs.setProperty(file, "jcr:encoding", encoding);
			}
			session.save();
		} catch (RepositoryException | RuntimeException ex) {
			session.refresh(false);
			throw ex;
		}
		return file;
	}

	/** {@code name}, or {@code name (2)}, {@code name (3)}, ... when the folder has it. */
	private static String freeName(Node folder, String name) throws RepositoryException {
		int dot = name.lastIndexOf('.');
		String stem = (dot > 0) ? name.substring(0, dot) : name;
		String extension = (dot > 0) ? name.substring(dot) : "";
		for (int i = 2; i < 10_000; i++) {
			String candidate = stem + " (" + i + ")" + extension;
			if (!JCRs.exists(folder, candidate)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException(folder.getPath() + " already has too many files named " + name + ".");
	}

	/** A name a repository node can carry: no path separators or control characters, not empty. */
	static String safeName(String name) {
		String clean = (name == null) ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
		while (clean.startsWith(".")) {
			clean = clean.substring(1);
		}
		if (clean.isEmpty()) {
			clean = "file";
		}
		return clean;
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return (dot > 0 && name.length() - dot <= 6) ? name.substring(0, dot) : name;
	}

	private static String displayName(String providerId) {
		ConnectionProviders.Provider provider = ConnectionProviders.get(providerId);
		return (provider != null) ? provider.getDisplayName() : providerId;
	}

}
