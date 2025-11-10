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

package org.mintjams.rt.jcr.internal;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.NamespaceException;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;

import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;

public class JcrNamespaceRegistry implements org.mintjams.jcr.NamespaceRegistry, Adaptable {

	public static final Map<String, String> PREDEFINEDS = Collections.unmodifiableMap(AdaptableMap
			.<String, String>newBuilder(String.CASE_INSENSITIVE_ORDER)
			.put(PREFIX_JCR, NAMESPACE_JCR)
			.put(PREFIX_MIX, NAMESPACE_MIX)
			.put(PREFIX_NT, NAMESPACE_NT)
			.put(PREFIX_XML, NAMESPACE_XML)
			.put(PREFIX_MI, NAMESPACE_MI)
			.build());

	private final JcrWorkspace fWorkspace;
	private final Map<String, String> fCachePrefixes = new HashMap<>();
	private final Map<String, String> fCacheURIs = new HashMap<>();

	private JcrNamespaceRegistry(JcrWorkspace workspace) {
		fWorkspace = workspace;
		for (Map.Entry<String, String> e : PREDEFINEDS.entrySet()) {
			fCachePrefixes.put(e.getValue(), e.getKey());
			fCacheURIs.put(e.getKey(), e.getValue());
		}
	}

	public static JcrNamespaceRegistry create(JcrWorkspace workspace) {
		return new JcrNamespaceRegistry(workspace);
	}

	@Override
	public String getPrefix(String uri) throws NamespaceException, RepositoryException {
		if (fCachePrefixes.containsKey(uri)) {
			return fCachePrefixes.get(uri);
		}

		try {
			String prefix = getWorkspaceQuery().namespaces().getPrefix(uri);
			if (prefix == null) {
				throw new NamespaceException("Namespace prefix does not exist: " + uri);
			}
			fCachePrefixes.put(uri, prefix);
			fCacheURIs.put(prefix, uri);
			return prefix;
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public String[] getPrefixes() throws RepositoryException {
		try {
			return getWorkspaceQuery().namespaces().getPrefixes();
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public String getURI(String prefix) throws NamespaceException, RepositoryException {
		if (fCacheURIs.containsKey(prefix)) {
			return fCacheURIs.get(prefix);
		}

		try {
			String uri = getWorkspaceQuery().namespaces().getURI(prefix);
			if (uri == null) {
				throw new NamespaceException("Namespace URI does not exist: " + prefix);
			}
			fCacheURIs.put(prefix, uri);
			fCachePrefixes.put(uri, prefix);
			return uri;
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public String[] getURIs() throws RepositoryException {
		try {
			return getWorkspaceQuery().namespaces().getURIs();
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public void registerNamespace(String prefix, String uri) throws NamespaceException,
			UnsupportedRepositoryOperationException, AccessDeniedException, RepositoryException {
		try {
			getWorkspaceQuery().namespaces().registerNamespace(prefix, uri);
			fCacheURIs.put(prefix, uri);
			fCachePrefixes.put(uri, prefix);
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public void unregisterNamespace(String prefix) throws NamespaceException, UnsupportedRepositoryOperationException,
			AccessDeniedException, RepositoryException {
		try {
			getWorkspaceQuery().namespaces().unregisterNamespace(prefix);
			String uri = fCacheURIs.remove(prefix);
			if (uri != null) {
				fCachePrefixes.remove(uri);
			}
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	private WorkspaceQuery getWorkspaceQuery() {
		return adaptTo(WorkspaceQuery.class);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

}
