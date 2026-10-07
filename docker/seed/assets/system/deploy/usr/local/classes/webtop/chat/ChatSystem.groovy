package webtop.chat;

/**
 * Posting to a conversation from a script: an EIP route, the service task of a
 * process, a scheduled job. There is no caller whose rights to check; the
 * message is written as the chat service user and marked `system`, so the
 * conversation shows it as a notice rather than as somebody's words.
 *
 *     webtop.chat.ChatSystem.post(ScriptAPI, [channelId: 'orders'],
 *         'Refund for order #4711 completed.');
 *
 *     webtop.chat.ChatSystem.post(ScriptAPI, [fileId: node.identifier], null, [
 *         card: [path: '/etc/chat/cards/notice',
 *                fields: [title: 'Refund completed', level: 'success',
 *                         message: 'Order #4711 was refunded in full.']],
 *     ]);
 *
 * `ScriptAPI` is the binding every script has. The reference names a channel
 * (channelId) or the conversation of a file (fileId). The text may be empty
 * when a card is given: the design's summary stands in for it.
 *
 * Options:
 *   card     [path, fields, locale]: a design under /etc/chat/cards and the
 *            values of its fields, checked against the design (ChatCards);
 *            the summary that stands in for an empty text is written in the
 *            locale, English when none is given
 *   links    identifiers of files or folders to link
 *   author   the user shown as the author; the service user when absent
 *   kind     `system` unless given
 *
 * Whoever the text names as @user is told, as for any message, if they take
 * part in the channel. Nothing is posted to an archived channel.
 */
class ChatSystem {

	static Map post(scriptAPI, Map ref, String body, Map options = [:]) {
		def service = scriptAPI.createServiceUserContext(ChatStore.SERVICE_USER);
		try {
			def session = service.session;
			Map conversation = resolve(session, ref);
			Map card = cardOf(service, session, options?.card as Map);
			List<Map> links = linksOf(session, options?.links as List);
			String author = (options?.author as String) ?: ChatStore.SERVICE_USER;
			String kind = (options?.kind as String) ?: ChatMessages.KIND_SYSTEM;
			String text = body ?: '';
			List<String> mentions = ChatMessages.mentionsIn(text).findAll { String name ->
				try {
					return session.userManager.getUser(name) != null;
				} catch (Throwable ignore) {
					return false;
				}
			};

			Map posted = ChatMessages.post(session, conversation.root as String, author, text, kind, [], links, mentions, card);
			if (conversation.kind == 'file') {
				ChatStore.signal(session, conversation.fileId as String);
			}
			Map target = (conversation.kind == 'file') ? [fileId: conversation.fileId] : [channelId: conversation.channelId];
			List<String> told = [];
			mentions.findAll { it != author }.each { String mentioned ->
				if (conversation.kind == 'channel' && !ChatApi.isParticipant(session, conversation.channelId as String, mentioned)) {
					return;
				}
				ChatStore.mention(session, mentioned, target, posted.id as String, author);
				told.add(mentioned);
			};
			ChatNotices.posted(service.getAttribute('EventAdminAPI'), session, conversation, posted, told);
			return posted;
		} finally {
			service.close();
		}
	}

	/** Where the conversation is kept. A channel must exist and take posts; a file must exist. */
	private static Map resolve(session, Map ref) {
		if (ref?.channelId && ref?.fileId) {
			throw new IllegalArgumentException('Name the conversation by channelId or fileId, not both.');
		}
		if (ref?.fileId) {
			String fileId = ChatStore.checkId(ref.fileId as String);
			if (!ChatStore.exists(session, fileId)) {
				throw new IllegalArgumentException("No such file: ${fileId}".toString());
			}
			String fileName = '';
			try {
				fileName = session.getResourceByIdentifier(fileId).name;
			} catch (Throwable ignore) {}
			return [kind: 'file', fileId: fileId, fileName: fileName, root: ChatStore.fileRoot(fileId)];
		}
		if (!ref?.channelId) {
			throw new IllegalArgumentException('Name the conversation by channelId or fileId.');
		}
		Map channel = ChatChannels.read(session, ref.channelId as String);
		if (channel == null) {
			throw new IllegalArgumentException("No such channel: ${ref.channelId}".toString());
		}
		if (channel.archived) {
			throw new IllegalStateException('The channel is archived.');
		}
		return [kind: 'channel', channelId: channel.id, channel: channel, root: channel.path];
	}

	/** The card to post, checked against its design, or null. */
	private static Map cardOf(service, session, Map input) {
		if (input == null) {
			return null;
		}
		Map design = ChatCards.read(service, session, input.path as String);
		if (design == null) {
			throw new IllegalArgumentException("No such card: ${input.path}".toString());
		}
		return ChatCards.toPost(service, session, design, input.fields as Map, input.locale as String);
	}

	/** The links to carry, [id, path] each; what is linked must exist. */
	private static List<Map> linksOf(session, List ids) {
		List<Map> links = [];
		(ids ?: []).collect { it as String }.unique(false).each { String id ->
			def r = session.getResourceByIdentifier(id);
			if (!r.exists()) {
				throw new IllegalArgumentException("No such file to link: ${id}".toString());
			}
			links.add([id: id, path: r.path]);
		};
		return links;
	}

}
