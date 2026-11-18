// Dashboard — what needs attention, gathered in one place.
//
// The Dashboard owns no data. Apps declare sections and cards in their
// app.yml (`dashboard:`), and what a card shows — its snapshot — is made on
// the server by the card's provider and pushed here (Subscription
// dashboardCards): the current snapshot of every card the user may see first,
// then each one as it changes. This app queries nothing but the declarations
// and draws what arrives, so every Dashboard shows the same numbers and none
// of them polls. The stream reconnects by itself and the feed then sends the
// current snapshots again, so there is nothing to refresh by hand; when an
// app.yml under the apps folder changes, the declarations are read again and
// the feed opened anew. See documents/webtop-dashboard.md.
//
//   • A section gathers cards, from any app. A card declared `summary` leads
//     its section at full width, and the section can be folded to it alone
//     (remembered per user and section).
//   • A card declared `detail` can be maximized: it then fills the window and
//     shows its detail snapshot (Subscription dashboardCardDetail), which the
//     server makes only while somebody looks at it.
//   • Who sees a card is decided on the server (the card's `roles`, or the
//     app's); a section without a visible card is not shown.
//
// A snapshot is a list of blocks of a few fixed kinds (kpis, metric, rows,
// list, matrix, note). Its texts are strings or message references resolved
// here in the reader's language, and its times are ISO strings turned into
// ages here, so that one snapshot serves everyone.

import { VDOM } from '@mintjamsinc/ichigojs';
import { initUi } from "../../ui/index.js";
import { createGraphQLClient } from '../../graphql/client.js';
import UrlUtils from '../../utils/url.js';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatNumber,
	formatDate,
} from '../../composables/use-localization.js';

// Type-only: the shell passes a fully-featured ApplicationInstance at launch.
type AnyInstance = any;

// Ages and "due" pills are relative to now, so the layout is drawn again
// this often even when nothing arrives.
const REDRAW_INTERVAL_MS = 60000;

// After an app.yml changed, the declarations are read again this long after
// the last change: the server reads its own again some 3 seconds after the
// change (once the search index follows), and a feed opened before that would
// hold the cards of before.
const DECLARATIONS_SETTLE_MS = 5000;

const APPS_QUERY = `
	query DashboardApps($path: String!, $first: Int, $after: String) {
		apps(path: $path, first: $first, after: $after) {
			edges {
				node {
					identifier
					relPath
					dashboard {
						sections { identifier title icon order }
						cards { identifier section title icon order size scope summary detail open }
					}
				}
			}
			pageInfo { hasNextPage endCursor }
		}
	}
`;

const SNAPSHOT_FIELDS = 'card detail version at data';

function formatLatency(ms: number | undefined | null): string {
	const n = Number(ms);
	if (!Number.isFinite(n) || n <= 0) return '—';
	if (n < 1000) return `${Math.round(n)} ms`;
	return `${(n / 1000).toFixed(1)} s`;
}

// The distance between two moments, coarsely: "5m", "3h", "2d".
function ageLabel(a: number, b: number): string {
	const ms = Math.abs(b - a);
	const min = Math.floor(ms / 60000);
	if (min < 60) return `${min}m`;
	const hr = Math.floor(min / 60);
	if (hr < 24) return `${hr}h`;
	return `${Math.floor(hr / 24)}d`;
}

function toneClass(tone: string | undefined): string {
	return tone === 'danger' ? 'dash-value-danger' : tone === 'warning' ? 'dash-value-warn' : '';
}

function badgeTone(tone: string | undefined): string {
	return tone === 'danger' || tone === 'warning' || tone === 'success' ? tone : 'neutral';
}

const App = {
	data() {
		return {
			// Readiness gate for the whole screen (see the <template v-if> in
			// index.html). Flipped by appLaunch() once the component templates
			// are present, so no component element is connected before its
			// <template> exists.
			isReady: false,
			instance: null as AnyInstance,

			// Reactive Localization snapshot (effective locale + IANA time
			// zone + number format). See composables/use-localization.ts.
			localization: createLocalizationSnapshot(),

			view: 'loading' as 'loading' | 'error' | 'ready',
			errorMessage: '',
			// What is drawn: the sections with their cards and blocks, every
			// text resolved. Rebuilt as a whole by rebuild() and never changed
			// in place.
			layout: [] as any[],
			// Epoch ms of the last snapshot that arrived (0 = none yet).
			lastUpdated: 0,
			// Whether the stream the snapshots come by is open.
			connected: false,

			isAdmin: false,
			userId: '' as string,
			userDisplay: '' as string,

			// The card shown maximized, or '' for the dashboard.
			maximized: '' as string,

			_workspace: '' as string,
			_appsPath: '' as string,
			// relPath -> identifier of the apps the user is offered.
			_appIds: {} as Record<string, string>,
			_sections: [] as any[],
			_cards: [] as any[],
			// card id -> the latest snapshot ({ data, at }).
			_snapshots: {} as Record<string, any>,
			_detail: null as any,
			// section id -> true when folded to its summary.
			_summaryOnly: {} as Record<string, boolean>,
			_timeZone: '' as string,
			_unsubscribe: null as null | (() => void),
			_unsubscribeDetail: null as null | (() => void),
			_unwatchApps: null as null | (() => void),
			_unwatchConnection: null as null | (() => void),
			_declarationsTimer: null as any,
			_redrawTimer: null as any,
			_messageListener: null as any,
		};
	},

	computed: {
		greeting(): string {
			const name = this.userDisplay || this.userId;
			const h = new Date().getHours();
			const key = h < 5 ? 'app.dashboard.greeting.hello'
				: h < 12 ? 'app.dashboard.greeting.morning'
					: h < 18 ? 'app.dashboard.greeting.afternoon'
						: 'app.dashboard.greeting.evening';
			const fallbackPart = h < 5 ? 'Hello' : h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
			// Whole-sentence keys (named vs anonymous) so word order can differ
			// per language; never concatenate the greeting and the name.
			return name
				? this.t(key + 'Named', { name }, `${fallbackPart}, ${name}`)
				: this.t(key, undefined, fallbackPart);
		},

		lastUpdatedLabel(): string {
			if (!this.lastUpdated) return '';
			return formatDate(this.localization, this.lastUpdated, { format: 'time' });
		},
	},

	methods: {
		/**
		 * Reactive i18n lookup. Reads the localization snapshot so every
		 * `{{ t(...) }}` binding repaints the moment the user switches language
		 * or an i18n bundle is hot-reloaded. See composables/use-localization.ts.
		 */
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},

		fmtNum(n: number): string {
			if (!Number.isFinite(n)) return '0';
			return formatNumber(this.localization, n, { maximumFractionDigits: 0 });
		},

		// Compact count for the big numbers (1234 -> 1.2k / 1.2万), locale-aware.
		fmtCompact(n: number): string {
			if (!Number.isFinite(n)) return '0';
			if (Math.abs(n) >= 1000) return formatNumber(this.localization, n, { notation: 'compact', maximumFractionDigits: 1 });
			return String(n);
		},

		onMounted() {
			const vm = this;

			vm._messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const data: any = event.data || {};
				// Localization changes (locale / time zone / bundle hot-reload)
				// broadcast by the shell: the texts of the layout are resolved
				// when it is built, so build it again; a new time zone changes
				// what "due today" counts, so subscribe again.
				if (handleLocalizationMessage(data.type, vm.localization, vm.instance)) {
					if ((vm.localization?.timeZone || '') !== vm._timeZone) {
						vm.subscribe();
					}
					vm.rebuild();
					return;
				}
				if (data.type === 'theme-changed' && data.theme) {
					document.documentElement.dataset.theme = data.theme;
				}
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance) => {
				vm.instance = vm.$markRaw(instance);

				try {
					const theme = instance.api.theme.currentTheme || 'light';
					document.documentElement.dataset.theme = theme;
				} catch (_) { /* theme service unavailable */ }

				refreshLocalization(vm.localization, vm.instance);

				try { instance.windowTitle = vm.t('app.dashboard.title', undefined, 'Dashboard'); } catch (_) {}

				// --- Readiness gate ---
				// Load the component templates BEFORE the gated markup is
				// compiled, so each <wt-*> element finds its <template> on the
				// single connectedCallback it gets.
				try {
					await initUi();
				} catch (e) {
					console.warn('[Dashboard] Failed to load component templates:', e);
				}
				vm.isReady = true;
				await new Promise<void>((resolve) => vm.$nextTick(() => resolve()));

				vm.resolveUser();
				vm._workspace = (() => { try { return instance.api.workspace as string; } catch (_) { return ''; } })();
				vm._appsPath = UrlUtils.getAppsPath(instance.api.context.rootPath);
				await vm.loadFolded();

				await vm.load();
				vm._redrawTimer = window.setInterval(() => {
					if (document.visibilityState !== 'hidden') vm.rebuild();
				}, REDRAW_INTERVAL_MS);

				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},

		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._redrawTimer) clearInterval(this._redrawTimer);
			this.unsubscribe();
			this.stopWatching();
		},

		// ---- Identity --------------------------------------------------------
		resolveUser() {
			try {
				const u = (this.instance && this.instance.currentUser) || (window as any).Webtop?.currentUser;
				if (u) {
					this.userId = u.id || '';
					this.userDisplay = u.fullName || u.id || '';
					this.isAdmin = !!u.isAdmin;
				}
			} catch (_) { /* anonymous host context */ }
		},

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// ---- Links -----------------------------------------------------------
		// Launch (or re-target, for a singleton) the app a card or a row leads
		// to, with its options. The shell focuses a running app and delivers
		// `{ type: 'app-reopen', options }`.
		open(link: { appId: string; options: Record<string, any> } | null) {
			if (!link) return;
			try {
				window.parent?.postMessage({ type: 'open-app', appId: link.appId, options: link.options || {} }, window.location.origin);
			} catch (_) { /* parent unavailable */ }
		},

		// The link of an `open` value ({ app, options }): `app` is the folder of
		// the target app, the card's own app when absent. No link when the user
		// is not offered that app, so that nothing leads to a dead end.
		linkOf(open: any, ownApp: string): { appId: string; options: Record<string, any> } | null {
			if (!open || typeof open !== 'object') return null;
			const appId = this._appIds[typeof open.app === 'string' && open.app ? open.app : ownApp];
			if (!appId) return null;
			return { appId, options: (open.options && typeof open.options === 'object') ? open.options : {} };
		},

		// ---- Declarations and the feed -----------------------------------------
		async load() {
			try {
				await this.loadDeclarations();
				this.startWatching();
				this.subscribe();
				this.rebuild();
				this.view = 'ready';
			} catch (e: any) {
				this.errorMessage = (e && e.message) ? e.message : String(e);
				this.view = 'error';
			}
		},

		// From the error state: try loading again.
		async retry() {
			this.view = 'loading';
			await this.load();
		},

		// Watches what the feed itself does not tell: whether the stream is
		// open, and the app.yml files under the apps folder. Held apart from
		// the feed, so that opening the feed anew never leaves the stream with
		// nothing subscribed (which closes it).
		startWatching() {
			const hub = this.instance?.api?.eventHub;
			if (!hub) return;
			if (!this._unwatchConnection) {
				if (typeof hub.watchConnection === 'function') {
					this._unwatchConnection = hub.watchConnection((connected: boolean) => { this.connected = connected; });
				} else {
					this.connected = true;
				}
			}
			if (!this._unwatchApps && typeof hub.watchNode === 'function') {
				this._unwatchApps = hub.watchNode(this._appsPath, (event: any) => this.onAppsChanged(event), true);
			}
		},

		stopWatching() {
			if (this._declarationsTimer) {
				clearTimeout(this._declarationsTimer);
				this._declarationsTimer = null;
			}
			for (const key of ['_unwatchApps', '_unwatchConnection']) {
				if (this[key]) {
					try { this[key](); } catch { /* noop */ }
					this[key] = null;
				}
			}
		},

		// A change under the apps folder: an app.yml written or removed, or an
		// app folder added or removed. Read the declarations again once the
		// changes have settled.
		onAppsChanged(event: any) {
			const path = event && event.path;
			const prefix = this._appsPath + '/';
			if (typeof path !== 'string' || !path.startsWith(prefix)) return;
			const parts = path.slice(prefix.length).split('/');
			if (parts.length > 1 && parts[1] !== 'app.yml') return;
			if (this._declarationsTimer) clearTimeout(this._declarationsTimer);
			this._declarationsTimer = setTimeout(() => {
				this._declarationsTimer = null;
				void this.reloadDeclarations();
			}, DECLARATIONS_SETTLE_MS);
		},

		// Reads the declarations again and opens the feed anew: the server
		// fixes the cards of a feed when it opens.
		async reloadDeclarations() {
			try {
				await this.loadDeclarations();
				this.subscribe();
				this.rebuild();
			} catch (e) {
				console.warn('[Dashboard] Could not read the cards again:', e);
			}
		},

		// The sections and cards the installed apps declare. The server lists a
		// card only to who may see it.
		async loadDeclarations() {
			const client = createGraphQLClient(this._workspace);
			const appIds: Record<string, string> = {};
			const sections: Record<string, any> = {};
			const cards: any[] = [];
			let after: string | null = null;
			do {
				const result: any = await client.query(APPS_QUERY, { path: this._appsPath, first: 100, after });
				const connection = result.apps;
				for (const edge of connection.edges) {
					const app = edge.node;
					appIds[app.relPath] = app.identifier;
					for (const s of (app.dashboard?.sections || [])) {
						// Declared by several apps: the one with the lowest order names it.
						const known = sections[s.identifier];
						if (!known || s.order < known.order) {
							sections[s.identifier] = { ...s, app: app.relPath };
						}
					}
					for (const c of (app.dashboard?.cards || [])) {
						cards.push({ ...c, app: app.relPath, id: `${app.relPath}/${c.identifier}` });
					}
				}
				after = connection.pageInfo.hasNextPage ? connection.pageInfo.endCursor : null;
			} while (after);

			this._appIds = this.$markRaw(appIds);
			this._sections = this.$markRaw(Object.values(sections).sort((a: any, b: any) => (a.order - b.order) || String(a.identifier).localeCompare(String(b.identifier))));
			this._cards = this.$markRaw(cards.sort((a, b) => (a.order - b.order) || String(a.id).localeCompare(String(b.id))));
			if (this.maximized && !cards.some((c) => c.id === this.maximized && c.detail)) {
				this.maximized = '';
			}
		},

		subscribe() {
			this.unsubscribe();
			const hub = this.instance?.api?.eventHub;
			if (!hub || typeof hub.subscribe !== 'function') {
				throw new Error(this.t('app.dashboard.error.feedUnavailable', undefined, 'Live updates are unavailable.'));
			}
			this._timeZone = this.localization?.timeZone || '';
			const args = this.feedArguments();
			this._unsubscribe = hub.subscribe(
				`subscription { dashboardCards(${args}) { ${SNAPSHOT_FIELDS} } }`,
				(snapshot: any) => this.onSnapshot(snapshot));
			if (this.maximized) {
				this.subscribeDetail();
			}
		},

		subscribeDetail() {
			if (this._unsubscribeDetail) {
				try { this._unsubscribeDetail(); } catch { /* noop */ }
				this._unsubscribeDetail = null;
			}
			this._detail = null;
			const hub = this.instance?.api?.eventHub;
			if (!this.maximized || !hub) return;
			const args = `${this.feedArguments()}, card: ${JSON.stringify(this.maximized)}`;
			this._unsubscribeDetail = hub.subscribe(
				`subscription { dashboardCardDetail(${args}) { ${SNAPSHOT_FIELDS} } }`,
				(snapshot: any) => this.onSnapshot(snapshot));
		},

		feedArguments(): string {
			const zone = this._timeZone ? `, timeZone: ${JSON.stringify(this._timeZone)}` : '';
			return `path: ${JSON.stringify(this._appsPath)}${zone}`;
		},

		unsubscribe() {
			for (const key of ['_unsubscribe', '_unsubscribeDetail']) {
				if (this[key]) {
					try { this[key](); } catch { /* noop */ }
					this[key] = null;
				}
			}
		},

		onSnapshot(snapshot: any) {
			if (!snapshot || typeof snapshot.card !== 'string' || !snapshot.data || typeof snapshot.data !== 'object') return;
			if (snapshot.detail) {
				if (snapshot.card !== this.maximized) return;
				this._detail = this.$markRaw(snapshot);
			} else {
				this._snapshots = this.$markRaw({ ...this._snapshots, [snapshot.card]: snapshot });
			}
			this.lastUpdated = Date.now();
			this.rebuild();
		},

		// ---- Folding and maximizing --------------------------------------------
		async loadFolded() {
			try {
				const db = this.instance?.api?.db;
				const value = (db && this.userId) ? await db.getUserSetting(this.userId, 'dashboard', 'summaryOnly') : null;
				if (value && typeof value === 'object') {
					this._summaryOnly = this.$markRaw({ ...value });
				}
			} catch (_) { /* start unfolded */ }
		},

		toggleSection(sectionId: string) {
			const folded = { ...this._summaryOnly, [sectionId]: !this._summaryOnly[sectionId] };
			this._summaryOnly = this.$markRaw(folded);
			this.rebuild();
			try {
				const db = this.instance?.api?.db;
				if (db && this.userId) void db.setUserSetting(this.userId, 'dashboard', 'summaryOnly', folded);
			} catch (_) { /* not remembered */ }
		},

		toggleMaximize(cardId: string) {
			this.maximized = (this.maximized === cardId) ? '' : cardId;
			this.subscribeDetail();
			this.rebuild();
		},

		// ---- Drawing -----------------------------------------------------------
		// Builds the layout from the declarations and the snapshots that have
		// arrived, with every text resolved for the reader.
		rebuild() {
			const now = Date.now();
			const cardsOf = (sectionId: string) => this._cards.filter((c: any) => c.section === sectionId);

			if (this.maximized) {
				const card = this._cards.find((c: any) => c.id === this.maximized);
				if (card) {
					const section = this._sections.find((s: any) => s.identifier === card.section);
					// Until the detail arrives the card keeps showing what it showed.
					const snapshot = this._detail || this._snapshots[card.id];
					this.layout = this.$markRaw([{
						key: 'maximized:' + card.id,
						id: card.section,
						title: this.sectionTitle(section, card.section),
						icon: (section && section.icon) || 'bi-grid',
						canToggle: false,
						summaryOnly: false,
						maximized: true,
						cards: [this.cardView(card, snapshot, now, true)],
					}]);
					return;
				}
				this.maximized = '';
			}

			const layout: any[] = [];
			const known = new Set(this._sections.map((s: any) => s.identifier));
			// A card may name a section no installed app declares; it is shown under the name itself.
			const sections = [...this._sections];
			for (const card of this._cards) {
				if (!known.has(card.section)) {
					known.add(card.section);
					sections.push({ identifier: card.section, title: null, icon: null, order: 1000, app: card.app });
				}
			}
			for (const section of sections) {
				const cards = cardsOf(section.identifier);
				if (cards.length === 0) continue;
				// The summary leads; a second card declared as one is an ordinary card.
				const summary = cards.find((c: any) => c.summary) || null;
				const others = cards.filter((c: any) => c !== summary);
				const canToggle = !!summary && others.length > 0;
				const summaryOnly = canToggle && !!this._summaryOnly[section.identifier];
				const shown = summary ? (summaryOnly ? [summary] : [summary, ...others]) : others;
				layout.push({
					key: section.identifier,
					id: section.identifier,
					title: this.sectionTitle(section, section.identifier),
					icon: section.icon || 'bi-grid',
					canToggle,
					summaryOnly,
					maximized: false,
					cards: shown.map((c: any) => this.cardView(c, this._snapshots[c.id], now, false, c === summary)),
				});
			}
			this.layout = this.$markRaw(layout);
		},

		sectionTitle(section: any, identifier: string): string {
			const fallback = (section && section.title) || identifier;
			const app = section ? section.app : undefined;
			return translate(this.localization, this.instance, `app.${app}.dashboard.section.${identifier}.title`, undefined, fallback, app);
		},

		cardView(card: any, snapshot: any, now: number, maximized: boolean, leading = false): any {
			const data = snapshot && snapshot.data;
			const blocks: any[] = (data && Array.isArray(data.blocks)) ? data.blocks : [];
			// A block may ask for the second column (`col: 2`) of a wide card.
			const first = blocks.filter((b) => b && b.col !== 2).map((b) => this.blockView(b, card.app, now)).filter(Boolean);
			const second = blocks.filter((b) => b && b.col === 2).map((b) => this.blockView(b, card.app, now)).filter(Boolean);
			const wide = maximized || leading || card.size === 'full';
			return {
				key: (maximized ? 'max:' : '') + card.id,
				id: card.id,
				title: translate(this.localization, this.instance, `app.${card.app}.dashboard.${card.identifier}.title`, undefined, card.title || card.identifier, card.app),
				icon: card.icon || 'bi-card-text',
				sizeClass: wide ? 'dash-card-wide' : (card.size === '2' ? 'dash-card-double' : ''),
				canMaximize: !!card.detail,
				maximized,
				link: this.linkOf(card.open, card.app),
				loaded: !!data,
				columns: second.length ? [first, second] : [first],
			};
		},

		blockView(block: any, app: string, now: number): any {
			const title = block.title ? this.text(block.title, app, now) : '';
			switch (block.type) {
				case 'kpis':
					return {
						type: 'kpis',
						title,
						items: (Array.isArray(block.items) ? block.items : []).map((item: any) => ({
							label: this.text(item.label, app, now),
							hint: item.hint ? this.text(item.hint, app, now) : '',
							...this.valueView(item, app, now),
							link: this.linkOf(item.open, app),
						})),
					};
				case 'metric':
					return { type: 'metric', title, unit: this.text(block.unit, app, now), ...this.valueView(block, app, now) };
				case 'rows':
					return {
						type: 'rows',
						title,
						items: (Array.isArray(block.items) ? block.items : []).map((row: any) => ({
							label: this.text(row.label, app, now),
							icon: typeof row.icon === 'string' ? row.icon : '',
							hint: row.hint ? this.text(row.hint, app, now) : '',
							...this.valueView(row, app, now),
						})),
					};
				case 'list':
					return {
						type: 'list',
						title,
						empty: block.empty ? this.text(block.empty, app, now) : '',
						items: (Array.isArray(block.items) ? block.items : []).map((row: any, i: number) => {
							const badges = (Array.isArray(row.badges) ? row.badges : []).map((b: any) => ({
								text: this.text(b.text, app, now),
								tone: badgeTone(b.tone),
								hint: b.hint ? this.text(b.hint, app, now) : '',
							}));
							const due = this.dueBadge(row.due, now);
							if (due) badges.push(due);
							return {
								rank: block.ranked ? String(i + 1) : '',
								icon: typeof row.icon === 'string' ? row.icon : '',
								title: this.text(row.title, app, now),
								tags: (Array.isArray(row.tags) ? row.tags : []).map((tag: any) => this.text(tag, app, now)),
								sub: row.sub ? this.text(row.sub, app, now) : '',
								hint: row.hint ? this.text(row.hint, app, now) : '',
								value: row.value == null ? '' : this.valueView(row, app, now).value,
								valueClass: toneClass(row.tone),
								badges,
								link: row.static ? null : this.linkOf(row.open, app),
							};
						}),
					};
				case 'matrix':
					return {
						type: 'matrix',
						title,
						caption: block.caption ? this.text(block.caption, app, now) : '',
						columns: (Array.isArray(block.columns) ? block.columns : []).map(String),
						rows: (Array.isArray(block.rows) ? block.rows : []).map((row: any) => ({
							label: String(row.label ?? ''),
							cells: (Array.isArray(row.cells) ? row.cells : []).map(String),
						})),
					};
				case 'note':
					return { type: 'note', title, text: this.text(block.text, app, now) };
				default:
					// A kind this Dashboard does not know is left out; the rest of the card is drawn.
					return null;
			}
		},

		// The value of a kpi, a metric or a row: a number (formatted here) or a
		// text, with an optional "of" part, a tone, and whether it is a badge.
		valueView(item: any, app: string, now: number): any {
			const format = (n: number) => item.format === 'compact' ? this.fmtCompact(n) : this.fmtNum(n);
			const value = (typeof item.value === 'number') ? format(item.value)
				: (item.text != null) ? this.text(item.text, app, now)
					: (item.value != null) ? String(item.value) : '';
			return {
				value,
				denom: (typeof item.denom === 'number') ? this.fmtNum(item.denom) : '',
				badge: !!item.badge,
				tone: badgeTone(item.tone),
				valueClass: toneClass(item.tone),
			};
		},

		// When a task is due, as a badge: overdue, today, or how far ahead.
		dueBadge(due: any, now: number): any {
			const at = (typeof due === 'string') ? Date.parse(due) : NaN;
			if (!Number.isFinite(at)) return null;
			if (at < now) return { text: this.t('app.dashboard.pill.overdue', undefined, 'Overdue'), tone: 'danger', hint: '' };
			if (at - now < 24 * 3600 * 1000) return { text: this.t('app.dashboard.pill.today', undefined, 'Today'), tone: 'warning', hint: '' };
			const age = ageLabel(now, at);
			return { text: this.t('app.dashboard.pill.dueIn', { age }, `in ${age}`), tone: 'neutral', hint: '' };
		},

		// A text of a snapshot: a string, or a message reference
		// { id, params, fallback } resolved in the reader's language, in the
		// scope of the card's app first. A parameter is a text again,
		// { age: <ISO time> } or { duration: <ms> }.
		text(value: any, app: string, now: number, depth = 0): string {
			if (value == null) return '';
			if (typeof value === 'string') return value;
			if (typeof value === 'number') return this.fmtNum(value);
			if (typeof value !== 'object' || depth > 4) return '';
			if (typeof value.age === 'string') {
				const at = Date.parse(value.age);
				return Number.isFinite(at) ? ageLabel(at, now) : '—';
			}
			if (value.duration != null) return formatLatency(Number(value.duration));
			if (typeof value.id !== 'string' || !value.id) return '';
			const params: Record<string, string> = {};
			if (value.params && typeof value.params === 'object') {
				for (const name of Object.keys(value.params)) {
					params[name] = this.text(value.params[name], app, now, depth + 1);
				}
			}
			// The fallback is a pattern too: fill it in, for a bundle that lacks the id.
			const pattern = (typeof value.fallback === 'string') ? value.fallback : '';
			const fallback = pattern.replace(/\{([A-Za-z0-9_]+)\}/g, (whole: string, name: string) => (name in params) ? params[name] : whole);
			return translate(this.localization, this.instance, value.id, params, fallback, app);
		},
	},
};

// Mount immediately. The screen itself is behind the readiness gate
// (<template v-if="isReady"> in index.html), which appLaunch opens once the
// component templates are loaded — so mounting no longer has to wait on a
// fetch, and window.appLaunch is defined the moment the iframe finishes
// loading.
VDOM.createApp(App).mount('#app');
