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

package org.mintjams.rt.cms.internal.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * The tools this server offers, in the order {@code tools/list} reports them:
 * reading first, diagnosis next, then the tools that change content, and the
 * general-purpose GraphQL tool last.
 */
final class McpTools {

	private McpTools() {}

	static List<McpTool> all() {
		List<McpTool> read = new ArrayList<>();
		List<McpTool> write = new ArrayList<>();
		for (McpTool tool : ContentTools.all()) {
			(tool.isWrite() ? write : read).add(tool);
		}

		List<McpTool> tools = new ArrayList<>(read);
		tools.addAll(WebRenderTools.all());
		tools.addAll(write);
		tools.addAll(GraphQLTools.all());
		return tools;
	}

}
