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

package org.mintjams.rt.cms.internal.graphql;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.Session;

import org.mintjams.rt.cms.internal.CmsService;

/**
 * Manages multipart file uploads for GraphQL mutations.
 * Handles temporary file storage for chunked uploads.
 */
public class MultipartUploadManager {

	private static final String UPLOAD_DIR_PREFIX = "graphql-upload-";

	private final Session session;

	public MultipartUploadManager(Session session) {
		this.session = session;
	}

	/**
	 * Initiate a new multipart upload.
	 * Creates a temporary file for storing uploaded chunks.
	 *
	 * @return Map containing uploadId and totalSize (initially 0)
	 */
	public Map<String, Object> initiate() throws IOException {
		// Generate unique upload ID
		String uploadId = UUID.randomUUID().toString();

		// Create upload directory if it doesn't exist
		Path uploadDir = getUploadDirectory();
		if (!Files.exists(uploadDir)) {
			Files.createDirectories(uploadDir);
		}

		// Create empty temporary file
		Path uploadFile = getUploadFilePath(uploadId);
		Files.createFile(uploadFile);

		Map<String, Object> result = new HashMap<>();
		result.put("uploadId", uploadId);
		result.put("totalSize", 0L);

		return result;
	}

	/**
	 * Append a chunk of data to an existing upload.
	 *
	 * @param uploadId The upload identifier
	 * @param base64Data Base64 encoded chunk data
	 * @return Map containing uploadId and updated totalSize
	 */
	public Map<String, Object> append(String uploadId, String base64Data) throws IOException {
		if (uploadId == null || uploadId.trim().isEmpty()) {
			throw new IllegalArgumentException("uploadId is required");
		}

		if (base64Data == null || base64Data.trim().isEmpty()) {
			throw new IllegalArgumentException("data is required");
		}

		Path uploadFile = getUploadFilePath(uploadId);
		if (!Files.exists(uploadFile)) {
			throw new IllegalArgumentException("Upload not found: " + uploadId);
		}

		// Decode Base64 data and append to file
		byte[] data = Base64.getDecoder().decode(base64Data.trim());
		try (OutputStream out = Files.newOutputStream(uploadFile, StandardOpenOption.APPEND)) {
			out.write(data);
		}

		// Get updated file size
		long totalSize = Files.size(uploadFile);

		Map<String, Object> result = new HashMap<>();
		result.put("uploadId", uploadId);
		result.put("totalSize", totalSize);

		return result;
	}

	/**
	 * Complete a multipart upload by creating the JCR node.
	 *
	 * @param uploadId The upload identifier
	 * @param parentPath Parent node path
	 * @param name File name
	 * @param mimeType MIME type
	 * @param overwrite Whether to overwrite existing file
	 * @return The created Node
	 */
	public Node complete(String uploadId, String parentPath, String name, String mimeType, boolean overwrite) throws Exception {
		if (uploadId == null || uploadId.trim().isEmpty()) {
			throw new IllegalArgumentException("uploadId is required");
		}

		if (parentPath == null || parentPath.trim().isEmpty()) {
			throw new IllegalArgumentException("path is required");
		}

		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("name is required");
		}

		if (mimeType == null || mimeType.trim().isEmpty()) {
			throw new IllegalArgumentException("mimeType is required");
		}

		Path uploadFile = getUploadFilePath(uploadId);
		if (!Files.exists(uploadFile)) {
			throw new IllegalArgumentException("Upload not found: " + uploadId);
		}

		try {
			// Check if parent exists
			if (!session.nodeExists(parentPath)) {
				throw new IllegalArgumentException("Parent node not found: " + parentPath);
			}

			Node parentNode = session.getNode(parentPath);

			// Build target path
			String targetPath;
			if ("/".equals(parentPath)) {
				targetPath = "/" + name;
			} else {
				targetPath = parentPath + "/" + name;
			}

			// Check for existing node
			if (session.nodeExists(targetPath)) {
				if (!overwrite) {
					throw new IllegalArgumentException("Node already exists: " + targetPath);
				}
				// Remove existing node
				session.getNode(targetPath).remove();
			}

			// Create nt:file node
			Node fileNode = parentNode.addNode(name, "nt:file");

			// Create jcr:content node
			Node contentNode = fileNode.addNode("jcr:content", "nt:resource");

			// Set content properties
			Calendar now = Calendar.getInstance();
			try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(uploadFile))) {
				contentNode.setProperty("jcr:data", session.getValueFactory().createBinary(in));
			}
			contentNode.setProperty("jcr:mimeType", mimeType);
			contentNode.setProperty("jcr:lastModified", now);
			contentNode.setProperty("jcr:lastModifiedBy", session.getUserID());

			session.save();

			// Re-get node after save
			Node savedNode = session.getNode(targetPath);

			return savedNode;
		} finally {
			// Clean up temporary file
			deleteUploadFile(uploadId);
		}
	}

	/**
	 * Abort a multipart upload and clean up temporary files.
	 *
	 * @param uploadId The upload identifier
	 * @return true if successfully aborted
	 */
	public boolean abort(String uploadId) throws IOException {
		if (uploadId == null || uploadId.trim().isEmpty()) {
			throw new IllegalArgumentException("uploadId is required");
		}

		return deleteUploadFile(uploadId);
	}

	/**
	 * Get the upload directory path.
	 */
	private Path getUploadDirectory() {
		return CmsService.getTemporaryDirectoryPath().resolve("graphql-uploads");
	}

	/**
	 * Get the file path for a specific upload.
	 */
	private Path getUploadFilePath(String uploadId) {
		return getUploadDirectory().resolve(UPLOAD_DIR_PREFIX + uploadId);
	}

	/**
	 * Delete the temporary upload file.
	 */
	private boolean deleteUploadFile(String uploadId) throws IOException {
		Path uploadFile = getUploadFilePath(uploadId);
		if (Files.exists(uploadFile)) {
			Files.delete(uploadFile);
			return true;
		}
		return false;
	}
}
