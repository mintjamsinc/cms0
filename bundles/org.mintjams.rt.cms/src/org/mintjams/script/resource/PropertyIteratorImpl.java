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

import org.mintjams.script.resource.Resource.PropertyIterator;

public class PropertyIteratorImpl implements PropertyIterator {

	private final javax.jcr.PropertyIterator fPropertyIterator;
	private final Resource fResource;

	protected PropertyIteratorImpl(javax.jcr.PropertyIterator propertyIterator, Resource resource) throws ResourceException {
		if (propertyIterator == null) {
			throw new IllegalArgumentException("PropertyIterator must not be null.");
		}
		if (resource == null) {
			throw new IllegalArgumentException("Resource must not be null.");
		}

		fPropertyIterator = propertyIterator;
		fResource = resource;
	}

	@Override
	public boolean hasNext() {
		return fPropertyIterator.hasNext();
	}

	@Override
	public Property next() {
		if (!hasNext()) {
			throw new IllegalStateException("No more properties.");
		}

		return new Property(fPropertyIterator.nextProperty(), fResource);
	}

	@Override
	public void skip(long skipNum) {
		fPropertyIterator.skip(skipNum);
	}

}
