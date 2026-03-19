# org.mintjams.idp - MintJams SAML 2.0 Identity Provider

Minimal SAML 2.0 IdP as an OSGi bundle. Deploy it and get a login screen.

## What it does

- Receives SAML AuthnRequest from any SAML SP (your CMS, GitLab, Redmine, etc.)
- Shows a login form
- Authenticates against a properties file (JCR-based store in Phase 2)
- Returns a signed SAMLResponse with user attributes and roles
- Auto-generates a self-signed signing certificate on first run

## Endpoints

| Path             | Description                          |
|------------------|--------------------------------------|
| `/idp/metadata`  | IdP SAML metadata XML                |
| `/idp/sso`       | SSO endpoint (AuthnRequest receiver) |
| `/idp/login`     | Login form                           |

## Quick Start

1. Place BouncyCastle JARs in `lib/`:
   - `bcprov.jar` (bcprov-jdk18on-1.78.1.jar or similar)
   - `bcpkix.jar` (bcpkix-jdk18on-1.78.1.jar)
   - `bcutil.jar` (bcutil-jdk18on-1.78.1.jar)

2. Build the bundle (Eclipse PDE or manual jar)

3. Deploy to your OSGi container

4. On first startup:
   - `~/.mintjams-idp/idp-keystore.p12` is created (signing certificate)
   - `~/.mintjams-idp/idp-users.properties` is created (default: admin/admin)

5. Update your CMS's `saml2.yml`:
   ```yaml
   idp:
     entityID: https://your-host/idp
     loginURL: https://your-host/idp/sso
     certificate: (from /idp/metadata)
   ```

## Configuration

Set via JVM system properties (`-D`):

| Property               | Default                    | Description                     |
|------------------------|----------------------------|---------------------------------|
| `idp.baseUrl`          | `https://localhost:8443`   | Base URL of the IdP             |
| `idp.contextPath`      | `/idp`                     | Servlet context path            |
| `idp.dataDir`          | `~/.mintjams-idp`          | Data directory                  |
| `idp.roleAttribute`    | `Role`                     | SAML attribute name for roles   |
| `idp.keystorePassword` | `changeit`                 | KeyStore password               |

## User Management (Phase 1)

Users are stored in `idp-users.properties`:

```properties
admin.password.sha256=8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918
admin.email=admin@example.com
admin.displayName=Administrator
admin.roles=administration
```

Password is SHA-256 hex of the plain text. To generate:
```bash
echo -n "yourpassword" | sha256sum
```

The file is hot-reloaded — edit it while running, changes take effect immediately.

## Dependencies

Bundled in `lib/`:
- BouncyCastle Provider (bcprov)
- BouncyCastle PKIX (bcpkix)
- BouncyCastle Util (bcutil)

Required OSGi bundles (already in your CMS):
- slf4j.api
- org.apache.commons.commons-codec
- org.apache.commons.lang3
- org.mintjams.tools

## Authentication Flow

```
SP                          IdP
 |                           |
 |--- AuthnRequest --------->|  (HTTP-Redirect or HTTP-POST to /idp/sso)
 |                           |
 |                           |---> User not logged in?
 |                           |       Redirect to /idp/login
 |                           |       User enters credentials
 |                           |       POST /idp/login
 |                           |       Authenticate against UserStore
 |                           |       Redirect back to /idp/sso
 |                           |
 |<-- SAMLResponse (POST) ---|  (signed, via auto-submit HTML form)
 |                           |
 |  SP validates signature   |
 |  SP extracts NameID,      |
 |    attributes, roles      |
 |  Session established      |
```

## License

MIT License - Copyright (c) 2024 MintJams Inc.
