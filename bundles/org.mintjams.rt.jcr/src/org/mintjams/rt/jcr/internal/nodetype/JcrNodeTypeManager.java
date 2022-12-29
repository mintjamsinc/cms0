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

package org.mintjams.rt.jcr.internal.nodetype;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.nodetype.InvalidNodeTypeDefinitionException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeDefinitionTemplate;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeDefinition;
import javax.jcr.nodetype.NodeTypeExistsException;
import javax.jcr.nodetype.NodeTypeIterator;
import javax.jcr.nodetype.NodeTypeTemplate;
import javax.jcr.nodetype.PropertyDefinition;
import javax.jcr.nodetype.PropertyDefinitionTemplate;

import org.mintjams.jcr.JcrName;
import org.mintjams.jcr.NamespaceProvider;
import org.mintjams.rt.jcr.internal.JcrNamespaceProvider;
import org.mintjams.rt.jcr.internal.JcrWorkspace;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class JcrNodeTypeManager implements org.mintjams.jcr.nodetype.NodeTypeManager, Adaptable {

	private final JcrRootNodeDefinition fRootNodeDefinition;

	private final JcrWorkspace fWorkspace;
	private final Map<String, NodeType> fNodeTypes = new HashMap<>();
	private final List<String> fProtectedProperties = new ArrayList<>();
	private final List<String> fProtectedNodes = new ArrayList<>();

	private JcrNodeTypeManager(JcrWorkspace workspace) throws IOException {
		fWorkspace = workspace;
		fRootNodeDefinition = new JcrRootNodeDefinition(this);
		load();
	}

	public static JcrNodeTypeManager create(JcrWorkspace workspace) throws IOException {
		return new JcrNodeTypeManager(workspace);
	}

	@SuppressWarnings("unchecked")
	private void load() throws IOException {
		try (InputStream in = getClass().getResourceAsStream("nodetypes.yml")) {
			Map<String, Object> root = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
			for (Map<String, Object> e : (List<Map<String, Object>>) root.get("nodeTypes")) {
				JcrNodeType definition = JcrNodeType.create(e, this);
				fNodeTypes.put(definition.getName(), definition);

				for (PropertyDefinition pd : definition.getDeclaredPropertyDefinitions()) {
					if (!pd.isProtected()) {
						continue;
					}

					String propertyName = JcrName.valueOf(pd.getName()).with(getNamespaceProvider()).toString();
					if (fProtectedProperties.contains(propertyName)) {
						continue;
					}
					fProtectedProperties.add(propertyName);
				}

				for (NodeDefinition nd : definition.getDeclaredChildNodeDefinitions()) {
					if (!nd.isProtected()) {
						continue;
					}

					String nodeName = JcrName.valueOf(nd.getName()).with(getNamespaceProvider()).toString();
					if (fProtectedNodes.contains(nodeName)) {
						continue;
					}
					fProtectedNodes.add(nodeName);
				}
			}
		}
	}

	@Override
	public NodeDefinitionTemplate createNodeDefinitionTemplate()
			throws UnsupportedRepositoryOperationException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public NodeTypeTemplate createNodeTypeTemplate()
			throws UnsupportedRepositoryOperationException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public NodeTypeTemplate createNodeTypeTemplate(NodeTypeDefinition ntd)
			throws UnsupportedRepositoryOperationException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public PropertyDefinitionTemplate createPropertyDefinitionTemplate()
			throws UnsupportedRepositoryOperationException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public NodeTypeIterator getAllNodeTypes() throws RepositoryException {
		List<NodeType> l = new ArrayList<>();
		for (NodeType e : fNodeTypes.values()) {
			l.add(e);
		}
		return JcrNodeTypeIterator.create(l);
	}

	@Override
	public NodeTypeIterator getMixinNodeTypes() throws RepositoryException {
		List<NodeType> l = new ArrayList<>();
		for (NodeType e : fNodeTypes.values()) {
			if (e.isMixin()) {
				l.add(e);
			}
		}
		return JcrNodeTypeIterator.create(l);
	}

	@Override
	public NodeType getNodeType(String nodeTypeName) throws NoSuchNodeTypeException, RepositoryException {
		NodeType nodeType = fNodeTypes.get(JcrName.valueOf(nodeTypeName).with(adaptTo(NamespaceProvider.class)).toString());
		if (nodeType == null) {
			throw new NoSuchNodeTypeException(nodeTypeName);
		}

		return nodeType;
	}

	@Override
	public NodeTypeIterator getPrimaryNodeTypes() throws RepositoryException {
		List<NodeType> l = new ArrayList<>();
		for (NodeType e : fNodeTypes.values()) {
			if (!e.isMixin()) {
				l.add(e);
			}
		}
		return JcrNodeTypeIterator.create(l);
	}

	@Override
	public boolean hasNodeType(String nodeTypeName) throws RepositoryException {
		return fNodeTypes.containsKey(JcrName.valueOf(nodeTypeName).with(adaptTo(NamespaceProvider.class)).toString());
	}

	@Override
	public NodeType registerNodeType(NodeTypeDefinition ntd, boolean allowUpdate) throws InvalidNodeTypeDefinitionException,
			NodeTypeExistsException, UnsupportedRepositoryOperationException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public NodeTypeIterator registerNodeTypes(NodeTypeDefinition[] ntds, boolean allowUpdate)
			throws InvalidNodeTypeDefinitionException, NodeTypeExistsException, UnsupportedRepositoryOperationException,
			RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public void unregisterNodeType(String name)
			throws UnsupportedRepositoryOperationException, NoSuchNodeTypeException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public void unregisterNodeTypes(String[] names)
			throws UnsupportedRepositoryOperationException, NoSuchNodeTypeException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public boolean isProtectedProperty(String name) throws RepositoryException {
		return fProtectedProperties.contains(JcrName.valueOf(name).with(getNamespaceProvider()).toString());
	}

	@Override
	public boolean isProtectedNode(String name) throws RepositoryException {
		return fProtectedNodes.contains(JcrName.valueOf(name).with(getNamespaceProvider()).toString());
	}

	public NodeDefinition getRootNodeDefinition() {
		return fRootNodeDefinition;
	}

	private JcrNamespaceProvider getNamespaceProvider() {
		return adaptTo(JcrNamespaceProvider.class);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

}
