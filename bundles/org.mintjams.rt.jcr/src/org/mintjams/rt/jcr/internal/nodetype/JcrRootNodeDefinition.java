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

import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.OnParentVersionAction;

import org.mintjams.tools.lang.Cause;

public final class JcrRootNodeDefinition implements NodeDefinition {

	private static final String MX_ROOT = "mi:root";

	private final JcrNodeTypeManager fNodeTypeManager;

	protected JcrRootNodeDefinition(JcrNodeTypeManager nodeTypeManager) {
		fNodeTypeManager = nodeTypeManager;
	}

	@Override
	public NodeType getDeclaringNodeType() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getName() {
		return "";
	}

	@Override
	public int getOnParentVersion() {
		return OnParentVersionAction.VERSION;
	}

	@Override
	public boolean isAutoCreated() {
		return true;
	}

	@Override
	public boolean isMandatory() {
		return true;
	}

	@Override
	public boolean isProtected() {
		return false;
	}

	@Override
	public boolean allowsSameNameSiblings() {
		return false;
	}

	@Override
	public NodeType getDefaultPrimaryType() {
		try {
			return fNodeTypeManager.getNodeType(MX_ROOT);
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public String getDefaultPrimaryTypeName() {
		return MX_ROOT;
	}

	@Override
	public String[] getRequiredPrimaryTypeNames() {
		return new String[] { MX_ROOT };
	}

	@Override
	public NodeType[] getRequiredPrimaryTypes() {
		try {
			return new NodeType[] { fNodeTypeManager.getNodeType(MX_ROOT) };
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

}
