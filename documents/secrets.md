# Secrets

Passwords and API keys that the server's configuration needs are kept in one
file, and the configuration files refer to them by name:

```
<repository>/etc/secrets.yml
```

The server watches the file. A value written in the clear is encrypted within
a couple of seconds and written back in place, so the file holds only
`ENC[v1:...]` values once the server has seen it. Reading never waits for
that: a value works the moment the file is placed, encrypted or not.

The file is not tied to any one feature. Outgoing mail (`etc/mail.yml`), the
OAuth clients of linked accounts (`/etc/oauth/clients.yml`), the database
passwords of `jcr.yml` and `bpm.yml`, and whatever needs a secret in the
future all read it the same way. Nothing is shipped: create the file when the
first secret is needed.

## The file

One `key: value` per line. The keys are yours to name.

```yaml
# Secrets of this installation. The server encrypts what is written here.
SMTP_PASSWORD: ENC[v1:...]          # already encrypted by the server
SHOPIFY_API_KEY: shpat_xxxxxxxx     # just placed; ENC[v1:...] in a moment
DB_PASSWORD: "p@ss: word"           # quote when YAML needs it; the quotes go when it is encrypted
UNUSED: # an empty value is "not set", and stays as it is
```

Rules:

- The document is a flat mapping. Every value is a single-line scalar, plain
  or quoted. Nested mappings, lists, block scalars (`|`, `>`), values that
  continue on the next line, anchors and aliases are not accepted, nor is a
  key written twice.
- Every value is text. `PORT: 587` is the string `587`.
- An empty value (`KEY:` alone, or `null`) means the key is not set. It is
  left as written, and a configuration that refers to it is rejected.
- Comments, blank lines and the order of the keys are kept when the server
  writes the file back. Quotes around a value are not: the encrypted form
  needs none.
- A key name may not contain `}` or whitespace, since `${secrets.KEY}` ends
  at the first `}`. `UPPER_SNAKE_CASE` is a good habit.

### When the file is wrong

A file that is not a flat mapping, or has a value that does not fit the
rules above, is reported once in the log with the keys at fault, and
**nothing in it is encrypted** until it is fixed. The keys that are fine can
still be read; a configuration that refers to a key at fault is rejected with
the reason. Values and ciphertexts never appear in the log, only key names.

## Referring to a secret

In a configuration file, write `${secrets.KEY}` where the value would go:

```yaml
# etc/mail.yml
smtp:
    host: smtp.example.com
    username: ${secrets.SMTP_USER}
    password: ${secrets.SMTP_PASSWORD}
from: MintJams CMS <no-reply@example.com>
```

- The reference may be part of a longer value:
  `jdbc:postgresql://db/jcr?password=${secrets.DB_PASSWORD}`.
- There is no default-value form (`${env.NAME:-default}` has one;
  `${secrets.KEY}` does not). A secret that may be absent is not a secret.
- A key that is missing, empty or unusable makes the configuration file
  invalid, the same way an unset `${env.NAME}` does. The log names the file,
  the setting and the key: `mail.yml#smtp.password: The secret SMTP_PASSWORD
  is not in .../etc/secrets.yml.`
- `${env.NAME}`, `NAME_FILE` and a literal `ENC[v1:...]` keep working beside
  it (see [clustering.md](clustering.md), *Secrets*). `${secrets.KEY}` is the
  form to prefer: the value is written once, in one place, and the server
  does the encrypting.

Where a reference takes effect:

| File | A change to `secrets.yml` takes effect |
| --- | --- |
| `etc/mail.yml`, `/etc/oauth/clients.yml` | within seconds, like a change to the file itself |
| `<workspace>/etc/jcr/jcr.yml`, `<workspace>/etc/bpm/bpm.yml` | when the workspace is next started (they are read once, at start) |

## How the file is encrypted

Every couple of seconds the server looks at the file's modification time and
size. When they changed, it reads the file, and when a value is in the clear,
encrypts that value with the installation's secret key and replaces exactly
the value in the text — nothing else moves. The new text is written next to
the file and moved over it in one step, so a reader sees either the old file
or the new one, never half of each. The file is made readable by its owner
only.

In a cluster every node watches the same shared file. One node writes at a
time (the `secrets-encryption` lease), and right before the move the writer
checks that the file is still the one it read, so an edit saved in between is
kept rather than overwritten; the next look encrypts what that edit left in
the clear. With node-local repository directories each node encrypts its own
copy; the ciphertexts differ, the key is the same, and each node reads its
own.

What this means in practice:

- A value is in the clear on disk for a couple of seconds while the server
  runs, or until the next start when it does not. Place secrets while the
  server is up when that matters.
- An editor left open on the file reports that it changed on disk shortly
  after you save. Reload it. Saving the old buffer again puts the clear text
  back; it is encrypted again.
- To change a secret, overwrite the value in the clear. To remove one, delete
  the line. Encrypted values are never edited by hand.
- An encrypted value can be decrypted only with this installation's key
  (`etc/secret.key`). To carry `secrets.yml` to another installation, write
  the values in the clear again and let that server encrypt them.

## The key

`ENC[v1:...]` values — in `secrets.yml` and anywhere else — are encrypted
with the key in

```
<repository>/etc/secret.key
```

which is created on first use. It lives with the other files that every node
of a cluster must share (see [clustering.md](clustering.md), *Identity
files*). `MINTJAMS_CMS_SECRET_KEY_PATH` (or the system property
`mintjams.cms.secret-key.path`) puts it somewhere else; the container image
does that and keeps it on its own volume, `/data/secrets`.

An installation made before the key moved here keeps working: a key found at
the old name, `etc/secret-key.yml`, or at the old place,
`$HOME/.mintjams/cms/secret-key.yml`, is used, and the log asks once for the
rename or the move. Rename or move the file while the server is stopped; do
not copy it, so that there is one key. **Never let a server start with a new,
empty key location while values encrypted with the old key exist**: it would
mint a fresh key and those values would become unreadable.

### Backups

The key is what turns the encrypted values into secrets. Keep it out of the
repository backup and keep it elsewhere, encrypted:

- Exclude `etc/*.key` from the repository backup — `secret.key` and the
  cluster's `cluster-secret.key` end in `.key` for exactly this. Everything
  else in `etc/` (including the encrypted `secrets.yml`, `idp.yml` and
  `saml2.yml`) belongs in the ordinary backup.
- Back up the key files on their own, encrypted (a password-protected
  archive, an encryption tool, your organization's secret store), stored
  apart from the repository backup and with tighter access. The key is a few
  dozen bytes and changes only when it is created or rotated.
- To restore, put the key back into `etc/` **before** the first node starts.
  A node that starts without it creates a new key, and the restored
  `ENC[v1:...]` values cannot be read with it. `cluster-secret.key` is
  recoverable (delete it, restart every node, a new one is made);
  `secret.key` is not.
