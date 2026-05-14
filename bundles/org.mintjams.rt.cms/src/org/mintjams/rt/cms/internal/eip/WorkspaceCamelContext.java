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

package org.mintjams.rt.cms.internal.eip;

import org.apache.camel.impl.DefaultCamelContext;
import org.mintjams.rt.cms.internal.WorkspaceDelegatingClassLoader;
import org.mintjams.rt.cms.internal.eip.aggregate.HistogramAggregationStrategy;
import org.mintjams.rt.cms.internal.eip.aggregate.StatsConfigCache;
import org.mintjams.rt.cms.internal.eip.stats.Interval;

public class WorkspaceCamelContext extends DefaultCamelContext {

	@SuppressWarnings("resource")
	public WorkspaceCamelContext(WorkspaceIntegrationEngineProviderConfiguration config) {
		setApplicationContextClassLoader(new WorkspaceDelegatingClassLoader(config.getWorkspaceName()));

		addComponent(EventAdminComponent.COMPONENT_NAME, new EventAdminComponent());
		addComponent(BpmComponent.COMPONENT_NAME, new BpmComponent(config.getWorkspaceName()));
		addComponent(CmsComponent.COMPONENT_NAME, new CmsComponent(config.getWorkspaceName()));
		addComponent(EipComponent.COMPONENT_NAME, new EipComponent(config.getWorkspaceName()));
		addComponent(TransformComponent.COMPONENT_NAME, new TransformComponent(config.getWorkspaceName()));

		StatsConfigCache statsConfigCache = new StatsConfigCache(config.getWorkspaceName());
		getRegistry().bind("statsConfigCache", statsConfigCache);
		getRegistry().bind("histogramStrategy", new HistogramAggregationStrategy(Interval.ONE_MINUTE, statsConfigCache));

		getManagementStrategy().addEventNotifier(
				new ExchangeHistoryEventNotifier(config.getWorkspaceName())
				.setTraceSteps(true)
				.setStatsConfigCache(statsConfigCache));
	}

}
