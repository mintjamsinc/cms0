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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.script.WorkspaceScriptContext;
import org.mintjams.tools.osgi.Registration;
import org.osgi.service.event.EventConstants;

public class ResourceEventHandlerRegistration implements EventHandlerRegistration {

	private final Registration<org.osgi.service.event.EventHandler> fRegistration;

	private ResourceEventHandlerRegistration(Registration<org.osgi.service.event.EventHandler> registration) {
		fRegistration = registration;
	}

	@Override
	public void close() throws IOException {
		if (fRegistration != null) {
			fRegistration.close();
		}
	}

	public static Builder newBuilder(WorkspaceScriptContext context) {
		return Builder.create(context);
	}

	public static class Builder {
		private final WorkspaceScriptContext fContext;
		private EventHandler fEventHandler;
		private String fPath;
		private final EnumSet<ResourceEvent.Topic> fTopics = EnumSet.noneOf(ResourceEvent.Topic.class);

		private Builder(WorkspaceScriptContext context) {
			fContext = context;
		}

		public static Builder create(WorkspaceScriptContext context) {
			return new Builder(context);
		}

		public Builder setTopics(ResourceEvent.Topic... topics) {
			fTopics.clear();
			Collections.addAll(fTopics, topics);
			return this;
		}

		public Builder setPath(String path) {
			fPath = path;
			return this;
		}

		public Builder setEventHandler(EventHandler eventHandler) {
			fEventHandler = eventHandler;
			return this;
		}

		public ResourceEventHandlerRegistration build() {
			if (fEventHandler == null) {
				throw new NullPointerException("The event handler must be provided.");
			}

			org.osgi.service.event.EventHandler service = new org.osgi.service.event.EventHandler() {
				@Override
				public void handleEvent(org.osgi.service.event.Event event) {
					fEventHandler.handleEvent(new ResourceEvent(event));
				}
			};

			Set<String> topics = fTopics.isEmpty()
					? EnumSet.allOf(ResourceEvent.Topic.class).stream().map(ResourceEvent.Topic::getValue).collect(Collectors.toSet())
					: fTopics.stream().map(ResourceEvent.Topic::getValue).collect(Collectors.toSet());

			String filter;
			{
				List<String> filters = new ArrayList<>();
				filters.add("(workspace=" + fContext.getWorkspaceName() + ")");
				if (StringUtils.isNotBlank(fPath)) {
					filters.add("(path=" + fPath + ")");
				}

				if (filters.size() == 1) {
					filter = filters.get(0);
				} else {
					filter = "(&" + String.join("", filters) + ")";
				}
			}

			Registration<org.osgi.service.event.EventHandler> registration = Registration.newBuilder(org.osgi.service.event.EventHandler.class)
					.setService(service)
					.setProperty(EventConstants.EVENT_TOPIC, topics.toArray(new String[0]))
					.setProperty(EventConstants.EVENT_FILTER, filter)
					.setBundleContext(CmsService.getDefault().getBundleContext())
					.build();
			return new ResourceEventHandlerRegistration(registration);
		}
	}

}
