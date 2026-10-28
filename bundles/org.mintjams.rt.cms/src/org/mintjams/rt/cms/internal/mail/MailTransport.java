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

package org.mintjams.rt.cms.internal.mail;

import java.util.Date;
import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.mintjams.cms.mail.MailException;
import org.mintjams.cms.mail.MailMessage;
import org.mintjams.cms.mail.MailService;
import org.mintjams.rt.cms.internal.CmsService;

/**
 * The platform's {@link MailService}: SMTP as configured in
 * {@code etc/mail.yml}. The configuration is read on every call, so placing
 * or fixing the file takes effect without a restart.
 */
public class MailTransport implements MailService {

	@Override
	public boolean isAvailable() {
		return MailConfiguration.get().isAvailable();
	}

	@Override
	public String getFromAddress() {
		MailConfiguration config = MailConfiguration.get();
		return config.isAvailable() ? config.getFrom() : null;
	}

	@Override
	public void send(MailMessage message) throws MailException {
		MailConfiguration config = MailConfiguration.get();
		if (!config.isAvailable()) {
			throw new MailException(MailException.NOT_CONFIGURED,
					config.getError() != null ? config.getError() : "No mail transport is configured (etc/" + MailConfiguration.FILE_NAME + ").");
		}
		if (message == null || message.getTo().isEmpty()) {
			throw new MailException(MailException.INVALID_MESSAGE, "A recipient is required.");
		}
		if (message.getSubject() == null || message.getSubject().isBlank()) {
			throw new MailException(MailException.INVALID_MESSAGE, "A subject is required.");
		}
		if (message.getText() == null) {
			throw new MailException(MailException.INVALID_MESSAGE, "A body is required.");
		}

		InternetAddress from;
		InternetAddress[] to = new InternetAddress[message.getTo().size()];
		try {
			InternetAddress[] parsed = InternetAddress.parse(config.getFrom(), false);
			if (parsed.length != 1) {
				throw new MailException(MailException.NOT_CONFIGURED, "'from' must be a single address: " + config.getFrom());
			}
			from = parsed[0];
			for (int i = 0; i < to.length; i++) {
				to[i] = new InternetAddress(message.getTo().get(i), true);
			}
		} catch (AddressException ex) {
			throw new MailException(MailException.INVALID_MESSAGE, "Invalid address: " + ex.getMessage(), ex);
		}

		// javax.mail finds its providers through the context class loader;
		// a caller may run under a workspace or servlet loader that cannot
		// see the javax.mail bundle.
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(Session.class.getClassLoader());
		try {
			Session session = Session.getInstance(properties(config), authenticator(config));
			MimeMessage mime = new MimeMessage(session);
			mime.setFrom(from);
			mime.setRecipients(Message.RecipientType.TO, to);
			mime.setSubject(message.getSubject(), "UTF-8");
			mime.setText(message.getText(), "UTF-8");
			mime.setSentDate(new Date());
			Transport.send(mime);
		} catch (Exception ex) {
			CmsService.getLogger(MailTransport.class).error("Failed to send mail to " + String.join(", ", message.getTo())
					+ " via " + config.getHost() + ":" + config.getPort(), ex);
			throw new MailException(MailException.SEND_FAILED, "The message could not be sent: " + ex.getMessage(), ex);
		} finally {
			thread.setContextClassLoader(previous);
		}
	}

	private static Properties properties(MailConfiguration config) {
		Properties props = new Properties();
		String timeout = String.valueOf(config.getTimeoutSeconds() * 1000L);
		props.put("mail.transport.protocol", "smtp");
		props.put("mail.smtp.host", config.getHost());
		props.put("mail.smtp.port", String.valueOf(config.getPort()));
		props.put("mail.smtp.auth", String.valueOf(config.getUsername() != null));
		props.put("mail.smtp.starttls.enable", String.valueOf(config.isStartTls()));
		props.put("mail.smtp.starttls.required", String.valueOf(config.isStartTls() && !config.isSsl()));
		props.put("mail.smtp.ssl.enable", String.valueOf(config.isSsl()));
		props.put("mail.smtp.connectiontimeout", timeout);
		props.put("mail.smtp.timeout", timeout);
		props.put("mail.smtp.writetimeout", timeout);
		props.put("mail.mime.charset", "UTF-8");
		return props;
	}

	private static Authenticator authenticator(MailConfiguration config) {
		if (config.getUsername() == null) {
			return null;
		}
		String username = config.getUsername();
		String password = config.getPassword() != null ? config.getPassword() : "";
		return new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(username, password);
			}
		};
	}

}
