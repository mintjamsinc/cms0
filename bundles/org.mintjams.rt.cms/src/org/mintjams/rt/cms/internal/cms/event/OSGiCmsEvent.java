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

package org.mintjams.rt.cms.internal.cms.event;

import java.util.Arrays;
import java.util.List;

import org.osgi.service.event.Event;

public class OSGiCmsEvent implements CmsEvent {

	private final String fTopic;
	private final String fIdentifier;
	private final String fPath;
	private final String fType;
	private final String fWorkspaceName;
	private final List<String> fPropertyNames;
	private final String fSourcePath;
	private final String fUserID;

	public OSGiCmsEvent(Event event) {
		fTopic = event.getTopic();
		fIdentifier = (String) event.getProperty("identifier");
		fPath = (String) event.getProperty("path");
		fType = (String) event.getProperty("type");
		fWorkspaceName = (String) event.getProperty("workspace");
		if (event.getProperty("properties") != null) {
			fPropertyNames = List.copyOf(Arrays.asList((String[]) event.getProperty("properties")));
		} else {
			fPropertyNames = List.of();
		}
		if (event.getProperty("source_path") != null) {
			fSourcePath = (String) event.getProperty("source_path");
		} else {
			fSourcePath = null;
		}
		fUserID = (String) event.getProperty("user_id");
	}

	@Override
	public String getTopic() {
		return fTopic;
	}

	@Override
	public String getIdentifier() {
		return fIdentifier;
	}

	@Override
	public String getPath() {
		return fPath;
	}

	@Override
	public String getType() {
		return fType;
	}

	@Override
	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	@Override
	public List<String> getPropertyNames() {
		return fPropertyNames;
	}

	@Override
	public String getSourcePath() {
		return fSourcePath;
	}

	@Override
	public String getUserID() {
		return fUserID;
	}

}
