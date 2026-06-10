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

package org.mintjams.rt.cms.internal.bpm.event;

import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.history.handler.CompositeDbHistoryEventHandler;
import org.camunda.bpm.engine.impl.history.handler.CompositeHistoryEventHandler;
import org.camunda.bpm.engine.impl.history.handler.HistoryEventHandler;

/**
 * Process engine plugin that bridges the Camunda lifecycle to the OSGi
 * EventAdmin service for a workspace, without requiring any BPMN authoring.
 *
 * <p>It installs two complementary, level-aware hooks:
 * <ul>
 *   <li>{@link EventAdminBpmnParseListener} as a pre-parse listener, attaching
 *       execution/task listeners to every process, flow node, sequence flow and
 *       user task. These fire regardless of the configured history level.</li>
 *   <li>{@link EventAdminHistoryEventHandler}, wrapped together with the engine's
 *       database history handler so existing history persistence is preserved,
 *       covering variable and incident events.</li>
 * </ul>
 *
 * <p>All resulting EventAdmin notifications are deferred to transaction commit
 * by {@link BpmEventDispatcher}.
 */
public class EventAdminProcessEnginePlugin extends AbstractProcessEnginePlugin {

	private final BpmEventDispatcher fDispatcher;

	public EventAdminProcessEnginePlugin(String workspaceName) {
		fDispatcher = new BpmEventDispatcher(workspaceName);
	}

	@Override
	public void preInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
		registerParseListener(processEngineConfiguration);
		registerHistoryEventHandler(processEngineConfiguration);
	}

	private void registerParseListener(ProcessEngineConfigurationImpl processEngineConfiguration) {
		List<BpmnParseListener> preParseListeners = processEngineConfiguration.getCustomPreBPMNParseListeners();
		if (preParseListeners == null) {
			preParseListeners = new ArrayList<>();
			processEngineConfiguration.setCustomPreBPMNParseListeners(preParseListeners);
		}
		preParseListeners.add(new EventAdminBpmnParseListener(fDispatcher));
	}

	private void registerHistoryEventHandler(ProcessEngineConfigurationImpl processEngineConfiguration) {
		EventAdminHistoryEventHandler eventAdminHandler = new EventAdminHistoryEventHandler(fDispatcher);

		HistoryEventHandler existing = processEngineConfiguration.getHistoryEventHandler();
		if (existing == null) {
			// CompositeDbHistoryEventHandler keeps the engine's own database
			// history handler and adds ours on top of it.
			processEngineConfiguration.setHistoryEventHandler(new CompositeDbHistoryEventHandler(eventAdminHandler));
		} else {
			// A handler was already configured upstream; preserve it and add ours
			// without introducing a second database handler.
			processEngineConfiguration.setHistoryEventHandler(new CompositeHistoryEventHandler(existing, eventAdminHandler));
		}
	}

}
