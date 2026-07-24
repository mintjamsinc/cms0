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

package org.mintjams.script.resource;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Stream;

import javax.jcr.Credentials;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.SimpleCredentials;

import org.mintjams.jcr.cluster.ClusterLeaseStore;
import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.IdentityProvider;
import org.mintjams.jcr.security.PrincipalProvider;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.provisioning.Provisioner;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.rt.cms.internal.web.Webs;
import org.mintjams.script.resource.security.AccessControlManager;
import org.mintjams.script.resource.security.AccessDeniedException;
import org.mintjams.script.resource.security.UserManager;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class Session implements Closeable, Adaptable {

	private final javax.jcr.Session fJcrSession;
	private final WorkspaceScriptContext fContext;
	private final Workspace fWorkspace;
	private final ResourceResolver fResourceResolver;
	private final UserManager fUserManager;
	private final AccessControlManager fAccessControlManager;

	public Session(javax.jcr.Session session, WorkspaceScriptContext context) {
		fJcrSession = session;
		fContext = context;
		fResourceResolver = new ResourceResolver(this);
		fWorkspace = new Workspace(this);
		fUserManager = new UserManager(this);
		fAccessControlManager = new AccessControlManager(this);
	}

	public String getUserID() {
		return fJcrSession.getUserID();
	}

	public UserPrincipal getUserPrincipal() {
		return adaptTo(org.mintjams.jcr.Session.class).getUserPrincipal();
	}

	public Collection<GroupPrincipal> getGroups() {
		return adaptTo(org.mintjams.jcr.Session.class).getGroups();
	}

	public boolean isAdmin() {
		return adaptTo(org.mintjams.jcr.Session.class).isAdmin();
	}

	public boolean isAnonymous() {
		return adaptTo(org.mintjams.jcr.Session.class).isAnonymous();
	}

	public boolean isService() {
		return adaptTo(org.mintjams.jcr.Session.class).isService();
	}

	public boolean isSystem() {
		return adaptTo(org.mintjams.jcr.Session.class).isSystem();
	}

	public boolean isAuthorized() {
		if (isAnonymous()) {
			return false;
		}

		try {
			Resource attributes = fUserManager.getUserAttributes();
			if (!attributes.exists()) {
				return true;
			}
			if (!attributes.hasProperty("mi:totpSecret")) {
				return true;
			}
		} catch (ResourceException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}

		String authenticatedFactors = (String) Webs.getRequest(fContext).getSession().getAttribute(Webs.AUTHENTICATED_FACTORS_ATTRIBUTE);
		if (Strings.isEmpty(authenticatedFactors)) {
			return false;
		}
		if (!Arrays.asList(authenticatedFactors.split(",")).contains("totp")) {
			return false;
		}
		return true;
	}

	public Resource getResource(String absPath) throws ResourceException {
		return new ResourceImpl(absPath, this);
	}

	public Resource getResourceByIdentifier(String id) throws ResourceException {
		Node node = null;
		try {
			node = fJcrSession.getNodeByIdentifier(id);
		} catch (ItemNotFoundException ignore) {
			return new UnknownIdentifierResource(id, this);
		} catch (javax.jcr.AccessDeniedException ex) {
			throw (AccessDeniedException) new AccessDeniedException(id).initCause(ex);
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}

		try {
			return new ResourceImpl(node, this);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Resource getRootFolder() throws ResourceException {
		Node node;
		try {
			node = fJcrSession.getRootNode();
		} catch (javax.jcr.AccessDeniedException ex) {
			throw (AccessDeniedException) new AccessDeniedException("/").initCause(ex);
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}

		try {
			return new ResourceImpl(node, this);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public void commit() throws ResourceException {
		fUserManager.commit();

		if (fJcrSession == null) {
			return;
		}

		if (!fJcrSession.isLive()) {
			return;
		}

		try {
			if (fJcrSession.hasPendingChanges()) {
				fJcrSession.save();
			}
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public void rollback() throws ResourceException {
		fUserManager.rollback();

		if (fJcrSession == null) {
			return;
		}

		if (!fJcrSession.isLive()) {
			return;
		}

		try {
			fJcrSession.refresh(false);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public void logout() {
		fUserManager.logout();

		if (fJcrSession == null) {
			return;
		}

		if (!fJcrSession.isLive()) {
			return;
		}

		try {
			rollback();
		} catch (Throwable ignore) {}

		fJcrSession.logout();
	}

	@Override
	public void close() throws IOException {
		try {
			logout();
		} catch (Throwable ignore) {}
	}

	public Workspace getWorkspace() {
		return fWorkspace;
	}

	public ResourceResolver getResourceResolver() {
		return fResourceResolver;
	}

	/**
	 * @deprecated
	 */
	public UserManager getUserManager() {
		return fUserManager;
	}

	public IdentityProvider getIdentityProvider() {
		return Adaptables.getAdapter(fJcrSession, IdentityProvider.class);
	}

	public PrincipalProvider getPrincipalProvider() {
		return Adaptables.getAdapter(fJcrSession, PrincipalProvider.class);
	}

	public AccessControlManager getAccessControlManager() {
		return fAccessControlManager;
	}

	public Session newSession() throws ResourceException {
		try {
			return new Session(fJcrSession.getRepository().login(fContext.adaptTo(Credentials.class), fContext.getWorkspaceName()), fContext);
		} catch (RepositoryException ex) {
			throw (ResourceException) new ResourceException(ex.getMessage()).initCause(ex);
		}
	}

	public Session impersonate(String userId) throws ResourceException {
		try {
			return new Session(fJcrSession.impersonate(new SimpleCredentials(userId, "".toCharArray())), fContext);
		} catch (RepositoryException ex) {
			throw (ResourceException) new ResourceException(ex.getMessage()).initCause(ex);
		}
	}

	public void deploy() throws ResourceException, IOException {
		// In a cluster every node deploys from the shared directory at
		// startup; the lease serializes them so that two nodes never create
		// or update the same resource concurrently. The second node then
		// finds everything already up to date and walks through unchanged.
		// In standalone mode the lease is granted immediately.
		ClusterLeaseStore.Lease lease = null;
		ClusterLeaseStore leases = Adaptables.getAdapter(fJcrSession, ClusterLeaseStore.class);
		if (leases != null) {
			lease = leases.lock("content-deployment", 3600000L);
		}
		try {
			Path jcrPath = CmsService.getWorkspacePath(fContext.getWorkspaceName()).resolve("etc/jcr");
			// Provision identity and access control first so that the node
			// hierarchy, ACLs and principals are already in place before content
			// is imported into them. Importing files into an established,
			// permission-bounded structure keeps the workspace consistent and
			// avoids resolving content against not-yet-known principals.
			try (Provisioner provisioner = new Provisioner(this)) {
				provisioner.provision(jcrPath.resolve("provisioning"));
			}
			DeployProgress progress = new DeployProgress();
			deploy(jcrPath.resolve("deploy"), getRootFolder(), progress);
			CmsService.getLogger(getClass()).info("Content deployment finished: " + progress.summarize());
		} catch (Throwable ex) {
			try {
				rollback();
			} catch (Throwable ignore) {}
			throw ex;
		} finally {
			if (lease != null) {
				lease.close();
			}
		}
	}

	private void deploy(Path path, Resource resource, DeployProgress progress) throws ResourceException, IOException {
		if (!Files.exists(path)) {
			return;
		}

		if (Files.isDirectory(path)) {
			if (!resource.exists()) {
				resource.createFolder();
				commit();
				progress.created();
			}

			try (Stream<Path> stream = Files.list(path)) {
				stream.forEach(childPath -> {
					try {
						deploy(childPath, resource.getResource(childPath.getFileName().toString()), progress);
					} catch (Throwable ex) {
						throw new UncheckedIOException(Cause.create(ex).wrap(IOException.class));
					}
				});
			} catch (UncheckedIOException ex) {
				throw ex.getCause();
			}
			return;
		}

		if (!resource.exists()) {
			CmsService.getLogger(getClass()).debug("Create content: " + path.toAbsolutePath().toString());
			resource.createFile();
			resource.write(path);
			commit();
			progress.created();
		} else {
			if (resource.getLastModified().getTime() < Files.getLastModifiedTime(path).toMillis()) {
				CmsService.getLogger(getClass()).debug("Update content: " + path.toAbsolutePath().toString());
				resource.write(path);
				resource.setProperty(Property.JCR_LAST_MODIFIED, new java.util.Date(Files.getLastModifiedTime(path).toMillis()));
				commit();
				progress.updated();
			} else {
				progress.unchanged();
			}
		}
	}

	/**
	 * Reports content deployment progress periodically. A full deployment
	 * against a networked database can run for minutes, and without these
	 * reports it is indistinguishable from a hung process.
	 */
	private class DeployProgress {
		private static final long REPORT_INTERVAL_MILLIS = 10000L;

		private final long fStarted = System.currentTimeMillis();
		private long fLastReported = fStarted;
		private long fCreated;
		private long fUpdated;
		private long fUnchanged;

		private void created() {
			fCreated++;
			reportIfStale();
		}

		private void updated() {
			fUpdated++;
			reportIfStale();
		}

		private void unchanged() {
			fUnchanged++;
			reportIfStale();
		}

		private void reportIfStale() {
			if (System.currentTimeMillis() - fLastReported < REPORT_INTERVAL_MILLIS) {
				return;
			}

			fLastReported = System.currentTimeMillis();
			CmsService.getLogger(Session.class).info("Content deployment in progress: " + summarize());
		}

		private String summarize() {
			return fCreated + " created, " + fUpdated + " updated, " + fUnchanged + " unchanged ("
					+ ((System.currentTimeMillis() - fStarted) / 1000) + " seconds).";
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(javax.jcr.Session.class) || adapterType.equals(org.mintjams.jcr.Session.class)) {
			return (AdapterType) fJcrSession;
		}

		if (adapterType.equals(WorkspaceScriptContext.class)) {
			return (AdapterType) fContext;
		}

		return null;
	}

}
