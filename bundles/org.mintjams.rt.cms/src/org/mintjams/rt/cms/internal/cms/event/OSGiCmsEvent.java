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

	private final String topic;
	private final String identifier;
	private final String path;
	private final String type;
	private final String workspaceName;
	private final List<String> propertyNames;
	private final String sourcePath;
	private final String userID;

	public OSGiCmsEvent(Event event) {
		topic = event.getTopic();
		identifier = (String) event.getProperty("identifier");
		path = (String) event.getProperty("path");
		type = (String) event.getProperty("type");
		workspaceName = (String) event.getProperty("workspaceName");
		if (event.getProperty("propertyNames") != null) {
			propertyNames = List.copyOf(Arrays.asList((String[]) event.getProperty("propertyNames")));
		} else {
			propertyNames = List.of();
		}
		if (event.getProperty("sourcePath") != null) {
			sourcePath = (String) event.getProperty("sourcePath");
		} else {
			sourcePath = null;
		}
		userID = (String) event.getProperty("userID");
	}

	@Override
	public String getTopic() {
		return topic;
	}

	@Override
	public String getIdentifier() {
		return identifier;
	}

	@Override
	public String getPath() {
		return path;
	}

	@Override
	public String getType() {
		return type;
	}

	@Override
	public String getWorkspaceName() {
		return workspaceName;
	}

	@Override
	public List<String> getPropertyNames() {
		return propertyNames;
	}

	@Override
	public String getSourcePath() {
		return sourcePath;
	}

	@Override
	public String getUserID() {
		return userID;
	}

}
