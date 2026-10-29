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

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;

import org.mintjams.cms.mail.MailMessage;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import groovy.text.GStringTemplateEngine;
import groovy.text.Template;

/**
 * Messages written by an administrator, in place of the built-in ones. A
 * template is a file in {@value #FOLDER_PATH} of the {@value #WORKSPACE_NAME}
 * workspace:
 *
 * <pre>
 * ---
 * subject: "Your sign-in code: ${code}"
 * ---
 * Hello ${displayName ?: username},
 *
 * Your sign-in code is ${code}. It expires in ${minutes} minutes.
 * </pre>
 *
 * <p>The file is named {@code <name>.<language>.<extension>}, or
 * {@code <name>.<extension>} for every language. For {@code ja-JP} the files
 * for {@code ja-JP}, {@code ja} and every language are tried in that order;
 * without any the built-in message is sent. The front matter is YAML and is
 * optional (the built-in subject is used without one); the subject and the
 * body are GString templates (as in a GSP) over the caller's variables. The
 * body is sent as the file's {@code jcr:mimeType} in its {@code jcr:encoding}
 * ({@code text/plain} and UTF-8 when not set); in an HTML body the values are
 * HTML-escaped.</p>
 *
 * <p>A template that cannot be read, compiled or rendered is logged and the
 * built-in message is sent, so that a broken file never stops a sign-in
 * code. Compiled templates are kept until their file changes.</p>
 */
public final class MailTemplates {

	public static final String WORKSPACE_NAME = "system";
	public static final String FOLDER_PATH = "/etc/mail/templates";

	private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
	private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*");
	private static final Pattern FRONT_MATTER = Pattern.compile(
			"\\A---[ \\t]*\\r?\\n(.*?)(?:\\r?\\n)?^---[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL | Pattern.MULTILINE);
	private static final String OCTET_STREAM = "application/octet-stream";

	private static final Map<String, Compiled> fCache = new ConcurrentHashMap<>();

	private MailTemplates() {}

	/** See {@link org.mintjams.cms.mail.MailService#applyTemplate}. */
	public static MailMessage apply(String name, String lang, Map<String, Object> variables, MailMessage builtin) {
		if (name == null || !NAME_PATTERN.matcher(name).matches()) {
			throw new IllegalArgumentException("Invalid mail template name: " + name);
		}
		Compiled template;
		try {
			template = find(name, lang);
		} catch (Throwable ex) {
			CmsService.getLogger(MailTemplates.class).warn("The mail template " + name + " could not be read; the built-in message is sent instead.", ex);
			return builtin;
		}
		if (template == null) {
			return builtin;
		}
		try {
			return template.render(variables, builtin);
		} catch (Throwable ex) {
			CmsService.getLogger(MailTemplates.class).warn("The mail template " + template.fPath + " could not be rendered; the built-in message is sent instead.", ex);
			return builtin;
		}
	}

	/**
	 * The base names to look for, most specific first: {@code name.ja-JP},
	 * {@code name.ja}, {@code name}. A language that is not a plain tag is
	 * ignored.
	 */
	static List<String> candidates(String name, String lang) {
		List<String> list = new ArrayList<>();
		String tag = (lang == null) ? "" : lang.trim().replace('_', '-');
		if (LANGUAGE_PATTERN.matcher(tag).matches()) {
			while (!tag.isEmpty()) {
				list.add(name + "." + tag);
				int i = tag.lastIndexOf('-');
				tag = (i < 0) ? "" : tag.substring(0, i);
			}
		}
		list.add(name);
		return list;
	}

	/** Whether {@code fileName} is {@code base} plus one extension (case is ignored). */
	static boolean matches(String fileName, String base) {
		int length = base.length();
		return fileName.length() > length + 1
				&& fileName.regionMatches(true, 0, base, 0, length)
				&& fileName.charAt(length) == '.'
				&& fileName.indexOf('.', length + 1) < 0;
	}

	private static Compiled find(String name, String lang) throws Exception {
		Session session = CmsService.getRepository().login(new CmsServiceCredentials(), WORKSPACE_NAME);
		try {
			if (!session.nodeExists(FOLDER_PATH)) {
				return null;
			}
			Node folder = session.getNode(FOLDER_PATH);
			List<String> fileNames = new ArrayList<>();
			for (NodeIterator i = folder.getNodes(); i.hasNext();) {
				Node child = i.nextNode();
				if (JCRs.isFile(child)) {
					fileNames.add(child.getName());
				}
			}
			Collections.sort(fileNames);
			for (String base : candidates(name, lang)) {
				for (String fileName : fileNames) {
					if (matches(fileName, base)) {
						return compile(folder.getNode(fileName));
					}
				}
			}
			return null;
		} finally {
			session.logout();
		}
	}

	private static Compiled compile(Node file) throws Exception {
		String path = file.getPath();
		Node content = JCRs.getContentNode(file);
		long lastModified = content.hasProperty("jcr:lastModified") ? content.getProperty("jcr:lastModified").getDate().getTimeInMillis() : 0;
		String mimeType = JCRs.getMimeType(file);
		if (mimeType == null || mimeType.isBlank() || mimeType.equalsIgnoreCase(OCTET_STREAM)) {
			mimeType = MailMessage.DEFAULT_MIME_TYPE;
		}
		mimeType = mimeType.split(";")[0].trim().toLowerCase(Locale.ROOT);
		String encoding = JCRs.getEncoding(file);
		if (encoding == null || encoding.isBlank()) {
			encoding = MailMessage.DEFAULT_ENCODING;
		}
		encoding = encoding.trim();

		Compiled cached = fCache.get(path);
		if (cached != null && cached.fLastModified == lastModified
				&& cached.fMimeType.equals(mimeType) && cached.fEncoding.equals(encoding)) {
			return cached;
		}
		if (!mimeType.startsWith("text/")) {
			throw new IllegalArgumentException(path + ": not a text type (" + mimeType + ")");
		}
		if (!Charset.isSupported(encoding)) {
			throw new IllegalArgumentException(path + ": unknown charset (" + encoding + ")");
		}
		String source;
		try (InputStream in = content.getProperty("jcr:data").getBinary().getStream()) {
			source = new String(in.readAllBytes(), Charset.forName(encoding));
		}
		Compiled compiled;
		try {
			compiled = new Compiled(path, lastModified, mimeType, encoding, source);
		} catch (Exception ex) {
			throw new IllegalArgumentException(path + ": " + ex.getMessage(), ex);
		}
		fCache.put(path, compiled);
		return compiled;
	}

	private static String escapeHtml(String value) {
		StringBuilder sb = new StringBuilder(value.length() + 16);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '&': sb.append("&amp;"); break;
			case '<': sb.append("&lt;"); break;
			case '>': sb.append("&gt;"); break;
			case '"': sb.append("&quot;"); break;
			case '\'': sb.append("&#39;"); break;
			default: sb.append(c);
			}
		}
		return sb.toString();
	}

	private static final class Compiled {
		private final String fPath;
		private final long fLastModified;
		private final String fMimeType;
		private final String fEncoding;
		/** Null when the front matter names no subject. */
		private final Template fSubject;
		private final Template fBody;

		private Compiled(String path, long lastModified, String mimeType, String encoding, String source) throws Exception {
			fPath = path;
			fLastModified = lastModified;
			fMimeType = mimeType;
			fEncoding = encoding;

			String text = source.startsWith("﻿") ? source.substring(1) : source;
			String subject = null;
			Matcher m = FRONT_MATTER.matcher(text);
			if (m.lookingAt()) {
				Object parsed = new Load(LoadSettings.builder().build()).loadFromString(m.group(1));
				if (parsed != null && !(parsed instanceof Map)) {
					throw new IllegalArgumentException("the front matter is not a mapping");
				}
				if (parsed != null) {
					for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) {
						if (String.valueOf(e.getKey()).equalsIgnoreCase("subject") && e.getValue() != null) {
							if (e.getValue() instanceof Map || e.getValue() instanceof List) {
								throw new IllegalArgumentException("the subject is not a text");
							}
							subject = String.valueOf(e.getValue());
						}
					}
				}
				text = text.substring(m.end());
			}

			GStringTemplateEngine engine = new GStringTemplateEngine();
			fSubject = (subject == null) ? null : engine.createTemplate(subject);
			fBody = engine.createTemplate(text);
		}

		private MailMessage render(Map<String, Object> variables, MailMessage builtin) {
			String subject = builtin.getSubject();
			if (fSubject != null) {
				String rendered = fSubject.make(values(variables, false)).toString().replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
				if (!rendered.isEmpty()) {
					subject = rendered;
				}
			}
			String body = fBody.make(values(variables, fMimeType.equals("text/html"))).toString();
			return MailMessage.toRecipientsOf(builtin).setSubject(subject).setText(body, fMimeType, fEncoding);
		}

		/** A fresh copy for one rendering: null becomes "", text is escaped for HTML. */
		private static Map<String, Object> values(Map<String, Object> variables, boolean html) {
			Map<String, Object> values = new LinkedHashMap<>();
			if (variables != null) {
				for (Map.Entry<String, Object> e : variables.entrySet()) {
					Object value = e.getValue();
					if (value == null) {
						value = "";
					} else if (html && value instanceof CharSequence) {
						value = escapeHtml(value.toString());
					}
					values.put(e.getKey(), value);
				}
			}
			return values;
		}
	}

}
