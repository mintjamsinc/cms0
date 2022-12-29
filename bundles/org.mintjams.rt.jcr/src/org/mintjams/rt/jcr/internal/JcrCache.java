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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.security.Privilege;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.NamespaceProvider;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;

public class JcrCache implements Adaptable, Closeable {

	private final JcrWorkspace fWorkspace;
	private final Map<String, AdaptableMap<String, Object>> fNodes = new HashMap<>();
	private final Map<String, String> fPaths = new HashMap<>();
	private final Map<String, Map<String, AdaptableMap<String, Object>>> fProperties = new HashMap<>();
	private final Map<String, Privilege[]> fPrivileges = new HashMap<>();
	private final List<String> fNodeUsed = new ArrayList<>();

	private JcrCache(JcrWorkspace workspace) {
		fWorkspace = workspace;
	}

	public static JcrCache create(JcrWorkspace workspace) {
		return new JcrCache(workspace);
	}

	@Override
	public synchronized void close() throws IOException {
		clear();
	}

	public AdaptableMap<String, Object> getNode(String absPath) throws PathNotFoundException, RepositoryException {
		absPath = JcrPath.valueOf(absPath).with(adaptTo(NamespaceProvider.class)).toString();
		if (fPaths.containsKey(absPath)) {
			String identifier = fPaths.get(absPath);
			fNodeUsed.remove(identifier);
			fNodeUsed.add(identifier);
			return fNodes.get(identifier);
		}
		Activator.getDefault().getLogger(getClass()).debug("JCR node '" + absPath + "' not found in JCR cache.");
		return null;
	}

	public AdaptableMap<String, Object> getNodeByIdentifier(String identifier) throws ItemNotFoundException, RepositoryException {
		if (fNodes.containsKey(identifier)) {
			fNodeUsed.remove(identifier);
			fNodeUsed.add(identifier);
			return fNodes.get(identifier);
		}
		Activator.getDefault().getLogger(getClass()).debug("JCR node '" + identifier + "' not found in JCR cache.");
		return null;
	}

	public void setNode(AdaptableMap<String, Object> itemData) {
		String identifier = itemData.getString("item_id");
		String path = itemData.getString("item_path");
		fNodes.put(identifier, itemData);
		fPaths.put(path, identifier);
		fNodeUsed.remove(identifier);
		fNodeUsed.add(identifier);
		int cacheSize = adaptTo(JcrRepository.class).getConfiguration().getNodeCacheSize();
		while (fNodeUsed.size() > cacheSize) {
			remove(fNodeUsed.get(0));
		}
	}

	public Map<String, AdaptableMap<String, Object>> getProperties(String identifier) {
		if (fNodes.containsKey(identifier)) {
			fNodeUsed.remove(identifier);
			fNodeUsed.add(identifier);
			return fProperties.get(identifier);
		}
		Activator.getDefault().getLogger(getClass()).debug("JCR properties '" + identifier + "' not found in JCR cache.");
		return null;
	}

	public void setProperties(String identifier, Map<String, AdaptableMap<String, Object>> properties) {
		fNodeUsed.remove(identifier);
		fNodeUsed.add(identifier);
		int cacheSize = adaptTo(JcrRepository.class).getConfiguration().getNodeCacheSize();
		while (fNodeUsed.size() > cacheSize) {
			remove(fNodeUsed.get(0));
		}
		fProperties.put(identifier, properties);
	}

	public Privilege[] getPrivileges(String absPath) {
		absPath = JcrPath.valueOf(absPath).with(adaptTo(NamespaceProvider.class)).toString();
		if (fPrivileges.containsKey(absPath)) {
			String identifier = fPaths.get(absPath);
			if (identifier != null) {
				fNodeUsed.remove(identifier);
				fNodeUsed.add(identifier);
			}
			return fPrivileges.get(absPath);
		}
		Activator.getDefault().getLogger(getClass()).debug("JCR privileges '" + absPath + "' not found in JCR cache.");
		return null;
	}

	public void setPrivileges(String absPath, Privilege[] privileges) {
		absPath = JcrPath.valueOf(absPath).with(adaptTo(NamespaceProvider.class)).toString();
		String identifier = fPaths.get(absPath);
		if (identifier != null) {
			fNodeUsed.remove(identifier);
			fNodeUsed.add(identifier);
			int cacheSize = adaptTo(JcrRepository.class).getConfiguration().getNodeCacheSize();
			while (fNodeUsed.size() > cacheSize) {
				remove(fNodeUsed.get(0));
			}
		}
		fPrivileges.put(absPath, privileges);
	}

	public void remove(String identifier) {
		fNodeUsed.remove(identifier);
		AdaptableMap<String, Object> itemData = fNodes.remove(identifier);
		fProperties.remove(identifier);
		if (itemData != null) {
			String path = itemData.getString("item_path");
			fPrivileges.remove(path);
			fPaths.remove(path);
		}
	}

	public void clear() {
		fNodes.clear();
		fPaths.clear();
		fProperties.clear();
		fPrivileges.clear();
		fNodeUsed.clear();
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

}
