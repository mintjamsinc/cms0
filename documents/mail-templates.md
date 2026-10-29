# Mail templates

The messages the server sends itself (see [mail-transport.md](mail-transport.md))
have built-in texts in English and Japanese. An administrator can replace any
of them by writing a template in the `system` workspace:

```
/etc/mail/templates/
```

A template holds the subject and the body in one file. Without a file for a
message, or when the file cannot be used, the built-in text is sent. Changes
take effect with the next message; no restart is needed.

## The messages

| Template | Sent when | Variables besides the common ones |
| --- | --- | --- |
| `sign-in-code` | a user asks for a code on the sign-in page | `code`, `minutes` |
| `sign-in-unconfirmed` | a code is asked for an address its owner has not confirmed; it is sent instead of a code | none |
| `mail-confirmation` | a user enters a new address in Preferences › Profile; carries the code to the new address | `code`, `minutes` |
| `mail-change-notice` | the same change, told to the current address | `newMail` |

Every template also gets:

| Variable | Value |
| --- | --- |
| `mail` | the address the message is sent to |
| `username` | the account |
| `displayName` | the user's display name, empty when there is none |
| `issuer` | the name the server signs with (`totp.issuer` in `idp.yml`), empty without an IdP |
| `lang` | the language tag the message was asked in (`ja`, `en-US`), empty when none was given |

`code` is the six-digit code, `minutes` how long it stays valid, `newMail`
the address that replaces `mail`.

## File names and languages

A file is named after the template, with the language before the extension:

```
sign-in-code.ja.txt       Japanese
sign-in-code.en.txt       English
sign-in-code.txt          every other language
```

For a user whose language is `ja-JP` the server looks for
`sign-in-code.ja-JP.*`, then `sign-in-code.ja.*`, then `sign-in-code.*`, and
sends the built-in text when none exists. A file without a language is
therefore used for every language that has no file of its own, including
English and Japanese. The extension is required (`sign-in-code.ja` alone is
not a Japanese template), and case does not matter. Keep one file per
language: of two, the first in alphabetical order wins.

The language is the one of the sign-in page (or the browser's
`Accept-Language`) for the sign-in messages, and the Webtop language for the
address change.

## The format

The file starts with a front matter that names the subject, followed by the
body:

```
---
subject: "サインイン用の認証コード: ${code}"
---
<% if (displayName) { %>${displayName} さん

<% } %>サインイン用の認証コードです。

    ${code}

有効期間は ${minutes} 分、使えるのは 1 回です。
サインインを試みた覚えがなければ、このメールは無視してください。

${issuer}
```

- The front matter is YAML between two `---` lines. Only `subject` is read.
  Quote the value when it contains `: ` or starts with a character YAML
  treats specially (`"`, `'`, `{`, `[`, `#`, `&`, `*`, `!`, `|`, `>`, `%`, `@`).
  A subject over several lines is joined into one.
- Without a front matter, or without `subject`, the built-in subject is used
  with the template's body.
- The subject and the body are written as in a GSP: `${expression}` inserts a
  value and `<% ... %>` runs Groovy code. A literal `$` is written `\$`. An
  empty value counts as false, so `${displayName ?: username}` greets by the
  user name when there is no display name.

The English counterpart of the example:

```
---
subject: "Your sign-in code: ${code}"
---
Hello ${displayName ?: username},

Your sign-in code is:

    ${code}

It expires in ${minutes} minutes and works once.
If you did not try to sign in, you can ignore this message.

${issuer}
```

## Type and character set

The body is sent with the file's `jcr:mimeType` and in its `jcr:encoding`:
the file is read in that encoding, and the subject and body go out in it.
When the file has no `jcr:mimeType` (or `application/octet-stream`) the body
is plain text, and without `jcr:encoding` it is UTF-8. Only `text/*` types can
be sent.

A file named `.html` is HTML mail (`text/html`). In an HTML body every value
is HTML-escaped, so a display name cannot add markup; the subject is not
escaped.

```
---
subject: "Your sign-in code: ${code}"
---
<!DOCTYPE html>
<html>
<body>
<p>Hello ${displayName ?: username},</p>
<p>Your sign-in code is <strong>${code}</strong>. It expires in ${minutes} minutes.</p>
<p>${issuer}</p>
</body>
</html>
```

To send Japanese mail in ISO-2022-JP, set `jcr:encoding` of a file saved in
ISO-2022-JP to `ISO-2022-JP`.

## When a template is wrong

A file that cannot be read, parsed or rendered (a YAML error in the front
matter, a GSP syntax error, a variable that does not exist, a type other than
`text/*`, an unknown encoding) is reported in the log with its path, and the
built-in text is sent instead. A broken template never stops a sign-in code.

Test a template by triggering its message: request a code on the sign-in
page, or change your address in Preferences › Profile.
