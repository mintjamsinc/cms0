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

package org.mintjams.rt.cms.internal.job.archive;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;

/**
 * Applies the per-node records written by {@link NodeSerializer} back onto the
 * repository — the read half of the CMS Archive format, kept beside the writer
 * so the two can never silently drift. See
 * {@code documents/cms-archive-export-import.md}.
 *
 * <p>Every property is imported with its original JCR type. Reference and
 * weak-reference properties are <em>not</em> set here: their targets may not
 * exist yet, so they are collected as {@link DeferredRef}s for the importer to
 * resolve in a second pass once all nodes (and thus all identities) exist.
 * Binary values are read back from the archive — a file body from the content
 * tree, any other binary from {@code .cms-archive/blobs/}.
 */
public final class NodeDeserializer {

	private final Session fSession;
	private final ValueFactory fValueFactory;
	private final ZipFile fZip;
	private final java.util.Set<String> fProtectedNames;

	public NodeDeserializer(Session session, ZipFile zip) throws Exception {
		fSession = session;
		fValueFactory = session.getValueFactory();
		fZip = zip;
		// The same name-based authority the exporter used to decide what to omit
		// and the repository uses to reject writes (see NodeSerializer). A
		// well-formed archive never carries a protected property, but an archive
		// from another producer might — guard so one bad field can't fail the
		// whole import.
		fProtectedNames = NodeSerializer.collectProtectedPropertyNames(session);
	}

	/**
	 * A reference property whose values must be resolved after all nodes exist.
	 * {@link #targets} holds the source identifiers as stored in the archive;
	 * the importer translates them (identity-preserving: unchanged; new identity:
	 * via the old→new map) before setting the property.
	 */
	public static final class DeferredRef {
		public final String nodePath;
		public final String name;
		public final boolean weak;
		public final boolean multiple;
		public final List<String> targets;

		DeferredRef(String nodePath, String name, boolean weak, boolean multiple, List<String> targets) {
			this.nodePath = nodePath;
			this.name = name;
			this.weak = weak;
			this.multiple = multiple;
			this.targets = targets;
		}
	}

	/**
	 * Import every property of {@code node} from its archive record, deferring
	 * references into {@code deferred}.
	 *
	 * @param node      the freshly created (or merged) target node.
	 * @param props     the {@code properties} array from the node's JSON record.
	 * @param bodyEntry the content-tree ZIP entry holding this node's file body,
	 *                  used to satisfy a {@code jcr:data} body marker; may be
	 *                  {@code null} when the node has no body.
	 * @param deferred  accumulator for reference properties to resolve later.
	 */
	@SuppressWarnings("unchecked")
	public void applyProperties(Node node, List<Map<String, Object>> props, String bodyEntry,
			List<DeferredRef> deferred) throws Exception {
		if (props == null) {
			return;
		}
		for (Map<String, Object> prop : props) {
			String name = (String) prop.get("name");
			// The repository computes and manages protected properties itself
			// (jcr:created, jcr:createdBy, version/lock state, the structural
			// identity properties). The serializer aims to omit them, but guard
			// here too: an archive may carry one that slipped through, and setting a
			// protected property throws and would fail the whole import. The check
			// is name-based — the exact authority the repository's setProperty
			// consults — so it never disagrees with what the write would accept.
			if (fProtectedNames.contains(name)) {
				continue;
			}
			int type = PropertyType.valueFromName((String) prop.get("type"));
			boolean multiple = Boolean.TRUE.equals(prop.get("multiple"));
			Object raw = prop.get("value");

			if (type == PropertyType.REFERENCE || type == PropertyType.WEAKREFERENCE) {
				List<String> targets = new ArrayList<>();
				if (multiple) {
					for (Object o : (List<Object>) raw) {
						targets.add(String.valueOf(o));
					}
				} else {
					targets.add(String.valueOf(raw));
				}
				deferred.add(new DeferredRef(node.getPath(), name,
						type == PropertyType.WEAKREFERENCE, multiple, targets));
				continue;
			}

			if (multiple) {
				List<Object> elems = (List<Object>) raw;
				Value[] values = new Value[elems.size()];
				for (int i = 0; i < elems.size(); i++) {
					values[i] = toValue(type, elems.get(i), bodyEntry);
				}
				node.setProperty(name, values);
			} else {
				node.setProperty(name, toValue(type, raw, bodyEntry));
			}
		}
	}

	private Value toValue(int type, Object raw, String bodyEntry) throws Exception {
		switch (type) {
		case PropertyType.BINARY:
			return fValueFactory.createValue(fValueFactory.createBinary(openBinary(raw, bodyEntry)));
		case PropertyType.DATE:
			return fValueFactory.createValue(parseDate(String.valueOf(raw)));
		case PropertyType.BOOLEAN:
		case PropertyType.LONG:
		case PropertyType.DOUBLE:
		case PropertyType.DECIMAL:
		case PropertyType.STRING:
		case PropertyType.NAME:
		case PropertyType.PATH:
		case PropertyType.URI:
			return fValueFactory.createValue(String.valueOf(raw), type);
		default:
			return fValueFactory.createValue(String.valueOf(raw), PropertyType.STRING);
		}
	}

	/**
	 * Open the bytes behind a binary value: a {@code {"body": true}} marker reads
	 * the file body from the content tree; a {@code {"blob": "blobs/NNNN"}}
	 * reference reads the spilled payload from {@code .cms-archive/blobs/}.
	 */
	@SuppressWarnings("unchecked")
	private InputStream openBinary(Object raw, String bodyEntry) throws Exception {
		Map<String, Object> ref = (Map<String, Object>) raw;
		if (Boolean.TRUE.equals(ref.get("body"))) {
			if (bodyEntry == null) {
				throw new IllegalStateException("Binary body marker without a content-tree entry");
			}
			return entryStream(bodyEntry);
		}
		String blob = (String) ref.get("blob");
		if (blob == null) {
			throw new IllegalStateException("Binary value is neither a body nor a blob reference");
		}
		return entryStream(ArchiveManifest.DIR + "/" + blob);
	}

	private InputStream entryStream(String entryName) throws Exception {
		ZipEntry entry = fZip.getEntry(entryName);
		if (entry == null) {
			throw new IllegalStateException("Archive is missing entry: " + entryName);
		}
		return fZip.getInputStream(entry);
	}

	/** Resolve and set a deferred reference, translating identifiers via {@code idMap}. */
	public void resolveReference(DeferredRef ref, Map<String, String> idMap, List<String> warnings)
			throws Exception {
		Node node;
		try {
			node = fSession.getNode(ref.nodePath);
		} catch (Exception missing) {
			return; // node went away (e.g. removed by a later conflict policy)
		}
		List<Value> values = new ArrayList<>();
		for (String oldId : ref.targets) {
			String newId = idMap.getOrDefault(oldId, oldId);
			Node target;
			try {
				target = fSession.getNodeByIdentifier(newId);
			} catch (Exception notFound) {
				warnings.add("Reference target not found for " + ref.nodePath + "/@" + ref.name + ": " + oldId);
				continue;
			}
			values.add(fValueFactory.createValue(target, ref.weak));
		}
		if (ref.multiple) {
			node.setProperty(ref.name, values.toArray(new Value[0]));
		} else if (!values.isEmpty()) {
			node.setProperty(ref.name, values.get(0));
		}
	}

	private static Calendar parseDate(String iso) {
		Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
		cal.setTimeInMillis(Instant.parse(iso).toEpochMilli());
		return cal;
	}
}
