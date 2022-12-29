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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeIterator;
import javax.jcr.nodetype.PropertyDefinition;

import org.mintjams.rt.jcr.internal.WorkspaceQuery;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;

public class JcrNodeType implements org.mintjams.jcr.nodetype.NodeType, Adaptable {

	private final AdaptableMap<String, Object> fMetadata;
	private final JcrNodeTypeManager fNodeTypeManager;

	private JcrNodeType(Map<String, Object> metadata, JcrNodeTypeManager nodeTypeManager) {
		fMetadata = AdaptableMap.<String, Object>newBuilder().putAll(metadata).build();
		fNodeTypeManager = nodeTypeManager;
	}

	public static JcrNodeType create(Map<String, Object> metadata, JcrNodeTypeManager nodeTypeManager) {
		return new JcrNodeType(metadata, nodeTypeManager);
	}

	@SuppressWarnings("unchecked")
	private List<String> attributes() {
		List<String> attributes = (List<String>) fMetadata.get("attributes");
		if (attributes == null) {
			attributes = new ArrayList<>();
		}
		return attributes;
	}

	@SuppressWarnings("unchecked")
	private List<String> superTypes() {
		return (List<String>) fMetadata.get("superTypes");
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> propertyDefinitions() {
		if (!fMetadata.containsKey("propertyDefinitions")) {
			return new ArrayList<Map<String,Object>>();
		}
		return (List<Map<String, Object>>) fMetadata.get("propertyDefinitions");
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> childNodeDefinitions() {
		if (!fMetadata.containsKey("childNodeDefinitions")) {
			return new ArrayList<Map<String,Object>>();
		}
		return (List<Map<String, Object>>) fMetadata.get("childNodeDefinitions");
	}

	@Override
	public NodeDefinition[] getDeclaredChildNodeDefinitions() {
		List<NodeDefinition> l = new ArrayList<>();
		for (Map<String, Object> e : propertyDefinitions()) {
			l.add(JcrNodeDefinition.create(e, this));
		}
		return l.toArray(NodeDefinition[]::new);
	}

	@Override
	public PropertyDefinition[] getDeclaredPropertyDefinitions() {
		List<PropertyDefinition> l = new ArrayList<>();
		for (Map<String, Object> e : propertyDefinitions()) {
			l.add(JcrPropertyDefinition.create(e, this));
		}
		return l.toArray(PropertyDefinition[]::new);
	}

	@Override
	public String[] getDeclaredSupertypeNames() {
		List<String> l = superTypes();
		if (l == null) {
			return new String[0];
		}
		return l.toArray(String[]::new);
	}

	@Override
	public String getName() {
		return fMetadata.getString("name");
	}

	@Override
	public String getPrimaryItemName() {
		return fMetadata.getString("primaryItem");
	}

	@Override
	public boolean hasOrderableChildNodes() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isAbstract() {
		return attributes().contains("abstract");
	}

	@Override
	public boolean isMixin() {
		return attributes().contains("mixin");
	}

	@Override
	public boolean isQueryable() {
		return attributes().contains("query");
	}

	@Override
	public boolean canAddChildNode(String childNodeName) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean canAddChildNode(String childNodeName, String nodeTypeName) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean canRemoveItem(String itemName) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean canRemoveNode(String nodeName) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean canRemoveProperty(String propertyName) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean canSetProperty(String propertyName, Value value) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean canSetProperty(String propertyName, Value[] values) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public NodeDefinition[] getChildNodeDefinitions() {
		Map<String, NodeDefinition> m = new HashMap<>();
		for (Map<String, Object> e : childNodeDefinitions()) {
			JcrNodeDefinition nodeDefinition = JcrNodeDefinition.create(e, this);
			m.put(nodeDefinition.getName(), nodeDefinition);
		}
		for (NodeType type : getSupertypes()) {
			for (NodeDefinition e : type.getChildNodeDefinitions()) {
				if (!m.containsKey(e.getName())) {
					m.put(e.getName(), e);
				}
			}
		}
		return m.values().toArray(NodeDefinition[]::new);
	}

	@Override
	public NodeTypeIterator getDeclaredSubtypes() {
		List<NodeType> l = new ArrayList<>();
		try {
			for (NodeTypeIterator i = fNodeTypeManager.getAllNodeTypes(); i.hasNext();) {
				NodeType subType = i.nextNodeType();
				if (Arrays.asList(subType.getDeclaredSupertypeNames()).contains(getName())) {
					l.add(subType);
				}
			}
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
		return JcrNodeTypeIterator.create(l);
	}

	@Override
	public NodeType[] getDeclaredSupertypes() {
		List<NodeType> l = new ArrayList<>();
		for (String nodeTypeName : getDeclaredSupertypeNames()) {
			try {
				l.add(fNodeTypeManager.getNodeType(nodeTypeName));
			} catch (RepositoryException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
		return l.toArray(NodeType[]::new);
	}

	@Override
	public PropertyDefinition[] getPropertyDefinitions() {
		Map<String, PropertyDefinition> m = new HashMap<>();
		for (Map<String, Object> e : propertyDefinitions()) {
			JcrPropertyDefinition propertyDefinition = JcrPropertyDefinition.create(e, this);
			m.put(propertyDefinition.getName(), propertyDefinition);
		}
		for (NodeType type : getSupertypes()) {
			for (PropertyDefinition e : type.getPropertyDefinitions()) {
				if (!m.containsKey(e.getName())) {
					m.put(e.getName(), e);
				}
			}
		}
		return m.values().toArray(PropertyDefinition[]::new);
	}

	@Override
	public NodeTypeIterator getSubtypes() {
		Map<String, NodeType> m = new HashMap<>();
		for (NodeTypeIterator i = getDeclaredSubtypes(); i.hasNext();) {
			NodeType type = i.nextNodeType();
			if (m.containsKey(type.getName())) {
				continue;
			}

			m.put(type.getName(), type);

			for (NodeTypeIterator j = type.getSubtypes(); j.hasNext();) {
				NodeType subType = j.nextNodeType();
				if (!m.containsKey(subType.getName())) {
					m.put(subType.getName(), subType);
				}
			}
		}
		return JcrNodeTypeIterator.create(m.values());
	}

	@Override
	public NodeType[] getSupertypes() {
		Map<String, NodeType> m = new HashMap<>();
		for (NodeType type : getDeclaredSupertypes()) {
			if (m.containsKey(type.getName())) {
				continue;
			}

			m.put(type.getName(), type);

			for (NodeType superType : type.getSupertypes()) {
				if (!m.containsKey(superType.getName())) {
					m.put(superType.getName(), superType);
				}
			}
		}
		return m.values().toArray(NodeType[]::new);
	}

	@Override
	public boolean isNodeType(String nodeTypeName) {
		nodeTypeName = getWorkspaceQuery().getResolved(nodeTypeName);
		if (getName().equals(nodeTypeName)) {
			return true;
		}
		for (NodeType type : getSupertypes()) {
			if (type.getName().equals(nodeTypeName)) {
				return true;
			}
		}
		return false;
	}

	private WorkspaceQuery getWorkspaceQuery() {
		return adaptTo(WorkspaceQuery.class);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fNodeTypeManager, adapterType);
	}

}
