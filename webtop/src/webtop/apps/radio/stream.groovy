import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;

/*
 * Stream relay for the Radio app.
 *
 * GET stream.groovy?url=<http stream url>
 *
 * When the desktop is served over https, the browser refuses to play a
 * station that streams over plain http (mixed content). This script opens
 * the station's stream on the server and copies the audio bytes to the
 * browser unchanged, so the <audio> element sees a same-origin https URL.
 * Only http stations are accepted: an https station needs no relay.
 *
 * The request thread does the work that needs the request: authorisation
 * has already happened, the target is checked, the station is connected
 * and its response headers are read. The response is then switched to
 * asynchronous mode and the copying continues on a small daemon thread, so
 * the request thread and the workspace session it holds are released within
 * a moment while the connection stays open for as long as the listener
 * plays. Nothing on that thread touches the script context: it holds the
 * station socket, the response output stream and the AsyncContext only.
 *
 * No ICY metadata is requested, so the relayed bytes are pure audio; the
 * track title keeps coming from icy.groovy over its own short connection.
 *
 * At most MAX_STREAMS relays run at once per server (a Semaphore kept on
 * the servlet context); beyond that the request is refused with 503.
 */

final int MAX_STREAMS = 8;
final int CONNECT_TIMEOUT_MS = 4000;
final int HEADER_TIMEOUT_MS = 6000;
// Below the HTTP connector's idle timeout (30 s), so a station that stalls
// ends the relay before the connector does, with the socket closed cleanly.
final int STALL_TIMEOUT_MS = 20000;
final int BUFFER_SIZE = 8192;

// Refuse addresses inside the server's own network so the script cannot be
// used to reach hosts the browser could not reach itself.
def isPrivateHost = { String host ->
	if ("localhost".equalsIgnoreCase(host)) {
		return true;
	}
	try {
		for (InetAddress a : InetAddress.getAllByName(host)) {
			if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() ||
					a.isAnyLocalAddress() || a.isMulticastAddress()) {
				return true;
			}
			byte[] b = a.getAddress();
			// 0.0.0.0/8 and 100.64.0.0/10 (carrier-grade NAT)
			if (b.length == 4 && ((b[0] & 0xFF) == 0 || ((b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64))) {
				return true;
			}
		}
	} catch (Throwable ignore) {
		// Unresolvable: let the connect report it.
		return false;
	}
	return false;
};

// Reads one line (up to CRLF) as ISO-8859-1; null at EOF.
def readLine = { InputStream input ->
	ByteArrayOutputStream buf = new ByteArrayOutputStream();
	int c;
	while ((c = input.read()) != -1) {
		if (c == 10) {
			break;
		}
		if (c != 13) {
			buf.write(c);
		}
		if (buf.size() > 8192) {
			throw new IOException("Header line too long");
		}
	}
	if (c == -1 && buf.size() == 0) {
		return null;
	}
	return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
};

def closeQuietly = { Socket socket ->
	if (socket != null) {
		try {
			socket.close();
		} catch (Throwable ignore) {
		}
	}
};

// Connects to the station and reads its response headers. Returns
// [socket, input, contentType] on success, or [error, status] on failure
// (status 400 for a request that is refused, 502 for a station that did
// not answer with a stream); the socket is closed on failure. The initial
// URL must be http; redirects may lead to https.
def open;
open = { URI uri, int redirects ->
	if (redirects > 4) {
		return [error: "too many redirects", status: 502];
	}
	String scheme = (uri.getScheme() == null) ? "" : uri.getScheme().toLowerCase();
	if (scheme != "http" && scheme != "https") {
		return [error: "unsupported scheme", status: 400];
	}
	if (redirects == 0 && scheme != "http") {
		return [error: "only http streams are relayed", status: 400];
	}
	String host = uri.getHost();
	if (host == null || host.isEmpty() || isPrivateHost(host)) {
		return [error: "host not allowed", status: 400];
	}
	int port = (uri.getPort() > 0) ? uri.getPort() : (scheme == "https" ? 443 : 80);
	String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
	if (uri.getRawQuery() != null) {
		path += "?" + uri.getRawQuery();
	}

	Socket socket = null;
	try {
		socket = new Socket();
		socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
		socket.setSoTimeout(HEADER_TIMEOUT_MS);
		if (scheme == "https") {
			SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket(socket, host, port, true);
			SSLParameters params = ssl.getSSLParameters();
			params.setServerNames([new SNIHostName(host)]);
			ssl.setSSLParameters(params);
			ssl.startHandshake();
			socket = ssl;
		}

		OutputStream output = socket.getOutputStream();
		String hostHeader = host + ((uri.getPort() > 0) ? ":" + uri.getPort() : "");
		// HTTP/1.0 without Accept-Encoding: the station answers with the raw
		// audio bytes, neither chunked nor compressed, and closes when done.
		String req = "GET " + path + " HTTP/1.0\r\n" +
				"Host: " + hostHeader + "\r\n" +
				"User-Agent: cms0-radio/1.0\r\n" +
				"Accept: */*\r\n" +
				"Connection: close\r\n\r\n";
		output.write(req.getBytes(StandardCharsets.ISO_8859_1));
		output.flush();

		InputStream input = new BufferedInputStream(socket.getInputStream(), 16384);
		String statusLine = readLine(input);
		if (statusLine == null) {
			closeQuietly(socket);
			return [error: "empty response", status: 502];
		}
		// "HTTP/1.1 200 OK" or the SHOUTcast v1 form "ICY 200 OK".
		String[] parts = statusLine.split(" ", 3);
		int status = (parts.length >= 2 && parts[1].isInteger()) ? parts[1].toInteger() : 0;

		Map<String, String> headers = [:];
		String line;
		while ((line = readLine(input)) != null && !line.isEmpty()) {
			int i = line.indexOf(":");
			if (i > 0) {
				headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
			}
		}

		if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
			String location = headers.get("location");
			closeQuietly(socket);
			if (location == null || location.isEmpty()) {
				return [error: "redirect without location", status: 502];
			}
			return open(uri.resolve(location), redirects + 1);
		}
		if (status != 200) {
			closeQuietly(socket);
			return [error: "HTTP " + status, status: 502];
		}

		String contentType = headers.get("content-type");
		if (contentType == null || contentType.isEmpty()) {
			contentType = "audio/mpeg";
		}
		return [socket: socket, input: input, contentType: contentType];
	} catch (Throwable ex) {
		closeQuietly(socket);
		throw ex;
	}
};

// One Semaphore per server, kept on the servlet context: the script class
// may be recompiled while relays are running, so a static would not do.
def slots = { ->
	synchronized (application) {
		Semaphore s = (Semaphore) application.getAttribute("radio.stream.slots");
		if (s == null) {
			s = new Semaphore(MAX_STREAMS, true);
			application.setAttribute("radio.stream.slots", s);
		}
		return s;
	}
};

def refuse = { int status, String message ->
	response.setStatus(status);
	response.setHeader("Cache-Control", "no-store");
	response.setContentType("text/plain; charset=UTF-8");
	out.print(message);
};

{->
	if (!"GET".equalsIgnoreCase(request.getMethod())) {
		refuse(405, "method not allowed");
		return;
	}

	String raw = request.getParameter("url");
	if (raw == null || raw.trim().isEmpty()) {
		refuse(400, "url is required");
		return;
	}
	URI uri;
	try {
		uri = new URI(raw.trim());
	} catch (Throwable ex) {
		refuse(400, "invalid url");
		return;
	}

	Semaphore semaphore = slots();
	if (!semaphore.tryAcquire()) {
		response.setHeader("Retry-After", "30");
		refuse(503, "too many streams");
		return;
	}
	// Whichever ends the relay first (the copy loop, or the container via
	// the async listener) gives the slot back exactly once.
	AtomicBoolean released = new AtomicBoolean(false);
	def release = { ->
		if (released.compareAndSet(false, true)) {
			semaphore.release();
		}
	};

	Map station;
	try {
		station = open(uri, 0);
	} catch (java.net.SocketTimeoutException ex) {
		release();
		refuse(504, "timeout");
		return;
	} catch (Throwable ex) {
		release();
		log.warn("stream: " + raw + ": " + ex.message);
		refuse(502, (ex.message != null) ? ex.message : ex.class.simpleName);
		return;
	}
	if (station.error != null) {
		release();
		refuse((int) station.status, (String) station.error);
		return;
	}

	Socket socket = (Socket) station.socket;
	InputStream input = (InputStream) station.input;
	try {
		socket.setSoTimeout(STALL_TIMEOUT_MS);
	} catch (Throwable ignore) {
	}

	response.setStatus(200);
	response.setContentType((String) station.contentType);
	response.setHeader("Cache-Control", "no-store");
	response.setHeader("Accept-Ranges", "none");
	// The reverse proxy must pass the bytes through as they arrive.
	response.setHeader("X-Accel-Buffering", "no");

	// From here on the request thread is released: the response stays open
	// under the AsyncContext until complete() is called. The script context
	// is closed as soon as this script returns, so the relay thread must
	// only use what is copied into locals here.
	AsyncContext async = request.startAsync();
	async.setTimeout(0);
	def logger = log;
	def onEnd = { ->
		closeQuietly(socket);
		release();
	};
	async.addListener([
		onComplete: { AsyncEvent e -> onEnd(); },
		onTimeout: { AsyncEvent e -> onEnd(); },
		onError: { AsyncEvent e -> onEnd(); },
		onStartAsync: { AsyncEvent e -> },
	] as AsyncListener);
	// startAsync() must precede committing the response.
	response.flushBuffer();

	OutputStream output = response.getOutputStream();
	String source = raw;
	Thread relay = new Thread({ ->
		byte[] buf = new byte[BUFFER_SIZE];
		try {
			int n;
			while ((n = input.read(buf)) != -1) {
				output.write(buf, 0, n);
				// Flush per chunk: keeps latency low and makes a listener
				// that has gone away fail the write promptly.
				output.flush();
			}
		} catch (Throwable ex) {
			// Expected ends: the listener stopped (write fails), the station
			// stalled (read times out) or dropped the connection.
			logger.debug("stream: " + source + ": " + ex.message);
		} finally {
			onEnd();
			try {
				async.complete();
			} catch (Throwable ignore) {
				// Already completed by the container.
			}
		}
	}, "radio-stream-relay");
	relay.setDaemon(true);
	relay.start();
}();
