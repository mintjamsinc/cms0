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
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.FacetField;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.apache.tika.Tika;
import org.apache.tika.exception.ZeroByteFileException;
import org.mintjams.searchindex.SearchIndex;
import org.mintjams.tools.collections.AdaptableList;
import org.mintjams.tools.lang.Strings;

public class IndexableDocument implements SearchIndex.Document {

	private String fIdentifier;
	private String fPath;
	private String fName;
	private String fMimeType;
	private String fEncoding;
	private Long fSize;
	private java.util.Date fCreated;
	private String fCreatedBy;
	private java.util.Date fLastModified;
	private String fLastModifiedBy;
	private Object fContent;
	private final Map<String, List<Object>> fProperties = new HashMap<>();
	private boolean fStored;
	private final List<String> fAuthorized = new ArrayList<>();
	private final List<String> fMultiValuedDimensions = new ArrayList<>();

	private IndexableDocument() {}

	public static IndexableDocument create(UnaryOperator<SearchIndex.Document> op) {
		return (IndexableDocument) op.apply(new IndexableDocument());
	}

	@Override
	public SearchIndex.Document setIdentifier(String identifier) {
		if (identifier == null) {
			throw new IllegalArgumentException("Identifier must not be null.");
		}
		fIdentifier = identifier;
		return this;
	}

	@Override
	public SearchIndex.Document setPath(String path) {
		fPath = path;
		if (Strings.isEmpty(fPath) || fPath.equals("/")) {
			fName = "";
		} else {
			fName = fPath.substring(fPath.lastIndexOf("/") + 1);
		}
		return this;
	}

	@Override
	public SearchIndex.Document setMimeType(String mimeType) {
		fMimeType = mimeType;
		return this;
	}

	@Override
	public SearchIndex.Document setEncoding(String encoding) {
		fEncoding = encoding;
		return this;
	}

	@Override
	public SearchIndex.Document setSize(long size) {
		if (size < 0) {
			throw new IllegalArgumentException("Invalid document size: " + size);
		}

		fSize = size;
		return this;
	}

	@Override
	public SearchIndex.Document setCreated(Date created) {
		fCreated = created;
		return this;
	}

	@Override
	public SearchIndex.Document setCreatedBy(String createdBy) {
		fCreatedBy = createdBy;
		return this;
	}

	@Override
	public SearchIndex.Document setLastModified(Date lastModified) {
		fLastModified = lastModified;
		return this;
	}

	@Override
	public SearchIndex.Document setLastModifiedBy(String lastModifiedBy) {
		fLastModifiedBy = lastModifiedBy;
		return this;
	}

	@Override
	public SearchIndex.Document setContent(String text) {
		fContent = text;
		return this;
	}

	@Override
	public SearchIndex.Document setContent(Path path) {
		fContent = path;
		return this;
	}

	@Override
	public SearchIndex.Document setContent(InputStream stream) {
		fContent = stream;
		return this;
	}

	@Override
	public SearchIndex.Document addProperty(String name, String value) {
		property(name, value);
		return this;
	}

	@Override
	public SearchIndex.Document addProperty(String name, BigDecimal value) {
		property(name, value);
		return this;
	}

	@Override
	public SearchIndex.Document addProperty(String name, Date value) {
		property(name, value);
		return this;
	}

	@Override
	public SearchIndex.Document addProperty(String name, Calendar value) {
		property(name, value);
		return this;
	}

	@Override
	public SearchIndex.Document addProperty(String name, boolean value) {
		property(name, value);
		return this;
	}

	@Override
	public SearchIndex.Document removeProperty(String name) {
		fProperties.remove(name);
		return this;
	}

	@Override
	public SearchIndex.Document setStored(boolean stored) {
		fStored = stored;
		return this;
	}

	@Override
	public SearchIndex.Document addAuthorized(String... names) {
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
	public SearchIndex.Document removeAuthorized(String name) {
		fAuthorized.remove(name);
		return this;
	}

	public org.apache.lucene.document.Document asLuceneDocument() throws IOException {
		Document doc = new Document();
		StringBuilder fulltext = new StringBuilder();
		doc.add(new StringField("_identifier", fIdentifier, Field.Store.YES));
		doc.add(new SortedDocValuesField("_identifier", new BytesRef(fIdentifier)));

		fulltext.append(getContentAsString());

		for (Map.Entry<String, List<Object>> e : fProperties.entrySet()) {
			String name = e.getKey();
			addField(doc, name, e.getValue(), fulltext);
			doc.add(new StringField("_properties", name, Field.Store.NO));
		}

		if (fPath != null) {
			doc.add(new StringField("_path", fPath, Field.Store.NO));
			doc.add(new SortedDocValuesField("_path", new BytesRef(fPath)));
			fulltext.append("\n").append(fPath);

                       if (Strings.isNotEmpty(fPath) && !fPath.equals("/")) {
                               String parentPath = fPath;
                               int idx = parentPath.lastIndexOf("/");
                               if (idx >= 0) {
                                       parentPath = parentPath.substring(0, idx);
                               } else {
                                       parentPath = "";
                               }
                               doc.add(new StringField("_parentPath", parentPath, Field.Store.NO));
                       }

			if (Strings.isEmpty(fPath) || fPath.equals("/")) {
				doc.add(new LongPoint("_depth", 0));
				doc.add(new SortedNumericDocValuesField("_depth", 0));
			} else {
				long depth = fPath.split("\\/").length - 1;
				doc.add(new LongPoint("_depth", depth));
				doc.add(new SortedNumericDocValuesField("_depth", depth));
			}
		}

		if (fName != null) {
			doc.add(new StringField("_name", fName, Field.Store.NO));
			doc.add(new SortedDocValuesField("_name", new BytesRef(fName)));
			fulltext.append("\n").append(fName);
		}

		if (fMimeType != null) {
			String mimeType = Strings.defaultIfEmpty(fMimeType, "application/octet-stream");
			doc.add(new StringField("_mimeType", mimeType, Field.Store.NO));
			doc.add(new SortedDocValuesField("_mimeType", new BytesRef(mimeType)));
			fulltext.append("\n").append(mimeType);
		}

		if (fEncoding != null) {
			doc.add(new StringField("_encoding", fEncoding, Field.Store.NO));
			doc.add(new SortedDocValuesField("_encoding", new BytesRef(fEncoding)));
			fulltext.append("\n").append(Strings.defaultIfEmpty(fEncoding, StandardCharsets.UTF_8.toString()));
		}

		if (fSize != null) {
			long value = fSize.longValue();
			doc.add(new LongPoint("_size", value));
			doc.add(new SortedNumericDocValuesField("_size", value));
		}

		if (fCreated != null) {
			long value = fCreated.getTime();
			doc.add(new LongPoint("_created", value));
			doc.add(new SortedNumericDocValuesField("_created", value));
		}

		if (fCreatedBy != null) {
			doc.add(new StringField("_createdBy", fCreatedBy, Field.Store.NO));
			doc.add(new SortedDocValuesField("_createdBy", new BytesRef(fCreatedBy)));
			fulltext.append("\n").append(Strings.defaultIfEmpty(fCreatedBy, StandardCharsets.UTF_8.toString()));
		}

		if (fLastModified != null) {
			long value = fLastModified.getTime();
			doc.add(new LongPoint("_lastModified", value));
			doc.add(new SortedNumericDocValuesField("_lastModified", value));
		}

		if (fLastModifiedBy != null) {
			doc.add(new StringField("_lastModifiedBy", fLastModifiedBy, Field.Store.NO));
			doc.add(new SortedDocValuesField("_lastModifiedBy", new BytesRef(fLastModifiedBy)));
			fulltext.append("\n").append(Strings.defaultIfEmpty(fLastModifiedBy, StandardCharsets.UTF_8.toString()));
		}

		doc.add(new TextField("_fulltext", fulltext.toString(), Field.Store.NO));

		for (String e : fAuthorized) {
			doc.add(new StringField("_authorized", e, Field.Store.NO));
		}

		return doc;
	}

	private SearchIndex.Document property(String name, Object value) {
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
				doc.add(new StringField(name, v.getString(0), fStored ? Field.Store.YES : Field.Store.NO));
				if (i == 0) {
					doc.add(new SortedDocValuesField(name, new BytesRef(v.getString(0))));
				}
				fulltext.append("\n").append(v.getString(0));
				if (Strings.isNotEmpty(v.getString(0))) {
					doc.add(new FacetField(name, v.getString(0)));
					if (i > 0) {
						fMultiValuedDimensions.add(name);
					}
				}
				continue;
			}

			if (value instanceof BigDecimal) {
				doc.add(new DoublePoint(name, v.getDouble(0)));
				if (i == 0) {
					doc.add(new SortedNumericDocValuesField(name, NumericUtils.doubleToSortableLong(v.getDouble(0))));
				}
				if (fStored) {
					doc.add(new StoredField(name, v.getString(0)));
				}
				continue;
			}

			if (value instanceof java.util.Date || value instanceof Calendar) {
				doc.add(new LongPoint(name, v.getDate(0).getTime()));
				if (i == 0) {
					doc.add(new SortedNumericDocValuesField(name, v.getDate(0).getTime()));
				}
				if (fStored) {
					doc.add(new StoredField(name, v.getString(0)));
				}
				continue;
			}

			if (value instanceof Boolean) {
				doc.add(new IntPoint(name, v.getBoolean(0) ? 1 : 0));
				if (i == 0) {
					doc.add(new SortedNumericDocValuesField(name, v.getBoolean(0) ? 1 : 0));
				}
				if (fStored) {
					doc.add(new StoredField(name, v.getString(0)));
				}
				continue;
			}
		}
	}

	public String[] getMultiValuedDimensions() {
		return fMultiValuedDimensions.toArray(String[]::new);
	}

}
