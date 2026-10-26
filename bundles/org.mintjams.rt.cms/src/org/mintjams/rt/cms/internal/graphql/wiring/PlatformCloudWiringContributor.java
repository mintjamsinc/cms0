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

package org.mintjams.rt.cms.internal.graphql.wiring;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.connections.ConnectionClients;
import org.mintjams.rt.cms.internal.connections.ConnectionProviders;
import org.mintjams.rt.cms.internal.connections.cloud.CloudDrive;
import org.mintjams.rt.cms.internal.connections.cloud.CloudEntry;
import org.mintjams.rt.cms.internal.connections.cloud.CloudFiles;
import org.mintjams.rt.cms.internal.connections.cloud.CloudPage;
import org.mintjams.rt.cms.internal.connections.cloud.CloudProvider;
import org.mintjams.rt.cms.internal.connections.cloud.CloudQuery;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;
import org.mintjams.rt.cms.internal.mcp.McpSealed;
import org.mintjams.rt.cms.internal.util.ISO8601;

import com.google.gson.JsonObject;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;

/**
 * The cloud files fields ({@code cloud-schema.graphqls}): the caller's
 * drives at Google or Microsoft through the files bundle of their linked
 * account, and copies between them and the workspace this endpoint serves.
 * Part of the platform, so the Content Browser has it in every workspace;
 * the repository side runs in the caller's session.
 *
 * <p>A page cursor is the provider's own token, sealed with the caller and
 * the provider so that it opens only for the request it belongs to.
 */
public final class PlatformCloudWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/cloud-schema.graphqls";

	/** The largest content exportCloudContent accepts, in characters. */
	private static final int MAX_CONTENT_CHARS = 16 * 1024 * 1024;

	private static final String CURSOR_PREFIX = "cc_";
	private static final String CURSOR_TYPE = "cloud-cursor";

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "cloudStorages", (DataFetcher<Object>) PlatformCloudWiringContributor::cloudStorages)
				.dataFetcher("Query", "cloudDrives", (DataFetcher<Object>) PlatformCloudWiringContributor::cloudDrives)
				.dataFetcher("Query", "cloudFolder", (DataFetcher<Object>) PlatformCloudWiringContributor::cloudFolder)
				.dataFetcher("Query", "cloudChildren",
						(DataFetcher<Object>) PlatformCloudWiringContributor::cloudChildren)
				.dataFetcher("Query", "cloudSearch", (DataFetcher<Object>) PlatformCloudWiringContributor::cloudSearch)
				.dataFetcher("Query", "cloudFile", (DataFetcher<Object>) PlatformCloudWiringContributor::cloudFile)
				.dataFetcher("Query", "cloudPreview", (DataFetcher<Object>) PlatformCloudWiringContributor::cloudPreview)
				.dataFetcher("Mutation", "createCloudFolder",
						(DataFetcher<Object>) PlatformCloudWiringContributor::createCloudFolder)
				.dataFetcher("Mutation", "renameCloudEntry",
						(DataFetcher<Object>) PlatformCloudWiringContributor::renameCloudEntry)
				.dataFetcher("Mutation", "deleteCloudEntry",
						(DataFetcher<Object>) PlatformCloudWiringContributor::deleteCloudEntry)
				.dataFetcher("Mutation", "moveCloudEntry",
						(DataFetcher<Object>) PlatformCloudWiringContributor::moveCloudEntry)
				.dataFetcher("Mutation", "copyCloudEntry",
						(DataFetcher<Object>) PlatformCloudWiringContributor::copyCloudEntry)
				.dataFetcher("Mutation", "importCloudFile",
						(DataFetcher<Object>) PlatformCloudWiringContributor::importCloudFile)
				.dataFetcher("Mutation", "exportCloudFile",
						(DataFetcher<Object>) PlatformCloudWiringContributor::exportCloudFile)
				.dataFetcher("Mutation", "exportCloudContent",
						(DataFetcher<Object>) PlatformCloudWiringContributor::exportCloudContent)
				.dataFetcher("Mutation", "createCloudLink",
						(DataFetcher<Object>) PlatformCloudWiringContributor::createCloudLink);
	}

	// ---- queries -------------------------------------------------------------------

	private static Object cloudStorages(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		List<Map<String, Object>> storages = new ArrayList<>();
		for (ConnectionClients.Client client : ConnectionClients.get().all()) {
			String providerId = client.getProvider().getId();
			CloudProvider provider = CloudFiles.provider(providerId);
			if (provider == null) {
				continue;
			}
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("provider", providerId);
			map.put("name", client.getProvider().getDisplayName());
			map.put("granted", CloudFiles.isGranted(session.getUserID(), providerId));
			map.put("searchScope", provider.getSearchScope().name());
			List<String> facets = new ArrayList<>();
			for (CloudProvider.Facet facet : provider.getFacets()) {
				facets.add(facet.name());
			}
			map.put("facets", facets);
			map.put("canPreview", provider.canPreview());
			storages.add(map);
		}
		return storages;
	}

	private static Object cloudDrives(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		CloudProvider provider = CloudFiles.requireProvider(providerId);
		return call(providerId, () -> CloudFiles.call(session.getUserID(), providerId, token -> {
			List<Map<String, Object>> drives = new ArrayList<>();
			drives.add(provider.myDrive(token).toMap());
			for (CloudDrive drive : provider.sharedDrives(token)) {
				drives.add(drive.toMap());
			}
			return drives;
		}));
	}

	private static Object cloudFolder(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String folderId = emptyToNull(environment.getArgument("folderId"));
		CloudProvider provider = CloudFiles.requireProvider(providerId);
		String userId = session.getUserID();
		return call(providerId, () -> CloudFiles.call(userId, providerId, token -> {
			CloudDrive drive = provider.drive(token, driveId);
			if (drive == null) {
				throw new CloudProvider.NotFoundException("the drive was not found");
			}
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("drive", drive.toMap());
			if (folderId == null) {
				map.put("entry", null);
				map.put("ancestors", new ArrayList<>());
				return map;
			}
			CloudEntry entry = provider.get(token, driveId, folderId);
			if (entry == null) {
				throw new CloudProvider.NotFoundException("the folder was not found");
			}
			map.put("entry", entry.toMap());
			List<Map<String, Object>> ancestors = new ArrayList<>();
			for (CloudEntry ancestor : provider.ancestors(token, driveId, folderId,
					CloudFiles.ancestorCache(userId, providerId))) {
				ancestors.add(ancestor.toMap());
			}
			map.put("ancestors", ancestors);
			return map;
		}));
	}

	private static Object cloudChildren(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String folderId = emptyToNull(environment.getArgument("folderId"));
		String cursor = openCursor(environment.getArgument("after"), session.getUserID(), providerId);
		int first = first(environment, 200);
		CloudProvider.Order order = order(environment.getArgument("order"));
		CloudProvider provider = CloudFiles.requireProvider(providerId);
		CloudPage page = call(providerId, () -> CloudFiles.call(session.getUserID(), providerId,
				token -> provider.children(token, driveId, folderId, cursor, first, order)));
		return page(page, session.getUserID(), providerId);
	}

	private static Object cloudSearch(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String folderId = emptyToNull(environment.getArgument("folderId"));
		String cursor = openCursor(environment.getArgument("after"), session.getUserID(), providerId);
		int first = first(environment, 100);
		CloudQuery query = query(environment.getArgument("query"));
		CloudProvider provider = CloudFiles.requireProvider(providerId);
		CloudPage page = call(providerId, () -> CloudFiles.call(session.getUserID(), providerId,
				token -> provider.search(token, driveId, folderId, query, cursor, first)));
		return page(page, session.getUserID(), providerId);
	}

	private static Object cloudFile(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String id = required(environment, "id");
		CloudProvider provider = CloudFiles.requireProvider(providerId);
		CloudEntry entry = call(providerId,
				() -> CloudFiles.call(session.getUserID(), providerId, token -> provider.get(token, driveId, id)));
		return (entry == null) ? null : entry.toMap();
	}

	private static Object cloudPreview(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String id = required(environment, "id");
		CloudProvider provider = CloudFiles.requireProvider(providerId);
		if (!provider.canPreview()) {
			return null;
		}
		return call(providerId,
				() -> CloudFiles.call(session.getUserID(), providerId, token -> provider.previewUrl(token, driveId, id)));
	}

	// ---- mutations on a drive -----------------------------------------------------------

	private static Object createCloudFolder(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String parentId = emptyToNull(environment.getArgument("parentId"));
		String name = required(environment, "name");
		CloudProvider provider = CloudFiles.requireProvider(providerId);
		CloudEntry entry = call(providerId, () -> CloudFiles.call(session.getUserID(), providerId,
				token -> provider.createFolder(token, driveId, parentId, name)));
		return entry.toMap();
	}

	private static Object renameCloudEntry(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String id = required(environment, "id");
		String name = required(environment, "name");
		CloudProvider provider = CloudFiles.requireProvider(providerId);
		CloudEntry entry = call(providerId, () -> CloudFiles.call(session.getUserID(), providerId,
				token -> provider.rename(token, driveId, id, name)));
		CloudFiles.forgetAncestors(session.getUserID(), providerId, driveId);
		return entry.toMap();
	}

	private static Object deleteCloudEntry(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String id = required(environment, "id");
		CloudProvider provider = CloudFiles.requireProvider(providerId);
		try {
			call(providerId, () -> CloudFiles.call(session.getUserID(), providerId, token -> {
				provider.delete(token, driveId, id);
				return Boolean.TRUE;
			}));
		} catch (CloudProvider.NotFoundException gone) {
			return false;
		}
		CloudFiles.forgetAncestors(session.getUserID(), providerId, driveId);
		CmsService.getLogger(PlatformCloudWiringContributor.class).info("{} entry trashed: user={} drive={} id={}",
				providerId, session.getUserID(), driveId, id);
		return true;
	}

	private static Object moveCloudEntry(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String id = required(environment, "id");
		String toFolderId = emptyToNull(environment.getArgument("toFolderId"));
		CloudProvider provider = CloudFiles.requireProvider(providerId);
		CloudEntry entry = call(providerId, () -> CloudFiles.call(session.getUserID(), providerId,
				token -> provider.move(token, driveId, id, toFolderId)));
		CloudFiles.forgetAncestors(session.getUserID(), providerId, driveId);
		return entry.toMap();
	}

	private static Object copyCloudEntry(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String id = required(environment, "id");
		String toDriveId = emptyToNull(environment.getArgument("toDriveId"));
		if (toDriveId == null) {
			toDriveId = CloudEntry.MY_DRIVE;
		}
		String toFolderId = emptyToNull(environment.getArgument("toFolderId"));
		String name = emptyToNull(environment.getArgument("name"));
		final String target = toDriveId;
		CloudEntry entry = call(providerId,
				() -> CloudFiles.copy(session.getUserID(), providerId, driveId, id, target, toFolderId, name));
		return entry.toMap();
	}

	// ---- mutations between a drive and the repository --------------------------------------

	private static Object importCloudFile(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String id = required(environment, "id");
		String folder = required(environment, "folder");
		String name = emptyToNull(environment.getArgument("name"));
		String exportMimeType = emptyToNull(environment.getArgument("exportMimeType"));
		CloudFiles.Conflict conflict = conflict(environment);
		CloudFiles.Imported imported = call(providerId, () -> CloudFiles.importEntry(session, providerId, driveId, id,
				folder, name, exportMimeType, conflict));
		CmsService.getLogger(PlatformCloudWiringContributor.class).info("{} entry imported: user={} path={}",
				providerId, session.getUserID(), imported.getPath());
		return map(imported);
	}

	private static Object exportCloudFile(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String path = required(environment, "path");
		String driveId = driveId(environment);
		String folderId = emptyToNull(environment.getArgument("folderId"));
		String name = emptyToNull(environment.getArgument("name"));
		boolean convert = Boolean.TRUE.equals(environment.getArgument("convert"));
		CloudEntry entry = call(providerId,
				() -> CloudFiles.exportNode(session, providerId, path, driveId, folderId, name, convert));
		CmsService.getLogger(PlatformCloudWiringContributor.class).info("Node exported to {}: user={} path={}",
				providerId, session.getUserID(), path);
		return entry.toMap();
	}

	private static Object exportCloudContent(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String folderId = emptyToNull(environment.getArgument("folderId"));
		String name = required(environment, "name");
		String mimeType = required(environment, "mimeType");
		String content = environment.getArgument("content");
		if (content == null) {
			content = "";
		}
		if (content.length() > MAX_CONTENT_CHARS) {
			throw new IllegalArgumentException("content is longer than " + MAX_CONTENT_CHARS + " characters.");
		}
		boolean convert = Boolean.TRUE.equals(environment.getArgument("convert"));
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		CloudEntry entry = call(providerId, () -> CloudFiles.exportContent(session, providerId, driveId, folderId,
				name, mimeType, bytes, convert));
		CmsService.getLogger(PlatformCloudWiringContributor.class).info("Content exported to {}: user={} name={}",
				providerId, session.getUserID(), name);
		return entry.toMap();
	}

	private static Object createCloudLink(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String providerId = provider(environment);
		String driveId = driveId(environment);
		String id = required(environment, "id");
		String folder = required(environment, "folder");
		String name = emptyToNull(environment.getArgument("name"));
		CloudFiles.Conflict conflict = conflict(environment);
		CloudFiles.Imported imported = call(providerId,
				() -> CloudFiles.createLink(session, providerId, driveId, id, folder, name, conflict));
		return map(imported);
	}

	// ---- helpers ------------------------------------------------------------------

	private interface Call<T> {
		T run() throws Exception;
	}

	/**
	 * Runs a call against the provider, turning what it refuses, or a network
	 * failure, into an error the client is shown: the provider's own words,
	 * never a server failure. A missing item and a throttled request keep
	 * their own codes (NOT_FOUND, RATE_LIMITED).
	 */
	private static <T> T call(String provider, Call<T> call) throws Exception {
		try {
			return call.run();
		} catch (CloudProvider.NotFoundException | CloudProvider.RateLimitedException ex) {
			throw ex;
		} catch (CloudProvider.CloudException ex) {
			throw new IllegalStateException(CloudFiles.displayName(provider) + " refused: " + ex.getMessage());
		} catch (IOException ex) {
			throw new IllegalStateException(CloudFiles.displayName(provider) + " could not be reached: " + ex.getMessage());
		}
	}

	private static Map<String, Object> page(CloudPage page, String userId, String providerId) {
		List<Map<String, Object>> entries = new ArrayList<>();
		for (CloudEntry entry : page.getEntries()) {
			entries.add(entry.toMap());
		}
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("entries", entries);
		map.put("nextCursor", page.hasMore() ? sealCursor(page.getNextCursor(), userId, providerId) : null);
		return map;
	}

	private static String sealCursor(String cursor, String userId, String providerId) {
		JsonObject payload = new JsonObject();
		payload.addProperty("u", userId);
		payload.addProperty("p", providerId);
		payload.addProperty("c", cursor);
		return McpSealed.seal(CmsService.getEncryptor(), CURSOR_PREFIX, CURSOR_TYPE, payload);
	}

	/** The provider's token out of a cursor this server sealed for the same caller and provider. */
	private static String openCursor(String after, String userId, String providerId) {
		if (after == null || after.isBlank()) {
			return null;
		}
		JsonObject payload = McpSealed.open(CmsService.getEncryptor(), CURSOR_PREFIX, CURSOR_TYPE, after);
		if (payload == null || !userId.equals(string(payload, "u")) || !providerId.equals(string(payload, "p"))) {
			throw new IllegalArgumentException("The cursor is not one of this listing.");
		}
		return string(payload, "c");
	}

	private static String string(JsonObject json, String name) {
		return (json.has(name) && !json.get(name).isJsonNull()) ? json.get(name).getAsString() : null;
	}

	private static CloudQuery query(Object argument) {
		if (!(argument instanceof Map)) {
			return new CloudQuery("", null, null, null, false);
		}
		Map<?, ?> m = (Map<?, ?>) argument;
		String text = (m.get("text") == null) ? "" : String.valueOf(m.get("text"));
		Set<CloudQuery.Kind> kinds = EnumSet.noneOf(CloudQuery.Kind.class);
		if (m.get("kinds") instanceof List) {
			for (Object kind : (List<?>) m.get("kinds")) {
				if (kind != null) {
					kinds.add(CloudQuery.Kind.valueOf(String.valueOf(kind)));
				}
			}
		}
		return new CloudQuery(text, kinds, instant(m.get("modifiedAfter")), instant(m.get("modifiedBefore")),
				Boolean.TRUE.equals(m.get("ownedByMe")));
	}

	private static Instant instant(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Instant) {
			return (Instant) value;
		}
		return ISO8601.parseInstant(String.valueOf(value));
	}

	private static CloudProvider.Order order(Object value) {
		if (value == null) {
			return CloudProvider.Order.NAME;
		}
		return CloudProvider.Order.valueOf(String.valueOf(value));
	}

	private static int first(DataFetchingEnvironment environment, int fallback) {
		Object value = environment.getArgument("first");
		if (!(value instanceof Number)) {
			return fallback;
		}
		int first = ((Number) value).intValue();
		return (first <= 0) ? fallback : Math.min(first, 1000);
	}

	private static Map<String, Object> map(CloudFiles.Imported imported) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("path", imported.getPath());
		map.put("name", imported.getName());
		map.put("mimeType", imported.getMimeType());
		map.put("size", (imported.getSize() >= 0) ? imported.getSize() : null);
		map.put("entry", imported.getEntry().toMap());
		return map;
	}

	private static String provider(DataFetchingEnvironment environment) {
		String provider = required(environment, "provider");
		if (ConnectionProviders.get(provider) == null) {
			throw new IllegalArgumentException("Unknown provider: " + provider);
		}
		return provider;
	}

	private static String driveId(DataFetchingEnvironment environment) {
		String driveId = emptyToNull(environment.getArgument("driveId"));
		return (driveId == null) ? CloudEntry.MY_DRIVE : driveId;
	}

	private static CloudFiles.Conflict conflict(DataFetchingEnvironment environment) {
		Object value = environment.getArgument("conflict");
		if (value == null) {
			return CloudFiles.Conflict.RENAME;
		}
		return CloudFiles.Conflict.valueOf(String.valueOf(value));
	}

	private static String required(DataFetchingEnvironment environment, String name) {
		String value = emptyToNull(environment.getArgument(name));
		if (value == null) {
			throw new IllegalArgumentException(name + " is required.");
		}
		return value;
	}

	private static String emptyToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

	/** The caller's session; a guest, anonymous, system or service session has no linked accounts. */
	private static Session userSession(DataFetchingEnvironment environment) {
		Session session = GraphQLExecutionContext.from(environment).getCallerSession();
		org.mintjams.jcr.Session jcrSession = org.mintjams.jcr.Session.class.cast(session);
		if (jcrSession.isGuest() || jcrSession.isAnonymous() || jcrSession.isSystem() || jcrSession.isService()) {
			throw new IllegalStateException("Only a signed-in user has linked accounts.");
		}
		return session;
	}

	private static String loadSchema() throws Exception {
		try (InputStream in = PlatformCloudWiringContributor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Schema resource not found: " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
