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

package org.mintjams.cms;

/**
 * The Workspace interface defines the event topics for the workspace lifecycle.
 * Creation and deletion are facts for the whole cluster: every node receives each of those events once, whichever node carried out the operation.
 * Start and stop describe a single node: each node posts them for its own services only, so a handler receives them for the node it runs on.
 */
public interface Workspace {

	/**
	 * Event topic for workspace creation.
	 * Posted on every node in the cluster, once the workspace has been created; the services start afterwards, announced by {@link #TOPIC_STARTED}.
	 * property: workspace (String) - The name of the created workspace.
	 */
	String TOPIC_CREATED = "org/mintjams/cms/Workspace/CREATED";

	/**
	 * Event topic for workspace deletion.
	 * Posted on every node in the cluster, once no node has the workspace open and its data has been removed.
	 * property: workspace (String) - The name of the deleted workspace.
	 */
	String TOPIC_DELETED = "org/mintjams/cms/Workspace/DELETED";

	/**
	 * Event topic for workspace start.
	 * Posted on this node only, once the workspace's services on this node have fully started, including at node startup.
	 * A start that fails part-way posts nothing.
	 * property: workspace (String) - The name of the started workspace.
	 */
	String TOPIC_STARTED = "org/mintjams/cms/Workspace/STARTED";

	/**
	 * Event topic for workspace stop.
	 * Posted on this node only, once the workspace's services on this node have stopped, including at node shutdown.
	 * Always preceded by {@link #TOPIC_STARTED} for the same workspace on the same node.
	 * property: workspace (String) - The name of the stopped workspace.
	 */
	String TOPIC_STOPPED = "org/mintjams/cms/Workspace/STOPPED";

}
