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

import org.camunda.bpm.engine.impl.history.HistoryLevelAudit;
import org.camunda.bpm.engine.impl.history.event.HistoryEventType;
import org.camunda.bpm.engine.impl.history.event.HistoryEventTypes;

/**
 * The history level {@code audit} of a workspace: what the engine's own
 * {@code audit} keeps, and who held each task.
 *
 * <p>The engine keeps the assignments of a task (the identity link log) and
 * what the people signed in did to it (the user operation log: claimed,
 * released, reassigned, completed) only at {@code full}, together with every
 * change of every variable. At {@code audit} a task remembers its last
 * assignee and nothing else, so somebody who claimed a task and released it
 * again is nowhere in the history. This level adds those two logs to
 * {@code audit}, and leaves the variable changes to {@code full}.
 *
 * <p>It takes the place of the engine's level of the same name and id, so a
 * database that was created at {@code audit} needs nothing done to it; what
 * ran before this level existed has no such entries.
 */
public class WorkspaceAuditHistoryLevel extends HistoryLevelAudit {

	@Override
	public boolean isHistoryEventProduced(HistoryEventType eventType, Object entity) {
		return super.isHistoryEventProduced(eventType, entity) ||
				eventType == HistoryEventTypes.IDENTITY_LINK_ADD ||
				eventType == HistoryEventTypes.IDENTITY_LINK_DELETE ||
				eventType == HistoryEventTypes.USER_OPERATION_LOG;
	}

}
