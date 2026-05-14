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

package org.mintjams.rt.cms.internal.eip.aggregate;

import java.util.Collections;
import java.util.List;

/**
 * Per-route configuration that drives EIP statistics aggregation.
 *
 * <p>Loaded from {@code /etc/eip/stats-config/{routeId}.json} in JCR. Shape:
 * <pre>{@code
 * {
 *   "route": "commerce-product-update",
 *   "dimensions": ["header.vendor", "header.product_type"],
 *   "indexedHeaders": ["product_id", "title", "vendor", "product_type"]
 * }
 * }</pre>
 *
 * <p>A {@code dimension} is a path-style selector. Currently supported:
 * <ul>
 *   <li>{@code header.{name}} — extracts {@code headers.{name}.value} from
 *       the exchange history record produced by
 *       {@code ExchangeHistoryEventNotifier}.</li>
 * </ul>
 *
 * <p>{@code indexedHeaders} is reserved for the future Lucene-promotion path
 * (history record → JCR property) and is not consumed by the aggregator.
 */
public class StatsConfig {

	public static final StatsConfig EMPTY = new StatsConfig(null, Collections.emptyList(), Collections.emptyList());

	private final String fRoute;
	private final List<String> fDimensions;
	private final List<String> fIndexedHeaders;

	public StatsConfig(String route, List<String> dimensions, List<String> indexedHeaders) {
		fRoute = route;
		fDimensions = dimensions == null ? Collections.emptyList() : List.copyOf(dimensions);
		fIndexedHeaders = indexedHeaders == null ? Collections.emptyList() : List.copyOf(indexedHeaders);
	}

	public String route() {
		return fRoute;
	}

	public List<String> dimensions() {
		return fDimensions;
	}

	public List<String> indexedHeaders() {
		return fIndexedHeaders;
	}
}
