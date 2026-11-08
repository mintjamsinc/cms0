# Workspace secrets

Passwords and API keys that a workspace's scripts need — the SMTP password of
a contact form, the API key of a payment service — are kept in one file
inside the workspace:

```
/etc/security/secrets.yml
```

The platform watches the file. A value saved in the clear is encrypted as soon
as the save is seen — usually within a second — and written back in place, so the file holds only
`ENC[v1:...]` values once the platform has seen it. Scripts read the values
through `SecretsAPI`; nothing else hands them out.

This is not the server's own `<repository>/etc/secrets.yml`, which the
operator keeps on disk for `mail.yml`, `jcr.yml` and the other server
configuration (see [secrets.md](secrets.md)). The two are used apart:
`SecretsAPI` reads only the workspace's file, and the server configuration
reads only the server's. This keeps each secret in one obvious place; it is
not a security boundary (see *What is protected, and what is not*).

There is one file per workspace. It belongs to the platform, not to any
package: a package documents the keys it needs and reads them through
`SecretsAPI`, but it never creates the file, grants access to it or reads it
directly.

## The file

The format is the same as the server's file: one `key: value` per line, a
flat mapping, single-line values, every value text.

```yaml
# Secrets of this workspace. The platform encrypts what is written here.
SMTP_PASSWORD: ENC[v1:...]          # already encrypted by the platform
STRIPE_SECRET_KEY: sk_live_xxxx     # just saved; ENC[v1:...] in a moment
UNUSED: # an empty value is "not set", and stays as it is
```

- Nested mappings, lists, block scalars, values that continue on the next
  line, anchors, aliases and repeated keys are not accepted. A file that
  breaks these rules is reported once in the log with the keys at fault, and
  nothing in it is encrypted until it is fixed.
- Comments, blank lines and the order of the keys are kept when the platform
  writes the file back. Quotes around a value are not.
- Key names are shared by everything in the workspace. A prefix per package
  (`SITE_SMTP_PASSWORD`) keeps them apart.
- A key name may not contain `}` or whitespace. `UPPER_SNAKE_CASE` is a good
  habit.

## Who can read and write it

- The platform's provisioning (`security.yml`) creates `/etc/security` when
  the workspace starts, if it is missing, and denies reading it to everyone.
  Administrators are not affected by access control and can open and save
  the file.
- To let someone else maintain the file, an administrator grants that user
  or group read and write access on `/etc/security/secrets.yml`. Keep the
  deny entry on `/etc/security`: it is put back at the next start if it is
  removed, and then it comes after your grants and overrides them.
- The container image applies this to every workspace on every start. When
  the server runs outside the image, copy the image's
  `assets/workspace/provisioning/security.yml` into each workspace's
  `etc/jcr/provisioning/` and into `etc/workspace-template/etc/jcr/provisioning/`
  (see [workspaces.md](workspaces.md#workspace-template)).
- Even a reader sees only `ENC[v1:...]` once the platform has encrypted the
  values. Access is denied anyway, because the key names tell which services
  the workspace uses.

To set a secret, open the file in the Text Editor, write the value in the
clear and save. To change one, overwrite the value in the clear. To remove
one, delete the line. Encrypted values are never edited by hand.

**Never paste a secret into a chat with an AI assistant.** Tell the assistant
which key to set, and write the value yourself.

## Reading a secret from a script

`SecretsAPI` is available to every workspace script: GraphQL resolvers, EIP
routes, BPM scripts, web scripts and MCP tool scripts.

```groovy
def secrets = context.getAttribute('SecretsAPI')

secrets.has('SMTP_PASSWORD')          // true when the key is set
secrets.get('SMTP_PASSWORD')          // the value, decrypted
secrets.resolve(text)                 // text with every ${secrets.KEY} replaced
```

- `get` fails when the key is missing, empty or cannot be decrypted. The
  message names the key, never the value.
- `resolve` replaces `${secrets.KEY}` and leaves any other `${...}` as it is.
  It is meant for a package's own configuration file:

  ```yaml
  # /etc/site/contact.yml
  smtp:
      host: smtp.example.com
      username: shop@example.com
      password: ${secrets.SMTP_PASSWORD}
  ```

  ```groovy
  def config = YAML.parse(secrets.resolve(contactYml))
  ```

- A script reads the values whatever its caller may access: the platform
  reads the file itself. A package's service user needs no access to
  `/etc/security`.
- `SecretsAPI` reads only this workspace's file, never the server's
  `secrets.yml` or another workspace's file. Keep the secrets your scripts
  need here.
- There is no way to write a secret from a script, from GraphQL or from MCP.
  Values are written only by saving the file.

## What is protected, and what is not

The aim is that opening the file never shows a value in the clear.

Protected:

- Opening the file — in the Text Editor, through MCP `read_file`, in an
  archive export — shows only `ENC[v1:...]`.
- No script, GraphQL field or MCP tool can change a value.

Not protected:

- Anyone who can deploy a script in the workspace (write to `/etc/graphql`,
  `/etc/eip`, `/etc/bpm` or a web script), and an AI assistant working with
  their permissions, can read the values with a script. Scripts run inside
  the server and can reach more than `SecretsAPI`: the server's own
  `secrets.yml`, and the repository without access control. **Grant the
  right to deploy scripts only to people you would trust as server
  administrators.** The deployment stays in the file's history.
- A value written in a chat stays in that conversation.
- What was saved in the clear can remain for a while inside the repository's
  own storage — the full-text search index, and the stored file content until
  unused content is cleaned up. These are not places where a value can be
  opened or read back through the Webtop or an API.

## How the file is encrypted

When the file is saved, or when the workspace starts, the platform reads it.
When a value is in the clear, it encrypts that value with the installation's
secret key and replaces exactly the value in the text. It is the same key as
the server's `secrets.yml` (`etc/secret.key`, see [secrets.md](secrets.md),
*The key*): there is no key per workspace, and backing up and restoring that
one key covers both files. The file is then saved by the platform, so its
last modifier shows as the platform's service.

While the platform rewrites the file it holds a lock on it, for a few
milliseconds. A save that lands in that moment fails with a lock error
instead of being overwritten; save again. The other way round, while someone
else holds a lock on the file, the platform leaves it alone and logs a
warning with the keys in the clear; releasing the lock is enough for it to
encrypt them.

In a cluster every node receives the change, and the lock lets one node
write it back. Scripts on the other nodes see a changed value once their
node has picked the change up from the cluster journal, usually within
seconds. Every node must share the installation's secret key (see
[clustering.md](clustering.md), *Identity files*), so that a value
encrypted on one node can be read on all of them.

What this means in practice:

- A value is in the clear for a few seconds after it is saved. Nobody but an
  administrator can read the file meanwhile.
- An editor left open on the file shows that it changed shortly after you
  save. Reload it. Saving the old content again puts the clear text back; it
  is encrypted again.
- **Do not put the file under version control** (do not call
  `addVersionControl` on it). A version saved before the platform encrypted
  it keeps the value in the clear, and anyone who can read the history can
  open it. The platform does not prevent this; it is a rule for the people
  who maintain the workspace.
- An encrypted value can be decrypted only by an installation with the same
  secret key. To carry the file to another installation, write the values in
  the clear again there.
