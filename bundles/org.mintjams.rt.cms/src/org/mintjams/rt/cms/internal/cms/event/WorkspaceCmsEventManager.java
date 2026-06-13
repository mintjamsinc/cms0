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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.osgi.BundleLocalization;
import org.mintjams.tools.osgi.Registration;
import org.osgi.framework.Constants;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

public class WorkspaceCmsEventManager implements Closeable {

	private final String fWorkspaceName;
	private final Closer fCloser = Closer.create();
	private final List<CmsEventHandler> fEventHandlers = new ArrayList<>();

	private final EventHandler fEventHandler = new EventHandler() {
		@Override
		public void handleEvent(Event event) {
			CmsEvent cmsEvent = new OSGiCmsEvent(event);
			for (CmsEventHandler handler : fEventHandlers.toArray(CmsEventHandler[]::new)) {
				handler.handleEvent(cmsEvent);
			}
		}
	};

	public WorkspaceCmsEventManager(String workspaceName) {
		fWorkspaceName = workspaceName;
	}

	public String getWorkspaceName() {
		return fWorkspaceName;
	}

	public void addEventHandler(CmsEventHandler handler) {
		synchronized (fEventHandlers) {
			if (fEventHandlers.contains(handler)) {
				return;
			}

			fEventHandlers.add(handler);
		}
	}

	public void removeEventHandler(CmsEventHandler handler) {
		synchronized (fEventHandlers) {
			if (!fEventHandlers.contains(handler)) {
				return;
			}

			fEventHandlers.remove(handler);
		}
	}

	public void open() {
		// JCR node-change events for this workspace, plus the repository-wide
		// workspace-changed signal. The latter is delivered to every workspace's
		// manager (it carries no path, only the affected workspace name) so that
		// any connected desktop, whichever workspace it is bound to, can refresh
		// its workspace switcher and dashboard via the workspaceChanged
		// subscription when another workspace starts, stops, or has its settings
		// edited.
		Registration.Builder<EventHandler> builder = Registration.newBuilder(EventHandler.class)
				.setService(fEventHandler)
				.setProperty(EventConstants.EVENT_TOPIC, new String[] {
						Node.class.getName().replace(".", "/") + "/*",
						CmsService.TOPIC_WORKSPACE_CHANGED })
				.setProperty(Constants.SERVICE_DESCRIPTION, "CMS Event Manager for workspace: " + fWorkspaceName)
				.setProperty(Constants.SERVICE_VENDOR, BundleLocalization.create(CmsService.getDefault().getBundle()).getVendor())
				.setBundleContext(CmsService.getDefault().getBundleContext());
		fCloser.add(builder.build());
	}

	@Override
	public void close() throws IOException {
		fCloser.close();
	}

}
