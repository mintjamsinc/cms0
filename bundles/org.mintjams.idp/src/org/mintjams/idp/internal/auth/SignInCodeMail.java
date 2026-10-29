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

package org.mintjams.idp.internal.auth;

import java.security.SecureRandom;

import org.mintjams.cms.mail.MailMessage;

/**
 * The message that carries a sign-in code, in English or Japanese.
 */
public final class SignInCodeMail {

	/** Digits in a code. */
	public static final int CODE_LENGTH = 6;

	private static final SecureRandom RANDOM = new SecureRandom();

	private SignInCodeMail() {}

	/** A fresh code: {@value #CODE_LENGTH} decimal digits, zero-padded. */
	public static String generateCode() {
		int bound = 1;
		for (int i = 0; i < CODE_LENGTH; i++) {
			bound *= 10;
		}
		return String.format("%0" + CODE_LENGTH + "d", RANDOM.nextInt(bound));
	}

	/**
	 * Picks {@code ja} or {@code en} from a language tag or an
	 * {@code Accept-Language} header; English when neither says Japanese.
	 */
	public static String language(String preferred, String acceptLanguage) {
		if (isJapanese(preferred)) {
			return "ja";
		}
		if (preferred == null || preferred.isBlank()) {
			if (acceptLanguage != null) {
				String first = acceptLanguage.split(",")[0].split(";")[0].trim();
				if (isJapanese(first)) {
					return "ja";
				}
			}
		}
		return "en";
	}

	private static boolean isJapanese(String tag) {
		return tag != null && tag.trim().toLowerCase().startsWith("ja");
	}

	/**
	 * The sign-in code message.
	 *
	 * @param to          the user's registered address
	 * @param displayName the name to greet the user by (the username when blank)
	 * @param code        the code
	 * @param minutes     how long the code stays valid
	 * @param issuer      the name signed under the message (the TOTP issuer)
	 * @param lang        {@code ja} or {@code en}
	 */
	public static MailMessage signInCode(String to, String displayName, String code, int minutes, String issuer, String lang) {
		String name = (displayName == null || displayName.isBlank()) ? "" : displayName.trim();
		if ("ja".equals(lang)) {
			return MailMessage.to(to)
					.setSubject("サインイン用の認証コード: " + code)
					.setText((name.isEmpty() ? "" : name + " さん\n\n")
							+ "サインイン用の認証コードです。\n\n"
							+ "    " + code + "\n\n"
							+ "有効期間は " + minutes + " 分、使えるのは 1 回です。\n"
							+ "サインインを試みた覚えがなければ、このメールは無視してください。\n\n"
							+ issuer + "\n");
		}
		return MailMessage.to(to)
				.setSubject("Your sign-in code: " + code)
				.setText((name.isEmpty() ? "Hello,\n\n" : "Hello " + name + ",\n\n")
						+ "Your sign-in code is:\n\n"
						+ "    " + code + "\n\n"
						+ "It expires in " + minutes + " minutes and works once.\n"
						+ "If you did not try to sign in, you can ignore this message.\n\n"
						+ issuer + "\n");
	}

	/**
	 * Sent instead of a code when the account's address has not been
	 * confirmed by its owner. It carries nothing that could be entered
	 * anywhere.
	 *
	 * @param to          the user's registered, unconfirmed address
	 * @param displayName the name to greet the user by
	 * @param issuer      the name signed under the message (the TOTP issuer)
	 * @param lang        {@code ja} or {@code en}
	 */
	public static MailMessage unconfirmedNotice(String to, String displayName, String issuer, String lang) {
		String name = (displayName == null || displayName.isBlank()) ? "" : displayName.trim();
		if ("ja".equals(lang)) {
			return MailMessage.to(to)
					.setSubject("メールアドレスの確認が必要です")
					.setText((name.isEmpty() ? "" : name + " さん\n\n")
							+ "このアドレスで認証コードによるサインインが要求されましたが、"
							+ "アドレスの確認がまだ済んでいないため、認証コードは送っていません。\n\n"
							+ "パスワードまたはパスキーでサインインし、環境設定のプロフィールでこのアドレスを確認すると、"
							+ "次回から認証コードが届きます。どちらも使えない場合は管理者に連絡してください。\n\n"
							+ "サインインを試みた覚えがなければ、このメールは無視してください。\n\n"
							+ issuer + "\n");
		}
		return MailMessage.to(to)
				.setSubject("Your email address needs to be confirmed")
				.setText((name.isEmpty() ? "Hello,\n\n" : "Hello " + name + ",\n\n")
						+ "A sign-in code was requested for this address, but no code was sent: "
						+ "the address has not been confirmed yet.\n\n"
						+ "Sign in with your password or a passkey and confirm this address under "
						+ "Preferences > Profile; sign-in codes will reach you from then on. "
						+ "If neither is possible, please contact your administrator.\n\n"
						+ "If you did not try to sign in, you can ignore this message.\n\n"
						+ issuer + "\n");
	}

}
