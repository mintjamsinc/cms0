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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharFilterFactory;
import org.apache.lucene.analysis.TokenFilterFactory;
import org.apache.lucene.analysis.TokenizerFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.searchindex.SearchIndexConfiguration;
import org.mintjams.tools.collections.AdaptableList;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.io.IOs;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.osgi.Configuration;
import org.mintjams.tools.osgi.VariableProvider;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class SearchIndexConfigurationImpl implements SearchIndexConfiguration {

	private static final String PROP_DATA_PATH = "dataPath";
	private static final String PROP_CONFIG_PATH = "configPath";

	private final Map<String, Object> fConfig = new HashMap<>();

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

		Path searchPath = configPath.resolve("search.yml");
		if (!Files.exists(searchPath)) {
			try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(searchPath, StandardOpenOption.CREATE_NEW))) {
				try (InputStream in = getClass().getResourceAsStream("search.yml")) {
					IOs.copy(in, out);
				}
			}
		}

		try (InputStream in = new BufferedInputStream(Files.newInputStream(searchPath))) {
			Map<String, Object> config = (Map<String, Object>) new Load(LoadSettings.builder().build()).loadFromInputStream(in);
			config.remove(PROP_DATA_PATH);
			config.remove(PROP_CONFIG_PATH);
			fConfig.putAll(config);
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

	public Analyzer getAnalyzer(String name) throws IOException {
		if (!fConfig.containsKey("analyzers")) {
			return null;
		}

		List<String> names = new ArrayList<>();
		names.add(name);
		if (name.indexOf("@") != -1) {
			names.add(name.substring(0, name.indexOf("@")));
		}

		Map<String, Object> analyzers = (Map<String, Object>) fConfig.get("analyzers");
		for (String k : names) {
			Object settings = analyzers.get(k);
			if (settings == null) {
				continue;
			}

			if (settings instanceof List) {
				CustomAnalyzer.Builder builder = CustomAnalyzer.builder(getConfigPath());
				for (Object setting : (List<Object>) settings) {
					if (setting instanceof String) {
						Class<?> type;
						try {
							type = Activator.getDefault().getBundleClassLoader().loadClass((String) setting);
						} catch (ClassNotFoundException ex) {
							throw Cause.create(ex).wrap(IOException.class);
						}
						Map<String, String> params = new HashMap<>();
						if (TokenizerFactory.class.isAssignableFrom(type)) {
							builder.withTokenizer((Class<TokenizerFactory>) type, params);
						} else if (CharFilterFactory.class.isAssignableFrom(type)) {
							builder.addCharFilter((Class<CharFilterFactory>) type, params);
						} else if (TokenFilterFactory.class.isAssignableFrom(type)) {
							builder.addTokenFilter((Class<TokenFilterFactory>) type, params);
						} else {
							throw new IOException("Invalid factory type for analyzer '" + k + "': " + type.getName());
						}
						continue;
					}

					if (setting instanceof Map) {
						Map<String, Object> settingMap = (Map<String, Object>) setting;
						Class<?> type;
						try {
							type = Activator.getDefault().getBundleClassLoader().loadClass((String) settingMap.get("type"));
						} catch (ClassNotFoundException ex) {
							throw Cause.create(ex).wrap(IOException.class);
						}
						Map<String, String> params = new HashMap<>();
						if (!settingMap.containsKey("attributes")) {
							// empty attributes
						} else if (settingMap.get("attributes") instanceof Map) {
							AdaptableMap<String, Object> attributes = AdaptableMap.<String, Object>newBuilder().putAll((Map<String, Object>) settingMap.get("attributes")).build();
							for (String key : attributes.keySet()) {
								params.put(key, attributes.getString(key));
							}
						} else {
							throw new IOException("Invalid attributes for analyzer '" + k + "': " + type.getName());
						}

						if (TokenizerFactory.class.isAssignableFrom(type)) {
							builder.withTokenizer((Class<TokenizerFactory>) type, params);
						} else if (CharFilterFactory.class.isAssignableFrom(type)) {
							builder.addCharFilter((Class<CharFilterFactory>) type, params);
						} else if (TokenFilterFactory.class.isAssignableFrom(type)) {
							builder.addTokenFilter((Class<TokenFilterFactory>) type, params);
						} else {
							throw new IOException("Invalid factory type for analyzer '" + k + "': " + type.getName());
						}
						continue;
					}

					throw new IOException("Invalid configuration for analyzer '" + k + "': " + setting.getClass().getName());
				}
				return builder.build();
			}

			throw new IOException("Invalid configuration for analyzer '" + k + "'.");
		}

		return null;
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
