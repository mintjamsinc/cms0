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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.lock.LockManager;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.cms.event.CmsEvent;
import org.mintjams.rt.cms.internal.graphql.ClusterQueryExecutor;
import org.mintjams.rt.cms.internal.graphql.GraphQLRequest;
import org.mintjams.rt.cms.internal.graphql.MultipartUploadManager;
import org.mintjams.rt.cms.internal.graphql.type.NodeMapper;
import org.mintjams.rt.cms.internal.graphql.type.PrincipalDisplayNameResolver;
import org.mintjams.rt.cms.internal.graphql.type.PropertyValue;
import org.mintjams.rt.cms.internal.graphql.QueryExecutor;
import org.mintjams.rt.cms.internal.graphql.ast.Field;
import org.mintjams.rt.cms.internal.graphql.ast.SelectionSet;
import org.mintjams.rt.cms.internal.job.JobNodes;
import org.mintjams.rt.cms.internal.job.JobStatus;
import org.mintjams.rt.cms.internal.job.archive.ArchiveJob;
import org.mintjams.rt.cms.internal.job.archive.ImportArchiveJob;
import org.mintjams.rt.cms.internal.job.delete.DeleteJob;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.security.ServiceUserCredentials;
import org.osgi.service.event.Event;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import org.mintjams.rt.cms.internal.graphql.event.CmsEventPublisher;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;
import org.mintjams.rt.cms.internal.graphql.event.OsgiEventPublisher;
import org.mintjams.rt.cms.internal.graphql.type.PropertyValueTypeResolver;

/**
 * Contributes the platform's built-in GraphQL schema (the migration target of
 * the handmade {@code /bin/graphql.cgi} API) to the unified per-workspace
 * {@link org.mintjams.rt.cms.internal.graphql.engine.WorkspaceGraphQLEngineProvider}: the SDL bundle resource
 * {@code platform-schema.graphqls} plus Java {@link DataFetcher}s that reuse the
 * existing {@link NodeMapper} / {@link ClusterQueryExecutor}, run under the
 * caller's JCR session via {@link GraphQLExecutionContext}.
 *
 * <p>This is a side-by-side reimplementation: it deliberately does <em>not</em>
 * touch the production handmade {@code QueryExecutor}; it re-expresses the same
 * JCR work (cursor format {@code base64("arrayconnection:" + offset)},
 * {@code totalCount} from the iterator size, {@code NodeMapper} projection) so
 * the two engines can be compared until the new one reaches parity. The handmade
 * engine is removed at cut-over, retiring the temporary duplication.
 *
 * <p>Current coverage: {@code cluster}; JCR content reads ({@code node},
 * {@code children}, {@code references}, {@code xpath}, {@code query},
 * {@code search}, {@code accessControl}, {@code versionHistory}, {@code apps} —
 * with the {@code PropertyValue} union and lock/version/webRender detail);
 * content mutations ({@code createFolder}, {@code createFile},
 * {@code renameNode}, {@code moveNode}, {@code copyNode}, {@code deleteNode},
 * {@code setProperties} — including upload-backed binary properties via
 * {@code binaryUploadId}, {@code binaryArrayUploadIds} and
 * {@code binaryArrayItems}); the chunked multipart upload lifecycle
 * ({@code initiateMultipartUpload}, {@code appendMultipartUploadChunk},
 * {@code completeMultipartUpload}, {@code abortMultipartUpload}); and node
 * operations ({@code lockNode}/{@code unlockNode},
 * {@code addMixin}/{@code deleteMixin},
 * {@code setAccessControl}/{@code deleteAccessControl},
 * {@code addVersionControl}/{@code checkin}/{@code checkout}/{@code uncheckout}/
 * {@code checkpoint}/{@code restoreVersion}); and async-job lifecycle mutations
 * ({@code deleteNodes}, {@code downloadArchive}, {@code importArchive} —
 * init/append/start/abort, written under a privileged management session); and
 * the live subscriptions ({@code jobProgress}, {@code nodeChanged},
 * {@code preferenceChanged}, {@code wallpaperChanged}, {@code avatarChanged},
 * {@code workspaceChanged} — {@code Publisher}s bridged from the workspace
 * CmsEvent feed, served over the legacy SSE envelope; preference/wallpaper/avatar
 * resolve the user from the session; nodeChanged is a metadata-only re-check
 * signal, content being ACL-protected at the client's re-query), plus the BPM
 * subscriptions ({@code taskAssigned}/{@code taskCompleted}/{@code taskUpdated}/
 * {@code processStarted}/{@code processEnded}) bridged from the Camunda
 * EventAdmin topics via {@link OsgiEventPublisher}.
 * EIP {@code routeStateChanged} is bridged from the Camel Route EventAdmin
 * topics (declared in eip-schema.graphqls, wired in PlatformEipWiringContributor).
 * {@code queryChanged} and {@code systemNotification} were removed — dead
 * declarations with no event source or live consumer, to be redesigned if a
 * concrete need arises. Deferred: nested Node relations.
 *
 * <p>To preserve parity with the handmade engine (which computes a field only
 * when selected), each fetcher builds an {@code ast.SelectionSet} from
 * graphql-java's selected fields and hands it to {@link NodeMapper}.
 */
public final class PlatformWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/platform-schema.graphqls";

	private static final DateTimeFormatter ISO8601 =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "cluster", (DataFetcher<Object>) PlatformWiringContributor::cluster)
				.dataFetcher("Query", "node", (DataFetcher<Object>) PlatformWiringContributor::node)
				.dataFetcher("Query", "children", (DataFetcher<Object>) PlatformWiringContributor::children)
				.dataFetcher("Query", "references", (DataFetcher<Object>) PlatformWiringContributor::references)
				.dataFetcher("Query", "xpath", (DataFetcher<Object>) PlatformWiringContributor::xpath)
				.dataFetcher("Query", "query", (DataFetcher<Object>) PlatformWiringContributor::genericQuery)
				.dataFetcher("Query", "search", (DataFetcher<Object>) PlatformWiringContributor::search)
				.dataFetcher("Query", "accessControl", (DataFetcher<Object>) PlatformWiringContributor::accessControl)
				.dataFetcher("Query", "versionHistory", (DataFetcher<Object>) PlatformWiringContributor::versionHistory)
				.dataFetcher("Query", "apps", (DataFetcher<Object>) PlatformWiringContributor::apps)
				.dataFetcher("Query", "effectiveAccessControl", (DataFetcher<Object>) PlatformWiringContributor::effectiveAccessControl)
				.dataFetcher("Query", "searchPrincipals", (DataFetcher<Object>) PlatformWiringContributor::searchPrincipals)
				.dataFetcher("Mutation", "createFolder", (DataFetcher<Object>) PlatformWiringContributor::createFolder)
				.dataFetcher("Mutation", "createFile", (DataFetcher<Object>) PlatformWiringContributor::createFile)
				.dataFetcher("Mutation", "renameNode", (DataFetcher<Object>) PlatformWiringContributor::renameNode)
				.dataFetcher("Mutation", "moveNode", (DataFetcher<Object>) PlatformWiringContributor::moveNode)
				.dataFetcher("Mutation", "deleteNode", (DataFetcher<Object>) PlatformWiringContributor::deleteNode)
				.dataFetcher("Mutation", "setProperties", (DataFetcher<Object>) PlatformWiringContributor::setProperties)
				.dataFetcher("Mutation", "initiateMultipartUpload",
						(DataFetcher<Object>) PlatformWiringContributor::initiateMultipartUpload)
				.dataFetcher("Mutation", "appendMultipartUploadChunk",
						(DataFetcher<Object>) PlatformWiringContributor::appendMultipartUploadChunk)
				.dataFetcher("Mutation", "completeMultipartUpload",
						(DataFetcher<Object>) PlatformWiringContributor::completeMultipartUpload)
				.dataFetcher("Mutation", "abortMultipartUpload",
						(DataFetcher<Object>) PlatformWiringContributor::abortMultipartUpload)
				.dataFetcher("Mutation", "copyNode", (DataFetcher<Object>) PlatformWiringContributor::copyNode)
				.dataFetcher("Mutation", "lockNode", (DataFetcher<Object>) PlatformWiringContributor::lockNode)
				.dataFetcher("Mutation", "unlockNode", (DataFetcher<Object>) PlatformWiringContributor::unlockNode)
				.dataFetcher("Mutation", "addMixin", (DataFetcher<Object>) PlatformWiringContributor::addMixin)
				.dataFetcher("Mutation", "deleteMixin", (DataFetcher<Object>) PlatformWiringContributor::deleteMixin)
				.dataFetcher("Mutation", "setAccessControl",
						(DataFetcher<Object>) PlatformWiringContributor::setAccessControl)
				.dataFetcher("Mutation", "deleteAccessControl",
						(DataFetcher<Object>) PlatformWiringContributor::deleteAccessControl)
				.dataFetcher("Mutation", "addVersionControl",
						(DataFetcher<Object>) PlatformWiringContributor::addVersionControl)
				.dataFetcher("Mutation", "checkin", (DataFetcher<Object>) PlatformWiringContributor::checkin)
				.dataFetcher("Mutation", "checkout", (DataFetcher<Object>) PlatformWiringContributor::checkout)
				.dataFetcher("Mutation", "uncheckout", (DataFetcher<Object>) PlatformWiringContributor::uncheckout)
				.dataFetcher("Mutation", "checkpoint", (DataFetcher<Object>) PlatformWiringContributor::checkpoint)
				.dataFetcher("Mutation", "restoreVersion",
						(DataFetcher<Object>) PlatformWiringContributor::restoreVersion)
				.dataFetcher("Mutation", "initDeleteNodes",
						(DataFetcher<Object>) PlatformWiringContributor::initDeleteNodes)
				.dataFetcher("Mutation", "appendDeleteNodes",
						(DataFetcher<Object>) PlatformWiringContributor::appendDeleteNodes)
				.dataFetcher("Mutation", "startDeleteNodes",
						(DataFetcher<Object>) PlatformWiringContributor::startDeleteNodes)
				.dataFetcher("Mutation", "abortDeleteNodes",
						(DataFetcher<Object>) PlatformWiringContributor::abortDeleteNodes)
				.dataFetcher("Mutation", "initDownloadArchive",
						(DataFetcher<Object>) PlatformWiringContributor::initDownloadArchive)
				.dataFetcher("Mutation", "appendDownloadArchive",
						(DataFetcher<Object>) PlatformWiringContributor::appendDownloadArchive)
				.dataFetcher("Mutation", "startDownloadArchive",
						(DataFetcher<Object>) PlatformWiringContributor::startDownloadArchive)
				.dataFetcher("Mutation", "abortDownloadArchive",
						(DataFetcher<Object>) PlatformWiringContributor::abortDownloadArchive)
				.dataFetcher("Mutation", "initImportArchive",
						(DataFetcher<Object>) PlatformWiringContributor::initImportArchive)
				.dataFetcher("Mutation", "startImportArchive",
						(DataFetcher<Object>) PlatformWiringContributor::startImportArchive)
				.dataFetcher("Mutation", "abortImportArchive",
						(DataFetcher<Object>) PlatformWiringContributor::abortImportArchive)
				.dataFetcher("Subscription", "jobProgress",
						(DataFetcher<Object>) PlatformWiringContributor::jobProgress)
				.dataFetcher("Subscription", "nodeChanged",
						(DataFetcher<Object>) PlatformWiringContributor::nodeChanged)
				.dataFetcher("Subscription", "preferenceChanged",
						(DataFetcher<Object>) PlatformWiringContributor::preferenceChanged)
				.dataFetcher("Subscription", "wallpaperChanged",
						(DataFetcher<Object>) PlatformWiringContributor::wallpaperChanged)
				.dataFetcher("Subscription", "avatarChanged",
						(DataFetcher<Object>) PlatformWiringContributor::avatarChanged)
				.dataFetcher("Subscription", "workspaceChanged",
						(DataFetcher<Object>) PlatformWiringContributor::workspaceChanged)
				.dataFetcher("Subscription", "taskAssigned",
						(DataFetcher<Object>) PlatformWiringContributor::taskAssigned)
				.dataFetcher("Subscription", "taskCompleted",
						(DataFetcher<Object>) PlatformWiringContributor::taskCompleted)
				.dataFetcher("Subscription", "taskUpdated",
						(DataFetcher<Object>) PlatformWiringContributor::taskUpdated)
				.dataFetcher("Subscription", "processStarted",
						(DataFetcher<Object>) PlatformWiringContributor::processStarted)
				.dataFetcher("Subscription", "processEnded",
						(DataFetcher<Object>) PlatformWiringContributor::processEnded)
				.typeResolver("PropertyValue", new PropertyValueTypeResolver());
	}

	// ---- resolvers ---------------------------------------------------------

	/** {@code Query.cluster} — delegates to the existing executor (enforces admin/service itself). */
	private static Object cluster(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> data = new ClusterQueryExecutor(session).executeClusterQuery(null);
		return data.get("cluster");
	}

	/** {@code Query.node(path)} — one node mapped via {@link NodeMapper}, or null. */
	private static Object node(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = environment.getArgument("path");
		if (path == null || !session.nodeExists(path)) {
			return null;
		}
		return NodeMapper.toGraphQL(session.getNode(path), astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/** {@code Query.children(path, first, after)} — child nodes as a Relay connection. */
	private static Object children(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = requireNode(session, environment.getArgument("path"));
		int first = first(environment);
		int start = startPosition(environment.getArgument("after"));
		PrincipalDisplayNameResolver resolver = new PrincipalDisplayNameResolver(session);
		SelectionSet nodeSelection = connectionNodeSelection(environment.getSelectionSet());

		NodeIterator iterator = session.getNode(path).getNodes();
		long totalCount = iterator.getSize();
		if (totalCount == -1) {
			totalCount = 0;
			while (iterator.hasNext()) {
				iterator.nextNode();
				totalCount++;
			}
			iterator = session.getNode(path).getNodes();
		}
		List<Map<String, Object>> edges = nodeEdges(iterator, start, first, nodeSelection, resolver);
		return connection(edges, iterator.hasNext(), start > 0, totalCount);
	}

	/**
	 * {@code Query.references(path, first, after)} — nodes referencing the target
	 * (strong + weak). Mirrors the handmade dual-iterator paging: strong refs
	 * whose parent is {@code jcr:content} are climbed to their parent; weak refs
	 * are not.
	 */
	private static Object references(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = requireNode(session, environment.getArgument("path"));
		int first = first(environment);
		int start = startPosition(environment.getArgument("after"));
		PrincipalDisplayNameResolver resolver = new PrincipalDisplayNameResolver(session);
		SelectionSet nodeSelection = connectionNodeSelection(environment.getSelectionSet());

		Node target = session.getNode(path);
		if (!target.isNodeType("mix:referenceable")) {
			return connection(new ArrayList<>(), false, false, 0L);
		}

		PropertyIterator refProps = target.getReferences();
		PropertyIterator weakRefProps = target.getWeakReferences();
		long refCount = refProps.getSize();
		long weakRefCount = weakRefProps.getSize();
		long totalCount = refCount + weakRefCount;

		List<Map<String, Object>> edges = new ArrayList<>();
		int position = start;
		int count = 0;

		if (start < refCount) {
			if (start > 0) {
				refProps.skip(start);
			}
			while (refProps.hasNext() && count < first) {
				Node referencing = refProps.nextProperty().getParent();
				if (referencing.getName().equals("jcr:content")) {
					referencing = referencing.getParent();
				}
				edges.add(edge(NodeMapper.toGraphQL(referencing, nodeSelection, resolver), position));
				position++;
				count++;
			}
		}

		if (count < first && start < totalCount) {
			long weakStart = Math.max(0L, start - refCount);
			if (weakStart > 0) {
				weakRefProps.skip(weakStart);
			}
			while (weakRefProps.hasNext() && count < first) {
				Node referencing = weakRefProps.nextProperty().getParent();
				edges.add(edge(NodeMapper.toGraphQL(referencing, nodeSelection, resolver), position));
				position++;
				count++;
			}
		}

		boolean hasNextPage = (start < refCount && refProps.hasNext()) || weakRefProps.hasNext();
		return connection(edges, hasNextPage, start > 0, totalCount);
	}

	/** {@code Query.xpath(query, first, after)} — JCR XPath query as a connection. */
	private static Object xpath(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String jcrQuery = environment.getArgument("query");
		return queryConnection(session, jcrQuery, Query.XPATH, environment);
	}

	/** {@code Query.query(statement, language, first, after)} — generic JCR query as a connection. */
	private static Object genericQuery(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String statement = environment.getArgument("statement");
		String language = normalizeLanguage(environment.getArgument("language"));
		return queryConnection(session, statement, language, environment);
	}

	/** Shared node-query connection (xpath / generic). */
	private static Object queryConnection(Session session, String statement, String language,
			DataFetchingEnvironment environment) throws RepositoryException {
		int first = first(environment);
		int start = startPosition(environment.getArgument("after"));
		PrincipalDisplayNameResolver resolver = new PrincipalDisplayNameResolver(session);
		SelectionSet nodeSelection = connectionNodeSelection(environment.getSelectionSet());

		QueryManager queryManager = session.getWorkspace().getQueryManager();
		NodeIterator iterator = queryManager.createQuery(statement, language).execute().getNodes();
		long totalCount = iterator.getSize();
		List<Map<String, Object>> edges = nodeEdges(iterator, start, first, nodeSelection, resolver);
		return connection(edges, iterator.hasNext(), start > 0, totalCount);
	}

	/**
	 * {@code Query.search(text, path, first, after)} — full-text search over
	 * nt:file nodes via {@code jcr:contains}. Injects {@code score} onto each node
	 * map (the only place {@code score} is set).
	 */
	private static Object search(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String text = environment.getArgument("text");
		String searchPath = environment.getArgument("path");
		int first = first(environment);
		int start = startPosition(environment.getArgument("after"));
		PrincipalDisplayNameResolver resolver = new PrincipalDisplayNameResolver(session);
		SelectionSet nodeSelection = connectionNodeSelection(environment.getSelectionSet());

		if (searchPath == null || searchPath.isEmpty()) {
			searchPath = "";
		} else if (!searchPath.startsWith("/")) {
			throw new IllegalArgumentException("Invalid search path: " + searchPath);
		} else if (searchPath.endsWith("/")) {
			searchPath = searchPath.substring(0, searchPath.length() - 1);
		}
		if (!searchPath.isEmpty()) {
			searchPath = "/jcr:root" + searchPath;
		}
		String xpathQuery = searchPath + "//element(*, nt:file)[jcr:contains(., '"
				+ text.replaceAll("'", "\\'") + "')]";

		QueryManager queryManager = session.getWorkspace().getQueryManager();
		RowIterator rows = queryManager.createQuery(xpathQuery, Query.XPATH).execute().getRows();
		long totalCount = rows.getSize();
		if (start > 0) {
			rows.skip(start);
		}

		List<Map<String, Object>> edges = new ArrayList<>();
		int position = start;
		int count = 0;
		while (rows.hasNext() && count < first) {
			Row row = rows.nextRow();
			Map<String, Object> nodeData = NodeMapper.toGraphQL(row.getNode(), nodeSelection, resolver);
			// The search index may not produce a finite relevance score; graphql-java's
			// Float scalar rejects NaN/Infinity, so coerce a non-finite score to null.
			double score = row.getScore();
			nodeData.put("score", Double.isFinite(score) ? Double.valueOf(score) : null);
			edges.add(edge(nodeData, position));
			position++;
			count++;
		}
		return connection(edges, rows.hasNext(), start > 0, totalCount);
	}

	/** {@code Query.accessControl(path)} — ACL entries (AccessControlList policies only). */
	private static Object accessControl(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = requireNode(session, environment.getArgument("path"));
		PrincipalDisplayNameResolver resolver = new PrincipalDisplayNameResolver(session);

		AccessControlManager acm = session.getAccessControlManager();
		List<Map<String, Object>> entries = new ArrayList<>();
		for (AccessControlPolicy policy : acm.getPolicies(path)) {
			if (!(policy instanceof AccessControlList)) {
				continue;
			}
			for (AccessControlEntry entry : ((AccessControlList) policy).getAccessControlEntries()) {
				String principalName = entry.getPrincipal().getName();
				boolean isGroup = entry.getPrincipal() instanceof org.mintjams.jcr.security.GroupPrincipal;
				Map<String, Object> principal = new HashMap<>();
				principal.put("id", principalName);
				principal.put("displayName", resolver.resolve(principalName, isGroup));
				principal.put("isGroup", isGroup);

				List<String> privileges = new ArrayList<>();
				for (Privilege privilege : entry.getPrivileges()) {
					privileges.add(privilege.getName());
				}

				Map<String, Object> entryMap = new HashMap<>();
				entryMap.put("principal", principal);
				entryMap.put("privileges", privileges);
				entryMap.put("allow", (entry instanceof org.mintjams.jcr.security.AccessControlEntry)
						? ((org.mintjams.jcr.security.AccessControlEntry) entry).isAllow()
						: true);
				entries.add(entryMap);
			}
		}
		Map<String, Object> result = new HashMap<>();
		result.put("entries", entries);
		return result;
	}

	/**
	 * {@code Query.effectiveAccessControl(path)} — the node's own ACL plus every
	 * ancestor policy that applies (current + inherited). Delegates to the existing
	 * executor so the effective-policy walk and result shape stay single-sourced;
	 * the argument is passed as a variable so the executor's variable-binding path
	 * resolves it. Returns the list of per-path policies (each {@code {path, entries}}).
	 */
	private static Object effectiveAccessControl(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> variables = new HashMap<>();
		variables.put("path", environment.getArgument("path"));
		GraphQLRequest request = new GraphQLRequest(
				"query($path:String!){ effectiveAccessControl(path:$path) }", null, variables);
		Map<String, Object> data = new QueryExecutor(session).executeEffectiveAccessControlQuery(request);
		return data.get("effectiveAccessControl");
	}

	/**
	 * {@code Query.searchPrincipals(keyword, offset, limit)} — identity-store search.
	 * Delegates to the existing executor, which runs the lookup under privilege against
	 * the system workspace as the caller (a plain caller session cannot resolve the
	 * lookup). Arguments are passed as variables so the executor's variable-binding path
	 * is used; {@code offset}/{@code limit} carry their schema defaults (0 / 20).
	 */
	private static Object searchPrincipals(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> variables = new HashMap<>();
		variables.put("keyword", environment.getArgument("keyword"));
		variables.put("offset", environment.getArgument("offset"));
		variables.put("limit", environment.getArgument("limit"));
		GraphQLRequest request = new GraphQLRequest(
				"query($keyword:String,$offset:Int,$limit:Int){ searchPrincipals(keyword:$keyword, offset:$offset, limit:$limit) }",
				null, variables);
		Map<String, Object> data = new QueryExecutor(session).executeSearchPrincipalsQuery(request);
		return data.get("searchPrincipals");
	}

	/**
	 * {@code Query.versionHistory(path)} — version history of a mix:versionable
	 * node, in the handmade connection-like shape ({@code edges}/{@code pageInfo}/
	 * {@code totalCount} + {@code baseVersion} + {@code versionableUuid}; version
	 * cursors are {@code base64("version:" + index)}).
	 */
	private static Object versionHistory(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = requireNode(session, environment.getArgument("path"));
		Node node = session.getNode(path);
		if (!node.isNodeType("mix:versionable")) {
			throw new IllegalArgumentException("Node is not versionable: " + path);
		}
		VersionManager versionManager = session.getWorkspace().getVersionManager();
		VersionHistory history = versionManager.getVersionHistory(path);

		List<Map<String, Object>> edges = new ArrayList<>();
		int index = 0;
		for (VersionIterator versions = history.getAllVersions(); versions.hasNext();) {
			Version version = versions.nextVersion();
			if (version.getName().equals("jcr:rootVersion")) {
				continue;
			}
			Map<String, Object> versionData = new HashMap<>();
			versionData.put("name", version.getName());
			if (version.hasProperty("jcr:created")) {
				versionData.put("created", formatDate(version.getProperty("jcr:created").getDate()));
			}
			Node frozen = version.getFrozenNode();
			if (frozen != null) {
				if (frozen.hasProperty("jcr:createdBy")) {
					versionData.put("createdBy", frozen.getProperty("jcr:createdBy").getString());
				}
				versionData.put("frozenNodePath", frozen.getPath());
			}
			List<String> predecessors = new ArrayList<>();
			for (Version predecessor : version.getPredecessors()) {
				if (!predecessor.getName().equals("jcr:rootVersion")) {
					predecessors.add(predecessor.getName());
				}
			}
			versionData.put("predecessors", predecessors);
			List<String> successors = new ArrayList<>();
			for (Version successor : version.getSuccessors()) {
				successors.add(successor.getName());
			}
			versionData.put("successors", successors);

			Map<String, Object> edge = new HashMap<>();
			edge.put("node", versionData);
			edge.put("cursor",
					Base64.getEncoder().encodeToString(("version:" + index).getBytes(StandardCharsets.UTF_8)));
			edges.add(edge);
			index++;
		}

		Version baseVersion = versionManager.getBaseVersion(path);
		Map<String, Object> baseVersionData = null;
		if (baseVersion != null) {
			baseVersionData = new HashMap<>();
			baseVersionData.put("name", baseVersion.getName());
			if (baseVersion.hasProperty("jcr:created")) {
				baseVersionData.put("created", formatDate(baseVersion.getProperty("jcr:created").getDate()));
			}
		}

		Map<String, Object> pageInfo = new HashMap<>();
		pageInfo.put("hasNextPage", false);
		pageInfo.put("hasPreviousPage", false);
		pageInfo.put("startCursor", edges.isEmpty() ? null : edges.get(0).get("cursor"));
		pageInfo.put("endCursor", edges.isEmpty() ? null : edges.get(edges.size() - 1).get("cursor"));

		Map<String, Object> result = new HashMap<>();
		result.put("edges", edges);
		result.put("pageInfo", pageInfo);
		result.put("totalCount", edges.size());
		result.put("baseVersion", baseVersionData);
		result.put("versionableUuid", history.getVersionableIdentifier());
		return result;
	}

	/** {@code Query.apps(path, first, after)} — Webtop applications under an apps root. */
	private static Object apps(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String appsPath = environment.getArgument("path");
		while (appsPath.length() > 1 && appsPath.endsWith("/")) {
			appsPath = appsPath.substring(0, appsPath.length() - 1);
		}
		int first = Math.max(0, first(environment));
		int start = startPosition(environment.getArgument("after"));

		if (!session.nodeExists(appsPath)) {
			return connection(new ArrayList<>(), false, false, 0L);
		}
		String xpath = "/jcr:root" + appsPath + "//element(app.yml,nt:file)";
		NodeIterator iterator = session.getWorkspace().getQueryManager()
				.createQuery(xpath, Query.XPATH).execute().getNodes();
		long totalCount = iterator.getSize();
		if (start > 0) {
			iterator.skip(start);
		}
		List<Map<String, Object>> edges = new ArrayList<>();
		int position = start;
		int count = 0;
		while (iterator.hasNext() && count < first) {
			edges.add(edge(appNode(iterator.nextNode(), appsPath), position));
			position++;
			count++;
		}
		return connection(edges, iterator.hasNext(), start > 0, totalCount);
	}

	/** Builds an App node map from an {@code app.yml} node (mirrors QueryExecutor.toAppNode). */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> appNode(Node appYmlNode, String appsPath) throws Exception {
		Node appDir = appYmlNode.getParent();
		String appHome = appDir.getPath();
		String name = appDir.getName();
		String relPath = name;
		if (appHome.length() > appsPath.length() && appHome.startsWith(appsPath + "/")) {
			relPath = appHome.substring(appsPath.length() + 1);
		}
		Map<String, Object> data = parseAppDescriptor(appYmlNode);

		Map<String, Object> app = new HashMap<>();
		app.put("identifier", data.get("identifier"));
		app.put("name", name);
		app.put("title", data.get("title"));
		app.put("path", appHome);
		app.put("relPath", relPath);
		app.put("editor", asBoolean(data.get("editor")));
		app.put("enableStartMenu", asBooleanOrNull(data.get("enableStartMenu")));
		app.put("isAdminOnly", asBoolean(data.get("isAdminOnly")));
		app.put("singleton", asBoolean(data.get("singleton")));
		app.put("customWindowControls", asBoolean(data.get("customWindowControls")));
		app.put("minimumWidth", asInteger(data.get("minimumWidth")));
		app.put("minimumHeight", asInteger(data.get("minimumHeight")));

		List<String> contentTypes = new ArrayList<>();
		Object contentTypesObj = data.get("contentTypes");
		if (contentTypesObj instanceof List) {
			for (Object item : (List<Object>) contentTypesObj) {
				if (item != null) {
					contentTypes.add(item.toString());
				}
			}
		}
		app.put("contentTypes", contentTypes);

		Object iconObj = data.get("icon");
		String icon = (iconObj instanceof String && !((String) iconObj).isEmpty()) ? (String) iconObj : null;
		if (icon == null && appDir.hasNode("icon.svg")) {
			icon = "icon.svg";
		}
		app.put("icon", icon);

		String modified = lastModified(appDir, "index.html");
		if (modified == null) {
			modified = contentLastModified(appYmlNode);
		}
		app.put("modified", modified);

		List<Map<String, Object>> actions = new ArrayList<>();
		Object actionsObj = data.get("actions");
		if (actionsObj instanceof Map) {
			for (Map.Entry<String, Object> entry : ((Map<String, Object>) actionsObj).entrySet()) {
				Map<String, Object> action = new LinkedHashMap<>();
				action.put("identifier", entry.getKey());
				if (entry.getValue() instanceof Map) {
					Map<String, Object> value = (Map<String, Object>) entry.getValue();
					action.put("label", value.get("label"));
					action.put("icon", value.get("icon"));
					action.put("title", value.get("title"));
					action.put("handler", value.get("handler"));
				}
				actions.add(action);
			}
		}
		app.put("actions", actions);
		return app;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseAppDescriptor(Node appYmlNode) {
		try {
			if (!appYmlNode.hasNode("jcr:content")) {
				return new HashMap<>();
			}
			Node content = appYmlNode.getNode("jcr:content");
			if (!content.hasProperty("jcr:data")) {
				return new HashMap<>();
			}
			try (InputStream in = content.getProperty("jcr:data").getBinary().getStream()) {
				Object parsed = new Load(LoadSettings.builder().build()).loadFromInputStream(in);
				if (parsed instanceof Map) {
					return (Map<String, Object>) parsed;
				}
			}
		} catch (Throwable ex) {
			// A malformed descriptor must not abort the whole listing.
		}
		return new HashMap<>();
	}

	private static String lastModified(Node parent, String childName) throws Exception {
		return parent.hasNode(childName) ? contentLastModified(parent.getNode(childName)) : null;
	}

	private static String contentLastModified(Node fileNode) throws Exception {
		if (fileNode.hasNode("jcr:content")) {
			Node content = fileNode.getNode("jcr:content");
			if (content.hasProperty("jcr:lastModified")) {
				return formatDate(content.getProperty("jcr:lastModified").getDate());
			}
		}
		if (fileNode.hasProperty("jcr:lastModified")) {
			return formatDate(fileNode.getProperty("jcr:lastModified").getDate());
		}
		return null;
	}

	private static boolean asBoolean(Object value) {
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof String) {
			return Boolean.parseBoolean((String) value);
		}
		return false;
	}

	private static Boolean asBooleanOrNull(Object value) {
		return (value == null) ? null : asBoolean(value);
	}

	private static Integer asInteger(Object value) {
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		if (value instanceof String) {
			try {
				return Integer.valueOf(((String) value).trim());
			} catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

	private static String formatDate(Calendar calendar) {
		return (calendar == null) ? null : ISO8601.format(calendar.toInstant());
	}

	// ---- mutations (content CRUD — Phase 2a) -------------------------------
	// Side-by-side reimplementation of MutationExecutor's content CRUD; runs
	// under the caller's JCR session and calls session.save() on success. The
	// engine's per-request finally (refresh(false) + logout) rolls back transient
	// changes when a resolver throws before saving — same model as the handmade
	// servlet. Returned nodes are projected through NodeMapper (selected fields).

	/** {@code Mutation.createFolder(input)} — returns the created folder node. */
	private static Object createFolder(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String parentPath = (String) input.get("path");
		String name = (String) input.get("name");
		String nodeType = (input.get("nodeType") != null) ? (String) input.get("nodeType") : "nt:folder";
		boolean createParents = !Boolean.FALSE.equals(input.get("createParents"));
		if (parentPath == null || name == null) {
			throw new IllegalArgumentException("path and name are required");
		}
		ensureParentExists(session, parentPath, createParents);
		Node folder = session.getNode(parentPath).addNode(name, nodeType);
		session.save();
		return NodeMapper.toGraphQL(session.getNode(folder.getPath()), astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/** {@code Mutation.createFile(input)} — creates nt:file + jcr:content from Base64 content. */
	private static Object createFile(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String parentPath = (String) input.get("path");
		String name = (String) input.get("name");
		String mimeType = (String) input.get("mimeType");
		String contentBase64 = (String) input.get("content");
		String nodeType = (input.get("nodeType") != null) ? (String) input.get("nodeType") : "nt:file";
		boolean createParents = !Boolean.FALSE.equals(input.get("createParents"));
		if (parentPath == null || name == null || contentBase64 == null) {
			throw new IllegalArgumentException("path, name, and content are required");
		}
		if (mimeType == null || mimeType.trim().isEmpty()) {
			mimeType = "application/octet-stream";
		}
		ensureParentExists(session, parentPath, createParents);
		Node fileNode = session.getNode(parentPath).addNode(name, nodeType);
		Node contentNode = fileNode.addNode("jcr:content", "nt:resource");
		byte[] data = Base64.getDecoder().decode(contentBase64);
		Calendar now = Calendar.getInstance();
		try (InputStream in = new ByteArrayInputStream(data)) {
			JCRs.write(fileNode, in);
		}
		contentNode.setProperty("jcr:mimeType", mimeType);
		contentNode.setProperty("jcr:lastModified", now);
		contentNode.setProperty("jcr:lastModifiedBy", session.getUserID());
		session.save();
		return NodeMapper.toGraphQL(session.getNode(fileNode.getPath()), astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/** {@code Mutation.renameNode(input)} — rename = move within the same parent. */
	private static Object renameNode(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String path = (String) input.get("path");
		String newName = (String) input.get("name");
		if (path == null || newName == null) {
			throw new IllegalArgumentException("path and name are required");
		}
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}
		String parentPath = session.getNode(path).getParent().getPath();
		String newPath = "/".equals(parentPath) ? "/" + newName : parentPath + "/" + newName;
		if (session.nodeExists(newPath)) {
			throw new IllegalArgumentException("Node already exists at path: " + newPath);
		}
		session.move(path, newPath);
		session.save();
		return NodeMapper.toGraphQL(session.getNode(newPath), astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/** {@code Mutation.moveNode(input)} — move into destPath (a parent folder), optionally renaming. */
	private static Object moveNode(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String sourcePath = (String) input.get("sourcePath");
		String destPath = (String) input.get("destPath");
		String newName = (String) input.get("name");
		if (sourcePath == null || destPath == null) {
			throw new IllegalArgumentException("sourcePath and destPath are required");
		}
		if (!session.nodeExists(sourcePath)) {
			throw new IllegalArgumentException("Source node not found: " + sourcePath);
		}
		if (!session.nodeExists(destPath)) {
			throw new IllegalArgumentException("Destination parent node not found: " + destPath);
		}
		Node sourceNode = session.getNode(sourcePath);
		Node destParentNode = session.getNode(destPath);
		if (!destParentNode.isNodeType("nt:folder") && !destParentNode.isNodeType("nt:unstructured")) {
			throw new IllegalArgumentException("Destination must be a folder node: " + destPath);
		}
		String nodeName = (newName != null && !newName.trim().isEmpty()) ? newName : sourceNode.getName();
		String targetPath = "/".equals(destPath) ? "/" + nodeName : destPath + "/" + nodeName;
		if (session.nodeExists(targetPath)) {
			throw new IllegalArgumentException("Node already exists at destination: " + targetPath);
		}
		session.move(sourcePath, targetPath);
		session.save();
		return NodeMapper.toGraphQL(session.getNode(targetPath), astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/** {@code Mutation.deleteNode(input)} — returns false (no-op) when the node is absent. */
	private static Object deleteNode(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String path = (String) input.get("path");
		if (path == null) {
			throw new IllegalArgumentException("path is required");
		}
		if (!session.nodeExists(path)) {
			return false;
		}
		session.getNode(path).remove();
		session.save();
		return true;
	}

	/**
	 * {@code Mutation.setProperties(input)} — atomically set/delete properties on a
	 * node's {@code jcr:content} (mirrors MutationExecutor.executeSetProperties).
	 * All writes are staged then committed with a single {@code save()} only when
	 * there are no per-property errors; otherwise {@code refresh(false)} discards
	 * everything (all-or-nothing). Reuses {@link PropertyValue#fromInput(Map)}.
	 */
	@SuppressWarnings("unchecked")
	private static Object setProperties(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String path = (String) input.get("path");
		Object propertiesObj = input.get("properties");
		if (path == null || propertiesObj == null) {
			throw new IllegalArgumentException("path and properties are required");
		}
		if (!(propertiesObj instanceof List)) {
			throw new IllegalArgumentException("properties must be an array");
		}
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}

		Node node = session.getNode(path);
		List<Map<String, Object>> errors = new ArrayList<>();
		for (Object propObj : (List<Object>) propertiesObj) {
			if (!(propObj instanceof Map)) {
				errors.add(propertyError(null, "Each property must be an object with name and value fields"));
				continue;
			}
			Map<String, Object> propertyInput = (Map<String, Object>) propObj;
			String name = (String) propertyInput.get("name");
			Object valueObj = propertyInput.get("value");
			if (name == null) {
				errors.add(propertyError(null, "Property name is required"));
				continue;
			}
			try {
				Node targetNode = node.getNode("jcr:content");
				if (valueObj == null || "null".equals(valueObj)) {
					if (targetNode.hasProperty(name)) {
						targetNode.getProperty(name).remove();
					}
					continue;
				}
				if (!(valueObj instanceof Map)) {
					errors.add(propertyError(name, "Property value must be a PropertyValueInput object or null"));
					continue;
				}
				PropertyValue propValue = PropertyValue.fromInput((Map<String, Object>) valueObj);
				applyPropertyValue(session, targetNode, name, propValue);
			} catch (Throwable ex) {
				errors.add(propertyError(name, "Failed to set property '" + name + "': " + ex.getMessage()));
			}
		}

		if (errors.isEmpty()) {
			session.save();
		} else {
			session.refresh(false);
		}

		Map<String, Object> payload = new HashMap<>();
		payload.put("node", NodeMapper.toGraphQL(node, subSelection(environment.getSelectionSet(), "node"),
				new PrincipalDisplayNameResolver(session)));
		payload.put("errors", errors);
		return payload;
	}

	/** {@code Mutation.initiateMultipartUpload} — begins a chunked upload session. */
	private static Object initiateMultipartUpload(DataFetchingEnvironment environment) throws Exception {
		return new MultipartUploadManager(session(environment)).initiate();
	}

	/** {@code Mutation.appendMultipartUploadChunk(input)} — appends a Base64 chunk. */
	private static Object appendMultipartUploadChunk(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> input = inputArg(environment);
		String uploadId = (String) input.get("uploadId");
		String data = (String) input.get("data");
		if (uploadId == null || uploadId.trim().isEmpty()) {
			throw new IllegalArgumentException("uploadId is required");
		}
		if (data == null || data.trim().isEmpty()) {
			throw new IllegalArgumentException("data is required");
		}
		return new MultipartUploadManager(session(environment)).append(uploadId, data);
	}

	/** {@code Mutation.completeMultipartUpload(input)} — writes the upload to a new nt:file node. */
	private static Object completeMultipartUpload(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String uploadId = (String) input.get("uploadId");
		String path = (String) input.get("path");
		String name = (String) input.get("name");
		String mimeType = (String) input.get("mimeType");
		boolean overwrite = Boolean.TRUE.equals(input.get("overwrite"));
		if (uploadId == null || uploadId.trim().isEmpty()) {
			throw new IllegalArgumentException("uploadId is required");
		}
		if (path == null || path.trim().isEmpty()) {
			throw new IllegalArgumentException("path is required");
		}
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("name is required");
		}
		if (mimeType == null || mimeType.trim().isEmpty()) {
			throw new IllegalArgumentException("mimeType is required");
		}
		Node created = new MultipartUploadManager(session).complete(uploadId, path, name, mimeType, overwrite);
		return NodeMapper.toGraphQL(created, astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/** {@code Mutation.abortMultipartUpload(input)} — discards an upload session. */
	private static Object abortMultipartUpload(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> input = inputArg(environment);
		String uploadId = (String) input.get("uploadId");
		if (uploadId == null || uploadId.trim().isEmpty()) {
			throw new IllegalArgumentException("uploadId is required");
		}
		return new MultipartUploadManager(session(environment)).abort(uploadId);
	}

	// ---- mutations (node operations — Phase 2d) ----------------------------
	// copy / lock / mixin / ACL / versioning. Mirror MutationExecutor; run under
	// the caller session and persist with save() (or the lock/version/workspace
	// manager's own commit). Returned nodes are NodeMapper-projected (selected
	// fields). The engine's per-request finally (refresh(false) + logout) rolls
	// back transient changes when a resolver throws before committing.

	/** {@code Mutation.copyNode(input)} — deep copy under destPath (a folder), optionally renaming. */
	private static Object copyNode(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String sourcePath = (String) input.get("sourcePath");
		String destPath = (String) input.get("destPath");
		String newName = (String) input.get("name");
		if (sourcePath == null || destPath == null) {
			throw new IllegalArgumentException("sourcePath and destPath are required");
		}
		if (!session.nodeExists(sourcePath)) {
			throw new IllegalArgumentException("Source node not found: " + sourcePath);
		}
		if (!session.nodeExists(destPath)) {
			throw new IllegalArgumentException("Destination parent node not found: " + destPath);
		}
		Node sourceNode = session.getNode(sourcePath);
		Node destParentNode = session.getNode(destPath);
		if (!destParentNode.isNodeType("nt:folder") && !destParentNode.isNodeType("nt:unstructured")) {
			throw new IllegalArgumentException("Destination must be a folder node: " + destPath);
		}
		String nodeName = (newName != null && !newName.trim().isEmpty()) ? newName : sourceNode.getName();
		String targetPath = "/".equals(destPath) ? "/" + nodeName : destPath + "/" + nodeName;
		if (session.nodeExists(targetPath)) {
			throw new IllegalArgumentException("Node already exists at destination: " + targetPath);
		}
		// Workspace.copy commits directly to the workspace (no session.save()).
		session.getWorkspace().copy(sourcePath, targetPath);
		return NodeMapper.toGraphQL(session.getNode(targetPath), astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/** {@code Mutation.lockNode(input)} — open-scoped by default so the lock persists across requests. */
	private static Object lockNode(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String path = (String) input.get("path");
		if (path == null || path.isEmpty()) {
			throw new IllegalArgumentException("path is required");
		}
		boolean isDeep = Boolean.TRUE.equals(input.get("isDeep"));
		boolean isSessionScoped = Boolean.TRUE.equals(input.get("isSessionScoped"));
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}
		Node node = session.getNode(path);
		if (!node.isNodeType("mix:lockable")) {
			node.addMixin("mix:lockable");
			session.save();
		}
		LockManager lockManager = session.getWorkspace().getLockManager();
		lockManager.lock(path, isDeep, isSessionScoped, Long.MAX_VALUE, session.getUserID());
		return NodeMapper.toGraphQL(session.getNode(path), astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/** {@code Mutation.unlockNode(input)} — returns true. */
	private static Object unlockNode(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String path = (String) input.get("path");
		if (path == null) {
			throw new IllegalArgumentException("path is required");
		}
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}
		session.getWorkspace().getLockManager().unlock(path);
		return true;
	}

	/** {@code Mutation.addMixin(input)} — adds the mixin if absent; returns the node. */
	private static Object addMixin(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String path = (String) input.get("path");
		String mixinType = (String) input.get("mixinType");
		if (path == null || mixinType == null) {
			throw new IllegalArgumentException("path and mixinType are required");
		}
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}
		Node node = session.getNode(path);
		if (!node.isNodeType(mixinType)) {
			node.addMixin(mixinType);
			session.save();
		}
		return NodeMapper.toGraphQL(node, astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/** {@code Mutation.deleteMixin(input)} — removes the mixin if present; returns the node. */
	private static Object deleteMixin(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String path = (String) input.get("path");
		String mixinType = (String) input.get("mixinType");
		if (path == null || mixinType == null) {
			throw new IllegalArgumentException("path and mixinType are required");
		}
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}
		Node node = session.getNode(path);
		if (node.isNodeType(mixinType)) {
			node.removeMixin(mixinType);
			session.save();
		}
		return NodeMapper.toGraphQL(node, astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/**
	 * {@code Mutation.setAccessControl(input)} — {@code entries} (batch) replaces the
	 * whole list; a single {@code principal}/{@code privileges}/{@code allow} upserts
	 * one entry. Returns bare-name entries (see {@code SetAccessControlResult}).
	 */
	private static Object setAccessControl(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String path = (String) input.get("path");
		if (path == null) {
			throw new IllegalArgumentException("path is required");
		}
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}
		AccessControlManager acm = session.getAccessControlManager();
		AccessControlList acl = getOrCreateAccessControlList(acm, path);
		if (input.get("entries") != null) {
			Object entriesObj = input.get("entries");
			if (!(entriesObj instanceof List)) {
				throw new IllegalArgumentException("entries must be an array");
			}
			// Replace the whole list: clear before applying the new entries.
			((org.mintjams.jcr.security.AccessControlList) acl).clear();
			for (Object entryObj : (List<?>) entriesObj) {
				if (!(entryObj instanceof Map)) {
					throw new IllegalArgumentException(
							"Each entry must be an object with principal, privileges, and optional allow fields");
				}
				@SuppressWarnings("unchecked")
				Map<String, Object> entry = (Map<String, Object>) entryObj;
				processAccessControlEntry(acm, acl, entry);
			}
		} else {
			processAccessControlEntry(acm, acl, input);
		}
		acm.setPolicy(path, acl);
		session.save();
		Map<String, Object> result = new HashMap<>();
		result.put("entries", buildAclEntriesResponse(acl));
		return result;
	}

	/** {@code Mutation.deleteAccessControl(input)} — removes the principal's entry; errors when absent. */
	private static Object deleteAccessControl(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String path = (String) input.get("path");
		String principalName = (String) input.get("principal");
		if (path == null || principalName == null) {
			throw new IllegalArgumentException("path and principal are required");
		}
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}
		AccessControlManager acm = session.getAccessControlManager();
		AccessControlList acl = null;
		for (AccessControlPolicy policy : acm.getPolicies(path)) {
			if (policy instanceof AccessControlList) {
				acl = (AccessControlList) policy;
				break;
			}
		}
		if (acl == null) {
			throw new IllegalStateException("No AccessControlList found for path: " + path);
		}
		boolean removed = false;
		for (AccessControlEntry entry : acl.getAccessControlEntries()) {
			if (entry.getPrincipal().getName().equals(principalName)) {
				acl.removeAccessControlEntry(entry);
				removed = true;
			}
		}
		if (!removed) {
			throw new IllegalArgumentException("No ACL entry found for principal: " + principalName);
		}
		acm.setPolicy(path, acl);
		session.save();
		return true;
	}

	/** {@code Mutation.addVersionControl(input)} — adds mix:versionable and creates the initial version. */
	private static Object addVersionControl(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String path = (String) input.get("path");
		if (path == null) {
			throw new IllegalArgumentException("path is required");
		}
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}
		Node node = session.getNode(path);
		if (node.isNodeType("mix:versionable")) {
			throw new IllegalArgumentException("Node is already versionable: " + path);
		}
		node.addMixin("mix:versionable");
		session.save();
		// After adding mix:versionable the node is checked out at jcr:rootVersion;
		// check in to create the initial version (v1.0).
		session.getWorkspace().getVersionManager().checkin(path);
		return NodeMapper.toGraphQL(session.getNode(path), astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/** {@code Mutation.checkin(input)} — creates a new version; node must be versionable and checked out. */
	private static Object checkin(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = versionablePath(session, inputArg(environment));
		VersionManager versionManager = session.getWorkspace().getVersionManager();
		if (!versionManager.isCheckedOut(path)) {
			throw new IllegalStateException("Node is already checked in: " + path);
		}
		return versionData(versionManager.checkin(path));
	}

	/** {@code Mutation.checkout(input)} — checks out a versionable node; returns true. */
	private static Object checkout(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = versionablePath(session, inputArg(environment));
		VersionManager versionManager = session.getWorkspace().getVersionManager();
		if (versionManager.isCheckedOut(path)) {
			throw new IllegalStateException("Node is already checked out: " + path);
		}
		versionManager.checkout(path);
		return true;
	}

	/** {@code Mutation.uncheckout(input)} — cancels a checkout, discarding changes; returns true. */
	private static Object uncheckout(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = versionablePath(session, inputArg(environment));
		VersionManager versionManager = session.getWorkspace().getVersionManager();
		if (!versionManager.isCheckedOut(path)) {
			throw new IllegalArgumentException("Node is not checked out: " + path);
		}
		((org.mintjams.jcr.version.VersionManager) versionManager).uncheckout(path);
		return true;
	}

	/** {@code Mutation.checkpoint(input)} — creates a version and keeps the node checked out. */
	private static Object checkpoint(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		String path = versionablePath(session, inputArg(environment));
		VersionManager versionManager = session.getWorkspace().getVersionManager();
		if (!versionManager.isCheckedOut(path)) {
			throw new IllegalArgumentException("Node is not checked out: " + path);
		}
		return versionData(versionManager.checkpoint(path));
	}

	/** {@code Mutation.restoreVersion(input)} — restores a versionable node to a named version. */
	private static Object restoreVersion(DataFetchingEnvironment environment) throws Exception {
		Session session = session(environment);
		Map<String, Object> input = inputArg(environment);
		String path = (String) input.get("path");
		String versionName = (String) input.get("versionName");
		if (path == null || versionName == null) {
			throw new IllegalArgumentException("path and versionName are required");
		}
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}
		Node node = session.getNode(path);
		if (!node.isNodeType("mix:versionable")) {
			throw new IllegalArgumentException("Node is not versionable: " + path);
		}
		VersionManager versionManager = session.getWorkspace().getVersionManager();
		if (!versionManager.isCheckedOut(path)) {
			versionManager.checkout(path);
		}
		// removeExisting=true allows restore even with local changes present.
		versionManager.restore(path, versionName, true);
		session.save();
		return NodeMapper.toGraphQL(session.getNode(path), astSelection(environment.getSelectionSet()),
				new PrincipalDisplayNameResolver(session));
	}

	/** Validates {@code input.path} points to an existing mix:versionable node; returns the path. */
	private static String versionablePath(Session session, Map<String, Object> input) throws RepositoryException {
		String path = (String) input.get("path");
		if (path == null) {
			throw new IllegalArgumentException("path is required");
		}
		if (!session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}
		if (!session.getNode(path).isNodeType("mix:versionable")) {
			throw new IllegalArgumentException("Node is not versionable: " + path);
		}
		return path;
	}

	/** Builds the {@code Version} payload ({@code name} + ISO-8601 {@code created}). */
	private static Map<String, Object> versionData(Version version) throws RepositoryException {
		Map<String, Object> data = new HashMap<>();
		data.put("name", version.getName());
		if (version.hasProperty("jcr:created")) {
			data.put("created", formatDate(version.getProperty("jcr:created").getDate()));
		}
		return data;
	}

	/** Mirrors {@code MutationExecutor.getOrCreateAccessControlList}. */
	private static AccessControlList getOrCreateAccessControlList(AccessControlManager acm, String path)
			throws Exception {
		AccessControlList acl = null;
		for (AccessControlPolicy policy : acm.getPolicies(path)) {
			if (policy instanceof AccessControlList) {
				acl = (AccessControlList) policy;
				break;
			}
		}
		if (acl == null) {
			AccessControlPolicyIterator applicablePolicies = acm.getApplicablePolicies(path);
			while (applicablePolicies.hasNext()) {
				AccessControlPolicy applicablePolicy = applicablePolicies.nextAccessControlPolicy();
				if (applicablePolicy instanceof AccessControlList) {
					acl = (AccessControlList) applicablePolicy;
					break;
				}
			}
		}
		if (acl == null) {
			throw new IllegalStateException("No AccessControlList available for path: " + path);
		}
		return acl;
	}

	/** Mirrors {@code MutationExecutor.processAccessControlEntry} (upsert one principal's entry). */
	private static void processAccessControlEntry(AccessControlManager acm, AccessControlList acl,
			Map<String, Object> entryData) throws Exception {
		final String principalName = (String) entryData.get("principal");
		Object privilegesObj = entryData.get("privileges");
		boolean allow = !Boolean.FALSE.equals(entryData.get("allow"));
		if (principalName == null || privilegesObj == null) {
			throw new IllegalArgumentException("principal and privileges are required for each entry");
		}
		List<String> privilegeNames = new ArrayList<>();
		if (privilegesObj instanceof List) {
			for (Object item : (List<?>) privilegesObj) {
				privilegeNames.add(item.toString());
			}
		} else if (privilegesObj instanceof String) {
			privilegeNames.add(privilegesObj.toString());
		} else {
			throw new IllegalArgumentException("privileges must be an array or string");
		}
		java.security.Principal principal = new java.security.Principal() {
			@Override
			public String getName() {
				return principalName;
			}
		};
		Privilege[] privileges = new Privilege[privilegeNames.size()];
		for (int i = 0; i < privilegeNames.size(); i++) {
			privileges[i] = acm.privilegeFromName(privilegeNames.get(i));
		}
		// Remove any existing entry for this principal, then add the new one.
		for (AccessControlEntry entry : acl.getAccessControlEntries()) {
			if (entry.getPrincipal().getName().equals(principalName)) {
				acl.removeAccessControlEntry(entry);
			}
		}
		if (acl instanceof org.mintjams.jcr.security.AccessControlList) {
			((org.mintjams.jcr.security.AccessControlList) acl).addAccessControlEntry(principal, allow, privileges);
		} else {
			// Standard JCR ACL has no allow/deny distinction.
			acl.addAccessControlEntry(principal, privileges);
		}
	}

	/** Mirrors {@code MutationExecutor.buildAccessControlEntriesResponse} (bare-name principals). */
	private static List<Map<String, Object>> buildAclEntriesResponse(AccessControlList acl) throws Exception {
		List<Map<String, Object>> entries = new ArrayList<>();
		for (AccessControlEntry entry : acl.getAccessControlEntries()) {
			Map<String, Object> entryMap = new HashMap<>();
			entryMap.put("principal", entry.getPrincipal().getName());
			List<String> privList = new ArrayList<>();
			for (Privilege priv : entry.getPrivileges()) {
				privList.add(priv.getName());
			}
			entryMap.put("privileges", privList);
			entryMap.put("allow", (entry instanceof org.mintjams.jcr.security.AccessControlEntry)
					? ((org.mintjams.jcr.security.AccessControlEntry) entry).isAllow()
					: true);
			entries.add(entryMap);
		}
		return entries;
	}

	// ---- mutations (async jobs — Phase 2e) ---------------------------------
	// Lifecycle drivers for background jobs (delete / download-archive /
	// import-archive). Mirror MutationExecutor: job records are written under a
	// privileged management session (openServiceSession running AS the caller, so
	// audit identity is preserved), NOT the caller's request session. The work
	// itself runs on a JobManager worker; progress is delivered via the
	// jobProgress(jobId) subscription (Phase 3). All return a JobRef map.

	/** Opens a privileged management session running as the caller (mirrors openManagementSession). */
	private static Session managementSession(DataFetchingEnvironment environment) throws RepositoryException {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		return context.openServiceSession(context.getCallerSession().getUserID());
	}

	private static Map<String, Object> jobRef(String jobId, String status, Long itemsAccepted, Long itemsTotal) {
		Map<String, Object> data = new HashMap<>();
		data.put("jobId", jobId);
		data.put("status", status);
		if (itemsAccepted != null) {
			data.put("itemsAccepted", itemsAccepted);
		}
		if (itemsTotal != null) {
			data.put("itemsTotal", itemsTotal);
		}
		return data;
	}

	/** Shared init for all three job kinds — creates the job node in INIT. */
	private static Object initJob(DataFetchingEnvironment environment, String jobType) throws Exception {
		Map<String, Object> input = inputArg(environment);
		int priority = (input.get("priority") instanceof Number) ? ((Number) input.get("priority")).intValue() : 0;
		String jobId = JobNodes.newJobId();
		Session mgmt = managementSession(environment);
		try {
			JobNodes.createJobNode(mgmt, jobId, jobType, mgmt.getUserID(), priority);
			mgmt.save();
		} catch (Exception ex) {
			try { mgmt.refresh(false); } catch (Throwable ignore) {}
			throw ex;
		} finally {
			try { mgmt.logout(); } catch (Throwable ignore) {}
		}
		return jobRef(jobId, JobStatus.INIT.toExternalString(), null, null);
	}

	/** Shared append for deleteNodes/downloadArchive — appends up to 100 paths while still INIT. */
	private static Object appendPathsJob(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> input = inputArg(environment);
		String jobId = (String) input.get("jobId");
		Object pathsObj = input.get("paths");
		if (jobId == null || jobId.trim().isEmpty()) {
			throw new IllegalArgumentException("jobId is required");
		}
		if (!(pathsObj instanceof List)) {
			throw new IllegalArgumentException("paths is required");
		}
		List<?> rawPaths = (List<?>) pathsObj;
		if (rawPaths.size() > 100) {
			throw new IllegalArgumentException("paths must contain at most 100 entries per call");
		}
		List<String> paths = new ArrayList<>();
		for (Object o : rawPaths) {
			if (o == null) {
				continue;
			}
			String s = String.valueOf(o).trim();
			if (!s.isEmpty()) {
				paths.add(s);
			}
		}
		Session mgmt = managementSession(environment);
		JobStatus current;
		int accepted;
		try {
			Node jobNode = JobNodes.getJobNode(mgmt, jobId);
			if (jobNode == null) {
				throw new IllegalArgumentException("Unknown jobId: " + jobId);
			}
			Node content = JobNodes.getContent(jobNode);
			current = JobNodes.getStatus(content);
			if (current != JobStatus.INIT) {
				throw new IllegalStateException("Cannot append to job in status: " + current);
			}
			accepted = JobNodes.appendPaths(jobNode, paths);
			mgmt.save();
		} catch (Exception ex) {
			try { mgmt.refresh(false); } catch (Throwable ignore) {}
			throw ex;
		} finally {
			try { mgmt.logout(); } catch (Throwable ignore) {}
		}
		return jobRef(jobId, current.toExternalString(), (long) accepted, null);
	}

	/** Shared abort for all three job kinds. */
	private static Object abortJobMutation(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> input = inputArg(environment);
		String jobId = (String) input.get("jobId");
		if (jobId == null || jobId.trim().isEmpty()) {
			throw new IllegalArgumentException("jobId is required");
		}
		return jobRef(jobId, abortJob(environment, jobId), null, null);
	}

	private static Object initDeleteNodes(DataFetchingEnvironment environment) throws Exception {
		return initJob(environment, DeleteJob.TYPE);
	}

	private static Object appendDeleteNodes(DataFetchingEnvironment environment) throws Exception {
		return appendPathsJob(environment);
	}

	/** Queues a deletion job and submits it to the JobManager. */
	private static Object startDeleteNodes(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> input = inputArg(environment);
		String jobId = (String) input.get("jobId");
		if (jobId == null || jobId.trim().isEmpty()) {
			throw new IllegalArgumentException("jobId is required");
		}
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		String workspaceName = context.getWorkspaceName();
		String userId = context.getCallerSession().getUserID();
		long itemsTotal;
		long priority;
		Session mgmt = managementSession(environment);
		try {
			Node content = startableJobContent(mgmt, jobId);
			itemsTotal = JobNodes.getLong(content, JobNodes.PROP_ITEMS_TOTAL, 0L);
			if (itemsTotal <= 0) {
				throw new IllegalStateException("Cannot start job with no paths registered");
			}
			priority = JobNodes.getLong(content, JobNodes.PROP_JOB_PRIORITY, 0L);
			JobNodes.setNodeId(mgmt, content);
			JobNodes.setStatus(content, JobStatus.QUEUED);
			mgmt.save();
		} catch (Exception ex) {
			try { mgmt.refresh(false); } catch (Throwable ignore) {}
			throw ex;
		} finally {
			try { mgmt.logout(); } catch (Throwable ignore) {}
		}
		CmsService.getJobManager().submit(new DeleteJob(jobId, workspaceName, userId, (int) priority));
		return jobRef(jobId, JobStatus.QUEUED.toExternalString(), null, itemsTotal);
	}

	private static Object abortDeleteNodes(DataFetchingEnvironment environment) throws Exception {
		return abortJobMutation(environment);
	}

	private static Object initDownloadArchive(DataFetchingEnvironment environment) throws Exception {
		return initJob(environment, ArchiveJob.TYPE);
	}

	private static Object appendDownloadArchive(DataFetchingEnvironment environment) throws Exception {
		return appendPathsJob(environment);
	}

	/** Records the archive file name + flags, queues the job and submits it. */
	private static Object startDownloadArchive(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> input = inputArg(environment);
		String jobId = (String) input.get("jobId");
		if (jobId == null || jobId.trim().isEmpty()) {
			throw new IllegalArgumentException("jobId is required");
		}
		String filename = sanitizeArchiveFilename((String) input.get("filename"));
		// Default true: a download doubles as a re-importable export unless opted out.
		boolean includeMetadata = !Boolean.FALSE.equals(input.get("includeMetadata"));
		boolean includeAcl = Boolean.TRUE.equals(input.get("includeAcl"));
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		String workspaceName = context.getWorkspaceName();
		String userId = context.getCallerSession().getUserID();
		long itemsTotal;
		long priority;
		Session mgmt = managementSession(environment);
		try {
			Node content = startableJobContent(mgmt, jobId);
			itemsTotal = JobNodes.getLong(content, JobNodes.PROP_ITEMS_TOTAL, 0L);
			if (itemsTotal <= 0) {
				throw new IllegalStateException("Cannot start job with no paths registered");
			}
			priority = JobNodes.getLong(content, JobNodes.PROP_JOB_PRIORITY, 0L);
			content.setProperty(JobNodes.PROP_ARCHIVE_FILENAME, filename);
			content.setProperty(JobNodes.PROP_INCLUDE_METADATA, includeMetadata);
			content.setProperty(JobNodes.PROP_INCLUDE_ACL, includeAcl);
			JobNodes.setNodeId(mgmt, content);
			JobNodes.setStatus(content, JobStatus.QUEUED);
			mgmt.save();
		} catch (Exception ex) {
			try { mgmt.refresh(false); } catch (Throwable ignore) {}
			throw ex;
		} finally {
			try { mgmt.logout(); } catch (Throwable ignore) {}
		}
		CmsService.getJobManager().submit(new ArchiveJob(jobId, workspaceName, userId, (int) priority));
		return jobRef(jobId, JobStatus.QUEUED.toExternalString(), null, itemsTotal);
	}

	private static Object abortDownloadArchive(DataFetchingEnvironment environment) throws Exception {
		return abortJobMutation(environment);
	}

	private static Object initImportArchive(DataFetchingEnvironment environment) throws Exception {
		return initJob(environment, ImportArchiveJob.TYPE);
	}

	/** Relocates the uploaded ZIP next to the job record, records options, queues + submits. */
	private static Object startImportArchive(DataFetchingEnvironment environment) throws Exception {
		Map<String, Object> input = inputArg(environment);
		String jobId = (String) input.get("jobId");
		if (jobId == null || jobId.trim().isEmpty()) {
			throw new IllegalArgumentException("jobId is required");
		}
		String archivePath = (String) input.get("archivePath");
		String destinationPath = (String) input.get("destinationPath");
		if (archivePath == null || archivePath.trim().isEmpty()) {
			throw new IllegalArgumentException("archivePath is required");
		}
		if (destinationPath == null || destinationPath.trim().isEmpty()) {
			throw new IllegalArgumentException("destinationPath is required");
		}
		int uuidBehavior = intOr(input.get("uuidBehavior"), 0);
		int pathBehavior = intOr(input.get("pathBehavior"), 0);
		String filename = stringOr(input.get("filename"), "archive.zip");
		boolean importAcl = Boolean.TRUE.equals(input.get("importAcl"));
		// Default true: preserve archived timestamps unless explicitly opted out.
		boolean preserveTimestamps = !Boolean.FALSE.equals(input.get("preserveTimestamps"));
		boolean dryRun = Boolean.TRUE.equals(input.get("dryRun"));
		String reportLocale = stringOr(input.get("locale"), "");
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		String workspaceName = context.getWorkspaceName();
		String userId = context.getCallerSession().getUserID();
		long priority;
		Session mgmt = managementSession(environment);
		try {
			Node content = startableJobContent(mgmt, jobId);
			priority = JobNodes.getLong(content, JobNodes.PROP_JOB_PRIORITY, 0L);
			// Move the uploaded archive next to the job record (privileged), so it
			// does not linger in the user's content tree. The job reads it there.
			String effectiveArchivePath = archivePath;
			String siblingPath = JobNodes.archiveNodePath(jobId);
			if (!archivePath.equals(siblingPath) && mgmt.nodeExists(archivePath)) {
				if (mgmt.nodeExists(siblingPath)) {
					mgmt.getNode(siblingPath).remove();
					mgmt.save();
				}
				mgmt.getWorkspace().move(archivePath, siblingPath);
				effectiveArchivePath = siblingPath;
			}
			content.setProperty(JobNodes.PROP_ARCHIVE_PATH, effectiveArchivePath);
			content.setProperty(JobNodes.PROP_IMPORT_FILENAME, filename);
			content.setProperty(JobNodes.PROP_DEST_PATH, destinationPath);
			content.setProperty(JobNodes.PROP_UUID_BEHAVIOR, (long) uuidBehavior);
			content.setProperty(JobNodes.PROP_PATH_BEHAVIOR, (long) pathBehavior);
			content.setProperty(JobNodes.PROP_IMPORT_ACL, importAcl);
			content.setProperty(JobNodes.PROP_PRESERVE_TIMESTAMPS, preserveTimestamps);
			content.setProperty(JobNodes.PROP_DRY_RUN, dryRun);
			content.setProperty(JobNodes.PROP_REPORT_LOCALE, reportLocale);
			JobNodes.setNodeId(mgmt, content);
			JobNodes.setStatus(content, JobStatus.QUEUED);
			mgmt.save();
		} catch (Exception ex) {
			try { mgmt.refresh(false); } catch (Throwable ignore) {}
			throw ex;
		} finally {
			try { mgmt.logout(); } catch (Throwable ignore) {}
		}
		CmsService.getJobManager().submit(new ImportArchiveJob(jobId, workspaceName, userId, (int) priority));
		return jobRef(jobId, JobStatus.QUEUED.toExternalString(), null, null);
	}

	private static Object abortImportArchive(DataFetchingEnvironment environment) throws Exception {
		return abortJobMutation(environment);
	}

	// ---- subscriptions (Phase 3) -------------------------------------------
	// Each subscription field returns a Publisher bridged from the workspace
	// CmsEvent feed (see CmsEventPublisher). The legacy SSE transport adapts each
	// emitted payload into the {subscription, data} envelope; graphql-java's
	// SubscriptionExecutionStrategy maps it through the field's selection set.

	/**
	 * {@code Subscription.jobProgress(jobId)} — emits a fresh progress snapshot of
	 * the job each time its record node is updated by the worker. The filter is the
	 * job-path predicate (same as the handmade engine); the payload is read from the
	 * job's {@code jcr:content} via a privileged session (a job is internal plumbing
	 * the subscriber owns by holding the opaque jobId), mirroring
	 * {@code GraphQLStreamHandler.sendJobProgressEvent}.
	 */
	private static Object jobProgress(DataFetchingEnvironment environment) {
		String jobId = environment.getArgument("jobId");
		if (jobId == null || jobId.trim().isEmpty()) {
			throw new IllegalArgumentException("jobId is required");
		}
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		return new CmsEventPublisher(workspaceName,
				event -> JobNodes.isJobPath(event.getPath(), jobId),
				event -> buildJobProgressPayload(workspaceName, jobId));
	}

	/** Reads the current job-progress snapshot (mirrors the handmade jobProgress payload). */
	private static Map<String, Object> buildJobProgressPayload(String workspaceName, String jobId) throws Exception {
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), workspaceName);
		try {
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("jobId", jobId);
			Node jobNode = JobNodes.getJobNode(session, jobId);
			if (jobNode != null) {
				Node content = JobNodes.getContent(jobNode);
				data.put("status", JobNodes.getString(content, JobNodes.PROP_JOB_STATUS, null));
				data.put("itemsTotal", JobNodes.getLong(content, JobNodes.PROP_ITEMS_TOTAL, 0L));
				data.put("itemsProcessed", JobNodes.getLong(content, JobNodes.PROP_ITEMS_PROCESSED, 0L));
				// Leaf counter: job-type-specific (delete/archive/import).
				if (content.hasProperty(JobNodes.PROP_ITEMS_DELETED)) {
					data.put("itemsDeleted", JobNodes.getLong(content, JobNodes.PROP_ITEMS_DELETED, 0L));
				}
				if (content.hasProperty(JobNodes.PROP_ITEMS_ARCHIVED)) {
					data.put("itemsArchived", JobNodes.getLong(content, JobNodes.PROP_ITEMS_ARCHIVED, 0L));
				}
				if (content.hasProperty(JobNodes.PROP_ITEMS_IMPORTED)) {
					data.put("itemsImported", JobNodes.getLong(content, JobNodes.PROP_ITEMS_IMPORTED, 0L));
				}
				// Import per-file outcome counts (the four sum to the archive's file count).
				if (content.hasProperty(JobNodes.PROP_ITEMS_NEW)) {
					data.put("itemsNew", JobNodes.getLong(content, JobNodes.PROP_ITEMS_NEW, 0L));
					data.put("itemsOverwritten", JobNodes.getLong(content, JobNodes.PROP_ITEMS_OVERWRITTEN, 0L));
					data.put("itemsSkipped", JobNodes.getLong(content, JobNodes.PROP_ITEMS_SKIPPED, 0L));
					data.put("itemsError", JobNodes.getLong(content, JobNodes.PROP_ITEMS_ERROR, 0L));
				}
				if (content.hasProperty(JobNodes.PROP_ERROR_SAMPLES)) {
					List<String> samples = new ArrayList<>();
					for (Value v : content.getProperty(JobNodes.PROP_ERROR_SAMPLES).getValues()) {
						samples.add(v.getString());
					}
					data.put("errorSamples", samples);
				}
				// Import dry-run verdict (terminal event of a dry run only).
				if (content.hasProperty(JobNodes.PROP_DRY_RUN_HAS_ERRORS)) {
					data.put("dryRunHasErrors", JobNodes.getBoolean(content, JobNodes.PROP_DRY_RUN_HAS_ERRORS, false));
					data.put("dryRunNodeCount", JobNodes.getLong(content, JobNodes.PROP_DRY_RUN_NODE_COUNT, 0L));
					data.put("dryRunBinaryCount", JobNodes.getLong(content, JobNodes.PROP_DRY_RUN_BINARY_COUNT, 0L));
					String dryRunDetail = JobNodes.getString(content, JobNodes.PROP_DRY_RUN_DETAIL, null);
					if (dryRunDetail != null) {
						data.put("dryRunDetail", dryRunDetail);
					}
				}
				String currentPath = JobNodes.getString(content, JobNodes.PROP_CURRENT_PATH, null);
				if (currentPath != null) {
					data.put("currentPath", currentPath);
				}
				String errorMessage = JobNodes.getString(content, JobNodes.PROP_ERROR_MESSAGE, null);
				if (errorMessage != null) {
					data.put("errorMessage", errorMessage);
				}
				String phase = JobNodes.getString(content, JobNodes.PROP_PHASE, null);
				if (phase != null) {
					data.put("phase", phase);
				}
				String targetWorkspace = JobNodes.getString(content, JobNodes.PROP_TARGET_WORKSPACE, null);
				if (targetWorkspace != null) {
					data.put("targetWorkspace", targetWorkspace);
				}
				String downloadUrl = JobNodes.getString(content, JobNodes.PROP_DOWNLOAD_URL, null);
				if (downloadUrl != null) {
					data.put("downloadUrl", downloadUrl);
				}
			}
			data.put("timestamp", Instant.now().toString());
			return data;
		} finally {
			try {
				session.logout();
			} catch (Throwable ignore) {}
		}
	}

	/**
	 * {@code Subscription.nodeChanged(path, deep)} — JCR node changes under a path.
	 * The flat payload never carries node content; it is a "something changed here,
	 * re-check" signal the client re-queries under its own session (where JCR ACLs
	 * apply) to decide what to show, and is what lets the Webtop auto-remove an item
	 * the moment it becomes unreadable.
	 *
	 * <p>Its <em>metadata</em> (path/name/type), however, must itself respect ACLs:
	 * a subscriber must not learn the path, name or type of a node it cannot read.
	 * So the payload is gated by {@link #buildNodeChangedPayload} on what the
	 * subscriber may read — readable nodes get the full payload; everything else
	 * (deletes, read-access revocations, moves out of view) collapses to a path-free
	 * {@code DELETED} drop signal keyed only by the opaque identifier.
	 */
	private static Object nodeChanged(DataFetchingEnvironment environment) {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		String workspaceName = context.getWorkspaceName();
		String pathArg = environment.getArgument("path");
		String watchPath = (pathArg != null) ? pathArg : "/";
		boolean deep = Boolean.TRUE.equals(environment.getArgument("deep"));
		// Captured at subscribe time on the request thread; the mapper runs later on a
		// delivery thread, so only this immutable id crosses the boundary.
		String subscriberId = context.getCallerSession().getUserID();
		return new CmsEventPublisher(workspaceName,
				event -> matchesNodePath(event, watchPath, deep),
				event -> buildNodeChangedPayload(workspaceName, subscriberId, event));
	}

	/**
	 * Builds the {@code nodeChanged} payload, gated by what {@code subscriberId} may
	 * read so the metadata never discloses a node the subscriber cannot access.
	 *
	 * <ul>
	 *   <li><b>Full payload</b> — the node still exists and the subscriber can read it
	 *       (ADDED/CHANGED, or a MOVED whose destination is readable): {@code path},
	 *       {@code identifier}, {@code nodeType}, {@code sourcePath}, exactly as before.
	 *       The subscriber can read the node, so its metadata is not a leak.</li>
	 *   <li><b>Drop signal</b> ({@code DELETED}) — the node has left the subscriber's
	 *       view: removed, moved to an unreadable destination, or had its read access
	 *       revoked (a CHANGED on a now-unreadable node). It carries the opaque
	 *       {@code identifier} (the client removes the item it was showing with that id;
	 *       a no-op if it never had it) so the item auto-removes — referenceable or not —
	 *       without any path disclosure.</li>
	 * </ul>
	 *
	 * <p>Every node carries a stable JCR identifier (its item id), exposed to clients as
	 * {@code Node.id} and present whether or not the node is {@code mix:referenceable}.
	 * The drop signal's {@code identifier} is that same id, so the client always matches
	 * the item it was showing by id — there is no non-referenceable residual. A drop may
	 * <em>also</em> carry the now-vacated path (a delete, or the source of a move to an
	 * unreadable destination) when its parent is readable, as a belt-and-suspenders for
	 * path-based matching; but a node that still exists and merely became unreadable
	 * carries <em>no</em> path — emitting it would disclose exactly what this gate hides.
	 *
	 * <p>Readability is evaluated under a short-lived session impersonating the subscriber
	 * (so repository ACLs decide), mirroring the per-event session pattern of the
	 * preference/wallpaper mappers; a freshly logged-in session already reflects the
	 * just-committed state. If readability cannot be determined the event is skipped
	 * (returns {@code null}) — never emitted with a path — so an error can neither leak
	 * metadata nor spuriously remove a valid item.
	 */
	private static Map<String, Object> buildNodeChangedPayload(
			String workspaceName, String subscriberId, CmsEvent event) {
		String topic = event.getTopic();
		String suffix = (topic == null) ? null : topic.substring(topic.lastIndexOf('/') + 1);
		String eventType = toNodeEventType(suffix);
		String identifier = event.getIdentifier();
		String path = event.getPath();
		String sourcePath = event.getSourcePath();
		boolean removed = "DELETED".equals(eventType);

		Session probe = null;
		try {
			probe = CmsService.getRepository().login(new ServiceUserCredentials(subscriberId), workspaceName);

			// Full payload only when the node still exists AND the subscriber can read it.
			if (!removed && path != null && nodeReadableQuietly(probe, path)) {
				Map<String, Object> data = new LinkedHashMap<>();
				data.put("eventType", eventType);
				data.put("path", path);
				data.put("timestamp", Instant.now().toString());
				data.put("userId", event.getUserID());
				if (identifier != null) {
					data.put("identifier", identifier);
				}
				if (event.getType() != null) {
					data.put("nodeType", event.getType());
				}
				if (sourcePath != null) {
					data.put("sourcePath", sourcePath);
				}
				return data;
			}

			// Drop signal. The vacated old path may be sent only when its parent is
			// readable: REMOVED leaves event.getPath(); a move to an unreadable dest leaves
			// its sourcePath. A still-existing-but-unreadable node has no vacated path.
			String oldPath = removed ? path : sourcePath;
			String dropPath = (oldPath != null && parentReadableQuietly(probe, oldPath)) ? oldPath : null;

			if (identifier == null && dropPath == null) {
				return null;
			}
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("eventType", "DELETED");
			if (dropPath != null) {
				data.put("path", dropPath);
			}
			if (identifier != null) {
				data.put("identifier", identifier);
			}
			data.put("timestamp", Instant.now().toString());
			data.put("userId", event.getUserID());
			return data;
		} catch (Throwable t) {
			// Could not determine readability — skip rather than risk a leak or a spurious
			// removal. The next event (or a manual refresh) reconciles the view.
			CmsService.getLogger(PlatformWiringContributor.class)
					.debug("nodeChanged readability probe failed for " + path + "; skipping event", t);
			return null;
		} finally {
			if (probe != null) {
				try { probe.logout(); } catch (Throwable ignore) {}
			}
		}
	}

	/**
	 * Whether the subscriber can read the node at {@code path}. A node that exists
	 * but whose read access was just revoked makes {@code getNode} throw
	 * {@link javax.jcr.AccessDeniedException} (and a removed node throws
	 * {@link javax.jcr.PathNotFoundException}); both mean "not visible to this
	 * subscriber" and map to {@code false} so the caller emits a drop signal. Any
	 * other error propagates so the caller skips the event rather than spuriously
	 * dropping a valid item.
	 *
	 * <p>{@code Session.nodeExists()} cannot be used for this gate: it swallows only
	 * {@code PathNotFoundException}, so an {@code AccessDeniedException} from a
	 * revoked-but-existing node would escape and skip the event — leaving the
	 * now-unreadable item stranded in the client's list instead of auto-hiding it.
	 */
	private static boolean nodeReadableQuietly(Session session, String path) throws RepositoryException {
		try {
			session.getNode(path);
			return true;
		} catch (javax.jcr.PathNotFoundException | javax.jcr.AccessDeniedException notVisible) {
			return false;
		}
	}

	/** True if the subscriber session can read the parent of {@code path} (quietly false on error). */
	private static boolean parentReadableQuietly(Session session, String path) {
		int i = path.lastIndexOf('/');
		String parent = (i <= 0) ? "/" : path.substring(0, i);
		try {
			return session.nodeExists(parent);
		} catch (Throwable t) {
			return false;
		}
	}

	/** {@code Subscription.preferenceChanged} — the userId arg is ignored; the subscriber is the session user. */
	private static Object preferenceChanged(DataFetchingEnvironment environment) {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		String workspaceName = context.getWorkspaceName();
		String userId = context.getCallerSession().getUserID();
		String prefix = "/home/users/" + userId + "/preferences/";
		return new CmsEventPublisher(workspaceName,
				event -> event.getPath() != null && event.getPath().startsWith(prefix),
				event -> buildPreferencePayload(workspaceName, userId, prefix, event.getPath()));
	}

	/** {@code Subscription.wallpaperChanged} — the userId arg is ignored; the subscriber is the session user. */
	private static Object wallpaperChanged(DataFetchingEnvironment environment) {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		String workspaceName = context.getWorkspaceName();
		String userId = context.getCallerSession().getUserID();
		String prefix = "/home/users/" + userId + "/wallpapers/";
		return new CmsEventPublisher(workspaceName,
				event -> event.getPath() != null && event.getPath().startsWith(prefix),
				event -> {
					String relative = event.getPath().substring(prefix.length());
					String filename = relative.split("/")[0];
					if (filename.isEmpty()) {
						return null;
					}
					String topic = event.getTopic();
					String topicType = topic.substring(topic.lastIndexOf('/') + 1).toLowerCase();
					String action = "removed".equals(topicType) ? "deleted"
							: ("added".equals(topicType) ? "added" : "updated");
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("userId", userId);
					data.put("action", action);
					data.put("filename", filename);
					data.put("timestamp", Instant.now().toString());
					return data;
				});
	}

	/** {@code Subscription.avatarChanged} — the userId arg is ignored; the subscriber is the session user. */
	private static Object avatarChanged(DataFetchingEnvironment environment) {
		GraphQLExecutionContext context = GraphQLExecutionContext.from(environment);
		String workspaceName = context.getWorkspaceName();
		String userId = context.getCallerSession().getUserID();
		String avatarPath = "/home/users/" + userId + "/avatar";
		return new CmsEventPublisher(workspaceName,
				event -> {
					String p = event.getPath();
					return p != null && (p.equals(avatarPath) || p.startsWith(avatarPath + "/"));
				},
				event -> {
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("userId", userId);
					data.put("timestamp", Instant.now().toString());
					return data;
				});
	}

	/** {@code Subscription.workspaceChanged} — repository-wide signal, matched on the event topic alone. */
	private static Object workspaceChanged(DataFetchingEnvironment environment) {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		return new CmsEventPublisher(workspaceName,
				event -> CmsService.TOPIC_WORKSPACE_CHANGED.equals(event.getTopic()),
				event -> {
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("workspace", event.getWorkspaceName());
					data.put("timestamp", Instant.now().toString());
					return data;
				});
	}

	// ---- BPM subscriptions (Phase 3b) --------------------------------------
	// Bridged from the Camunda EventAdmin topics (see EventAdminBpmnParseListener /
	// BpmEventDispatcher). Task and process events carry their key fields directly
	// (taskId/taskName/assignee/processInstanceId/processDefinitionId, ...), so the
	// flat payload is built from the event without an engine query. Each event's
	// "workspace" property scopes it to the subscriber's workspace.

	// type.getName().replace('.','/') + "/*" — the dispatcher's topic convention.
	private static final String[] TASK_TOPICS = { "org/camunda/bpm/engine/task/Task/*" };
	private static final String[] PROCESS_TOPICS = { "org/camunda/bpm/engine/runtime/ProcessInstance/*" };

	/** {@code Subscription.taskAssigned(assignee, candidateGroup)} — Task/ASSIGNED, filtered by assignee. */
	private static Object taskAssigned(DataFetchingEnvironment environment) {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		String assignee = environment.getArgument("assignee");
		String candidateGroup = environment.getArgument("candidateGroup");
		return new OsgiEventPublisher(TASK_TOPICS,
				event -> bpmWorkspaceMatches(event, workspaceName)
						&& "ASSIGNED".equals(topicAction(event))
						// candidateGroup is not carried by the engine event; an
						// assignee filter applies directly, otherwise a group-only
						// subscription matches nothing (no false-positive leak).
						&& (assignee != null ? assignee.equals(event.getProperty("assignee")) : candidateGroup == null),
				PlatformWiringContributor::taskEventPayload);
	}

	/** {@code Subscription.taskCompleted(processInstanceId)} — Task/COMPLETED. */
	private static Object taskCompleted(DataFetchingEnvironment environment) {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		String processInstanceId = environment.getArgument("processInstanceId");
		return new OsgiEventPublisher(TASK_TOPICS,
				event -> bpmWorkspaceMatches(event, workspaceName)
						&& "COMPLETED".equals(topicAction(event))
						&& (processInstanceId == null
								|| processInstanceId.equals(event.getProperty("processInstanceId"))),
				PlatformWiringContributor::taskEventPayload);
	}

	/** {@code Subscription.taskUpdated(taskId)} — any Task/* lifecycle change for one task. */
	private static Object taskUpdated(DataFetchingEnvironment environment) {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		String taskId = environment.getArgument("taskId");
		return new OsgiEventPublisher(TASK_TOPICS,
				event -> bpmWorkspaceMatches(event, workspaceName)
						&& (taskId == null || taskId.equals(event.getProperty("taskId"))),
				PlatformWiringContributor::taskEventPayload);
	}

	/** {@code Subscription.processStarted(definitionKey)} — ProcessInstance/STARTED. */
	private static Object processStarted(DataFetchingEnvironment environment) {
		return processEvents(environment, "STARTED");
	}

	/** {@code Subscription.processEnded(definitionKey)} — ProcessInstance/ENDED. */
	private static Object processEnded(DataFetchingEnvironment environment) {
		return processEvents(environment, "ENDED");
	}

	private static Object processEvents(DataFetchingEnvironment environment, String action) {
		String workspaceName = GraphQLExecutionContext.from(environment).getWorkspaceName();
		String definitionKey = environment.getArgument("definitionKey");
		return new OsgiEventPublisher(PROCESS_TOPICS,
				event -> bpmWorkspaceMatches(event, workspaceName)
						&& action.equals(topicAction(event))
						&& (definitionKey == null
								|| definitionKey.equals(definitionKeyOf(event.getProperty("processDefinitionId")))),
				PlatformWiringContributor::processEventPayload);
	}

	/** The "workspace" property the BpmEventDispatcher stamps on every event. */
	private static boolean bpmWorkspaceMatches(Event event, String workspaceName) {
		return workspaceName != null && workspaceName.equals(event.getProperty("workspace"));
	}

	/** The trailing topic segment — the Camunda action (ASSIGNED/COMPLETED/STARTED/...). */
	private static String topicAction(Event event) {
		String topic = event.getTopic();
		return (topic == null) ? null : topic.substring(topic.lastIndexOf('/') + 1);
	}

	/** Camunda processDefinitionId is "{key}:{version}:{id}"; the key is the first segment. */
	private static String definitionKeyOf(Object processDefinitionId) {
		if (processDefinitionId == null) {
			return null;
		}
		String id = processDefinitionId.toString();
		int colon = id.indexOf(':');
		return (colon < 0) ? id : id.substring(0, colon);
	}

	private static Map<String, Object> taskEventPayload(Event event) {
		Map<String, Object> task = new LinkedHashMap<>();
		task.put("id", event.getProperty("taskId"));
		task.put("name", event.getProperty("taskName"));
		task.put("assignee", event.getProperty("assignee"));
		task.put("taskDefinitionKey", event.getProperty("taskDefinitionKey"));
		task.put("processInstanceId", event.getProperty("processInstanceId"));
		task.put("processDefinitionId", event.getProperty("processDefinitionId"));
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("eventType", topicAction(event));
		data.put("task", task);
		data.put("timestamp", Instant.now().toString());
		return data;
	}

	private static Map<String, Object> processEventPayload(Event event) {
		Map<String, Object> instance = new LinkedHashMap<>();
		// Keys match the (now typed) ProcessInstance fields: the type exposes
		// definitionId, not the Camunda-internal processDefinitionId.
		instance.put("id", event.getProperty("processInstanceId"));
		instance.put("definitionId", event.getProperty("processDefinitionId"));
		instance.put("definitionKey", definitionKeyOf(event.getProperty("processDefinitionId")));
		instance.put("businessKey", event.getProperty("businessKey"));
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("eventType", topicAction(event));
		data.put("processInstance", instance);
		data.put("timestamp", Instant.now().toString());
		return data;
	}

	/** Reads a preference category's current (non-jcr) string properties (mirrors the handmade payload). */
	private static Map<String, Object> buildPreferencePayload(String workspaceName, String userId, String prefix,
			String eventPath) throws Exception {
		String relative = eventPath.substring(prefix.length());
		String category = relative.split("/")[0];
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), workspaceName);
		try {
			Map<String, Object> values = new LinkedHashMap<>();
			String contentPath = prefix + category + "/jcr:content";
			if (session.nodeExists(contentPath)) {
				Node contentNode = session.getNode(contentPath);
				for (PropertyIterator props = contentNode.getProperties(); props.hasNext();) {
					Property prop = props.nextProperty();
					String name = prop.getName();
					if (name.startsWith("jcr:")) {
						continue;
					}
					if (!prop.isMultiple() && prop.getType() == PropertyType.STRING) {
						values.put(name, prop.getString());
					}
				}
			}
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("category", category);
			data.put("data", values);
			data.put("timestamp", Instant.now().toString());
			data.put("userId", userId);
			return data;
		} finally {
			try {
				session.logout();
			} catch (Throwable ignore) {}
		}
	}

	/** nodeChanged path predicate (mirrors SubscriptionMatcher.matchesNodeChanged: dest or source path). */
	private static boolean matchesNodePath(CmsEvent event, String watchPath, boolean deep) {
		String eventPath = event.getPath();
		if (eventPath != null && matchesPath(eventPath, watchPath, deep)) {
			return true;
		}
		String sourcePath = event.getSourcePath();
		return sourcePath != null && matchesPath(sourcePath, watchPath, deep);
	}

	private static boolean matchesPath(String eventPath, String watchPath, boolean deep) {
		if (eventPath.equals(watchPath)) {
			return true;
		}
		if (deep) {
			return eventPath.startsWith(watchPath + "/");
		}
		return parentPath(eventPath).equals(watchPath);
	}

	private static String parentPath(String path) {
		if (path == null || path.equals("/")) {
			return "/";
		}
		int slash = path.lastIndexOf('/');
		return slash <= 0 ? "/" : path.substring(0, slash);
	}

	/** Maps a JCR node event topic suffix to the NodeChangeEvent eventType (mirrors the handmade mapping). */
	private static String toNodeEventType(String topicSuffix) {
		if (topicSuffix == null) {
			return null;
		}
		switch (topicSuffix) {
		case "ADDED":
			return "CREATED";
		case "REMOVED":
			return "DELETED";
		case "CHANGED":
			return "MODIFIED";
		default:
			return topicSuffix;
		}
	}

	/** Resolves a job's content node and asserts it is still in INIT (startable). */
	private static Node startableJobContent(Session mgmt, String jobId) throws RepositoryException {
		Node jobNode = JobNodes.getJobNode(mgmt, jobId);
		if (jobNode == null) {
			throw new IllegalArgumentException("Unknown jobId: " + jobId);
		}
		Node content = JobNodes.getContent(jobNode);
		JobStatus current = JobNodes.getStatus(content);
		if (current != JobStatus.INIT) {
			throw new IllegalStateException("Cannot start job in status: " + current);
		}
		return content;
	}

	/**
	 * Signals the named job to stop and returns the resulting external status
	 * (mirrors MutationExecutor.abortJob): a running worker is asked to finalise
	 * (status → ABORTING); a job that never started executing is marked ABORTED.
	 * Terminal jobs are left untouched.
	 */
	private static String abortJob(DataFetchingEnvironment environment, String jobId) throws Exception {
		Session mgmt = managementSession(environment);
		try {
			Node jobNode = JobNodes.getJobNode(mgmt, jobId);
			if (jobNode == null) {
				throw new IllegalArgumentException("Unknown jobId: " + jobId);
			}
			Node content = JobNodes.getContent(jobNode);
			JobStatus current = JobNodes.getStatus(content);
			if (current == null || current.isTerminal()) {
				return current != null ? current.toExternalString() : "unknown";
			}
			boolean signalled = CmsService.getJobManager().abort(jobId);
			if (signalled) {
				// Worker is running here — let it finalise; reflect the shutdown now.
				JobNodes.setStatus(content, JobStatus.ABORTING);
				mgmt.save();
				return JobStatus.ABORTING.toExternalString();
			}
			if (current == JobStatus.RUNNING || current == JobStatus.QUEUED) {
				// Running on another cluster node: mark ABORTING; that worker (or
				// pickup) observes the persisted status and finalises. Never finalise
				// from here — the work is genuinely still in progress elsewhere.
				JobNodes.setStatus(content, JobStatus.ABORTING);
				mgmt.save();
				return JobStatus.ABORTING.toExternalString();
			}
			// Never submitted anywhere (still INIT). Finalise here.
			JobNodes.setStatus(content, JobStatus.ABORTED);
			content.setProperty(JobNodes.PROP_FINISHED_AT, Calendar.getInstance());
			mgmt.save();
			return JobStatus.ABORTED.toExternalString();
		} catch (Exception ex) {
			try { mgmt.refresh(false); } catch (Throwable ignore) {}
			throw ex;
		} finally {
			try { mgmt.logout(); } catch (Throwable ignore) {}
		}
	}

	private static String stringOr(Object value, String fallback) {
		if (value == null) {
			return fallback;
		}
		String s = String.valueOf(value).trim();
		return s.isEmpty() ? fallback : s;
	}

	private static int intOr(Object value, int fallback) {
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		if (value != null) {
			try {
				return Integer.parseInt(String.valueOf(value).trim());
			} catch (NumberFormatException ignore) {}
		}
		return fallback;
	}

	/**
	 * Reduces a client-supplied archive file name to a safe single path segment
	 * (mirrors MutationExecutor.sanitizeArchiveFilename): strips separators and
	 * control characters and guarantees a {@code .zip} suffix.
	 */
	private static String sanitizeArchiveFilename(String filename) {
		String name = (filename == null) ? "" : filename.trim();
		name = name.replaceAll("[\\\\/\\r\\n\\t\\x00]", "_");
		if (name.isEmpty()) {
			name = "archive.zip";
		} else if (!name.toLowerCase().endsWith(".zip")) {
			name = name + ".zip";
		}
		return name;
	}

	/**
	 * Applies a single {@link PropertyValue} to {@code node} (mirrors
	 * {@code MutationExecutor.setPropertyValue}): all scalar/array types, inline
	 * {@code binaryValue} (Base64), and upload-backed binary via
	 * {@code binaryUploadId} (single), {@code binaryArrayUploadIds} (array), and
	 * {@code binaryArrayItems} (mixed array of {@code keepIndex}/{@code uploadId}).
	 * Consumed upload IDs are deleted after their bytes are streamed in.
	 */
	private static void applyPropertyValue(Session session, Node node, String name, PropertyValue propValue)
			throws Exception {
		int type = propValue.getPropertyType();
		Object value = propValue.getValue();
		ValueFactory vf = session.getValueFactory();

		if (propValue.isMultiple()) {
			if (!(value instanceof List)) {
				throw new IllegalArgumentException("Array value must be a List");
			}
			List<?> list = (List<?>) value;
			if (type == PropertyType.REFERENCE || type == PropertyType.WEAKREFERENCE) {
				Value[] values = new Value[list.size()];
				for (int i = 0; i < list.size(); i++) {
					Node target = session.getNodeByIdentifier(list.get(i).toString());
					values[i] = vf.createValue(target, type == PropertyType.WEAKREFERENCE);
				}
				node.setProperty(name, values);
			} else if (type == PropertyType.BINARY && propValue.isBinaryMixedArray()) {
				// Mixed binary array: each item is either {keepIndex: N} (preserve an
				// existing value) or {uploadId: "..."} (a completed chunk upload). A
				// single-valued property is exposed as a one-element array so a
				// keepIndex of 0 can preserve its content when converting to multiple.
				Value[] values = new Value[list.size()];
				MultipartUploadManager uploadManager = new MultipartUploadManager(session);
				Value[] existingValues = null;
				if (node.hasProperty(name)) {
					Property existingProp = node.getProperty(name);
					if (existingProp.isMultiple()) {
						existingValues = existingProp.getValues();
					} else {
						existingValues = new Value[] { existingProp.getValue() };
					}
				}
				for (int i = 0; i < list.size(); i++) {
					Map<?, ?> item = (Map<?, ?>) list.get(i);
					if (item.get("keepIndex") != null) {
						int keepIdx = ((Number) item.get("keepIndex")).intValue();
						if (existingValues == null || keepIdx < 0 || keepIdx >= existingValues.length) {
							throw new IllegalArgumentException(
									"keepIndex " + keepIdx + " is out of range for property " + name);
						}
						Binary existingBinary = existingValues[keepIdx].getBinary();
						try (InputStream in = existingBinary.getStream()) {
							values[i] = vf.createValue(vf.createBinary(in));
						} finally {
							existingBinary.dispose();
						}
					} else if (item.get("uploadId") != null) {
						String uploadId = item.get("uploadId").toString();
						try (InputStream in = uploadManager.openInputStream(uploadId)) {
							values[i] = vf.createValue(vf.createBinary(in));
						} finally {
							uploadManager.deleteUpload(uploadId);
						}
					} else {
						throw new IllegalArgumentException(
								"BinaryArrayItemInput must specify either keepIndex or uploadId");
					}
				}
				node.setProperty(name, values);
			} else if (type == PropertyType.BINARY) {
				// Binary array via completed chunk upload IDs.
				Value[] values = new Value[list.size()];
				MultipartUploadManager uploadManager = new MultipartUploadManager(session);
				for (int i = 0; i < list.size(); i++) {
					String uploadId = list.get(i).toString();
					try (InputStream in = uploadManager.openInputStream(uploadId)) {
						values[i] = vf.createValue(vf.createBinary(in));
					} finally {
						uploadManager.deleteUpload(uploadId);
					}
				}
				node.setProperty(name, values);
			} else {
				switch (type) {
				case PropertyType.STRING:
				case PropertyType.NAME:
				case PropertyType.PATH:
				case PropertyType.URI:
					node.setProperty(name, list.stream().map(Object::toString).toArray(String[]::new), type);
					break;
				case PropertyType.BOOLEAN: {
					Value[] values = new Value[list.size()];
					for (int i = 0; i < list.size(); i++) {
						values[i] = vf.createValue(Boolean.parseBoolean(list.get(i).toString()));
					}
					node.setProperty(name, values);
					break;
				}
				case PropertyType.LONG: {
					Value[] values = new Value[list.size()];
					for (int i = 0; i < list.size(); i++) {
						Object item = list.get(i);
						values[i] = vf.createValue(
								(item instanceof Number) ? ((Number) item).longValue() : Long.parseLong(item.toString()));
					}
					node.setProperty(name, values);
					break;
				}
				case PropertyType.DOUBLE: {
					Value[] values = new Value[list.size()];
					for (int i = 0; i < list.size(); i++) {
						Object item = list.get(i);
						values[i] = vf.createValue((item instanceof Number) ? ((Number) item).doubleValue()
								: Double.parseDouble(item.toString()));
					}
					node.setProperty(name, values);
					break;
				}
				case PropertyType.DECIMAL: {
					Value[] values = new Value[list.size()];
					for (int i = 0; i < list.size(); i++) {
						values[i] = vf.createValue(new BigDecimal(list.get(i).toString()));
					}
					node.setProperty(name, values);
					break;
				}
				case PropertyType.DATE: {
					Value[] values = new Value[list.size()];
					for (int i = 0; i < list.size(); i++) {
						values[i] = vf.createValue(parseISO8601Date(list.get(i).toString()));
					}
					node.setProperty(name, values);
					break;
				}
				default:
					throw new IllegalArgumentException("Unsupported array property type: " + propValue.getType());
				}
			}
		} else {
			if (type == PropertyType.REFERENCE || type == PropertyType.WEAKREFERENCE) {
				Node target = session.getNodeByIdentifier(value.toString());
				node.setProperty(name, vf.createValue(target, type == PropertyType.WEAKREFERENCE));
			} else if (type == PropertyType.BINARY) {
				if (propValue.isBinaryUpload()) {
					// Binary via completed chunk upload: stream the temp file, then delete it.
					String uploadId = value.toString();
					MultipartUploadManager uploadManager = new MultipartUploadManager(session);
					try (InputStream in = uploadManager.openInputStream(uploadId)) {
						node.setProperty(name, vf.createBinary(in));
					} finally {
						uploadManager.deleteUpload(uploadId);
					}
				} else {
					byte[] data = Base64.getDecoder().decode(value.toString());
					node.setProperty(name, vf.createBinary(new ByteArrayInputStream(data)));
				}
			} else if (type == PropertyType.DATE) {
				node.setProperty(name, parseISO8601Date(value.toString()));
			} else {
				switch (type) {
				case PropertyType.BOOLEAN:
					node.setProperty(name, Boolean.parseBoolean(value.toString()));
					break;
				case PropertyType.LONG:
					node.setProperty(name,
							(value instanceof Number) ? ((Number) value).longValue() : Long.parseLong(value.toString()));
					break;
				case PropertyType.DOUBLE:
					node.setProperty(name, (value instanceof Number) ? ((Number) value).doubleValue()
							: Double.parseDouble(value.toString()));
					break;
				case PropertyType.DECIMAL:
					node.setProperty(name, new BigDecimal(value.toString()));
					break;
				default:
					node.setProperty(name, value.toString(), type);
					break;
				}
			}
		}
	}

	private static Calendar parseISO8601Date(String dateString) {
		Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		cal.setTimeInMillis(OffsetDateTime.parse(dateString).toInstant().toEpochMilli());
		return cal;
	}

	private static Map<String, Object> propertyError(String propertyName, String message) {
		Map<String, Object> error = new HashMap<>();
		error.put("propertyName", propertyName);
		error.put("message", message);
		return error;
	}

	/** Mirrors MutationExecutor.ensureParentExists (recursive folder creation when createParents). */
	private static void ensureParentExists(Session session, String parentPath, boolean createParents) throws Exception {
		JcrPath jcrParentPath = JcrPath.valueOf(parentPath);
		if (jcrParentPath.isRoot()) {
			return;
		}
		if (JCRs.isFolder(JcrPath.valueOf(parentPath), session)) {
			return;
		}
		if (JCRs.exists(JcrPath.valueOf(parentPath), session)) {
			throw new IllegalArgumentException("Parent path exists but is not a folder: " + parentPath);
		}
		if (!createParents) {
			throw new IllegalArgumentException("Parent node not found: " + parentPath);
		}
		JCRs.getOrCreateFolder(JcrPath.valueOf(parentPath), session);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> inputArg(DataFetchingEnvironment environment) {
		Object input = environment.getArgument("input");
		return (input instanceof Map) ? (Map<String, Object>) input : new HashMap<>();
	}

	// ---- helpers -----------------------------------------------------------

	private static Session session(DataFetchingEnvironment environment) {
		return GraphQLExecutionContext.from(environment).getCallerSession();
	}

	private static String requireNode(Session session, String path) throws RepositoryException {
		if (path == null || !session.nodeExists(path)) {
			throw new IllegalArgumentException("Node not found: " + path);
		}
		return path;
	}

	private static int first(DataFetchingEnvironment environment) {
		Integer first = environment.getArgument("first");
		return (first != null) ? first : 20;
	}

	private static int startPosition(String after) {
		return (after != null && !after.isEmpty()) ? decodeCursor(after) + 1 : 0;
	}

	/** Walks {@code first} nodes from {@code startPosition} into Relay edges. */
	private static List<Map<String, Object>> nodeEdges(NodeIterator iterator, int startPosition, int first,
			SelectionSet nodeSelection, PrincipalDisplayNameResolver resolver) throws RepositoryException {
		if (startPosition > 0) {
			iterator.skip(startPosition);
		}
		List<Map<String, Object>> edges = new ArrayList<>();
		int position = startPosition;
		int count = 0;
		while (iterator.hasNext() && count < first) {
			edges.add(edge(NodeMapper.toGraphQL(iterator.nextNode(), nodeSelection, resolver), position));
			position++;
			count++;
		}
		return edges;
	}

	private static Map<String, Object> edge(Object node, int position) {
		Map<String, Object> edge = new HashMap<>();
		edge.put("node", node);
		edge.put("cursor", encodeCursor(position));
		return edge;
	}

	private static Map<String, Object> connection(List<Map<String, Object>> edges, boolean hasNextPage,
			boolean hasPreviousPage, long totalCount) {
		Map<String, Object> pageInfo = new HashMap<>();
		pageInfo.put("hasNextPage", hasNextPage);
		pageInfo.put("hasPreviousPage", hasPreviousPage);
		pageInfo.put("startCursor", edges.isEmpty() ? null : edges.get(0).get("cursor"));
		pageInfo.put("endCursor", edges.isEmpty() ? null : edges.get(edges.size() - 1).get("cursor"));
		Map<String, Object> connection = new HashMap<>();
		connection.put("edges", edges);
		connection.put("pageInfo", pageInfo);
		connection.put("totalCount", totalCount);
		return connection;
	}

	/**
	 * Builds an {@code ast.SelectionSet} of the immediate selected field names so
	 * {@link NodeMapper} computes only those (fields not in the schema, such as a
	 * not-yet-migrated detail, are never selected and so never computed).
	 */
	private static SelectionSet astSelection(DataFetchingFieldSelectionSet selection) {
		if (selection == null) {
			return SelectionSet.empty();
		}
		List<Field> fields = new ArrayList<>();
		for (SelectedField field : selection.getImmediateFields()) {
			fields.add(new Field(field.getName()));
		}
		return new SelectionSet(fields);
	}

	/** Extracts the {@code edges.node} sub-selection from a connection's selection. */
	private static SelectionSet connectionNodeSelection(DataFetchingFieldSelectionSet connectionSelection) {
		if (connectionSelection == null) {
			return SelectionSet.empty();
		}
		for (SelectedField edges : connectionSelection.getImmediateFields()) {
			if ("edges".equals(edges.getName()) && edges.getSelectionSet() != null) {
				for (SelectedField node : edges.getSelectionSet().getImmediateFields()) {
					if ("node".equals(node.getName())) {
						return astSelection(node.getSelectionSet());
					}
				}
			}
		}
		return SelectionSet.empty();
	}

	/** Extracts the immediate sub-selection of a named field (e.g. {@code setProperties.node}). */
	private static SelectionSet subSelection(DataFetchingFieldSelectionSet selection, String fieldName) {
		if (selection == null) {
			return SelectionSet.empty();
		}
		for (SelectedField field : selection.getImmediateFields()) {
			if (fieldName.equals(field.getName())) {
				return astSelection(field.getSelectionSet());
			}
		}
		return SelectionSet.empty();
	}

	/** Normalises a JCR query language label to the {@link Query} constant. */
	private static String normalizeLanguage(String language) {
		if (language == null || language.isEmpty()) {
			return Query.JCR_SQL2;
		}
		switch (language.toUpperCase().trim()) {
		case "XPATH":
		case "JCR-XPATH":
			return Query.XPATH;
		case "SQL2":
		case "JCR-SQL2":
		case "JCRSQL2":
			return Query.JCR_SQL2;
		case "SQL":
		case "JCR-SQL":
		case "JCRSQL":
			return Query.SQL;
		default:
			return language;
		}
	}

	private static String encodeCursor(int position) {
		return Base64.getEncoder().encodeToString(("arrayconnection:" + position).getBytes(StandardCharsets.UTF_8));
	}

	private static int decodeCursor(String cursor) {
		try {
			String decoded = new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
			if (decoded.startsWith("arrayconnection:")) {
				return Integer.parseInt(decoded.substring("arrayconnection:".length()));
			}
			return 0;
		} catch (Throwable ex) {
			return 0;
		}
	}

	private static String loadSchema() throws Exception {
		try (InputStream in = PlatformWiringContributor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Platform GraphQL schema resource not found: " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
