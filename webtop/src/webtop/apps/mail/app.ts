/**
 * Mail Application
 *
 * Reads the IMAP accounts of the user side by side. The server downloads the
 * mail in the background (see /etc/eip/routes/webtop/mail.xml): the first time
 * only the last N days, older mail when asked for, then whatever is new. This
 * app shows what has been downloaded and changes flags, which the server
 * carries back to the mail server. Nothing here deletes mail on the server: the
 * trash is the Webtop's own.
 *
 * Mail is written in the compose pane, which takes the reader's place. Drafts
 * are saved as one types and kept in the Webtop; sending goes through the
 * account's outgoing server, and the copy stays in the Webtop only.
 *
 * Every call goes to the system workspace, where the user's home and the Mail
 * GraphQL schema live (api.ts). Changes under the user's mail folder arrive as
 * nodeChanged events and refresh the view.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import { Editor } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import { Placeholder } from "@tiptap/extension-placeholder";
import { ApplicationInstance } from "../../services/webtop-service.js";
import { initUi } from "../../ui/index.js";
import { createShellPopupAdapter } from "../../ui/shell-popup-adapter.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";
import { createEventHub, type EventHub } from "../../realtime/event-hub.js";
import { SWATCH_COLORS, SWATCH_COLOR_MAP } from "../../lib/color-palette.js";
import {
	MailApi,
	attachmentUrl,
	type ComposeFormat,
	type ComposeMode,
	type MailAccount,
	type MailAccountInput,
	type MailAddress,
	type MailDraft,
	type MailDraftAttachment,
	type MailDraftInput,
	type MailFilter,
	type MailMessage,
	type MailSummary,
	type MailLabels,
	type Security,
} from './api.js';
import { currentToken, formatAddress, htmlToText, prefill, textToHtml } from './compose.js';

type Box = 'inbox' | 'sent' | 'unread' | 'flagged' | 'trash' | 'drafts';

interface Selection {
	/** Account id, or '' for every account. */
	accountId: string;
	box: Box;
}

// Kept out of reactive data: ichigo.js wraps data in deep Proxies.
let api: MailApi | null = null;
let hub: EventHub | null = null;
let unwatch: (() => void) | null = null;
let refreshTimer: ReturnType<typeof setTimeout> | null = null;
let seenTimer: ReturnType<typeof setTimeout> | null = null;
let statusTimer: ReturnType<typeof setTimeout> | null = null;
let confirmAction: (() => void) | null = null;
// Sequence numbers of the latest list and message requests; older replies are dropped.
let listSeq = 0;
let messageSeq = 0;

const PAGE_SIZE = 50;
// Changes under the mail folder come in bursts while a pass downloads.
const REFRESH_DELAY_MS = 1500;
// A message counts as read after it has been open this long.
const SEEN_DELAY_MS = 1000;
const OLDER_OPTIONS = [30, 90, 180, 365, 730, 1825];
// Label colors are the Webtop's shared swatch palette; the keys are what the
// server stores.
const COLOR_LABELS: Record<string, string> = SWATCH_COLORS.reduce((m, c) => { m[c.key] = c.label; return m; }, {} as Record<string, string>);
// Action ids of the shell swatch grid.
const COLOR_ACTION = 'mail-color:';
const COLOR_NONE = '__none__';
// Tag suggestions shown under a tag input, as in the inspector.
const TAG_SUGGESTIONS = 20;
let tagPopupHandle: { update(items: any[]): void; close(): void; result: Promise<any> } | null = null;

// Compose. The editor and the HTML it holds stay out of reactive data.
let editor: Editor | null = null;
let composeHtml = '';
let saveTimer: ReturnType<typeof setTimeout> | null = null;
// Saves run one after another, so a draft is never created twice.
let saveChain: Promise<void> = Promise.resolve();
let addressPopupHandle: { update(items: any[]): void; close(): void; result: Promise<any> } | null = null;
const SAVE_DELAY_MS = 2000;
const ADDRESS_SUGGESTIONS = 10;
// Counts compose sessions, so late replies for a closed one are dropped.
let composeEpoch = 0;
type AddressField = 'to' | 'cc' | 'bcc';

function emptyCompose() {
	return {
		active: false,
		id: '',
		accountId: '',
		mode: 'new' as ComposeMode,
		originalId: null as string | null,
		inReplyTo: [] as string[],
		references: [] as string[],
		to: '',
		cc: '',
		bcc: '',
		showCc: false,
		subject: '',
		format: 'text' as ComposeFormat,
		text: '',
		attachments: [] as MailDraftAttachment[],
		uploading: 0,
		saving: false,
		sending: false,
		dirty: false,
		savedAt: '',
		error: '',
	};
}

function randomKey(): string {
	const bytes = new Uint8Array(16);
	crypto.getRandomValues(bytes);
	return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function emptyAccountForm() {
	return {
		visible: false,
		id: '',
		name: '',
		address: '',
		personal: '',
		color: '',
		enabled: true,
		incoming: { host: '', port: 993, security: 'ssl' as Security, username: '' },
		outgoing: { host: '', port: 587, security: 'starttls' as Security, username: '', sameAuthentication: true },
		incomingPassword: '',
		outgoingPassword: '',
		hasIncomingPassword: false,
		hasOutgoingPassword: false,
		initialDays: 30,
		intervalMinutes: 5,
		saving: false,
		testing: false,
		testResult: null as null | { ok: boolean; messages: string[] },
		error: '',
	};
}

const DEFAULT_PORTS: Record<'incoming' | 'outgoing', Record<Security, number>> = {
	incoming: { ssl: 993, starttls: 143, none: 143 },
	outgoing: { ssl: 465, starttls: 587, none: 25 },
};

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			keyListener: null as ((event: KeyboardEvent) => void) | null,
			localization: createLocalizationSnapshot(),
			isReady: false,

			accounts: [] as MailAccount[],
			selection: { accountId: '', box: 'inbox' } as Selection,
			searchText: '',
			searchQuery: '',

			messages: [] as MailSummary[],
			hasMore: false,
			isListLoading: false,
			listError: '',
			checked: {} as Record<string, boolean>,

			current: null as MailMessage | null,
			currentId: '',
			isMessageLoading: false,
			messageError: '',
			showRemote: false,
			view: 'html' as 'html' | 'text',

			sidePanelWidth: 220,
			listPanelWidth: 380,
			isResizing: false,

			accountForm: emptyAccountForm(),
			accountsDialog: { visible: false },
			olderDialog: { visible: false, accountId: '', days: 90 },
			confirmDialog: { visible: false, title: '', message: '' },
			statusMessage: '',

			labels: { colors: [], tags: [] } as MailLabels,
			// Narrows whatever box is selected.
			labelFilter: { color: '', tag: '' },
			// Which messages the swatch grid colors: the open one or the checked ones.
			colorTarget: 'current' as 'current' | 'checked',
			// The tag input whose suggestions are shown, and the highlighted one.
			tagSuggest: { input: '' as '' | 'current' | 'bulk', items: [] as string[], index: -1 },
			tagInput: '',
			bulkTag: '',

			compose: emptyCompose(),
			drafts: [] as MailDraft[],
			recipients: [] as MailAddress[],
			savedAddresses: [] as MailAddress[],
			addressSuggest: { field: '' as '' | AddressField, items: [] as MailAddress[], index: -1 },
		};
	},
	computed: {
		boxes(): { key: Box; icon: string; label: string }[] {
			return [
				{ key: 'inbox', icon: 'bi-inbox', label: this.t('app.mail.box.inbox', undefined, 'Inbox') },
				{ key: 'sent', icon: 'bi-send', label: this.t('app.mail.box.sent', undefined, 'Sent') },
			];
		},
		views(): { key: Box; icon: string; label: string }[] {
			return [
				{ key: 'unread', icon: 'bi-envelope', label: this.t('app.mail.box.unread', undefined, 'Unread') },
				{ key: 'flagged', icon: 'bi-flag', label: this.t('app.mail.box.flagged', undefined, 'Flagged') },
				{ key: 'drafts', icon: 'bi-pencil-square', label: this.t('app.mail.box.drafts', undefined, 'Drafts') },
				{ key: 'trash', icon: 'bi-trash', label: this.t('app.mail.box.trash', undefined, 'Trash') },
			];
		},
		/** The address rows of the open message; From is always shown. */
		addressRows(): { key: string; label: string; list: MailAddress[] }[] {
			const m = this.current as MailMessage | null;
			if (!m) return [];
			return [
				{ key: 'from', label: this.t('app.mail.message.from', undefined, 'From'), list: m.from },
				{ key: 'to', label: this.t('app.mail.message.to', undefined, 'To'), list: m.to },
				{ key: 'cc', label: this.t('app.mail.message.cc', undefined, 'Cc'), list: m.cc },
				{ key: 'replyTo', label: this.t('app.mail.message.replyTo', undefined, 'Reply-To'), list: m.replyTo },
			].filter((r) => r.key === 'from' || r.list.length);
		},
		/** Saved and own addresses, lower-cased; these get no save button. */
		unsavable(): Record<string, true> {
			const map: Record<string, true> = {};
			for (const a of this.savedAddresses as MailAddress[]) map[a.address.toLowerCase()] = true;
			for (const a of this.accounts as MailAccount[]) if (a.address) map[a.address.toLowerCase()] = true;
			return map;
		},
		accountById(): Record<string, MailAccount> {
			const map: Record<string, MailAccount> = {};
			for (const a of this.accounts as MailAccount[]) map[a.id] = a;
			return map;
		},
		selectedAccount(): MailAccount | null {
			return this.selection.accountId ? this.accountById[this.selection.accountId] || null : null;
		},
		listTitle(): string {
			const box = [...this.boxes, ...this.views].find((b: { key: Box }) => b.key === this.selection.box);
			const name = this.selectedAccount ? this.selectedAccount.name : this.t('app.mail.allAccounts', undefined, 'All accounts');
			return `${box ? box.label : ''} · ${name}`;
		},
		checkedIds(): string[] {
			return Object.keys(this.checked).filter((id) => this.checked[id]);
		},
		/** Accounts in scope for the "fetch older mail" footer. */
		scopeAccounts(): MailAccount[] {
			return this.selectedAccount ? [this.selectedAccount] : this.accounts;
		},
		/** The latest date mail is complete from, across the accounts in scope. */
		oldestDateLabel(): string {
			const dates = (this.scopeAccounts as MailAccount[])
				.map((a) => a.status.oldestDate)
				.filter((d): d is string => !!d)
				.map((d) => new Date(d).getTime());
			if (!dates.length) return '';
			return this.formatDay(new Date(Math.max(...dates)));
		},
		syncingAccounts(): MailAccount[] {
			return (this.accounts as MailAccount[]).filter((a) => a.status.state === 'syncing' || a.status.more || a.status.fetchOlderDays);
		},
		errorAccounts(): MailAccount[] {
			return (this.accounts as MailAccount[]).filter((a) => a.status.state === 'error');
		},
		syncSummary(): string {
			const syncing = this.syncingAccounts as MailAccount[];
			if (syncing.length) {
				const a = syncing[0];
				const s = a.status;
				if (s.progressTotal) {
					return this.t('app.mail.status.progress', { account: a.name, done: s.progressDone || 0, total: s.progressTotal },
						'{account}: downloading {done} / {total}');
				}
				return this.t('app.mail.status.syncing', { account: a.name }, '{account}: synchronizing');
			}
			const last = (this.accounts as MailAccount[]).map((a) => a.status.lastSuccess).filter(Boolean).sort().pop();
			if (last) {
				return this.t('app.mail.status.lastSync', { time: this.formatTime(new Date(last as string)) }, 'Synchronized {time}');
			}
			return '';
		},
		currentHasRemoteContent(): boolean {
			const html = this.current?.html || '';
			return /<img[^>]+src\s*=\s*["']?\s*https?:|url\(\s*["']?\s*https?:|<link[^>]+https?:/i.test(html);
		},
		/** The document shown in the sandboxed frame. */
		messageDocument(): string {
			const m = this.current as MailMessage | null;
			if (!m) return '';
			const remote = this.showRemote ? ' https: http:' : '';
			const csp = `default-src 'none'; img-src data:${remote}; style-src 'unsafe-inline'${remote}; font-src data:${remote}; media-src data:${remote}`;
			const body = (this.view === 'html' && m.html != null) ?
				m.html :
				`<pre class="mail-text">${this.linkify(escapeHtml(m.text || ''))}</pre>`;
			return '<!DOCTYPE html><html><head><meta charset="utf-8">' +
				`<meta http-equiv="Content-Security-Policy" content="${csp}">` +
				'<base target="_blank">' +
				'<style>html,body{margin:0;background:#fff;color:#1f2328}' +
				'body{padding:16px;font:14px/1.6 system-ui,-apple-system,"Segoe UI","Hiragino Sans","Noto Sans JP",sans-serif;overflow-wrap:anywhere}' +
				'img{max-width:100%;height:auto}' +
				'pre.mail-text{margin:0;white-space:pre-wrap;font:inherit}' +
				'blockquote{margin:0 0 0 .5em;padding-left:.75em;border-left:3px solid #d0d7de;color:#57606a}</style>' +
				`</head><body>${body}</body></html>`;
		},
		visibleAttachments(): { index: number; name: string; mimeType: string }[] {
			return (this.current?.attachments || []).filter((a: { inline: boolean }) => !a.inline);
		},
		securityItems(): { value: Security; label: string }[] {
			return [
				{ value: 'ssl', label: this.t('app.mail.account.security.ssl', undefined, 'SSL/TLS') },
				{ value: 'starttls', label: this.t('app.mail.account.security.starttls', undefined, 'STARTTLS') },
				{ value: 'none', label: this.t('app.mail.account.security.none', undefined, 'None') },
			];
		},
		fromItems(): { value: string; label: string }[] {
			return (this.accounts as MailAccount[]).map((a) => ({
				value: a.id,
				label: a.personal ? `${a.personal} <${a.address}> (${a.name})` : `${a.address} (${a.name})`,
			}));
		},
		olderItems(): { value: number; label: string }[] {
			return OLDER_OPTIONS.map((d) => ({ value: d, label: this.t('app.mail.older.days', { days: d }, '{days} days') }));
		},
	},
	methods: {
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},

		onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) {
					return;
				}
				if (type === 'context-menu-action') {
					const action = String(payload.action || '');
					if (action.startsWith(COLOR_ACTION)) {
						vm.chooseColor(action.slice(COLOR_ACTION.length));
					}
					return;
				}
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);
			vm.keyListener = (event: KeyboardEvent) => vm.onKeyDown(event);
			window.addEventListener('keydown', vm.keyListener);

			window.appLaunch = async (instance: ApplicationInstance) => {
				vm.instance = this.$markRaw(instance);
				refreshLocalization(vm.localization, vm.instance);
				document.documentElement.dataset.theme = vm.instance.api.theme.currentTheme || 'light';

				try {
					await initUi({ popupAdapter: createShellPopupAdapter(instance) });
				} catch (e) {
					console.warn('[Mail] Failed to load component templates:', e);
				}

				api = new MailApi();
				instance.setBeforeCloseCallback(async () => {
					await vm.closeCompose();
					vm.stopWatching();
					return true;
				});

				vm.isReady = true;
				await new Promise<void>((resolve) => vm.$nextTick(() => resolve()));
				this.$nextTick(() => {
					instance.notifyLaunched();
				});

				await vm.loadAccounts();
				vm.loadLabels();
				vm.loadRecipients();
				vm.startWatching();
				if (!vm.accounts.length) {
					vm.openAccounts();
				} else {
					vm.loadList();
				}
			};
		},
		onUnmount() {
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
			}
			if (this.keyListener) {
				window.removeEventListener('keydown', this.keyListener);
			}
			this.stopWatching();
		},

		// =====================================================================
		// Window controls
		// =====================================================================

		onMinimizeWindow() {
			this.instance?.minimize();
		},
		onToggleMaximizeWindow() {
			this.instance?.toggleMaximize();
		},
		onCloseWindow() {
			this.instance?.requestClose();
		},

		// =====================================================================
		// Live updates
		// =====================================================================

		startWatching() {
			const userId = this.instance?.currentUser?.id;
			if (!userId) return;
			hub = createEventHub('system');
			unwatch = hub.watchNode(`/home/users/${userId}/mail`, () => this.scheduleRefresh(), true);
		},
		stopWatching() {
			if (unwatch) {
				unwatch();
				unwatch = null;
			}
			hub?.dispose();
			hub = null;
			if (refreshTimer) {
				clearTimeout(refreshTimer);
				refreshTimer = null;
			}
		},
		scheduleRefresh() {
			if (refreshTimer) clearTimeout(refreshTimer);
			refreshTimer = setTimeout(() => {
				refreshTimer = null;
				this.loadAccounts();
				this.loadList({ keep: true });
			}, REFRESH_DELAY_MS);
		},

		// =====================================================================
		// Accounts and navigation
		// =====================================================================

		async loadAccounts() {
			if (!api) return;
			try {
				this.accounts = await api.listAccounts();
			} catch (e) {
				console.warn('[Mail] Accounts could not be loaded:', e);
				this.showStatus(this.t('app.mail.error.load', undefined, 'Mail could not be loaded.'));
			}
		},
		select(accountId: string, box: Box) {
			this.selection = { accountId, box };
			this.checked = {};
			this.loadList();
		},
		isSelected(accountId: string, box: Box): boolean {
			return this.selection.accountId === accountId && this.selection.box === box;
		},
		async syncNow() {
			if (!api) return;
			try {
				await api.sync(this.selection.accountId || undefined);
				this.showStatus(this.t('app.mail.status.requested', undefined, 'Checking for new mail…'));
			} catch (e) {
				this.showError(e);
			}
		},

		// =====================================================================
		// Message list
		// =====================================================================

		currentFilter(): MailFilter {
			const s = this.selection as Selection;
			const filter: MailFilter = {};
			if (s.accountId) filter.accountIds = [s.accountId];
			if (s.box === 'inbox' || s.box === 'sent') {
				filter.role = s.box;
				filter.view = 'all';
			} else if (s.box !== 'drafts') {
				filter.view = s.box;
			}
			if (this.searchQuery) filter.text = this.searchQuery;
			if (this.labelFilter.color) filter.color = this.labelFilter.color;
			if (this.labelFilter.tag) filter.tag = this.labelFilter.tag;
			return filter;
		},
		/**
		 * Loads the first page. With `keep`, a refresh after background changes:
		 * as many rows as are shown are reloaded and the selection stays.
		 */
		async loadList(options: { keep?: boolean } = {}) {
			if (!api) return;
			if (this.selection.box === 'drafts') {
				await this.loadDrafts();
				return;
			}
			const seq = ++listSeq;
			const limit = options.keep ? Math.max(PAGE_SIZE, this.messages.length) : PAGE_SIZE;
			if (!options.keep) {
				this.isListLoading = true;
			}
			try {
				const page = await api.listMessages(this.currentFilter(), 0, limit);
				if (seq !== listSeq) return;
				this.messages = page.items;
				this.hasMore = page.hasMore;
				this.listError = '';
				if (this.currentId) {
					const row = page.items.find((m) => m.id === this.currentId);
					if (row && this.current) {
						this.current.seen = row.seen;
						this.current.flagged = row.flagged;
						this.current.trashed = row.trashed;
					}
				}
			} catch (e) {
				if (seq !== listSeq) return;
				console.warn('[Mail] Messages could not be loaded:', e);
				this.listError = this.t('app.mail.error.load', undefined, 'Mail could not be loaded.');
			} finally {
				if (seq === listSeq) this.isListLoading = false;
			}
		},
		async loadMore() {
			if (!api || !this.hasMore || this.isListLoading || this.selection.box === 'drafts') return;
			const seq = ++listSeq;
			this.isListLoading = true;
			try {
				const page = await api.listMessages(this.currentFilter(), this.messages.length, PAGE_SIZE);
				if (seq !== listSeq) return;
				const known = new Set((this.messages as MailSummary[]).map((m) => m.id));
				this.messages = [...this.messages, ...page.items.filter((m) => !known.has(m.id))];
				this.hasMore = page.hasMore;
			} catch (e) {
				if (seq === listSeq) this.showError(e);
			} finally {
				if (seq === listSeq) this.isListLoading = false;
			}
		},
		onListScroll(event: Event) {
			const el = event.target as HTMLElement;
			if (el.scrollTop + el.clientHeight >= el.scrollHeight - 200) {
				this.loadMore();
			}
		},

		// ---- Search ----

		onSearch() {
			const q = (this.searchText || '').trim();
			if (q === this.searchQuery) return;
			this.searchQuery = q;
			this.loadList();
		},
		onSearchInput(text: string) {
			if (!(text || '').trim() && this.searchQuery) {
				this.onSearchClear();
			}
		},
		onSearchClear() {
			this.searchText = '';
			if (!this.searchQuery) return;
			this.searchQuery = '';
			this.loadList();
		},

		// =====================================================================
		// Reading
		// =====================================================================

		async open(summary: MailSummary) {
			if (!api) return;
			if (this.compose.active) await this.closeCompose();
			const seq = ++messageSeq;
			if (seenTimer) {
				clearTimeout(seenTimer);
				seenTimer = null;
			}
			this.currentId = summary.id;
			this.isMessageLoading = true;
			this.messageError = '';
			this.showRemote = false;
			try {
				const m = await api.getMessage(summary.id);
				if (seq !== messageSeq) return;
				this.current = m;
				this.view = (m && m.html != null) ? 'html' : 'text';
				if (!m) {
					this.messageError = this.t('app.mail.message.gone', undefined, 'This message is no longer available.');
				} else if (!m.seen) {
					seenTimer = setTimeout(() => {
						seenTimer = null;
						if (this.currentId === m.id) this.setSeen([m.id], true);
					}, SEEN_DELAY_MS);
				}
			} catch (e) {
				if (seq !== messageSeq) return;
				console.warn('[Mail] Message could not be loaded:', e);
				this.current = null;
				this.messageError = this.t('app.mail.message.loadFailed', undefined, 'The message could not be opened.');
			} finally {
				if (seq === messageSeq) this.isMessageLoading = false;
			}
		},
		/** Moves the selection up or down the list. */
		step(delta: number) {
			const list = this.messages as MailSummary[];
			if (!list.length) return;
			const i = list.findIndex((m) => m.id === this.currentId);
			const next = list[Math.min(list.length - 1, Math.max(0, (i < 0 ? -1 : i) + delta))];
			if (next && next.id !== this.currentId) {
				this.open(next);
				this.$nextTick(() => {
					document.querySelector(`[data-id="${CSS.escape(next.id)}"]`)?.scrollIntoView({ block: 'nearest' });
				});
			}
		},
		onKeyDown(event: KeyboardEvent) {
			const target = event.target as HTMLElement;
			if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)) {
				return;
			}
			if (this.accountsDialog.visible || this.olderDialog.visible || this.confirmDialog.visible || this.compose.active) return;
			if (event.key === 'ArrowDown' || event.key === 'j') {
				event.preventDefault();
				this.step(1);
			} else if (event.key === 'ArrowUp' || event.key === 'k') {
				event.preventDefault();
				this.step(-1);
			} else if (event.key === 'Delete' && this.current) {
				event.preventDefault();
				this.trashCurrent();
			} else if (event.key === 'F5') {
				event.preventDefault();
				this.syncNow();
			}
		},

		// ---- Flags ----

		/** Applies a change to the rows shown, then stores it. */
		patchRows(ids: string[], patch: Partial<MailSummary>) {
			const set = new Set(ids);
			this.messages = (this.messages as MailSummary[]).map((m) => set.has(m.id) ? { ...m, ...patch } : m);
			if (this.current && set.has(this.current.id)) {
				Object.assign(this.current, patch);
			}
		},
		async setSeen(ids: string[], seen: boolean) {
			if (!api || !ids.length) return;
			this.patchRows(ids, { seen });
			try {
				await api.setFlags(ids, { seen });
			} catch (e) {
				this.showError(e);
				this.loadList({ keep: true });
			}
		},
		async setFlagged(ids: string[], flagged: boolean) {
			if (!api || !ids.length) return;
			this.patchRows(ids, { flagged });
			try {
				await api.setFlags(ids, { flagged });
			} catch (e) {
				this.showError(e);
				this.loadList({ keep: true });
			}
		},
		async setTrashed(ids: string[], trashed: boolean) {
			if (!api) return;
			if (trashed) {
				// Locked mail stays where it is.
				const locked = new Set((this.messages as MailSummary[]).filter((m) => m.locked).map((m) => m.id));
				if (this.current?.locked) locked.add(this.current.id);
				const skipped = ids.filter((id) => locked.has(id)).length;
				ids = ids.filter((id) => !locked.has(id));
				if (skipped) {
					this.showStatus(this.t('app.mail.lock.notTrashed', undefined, 'Locked mail is not moved to the trash.'));
				}
			}
			if (!ids.length) return;
			try {
				await api.setTrashed(ids, trashed);
				const set = new Set(ids);
				const list = this.messages as MailSummary[];
				const index = list.findIndex((m) => m.id === this.currentId);
				this.messages = list.filter((m) => !set.has(m.id));
				this.checked = {};
				if (this.current && set.has(this.current.id)) {
					const next = this.messages[Math.min(index, this.messages.length - 1)];
					this.current = null;
					this.currentId = '';
					if (next) this.open(next);
				}
			} catch (e) {
				this.showError(e);
			}
		},
		toggleCurrentFlag() {
			if (this.current) this.setFlagged([this.current.id], !this.current.flagged);
		},
		markCurrentUnread() {
			if (!this.current) return;
			if (seenTimer) {
				clearTimeout(seenTimer);
				seenTimer = null;
			}
			this.setSeen([this.current.id], !this.current.seen);
		},
		trashCurrent() {
			if (this.current) this.setTrashed([this.current.id], !this.current.trashed);
		},
		toggleChecked(id: string) {
			this.checked = { ...this.checked, [id]: !this.checked[id] };
		},
		clearChecked() {
			this.checked = {};
		},
		checkedAction(action: 'read' | 'unread' | 'flag' | 'unflag' | 'trash' | 'restore') {
			const ids = this.checkedIds as string[];
			switch (action) {
				case 'read': this.setSeen(ids, true); break;
				case 'unread': this.setSeen(ids, false); break;
				case 'flag': this.setFlagged(ids, true); break;
				case 'unflag': this.setFlagged(ids, false); break;
				case 'trash': this.setTrashed(ids, true); return;
				case 'restore': this.setTrashed(ids, false); return;
			}
			this.checked = {};
		},

		// ---- Colors, tags, locks ----

		async loadLabels() {
			if (!api) return;
			try {
				this.labels = await api.listLabels();
			} catch (e) {
				console.warn('[Mail] Labels could not be loaded:', e);
			}
		},
		colorHex(color: string | null): string {
			return (color && SWATCH_COLOR_MAP[color]) || '';
		},
		colorLabel(color: string): string {
			return this.t(`app.mail.color.${color}`, undefined, COLOR_LABELS[color] || color);
		},
		/** Narrows the list to a color or a tag; again to clear it. */
		toggleLabelFilter(kind: 'color' | 'tag', value: string) {
			const f = this.labelFilter;
			f[kind] = f[kind] === value ? '' : value;
			this.checked = {};
			this.loadList();
		},
		clearLabelFilter(kind: 'color' | 'tag') {
			this.labelFilter[kind] = '';
			this.loadList();
		},
		/**
		 * Raises the shell's swatch grid under the clicked button, as the EIP
		 * Console's band colors and the Memo text colors do. The choice comes back
		 * as a 'context-menu-action' message (see onMounted).
		 */
		openColorMenu(target: 'current' | 'checked', event: MouseEvent) {
			this.colorTarget = target;
			const current = target === 'current' ? (this.current?.color || '') : '';
			const items: any[] = SWATCH_COLORS.map((c) => ({
				id: COLOR_ACTION + c.key,
				label: this.colorLabel(c.key),
				swatch: c.value,
				selected: c.key === current,
			}));
			items.push({
				id: COLOR_ACTION + COLOR_NONE,
				label: this.t('app.mail.labels.noColor', undefined, 'No color'),
				icon: 'bi-x-lg',
				selected: target === 'current' && !current,
			});
			const button = (event.currentTarget as HTMLElement) || (event.target as HTMLElement);
			const r = button.getBoundingClientRect();
			try {
				window.parent.postMessage({
					type: 'show-context-menu',
					x: r.left,
					y: r.bottom + 4,
					variant: 'swatch-grid',
					columns: 6,
					items,
					sourceAppId: this.instance?.id,
				}, window.location.origin);
			} catch { /* parent unavailable */ }
		},
		async chooseColor(key: string) {
			const color = key === COLOR_NONE ? '' : key;
			const ids = this.colorTarget === 'checked' ? this.checkedIds as string[] : (this.current ? [this.current.id] : []);
			if (!ids.length) return;
			await this.applyLabels(ids, { color }, { color: color || null });
		},

		// ---- Tag suggestions (the inspector's suggestion popup) ----

		tagInputValue(input: 'current' | 'bulk'): string {
			return input === 'current' ? this.tagInput : this.bulkTag;
		},
		/** Known tags matching what is typed; all of them for an empty input. */
		refreshTagSuggestions(input: 'current' | 'bulk') {
			const query = this.tagInputValue(input).trim().toLowerCase();
			const taken: string[] = input === 'current' ? (this.current?.tags || []) : [];
			const items = (this.labels.tags as string[]).
				filter((t) => !taken.includes(t) && (!query || t.toLowerCase().includes(query))).
				slice(0, TAG_SUGGESTIONS);
			this.tagSuggest = { input, items, index: -1 };
			this.showTagSuggestions();
		},
		showTagSuggestions() {
			const s = this.tagSuggest;
			if (!s.input || !s.items.length || !this.instance) {
				this.closeTagSuggestions();
				return;
			}
			const items = (s.items as string[]).map((t, i) => ({ id: t, label: t, highlighted: i === s.index }));
			if (tagPopupHandle) {
				tagPopupHandle.update(items);
				return;
			}
			const el = this.$refs[s.input === 'current' ? 'tagInput' : 'bulkTagInput'] as HTMLInputElement | undefined;
			if (!el) return;
			const rect = el.getBoundingClientRect();
			const input = s.input;
			tagPopupHandle = this.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: Math.max(rect.width, 200),
				maxHeight: 360,
				items,
			});
			tagPopupHandle!.result.then((picked: any) => {
				tagPopupHandle = null;
				if (picked == null) return;
				this.pickTag(input, String(picked));
			});
		},
		closeTagSuggestions() {
			if (tagPopupHandle) {
				tagPopupHandle.close();
				tagPopupHandle = null;
			}
		},
		onTagBlur() {
			// Later than the popup's own click, which would otherwise be lost.
			setTimeout(() => {
				if (document.activeElement !== this.$refs.tagInput && document.activeElement !== this.$refs.bulkTagInput) {
					this.closeTagSuggestions();
				}
			}, 200);
		},
		onTagKeydown(input: 'current' | 'bulk', event: KeyboardEvent) {
			const s = this.tagSuggest;
			if (event.key === 'ArrowDown' && s.items.length) {
				event.preventDefault();
				s.index = Math.min(s.index + 1, s.items.length - 1);
				this.showTagSuggestions();
			} else if (event.key === 'ArrowUp' && s.items.length) {
				event.preventDefault();
				s.index = Math.max(s.index - 1, -1);
				this.showTagSuggestions();
			} else if (event.key === 'Escape') {
				this.closeTagSuggestions();
			} else if (event.key === 'Enter') {
				event.preventDefault();
				const value = s.index >= 0 ? s.items[s.index] : this.tagInputValue(input);
				this.pickTag(input, value);
			}
		},
		pickTag(input: 'current' | 'bulk', value: string) {
			this.closeTagSuggestions();
			this.tagSuggest = { input: '', items: [], index: -1 };
			if (input === 'current') {
				this.tagInput = '';
				if (this.current) this.addTag([this.current.id], value);
			} else {
				this.bulkTag = '';
				this.addTag(this.checkedIds as string[], value);
			}
		},
		async addTag(ids: string[], value: string) {
			const tag = (value || '').replace(/\s+/g, ' ').trim();
			if (!tag || !ids.length) return;
			await this.applyLabels(ids, { addTags: [tag] }, null, (m: { tags: string[] }) => ({
				tags: m.tags.includes(tag) ? m.tags : [...m.tags, tag],
			}));
			if (!this.labels.tags.includes(tag)) this.loadLabels();
		},
		async removeTag(ids: string[], tag: string) {
			await this.applyLabels(ids, { removeTags: [tag] }, null, (m: { tags: string[] }) => ({
				tags: m.tags.filter((t) => t !== tag),
			}));
		},
		/**
		 * Stores label changes and shows them at once. `patch` applies to every row,
		 * `each` computes a per-row change.
		 */
		async applyLabels(ids: string[], change: { color?: string; addTags?: string[]; removeTags?: string[] },
			patch: Partial<MailSummary> | null, each?: (m: MailSummary) => Partial<MailSummary>) {
			if (!api) return;
			const set = new Set(ids);
			const update = (m: any) => ({ ...(patch || {}), ...(each ? each(m) : {}) });
			this.messages = (this.messages as MailSummary[]).map((m) => set.has(m.id) ? { ...m, ...update(m) } : m);
			if (this.current && set.has(this.current.id)) {
				Object.assign(this.current, update(this.current));
			}
			try {
				await api.setLabels(ids, change);
			} catch (e) {
				this.showError(e);
				this.loadList({ keep: true });
			}
		},
		/** Locks or unlocks messages; a locked message cannot be moved to the trash. */
		async setLocked(ids: string[], locked: boolean) {
			if (!api || !ids.length) return;
			this.patchRows(ids, { locked });
			try {
				await api.setLocked(ids, locked);
			} catch (e) {
				this.showError(e);
				this.loadList({ keep: true });
			}
		},
		toggleCurrentLock() {
			if (this.current) this.setLocked([this.current.id], !this.current.locked);
		},

		// ---- Attachments ----

		attachmentHref(index: number): string {
			return this.current ? attachmentUrl(this.current.id, { index }) : '#';
		},
		rawHref(): string {
			return this.current ? attachmentUrl(this.current.id, { raw: true }) : '#';
		},

		// =====================================================================
		// Older mail
		// =====================================================================

		openOlder(accountId?: string) {
			const id = accountId || this.selection.accountId || (this.accounts[0]?.id ?? '');
			if (!id) return;
			this.olderDialog = { visible: true, accountId: id, days: 90 };
		},
		closeOlder() {
			this.olderDialog.visible = false;
		},
		async fetchOlder() {
			if (!api) return;
			const d = this.olderDialog;
			const ids = d.accountId === '*' ? (this.accounts as MailAccount[]).map((a) => a.id) : [d.accountId];
			try {
				for (const id of ids) {
					await api.fetchOlder(id, Number(d.days));
				}
				d.visible = false;
				this.showStatus(this.t('app.mail.older.requested', undefined, 'Older mail will be downloaded in the background.'));
				this.loadAccounts();
			} catch (e) {
				this.showError(e);
			}
		},
		olderAccountItems(): { value: string; label: string }[] {
			const items = (this.accounts as MailAccount[]).map((a) => ({ value: a.id, label: a.name }));
			if (items.length > 1) {
				items.unshift({ value: '*', label: this.t('app.mail.allAccounts', undefined, 'All accounts') });
			}
			return items;
		},

		// =====================================================================
		// Account settings
		// =====================================================================

		openAccounts() {
			this.accountsDialog.visible = true;
			if (!this.accounts.length) {
				this.newAccount();
			} else if (!this.accountForm.id) {
				this.editAccount(this.accounts[0]);
			}
		},
		closeAccounts() {
			this.accountsDialog.visible = false;
			this.accountForm = emptyAccountForm();
		},
		newAccount() {
			this.accountForm = { ...emptyAccountForm(), visible: true };
		},
		editAccount(a: MailAccount) {
			this.accountForm = {
				...emptyAccountForm(),
				visible: true,
				id: a.id,
				name: a.name,
				address: a.address,
				personal: a.personal || '',
				color: a.color || '',
				enabled: a.enabled,
				incoming: {
					host: a.incoming.host,
					port: a.incoming.port,
					security: a.incoming.security,
					username: a.incoming.username || '',
				},
				outgoing: {
					host: a.outgoing.host,
					port: a.outgoing.port,
					security: a.outgoing.security,
					username: a.outgoing.username || '',
					sameAuthentication: a.outgoing.sameAuthentication !== false,
				},
				hasIncomingPassword: a.hasIncomingPassword,
				hasOutgoingPassword: a.hasOutgoingPassword,
				initialDays: a.initialDays,
				intervalMinutes: a.intervalMinutes,
			};
		},
		/** Fills in the server names and user name from the address, for a new account. */
		onAddressInput() {
			const f = this.accountForm;
			if (f.id) return;
			const m = /^([^@\s]+)@([^@\s]+)$/.exec(f.address.trim());
			if (!m) return;
			if (!f.incoming.host) f.incoming.host = `imap.${m[2]}`;
			if (!f.outgoing.host) f.outgoing.host = `smtp.${m[2]}`;
		},
		/** Follows the port when it still is a default one. */
		onSecurityChange(kind: 'incoming' | 'outgoing') {
			this.accountForm.testResult = null;
			// Read after the v-model update has been applied.
			this.$nextTick(() => {
				const server = this.accountForm[kind];
				const security = server.security as Security;
				const ports = Object.values(DEFAULT_PORTS[kind]);
				if (!server.port || ports.includes(Number(server.port))) {
					server.port = DEFAULT_PORTS[kind][security];
				}
			});
		},
		/** "host:port" or a URL typed into the server field: the port goes to its own field. */
		onHostBlur(kind: 'incoming' | 'outgoing') {
			const server = this.accountForm[kind];
			let host = (server.host || '').trim().replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').replace(/\/.*$/, '');
			const m = /^(.*):(\d{1,5})$/.exec(host);
			if (m) {
				host = m[1];
				server.port = Number(m[2]);
			}
			server.host = host;
		},
		/** A test result is only valid for the settings it was made with. */
		onFormInput() {
			this.accountForm.testResult = null;
		},
		formInput(): MailAccountInput {
			const f = this.accountForm;
			const input: MailAccountInput = {
				name: f.name.trim(),
				address: f.address.trim(),
				personal: f.personal.trim(),
				color: f.color,
				enabled: f.enabled,
				incoming: {
					host: f.incoming.host.trim(),
					port: Number(f.incoming.port),
					security: f.incoming.security,
					username: f.incoming.username.trim() || f.address.trim(),
				},
				outgoing: {
					host: f.outgoing.host.trim(),
					port: Number(f.outgoing.port),
					security: f.outgoing.security,
					username: f.outgoing.username.trim() || f.address.trim(),
					sameAuthentication: f.outgoing.sameAuthentication,
				},
				initialDays: Number(f.initialDays),
				intervalMinutes: Number(f.intervalMinutes),
			};
			if (f.id) input.id = f.id;
			if (f.incomingPassword) input.incomingPassword = f.incomingPassword;
			if (f.outgoingPassword) input.outgoingPassword = f.outgoingPassword;
			return input;
		},
		validateForm(): boolean {
			this.onHostBlur('incoming');
			this.onHostBlur('outgoing');
			const f = this.accountForm;
			if (!/^[^@\s]+@[^@\s]+$/.test(f.address.trim())) {
				f.error = this.t('app.mail.account.error.address', undefined, 'Enter a valid mail address.');
				return false;
			}
			if (!f.incoming.host.trim() || !f.outgoing.host.trim()) {
				f.error = this.t('app.mail.account.error.host', undefined, 'Enter the server host names.');
				return false;
			}
			if (!f.id && !f.incomingPassword) {
				f.error = this.t('app.mail.account.error.password', undefined, 'Enter the password.');
				return false;
			}
			const days = Number(f.initialDays);
			if (!Number.isInteger(days) || days < 1 || days > 3650) {
				f.error = this.t('app.mail.account.error.days', undefined, 'Enter the number of days between 1 and 3650.');
				return false;
			}
			f.error = '';
			return true;
		},
		async testAccount() {
			if (!api || !this.validateForm()) return;
			const f = this.accountForm;
			f.testing = true;
			f.testResult = null;
			try {
				const result = await api.testAccount(this.formInput());
				f.testResult = { ok: result.ok, messages: result.messages };
			} catch (e) {
				f.testResult = { ok: false, messages: [errorText(e)] };
			} finally {
				f.testing = false;
			}
		},
		async saveAccount() {
			if (!api || !this.validateForm()) return;
			const f = this.accountForm;
			f.saving = true;
			try {
				const saved = await api.saveAccount(this.formInput());
				await this.loadAccounts();
				const fresh = (this.accounts as MailAccount[]).find((a) => a.id === saved.id) || saved;
				this.editAccount(fresh);
				this.showStatus(this.t('app.mail.account.saved', undefined, 'Account saved. Mail is downloaded in the background.'));
				this.loadList();
			} catch (e) {
				f.error = errorText(e);
			} finally {
				f.saving = false;
			}
		},
		async confirmRemoveAccount() {
			const f = this.accountForm;
			if (!f.id) return;
			const id = f.id;
			const name = f.name || f.address;
			// Locked mail does not stop the removal; the question says it is there.
			let hasLocked = false;
			try {
				hasLocked = (await api!.listMessages({ accountIds: [id], locked: true }, 0, 1)).items.length > 0;
			} catch (e) {
				console.warn('[Mail] Locked mail could not be checked:', e);
			}
			this.openConfirm(
				this.t('app.mail.account.remove', undefined, 'Remove account'),
				hasLocked ?
					this.t('app.mail.account.removeConfirmLocked', { name },
						'{name} has locked mail. Remove the account and the mail downloaded for it, locked mail included, from the Webtop? The mail on the server stays.') :
					this.t('app.mail.account.removeConfirm', { name },
						'Remove {name} and the mail downloaded for it from the Webtop? The mail on the server stays.'),
				async () => {
					try {
						await api!.removeAccount(id);
						if (this.selection.accountId === id) this.selection = { accountId: '', box: 'inbox' };
						await this.loadAccounts();
						if (this.accounts.length) this.editAccount(this.accounts[0]);
						else this.newAccount();
						this.loadList();
					} catch (e) {
						this.showError(e);
					}
				},
			);
		},

		// =====================================================================
		// Drafts and compose
		// =====================================================================

		async loadDrafts() {
			if (!api) return;
			const seq = ++listSeq;
			try {
				const list = await api.listDrafts();
				if (seq !== listSeq) return;
				const accountId = this.selection.accountId;
				this.drafts = accountId ? list.filter((d) => d.accountId === accountId) : list;
				this.messages = [];
				this.hasMore = false;
				this.listError = '';
			} catch (e) {
				if (seq !== listSeq) return;
				console.warn('[Mail] Drafts could not be loaded:', e);
				this.listError = this.t('app.mail.error.load', undefined, 'Mail could not be loaded.');
			} finally {
				if (seq === listSeq) this.isListLoading = false;
			}
		},
		async loadRecipients() {
			if (!api) return;
			try {
				const [recipients, saved] = await Promise.all([api.listRecipients(), api.listSavedAddresses()]);
				this.recipients = recipients;
				this.savedAddresses = saved;
			} catch (e) {
				console.warn('[Mail] Recipients could not be loaded:', e);
			}
		},
		canSaveAddress(a: MailAddress): boolean {
			return !this.unsavable[a.address.toLowerCase()];
		},
		async saveAddress(a: MailAddress) {
			if (!api || !this.canSaveAddress(a)) return;
			const saved = { name: a.name, address: a.address };
			this.savedAddresses = [saved, ...(this.savedAddresses as MailAddress[])];
			try {
				await api.saveAddress(saved);
				this.showStatus(this.t('app.mail.address.saved', { address: a.address }, 'Saved {address} for suggestions.'));
			} catch (e) {
				this.savedAddresses = (this.savedAddresses as MailAddress[]).filter((s) => s !== saved);
				this.showStatus(errorText(e));
			}
		},
		async forgetAddress(a: MailAddress) {
			if (!api) return;
			const key = a.address.toLowerCase();
			this.savedAddresses = (this.savedAddresses as MailAddress[]).filter((s) => s.address.toLowerCase() !== key);
			this.recipients = (this.recipients as MailAddress[]).filter((s) => s.address.toLowerCase() !== key);
			try {
				await api.forgetAddress(a.address);
			} catch (e) {
				this.showStatus(errorText(e));
				this.loadRecipients();
			}
		},
		ownAddresses(): Set<string> {
			return new Set((this.accounts as MailAccount[]).map((a) => a.address.toLowerCase()));
		},

		newMessage() {
			const accountId = this.selection.accountId || this.accounts[0]?.id;
			if (!accountId) {
				this.openAccounts();
				return;
			}
			this.startCompose({ accountId, mode: 'new', format: 'text', to: '', cc: '', bcc: '', subject: '', text: '' });
		},
		replyCurrent(mode: 'reply' | 'replyAll' | 'forward') {
			const m = this.current as MailMessage | null;
			if (!m) return;
			const input = prefill(m, mode, this.ownAddresses(), this.formatFullDate(m.sentDate || m.receivedDate), {
				wrote: (date, name) => this.t('app.mail.compose.wrote', { date, name }, 'On {date}, {name} wrote:'),
				forwarded: this.t('app.mail.compose.forwarded', undefined, 'Forwarded message'),
				from: this.t('app.mail.message.from', undefined, 'From'),
				to: this.t('app.mail.message.to', undefined, 'To'),
				cc: this.t('app.mail.message.cc', undefined, 'Cc'),
				date: this.t('app.mail.message.date', undefined, 'Date'),
				subject: this.t('app.mail.compose.subject', undefined, 'Subject'),
			});
			this.startCompose(input);
		},
		openDraft(d: MailDraft) {
			this.startCompose(d, d.id);
		},
		/** Shows the compose pane in place of the reader. */
		async startCompose(input: MailDraftInput | MailDraft, id?: string) {
			if (this.compose.active) await this.closeCompose();
			const c = emptyCompose();
			c.active = true;
			c.id = id || '';
			c.accountId = input.accountId || '';
			c.mode = input.mode || 'new';
			c.originalId = input.originalId || null;
			c.inReplyTo = [...(input.inReplyTo || [])];
			c.references = [...(input.references || [])];
			c.to = input.to || '';
			c.cc = input.cc || '';
			c.bcc = input.bcc || '';
			c.showCc = !!(c.cc || c.bcc);
			c.subject = input.subject || '';
			c.format = input.format || 'text';
			c.text = input.text || '';
			c.attachments = (input.attachments || []).map((a: any) => ({
				key: a.key || '',
				name: a.name,
				mimeType: a.mimeType || 'application/octet-stream',
				size: a.size ?? null,
				forwarded: !!a.messageId,
				messageId: a.messageId || null,
				index: a.index ?? null,
			}));
			composeHtml = input.html || '';
			composeEpoch++;
			this.compose = c;
			if (c.format === 'html') {
				this.$nextTick(() => this.createEditor(composeHtml, !id));
			} else {
				this.$nextTick(() => this.focusCompose(!id));
			}
			// A reply or a forward is a draft from the start: its attachments come
			// from the original.
			if (!id && c.mode !== 'new') {
				c.dirty = true;
				this.saveDraft();
			}
		},
		focusCompose(atBody: boolean) {
			const c = this.compose;
			if (atBody && c.to) {
				if (editor) {
					editor.commands.focus('start');
				} else {
					const area = this.$refs.composeText as HTMLTextAreaElement | undefined;
					area?.focus();
					area?.setSelectionRange(0, 0);
				}
			} else {
				(this.$refs.composeTo as HTMLInputElement | undefined)?.focus();
			}
		},
		createEditor(html: string, focus = false) {
			this.destroyEditor();
			const host = this.$refs.composeEditorHost as HTMLElement | undefined;
			if (!host) return;
			const mount = document.createElement('div');
			mount.className = 'ml-editor-mount';
			host.appendChild(mount);
			editor = new Editor({
				element: mount,
				extensions: [
					StarterKit.configure({
						heading: { levels: [1, 2, 3] },
						link: { openOnClick: false, autolink: true, HTMLAttributes: { rel: 'noopener noreferrer', target: '_blank' } },
					}),
					Placeholder.configure({ placeholder: this.t('app.mail.compose.placeholder', undefined, 'Write your message') }),
				],
				content: html,
				onUpdate: () => {
					composeHtml = editor ? editor.getHTML() : composeHtml;
					this.markDirty();
				},
			});
			if (focus) this.focusCompose(true);
		},
		destroyEditor() {
			if (editor) {
				composeHtml = editor.getHTML();
				const mount = editor.options.element as HTMLElement | undefined;
				editor.destroy();
				mount?.remove?.();
				editor = null;
			}
		},
		editorCommand(name: string) {
			if (!editor) return;
			const chain = editor.chain().focus() as any;
			switch (name) {
				case 'bold': chain.toggleBold(); break;
				case 'italic': chain.toggleItalic(); break;
				case 'underline': chain.toggleUnderline(); break;
				case 'strike': chain.toggleStrike(); break;
				case 'bulletList': chain.toggleBulletList(); break;
				case 'orderedList': chain.toggleOrderedList(); break;
				case 'blockquote': chain.toggleBlockquote(); break;
				case 'clear': chain.unsetAllMarks().clearNodes(); break;
			}
			chain.run();
		},
		/** Switches between text and HTML. Formatting is lost on the way to text. */
		setComposeFormat(format: ComposeFormat) {
			const c = this.compose;
			if (c.format === format) return;
			if (format === 'html') {
				composeHtml = textToHtml(c.text);
				c.format = 'html';
				this.$nextTick(() => this.createEditor(composeHtml));
				this.markDirty();
				return;
			}
			this.openConfirm(
				this.t('app.mail.compose.toText', undefined, 'Switch to text'),
				this.t('app.mail.compose.toTextConfirm', undefined, 'Formatting is lost when the message is switched to text. Continue?'),
				() => {
					this.destroyEditor();
					c.text = htmlToText(composeHtml);
					composeHtml = '';
					c.format = 'text';
					this.markDirty();
				},
			);
		},
		markDirty() {
			const c = this.compose;
			if (!c.active) return;
			c.dirty = true;
			c.error = '';
			if (saveTimer) clearTimeout(saveTimer);
			saveTimer = setTimeout(() => {
				saveTimer = null;
				this.saveDraft();
			}, SAVE_DELAY_MS);
		},
		composeInput(): MailDraftInput {
			const c = this.compose;
			const html = c.format === 'html' ? (editor ? editor.getHTML() : composeHtml) : '';
			return {
				...(c.id ? { id: c.id } : { mode: c.mode, originalId: c.originalId, inReplyTo: c.inReplyTo, references: c.references }),
				accountId: c.accountId,
				to: c.to,
				cc: c.cc,
				bcc: c.bcc,
				subject: c.subject,
				format: c.format,
				text: c.format === 'html' ? htmlToText(html) : c.text,
				html,
				attachments: (c.attachments as MailDraftAttachment[]).map((a) => ({
					key: a.key || undefined,
					name: a.name,
					mimeType: a.mimeType,
					size: a.size,
					messageId: a.messageId || undefined,
					index: a.messageId ? a.index : undefined,
				})),
			};
		},
		/** Stores the draft now; saves are queued so a draft is created only once. */
		saveDraft(): Promise<void> {
			if (saveTimer) {
				clearTimeout(saveTimer);
				saveTimer = null;
			}
			const run = async () => {
				const c = this.compose;
				const epoch = composeEpoch;
				if (!api || !c.active || !c.dirty) return;
				c.dirty = false;
				c.saving = true;
				const input = this.composeInput();
				try {
					const saved = await api.saveDraft(input);
					if (epoch !== composeEpoch) return;
					c.id = saved.id;
					// Forwarded attachments get their keys from the server.
					if (c.attachments.length === saved.attachments.length) {
						c.attachments = c.attachments.map((a: MailDraftAttachment, i: number) => a.key ? a : { ...a, key: saved.attachments[i].key });
					}
					c.savedAt = this.formatTime(new Date());
				} catch (e) {
					c.dirty = true;
					c.error = this.t('app.mail.compose.saveFailed', { error: errorText(e) }, 'The draft could not be saved: {error}');
					throw e;
				} finally {
					c.saving = false;
				}
			};
			saveChain = saveChain.then(run, run);
			return saveChain.catch(() => undefined);
		},
		async ensureDraft(): Promise<string> {
			const c = this.compose;
			if (!c.id) {
				c.dirty = true;
				await this.saveDraft();
			}
			return c.id;
		},
		/**
		 * Closes the compose pane. What was written is kept as a draft, unless
		 * `discard` (after sending or discarding).
		 */
		async closeCompose(options: { discard?: boolean } = {}) {
			const c = this.compose;
			if (!c.active) return;
			this.closeAddressSuggestions();
			if (!options.discard) {
				await this.saveDraft();
			} else if (saveTimer) {
				clearTimeout(saveTimer);
				saveTimer = null;
			}
			this.destroyEditor();
			composeHtml = '';
			composeEpoch++;
			this.compose = emptyCompose();
			if (this.selection.box === 'drafts') this.loadDrafts();
		},
		confirmDiscard() {
			const c = this.compose;
			this.openConfirm(
				this.t('app.mail.compose.discard', undefined, 'Discard draft'),
				this.t('app.mail.compose.discardConfirm', undefined, 'Discard this draft and its attachments?'),
				async () => {
					const id = c.id;
					await this.closeCompose({ discard: true });
					if (id && api) {
						try {
							await saveChain;
							await api.removeDraft(id);
						} catch (e) {
							this.showError(e);
						}
					}
					if (this.selection.box === 'drafts') this.loadDrafts();
				},
			);
		},
		async sendCompose() {
			const c = this.compose;
			if (!api || c.sending || c.uploading) return;
			if (!(c.to.trim() || c.cc.trim() || c.bcc.trim())) {
				c.error = this.t('app.mail.compose.noRecipient', undefined, 'Enter at least one recipient.');
				return;
			}
			const epoch = composeEpoch;
			c.sending = true;
			c.error = '';
			try {
				c.dirty = true;
				await this.saveDraft();
				if (c.dirty || !c.id) throw new Error(c.error || 'The draft could not be saved.');
				await api.sendDraft(c.id);
				await this.closeCompose({ discard: true });
				this.showStatus(this.t('app.mail.compose.sent', undefined, 'Sent.'));
				this.loadList({ keep: true });
				this.loadRecipients();
			} catch (e) {
				if (epoch === composeEpoch) c.error = errorText(e);
			} finally {
				c.sending = false;
			}
		},

		// ---- Attachments ----

		pickFiles() {
			(this.$refs.composeFile as HTMLInputElement | undefined)?.click();
		},
		onFilesPicked(event: Event) {
			const input = event.target as HTMLInputElement;
			const files = Array.from(input.files || []);
			input.value = '';
			this.attachFiles(files);
		},
		onComposeDrop(event: DragEvent) {
			const files = Array.from(event.dataTransfer?.files || []);
			if (!files.length) return;
			event.preventDefault();
			this.attachFiles(files);
		},
		/** Uploads files into the draft's folder, then records them in the draft. */
		async attachFiles(files: File[]) {
			const c = this.compose;
			const content = this.instance?.api.systemContent;
			const userId = this.instance?.currentUser?.id;
			if (!files.length || !content || !userId) return;
			const epoch = composeEpoch;
			c.uploading += files.length;
			try {
				const id = await this.ensureDraft();
				if (!id) return;
				for (const file of files) {
					try {
						const key = randomKey();
						const mimeType = file.type || 'application/octet-stream';
						const upload = await content.initiateMultipartUpload();
						await content.appendMultipartUploadData(upload.uploadId, file);
						await content.completeMultipartUpload(upload.uploadId, `/home/users/${userId}/mail/drafts/${id}`, key, mimeType, true);
						if (epoch !== composeEpoch) return;
						c.attachments = [...c.attachments, { key, name: file.name, mimeType, size: file.size }];
						c.dirty = true;
						await this.saveDraft();
					} catch (e) {
						c.error = this.t('app.mail.compose.uploadFailed', { name: file.name, error: errorText(e) }, '{name} could not be attached: {error}');
					} finally {
						c.uploading--;
					}
				}
			} catch (e) {
				c.uploading = 0;
				this.showError(e);
			}
		},
		removeAttachment(index: number) {
			const c = this.compose;
			c.attachments = c.attachments.filter((_: MailDraftAttachment, i: number) => i !== index);
			c.dirty = true;
			this.saveDraft();
		},
		formatSize(size: number | null): string {
			if (size == null) return '';
			if (size < 1024) return `${size} B`;
			if (size < 1024 * 1024) return `${Math.round(size / 1024)} KB`;
			return `${(size / 1024 / 1024).toFixed(1)} MB`;
		},

		// ---- Address suggestions (the inspector's suggestion popup) ----

		refreshAddressSuggestions(field: AddressField) {
			const { token } = currentToken(this.compose[field]);
			const query = token.toLowerCase();
			const items = !query ? [] : this.suggestableAddresses().
				filter((r) => r.address.toLowerCase().includes(query) || (r.name || '').toLowerCase().includes(query)).
				slice(0, ADDRESS_SUGGESTIONS);
			this.addressSuggest = { field, items, index: -1 };
			this.showAddressSuggestions();
		},
		/** Saved addresses first, then those mail was sent to. */
		suggestableAddresses(): MailAddress[] {
			const seen = new Set<string>();
			return [...(this.savedAddresses as MailAddress[]), ...(this.recipients as MailAddress[])].filter((r) => {
				const key = r.address.toLowerCase();
				if (seen.has(key)) return false;
				seen.add(key);
				return true;
			});
		},
		showAddressSuggestions() {
			const s = this.addressSuggest;
			if (!s.field || !s.items.length || !this.instance) {
				this.closeAddressSuggestions();
				return;
			}
			const remove = { id: 'forget', icon: 'bi bi-x-lg', title: this.t('app.mail.address.forget', undefined, 'Remove from suggestions'), danger: true };
			const items = (s.items as MailAddress[]).map((r, i) => ({ id: String(i), label: formatAddress(r), highlighted: i === s.index, actions: [remove] }));
			if (addressPopupHandle) {
				addressPopupHandle.update(items);
				return;
			}
			const ref = { to: 'composeTo', cc: 'composeCc', bcc: 'composeBcc' }[s.field];
			const el = this.$refs[ref] as HTMLInputElement | undefined;
			if (!el) return;
			const rect = el.getBoundingClientRect();
			const field = s.field;
			addressPopupHandle = this.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: Math.max(rect.width, 240),
				maxHeight: 360,
				items,
				onAction: (itemId) => {
					const r = this.addressSuggest.items[Number(itemId)];
					if (!r) return;
					this.forgetAddress(r);
					this.refreshAddressSuggestions(field);
					el.focus();
				},
			});
			addressPopupHandle!.result.then((picked: any) => {
				addressPopupHandle = null;
				if (picked == null) return;
				const r = this.addressSuggest.items[Number(picked)];
				if (r) this.pickAddress(field, r);
			});
		},
		closeAddressSuggestions() {
			if (addressPopupHandle) {
				addressPopupHandle.close();
				addressPopupHandle = null;
			}
		},
		onAddressBlur() {
			setTimeout(() => {
				const active = document.activeElement;
				if (active !== this.$refs.composeTo && active !== this.$refs.composeCc && active !== this.$refs.composeBcc) {
					this.closeAddressSuggestions();
				}
			}, 200);
		},
		onAddressKeydown(field: AddressField, event: KeyboardEvent) {
			const s = this.addressSuggest;
			if (s.field !== field || !s.items.length) return;
			if (event.key === 'ArrowDown') {
				event.preventDefault();
				s.index = Math.min(s.index + 1, s.items.length - 1);
				this.showAddressSuggestions();
			} else if (event.key === 'ArrowUp') {
				event.preventDefault();
				s.index = Math.max(s.index - 1, -1);
				this.showAddressSuggestions();
			} else if (event.key === 'Escape') {
				this.closeAddressSuggestions();
			} else if ((event.key === 'Enter' || event.key === 'Tab') && s.index >= 0) {
				event.preventDefault();
				this.pickAddress(field, s.items[s.index]);
			}
		},
		pickAddress(field: AddressField, r: MailAddress) {
			this.closeAddressSuggestions();
			this.addressSuggest = { field: '', items: [], index: -1 };
			const { head } = currentToken(this.compose[field]);
			this.compose[field] = `${head}${formatAddress(r)}, `;
			this.markDirty();
			(this.$refs[{ to: 'composeTo', cc: 'composeCc', bcc: 'composeBcc' }[field]] as HTMLInputElement | undefined)?.focus();
		},

		// =====================================================================
		// Presentation helpers
		// =====================================================================

		accountColor(accountId: string): string {
			return this.accountById[accountId]?.color || '';
		},
		accountName(accountId: string): string {
			return this.accountById[accountId]?.name || '';
		},
		statusIcon(a: MailAccount): string {
			switch (a.status.state) {
				case 'syncing': return 'bi-arrow-repeat mail-spin';
				case 'error': return 'bi-exclamation-triangle-fill text-danger';
				default: return '';
			}
		},
		senderLabel(m: MailSummary): string {
			if (m.role === 'sent') {
				return this.t('app.mail.list.to', { to: displayName(m.to || '') }, 'To: {to}');
			}
			return displayName(m.from || '') || this.t('app.mail.list.unknownSender', undefined, '(unknown sender)');
		},
		addressLabel(a: MailAddress): string {
			return a.name ? `${a.name} <${a.address}>` : a.address;
		},
		displayLocale(): string {
			return this.localization.locale || navigator.language || 'en';
		},
		/** Time today, date within the year, full date before. */
		formatListDate(value: string | null): string {
			if (!value) return '';
			const d = new Date(value);
			const now = new Date();
			const locale = this.displayLocale();
			if (d.toDateString() === now.toDateString()) {
				return d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
			}
			if (d.getFullYear() === now.getFullYear()) {
				return d.toLocaleDateString(locale, { month: 'short', day: 'numeric' });
			}
			return d.toLocaleDateString(locale, { year: 'numeric', month: 'short', day: 'numeric' });
		},
		formatFullDate(value: string | null): string {
			if (!value) return '';
			return new Date(value).toLocaleString(this.displayLocale(), {
				year: 'numeric', month: 'short', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit',
			});
		},
		formatDay(d: Date): string {
			return d.toLocaleDateString(this.displayLocale(), { year: 'numeric', month: 'short', day: 'numeric' });
		},
		formatTime(d: Date): string {
			return d.toLocaleTimeString(this.displayLocale(), { hour: '2-digit', minute: '2-digit' });
		},
		linkify(escaped: string): string {
			return escaped.replace(/\bhttps?:\/\/[^\s<>"']+/g, (url) => `<a href="${url}" rel="noopener noreferrer">${url}</a>`);
		},
		showStatus(message: string) {
			this.statusMessage = message;
			if (statusTimer) clearTimeout(statusTimer);
			statusTimer = setTimeout(() => {
				this.statusMessage = '';
				statusTimer = null;
			}, 5000);
		},
		showError(e: unknown) {
			console.warn('[Mail]', e);
			this.showStatus(errorText(e));
		},
		openConfirm(title: string, message: string, onAccept: () => void) {
			confirmAction = onAccept;
			this.confirmDialog = { visible: true, title, message };
		},
		closeConfirmDialog() {
			this.confirmDialog.visible = false;
			confirmAction = null;
		},
		acceptConfirmDialog() {
			const fn = confirmAction;
			this.closeConfirmDialog();
			if (fn) fn();
		},
	},
};

function escapeHtml(s: string): string {
	return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/** "Name <address>" → "Name"; a bare address stays as it is. */
function displayName(value: string): string {
	return value.split(', ').map((part) => {
		const m = /^(.*?)\s*<([^>]*)>$/.exec(part);
		return m ? (m[1] || m[2]) : part;
	}).join(', ');
}

function errorText(e: unknown): string {
	return (e instanceof Error) ? e.message : String(e);
}

VDOM.createApp(App).mount('#app');
