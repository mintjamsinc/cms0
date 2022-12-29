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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.jcr.PropertyType;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.PropertyDefinition;

import org.mintjams.rt.jcr.internal.JcrValue;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;

public class JcrPropertyDefinition implements PropertyDefinition, Adaptable {

	private static final Map<String, Integer> STANDARD_TYPES = Collections.unmodifiableMap(AdaptableMap.<String, Integer>newBuilder()
			.put("BINARY", PropertyType.BINARY)
			.put("BOOLEAN", PropertyType.BOOLEAN)
			.put("DATE", PropertyType.DATE)
			.put("DECIMAL", PropertyType.DECIMAL)
			.put("DOUBLE", PropertyType.DOUBLE)
			.put("LONG", PropertyType.LONG)
			.put("STRING", PropertyType.STRING)
			.put("NAME", PropertyType.NAME)
			.put("PATH", PropertyType.PATH)
			.put("URI", PropertyType.URI)
			.put("REFERENCE", PropertyType.REFERENCE)
			.put("WEAKREFERENCE", PropertyType.WEAKREFERENCE)
			.build());

	private final AdaptableMap<String, Object> fMetadata;
	private final JcrNodeType fNodeType;

	private JcrPropertyDefinition(Map<String, Object> metadata, JcrNodeType nodeType) {
		fMetadata = AdaptableMap.<String, Object>newBuilder().putAll(metadata).build();
		fNodeType = nodeType;
	}

	public static JcrPropertyDefinition create(Map<String, Object> metadata, JcrNodeType nodeType) {
		return new JcrPropertyDefinition(metadata, nodeType);
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
	private List<String> valueConstraints() {
		return (List<String>) fMetadata.get("valueConstraints");
	}

	@SuppressWarnings("unchecked")
	private List<String> defaultValues() {
		return (List<String>) fMetadata.get("defaultValues");
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
	public String[] getAvailableQueryOperators() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Value[] getDefaultValues() {
		if (!fMetadata.containsKey("defaultValues")) {
			return null;
		}

		List<Value> l = new ArrayList<>();
		for (String e : defaultValues()) {
			l.add(JcrValue.create(e, getRequiredType()));
		}
		return l.toArray(Value[]::new);
	}

	@Override
	public int getRequiredType() {
		return STANDARD_TYPES.get(fMetadata.getString("type"));
	}

	@Override
	public String[] getValueConstraints() {
		List<String> l = valueConstraints();
		return l.toArray(String[]::new);
	}

	@Override
	public boolean isFullTextSearchable() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isMultiple() {
		return attributes().contains("multiple");
	}

	@Override
	public boolean isQueryOrderable() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fNodeType, adapterType);
	}

}
