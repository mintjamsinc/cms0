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

package org.mintjams.rt.cms.internal.graphql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.commons.io.IOUtils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Class for parsing GraphQL requests
 */
public class GraphQLRequestParser {

	private static final Gson GSON = new Gson();

	/**
	 * Parse GraphQL request from InputStream
	 */
	public static GraphQLRequest parse(InputStream inputStream) throws IOException {
		String json = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
		return parse(json);
	}

	/**
	 * Parse GraphQL request from JSON string
	 */
	public static GraphQLRequest parse(String json) throws IOException {
		try {
			JsonObject jsonObject = GSON.fromJson(json, JsonObject.class);

			String query = jsonObject.has("query") ? jsonObject.get("query").getAsString() : null;
			String operationName = jsonObject.has("operationName") && !jsonObject.get("operationName").isJsonNull()
					? jsonObject.get("operationName").getAsString()
					: null;

			@SuppressWarnings("unchecked")
			Map<String, Object> variables = jsonObject.has("variables") && !jsonObject.get("variables").isJsonNull()
					? GSON.fromJson(jsonObject.get("variables"), Map.class)
					: null;

			return new GraphQLRequest(query, operationName, variables);
		} catch (Exception e) {
			throw new IOException("Failed to parse GraphQL request", e);
		}
	}

	/**
	 * Parse GraphQL request from GET request query parameters
	 */
	public static GraphQLRequest parseFromQueryParams(String query, String operationName, String variablesJson)
			throws IOException {
		@SuppressWarnings("unchecked")
		Map<String, Object> variables = (variablesJson != null && !variablesJson.isEmpty())
				? GSON.fromJson(variablesJson, Map.class)
				: null;

		return new GraphQLRequest(query, operationName, variables);
	}
}
