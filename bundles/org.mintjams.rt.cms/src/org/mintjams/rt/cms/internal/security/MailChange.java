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

package org.mintjams.rt.cms.internal.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Calendar;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.mintjams.cms.mail.MailException;
import org.mintjams.cms.mail.MailMessage;
import org.mintjams.cms.mail.MailService;
import org.mintjams.cms.security.UserCredentials;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;

/**
 * A change of a user's email address that waits for a code sent to the new
 * address. The pending change lives in the credential store
 * ({@code /home/credentials/users/<username>/mailChange}), so it is shared by
 * the nodes of a cluster and outlives the browser session; only a salted
 * hash of the code is kept.
 *
 * <p>A code is six digits, valid for {@value #TTL_MILLIS} ms, accepted once,
 * and refused after {@value #MAX_ATTEMPTS} wrong entries. Asking again
 * within {@value #RESEND_MILLIS} ms of the last message sends nothing (the
 * earlier code stays valid).</p>
 */
public final class MailChange {

	public static final String NODE_NAME = "mailChange";
	public static final long TTL_MILLIS = 5L * 60 * 1000;
	public static final long RESEND_MILLIS = 60L * 1000;
	public static final int MAX_ATTEMPTS = 5;
	public static final int CODE_LENGTH = 6;

	private static final SecureRandom RANDOM = new SecureRandom();

	private MailChange() {}

	/** The pending change of a user, read from the credential store. */
	public static final class Pending {
		private final String fMail;
		private final String fSalt;
		private final String fHash;
		private final long fRequestedAt;
		private final long fAttempts;

		Pending(String mail, String salt, String hash, long requestedAt, long attempts) {
			fMail = mail;
			fSalt = salt;
			fHash = hash;
			fRequestedAt = requestedAt;
			fAttempts = attempts;
		}

		public String getMail() {
			return fMail;
		}

		public boolean isExpired() {
			return System.currentTimeMillis() - fRequestedAt > TTL_MILLIS;
		}

		/** Whether a new message may be sent for this address now. */
		public boolean canResend() {
			return System.currentTimeMillis() - fRequestedAt >= RESEND_MILLIS;
		}
	}

	/** The outcome of entering a code. */
	public enum Outcome {
		VERIFIED, INVALID, TOO_MANY_ATTEMPTS, NONE_PENDING, EXPIRED
	}

	/** A fresh code of {@value #CODE_LENGTH} digits. */
	public static String generateCode() {
		int bound = 1;
		for (int i = 0; i < CODE_LENGTH; i++) {
			bound *= 10;
		}
		return String.format("%0" + CODE_LENGTH + "d", RANDOM.nextInt(bound));
	}

	/** The pending change of the user, or null (an expired one is removed). */
	public static Pending read(Session systemSession, String username) throws RepositoryException {
		Node folder = UserCredentials.getUserFolder(systemSession, username);
		if (folder == null || !folder.hasNode(NODE_NAME)) {
			return null;
		}
		Node content = JCRs.getContentNode(folder.getNode(NODE_NAME));
		Pending pending = new Pending(
				content.hasProperty("mail") ? content.getProperty("mail").getString() : null,
				content.hasProperty("salt") ? content.getProperty("salt").getString() : "",
				content.hasProperty("hash") ? content.getProperty("hash").getString() : "",
				content.hasProperty("requestedAt") ? content.getProperty("requestedAt").getDate().getTimeInMillis() : 0,
				content.hasProperty("attempts") ? content.getProperty("attempts").getLong() : 0);
		if (pending.isExpired() || pending.getMail() == null) {
			clear(systemSession, username);
			return null;
		}
		return pending;
	}

	/** Records a code just sent to the new address, replacing any earlier change. */
	public static void start(Session systemSession, String username, String mail, String code) throws RepositoryException {
		Node folder = UserCredentials.getOrCreateUserFolder(systemSession, username);
		Node file = folder.hasNode(NODE_NAME) ? folder.getNode(NODE_NAME) : JCRs.createFile(folder, NODE_NAME);
		Node content = JCRs.getContentNode(file);
		byte[] saltBytes = new byte[16];
		RANDOM.nextBytes(saltBytes);
		String salt = hex(saltBytes);
		content.setProperty("mail", mail);
		content.setProperty("salt", salt);
		content.setProperty("hash", hash(salt, code));
		content.setProperty("requestedAt", Calendar.getInstance());
		content.setProperty("attempts", 0L);
		systemSession.save();
	}

	/**
	 * Checks a code. A verified or exhausted change is removed; a wrong code
	 * counts an attempt. The address of a verified change is returned through
	 * {@code pending} read before the call.
	 */
	public static Outcome verify(Session systemSession, String username, String code) throws RepositoryException {
		Node folder = UserCredentials.getUserFolder(systemSession, username);
		if (folder == null || !folder.hasNode(NODE_NAME)) {
			return Outcome.NONE_PENDING;
		}
		Node content = JCRs.getContentNode(folder.getNode(NODE_NAME));
		long requestedAt = content.hasProperty("requestedAt") ? content.getProperty("requestedAt").getDate().getTimeInMillis() : 0;
		if (System.currentTimeMillis() - requestedAt > TTL_MILLIS) {
			clear(systemSession, username);
			return Outcome.EXPIRED;
		}
		String salt = content.hasProperty("salt") ? content.getProperty("salt").getString() : "";
		String stored = content.hasProperty("hash") ? content.getProperty("hash").getString() : "";
		String entered = code == null ? "" : code.replaceAll("[^0-9]", "");
		boolean matches = !stored.isEmpty() && MessageDigest.isEqual(
				hash(salt, entered).getBytes(StandardCharsets.US_ASCII), stored.getBytes(StandardCharsets.US_ASCII));
		if (matches) {
			clear(systemSession, username);
			return Outcome.VERIFIED;
		}
		long attempts = (content.hasProperty("attempts") ? content.getProperty("attempts").getLong() : 0) + 1;
		if (attempts >= MAX_ATTEMPTS) {
			clear(systemSession, username);
			return Outcome.TOO_MANY_ATTEMPTS;
		}
		content.setProperty("attempts", attempts);
		systemSession.save();
		return Outcome.INVALID;
	}

	/** Forgets the pending change, if any. */
	public static void clear(Session systemSession, String username) throws RepositoryException {
		Node folder = UserCredentials.getUserFolder(systemSession, username);
		if (folder != null && folder.hasNode(NODE_NAME)) {
			folder.getNode(NODE_NAME).remove();
			systemSession.save();
		}
	}

	// ---- messages -------------------------------------------------------------

	/** {@code ja} or {@code en} from a language tag; English otherwise. */
	public static String language(String lang) {
		return (lang != null && lang.trim().toLowerCase().startsWith("ja")) ? "ja" : "en";
	}

	/** The message that carries the confirmation code to the new address. */
	public static MailMessage confirmationCode(String to, String displayName, String code, String lang) {
		int minutes = (int) (TTL_MILLIS / 60000);
		String name = (displayName == null || displayName.isBlank()) ? "" : displayName.trim();
		if ("ja".equals(lang)) {
			return MailMessage.to(to)
					.setSubject("メールアドレスの確認コード: " + code)
					.setText((name.isEmpty() ? "" : name + " さん\n\n")
							+ "このメールアドレスをアカウントに登録するための確認コードです。\n\n"
							+ "    " + code + "\n\n"
							+ "有効期間は " + minutes + " 分、使えるのは 1 回です。\n"
							+ "心当たりがなければ、このメールは無視してください。アドレスは登録されません。\n");
		}
		return MailMessage.to(to)
				.setSubject("Your confirmation code: " + code)
				.setText((name.isEmpty() ? "Hello,\n\n" : "Hello " + name + ",\n\n")
						+ "Enter this code to register this email address with your account:\n\n"
						+ "    " + code + "\n\n"
						+ "It expires in " + minutes + " minutes and works once.\n"
						+ "If you did not ask for this, you can ignore this message; the address is not registered.\n");
	}

	/** The notice sent to the address being replaced. */
	public static MailMessage changeNotice(String to, String displayName, String newMail, String lang) {
		String name = (displayName == null || displayName.isBlank()) ? "" : displayName.trim();
		if ("ja".equals(lang)) {
			return MailMessage.to(to)
					.setSubject("メールアドレスの変更が要求されました")
					.setText((name.isEmpty() ? "" : name + " さん\n\n")
							+ "アカウントのメールアドレスを " + newMail + " に変更する要求がありました。\n"
							+ "新しいアドレスで確認コードが入力されると、変更が確定します。\n\n"
							+ "心当たりがなければ、管理者に連絡してください。\n");
		}
		return MailMessage.to(to)
				.setSubject("A change of your email address was requested")
				.setText((name.isEmpty() ? "Hello,\n\n" : "Hello " + name + ",\n\n")
						+ "A request was made to change the email address of your account to " + newMail + ".\n"
						+ "The change takes effect once the code sent to the new address is entered.\n\n"
						+ "If this was not you, please contact your administrator.\n");
	}

	/** Sends a message and reports failure instead of throwing (for notices). */
	public static boolean trySend(MailService mail, MailMessage message) {
		try {
			mail.send(message);
			return true;
		} catch (MailException ex) {
			CmsService.getLogger(MailChange.class).warn("Could not send a notice to " + String.join(", ", message.getTo()), ex);
			return false;
		}
	}

	// ---- hashing --------------------------------------------------------------

	private static String hash(String salt, String code) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return hex(digest.digest((salt + ":" + code).getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

}
