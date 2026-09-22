package webtop.mail;

import java.util.Base64;

import javax.mail.internet.InternetAddress;

import org.mintjams.tools.mail.MailStore;
import org.mintjams.tools.mail.Message;
import org.mintjams.tools.mail.Transport;

/**
 * What the GraphQL resolvers of the Mail app do, run in the caller's context.
 * A message is addressed by the path of its .eml file, which must lie in the
 * caller's own mail folder.
 */
class MailApi {

	static final int MAX_LIMIT = 200;
	// Inline images larger than this are left as attachments instead of being
	// embedded into the HTML as data: URIs.
	static final long MAX_INLINE_IMAGE = 2L * 1024 * 1024;

	def context;
	def session;
	def XPath;
	def JSON;
	MailAccounts accounts;

	protected MailApi(context) {
		this.context = context;
		this.session = context.session;
		this.XPath = context.getAttribute('XPath');
		this.JSON = context.getAttribute('JSON');
		this.accounts = MailAccounts.create(context);
		if (session.isAnonymous()) {
			throw new IllegalStateException('Sign in to use mail.');
		}
	}

	static MailApi create(context) {
		return new MailApi(context);
	}

	String getMessagesRoot() {
		return "${accounts.root}/messages".toString();
	}

	// --- accounts ---------------------------------------------------------------

	List<Map> listAccounts() {
		return accounts.list().collect { toAccount(it) };
	}

	Map toAccount(Map a) {
		Map state = MailSync.readState(context, accounts, a.id as String);
		Long oldest = null;
		(state.folders as Map).values().each { fs ->
			if (fs.since != null && (oldest == null || (fs.since as long) < oldest)) {
				oldest = fs.since as long;
			}
		};
		Map progress = state.progress as Map;
		return [
			id: a.id,
			name: a.name,
			address: a.address,
			personal: a.personal,
			color: a.color,
			enabled: a.enabled != false,
			incoming: a.incoming,
			outgoing: a.outgoing,
			initialDays: a.initialDays,
			intervalMinutes: a.intervalMinutes,
			hasIncomingPassword: !!a.hasIncomingPassword,
			hasOutgoingPassword: !!a.hasOutgoingPassword,
			status: [
				state: state.status ?: 'never',
				lastRun: toDate(state.lastRun),
				lastSuccess: toDate(state.lastSuccess),
				lastError: state.lastError,
				progressFolder: progress?.folder,
				progressDone: progress?.done,
				progressTotal: progress?.total,
				more: !!state.more,
				fetchOlderDays: state.fetchOlderDays,
				oldestDate: toDate(oldest),
			],
		];
	}

	private static Date toDate(value) {
		return (value == null) ? null : new Date(value as long);
	}

	Map saveAccount(Map input) {
		return toAccount(accounts.save(input));
	}

	boolean removeAccount(String id) {
		if (accounts.get(id) == null) {
			return false;
		}
		accounts.remove(id);
		MailCompose compose = compose();
		compose.listDrafts().findAll { it.accountId == id }.each { compose.removeDraft(it.id as String); };
		return true;
	}

	/**
	 * Connects to the servers with the given settings. A missing password is taken
	 * from the stored account when the input has an id.
	 */
	Map testAccount(Map input) {
		Map stored = input.id ? accounts.get(input.id as String) : null;
		Map result = [ok: true, messages: [], folders: []];

		String address = (input.address ?: '').toString().trim();
		String incomingPassword = (input.incomingPassword ?: (stored ? accounts.password(stored.id as String, 'incoming') : null)) as String;
		Map incoming = null;
		try {
			incoming = MailAccounts.server(input.incoming as Map, 'ssl', 993, address);
			def store = MailStore.Builder.create(MailAccounts.incomingURI([incoming: incoming])).
					setUsername(incoming.username as String).
					setPassword(incomingPassword).
					build();
			try {
				result.folders = store.listFolders().findAll { it.holdsMessages() }.collect { it.fullName };
			} finally {
				store.close();
			}
			result.messages.add("IMAP ${MailAccounts.describeServer(incoming)}: OK (${result.folders.size()} folders)".toString());
		} catch (Throwable ex) {
			result.ok = false;
			String target = incoming ? " ${MailAccounts.describeServer(incoming)}" : '';
			result.messages.add("IMAP${target}: ${MailSync.describeError(ex)}".toString());
		}

		Map outgoingInput = input.outgoing as Map;
		if (outgoingInput?.host) {
			Map outgoing = null;
			try {
				outgoing = MailAccounts.server(outgoingInput, 'starttls', 587, address);
				boolean same = (outgoingInput.sameAuthentication == null) ? true : !!outgoingInput.sameAuthentication;
				String username = same ? (incoming?.username ?: address) : outgoing.username;
				String password = same ? incomingPassword : (input.outgoingPassword ?: (stored ? accounts.password(stored.id as String, 'outgoing') : null));
				def transport = Transport.Builder.create(MailAccounts.outgoingURI([outgoing: outgoing])).
						setUsername(username as String).
						setPassword(password as String).
						build();
				transport.close();
				result.messages.add("SMTP ${MailAccounts.describeServer(outgoing)}: OK".toString());
			} catch (Throwable ex) {
				result.ok = false;
				String target = outgoing ? " ${MailAccounts.describeServer(outgoing)}" : '';
				result.messages.add("SMTP${target}: ${MailSync.describeError(ex)}".toString());
			}
		}
		return result;
	}

	boolean requestSync(String accountId) {
		List<String> ids = accountId ? [accountId] : accounts.list().collect { it.id as String };
		ids.each { id ->
			if (accounts.get(id) == null) {
				throw new IllegalArgumentException("No such mail account: ${id}".toString());
			}
			MailAccounts.updateIndex(context, accounts.userId, id, [requested: true]);
		};
		return true;
	}

	boolean fetchOlder(String accountId, int days) {
		MailSync.requestOlder(context, accountId, days);
		return true;
	}

	// --- messages ---------------------------------------------------------------

	/**
	 * Lists messages, newest first.
	 *
	 * filter.accountIds  the accounts to include; all when empty
	 * filter.role        "inbox", "sent" or null for both
	 * filter.view        "all" (default), "unread", "flagged" or "trash"
	 * filter.text        words to find in the subject, addresses or full text
	 * filter.color       one of MailLabels.COLORS
	 * filter.tag         a tag
	 * filter.locked      true for locked messages only
	 */
	Map listMessages(Map filter, int offset, int limit) {
		filter = filter ?: [:];
		limit = Math.max(1, Math.min(limit, MAX_LIMIT));
		offset = Math.max(0, offset);

		def builder = XPath.newBuilder().
				append("${messagesRoot}//*".toString()).
				append('[(@mail:removed=$false or @mail:locked=$true)');
		builder.variable('false', false);
		builder.variable('true', true);

		List<String> ids = (filter.accountIds ?: []) as List<String>;
		if (ids) {
			builder.append(' and (');
			ids.eachWithIndex { id, i ->
				builder.append((i > 0) ? ' or ' : '').append("@mail:account=\$a${i}".toString());
				builder.variable("a${i}".toString(), MailAccounts.checkId(id));
			};
			builder.append(')');
		}

		if (filter.role in ['inbox', 'sent']) {
			builder.append(' and @mail:role=$role').variable('role', filter.role as String);
		}

		switch (filter.view) {
		case 'unread':
			builder.append(' and @mail:seen=$false and @mail:trashed=$false and @mail:deleted=$false');
			break;
		case 'flagged':
			builder.append(' and @mail:flagged=$true and @mail:trashed=$false and @mail:deleted=$false');
			break;
		case 'trash':
			builder.append(' and (@mail:trashed=$true or @mail:deleted=$true)');
			break;
		default:
			builder.append(' and @mail:trashed=$false and @mail:deleted=$false');
		}

		if (filter.color) {
			builder.append(' and @mail:color=$color').variable('color', filter.color as String);
		}
		if (filter.tag) {
			builder.append(' and @mail:tags=$tag').variable('tag', filter.tag as String);
		}
		if (filter.locked) {
			builder.append(' and @mail:locked=$true');
		}

		String text = (filter.text ?: '').toString().trim();
		if (text) {
			text.split(/\s+/).take(5).eachWithIndex { word, i ->
				String v = "q${i}".toString();
				builder.append(" and (jcr:contains(., \$${v}) or jcr:contains(@mail:subject, \$${v}) or jcr:contains(@mail:from, \$${v}) or jcr:contains(@mail:to, \$${v}))".toString());
				builder.variable(v, word);
			};
		}

		builder.append('] order by xs:dateTime(@mail:receivedDate) descending');
		def result = builder.build().offset(offset as long).limit(limit as long).execute();
		return [
			items: result.resources.collect { toSummary(it) },
			hasMore: result.hasMore(),
			offset: offset,
		];
	}

	Map toSummary(r) {
		return [
			id: r.path,
			accountId: string(r, 'mail:account'),
			folder: string(r, 'mail:folder'),
			role: string(r, 'mail:role'),
			subject: string(r, 'mail:subject') ?: '',
			from: string(r, 'mail:from'),
			fromAddress: string(r, 'mail:fromAddress'),
			to: string(r, 'mail:to'),
			preview: string(r, 'mail:preview'),
			sentDate: date(r, 'mail:sentDate'),
			receivedDate: date(r, 'mail:receivedDate'),
			size: r.hasProperty('mail:size') ? r.getProperty('mail:size').getLong() : null,
			hasAttachments: MailSync.flag(r, 'mail:hasAttachments'),
			seen: MailSync.flag(r, 'mail:seen'),
			flagged: MailSync.flag(r, 'mail:flagged'),
			answered: MailSync.flag(r, 'mail:answered'),
			trashed: MailSync.flag(r, 'mail:trashed') || MailSync.flag(r, 'mail:deleted'),
			color: string(r, 'mail:color'),
			tags: r.hasProperty('mail:tags') ? (r.getProperty('mail:tags').getStringArray() as List) : [],
			locked: MailLabels.isLocked(r),
			error: string(r, 'mail:error'),
		];
	}

	private static String string(r, String name) {
		return r.hasProperty(name) ? r.getProperty(name).getString() : null;
	}

	private static Date date(r, String name) {
		return r.hasProperty(name) ? r.getProperty(name).getDate().getTime() : null;
	}

	/** The message file, checked to be one of the caller's messages. */
	def messageResource(String id) {
		if (!id || !id.startsWith("${messagesRoot}/".toString()) || id.contains('/../') || !id.endsWith('.eml')) {
			throw new IllegalArgumentException("Not a mail message: ${id}".toString());
		}
		def r = session.getResource(id);
		if (!r.exists()) {
			return null;
		}
		return r;
	}

	/** The message with its bodies and attachments. */
	Map getMessage(String id) {
		def r = messageResource(id);
		if (r == null) {
			return null;
		}
		Map summary = toSummary(r);
		Message m = Message.from(r.getContentAsStream());
		try {
			if (summary.receivedDate) {
				m.setReceivedDate(summary.receivedDate as Date);
			}
			List<Map> attachments = [];
			Map<String, String> inlineImages = [:];
			m.attachments.eachWithIndex { a, i ->
				String cid = a.contentID;
				attachments.add([
					index: i,
					name: a.filename,
					mimeType: a.mimeType,
					inline: a.isInline(),
					contentId: cid,
				]);
			};

			String html = m.getContent('text/html');
			if (html != null) {
				// Embed the images the HTML references by cid:, so the viewer needs no
				// further request and no remote access.
				m.attachments.eachWithIndex { a, i ->
					String cid = a.contentID;
					if (cid && a.mimeType.startsWith('image/') && html.contains("cid:${cid}".toString())) {
						byte[] bytes = a.inputStream.withCloseable { it.readNBytes((int) MAX_INLINE_IMAGE + 1) };
						if (bytes.length <= MAX_INLINE_IMAGE) {
							inlineImages[cid] = "data:${a.mimeType};base64,${Base64.encoder.encodeToString(bytes)}".toString();
						}
					}
				};
				html = MailHtml.sanitize(html, inlineImages);
			}
			attachments.removeAll { it.contentId && inlineImages.containsKey(it.contentId) };

			return summary + [
				from: addresses(m.from),
				to: addresses(m.to),
				cc: addresses(m.cc),
				bcc: addresses(m.bcc),
				replyTo: addresses(m.replyTo),
				messageId: m.messageID,
				inReplyTo: (m.inReplyTo ?: []) as List,
				references: (m.references ?: []) as List,
				text: m.getContent('text/plain'),
				html: html,
				attachments: attachments,
			];
		} finally {
			m.close();
		}
	}

	private static List<Map> addresses(a) {
		return (a ?: []).collect { InternetAddress ia -> [name: ia.personal, address: ia.address ?: ''] };
	}

	/**
	 * Changes flags of messages. The change is shown at once and pushed to the
	 * server by the next pass, which is requested here.
	 */
	int setFlags(List<String> ids, Boolean seen, Boolean flagged) {
		Map changes = [:];
		if (seen != null) {
			changes.seen = seen;
		}
		if (flagged != null) {
			changes.flagged = flagged;
		}
		return changeFlags(ids, changes);
	}

	/** Marks the original of a sent reply as answered. */
	void markAnswered(String id) {
		changeFlags([id], [answered: true]);
	}

	private int changeFlags(List<String> ids, Map changes) {
		if (!changes) {
			return 0;
		}
		int count = 0;
		Set<String> touched = [] as Set;
		ids.each { id ->
			def r = messageResource(id);
			if (r == null) {
				return;
			}
			Map pending = r.hasProperty('mail:pending') ? (JSON.parse(r.getProperty('mail:pending').getString()) as Map) : [:];
			boolean changed = false;
			changes.each { name, value ->
				if (MailSync.flag(r, "mail:${name}".toString()) != value) {
					r.setProperty("mail:${name}".toString(), value as boolean);
					pending[name] = value;
					changed = true;
				}
			};
			if (changed && MailSync.flag(r, 'mail:local')) {
				// Kept in the Webtop only: nothing to write to the server.
				count++;
			} else if (changed) {
				r.setProperty('mail:pending', JSON.stringify(pending));
				r.setProperty('mail:hasPending', true);
				touched.add(r.getProperty('mail:account').getString());
				count++;
			}
		};
		session.commit();
		touched.each { MailAccounts.updateIndex(context, accounts.userId, it, [requested: true]); };
		return count;
	}

	/** Moves messages to or out of the Webtop trash. The server copy stays. */
	int setTrashed(List<String> ids, boolean trashed) {
		int count = 0;
		ids.each { id ->
			def r = messageResource(id);
			// A locked message stays where it is.
			if (r != null && MailSync.flag(r, 'mail:trashed') != trashed && !(trashed && MailLabels.isLocked(r))) {
				r.setProperty('mail:trashed', trashed);
				count++;
			}
		};
		session.commit();
		return count;
	}

	// --- colors, tags, locks ------------------------------------------------------

	MailLabels labels() {
		return MailLabels.create(context, this);
	}

	Map listLabels() {
		return [colors: MailLabels.COLORS, tags: labels().listTags()];
	}

	int setLabels(List<String> ids, String color, List<String> addTags, List<String> removeTags) {
		return labels().setLabels(ids, color, addTags, removeTags);
	}

	int setLocked(List<String> ids, boolean locked) {
		return labels().setLocked(ids, locked);
	}

	// --- drafts and sending ---------------------------------------------------------

	MailCompose compose() {
		return MailCompose.create(context, this);
	}

	List<Map> listDrafts() {
		return compose().listDrafts();
	}

	Map getDraft(String id) {
		return compose().getDraft(id);
	}

	Map saveDraft(Map input) {
		return compose().saveDraft(input);
	}

	boolean removeDraft(String id) {
		return compose().removeDraft(id);
	}

	String sendDraft(String id) {
		return compose().send(id);
	}

	List<Map> listRecipients() {
		return compose().listRecipients();
	}

}
