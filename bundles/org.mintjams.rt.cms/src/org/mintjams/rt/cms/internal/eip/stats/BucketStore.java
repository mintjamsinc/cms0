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

package org.mintjams.rt.cms.internal.eip.stats;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.util.JCRs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JCR I/O for {@link Bucket} files under {@code /var/eip/stats}.
 *
 * <p>Centralises the read/write logic so that the live aggregator
 * ({@code eip:rollupUp}) and the offline rebuild ({@code eip:rebuild}) share
 * exactly the same on-disk format, file naming convention and indexed
 * property set.
 *
 * <p>Each instance is stateless and safe to share across threads.
 */
public class BucketStore {

	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
			new TypeReference<LinkedHashMap<String, Object>>() {};

	private final ObjectMapper fMapper = new ObjectMapper();

	/**
	 * Load the bucket stored at {@code path}, or {@code null} if the node
	 * does not exist or is empty.
	 */
	public Bucket read(Session session, String path) throws Exception {
		if (!session.nodeExists(path)) {
			return null;
		}
		Node fileNode = session.getNode(path);
		String json = JCRs.getContentAsString(fileNode);
		if (json == null || json.isEmpty()) {
			return null;
		}
		Map<String, Object> map = fMapper.readValue(json, MAP_TYPE);
		return Bucket.fromJsonMap(map);
	}

	/**
	 * Write the bucket to its canonical path, creating intermediate folders
	 * as needed. Also stamps Lucene-searchable properties on the file node
	 * (mi:routeId, mi:interval, mi:bucket, mi:count, mi:errors). The session
	 * is NOT saved by this method.
	 */
	public void write(Session session, Bucket bucket) throws Exception {
		String path = BucketPathResolver.bucketPath(
				bucket.getRouteId(), bucket.getInterval(), bucket.getBucketStart());
		int slash = path.lastIndexOf('/');
		String parentPath = path.substring(0, slash);
		String fileName = path.substring(slash + 1);

		Node parent = JCRs.getOrCreateFolder(JcrPath.valueOf(parentPath), session);
		Node fileNode;
		if (parent.hasNode(fileName)) {
			fileNode = parent.getNode(fileName);
		} else {
			fileNode = JCRs.createFile(parent, fileName);
		}

		String json = fMapper.writeValueAsString(bucket.toJsonMap());
		try (ByteArrayInputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
			JCRs.write(fileNode, in);
		}
		JCRs.setProperty(fileNode, "jcr:mimeType", "application/json");
		JCRs.setProperty(fileNode, "jcr:lastModified", Calendar.getInstance());

		JCRs.setProperty(fileNode, "mi:routeId", bucket.getRouteId());
		JCRs.setProperty(fileNode, "mi:interval", bucket.getInterval().label());
		Calendar bucketCal = Calendar.getInstance();
		bucketCal.setTimeInMillis(bucket.getBucketStart().toEpochMilli());
		JCRs.setProperty(fileNode, "mi:bucket", bucketCal);
		JCRs.setProperty(fileNode, "mi:count", bucket.elapsed().getCount());
		JCRs.setProperty(fileNode, "mi:errors", bucket.elapsed().getErrors());
	}
}
