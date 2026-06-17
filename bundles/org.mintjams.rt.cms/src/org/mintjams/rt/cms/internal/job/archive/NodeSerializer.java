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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeIterator;
import javax.jcr.nodetype.PropertyDefinition;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Serialises a JCR node to one line of the {@code .cms-archive/nodes.ndjson}
 * sidecar that turns a plain ZIP download into an importable CMS Archive. See
 * {@code documents/cms-archive-export-import.md} for the format.
 *
 * <p>Each node becomes a single JSON object carrying its primary type, mixins,
 * identity (UUID, when {@code mix:referenceable}) and every non-structural
 * property with its JCR type, so the value round-trips exactly. Structural
 * properties the repository manages itself ({@code jcr:primaryType},
 * {@code jcr:mixinTypes}, {@code jcr:uuid}) and other protected/auto-created
 * properties are not emitted as ordinary properties — they are represented by
 * the dedicated fields above or re-derived on import.
 *
 * <p>Binaries are never inlined as Base64. A file's body (the {@code jcr:data}
 * of an {@code nt:file}'s {@code jcr:content}) is carried by the ordinary file
 * in the content tree and marked {@code {"body": true}}; any other binary
 * property is spilled to {@code .cms-archive/blobs/<id>} and referenced by
 * relative path and size. This keeps {@code nodes.ndjson} small and text-like
 * and avoids the ~33% bloat Base64 would add to large media.
 *
 * <p>The class is deliberately a pure writer with no JCR mutation: export and
 * import share this format definition so the two can never silently drift.
 */
public final class NodeSerializer {

	/** Structural properties represented by dedicated node fields, never as properties. */
	private static final String JCR_PRIMARY_TYPE = "jcr:primaryType";
	private static final String JCR_MIXIN_TYPES = "jcr:mixinTypes";
	private static final String JCR_UUID = "jcr:uuid";
	private static final String JCR_DATA = "jcr:data";

	/**
	 * Audit properties the repository manages itself. They are recorded out of the
	 * ordinary property set so the importer controls them deliberately rather than
	 * replaying them blindly: the two timestamps travel as the dedicated
	 * {@code created}/{@code lastModified} fields (re-applied only when the user
	 * opts to preserve timestamps), while {@code *By} are never carried — they
	 * always become the importing user. {@code jcr:created}/{@code jcr:createdBy}
	 * are also protected, so they are excluded by {@link #isProtected} too; the
	 * explicit set keeps the contract clear and covers the non-protected
	 * {@code jcr:lastModified}/{@code jcr:lastModifiedBy}.
	 */
	private static final String JCR_CREATED = "jcr:created";
	private static final String JCR_CREATED_BY = "jcr:createdBy";
	private static final String JCR_LAST_MODIFIED = "jcr:lastModified";
	private static final String JCR_LAST_MODIFIED_BY = "jcr:lastModifiedBy";

	private static final DateTimeFormatter ISO_UTC =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

	private final ObjectMapper fMapper = new ObjectMapper();
	private final Path fBlobsDir;
	private final Set<String> fProtectedNames;
	private int fBlobSeq;

	/**
	 * @param blobsDir       local working directory that receives spilled binary
	 *                       property payloads (one file per binary value). Created
	 *                       on first use. The caller adds its contents to the
	 *                       archive under {@code .cms-archive/blobs/}.
	 * @param protectedNames the set of property names the repository protects, as
	 *                       returned by {@link #collectProtectedPropertyNames}.
	 *                       These are never emitted as ordinary properties: this
	 *                       is the <em>same authority the repository uses to
	 *                       reject writes</em>, so the importer can always set
	 *                       back everything the exporter wrote (and never a
	 *                       protected property such as {@code jcr:frozenUuid}).
	 */
	public NodeSerializer(Path blobsDir, Set<String> protectedNames) {
		fBlobsDir = blobsDir;
		fProtectedNames = protectedNames;
	}

	/**
	 * Collect every property name the repository declares protected, by name,
	 * across all registered node types — mirroring exactly what the write path
	 * ({@code JcrNode.setProperty} → {@code isProtectedProperty(name)}) consults.
	 *
	 * <p>Using this name-based set, rather than each property's per-node
	 * {@link PropertyDefinition#isProtected() definition}, is what keeps export
	 * and import symmetric. A property can resolve to a non-protected residual
	 * ({@code *}) definition on the node it sits on yet still be protected by name
	 * globally (e.g. {@code jcr:frozenUuid}, declared protected by
	 * {@code nt:frozenNode}); the definition view would export it, the write path
	 * would then refuse it. Both halves consult this one set so they never
	 * disagree. Residual ({@code *}) definitions are ignored, exactly as the
	 * repository's own name lookup ignores them.
	 */
	public static Set<String> collectProtectedPropertyNames(Session session) throws RepositoryException {
		Set<String> names = new HashSet<>();
		NodeTypeIterator it = session.getWorkspace().getNodeTypeManager().getAllNodeTypes();
		while (it.hasNext()) {
			NodeType type = it.nextNodeType();
			for (PropertyDefinition def : type.getPropertyDefinitions()) {
				if (def.isProtected() && !"*".equals(def.getName())) {
					names.add(def.getName());
				}
			}
		}
		return names;
	}

	/** Number of binary payloads spilled to {@link #fBlobsDir} so far. */
	public int getBlobCount() {
		return fBlobSeq;
	}

	/**
	 * Serialise one node to a single-line JSON string (no trailing newline).
	 *
	 * @param node         the node to serialise.
	 * @param entry        the node's path inside the content tree of the ZIP
	 *                     (the file body or folder entry), or {@code null} for
	 *                     metadata-only nodes such as {@code jcr:content}.
	 * @param fileBodyNode {@code true} when this is the {@code jcr:content} of an
	 *                     {@code nt:file}; its {@code jcr:data} is then recorded
	 *                     as the body marker rather than spilled to a blob.
	 */
	public String toLine(Node node, String entry, boolean fileBodyNode) throws Exception {
		return fMapper.writeValueAsString(toMap(node, entry, fileBodyNode));
	}

	private Map<String, Object> toMap(Node node, String entry, boolean fileBodyNode) throws Exception {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("path", node.getPath());
		out.put("name", node.getName());
		out.put("primaryType", node.getPrimaryNodeType().getName());

		List<String> mixins = new ArrayList<>();
		for (NodeType mixin : node.getMixinNodeTypes()) {
			mixins.add(mixin.getName());
		}
		out.put("mixins", mixins);

		if (node.isNodeType("mix:referenceable")) {
			out.put("uuid", node.getIdentifier());
		}
		if (entry != null) {
			out.put("entry", entry);
		}

		// Audit timestamps travel as dedicated fields (not ordinary properties):
		// jcr:created is protected and re-applied only at creation time, and both
		// are gated by the importer's preserve-timestamps option. The matching
		// *By properties are deliberately omitted — on import they become the
		// importing user, never the original author.
		if (node.hasProperty(JCR_CREATED)) {
			out.put("created", formatDate(node.getProperty(JCR_CREATED).getDate()));
		}
		if (node.hasProperty(JCR_LAST_MODIFIED)) {
			out.put("lastModified", formatDate(node.getProperty(JCR_LAST_MODIFIED).getDate()));
		}

		List<Map<String, Object>> properties = new ArrayList<>();
		for (PropertyIterator it = node.getProperties(); it.hasNext();) {
			Property prop = it.nextProperty();
			Map<String, Object> encoded = encodeProperty(prop, fileBodyNode);
			if (encoded != null) {
				properties.add(encoded);
			}
		}
		out.put("properties", properties);
		return out;
	}

	private Map<String, Object> encodeProperty(Property prop, boolean fileBodyNode) throws Exception {
		String name = prop.getName();
		// Structural identity is carried by dedicated node fields.
		if (JCR_PRIMARY_TYPE.equals(name) || JCR_MIXIN_TYPES.equals(name) || JCR_UUID.equals(name)) {
			return null;
		}
		// Audit properties are managed by the importer, not replayed as data:
		// the timestamps are carried by the dedicated created/lastModified fields
		// and the *By values always become the importing user.
		if (JCR_CREATED.equals(name) || JCR_CREATED_BY.equals(name)
				|| JCR_LAST_MODIFIED.equals(name) || JCR_LAST_MODIFIED_BY.equals(name)) {
			return null;
		}
		// Protected/auto-created properties are recomputed by the repository on
		// write, so they are not part of the portable property set, and the write
		// path would reject them anyway. The check is name-based — the same
		// authority the repository's setProperty consults — so the importer can
		// always set back exactly what we emit here. The file body (jcr:data on a
		// content node) is not protected and is handled below as the body marker.
		if (fProtectedNames.contains(name)) {
			return null;
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", name);
		out.put("type", PropertyType.nameFromValue(prop.getType()));
		boolean multiple = prop.isMultiple();
		if (multiple) {
			out.put("multiple", true);
			List<Object> values = new ArrayList<>();
			for (Value v : prop.getValues()) {
				values.add(encodeValue(v, name, fileBodyNode));
			}
			out.put("value", values);
		} else {
			out.put("value", encodeValue(prop.getValue(), name, fileBodyNode));
		}
		return out;
	}

	private Object encodeValue(Value value, String propName, boolean fileBodyNode) throws Exception {
		switch (value.getType()) {
		case PropertyType.STRING:
		case PropertyType.NAME:
		case PropertyType.PATH:
		case PropertyType.URI:
		case PropertyType.REFERENCE:
		case PropertyType.WEAKREFERENCE:
			return value.getString();
		case PropertyType.BOOLEAN:
			return value.getBoolean();
		case PropertyType.LONG:
			return value.getLong();
		case PropertyType.DOUBLE:
			return value.getDouble();
		case PropertyType.DECIMAL:
			// Kept as a string so arbitrary-precision decimals survive JSON.
			return value.getDecimal().toString();
		case PropertyType.DATE:
			return formatDate(value.getDate());
		case PropertyType.BINARY:
			if (fileBodyNode && JCR_DATA.equals(propName)) {
				// The body is the ordinary file in the content tree.
				return body();
			}
			return spillBlob(value);
		default:
			return value.getString();
		}
	}

	/** Spill a binary value to the blobs working directory and reference it. */
	private Map<String, Object> spillBlob(Value value) throws Exception {
		if (fBlobSeq == 0) {
			Files.createDirectories(fBlobsDir);
		}
		String id = String.format("%04d", ++fBlobSeq);
		Path target = fBlobsDir.resolve(id);
		long size;
		Binary binary = value.getBinary();
		try (InputStream in = binary.getStream(); OutputStream out = Files.newOutputStream(target)) {
			size = copy(in, out);
		} finally {
			binary.dispose();
		}
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("blob", "blobs/" + id);
		ref.put("size", size);
		return ref;
	}

	private static Map<String, Object> body() {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("body", true);
		return ref;
	}

	private static String formatDate(Calendar cal) {
		return ISO_UTC.format(ZonedDateTime.ofInstant(cal.toInstant(), ZoneOffset.UTC));
	}

	private static long copy(InputStream in, OutputStream out) throws java.io.IOException {
		byte[] buf = new byte[8192];
		long total = 0;
		int n;
		while ((n = in.read(buf)) != -1) {
			out.write(buf, 0, n);
			total += n;
		}
		return total;
	}
}
