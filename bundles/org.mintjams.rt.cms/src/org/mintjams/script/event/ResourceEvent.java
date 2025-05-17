/*
 * Copyright (c) 2025 MintJams Inc.
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

package org.mintjams.script.event;

import javax.jcr.Node;

public class ResourceEvent extends Event {

	protected ResourceEvent(org.osgi.service.event.Event event) {
		super(event);
	}

	public String getWorkspaceName() {
		return getPropertyAsString("workspace");
	}

	public String getPath() {
		return getPropertyAsString("path");
	}

	public static enum Topic {
		ADDED(Node.class.getName().replace(".", "/") + "/ADDED"),
		CHANGED(Node.class.getName().replace(".", "/") + "/CHANGED"),
		REMOVED(Node.class.getName().replace(".", "/") + "/REMOVED"),
		MOVED(Node.class.getName().replace(".", "/") + "/MOVED");

		private final String fValue;

		private Topic(String value) {
			fValue = value;
		}

		public String getValue() {
			return fValue;
		}
	}
}
