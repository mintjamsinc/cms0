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

package org.mintjams.rt.cms.internal.dataset;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.tools.lang.Strings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Resolves the dataset a node belongs to.
 *
 * <p>A folder becomes a dataset by carrying a {@value #DESCRIPTOR_NAME}
 * descriptor. The files directly inside the folder are its rows, and the
 * descriptor declares the typed properties (columns) each row may carry, so
 * that a client can edit them with the right editor and a query can filter,
 * group and aggregate them through the search index.</p>
 *
 * <pre>
 * id: dmg4k2x9a          # required; see {@link Dataset#getId()}
 * label: Equipment loans
 * description: Who has what, and until when.
 * properties:
 *   - key: status        # stored on each row as "&lt;id&gt;_&lt;key&gt;"
 *     label: Status
 *     type: STRING       # STRING | LONG | DOUBLE | DECIMAL | BOOLEAN | DATE
 *     required: true
 *     choices:
 *       - value: available
 *         label: Available
 *         color: sage    # a swatch name a client shows the value with
 *       - value: loaned
 *         label: On loan
 *   - key: due
 *     label: Due
 *     type: DATE
 *   - key: tags
 *     type: STRING
 *     multiple: true
 *     print: false       # left out when the rows are printed
 * </pre>
 *
 * <p>The stored property name is the descriptor's {@code id} joined to the
 * column {@code key} with an underscore. The search index types a field by
 * its name across the whole repository, so a column must never be written
 * under a name that another dataset uses for a different type; the id prefix
 * keeps every dataset's columns in a namespace of its own while the key
 * stays readable in the descriptor and in queries
 * ({@code @dmg4k2x9a_status = 'loaned'}).</p>
 *
 * <p>A descriptor that is missing, unreadable or malformed simply means "no
 * dataset"; it never breaks a read. A column with an invalid key or an unknown
 * type is skipped, not defaulted, so a typo cannot write values under a name
 * with the wrong type.</p>
 */
public final class Datasets {

	/**
	 * Reserved name of the per-folder dataset descriptor. It is configuration,
	 * never a row, and never served over the web (see
	 * {@code CheckProtectedAction}).
	 */
	public static final String DESCRIPTOR_NAME = ".dataset.yml";

	/** Property types a column may declare, spelled as the JCR type names clients already use. */
	public static final Set<String> TYPES = Set.of("STRING", "LONG", "DOUBLE", "DECIMAL", "BOOLEAN", "DATE");

	/**
	 * Shape of a dataset id and of a column key: a letter followed by letters,
	 * digits or underscores. The joined property name then needs no namespace,
	 * is a valid JCR name, and reads as a single token in an XPath predicate.
	 */
	private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

	private Datasets() {}

	/**
	 * The dataset {@code node} belongs to: a folder's own descriptor, or the
	 * parent folder's descriptor for a file. Returns {@code null} when there is
	 * none, and for the descriptor file itself, which is not a row.
	 */
	public static Dataset resolve(Node node) throws RepositoryException {
		Node folder;
		if (node.isNodeType(NodeType.NT_FOLDER)) {
			folder = node;
		} else if (node.isNodeType(NodeType.NT_FILE)) {
			if (DESCRIPTOR_NAME.equals(node.getName())) {
				return null;
			}
			try {
				folder = node.getParent();
			} catch (RepositoryException ignore) {
				return null;
			}
		} else {
			return null;
		}
		return read(folder);
	}

	/**
	 * Client-facing description of the dataset {@code node} belongs to (see
	 * {@link #resolve}), or {@code null} when it belongs to none. Keys:
	 * {@code id}, {@code label}, {@code description}, {@code path} (the dataset
	 * folder) and {@code properties}, a list of {@code key}, {@code name},
	 * {@code label}, {@code description}, {@code type}, {@code multiple},
	 * {@code required}, {@code print} and {@code choices} ({@code value},
	 * {@code label}, {@code color}).
	 */
	public static Map<String, Object> describe(Node node) throws RepositoryException {
		Dataset dataset = resolve(node);
		return (dataset == null) ? null : dataset.toMap();
	}

	/** Parses {@code folder}'s descriptor, or {@code null} when it has no usable one. */
	private static Dataset read(Node folder) {
		Map<?, ?> parsed = readDescriptor(folder);
		if (parsed == null) {
			return null;
		}
		String id = asString(parsed.get("id"));
		if (id == null || !NAME_PATTERN.matcher(id.trim()).matches()) {
			return null;
		}
		id = id.trim();

		String path;
		try {
			path = folder.getPath();
		} catch (RepositoryException ignore) {
			return null;
		}

		List<Property> properties = new ArrayList<>();
		Object declared = parsed.get("properties");
		if (declared instanceof List) {
			for (Object entry : (List<?>) declared) {
				Property property = toProperty(id, entry);
				if (property == null) {
					continue;
				}
				boolean duplicate = false;
				for (Property existing : properties) {
					if (existing.getKey().equals(property.getKey())) {
						duplicate = true;
						break;
					}
				}
				if (!duplicate) {
					properties.add(property);
				}
			}
		}

		return new Dataset(id, path, asString(parsed.get("label")), asString(parsed.get("description")), properties);
	}

	private static Property toProperty(String datasetId, Object entry) {
		if (!(entry instanceof Map)) {
			return null;
		}
		Map<?, ?> map = (Map<?, ?>) entry;
		String key = asString(map.get("key"));
		if (key == null || !NAME_PATTERN.matcher(key.trim()).matches()) {
			return null;
		}
		key = key.trim();

		String type = asString(map.get("type"));
		type = (type == null || type.trim().isEmpty()) ? "STRING" : type.trim().toUpperCase(Locale.ROOT);
		if (!TYPES.contains(type)) {
			return null;
		}

		List<Choice> choices = new ArrayList<>();
		Object declared = map.get("choices");
		if (declared instanceof List) {
			for (Object item : (List<?>) declared) {
				Choice choice = toChoice(item);
				if (choice != null) {
					choices.add(choice);
				}
			}
		}

		// A column prints unless the descriptor says otherwise.
		Object print = map.get("print");
		return new Property(datasetId, key, asString(map.get("label")), asString(map.get("description")), type,
				isTrue(map.get("multiple")), isTrue(map.get("required")), (print == null) || isTrue(print), choices);
	}

	/**
	 * A choice is either a scalar (its value is its label) or a {@code value} /
	 * {@code label} / {@code color} map. The color is the name of a swatch in
	 * the client's palette; the server only carries it.
	 */
	private static Choice toChoice(Object item) {
		if (item instanceof Map) {
			Map<?, ?> map = (Map<?, ?>) item;
			String value = asString(map.get("value"));
			if (value == null) {
				return null;
			}
			String label = asString(map.get("label"));
			return new Choice(value, (label == null) ? value : label, asString(map.get("color")));
		}
		String value = asString(item);
		return (value == null) ? null : new Choice(value, value, null);
	}

	/**
	 * Parses {@code folder}'s {@value #DESCRIPTOR_NAME} descriptor into a map,
	 * or {@code null} when the folder has no descriptor, it is not a file, or
	 * it cannot be parsed.
	 */
	private static Map<?, ?> readDescriptor(Node folder) {
		Node descriptor;
		try {
			if (!folder.hasNode(DESCRIPTOR_NAME)) {
				return null;
			}
			descriptor = folder.getNode(DESCRIPTOR_NAME);
			if (!descriptor.isNodeType(NodeType.NT_FILE)) {
				return null;
			}
		} catch (RepositoryException ignore) {
			return null;
		}

		Object parsed;
		try (Reader in = JCRs.getContentAsReader(descriptor)) {
			parsed = new Load(LoadSettings.builder().build()).loadFromReader(in);
		} catch (Throwable ignore) {
			return null;
		}
		return (parsed instanceof Map) ? (Map<?, ?>) parsed : null;
	}

	private static boolean isTrue(Object value) {
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		return value != null && "true".equalsIgnoreCase(value.toString().trim());
	}

	private static String asString(Object value) {
		if (value == null) {
			return null;
		}
		String s = value.toString();
		return Strings.isEmpty(s) ? null : s;
	}

	/** A dataset: a folder whose direct child files are rows with declared typed properties. */
	public static final class Dataset {
		private final String fId;
		private final String fPath;
		private final String fLabel;
		private final String fDescription;
		private final List<Property> fProperties;

		private Dataset(String id, String path, String label, String description, List<Property> properties) {
			fId = id;
			fPath = path;
			fLabel = label;
			fDescription = description;
			fProperties = Collections.unmodifiableList(new ArrayList<>(properties));
		}

		/**
		 * The descriptor's {@code id}: the prefix of every stored property name.
		 * Generated once when the dataset is created and never changed, since
		 * renaming it would orphan the values already written to the rows.
		 */
		public String getId() {
			return fId;
		}

		/** Path of the dataset folder. */
		public String getPath() {
			return fPath;
		}

		public String getLabel() {
			return fLabel;
		}

		public String getDescription() {
			return fDescription;
		}

		public List<Property> getProperties() {
			return fProperties;
		}

		/** The column stored under {@code name}, or {@code null} when none is. */
		public Property getPropertyByName(String name) {
			for (Property property : fProperties) {
				if (property.getName().equals(name)) {
					return property;
				}
			}
			return null;
		}

		public Map<String, Object> toMap() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("id", fId);
			result.put("path", fPath);
			result.put("label", (fLabel != null) ? fLabel : fId);
			result.put("description", fDescription);
			List<Map<String, Object>> properties = new ArrayList<>(fProperties.size());
			for (Property property : fProperties) {
				properties.add(property.toMap());
			}
			result.put("properties", properties);
			return result;
		}
	}

	/** A column of a dataset. */
	public static final class Property {
		private final String fKey;
		private final String fName;
		private final String fLabel;
		private final String fDescription;
		private final String fType;
		private final boolean fMultiple;
		private final boolean fRequired;
		private final boolean fPrint;
		private final List<Choice> fChoices;

		private Property(String datasetId, String key, String label, String description, String type,
				boolean multiple, boolean required, boolean print, List<Choice> choices) {
			fKey = key;
			fName = datasetId + "_" + key;
			fLabel = label;
			fDescription = description;
			fType = type;
			fMultiple = multiple;
			fRequired = required;
			fPrint = print;
			fChoices = Collections.unmodifiableList(new ArrayList<>(choices));
		}

		/** The key as written in the descriptor. */
		public String getKey() {
			return fKey;
		}

		/** The property name the value is stored under on each row: {@code <id>_<key>}. */
		public String getName() {
			return fName;
		}

		public String getLabel() {
			return fLabel;
		}

		public String getDescription() {
			return fDescription;
		}

		/** One of {@link Datasets#TYPES}. */
		public String getType() {
			return fType;
		}

		public boolean isMultiple() {
			return fMultiple;
		}

		public boolean isRequired() {
			return fRequired;
		}

		/** Whether the column is included when the rows are printed; {@code true} unless the descriptor says {@code print: false}. */
		public boolean isPrint() {
			return fPrint;
		}

		/** Allowed values, or empty when any value of the type is allowed. */
		public List<Choice> getChoices() {
			return fChoices;
		}

		public Map<String, Object> toMap() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("key", fKey);
			result.put("name", fName);
			result.put("label", (fLabel != null) ? fLabel : fKey);
			result.put("description", fDescription);
			result.put("type", fType);
			result.put("multiple", fMultiple);
			result.put("required", fRequired);
			result.put("print", fPrint);
			List<Map<String, Object>> choices = new ArrayList<>(fChoices.size());
			for (Choice choice : fChoices) {
				Map<String, Object> map = new LinkedHashMap<>();
				map.put("value", choice.getValue());
				map.put("label", choice.getLabel());
				map.put("color", choice.getColor());
				choices.add(map);
			}
			result.put("choices", choices);
			return result;
		}
	}

	/** One allowed value of a column. */
	public static final class Choice {
		private final String fValue;
		private final String fLabel;
		private final String fColor;

		private Choice(String value, String label, String color) {
			fValue = value;
			fLabel = label;
			fColor = color;
		}

		public String getValue() {
			return fValue;
		}

		public String getLabel() {
			return fLabel;
		}

		/** The name of the swatch a client shows the value with, or {@code null} for none. */
		public String getColor() {
			return fColor;
		}
	}

}
