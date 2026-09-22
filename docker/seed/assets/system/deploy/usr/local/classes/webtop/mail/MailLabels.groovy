package webtop.mail;

/**
 * Colors, tags and locks of messages. They are the Webtop's own and are not
 * written to the mail server.
 *
 * A lock guards a message against deletion by mistake: it cannot be moved to
 * the trash, and it is kept when the Webtop discards its copy of a folder. The
 * owner locks and unlocks messages freely. Nothing is deleted on the mail
 * server in any case.
 */
class MailLabels {

	// The Webtop's shared swatch palette (webtop/src/webtop/lib/color-palette.ts).
	static final List<String> COLORS = ['tomato', 'tangerine', 'banana', 'basil', 'sage', 'peacock', 'blueberry', 'lavender', 'grape', 'flamingo', 'graphite'];
	static final int MAX_TAGS = 20;
	static final int MAX_TAG_LENGTH = 50;

	def context;
	def session;
	def JSON;
	def log;
	MailApi api;

	protected MailLabels(context, MailApi api) {
		this.context = context;
		this.session = context.session;
		this.JSON = context.getAttribute('JSON');
		this.log = context.getAttribute('log');
		this.api = api;
	}

	static MailLabels create(context, MailApi api) {
		return new MailLabels(context, api);
	}

	static boolean isLocked(r) {
		return MailSync.flag(r, 'mail:locked');
	}

	// --- tags -------------------------------------------------------------------

	String getLabelsPath() {
		return "${api.accounts.root}/labels.json".toString();
	}

	/** Every tag used so far, for suggestions and the filter list. */
	List<String> listTags() {
		def file = session.getResource(labelsPath);
		if (!file.exists()) {
			return [];
		}
		try {
			return ((JSON.parse(file.getContent()) as Map).tags ?: []) as List<String>;
		} catch (Throwable ex) {
			return [];
		}
	}

	private void rememberTags(Collection<String> tags) {
		List<String> known = listTags();
		List<String> added = tags.findAll { !known.contains(it) } as List<String>;
		if (!added) {
			return;
		}
		known.addAll(added);
		known.sort { a, b -> a.compareToIgnoreCase(b) };
		def file = session.getResource(labelsPath);
		if (!file.exists()) {
			file.getParent().getOrCreateFolder();
			file.createFile();
		}
		file.write(JSON.stringify([tags: known]));
		file.setContentType('application/json');
		file.setContentEncoding('UTF-8');
	}

	static String normalizeTag(value) {
		String tag = (value ?: '').toString().replaceAll(/\s+/, ' ').trim();
		if (!tag) {
			return null;
		}
		if (tag.length() > MAX_TAG_LENGTH) {
			throw new IllegalArgumentException("A tag can have at most ${MAX_TAG_LENGTH} characters.".toString());
		}
		return tag;
	}

	/**
	 * Sets the color (null leaves it, "" clears it) and adds and removes tags.
	 * Returns the number of messages changed.
	 */
	int setLabels(List<String> ids, String color, List<String> addTags, List<String> removeTags) {
		if (color != null && color != '' && !COLORS.contains(color)) {
			throw new IllegalArgumentException("Unknown color: ${color}".toString());
		}
		List<String> adds = (addTags ?: []).collect { normalizeTag(it) }.findAll { it } as List<String>;
		List<String> removes = (removeTags ?: []).collect { normalizeTag(it) }.findAll { it } as List<String>;

		int count = 0;
		ids.each { id ->
			def r = api.messageResource(id);
			if (r == null) {
				return;
			}
			boolean changed = false;
			if (color != null) {
				String current = r.hasProperty('mail:color') ? r.getProperty('mail:color').getString() : '';
				if (current != color) {
					if (color) {
						r.setProperty('mail:color', color);
					} else {
						r.removeProperty('mail:color');
					}
					changed = true;
				}
			}
			if (adds || removes) {
				List<String> tags = r.hasProperty('mail:tags') ? (r.getProperty('mail:tags').getStringArray() as List<String>) : [];
				List<String> updated = new ArrayList<>(tags);
				updated.removeAll(removes);
				adds.each { tag ->
					if (!updated.contains(tag)) {
						updated.add(tag);
					}
				};
				if (updated.size() > MAX_TAGS) {
					throw new IllegalArgumentException("A message can have at most ${MAX_TAGS} tags.".toString());
				}
				if (updated != tags) {
					MailStorage.setStrings(r, 'mail:tags', updated);
					changed = true;
				}
			}
			if (changed) {
				count++;
			}
		};
		rememberTags(adds);
		session.commit();
		return count;
	}

	// --- locks ------------------------------------------------------------------

	/**
	 * Locks or unlocks messages. The owner can do both at any time. Returns the
	 * number of messages changed.
	 */
	int setLocked(List<String> ids, boolean locked) {
		int count = 0;
		ids.each { id ->
			def r = api.messageResource(id);
			if (r != null && isLocked(r) != locked) {
				r.setProperty('mail:locked', locked);
				count++;
			}
		};
		session.commit();
		return count;
	}

}
