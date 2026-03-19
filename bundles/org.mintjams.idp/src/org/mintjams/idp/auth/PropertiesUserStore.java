/*
 * Copyright (c) 2024 MintJams Inc.
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

package org.mintjams.idp.auth;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Properties;

import org.mintjams.idp.model.IdpUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Properties file based user store for Phase 1 / starter usage.
 *
 * <p>User file format (idp-users.properties):</p>
 * <pre>
 * admin.password.sha256=8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918
 * admin.email=admin@example.com
 * admin.displayName=Administrator
 * admin.roles=administration
 * </pre>
 *
 * <p>Passwords are stored as SHA-256 hex digests.</p>
 */
public class PropertiesUserStore implements UserStore {

	private static final Logger LOG = LoggerFactory.getLogger(PropertiesUserStore.class);

	private final File usersFile;
	private Properties users;
	private long lastModified;

	public PropertiesUserStore(File usersFile) {
		this.usersFile = usersFile;
	}

	/**
	 * Initializes the user store. Creates a default admin user if the file does not exist.
	 */
	public void init() throws IOException {
		if (!usersFile.exists()) {
			createDefaultUsers();
			LOG.info("Created default user store: {}", usersFile.getAbsolutePath());
		}
		loadUsers();
	}

	private void createDefaultUsers() throws IOException {
		usersFile.getParentFile().mkdirs();
		Properties defaults = new Properties();
		defaults.setProperty("admin.password.sha256", sha256Hex("admin"));
		defaults.setProperty("admin.email", "admin@example.com");
		defaults.setProperty("admin.displayName", "Administrator");
		defaults.setProperty("admin.roles", "administration");
		try (FileOutputStream fos = new FileOutputStream(usersFile)) {
			defaults.store(fos, "MintJams IdP User Store - Change default password!");
		}
	}

	private void loadUsers() throws IOException {
		users = new Properties();
		try (FileInputStream fis = new FileInputStream(usersFile)) {
			users.load(fis);
		}
		lastModified = usersFile.lastModified();
	}

	private void reloadIfModified() {
		if (usersFile.lastModified() != lastModified) {
			try {
				loadUsers();
				LOG.info("Reloaded user store: {}", usersFile.getAbsolutePath());
			} catch (IOException e) {
				LOG.warn("Failed to reload user store", e);
			}
		}
	}

	@Override
	public IdpUser authenticate(String username, String password) {
		reloadIfModified();

		String storedHash = users.getProperty(username + ".password.sha256");
		if (storedHash == null) {
			return null;
		}

		String inputHash = sha256Hex(password);
		if (!storedHash.equalsIgnoreCase(inputHash)) {
			return null;
		}

		return buildUser(username);
	}

	@Override
	public IdpUser findUser(String username) {
		reloadIfModified();

		String storedHash = users.getProperty(username + ".password.sha256");
		if (storedHash == null) {
			return null;
		}

		return buildUser(username);
	}

	private IdpUser buildUser(String username) {
		IdpUser user = new IdpUser();
		user.setUsername(username);

		String email = users.getProperty(username + ".email");
		if (email != null && !email.isEmpty()) {
			user.setEmail(email);
		}

		String displayName = users.getProperty(username + ".displayName");
		if (displayName != null && !displayName.isEmpty()) {
			user.setDisplayName(displayName);
		}

		String rolesStr = users.getProperty(username + ".roles", "");
		if (!rolesStr.isEmpty()) {
			Arrays.stream(rolesStr.split(","))
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.forEach(user::addRole);
		}

		return user;
	}

	/**
	 * Computes SHA-256 hex digest of the input string.
	 */
	static String sha256Hex(String input) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : hash) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (Exception e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}

}
