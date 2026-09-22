import org.mintjams.tools.mail.Message;

/*
 * Attachment download for the Mail app.
 *
 * GET attachment.groovy?id=<message path>&index=<attachment index>[&inline=1]
 * GET attachment.groovy?id=<message path>&raw=1
 *
 * The mail lives in the caller's home in the system workspace, so the app calls
 * the copy of this script in the system workspace
 * (/bin/cms.cgi/system/usr/share/webtop/apps/mail/attachment.groovy) whichever
 * workspace it runs in. The message must be one of the caller's own
 * (webtop.mail.MailApi.messageResource); the caller's session reads it, so the
 * repository's access control applies as well.
 *
 * raw=1 returns the message as stored (.eml). inline=1 lets the browser show
 * images and PDF files; anything else is always a download, so an attached HTML
 * file never runs on this origin.
 */

def INLINE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'application/pdf'];

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

def api;
def file;
try {
	api = webtop.mail.MailApi.create(context);
	file = api.messageResource(request.getParameter('id'));
} catch (IllegalStateException ex) {
	response.setStatus(401);
	return;
} catch (IllegalArgumentException ex) {
	response.setStatus(400);
	return;
}
if (file == null) {
	response.setStatus(404);
	return;
}

if (request.getParameter('raw') == '1') {
	String subject = file.hasProperty('mail:subject') ? file.getProperty('mail:subject').getString() : '';
	String name = (subject ?: 'message').replaceAll(/[\\\/:*?"<>|\x00-\x1F\x7F]/, '_').trim();
	if (name.length() > 100) {
		name = name.substring(0, 100);
	}
	response.setContentType('message/rfc822');
	response.setHeader('Content-Disposition', contentDisposition('attachment', "${name ?: 'message'}.eml".toString()));
	file.getContentAsStream().withCloseable { input ->
		response.outputStream << input;
	};
	return;
}

int index;
try {
	index = Integer.parseInt(request.getParameter('index') ?: '');
} catch (NumberFormatException ex) {
	response.setStatus(400);
	return;
}

Message m = Message.from(file.getContentAsStream());
try {
	def attachments = m.getAttachments();
	if (index < 0 || index >= attachments.length) {
		response.setStatus(404);
		return;
	}
	def a = attachments[index];
	String mimeType = a.getMimeType();
	boolean inline = request.getParameter('inline') == '1' && INLINE_TYPES.contains(mimeType);
	response.setContentType(inline ? mimeType : 'application/octet-stream');
	response.setHeader('Content-Disposition', contentDisposition(inline ? 'inline' : 'attachment', a.getFilename()));
	a.getInputStream().withCloseable { input ->
		response.outputStream << input;
	};
} finally {
	m.close();
}
