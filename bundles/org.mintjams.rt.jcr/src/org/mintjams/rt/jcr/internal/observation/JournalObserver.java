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

package org.mintjams.rt.jcr.internal.observation;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.spi.FileTypeDetector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.Workspace;
import javax.jcr.nodetype.NodeType;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.Privilege;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.observation.Event;
import org.mintjams.jcr.security.EveryonePrincipal;
import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.jcr.internal.Activator;
import org.mintjams.rt.jcr.internal.JcrNode;
import org.mintjams.rt.jcr.internal.JcrProperty;
import org.mintjams.rt.jcr.internal.JcrValue;
import org.mintjams.rt.jcr.internal.JcrWorkspace;
import org.mintjams.rt.jcr.internal.JcrWorkspaceProvider;
import org.mintjams.rt.jcr.internal.WorkspaceQuery;
import org.mintjams.rt.jcr.internal.security.JcrAccessControlEntry;
import org.mintjams.rt.jcr.internal.security.JcrAccessControlList;
import org.mintjams.rt.jcr.internal.security.JcrAccessControlManager;
import org.mintjams.rt.jcr.internal.security.JcrPrivilege;
import org.mintjams.rt.jcr.internal.security.SystemPrincipal;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;
import org.mintjams.tools.sql.Query;

public class JournalObserver implements Adaptable, Closeable {

	private final JcrWorkspaceProvider fWorkspaceProvider;
	private Thread fThread;
	private boolean fCloseRequested;
	private final List<String> fTransactionIdentifiers = new ArrayList<>();

	private JournalObserver(JcrWorkspaceProvider workspaceProvider) {
		fWorkspaceProvider = workspaceProvider;
	}

	public static JournalObserver create(JcrWorkspaceProvider workspaceProvider) {
		return new JournalObserver(workspaceProvider);
	}

	public synchronized JournalObserver open() {
		if (fThread != null) {
			return this;
		}

		fThread = new Thread(new Task());
		fThread.setDaemon(true);
		fThread.start();

		return this;
	}

	public boolean isLive() {
		return (fThread != null && !fCloseRequested);
	}

	public JournalObserver comitted(String id) {
		synchronized (fTransactionIdentifiers) {
			fTransactionIdentifiers.add(id);
			fTransactionIdentifiers.notifyAll();
		}

		return this;
	}

	@Override
	public synchronized void close() throws IOException {
		if (fCloseRequested) {
			return;
		}

		fCloseRequested = true;
		synchronized (fTransactionIdentifiers) {
			fTransactionIdentifiers.notifyAll();
		}
		try {
			fThread.interrupt();
			fThread.join(10000);
		} catch (InterruptedException ignore) {}
		fThread = null;
		fCloseRequested = false;
	}

	public void buildSearchIndex(Node item) throws RepositoryException, IOException {
		String path = item.getPath();
		if (JCRs.isSystemPath(path)) {
			return;
		}

		Node versionControlledNode = JCRs.getVersionControlledNode(item);
		if (versionControlledNode != null && versionControlledNode.isCheckedOut()) {
			return;
		}

		NodeType type = item.getPrimaryNodeType();
		if (type.isNodeType(NodeType.NT_FOLDER)) {
			for (NodeIterator i = item.getNodes(); i.hasNext();) {
				buildSearchIndex(i.nextNode());
			}
			return;
		}

		updateSearchIndex(item);
	}

	private void postEvent(AdaptableMap<String, Object> event) throws RepositoryException {
		String modifier;
		switch (event.getInteger("event_type")) {
		case Event.NODE_ADDED:
			modifier = "ADDED";
			break;
		case Event.NODE_MOVED:
			modifier = "MOVED";
			break;
		case Event.NODE_REMOVED:
			modifier = "REMOVED";
			break;
		default:
			modifier = "CHANGED";
		}

		AdaptableMap<String, Object> p = AdaptableMap.<String, Object>newBuilder()
				.put("identifier", event.getString("item_id"))
				.put("path", event.getString("item_path"))
				.put("type", event.getString("primary_type"))
				.put("workspace", fWorkspaceProvider.getWorkspaceName())
				.build();
		if (event.containsKey("properties")) {
			@SuppressWarnings("unchecked")
			List<String> properties = (List<String>) event.get("properties");
			p.put("properties", properties.toArray(String[]::new));
		}
		if (event.containsKey("source_path")) {
			p.put("source_path", event.getString("source_path"));
		}

		Activator.getDefault().postEvent(Node.class.getName().replace(".", "/") + "/" + modifier, p);
	}

	private void updateSearchIndex(Node item) throws RepositoryException, IOException {
		NodeType type = item.getPrimaryNodeType();
		if (!type.isNodeType(NodeType.NT_FILE)) {
			return;
		}

		String itemId = item.getIdentifier();
		Node contentNode = getContentNode(item);
		if (contentNode == null) {
			return;
		}

		WorkspaceQuery workspaceQuery = Adaptables.getAdapter(item, WorkspaceQuery.class);
		List<String> authorized = getAuthorized(item);

		SearchIndex.DocumentWriter documentWriter = adaptTo(SearchIndex.class).getDocumentWriter();
		try {
			documentWriter.update(document -> {
				try {
					document.setIdentifier(itemId).setPath(item.getPath());

					String mimeType = null;
					try {
						if (contentNode.hasProperty(JcrProperty.JCR_MIMETYPE)) {
							mimeType = contentNode.getProperty(JcrProperty.JCR_MIMETYPE).getString();
						}
						if (Strings.isEmpty(mimeType)) {
							mimeType = adaptTo(FileTypeDetector.class).probeContentType(Path.of(item.getName()));
						}
						if (Strings.isEmpty(mimeType)) {
							mimeType = JcrProperty.DEFAULT_MIMETYPE;
						}
						document.setMimeType(mimeType);
					} catch (PathNotFoundException ignore) {
						mimeType = JcrProperty.DEFAULT_MIMETYPE;
						document.setMimeType(mimeType);
					}

					String encoding = null;
					try {
						if (mimeType.startsWith("text/")) {
							if (contentNode.hasProperty(JcrProperty.JCR_ENCODING)) {
								encoding = contentNode.getProperty(JcrProperty.JCR_ENCODING).getString();
							}
							if (Strings.isEmpty(encoding)) {
								encoding = StandardCharsets.UTF_8.name();
							}
							document.setEncoding(encoding);
						}
					} catch (PathNotFoundException ignore) {
						document.setEncoding(null);
					}

					try {
						Property dataProperty = contentNode.getProperty(JcrProperty.JCR_DATA);
						if (mimeType.startsWith("text/")) {
							String text = dataProperty.getString();
							document.setSize(text.getBytes(encoding).length);
							document.setContent(text);
						} else {
							document.setSize(dataProperty.getLength());
							document.setContent(Adaptables.getAdapter(dataProperty.getValue(), Path.class));
						}
					} catch (PathNotFoundException ignore) {
						document.setSize(0);
						document.setContent("");
					}

					List<String> primaryTypes = new ArrayList<>();
					primaryTypes.addAll(getPrimaryTypes(item));
					for (String name : getPrimaryTypes(contentNode)) {
						if (!primaryTypes.contains(name)) {
							primaryTypes.add(name);
						}
					}
					for (String name : primaryTypes) {
						addProperty(document, JcrProperty.JCR_PRIMARY_TYPE_NAME,
								JcrValue.create(name, PropertyType.STRING));
					}

					List<String> mixinTypes = new ArrayList<>();
					mixinTypes.addAll(getMixinTypes(item));
					for (String name : getMixinTypes(contentNode)) {
						if (!mixinTypes.contains(name)) {
							mixinTypes.add(name);
						}
					}
					for (String name : mixinTypes) {
						addProperty(document, JcrProperty.JCR_MIXIN_TYPES_NAME,
								JcrValue.create(name, PropertyType.STRING));
					}

					document.setCreated(item.getProperty(JcrProperty.JCR_CREATED_NAME).getDate().getTime());
					document.setCreatedBy(item.getProperty(JcrProperty.JCR_CREATED_BY_NAME).getString());
					document.setLastModified(contentNode.getProperty(JcrProperty.JCR_LAST_MODIFIED_NAME).getDate().getTime());
					document.setLastModifiedBy(contentNode.getProperty(JcrProperty.JCR_LAST_MODIFIED_BY_NAME).getString());

					for (Node node : new Node[] { item, contentNode }) {
						for (PropertyIterator i = node.getProperties(); i.hasNext();) {
							Property property = i.nextProperty();
							if (property.getName().equals(JcrProperty.JCR_PRIMARY_TYPE_NAME)
									|| property.getName().equals(JcrProperty.JCR_MIXIN_TYPES_NAME)
									|| property.getName().equals(JcrProperty.JCR_DATA_NAME)
									|| property.getName().equals(JcrProperty.JCR_CREATED_NAME)
									|| property.getName().equals(JcrProperty.JCR_CREATED_BY_NAME)
									|| property.getName().equals(JcrProperty.JCR_LAST_MODIFIED_NAME)
									|| property.getName().equals(JcrProperty.JCR_LAST_MODIFIED_BY_NAME)) {
								continue;
							}

							if (property.isMultiple()) {
								for (Value value : property.getValues()) {
									addProperty(document, property.getName(), value);
								}
							} else {
								addProperty(document, property.getName(), property.getValue());
							}
						}
					}

					for (String name : authorized) {
						document.addAuthorized(name);
					}

					return document;
				} catch (Throwable ex) {
					Activator.getDefault().getLogger(JournalObserver.class).error("An error occurred while creating the index: " + itemId, ex);
					throw Cause.create(ex).wrap(IllegalStateException.class);
				}
			});
			documentWriter.commit();
		} catch (Throwable ex) {
			try {
				documentWriter.rollback();
			} catch (Throwable ignore) {}
			throw Cause.create(ex).wrap(IOException.class);
		}

		SearchIndex.SuggestionWriter suggestionWriter = adaptTo(SearchIndex.class).getSuggestionWriter();
		try {
			suggestionWriter.delete(itemId);
			List<String> suggestions = new ArrayList<>();
			for (String key : fWorkspaceProvider.getConfiguration().getSuggestionPropertyKeys()) {
				key = workspaceQuery.getResolved(key);
				if (!contentNode.hasProperty(key)) {
					continue;
				}

				Property p = contentNode.getProperty(key);
				if (!p.isMultiple()) {
					for (String v : p.getString().split("\r?\n|\r")) {
						if (Strings.isEmpty(v)) {
							continue;
						}
						suggestions.add(v);
					}
				} else {
					for (Value v : p.getValues()) {
						String s = v.getString();
						if (Strings.isEmpty(s)) {
							continue;
						}
						suggestions.add(s);
					}
				}
			}
			if (!suggestions.isEmpty()) {
				for (String suggestionText : suggestions) {
					suggestionWriter.update(suggestion -> {
						try {
							suggestion.setIdentifier(itemId).setPath(item.getPath());

							String mimeType = null;
							try {
								if (contentNode.hasProperty(JcrProperty.JCR_MIMETYPE)) {
									mimeType = contentNode.getProperty(JcrProperty.JCR_MIMETYPE).getString();
								}
								if (Strings.isEmpty(mimeType)) {
									mimeType = adaptTo(FileTypeDetector.class).probeContentType(Path.of(item.getName()));
								}
								if (Strings.isEmpty(mimeType)) {
									mimeType = JcrProperty.DEFAULT_MIMETYPE;
								}
								suggestion.setMimeType(mimeType);
							} catch (PathNotFoundException ignore) {
								mimeType = JcrProperty.DEFAULT_MIMETYPE;
								suggestion.setMimeType(mimeType);
							}

							String encoding = null;
							try {
								if (mimeType.startsWith("text/")) {
									if (contentNode.hasProperty(JcrProperty.JCR_ENCODING)) {
										encoding = contentNode.getProperty(JcrProperty.JCR_ENCODING).getString();
									}
									if (Strings.isEmpty(encoding)) {
										encoding = StandardCharsets.UTF_8.name();
									}
									suggestion.setEncoding(encoding);
								}
							} catch (PathNotFoundException ignore) {
								suggestion.setEncoding(null);
							}

							try {
								Property dataProperty = contentNode.getProperty(JcrProperty.JCR_DATA);
								if (mimeType.startsWith("text/")) {
									String text = dataProperty.getString();
									suggestion.setSize(text.getBytes(encoding).length);
									suggestion.setContent(text);
								} else {
									suggestion.setSize(dataProperty.getLength());
									suggestion.setContent(Adaptables.getAdapter(dataProperty.getValue(), Path.class));
								}
							} catch (PathNotFoundException ignore) {
								suggestion.setSize(0);
								suggestion.setContent("");
							}

							List<String> primaryTypes = new ArrayList<>();
							primaryTypes.addAll(getPrimaryTypes(item));
							for (String name : getPrimaryTypes(contentNode)) {
								if (!primaryTypes.contains(name)) {
									primaryTypes.add(name);
								}
							}
							for (String name : primaryTypes) {
								addProperty(suggestion, JcrProperty.JCR_PRIMARY_TYPE_NAME,
										JcrValue.create(name, PropertyType.STRING));
							}

							List<String> mixinTypes = new ArrayList<>();
							mixinTypes.addAll(getMixinTypes(item));
							for (String name : getMixinTypes(contentNode)) {
								if (!mixinTypes.contains(name)) {
									mixinTypes.add(name);
								}
							}
							for (String name : mixinTypes) {
								addProperty(suggestion, JcrProperty.JCR_MIXIN_TYPES_NAME,
										JcrValue.create(name, PropertyType.STRING));
							}

							suggestion.setCreated(item.getProperty(JcrProperty.JCR_CREATED_NAME).getDate().getTime());
							suggestion.setCreatedBy(item.getProperty(JcrProperty.JCR_CREATED_BY_NAME).getString());
							suggestion.setLastModified(contentNode.getProperty(JcrProperty.JCR_LAST_MODIFIED_NAME).getDate().getTime());
							suggestion.setLastModifiedBy(contentNode.getProperty(JcrProperty.JCR_LAST_MODIFIED_BY_NAME).getString());

							for (Node node : new Node[] { item, contentNode }) {
								for (PropertyIterator i = node.getProperties(); i.hasNext();) {
									Property property = i.nextProperty();
									if (property.getName().equals(JcrProperty.JCR_PRIMARY_TYPE_NAME)
											|| property.getName().equals(JcrProperty.JCR_MIXIN_TYPES_NAME)
											|| property.getName().equals(JcrProperty.JCR_DATA_NAME)
											|| property.getName().equals(JcrProperty.JCR_CREATED_NAME)
											|| property.getName().equals(JcrProperty.JCR_CREATED_BY_NAME)
											|| property.getName().equals(JcrProperty.JCR_LAST_MODIFIED_NAME)
											|| property.getName().equals(JcrProperty.JCR_LAST_MODIFIED_BY_NAME)) {
										continue;
									}

									if (property.isMultiple()) {
										for (Value value : property.getValues()) {
											addProperty(suggestion, property.getName(), value);
										}
									} else {
										addProperty(suggestion, property.getName(), property.getValue());
									}
								}
							}

							suggestion.setSuggestion(suggestionText);

							for (String name : authorized) {
								suggestion.addAuthorized(name);
							}

							return suggestion;
						} catch (Throwable ex) {
							Activator.getDefault().getLogger(JournalObserver.class).error("An error occurred while creating the index: " + itemId, ex);
							throw Cause.create(ex).wrap(IllegalStateException.class);
						}
					});
				}
			}
			suggestionWriter.commit();
		} catch (Throwable ex) {
			try {
				suggestionWriter.rollback();
			} catch (Throwable ignore) {}
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	private void updateSearchIndex(AdaptableMap<String, Object> eventData, Node item) throws RepositoryException, IOException {
		NodeType type = item.getPrimaryNodeType();

		if (type.isNodeType(NodeType.NT_FOLDER)) {
			if (eventData.containsKey("path") || eventData.containsKey("acl")) {
				for (NodeIterator i = item.getNodes(); i.hasNext();) {
					Node childItem = i.nextNode();
					AdaptableMap<String, Object> childEvent = AdaptableMap.<String, Object>newBuilder()
							.put("item_id", childItem.getIdentifier())
							.put("item_path", childItem.getPath())
							.put("primary_type", childItem.getPrimaryNodeType().getName())
							.put("event_type", eventData.getInteger("event_type"))
							.build();
					if (eventData.containsKey("path")) {
						childEvent.put("path", eventData.get("path"));
					}
					if (eventData.containsKey("acl")) {
						childEvent.put("acl", eventData.get("acl"));
					}
					updateSearchIndex(childEvent, childItem);
				}
			}
			return;
		}

		updateSearchIndex(item);
	}

	private Node getContentNode(Node item) throws RepositoryException {
		try {
			return item.getNode(JcrNode.JCR_CONTENT_NAME);
		} catch (PathNotFoundException ignore) {
			if (item.hasProperty(JcrNode.JCR_CONTENT_NAME)) {
				Property p = item.getProperty(JcrNode.JCR_CONTENT_NAME);
				if (p.getType() == PropertyType.REFERENCE) {
					return p.getNode();
				}
			}
		}
		return null;
	}

	private void addProperty(SearchIndex.Document document, String name, Value value) throws ValueFormatException, RepositoryException {
		int type = value.getType();
		if (type == PropertyType.BINARY || type == PropertyType.NAME || type == PropertyType.PATH
				|| type == PropertyType.URI || type == PropertyType.REFERENCE || type == PropertyType.WEAKREFERENCE) {
			document.addProperty(name, "");
			return;
		}

		if (type == PropertyType.BOOLEAN) {
			document.addProperty(name, value.getBoolean());
			return;
		}

		if (type == PropertyType.DATE) {
			document.addProperty(name, value.getDate());
			return;
		}

		if (type == PropertyType.DECIMAL || type == PropertyType.DOUBLE || type == PropertyType.LONG) {
			document.addProperty(name, value.getDecimal());
			return;
		}

		document.addProperty(name, value.getString());
	}

	private void addProperty(SearchIndex.Suggestion suggestion, String name, Value value) throws ValueFormatException, RepositoryException {
		int type = value.getType();
		if (type == PropertyType.BINARY || type == PropertyType.NAME || type == PropertyType.PATH
				|| type == PropertyType.URI || type == PropertyType.REFERENCE || type == PropertyType.WEAKREFERENCE) {
			suggestion.addProperty(name, "");
			return;
		}

		if (type == PropertyType.BOOLEAN) {
			suggestion.addProperty(name, value.getBoolean());
			return;
		}

		if (type == PropertyType.DATE) {
			suggestion.addProperty(name, value.getDate());
			return;
		}

		if (type == PropertyType.DECIMAL || type == PropertyType.DOUBLE || type == PropertyType.LONG) {
			suggestion.addProperty(name, value.getDecimal());
			return;
		}

		suggestion.addProperty(name, value.getString());
	}

	private List<String> getPrimaryTypes(Node item) throws RepositoryException {
		List<String> l = new ArrayList<>();
		NodeType primaryType = item.getPrimaryNodeType();
		l.add(primaryType.getName());
		for (NodeType superType : primaryType.getSupertypes()) {
			if (!superType.isMixin()) {
				l.add(superType.getName());
			}
		}
		return l;
	}

	private List<String> getMixinTypes(Node item) throws RepositoryException {
		List<String> l = new ArrayList<>();
		for (NodeType mixinType : item.getMixinNodeTypes()) {
			if (l.contains(mixinType.getName())) {
				continue;
			}
			l.add(mixinType.getName());
			for (NodeType e : mixinType.getSupertypes()) {
				if (!l.contains(e.getName())) {
					l.add(e.getName());
				}
			}
		}
		return l;
	}

	private List<String> getAuthorized(Node item) throws RepositoryException {
		List<String> l = new ArrayList<>();
		Privilege read = Adaptables.getAdapter(item, JcrAccessControlManager.class).privilegeFromName(Privilege.JCR_READ);
		AccessControlPolicy[] policies = item.getSession().getAccessControlManager().getEffectivePolicies(item.getPath());
		Collections.reverse(Arrays.asList(policies));
		for (AccessControlPolicy acp : policies) {
			for (AccessControlEntry ace : ((JcrAccessControlList) acp).getAccessControlEntries()) {
				for (Privilege p : ace.getPrivileges()) {
					if (!(p.equals(read) || ((JcrPrivilege) p).contains(read))) {
						continue;
					}

					String grantee = ace.getPrincipal().getName();
					if (ace.getPrincipal() instanceof GroupPrincipal) {
						grantee += "@group";
					} else {
						grantee += "@user";
					}

					if (((JcrAccessControlEntry) ace).isAllow()) {
						if (!l.contains(grantee)) {
							l.add(grantee);
						}
					} else {
						if (grantee.equals(EveryonePrincipal.NAME)) {
							l.clear();
						} else {
							l.remove(grantee);
						}
					}
				}
			}
		}
		return l;
	}

	private void removeSearchIndex(AdaptableMap<String, Object> eventData, Workspace workspace) throws IOException {
		NodeType type;
		try {
			type = workspace.getNodeTypeManager().getNodeType(eventData.getString("primary_type"));
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IOException.class);
		}

		if (!type.isNodeType(NodeType.NT_FOLDER)) {
			SearchIndex.DocumentWriter indexWriter = adaptTo(SearchIndex.class).getDocumentWriter();
			try {
				indexWriter.delete(eventData.getString("item_id"));
				indexWriter.commit();
			} catch (Throwable ex) {
				try {
					indexWriter.rollback();
				} catch (Throwable ignore) {}
				throw Cause.create(ex).wrap(IOException.class);
			}
			return;
		}

		SearchIndex.DocumentWriter documentWriter = adaptTo(SearchIndex.class).getDocumentWriter();
		try {
			StringBuilder stmt = new StringBuilder("/jcr:root").append(eventData.getString("item_path")).append("//*");
			for (;;) {
				SearchIndex.QueryResult.Row[] rows = adaptTo(SearchIndex.class)
						.createQuery(stmt.toString(), "jcr:xpath").setOffset(0).setLimit(100).execute().toArray();
				if (rows.length == 0) {
					break;
				}

				documentWriter.delete(Arrays.stream(rows).map(e -> e.getIdentifier()).toArray(String[]::new));
			}
			documentWriter.commit();
		} catch (Throwable ex) {
			try {
				documentWriter.rollback();
			} catch (Throwable ignore) {}
			throw Cause.create(ex).wrap(IOException.class);
		}

		SearchIndex.SuggestionWriter suggestionWriter = adaptTo(SearchIndex.class).getSuggestionWriter();
		try {
			suggestionWriter.delete(eventData.getString("item_id"));
			suggestionWriter.commit();
		} catch (Throwable ex) {
			try {
				suggestionWriter.rollback();
			} catch (Throwable ignore) {}
			throw Cause.create(ex).wrap(IOException.class);
		}
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspaceProvider, adapterType);
	}

	private class Task implements Runnable {
		@Override
		public void run() {
			while (!fCloseRequested) {
				if (Thread.interrupted()) {
					fCloseRequested = true;
					break;
				}
				String transactionId;
				synchronized (fTransactionIdentifiers) {
					if (fTransactionIdentifiers.isEmpty()) {
						try {
							fTransactionIdentifiers.wait();
						} catch (InterruptedException ignore) {}
						continue;
					}

					transactionId = fTransactionIdentifiers.remove(0);
				}

				try (JcrWorkspace workspace = fWorkspaceProvider.createSession(new SystemPrincipal())) {
					WorkspaceQuery workspaceQuery = Adaptables.getAdapter(workspace, WorkspaceQuery.class);
					try (Query.Result result = workspaceQuery.journal().listJournal(transactionId)) {
						Map<String, AdaptableMap<String, Object>> events = new LinkedHashMap<>();
						for (AdaptableMap<String, Object> r : result) {
							String id = r.getString("item_id");
							String path = r.getString("item_path");
							int eventType = r.getInteger("event_type");
							String primaryType = r.getString("primary_type");

							if (path.equals("/" + JcrNode.JCR_SYSTEM_NAME) || path.startsWith("/" + JcrNode.JCR_SYSTEM_NAME + "/")) {
								continue;
							}

							if (eventType == Event.NODE_ADDED) {
								do {
									if (path.endsWith(JcrNode.JCR_CONTENT_NAME)) {
										break;
									}

									events.put(id, AdaptableMap.<String, Object>newBuilder()
											.put("item_id", id)
											.put("item_path", path)
											.put("primary_type", primaryType)
											.put("event_type", eventType)
											.build());
								} while (false);
								continue;
							}

							if (eventType == Event.NODE_MOVED) {
								do {
									if (path.endsWith(JcrNode.JCR_CONTENT_NAME)) {
										break;
									}

									AdaptableMap<String, Object> event = events.get(id);
									if (event == null) {
										events.put(id, AdaptableMap.<String, Object>newBuilder()
												.put("item_id", id)
												.put("item_path", path)
												.put("primary_type", primaryType)
												.put("event_type", eventType)
												.put("source_path", r.getString("source_path"))
												.put("path", true)
												.build());
									} else {
										event.put("item_path", path);
										if (event.getInteger("event_type") != Event.NODE_ADDED) {
											event.put("event_type", Event.NODE_MOVED);
										}
										if (!event.containsKey("source_path")) {
											event.put("source_path", r.getString("source_path"));
										}
										event.put("path", true);
									}
								} while (false);
								continue;
							}

							if (eventType == Event.NODE_REMOVED) {
								do {
									if (path.endsWith(JcrNode.JCR_CONTENT_NAME)) {
										break;
									}

									AdaptableMap<String, Object> event = events.get(id);
									if (event == null) {
										events.put(id, AdaptableMap.<String, Object>newBuilder()
												.put("item_id", id)
												.put("item_path", path)
												.put("primary_type", primaryType)
												.put("event_type", eventType)
												.build());
									} else {
										if (event.getInteger("event_type") == Event.NODE_ADDED) {
											events.remove(event.getString("item_id"));
											break;
										}

										event.put("event_type", eventType);
									}
								} while (false);
								continue;
							}

							if (eventType == Event.PROPERTY_ADDED
									|| eventType == Event.PROPERTY_CHANGED
									|| eventType == Event.PROPERTY_REMOVED) {
								JcrPath itemPath = JcrPath.valueOf(path);
								if (itemPath.getName().toString().equals(JcrNode.JCR_CONTENT_NAME)) {
									itemPath = itemPath.getParent();
								}

								Node item;
								try {
									item = workspace.getSession().getNode(itemPath.toString());
								} catch (ItemNotFoundException ignore) {
									continue;
								}

								AdaptableMap<String, Object> event = events.get(item.getIdentifier());
								if (event == null) {
									List<String> properties = new ArrayList<>();
									properties.add(r.getString("property_name"));
									events.put(item.getIdentifier(), AdaptableMap.<String, Object>newBuilder()
											.put("item_id", item.getIdentifier())
											.put("item_path", itemPath.toString())
											.put("primary_type", item.getPrimaryNodeType().getName())
											.put("event_type", eventType)
											.put("properties", properties)
											.build());
								} else {
									@SuppressWarnings("unchecked")
									List<String> properties = (List<String>) event.get("properties");
									if (properties == null) {
										properties = new ArrayList<>();
										event.put("properties", properties);
									}
									properties.add(r.getString("property_name"));
								}
								continue;
							}

							if (eventType == Event.ACCESS_CONTROL_POLICY_CHANGED
									|| eventType == Event.ACCESS_CONTROL_POLICY_REMOVED) {
								do {
									if (path.endsWith(JcrNode.JCR_CONTENT_NAME)) {
										break;
									}

									AdaptableMap<String, Object> event = events.get(id);
									if (event == null) {
										events.put(id, AdaptableMap.<String, Object>newBuilder()
												.put("item_id", id)
												.put("item_path", path)
												.put("primary_type", primaryType)
												.put("event_type", eventType)
												.put("acl", true)
												.build());
									} else {
										event.put("acl", true);
									}
								} while (false);
								continue;
							}
						}

						for (String id : events.keySet()) {
							AdaptableMap<String, Object> event = events.get(id);
							postEvent(event);
							try {
								Node item = workspace.getSession().getNodeByIdentifier(id);
								updateSearchIndex(event, item);
							} catch (ItemNotFoundException ignore) {
								removeSearchIndex(event, workspace);
							}
							workspace.getSession().refresh(true);
						}
					}
				} catch (Throwable ex) {
					Activator.getDefault().getLogger(JournalObserver.class).error("An error occurred while writing the journal: " + transactionId, ex);
					if (!fCloseRequested) {
						try {
							Thread.sleep(5000);
						} catch (InterruptedException ignore) {}
					}
					continue;
				}
			}
		}
	}

}
