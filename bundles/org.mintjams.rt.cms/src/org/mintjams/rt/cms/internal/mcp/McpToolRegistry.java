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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools a workspace defines for itself, as deployed from its
 * {@code tools.yml} files: an immutable snapshot that
 * {@link McpToolCompiler} builds and the workspace's GraphQL engine provider
 * swaps in as a whole after every change under the watched folders.
 *
 * <p>Deployment is recorded file by file, so that a file that could not be
 * deployed is reported next to the ones that were, and so that a client can
 * find out why a tool it expects is missing.
 */
public final class McpToolRegistry {

	public static final McpToolRegistry EMPTY = new McpToolRegistry(Map.of());

	/** What became of one {@code tools.yml}. */
	public static final class Deployment {
		private final String fPath;
		private final long fDeployedAtMillis;
		private final boolean fStale;
		private final List<Entry> fEntries;
		private final List<String> fProblems;

		Deployment(String path, long deployedAtMillis, boolean stale, List<Entry> entries, List<String> problems) {
			fPath = path;
			fDeployedAtMillis = deployedAtMillis;
			fStale = stale;
			fEntries = Collections.unmodifiableList(new ArrayList<>(entries));
			fProblems = Collections.unmodifiableList(new ArrayList<>(problems));
		}

		/** The repository path of the file. */
		public String getPath() {
			return fPath;
		}

		/** When the tools now served from this file were deployed. */
		public long getDeployedAtMillis() {
			return fDeployedAtMillis;
		}

		/**
		 * True when the file could not be read on the last attempt and the
		 * tools of its last good version are still served.
		 */
		public boolean isStale() {
			return fStale;
		}

		public List<Entry> getEntries() {
			return fEntries;
		}

		/** What did not deploy, and why; empty when everything did. */
		public List<String> getProblems() {
			return fProblems;
		}

		Deployment asStale(long deployedAtMillis, List<String> problems) {
			return new Deployment(fPath, fDeployedAtMillis, true, fEntries, problems);
		}
	}

	/** One tool of a file, with what a status listing needs to say about it. */
	public static final class Entry {
		private final McpTool fTool;
		private final String fKind;
		private final String fAccess;
		private final boolean fEnabled;

		Entry(McpTool tool, String kind, String access, boolean enabled) {
			fTool = tool;
			fKind = kind;
			fAccess = access;
			fEnabled = enabled;
		}

		public McpTool getTool() {
			return fTool;
		}

		/** {@code graphql} or {@code script}. */
		public String getKind() {
			return fKind;
		}

		/** {@code read}, {@code write} or {@code destructive}. */
		public String getAccess() {
			return fAccess;
		}

		/** False for a tool whose definition says {@code enabled: false}; it is not served. */
		public boolean isEnabled() {
			return fEnabled;
		}
	}

	private final Map<String, Deployment> fDeployments;
	private final Map<String, McpTool> fTools;

	McpToolRegistry(Map<String, Deployment> deployments) {
		fDeployments = Collections.unmodifiableMap(new LinkedHashMap<>(deployments));
		Map<String, McpTool> tools = new LinkedHashMap<>();
		for (Deployment deployment : deployments.values()) {
			for (Entry entry : deployment.getEntries()) {
				if (entry.isEnabled()) {
					tools.put(entry.getTool().getName(), entry.getTool());
				}
			}
		}
		fTools = Collections.unmodifiableMap(tools);
	}

	/** The deployments, by file path, in the order the files were found. */
	public Map<String, Deployment> getDeployments() {
		return fDeployments;
	}

	/** The tools that are served. */
	public Collection<McpTool> getTools() {
		return fTools.values();
	}

	public McpTool getTool(String name) {
		return fTools.get(name);
	}

	public boolean isEmpty() {
		return fDeployments.isEmpty();
	}
}
