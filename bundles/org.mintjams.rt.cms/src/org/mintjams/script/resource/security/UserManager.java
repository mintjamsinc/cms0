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

package org.mintjams.script.resource.security;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.Iterator;
import java.util.Vector;

import javax.jcr.RepositoryException;
import javax.jcr.security.Privilege;

import org.apache.commons.codec.binary.Hex;
import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.security.AccessControlList;
import org.mintjams.jcr.security.AuthenticatedCredentials;
import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.PrincipalProvider;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Session;
import org.mintjams.script.resource.query.Query;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class UserManager implements Closeable {

	private static final String SYSTEM_WORKSPACE_NAME = "system";

	private final org.mintjams.script.resource.Session fSession;

	public UserManager(org.mintjams.script.resource.Session session) {
		fSession = session;
	}

	private WorkspaceScriptContext fSystemContext;
	private WorkspaceScriptContext systemContext() throws RepositoryException, IOException {
		if (fSystemContext == null) {
			fSystemContext = new UserManagerScriptContext(SYSTEM_WORKSPACE_NAME);
			fSystemContext.setCredentials(new CmsServiceCredentials(fSession.getUserID()));
		}
		return fSystemContext;
	}

	private WorkspaceScriptContext fUserContext;
	private WorkspaceScriptContext userContext() throws RepositoryException, IOException {
		if (fUserContext == null) {
			fUserContext = new UserManagerScriptContext(SYSTEM_WORKSPACE_NAME);
			fUserContext.setCredentials(new AuthenticatedCredentials(fSession.getUserPrincipal()));
		}
		return fUserContext;
	}

	public void registerIfNotExists() throws ResourceException {
		registerIfNotExists(fSession.getUserPrincipal());
		registerIfNotExists(fSession.getGroups().toArray(GroupPrincipal[]::new));
	}

	public void registerIfNotExists(Principal... principals) throws ResourceException {
		for (Principal principal : principals) {
			do {
				try {
					JcrPath homePath = getHomePath(principal);
					Resource homeFolder = systemContext().getResourceResolver().getResource(homePath.toString());
					if (homeFolder.exists()) {
						break;
					}

					homeFolder = mkdirs(homePath, systemContext().getSession());
					Resource attributesFile = homeFolder.createFile("attributes");
					attributesFile.setProperty("identifier", principal.getName());
					attributesFile.setProperty("isGroup", (principal instanceof GroupPrincipal));

					AccessControlList acl = attributesFile.getAccessControlList();
					acl.addAccessControlEntry(principal, true, Privilege.JCR_ALL);
					attributesFile.setAccessControlList(acl);

					systemContext().getSession().commit();
				} catch (Throwable ex) {
					throw Cause.create(ex).wrap(ResourceException.class);
				}

				if (!(principal instanceof GroupPrincipal)) {
					try (WorkspaceScriptContext context = new WorkspaceScriptContext(fSession.getWorkspace().getName())) {
						context.setCredentials(new CmsServiceCredentials());

						JcrPath homePath = JcrPath.valueOf("/home/" + principal.getName());
						Resource homeFolder = context.getResourceResolver().getResource(homePath.toString());
						if (homeFolder.exists()) {
							break;
						}

						homeFolder = mkdirs(homePath, context.getSession());

						AccessControlList acl = homeFolder.getAccessControlList();
						acl.addAccessControlEntry(principal, true, Privilege.JCR_ALL);
						homeFolder.setAccessControlList(acl);

						context.getSession().commit();
					} catch (Throwable ex) {
						throw Cause.create(ex).wrap(ResourceException.class);
					}
				}
			} while (false);
		}
	}

	public void unregisterIfExists() throws ResourceException {
		unregisterIfExists(fSession.getUserPrincipal());
	}

	public void unregisterIfExists(Principal... principals) throws ResourceException {
		for (Principal principal : principals) {
			try {
				JcrPath homePath = getHomePath(principal);
				Resource homeFolder = systemContext().getResourceResolver().getResource(homePath.toString());
				if (homeFolder.exists()) {
					homeFolder.remove();
					systemContext().getSession().commit();
				}
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ResourceException.class);
			}

			if (!(principal instanceof GroupPrincipal)) {
				try (WorkspaceScriptContext context = new WorkspaceScriptContext(fSession.getWorkspace().getName())) {
					context.setCredentials(new CmsServiceCredentials());

					JcrPath homePath = getHomePath(principal);
					Resource homeFolder = context.getResourceResolver().getResource(homePath.toString());
					if (homeFolder.exists()) {
						homeFolder.remove();
						context.getSession().commit();
					}
				} catch (Throwable ex) {
					throw Cause.create(ex).wrap(ResourceException.class);
				}
			}
		}
	}

	public Resource getHomeFolder() throws ResourceException {
		return getHomeFolder(fSession.getUserPrincipal());
	}

	public Resource getHomeFolder(Principal principal) throws ResourceException {
		try {
			return userContext().getResourceResolver().getResource(getHomePath(principal).toString());
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(ResourceException.class);
		}
	}

	public Resource getUserAttributes() throws ResourceException {
		return getUserAttributes(fSession.getUserPrincipal());
	}

	public Resource getUserAttributes(Principal principal) throws ResourceException {
		try {
			return userContext().getResourceResolver().getResource(getHomePath(principal).resolve("attributes").toString());
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(ResourceException.class);
		}
	}

	public boolean isDisabled(Principal principal) throws ResourceException {
		if (principal == null) {
			throw new IllegalArgumentException("Principal must not be null.");
		}

		if (fSession.isAnonymous() || fSession.isService() || fSession.isSystem()) {
			return false;
		}

		Resource attributes = getUserAttributes();
		if (attributes.exists()) {
			if (attributes.hasProperty("disabled") && attributes.getProperty("disabled").getBoolean()) {
				return true;
			}
		}

		return false;
	}

	public boolean isDisabled() throws ResourceException {
		return isDisabled(fSession.getUserPrincipal());
	}

	public Iterator<Principal> search(String statement, long offset, long limit) throws ResourceException {
		if (Strings.isEmpty(statement) || !statement.startsWith("[")) {
			throw new ResourceException("Invalid query: " + statement);
		}

		try {
			Query.QueryResult result = systemContext().getSession().getWorkspace().getQueryManager()
					.createQuery("/jcr:root/home//*" + statement, javax.jcr.query.Query.XPATH).offset(offset).limit(limit).execute();
			PrincipalProvider principalProvider = systemContext().getSession().getPrincipalProvider();
			Vector<Principal> l = new Vector<>();
			for (Resource r : result.getResources()) {
				if (!r.hasProperty("identifier") || !r.hasProperty("isGroup")) {
					continue;
				}

				String id = r.getProperty("identifier").getString();
				if (r.getProperty("isGroup").getBoolean()) {
					l.add(principalProvider.getGroupPrincipal(id));
				} else {
					l.add(principalProvider.getUserPrincipal(id));
				}
			}
			return l.iterator();
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(ResourceException.class);
		}
	}

	private Resource mkdirs(JcrPath path, Session session) throws ResourceException {
		Resource resource = session.getResource(path.toString());
		if (resource.exists()) {
			return resource;
		}
		return mkdirs(path.getParent(), session).createFolder(path.getName().toString());
	}

	private JcrPath getHomePath(Principal principal) throws RepositoryException, NoSuchAlgorithmException {
		String s = principal.getName();
		if (principal instanceof GroupPrincipal) {
			s += "@group";
		}
		String hex = Hex.encodeHexString(
				MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		return JcrPath.valueOf("/home")
				.resolve(hex.substring(0, 2))
				.resolve(hex.substring(2, 4))
				.resolve(hex.substring(4, 6))
				.resolve(hex.substring(6, 8))
				.resolve(hex);
	}

	public void commit() throws ResourceException {
		if (fSystemContext != null) {
			fSystemContext.getResourceResolver().getSession().commit();
		}
		if (fUserContext != null) {
			fUserContext.getResourceResolver().getSession().commit();
		}
	}

	public void rollback() throws ResourceException {
		if (fSystemContext != null) {
			fSystemContext.getResourceResolver().getSession().rollback();
		}
		if (fUserContext != null) {
			fUserContext.getResourceResolver().getSession().rollback();
		}
	}

	public void logout() {
		try {
			rollback();
		} catch (Throwable ignore) {}
		try {
			fSystemContext.close();
		} catch (Throwable ignore) {}
		try {
			fUserContext.close();
		} catch (Throwable ignore) {}
	}

	@Override
	public void close() throws IOException {
		try {
			logout();
		} catch (Throwable ignore) {}
	}

}
