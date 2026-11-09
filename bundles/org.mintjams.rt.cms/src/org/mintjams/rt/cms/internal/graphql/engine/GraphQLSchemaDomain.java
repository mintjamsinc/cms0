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

import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.parser.Parser;

/**
 * One part of a workspace's GraphQL schema, as its authors wrote it: a platform
 * contributor's SDL, or the files of one folder under {@code /etc/graphql} or
 * {@code /content/WEB-INF/graphql}.
 *
 * <p>The schema is compiled as one unit, but it is written in parts, each
 * about one thing — content, BPM, the identity store, a package's own fields
 * — and each small enough to read whole. The MCP server hands these parts to
 * a model instead of the introspection of the whole schema, which is several
 * times larger and carries none of the comments. The root fields are listed
 * so that the parts can be told apart before any is read.
 */
public final class GraphQLSchemaDomain {

	private final String fName;
	private final String fDescription;
	private final String fSdl;
	private final boolean fPlatform;
	private final List<String> fQueryFields;
	private final List<String> fMutationFields;
	private final List<String> fSubscriptionFields;

	private GraphQLSchemaDomain(String name, String description, String sdl, boolean platform,
			List<String> queryFields, List<String> mutationFields, List<String> subscriptionFields) {
		fName = name;
		fDescription = description;
		fSdl = sdl;
		fPlatform = platform;
		fQueryFields = Collections.unmodifiableList(queryFields);
		fMutationFields = Collections.unmodifiableList(mutationFields);
		fSubscriptionFields = Collections.unmodifiableList(subscriptionFields);
	}

	/**
	 * Describes {@code sdl} as one domain. The root fields are read from the
	 * SDL's {@code Query}, {@code Mutation} and {@code Subscription} definitions
	 * and extensions; SDL that does not parse yields a domain with no root
	 * fields rather than failing, since the compile reports the fault itself.
	 */
	public static GraphQLSchemaDomain of(String name, String description, String sdl, boolean platform) {
		List<String> queries = new ArrayList<>();
		List<String> mutations = new ArrayList<>();
		List<String> subscriptions = new ArrayList<>();
		try {
			Document document = Parser.parse(sdl);
			for (Definition<?> definition : document.getDefinitions()) {
				String typeName;
				List<FieldDefinition> fields;
				if (definition instanceof ObjectTypeExtensionDefinition) {
					typeName = ((ObjectTypeExtensionDefinition) definition).getName();
					fields = ((ObjectTypeExtensionDefinition) definition).getFieldDefinitions();
				} else if (definition instanceof ObjectTypeDefinition) {
					typeName = ((ObjectTypeDefinition) definition).getName();
					fields = ((ObjectTypeDefinition) definition).getFieldDefinitions();
				} else {
					continue;
				}
				List<String> target;
				if (typeName.equals("Query")) {
					target = queries;
				} else if (typeName.equals("Mutation")) {
					target = mutations;
				} else if (typeName.equals("Subscription")) {
					target = subscriptions;
				} else {
					continue;
				}
				for (FieldDefinition field : fields) {
					target.add(field.getName());
				}
			}
		} catch (RuntimeException ignore) {
			// reported by the compile
		}
		return new GraphQLSchemaDomain(name, description, sdl, platform, queries, mutations, subscriptions);
	}

	/**
	 * The first line of comment in {@code sdl} that says something, with its
	 * {@code #} removed: the one-line summary the platform's and the packages'
	 * schema files all start with. Rulers (a line of {@code =} or {@code -})
	 * are skipped. Null when the file starts with no comment.
	 */
	public static String summaryOf(String sdl) {
		if (sdl == null) {
			return null;
		}
		for (String line : sdl.split("\r?\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (!trimmed.startsWith("#")) {
				return null;
			}
			String text = trimmed.substring(1).trim();
			if (text.isEmpty() || text.chars().allMatch(c -> c == '=' || c == '-')) {
				continue;
			}
			return text;
		}
		return null;
	}

	/** A short name: a platform part's name, or a folder relative to the schema root (e.g. {@code site}). */
	public String getName() {
		return fName;
	}

	/** One line on what the part is about. */
	public String getDescription() {
		return fDescription;
	}

	/** The SDL as written, comments included. */
	public String getSdl() {
		return fSdl;
	}

	/** Whether the part is the platform's (true) or the workspace's own (false). */
	public boolean isPlatform() {
		return fPlatform;
	}

	public List<String> getQueryFields() {
		return fQueryFields;
	}

	public List<String> getMutationFields() {
		return fMutationFields;
	}

	public List<String> getSubscriptionFields() {
		return fSubscriptionFields;
	}

}
