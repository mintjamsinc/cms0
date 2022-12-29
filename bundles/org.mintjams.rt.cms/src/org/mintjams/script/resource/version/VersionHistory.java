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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.jcr.NodeIterator;
import javax.jcr.version.VersionIterator;

import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceImpl;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.lang.Cause;

public class VersionHistory implements Adaptable {

	private final javax.jcr.version.VersionHistory fVersionHistory;
	private final Resource fResource;

	public VersionHistory(javax.jcr.version.VersionHistory versionHistory, Resource resource) {
		fVersionHistory = versionHistory;
		fResource = resource;
	}

	public Iterator<Resource> getAllFrozenNodes() throws ResourceException {
		try {
			return asResource(fVersionHistory.getAllFrozenNodes());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Iterator<Resource> getAllLinearFrozenNodes() throws ResourceException {
		try {
			return asResource(fVersionHistory.getAllLinearFrozenNodes());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Iterator<Version> getAllLinearVersions() throws ResourceException {
		try {
			return asVersion(fVersionHistory.getAllLinearVersions());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Iterator<Version> getAllVersions() throws ResourceException {
		try {
			return asVersion(fVersionHistory.getAllVersions());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Version getRootVersion() throws ResourceException {
		try {
			return new Version(fVersionHistory.getRootVersion(), this);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Version getVersion(String versionName) throws ResourceException {
		try {
			return new Version(fVersionHistory.getVersion(versionName), this);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Version getVersionByLabel(String label) throws ResourceException {
		try {
			return new Version(fVersionHistory.getVersionByLabel(label), this);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public String[] getVersionLabels() throws ResourceException {
		try {
			return fVersionHistory.getVersionLabels();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public String getVersionableIdentifier() throws ResourceException {
		try {
			return fVersionHistory.getVersionableIdentifier();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public boolean hasVersionLabel(String label) throws ResourceException {
		try {
			return fVersionHistory.hasVersionLabel(label);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public VersionHistory removeVersionLabel(String label) throws ResourceException {
		try {
			fVersionHistory.removeVersionLabel(label);
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
		return this;
	}

	protected Version[] asVersion(javax.jcr.version.Version[] versions) throws ResourceException {
		List<Version> results = new ArrayList<>();
		for (javax.jcr.version.Version version : versions) {
			results.add(new Version(version, fResource.getVersionHistory()));
		}
		return results.toArray(new Version[results.size()]);
	}

	protected Iterator<Version> asVersion(VersionIterator i) {
		return new Iterator<Version>() {
			@Override
			public boolean hasNext() {
				return i.hasNext();
			}

			@Override
			public Version next() {
				try {
					return new Version(i.nextVersion(), fResource.getVersionHistory());
				} catch (ResourceException ex) {
					throw Cause.create(ex).wrap(IllegalStateException.class);
				}
			}
		};
	}

	protected Iterator<Resource> asResource(NodeIterator i) {
		return new Iterator<Resource>() {
			@Override
			public boolean hasNext() {
				return i.hasNext();
			}

			@Override
			public Resource next() {
				try {
					return new ResourceImpl(i.nextNode(), fResource.getSession());
				} catch (Throwable ex) {
					throw (IllegalStateException) new IllegalStateException(ex.getMessage()).initCause(ex);
				}
			}
		};
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fResource, adapterType);
	}

}
