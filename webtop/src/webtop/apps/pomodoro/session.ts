/**
 * Pomodoro: one frame's view of the shared timer.
 *
 * The widget and the window each run a session. A session holds the state
 * (timer.ts), checks every half second whether a phase has ended, and writes
 * every change to the user's file (store.ts). Other frames of this browser
 * hear of a change at once over a BroadcastChannel; other browsers through
 * the file's change events. The newest state (updatedAt) wins.
 *
 * Sound: the frame where focus was started plays the chosen Ambience mix
 * during focus. Browsers let a page start audio only after a click in it, so
 * that frame is the one that can; it tells the Ambience app (and the other
 * frames) over the Ambience channel, and whoever was playing stops.
 */
import { notify } from '../../lib/notifications.js';
import { AmbientEngine, type Levels } from '../../lib/ambient/engine.js';
import { AmbienceStore, AMBIENCE_CHANNEL, BUILTIN_MIXES, type AmbienceMessage, type SavedMix } from '../../lib/ambient/mixes.js';
import { PomodoroStore } from './store.js';
import {
	advance,
	isRunning,
	normalizeData,
	pause as pauseTimer,
	reset as resetTimer,
	skip as skipTimer,
	start as startTimer,
	type PhaseEvent,
	type PomodoroData,
	type Settings,
} from './timer.js';

const TICK_MS = 500;
const CHANNEL = 'webtop-pomodoro';

export interface MixChoice {
	id: string;
	builtinKey?: string;   // name under app.ambience.mix.<key>
	label: string;         // English fallback, or the saved mix's name
	levels: Levels;
}

interface StateMessage {
	type: 'state';
	data: PomodoroData;
}

export interface SessionOptions {
	api: any;
	userId: string;
	/** Called after every change and every tick (for the clock). */
	onChange: (data: PomodoroData) => void;
}

export class PomodoroSession {
	#options: SessionOptions;
	#store: PomodoroStore;
	#ambienceStore: AmbienceStore;
	#data: PomodoroData;
	#writer = crypto.randomUUID();
	#channel: BroadcastChannel | null = null;
	#ambienceChannel: BroadcastChannel | null = null;
	#unwatch: (() => void) | null = null;
	#timer: ReturnType<typeof setInterval> | null = null;
	#engine: AmbientEngine | null = null;
	#audioOwner = false;
	#savedMixes: SavedMix[] = [];

	constructor(options: SessionOptions, initial: PomodoroData) {
		this.#options = options;
		this.#data = initial;
		this.#store = new PomodoroStore({ userId: options.userId, content: options.api.systemContent });
		this.#ambienceStore = new AmbienceStore({ userId: options.userId, content: options.api.systemContent });
	}

	get data(): PomodoroData { return this.#data; }

	/** None, the built-in mixes, then the user's own (from the Ambience app). */
	get mixes(): MixChoice[] {
		return [
			...BUILTIN_MIXES.map((m) => ({ id: m.id, builtinKey: m.key, label: m.label, levels: m.levels })),
			...this.#savedMixes.map((m) => ({ id: m.id, label: m.name, levels: m.levels })),
		];
	}

	async init(): Promise<void> {
		this.#data = await this.#store.load();
		await this.refreshMixes();

		this.#channel = new BroadcastChannel(CHANNEL);
		this.#channel.onmessage = (e: MessageEvent<StateMessage>) => {
			if (e.data?.type === 'state') this.#adopt(e.data.data);
		};
		// Someone else started playing (the mixer, or focus started in another frame).
		this.#ambienceChannel = new BroadcastChannel(AMBIENCE_CHANNEL);
		this.#ambienceChannel.onmessage = (e: MessageEvent<AmbienceMessage>) => {
			if (e.data?.type === 'playing') {
				this.#audioOwner = false;
				this.#syncAudio();
			}
		};
		try {
			this.#unwatch = this.#options.api.systemEventHub.watchNode(this.#store.path, () => this.#onFileChanged(), true);
		} catch (e) {
			console.warn('[Pomodoro] Changes from other browsers cannot be watched:', e);
		}
		this.#tick();
		this.#timer = setInterval(() => this.#tick(), TICK_MS);
	}

	async refreshMixes(): Promise<void> {
		this.#savedMixes = (await this.#ambienceStore.load()).mixes;
	}

	// --- actions (from a click) ---------------------------------------------

	start(): void {
		const before = this.#data.timer.phase;
		this.#commit(startTimer(this.#data, Date.now()), []);
		// This click lets this frame play; take over the sound for this focus.
		if (this.#data.timer.phase === 'focus' && (before === 'idle' || before === 'focus')) this.#takeAudio();
	}

	pause(): void {
		this.#commit(pauseTimer(this.#data, Date.now()), []);
	}

	skip(): void {
		this.#commit(skipTimer(this.#data, Date.now()), []);
	}

	reset(): void {
		this.#commit(resetTimer(this.#data), []);
	}

	updateSettings(changes: Partial<Settings>): void {
		const settings = { ...this.#data.settings, ...changes };
		this.#commit({ ...this.#data, settings, updatedAt: new Date().toISOString() }, [], true);
	}

	async dispose(): Promise<void> {
		if (this.#timer) clearInterval(this.#timer);
		this.#timer = null;
		this.#unwatch?.();
		this.#unwatch = null;
		this.#channel?.close();
		this.#ambienceChannel?.close();
		this.#channel = null;
		this.#ambienceChannel = null;
		await this.#engine?.destroy();
		this.#engine = null;
		await this.#store.flush();
	}

	// --- state ---------------------------------------------------------------

	#commit(next: PomodoroData, events: PhaseEvent[], gentle = false): void {
		if (next === this.#data && !events.length) return;
		this.#data = { ...next, writer: this.#writer };
		this.#store.save(this.#data, gentle);
		this.#channel?.postMessage({ type: 'state', data: this.#data } satisfies StateMessage);
		for (const event of events) this.#announce(event);
		this.#syncAudio();
		this.#options.onChange(this.#data);
	}

	#adopt(incoming: unknown): void {
		const data = normalizeData(incoming);
		if (!data || data.updatedAt <= this.#data.updatedAt) return;
		this.#data = data;
		this.#syncAudio();
		this.#options.onChange(this.#data);
	}

	async #onFileChanged(): Promise<void> {
		const data = await this.#store.load();
		if (data.writer !== this.#writer) this.#adopt(data);
	}

	#tick(): void {
		const { data, events } = advance(this.#data, Date.now());
		if (events.length) this.#commit(data, events);
		else this.#options.onChange(this.#data);
	}

	/** One notice per phase end: every frame raises it, the key keeps only one. */
	#announce(event: PhaseEvent): void {
		const key = `pomodoro:${event.startedAt}`;
		if (event.type === 'focusDone') {
			const next = this.#data.timer.phase;
			notify({
				app: 'pomodoro',
				key,
				title: { id: 'app.pomodoro.notice.focusDone.title', fallback: 'Focus finished' },
				body: next === 'longBreak'
					? { id: 'app.pomodoro.notice.focusDone.longBreak', params: { minutes: this.#data.settings.longBreakMinutes }, fallback: 'Time for a long break ({minutes} min).' }
					: next === 'shortBreak'
						? { id: 'app.pomodoro.notice.focusDone.shortBreak', params: { minutes: this.#data.settings.shortBreakMinutes }, fallback: 'Time for a break ({minutes} min).' }
						: { id: 'app.pomodoro.notice.focusDone.idle', fallback: 'Take a break when you are ready.' },
			});
		} else {
			notify({
				app: 'pomodoro',
				key,
				title: { id: 'app.pomodoro.notice.breakDone.title', fallback: 'Break finished' },
				body: { id: 'app.pomodoro.notice.breakDone.body', fallback: 'Start the next focus when you are ready.' },
			});
		}
	}

	// --- sound ---------------------------------------------------------------

	#takeAudio(): void {
		if (!this.#data.settings.ambienceMixId) return;
		this.#audioOwner = true;
		this.#ambienceChannel?.postMessage({ type: 'playing', source: 'pomodoro' } satisfies AmbienceMessage);
		this.#syncAudio();
	}

	#syncAudio(): void {
		const s = this.#data.settings;
		const mix = this.mixes.find((m) => m.id === s.ambienceMixId);
		const shouldPlay = this.#audioOwner && !!mix && this.#data.timer.phase === 'focus' && isRunning(this.#data.timer);
		if (!shouldPlay) {
			this.#engine?.pause();
			return;
		}
		this.#engine ??= new AmbientEngine();
		this.#engine.setLevels(mix!.levels);
		this.#engine.setVolume(s.ambienceVolume);
		if (!this.#engine.playing) {
			this.#engine.play().catch((e) => console.warn('[Pomodoro] Sound could not be started:', e));
		}
	}
}
