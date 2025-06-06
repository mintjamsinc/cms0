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

package org.mintjams.script.event;

import java.util.Map;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.script.ScriptingContext;
import org.osgi.service.event.Event;

public class EventAdminAPI {

	private WorkspaceScriptContext fContext;

	public EventAdminAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static EventAdminAPI get(ScriptingContext context) {
		return (EventAdminAPI) context.getAttribute(EventAdminAPI.class.getSimpleName());
	}

	public void postEvent(String topic, Map<String, ?> properties) {
		CmsService.postEvent(new Event(topic, properties));
	}

	public ResourceEventHandlerRegistration.Builder beginResourceEventHandlerRegistration() {
		return ResourceEventHandlerRegistration.newBuilder(fContext);
	}

}
