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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.connections.ConnectionClients;
import org.mintjams.rt.cms.internal.connections.ConnectionProviders;
import org.mintjams.rt.cms.internal.connections.ConnectionTokens;
import org.mintjams.rt.cms.internal.connections.LinkedAccounts;
import org.mintjams.rt.cms.internal.script.resource.JcrAction;
import org.mintjams.script.ConsentRequiredException;

import com.google.gson.JsonObject;

/**
 * The files bundle at work: the user's drives read and written through
 * {@link CloudProvider}, copies between them and the repository, and
 * references to cloud files kept as small files in the repository.
 *
 * <p>Every repository read and write here runs in the session handed in, the
 * caller's: what the caller cannot read is not exported, where the caller
 * cannot write nothing is imported. The token is the caller's too
 * ({@link ConnectionTokens}); an account that is not linked, or has not
 * granted the files bundle, is reported as {@link ConsentRequiredException}
 * for the app to open the grant page.
 *
 * <p>A folder is copied by walking it: one GraphQL call, up to
 * {@value #FOLDER_LIMIT} entries per folder. A larger folder is refused and
 * what was copied so far is left, so the user can split the work.
 */
public final class CloudFiles {

	/** The type of a reference to a cloud file: {@code {provider, driveId, id, name, mimeType, folder, webUrl}}. */
	public static final String LINK_TYPE = "application/vnd.mintjams.webtop.cloud-link+json";

	/** How many entries one folder may hold to be copied in one call. */
	public static final int FOLDER_LIMIT = 1000;

	/** How long an entry read on the way up a hierarchy is remembered. */
	private static final long ANCESTOR_CACHE_MILLIS = 60_000;
	private static final int ANCESTOR_CACHE_SIZE = 20_000;

	private static final Map<String, Cached> fAncestorCache = new ConcurrentHashMap<>();

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

	/** A call against the provider with the user's token. */
	public interface Call<T> {
		T run(String token) throws IOException, RepositoryException;
	}

	private static final class Cached {
		final CloudEntry entry;
		final long expiresAt;

		Cached(CloudEntry entry, long expiresAt) {
			this.entry = entry;
			this.expiresAt = expiresAt;
		}
	}

	private CloudFiles() {}

	// ---- the provider and the token ----------------------------------------------

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
	 * Runs a call with the user's token. A token the provider no longer
	 * accepts (401) is thrown away and the call made once more with a fresh
	 * one; refused again, the account is reported as needing a new link.
	 */
	public static <T> T call(String userId, String providerId, Call<T> call) throws IOException, RepositoryException {
		String token = token(userId, providerId);
		try {
			return call.run(token);
		} catch (CloudProvider.CloudException first) {
			if (first.getStatus() != 401) {
				throw first;
			}
			ConnectionTokens.invalidate(userId, providerId);
			String fresh = token(userId, providerId);
			if (fresh.equals(token)) {
				// Nothing new to try with.
				throw first;
			}
			try {
				return call.run(fresh);
			} catch (CloudProvider.CloudException second) {
				if (second.getStatus() != 401) {
					throw second;
				}
				ConnectionTokens.invalidate(userId, providerId);
				CmsService.getLogger(CloudFiles.class).info(
						"{} does not accept the tokens of {} for the files bundle; the account needs a new link.",
						providerId, userId);
				throw new ConsentRequiredException(providerId, ConnectionProviders.BUNDLE_FILES,
						ConsentRequiredException.REAUTHORIZE);
			}
		}
	}

	/** Whether the user's account of the provider has the files bundle, with every scope it takes today. */
	public static boolean isGranted(String userId, String providerId) {
		try {
			ConnectionProviders.Provider provider = ConnectionProviders.get(providerId);
			LinkedAccounts.Account account = LinkedAccounts.get(userId, providerId);
			return provider != null && account != null && !account.isNeedsReauth()
					&& account.getBundles().contains(ConnectionProviders.BUNDLE_FILES)
					&& account.covers(provider.getBundleScopes(ConnectionProviders.BUNDLE_FILES));
		} catch (RepositoryException ex) {
			return false;
		}
	}

	// ---- reading -----------------------------------------------------------------

	/** The cache of entries read on the way up a hierarchy, for one user and provider. */
	public static CloudProvider.EntryCache ancestorCache(String userId, String providerId) {
		String prefix = userId + "|" + providerId + "|";
		return (driveId, id, loader) -> {
			String key = prefix + driveId + "|" + id;
			long now = System.currentTimeMillis();
			Cached cached = fAncestorCache.get(key);
			if (cached != null && cached.expiresAt > now) {
				return cached.entry;
			}
			CloudEntry entry = loader.load();
			if (entry != null) {
				if (fAncestorCache.size() > ANCESTOR_CACHE_SIZE) {
					fAncestorCache.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
				}
				fAncestorCache.put(key, new Cached(entry, now + ANCESTOR_CACHE_MILLIS));
			}
			return entry;
		};
	}

	/** Forgets what the cache holds for a drive: after a rename or a move in it. */
	public static void forgetAncestors(String userId, String providerId, String driveId) {
		String prefix = userId + "|" + providerId + "|" + driveId + "|";
		fAncestorCache.keySet().removeIf(key -> key.startsWith(prefix));
	}

	/** The folders above an entry, from the first below the root to its parent. */
	public static List<CloudEntry> ancestors(String userId, String providerId, String driveId, String id)
			throws IOException, RepositoryException {
		CloudProvider provider = requireProvider(providerId);
		CloudProvider.EntryCache cache = ancestorCache(userId, providerId);
		return call(userId, providerId, token -> provider.ancestors(token, driveId, id, cache));
	}

	// ---- writing to a drive ----------------------------------------------------------

	/**
	 * Writes content as a file of a folder of a drive, resolving a name the
	 * folder already has as {@code conflict} says: refused, replaced
	 * (the existing file's content), or written under {@code name (2)}. The
	 * providers' own conflict handling is not relied on, because Drive lets two
	 * files share a name.
	 */
	public static CloudEntry upload(String token, CloudProvider provider, String driveId, String folderId, String name,
			String mimeType, InputStream content, long length, boolean convert, Conflict conflict) throws IOException {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name is required.");
		}
		String uploadName = (convert && provider.getId().equals(GoogleDrive.ID)) ? stripExtension(name) : name;
		CloudEntry existing = provider.child(token, driveId, folderId, uploadName);
		if (existing != null) {
			if (conflict == Conflict.REPLACE && !convert) {
				if (existing.isFolder()) {
					throw new IllegalArgumentException(existing.getName() + " is a folder and cannot be replaced.");
				}
				return provider.update(token, driveId, existing.getId(), mimeType, content, length, null);
			}
			if (conflict == Conflict.FAIL) {
				throw new IllegalArgumentException("The folder already has " + uploadName + ".");
			}
			uploadName = freeName(token, provider, driveId, folderId, uploadName);
		}
		return provider.upload(token, driveId, folderId, uploadName, mimeType, content, length, convert);
	}

	/** {@code name (2)}, {@code name (3)}, ... the first the folder does not have. */
	private static String freeName(String token, CloudProvider provider, String driveId, String folderId, String name)
			throws IOException {
		int dot = name.lastIndexOf('.');
		String stem = (dot > 0) ? name.substring(0, dot) : name;
		String extension = (dot > 0) ? name.substring(dot) : "";
		for (int i = 2; i < 100; i++) {
			String candidate = stem + " (" + i + ")" + extension;
			if (provider.child(token, driveId, folderId, candidate) == null) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("The folder already has too many files named " + name + ".");
	}

	/**
	 * A new folder of the name in a folder; a name the folder already has (a
	 * file or a folder) gets a number, as a file's would, so that a copy never
	 * lands inside something that was already there.
	 */
	private static CloudEntry folderFor(String token, CloudProvider provider, String driveId, String parentId,
			String name) throws IOException {
		String folderName = (provider.child(token, driveId, parentId, name) == null) ? name
				: freeName(token, provider, driveId, parentId, name);
		return provider.createFolder(token, driveId, parentId, folderName);
	}

	/**
	 * Copies an entry to a folder of a drive of the same provider. A folder
	 * the provider cannot copy itself is walked: a folder of the name is made
	 * and its entries copied one by one, up to {@value #FOLDER_LIMIT} each.
	 */
	public static CloudEntry copy(String userId, String providerId, String driveId, String id, String toDriveId,
			String toFolderId, String name) throws IOException, RepositoryException {
		CloudProvider provider = requireProvider(providerId);
		return call(userId, providerId, token -> {
			CloudEntry entry = provider.get(token, driveId, id);
			if (entry == null) {
				throw new CloudProvider.NotFoundException("the file was not found");
			}
			if (entry.isFolder() && !provider.canCopyFolder()) {
				String folderName = (name == null || name.isBlank()) ? entry.getName() : name;
				CloudEntry made = folderFor(token, provider, toDriveId, toFolderId, folderName);
				copyChildren(token, provider, entry, made, 0);
				return made;
			}
			return provider.copy(token, driveId, id, toDriveId, toFolderId, name);
		});
	}

	private static void copyChildren(String token, CloudProvider provider, CloudEntry from, CloudEntry to, int depth)
			throws IOException {
		if (depth > 32) {
			throw new IllegalArgumentException("The folder is nested too deeply to be copied.");
		}
		int count = 0;
		String cursor = null;
		do {
			CloudPage page = provider.children(token, from.getDriveId(), from.getId(), cursor, 200,
					CloudProvider.Order.NAME);
			for (CloudEntry child : page.getEntries()) {
				if (++count > FOLDER_LIMIT) {
					throw new IllegalArgumentException("The folder " + from.getName() + " holds more than "
							+ FOLDER_LIMIT + " entries; it is too large to copy in one go.");
				}
				if (child.isFolder()) {
					CloudEntry made = folderFor(token, provider, to.getDriveId(), to.getId(), child.getName());
					copyChildren(token, provider, child, made, depth + 1);
				} else {
					provider.copy(token, child.getDriveId(), child.getId(), to.getDriveId(), to.getId(), null);
				}
			}
			cursor = page.getNextCursor();
		} while (cursor != null);
	}

	// ---- drive → repository ----------------------------------------------------------

	/**
	 * Copies a cloud entry into a folder of the repository. A file is written
	 * as a file (a Google native document exported as {@code exportMimeType},
	 * or the usual Office type of its kind, and named with the matching
	 * extension); a folder becomes a folder with everything in it. Returns
	 * what was written at the top.
	 */
	public static Imported importEntry(Session session, String providerId, String driveId, String id,
			String folderPath, String name, String exportMimeType, Conflict conflict)
			throws IOException, RepositoryException {
		CloudProvider provider = requireProvider(providerId);
		Node folder = folder(session, folderPath);
		return call(session.getUserID(), providerId, token -> {
			CloudEntry entry = provider.get(token, driveId, id);
			if (entry == null) {
				throw new IllegalArgumentException("The file was not found on " + displayName(providerId) + ".");
			}
			if (entry.isFolder()) {
				String folderName = safeName((name == null || name.isBlank()) ? entry.getName() : name);
				Node made = folderFor(session, folder, folderName, conflict);
				importChildren(session, token, provider, entry, made, 0);
				return new Imported(made.getPath(), made.getName(), null, -1, entry);
			}
			return importFile(session, token, provider, entry, folder, name, exportMimeType, conflict);
		});
	}

	private static Imported importFile(Session session, String token, CloudProvider provider, CloudEntry entry,
			Node folder, String name, String exportMimeType, Conflict conflict) throws IOException, RepositoryException {
		try (CloudProvider.Download download = provider.download(token, entry, exportMimeType, null)) {
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

	private static void importChildren(Session session, String token, CloudProvider provider, CloudEntry from,
			Node to, int depth) throws IOException, RepositoryException {
		if (depth > 32) {
			throw new IllegalArgumentException("The folder is nested too deeply to be imported.");
		}
		int count = 0;
		String cursor = null;
		do {
			CloudPage page = provider.children(token, from.getDriveId(), from.getId(), cursor, 200,
					CloudProvider.Order.NAME);
			for (CloudEntry child : page.getEntries()) {
				if (++count > FOLDER_LIMIT) {
					throw new IllegalArgumentException("The folder " + from.getName() + " holds more than "
							+ FOLDER_LIMIT + " entries; it is too large to import in one go.");
				}
				if (child.isFolder()) {
					Node made = folderFor(session, to, safeName(child.getName()), Conflict.REPLACE);
					importChildren(session, token, provider, child, made, depth + 1);
				} else {
					importFile(session, token, provider, child, to, null, null, Conflict.RENAME);
				}
			}
			cursor = page.getNextCursor();
		} while (cursor != null);
	}

	// ---- repository → drive ----------------------------------------------------------

	/**
	 * Uploads a repository file or folder to a folder of a drive (the drive's
	 * root when {@code folderId} is null), under its own name unless
	 * {@code name} says otherwise. With {@code convert}, Google turns a file
	 * into a native document. A folder is walked, {@value #FOLDER_LIMIT}
	 * entries per folder at most.
	 */
	public static CloudEntry exportNode(Session session, String providerId, String path, String driveId,
			String folderId, String name, boolean convert) throws IOException, RepositoryException {
		CloudProvider provider = requireProvider(providerId);
		if (path == null || !path.startsWith("/") || !session.nodeExists(path)) {
			throw new IllegalArgumentException("The file " + path + " does not exist.");
		}
		Node node = session.getNode(path);
		if (!JCRs.isFile(node) && !JCRs.isFolder(node)) {
			throw new IllegalArgumentException(path + " is neither a file nor a folder.");
		}
		return call(session.getUserID(), providerId, token -> {
			if (JCRs.isFolder(node)) {
				String folderName = (name == null || name.isBlank()) ? node.getName() : name;
				CloudEntry made = folderFor(token, provider, driveId, folderId, folderName);
				exportChildren(token, provider, node, made, 0);
				return made;
			}
			return exportFile(token, provider, node, driveId, folderId, name, convert);
		});
	}

	private static CloudEntry exportFile(String token, CloudProvider provider, Node file, String driveId,
			String folderId, String name, boolean convert) throws IOException, RepositoryException {
		String mimeType = JCRs.getMimeType(file);
		String uploadName = (name == null || name.isBlank()) ? file.getName() : name;
		try (InputStream in = JCRs.getContentAsStream(file)) {
			return upload(token, provider, driveId, folderId, uploadName, mimeType, in, JCRs.getContentLength(file),
					convert, Conflict.RENAME);
		}
	}

	private static void exportChildren(String token, CloudProvider provider, Node from, CloudEntry to, int depth)
			throws IOException, RepositoryException {
		if (depth > 32) {
			throw new IllegalArgumentException("The folder is nested too deeply to be exported.");
		}
		int count = 0;
		for (NodeIterator it = from.getNodes(); it.hasNext();) {
			Node child = it.nextNode();
			if (!JCRs.isFile(child) && !JCRs.isFolder(child)) {
				continue;
			}
			if (++count > FOLDER_LIMIT) {
				throw new IllegalArgumentException("The folder " + from.getName() + " holds more than " + FOLDER_LIMIT
						+ " entries; it is too large to export in one go.");
			}
			if (JCRs.isFolder(child)) {
				CloudEntry made = folderFor(token, provider, to.getDriveId(), to.getId(), child.getName());
				exportChildren(token, provider, child, made, depth + 1);
			} else {
				exportFile(token, provider, child, to.getDriveId(), to.getId(), null, false);
			}
		}
	}

	/** Uploads content handed in directly (a memo's HTML, say) as a new file of a drive. */
	public static CloudEntry exportContent(Session session, String providerId, String driveId, String folderId,
			String name, String mimeType, byte[] content, boolean convert) throws IOException, RepositoryException {
		CloudProvider provider = requireProvider(providerId);
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name is required.");
		}
		return call(session.getUserID(), providerId, token -> upload(token, provider, driveId, folderId, name,
				mimeType, new ByteArrayInputStream(content), content.length, convert, Conflict.RENAME));
	}

	/**
	 * Writes a reference to a cloud file into a folder: a small JSON file of
	 * type {@link #LINK_TYPE} that an app opens by following its {@code webUrl}.
	 */
	public static Imported createLink(Session session, String providerId, String driveId, String fileId,
			String folderPath, String name, Conflict conflict) throws IOException, RepositoryException {
		CloudProvider provider = requireProvider(providerId);
		Node folder = folder(session, folderPath);
		return call(session.getUserID(), providerId, token -> {
			CloudEntry entry = provider.get(token, driveId, fileId);
			if (entry == null) {
				throw new IllegalArgumentException("The file was not found on " + displayName(providerId) + ".");
			}
			JsonObject link = new JsonObject();
			link.addProperty("provider", providerId);
			link.addProperty("driveId", entry.getDriveId());
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
		});
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

	/** A folder of the name in a folder: as {@code conflict} says when the name is taken. */
	private static Node folderFor(Session session, Node parent, String name, Conflict conflict)
			throws RepositoryException {
		if (JCRs.exists(parent, name)) {
			Node existing = parent.getNode(name);
			if (conflict == Conflict.REPLACE && JCRs.isFolder(existing)) {
				return existing;
			}
			if (conflict == Conflict.FAIL) {
				throw new IllegalArgumentException(parent.getPath() + " already has " + name + ".");
			}
			name = freeName(parent, name);
		}
		JcrAction.create(parent).addLockToken().checkLock();
		Node made = JCRs.createFolder(parent, name);
		session.save();
		return made;
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

	static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return (dot > 0 && name.length() - dot <= 6) ? name.substring(0, dot) : name;
	}

	public static String displayName(String providerId) {
		ConnectionProviders.Provider provider = ConnectionProviders.get(providerId);
		return (provider != null) ? provider.getDisplayName() : providerId;
	}

	/** For diagnostics: the entries kept for walking up hierarchies. */
	static int cachedAncestors() {
		return fAncestorCache.size();
	}

	static List<String> cachedAncestorKeys() {
		return new ArrayList<>(fAncestorCache.keySet());
	}

}
