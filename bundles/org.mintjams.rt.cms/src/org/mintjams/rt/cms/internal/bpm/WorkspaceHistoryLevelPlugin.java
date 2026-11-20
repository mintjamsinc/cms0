/*
 * Copyright (c) 2026 MintJams Inc.
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

package org.mintjams.rt.cms.internal.bpm;

import java.util.List;

import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.history.HistoryLevel;

/**
 * Puts {@link WorkspaceAuditHistoryLevel} where the engine's own {@code audit}
 * stands, once the engine has set its history levels up and before it reads
 * the level the database was created with. So the workspace runs at it
 * whether {@code bpm.yml} names the level ({@code history: audit}, or nothing)
 * or leaves it to the database ({@code history: auto}).
 */
public class WorkspaceHistoryLevelPlugin extends AbstractProcessEnginePlugin {

	@Override
	public void postInit(ProcessEngineConfigurationImpl configuration) {
		HistoryLevel audit = new WorkspaceAuditHistoryLevel();
		List<HistoryLevel> levels = configuration.getHistoryLevels();
		if (levels != null) {
			for (int i = 0; i < levels.size(); i++) {
				if (levels.get(i).getId() == audit.getId()) {
					levels.set(i, audit);
				}
			}
		}
		// Null when the level is left to the database (auto): the engine then
		// picks it from the list above.
		HistoryLevel configured = configuration.getHistoryLevel();
		if (configured != null && configured.getId() == audit.getId()) {
			configuration.setHistoryLevel(audit);
		}
	}

}
