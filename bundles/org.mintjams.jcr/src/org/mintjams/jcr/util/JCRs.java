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

package org.mintjams.jcr.util;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import javax.jcr.Binary;
import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.ValueFormatException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.PropertyDefinition;

import org.mintjams.jcr.JcrPath;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class JCRs {

	private JCRs() {}

	public static String normalizePath(String path) {
		String srcPath = Strings.trimToEmpty(path);
		if (Strings.isEmpty(srcPath)) {
			return "";
		}
		if (srcPath.equals("/")) {
			return "/";
		}

		return String.join("/", normalizeAndSplitPath(srcPath));
	}

	public static String[] normalizeAndSplitPath(String path) {
		String srcPath = Strings.trimToEmpty(path);
		if (Strings.isEmpty(srcPath)) {
			return new String[0];
		}

		List<String> l = new ArrayList<>();
		for (String name : splitPath(srcPath)) {
			name = name.trim();
			if (name.equals(".")) {
				continue;
			}

			if (name.equals("..")) {
				if (l.isEmpty()) {
					throw new IllegalArgumentException("Invalid path: " + path);
				}

				l.remove(l.size() - 1);
				continue;
			}

			l.add(name);
		}

		return l.toArray(String[]::new);
	}

	private static String[] splitPath(String path) {
		List<String> l = new ArrayList<>();
		String pathname = "";
		boolean f = false;
		for (char c : path.toCharArray()) {
			if (f) {
				pathname += c;
				continue;
			}

			if (c == '{') {
				pathname += c;
				f = true;
				continue;
			}

			if (c == '}') {
				pathname += c;
				f = false;
				continue;
			}

			if (c == '/') {
				l.add(pathname);
				pathname = "";
				continue;
			}

			pathname += c;
		}
		if (!Strings.isEmpty(pathname)) {
			l.add(pathname);
		}
		return l.toArray(String[]::new);
	}

	public static NodeDefinition findChildNodeDefinition(Node item, String name) throws RepositoryException {
		NodeDefinition any = null;
		NodeDefinition definition = findNodeDefinition(item.getPrimaryNodeType(), name);
		if (definition.getName().equals(name)) {
			return definition;
		}
		if (definition.getName().equals("*")) {
			any = definition;
		}

		for (NodeType type : item.getMixinNodeTypes()) {
			definition = findNodeDefinition(type, name);
			if (definition.getName().equals(name)) {
				return definition;
			}
			if (definition.getName().equals("*")) {
				if (any == null) {
					any = definition;
				}
			}
		}

		if (any != null) {
			return any;
		}

		throw new ConstraintViolationException("Node definition not found.");
	}

	public static PropertyDefinition findPropertyDefinition(Node item, String name) throws RepositoryException {
		PropertyDefinition any = null;
		PropertyDefinition definition = findPropertyDefinition(item.getPrimaryNodeType(), name);
		if (definition.getName().equals(name)) {
			return definition;
		}
		if (definition.getName().equals("*")) {
			any = definition;
		}

		for (NodeType type : item.getMixinNodeTypes()) {
			definition = findPropertyDefinition(type, name);
			if (definition.getName().equals(name)) {
				return definition;
			}
			if (definition.getName().equals("*")) {
				if (any == null) {
					any = definition;
				}
			}
		}

		if (any != null) {
			return any;
		}

		throw new ConstraintViolationException("Node definition not found.");
	}

	public static NodeDefinition findNodeDefinition(NodeType type, String name) {
		NodeDefinition any = null;
		for (NodeDefinition definition : type.getChildNodeDefinitions()) {
			if (definition.getName().equals("*")) {
				any = definition;
				continue;
			}
			if (definition.getName().equals(name)) {
				return definition;
			}
		}

		if (any != null) {
			return any;
		}

		return null;
	}

	public static PropertyDefinition findPropertyDefinition(NodeType type, String name) {
		PropertyDefinition any = null;
		for (PropertyDefinition definition : type.getPropertyDefinitions()) {
			if (definition.getName().equals("*")) {
				any = definition;
				continue;
			}
			if (definition.getName().equals(name)) {
				return definition;
			}
		}

		if (any != null) {
			return any;
		}

		return null;
	}

	public static Node createFolder(Node parentNode, String childName) throws RepositoryException {
		return parentNode.addNode(childName, NodeType.NT_FOLDER);
	}

	public static Node createFile(Node parentNode, String childName) throws RepositoryException {
		Node node = parentNode.addNode(childName, NodeType.NT_FILE);
		Node contentNode = node.addNode(Node.JCR_CONTENT, NodeType.NT_RESOURCE);
		try (org.mintjams.jcr.Binary value = (org.mintjams.jcr.Binary) parentNode.getSession().getValueFactory().createBinary(null)) {
			contentNode.setProperty(Property.JCR_DATA, value);
			return node;
		} catch (IOException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	public static boolean exists(Node parentNode, String childName) throws RepositoryException {
		try {
			parentNode.getNode(childName);
			return true;
		} catch (PathNotFoundException ignore) {
			return false;
		}
	}

	public static Node getOrCreateFolder(Node parentNode, String childName) throws RepositoryException {
		try {
			Node childNode = parentNode.getNode(childName);
			if (!childNode.isNodeType(NodeType.NT_FOLDER)) {
				throw new ItemExistsException("A node with the same name that is not a folder already exists: " + childNode.getPath());
			}
			return childNode;
		} catch (PathNotFoundException ignore) {
			return createFolder(parentNode, childName);
		}
	}

	public static InputStream getContentAsStream(Node node) throws RepositoryException, IOException {
		if (node.isNodeType(NodeType.NT_FILE)) {
			Node contentNode = getContentNode(node);
			return new BufferedInputStream(((org.mintjams.jcr.Binary) contentNode.getProperty(Property.JCR_DATA).getBinary()).getStream());
		}

		if (isVersion(node)) {
			return getContentAsStream(node.getNode(Node.JCR_FROZEN_NODE));
		}

		if (isFrozenNode(node) && node.getName().equals(org.mintjams.jcr.Node.JCR_FROZEN_NODE_NAME)) {
			Node contentNode = getContentNode(node);
			return new BufferedInputStream(((org.mintjams.jcr.Binary) contentNode.getProperty(Property.JCR_DATA).getBinary()).getStream());
		}

		throw new IllegalArgumentException("The specified node is not a file.");
	}

	public static Reader getContentAsReader(Node node) throws RepositoryException, IOException {
		if (node.isNodeType(NodeType.NT_FILE)) {
			Node contentNode = getContentNode(node);
			String encoding = Strings.defaultIfEmpty(getEncoding(node), StandardCharsets.UTF_8.name());
			return new BufferedReader(new InputStreamReader(((org.mintjams.jcr.Binary) contentNode.getProperty(Property.JCR_DATA).getBinary()).getStream(), encoding));
		}

		if (isVersion(node)) {
			return getContentAsReader(node.getNode(Node.JCR_FROZEN_NODE));
		}

		if (isFrozenNode(node) && node.getName().equals(org.mintjams.jcr.Node.JCR_FROZEN_NODE_NAME)) {
			Node contentNode = getContentNode(node);
			String encoding = Strings.defaultIfEmpty(getEncoding(node), StandardCharsets.UTF_8.name());
			return new BufferedReader(new InputStreamReader(((org.mintjams.jcr.Binary) contentNode.getProperty(Property.JCR_DATA).getBinary()).getStream(), encoding));
		}

		throw new IllegalArgumentException("The specified node is not a file.");
	}

	public static java.util.Date getCreatedDate(Node node) throws RepositoryException, IOException {
		return node.getProperty(Property.JCR_CREATED).getDate().getTime();
	}

	public static String getCreatedBy(Node node) throws RepositoryException, IOException {
		return node.getProperty(Property.JCR_CREATED_BY).getString();
	}

	public static boolean isRoot(Node node) throws RepositoryException {
		return (node.getDepth() == 0);
	}

	public static boolean isFolder(Node node) throws RepositoryException {
		return node.isNodeType(NodeType.NT_FOLDER) || node.getDepth() == 0;
	}

	public static boolean isFile(Node node) throws RepositoryException {
		return node.isNodeType(NodeType.NT_FILE);
	}

	public static boolean isVersionHistory(Node node) throws RepositoryException {
		return node.isNodeType(NodeType.NT_VERSION_HISTORY);
	}

	public static boolean isVersion(Node node) throws RepositoryException {
		return node.isNodeType(NodeType.NT_VERSION);
	}

	public static boolean isFrozenNode(Node node) throws RepositoryException {
		return node.isNodeType(NodeType.NT_FROZEN_NODE);
	}

	public static Node getContentNode(Node node) throws RepositoryException {
		return node.getNode(Node.JCR_CONTENT);
	}

	public static boolean isContentNode(Node node) throws RepositoryException {
		return node.getName().equals(org.mintjams.jcr.Node.JCR_CONTENT_NAME);
	}

	public static Node getFileNode(Node node) throws RepositoryException {
		if (isFile(node)) {
			return node;
		}

		if (isRoot(node)) {
			return null;
		}

		return getFileNode(node.getParent());
	}

	public static Node getFileNode(Property property) throws RepositoryException {
		return getFileNode(property.getParent());
	}

	public static Node getFolderNode(Node node) throws RepositoryException {
		if (isFolder(node)) {
			return node;
		}

		if (isRoot(node)) {
			return null;
		}

		return getFolderNode(node.getParent());
	}

	public static Node getVersionHistoryNode(Node node) throws RepositoryException {
		if (isVersionHistory(node)) {
			return node;
		}

		if (isRoot(node)) {
			return null;
		}

		return getVersionHistoryNode(node.getParent());
	}

	public static Node getVersionControlledNode(Node node) throws RepositoryException {
		if (isRoot(node)) {
			return null;
		}

		if (isInSystem(node)) {
			return null;
		}

		if (isFolder(node)) {
			return null;
		}

		if (isContentNode(node)) {
			return getVersionControlledNode(node.getParent());
		}

		if (node.isNodeType(NodeType.MIX_SIMPLE_VERSIONABLE) || node.isNodeType(NodeType.MIX_VERSIONABLE)) {
			return node;
		}

		return getVersionControlledNode(node.getParent());
	}

	public static Node getFileOrFolderNode(Node node) throws RepositoryException {
		if (isFile(node) || isFolder(node)) {
			return node;
		}

		if (isRoot(node)) {
			return null;
		}

		return getFileOrFolderNode(node.getParent());
	}

	public static boolean isInSystem(Node node) throws RepositoryException {
		String path = node.getPath();
		String searchPath = "/" + org.mintjams.jcr.Node.JCR_SYSTEM_NAME;
		return (path.equals(searchPath) || path.startsWith(searchPath + "/"));
	}

	public static java.util.Date getLastModified(Node node) throws RepositoryException, IOException {
		if (isFile(node)) {
			return getContentNode(node).getProperty(Property.JCR_LAST_MODIFIED).getDate().getTime();
		}

		if (isFolder(node)) {
			return getCreatedDate(node);
		}

		if (isVersion(node)) {
			return getLastModified(node.getNode(Node.JCR_FROZEN_NODE));
		}

		if (isFrozenNode(node) && node.getName().equals(org.mintjams.jcr.Node.JCR_FROZEN_NODE_NAME)) {
			return getContentNode(node).getProperty(Property.JCR_LAST_MODIFIED).getDate().getTime();
		}

		throw new IllegalArgumentException("The specified node is neither a file nor a folder.");
	}

	public static String getLastModifiedBy(Node node) throws RepositoryException, IOException {
		if (isFile(node)) {
			return getContentNode(node).getProperty(Property.JCR_LAST_MODIFIED_BY).getString();
		}

		if (isFolder(node)) {
			return getCreatedBy(node);
		}

		if (isVersion(node)) {
			return getLastModifiedBy(node.getNode(Node.JCR_FROZEN_NODE));
		}

		if (isFrozenNode(node) && node.getName().equals(org.mintjams.jcr.Node.JCR_FROZEN_NODE_NAME)) {
			return getContentNode(node).getProperty(Property.JCR_LAST_MODIFIED_BY).getString();
		}

		throw new IllegalArgumentException("The specified node is neither a file nor a folder.");
	}

	public static String getMimeType(Node node) throws RepositoryException {
		if (isFile(node)) {
			Node contentNode = getContentNode(node);
			try {
				return Strings.defaultIfEmpty(contentNode.getProperty(Property.JCR_MIMETYPE).getString(), "application/octet-stream");
			} catch (PathNotFoundException ignore) {}
			return "application/octet-stream";
		}

		if (isFolder(node)) {
			return null;
		}

		if (isVersion(node)) {
			return getMimeType(node.getNode(Node.JCR_FROZEN_NODE));
		}

		if (isFrozenNode(node) && node.getName().equals(org.mintjams.jcr.Node.JCR_FROZEN_NODE_NAME)) {
			Node contentNode = getContentNode(node);
			try {
				return Strings.defaultIfEmpty(contentNode.getProperty(Property.JCR_MIMETYPE).getString(), "application/octet-stream");
			} catch (PathNotFoundException ignore) {}
			return "application/octet-stream";
		}

		throw new IllegalArgumentException("The specified node is not a file.");
	}

	public static String getEncoding(Node node) throws RepositoryException {
		if (isFile(node)) {
			Node contentNode = getContentNode(node);
			try {
				return contentNode.getProperty(Property.JCR_ENCODING).getString();
			} catch (PathNotFoundException ignore) {}
			return null;
		}

		if (isFolder(node)) {
			return null;
		}

		if (isVersion(node)) {
			return getEncoding(node.getNode(Node.JCR_FROZEN_NODE));
		}

		if (isFrozenNode(node) && node.getName().equals(org.mintjams.jcr.Node.JCR_FROZEN_NODE_NAME)) {
			Node contentNode = getContentNode(node);
			try {
				return contentNode.getProperty(Property.JCR_ENCODING).getString();
			} catch (PathNotFoundException ignore) {}
			return null;
		}

		throw new IllegalArgumentException("The specified node is not a file.");
	}

	public static long getContentLength(Node node) throws RepositoryException {
		if (isFile(node)) {
			return getContentNode(node).getProperty(Property.JCR_DATA).getLength();
		}

		if (isFolder(node)) {
			return 0;
		}

		if (isVersion(node)) {
			return getContentLength(node.getNode(Node.JCR_FROZEN_NODE));
		}

		if (isFrozenNode(node) && node.getName().equals(org.mintjams.jcr.Node.JCR_FROZEN_NODE_NAME)) {
			return getContentNode(node).getProperty(Property.JCR_DATA).getLength();
		}

		throw new IllegalArgumentException("The specified node is not a file.");
	}

	public static void write(Node node, InputStream in) throws RepositoryException {
		if (!isFile(node)) {
			throw new IllegalArgumentException("The specified node is not a file.");
		}

		try (org.mintjams.jcr.Binary value = (org.mintjams.jcr.Binary) node.getSession().getValueFactory().createBinary(in)) {
			getContentNode(node).setProperty(Property.JCR_DATA, value);
		} catch (IOException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	public static void setProperty(Node node, String name, Object value) throws RepositoryException {
		if (!isFile(node)) {
			throw new IllegalArgumentException("The specified node is not a file.");
		}

		Node contentNode = getContentNode(node);

		if (value instanceof String) {
			contentNode.setProperty(name, (String) value);
			return;
		}

		if (value instanceof BigDecimal) {
			contentNode.setProperty(name, (BigDecimal) value);
			return;
		}

		if (value instanceof Double) {
			contentNode.setProperty(name, (Double) value);
			return;
		}

		if (value instanceof Long) {
			contentNode.setProperty(name, (Long) value);
			return;
		}

		if (value instanceof Integer) {
			contentNode.setProperty(name, (Integer) value);
			return;
		}

		if (value instanceof Calendar) {
			contentNode.setProperty(name, (Calendar) value);
			return;
		}

		if (value instanceof java.util.Date) {
			Calendar c = Calendar.getInstance();
			c.setTime((java.util.Date) value);
			contentNode.setProperty(name, c);
			return;
		}

		if (value instanceof Boolean) {
			contentNode.setProperty(name, (Boolean) value);
			return;
		}

		if (value instanceof Value) {
			contentNode.setProperty(name, (Value) value);
			return;
		}

		if (value instanceof Value[]) {
			contentNode.setProperty(name, (Value[]) value);
			return;
		}

		if (value instanceof Binary) {
			contentNode.setProperty(name, (Binary) value);
			return;
		}

		if (value instanceof Node) {
			contentNode.setProperty(name, (Node) value);
			return;
		}

		ValueFactory valueFactory = node.getSession().getValueFactory();

		if (value instanceof String[]) {
			contentNode.setProperty(name, Arrays.stream((String[]) value).map(e -> {
				return valueFactory.createValue(e);
			}).toArray(Value[]::new));
			return;
		}

		if (value instanceof BigDecimal[]) {
			contentNode.setProperty(name, Arrays.stream((BigDecimal[]) value).map(e -> {
				return valueFactory.createValue(e);
			}).toArray(Value[]::new));
			return;
		}

		if (value instanceof Double[]) {
			contentNode.setProperty(name, Arrays.stream((Double[]) value).map(e -> {
				return valueFactory.createValue(e);
			}).toArray(Value[]::new));
			return;
		}

		if (value instanceof Long[]) {
			contentNode.setProperty(name, Arrays.stream((Long[]) value).map(e -> {
				return valueFactory.createValue(e);
			}).toArray(Value[]::new));
			return;
		}

		if (value instanceof Integer[]) {
			contentNode.setProperty(name, Arrays.stream((Integer[]) value).map(e -> {
				return valueFactory.createValue(e);
			}).toArray(Value[]::new));
			return;
		}

		if (value instanceof Calendar[]) {
			contentNode.setProperty(name, Arrays.stream((Calendar[]) value).map(e -> {
				return valueFactory.createValue(e);
			}).toArray(Value[]::new));
			return;
		}

		if (value instanceof java.util.Date[]) {
			contentNode.setProperty(name, Arrays.stream((java.util.Date[]) value).map(e -> {
				Calendar c = Calendar.getInstance();
				c.setTime(e);
				return valueFactory.createValue(c);
			}).toArray(Value[]::new));
			return;
		}

		if (value instanceof Boolean[]) {
			contentNode.setProperty(name, Arrays.stream((Boolean[]) value).map(e -> {
				return valueFactory.createValue(e);
			}).toArray(Value[]::new));
			return;
		}

		if (value instanceof byte[]) {
			try (InputStream in = new ByteArrayInputStream((byte[]) value)) {
				try (org.mintjams.jcr.Binary binaryValue = (org.mintjams.jcr.Binary) valueFactory.createBinary(in)) {
					contentNode.setProperty(name, binaryValue);
				}
			} catch (IOException ex) {
				throw Cause.create(ex).wrap(RepositoryException.class);
			}
			return;
		}

		if (value instanceof InputStream) {
			try (org.mintjams.jcr.Binary binaryValue = (org.mintjams.jcr.Binary) valueFactory.createBinary((InputStream) value)) {
				contentNode.setProperty(name, binaryValue);
			} catch (IOException ex) {
				throw Cause.create(ex).wrap(RepositoryException.class);
			}
			return;
		}

		throw new ValueFormatException("Unsupported value type: " + value.getClass().getName());
	}

	public static void removePropertyIfExists(Node node, String name) throws RepositoryException {
		if (!isFile(node)) {
			throw new IllegalArgumentException("The specified node is not a file.");
		}

		Node contentNode = getContentNode(node);
		if (contentNode.hasProperty(name)) {
			contentNode.getProperty(name).remove();
		}
	}

	public static Node mkdirs(JcrPath path, Session session) throws RepositoryException {
		if (path.isRoot()) {
			return session.getRootNode();
		}

		try {
			return session.getNode(path.toString());
		} catch (PathNotFoundException ignore) {}

		Node parent;
		try {
			parent = session.getNode(path.getParent().toString());
		} catch (PathNotFoundException pathNotFound) {
			parent = mkdirs(path.getParent(), session);
		}

		return parent.addNode(path.getName().toString());
	}

}
