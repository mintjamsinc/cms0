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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.connections.ConnectionClients;
import org.mintjams.rt.cms.internal.connections.ConnectionProviders;
import org.mintjams.rt.cms.internal.connections.cloud.CloudEntry;
import org.mintjams.rt.cms.internal.connections.cloud.CloudFiles;
import org.mintjams.rt.cms.internal.connections.cloud.CloudProvider;
import org.mintjams.rt.cms.internal.connections.cloud.GoogleDrive;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;

/**
 * The cloud files fields ({@code cloud-schema.graphqls}): the caller's Google
 * Drive or OneDrive through the files bundle of their linked account, and
 * copies between it and the workspace this endpoint serves. Part of the
 * platform, so the Content Browser has it in every workspace; the repository
 * side runs in the caller's session.
 */
public final class PlatformCloudWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/cloud-schema.graphqls";

	/** The largest content exportCloudContent accepts, in characters. */
	private static final int MAX_CONTENT_CHARS = 16 * 1024 * 1024;

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "cloudPicker", (DataFetcher<Object>) PlatformCloudWiringContributor::cloudPicker)
				.dataFetcher("Query", "cloudChildren",
						(DataFetcher<Object>) PlatformCloudWiringContributor::cloudChildren)
				.dataFetcher("Query", "cloudFile", (DataFetcher<Object>) PlatformCloudWiringContributor::cloudFile)
				.dataFetcher("Mutation", "importCloudFile",
						(DataFetcher<Object>) PlatformCloudWiringContributor::importCloudFile)
				.dataFetcher("Mutation", "exportCloudFile",
						(DataFetcher<Object>) PlatformCloudWiringContributor::exportCloudFile)
				.dataFetcher("Mutation", "exportCloudContent",
						(DataFetcher<Object>) PlatformCloudWiringContributor::exportCloudContent)
				.dataFetcher("Mutation", "createCloudLink",
						(DataFetcher<Object>) PlatformCloudWiringContributor::createCloudLink);
	}

	private static Object cloudPicker(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String provider = provider(environment);
		CloudFiles.requireProvider(provider);
		String token = call(provider, () -> CloudFiles.token(session.getUserID(), provider));
		ConnectionClients.Client client = ConnectionClients.get().get(provider);
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("provider", provider);
		map.put("available", GoogleDrive.ID.equals(provider) && client != null && client.getApiKey() != null);
		map.put("accessToken", token);
		map.put("apiKey", (client != null) ? client.getApiKey() : null);
		map.put("appId", (client != null) ? client.getAppId() : null);
		return map;
	}

	private static Object cloudChildren(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String provider = provider(environment);
		String folderId = emptyToNull(environment.getArgument("folderId"));
		CloudProvider drive = CloudFiles.requireProvider(provider);
		List<Map<String, Object>> entries = new ArrayList<>();
		for (CloudEntry entry : call(provider,
				() -> drive.children(CloudFiles.token(session.getUserID(), provider), folderId))) {
			entries.add(entry.toMap());
		}
		return entries;
	}

	private static Object cloudFile(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String provider = provider(environment);
		String id = required(environment, "id");
		CloudProvider drive = CloudFiles.requireProvider(provider);
		CloudEntry entry = call(provider, () -> drive.get(CloudFiles.token(session.getUserID(), provider), id));
		return (entry == null) ? null : entry.toMap();
	}

	private static Object importCloudFile(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String provider = provider(environment);
		String id = required(environment, "id");
		String folder = required(environment, "folder");
		String name = emptyToNull(environment.getArgument("name"));
		String exportMimeType = emptyToNull(environment.getArgument("exportMimeType"));
		CloudFiles.Conflict conflict = conflict(environment);
		CloudFiles.Imported imported = call(provider,
				() -> CloudFiles.importFile(session, provider, id, folder, name, exportMimeType, conflict));
		CmsService.getLogger(PlatformCloudWiringContributor.class).info("{} file imported: user={} path={}", provider,
				session.getUserID(), imported.getPath());
		return map(imported);
	}

	private static Object exportCloudFile(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String provider = provider(environment);
		String path = required(environment, "path");
		String folderId = emptyToNull(environment.getArgument("folderId"));
		String name = emptyToNull(environment.getArgument("name"));
		boolean convert = Boolean.TRUE.equals(environment.getArgument("convert"));
		CloudEntry entry = call(provider,
				() -> CloudFiles.exportFile(session, provider, path, folderId, name, convert));
		CmsService.getLogger(PlatformCloudWiringContributor.class).info("File exported to {}: user={} path={}",
				provider, session.getUserID(), path);
		return entry.toMap();
	}

	private static Object exportCloudContent(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String provider = provider(environment);
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
		CloudEntry entry = call(provider,
				() -> CloudFiles.exportContent(session, provider, folderId, name, mimeType, bytes, convert));
		CmsService.getLogger(PlatformCloudWiringContributor.class).info("Content exported to {}: user={} name={}",
				provider, session.getUserID(), name);
		return entry.toMap();
	}

	private static Object createCloudLink(DataFetchingEnvironment environment) throws Exception {
		Session session = userSession(environment);
		String provider = provider(environment);
		String id = required(environment, "id");
		String folder = required(environment, "folder");
		String name = emptyToNull(environment.getArgument("name"));
		CloudFiles.Conflict conflict = conflict(environment);
		CloudFiles.Imported imported = call(provider,
				() -> CloudFiles.createLink(session, provider, id, folder, name, conflict));
		return map(imported);
	}

	// ---- helpers ------------------------------------------------------------------

	private interface Call<T> {
		T run() throws Exception;
	}

	/**
	 * Runs a call against the provider, turning what it refuses, or a network
	 * failure, into an error the client is shown: the provider's own words,
	 * never a server failure.
	 */
	private static <T> T call(String provider, Call<T> call) throws Exception {
		try {
			return call.run();
		} catch (CloudProvider.CloudException ex) {
			throw new IllegalStateException(displayName(provider) + " refused: " + ex.getMessage());
		} catch (IOException ex) {
			throw new IllegalStateException(displayName(provider) + " could not be reached: " + ex.getMessage());
		}
	}

	private static String displayName(String provider) {
		ConnectionProviders.Provider p = ConnectionProviders.get(provider);
		return (p != null) ? p.getDisplayName() : provider;
	}

	private static Map<String, Object> map(CloudFiles.Imported imported) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("path", imported.getPath());
		map.put("name", imported.getName());
		map.put("mimeType", imported.getMimeType());
		map.put("size", imported.getSize());
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
