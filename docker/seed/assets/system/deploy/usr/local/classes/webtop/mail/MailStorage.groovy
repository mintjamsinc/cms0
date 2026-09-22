package webtop.mail;

import java.security.MessageDigest;

import javax.mail.internet.InternetAddress;

import org.mintjams.tools.mail.Message;

/**
 * The downloaded messages of one account.
 *
 * Each message is stored as it came from the server, one message/rfc822 file:
 *
 *   <messages>/<folder key>/<UIDVALIDITY>/<UID / 1000>/<UID>.eml
 *
 * The path follows from the folder and UID alone, so synchronization finds a
 * message without a query. What the list shows (subject, sender, dates, flags)
 * is copied into properties of the file, which the search index makes
 * queryable; the full text comes from the index reading the .eml itself.
 */
class MailStorage {

	static final String MESSAGE_TYPE = 'message/rfc822';
	static final int PREVIEW_LENGTH = 200;

	def context;
	def session;
	def log;
	String root;
	String accountId;

	protected MailStorage(context, String userId, String accountId) {
		this.context = context;
		this.session = context.session;
		this.log = context.getAttribute('log');
		this.accountId = MailAccounts.checkId(accountId);
		this.root = "${MailAccounts.homeOf(userId)}/messages/${accountId}".toString();
	}

	static MailStorage create(context, String userId, String accountId) {
		return new MailStorage(context, userId, accountId);
	}

	/**
	 * A node name for an IMAP folder: readable where possible, unique always.
	 * IMAP names may contain characters JCR names cannot ("/", ":", "[" ...).
	 */
	static String folderKey(String fullName) {
		if (fullName.equalsIgnoreCase('INBOX')) {
			return 'INBOX';
		}
		String readable = fullName.replaceAll(/[^A-Za-z0-9_-]/, '_');
		if (readable.length() > 40) {
			readable = readable.substring(0, 40);
		}
		byte[] digest = MessageDigest.getInstance('SHA-1').digest(fullName.getBytes('UTF-8'));
		return "${readable}-${digest.encodeHex().toString().substring(0, 8)}".toString();
	}

	String folderPath(String key) {
		return "${root}/${key}".toString();
	}

	String messagePath(String key, long uidValidity, long uid) {
		return "${root}/${key}/${uidValidity}/${uid.intdiv(1000)}/${uid}.eml".toString();
	}

	def getMessage(String key, long uidValidity, long uid) {
		return session.getResource(messagePath(key, uidValidity, uid));
	}

	boolean exists(String key, long uidValidity, long uid) {
		return getMessage(key, uidValidity, uid).exists();
	}

	/**
	 * Stores a downloaded message. `raw` is the message file as sent by the
	 * server, `summary` its MailFolder.MessageSummary. A message that cannot be
	 * parsed is stored all the same, with the error recorded.
	 */
	void store(Map folder, long uidValidity, summary, File raw) {
		def file = getMessage(folder.key as String, uidValidity, summary.UID as long);
		if (!file.exists()) {
			file.getParent().getOrCreateFolder();
			file.createFile();
		}
		file.write(new FileInputStream(raw));
		file.setContentType(MESSAGE_TYPE);

		file.setProperty('mail:account', accountId);
		file.setProperty('mail:folder', folder.fullName as String);
		file.setProperty('mail:role', (folder.role ?: 'other') as String);
		file.setProperty('mail:uid', summary.UID as long);
		file.setProperty('mail:uidValidity', uidValidity);
		file.setProperty('mail:size', (summary.size >= 0 ? summary.size : raw.length()) as long);
		setFlags(file, summary);
		file.setProperty('mail:trashed', false);
		file.setProperty('mail:removed', false);
		file.setProperty('mail:hasPending', false);

		Map meta;
		try {
			meta = describe(raw, summary.internalDate as Date);
		} catch (Throwable ex) {
			log?.warn("Mail message could not be parsed: ${file.path}: ${ex.message}".toString());
			meta = [
				subject: '',
				receivedDate: (summary.internalDate ?: new Date()) as Date,
				error: (ex.message ?: ex.class.name).toString(),
			];
		}
		meta.each { name, value ->
			setMeta(file, name as String, value);
		};
	}

	/**
	 * Stores a message that exists in the Webtop only, such as the copy of a sent
	 * message. It has no UID and is never synchronized (mail:local).
	 */
	void storeLocal(String path, String role, File raw, Date date) {
		def file = session.getResource(path);
		file.getParent().getOrCreateFolder();
		file.createFile();
		file.write(new FileInputStream(raw));
		file.setContentType(MESSAGE_TYPE);
		file.setProperty('mail:account', accountId);
		file.setProperty('mail:folder', '');
		file.setProperty('mail:role', role);
		file.setProperty('mail:local', true);
		file.setProperty('mail:size', raw.length());
		file.setProperty('mail:seen', true);
		file.setProperty('mail:flagged', false);
		file.setProperty('mail:answered', false);
		file.setProperty('mail:draft', false);
		file.setProperty('mail:deleted', false);
		file.setProperty('mail:trashed', false);
		file.setProperty('mail:removed', false);
		file.setProperty('mail:hasPending', false);
		describe(raw, date).each { name, value ->
			setMeta(file, name as String, value);
		};
	}

	private static void setMeta(file, String name, value) {
		String key = "mail:${name}".toString();
		if (value instanceof Collection || value instanceof Object[]) {
			setStrings(file, key, value.collect { it.toString() });
		} else if (value == null) {
			remove(file, key);
		} else if (value instanceof Date) {
			file.setProperty(key, value as Date);
		} else if (value instanceof Boolean) {
			file.setProperty(key, value as boolean);
		} else {
			file.setProperty(key, value.toString());
		}
	}

	/** A multi-valued property; the repository refuses an empty one, so none is set. */
	static void setStrings(file, String key, List<String> values) {
		if (values) {
			file.setProperty(key, values as String[]);
		} else {
			remove(file, key);
		}
	}

	private static void remove(file, String key) {
		if (file.hasProperty(key)) {
			file.removeProperty(key);
		}
	}

	static void setFlags(file, summary) {
		file.setProperty('mail:seen', summary.seen as boolean);
		file.setProperty('mail:flagged', summary.flagged as boolean);
		file.setProperty('mail:answered', summary.answered as boolean);
		file.setProperty('mail:draft', summary.draft as boolean);
		file.setProperty('mail:deleted', summary.deleted as boolean);
		setStrings(file, 'mail:keywords', (summary.keywords ?: []) as List<String>);
	}

	/**
	 * Stores a downloaded message with only what the list needs, recording why the
	 * full store failed. Used after store() failed, so that a message that could be
	 * downloaded is never dropped.
	 */
	void storeMinimal(Map folder, long uidValidity, summary, File raw, Throwable cause) {
		def file = getMessage(folder.key as String, uidValidity, summary.UID as long);
		if (!file.exists()) {
			file.getParent().getOrCreateFolder();
			file.createFile();
		}
		file.write(new FileInputStream(raw));
		file.setContentType(MESSAGE_TYPE);
		file.setProperty('mail:account', accountId);
		file.setProperty('mail:folder', folder.fullName as String);
		file.setProperty('mail:role', (folder.role ?: 'other') as String);
		file.setProperty('mail:uid', summary.UID as long);
		file.setProperty('mail:uidValidity', uidValidity);
		file.setProperty('mail:size', raw.length());
		file.setProperty('mail:seen', summary.seen as boolean);
		file.setProperty('mail:flagged', summary.flagged as boolean);
		file.setProperty('mail:answered', summary.answered as boolean);
		file.setProperty('mail:draft', summary.draft as boolean);
		file.setProperty('mail:deleted', summary.deleted as boolean);
		file.setProperty('mail:trashed', false);
		file.setProperty('mail:removed', false);
		file.setProperty('mail:hasPending', false);
		file.setProperty('mail:hasAttachments', false);
		file.setProperty('mail:subject', '');
		Date received = (summary.internalDate ?: new Date()) as Date;
		file.setProperty('mail:receivedDate', received);
		file.setProperty('mail:sentDate', received);
		file.setProperty('mail:error', (cause.message ?: cause.class.name).toString());
	}

	/**
	 * The list metadata of a message. Every date has a fallback, because Date and
	 * Received headers are often missing or wrong: the received date is the
	 * server's arrival date (INTERNALDATE), which the server always sets.
	 */
	static Map describe(File raw, Date internalDate) {
		Message m = Message.from(new FileInputStream(raw));
		try {
			if (internalDate != null) {
				m.setReceivedDate(internalDate);
			}
			Date received = m.receivedDate ?: internalDate ?: new Date();
			Date sent = m.sentDate ?: received;

			InternetAddress[] from = m.from as InternetAddress[];
			InternetAddress[] to = m.to as InternetAddress[];
			InternetAddress[] cc = m.cc as InternetAddress[];
			return [
				subject: (m.subject ?: '').toString(),
				messageId: m.messageID,
				inReplyTo: (m.inReplyTo ?: [] as String[]).join(' ') ?: null,
				references: (m.references ?: [] as String[]) as List,
				from: from ? display(from[0]) : '',
				fromAddress: from ? (from[0].address ?: '').toLowerCase() : '',
				to: to.collect { display(it) }.join(', '),
				toAddresses: to.collect { (it.address ?: '').toLowerCase() },
				cc: cc.collect { display(it) }.join(', '),
				sentDate: sent,
				receivedDate: received,
				hasAttachments: m.hasAttachments(),
				preview: previewOf(m),
				error: m.hasError() ? (m.error.message ?: m.error.class.name).toString() : null,
			];
		} finally {
			m.close();
		}
	}

	static String display(InternetAddress a) {
		if (a.personal) {
			return "${a.personal} <${a.address}>".toString();
		}
		return (a.address ?: '').toString();
	}

	/** The preview of a message: the start of its text, or of its HTML as text. */
	static String previewOf(Message m) {
		String text = m.getContent('text/plain');
		if (!text?.trim() && m.getContent('text/html') != null) {
			text = htmlToText(m.getContent('text/html'));
		}
		return preview(text);
	}

	/**
	 * The start of the text as one line. Quoted replies and decoration are left
	 * out so the preview shows what the message says: newsletters often open
	 * with ruled lines ("――――", "━━━━", "====", "ーーーー"), and headings
	 * are framed with runs of symbols ("■■■■").
	 */
	static String preview(String text) {
		if (!text) {
			return '';
		}
		List<String> lines = [];
		for (String line : text.readLines()) {
			String s = line.trim();
			if (s.startsWith('>') || isDecoration(s)) {
				continue;
			}
			// Runs of three or more of the same symbol, e.g. "■■■■ News ■■■■".
			s = s.replaceAll(/([^\p{L}\p{N}\s])\1{2,}/, ' ');
			lines.add(s);
			if (lines.sum(0) { it.length() } > PREVIEW_LENGTH) {
				break;
			}
		}
		String s = lines.join(' ').replaceAll(/\s+/, ' ').trim();
		return (s.length() > PREVIEW_LENGTH) ? s.substring(0, PREVIEW_LENGTH) : s;
	}

	/**
	 * A line with nothing to read: no letter or digit, apart from the prolonged
	 * sound mark and dashes used to draw rules in Japanese text.
	 */
	static boolean isDecoration(String line) {
		return !(line.replaceAll(/[ー－ｰ〜～]/, '') =~ /[\p{L}\p{N}]/);
	}

	/**
	 * Recomputes the preview of a stored message. Returns true when it changed.
	 */
	static boolean refreshPreview(file) {
		Message m = Message.from(file.getContentAsStream());
		try {
			String preview = previewOf(m);
			String current = file.hasProperty('mail:preview') ? file.getProperty('mail:preview').getString() : null;
			if (preview == current) {
				return false;
			}
			file.setProperty('mail:preview', preview);
			return true;
		} finally {
			m.close();
		}
	}

	static String htmlToText(String html) {
		String s = html.replaceAll(/(?is)<(script|style|head)[^>]*>.*?<\/\1>/, ' ');
		s = s.replaceAll(/(?i)<br\s*\/?>|<\/p>|<\/div>|<\/li>|<\/tr>/, '\n');
		s = s.replaceAll(/<[^>]+>/, ' ');
		return decodeEntities(s);
	}

	private static final Map<String, String> ENTITIES = [
		nbsp: ' ', lt: '<', gt: '>', quot: '"', apos: "'", amp: '&',
		mdash: '—', ndash: '–', hellip: '…', bull: '•', middot: '·', copy: '©', reg: '®', trade: '™',
		yen: '¥', laquo: '«', raquo: '»', lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”',
	];

	/** Decodes numeric character references and the usual named entities. */
	static String decodeEntities(String s) {
		return s.replaceAll(/&(#[xX][0-9A-Fa-f]{1,6}|#[0-9]{1,7}|[A-Za-z]{2,8});/) { all, ref ->
			String r = ref as String;
			try {
				if (r.startsWith('#x') || r.startsWith('#X')) {
					return new String(Character.toChars(Integer.parseInt(r.substring(2), 16)));
				}
				if (r.startsWith('#')) {
					return new String(Character.toChars(Integer.parseInt(r.substring(1))));
				}
			} catch (Throwable ignore) {
				return all;
			}
			return ENTITIES[r] ?: all;
		};
	}

	/** Removes every message of a folder, e.g. after its UIDVALIDITY changed. */
	void discardFolder(String key) {
		def folder = session.getResource(folderPath(key));
		if (folder.exists()) {
			// Locked messages stay; they keep their old UIDVALIDITY.
			removeUnlocked(folder);
			session.commit();
		}
	}

	/** Removes the tree except locked messages. Returns true when nothing is left. */
	private static boolean removeUnlocked(r) {
		if (!r.isCollection()) {
			if (MailLabels.isLocked(r)) {
				return false;
			}
			r.remove();
			return true;
		}
		boolean empty = true;
		// Collected first: the children are removed while going through them.
		List children = [];
		r.list().each { children.add(it); };
		for (def child : children) {
			if (!removeUnlocked(child)) {
				empty = false;
			}
		}
		if (empty) {
			r.remove();
		}
		return empty;
	}

}
