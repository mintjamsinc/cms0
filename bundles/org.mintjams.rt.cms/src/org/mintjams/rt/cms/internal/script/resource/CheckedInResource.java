/*
 * Copyright (c) 2026 MintJams Inc.
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

package org.mintjams.rt.cms.internal.script.resource;

import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceException;

/**
 * Finds what keeps a resource read-only under version control, for the
 * deployments that have to write to it all the same.
 */
public class CheckedInResource {

	private CheckedInResource() {}

	/**
	 * The checked-in resource that makes the given resource read-only, or
	 * {@code null} when it may be changed. The nearest version-controlled
	 * resource among the resource and its ancestors decides.
	 */
	public static Resource of(Resource resource) throws ResourceException {
		for (Resource r = resource;; r = r.getParent()) {
			if (r.isVersionControlled()) {
				return r.isCheckedOut() ? null : r;
			}
			if (r.isRoot()) {
				return null;
			}
		}
	}

}
