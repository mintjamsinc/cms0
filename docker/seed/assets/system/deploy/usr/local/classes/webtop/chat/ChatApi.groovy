package webtop.chat;

/**
 * What the GraphQL resolvers of the Chat app do.
 *
 * Every operation first checks, in the caller's own session, what the caller
 * may do, and then reads and writes the conversation as the chat service user
 * (ChatStore). The caller's session writes one place only: what the caller
 * keeps in the home (ChatHome).
 *
 * A conversation is addressed by a reference, { channelId } or { fileId }.
 * Both kinds are read and written the same way; they differ in who may read
 * them. A channel is read by whoever was granted its folder. The conversation
 * of a file is read by whoever can read the file: the caller's own session
 * resolves the identifier each time, and nothing more is asked.
 */
class ChatApi {

	static final int DEFAULT_PAGE = 50;
	static final int MAX_FOUND = 100;
	static final int SEARCH_PAGE = 20;
	static final int MAX_SEARCH_PAGE = 50;
	// How many of the latest mentions bring a conversation into the sidebar.
	static final int SIDEBAR_MENTIONS = 100;

	def context;
	def session;
	String userId;
	ChatHome home;
	private Map<String, String> names = [:];

	protected ChatApi(context) {
		this.context = context;
		this.session = context.session;
		if (session.isAnonymous()) {
			throw new IllegalStateException('Sign in to use chat.');
		}
		this.userId = session.userID;
		this.home = new ChatHome(session, userId);
	}

	static ChatApi create(context) {
		return new ChatApi(context);
	}

	private Object asService(Closure closure) {
		return ChatStore.withService(context, closure);
	}

	// --- what the caller may do -------------------------------------------------

	/** Whether the caller's own session can read the path. */
	private boolean canRead(String path) {
		try {
			return session.getResource(path).canRead();
		} catch (Throwable ignore) {
			return false;
		}
	}

	private static String channelIdOf(Map ref) {
		if (!ref?.channelId) {
			throw new IllegalArgumentException('Name a channel by channelId.');
		}
		return ref.channelId as String;
	}

	/**
	 * The conversation the reference names, which the caller must be able to
	 * read: where it is kept (root), its key in the caller's home, whether it
	 * takes posts, and what a client watches for changes. With `channel`, the
	 * settings of the channel; with `file`, the file the conversation is about.
	 */
	private Map resolve(service, Map ref) {
		if (ref?.channelId && ref?.fileId) {
			throw new IllegalArgumentException('Name the conversation by channelId or fileId, not both.');
		}
		if (ref?.fileId) {
			String fileId = ChatStore.checkId(ref.fileId as String);
			def file = readable(fileId);
			if (file == null) {
				throw new IllegalArgumentException('No such file, or you cannot read it.');
			}
			return [
				kind: 'file',
				fileId: fileId,
				file: file,
				root: ChatStore.fileRoot(fileId),
				key: ChatHome.fileKey(fileId),
				canPost: true,
				watchPath: ChatStore.signalRoot(fileId),
			];
		}
		Map channel = joinedChannel(service, channelIdOf(ref));
		return [
			kind: 'channel',
			channel: channel,
			root: channel.path,
			key: ChatHome.channelKey(channel.id as String),
			canPost: !channel.archived,
			watchPath: channel.path,
		];
	}

	private static void checkCanPost(Map conversation) {
		if (!conversation.canPost) {
			throw new IllegalStateException('The channel is archived.');
		}
	}

	/**
	 * Tells the clients that the conversation changed. A channel needs nothing:
	 * its participants watch the folder the messages are in. The conversation of
	 * a file is closed to everyone, so a mark is left where everyone can see it.
	 */
	private static void changed(service, Map conversation) {
		if (conversation.kind == 'file') {
			ChatStore.signal(service, conversation.fileId as String);
		}
	}

	/** The channel, which the caller must take part in. */
	private Map joinedChannel(service, String id) {
		Map channel = ChatChannels.read(service, id);
		if (channel == null || !canRead(channel.path as String)) {
			throw new IllegalArgumentException('No such channel, or you do not take part in it.');
		}
		return channel;
	}

	/**
	 * Whether the user is granted the channel, as a user or through a group.
	 * Being able to read it is not the same: an administrator reads every
	 * channel without being a participant of any.
	 */
	private static boolean isParticipant(service, String channelId, String user) {
		List<String> members = ChatChannels.memberIds(service, channelId);
		if (members.contains(ChatStore.EVERYONE) || members.contains(user)) {
			return true;
		}
		def principal = ChatStore.findPrincipal(service, user);
		if (principal == null) {
			return false;
		}
		try {
			return service.principalProvider.getMemberOf(principal).any { members.contains(it.name) };
		} catch (Throwable ignore) {
			return false;
		}
	}

	/** The channel, which the caller must administer. */
	private Map administered(service, String id) {
		Map channel = joinedChannel(service, id);
		if (!(channel.admins as List).contains(userId)) {
			throw new IllegalStateException('Only an administrator of the channel can do this.');
		}
		return channel;
	}

	// --- channels ---------------------------------------------------------------

	private Map describe(service, Map channel, boolean following, Map<String, Date> reads) {
		Map described = [
			id: channel.id,
			title: channel.title,
			description: channel.description,
			kind: channel.kind,
			archived: channel.archived,
			isAdmin: (channel.admins as List).contains(userId),
			following: following,
			peerId: null,
			watchPath: channel.path,
			lastMessageAt: ChatMessages.lastMessageAt(service, channel.path as String),
			readAt: reads[ChatHome.channelKey(channel.id as String)],
		];
		if (channel.kind == ChatChannels.DIRECT) {
			// A direct message is shown under the other user's name.
			String peer = ChatChannels.memberIds(service, channel.id as String).find { it != userId } ?: userId;
			described.peerId = peer;
			described.title = displayName(service, peer, false);
		}
		return described;
	}

	/** Opens the direct messages with the user, creating them the first time. */
	Map openDirectMessage(String peerId) {
		return asService { service ->
			if (!peerId || peerId == userId) {
				throw new IllegalArgumentException('Name another user.');
			}
			def user = service.userManager.getUser(peerId);
			if (user == null) {
				throw new IllegalArgumentException("No such user: ${peerId}".toString());
			}
			Map channel = ChatChannels.openDirect(service, userId, peerId);
			return describe(service, channel, false, home.reads());
		} as Map;
	}

	/**
	 * The channels of the sidebar: the private channels the caller takes part
	 * in and the public ones the caller added. Archived channels are left out.
	 */
	List<Map> listChannels() {
		return asService { service ->
			Set<String> following = home.following();
			Map<String, Date> reads = home.reads();
			List<Map> channels = [];
			ChatChannels.ids(service).each { String id ->
				boolean followed = following.contains(ChatHome.channelKey(id));
				if (!canRead(ChatChannels.path(id))) {
					return;
				}
				Map channel = ChatChannels.read(service, id);
				if (channel == null || channel.archived) {
					return;
				}
				if (channel.kind == ChatChannels.PUBLIC && !followed) {
					return;
				}
				// An administrator of the workspace can read every channel; the
				// sidebar lists the ones the caller is a participant of.
				if (channel.kind != ChatChannels.PUBLIC && !isParticipant(service, id, userId)) {
					return;
				}
				channels.add(describe(service, channel, followed, reads));
			};
			channels.sort { a, b -> (a.title as String).compareToIgnoreCase(b.title as String); };
			return channels;
		} as List<Map>;
	}

	/**
	 * The channels to pick from: the public ones and, when archived channels are
	 * asked for, also the archived private ones the caller takes part in, which
	 * no sidebar shows any more. Narrowed to those whose name or description
	 * contains the keyword.
	 */
	List<Map> findChannels(String keyword, boolean includeArchived) {
		String text = (keyword ?: '').trim().toLowerCase();
		return asService { service ->
			Set<String> following = home.following();
			Map<String, Date> reads = home.reads();
			List<Map> channels = [];
			for (String id : ChatChannels.ids(service)) {
				Map channel = ChatChannels.read(service, id);
				if (channel == null || (channel.archived && !includeArchived)) {
					continue;
				}
				if (channel.kind != ChatChannels.PUBLIC && !(channel.archived && canRead(channel.path as String))) {
					continue;
				}
				if (text && !"${channel.title}\n${channel.description}".toString().toLowerCase().contains(text)) {
					continue;
				}
				channels.add(describe(service, channel, following.contains(ChatHome.channelKey(id)), reads));
				if (channels.size() >= MAX_FOUND) {
					break;
				}
			}
			channels.sort { a, b -> (a.title as String).compareToIgnoreCase(b.title as String); };
			return channels;
		} as List<Map>;
	}

	Map createChannel(Map input) {
		return asService { service ->
			String kind = (input.kind ?: ChatChannels.PRIVATE) as String;
			// A public channel is added to the creator's sidebar, which is kept in
			// the home. Make sure that can be written before the channel exists.
			if (kind == ChatChannels.PUBLIC) {
				home.prepare();
			}
			Map channel = ChatChannels.create(service, userId, input.title as String, input.description as String, kind);
			if (kind == ChatChannels.PUBLIC) {
				home.follow(ChatHome.channelKey(channel.id as String));
			}
			return describe(service, channel, kind == ChatChannels.PUBLIC, home.reads());
		} as Map;
	}

	Map updateChannel(String id, Map input) {
		return asService { service ->
			Map channel = administered(service, id);
			Map fields = [title: input.title, description: input.description];
			if (input.admins != null) {
				List<String> admins = (input.admins as List).collect { it as String }.unique();
				if (!admins) {
					throw new IllegalArgumentException('A channel needs an administrator.');
				}
				admins.each { String admin ->
					if (service.userManager.getUser(admin) == null) {
						throw new IllegalArgumentException("No such user: ${admin}".toString());
					}
				};
				fields.admins = admins;
			}
			channel = ChatChannels.update(service, id, fields);
			return describe(service, channel, isFollowing(channel), home.reads());
		} as Map;
	}

	Map archiveChannel(String id, boolean archived) {
		return asService { service ->
			administered(service, id);
			Map channel = ChatChannels.update(service, id, [archived: archived]);
			return describe(service, channel, isFollowing(channel), home.reads());
		} as Map;
	}

	private boolean isFollowing(Map channel) {
		return home.following().contains(ChatHome.channelKey(channel.id as String));
	}

	// --- participants -----------------------------------------------------------

	private String displayName(service, String id, boolean group) {
		String key = "${group ? 'g' : 'u'}:${id}".toString();
		if (!names.containsKey(key)) {
			String name = null;
			try {
				name = group ? service.userManager.getGroup(id)?.displayName : service.userManager.getUser(id)?.displayName;
			} catch (Throwable ignore) {}
			names[key] = name ?: id;
		}
		return names[key];
	}

	private static boolean isGroup(service, String id) {
		if (id == ChatStore.EVERYONE) {
			return true;
		}
		try {
			return service.userManager.getUser(id) == null && service.userManager.getGroup(id) != null;
		} catch (Throwable ignore) {
			return false;
		}
	}

	private List<Map> members(service, Map channel) {
		List<Map> members = ChatChannels.memberIds(service, channel.id as String).collect { String id ->
			boolean group = isGroup(service, id);
			return [
				id: id,
				displayName: displayName(service, id, group),
				isGroup: group,
				isAdmin: !group && (channel.admins as List).contains(id),
			];
		};
		members.sort { a, b -> (a.displayName as String).compareToIgnoreCase(b.displayName as String); };
		return members;
	}

	private static void checkPrivate(Map channel) {
		if (channel.kind != ChatChannels.PRIVATE) {
			throw new IllegalStateException('Only a private channel has a list of participants.');
		}
	}

	Map addMembers(String id, List<String> principals) {
		return asService { service ->
			Map channel = administered(service, id);
			checkPrivate(channel);
			ChatChannels.addMembers(service, id, principals);
			return conversationOf(service, channel);
		} as Map;
	}

	/** Removes participants. An administrator among them stops being one. */
	Map removeMembers(String id, List<String> principals) {
		return asService { service ->
			Map channel = administered(service, id);
			checkPrivate(channel);
			channel = dropAdmins(service, channel, principals);
			ChatChannels.removeMembers(service, id, principals);
			return conversationOf(service, channel);
		} as Map;
	}

	private static Map dropAdmins(service, Map channel, List<String> leaving) {
		List<String> admins = (channel.admins as List<String>).findAll { !leaving.contains(it) };
		if (admins.size() == (channel.admins as List).size()) {
			return channel;
		}
		if (!admins) {
			throw new IllegalStateException('A channel needs an administrator. Appoint another one first.');
		}
		return ChatChannels.update(service, channel.id as String, [admins: admins]);
	}

	/**
	 * Leaves a channel: a private one by giving up the caller's own grant, a
	 * public one by taking it out of the sidebar.
	 */
	boolean leaveChannel(String id) {
		return asService { service ->
			Map channel = joinedChannel(service, id);
			if (channel.kind == ChatChannels.PUBLIC) {
				home.unfollow(ChatHome.channelKey(id));
				return true;
			}
			checkPrivate(channel);
			if (!ChatChannels.memberIds(service, id).contains(userId)) {
				throw new IllegalStateException('You take part in this channel through a group, so you cannot leave it on your own.');
			}
			dropAdmins(service, channel, [userId]);
			ChatChannels.removeMembers(service, id, [userId]);
			return true;
		} as boolean;
	}

	// --- conversations ----------------------------------------------------------

	private Map conversationOf(service, Map channel) {
		Map described = describe(service, channel, isFollowing(channel), home.reads());
		return [
			channelId: channel.id,
			fileId: null,
			channel: described,
			file: null,
			members: members(service, channel),
			canPost: !channel.archived,
			following: described.following,
			watchPath: channel.path,
			lastMessageAt: described.lastMessageAt,
			readAt: described.readAt,
		];
	}

	private static Map describeFile(file) {
		boolean folder = file.isCollection();
		return [
			id: file.getIdentifier(),
			name: file.name,
			path: file.path,
			mimeType: folder ? null : file.getContentType(),
			isCollection: folder,
		];
	}

	private Map describeConversation(service, Map conversation) {
		if (conversation.kind == 'channel') {
			return conversationOf(service, conversation.channel as Map);
		}
		String key = conversation.key as String;
		return [
			channelId: null,
			fileId: conversation.fileId,
			channel: null,
			file: describeFile(conversation.file),
			members: [],
			canPost: true,
			following: home.following().contains(key),
			watchPath: conversation.watchPath,
			lastMessageAt: ChatMessages.lastMessageAt(service, conversation.root as String),
			readAt: home.reads()[key],
		];
	}

	Map getConversation(Map ref) {
		return asService { service ->
			return describeConversation(service, resolve(service, ref));
		} as Map;
	}

	/**
	 * The conversations of files in the caller's sidebar, the most recently
	 * active first. One whose file was deleted is taken out of the sidebar. One
	 * whose file the caller cannot read at present is left out but kept, so it
	 * comes back when the caller can read the file again; the caller's session
	 * cannot tell the two apart, the service user's can.
	 */
	List<Map> listFollowedThreads() {
		return asService { service ->
			List<Map> threads = [];
			List<String> fileIds = home.following().findAll { it.startsWith(ChatHome.FILE_PREFIX) }
				.collect { String key -> key.substring(ChatHome.FILE_PREFIX.length()) };
			// A file the caller was mentioned about is in the sidebar as well.
			ChatStore.recentMentions(session, userId, SIDEBAR_MENTIONS).each { Map mark ->
				if (mark.fileId && !fileIds.contains(mark.fileId)) {
					fileIds.add(mark.fileId as String);
				}
			};
			fileIds.each { String fileId ->
				if (readable(fileId) == null) {
					if (!ChatStore.exists(service, fileId)) {
						home.unfollow(ChatHome.fileKey(fileId));
					}
					return;
				}
				threads.add(describeConversation(service, resolve(service, [fileId: fileId])));
			};
			threads.sort { a, b -> (b.lastMessageAt ?: new Date(0)) <=> (a.lastMessageAt ?: new Date(0)); };
			return threads;
		} as List<Map>;
	}

	private Map toMessage(service, Map message) {
		message.authorName = displayName(service, message.author as String, false);
		message.mine = (message.author == userId);
		message.links = (message.remove('linkIds') as List<String>).collect { String id -> describeLink(id) };
		return message;
	}

	/**
	 * The file or folder of that identifier as the caller's own session reads
	 * it, or null when there is none or the caller cannot read it.
	 */
	private Object readable(String id) {
		try {
			def r = session.getResourceByIdentifier(id);
			return (r.exists() && r.canRead()) ? r : null;
		} catch (Throwable ignore) {
			return null;
		}
	}

	/**
	 * A link as the caller sees it. A link gives no access: whoever cannot read
	 * what is linked learns nothing about it, not even its name.
	 */
	private Map describeLink(String id) {
		def r = readable(id);
		if (r == null) {
			return [id: id, accessible: false];
		}
		boolean folder = r.isCollection();
		return [
			id: id,
			accessible: true,
			name: r.name,
			path: r.path,
			mimeType: folder ? null : r.getContentType(),
			isCollection: folder,
		];
	}

	/**
	 * What a message is to attach, read in the caller's session: the files the
	 * caller uploaded into the home for this message, and the repository files to
	 * copy. Each is [name, resource].
	 */
	private List<Map> attachmentSources(String draftId, List<Map> uploads, List<String> copies) {
		List<Map> sources = [];
		(uploads ?: []).each { Map upload ->
			if (!draftId) {
				throw new IllegalArgumentException('Uploads need the draft they were uploaded for.');
			}
			def r = session.getResource("${home.uploadsPath(draftId)}/${ChatStore.checkId(upload.key as String)}".toString());
			if (!r.exists() || r.isCollection()) {
				throw new IllegalArgumentException("The upload is not there: ${upload.name}".toString());
			}
			sources.add([name: upload.name, resource: r]);
		};
		(copies ?: []).each { String id ->
			def r = readable(id);
			if (r == null || r.isCollection()) {
				throw new IllegalArgumentException('A file to attach is not there, or you cannot read it.');
			}
			sources.add([name: r.name, resource: r]);
		};
		return sources;
	}

	/**
	 * The links a message is to carry, as [id, path]. A link already on the
	 * message is kept as it is; a new one must be readable by the caller.
	 */
	private List<Map> linkTargets(List<String> ids, List<Map> existing = []) {
		List<Map> links = [];
		(ids ?: []).unique(false).each { String id ->
			Map kept = existing.find { it.id == id };
			if (kept != null) {
				links.add(kept);
				return;
			}
			def r = readable(id);
			if (r == null) {
				throw new IllegalArgumentException('A file to link is not there, or you cannot read it.');
			}
			links.add([id: id, path: r.path]);
		};
		return links;
	}

	Map listMessages(Map ref, String before, String after, String around, Integer first) {
		[before, after, around].findAll { it }.each { ChatStore.checkMessageId(it as String); };
		return asService { service ->
			Map conversation = resolve(service, ref);
			Map page = ChatMessages.page(service, conversation.root as String, before, after, around, first ?: DEFAULT_PAGE);
			(page.items as List<Map>).each { toMessage(service, it); };
			return page;
		} as Map;
	}

	/**
	 * Posts a message. content: draftId and uploads (files uploaded into the
	 * caller's home for this message), copies (identifiers of repository files to
	 * attach a copy of) and links (identifiers of files or folders to link).
	 */
	Map postMessage(Map ref, String body, Map content = [:]) {
		String draftId = content.draftId as String;
		Map message = asService { service ->
			Map conversation = resolve(service, ref);
			checkCanPost(conversation);
			List<Map> sources = attachmentSources(draftId, content.uploads as List<Map>, content.copies as List<String>);
			List<Map> links = linkTargets(content.links as List<String>);
			List<String> mentions = mentionedUsers(service, body);
			Map posted = ChatMessages.post(service, conversation.root as String, userId, body,
				ChatMessages.KIND_USER, sources, links, mentions);
			changed(service, conversation);
			notifyMentions(service, conversation, posted.id as String, mentions);
			// Whoever posts about a file keeps its conversation in the sidebar.
			if (conversation.kind == 'file') {
				home.follow(conversation.key as String);
			}
			return toMessage(service, posted);
		} as Map;
		home.removeUploads(draftId);
		return message;
	}

	/** The caller's own message in the conversation, which must still be there. */
	private Object ownMessage(service, Map conversation, String messageId) {
		def file = ChatMessages.find(service, conversation.root as String, messageId);
		if (file == null || ChatMessages.isDeleted(file)) {
			throw new IllegalArgumentException('No such message.');
		}
		if (ChatMessages.kind(file) != ChatMessages.KIND_USER || ChatMessages.author(file) != userId) {
			throw new IllegalStateException('Only the author can change a message.');
		}
		return file;
	}

	/**
	 * Changes the caller's own message. content: as for a post, with links
	 * replacing those of the message when given, and removeAttachments naming the
	 * attachments to take off.
	 */
	Map editMessage(Map ref, String messageId, String body, Map content = [:]) {
		String draftId = content.draftId as String;
		Map message = asService { service ->
			Map conversation = resolve(service, ref);
			checkCanPost(conversation);
			def file = ownMessage(service, conversation, messageId);
			List<Map> sources = attachmentSources(draftId, content.uploads as List<Map>, content.copies as List<String>);
			List<Map> links = (content.links != null) ?
				linkTargets(content.links as List<String>, ChatMessages.linksOf(file)) :
				null;
			List<String> mentions = mentionedUsers(service, body);
			List<String> newMentions = mentions.findAll { !ChatMessages.mentionsOf(file).contains(it) };
			Map edited = ChatMessages.edit(service, file, body, sources,
				(content.removeAttachments ?: []) as List<String>, links, mentions);
			changed(service, conversation);
			notifyMentions(service, conversation, messageId, newMentions);
			return toMessage(service, edited);
		} as Map;
		home.removeUploads(draftId);
		return message;
	}

	Map deleteMessage(Map ref, String messageId) {
		return asService { service ->
			Map conversation = resolve(service, ref);
			checkCanPost(conversation);
			Map deleted = ChatMessages.delete(service, ownMessage(service, conversation, messageId));
			changed(service, conversation);
			return toMessage(service, deleted);
		} as Map;
	}

	/**
	 * Hands an attachment of a message to the closure, as the file the service
	 * user reads, after checking that the caller can read the conversation.
	 * Returns what the closure returns, or null when there is no such
	 * attachment. This is how the attachments of a file's conversation are
	 * served: the caller cannot read them in the repository.
	 */
	Object withAttachment(Map ref, String messageId, String name, Closure closure) {
		return asService { service ->
			Map conversation = resolve(service, ref);
			def file = ChatMessages.find(service, conversation.root as String, messageId);
			if (file == null || ChatMessages.isDeleted(file) || name != ChatMessages.cleanName(name)) {
				return null;
			}
			def attachment = ChatMessages.filesFolder(file).getResource(name);
			if (!attachment.exists() || attachment.isCollection()) {
				return null;
			}
			return closure.call(attachment);
		};
	}

	// --- mentions -----------------------------------------------------------------

	/** The users named as @name in the text; a name that is no user is left as text. */
	private List<String> mentionedUsers(service, String body) {
		return ChatMessages.mentionsIn(body).findAll { String name ->
			try {
				return service.userManager.getUser(name) != null;
			} catch (Throwable ignore) {
				return false;
			}
		};
	}

	/** Leaves each mentioned user a mark, except the author. */
	/**
	 * Leaves each mentioned user a mark, except the author and those who are
	 * no participant of the channel: a mention gives no access, and a notice
	 * of a conversation one cannot open would be noise. Who may read the
	 * conversation of a file cannot be told for another user here; the
	 * mentioned user's own listing drops what that user cannot read.
	 */
	private void notifyMentions(service, Map conversation, String messageId, List<String> mentions) {
		Map ref = (conversation.kind == 'file') ? [fileId: conversation.fileId] : [channelId: (conversation.channel as Map).id];
		mentions.findAll { it != userId }.each { String mentioned ->
			if (conversation.kind == 'channel' && !isParticipant(service, (conversation.channel as Map).id as String, mentioned)) {
				return;
			}
			ChatStore.mention(service, mentioned, ref, messageId, userId);
		};
	}

	/** Where the caller's own mention marks are: what a client watches for them. */
	String mentionWatchPath() {
		return ChatStore.mentionRoot(userId);
	}

	// --- search -------------------------------------------------------------------

	/**
	 * Messages whose text contains the words, newest first: those of the
	 * channels the caller takes part in (searched in the caller's session, so
	 * access control applies) and those of the conversations of files the
	 * caller can read (searched as the service user, then kept only where the
	 * caller can read the file). `after` is the cursor of the previous page.
	 */
	Map search(String text, Integer first, String after) {
		List<String> words = (text ?: '').trim().split(/\s+/).findAll { it }.take(5) as List<String>;
		int limit = Math.max(1, Math.min(first ?: SEARCH_PAGE, MAX_SEARCH_PAGE));
		String cursor = after ?: '0';
		int offset = (cursor.isInteger() && (cursor as int) > 0) ? (cursor as int) : 0;
		if (!words) {
			return [items: [], hasMore: false, cursor: null];
		}
		// Each hit: the conversation and the message as stored, read while the
		// context that found it is open.
		List<Map> hits = [];
		boolean more = false;

		Map channelPage = findMessages(context, ChatStore.CHANNELS, words, offset, limit);
		(channelPage.resources as List).each { file ->
			String channelId = segment(file.path as String, ChatStore.CHANNELS);
			if (channelId) {
				hits.add([channelId: channelId, fileId: null, message: ChatMessages.toMessage(file)]);
			}
		};
		more = more || channelPage.hasMore;

		Map filePage = ChatStore.withServiceContext(context) { service ->
			Map page = findMessages(service, ChatStore.FILES, words, offset, limit);
			List<Map> found = [];
			(page.resources as List).each { file ->
				String fileId = segment(file.path as String, ChatStore.FILES);
				if (fileId && readable(fileId) != null) {
					found.add([channelId: null, fileId: fileId, message: ChatMessages.toMessage(file)]);
				}
			};
			return [hits: found, hasMore: page.hasMore];
		} as Map;
		hits.addAll(filePage.hits as List<Map>);
		more = more || filePage.hasMore;

		return asService { service ->
			Map<String, Date> reads = home.reads();
			Map<String, Map> channels = [:];
			List<Map> items = [];
			hits.each { Map hit ->
				Map channel = null;
				if (hit.channelId) {
					String channelId = hit.channelId as String;
					if (!channels.containsKey(channelId)) {
						Map settings = ChatChannels.read(service, channelId);
						// As in the sidebar: an administrator reads every channel, but
						// finds messages in the ones taken part in.
						boolean listed = settings != null &&
							(settings.kind == ChatChannels.PUBLIC || isParticipant(service, channelId, userId));
						channels[channelId] = listed ? describe(service, settings, false, reads) : null;
					}
					channel = channels[channelId];
					if (channel == null) {
						return;
					}
				}
				items.add([
					channelId: hit.channelId,
					fileId: hit.fileId,
					channel: channel,
					file: hit.fileId ? describeFile(readable(hit.fileId as String)) : null,
					message: toMessage(service, hit.message as Map),
				]);
			};
			items.sort { a, b -> ((b.message as Map).postedAt as Date) <=> ((a.message as Map).postedAt as Date); };
			return [items: items, hasMore: more, cursor: more ? String.valueOf(offset + limit) : null];
		} as Map;
	}

	/** A page of the messages under the root that contain every word, newest first, as resources of the given context. */
	private static Map findMessages(scriptContext, String root, List<String> words, int offset, int limit) {
		def xpath = scriptContext.getAttribute('XPath');
		def builder = xpath.newBuilder().append("${root}//*".toString()).append('[@chat:postedAt and not(@chat:deleted=$deleted)');
		builder.variable('deleted', true);
		words.eachWithIndex { String word, int i ->
			builder.append(" and jcr:contains(., \$w${i})".toString()).variable("w${i}".toString(), word);
		};
		builder.append('] order by xs:dateTime(@chat:postedAt) descending');
		def result = builder.build().offset(offset as long).limit(limit as long).execute();
		return [resources: result.resources, hasMore: result.hasMore()];
	}

	/** The first path segment under the root, or null when the path is not under it. */
	private static String segment(String path, String root) {
		if (!path.startsWith(root + '/')) {
			return null;
		}
		String rest = path.substring(root.length() + 1);
		int slash = rest.indexOf('/');
		return slash > 0 ? rest.substring(0, slash) : null;
	}

	// --- what the caller keeps --------------------------------------------------

	/** Remembers that the caller has read the conversation up to now. */
	Date markRead(Map ref) {
		String key = asService { service -> resolve(service, ref).key; } as String;
		return home.markRead(key);
	}

	/** Adds the conversation to the caller's sidebar. */
	boolean follow(Map ref) {
		String key = asService { service -> resolve(service, ref).key; } as String;
		home.follow(key);
		return true;
	}

	/** Works for a conversation the caller can no longer read as well. */
	boolean unfollow(Map ref) {
		String key = ref?.fileId ?
			ChatHome.fileKey(ChatStore.checkId(ref.fileId as String)) :
			ChatHome.channelKey(channelIdOf(ref));
		home.unfollow(key);
		return true;
	}

}
