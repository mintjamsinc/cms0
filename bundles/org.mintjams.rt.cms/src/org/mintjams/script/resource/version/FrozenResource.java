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

package org.mintjams.script.resource.version;

import javax.jcr.RepositoryException;

import org.mintjams.script.resource.ResourceException;
import org.mintjams.script.resource.Resource;
import org.mintjams.script.resource.ResourceImpl;

public class FrozenResource extends ResourceImpl {

	private final Version fVersion;
	private final Resource fResource;

	protected FrozenResource(Version version, Resource resource) throws ResourceException, RepositoryException {
		super(version.adaptTo(javax.jcr.version.Version.class).getFrozenNode(), resource.getSession());
		fVersion = version;
		fResource = resource;
	}

	@Override
	public String getName() throws ResourceException {
		return fResource.getName();
	}

	@Override
	public String getPath() throws ResourceException {
		return fResource.getPath();
	}

	public String getFrozenPath() throws ResourceException {
		return super.getPath();
	}

	public Version getVersion() throws ResourceException {
		return fVersion;
	}

	@Override
	public boolean canWrite() throws ResourceException {
		return false;
	}

	@Override
	public boolean canReadACL() throws ResourceException {
		return false;
	}

	@Override
	public boolean canWriteACL() throws ResourceException {
		return false;
	}

	@Override
	public boolean canRemove() throws ResourceException {
		return false;
	}

}
