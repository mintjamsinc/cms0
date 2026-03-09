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
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;

import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;

public class JcrNodeDefinition implements NodeDefinition, Adaptable {

	private final AdaptableMap<String, Object> fMetadata;
	private final JcrNodeType fNodeType;

	private JcrNodeDefinition(Map<String, Object> metadata, JcrNodeType nodeType) {
		fMetadata = AdaptableMap.<String, Object>newBuilder().putAll(metadata).build();
		fNodeType = nodeType;
	}

	public static JcrNodeDefinition create(Map<String, Object> metadata, JcrNodeType nodeType) {
		return new JcrNodeDefinition(metadata, nodeType);
	}

	@SuppressWarnings("unchecked")
	private List<String> requiredTypes() {
		List<String> types = (List<String>) fMetadata.get("requiredTypes");
		if (types == null) {
			types = new ArrayList<>();
		}
		return types;
	}

	@SuppressWarnings("unchecked")
	private List<String> attributes() {
		List<String> attributes = (List<String>) fMetadata.get("attributes");
		if (attributes == null) {
			attributes = new ArrayList<>();
		}
		return attributes;
	}

	@Override
	public NodeType getDeclaringNodeType() {
		return fNodeType;
	}

	@Override
	public String getName() {
		return fMetadata.getString("name");
	}

	@Override
	public int getOnParentVersion() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean isAutoCreated() {
		return attributes().contains("autocreated");
	}

	@Override
	public boolean isMandatory() {
		return attributes().contains("mandatory");
	}

	@Override
	public boolean isProtected() {
		return attributes().contains("protected");
	}

	@Override
	public boolean allowsSameNameSiblings() {
		return attributes().contains("sns");
	}

	@Override
	public NodeType getDefaultPrimaryType() {
		String name = getDefaultPrimaryTypeName();
		if (name == null) {
			return null;
		}
		try {
			return adaptTo(JcrNodeTypeManager.class).getNodeType(name);
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public String getDefaultPrimaryTypeName() {
		return fMetadata.getString("defaultType");
	}

	@Override
	public String[] getRequiredPrimaryTypeNames() {
		List<String> types = requiredTypes();
		if (types.isEmpty()) {
			return new String[0];
		}
		return types.toArray(String[]::new);
	}

	@Override
	public NodeType[] getRequiredPrimaryTypes() {
		List<String> typeNames = requiredTypes();
		if (typeNames.isEmpty()) {
			return new NodeType[0];
		}
		List<NodeType> types = new ArrayList<>();
		try {
			JcrNodeTypeManager ntm = adaptTo(JcrNodeTypeManager.class);
			for (String name : typeNames) {
				types.add(ntm.getNodeType(name));
			}
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
		return types.toArray(NodeType[]::new);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fNodeType, adapterType);
	}

}
