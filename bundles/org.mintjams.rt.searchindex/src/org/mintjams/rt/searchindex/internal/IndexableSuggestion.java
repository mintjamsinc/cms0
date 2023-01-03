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
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.util.BytesRef;
import org.apache.tika.Tika;
import org.apache.tika.exception.ZeroByteFileException;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.collections.AdaptableList;
import org.mintjams.tools.lang.Strings;

public class IndexableSuggestion implements SearchIndex.Suggestion {

	private String fIdentifier;
	private String fPath;
	private String fName;
	private String fMimeType;
	private String fEncoding;
	private Long fSize;
	private Object fContent;
	private final Map<String, List<Object>> fProperties = new HashMap<>();
	private String fSuggestion;
	private final List<String> fAuthorized = new ArrayList<>();

	private IndexableSuggestion() {}

	public static IndexableSuggestion create(UnaryOperator<SearchIndex.Suggestion> op) {
		return (IndexableSuggestion) op.apply(new IndexableSuggestion());
	}

	@Override
	public SearchIndex.Suggestion setIdentifier(String identifier) {
		if (identifier == null) {
			throw new IllegalArgumentException("Identifier must not be null.");
		}
		fIdentifier = identifier;
		return this;
	}

	@Override
	public SearchIndex.Suggestion setPath(String path) {
		fPath = path;
		if (Strings.isEmpty(fPath) || fPath.equals("/")) {
			fName = "";
		} else {
			fName = fPath.substring(fPath.lastIndexOf("/") + 1);
		}
		return this;
	}

	@Override
	public SearchIndex.Suggestion setMimeType(String mimeType) {
		fMimeType = mimeType;
		return this;
	}

	@Override
	public SearchIndex.Suggestion setEncoding(String encoding) {
		fEncoding = encoding;
		return this;
	}

	@Override
	public SearchIndex.Suggestion setSize(long size) {
		if (size < 0) {
			throw new IllegalArgumentException("Invalid document size: " + size);
		}

		fSize = size;
		return this;
	}

	@Override
	public SearchIndex.Suggestion setContent(String text) {
		fContent = text;
		return this;
	}

	@Override
	public SearchIndex.Suggestion setContent(Path path) {
		fContent = path;
		return this;
	}

	@Override
	public SearchIndex.Suggestion setContent(InputStream stream) {
		fContent = stream;
		return this;
	}

	@Override
	public SearchIndex.Suggestion addProperty(String name, String value) {
		property(name, value);
		return this;
	}

	@Override
	public SearchIndex.Suggestion addProperty(String name, BigDecimal value) {
		property(name, value);
		return this;
	}

	@Override
	public SearchIndex.Suggestion addProperty(String name, Date value) {
		property(name, value);
		return this;
	}

	@Override
	public SearchIndex.Suggestion addProperty(String name, Calendar value) {
		property(name, value);
		return this;
	}

	@Override
	public SearchIndex.Suggestion addProperty(String name, boolean value) {
		property(name, value);
		return this;
	}

	@Override
	public SearchIndex.Suggestion removeProperty(String name) {
		fProperties.remove(name);
		return this;
	}

	@Override
	public SearchIndex.Suggestion setSuggestion(String suggestion) {
		if (Strings.isEmpty(suggestion)) {
			throw new IllegalArgumentException("Suggestion must not be null or empty.");
		}
		fSuggestion = suggestion;
		return this;
	}

	@Override
	public SearchIndex.Suggestion addAuthorized(String... names) {
		if (names == null) {
			return this;
		}

		for (String name : names) {
			if (!fAuthorized.contains(name)) {
				fAuthorized.add(name);
			}
		}
		return this;
	}

	@Override
	public SearchIndex.Suggestion removeAuthorized(String name) {
		fAuthorized.remove(name);
		return this;
	}

	public org.apache.lucene.document.Document asLuceneDocument() throws IOException {
		Document doc = new Document();
		StringBuilder fulltext = new StringBuilder();
		doc.add(new StringField("_identifier", fIdentifier, Field.Store.YES));

		fulltext.append(getContentAsString());

		for (Map.Entry<String, List<Object>> e : fProperties.entrySet()) {
			String name = e.getKey();
			addField(doc, name, e.getValue(), fulltext);
			doc.add(new StringField("_properties", name, Field.Store.NO));
		}

		if (fPath != null) {
			doc.add(new StringField("_path", fPath, Field.Store.NO));
			fulltext.append("\n").append(fPath);

			if (Strings.isNotEmpty(fPath) && !fPath.equals("/")) {
				String parentPath = fPath;
				parentPath = parentPath.substring(0, parentPath.lastIndexOf("/"));
				doc.add(new StringField("_parentPath", parentPath, Field.Store.NO));
			}

			if (Strings.isEmpty(fPath) || fPath.equals("/")) {
				doc.add(new LongPoint("_depth", 0));
			} else {
				long depth = fPath.split("\\/").length - 1;
				doc.add(new LongPoint("_depth", depth));
			}
		}

		if (fName != null) {
			doc.add(new StringField("_name", fName, Field.Store.NO));
			fulltext.append("\n").append(fName);
		}

		if (fMimeType != null) {
			String mimeType = Strings.defaultIfEmpty(fMimeType, "application/octet-stream");
			doc.add(new StringField("_mimeType", mimeType, Field.Store.NO));
			fulltext.append("\n").append(mimeType);
		}

		if (fEncoding != null) {
			doc.add(new StringField("_encoding", fEncoding, Field.Store.NO));
			fulltext.append("\n").append(Strings.defaultIfEmpty(fEncoding, StandardCharsets.UTF_8.toString()));
		}

		if (fSize != null) {
			long value = fSize.longValue();
			doc.add(new LongPoint("_size", value));
		}

		doc.add(new TextField("_fulltext", fulltext.toString(), Field.Store.NO));

		doc.add(new StringField("_suggestion", fSuggestion, Field.Store.YES));
		doc.add(new SortedDocValuesField("_suggestion", new BytesRef(fSuggestion)));
		for (String s : fSuggestion.split("\\s")) {
			if (Strings.isEmpty(s)) {
				continue;
			}
			doc.add(new TextField("_completion", s.trim(), Field.Store.NO));
		}

		for (String e : fAuthorized) {
			doc.add(new StringField("_authorized", e, Field.Store.NO));
		}

		return doc;
	}

	private SearchIndex.Suggestion property(String name, Object value) {
		validate(name, value);
		List<Object> values = fProperties.get(name);
		if (values == null) {
			values = new ArrayList<>();
			fProperties.put(name, values);
		}
		values.add(value);
		return this;
	}

	private void validate(String name, Object value) {
		if (name == null) {
			throw new IllegalArgumentException("Name must not be null.");
		}
		if (name.startsWith("_")) {
			throw new IllegalArgumentException("Reserved property name: " + name);
		}
		if (value == null) {
			throw new IllegalArgumentException("Value must not be null.");
		}
	}

	String _contentString;
	private String getContentAsString() throws IOException {
		if (_contentString == null) {
			_contentString = "";
			do {
				if (fContent == null) {
					break;
				}

				if (Strings.defaultString(fMimeType).toLowerCase().startsWith("image/")
						|| Strings.defaultString(fMimeType).toLowerCase().startsWith("video/")
						|| Strings.defaultString(fMimeType).toLowerCase().startsWith("audio/")) {
					break;
				}

				if (fContent instanceof String) {
					_contentString = (String) fContent;
					break;
				}

				if (fContent instanceof Path) {
					if (Files.size((Path) fContent) == 0) {
						break;
					}

					try {
						_contentString = Strings.defaultString(new Tika().parseToString((Path) fContent));
					} catch (ZeroByteFileException ex) {
						_contentString = "";
					} catch (Throwable ex) {
						Activator.getLogger(getClass()).warn("An error occurred while extracting the text from the document.", ex);
					}
					break;
				}

				if (fContent instanceof InputStream) {
					try (InputStream in = (InputStream) fContent) {
						_contentString = Strings.defaultString(new Tika().parseToString(in));
					} catch (ZeroByteFileException ex) {
						_contentString = "";
					} catch (Throwable ex) {
						Activator.getLogger(getClass()).warn("An error occurred while extracting the text from the document.", ex);
					}
					break;
				}
			} while (false);
		}

		return _contentString;
	}

	private void addField(org.apache.lucene.document.Document doc, String name, List<Object> values, StringBuilder fulltext) {
		for (int i = 0; i < values.size(); i++) {
			Object value = values.get(i);
			AdaptableList<Object> v = AdaptableList.<Object>newBuilder().add(value).build();

			if (value instanceof String) {
				doc.add(new StringField(name, v.getString(0), Field.Store.NO));
				fulltext.append("\n").append(v.getString(0));
				continue;
			}

			if (value instanceof BigDecimal) {
				doc.add(new DoublePoint(name, v.getDouble(0)));
				continue;
			}

			if (value instanceof java.util.Date || value instanceof Calendar) {
				doc.add(new LongPoint(name, v.getDate(0).getTime()));
				continue;
			}

			if (value instanceof Boolean) {
				doc.add(new IntPoint(name, v.getBoolean(0) ? 1 : 0));
				continue;
			}
		}
	}

}
