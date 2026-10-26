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

package org.mintjams.rt.cms.internal.graphql.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What became of the last deployment of a workspace's application GraphQL
 * schema: what is served, the files that took part, and what failed or was
 * ignored. The engine keeps the latest one; the {@code graphqlSchemaDeployment}
 * query reports it, so an author does not have to read the server log.
 */
public final class GraphQLSchemaDeployment {

	public enum Status {
		/** The application files compiled and are served. */
		DEPLOYED,
		/** The last compile failed; the schema served before it still is. */
		KEEPING_PREVIOUS,
		/** The compile at start failed; only the platform schema is served. */
		PLATFORM_ONLY,
		/** Application GraphQL is turned off (graphql.yml); only the platform schema is served. */
		DISABLED,
		/** Not even the platform schema could be compiled; nothing is served. */
		UNAVAILABLE
	}

	/** One file of the application schema and what it contributes. */
	public static final class FileEntry {
		private final String fPath;
		private final String fKind;
		private final String fTarget;

		FileEntry(String path, String kind, String target) {
			fPath = path;
			fKind = kind;
			fTarget = target;
		}

		public String getPath() {
			return fPath;
		}

		/** schema, wiring, resolver, typeResolver or scalar. */
		public String getKind() {
			return fKind;
		}

		/** What the script is bound to ({@code Type.field}, a type or a scalar name); null for schema and wiring files. */
		public String getTarget() {
			return fTarget;
		}
	}

	/** An error or a warning, with the file it is about when there is one. */
	public static final class Problem {
		private final String fPath;
		private final String fMessage;

		Problem(String path, String message) {
			fPath = path;
			fMessage = message;
		}

		public String getPath() {
			return fPath;
		}

		public String getMessage() {
			return fMessage;
		}
	}

	/** Collects what a compile finds; one per compile. */
	public static final class Report {
		private final List<FileEntry> fFiles = new ArrayList<>();
		private final List<Problem> fErrors = new ArrayList<>();
		private final List<Problem> fWarnings = new ArrayList<>();

		public void file(String path, String kind, String target) {
			fFiles.add(new FileEntry(path, kind, target));
		}

		public void error(String path, String message) {
			fErrors.add(new Problem(path, message));
		}

		public void warning(String path, String message) {
			fWarnings.add(new Problem(path, message));
		}

		public boolean hasErrors() {
			return !fErrors.isEmpty();
		}

		public List<String> errorMessages() {
			List<String> messages = new ArrayList<>();
			for (Problem error : fErrors) {
				messages.add(error.getMessage());
			}
			return messages;
		}
	}

	private final Status fStatus;
	private final long fAttemptedAtMillis;
	private final long fServedSinceMillis;
	private final List<FileEntry> fFiles;
	private final List<Problem> fErrors;
	private final List<Problem> fWarnings;

	GraphQLSchemaDeployment(Status status, long attemptedAtMillis, long servedSinceMillis, Report report) {
		fStatus = status;
		fAttemptedAtMillis = attemptedAtMillis;
		fServedSinceMillis = servedSinceMillis;
		fFiles = Collections.unmodifiableList(new ArrayList<>(report.fFiles));
		fErrors = Collections.unmodifiableList(new ArrayList<>(report.fErrors));
		fWarnings = Collections.unmodifiableList(new ArrayList<>(report.fWarnings));
	}

	public Status getStatus() {
		return fStatus;
	}

	/** When the last compile ran. */
	public long getAttemptedAtMillis() {
		return fAttemptedAtMillis;
	}

	/** When the schema now served was compiled; 0 when nothing is served. */
	public long getServedSinceMillis() {
		return fServedSinceMillis;
	}

	/** The application files the last compile found. */
	public List<FileEntry> getFiles() {
		return fFiles;
	}

	/** Why the last compile failed; empty when it did not. */
	public List<Problem> getErrors() {
		return fErrors;
	}

	/** What the last compile ignored or could not use, without failing. */
	public List<Problem> getWarnings() {
		return fWarnings;
	}

}
