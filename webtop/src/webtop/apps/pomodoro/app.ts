/**
 * Pomodoro Application
 *
 * Focus for a while, take a short break, and after a few rounds a long one;
 * a pixel-art houseplant grows with the minutes focused. The window shows
 * the plant large, the timer, today's and the week's record, and the
 * settings, including an Ambience mix to play during focus. The timer widget
 * keeps the same timer above the windows. Both run the shared session
 * (session.ts) over the user's state file (store.ts).
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import type { ApplicationInstance } from '../../services/webtop-service.js';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from '../../composables/use-localization.js';
import { PomodoroSession, type MixChoice } from './session.js';
import { emptyData, localDay, type PomodoroData, type Settings } from './timer.js';
import { drawPlant, STAGES } from './plant.js';
import { timerView, type TimerView } from './view.js';

const TIMER_WIDGET = 'timer';
const PLANT_SCALE = 8;
const WEEK_DAYS = 7;

let session: PomodoroSession | null = null;
let drawnStage = -1;
let focusListener: (() => void) | null = null;
// updatedAt of the state last shown in full (settings, week); ticks in between only move the clock.
let shownAt = '';

interface DayBar {
	day: string;
	label: string;
	minutes: number;
	height: number;   // 0..100 (%)
	today: boolean;
}

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			localization: createLocalizationSnapshot(),
			isReady: false,
			view: timerView(emptyData(), Date.now()) as TimerView,
			settings: { ...emptyData().settings } as Settings,
			mixes: [] as MixChoice[],
			week: [] as DayBar[],
			weekMinutes: 0,
		};
	},
	computed: {
		phaseLabel(): string {
			return this.t('app.pomodoro.phase.' + this.view.phase, undefined, this.view.phase);
		},
		canReset(): boolean {
			return this.view.phase !== 'idle' || this.view.dots.some((done: boolean) => done);
		},
		stageLabel(): string {
			const s = STAGES[this.view.stage];
			return this.t('app.pomodoro.plant.' + s.key, undefined, s.label);
		},
		nextStageText(): string {
			const left = this.view.minutesToNext;
			return left === null
				? this.t('app.pomodoro.plant.fullyGrown', undefined, 'Fully grown. Thank you for all the focus.')
				: this.t('app.pomodoro.plant.toNext', { duration: this.duration(left) }, 'Grows again after {duration} more of focus');
		},
	},
	methods: {
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		duration(minutes: number): string {
			const h = Math.floor(minutes / 60);
			const m = minutes % 60;
			if (!h) return this.t('app.pomodoro.duration.m', { m }, '{m} min');
			if (!m) return this.t('app.pomodoro.duration.h', { h }, '{h} h');
			return this.t('app.pomodoro.duration.hm', { h, m }, '{h} h {m} min');
		},
		mixLabel(m: MixChoice): string {
			return m.builtinKey ? this.t('app.ambience.mix.' + m.builtinKey, undefined, m.label) : m.label;
		},

		onMounted() {
			const vm = this;
			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) return;
				if (type === 'theme-changed') document.documentElement.dataset.theme = payload.theme;
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (instance: ApplicationInstance) => {
				vm.instance = this.$markRaw(instance);
				refreshLocalization(vm.localization, vm.instance);
				document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light';

				session = new PomodoroSession({
					api: instance.api,
					userId: instance.currentUser?.id || '',
					onChange: (data: PomodoroData) => vm.show(data),
				}, emptyData());
				await session.init();
				vm.mixes = session.mixes;
				vm.settings = { ...session.data.settings };

				// Mixes saved in the Ambience app meanwhile.
				focusListener = () => {
					session?.refreshMixes().then(() => { vm.mixes = session?.mixes || []; });
				};
				window.addEventListener('focus', focusListener);

				instance.setBeforeCloseCallback(async () => {
					if (focusListener) window.removeEventListener('focus', focusListener);
					await session?.dispose();
					session = null;
					return true;
				});

				vm.isReady = true;
				vm.$nextTick(() => {
					vm.show(session!.data);
					instance.notifyLaunched();
				});
			};
		},
		onUnmount() {
			if (this.messageListener) window.removeEventListener('message', this.messageListener);
			if (focusListener) window.removeEventListener('focus', focusListener);
			session?.dispose();
			session = null;
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

		// --- showing ----------------------------------------------------------

		show(data: PomodoroData) {
			const vm = this;
			const now = Date.now();
			const view = timerView(data, now);
			vm.view = view;
			if (data.updatedAt !== shownAt) {
				shownAt = data.updatedAt;
				vm.settings = { ...data.settings };
				vm.showWeek(data, now);
			}
			const canvas = vm.$refs.plant as HTMLCanvasElement | undefined;
			if (canvas && view.stage !== drawnStage) {
				drawnStage = view.stage;
				drawPlant(canvas, drawnStage, PLANT_SCALE);
			}
			vm.instance?.setDisplayInfo({ subtitle: view.phase === 'idle' ? '' : `${vm.phaseLabel} ${view.clock}` });
		},
		showWeek(data: PomodoroData, now: number) {
			const vm = this;
			const days: { day: string; minutes: number; date: Date }[] = [];
			for (let i = WEEK_DAYS - 1; i >= 0; i--) {
				const date = new Date(now - i * 86_400_000);
				const day = localDay(date.getTime());
				days.push({ day, minutes: data.history[day]?.minutes || 0, date });
			}
			const max = Math.max(60, ...days.map((d) => d.minutes));
			const today = localDay(now);
			vm.week = days.map((d) => ({
				day: d.day,
				label: d.date.toLocaleDateString(vm.localization.locale || undefined, { weekday: 'short' }),
				minutes: d.minutes,
				height: Math.round((d.minutes / max) * 100),
				today: d.day === today,
			}));
			vm.weekMinutes = days.reduce((sum, d) => sum + d.minutes, 0);
		},

		// --- timer ------------------------------------------------------------

		toggle() {
			if (!session) return;
			if (this.view.running) session.pause();
			else session.start();
		},
		skip() {
			session?.skip();
		},
		reset() {
			session?.reset();
		},
		putOnDesktop() {
			this.instance?.addWidget(TIMER_WIDGET);
		},

		// --- settings ---------------------------------------------------------

		onNumberChange(key: 'focusMinutes' | 'shortBreakMinutes' | 'longBreakMinutes' | 'longBreakEvery', event: Event, min: number, max: number) {
			const input = event.target as HTMLInputElement;
			const value = Math.max(min, Math.min(max, Math.round(Number(input.value) || min)));
			input.value = String(value);
			session?.updateSettings({ [key]: value });
		},
		onAutoStartChange(event: Event) {
			session?.updateSettings({ autoStartBreaks: (event.target as HTMLInputElement).checked });
		},
		onMixChange(event: Event) {
			const value = (event.target as HTMLSelectElement).value;
			session?.updateSettings({ ambienceMixId: value || null });
		},
		onVolumeInput(event: Event) {
			session?.updateSettings({ ambienceVolume: Number((event.target as HTMLInputElement).value) / 100 });
		},
	},
};

VDOM.createApp(App).mount('#app');
