package webtop.mail;

import org.mintjams.cms.security.Encryptors;

/**
 * Mail accounts of one user.
 *
 * Everything a user owns lives in the user's home in the system workspace:
 *
 *   /home/users/<user>/mail/accounts/<account>.json   settings (no secrets)
 *   /home/users/<user>/mail/state/<account>.json      synchronization state
 *   /home/users/<user>/mail/messages/<account>/...    one .eml file per message
 *
 * Passwords are encrypted with the installation key (Encryptors) and kept as
 * properties of the settings file, never in its JSON, so reading the file does
 * not reveal them and the GraphQL API never returns them.
 *
 * The background synchronization (MailScheduler) finds the accounts with a
 * query on the mail: properties of the settings files, and keeps its schedule
 * on them: mail:accountId, mail:enabled and mail:intervalMinutes are written
 * with the settings, mail:requested asks for the next pass at once,
 * mail:followUp asks for one more pass at that time (a sent message that the
 * server files with a delay), and mail:nextRun is when the next regular pass
 * is due. For that the accounts
 * folder grants the mail service group read and modifyProperties
 * (SERVICE_PRIVILEGES), the first time the user saves an account; the service
 * user sees nothing else of the home. A deleted user takes the accounts away
 * with the home, so nothing is left to synchronize.
 */
class MailAccounts {

	static final String SERVICE_USER = 'mail-service-user';
	static final String SERVICE_GROUP = 'mail-service-group';
	static final List<String> SERVICE_PRIVILEGES = ['jcr:read', 'jcr:modifyProperties'];
	static final String LOCK_ROOT = '/var/lock/mail';
	static final String HOMES = '/home/users';
	static final String ACCOUNT_TYPE = 'application/vnd.mintjams.webtop.mail.account+json';
	static final String STATE_TYPE = 'application/vnd.mintjams.webtop.mail.state+json';

	static final String INCOMING_PASSWORD = 'mail:incomingPassword';
	static final String OUTGOING_PASSWORD = 'mail:outgoingPassword';

	static final List<String> SECURITY = ['ssl', 'starttls', 'none'];
	static final int DEFAULT_INITIAL_DAYS = 30;
	static final int DEFAULT_INTERVAL_MINUTES = 5;
	static final int MAX_INITIAL_DAYS = 3650;

	/** imap (any server, password), gmail and microsoft (a linked account, XOAUTH2). */
	static final List<String> KINDS = ['imap', 'gmail', 'microsoft'];

	/**
	 * How mail goes out: smtp (the outgoing server), or graph for a Microsoft
	 * account whose tenant has SMTP AUTH turned off (GraphTransport).
	 */
	static final List<String> SEND_VIA = ['smtp', 'graph'];

	/**
	 * What a kind fixes: the servers, the provider of the linked account that
	 * signs in, and whether a sent message is put into the server's Sent folder
	 * (Gmail files what SMTP sends there by itself; Outlook does not).
	 */
	static final Map<String, Map> PRESETS = [
		gmail: [
			provider: 'google',
			incoming: [host: 'imap.gmail.com', port: 993, security: 'ssl'],
			outgoing: [host: 'smtp.gmail.com', port: 587, security: 'starttls'],
			saveSentOnServer: false,
		],
		microsoft: [
			provider: 'microsoft',
			incoming: [host: 'outlook.office365.com', port: 993, security: 'ssl'],
			outgoing: [host: 'smtp.office365.com', port: 587, security: 'starttls'],
			saveSentOnServer: true,
		],
	];

	/** The permission bundle of the linked account the Mail app uses. */
	static final String BUNDLE = 'mail';

	/** Tells javax.mail to sign in with the access token (SASL XOAUTH2) and nothing else. */
	static final Map<String, String> XOAUTH2 = [
		'mail.imap.auth.mechanisms': 'XOAUTH2',
		'mail.imaps.auth.mechanisms': 'XOAUTH2',
		'mail.smtp.auth.mechanisms': 'XOAUTH2',
		'mail.smtps.auth.mechanisms': 'XOAUTH2',
	];

	def context;
	def session;
	def JSON;
	String userId;

	protected MailAccounts(context) {
		this.context = context;
		this.session = context.session;
		this.JSON = context.getAttribute('JSON');
		this.userId = this.session.userID;
	}

	static MailAccounts create(context) {
		return new MailAccounts(context);
	}

	static String homeOf(String userId) {
		return "${HOMES}/${userId}/mail".toString();
	}

	String getRoot() {
		return homeOf(userId);
	}

	String accountPath(String id) {
		return "${root}/accounts/${checkId(id)}.json".toString();
	}

	String statePath(String id) {
		return "${root}/state/${checkId(id)}.json".toString();
	}

	String messagesPath(String id) {
		return "${root}/messages/${checkId(id)}".toString();
	}

	static String checkId(String id) {
		if (!(id ==~ /[A-Za-z0-9_-]{1,64}/)) {
			throw new IllegalArgumentException("Invalid account id: ${id}".toString());
		}
		return id;
	}

	/** Every account of the user, ordered by name. */
	List<Map> list() {
		def folder = session.getResource("${root}/accounts".toString());
		if (!folder.exists()) {
			return [];
		}
		List<Map> l = [];
		folder.list().each { r ->
			if (!r.isCollection() && r.name.endsWith('.json')) {
				try {
					l.add(read(r));
				} catch (Throwable ex) {
					context.getAttribute('log')?.warn("Unreadable mail account ${r.path}: ${ex.message}".toString());
				}
			}
		}
		l.sort { a, b -> (a.name ?: a.address ?: '').toString().compareToIgnoreCase((b.name ?: b.address ?: '').toString()); };
		return l;
	}

	/** The account settings, or null when there is no such account. */
	Map get(String id) {
		def r = session.getResource(accountPath(id));
		if (!r.exists()) {
			return null;
		}
		return read(r);
	}

	private Map read(r) {
		Map account = JSON.parse(r.getContent()) as Map;
		account.hasIncomingPassword = r.hasProperty(INCOMING_PASSWORD);
		account.hasOutgoingPassword = r.hasProperty(OUTGOING_PASSWORD);
		return account;
	}

	/** The decrypted password, or null when none is stored. */
	String password(String id, String kind) {
		def r = session.getResource(accountPath(id));
		String name = (kind == 'outgoing') ? OUTGOING_PASSWORD : INCOMING_PASSWORD;
		if (!r.exists() || !r.hasProperty(name)) {
			return null;
		}
		return Encryptors.getDefault().decrypt(r.getProperty(name).getString());
	}

	/**
	 * Creates or updates an account from the GraphQL input. A blank password keeps
	 * the stored one. Returns the saved settings.
	 */
	Map save(Map input) {
		Map existing = input.id ? get(input.id as String) : null;
		if (input.id && existing == null) {
			throw new IllegalArgumentException("No such mail account: ${input.id}".toString());
		}
		Map account = normalize(input, existing);

		def file = session.getResource(accountPath(account.id as String));
		if (!file.exists()) {
			def folder = file.getParent();
			folder.getOrCreateFolder();
			ensureServiceAccess(folder);
			file.createFile();
		}
		Map stored = new LinkedHashMap(account);
		stored.remove('hasIncomingPassword');
		stored.remove('hasOutgoingPassword');
		file.write(JSON.stringify(stored));
		file.setContentType(ACCOUNT_TYPE);
		file.setContentEncoding('UTF-8');
		if (input.incomingPassword) {
			file.setProperty(INCOMING_PASSWORD, Encryptors.getDefault().encrypt(input.incomingPassword as String));
		}
		if (input.outgoingPassword) {
			file.setProperty(OUTGOING_PASSWORD, Encryptors.getDefault().encrypt(input.outgoingPassword as String));
		}
		// What the background synchronization reads (see the class comment).
		file.setProperty('mail:accountId', account.id as String);
		file.setProperty('mail:enabled', !!account.enabled);
		file.setProperty('mail:intervalMinutes', account.intervalMinutes as long);
		file.setProperty('mail:requested', true);
		session.commit();
		return get(account.id as String);
	}

	/**
	 * Grants the mail service group what the synchronization needs on the
	 * accounts folder, unless it has it already. Runs in the user's session, the
	 * only one that may change the home.
	 */
	private void ensureServiceAccess(folder) {
		def acl = folder.getAccessControlList();
		for (def entry : acl.getAccessControlEntries()) {
			if (entry.isAllow() && entry.principal.name == SERVICE_GROUP) {
				return;
			}
		}
		def group = session.principalProvider.getPrincipal(SERVICE_GROUP);
		if (group == null) {
			throw new IllegalStateException("The group ${SERVICE_GROUP} is not provisioned (provisioning/mail.yml).".toString());
		}
		acl.addAccessControlEntry(group, true, SERVICE_PRIVILEGES as String[]);
		folder.setAccessControlList(acl);
	}

	/** Asks the background synchronization to run a pass for the account at once. */
	void requestRun(String id) {
		requestRun(id, 0L);
	}

	/**
	 * Asks for a pass at once and, with {@code followUpSeconds} above zero, for
	 * one more pass that many seconds later: for what the server has to do first,
	 * such as Exchange filing a message sent through Graph into Sent Items.
	 */
	void requestRun(String id, long followUpSeconds) {
		def file = session.getResource(accountPath(id));
		if (!file.exists()) {
			return;
		}
		file.setProperty('mail:requested', true);
		if (followUpSeconds > 0) {
			file.setProperty('mail:followUp', new Date(System.currentTimeMillis() + followUpSeconds * 1000L));
		}
		session.commit();
	}

	/**
	 * The settings an input describes, validated: for a new account from scratch,
	 * for an existing one on top of what is stored. A preset kind (gmail,
	 * microsoft) fixes the servers and signs in with the linked account; the
	 * input's servers are ignored then. Also used to probe unsaved settings.
	 */
	Map normalize(Map input, Map existing) {
		Map account = (existing != null) ? new LinkedHashMap(existing) : [
			id: UUID.randomUUID().toString().replace('-', '').substring(0, 16),
			created: new Date().time,
		];

		String address = (input.address ?: '').toString().trim();
		if (!(address ==~ /[^\s@<>]+@[^\s@<>]+/)) {
			throw new IllegalArgumentException('Enter a valid mail address.');
		}
		account.address = address;
		account.name = (input.name ?: '').toString().trim() ?: address;
		account.personal = (input.personal ?: '').toString().trim();
		account.color = (input.color ?: '').toString().trim();
		account.enabled = (input.enabled == null) ? true : !!input.enabled;

		String kind = (input.kind ?: existing?.kind ?: 'imap').toString();
		if (!KINDS.contains(kind)) {
			throw new IllegalArgumentException("Unknown account kind: ${kind}".toString());
		}
		Map preset = PRESETS[kind] as Map;
		account.kind = kind;
		account.auth = preset ? 'oauth' : 'password';
		account.provider = preset ? preset.provider : null;
		if (preset) {
			account.incoming = server(preset.incoming as Map, 'ssl', 993, address);
			Map outgoing = server(preset.outgoing as Map, 'starttls', 587, address);
			outgoing.sameAuthentication = true;
			account.outgoing = outgoing;
		} else {
			account.incoming = server(input.incoming as Map, 'ssl', 993, address);
			Map outgoing = server(input.outgoing as Map, 'starttls', 587, address);
			outgoing.sameAuthentication = (input.outgoing?.sameAuthentication == null) ? true : !!input.outgoing.sameAuthentication;
			account.outgoing = outgoing;
		}
		if (input.saveSentOnServer != null) {
			account.saveSentOnServer = !!input.saveSentOnServer;
		} else if (existing?.saveSentOnServer == null) {
			account.saveSentOnServer = preset ? !!preset.saveSentOnServer : true;
		}
		String sendVia = (input.sendVia ?: existing?.sendVia ?: 'smtp').toString();
		if (!SEND_VIA.contains(sendVia)) {
			throw new IllegalArgumentException("Unknown sending method: ${sendVia}".toString());
		}
		if (sendVia == 'graph' && kind != 'microsoft') {
			throw new IllegalArgumentException('Only a Microsoft account can send through Microsoft Graph.');
		}
		account.sendVia = sendVia;

		int days = (input.initialDays != null) ? (input.initialDays as int) : ((existing?.initialDays ?: DEFAULT_INITIAL_DAYS) as int);
		if (days < 1 || days > MAX_INITIAL_DAYS) {
			throw new IllegalArgumentException("Initial days must be between 1 and ${MAX_INITIAL_DAYS}.".toString());
		}
		account.initialDays = days;
		int interval = (input.intervalMinutes != null) ? (input.intervalMinutes as int) : ((existing?.intervalMinutes ?: DEFAULT_INTERVAL_MINUTES) as int);
		account.intervalMinutes = Math.max(1, Math.min(interval, 1440));
		account.modified = new Date().time;
		return account;
	}

	/**
	 * Validates server settings. A host entered as "host:port" or as a URL is
	 * split, the port in the host taking effect when no port is given.
	 */
	static Map server(Map input, String defaultSecurity, int defaultPort, String address) {
		String host = (input?.host ?: '').toString().trim();
		host = host.replaceFirst(/^[A-Za-z][A-Za-z0-9+.-]*:\/\//, '').replaceFirst(/\/.*$/, '');
		Integer hostPort = null;
		def m = (host =~ /^(.*):(\d{1,5})$/);
		if (m.matches()) {
			host = m.group(1);
			hostPort = m.group(2) as int;
		}
		if (!host) {
			throw new IllegalArgumentException('Enter the server host name.');
		}
		if (!(host ==~ /[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+\]/)) {
			throw new IllegalArgumentException("Invalid server name: ${input.host}".toString());
		}
		String security = (input.security ?: defaultSecurity).toString().toLowerCase();
		if (!SECURITY.contains(security)) {
			throw new IllegalArgumentException("Unknown connection security: ${security}".toString());
		}
		int port = (input.port ?: hostPort ?: defaultPort) as int;
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("Invalid port: ${port}".toString());
		}
		return [
			host: host,
			port: port,
			security: security,
			username: (input.username ?: address).toString().trim(),
		];
	}

	/**
	 * What a connection signs in with: username, password and the javax.mail
	 * properties to set. `kind` is incoming or outgoing. An account that signs in
	 * with a linked Google / Microsoft account gets an access token of the mail
	 * bundle as the password and the XOAUTH2 properties; the Connections binding
	 * renews the token as needed and throws ConsentRequiredException when the
	 * user has to link or grant first (MailSync.describeError words it).
	 */
	Map credentials(Map account, String kind) {
		if (account.auth == 'oauth') {
			def connections = context.getAttribute('Connections');
			if (connections == null) {
				throw new IllegalStateException('The Connections binding is not available in this context.');
			}
			String token = connections.accessToken(account.provider as String, BUNDLE);
			return [
				username: ((account.incoming as Map)?.username ?: account.address) as String,
				password: token,
				properties: XOAUTH2,
			];
		}
		Map outgoing = account.outgoing as Map;
		boolean same = (kind != 'outgoing') || outgoing?.sameAuthentication == null || !!outgoing.sameAuthentication;
		String username = same ? (((account.incoming as Map)?.username ?: account.address) as String) : (outgoing.username as String);
		return [
			username: username,
			password: password(account.id as String, same ? 'incoming' : 'outgoing'),
			properties: [:],
		];
	}

	/** Whether an account signs in with a linked account rather than a password. */
	static boolean isOAuth(Map account) {
		return account?.auth == 'oauth';
	}

	/** Whether an account sends through Microsoft Graph instead of its outgoing server. */
	static boolean sendsViaGraph(Map account) {
		return account?.sendVia == 'graph';
	}

	/** "host:port (SSL/TLS)", to show which server was contacted. */
	static String describeServer(Map s) {
		String security = [ssl: 'SSL/TLS', starttls: 'STARTTLS', none: 'no encryption'][s.security] ?: s.security;
		return "${s.host}:${s.port} (${security})".toString();
	}

	/** Removes the account with its state and every downloaded message. */
	void remove(String id) {
		[accountPath(id), statePath(id), messagesPath(id)].each { path ->
			def r = session.getResource(path);
			if (r.exists()) {
				r.remove();
			}
		};
		session.commit();
	}

	// --- server URIs ------------------------------------------------------------

	/** The IMAP URI understood by org.mintjams.tools.mail.MailStore. */
	static String incomingURI(Map account) {
		Map s = account.incoming as Map;
		String scheme = (s.security == 'ssl') ? 'imaps' : 'imap';
		String query = (s.security == 'starttls') ? '?starttls=required' : '';
		return "${scheme}://${s.host}:${s.port}${query}".toString();
	}

	/** The SMTP URI understood by org.mintjams.tools.mail.Transport. */
	static String outgoingURI(Map account) {
		Map s = account.outgoing as Map;
		if (s.security == 'ssl') {
			return "smtps://${s.host}:${s.port}".toString();
		}
		String query = (s.security == 'starttls') ? '?starttls=required' : '';
		return "smtp://${s.host}:${s.port}${query}".toString();
	}

}
