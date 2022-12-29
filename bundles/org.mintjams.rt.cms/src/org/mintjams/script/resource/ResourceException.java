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

package org.mintjams.script.resource;

import org.mintjams.script.resource.lock.LockException;
import org.mintjams.script.resource.query.QueryException;
import org.mintjams.script.resource.security.AccessControlException;
import org.mintjams.script.resource.security.AccessDeniedException;
import org.mintjams.script.resource.version.VersionException;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.util.ActionException;

public class ResourceException extends Exception {

	private static final long serialVersionUID = 1L;

	public ResourceException() {
		super();
	}

	public ResourceException(String message) {
		super(message);
	}

	public ResourceException(Throwable cause) {
		super(cause);
	}

	public ResourceException(String message, Throwable cause) {
		super(message, cause);
	}

	public static ResourceException wrap(Throwable ex) {
		if (ex instanceof ResourceException) {
			return (ResourceException) ex;
		}

		if (ex instanceof javax.jcr.AccessDeniedException) {
			return (AccessDeniedException) new AccessDeniedException(ex.getMessage()).initCause(ex);
		}

		if (ex instanceof javax.jcr.ItemNotFoundException || ex instanceof javax.jcr.PathNotFoundException) {
			return (ResourceNotFoundException) new ResourceNotFoundException(ex.getMessage()).initCause(ex);
		}

		if (ex instanceof javax.jcr.ItemExistsException || ex instanceof javax.jcr.version.LabelExistsVersionException) {
			return (ResourceAlreadyExistsException) new ResourceAlreadyExistsException(ex.getMessage()).initCause(ex);
		}

		if (ex instanceof javax.jcr.ValueFormatException) {
			return (ValueFormatException) new ValueFormatException(ex.getMessage()).initCause(ex);
		}

		if (ex instanceof javax.jcr.lock.LockException) {
			return (LockException) new LockException(ex.getMessage()).initCause(ex);
		}

		if (ex instanceof javax.jcr.version.VersionException) {
			return (VersionException) new VersionException(ex.getMessage()).initCause(ex);
		}

		if (ex instanceof javax.jcr.security.AccessControlException) {
			return (AccessControlException) new AccessControlException(ex.getMessage()).initCause(ex);
		}

		if (ex instanceof javax.jcr.query.InvalidQueryException) {
			return (QueryException) new QueryException(ex.getMessage()).initCause(ex);
		}

		if (ex instanceof ActionException) {
			Throwable cause = ex.getCause();
			for (;;) {
				if (cause instanceof ActionException) {
					if (cause.getCause() == null) {
						return new ResourceException(cause.getMessage());
					}
					cause = cause.getCause();
					continue;
				}
				break;
			}
			return Cause.create(cause).wrap(ResourceException.class);
		}

		return Cause.create(ex).wrap(ResourceException.class);
	}

}
