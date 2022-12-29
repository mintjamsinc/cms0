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

package org.mintjams.rt.jcr.internal.nodetype;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeIterator;

public class JcrNodeTypeIterator implements NodeTypeIterator {

	private final List<NodeType> fNodeTypes;
	private long fPosition;

	private JcrNodeTypeIterator(List<NodeType> nodeTypes) {
		fNodeTypes = nodeTypes;
	}

	public static JcrNodeTypeIterator create(List<NodeType> nodeTypes) {
		return new JcrNodeTypeIterator(nodeTypes);
	}

	public static JcrNodeTypeIterator create(NodeType[] nodeTypes) {
		return create(Arrays.asList(nodeTypes));
	}

	public static JcrNodeTypeIterator create(Collection<NodeType> nodeTypes) {
		return create(nodeTypes.toArray(NodeType[]::new));
	}

	@Override
	public long getPosition() {
		return fPosition;
	}

	@Override
	public long getSize() {
		return fNodeTypes.size();
	}

	@Override
	public void skip(long skipNum) {
		fPosition += skipNum;
	}

	@Override
	public boolean hasNext() {
		return (fPosition < fNodeTypes.size());
	}

	@Override
	public Object next() {
		return nextNodeType();
	}

	@Override
	public NodeType nextNodeType() {
		NodeType nodeType = fNodeTypes.get(Math.toIntExact(fPosition));
		fPosition++;
		return nodeType;
	}

}
