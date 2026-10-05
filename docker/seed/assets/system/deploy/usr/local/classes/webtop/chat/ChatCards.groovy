package webtop.chat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * The card designs a message can carry.
 *
 * A design is a folder holding two files:
 *
 *   card.yml    what the card is and the fields it takes
 *   card.html   the page that shows it, in the reader's browser
 *
 * The designs offered when writing a message are the folders under
 * /etc/chat/cards that the writer can read. A message keeps the folder's path
 * and the values of the fields, never the page: the page is loaded, as it is
 * now, by whoever reads the message, in a frame of the conversation. The
 * reader must be able to read the folder; who cannot sees the message's text
 * and no card.
 *
 * card.yml:
 *
 *   label: Meeting
 *   description: Invites to a meeting.
 *   summary: "{{title}} — {{date}}"    # the message's text when none was written
 *   fields:
 *     - key: title
 *       label: Title
 *       type: STRING                   # STRING | LONG | DOUBLE | DECIMAL | BOOLEAN | DATE
 *       required: true
 *     - key: kind
 *       choices: [{ value: online, label: Online, color: peacock }, onsite]
 *
 * The fields are declared the way the columns of a dataset are (`properties`
 * of .dataset.yml): key, label, description, type, multiple, required and
 * choices, each choice a value with a label and a color of the palette, or a
 * bare value. A key is a letter followed by letters, digits or underscores.
 *
 * The values are stored by type: STRING as text, LONG and DOUBLE as numbers,
 * DECIMAL as the digits that were given, BOOLEAN as true or false, DATE as an
 * ISO 8601 instant in UTC; a multiple field as a list. A value that does not
 * fit its field is refused, so the page can trust what it is given.
 *
 * A design may keep its messages in its own folder, as an app does:
 * i18n/<locale>.json, flat JSON of message keys, the locale being the last
 * dot-delimited segment of the name (en.json, ja.json, notice.ja.json). Any
 * text of card.yml (the label, the description, the summary, a field's label
 * and description, a choice's label) may then be a key of those messages; a
 * text that is no key is shown as it is written. The reader's browser
 * resolves them in the reader's language; here they are resolved only for
 * the summary, in the writer's.
 */
class ChatCards {

	static final String ROOT = '/etc/chat/cards';
	static final String DESCRIPTOR = 'card.yml';
	static final String PAGE = 'card.html';
	static final String MESSAGES = 'i18n';
	static final String DEFAULT_LOCALE = 'en';
	static final List<String> TYPES = ['STRING', 'LONG', 'DOUBLE', 'DECIMAL', 'BOOLEAN', 'DATE'];
	static final int MAX_FIELDS = 50;
	static final int MAX_VALUES = 100;
	static final int MAX_TEXT = 4000;

	private static final Pattern NAME = ~/^[A-Za-z][A-Za-z0-9_]*$/;
	private static final Pattern PLACEHOLDER = ~/\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*\}\}/;

	/** The path of a design's folder, checked: absolute and plain. */
	static String checkPath(String path) {
		String p = (path ?: '').trim();
		if (!p.startsWith('/') || p.length() > 1024 || p.endsWith('/') || p.contains('//') ||
				p.split('/').any { it == '.' || it == '..' } || p.any { Character.isISOControl(it as char) }) {
			throw new IllegalArgumentException('Invalid card path.');
		}
		return p;
	}

	private static boolean readableFile(r) {
		try {
			return r.exists() && !r.isCollection() && r.canRead();
		} catch (Throwable ignore) {
			return false;
		}
	}

	/**
	 * The designs under ROOT the session can read, by label. `context` is the
	 * script context that reads them (for its YAML).
	 */
	static List<Map> list(context, session) {
		def root = session.getResource(ROOT);
		if (!root.exists()) {
			return [];
		}
		List<Map> cards = [];
		def children = root.list();
		while (children.hasNext()) {
			def r = children.next();
			if (!r.isCollection()) {
				continue;
			}
			Map card = read(context, session, r.path as String);
			if (card != null) {
				cards.add(card);
			}
		}
		cards.sort { Map a, Map b -> (a.label as String).compareToIgnoreCase(b.label as String) };
		return cards;
	}

	/**
	 * The design in the folder, as the session reads it: path, name, label,
	 * description, summary, fields and version (when the page was last
	 * changed). Null when the folder holds no design the session can read, or
	 * the descriptor cannot be parsed.
	 */
	static Map read(context, session, String path) {
		String folder = checkPath(path);
		def descriptor = session.getResource("${folder}/${DESCRIPTOR}".toString());
		def page = session.getResource("${folder}/${PAGE}".toString());
		if (!readableFile(descriptor) || !readableFile(page)) {
			return null;
		}
		Object parsed;
		try {
			parsed = context.getAttribute('YAML').parse(descriptor.getContent() ?: '');
		} catch (Throwable ignore) {
			return null;
		}
		if (!(parsed instanceof Map)) {
			return null;
		}
		Map declared = parsed as Map;
		String name = folder.substring(folder.lastIndexOf('/') + 1);
		List<Map> fields = [];
		Object entries = declared.fields;
		if (entries instanceof List) {
			for (Object entry : (entries as List)) {
				Map field = toField(entry);
				if (field != null && !fields.any { it.key == field.key } && fields.size() < MAX_FIELDS) {
					fields.add(field);
				}
			}
		}
		Date modified = null;
		try {
			modified = page.getLastModified();
		} catch (Throwable ignore) {
			// The page is shown without a version.
		}
		return [
			path: folder,
			name: name,
			label: text(declared.label) ?: name,
			description: text(declared.description),
			summary: text(declared.summary),
			fields: fields,
			version: modified?.time,
		];
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String s = value.toString().trim();
		return s ?: null;
	}

	private static Map toField(Object entry) {
		if (!(entry instanceof Map)) {
			return null;
		}
		Map map = entry as Map;
		String key = text(map.key);
		if (key == null || !NAME.matcher(key).matches()) {
			return null;
		}
		String type = (text(map.type) ?: 'STRING').toUpperCase(Locale.ROOT);
		if (!TYPES.contains(type)) {
			return null;
		}
		List<Map> choices = [];
		if (map.choices instanceof List) {
			(map.choices as List).each { Object item ->
				Map choice = toChoice(item);
				if (choice != null && !choices.any { it.value == choice.value }) {
					choices.add(choice);
				}
			};
		}
		return [
			key: key,
			label: text(map.label) ?: key,
			description: text(map.description),
			type: type,
			multiple: isTrue(map.multiple),
			required: isTrue(map.required),
			choices: choices,
		];
	}

	/** A choice: a value with a label and a color, or a bare value that is its own label. */
	private static Map toChoice(Object item) {
		if (item instanceof Map) {
			Map map = item as Map;
			String value = text(map.value);
			if (value == null) {
				return null;
			}
			return [value: value, label: text(map.label) ?: value, color: text(map.color)];
		}
		String value = text(item);
		return (value == null) ? null : [value: value, label: value, color: null];
	}

	private static boolean isTrue(Object value) {
		if (value instanceof Boolean) {
			return value as boolean;
		}
		return value != null && value.toString().trim().equalsIgnoreCase('true');
	}

	// --- messages ---------------------------------------------------------------

	/**
	 * The messages of the design in the folder for the locale, as the session
	 * reads them: those of the language without a region and of English fill
	 * in for keys the locale does not have, as in the browser. Empty when the
	 * design has none.
	 */
	static Map<String, String> messages(context, session, String path, String locale) {
		String folder = checkPath(path);
		def dir = session.getResource("${folder}/${MESSAGES}".toString());
		if (!dir.exists() || !dir.isCollection()) {
			return [:];
		}
		List files = [];
		def children = dir.list();
		while (children.hasNext()) {
			def r = children.next();
			if ((r.name as String).endsWith('.json') && readableFile(r)) {
				files.add(r);
			}
		}
		// Several files of one locale are merged, in the order of their names.
		files.sort { a, b -> (a.name as String) <=> (b.name as String) };
		Map<String, Map<String, String>> bundles = [:];
		files.each { r ->
			String name = r.name as String;
			String base = name.substring(0, name.length() - '.json'.length());
			String loc = base.substring(base.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
			Object parsed;
			try {
				parsed = context.getAttribute('JSON').parse(r.getContent() ?: '');
			} catch (Throwable ignore) {
				return;
			}
			if (!loc || !(parsed instanceof Map)) {
				return;
			}
			Map<String, String> bundle = bundles[loc] ?: [:];
			(parsed as Map).each { k, v ->
				if (k != null && v != null) {
					bundle[k as String] = v.toString();
				}
			};
			bundles[loc] = bundle;
		};
		Map<String, String> merged = [:];
		localeChain(locale).reverse().each { String loc ->
			if (bundles[loc]) {
				merged.putAll(bundles[loc]);
			}
		};
		return merged;
	}

	/** The locale, the language without a region, then English. */
	private static List<String> localeChain(String locale) {
		String exact = (locale ?: DEFAULT_LOCALE).trim().toLowerCase(Locale.ROOT).replace('_', '-') ?: DEFAULT_LOCALE;
		List<String> chain = [exact];
		int dash = exact.indexOf('-');
		if (dash > 0) {
			chain.add(exact.substring(0, dash));
		}
		chain.add(DEFAULT_LOCALE);
		return chain.unique(false);
	}

	/**
	 * The design with its texts in the locale: each label, description and the
	 * summary that is a key of the design's messages replaced by the message.
	 * The page's messages are left to the page.
	 */
	static Map localize(context, session, Map card, String locale) {
		Map<String, String> m = messages(context, session, card.path as String, locale);
		if (!m) {
			return card;
		}
		Closure<String> say = { String text -> (text != null && m.containsKey(text)) ? m[text] : text };
		return card + [
			label: say(card.label as String),
			description: say(card.description as String),
			summary: say(card.summary as String),
			fields: (card.fields as List<Map>).collect { Map field ->
				field + [
					label: say(field.label as String),
					description: say(field.description as String),
					choices: (field.choices as List<Map>).collect { Map choice -> choice + [label: say(choice.label as String)] },
				]
			},
		];
	}

	// --- values -----------------------------------------------------------------

	/**
	 * What ChatMessages.post takes for a card: the design's path and the
	 * values, checked against the design and written as JSON, with the text
	 * that stands in for a message without one, in the locale (English when
	 * none is given). `context` holds the JSON API.
	 */
	static Map toPost(context, session, Map design, Map fields, String locale) {
		Map card = localize(context, session, design, locale);
		Map values = normalize(card, (fields != null) ? fields : [:]);
		return [
			json: context.getAttribute('JSON').stringify([path: design.path, fields: values]),
			summary: summary(card, values),
		];
	}

	/**
	 * A card as ChatMessages read it ([json, bodyFromCard]) parsed back to
	 * [path, fields, bodyFromCard], or null when there is none or it cannot be
	 * read.
	 */
	static Map parse(context, Map stored) {
		if (stored == null || !stored.json) {
			return null;
		}
		Object parsed;
		try {
			parsed = context.getAttribute('JSON').parse(stored.json as String);
		} catch (Throwable ignore) {
			return null;
		}
		if (!(parsed instanceof Map) || !(parsed as Map).path) {
			return null;
		}
		Map card = parsed as Map;
		return [
			path: card.path as String,
			fields: (card.fields instanceof Map) ? (card.fields as Map) : [:],
			bodyFromCard: stored.bodyFromCard as boolean,
		];
	}

	/**
	 * The values of a card as they are stored: the value of each field of the
	 * design, in the field's type, and nothing else. A value that does not fit
	 * its field, a required field left empty, or a choice that is not offered
	 * is refused with a message naming the field.
	 */
	static Map normalize(Map card, Map fields) {
		Map values = [:];
		(card.fields as List<Map>).each { Map field ->
			String key = field.key as String;
			String label = field.label as String;
			Object raw = (fields != null) ? fields[key] : null;
			List items = (raw instanceof Collection) ? (raw as Collection).toList() : (raw instanceof Object[] ? (raw as Object[]).toList() : (raw == null ? [] : [raw]));
			items = items.findAll { it != null && !(it instanceof CharSequence && !(it as String).trim()) };
			if (items.size() > 1 && !field.multiple) {
				throw new IllegalArgumentException("${label} takes one value.".toString());
			}
			if (items.size() > MAX_VALUES) {
				throw new IllegalArgumentException("${label} takes up to ${MAX_VALUES} values.".toString());
			}
			List converted = items.collect { convert(field, it) };
			List<Map> choices = field.choices as List<Map>;
			if (choices) {
				converted.each { Object value ->
					if (!choices.any { it.value == String.valueOf(value) }) {
						throw new IllegalArgumentException("${label}: ${value} is not one of the choices.".toString());
					}
				};
			}
			if (!converted) {
				if (field.required) {
					throw new IllegalArgumentException("${label} is required.".toString());
				}
				return;
			}
			values[key] = field.multiple ? converted : converted[0];
		};
		return values;
	}

	/** One value in the type of its field. */
	private static Object convert(Map field, Object value) {
		String label = field.label as String;
		String type = field.type as String;
		String s = value.toString().trim();
		if (type == 'STRING') {
			if (s.length() > MAX_TEXT) {
				throw new IllegalArgumentException("${label} can be up to ${MAX_TEXT} characters.".toString());
			}
			return s;
		}
		try {
			switch (type) {
				case 'LONG':
					return (value instanceof Number) ? (value as Number).longValue() : Long.parseLong(s);
				case 'DOUBLE':
					return (value instanceof Number) ? (value as Number).doubleValue() : Double.parseDouble(s);
				case 'DECIMAL':
					// The digits as they were given, like a DECIMAL column.
					new BigDecimal(s);
					return s;
				case 'BOOLEAN':
					if (value instanceof Boolean) {
						return value;
					}
					if (s.equalsIgnoreCase('true') || s.equalsIgnoreCase('false')) {
						return Boolean.parseBoolean(s);
					}
					break;
				case 'DATE':
					return toInstant(value);
			}
		} catch (Throwable ignore) {
			// Said below.
		}
		throw new IllegalArgumentException("${label}: ${s} is not a ${type.toLowerCase(Locale.ROOT)}.".toString());
	}

	/** An ISO 8601 instant in UTC, from a date, epoch milliseconds, or an ISO 8601 text with or without an offset. */
	private static String toInstant(Object value) {
		if (value instanceof Date) {
			return Instant.ofEpochMilli((value as Date).time).toString();
		}
		if (value instanceof Number) {
			return Instant.ofEpochMilli((value as Number).longValue()).toString();
		}
		String s = value.toString().trim();
		try {
			return Instant.parse(s).toString();
		} catch (Throwable ignore) {
			// Not an instant with a Z.
		}
		try {
			return OffsetDateTime.parse(s).toInstant().toString();
		} catch (Throwable ignore) {
			// Not a time with an offset.
		}
		// A wall-clock time without a zone is taken as UTC.
		return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toString();
	}

	/**
	 * The text of a message that carries the card and was given none: the
	 * design's `summary` with each {{key}} replaced by the field's value, or
	 * the design's label when there is no summary.
	 */
	static String summary(Map card, Map values) {
		String template = card.summary as String;
		if (!template) {
			return card.label as String;
		}
		Map<String, Map> fields = [:];
		(card.fields as List<Map>).each { Map field -> fields[field.key as String] = field; };
		String text = template.replaceAll(PLACEHOLDER) { String all, String key ->
			Map field = fields[key];
			return (field == null) ? '' : java.util.regex.Matcher.quoteReplacement(display(field, values[key]));
		};
		return text.replaceAll(/[ \t]+/, ' ').trim() ?: (card.label as String);
	}

	/** A value as text: a choice by its label, a list joined with commas. */
	static String display(Map field, Object value) {
		if (value == null) {
			return '';
		}
		if (value instanceof Collection) {
			return (value as Collection).collect { display(field, it) }.findAll { it }.join(', ');
		}
		String s = value.toString();
		Map choice = (field.choices as List<Map>)?.find { it.value == s };
		return (choice != null) ? (choice.label as String) : s;
	}

}
