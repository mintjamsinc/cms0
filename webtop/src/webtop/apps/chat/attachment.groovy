/*
 * Attachment download for conversations.
 *
 * GET attachment.groovy?fileId=<identifier>&messageId=<id>&name=<name>[&inline=1]
 * GET attachment.groovy?channelId=<id>&messageId=<id>&name=<name>[&inline=1]
 *
 * The attachments of a file's conversation lie where nobody is granted
 * anything, so they cannot be fetched from the repository. This script checks,
 * in the caller's own session, that the caller can read the conversation
 * (webtop.chat.ChatApi.withAttachment) and then serves the attachment as the
 * chat service user. The attachments of a channel are readable by its
 * participants and are usually fetched directly; they are served here as well.
 *
 * inline=1 lets the browser show images; anything else is always a download,
 * so an attached HTML file never runs on this origin.
 */

def INLINE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'];

def contentDisposition = { String kind, String filename ->
	String ascii = filename.replaceAll(/[^\x20-\x7E]/, '_').replace('"', '_').replace('\\', '_');
	String encoded = URLEncoder.encode(filename, 'UTF-8').replace('+', '%20');
	return "${kind}; filename=\"${ascii}\"; filename*=UTF-8''${encoded}".toString();
};

response.setHeader('Cache-Control', 'private, no-store');
response.setHeader('X-Content-Type-Options', 'nosniff');

if (request.getMethod() != 'GET') {
	response.setStatus(405);
	return;
}

String name = request.getParameter('name');
String messageId = request.getParameter('messageId');
Map ref = request.getParameter('fileId') ?
	[fileId: request.getParameter('fileId')] :
	[channelId: request.getParameter('channelId')];
if (!name || !messageId) {
	response.setStatus(400);
	return;
}

try {
	def served = webtop.chat.ChatApi.create(context).withAttachment(ref, messageId, name) { attachment ->
		String mimeType = attachment.getContentType() ?: 'application/octet-stream';
		boolean inline = request.getParameter('inline') == '1' && INLINE_TYPES.contains(mimeType);
		response.setContentType(inline ? mimeType : 'application/octet-stream');
		response.setHeader('Content-Disposition', contentDisposition(inline ? 'inline' : 'attachment', attachment.name as String));
		attachment.getContentAsStream().withCloseable { input ->
			response.outputStream << input;
		};
		return true;
	};
	if (!served) {
		response.setStatus(404);
	}
} catch (IllegalStateException ex) {
	response.setStatus(401);
} catch (IllegalArgumentException ex) {
	// No such conversation, or the caller cannot read it: the two are not told apart.
	response.setStatus(404);
}
