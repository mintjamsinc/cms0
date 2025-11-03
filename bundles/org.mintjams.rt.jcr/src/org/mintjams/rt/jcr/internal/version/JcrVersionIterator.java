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

package org.mintjams.rt.jcr.internal.version;

import java.util.List;
import java.util.NoSuchElementException;

import javax.jcr.version.Version;
import javax.jcr.version.VersionIterator;

public class JcrVersionIterator implements VersionIterator {

	private final List<Version> fVersions;
	private int fPosition = -1;

	private JcrVersionIterator(List<Version> versions) {
		fVersions = versions;
	}

	public static JcrVersionIterator create(List<Version> versions) {
		return new JcrVersionIterator(versions);
	}

	@Override
	public long getPosition() {
		if (fPosition < 0) {
			return 0;
		}
		return fPosition;
	}

	@Override
	public long getSize() {
		return fVersions.size();
	}

	@Override
	public void skip(long skipNum) {
		if (skipNum < 0 || Integer.MAX_VALUE < skipNum) {
			throw new NoSuchElementException("Invalid skip number: " + skipNum);
		}
		if (fPosition + skipNum >= fVersions.size()) {
			throw new NoSuchElementException("Invalid skip number: " + skipNum);
		}
		fPosition += skipNum;
	}

	@Override
	public boolean hasNext() {
		return (fPosition < fVersions.size() - 1);
	}

	@Override
	public Object next() {
		return nextVersion();
	}

	@Override
	public Version nextVersion() {
		int index = fPosition + 1;
		Version item = fVersions.get(index);
		fPosition++;
		return item;
	}

}
