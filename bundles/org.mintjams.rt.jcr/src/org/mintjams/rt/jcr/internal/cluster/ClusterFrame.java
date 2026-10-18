/*
 * Copyright (c) 2026 MintJams Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.mintjams.rt.jcr.internal.cluster;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * One frame on a connection between cluster nodes: length (4 bytes, the
 * number of bytes that follow), kind (1), request id (4), body, and an
 * HMAC-SHA256 (32) over the kind, the request id and the body. The body is a
 * UTF-8 JSON object that always carries the sender's node id and the time it
 * was sent, so the signature covers both.
 */
final class ClusterFrame {

	/** First frame on a connection: who opened it. */
	static final byte HELLO = 0;
	/** "Read the journal of this workspace now"; no answer. */
	static final byte WAKE = 1;
	/** An event to post on the receiving node; no answer. */
	static final byte SIGNAL = 2;
	/** Values written to a replicated table; no answer. */
	static final byte PUT = 3;
	/** Values removed from a replicated table; no answer. */
	static final byte REMOVE = 4;
	/** A request; answered by a {@link #RESPONSE} with the same id. */
	static final byte REQUEST = 5;
	static final byte RESPONSE = 6;

	/** The largest body a frame may carry. */
	static final int MAX_BODY_BYTES = 64 * 1024;

	private static final int HEADER_BYTES = 1 + 4;
	private static final int MAC_BYTES = 32;

	final byte fKind;
	final int fRequestId;
	final byte[] fBody;

	ClusterFrame(byte kind, int requestId, byte[] body) {
		fKind = kind;
		fRequestId = requestId;
		fBody = body;
	}

	static String kindName(byte kind) {
		switch (kind) {
		case HELLO:
			return "hello";
		case WAKE:
			return "wake";
		case SIGNAL:
			return "signal";
		case PUT:
			return "put";
		case REMOVE:
			return "remove";
		case REQUEST:
			return "request";
		case RESPONSE:
			return "response";
		default:
			return "unknown(" + kind + ")";
		}
	}

	/**
	 * Signs and verifies frames with the key derived for the transport. Not
	 * thread-safe; each connection side uses its own.
	 */
	static final class Codec {
		private final Mac fMac;

		Codec(byte[] key) throws IOException {
			try {
				fMac = Mac.getInstance("HmacSHA256");
				fMac.init(new SecretKeySpec(key, "HmacSHA256"));
			} catch (GeneralSecurityException ex) {
				throw new IOException("The frame signature could not be initialized.", ex);
			}
		}

		void write(DataOutputStream out, ClusterFrame frame) throws IOException {
			if (frame.fBody.length > MAX_BODY_BYTES) {
				throw new IOException("The frame body exceeds " + MAX_BODY_BYTES + " bytes: " + frame.fBody.length);
			}
			ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
			header.put(frame.fKind).putInt(frame.fRequestId);
			fMac.reset();
			fMac.update(header.array());
			fMac.update(frame.fBody);
			byte[] signature = fMac.doFinal();

			out.writeInt(HEADER_BYTES + frame.fBody.length + MAC_BYTES);
			out.write(header.array());
			out.write(frame.fBody);
			out.write(signature);
			out.flush();
		}

		/**
		 * Reads the next frame. Returns {@code null} at the end of the
		 * stream; throws {@link SignatureException} when the frame is not
		 * signed with this key.
		 */
		ClusterFrame read(DataInputStream in) throws IOException {
			int length;
			try {
				length = in.readInt();
			} catch (EOFException ex) {
				return null;
			}
			if (length < HEADER_BYTES + MAC_BYTES || length > HEADER_BYTES + MAX_BODY_BYTES + MAC_BYTES) {
				throw new SignatureException("Invalid frame length: " + length);
			}
			byte[] data = new byte[length];
			in.readFully(data);

			fMac.reset();
			fMac.update(data, 0, length - MAC_BYTES);
			byte[] expected = fMac.doFinal();
			byte[] actual = new byte[MAC_BYTES];
			System.arraycopy(data, length - MAC_BYTES, actual, 0, MAC_BYTES);
			if (!MessageDigest.isEqual(expected, actual)) {
				throw new SignatureException("The frame signature does not match.");
			}

			ByteBuffer buffer = ByteBuffer.wrap(data, 0, length - MAC_BYTES);
			byte kind = buffer.get();
			int requestId = buffer.getInt();
			byte[] body = new byte[length - MAC_BYTES - HEADER_BYTES];
			buffer.get(body);
			return new ClusterFrame(kind, requestId, body);
		}
	}

	/**
	 * A frame that is not correctly signed (or not a frame at all): the
	 * connection is dropped.
	 */
	static final class SignatureException extends IOException {
		private static final long serialVersionUID = 1L;

		SignatureException(String message) {
			super(message);
		}
	}

}
