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

package org.mintjams.rt.cms.internal.util;

import java.io.IOException;
import java.io.Reader;

import javax.jcr.Credentials;
import javax.jcr.GuestCredentials;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.io.IOUtils;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;

/**
 * Utility class for loading resources from JCR repository
 */
public class ResourceLoader {

	/**
	 * Load resource from JCR repository.
	 * This method uses CmsServiceCredentials for authentication.
	 *
	 * @param workspaceName Workspace name
	 * @param resourcePath Resource path
	 * @return Resource content
	 * @throws RepositoryException
	 * @throws IOException
	 */
	public static String load(String workspaceName, String resourcePath) throws RepositoryException, IOException {
		return load(workspaceName, new CmsServiceCredentials(), resourcePath);
	}

	/**
	 * Load resource from JCR repository.
	 *
	 * @param workspaceName Workspace name
	 * @param credentials JCR Credentials. If null, GuestCredentials will be used.
	 * @param resourcePath Resource path
	 * @return Resource content
	 * @throws RepositoryException
	 * @throws IOException
	 */
	public static String load(String workspaceName, Credentials credentials, String resourcePath) throws RepositoryException, IOException {
		Session session = null;
		try {
			if (credentials == null) {
				credentials = new GuestCredentials();
			}

			session = CmsService.getRepository().login(credentials, workspaceName);
			return load(session, resourcePath);
		} finally {
			if (session != null && session.isLive()) {
				try {
					session.refresh(false);
				} catch (Throwable ignore) {}
				session.logout();
			}
		}
	}

	/**
	 * Load resource from JCR repository.
	 *
	 * @param session JCR Session
	 * @param resourcePath Resource path
	 * @return Resource content
	 * @throws RepositoryException
	 * @throws IOException
	 */
	public static String load(Session session, String resourcePath) throws RepositoryException, IOException {
		Node node = session.getNode(resourcePath);
		if (node == null || !JCRs.isFile(node)) {
			throw new IllegalArgumentException("Resource not found: " + resourcePath);
		}

		try (Reader in = JCRs.getContentAsReader(node)) {
			return IOUtils.toString(in);
		}
	}

}
