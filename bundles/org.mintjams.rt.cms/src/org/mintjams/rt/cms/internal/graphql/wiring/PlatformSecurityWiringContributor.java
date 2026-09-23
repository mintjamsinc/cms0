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

package org.mintjams.rt.cms.internal.graphql.wiring;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.mintjams.cms.security.UserCredentials;
import org.mintjams.cms.security.mfa.MultiFactorException;
import org.mintjams.cms.security.mfa.MultiFactorService;
import org.mintjams.cms.security.mfa.MultiFactorStatus;
import org.mintjams.cms.security.mfa.PasskeyInfo;
import org.mintjams.cms.security.mfa.TotpEnrollment;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.graphql.GraphQLExecutionContext;
import org.mintjams.rt.cms.internal.security.CmsServiceCredentials;
import org.mintjams.rt.cms.internal.util.ISO8601;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;

/**
 * Wires the account-security part of the platform schema
 * ({@code security-schema.graphqls}): a user's second factors (TOTP with
 * backup codes, passkeys).
 *
 * <p>The contributor decides who may do what and then delegates to the
 * identity provider's {@link MultiFactorService}, which owns the secrets. A
 * user manages their own factors; an administrator may inspect anyone's and
 * remove them (account recovery), but cannot enroll on someone's behalf.
 * Steps that weaken a sign-in or start a new enrollment re-verify the
 * caller's password against the credential store, so a forgotten browser
 * session cannot be used to quietly turn a second factor off or add a
 * passkey.</p>
 *
 * <p>Failures are reported in-band as an {@code errors} list (field/message/
 * code); the code is the {@link MultiFactorException} code, or one of the IdP
 * schema's codes for authorization and input problems.</p>
 */
public final class PlatformSecurityWiringContributor implements WiringContributor {

	private static final String SCHEMA_RESOURCE = "/org/mintjams/rt/cms/internal/graphql/engine/schema/security-schema.graphqls";

	@Override
	public SchemaContribution contribute(String workspaceName) throws Exception {
		return new SchemaContribution()
				.sdl(loadSchema())
				.dataFetcher("Query", "userSecurity", (DataFetcher<Object>) PlatformSecurityWiringContributor::userSecurity)
				.dataFetcher("Mutation", "beginTotpEnrollment",
						(DataFetcher<Object>) PlatformSecurityWiringContributor::beginTotpEnrollment)
				.dataFetcher("Mutation", "confirmTotpEnrollment",
						(DataFetcher<Object>) PlatformSecurityWiringContributor::confirmTotpEnrollment)
				.dataFetcher("Mutation", "disableTotp", (DataFetcher<Object>) PlatformSecurityWiringContributor::disableTotp)
				.dataFetcher("Mutation", "regenerateBackupCodes",
						(DataFetcher<Object>) PlatformSecurityWiringContributor::regenerateBackupCodes)
				.dataFetcher("Mutation", "beginPasskeyRegistration",
						(DataFetcher<Object>) PlatformSecurityWiringContributor::beginPasskeyRegistration)
				.dataFetcher("Mutation", "finishPasskeyRegistration",
						(DataFetcher<Object>) PlatformSecurityWiringContributor::finishPasskeyRegistration)
				.dataFetcher("Mutation", "renamePasskey", (DataFetcher<Object>) PlatformSecurityWiringContributor::renamePasskey)
				.dataFetcher("Mutation", "deletePasskey", (DataFetcher<Object>) PlatformSecurityWiringContributor::deletePasskey);
	}

	// ---- query ----------------------------------------------------------------

	private static Object userSecurity(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		String username = environment.getArgument("username");
		if (username == null || !(isSelf(session, username) || isAdmin(session))) {
			return null;
		}
		MultiFactorService service = service();
		if (service == null) {
			return unavailableStatus(username);
		}
		try {
			return mapStatus(username, service.getStatus(username));
		} catch (MultiFactorException ex) {
			CmsService.getLogger(PlatformSecurityWiringContributor.class).warn("Failed to read the security state of " + username, ex);
			return unavailableStatus(username);
		}
	}

	// ---- TOTP -----------------------------------------------------------------

	private static Object beginTotpEnrollment(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		Map<String, Object> denied = requireSelfWithPassword(session, username, (String) input.get("currentPassword"));
		if (denied != null) {
			return denied;
		}
		MultiFactorService service = service();
		if (service == null) {
			return errorResult("Second factors are not available on this server", "NOT_AVAILABLE");
		}
		try {
			TotpEnrollment enrollment = service.beginTotpEnrollment(username);
			Map<String, Object> result = new HashMap<>();
			result.put("secret", enrollment.getSecret());
			result.put("otpauthUri", enrollment.getOtpauthUri());
			result.put("issuer", enrollment.getIssuer());
			result.put("accountName", enrollment.getAccountName());
			result.put("errors", null);
			return result;
		} catch (MultiFactorException ex) {
			return errorResult(ex.getMessage(), ex.getCode());
		}
	}

	private static Object confirmTotpEnrollment(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		String code = (String) input.get("code");
		if (username == null || code == null) {
			return errorResult("username and code are required", "INVALID_INPUT");
		}
		if (!isSelf(session, username)) {
			return errorResult("Only the user can enroll a second factor", "PERMISSION_DENIED");
		}
		MultiFactorService service = service();
		if (service == null) {
			return errorResult("Second factors are not available on this server", "NOT_AVAILABLE");
		}
		try {
			List<String> codes = service.confirmTotpEnrollment(username, code);
			Map<String, Object> result = new HashMap<>();
			result.put("backupCodes", codes);
			result.put("security", mapStatus(username, service.getStatus(username)));
			result.put("errors", null);
			return result;
		} catch (MultiFactorException ex) {
			return errorResult(ex.getMessage(), ex.getCode());
		}
	}

	private static Object disableTotp(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		Map<String, Object> denied = requireSelfWithPasswordOrAdmin(session, username, (String) input.get("currentPassword"));
		if (denied != null) {
			return denied;
		}
		MultiFactorService service = service();
		if (service == null) {
			return errorResult("Second factors are not available on this server", "NOT_AVAILABLE");
		}
		try {
			service.disableTotp(username);
			return securityResult(username, service);
		} catch (MultiFactorException ex) {
			return errorResult(ex.getMessage(), ex.getCode());
		}
	}

	private static Object regenerateBackupCodes(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		Map<String, Object> denied = requireSelfWithPassword(session, username, (String) input.get("currentPassword"));
		if (denied != null) {
			return denied;
		}
		MultiFactorService service = service();
		if (service == null) {
			return errorResult("Second factors are not available on this server", "NOT_AVAILABLE");
		}
		try {
			List<String> codes = service.regenerateBackupCodes(username);
			Map<String, Object> result = new HashMap<>();
			result.put("backupCodes", codes);
			result.put("security", mapStatus(username, service.getStatus(username)));
			result.put("errors", null);
			return result;
		} catch (MultiFactorException ex) {
			return errorResult(ex.getMessage(), ex.getCode());
		}
	}

	// ---- passkeys -------------------------------------------------------------

	private static Object beginPasskeyRegistration(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		Map<String, Object> denied = requireSelfWithPassword(session, username, (String) input.get("currentPassword"));
		if (denied != null) {
			return denied;
		}
		MultiFactorService service = service();
		if (service == null) {
			return errorResult("Second factors are not available on this server", "NOT_AVAILABLE");
		}
		try {
			Map<String, Object> result = new HashMap<>();
			result.put("options", service.beginPasskeyRegistration(username));
			result.put("errors", null);
			return result;
		} catch (MultiFactorException ex) {
			return errorResult(ex.getMessage(), ex.getCode());
		}
	}

	@SuppressWarnings("unchecked")
	private static Object finishPasskeyRegistration(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		Object credential = input.get("credential");
		if (username == null || !(credential instanceof Map)) {
			return errorResult("username and credential are required", "INVALID_INPUT");
		}
		if (!isSelf(session, username)) {
			return errorResult("Only the user can register a passkey", "PERMISSION_DENIED");
		}
		MultiFactorService service = service();
		if (service == null) {
			return errorResult("Second factors are not available on this server", "NOT_AVAILABLE");
		}
		try {
			PasskeyInfo passkey = service.finishPasskeyRegistration(username, (Map<String, Object>) credential, (String) input.get("displayName"));
			Map<String, Object> result = new HashMap<>();
			result.put("passkey", mapPasskey(passkey));
			result.put("security", mapStatus(username, service.getStatus(username)));
			result.put("errors", null);
			return result;
		} catch (MultiFactorException ex) {
			return errorResult(ex.getMessage(), ex.getCode());
		}
	}

	private static Object renamePasskey(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		String id = (String) input.get("id");
		String displayName = (String) input.get("displayName");
		if (username == null || id == null || displayName == null || displayName.isBlank()) {
			return errorResult("username, id and displayName are required", "INVALID_INPUT");
		}
		if (!isSelf(session, username)) {
			return errorResult("Only the user can rename a passkey", "PERMISSION_DENIED");
		}
		MultiFactorService service = service();
		if (service == null) {
			return errorResult("Second factors are not available on this server", "NOT_AVAILABLE");
		}
		try {
			service.renamePasskey(username, id, displayName.trim());
			return securityResult(username, service);
		} catch (MultiFactorException ex) {
			return errorResult(ex.getMessage(), ex.getCode());
		}
	}

	private static Object deletePasskey(DataFetchingEnvironment environment) throws Exception {
		Session session = callerSession(environment);
		Map<String, Object> input = inputArg(environment);
		String username = (String) input.get("username");
		String id = (String) input.get("id");
		if (id == null) {
			return errorResult("id is required", "INVALID_INPUT");
		}
		Map<String, Object> denied = requireSelfWithPasswordOrAdmin(session, username, (String) input.get("currentPassword"));
		if (denied != null) {
			return denied;
		}
		MultiFactorService service = service();
		if (service == null) {
			return errorResult("Second factors are not available on this server", "NOT_AVAILABLE");
		}
		try {
			service.deletePasskey(username, id);
			return securityResult(username, service);
		} catch (MultiFactorException ex) {
			return errorResult(ex.getMessage(), ex.getCode());
		}
	}

	// ---- authorization --------------------------------------------------------

	/**
	 * The caller must be the user and must present their current password.
	 */
	private static Map<String, Object> requireSelfWithPassword(Session session, String username, String currentPassword) throws Exception {
		if (username == null) {
			return errorResult("username is required", "INVALID_INPUT");
		}
		if (!isSelf(session, username)) {
			return errorResult("Only the user can change their own second factors", "PERMISSION_DENIED");
		}
		if (currentPassword == null || !verifyPassword(username, currentPassword)) {
			return errorResult("Current password is incorrect", "INVALID_CREDENTIALS");
		}
		return null;
	}

	/**
	 * The caller must be the user (with their current password) or an
	 * administrator acting on another user.
	 */
	private static Map<String, Object> requireSelfWithPasswordOrAdmin(Session session, String username, String currentPassword) throws Exception {
		if (username == null) {
			return errorResult("username is required", "INVALID_INPUT");
		}
		if (isSelf(session, username)) {
			if (currentPassword == null || !verifyPassword(username, currentPassword)) {
				return errorResult("Current password is incorrect", "INVALID_CREDENTIALS");
			}
			return null;
		}
		if (!isAdmin(session)) {
			return errorResult("Not allowed to change this user's second factors", "PERMISSION_DENIED");
		}
		return null;
	}

	private static boolean verifyPassword(String username, String password) throws Exception {
		Session systemSession = CmsService.getRepository().login(new CmsServiceCredentials(), "system");
		try {
			return UserCredentials.verifyPassword(systemSession, username, password);
		} finally {
			systemSession.logout();
		}
	}

	private static boolean isSelf(Session session, String username) {
		return username != null && username.equals(session.getUserID());
	}

	private static boolean isAdmin(Session session) {
		return org.mintjams.jcr.Session.class.cast(session).isAdmin();
	}

	// ---- mappers --------------------------------------------------------------

	private static Map<String, Object> securityResult(String username, MultiFactorService service) throws MultiFactorException {
		Map<String, Object> result = new HashMap<>();
		result.put("security", mapStatus(username, service.getStatus(username)));
		result.put("errors", null);
		return result;
	}

	private static Map<String, Object> mapStatus(String username, MultiFactorStatus status) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("username", username);
		map.put("totpEnabled", status.isTotpEnabled());
		map.put("backupCodesRemaining", status.getBackupCodesRemaining());
		map.put("passkeysAvailable", status.isPasskeysAvailable());
		List<Map<String, Object>> passkeys = new ArrayList<>();
		for (PasskeyInfo passkey : status.getPasskeys()) {
			passkeys.add(mapPasskey(passkey));
		}
		map.put("passkeys", passkeys);
		return map;
	}

	private static Map<String, Object> unavailableStatus(String username) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("username", username);
		map.put("totpEnabled", false);
		map.put("backupCodesRemaining", 0);
		map.put("passkeysAvailable", false);
		map.put("passkeys", new ArrayList<>());
		return map;
	}

	private static Map<String, Object> mapPasskey(PasskeyInfo passkey) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", passkey.getId());
		map.put("displayName", passkey.getDisplayName());
		map.put("created", passkey.getCreated() == null ? null : ISO8601.format(passkey.getCreated()));
		map.put("lastUsed", passkey.getLastUsed() == null ? null : ISO8601.format(passkey.getLastUsed()));
		map.put("transports", passkey.getTransports());
		map.put("backedUp", passkey.isBackedUp());
		return map;
	}

	private static Map<String, Object> errorResult(String message, String code) {
		Map<String, Object> error = new HashMap<>();
		error.put("field", null);
		error.put("message", message);
		error.put("code", code);
		Map<String, Object> result = new HashMap<>();
		result.put("errors", List.of(error));
		return result;
	}

	// ---- plumbing -------------------------------------------------------------

	/**
	 * The IdP's service, or null when no identity provider bundle is running
	 * (the SP then federates with an external IdP and second factors are that
	 * IdP's business).
	 */
	static MultiFactorService service() {
		BundleContext context = CmsService.getDefault().getBundleContext();
		if (context == null) {
			return null;
		}
		ServiceReference<MultiFactorService> ref = context.getServiceReference(MultiFactorService.class);
		if (ref == null) {
			return null;
		}
		return context.getService(ref);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> inputArg(DataFetchingEnvironment environment) {
		Map<String, Object> input = environment.getArgument("input");
		return input == null ? new HashMap<>() : input;
	}

	private static Session callerSession(DataFetchingEnvironment environment) {
		return GraphQLExecutionContext.from(environment).getCallerSession();
	}

	private static String loadSchema() throws Exception {
		try (InputStream in = PlatformSecurityWiringContributor.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Security GraphQL schema resource not found: " + SCHEMA_RESOURCE);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
