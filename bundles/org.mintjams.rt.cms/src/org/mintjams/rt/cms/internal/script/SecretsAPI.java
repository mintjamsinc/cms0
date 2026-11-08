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

package org.mintjams.rt.cms.internal.script;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.RepositoryException;

import org.mintjams.cms.config.SecretsFile;
import org.mintjams.cms.security.Encryptors;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.secrets.WorkspaceSecrets;
import org.mintjams.script.ScriptingContext;

/**
 * Reads the secrets of the script's workspace, {@code /etc/security/secrets.yml}.
 *
 * <pre>
 * SecretsAPI.has('SMTP_PASSWORD')     // whether the key is set
 * SecretsAPI.get('SMTP_PASSWORD')     // the value, decrypted
 * SecretsAPI.resolve(text)            // text with every ${secrets.KEY} replaced
 * </pre>
 *
 * <p>The file is read by the platform with a service session, whatever the
 * script's caller may access, and only the file of the script's own
 * workspace: neither the server's {@code etc/secrets.yml} nor another
 * workspace's file. There is deliberately no way to write: values are set by
 * saving the file.
 *
 * <p>No message carries a value; the key, the workspace and the path are what
 * an error names.
 */
public class SecretsAPI {

	private static final String SECRETS_PREFIX = "secrets.";
	private static final Pattern VARIABLE = Pattern.compile("\\$\\{(.+?)\\}");

	private final WorkspaceScriptContext fContext;

	public SecretsAPI(WorkspaceScriptContext context) {
		fContext = context;
	}

	public static SecretsAPI get(ScriptingContext context) {
		return (SecretsAPI) context.getAttribute(SecretsAPI.class.getSimpleName());
	}

	/** Whether the file has a set (non-empty, usable) value for the key. */
	public boolean has(String key) throws RepositoryException, IOException {
		WorkspaceSecrets.Snapshot snapshot = snapshot();
		if (!snapshot.exists() || snapshot.getFile().getError() != null) {
			return false;
		}
		SecretsFile.Entry entry = snapshot.getFile().get(key);
		return entry != null && entry.getProblem() == null && !entry.isEmpty();
	}

	/**
	 * The value of a secret, decrypted when the file holds it encrypted.
	 *
	 * @throws IllegalArgumentException when the file or the key is missing,
	 *         the key is empty, or the entry does not fit the format
	 * @throws IllegalStateException when the value cannot be decrypted
	 */
	public String get(String key) throws RepositoryException, IOException {
		if (key == null || key.trim().isEmpty()) {
			throw new IllegalArgumentException("No secret is named.");
		}
		key = key.trim();
		String where = describe();
		WorkspaceSecrets.Snapshot snapshot = snapshot();
		if (!snapshot.exists()) {
			throw new IllegalArgumentException("The secret " + key + " is referenced, but there is no " + where + ".");
		}
		SecretsFile file = snapshot.getFile();
		if (file.getError() != null) {
			throw new IllegalArgumentException("The secret " + key + " cannot be read: " + where + " " + file.getError() + ".");
		}
		SecretsFile.Entry entry = file.get(key);
		if (entry == null) {
			throw new IllegalArgumentException("The secret " + key + " is not in " + where + ".");
		}
		if (entry.getProblem() != null) {
			throw new IllegalArgumentException("The secret " + key + " in " + where + " " + entry.getProblem() + ".");
		}
		if (entry.isEmpty()) {
			throw new IllegalArgumentException("The secret " + key + " in " + where + " is empty.");
		}
		if (!entry.isEncrypted()) {
			return entry.getValue();
		}
		try {
			return Encryptors.getDefault().decrypt(entry.getValue());
		} catch (Throwable ex) {
			// The cause carries no value either: see DefaultEncryptor.
			throw new IllegalStateException("The secret " + key + " in " + where + " could not be decrypted;"
					+ " is the secret key the one it was encrypted with?", ex);
		}
	}

	/**
	 * Replaces every {@code ${secrets.KEY}} in the text with the value of the
	 * key. Any other {@code ${...}} is left as it is, and a value is not
	 * resolved again.
	 *
	 * @throws IllegalArgumentException when a referenced key is missing or empty
	 * @throws IllegalStateException when a referenced value cannot be decrypted
	 */
	public String resolve(String text) throws RepositoryException, IOException {
		if (text == null || !text.contains("${" + SECRETS_PREFIX)) {
			return text;
		}
		Matcher m = VARIABLE.matcher(text);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String name = m.group(1).trim();
			String replacement = name.startsWith(SECRETS_PREFIX) ? get(name.substring(SECRETS_PREFIX.length())) : m.group(0);
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private WorkspaceSecrets.Snapshot snapshot() throws RepositoryException, IOException {
		WorkspaceSecrets service = CmsService.getWorkspaceSecrets(fContext.getWorkspaceName());
		if (service != null) {
			return service.getSnapshot();
		}
		// The workspace's services are not up yet (content deployment runs
		// scripts before them): read the file as it is now.
		return WorkspaceSecrets.read(fContext.getWorkspaceName());
	}

	private String describe() {
		return fContext.getWorkspaceName() + ":" + WorkspaceSecrets.FILE_PATH;
	}

}
