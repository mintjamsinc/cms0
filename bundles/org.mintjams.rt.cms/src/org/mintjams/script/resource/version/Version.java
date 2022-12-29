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

package org.mintjams.script.resource.version;

import java.util.Date;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.mintjams.rt.cms.internal.script.resource.JcrAction;
import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Resource;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public class Version implements Adaptable {

	private final javax.jcr.version.Version fVersion;
	private final VersionHistory fVersionHistory;

	public Version(javax.jcr.version.Version version, VersionHistory versionHistory) {
		fVersion = version;
		fVersionHistory = versionHistory;
	}

	public VersionHistory getContainingHistory() throws ResourceException {
		try {
			return new VersionHistory(fVersion.getContainingHistory(), fVersionHistory.adaptTo(Resource.class));
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public String getName() throws ResourceException {
		try {
			return fVersion.getName();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Date getCreated() throws ResourceException {
		try {
			return fVersion.getCreated().getTime();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public FrozenResource getFrozen() throws ResourceException {
		try {
			return new FrozenResource(this, fVersionHistory.adaptTo(Resource.class));
		} catch (RepositoryException ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Version getLinearPredecessor() throws ResourceException {
		try {
			return new Version(fVersion.getLinearPredecessor(), fVersionHistory);
		} catch (RepositoryException ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Version getLinearSuccessor() throws ResourceException {
		try {
			return new Version(fVersion.getLinearSuccessor(), fVersionHistory);
		} catch (RepositoryException ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Version[] getPredecessors() throws ResourceException {
		try {
			return fVersionHistory.asVersion(fVersion.getPredecessors());
		} catch (RepositoryException ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Version[] getSuccessors() throws ResourceException {
		try {
			return fVersionHistory.asVersion(fVersion.getSuccessors());
		} catch (RepositoryException ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public String[] getLabels() throws ResourceException {
		try {
			return fVersion.getContainingHistory().getVersionLabels(fVersion);
		} catch (RepositoryException ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public boolean hasLabel(String label) throws ResourceException {
		try {
			return fVersion.getContainingHistory().hasVersionLabel(fVersion, label);
		} catch (RepositoryException ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public void remove() throws ResourceException {
		try {
			fVersion.getContainingHistory().removeVersion(fVersion.getName());
		} catch (RepositoryException ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Version addLabel(String label, boolean moveLabel) throws ResourceException {
		try {
			JcrAction.create(adaptTo(Node.class)).addLockToken().checkLock();
			fVersion.getContainingHistory().addVersionLabel(fVersion.getName(), label, moveLabel);
		} catch (RepositoryException ex) {
			throw ResourceException.wrap(ex);
		}
		return this;
	}

	public void restore() throws ResourceException {
		try {
			JcrAction.create(adaptTo(Node.class)).addLockToken().checkLock().restore(fVersion.getName());
		} catch (RepositoryException ex) {
			throw ResourceException.wrap(ex);
		}
	}

	@SuppressWarnings("unchecked")
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(javax.jcr.version.Version.class)) {
			return (AdapterType) fVersion;
		}

		return Adaptables.getAdapter(fVersionHistory, adapterType);
	}

}
