/**
 * Ambience Application
 *
 * Ambient sound mixer. Each sound has a level; together they play under one
 * volume. All of it is synthesized (lib/ambient/engine.ts). The sliders, the
 * volume and the user's saved mixes are kept per user (lib/ambient/mixes.ts),
 * where the Pomodoro timer reads the saved mixes. When another player (the
 * Pomodoro timer) starts, the mixer stops, so only one sounds at a time.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import type { ApplicationInstance } from '../../services/webtop-service.js';
import { initUi } from '../../ui/index.js';
import { createShellPopupAdapter } from '../../ui/shell-popup-adapter.js';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from '../../composables/use-localization.js';
import { AmbientEngine, SOUNDS, type Levels, type SoundId, type SoundInfo } from '../../lib/ambient/engine.js';
import {
	AmbienceStore,
	AMBIENCE_CHANNEL,
	BUILTIN_MIXES,
	MAX_SAVED_MIXES,
	emptyLibrary,
	sameLevels,
	type AmbienceMessage,
	type BuiltinMix,
	type SavedMix,
} from '../../lib/ambient/mixes.js';

// Outside reactive data: the engine holds an AudioContext and nodes, which a
// reactive proxy would break (see the radio app).
let engine: AmbientEngine | null = null;
let store: AmbienceStore | null = null;
let channel: BroadcastChannel | null = null;
// Level a sound comes back at when its tile is switched on again.
const lastLevels: Partial<Record<SoundId, number>> = {};
const DEFAULT_TILE_LEVEL = 50;

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			localization: createLocalizationSnapshot(),
			isReady: false,
			sounds: SOUNDS,
			builtinMixes: BUILTIN_MIXES,
			// Sliders, 0..100.
			levels: Object.fromEntries(SOUNDS.map((s) => [s.id, 0])) as Record<SoundId, number>,
			volume: 80,
			playing: false,
			mixes: [] as SavedMix[],
			maxMixes: MAX_SAVED_MIXES,
			error: '',
			saveDialog: { visible: false, name: '' },
			deleteDialog: { visible: false, mix: null as SavedMix | null },
		};
	},
	computed: {
		activeMix(): { id: string; name: string } | null {
			const current = this.currentLevels();
			const builtin = BUILTIN_MIXES.find((m) => sameLevels(m.levels, current));
			if (builtin) return { id: builtin.id, name: this.builtinLabel(builtin) };
			const saved = this.mixes.find((m: SavedMix) => sameLevels(m.levels, current));
			return saved ? { id: saved.id, name: saved.name } : null;
		},
		anySound(): boolean {
			return SOUNDS.some((s) => this.levels[s.id] > 0);
		},
		statusText(): string {
			if (this.error) return this.error;
			if (!this.playing) return this.t('app.ambience.status.stopped', undefined, 'Stopped');
			const name = this.activeMix?.name;
			return name
				? this.t('app.ambience.status.playingMix', { name }, 'Playing: {name}')
				: this.t('app.ambience.status.playing', undefined, 'Playing');
		},
	},
	methods: {
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		soundLabel(s: SoundInfo): string {
			return this.t('app.ambience.sound.' + s.id, undefined, s.label);
		},
		builtinLabel(m: BuiltinMix): string {
			return this.t('app.ambience.mix.' + m.key, undefined, m.label);
		},

		onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) return;
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (instance: ApplicationInstance) => {
				vm.instance = this.$markRaw(instance);
				refreshLocalization(vm.localization, vm.instance);
				document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light';

				try {
					await initUi({ popupAdapter: createShellPopupAdapter(instance) });
				} catch (e) {
					console.warn('[Ambience] Failed to load component templates:', e);
				}

				engine = new AmbientEngine();
				store = new AmbienceStore({ userId: instance.currentUser?.id || '', content: instance.api.systemContent });
				const library = await store.load();
				for (const s of SOUNDS) vm.levels[s.id] = Math.round((library.current[s.id] || 0) * 100);
				vm.volume = Math.round(library.volume * 100);
				vm.mixes = library.mixes;
				engine.setLevels(library.current);
				engine.setVolume(library.volume);

				// Another player started (the Pomodoro timer): make way for it.
				channel = new BroadcastChannel(AMBIENCE_CHANNEL);
				channel.onmessage = (event: MessageEvent<AmbienceMessage>) => {
					if (event.data?.type === 'playing' && event.data.source !== 'ambience' && vm.playing) vm.stop();
				};

				instance.setBeforeCloseCallback(async () => {
					channel?.close();
					channel = null;
					await engine?.destroy();
					await store?.flush();
					return true;
				});

				vm.isReady = true;
				vm.$nextTick(() => instance.notifyLaunched());
			};
		},
		onUnmount() {
			if (this.messageListener) window.removeEventListener('message', this.messageListener);
			channel?.close();
			engine?.destroy();
		},

		// --- window controls ------------------------------------------------

		onMinimizeWindow() {
			this.instance?.minimize();
		},
		onToggleMaximizeWindow() {
			this.instance?.toggleMaximize();
		},
		onCloseWindow() {
			this.instance?.requestClose();
		},

		// --- playing --------------------------------------------------------

		currentLevels(): Levels {
			const out: Levels = {};
			for (const s of SOUNDS) if (this.levels[s.id] > 0) out[s.id] = this.levels[s.id] / 100;
			return out;
		},
		async play() {
			const vm = this;
			if (!engine || vm.playing) return;
			try {
				await engine.play();
				vm.playing = true;
				vm.error = '';
				channel?.postMessage({ type: 'playing', source: 'ambience' } satisfies AmbienceMessage);
			} catch (e) {
				console.warn('[Ambience] Audio could not be started:', e);
				vm.error = vm.t('app.ambience.status.audioFailed', undefined, 'Sound could not be started in this browser.');
			}
			vm.updateDisplayInfo();
		},
		stop() {
			engine?.pause();
			this.playing = false;
			this.updateDisplayInfo();
		},
		togglePlay() {
			if (this.playing) this.stop();
			else this.play();
		},
		updateDisplayInfo() {
			this.instance?.setDisplayInfo({ subtitle: this.playing ? this.statusText : '' });
		},

		// --- levels ---------------------------------------------------------

		setLevel(id: SoundId, value: number) {
			const vm = this;
			const level = Math.max(0, Math.min(100, Math.round(value)));
			vm.levels[id] = level;
			if (level > 0) lastLevels[id] = level;
			engine?.setLevel(id, level / 100);
			// Turning a sound up is a way to start.
			if (level > 0 && !vm.playing) vm.play();
			vm.persist();
			vm.updateDisplayInfo();
		},
		onLevelInput(id: SoundId, event: Event) {
			this.setLevel(id, Number((event.target as HTMLInputElement).value));
		},
		toggleSound(id: SoundId) {
			this.setLevel(id, this.levels[id] > 0 ? 0 : (lastLevels[id] || DEFAULT_TILE_LEVEL));
		},
		onVolumeInput(event: Event) {
			const vm = this;
			vm.volume = Math.max(0, Math.min(100, Number((event.target as HTMLInputElement).value)));
			engine?.setVolume(vm.volume / 100);
			vm.persist();
		},
		applyMix(levels: Levels) {
			const vm = this;
			for (const s of SOUNDS) {
				vm.levels[s.id] = Math.round((levels[s.id] || 0) * 100);
				if (vm.levels[s.id] > 0) lastLevels[s.id] = vm.levels[s.id];
			}
			engine?.setLevels(levels);
			if (!vm.playing) vm.play();
			vm.persist();
			vm.updateDisplayInfo();
		},
		persist() {
			if (!store) return;
			store.save({
				...emptyLibrary(),
				current: this.currentLevels(),
				volume: this.volume / 100,
				mixes: this.mixes.map((m: SavedMix) => ({ id: m.id, name: m.name, levels: { ...m.levels } })),
			});
		},

		// --- saved mixes ----------------------------------------------------

		openSaveDialog() {
			this.saveDialog = {
				visible: true,
				name: this.t('app.ambience.save.defaultName', { n: this.mixes.length + 1 }, 'Mix {n}'),
			};
		},
		closeSaveDialog() {
			this.saveDialog = { visible: false, name: '' };
		},
		confirmSave() {
			const vm = this;
			const name = vm.saveDialog.name.trim();
			if (!name || !vm.anySound || vm.mixes.length >= MAX_SAVED_MIXES) return;
			vm.mixes = [...vm.mixes, { id: crypto.randomUUID(), name, levels: vm.currentLevels() }];
			vm.closeSaveDialog();
			vm.persist();
		},
		openDeleteDialog(mix: SavedMix) {
			this.deleteDialog = { visible: true, mix };
		},
		closeDeleteDialog() {
			this.deleteDialog = { visible: false, mix: null };
		},
		confirmDelete() {
			const vm = this;
			const id = vm.deleteDialog.mix?.id;
			vm.mixes = vm.mixes.filter((m: SavedMix) => m.id !== id);
			vm.closeDeleteDialog();
			vm.persist();
		},
	},
};

VDOM.createApp(App).mount('#app');
