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

package org.mintjams.rt.cms.internal.connections;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The identity providers a Webtop account can be linked to, and the scope
 * bundles an app may ask for.
 *
 * <p>A bundle is the unit of consent: an app asks for {@code mail} or
 * {@code files}, never for individual scopes, and Preferences shows the user
 * what each bundle is used for. The scopes behind a bundle differ per provider.
 * Microsoft issues an access token per resource (Outlook, Graph), so there a
 * bundle is also the audience a token is requested for; Google issues one
 * token for every granted scope.
 *
 * <p>The endpoints are the providers' documented ones; {@code clients.yml} may
 * override them, for a proxy or a test double.
 */
public final class ConnectionProviders {

	public static final String GOOGLE = "google";
	public static final String MICROSOFT = "microsoft";

	public static final String BUNDLE_MAIL = "mail";
	public static final String BUNDLE_FILES = "files";
	public static final String BUNDLE_CONTACTS = "contacts";
	public static final String BUNDLE_CALENDAR = "calendar";

	/** Every bundle, in the order Preferences lists them. */
	public static final List<String> BUNDLES = List.of(BUNDLE_MAIL, BUNDLE_FILES, BUNDLE_CONTACTS, BUNDLE_CALENDAR);

	public static final String MICROSOFT_DEFAULT_TENANT = "organizations";

	private static final Map<String, Provider> PROVIDERS;

	static {
		Map<String, Provider> providers = new LinkedHashMap<>();

		Map<String, List<String>> googleBundles = new LinkedHashMap<>();
		googleBundles.put(BUNDLE_MAIL, List.of("https://mail.google.com/"));
		googleBundles.put(BUNDLE_FILES, List.of("https://www.googleapis.com/auth/drive.file"));
		googleBundles.put(BUNDLE_CONTACTS, List.of("https://www.googleapis.com/auth/contacts.readonly"));
		googleBundles.put(BUNDLE_CALENDAR, List.of("https://www.googleapis.com/auth/calendar.events"));
		Map<String, String> googleAuthorize = new LinkedHashMap<>();
		// A refresh token is issued only with offline access, and again only when
		// the user is shown the consent screen; every grant here is an explicit
		// action of the user, so the screen is acceptable each time.
		googleAuthorize.put("access_type", "offline");
		googleAuthorize.put("prompt", "consent");
		googleAuthorize.put("include_granted_scopes", "true");
		providers.put(GOOGLE, new Provider(GOOGLE, "Google",
				"https://accounts.google.com/o/oauth2/v2/auth",
				"https://oauth2.googleapis.com/token",
				"https://openidconnect.googleapis.com/v1/userinfo",
				"https://oauth2.googleapis.com/revoke",
				List.of("openid", "email", "profile"), googleBundles, false, googleAuthorize));

		Map<String, List<String>> microsoftBundles = new LinkedHashMap<>();
		microsoftBundles.put(BUNDLE_MAIL, List.of("https://outlook.office.com/IMAP.AccessAsUser.All",
				"https://outlook.office.com/SMTP.Send"));
		microsoftBundles.put(BUNDLE_FILES, List.of("https://graph.microsoft.com/Files.ReadWrite"));
		microsoftBundles.put(BUNDLE_CONTACTS, List.of("https://graph.microsoft.com/Contacts.Read"));
		microsoftBundles.put(BUNDLE_CALENDAR, List.of("https://graph.microsoft.com/Calendars.ReadWrite"));
		providers.put(MICROSOFT, new Provider(MICROSOFT, "Microsoft",
				"https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize",
				"https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token",
				"https://graph.microsoft.com/oidc/userinfo",
				null,
				List.of("openid", "email", "profile", "offline_access"), microsoftBundles, true,
				Collections.emptyMap()));

		PROVIDERS = Collections.unmodifiableMap(providers);
	}

	private ConnectionProviders() {}

	/** The provider with this id, or null. */
	public static Provider get(String id) {
		return (id == null) ? null : PROVIDERS.get(id);
	}

	public static List<Provider> all() {
		return new ArrayList<>(PROVIDERS.values());
	}

	public static boolean isBundle(String bundle) {
		return bundle != null && BUNDLES.contains(bundle);
	}

	public static final class Provider {
		private final String fId;
		private final String fDisplayName;
		private final String fAuthorizationEndpoint;
		private final String fTokenEndpoint;
		private final String fUserinfoEndpoint;
		private final String fRevocationEndpoint;
		private final List<String> fIdentityScopes;
		private final Map<String, List<String>> fBundleScopes;
		private final boolean fAudiencePerBundle;
		private final Map<String, String> fAuthorizeParameters;

		private Provider(String id, String displayName, String authorizationEndpoint, String tokenEndpoint,
				String userinfoEndpoint, String revocationEndpoint, List<String> identityScopes,
				Map<String, List<String>> bundleScopes, boolean audiencePerBundle,
				Map<String, String> authorizeParameters) {
			fId = id;
			fDisplayName = displayName;
			fAuthorizationEndpoint = authorizationEndpoint;
			fTokenEndpoint = tokenEndpoint;
			fUserinfoEndpoint = userinfoEndpoint;
			fRevocationEndpoint = revocationEndpoint;
			fIdentityScopes = identityScopes;
			fBundleScopes = bundleScopes;
			fAudiencePerBundle = audiencePerBundle;
			fAuthorizeParameters = authorizeParameters;
		}

		public String getId() {
			return fId;
		}

		public String getDisplayName() {
			return fDisplayName;
		}

		/** The documented endpoint, with {@code {tenant}} filled in; a client may override it. */
		public String getAuthorizationEndpoint(String tenant) {
			return fill(fAuthorizationEndpoint, tenant);
		}

		public String getTokenEndpoint(String tenant) {
			return fill(fTokenEndpoint, tenant);
		}

		public String getUserinfoEndpoint(String tenant) {
			return fill(fUserinfoEndpoint, tenant);
		}

		/** Null when the provider has no revocation endpoint (RFC 7009). */
		public String getRevocationEndpoint(String tenant) {
			return (fRevocationEndpoint == null) ? null : fill(fRevocationEndpoint, tenant);
		}

		/** The scopes that identify the account: asked for at every grant. */
		public List<String> getIdentityScopes() {
			return fIdentityScopes;
		}

		/** The scopes behind a bundle; empty for an unknown bundle. */
		public List<String> getBundleScopes(String bundle) {
			List<String> scopes = fBundleScopes.get(bundle);
			return (scopes == null) ? Collections.emptyList() : scopes;
		}

		/** The bundles whose scopes are all among {@code granted}. */
		public List<String> bundlesGrantedBy(List<String> granted) {
			List<String> bundles = new ArrayList<>();
			for (Map.Entry<String, List<String>> e : fBundleScopes.entrySet()) {
				if (!e.getValue().isEmpty() && granted.containsAll(e.getValue())) {
					bundles.add(e.getKey());
				}
			}
			return bundles;
		}

		/**
		 * Whether an access token is bound to one bundle's resource, so each bundle
		 * needs a token of its own (Microsoft), or one token serves every granted
		 * scope (Google).
		 */
		public boolean isAudiencePerBundle() {
			return fAudiencePerBundle;
		}

		/** Provider-specific parameters added to every authorization request. */
		public Map<String, String> getAuthorizeParameters() {
			return fAuthorizeParameters;
		}

		private static String fill(String template, String tenant) {
			String t = (tenant == null || tenant.isBlank()) ? MICROSOFT_DEFAULT_TENANT : tenant.trim();
			return template.replace("{tenant}", t);
		}
	}

}
