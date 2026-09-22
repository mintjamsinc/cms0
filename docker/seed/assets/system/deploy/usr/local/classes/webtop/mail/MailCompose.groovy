package webtop.mail;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

import org.mintjams.tools.mail.Message;
import org.mintjams.tools.mail.Transport;

/**
 * Drafts and sending.
 *
 * Drafts are the Webtop's own, never the server's Drafts folder:
 *
 *   /home/users/<user>/mail/drafts/<draft>.json   the draft
 *   /home/users/<user>/mail/drafts/<draft>/<key>  files uploaded for it
 *
 * A draft is sent through the account's outgoing server. The copy of what was
 * sent is kept in the Webtop only (it is not appended to the server's Sent
 * folder), under a folder key no IMAP folder can have:
 *
 *   <messages>/<account>/local/sent/<yyyy>/<MM>/<id>.eml
 */
class MailCompose {

	static final String DRAFT_TYPE = 'application/vnd.mintjams.webtop.mail.draft+json';
	static final List<String> MODES = ['new', 'reply', 'replyAll', 'forward'];
	static final List<String> FORMATS = ['text', 'html'];
	static final int MAX_ATTACHMENTS = 50;
	static final int MAX_RECIPIENTS = 100;
	static final int MAX_REMEMBERED = 500;
	static final int MAX_SAVED = 1000;
	static final long MAX_TOTAL_SIZE = 25L * 1024 * 1024;

	def context;
	def session;
	def JSON;
	def log;
	MailApi api;

	protected MailCompose(context, MailApi api) {
		this.context = context;
		this.session = context.session;
		this.JSON = context.getAttribute('JSON');
		this.log = context.getAttribute('log');
		this.api = api;
	}

	static MailCompose create(context, MailApi api) {
		return new MailCompose(context, api);
	}

	String getDraftsRoot() {
		return "${api.accounts.root}/drafts".toString();
	}

	String draftPath(String id) {
		return "${draftsRoot}/${MailAccounts.checkId(id)}.json".toString();
	}

	String filesPath(String id) {
		return "${draftsRoot}/${MailAccounts.checkId(id)}".toString();
	}

	// --- drafts -------------------------------------------------------------------

	/** Every draft, the most recently changed first. */
	List<Map> listDrafts() {
		def folder = session.getResource(draftsRoot);
		if (!folder.exists()) {
			return [];
		}
		List<Map> l = [];
		folder.list().each { r ->
			if (!r.isCollection() && r.name.endsWith('.json')) {
				try {
					l.add(toDraft(JSON.parse(r.getContent()) as Map));
				} catch (Throwable ex) {
					log?.warn("Unreadable mail draft ${r.path}: ${ex.message}".toString());
				}
			}
		};
		l.sort { a, b -> (b.modified as Date) <=> (a.modified as Date) };
		return l;
	}

	Map getDraft(String id) {
		Map d = readDraft(id);
		return (d == null) ? null : toDraft(d);
	}

	private Map readDraft(String id) {
		def r = session.getResource(draftPath(id));
		if (!r.exists()) {
			return null;
		}
		return JSON.parse(r.getContent()) as Map;
	}

	private Map toDraft(Map d) {
		return d + [
			created: new Date(d.created as long),
			modified: new Date(d.modified as long),
			attachments: (d.attachments ?: []).collect { Map a -> a + [forwarded: a.messageId != null] },
		];
	}

	/**
	 * Creates or updates a draft. The attachments of the input replace those of
	 * the draft: an entry names either a file uploaded into the draft's folder
	 * (key) or an attachment of a stored message (messageId and index), which is
	 * read from that message when the draft is sent.
	 */
	Map saveDraft(Map input) {
		Map existing = input.id ? readDraft(input.id as String) : null;
		if (input.id && existing == null) {
			throw new IllegalArgumentException("No such draft: ${input.id}".toString());
		}
		long now = new Date().time;
		Map d = (existing != null) ? new LinkedHashMap(existing) : [
			id: UUID.randomUUID().toString().replace('-', ''),
			created: now,
		];

		String accountId = (input.accountId ?: d.accountId) as String;
		if (!accountId || api.accounts.get(accountId) == null) {
			throw new IllegalArgumentException("No such mail account: ${accountId}".toString());
		}
		d.accountId = accountId;
		['to', 'cc', 'bcc', 'subject', 'text', 'html'].each { name ->
			if (input.containsKey(name)) {
				d[name] = (input[name] ?: '').toString();
			}
		};
		if (input.format != null) {
			if (!FORMATS.contains(input.format)) {
				throw new IllegalArgumentException("Unknown format: ${input.format}".toString());
			}
			d.format = input.format;
		}
		d.format = d.format ?: 'text';
		if (existing == null) {
			String mode = (input.mode ?: 'new') as String;
			if (!MODES.contains(mode)) {
				throw new IllegalArgumentException("Unknown mode: ${mode}".toString());
			}
			d.mode = mode;
			if (input.originalId) {
				if (api.messageResource(input.originalId as String) == null) {
					throw new IllegalArgumentException("No such message: ${input.originalId}".toString());
				}
				d.originalId = input.originalId;
			}
			d.inReplyTo = (input.inReplyTo ?: []) as List;
			d.references = (input.references ?: []) as List;
		}
		if (input.attachments != null) {
			d.attachments = normalizeAttachments(d.id as String, input.attachments as List<Map>);
		}
		d.attachments = d.attachments ?: [];
		d.modified = now;

		def file = session.getResource(draftPath(d.id as String));
		if (!file.exists()) {
			file.getParent().getOrCreateFolder();
			file.createFile();
		}
		file.write(JSON.stringify(d));
		file.setContentType(DRAFT_TYPE);
		file.setContentEncoding('UTF-8');
		// Uploads go straight into this folder, so it exists from the start.
		session.getResource(filesPath(d.id as String)).getOrCreateFolder();
		session.commit();
		return toDraft(d);
	}

	private List<Map> normalizeAttachments(String id, List<Map> input) {
		if (input.size() > MAX_ATTACHMENTS) {
			throw new IllegalArgumentException("A message can have at most ${MAX_ATTACHMENTS} attachments.".toString());
		}
		return input.collect { Map a ->
			String name = (a.name ?: '').toString().replaceAll(/[\\\/\r\n]/, '_').trim() ?: 'attachment';
			String mimeType = (a.mimeType ?: 'application/octet-stream').toString();
			if (a.messageId) {
				def r = api.messageResource(a.messageId as String);
				if (r == null) {
					throw new IllegalArgumentException("No such message: ${a.messageId}".toString());
				}
				return [
					key: (a.key ?: UUID.randomUUID().toString().replace('-', '')) as String,
					name: name,
					mimeType: mimeType,
					size: a.size,
					messageId: a.messageId,
					index: a.index as int,
				];
			}
			String key = MailAccounts.checkId(a.key as String);
			def r = session.getResource("${filesPath(id)}/${key}".toString());
			if (!r.exists()) {
				throw new IllegalArgumentException("The attachment ${name} has not been uploaded.".toString());
			}
			return [key: key, name: name, mimeType: mimeType, size: r.contentLength];
		};
	}

	boolean removeDraft(String id) {
		boolean found = false;
		[draftPath(id), filesPath(id)].each { path ->
			def r = session.getResource(path);
			if (r.exists()) {
				r.remove();
				found = true;
			}
		};
		session.commit();
		return found;
	}

	// --- sending ------------------------------------------------------------------

	/**
	 * Sends a draft through its account's outgoing server, keeps a copy in the
	 * Webtop, marks the original of a reply as answered and removes the draft.
	 * Returns the id of the copy.
	 */
	String send(String id) {
		Map d = readDraft(id);
		if (d == null) {
			throw new IllegalArgumentException("No such draft: ${id}".toString());
		}
		Map account = api.accounts.get(d.accountId as String);
		if (account == null) {
			throw new IllegalArgumentException("No such mail account: ${d.accountId}".toString());
		}
		if (!account.outgoing?.host) {
			throw new IllegalArgumentException('The account has no outgoing server.');
		}

		InternetAddress[] to = parseAddresses(d.to as String, 'To');
		InternetAddress[] cc = parseAddresses(d.cc as String, 'Cc');
		InternetAddress[] bcc = parseAddresses(d.bcc as String, 'Bcc');
		int count = to.length + cc.length + bcc.length;
		if (count == 0) {
			throw new IllegalArgumentException('Enter at least one recipient.');
		}
		if (count > MAX_RECIPIENTS) {
			throw new IllegalArgumentException("A message can have at most ${MAX_RECIPIENTS} recipients.".toString());
		}

		List<Closeable> opened = [];
		File raw = null;
		try {
			Message m = Message.create();
			opened.add(m);
			m.setDraft(false);
			String address = account.address as String;
			m.setFrom(new InternetAddress(address, (account.personal ?: null) as String, 'UTF-8'));
			m.setTo(to);
			m.setCc(cc);
			m.setBcc(bcc);
			m.setSubject((d.subject ?: '') as String);
			Date now = new Date();
			m.setSentDate(now);
			m.setMessageID("<${UUID.randomUUID().toString()}@${address.substring(address.lastIndexOf('@') + 1)}>".toString());
			m.setInReplyTo((d.inReplyTo ?: []) as String[]);
			m.setReferences((d.references ?: []) as String[]);

			String text = (d.text ?: '') as String;
			m.setContent(text.endsWith('\n') ? text : text + '\n', 'text/plain');
			if (d.format == 'html' && d.html) {
				m.setContent(htmlDocument(d.html as String), 'text/html');
			}

			long total = 0;
			Set<String> names = [] as Set;
			Map<String, Message> originals = [:];
			for (Map a : (d.attachments ?: []) as List<Map>) {
				InputStream in;
				if (a.messageId) {
					Message original = originals[a.messageId as String];
					if (original == null) {
						def r = api.messageResource(a.messageId as String);
						if (r == null) {
							throw new IllegalArgumentException("The message of the attachment ${a.name} no longer exists.".toString());
						}
						original = Message.from(r.getContentAsStream());
						opened.add(original);
						originals[a.messageId as String] = original;
					}
					def parts = original.attachments;
					int index = a.index as int;
					if (index < 0 || index >= parts.length) {
						throw new IllegalArgumentException("The attachment ${a.name} no longer exists.".toString());
					}
					in = parts[index].inputStream;
				} else {
					def r = session.getResource("${filesPath(id)}/${a.key}".toString());
					if (!r.exists()) {
						throw new IllegalArgumentException("The attachment ${a.name} no longer exists.".toString());
					}
					total += r.contentLength;
					in = r.getContentAsStream();
				}
				// Message.addAttachment replaces a file of the same name.
				m.addAttachment(in, uniqueName(a.name as String, names), a.mimeType as String);
			}
			if (total > MAX_TOTAL_SIZE) {
				throw new IllegalArgumentException("The attachments are larger than ${MAX_TOTAL_SIZE.intdiv(1024 * 1024)} MB.".toString());
			}

			raw = File.createTempFile('webtop-mail-', '.eml');
			raw.withOutputStream { out -> m.writeTo(out); };

			Map outgoing = account.outgoing as Map;
			boolean same = (outgoing.sameAuthentication == null) ? true : !!outgoing.sameAuthentication;
			String username = same ? ((account.incoming as Map)?.username ?: address) : outgoing.username;
			String password = api.accounts.password(account.id as String, same ? 'incoming' : 'outgoing');
			try {
				Transport transport = Transport.Builder.create(MailAccounts.outgoingURI(account)).
						setUsername(username as String).
						setPassword(password).
						build();
				try {
					transport.send(m);
				} finally {
					transport.close();
				}
			} catch (Throwable ex) {
				throw new IllegalStateException("SMTP ${MailAccounts.describeServer(outgoing)}: ${MailSync.describeError(ex)}".toString(), ex);
			}

			String copy = null;
			try {
				copy = storeSent(account.id as String, raw, now);
			} catch (Throwable ex) {
				// The message is out; losing the copy must not make it look unsent.
				log?.warn("The copy of a sent message could not be stored: ${ex.message}".toString());
				session.rollback();
			}
			if (d.originalId && d.mode in ['reply', 'replyAll']) {
				try {
					api.markAnswered(d.originalId as String);
				} catch (Throwable ex) {
					log?.warn("The original message could not be marked answered: ${ex.message}".toString());
				}
			}
			rememberRecipients(to + cc + bcc);
			removeDraft(id);
			return copy;
		} finally {
			opened.reverse().each { c ->
				try {
					c.close();
				} catch (Throwable ignore) {}
			};
			raw?.delete();
		}
	}

	private static InternetAddress[] parseAddresses(String value, String field) {
		if (!value?.trim()) {
			return new InternetAddress[0];
		}
		// Semicolons are a common separator in address books.
		String s = value.replaceAll(/;(?=(?:[^"]*"[^"]*")*[^"]*$)/, ',');
		InternetAddress[] l;
		try {
			l = InternetAddress.parse(s, false);
		} catch (AddressException ex) {
			throw new IllegalArgumentException("${field}: ${ex.message}".toString());
		}
		l.each { a ->
			if (!(a.address ==~ /[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+/)) {
				throw new IllegalArgumentException("${field}: not a mail address: ${a.address}".toString());
			}
			if (a.personal) {
				// Encoded as UTF-8 words when not ASCII.
				a.setPersonal(a.personal, 'UTF-8');
			}
		};
		return l;
	}

	private static String uniqueName(String name, Set<String> names) {
		String candidate = name;
		int dot = name.lastIndexOf('.');
		String base = (dot > 0) ? name.substring(0, dot) : name;
		String ext = (dot > 0) ? name.substring(dot) : '';
		int n = 2;
		while (names.contains(candidate.toLowerCase())) {
			candidate = "${base} (${n++})${ext}".toString();
		}
		names.add(candidate.toLowerCase());
		return candidate;
	}

	private static String htmlDocument(String body) {
		return '<!DOCTYPE html><html><head><meta charset="UTF-8"></head><body>' + body + '</body></html>';
	}

	/** Stores the copy of a sent message; returns its id. */
	private String storeSent(String accountId, File raw, Date date) {
		MailStorage storage = MailStorage.create(context, api.accounts.userId, accountId);
		String name = UUID.randomUUID().toString().replace('-', '');
		String month = new java.text.SimpleDateFormat('yyyy/MM').format(date);
		String path = "${storage.root}/local/sent/${month}/${name}.eml".toString();
		storage.storeLocal(path, 'sent', raw, date);
		session.commit();
		return path;
	}

	// --- recipients -----------------------------------------------------------------

	String getRecipientsPath() {
		return "${api.accounts.root}/recipients.json".toString();
	}

	/** Addresses mail was sent to, the most recent first, for suggestions. */
	List<Map> listRecipients() {
		def file = session.getResource(recipientsPath);
		if (!file.exists()) {
			return [];
		}
		try {
			return ((JSON.parse(file.getContent()) as Map).recipients ?: []) as List<Map>;
		} catch (Throwable ex) {
			return [];
		}
	}

	private void rememberRecipients(InternetAddress[] addresses) {
		try {
			List<Map> known = listRecipients();
			addresses.reverse().each { a ->
				String address = a.address.toLowerCase();
				known.removeAll { (it.address as String)?.toLowerCase() == address };
				known.add(0, [name: a.personal ?: null, address: a.address]);
			};
			if (known.size() > MAX_REMEMBERED) {
				known = known.subList(0, MAX_REMEMBERED);
			}
			writeJson(recipientsPath, [recipients: known]);
			session.commit();
		} catch (Throwable ex) {
			log?.warn("Mail recipients could not be remembered: ${ex.message}".toString());
			session.rollback();
		}
	}

	// --- saved addresses --------------------------------------------------------------

	String getSavedAddressesPath() {
		return "${api.accounts.root}/addresses.json".toString();
	}

	/** Addresses the user saved from mail they read, the most recent first. */
	List<Map> listSavedAddresses() {
		def file = session.getResource(savedAddressesPath);
		if (!file.exists()) {
			return [];
		}
		try {
			return ((JSON.parse(file.getContent()) as Map).addresses ?: []) as List<Map>;
		} catch (Throwable ex) {
			return [];
		}
	}

	/** Saves an address for suggestions; saving it again updates its name. */
	boolean saveAddress(String name, String address) {
		address = address?.trim();
		if (!(address ==~ /[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+/)) {
			throw new IllegalArgumentException("Not a mail address: ${address}".toString());
		}
		List<Map> saved = listSavedAddresses();
		String key = address.toLowerCase();
		saved.removeAll { (it.address as String)?.toLowerCase() == key };
		if (saved.size() >= MAX_SAVED) {
			throw new IllegalStateException("No more than ${MAX_SAVED} addresses can be saved.".toString());
		}
		saved.add(0, [name: name?.trim() ?: null, address: address]);
		writeJson(savedAddressesPath, [addresses: saved]);
		session.commit();
		return true;
	}

	/**
	 * Removes an address from the suggestions: from the saved addresses and from
	 * those mail was sent to. Returns false when it was in neither.
	 */
	boolean forgetAddress(String address) {
		String key = address?.trim()?.toLowerCase();
		if (!key) {
			return false;
		}
		boolean removed = false;
		List<Map> saved = listSavedAddresses();
		if (saved.removeAll { (it.address as String)?.toLowerCase() == key }) {
			writeJson(savedAddressesPath, [addresses: saved]);
			removed = true;
		}
		List<Map> known = listRecipients();
		if (known.removeAll { (it.address as String)?.toLowerCase() == key }) {
			writeJson(recipientsPath, [recipients: known]);
			removed = true;
		}
		if (removed) {
			session.commit();
		}
		return removed;
	}

	private void writeJson(String path, Map value) {
		def file = session.getResource(path);
		if (!file.exists()) {
			file.getParent().getOrCreateFolder();
			file.createFile();
		}
		file.write(JSON.stringify(value));
		file.setContentType('application/json');
		file.setContentEncoding('UTF-8');
	}

}
