/*
 * MintJams IdP sign-in page.
 *
 * Served at /idp/login/app.js. A page embeds it with
 *
 *   <div id="idp-login"></div>
 *   <script type="module" src="/idp/login/app.js"></script>
 *
 * and owns everything around that element (layout, logo, colours through the
 * --idp-* custom properties). The sign-in flow itself (password, one-time
 * code, passkey) lives here, so a branded page keeps working when the flow
 * grows.
 *
 * Optional page hooks, set before the script loads:
 *   window.IDP_LOGIN = {
 *     lang: 'ja',                       // default: <html lang> or the browser language
 *     labels: { signIn: '...' },        // overrides for any label below
 *     mount: '#idp-login',              // a different mount element
 *   };
 */
import { VDOM } from './ichigo.esm.min.js';

const BASE = new URL('../', import.meta.url).pathname; // "/idp/"
const API = {
	methods: BASE + 'api/login',
	login: BASE + 'api/login',
	mfa: BASE + 'api/login/mfa',
	sp: BASE + 'api/sp',
	webauthnOptions: BASE + 'api/webauthn/options',
	webauthnVerify: BASE + 'api/webauthn/verify',
};
const LAST_METHOD_KEY = 'idp.login.method';

const LABELS = {
	en: {
		serviceProvider: 'Service provider',
		chooseMethod: 'How do you want to sign in?',
		usePasskey: 'Sign in with a passkey',
		usePasskeyHint: 'Face, fingerprint, PIN or security key',
		usePassword: 'Sign in with a password',
		usePasswordHint: 'Username and password',
		username: 'Username',
		usernamePlaceholder: 'Enter your username',
		password: 'Password',
		next: 'Next',
		signIn: 'Sign in',
		verify: 'Verify',
		changeUsername: 'Change username',
		showPassword: 'Show password',
		otherMethods: 'Other ways to sign in',
		codeTitle: 'Two-step verification',
		codeHint: 'Enter the 6-digit code from your authenticator app.',
		code: 'Verification code',
		backupCodeHint: 'Enter one of your backup codes. Each code works once.',
		backupCode: 'Backup code',
		useBackupCode: 'Use a backup code',
		useAuthenticator: 'Use your authenticator app',
		startOver: 'Start over',
		passkeyWaiting: 'Follow the prompt from your browser or device.',
		passkeyCancelled: 'The passkey prompt was closed. You can try again.',
		passkeyFailed: 'The passkey could not be verified.',
		passkeyUnsupported: 'This browser does not support passkeys.',
		tryAgain: 'Try again',
		usePasswordInstead: 'Use a password instead',
		redirecting: 'Signing you in…',
		redirectingTo: 'Redirecting to ',
		errUsername: 'Please enter your username',
		errPassword: 'Please enter your password',
		errCode: 'Please enter the code',
		errNetwork: 'A network error occurred. Please try again.',
		errGeneric: 'Authentication failed',
		errRestart: 'Please sign in again.',
	},
	ja: {
		serviceProvider: 'サービスプロバイダー',
		chooseMethod: 'サインイン方法を選択してください',
		usePasskey: 'パスキーでサインイン',
		usePasskeyHint: '顔認証・指紋・PIN・セキュリティキー',
		usePassword: 'パスワードでサインイン',
		usePasswordHint: 'ユーザー名とパスワード',
		username: 'ユーザー名',
		usernamePlaceholder: 'ユーザー名を入力',
		password: 'パスワード',
		next: '次へ',
		signIn: 'サインイン',
		verify: '確認',
		changeUsername: 'ユーザー名を変更',
		showPassword: 'パスワードを表示',
		otherMethods: '他の方法でサインイン',
		codeTitle: '2 段階認証',
		codeHint: '認証アプリに表示された 6 桁のコードを入力してください。',
		code: '確認コード',
		backupCodeHint: 'バックアップコードのいずれかを入力してください。各コードは 1 回だけ使えます。',
		backupCode: 'バックアップコード',
		useBackupCode: 'バックアップコードを使う',
		useAuthenticator: '認証アプリのコードを使う',
		startOver: '最初からやり直す',
		passkeyWaiting: 'ブラウザーまたはデバイスの案内に従ってください。',
		passkeyCancelled: 'パスキーの操作が中断されました。もう一度お試しください。',
		passkeyFailed: 'パスキーを検証できませんでした。',
		passkeyUnsupported: 'このブラウザーはパスキーに対応していません。',
		tryAgain: 'もう一度試す',
		usePasswordInstead: 'パスワードでサインインする',
		redirecting: 'サインインしています…',
		redirectingTo: '移動先: ',
		errUsername: 'ユーザー名を入力してください',
		errPassword: 'パスワードを入力してください',
		errCode: 'コードを入力してください',
		errNetwork: 'ネットワークエラーが発生しました。もう一度お試しください。',
		errGeneric: '認証に失敗しました',
		errRestart: 'もう一度サインインしてください。',
	},
};

const STYLE = `
.idp-card{background:var(--idp-card,rgba(255,255,255,0.025));border:1px solid var(--idp-card-border,rgba(255,255,255,0.06));border-radius:var(--idp-radius-lg,16px);padding:32px;backdrop-filter:blur(20px);color:var(--idp-text,#E8E6E1);font-family:var(--idp-font,inherit)}
.idp-card *,.idp-card *::before,.idp-card *::after{box-sizing:border-box}
.idp-sp{display:flex;align-items:center;gap:10px;padding:10px 14px;background:var(--idp-accent-glow,rgba(99,153,34,0.06));border:1px solid var(--idp-accent-border,rgba(99,153,34,0.12));border-radius:var(--idp-radius,10px);margin-bottom:28px}
.idp-sp-label{font-size:11px;color:var(--idp-muted,#6B6961);letter-spacing:0.04em}
.idp-sp-name{font-size:13px;color:var(--idp-accent-text,#97C459);font-weight:500;margin-top:1px;word-break:break-all}
.idp-sp-url{color:var(--idp-dim,#4A4940);font-weight:400;font-size:11px}
.idp-title{font-size:15px;font-weight:500;margin:0 0 6px}
.idp-hint{font-size:13px;color:var(--idp-muted,#6B6961);margin:0 0 18px;line-height:1.5}
.idp-methods{display:flex;flex-direction:column;gap:10px}
.idp-method{display:flex;align-items:center;gap:14px;width:100%;text-align:left;padding:14px 16px;background:var(--idp-input-bg,rgba(255,255,255,0.04));border:1px solid var(--idp-input-border,rgba(255,255,255,0.08));border-radius:var(--idp-radius,10px);color:inherit;font:inherit;cursor:pointer;transition:border-color .2s,background .2s}
.idp-method:hover,.idp-method:focus-visible{border-color:var(--idp-focus,rgba(99,153,34,0.5));outline:none}
.idp-method.is-last{border-color:var(--idp-accent-border,rgba(99,153,34,0.3))}
.idp-method-icon{flex:none;width:36px;height:36px;display:flex;align-items:center;justify-content:center;border-radius:8px;background:var(--idp-accent-glow,rgba(99,153,34,0.1));color:var(--idp-accent-text,#97C459)}
.idp-method-title{font-size:14px;font-weight:500}
.idp-method-hint{font-size:12px;color:var(--idp-muted,#6B6961);margin-top:2px}
.idp-field{margin-bottom:16px}
.idp-field:last-of-type{margin-bottom:0}
.idp-label{display:block;font-size:12px;color:var(--idp-muted,#6B6961);margin-bottom:6px;letter-spacing:0.02em}
.idp-input-wrap{position:relative}
.idp-input{width:100%;padding:12px 14px;background:var(--idp-input-bg,rgba(255,255,255,0.04));border:1px solid var(--idp-input-border,rgba(255,255,255,0.08));border-radius:var(--idp-radius-sm,8px);color:inherit;font-size:14px;font-family:inherit;outline:none;transition:border-color .2s}
.idp-input::placeholder{color:var(--idp-ghost,#3A3930)}
.idp-input:focus{border-color:var(--idp-focus,rgba(99,153,34,0.5))}
.idp-input.is-readonly{color:var(--idp-muted,#6B6961);background:rgba(255,255,255,0.02);padding-right:44px}
.idp-input.has-error{border-color:var(--idp-error-border,rgba(226,75,74,0.4))}
.idp-input.has-icon{padding-right:44px}
.idp-input.is-code{font-size:22px;letter-spacing:0.3em;text-align:center;font-variant-numeric:tabular-nums}
.idp-icon-btn{position:absolute;right:10px;top:50%;transform:translateY(-50%);background:none;border:none;cursor:pointer;padding:4px;display:flex;color:var(--idp-muted,#6B6961)}
.idp-icon-btn:hover{color:inherit}
.idp-error{display:flex;align-items:center;gap:6px;margin-top:12px;font-size:12px;color:var(--idp-error,#E24B4A)}
.idp-submit{width:100%;padding:13px 20px;background:var(--idp-accent,linear-gradient(135deg,#639922,#4A7318));border:none;border-radius:var(--idp-radius,10px);color:var(--idp-accent-contrast,#fff);font-size:14px;font-weight:500;font-family:inherit;cursor:pointer;transition:opacity .15s;letter-spacing:0.01em;margin-top:20px}
.idp-submit:hover{opacity:.9}
.idp-submit:disabled{opacity:.5;cursor:not-allowed}
.idp-links{display:flex;justify-content:center;gap:20px;margin-top:18px;font-size:12px}
.idp-link{background:none;border:none;padding:0;color:var(--idp-muted,#6B6961);font:inherit;font-size:12px;cursor:pointer;text-decoration:none}
.idp-link:hover{color:inherit;text-decoration:underline}
.idp-wait{text-align:center;padding:24px 0}
.idp-spinner{width:48px;height:48px;margin:0 auto 20px;animation:idp-spin 1.2s linear infinite}
.idp-wait-title{font-size:14px;font-weight:500;margin-bottom:6px}
.idp-wait-sub{font-size:12px;color:var(--idp-muted,#6B6961)}
@keyframes idp-spin{from{transform:rotate(0)}to{transform:rotate(360deg)}}
`;

const ICON_PASSKEY = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="9" cy="8" r="4"/><path d="M2 21v-1a6 6 0 0 1 6-6h2"/><circle cx="17.5" cy="14.5" r="2.5"/><path d="M17.5 17v5l1.5-1.5M17.5 22l-1.5-1.5"/></svg>';
const ICON_PASSWORD = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>';
const ICON_ERROR = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 8v4M12 16h.01"/></svg>';
const ICON_EDIT = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>';
const ICON_EYE = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
const ICON_EYE_OFF = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0112 20c-7 0-11-8-11-8a18.45 18.45 0 015.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0112 4c7 0 11 8 11 8a18.5 18.5 0 01-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';
const SPINNER = '<svg class="idp-spinner" viewBox="0 0 48 48"><circle cx="24" cy="24" r="20" fill="none" stroke="rgba(128,128,128,0.15)" stroke-width="3"/><path d="M24 4a20 20 0 0 1 20 20" fill="none" stroke="var(--idp-accent-text,#639922)" stroke-width="3" stroke-linecap="round"/></svg>';

const TEMPLATE = `
<div class="idp-card" @mounted="init">

  <div class="idp-sp" v-if="sp">
    <div style="flex:1">
      <div class="idp-sp-label">{{ t.serviceProvider }}</div>
      <div class="idp-sp-name">{{ sp.name }}<br v-if="sp.url"><span class="idp-sp-url" v-if="sp.url">{{ sp.url }}</span></div>
    </div>
  </div>

  <div v-if="step === 'method'">
    <p class="idp-hint">{{ t.chooseMethod }}</p>
    <div class="idp-methods">
      <button type="button" class="idp-method" :class="{ 'is-last': lastMethod === 'webauthn' }" @click="chooseWebAuthn">
        <span class="idp-method-icon">${ICON_PASSKEY}</span>
        <span><span class="idp-method-title">{{ t.usePasskey }}</span><br><span class="idp-method-hint">{{ t.usePasskeyHint }}</span></span>
      </button>
      <button type="button" class="idp-method" :class="{ 'is-last': lastMethod === 'password' }" @click="choosePassword">
        <span class="idp-method-icon">${ICON_PASSWORD}</span>
        <span><span class="idp-method-title">{{ t.usePassword }}</span><br><span class="idp-method-hint">{{ t.usePasswordHint }}</span></span>
      </button>
    </div>
  </div>

  <div v-if="step === 'username' || step === 'password'">
    <div class="idp-field">
      <label class="idp-label">{{ t.username }}</label>
      <div class="idp-input-wrap">
        <input type="text" class="idp-input" v-model="username"
          :class="{ 'has-error': hasError && step === 'username', 'is-readonly': step === 'password' }"
          :readonly="step === 'password'" :placeholder="t.usernamePlaceholder"
          autocomplete="username" data-idp-focus="username"
          @input="clearError" @keydown="onEnter">
        <button type="button" class="idp-icon-btn" v-if="step === 'password'" @click="backToUsername" :title="t.changeUsername">${ICON_EDIT}</button>
      </div>
    </div>
    <div class="idp-field" v-if="step === 'password'">
      <label class="idp-label">{{ t.password }}</label>
      <div class="idp-input-wrap">
        <input :type="showPassword ? 'text' : 'password'" class="idp-input has-icon" v-model="password"
          :class="{ 'has-error': hasError && step === 'password' }" placeholder="••••••••"
          autocomplete="current-password" data-idp-focus="password"
          @input="clearError" @keydown="onEnter">
        <button type="button" class="idp-icon-btn" @click="togglePassword" :title="t.showPassword">
          <span v-if="!showPassword">${ICON_EYE}</span><span v-else>${ICON_EYE_OFF}</span>
        </button>
      </div>
    </div>
    <div class="idp-error" v-if="hasError">${ICON_ERROR}<span>{{ errorMessage }}</span></div>
    <button type="button" class="idp-submit" @click="submit" :disabled="busy">{{ step === 'username' ? t.next : t.signIn }}</button>
    <div class="idp-links" v-if="methods.webauthn">
      <button type="button" class="idp-link" @click="backToMethods">{{ t.otherMethods }}</button>
    </div>
  </div>

  <div v-if="step === 'totp'">
    <p class="idp-title">{{ t.codeTitle }}</p>
    <p class="idp-hint">{{ useBackupCode ? t.backupCodeHint : t.codeHint }}</p>
    <div class="idp-field">
      <label class="idp-label">{{ useBackupCode ? t.backupCode : t.code }}</label>
      <div class="idp-input-wrap">
        <input type="text" class="idp-input" :class="{ 'is-code': !useBackupCode, 'has-error': hasError }" v-model="code"
          :inputmode="useBackupCode ? 'text' : 'numeric'" :placeholder="useBackupCode ? 'XXXXX-XXXXX' : '000000'"
          autocomplete="one-time-code" data-idp-focus="code" @input="clearError" @keydown="onEnter">
      </div>
    </div>
    <div class="idp-error" v-if="hasError">${ICON_ERROR}<span>{{ errorMessage }}</span></div>
    <button type="button" class="idp-submit" @click="submit" :disabled="busy">{{ t.verify }}</button>
    <div class="idp-links">
      <button type="button" class="idp-link" @click="toggleBackupCode">{{ useBackupCode ? t.useAuthenticator : t.useBackupCode }}</button>
      <button type="button" class="idp-link" @click="startOver">{{ t.startOver }}</button>
    </div>
  </div>

  <div v-if="step === 'webauthn'">
    <div class="idp-wait" v-if="!hasError">
      ${SPINNER}
      <div class="idp-wait-title">{{ t.usePasskey }}</div>
      <div class="idp-wait-sub">{{ t.passkeyWaiting }}</div>
    </div>
    <div v-else>
      <p class="idp-title">{{ t.usePasskey }}</p>
      <div class="idp-error">${ICON_ERROR}<span>{{ errorMessage }}</span></div>
      <button type="button" class="idp-submit" @click="chooseWebAuthn">{{ t.tryAgain }}</button>
      <div class="idp-links">
        <button type="button" class="idp-link" @click="choosePassword">{{ t.usePasswordInstead }}</button>
      </div>
    </div>
  </div>

  <div class="idp-wait" v-if="step === 'redirecting'">
    ${SPINNER}
    <div class="idp-wait-title">{{ t.redirecting }}</div>
    <div class="idp-wait-sub" v-if="sp">{{ t.redirectingTo }}{{ sp.name }}</div>
  </div>

</div>
`;

function pickLanguage(options) {
	const requested = options.lang || document.documentElement.lang || navigator.language || 'en';
	const short = String(requested).toLowerCase().split('-')[0];
	return LABELS[short] ? short : 'en';
}

function toBuffer(base64url) {
	const base64 = base64url.replace(/-/g, '+').replace(/_/g, '/');
	const padded = base64 + '==='.slice(0, (4 - base64.length % 4) % 4);
	const binary = atob(padded);
	const bytes = new Uint8Array(binary.length);
	for (let i = 0; i < binary.length; i++) {
		bytes[i] = binary.charCodeAt(i);
	}
	return bytes.buffer;
}

function toBase64Url(buffer) {
	const bytes = new Uint8Array(buffer);
	let binary = '';
	for (let i = 0; i < bytes.length; i++) {
		binary += String.fromCharCode(bytes[i]);
	}
	return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function rememberMethod(method) {
	try {
		localStorage.setItem(LAST_METHOD_KEY, method);
	} catch (_) {
		// Storage may be unavailable; the choice is a convenience only.
	}
}

function recallMethod() {
	try {
		return localStorage.getItem(LAST_METHOD_KEY);
	} catch (_) {
		return null;
	}
}

function postSamlResponse(data) {
	const form = document.createElement('form');
	form.method = 'POST';
	form.action = data.acsUrl;
	form.style.display = 'none';
	const add = (name, value) => {
		const input = document.createElement('input');
		input.type = 'hidden';
		input.name = name;
		input.value = value;
		form.appendChild(input);
	};
	add('SAMLResponse', data.samlResponse);
	if (data.relayState) {
		add('RelayState', data.relayState);
	}
	document.body.appendChild(form);
	form.submit();
}

async function postJson(url, body) {
	const res = await fetch(url, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		credentials: 'same-origin',
		body: JSON.stringify(body || {}),
	});
	let json = null;
	try {
		json = await res.json();
	} catch (_) {
		json = null;
	}
	return { ok: res.ok, json: json || {} };
}

function mount() {
	const options = window.IDP_LOGIN || {};
	const lang = pickLanguage(options);
	const labels = Object.assign({}, LABELS.en, LABELS[lang], options.labels || {});
	const root = document.querySelector(options.mount || '#idp-login');
	if (!root) {
		console.error('IdP login: mount element not found');
		return;
	}
	const style = document.createElement('style');
	style.textContent = STYLE;
	document.head.insertBefore(style, document.head.firstChild);
	root.innerHTML = TEMPLATE;

	VDOM.createApp({
		data() {
			return {
				t: labels,
				step: 'loading',      // method | username | password | totp | webauthn | redirecting
				methods: { password: true, webauthn: false },
				lastMethod: recallMethod(),
				sp: null,
				username: '',
				password: '',
				code: '',
				useBackupCode: false,
				showPassword: false,
				errorMessage: '',
				busy: false,
			};
		},

		computed: {
			hasError() {
				return !!this.errorMessage;
			},
		},

		methods: {
			async init() {
				const [methods, sp] = await Promise.all([
					fetch(API.methods, { credentials: 'same-origin' }).then(r => r.ok ? r.json() : null).catch(() => null),
					fetch(API.sp, { credentials: 'same-origin' }).then(r => r.ok ? r.json() : null).catch(() => null),
				]);
				if (methods && methods.status === 'success' && methods.data) {
					this.methods = {
						password: methods.data.password !== false,
						webauthn: !!methods.data.webauthn && !!window.PublicKeyCredential,
					};
				}
				if (sp && sp.status === 'success' && sp.data) {
					this.sp = sp.data;
				}
				this.step = this.methods.webauthn ? 'method' : 'username';
				this.focus();
				root.dispatchEvent(new CustomEvent('idp-login-ready', { bubbles: true }));
			},

			focus() {
				this.$nextTick(() => {
					const target = root.querySelector('[data-idp-focus="' + (this.step === 'totp' ? 'code' : this.step) + '"]');
					if (target) {
						target.focus();
					}
				});
			},

			onEnter(e) {
				if (e.key === 'Enter') {
					this.submit();
				}
			},

			clearError() {
				this.errorMessage = '';
			},

			fail(message) {
				this.errorMessage = message || this.t.errGeneric;
				this.busy = false;
			},

			backToMethods() {
				this.step = 'method';
				this.password = '';
				this.errorMessage = '';
			},

			backToUsername() {
				this.step = 'username';
				this.password = '';
				this.errorMessage = '';
				this.focus();
			},

			choosePassword() {
				this.errorMessage = '';
				this.step = 'username';
				this.focus();
			},

			async chooseWebAuthn() {
				this.errorMessage = '';
				this.step = 'webauthn';
				if (!window.PublicKeyCredential) {
					this.fail(this.t.passkeyUnsupported);
					return;
				}
				try {
					const started = await postJson(API.webauthnOptions);
					if (!started.ok || started.json.status !== 'success') {
						this.fail(started.json.message || this.t.passkeyFailed);
						return;
					}
					const o = started.json.data;
					const credential = await navigator.credentials.get({
						publicKey: {
							challenge: toBuffer(o.challenge),
							rpId: o.rpId,
							timeout: o.timeout,
							userVerification: o.userVerification || 'required',
							allowCredentials: [],
						},
					});
					if (!credential) {
						this.fail(this.t.passkeyCancelled);
						return;
					}
					const body = {
						id: credential.id,
						rawId: toBase64Url(credential.rawId),
						type: credential.type,
						authenticatorAttachment: credential.authenticatorAttachment || null,
						response: {
							clientDataJSON: toBase64Url(credential.response.clientDataJSON),
							authenticatorData: toBase64Url(credential.response.authenticatorData),
							signature: toBase64Url(credential.response.signature),
							userHandle: credential.response.userHandle ? toBase64Url(credential.response.userHandle) : null,
						},
					};
					const verified = await postJson(API.webauthnVerify, body);
					if (verified.ok && verified.json.status === 'success') {
						rememberMethod('webauthn');
						this.step = 'redirecting';
						postSamlResponse(verified.json.data);
						return;
					}
					this.fail(verified.json.message || this.t.passkeyFailed);
				} catch (err) {
					if (err && (err.name === 'NotAllowedError' || err.name === 'AbortError')) {
						this.fail(this.t.passkeyCancelled);
					} else if (err instanceof TypeError) {
						this.fail(this.t.errNetwork);
					} else {
						this.fail(this.t.passkeyFailed);
					}
				}
			},

			submit() {
				if (this.busy) {
					return;
				}
				if (this.step === 'username') {
					if (!this.username.trim()) {
						this.fail(this.t.errUsername);
						return;
					}
					this.errorMessage = '';
					this.step = 'password';
					this.focus();
				} else if (this.step === 'password') {
					this.signInWithPassword();
				} else if (this.step === 'totp') {
					this.verifyCode();
				}
			},

			async signInWithPassword() {
				if (!this.password) {
					this.fail(this.t.errPassword);
					return;
				}
				this.errorMessage = '';
				this.busy = true;
				try {
					const result = await postJson(API.login, { username: this.username.trim(), password: this.password });
					if (result.ok && result.json.status === 'success') {
						rememberMethod('password');
						this.step = 'redirecting';
						postSamlResponse(result.json.data);
						return;
					}
					if (result.ok && result.json.status === 'mfa_required') {
						this.busy = false;
						this.password = '';
						this.code = '';
						this.useBackupCode = false;
						this.step = 'totp';
						this.focus();
						return;
					}
					this.fail(result.json.message);
				} catch (_) {
					this.fail(this.t.errNetwork);
				}
			},

			async verifyCode() {
				if (!this.code.trim()) {
					this.fail(this.t.errCode);
					return;
				}
				this.errorMessage = '';
				this.busy = true;
				try {
					const result = await postJson(API.mfa, {
						method: this.useBackupCode ? 'backupCode' : 'totp',
						code: this.code.trim(),
					});
					if (result.ok && result.json.status === 'success') {
						rememberMethod('password');
						this.step = 'redirecting';
						postSamlResponse(result.json.data);
						return;
					}
					if (result.json.code === 'RESTART') {
						this.startOver();
						this.fail(result.json.message || this.t.errRestart);
						return;
					}
					this.code = '';
					this.fail(result.json.message);
					this.focus();
				} catch (_) {
					this.fail(this.t.errNetwork);
				}
			},

			toggleBackupCode() {
				this.useBackupCode = !this.useBackupCode;
				this.code = '';
				this.errorMessage = '';
				this.focus();
			},

			startOver() {
				this.step = 'username';
				this.password = '';
				this.code = '';
				this.useBackupCode = false;
				this.errorMessage = '';
				this.busy = false;
				this.focus();
			},

			togglePassword() {
				this.showPassword = !this.showPassword;
			},
		},
	}).mount(root);
}

if (document.readyState === 'loading') {
	document.addEventListener('DOMContentLoaded', mount);
} else {
	mount();
}
