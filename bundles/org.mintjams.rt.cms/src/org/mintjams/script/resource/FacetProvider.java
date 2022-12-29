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

import java.util.Map;

import org.mintjams.rt.cms.internal.CmsService;

public class FacetProvider {

	private final Workspace fWorkspace;

	public FacetProvider(Workspace workspace) {
		fWorkspace = workspace;
	}

	public Map<String, Object> getFacet(String key) throws ResourceException {
		return CmsService.getWorkspaceFacetProvider(fWorkspace.getName()).getFacet(key);
	}

	public Map<String, Map<String, Object>> getFacets(String... keys) throws ResourceException {
		return CmsService.getWorkspaceFacetProvider(fWorkspace.getName()).getFacets(keys);
	}

	public Map<String, Map<String, Object>> getAllFacets() throws ResourceException {
		return CmsService.getWorkspaceFacetProvider(fWorkspace.getName()).getAllFacets();
	}

}
