package webtop.mail;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sending through Microsoft Graph: for a Microsoft account whose tenant has
 * SMTP AUTH turned off (the account's sendVia is graph). Reading stays on
 * IMAP; only the way out changes.
 *
 * A message whose MIME fits Graph's request limit goes out in one call,
 * POST /me/sendMail with the MIME in base64. A larger one becomes a draft made
 * from the MIME without its attachments (POST /me/messages), the attachments
 * are added one by one (a small one in a POST, a large one through an upload
 * session) and the draft is sent (POST /me/messages/{id}/send). Either way
 * Exchange files the message into Sent Items itself, so nothing is appended
 * over IMAP; the synchronization brings the copy in, and MailCompose asks for
 * a pass right after sending.
 *
 * The token is the mail bundle's graph audience (Mail.Send) of the linked
 * account; an account linked before that scope existed has to allow the
 * bundle again, which the Connections binding reports as consent required.
 * The endpoint is Graph v1.0 unless clients.yml overrides it (endpoints.graph).
 */
class GraphTransport {

	static final String DEFAULT_ENDPOINT = 'https://graph.microsoft.com/v1.0';
	static final String AUDIENCE = 'graph';
	static final String NAME = 'Microsoft Graph';

	/**
	 * The largest MIME sent in one call. Graph takes a request of 4 MB; base64
	 * adds a third, so this leaves room under either reading of the megabyte.
	 */
	static final long MIME_LIMIT = 2_800_000L;
	/** An attachment up to this size is added with one POST; a larger one through an upload session. */
	static final long SMALL_ATTACHMENT = 2_800_000L;
	/** A piece of an upload session: a multiple of 320 KiB, as Graph requires. */
	static final int CHUNK_SIZE = 12 * 320 * 1024;
	static final Duration TIMEOUT = Duration.ofMinutes(5);

	static final HttpClient HTTP = HttpClient.newBuilder().
			connectTimeout(Duration.ofSeconds(15)).
			followRedirects(HttpClient.Redirect.NORMAL).
			build();

	def JSON;
	String base;
	String token;

	protected GraphTransport(JSON, String base, String token) {
		this.JSON = JSON;
		this.base = base;
		this.token = token;
	}

	/**
	 * For an account that sends through Graph: takes the access token of the
	 * mail bundle's graph audience (ConsentRequiredException when the user has
	 * to link or allow first) and the endpoint.
	 */
	static GraphTransport create(context, Map account) {
		def connections = context.getAttribute('Connections');
		if (connections == null) {
			throw new IllegalStateException('The Connections binding is not available in this context.');
		}
		String provider = account.provider as String;
		String token = connections.accessToken(provider, MailAccounts.BUNDLE, AUDIENCE);
		String base = (connections.endpoint(provider, 'graph', DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT) as String;
		return new GraphTransport(context.getAttribute('JSON'), base, token);
	}

	/** The address of the mailbox the token belongs to: the connection test. */
	String mailbox() {
		Map me = json(request('/me?$select=mail,userPrincipalName').GET().build());
		return (me.mail ?: me.userPrincipalName ?: '?') as String;
	}

	/** Sends a complete MIME message in one call; {@code raw} must be within MIME_LIMIT. */
	void send(File raw) {
		HttpRequest req = request('/me/sendMail').
				header('Content-Type', 'text/plain').
				POST(HttpRequest.BodyPublishers.ofString(Base64.encoder.encodeToString(raw.bytes))).
				build();
		expect(req, 202);
	}

	/**
	 * Sends a message that is too large for one call. {@code raw} is the MIME
	 * without the attachments; each entry of {@code attachments} is a map with
	 * name, mimeType and file (a File holding the content).
	 */
	void send(File raw, List<Map> attachments) {
		Map draft = json(request('/me/messages').
				header('Content-Type', 'text/plain').
				POST(HttpRequest.BodyPublishers.ofString(Base64.encoder.encodeToString(raw.bytes))).
				build());
		String id = draft.id as String;
		if (!id) {
			throw new IOException("${NAME} created no draft.".toString());
		}
		for (Map a : attachments) {
			attach(id, a);
		}
		expect(request("/me/messages/${segment(id)}/send".toString()).
				POST(HttpRequest.BodyPublishers.noBody()).build(), 202);
	}

	private void attach(String id, Map a) {
		File f = a.file as File;
		long size = f.length();
		String name = a.name as String;
		String mimeType = (a.mimeType ?: 'application/octet-stream') as String;
		if (size <= SMALL_ATTACHMENT) {
			Map body = [
				'@odata.type': '#microsoft.graph.fileAttachment',
				name: name,
				contentType: mimeType,
				contentBytes: Base64.encoder.encodeToString(f.bytes),
			];
			json(request("/me/messages/${segment(id)}/attachments".toString()).
					header('Content-Type', 'application/json').
					POST(HttpRequest.BodyPublishers.ofString(JSON.stringify(body) as String)).
					build());
			return;
		}

		Map body = [AttachmentItem: [attachmentType: 'file', name: name, contentType: mimeType, size: size]];
		Map opened = json(request("/me/messages/${segment(id)}/attachments/createUploadSession".toString()).
				header('Content-Type', 'application/json').
				POST(HttpRequest.BodyPublishers.ofString(JSON.stringify(body) as String)).
				build());
		String uploadUrl = opened.uploadUrl as String;
		if (!uploadUrl) {
			throw new IOException("${NAME} opened no upload session for ${name}.".toString());
		}
		// The session URL is pre-authenticated: the pieces carry no bearer token.
		byte[] buffer = new byte[CHUNK_SIZE];
		long offset = 0;
		f.withInputStream { InputStream input ->
			while (offset < size) {
				int want = (int) Math.min(buffer.length, size - offset);
				int read = 0;
				while (read < want) {
					int n = input.read(buffer, read, want - read);
					if (n < 0) {
						throw new IOException("The attachment ${name} ended after ${offset + read} of ${size} bytes.".toString());
					}
					read += n;
				}
				HttpRequest put = HttpRequest.newBuilder(URI.create(uploadUrl)).timeout(Duration.ofMinutes(10)).
						header('Content-Range', "bytes ${offset}-${offset + read - 1}/${size}".toString()).
						PUT(HttpRequest.BodyPublishers.ofByteArray(buffer, 0, read)).
						build();
				HttpResponse<String> response = HTTP.send(put, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (response.statusCode().intdiv(100) != 2) {
					throw refused(response.statusCode(), response.body());
				}
				offset += read;
			}
		};
	}

	// --- HTTP -------------------------------------------------------------------

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder().uri(URI.create(base + path)).timeout(TIMEOUT).
				header('Authorization', 'Bearer ' + token).
				header('Accept', 'application/json');
	}

	private static String segment(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace('+', '%20');
	}

	/** Sends a request whose answer should be {@code status}; anything else is refused. */
	private void expect(HttpRequest req, int status) {
		HttpResponse<String> response = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() != status && response.statusCode().intdiv(100) != 2) {
			throw refused(response.statusCode(), response.body());
		}
	}

	/** Sends a request and parses its JSON answer. */
	private Map json(HttpRequest req) {
		HttpResponse<String> response = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode().intdiv(100) != 2) {
			throw refused(response.statusCode(), response.body());
		}
		String body = response.body();
		if (!body?.trim()) {
			return [:];
		}
		try {
			return JSON.parse(body) as Map;
		} catch (Throwable ex) {
			throw new IOException("${NAME} answered without JSON.".toString());
		}
	}

	/** Graph's refusal in words: its error message when it sent one. */
	private IOException refused(int status, String body) {
		String message = null;
		try {
			def error = (JSON.parse(body) as Map).error;
			if (error instanceof Map) {
				message = (error.message ?: error.code) as String;
			} else if (error != null) {
				message = error.toString();
			}
		} catch (Throwable ignore) {
			// Not JSON.
		}
		if (!message?.trim()) {
			message = (status == 401) ? 'the token was not accepted' :
					(status == 403) ? 'sending was refused (is Mail.Send allowed?)' :
					(status == 413) ? 'the message is too large' : "HTTP ${status}".toString();
		}
		return new IOException("${NAME}: ${message}".toString());
	}

}
