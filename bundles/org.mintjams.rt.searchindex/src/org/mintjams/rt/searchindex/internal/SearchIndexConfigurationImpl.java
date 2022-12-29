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

package org.mintjams.rt.searchindex.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.mintjams.searchindex.SearchIndex;
import org.mintjams.searchindex.SearchIndexConfiguration;
import org.mintjams.tools.collections.AdaptableList;
import org.mintjams.tools.osgi.Configuration;
import org.mintjams.tools.osgi.VariableProvider;

public class SearchIndexConfigurationImpl implements SearchIndexConfiguration {

	private static final String PROP_DATA_PATH = "dataPath";
	private static final String PROP_CONFIG_PATH = "configPath";

	private Map<String, Object> fConfig = new HashMap<>();

	@Override
	public SearchIndexConfiguration setDataPath(Path path) {
		fConfig.put(PROP_DATA_PATH, path);
		return this;
	}

	@Override
	public SearchIndexConfiguration setConfigPath(Path path) {
		fConfig.put(PROP_CONFIG_PATH, path);
		return this;
	}

	@Override
	public SearchIndex createSearchIndex() throws IOException {
		validate();

		Path configPath = getConfigPath();
		if (!Files.exists(configPath)) {
			Files.createDirectories(configPath);
		}
		Path mappingPath = configPath.resolve("mapping.txt");
		if (!Files.exists(mappingPath)) {
			Files.createFile(mappingPath);
		}
		Path stoptagsPath = configPath.resolve("stoptags.txt");
		if (!Files.exists(stoptagsPath)) {
			Files.createFile(stoptagsPath);
		}
		Path stopwordsPath = configPath.resolve("stopwords.txt");
		if (!Files.exists(stopwordsPath)) {
			Files.createFile(stopwordsPath);
		}
		Path userdictPath = configPath.resolve("userdict.txt");
		if (!Files.exists(userdictPath)) {
			Files.createFile(userdictPath);
		}

		return SearchIndexImpl.create(this);
	}

	private void validate() throws IOException {
		Path dataPath = getDataPath();
		if (Files.exists(dataPath)) {
			if (!Files.isDirectory(dataPath)) {
				throw new IOException("Index path is not a directory: " + dataPath.toString());
			}
		}

		Path configPath = getConfigPath();
		if (Files.exists(configPath)) {
			if (!Files.isDirectory(configPath)) {
				throw new IOException("Configuration path is not a directory: " + configPath.toString());
			}
		}
	}

	public Path getDataPath() {
		String v = adapt(fConfig.get(PROP_DATA_PATH), String.class).trim();
		v = Configuration.create(Activator.getDefault().getBundleContext()).with(new VariableProviderImpl()).replaceVariables(v);
		return Path.of(v).normalize();
	}

	public Path getDocumentPath() {
		return getDataPath().resolve("documents").normalize();
	}

	public Path getDocumentIndexPath() {
		return getDocumentPath().resolve("index").normalize();
	}

	public Path getDocumentTaxonomyPath() {
		return getDocumentPath().resolve("taxonomy").normalize();
	}

	public Path getMultiValuedPath() {
		return getDocumentPath().resolve("multivalued.txt").normalize();
	}

	public Path getSuggestionPath() {
		return getDataPath().resolve("suggestions").normalize();
	}

	public Path getSuggestionIndexPath() {
		return getSuggestionPath().resolve("index").normalize();
	}

	public Path getConfigPath() {
		if (!fConfig.containsKey(PROP_CONFIG_PATH)) {
			return getDataPath().resolve("etc");
		}

		String v = adapt(fConfig.get(PROP_CONFIG_PATH), String.class).trim();
		v = Configuration.create(Activator.getDefault().getBundleContext()).with(new VariableProviderImpl()).replaceVariables(v);
		return Path.of(v).normalize();
	}

	private <AdapterType> AdapterType adapt(Object value, Class<AdapterType> adapterType) {
		return AdaptableList.<Object>newBuilder()
				.setEncoding(StandardCharsets.UTF_8.name())
				.add(value).build().adapt(0, adapterType).getValue();
	}

	private static class VariableProviderImpl implements VariableProvider {
		private final java.util.Properties fSystemProperties = System.getProperties();

		@Override
		public Object getVariable(String name) {
			String value = Activator.getDefault().getBundleContext().getProperty(name);
			if (value != null) {
				return value;
			}

			value = fSystemProperties.getProperty(name);
			if (value != null) {
				return value;
			}

			if ("osgi.instance.area".equals(name)) {
				return ".";
			}

			return null;
		}
	}

}
