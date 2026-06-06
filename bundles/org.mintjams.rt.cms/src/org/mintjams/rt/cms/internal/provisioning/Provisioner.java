/*
 * Copyright (c) 2024 MintJams Inc.
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

package org.mintjams.rt.cms.internal.provisioning;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.NamespaceException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.Node;
import javax.jcr.Value;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.Privilege;

import org.mintjams.cms.security.BCrypt;
import org.mintjams.jcr.security.AccessControlList;
import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.PrincipalProvider;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.Session;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Declarative identity and access provisioning for a workspace.
 *
 * <p>{@link org.mintjams.script.resource.Session#deploy()} delegates to this
 * component to apply the YAML descriptors found under
 * {@code <workspace>/etc/jcr/provisioning/}. Each {@code *.yml} / {@code *.yaml}
 * file is an independent descriptor designed and shipped by an application, so
 * multiple applications can provision side by side without colliding: drop one
 * file per application into the folder.</p>
 *
 * <p>A descriptor may declare the following top-level sections (all optional):</p>
 * <pre>
 * namespaces:            # JCR namespace mappings for the deployed workspace
 *   - prefix: acme
 *     uri: http://www.example.com/acme/1.0
 *
 * roles:                 # hierarchical roles, stored under /home/roles
 *   - id: administrator
 *     displayName: Administrators
 *     description: Built-in administrators
 *
 * groups:                # hierarchical groups, stored under /home/groups
 *   - id: commerce-operators
 *     displayName: Commerce Operators
 *
 * users:                 # users, stored under /home/users
 *   - id: alice
 *     password: changeit
 *     displayName: Alice Smith
 *     givenName: Alice
 *     sn: Smith
 *     mail: alice&#64;example.com
 *     enabled: true
 *     roles: [administrator]
 *     memberOf: [commerce-operators]
 *   - id: commerce-service-user # a service account: non-interactive identity
 *     service: true             # marks it as a service account (isService=true)
 *     displayName: Commerce Service
 *     memberOf: [commerce-service-group]
 *                               # no password: service accounts never sign in,
 *                               # they are only assumed via runAs
 *
 * nodes:                 # folders/files and their ACLs (mirrors jcr.yml defaultNodes)
 *   - path: /content/commerce
 *     primaryType: nt:folder      # optional; the node is created if missing
 *     acl:
 *       - group: commerce-operators
 *         privileges: jcr:read, jcr:write
 *         effect: allow
 * </pre>
 *
 * <p>Provisioning is <strong>idempotent</strong> because {@code deploy()} runs on
 * every boot: principals are created only when absent (passwords are never
 * reset), and access control entries are added only when an equivalent entry is
 * not already present.</p>
 *
 * <p>Identity (users/groups/roles) is always written to the {@code system}
 * workspace — the global identity store shared by every workspace — while ACLs
 * are applied to the resources of the workspace currently being deployed. The
 * identity data model (paths and {@code application/vnd.webtop.*} content types)
 * is the same one managed by the Identity Manager
 * ({@code org.mintjams.rt.cms.internal.graphql.IdpMutationExecutor}); keep the
 * two in sync.</p>
 */
public class Provisioner implements Closeable {

	/** The system workspace that owns the global identity store. */
	private static final String SYSTEM_WORKSPACE_NAME = "system";

	/** Identity store roots in the system workspace (see IdpQueryExecutor). */
	private static final String USERS_ROOT = "/home/users";
	private static final String ROLES_ROOT = "/home/roles";
	private static final String GROUPS_ROOT = "/home/groups";

	/** Content types used by the Identity Manager for each authorizable kind. */
	private static final String USER_CONTENT_TYPE = "application/vnd.webtop.user";
	private static final String GROUP_CONTENT_TYPE = "application/vnd.webtop.group";
	private static final String ROLE_CONTENT_TYPE = "application/vnd.webtop.role";

	private final Session fSession;
	private final String fWorkspaceName;

	private javax.jcr.Session fIdentitySession;
	private boolean fOwnsIdentitySession;

	public Provisioner(Session session) {
		fSession = session;
		fWorkspaceName = session.getWorkspace().getName();
	}

	/**
	 * Applies every descriptor found directly under the given directory. The
	 * directory is optional: a workspace without provisioning descriptors is
	 * simply skipped, exactly like the content {@code deploy} folder.
	 */
	public void provision(Path provisioningPath) throws IOException {
		if (!Files.isDirectory(provisioningPath)) {
			return;
		}

		List<Path> descriptors;
		try (Stream<Path> stream = Files.list(provisioningPath)) {
			descriptors = stream
					.filter(Files::isRegularFile)
					.filter(Provisioner::isDescriptor)
					.sorted()
					.collect(Collectors.toList());
		}
		if (descriptors.isEmpty()) {
			return;
		}

		// Aggregate every descriptor up front, then apply in well-defined phases
		// (namespaces -> roles -> groups -> users -> nodes) so that references
		// between authorizables resolve regardless of which file declared them,
		// and so that any custom namespace is registered before node/property
		// names that rely on it are written.
		List<Map<String, Object>> namespaces = new ArrayList<>();
		List<Map<String, Object>> roles = new ArrayList<>();
		List<Map<String, Object>> groups = new ArrayList<>();
		List<Map<String, Object>> users = new ArrayList<>();
		List<Map<String, Object>> nodes = new ArrayList<>();
		for (Path descriptor : descriptors) {
			Map<String, Object> document = load(descriptor);
			if (document == null) {
				continue;
			}
			namespaces.addAll(section(document, "namespaces"));
			roles.addAll(section(document, "roles"));
			groups.addAll(section(document, "groups"));
			users.addAll(section(document, "users"));
			nodes.addAll(section(document, "nodes"));
		}

		try {
			for (Map<String, Object> namespace : namespaces) {
				provisionNamespace(namespace);
			}
			for (Map<String, Object> role : roles) {
				provisionRole(role);
			}
			for (Map<String, Object> group : groups) {
				provisionGroup(group);
			}
			for (Map<String, Object> user : users) {
				provisionUser(user);
			}
			for (Map<String, Object> node : nodes) {
				provisionNode(node);
			}
		} catch (IOException | RuntimeException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	// =========================================================================
	// Namespace provisioning (current workspace)
	// =========================================================================

	/**
	 * Registers a JCR namespace mapping on the workspace currently being deployed.
	 *
	 * <p>Registration is idempotent: a mapping that already exists exactly as
	 * declared is left untouched. A prefix that is already bound to a different URI
	 * (or a URI already bound to a different prefix) is reported as a conflict
	 * rather than silently remapped, so an application can never quietly hijack a
	 * namespace another descriptor depends on.</p>
	 */
	private void provisionNamespace(Map<String, Object> definition) throws Exception {
		String prefix = string(definition.get("prefix"));
		if (Strings.isEmpty(prefix)) {
			throw new IOException("A prefix is required for every namespace provisioning entry.");
		}
		prefix = prefix.trim();
		String uri = string(definition.get("uri"));
		if (Strings.isEmpty(uri)) {
			throw new IOException("A uri is required for namespace provisioning entry: " + prefix);
		}
		uri = uri.trim();

		javax.jcr.Session session = fSession.adaptTo(javax.jcr.Session.class);
		NamespaceRegistry registry = session.getWorkspace().getNamespaceRegistry();

		// Idempotency / conflict detection by prefix.
		try {
			String boundURI = registry.getURI(prefix);
			if (uri.equals(boundURI)) {
				return;
			}
			throw new IOException("Namespace prefix '" + prefix
					+ "' is already registered to a different URI: " + boundURI);
		} catch (NamespaceException notBound) {
			// The prefix is free; fall through to register it.
		}

		// The prefix is free, but the URI may already be bound to another prefix.
		try {
			String boundPrefix = registry.getPrefix(uri);
			throw new IOException("Namespace URI '" + uri
					+ "' is already registered under prefix '" + boundPrefix + "'.");
		} catch (NamespaceException notBound) {
			// The URI is free; proceed to register.
		}

		registry.registerNamespace(prefix, uri);
		fSession.commit();
		CmsService.getLogger(getClass()).debug("Provisioned namespace: " + prefix + " -> " + uri);
	}

	// =========================================================================
	// Identity provisioning (system workspace)
	// =========================================================================

	private void provisionRole(Map<String, Object> definition) throws Exception {
		String id = requireId(definition, "role");
		String folderPath = ROLES_ROOT + "/" + id;
		javax.jcr.Session session = identitySession();
		if (session.nodeExists(folderPath)) {
			return;
		}

		Node roleFolder = getOrCreateJcrFolder(session, folderPath);
		Node profile = JCRs.createFile(roleFolder, "profile");
		profile.addMixin("mix:referenceable");
		JCRs.setProperty(profile, "jcr:mimeType", ROLE_CONTENT_TYPE);
		setStringIfPresent(profile, "displayName", definition);
		setStringIfPresent(profile, "description", definition);
		session.save();
		CmsService.getLogger(getClass()).debug("Provisioned role: " + id);
	}

	private void provisionGroup(Map<String, Object> definition) throws Exception {
		String id = requireId(definition, "group");
		String folderPath = GROUPS_ROOT + "/" + id;
		javax.jcr.Session session = identitySession();
		if (session.nodeExists(folderPath)) {
			return;
		}

		Node groupFolder = getOrCreateJcrFolder(session, folderPath);
		Node profile = JCRs.createFile(groupFolder, "profile");
		profile.addMixin("mix:referenceable");
		JCRs.setProperty(profile, "jcr:mimeType", GROUP_CONTENT_TYPE);
		Node contentNode = JCRs.getContentNode(profile);
		// The leaf segment is the group identifier exposed to the security layer.
		contentNode.setProperty("identifier", leafName(id));
		contentNode.setProperty("isGroup", true);
		setStringIfPresent(profile, "displayName", definition);
		setStringIfPresent(profile, "description", definition);
		session.save();
		CmsService.getLogger(getClass()).debug("Provisioned group: " + id);
	}

	private void provisionUser(Map<String, Object> definition) throws Exception {
		String id = requireId(definition, "user");
		String userFolderPath = USERS_ROOT + "/" + id;
		javax.jcr.Session session = identitySession();
		if (session.nodeExists(userFolderPath)) {
			// Existing users are left untouched; passwords are never reset here.
			return;
		}

		// Service accounts are non-interactive identities used by integrations
		// (e.g. EIP routes / BPMN service tasks via runAs). They never sign in,
		// so a password is neither required nor stored for them; for every other
		// user a password is mandatory.
		boolean service = bool(definition.get("service"), false);
		String password = string(definition.get("password"));
		if (!service && Strings.isEmpty(password)) {
			throw new IOException("A password is required to provision user: " + id);
		}

		Node userFolder = getOrCreateJcrFolder(session, userFolderPath);
		Node profile = JCRs.createFile(userFolder, "profile");
		JCRs.setProperty(profile, "jcr:mimeType", USER_CONTENT_TYPE);
		if (Strings.isNotEmpty(password)) {
			JCRs.setProperty(profile, "password", "{bcrypt}" + BCrypt.hash(password));
		}

		Node contentNode = JCRs.getContentNode(profile);
		contentNode.setProperty("identifier", id);
		contentNode.setProperty("isGroup", false);
		contentNode.setProperty("isService", service);
		setStringIfPresent(profile, "sn", definition);
		setStringIfPresent(profile, "givenName", definition);
		setStringIfPresent(profile, "displayName", definition);
		setStringIfPresent(profile, "mail", definition);
		contentNode.setProperty("enabled", bool(definition.get("enabled"), true));

		setReferences(contentNode, "roles", ROLES_ROOT, list(definition.get("roles")));
		setReferences(contentNode, "memberOf", GROUPS_ROOT, list(definition.get("memberOf")));

		JCRs.getOrCreateFolder(userFolder, "preferences");
		JCRs.getOrCreateFolder(userFolder, "Desktop");
		session.save();

		// Grant the user full control over their own home, mirroring the
		// Identity Manager so the account behaves consistently afterwards.
		JCRs.setAccessControlEntry(userFolder, userPrincipal(id), true, Privilege.JCR_ALL);
		session.save();
		CmsService.getLogger(getClass()).debug("Provisioned user: " + id);
	}

	/**
	 * Resolves each id to its authorizable profile under {@code root} and stores
	 * the set as weak references, exactly as the Identity Manager does. Unknown
	 * ids are skipped so a descriptor never fails on a forward reference.
	 */
	private void setReferences(Node contentNode, String propertyName, String root, List<String> ids) throws Exception {
		if (ids.isEmpty()) {
			return;
		}

		javax.jcr.Session session = contentNode.getSession();
		List<Value> values = new ArrayList<>();
		for (String id : ids) {
			String profilePath = root + "/" + id + "/profile";
			if (!session.nodeExists(profilePath)) {
				CmsService.getLogger(getClass()).warn("Skipping unknown reference '" + id + "' under " + root);
				continue;
			}
			Node profile = session.getNode(profilePath);
			if (!profile.isNodeType("mix:referenceable")) {
				profile.addMixin("mix:referenceable");
			}
			values.add(session.getValueFactory().createValue(profile, true));
		}
		if (!values.isEmpty()) {
			contentNode.setProperty(propertyName, values.toArray(new Value[0]));
		}
	}

	// =========================================================================
	// Node and ACL provisioning (current workspace)
	// =========================================================================

	private void provisionNode(Map<String, Object> definition) throws Exception {
		String path = string(definition.get("path"));
		if (Strings.isEmpty(path)) {
			throw new IOException("A path is required for every node provisioning entry.");
		}

		Resource resource = fSession.getResource(path);
		if (!resource.exists()) {
			String primaryType = string(definition.get("primaryType"));
			if (Strings.isEmpty(primaryType)) {
				CmsService.getLogger(getClass())
						.warn("Skipping ACL for missing node (no primaryType to create it): " + path);
				return;
			}
			resource = createNode(path, primaryType);
			fSession.commit();
			CmsService.getLogger(getClass()).debug("Provisioned node: " + path);
		}

		List<Map<String, Object>> entries = mapList(definition.get("acl"));
		if (entries.isEmpty()) {
			return;
		}

		AccessControlList acl = resource.getAccessControlList();
		boolean changed = false;
		for (Map<String, Object> entry : entries) {
			AccessControlEntryDescriptor ace = parseEntry(entry, path);
			if (containsEntry(acl, ace)) {
				continue;
			}
			acl.addAccessControlEntry(ace.principal, ace.allow, ace.privileges);
			changed = true;
		}
		if (changed) {
			resource.setAccessControlList(acl);
			fSession.commit();
			CmsService.getLogger(getClass()).debug("Provisioned ACL: " + path);
		}
	}

	private Resource createNode(String absPath, String primaryType) throws Exception {
		Resource parent = getOrCreateFolderPath(absPath.substring(0, absPath.lastIndexOf('/')));
		String name = absPath.substring(absPath.lastIndexOf('/') + 1);
		if ("nt:file".equals(primaryType) || "mi:file".equals(primaryType)) {
			return parent.createFile(name);
		}
		return parent.getOrCreateFolder(name);
	}

	private Resource getOrCreateFolderPath(String absPath) throws Exception {
		if (Strings.isEmpty(absPath) || absPath.equals("/")) {
			return fSession.getRootFolder();
		}
		Resource parent = getOrCreateFolderPath(absPath.substring(0, absPath.lastIndexOf('/')));
		return parent.getOrCreateFolder(absPath.substring(absPath.lastIndexOf('/') + 1));
	}

	private AccessControlEntryDescriptor parseEntry(Map<String, Object> entry, String path) throws IOException {
		String[] privileges = privileges(entry.get("privileges"));
		if (privileges.length == 0) {
			throw new IOException("At least one privilege must be specified for an ACL entry on: " + path);
		}

		String effect = string(entry.get("effect"));
		boolean allow;
		if ("allow".equalsIgnoreCase(effect)) {
			allow = true;
		} else if ("deny".equalsIgnoreCase(effect)) {
			allow = false;
		} else {
			throw new IOException("The effect must be specified as either allow or deny on: " + path);
		}

		return new AccessControlEntryDescriptor(resolvePrincipal(entry, path), allow, privileges);
	}

	/**
	 * Resolves the ACE principal from the {@code principal}, {@code group} or
	 * {@code user} key. The principal provider is consulted first so that the
	 * canonical principal is stored; when it cannot be resolved (e.g. a built-in
	 * or not-yet-known principal) a typed placeholder is used so the entry is
	 * still recorded by name, matching the behaviour of the jcr.yml loader.
	 */
	private Principal resolvePrincipal(Map<String, Object> entry, String path) throws IOException {
		String name = string(entry.get("principal"));
		PrincipalKind kind = PrincipalKind.ANY;
		if (Strings.isEmpty(name)) {
			name = string(entry.get("group"));
			if (Strings.isNotEmpty(name)) {
				kind = PrincipalKind.GROUP;
			}
		}
		if (Strings.isEmpty(name)) {
			name = string(entry.get("user"));
			if (Strings.isNotEmpty(name)) {
				kind = PrincipalKind.USER;
			}
		}
		if (Strings.isEmpty(name)) {
			throw new IOException("An ACL principal must be specified as either a user or a group on: " + path);
		}

		PrincipalProvider provider = fSession.getPrincipalProvider();
		final String principalName = name;
		try {
			if (kind == PrincipalKind.GROUP) {
				return provider.getGroupPrincipal(principalName);
			}
			if (kind == PrincipalKind.USER) {
				return provider.getUserPrincipal(principalName);
			}
			return provider.getPrincipal(principalName);
		} catch (PrincipalNotFoundException notFound) {
			if (kind == PrincipalKind.GROUP) {
				return (GroupPrincipal) () -> principalName;
			}
			if (kind == PrincipalKind.USER) {
				return (UserPrincipal) () -> principalName;
			}
			return () -> principalName;
		}
	}

	private boolean containsEntry(AccessControlList acl, AccessControlEntryDescriptor ace) throws Exception {
		for (AccessControlEntry existing : acl) {
			if (!(existing instanceof org.mintjams.jcr.security.AccessControlEntry)) {
				continue;
			}
			if (((org.mintjams.jcr.security.AccessControlEntry) existing).isAllow() != ace.allow) {
				continue;
			}
			if (existing.getPrincipal() == null || !existing.getPrincipal().getName().equals(ace.principal.getName())) {
				continue;
			}
			if (privilegeNames(existing.getPrivileges()).containsAll(java.util.Arrays.asList(ace.privileges))) {
				return true;
			}
		}
		return false;
	}

	// =========================================================================
	// Identity session lifecycle
	// =========================================================================

	private javax.jcr.Session identitySession() throws Exception {
		if (fIdentitySession == null) {
			if (SYSTEM_WORKSPACE_NAME.equals(fWorkspaceName)) {
				// Already operating on the identity store; reuse the deploy session.
				fIdentitySession = fSession.adaptTo(javax.jcr.Session.class);
				fOwnsIdentitySession = false;
			} else {
				fIdentitySession = CmsService.getRepository().login(new CmsServiceCredentials(), SYSTEM_WORKSPACE_NAME);
				fOwnsIdentitySession = true;
			}
		}
		return fIdentitySession;
	}

	@Override
	public void close() throws IOException {
		if (fOwnsIdentitySession && fIdentitySession != null) {
			try {
				fIdentitySession.logout();
			} catch (Throwable ignore) {}
		}
		fIdentitySession = null;
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private static boolean isDescriptor(Path path) {
		String name = path.getFileName().toString().toLowerCase();
		return name.endsWith(".yml") || name.endsWith(".yaml");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> load(Path descriptor) throws IOException {
		try (InputStream in = new BufferedInputStream(Files.newInputStream(descriptor))) {
			Object document = new Load(LoadSettings.builder().build()).loadFromInputStream(in);
			if (document == null) {
				return null;
			}
			if (!(document instanceof Map)) {
				throw new IOException("A provisioning descriptor must be a mapping: " + descriptor);
			}
			return (Map<String, Object>) document;
		}
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> section(Map<String, Object> document, String name) throws IOException {
		Object value = document.get(name);
		if (value == null) {
			return Collections.emptyList();
		}
		if (!(value instanceof List)) {
			throw new IOException("The '" + name + "' section must be a list.");
		}
		return (List<Map<String, Object>>) value;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> mapList(Object value) {
		if (value instanceof List) {
			return (List<Map<String, Object>>) value;
		}
		return Collections.emptyList();
	}

	private String requireId(Map<String, Object> definition, String kind) throws IOException {
		String id = string(definition.get("id"));
		if (Strings.isEmpty(id)) {
			throw new IOException("An id is required for every " + kind + " provisioning entry.");
		}
		// Normalise to a relative path under the corresponding /home root.
		id = id.trim();
		while (id.startsWith("/")) {
			id = id.substring(1);
		}
		if (Strings.isEmpty(id)) {
			throw new IOException("An id is required for every " + kind + " provisioning entry.");
		}
		return id;
	}

	private static String leafName(String id) {
		int i = id.lastIndexOf('/');
		return (i < 0) ? id : id.substring(i + 1);
	}

	private void setStringIfPresent(Node fileNode, String name, Map<String, Object> definition) throws Exception {
		Object value = definition.get(name);
		if (value instanceof String) {
			JCRs.setProperty(fileNode, name, (String) value);
		}
	}

	private Node getOrCreateJcrFolder(javax.jcr.Session session, String absPath) throws Exception {
		return JCRs.getOrCreateFolder(org.mintjams.jcr.JcrPath.valueOf(absPath), session);
	}

	private UserPrincipal userPrincipal(String name) {
		// The account is brand new and lives in the system identity store, so a
		// named principal is used here, matching the Identity Manager.
		return () -> name;
	}

	private static List<String> privilegeNames(Privilege[] privileges) {
		List<String> names = new ArrayList<>();
		for (Privilege privilege : privileges) {
			names.add(privilege.getName());
		}
		return names;
	}

	private static String[] privileges(Object value) {
		if (value == null) {
			return new String[0];
		}
		Stream<String> tokens;
		if (value instanceof List) {
			tokens = ((List<?>) value).stream().filter(e -> e != null).map(Object::toString);
		} else {
			tokens = Stream.of(value.toString().split(","));
		}
		return tokens.map(String::trim).filter(Strings::isNotEmpty).toArray(String[]::new);
	}

	private static String string(Object value) {
		return (value == null) ? null : value.toString();
	}

	private static boolean bool(Object value, boolean defaultValue) {
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof String) {
			return Boolean.parseBoolean((String) value);
		}
		return defaultValue;
	}

	@SuppressWarnings("unchecked")
	private static List<String> list(Object value) {
		if (value == null) {
			return Collections.emptyList();
		}
		if (value instanceof List) {
			List<String> result = new ArrayList<>();
			for (Object e : (List<Object>) value) {
				if (e != null) {
					result.add(e.toString());
				}
			}
			return result;
		}
		return new ArrayList<>(Collections.singletonList(value.toString()));
	}

	private enum PrincipalKind {
		ANY, USER, GROUP
	}

	private static final class AccessControlEntryDescriptor {
		private final Principal principal;
		private final boolean allow;
		private final String[] privileges;

		private AccessControlEntryDescriptor(Principal principal, boolean allow, String[] privileges) {
			this.principal = principal;
			this.allow = allow;
			this.privileges = privileges;
		}
	}
}
