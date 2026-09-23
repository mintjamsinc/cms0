/**
 * Radio Application
 *
 * Internet radio player. The browser's <audio> element plays the station's
 * stream directly (see player.ts); stations come from the Radio Browser
 * directory (radio-browser.ts) or are added by hand; favorites, history and
 * settings are kept per user in the system workspace (library.ts). The track
 * title is read from the ICY metadata by icy.groovy next to this file.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import { ApplicationInstance } from "../../services/webtop-service.js";
import { initUi } from "../../ui/index.js";
import { createShellPopupAdapter } from "../../ui/shell-popup-adapter.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";
import { RadioBrowserClient, type DirectoryFacet, type StationOrder } from './radio-browser.js';
import {
	LibraryStore,
	emptyLibrary,
	newCustomStationId,
	pushRecent,
	type Library,
	type Station,
} from './library.js';
import { RadioPlayer, isHlsStation, type PlayerState, type PlayerErrorKind, type PlayerStateDetail } from './player.js';

type View = 'discover' | 'favorites' | 'recent' | 'mine';

// The player, directory client and store live OUTSIDE reactive data:
// ichigo.js wraps stored objects in deep Proxies, which would break the
// media elements and AudioContext the player holds.
let player: RadioPlayer | null = null;
let directory: RadioBrowserClient | null = null;
let store: LibraryStore | null = null;

let icyTimer: ReturnType<typeof setInterval> | null = null;
let elapsedTimer: ReturnType<typeof setInterval> | null = null;
let sleepTimer: ReturnType<typeof setTimeout> | null = null;
let sleepTick: ReturnType<typeof setInterval> | null = null;
let statusTimer: ReturnType<typeof setTimeout> | null = null;
let playingSince = 0;
// Pending confirmation action; a function is kept out of reactive data.
let confirmAction: (() => void) | null = null;
// Sequence number of the latest directory query; older replies are dropped.
let querySeq = 0;

const ICY_INTERVAL_MS = 15000;
const SLEEP_OPTIONS = [0, 15, 30, 60, 90];
// Facet rows shown before "Show more".
const FACET_PREVIEW = { country: 8, language: 6, tag: 16 };

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			// Reactive Localization snapshot — see composables/use-localization.ts.
			localization: createLocalizationSnapshot(),
			isReady: false,

			view: 'discover' as View,

			// Directory query: the search text, the left-pane filters and the
			// order combine into one directory search (see runQuery).
			searchText: '',
			searchQuery: '',
			filters: { country: '', language: '', tag: '' },
			order: 'votes' as StationOrder,
			results: [] as Station[],
			isResultsLoading: false,

			// Left-pane facets
			countries: [] as DirectoryFacet[],
			languages: [] as DirectoryFacet[],
			tags: [] as DirectoryFacet[],
			facetFilter: { country: '', language: '' },
			expanded: { country: false, language: false, tag: false },

			directoryError: false,

			// Per-user library
			library: emptyLibrary() as Library,

			// Playback
			current: null as Station | null,
			player: { state: 'idle' as PlayerState, error: '' as PlayerErrorKind | '', analyser: false },
			now: { title: '', name: '', genre: '', bitrate: '', elapsed: '' },
			volume: 80,
			muted: false,
			sleepMinutes: 0,
			sleep: { remaining: 0 },

			// Favicons that failed to load; shown as the placeholder glyph.
			brokenIcons: {} as Record<string, boolean>,

			stationDialog: {
				visible: false,
				id: '',
				name: '',
				url: '',
				homepage: '',
				tags: '',
				errors: { name: '', url: '' },
			},
			confirmDialog: {
				visible: false,
				title: '',
				message: '',
			},
			statusMessage: '',
		};
	},
	computed: {
		viewTabs(): { key: View; icon: string; label: string }[] {
			return [
				{ key: 'discover', icon: 'bi-compass', label: this.t('app.radio.nav.discover', undefined, 'Discover') },
				{ key: 'favorites', icon: 'bi-heart', label: this.t('app.radio.nav.favorites', undefined, 'Favorites') },
				{ key: 'recent', icon: 'bi-clock-history', label: this.t('app.radio.nav.recent', undefined, 'Recently played') },
				{ key: 'mine', icon: 'bi-collection', label: this.t('app.radio.nav.mine', undefined, 'My stations') },
			];
		},
		isActive(): boolean {
			return this.player.state === 'playing' || this.player.state === 'loading';
		},
		listStations(): Station[] {
			switch (this.view as View) {
				case 'favorites':
					return this.library.favorites;
				case 'recent':
					return this.library.recents.map((r: { station: Station }) => r.station);
				case 'mine':
					return this.library.custom;
				default:
					return this.results;
			}
		},
		isListLoading(): boolean {
			return this.view === 'discover' && this.isResultsLoading;
		},
		hasQuery(): boolean {
			const f = this.filters;
			return !!(this.searchQuery || f.country || f.language || f.tag);
		},
		listTitle(): string {
			switch (this.view as View) {
				case 'favorites':
					return this.t('app.radio.nav.favorites', undefined, 'Favorites');
				case 'recent':
					return this.t('app.radio.nav.recent', undefined, 'Recently played');
				case 'mine':
					return this.t('app.radio.nav.mine', undefined, 'My stations');
				default:
					if (this.hasQuery) {
						return this.t('app.radio.discover.filtered', undefined, 'Filtered stations');
					}
					return this.order === 'votes'
						? this.t('app.radio.discover.topVoted', undefined, 'Popular stations')
						: this.t('app.radio.discover.topClicked', undefined, 'Most played stations');
			}
		},
		/** Chips for the conditions in effect, each removable on its own. */
		activeConditions(): { key: string; icon: string; label: string }[] {
			const out: { key: string; icon: string; label: string }[] = [];
			if (this.searchQuery) {
				out.push({ key: 'name', icon: 'bi-search', label: this.searchQuery });
			}
			if (this.filters.country) {
				const code = this.filters.country;
				out.push({ key: 'country', icon: 'bi-geo-alt', label: this.countryLabel(code, code) });
			}
			if (this.filters.language) {
				const lang = this.languages.find((l: DirectoryFacet) => l.value === this.filters.language);
				out.push({ key: 'language', icon: 'bi-translate', label: this.languageLabel(this.filters.language, lang?.code || '') });
			}
			if (this.filters.tag) {
				out.push({ key: 'tag', icon: 'bi-hash', label: this.filters.tag });
			}
			return out;
		},
		orderItems(): { key: StationOrder; icon: string; label: string }[] {
			return [
				{ key: 'votes', icon: 'bi-hand-thumbs-up', label: this.t('app.radio.order.votes', undefined, 'Popular') },
				{ key: 'clickcount', icon: 'bi-fire', label: this.t('app.radio.order.clicks', undefined, 'Most played') },
			];
		},
		visibleCountries(): DirectoryFacet[] {
			return this.facetList('country', this.countries);
		},
		visibleLanguages(): DirectoryFacet[] {
			return this.facetList('language', this.languages);
		},
		visibleTags(): DirectoryFacet[] {
			return this.facetList('tag', this.tags);
		},
		listEmptyMessage(): string {
			switch (this.view as View) {
				case 'favorites':
					return this.t('app.radio.favorites.empty', undefined, 'No favorites yet.');
				case 'recent':
					return this.t('app.radio.recent.empty', undefined, 'Stations you play show up here.');
				case 'mine':
					return this.t('app.radio.mine.empty', undefined, 'Add a station by its stream URL to listen to it here.');
				default:
					if (this.directoryError) {
						return this.t('app.radio.discover.unavailable', undefined, 'The station directory could not be reached.');
					}
					return this.t('app.radio.discover.none', undefined, 'No stations match these conditions.');
			}
		},
		stateLabel(): string {
			switch (this.player.state as PlayerState) {
				case 'playing':
					return this.t('app.radio.now.live', undefined, 'LIVE');
				case 'loading':
					return this.t('app.radio.now.loading', undefined, 'Connecting…');
				case 'error':
					return this.t('app.radio.error.title', undefined, 'Playback failed');
				default:
					return this.t('app.radio.now.stopped', undefined, 'Stopped');
			}
		},
		errorMessage(): string {
			const kind = this.player.error as PlayerErrorKind | '';
			const fallbacks: Record<string, string> = {
				network: 'The station did not respond.',
				unsupported: 'This browser cannot decode the stream format.',
				mixedContent: 'This station streams HLS over plain http and the desktop is served over https, so the browser blocks it.',
				hls: 'This browser cannot play HLS streams.',
				hlsNetwork: 'The HLS stream could not be loaded. The station may be off the air, or it does not allow the browser to fetch the stream.',
			};
			const key = kind && fallbacks[kind] ? kind : 'generic';
			return this.t(`app.radio.error.${key}`, undefined, fallbacks[key] || 'The stream could not be played.');
		},
		nowBitrate(): string {
			return this.now.bitrate || (this.current?.bitrate ? String(this.current.bitrate) : '');
		},
		sleepItems(): { value: number; label: string }[] {
			return SLEEP_OPTIONS.map((m) => ({
				value: m,
				label: m
					? this.t('app.radio.sleep.minutes', { minutes: m }, `${m} min`)
					: this.t('app.radio.sleep.off', undefined, 'Off'),
			}));
		},
	},
	methods: {
		/** Reactive i18n lookup; repaints on language change. */
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
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (instance: ApplicationInstance) => {
				vm.instance = this.$markRaw(instance);
				refreshLocalization(vm.localization, vm.instance);

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				// --- Readiness gate --- (see index.html)
				try {
					await initUi({ popupAdapter: createShellPopupAdapter(instance) });
				} catch (e) {
					console.warn('[Radio] Failed to load component templates:', e);
				}

				player = new RadioPlayer({
					onState: (state, detail) => vm.onPlayerState(state, detail),
				});
				directory = new RadioBrowserClient();
				store = new LibraryStore({
					userId: instance.currentUser?.id || '',
					db: instance.api.db,
					content: instance.api.systemContent,
				});

				instance.setBeforeCloseCallback(async () => {
					vm.clearSleepTimer();
					player?.destroy();
					try {
						await store?.flush();
					} catch (e) {
						console.warn('[Radio] Library could not be saved on close:', e);
					}
					return true;
				});

				try {
					vm.library = await store.load();
				} catch (e) {
					console.warn('[Radio] Library could not be loaded:', e);
				}
				vm.volume = Math.round((vm.library.settings.volume ?? 0.8) * 100);
				vm.muted = !!vm.library.settings.muted;
				vm.current = vm.library.settings.lastStation || null;
				player.setVolume(vm.volume / 100);
				player.setMuted(vm.muted);

				vm.isReady = true;
				await new Promise<void>((resolve) => vm.$nextTick(() => resolve()));

				this.$nextTick(() => {
					instance.notifyLaunched();
				});

				vm.loadDirectory();
			};
		},
		onUnmount() {
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
			}
			this.stopIcyPolling();
			this.stopElapsed();
			this.clearSleepTimer();
			player?.destroy();
			player = null;
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
		// Views, search and directory
		// =====================================================================

		selectView(view: View) {
			this.view = view;
		},

		/** Loads the left-pane facets and the first station list. */
		async loadDirectory() {
			const vm = this;
			if (!directory) return;
			vm.runQuery();
			try {
				const [countries, languages, tags] = await Promise.all([
					directory.countries(),
					directory.languages(60),
					directory.tags(40),
				]);
				vm.countries = countries;
				vm.languages = languages;
				vm.tags = tags;
			} catch (e) {
				console.warn('[Radio] Directory facets unavailable:', e);
			}
		},

		/**
		 * Asks the directory for the stations matching the search text and
		 * every selected filter, in the selected order. Replies that arrive
		 * after a newer query was started are dropped.
		 */
		async runQuery() {
			const vm = this;
			if (!directory) return;
			const seq = ++querySeq;
			vm.isResultsLoading = true;
			try {
				const results = await directory.searchStations({
					name: vm.searchQuery,
					countryCode: vm.filters.country,
					language: vm.filters.language,
					tag: vm.filters.tag,
					order: vm.order,
					limit: 80,
				});
				if (seq !== querySeq) return;
				vm.results = results;
				vm.directoryError = false;
			} catch (e) {
				if (seq !== querySeq) return;
				console.warn('[Radio] Station directory unavailable:', e);
				vm.results = [];
				vm.directoryError = true;
			} finally {
				if (seq === querySeq) vm.isResultsLoading = false;
			}
		},

		// ---- Search box ----
		// wt-search-box emits its value as event.detail; the template passes
		// the bound searchText instead of the event.

		onSearch(text: string) {
			const q = (text || '').trim();
			this.view = 'discover';
			if (q === this.searchQuery) return;
			this.searchQuery = q;
			this.runQuery();
		},
		onSearchInput(text: string) {
			// Emptying the box by hand drops the name condition like the x button.
			if (!(text || '').trim() && this.searchQuery) {
				this.onSearchClear();
			}
		},
		onSearchClear() {
			this.searchText = '';
			if (!this.searchQuery) return;
			this.searchQuery = '';
			this.runQuery();
		},

		// ---- Left-pane filters ----

		setOrder(order: StationOrder) {
			this.view = 'discover';
			if (this.order === order) return;
			this.order = order;
			this.runQuery();
		},
		/** Selects a facet value, or clears it when it is already selected. */
		toggleFilter(kind: 'country' | 'language' | 'tag', value: string) {
			this.view = 'discover';
			this.filters[kind] = this.filters[kind] === value ? '' : value;
			this.runQuery();
		},
		removeCondition(key: string) {
			if (key === 'name') {
				this.onSearchClear();
				return;
			}
			if (key === 'country' || key === 'language' || key === 'tag') {
				this.filters[key] = '';
				this.runQuery();
			}
		},
		clearConditions() {
			this.searchText = '';
			this.searchQuery = '';
			this.filters = { country: '', language: '', tag: '' };
			this.runQuery();
		},
		toggleExpanded(kind: 'country' | 'language' | 'tag') {
			this.expanded[kind] = !this.expanded[kind];
		},

		/**
		 * Rows shown for a facet: the preview (plus the selected value when it
		 * is further down), everything when expanded, or the rows matching the
		 * narrowing text typed above the list.
		 */
		facetList(kind: 'country' | 'language' | 'tag', list: DirectoryFacet[]): DirectoryFacet[] {
			const text = (kind === 'tag' ? '' : this.facetFilter[kind] || '').trim().toLowerCase();
			if (text) {
				return list.filter((f) => {
					const label = kind === 'country' ? this.countryLabel(f.code, f.name)
						: this.languageLabel(f.value, f.code);
					return label.toLowerCase().includes(text) || f.name.toLowerCase().includes(text) ||
						f.code.toLowerCase() === text;
				});
			}
			if (this.expanded[kind]) return list;
			const head = list.slice(0, FACET_PREVIEW[kind]);
			const selected = this.filters[kind];
			if (selected && !head.some((f) => f.value === selected)) {
				const hit = list.find((f) => f.value === selected);
				if (hit) head.push(hit);
			}
			return head;
		},
		facetHasMore(kind: 'country' | 'language' | 'tag', list: DirectoryFacet[]): boolean {
			const text = kind === 'tag' ? '' : (this.facetFilter[kind] || '').trim();
			return !text && list.length > FACET_PREVIEW[kind];
		},

		/** Country name in the user's display language, from its ISO code. */
		countryLabel(code: string, fallback: string): string {
			try {
				const names = new Intl.DisplayNames([this.displayLocale()], { type: 'region' });
				const name = code ? names.of(code.toUpperCase()) : '';
				if (name && name !== code.toUpperCase()) return name;
			} catch { /* fall through */ }
			return fallback || code;
		},
		/** Language name in the user's display language when the directory gives an ISO code. */
		languageLabel(name: string, code: string): string {
			if (code) {
				try {
					const names = new Intl.DisplayNames([this.displayLocale()], { type: 'language' });
					const label = names.of(code);
					if (label && label !== code) return label;
				} catch { /* fall through */ }
			}
			return name ? name.charAt(0).toUpperCase() + name.slice(1) : '';
		},
		displayLocale(): string {
			return this.localization.locale || navigator.language || 'en';
		},
		formatCount(n: number): string {
			try {
				return new Intl.NumberFormat(this.displayLocale(), { notation: 'compact', maximumFractionDigits: 1 }).format(n || 0);
			} catch {
				return String(n || 0);
			}
		},

		// =====================================================================
		// Playback
		// =====================================================================

		isCurrent(station: Station): boolean {
			return !!this.current && this.current.id === station.id;
		},

		playStation(station: Station) {
			const vm = this;
			if (!player) return;
			const copy: Station = { ...station, tags: [...(station.tags || [])] };
			vm.current = copy;
			vm.now = { title: '', name: '', genre: '', bitrate: '', elapsed: '' };
			playingSince = 0;
			vm.stopIcyPolling();
			vm.stopElapsed();

			player.play(copy);

			pushRecent(vm.library, copy);
			vm.library.settings.lastStation = copy;
			vm.saveLibrary();
			if (copy.source === 'directory' && directory) {
				directory.countClick(copy.id);
			}
		},
		togglePlayStation(station: Station) {
			if (this.isCurrent(station) && this.isActive) {
				this.stopPlayback();
			} else {
				this.playStation(station);
			}
		},
		togglePlay() {
			if (this.isActive) {
				this.stopPlayback();
			} else if (this.current) {
				this.playStation(this.current);
			}
		},
		stopPlayback() {
			player?.stop();
		},

		onPlayerState(state: PlayerState, detail: PlayerStateDetail) {
			const vm = this;
			vm.player.state = state;
			vm.player.error = detail.error || '';
			vm.player.analyser = detail.analyser;

			if (state === 'playing') {
				if (!playingSince) playingSince = Date.now();
				vm.startElapsed();
				vm.startIcyPolling();
			} else if (state !== 'loading') {
				playingSince = 0;
				vm.now.elapsed = '';
				vm.stopElapsed();
				vm.stopIcyPolling();
				vm.clearSleepTimer();
			}
		},

		onSpectrumMounted($ctx: { element: HTMLCanvasElement }) {
			player?.attachCanvas($ctx.element);
		},
		onSpectrumUnmount() {
			player?.attachCanvas(null);
		},

		// ---- Track title (ICY metadata via icy.groovy) ----

		startIcyPolling() {
			this.stopIcyPolling();
			// HLS carries no ICY metadata: the playlist is plain HTTP.
			if (this.current && isHlsStation(this.current)) return;
			this.pollIcy();
			icyTimer = setInterval(() => this.pollIcy(), ICY_INTERVAL_MS);
		},
		stopIcyPolling() {
			if (icyTimer) {
				clearInterval(icyTimer);
				icyTimer = null;
			}
		},
		async pollIcy() {
			const vm = this;
			const station = vm.current;
			if (!station || vm.player.state !== 'playing') return;
			try {
				const res = await fetch(`icy.groovy?url=${encodeURIComponent(station.url)}`, { cache: 'no-store' });
				if (!res.ok) return;
				const meta = await res.json();
				if (!meta?.ok || !vm.current || vm.current.id !== station.id) return;
				const title = (meta.title || '').trim();
				if (title !== vm.now.title) {
					vm.now.title = title;
					player?.setNowPlaying(title);
				}
				vm.now.name = meta.name || '';
				vm.now.genre = meta.genre || '';
				vm.now.bitrate = meta.bitrate ? String(meta.bitrate) : '';
			} catch {
				// The title is decoration; playback is unaffected.
			}
		},

		// ---- Elapsed time ----

		startElapsed() {
			this.stopElapsed();
			this.updateElapsed();
			elapsedTimer = setInterval(() => this.updateElapsed(), 1000);
		},
		stopElapsed() {
			if (elapsedTimer) {
				clearInterval(elapsedTimer);
				elapsedTimer = null;
			}
		},
		updateElapsed() {
			if (!playingSince) return;
			const total = Math.floor((Date.now() - playingSince) / 1000);
			const h = Math.floor(total / 3600);
			const m = Math.floor((total % 3600) / 60);
			const s = total % 60;
			const mm = String(m).padStart(2, '0');
			const ss = String(s).padStart(2, '0');
			this.now.elapsed = h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
		},

		// ---- Volume ----

		onVolumeInput(event: Event) {
			const value = Number((event.target as HTMLInputElement).value);
			this.volume = value;
			player?.setVolume(value / 100);
			if (this.muted && value > 0) {
				this.muted = false;
				player?.setMuted(false);
			}
			this.library.settings.volume = value / 100;
			this.library.settings.muted = this.muted;
			this.saveLibrary(true);
		},
		toggleMute() {
			this.muted = !this.muted;
			player?.setMuted(this.muted);
			this.library.settings.muted = this.muted;
			this.saveLibrary(true);
		},

		// ---- Sleep timer ----

		onSleepChange(minutes: number) {
			this.setSleep(Number(minutes) || 0);
		},
		setSleep(minutes: number) {
			const vm = this;
			vm.clearSleepTimer();
			vm.sleepMinutes = minutes;
			if (!minutes) return;
			vm.sleep.remaining = minutes;
			sleepTimer = setTimeout(() => {
				vm.clearSleepTimer();
				vm.stopPlayback();
			}, minutes * 60000);
			sleepTick = setInterval(() => {
				vm.sleep.remaining = Math.max(0, vm.sleep.remaining - 1);
			}, 60000);
		},
		clearSleepTimer() {
			if (sleepTimer) {
				clearTimeout(sleepTimer);
				sleepTimer = null;
			}
			if (sleepTick) {
				clearInterval(sleepTick);
				sleepTick = null;
			}
			this.sleep.remaining = 0;
			this.sleepMinutes = 0;
		},

		// =====================================================================
		// Library: favorites, history, own stations
		// =====================================================================

		isFavorite(id: string): boolean {
			return this.library.favorites.some((s: Station) => s.id === id);
		},
		toggleFavorite(station: Station) {
			const vm = this;
			if (vm.isFavorite(station.id)) {
				vm.library.favorites = vm.library.favorites.filter((s: Station) => s.id !== station.id);
			} else {
				vm.library.favorites = [...vm.library.favorites, { ...station, tags: [...(station.tags || [])] }];
			}
			vm.saveLibrary();
		},

		clearRecents() {
			const vm = this;
			vm.openConfirm(
				vm.t('app.radio.recent.clear', undefined, 'Clear history'),
				vm.t('app.radio.recent.empty', undefined, 'Stations you play show up here.'),
				() => {
					vm.library.recents = [];
					vm.saveLibrary();
				},
			);
		},

		openAddStation() {
			this.stationDialog = {
				visible: true,
				id: '',
				name: '',
				url: '',
				homepage: '',
				tags: '',
				errors: { name: '', url: '' },
			};
		},
		openEditStation(station: Station) {
			this.stationDialog = {
				visible: true,
				id: station.id,
				name: station.name,
				url: station.url,
				homepage: station.homepage,
				tags: (station.tags || []).join(', '),
				errors: { name: '', url: '' },
			};
		},
		closeStationDialog() {
			this.stationDialog.visible = false;
		},
		saveStationDialog() {
			const vm = this;
			const d = vm.stationDialog;
			const name = d.name.trim();
			const url = d.url.trim();
			d.errors.name = name ? '' : vm.t('app.radio.station.nameRequired', undefined, 'Enter a name.');
			d.errors.url = /^https?:\/\/\S+$/i.test(url) ? '' : vm.t('app.radio.station.invalidUrl', undefined, 'Enter an http(s) URL.');
			if (d.errors.name || d.errors.url) return;

			const existing = d.id ? vm.library.custom.find((s: Station) => s.id === d.id) : null;
			const station: Station = {
				id: existing ? existing.id : newCustomStationId(),
				name,
				url,
				homepage: d.homepage.trim(),
				favicon: existing?.favicon || '',
				tags: d.tags.split(',').map((t: string) => t.trim()).filter(Boolean).slice(0, 8),
				country: existing?.country || '',
				countryCode: existing?.countryCode || '',
				codec: existing?.codec || '',
				bitrate: existing?.bitrate || 0,
				hls: /\.m3u8(\?|$)/i.test(url),
				source: 'custom',
			};

			if (existing) {
				vm.library.custom = vm.library.custom.map((s: Station) => s.id === station.id ? station : s);
				vm.library.favorites = vm.library.favorites.map((s: Station) => s.id === station.id ? station : s);
				if (vm.current?.id === station.id) vm.current = { ...station, tags: [...station.tags] };
			} else {
				vm.library.custom = [...vm.library.custom, station];
			}
			vm.saveLibrary();
			vm.view = 'mine';
			d.visible = false;
		},
		removeCustomStation(station: Station) {
			const vm = this;
			vm.openConfirm(
				vm.t('app.radio.mine.remove', undefined, 'Remove station'),
				vm.t('app.radio.mine.removeConfirm', { name: station.name }, `Remove "${station.name}" from your stations?`),
				() => {
					if (vm.current?.id === station.id && vm.isActive) vm.stopPlayback();
					vm.library.custom = vm.library.custom.filter((s: Station) => s.id !== station.id);
					vm.library.favorites = vm.library.favorites.filter((s: Station) => s.id !== station.id);
					vm.library.recents = vm.library.recents.filter((r: { station: Station }) => r.station.id !== station.id);
					vm.saveLibrary();
				},
			);
		},

		saveLibrary(quiet = false) {
			const vm = this;
			if (!store) return;
			store.save(vm.library).then(() => {
				if (!quiet) vm.showStatus(vm.t('app.radio.status.saved', undefined, 'Library saved'));
			}).catch((e: unknown) => {
				console.warn('[Radio] Library could not be saved:', e);
				vm.showStatus(vm.t('app.radio.status.saveFailed', undefined, 'Library could not be saved to the server'));
			});
		},

		// =====================================================================
		// Presentation helpers
		// =====================================================================

		stationSubtitle(station: Station): string {
			const parts: string[] = [];
			if (station.countryCode || station.country) {
				parts.push(this.countryLabel(station.countryCode, station.country));
			}
			parts.push(...(station.tags || []).slice(0, 3));
			return parts.join(' · ');
		},
		/** The figure the list is sorted by, for directory rows in Discover. */
		stationMetric(station: Station): { icon: string; value: string; title: string } | null {
			if (this.view !== 'discover' || station.source !== 'directory') return null;
			if (this.order === 'votes') {
				return {
					icon: 'bi-hand-thumbs-up',
					value: this.formatCount(station.votes || 0),
					title: this.t('app.radio.order.votesCount', { count: station.votes || 0 }, `${station.votes || 0} votes`),
				};
			}
			return {
				icon: 'bi-fire',
				value: this.formatCount(station.clicks || 0),
				title: this.t('app.radio.order.clicksCount', { count: station.clicks || 0 }, `${station.clicks || 0} plays`),
			};
		},
		onIconError(station: Station) {
			this.brokenIcons = { ...this.brokenIcons, [station.id]: true };
		},
		openHomepage(station: Station) {
			if (!/^https?:\/\//i.test(station.homepage)) return;
			window.open(station.homepage, '_blank', 'noopener');
		},
		showStatus(message: string) {
			this.statusMessage = message;
			if (statusTimer) clearTimeout(statusTimer);
			statusTimer = setTimeout(() => {
				this.statusMessage = '';
				statusTimer = null;
			}, 4000);
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

VDOM.createApp(App).mount('#app');
