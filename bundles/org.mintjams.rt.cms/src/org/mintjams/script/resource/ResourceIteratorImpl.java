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

import javax.jcr.NodeIterator;

import org.mintjams.script.resource.Resource.ResourceIterator;
import org.mintjams.tools.lang.Cause;

public class ResourceIteratorImpl implements ResourceIterator {

	private final NodeIterator fNodeIterator;
	private final Session fSession;
	private final ResourceFilter fResourceFilter;
	private final ResourceNameFilter fResourceNameFilter;
	private Resource fNextResource;

	protected ResourceIteratorImpl(NodeIterator nodeIterator, Session session) throws ResourceException {
		if (nodeIterator == null) {
			throw new IllegalArgumentException("NodeIterator must not be null.");
		}
		if (session == null) {
			throw new IllegalArgumentException("Session must not be null.");
		}

		fNodeIterator = nodeIterator;
		fSession = session;
		fResourceFilter = null;
		fResourceNameFilter = null;
		readNext();
	}

	protected ResourceIteratorImpl(NodeIterator nodeIterator, Session session, ResourceFilter resourceFilter) throws ResourceException {
		if (nodeIterator == null) {
			throw new IllegalArgumentException("NodeIterator must not be null.");
		}
		if (session == null) {
			throw new IllegalArgumentException("Session must not be null.");
		}

		fNodeIterator = nodeIterator;
		fSession = session;
		fResourceFilter = resourceFilter;
		fResourceNameFilter = null;
		readNext();
	}

	protected ResourceIteratorImpl(NodeIterator nodeIterator, Session session, ResourceNameFilter resourceNameFilter) throws ResourceException {
		if (nodeIterator == null) {
			throw new IllegalArgumentException("NodeIterator must not be null.");
		}
		if (session == null) {
			throw new IllegalArgumentException("Session must not be null.");
		}

		fNodeIterator = nodeIterator;
		fSession = session;
		fResourceFilter = null;
		fResourceNameFilter = resourceNameFilter;
		readNext();
	}

	@Override
	public boolean hasNext() {
		return (fNextResource != null);
	}

	@Override
	public Resource next() {
		if (!hasNext()) {
			throw new IllegalStateException("No more resources.");
		}

		Resource item = fNextResource;
		readNext();
		return item;
	}

	@Override
	public void skip(long skipNum) {
		if (skipNum < 1 || Integer.MAX_VALUE < skipNum) {
			throw new IllegalArgumentException("Invalid skip number: " + skipNum);
		}

		for (long i = 0; i < skipNum; i++) {
			if (!hasNext()) {
				break;
			}
			next();
		}
	}

	private void readNext() {
		fNextResource = null;
		while (fNextResource == null) {
			if (!fNodeIterator.hasNext()) {
				return;
			}

			try {
				ResourceImpl r = new ResourceImpl(fNodeIterator.nextNode(), fSession);
				if (fResourceFilter != null) {
					if (!fResourceFilter.accept(r)) {
						continue;
					}
				}
				if (fResourceNameFilter != null) {
					if (!fResourceNameFilter.accept(r.getParent(), r.getName())) {
						continue;
					}
				}
				fNextResource = r;
			} catch (ResourceException ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
	}

}
