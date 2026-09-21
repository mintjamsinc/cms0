import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/*
 * ICY metadata reader for the Radio app.
 *
 * GET icy.groovy?url=<stream url>
 *
 * Browsers cannot read the ICY metadata that Icecast / SHOUTcast interleave
 * into a stream, so the app asks this script. It opens the stream with
 * "Icy-MetaData: 1", reads the response headers and the first metadata block
 * (one interval of audio, normally 16 KB), extracts StreamTitle and closes the
 * connection. Nothing is proxied: the audio itself still flows from the
 * station straight to the browser.
 *
 * Reply (JSON):
 *   { ok: true,  title, name, genre, bitrate, contentType }
 *   { ok: false, error }
 */

// Refuse addresses inside the server's own network so the script cannot be
// used to probe hosts the browser could not reach itself.
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

def skipFully = { InputStream input, long count ->
	byte[] tmp = new byte[8192];
	long left = count;
	while (left > 0) {
		int n = input.read(tmp, 0, (int) Math.min(tmp.length, left));
		if (n < 0) {
			throw new IOException("Stream ended before the metadata block");
		}
		left -= n;
	}
};

def readFully = { InputStream input, int count ->
	byte[] data = new byte[count];
	int off = 0;
	while (off < count) {
		int n = input.read(data, off, count - off);
		if (n < 0) {
			break;
		}
		off += n;
	}
	return (off == count) ? data : Arrays.copyOf(data, off);
};

// Metadata text is nominally Latin-1 but stations commonly send UTF-8.
def decodeMeta = { byte[] data ->
	try {
		return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
	} catch (Throwable ignore) {
		return new String(data, StandardCharsets.ISO_8859_1);
	}
};

// StreamTitle='...'; — the title may itself contain quotes, so take
// everything up to the closing quote that is followed by a semicolon.
def extractTitle = { String meta ->
	int start = meta.indexOf("StreamTitle='");
	if (start < 0) {
		return null;
	}
	start += "StreamTitle='".length();
	int end = meta.indexOf("';", start);
	if (end < 0) {
		end = meta.lastIndexOf("'");
	}
	if (end < start) {
		return null;
	}
	return meta.substring(start, end).trim();
};

def fetch;
fetch = { URI uri, int redirects ->
	if (redirects > 4) {
		return [ok: false, error: "too many redirects"];
	}
	String scheme = (uri.getScheme() == null) ? "" : uri.getScheme().toLowerCase();
	if (scheme != "http" && scheme != "https") {
		return [ok: false, error: "unsupported scheme"];
	}
	String host = uri.getHost();
	if (host == null || host.isEmpty() || isPrivateHost(host)) {
		return [ok: false, error: "host not allowed"];
	}
	int port = (uri.getPort() > 0) ? uri.getPort() : (scheme == "https" ? 443 : 80);
	String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
	if (uri.getRawQuery() != null) {
		path += "?" + uri.getRawQuery();
	}

	Socket socket = null;
	try {
		socket = new Socket();
		socket.connect(new InetSocketAddress(host, port), 4000);
		socket.setSoTimeout(6000);
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
		String req = "GET " + path + " HTTP/1.0\r\n" +
				"Host: " + hostHeader + "\r\n" +
				"Icy-MetaData: 1\r\n" +
				"User-Agent: cms0-radio/1.0\r\n" +
				"Accept: */*\r\n" +
				"Connection: close\r\n\r\n";
		output.write(req.getBytes(StandardCharsets.ISO_8859_1));
		output.flush();

		InputStream input = new BufferedInputStream(socket.getInputStream(), 16384);
		String statusLine = readLine(input);
		if (statusLine == null) {
			return [ok: false, error: "empty response"];
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
			if (location == null || location.isEmpty()) {
				return [ok: false, error: "redirect without location"];
			}
			socket.close();
			socket = null;
			return fetch(uri.resolve(location), redirects + 1);
		}
		if (status != 200) {
			return [ok: false, error: "HTTP " + status];
		}

		def result = [
			ok: true,
			title: null,
			name: headers.get("icy-name"),
			genre: headers.get("icy-genre"),
			bitrate: headers.get("icy-br"),
			contentType: headers.get("content-type"),
		];

		String metaint = headers.get("icy-metaint");
		if (metaint != null && metaint.isInteger()) {
			int interval = metaint.toInteger();
			// One interval of audio must be read before the first block. 1 MB
			// is far above anything real (16 000 is typical) and bounds the
			// download this script does for one request.
			if (interval > 0 && interval <= 1048576) {
				skipFully(input, interval);
				int lengthByte = input.read();
				if (lengthByte > 0) {
					byte[] block = readFully(input, lengthByte * 16);
					result.title = extractTitle(decodeMeta(block));
				}
			}
		}
		return result;
	} finally {
		if (socket != null) {
			try {
				socket.close();
			} catch (Throwable ignore) {
			}
		}
	}
};

{->
	response.setHeader("Cache-Control", "no-store");
	response.setContentType("application/json; charset=UTF-8");

	if (!"GET".equalsIgnoreCase(request.getMethod())) {
		response.setStatus(405);
		out.print(JSON.stringify([ok: false, error: "method not allowed"]));
		return;
	}

	String raw = request.getParameter("url");
	if (raw == null || raw.trim().isEmpty()) {
		response.setStatus(400);
		out.print(JSON.stringify([ok: false, error: "url is required"]));
		return;
	}

	URI uri;
	try {
		uri = new URI(raw.trim());
	} catch (Throwable ex) {
		response.setStatus(400);
		out.print(JSON.stringify([ok: false, error: "invalid url"]));
		return;
	}

	try {
		out.print(JSON.stringify(fetch(uri, 0)));
	} catch (java.net.SocketTimeoutException ex) {
		out.print(JSON.stringify([ok: false, error: "timeout"]));
	} catch (Throwable ex) {
		log.warn("icy: " + raw + ": " + ex.message);
		out.print(JSON.stringify([ok: false, error: (ex.message != null) ? ex.message : ex.class.simpleName]));
	}
}();
