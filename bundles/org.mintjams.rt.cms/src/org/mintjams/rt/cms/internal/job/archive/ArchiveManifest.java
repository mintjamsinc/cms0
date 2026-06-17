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

import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code .cms-archive/manifest.json} header of a CMS Archive — read first
 * on restore to identify the producer and declare which optional sections the
 * archive contains, so the importer can validate before touching anything. See
 * {@code documents/cms-archive-backup-restore.md}.
 */
public final class ArchiveManifest {

	/** Discriminator: every CMS Archive manifest carries this {@code format}. */
	public static final String FORMAT = "cms-archive";
	/** Current format version. The importer refuses versions it does not know. */
	public static final int VERSION = 1;

	/** Reserved directory holding the restore metadata inside the ZIP. */
	public static final String DIR = ".cms-archive";
	public static final String MANIFEST_ENTRY = DIR + "/manifest.json";
	public static final String NODES_ENTRY = DIR + "/nodes.ndjson";
	public static final String ACL_ENTRY = DIR + "/acl.ndjson";
	public static final String BLOBS_DIR = DIR + "/blobs";

	private static final DateTimeFormatter ISO_UTC =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

	private String fCreatedBy;
	private String fWorkspace;
	private List<String> fRoots;
	private boolean fProperties;
	private boolean fBinaries;
	private boolean fAcl;
	private long fNodeCount;
	private long fBlobCount;

	public ArchiveManifest createdBy(String userId) { fCreatedBy = userId; return this; }
	public ArchiveManifest workspace(String workspace) { fWorkspace = workspace; return this; }
	public ArchiveManifest roots(List<String> roots) { fRoots = roots; return this; }
	public ArchiveManifest withProperties(boolean v) { fProperties = v; return this; }
	public ArchiveManifest withBinaries(boolean v) { fBinaries = v; return this; }
	public ArchiveManifest withAcl(boolean v) { fAcl = v; return this; }
	public ArchiveManifest nodeCount(long v) { fNodeCount = v; return this; }
	public ArchiveManifest blobCount(long v) { fBlobCount = v; return this; }

	/** Write the manifest as pretty-printed JSON to the given stream. */
	public void writeTo(OutputStream out) throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("format", FORMAT);
		m.put("version", VERSION);
		m.put("createdAt", ISO_UTC.format(Instant.now()));
		m.put("createdBy", fCreatedBy);

		Map<String, Object> source = new LinkedHashMap<>();
		source.put("workspace", fWorkspace);
		m.put("source", source);

		m.put("roots", fRoots);

		Map<String, Object> contains = new LinkedHashMap<>();
		contains.put("properties", fProperties);
		contains.put("binaries", fBinaries);
		contains.put("acl", fAcl);
		m.put("contains", contains);

		Map<String, Object> counts = new LinkedHashMap<>();
		counts.put("nodes", fNodeCount);
		counts.put("blobs", fBlobCount);
		m.put("counts", counts);

		// Write via a byte[] rather than handing the stream to Jackson, whose
		// writeValue(OutputStream, ...) closes the target by default — that would
		// close the shared ZipOutputStream and break the following closeEntry().
		out.write(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(m));
	}
}
