package webtop.chat;

/**
 * Tells a user, at the desktop, that a message is for them: a direct
 * message, or a message that names them. Each is published as a notice on
 * the Webtop's notification topic to those users alone; the Webtop shows it
 * as a toast and keeps it in its notification center, and a click opens the
 * conversation at the message. (The notice is a notification, not a record:
 * a user who is not connected when it is published never sees it, and finds
 * the message by the unread mark and the mention mark instead.)
 *
 * Nothing is published for an ordinary message in a channel: the channel's
 * unread mark, which the Chat app keeps, is enough there.
 *
 * The texts are given as i18n messages of the Chat app, which the Webtop
 * resolves in each recipient's language; the fallback is English.
 */
class ChatNotices {

	static final String TOPIC = 'webtop/notifications';
	static final int SNIPPET_LENGTH = 140;

	/**
	 * A message was posted. The other user of a direct message is told of it;
	 * the users it names (marked: those left a mention mark, so the author
	 * and non-participants are already out) are told they were mentioned,
	 * unless told already.
	 *
	 * conversation: kind ('channel' or 'file'), with channel (id, kind, title)
	 * or fileId and fileName. posted: the message (id, author, body).
	 */
	static void posted(eventAdmin, session, Map conversation, Map posted, List<String> marked) {
		String author = posted.author as String;
		List<String> told = [];
		if (conversation.kind == 'channel' && (conversation.channel as Map).kind == ChatChannels.DIRECT) {
			List<String> peers = ChatChannels.memberIds(session, (conversation.channel as Map).id as String).findAll { it != author };
			if (peers) {
				String authorName = displayName(session, author);
				publish(eventAdmin, peers, notice(conversation, posted,
					[id: 'app.chat.notify.direct.title', params: [author: authorName], fallback: authorName]));
				told.addAll(peers);
			}
		}
		mentioned(eventAdmin, session, conversation, posted, (marked ?: []).findAll { !told.contains(it) });
	}

	/** A message names users it did not before (an edit): they are told they were mentioned. */
	static void mentioned(eventAdmin, session, Map conversation, Map posted, List<String> marked) {
		String author = posted.author as String;
		List<String> users = (marked ?: []).findAll { it && it != author }.unique(false) as List<String>;
		if (!users) {
			return;
		}
		String authorName = displayName(session, author);
		Map title;
		if (conversation.kind == 'file') {
			String file = (conversation.fileName as String) ?: '';
			title = [id: 'app.chat.notify.mention.file.title', params: [author: authorName, file: file],
				fallback: "${authorName} mentioned you about ${file}".toString()];
		} else {
			String channel = ((conversation.channel as Map).title as String) ?: '';
			title = [id: 'app.chat.notify.mention.channel.title', params: [author: authorName, channel: channel],
				fallback: "${authorName} mentioned you in ${channel}".toString()];
		}
		publish(eventAdmin, users, notice(conversation, posted, title));
	}

	/** The notice: what the Webtop shows, and where a click goes. */
	private static Map notice(Map conversation, Map posted, Map title) {
		Map options;
		String key;
		if (conversation.kind == 'file') {
			options = [fileId: conversation.fileId as String, messageId: posted.id as String];
			key = "chat:file:${conversation.fileId}".toString();
		} else {
			String id = (conversation.channel as Map).id as String;
			options = [channelId: id, messageId: posted.id as String];
			key = "chat:channel:${id}".toString();
		}
		return [
			app: 'chat',
			title: title,
			body: snippet(posted.body as String),
			icon: 'bi-chat-dots',
			options: options,
			// What the notice is about: not raised while the reader has the
			// conversation open, and the newer notice of a conversation
			// replaces the older in the notification center.
			context: key,
			key: key,
		];
	}

	private static void publish(eventAdmin, List<String> recipients, Map notice) {
		if (eventAdmin == null || !recipients) {
			return;
		}
		try {
			eventAdmin.publish(TOPIC, notice, [recipients: recipients]);
		} catch (Throwable ignore) {
			// A notice that cannot be published loses nothing that is kept:
			// the message is there, with its unread and mention marks.
		}
	}

	/** The first line or so of the message, as the toast shows it. */
	static String snippet(String body) {
		String text = (body ?: '').replaceAll(/\s+/, ' ').trim();
		if (text.length() <= SNIPPET_LENGTH) {
			return text;
		}
		return text.substring(0, SNIPPET_LENGTH).trim() + '…';
	}

	private static String displayName(session, String id) {
		try {
			String name = session.userManager.getUser(id)?.displayName;
			return name ?: id;
		} catch (Throwable ignore) {
			return id;
		}
	}

}
