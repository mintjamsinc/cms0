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

package org.mintjams.rt.cms.internal.graphql.engine;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.jcr.nodetype.NodeType;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.Scripts;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import graphql.GraphQL;
import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.AsyncSerialExecutionStrategy;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.SubscriptionExecutionStrategy;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.TypeResolver;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.mintjams.rt.cms.internal.graphql.resolver.GroovyDataFetcher;
import org.mintjams.rt.cms.internal.graphql.resolver.GroovyTypeResolver;
import org.mintjams.rt.cms.internal.graphql.resolver.ResolverScript;
import org.mintjams.rt.cms.internal.graphql.type.ScalarTypes;
import org.mintjams.rt.cms.internal.graphql.wiring.SchemaContribution;
import org.mintjams.rt.cms.internal.graphql.type.ScriptCoercing;
import org.mintjams.rt.cms.internal.graphql.wiring.WiringContributor;

/**
 * Builds a {@link GraphQL} instance for a workspace by scanning its GraphQL
 * folders for SDL, resolver scripts, and {@code wiring.yml} manifests.
 *
 * <h2>Conventions</h2>
 * <ul>
 *   <li><b>Schema</b>: any {@code nt:file} whose name ends in {@code .graphqls}
 *       or {@code .graphql}. All such files (across every watched root) are
 *       merged into one schema.</li>
 *   <li><b>Resolvers (by name)</b>: a script file named
 *       {@code <Type>.<field>.<ext>} (e.g. {@code Query.products.groovy})
 *       registers a data fetcher for the {@code field} of {@code Type}.
 *       {@code <ext>} must be a registered script-engine extension.</li>
 *   <li><b>Explicit wiring</b>: a {@code wiring.yml} file maps fields to scripts
 *       explicitly, supports {@code runAs} impersonation, and — uniquely —
 *       registers a {@code typeResolver} for {@code interface}/{@code union}
 *       types. Explicit entries override the filename convention. See
 *       {@code documents/appql-dynamic-graphql.md}.</li>
 *   <li><b>Scalars</b>: every custom {@code scalar} declared in the SDL is wired
 *       automatically — a built-in coercing for well-known names
 *       ({@code Long}, {@code DateTime}, {@code JSON}, {@code Base64}) and a
 *       JSON pass-through otherwise.</li>
 * </ul>
 *
 * <p>The compiler reads all file contents with the supplied service session so
 * schema and resolver sources may be ACL-protected from end users; the compiled
 * resolvers nonetheless execute under the caller's session (or a {@code runAs}
 * identity) at request time — see {@link ResolverScript}.
 */
public final class GraphQLSchemaCompiler {

	/** GraphQL's spec-provided scalars, which must not be re-wired. */
	private static final Set<String> BUILT_IN_SCALARS = new HashSet<>(
			Arrays.asList("String", "Int", "Float", "Boolean", "ID"));

	private GraphQLSchemaCompiler() {}

	/**
	 * Compiles the schema for {@code workspaceName} from {@code roots} only
	 * (folder-based SDL, resolver scripts and {@code wiring.yml}), with no
	 * programmatic contributions (application/folder schema only). Equivalent to
	 * {@link #compile(Session, String, List, List)} with no contributors.
	 *
	 * @throws Exception if the collected SDL is invalid or a resolver references
	 *                   a type that is not defined; the caller keeps the previous
	 *                   schema in that case.
	 */
	public static GraphQL compile(Session serviceSession, String workspaceName, List<String> roots) throws Exception {
		return compile(serviceSession, workspaceName, roots, List.of());
	}

	/**
	 * Compiles with graphql-java's default resolver-exception handling (no
	 * sanitization). The unified engine instead uses the {@code exceptionHandler}
	 * overload, which sanitizes platform errors while preserving application
	 * (Groovy) resolver messages.
	 */
	public static GraphQL compile(Session serviceSession, String workspaceName, List<String> roots,
			List<WiringContributor> contributors) throws Exception {
		return compile(serviceSession, workspaceName, roots, contributors, null);
	}

	/**
	 * Compiles the schema for {@code workspaceName} from {@code roots} (folder
	 * SDL, resolver scripts and {@code wiring.yml}, read with
	 * {@code serviceSession}) <em>plus</em> any programmatic {@code contributors}
	 * — the hook by which the platform contributes built-in SDL and Java
	 * {@link DataFetcher}s / {@link TypeResolver}s / scalars (the seam for moving
	 * the handmade {@code /bin/graphql.cgi} API onto this engine). Returns
	 * {@code null} when neither the folders nor the contributors provide any SDL
	 * (the engine then stays inert and exposes nothing).
	 *
	 * <p>Precedence on conflict (same {@code Type.field}, type resolver, or scalar
	 * name): folder/script wiring overrides a contributor's, mirroring the
	 * "explicit wiring overrides convention" rule. Contributor SDL is merged
	 * before folder SDL so a workspace's files may {@code extend} contributor base
	 * types.
	 *
	 * <p>{@code exceptionHandler}, when non-null, replaces graphql-java's default
	 * resolver-exception handling on every execution strategy — the platform
	 * engine passes one to keep Java internals out of the wire response.
	 *
	 * @throws Exception if the collected SDL is invalid or a resolver references
	 *                   a type that is not defined; the caller keeps the previous
	 *                   schema in that case.
	 */
	public static GraphQL compile(Session serviceSession, String workspaceName, List<String> roots,
			List<WiringContributor> contributors, DataFetcherExceptionHandler exceptionHandler) throws Exception {
		Set<String> scriptExtensions = new HashSet<>(
				Arrays.asList(Scripts.getScriptExtensions(CmsService.getWorkspaceScriptEngineManager(workspaceName))));

		List<String> sdlSources = new ArrayList<>();
		// typeName -> (fieldName -> data fetcher) discovered by the folder scan
		Map<String, Map<String, GroovyDataFetcher>> resolvers = new LinkedHashMap<>();
		// typeName -> type resolver (interface/union)
		Map<String, GroovyTypeResolver> typeResolvers = new LinkedHashMap<>();
		// scalarName -> script-defined scalar (overrides built-in / pass-through)
		Map<String, GraphQLScalarType> scriptScalars = new LinkedHashMap<>();
		// paths of wiring.yml manifests, applied after the convention scan so
		// that explicit wiring overrides convention-named resolvers
		List<String> wiringPaths = new ArrayList<>();

		for (String root : roots) {
			Node node;
			try {
				node = serviceSession.getNode(root);
			} catch (PathNotFoundException ignore) {
				continue;
			}
			scan(node, workspaceName, scriptExtensions, sdlSources, resolvers, wiringPaths);
		}

		// Gather programmatic contributions (e.g. the built-in platform schema with
		// Java resolvers). Empty when no contributor is supplied.
		List<String> contributorSdl = new ArrayList<>();
		Map<String, Map<String, DataFetcher<?>>> contributorFetchers = new LinkedHashMap<>();
		Map<String, TypeResolver> contributorTypeResolvers = new LinkedHashMap<>();
		Map<String, GraphQLScalarType> contributorScalars = new LinkedHashMap<>();
		for (WiringContributor contributor : contributors) {
			SchemaContribution contribution = contributor.contribute(workspaceName);
			if (contribution == null) {
				continue;
			}
			contributorSdl.addAll(contribution.getSdl());
			for (Map.Entry<String, Map<String, DataFetcher<?>>> typeEntry : contribution.getDataFetchers().entrySet()) {
				contributorFetchers.computeIfAbsent(typeEntry.getKey(), k -> new LinkedHashMap<>())
						.putAll(typeEntry.getValue());
			}
			contributorTypeResolvers.putAll(contribution.getTypeResolvers());
			contributorScalars.putAll(contribution.getScalars());
		}

		if (sdlSources.isEmpty() && contributorSdl.isEmpty()) {
			return null;
		}

		for (String wiringPath : wiringPaths) {
			applyWiring(serviceSession, workspaceName, wiringPath, resolvers, typeResolvers, scriptScalars);
		}

		// Merge every SDL fragment into a single registry. Contributor (base) types
		// first, so folder SDL may `extend` them.
		SchemaParser parser = new SchemaParser();
		TypeDefinitionRegistry registry = new TypeDefinitionRegistry();
		for (String sdl : contributorSdl) {
			registry.merge(parser.parse(sdl));
		}
		for (String sdl : sdlSources) {
			registry.merge(parser.parse(sdl));
		}

		RuntimeWiring.Builder wiring = RuntimeWiring.newRuntimeWiring();

		// Wire every custom scalar the schema declares: a script-defined scalar
		// (wiring.yml) wins, then a contributor scalar, then a well-known built-in,
		// then a JSON pass-through.
		for (String scalarName : registry.scalars().keySet()) {
			if (BUILT_IN_SCALARS.contains(scalarName)) {
				continue;
			}
			GraphQLScalarType scalar = scriptScalars.get(scalarName);
			if (scalar == null) {
				scalar = contributorScalars.get(scalarName);
			}
			if (scalar == null) {
				scalar = ScalarTypes.forName(scalarName);
			}
			if (scalar == null) {
				scalar = ScalarTypes.passThrough(scalarName);
			}
			wiring.scalar(scalar);
		}

		// Combine contributor (base) and folder/script (override) fetchers and type
		// resolvers into one per-type map for wiring.
		Map<String, Map<String, DataFetcher<?>>> allFetchers = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, DataFetcher<?>>> typeEntry : contributorFetchers.entrySet()) {
			allFetchers.computeIfAbsent(typeEntry.getKey(), k -> new LinkedHashMap<>()).putAll(typeEntry.getValue());
		}
		for (Map.Entry<String, Map<String, GroovyDataFetcher>> typeEntry : resolvers.entrySet()) {
			allFetchers.computeIfAbsent(typeEntry.getKey(), k -> new LinkedHashMap<>()).putAll(typeEntry.getValue());
		}

		Map<String, TypeResolver> allTypeResolvers = new LinkedHashMap<>(contributorTypeResolvers);
		allTypeResolvers.putAll(typeResolvers);

		// Wire field data fetchers and type resolvers, skipping any whose target
		// type is not defined in the merged schema.
		Set<String> wiredTypes = new LinkedHashSet<>();
		wiredTypes.addAll(allFetchers.keySet());
		wiredTypes.addAll(allTypeResolvers.keySet());

		int resolverCount = 0;
		int typeResolverCount = 0;
		for (String typeName : wiredTypes) {
			if (!registry.getType(typeName).isPresent()) {
				CmsService.getLogger(GraphQLSchemaCompiler.class).warn(
						"Ignoring wiring for unknown GraphQL type \"" + typeName + "\" in workspace: " + workspaceName);
				continue;
			}
			final Map<String, DataFetcher<?>> fields = allFetchers.getOrDefault(typeName, Map.of());
			final TypeResolver typeResolver = allTypeResolvers.get(typeName);
			wiring.type(typeName, builder -> {
				for (Map.Entry<String, DataFetcher<?>> fieldEntry : fields.entrySet()) {
					builder.dataFetcher(fieldEntry.getKey(), fieldEntry.getValue());
				}
				if (typeResolver != null) {
					builder.typeResolver(typeResolver);
				}
				return builder;
			});
			resolverCount += fields.size();
			if (typeResolver != null) {
				typeResolverCount++;
			}
		}

		GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
		CmsService.getLogger(GraphQLSchemaCompiler.class).info("Compiled GraphQL schema for workspace \""
				+ workspaceName + "\": " + (sdlSources.size() + contributorSdl.size()) + " SDL source(s), "
				+ resolverCount + " resolver(s), " + typeResolverCount + " type resolver(s), "
				+ (scriptScalars.size() + contributorScalars.size()) + " custom scalar(s).");
		// SubscriptionExecutionStrategy makes a subscription operation's top-level
		// field return a Publisher<ExecutionResult> (graphql-java's default async
		// strategy does not); query/mutation are unaffected. Required by the
		// platform's jobProgress/* subscriptions and harmless for app schemas.
		// When an exceptionHandler is supplied it must be installed on every
		// strategy (query/mutation/subscription) — graphql-java has no single
		// builder-level default — so resolver errors are sanitized uniformly.
		GraphQL.Builder builder = GraphQL.newGraphQL(schema);
		if (exceptionHandler != null) {
			builder.queryExecutionStrategy(new AsyncExecutionStrategy(exceptionHandler))
					.mutationExecutionStrategy(new AsyncSerialExecutionStrategy(exceptionHandler))
					.subscriptionExecutionStrategy(new SubscriptionExecutionStrategy(exceptionHandler));
		} else {
			builder.subscriptionExecutionStrategy(new SubscriptionExecutionStrategy());
		}
		return builder.build();
	}

	private static void scan(Node node, String workspaceName, Set<String> scriptExtensions, List<String> sdlSources,
			Map<String, Map<String, GroovyDataFetcher>> resolvers, List<String> wiringPaths) throws Exception {
		String primaryType = node.getPrimaryNodeType().getName();

		if (primaryType.equals(NodeType.NT_FOLDER_NAME)) {
			for (NodeIterator i = node.getNodes(); i.hasNext();) {
				scan(i.nextNode(), workspaceName, scriptExtensions, sdlSources, resolvers, wiringPaths);
			}
			return;
		}

		if (!primaryType.equals(NodeType.NT_FILE_NAME)) {
			return;
		}

		String name = node.getName();
		if (name.equals("wiring.yml")) {
			wiringPaths.add(node.getPath());
			return;
		}
		if (name.endsWith(".graphqls") || name.endsWith(".graphql")) {
			sdlSources.add(readText(node));
			return;
		}

		// Resolver convention: <Type>.<field>.<ext>
		String[] parts = name.split("\\.");
		if (parts.length == 3 && scriptExtensions.contains(parts[2]) && isName(parts[0]) && isName(parts[1])) {
			String typeName = parts[0];
			String fieldName = parts[1];
			GroovyDataFetcher fetcher = new GroovyDataFetcher(workspaceName, typeName, fieldName, node.getPath(),
					parts[2], readText(node), null);
			resolvers.computeIfAbsent(typeName, k -> new LinkedHashMap<>()).put(fieldName, fetcher);
		}
	}

	/**
	 * Parses one {@code wiring.yml} and applies its {@code types} block. Field
	 * entries override any convention-named resolver for the same type/field.
	 */
	@SuppressWarnings("unchecked")
	private static void applyWiring(Session serviceSession, String workspaceName, String wiringPath,
			Map<String, Map<String, GroovyDataFetcher>> resolvers, Map<String, GroovyTypeResolver> typeResolvers,
			Map<String, GraphQLScalarType> scriptScalars) throws RepositoryException {
		Node wiringNode = serviceSession.getNode(wiringPath);
		String basePath = wiringNode.getParent().getPath();
		Map<String, Object> doc = parseYaml(wiringNode);

		Object typesObj = doc.get("types");
		if (typesObj instanceof Map) {
			for (Map.Entry<String, Object> typeEntry : ((Map<String, Object>) typesObj).entrySet()) {
				String typeName = typeEntry.getKey();
				if (!(typeEntry.getValue() instanceof Map)) {
					continue;
				}
				Map<String, Object> typeDef = (Map<String, Object>) typeEntry.getValue();

				Object fieldsObj = typeDef.get("fields");
				if (fieldsObj instanceof Map) {
					for (Map.Entry<String, Object> fieldEntry : ((Map<String, Object>) fieldsObj).entrySet()) {
						String fieldName = fieldEntry.getKey();
						String[] sr = scriptAndRunAs(fieldEntry.getValue());
						Node scriptNode = resolveScriptNode(serviceSession, basePath, sr, wiringPath,
								typeName + "." + fieldName);
						if (scriptNode == null) {
							continue;
						}
						GroovyDataFetcher fetcher = new GroovyDataFetcher(workspaceName, typeName, fieldName,
								scriptNode.getPath(), extensionOf(scriptNode.getName()), readText(scriptNode), sr[1]);
						resolvers.computeIfAbsent(typeName, k -> new LinkedHashMap<>()).put(fieldName, fetcher);
					}
				}

				Object typeResolverObj = typeDef.get("typeResolver");
				if (typeResolverObj != null) {
					String[] sr = scriptAndRunAs(typeResolverObj);
					Node scriptNode = resolveScriptNode(serviceSession, basePath, sr, wiringPath,
							typeName + " typeResolver");
					if (scriptNode != null) {
						typeResolvers.put(typeName, new GroovyTypeResolver(workspaceName, typeName, scriptNode.getPath(),
								extensionOf(scriptNode.getName()), readText(scriptNode), sr[1]));
					}
				}
			}
		}

		Object scalarsObj = doc.get("scalars");
		if (scalarsObj instanceof Map) {
			for (Map.Entry<String, Object> scalarEntry : ((Map<String, Object>) scalarsObj).entrySet()) {
				String scalarName = scalarEntry.getKey();
				String[] sr = scriptAndRunAs(scalarEntry.getValue());
				Node scriptNode = resolveScriptNode(serviceSession, basePath, sr, wiringPath, "scalar " + scalarName);
				if (scriptNode == null) {
					continue;
				}
				// A scalar's coercing is part of the schema, so its script is
				// evaluated once here, at compile time, to obtain the closures.
				// It runs without the per-request platform APIs (no JCR session):
				// scalar coercing must be a pure value converter.
				WorkspaceScriptContext context = new WorkspaceScriptContext(workspaceName);
				try {
					ResolverScript script = new ResolverScript(workspaceName, scriptNode.getPath(),
							extensionOf(scriptNode.getName()), readText(scriptNode), null);
					Object result = script.eval(context, Map.of());
					scriptScalars.put(scalarName, ScriptCoercing.newScalar(scalarName, result));
				} catch (Exception ex) {
					CmsService.getLogger(GraphQLSchemaCompiler.class)
							.warn("Failed to build script scalar \"" + scalarName + "\" in " + wiringPath, ex);
				} finally {
					try {
						context.close();
					} catch (Throwable ignore) {}
				}
			}
		}
	}

	/**
	 * Extracts {@code [scriptPath, runAs]} from a wiring entry that is either a
	 * bare script path string or a {@code {script:, runAs:}} map. Returns
	 * {@code null} when no script is given.
	 */
	private static String[] scriptAndRunAs(Object value) {
		if (value instanceof String) {
			String s = ((String) value).trim();
			return s.isEmpty() ? null : new String[] { s, null };
		}
		if (value instanceof Map) {
			Map<?, ?> map = (Map<?, ?>) value;
			Object script = map.get("script");
			if (script == null || script.toString().trim().isEmpty()) {
				return null;
			}
			Object runAs = map.get("runAs");
			return new String[] { script.toString().trim(),
					(runAs == null || runAs.toString().trim().isEmpty()) ? null : runAs.toString().trim() };
		}
		return null;
	}

	/**
	 * Resolves a wiring script reference to its node. A path starting with
	 * {@code /} is absolute; otherwise it is relative to the folder containing
	 * {@code wiring.yml}. Logs and returns {@code null} when missing.
	 */
	private static Node resolveScriptNode(Session serviceSession, String basePath, String[] scriptRef,
			String wiringPath, String coordinate) throws RepositoryException {
		if (scriptRef == null) {
			CmsService.getLogger(GraphQLSchemaCompiler.class)
					.warn("Missing 'script' for " + coordinate + " in " + wiringPath);
			return null;
		}
		String scriptPath = scriptRef[0];
		String absolute = scriptPath.startsWith("/") ? scriptPath : (basePath + "/" + scriptPath);
		try {
			return serviceSession.getNode(absolute);
		} catch (PathNotFoundException ex) {
			CmsService.getLogger(GraphQLSchemaCompiler.class)
					.warn("Resolver script not found for " + coordinate + " in " + wiringPath + ": " + absolute);
			return null;
		}
	}

	private static String extensionOf(String name) {
		int p = name.lastIndexOf('.');
		return (p == -1) ? "" : name.substring(p + 1);
	}

	private static boolean isName(String s) {
		if (s.isEmpty()) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean ok = (c == '_') || (i == 0 ? Character.isLetter(c) : Character.isLetterOrDigit(c));
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseYaml(Node fileNode) throws RepositoryException {
		Node content = fileNode.getNode(Node.JCR_CONTENT);
		try (InputStream in = new BufferedInputStream(
				content.getProperty(Property.JCR_DATA).getBinary().getStream())) {
			Object loaded = new Load(LoadSettings.builder().build()).loadFromInputStream(in);
			return (loaded instanceof Map) ? (Map<String, Object>) loaded : Map.of();
		} catch (java.io.IOException ex) {
			throw new RepositoryException("Failed to read GraphQL wiring file: " + fileNode, ex);
		}
	}

	private static String readText(Node fileNode) throws RepositoryException {
		Node content = fileNode.getNode(Node.JCR_CONTENT);
		try (InputStream in = new BufferedInputStream(
				content.getProperty(Property.JCR_DATA).getBinary().getStream())) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException ex) {
			throw new RepositoryException("Failed to read GraphQL file: " + fileNode, ex);
		}
	}

}
