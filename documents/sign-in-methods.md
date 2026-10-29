# Sign-in methods

The built-in identity provider offers up to three ways in, and the login page
shows the ones this server has:

| Method | Needs | Second step |
| --- | --- | --- |
| Password | a password set by the administrator or the user | the authenticator code when the user turned on two-step verification |
| A code sent by email | an email address on the account and the outgoing mail transport (`etc/mail.yml`, see [mail-transport.md](mail-transport.md)) | the same |
| Passkey | a passkey the user registered in Preferences › Security | none; a passkey counts as two factors |

A passkey can only be registered by a user who is already signed in, so a
new user always starts with a password or with a code sent by email.

## Turning a method off

`etc/idp.yml`:

```yaml
signIn:
    password: true     # username and password
    emailCode: true    # a one-time code sent to the user's email address
```

Both default to `true`; the file is read when the server starts. Passkeys
are not listed here: they are offered whenever the server has a relying
party, which it always has (`webauthn.rpId` defaults to the host).

A method that the administrator turned off is still shown on the login
page, greyed out and marked as turned off, so that a user knows why their
usual way in is gone; the server refuses it whatever the page does.

### What the server will not do

It will not leave the server with no way in. `password: false` is honoured
only while a code sent by email can be offered, that is while
`emailCode` is on and `etc/mail.yml` is in place. Otherwise passwords stay
on and the log carries a warning that says why:

```
WARN  signIn.password is false in idp.yml, but no other method could sign anyone in (no mail transport is configured (etc/mail.yml)); passwords stay enabled.
INFO  Sign-in methods: password=available, emailCode=unavailable, passkey=available
```

The mail transport can appear or disappear while the server runs; the
methods follow it, and each change is logged as above.

### Before turning passwords off

A code sent by email reaches a user only if the address on their account is
right, and a wrong address would hand the code to a stranger. So the server
sends sign-in codes only to an address that is *confirmed*: one whose owner,
signed in, entered a code sent to it in Preferences › Profile
(`mailVerifiedAt` on the profile). An unconfirmed address gets no code; a
notice goes to it instead, saying how to confirm it, and the login page
answers as it always does. Before setting `password: false`:

1. Place `etc/mail.yml` and confirm your own address: Preferences › Profile
   shows *Confirmed on …* next to it, or a *Confirm now* link when it is not.
   The code arriving proves the transport works.
2. Look at the user list in the Identity Manager. Users without an address
   or with an unconfirmed one carry a badge once passwords are off; they
   cannot sign in with a code until they confirm their address.

At start-up the server counts the administrators with a confirmed address;
when passwords are off and there are none, it warns:

```
WARN  Passwords are disabled, but no administrator has a verified email address. If administrators cannot sign in, start with CMS_IDP_ALLOW_PASSWORD=true, repair the addresses, and restart without it.
```

### Locked out

If passwords are off and the administrator's own address turns out to be
wrong, start the server once with the environment variable
`CMS_IDP_ALLOW_PASSWORD=true` (or the system property
`-Dcms.idp.allowPassword=true`). Passwords are accepted again for that run,
every such sign-in is logged as a warning, and nothing in `idp.yml` is
changed. Sign in, repair the address (Identity Manager, or Preferences ›
Profile with its confirmation code), and restart without the variable.

A user who cannot get a code because their address is wrong is helped by an
administrator, who corrects the address in the Identity Manager. The
corrected address does not replace the current one at once: it waits as
*pending* until the user, signed in with a password or a passkey, confirms it
in Preferences › Profile (*Send a confirmation code*). When the user has no
other way in, the administrator can tick *Register as confirmed* to save the
address at once on their own word; the log records who did so.

## Signing in with a code sent by email

The user chooses *Sign in with a code sent by email*, enters the username
and presses *Send code*. The server sends a 6-digit code to the address on
the account and the page asks for it.

- The code is valid for five minutes and works once. After five wrong
  entries the user starts over.
- A new code can be asked for after one minute; asking earlier sends nothing
  and the earlier code stays valid.
- The page shows the same message whether or not the username exists or has
  an address, so it cannot be used to find out which accounts exist. An
  unknown account gets no message; an unconfirmed address gets a notice
  instead of a code, with nothing in it that could be entered anywhere.
- The message is in Japanese when the login page is, English otherwise.
- When the user has two-step verification, the authenticator code follows
  as it does after a password.

What the user proved is carried to the service provider in the SAML
assertion: `urn:mintjams:idp:ac:classes:EmailCode`, or
`urn:mintjams:idp:ac:classes:EmailCodeTimeSyncToken` with the authenticator
code; the session records the factors as `saml2,emailCode` or
`saml2,emailCode,totp`.

The login API is at `/idp/api/login`: `GET` lists the methods
(`password`, `webauthn`, `emailCode` as booleans, and `states` with
`available`, `disabled` or `unavailable` for each), `POST
/idp/api/login/code/request` with `{"username": "…"}` sends the code and
`POST /idp/api/login/code` with `{"code": "…"}` verifies it. A page of your
own built on the bundled `app.js` (see the README, *Branding the login
page*) gets the new method without changes.

## Changing an email address

In Preferences › Profile the address is changed through a dialog that asks
for it twice; nothing can be pasted into the second field. When the server
can send mail, a code goes to the new address and the address is saved only
once the code is entered, which also marks it confirmed. The previous
address, when there was one, is told that a change was requested. Without a
mail transport the address is saved at once and stays unconfirmed; the
dialog says so.

Removing the address asks for confirmation, with a stronger warning when
passwords are off.

An address an administrator enters in the Identity Manager does not replace
the current one. It is kept as pending, the user sees it in Preferences ›
Profile with *Send a confirmation code*, and it becomes the address,
confirmed, once the code is entered. Entering the current address again
withdraws a pending one; clearing the field removes the address. *Register
as confirmed* saves the address at once on the administrator's word, which
the log records. Without a mail transport every address is saved at once,
unconfirmed. The form warns when passwords are off and the user has no
address, or an unconfirmed one.

For other clients, the platform GraphQL API has `signInPolicy` (the three
states and whether addresses are confirmed by a code), `beginMailChange`
and `confirmMailChange`; `updateMyProfile` refuses a new address with
`MAIL_VERIFICATION_REQUIRED` while a transport is configured, and still
clears one. `createUser` and `updateUser` take `mail` (kept as
`User.pendingMail` while a transport is configured) and `mailVerified`.
`User.mailVerifiedAt` says when an address was confirmed.
