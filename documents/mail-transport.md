# Outgoing mail

The server itself sends a few messages: the one-time code for signing in with
an email address, and the code that confirms a new email address in
Preferences. They go out through an SMTP server that the operator names in one
file:

```
<repository>/etc/mail.yml
```

Without the file nothing is sent. Everything that needs the transport then
says so: email-code sign-in is not offered, and an email address can be changed
without confirmation. Place the file and the server picks it up by itself; no
restart is needed. This is not the Mail app, which reads and sends through each
user's own account.

## The file

```yaml
smtp:
    host: smtp.example.com
    port: 587
    starttls: true            # upgrade the connection with STARTTLS (default true)
    ssl: false                # connect over TLS from the start (SMTPS, usually port 465)
    username: ${env.CMS_SMTP_USER}
    password: ${env.CMS_SMTP_PASSWORD}
    timeoutSeconds: 15
from: MintJams CMS <no-reply@example.com>
```

| Key | Meaning | Default |
| --- | --- | --- |
| `smtp.host` | the SMTP server | required |
| `smtp.port` | its port | `587` |
| `smtp.starttls` | upgrade a plain connection with STARTTLS; required when `ssl` is off | `true` |
| `smtp.ssl` | connect over TLS from the start (SMTPS) | `false` |
| `smtp.username`, `smtp.password` | credentials; leave both out for a server that needs none | none |
| `smtp.timeoutSeconds` | how long to wait for the server to connect, answer and take the message | `15` |
| `from` | the sender, as `Name <address>` or a bare address | required |

Messages are plain text, in the language of the person they are sent to:
Japanese when the browser asked for it, English otherwise.

### Secrets

A value may name an environment variable, or be encrypted, instead of being
written in the file:

- `${env.NAME}` is replaced by the environment variable `NAME`. When
  `NAME_FILE` is set instead, its file is read (a Docker secret or a mounted
  Kubernetes secret). `${env.NAME:-default}` supplies a value for when the
  variable is unset.
- `ENC[v1:...]` is decrypted with the server's secret key, the same way as
  other configuration files.

### When the file is wrong

A file that cannot be read (a missing `host` or `from`, an unset environment
variable, a value that is not a number) is reported once in the log, and the
transport is treated as absent until the file is fixed. A server that cannot
be reached is noticed only when a message is sent: the log then carries the
cause, and the person who asked for the message sees that it could not be
sent.

## Checking it works

Place the file, then in Preferences › Profile press **Change** next to the
email address and enter your own address. If a code arrives, the transport is
working; if not, the log (`ROOT.<date>.log`) says what the SMTP server
answered.
