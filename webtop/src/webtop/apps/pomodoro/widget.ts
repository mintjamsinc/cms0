/**
 * Pomodoro — the `timer` widget.
 *
 * A small timer kept above the windows (pinned by default): the plant, the
 * phase and the time left, start / pause, skip, and a way to the window with
 * the settings and the record. The timer itself is the shared session
 * (session.ts), so the widget and the window always show the same.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import type { WidgetInstance } from '../../services/webtop-service.js';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from '../../composables/use-localization.js';
import { PomodoroSession } from './session.js';
import { emptyData, type PomodoroData } from './timer.js';
import { drawPlant } from './plant.js';
import { timerView, type TimerView } from './view.js';

const PLANT_SCALE = 4;

let session: PomodoroSession | null = null;
let drawnStage = -1;

const Widget = {
	data() {
		return {
			widget: null as WidgetInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			localization: createLocalizationSnapshot(),
			isReady: false,
			view: timerView(emptyData(), Date.now()) as TimerView,
		};
	},
	computed: {
		phaseLabel(): string {
			return this.t('app.pomodoro.phase.' + this.view.phase, undefined, this.view.phase);
		},
	},
	methods: {
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.widget, messageId, params, fallback);
		},

		onMounted() {
			const vm = this;
			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (handleLocalizationMessage(type, vm.localization, vm.widget)) return;
				if (type === 'theme-changed') document.documentElement.dataset.theme = payload.theme;
			};
			window.addEventListener('message', vm.messageListener);

			window.widgetLaunch = async (widget: WidgetInstance) => {
				vm.widget = this.$markRaw(widget);
				refreshLocalization(vm.localization, vm.widget);
				document.documentElement.dataset.theme = widget.api.theme.currentTheme || 'light';

				session = new PomodoroSession({
					api: widget.api,
					userId: widget.currentUser?.id || '',
					onChange: (data: PomodoroData) => vm.show(data),
				}, emptyData());
				await session.init();
				vm.isReady = true;
				vm.$nextTick(() => {
					vm.show(session!.data);
					widget.notifyLaunched();
				});
			};
		},
		onUnmount() {
			if (this.messageListener) window.removeEventListener('message', this.messageListener);
			session?.dispose();
			session = null;
		},

		show(data: PomodoroData) {
			const vm = this;
			vm.view = timerView(data, Date.now());
			const canvas = vm.$refs.plant as HTMLCanvasElement | undefined;
			if (canvas && vm.view.stage !== drawnStage) {
				drawnStage = vm.view.stage;
				drawPlant(canvas, drawnStage, PLANT_SCALE);
			}
		},
		toggle() {
			if (!session) return;
			if (this.view.running) session.pause();
			else session.start();
		},
		skip() {
			session?.skip();
		},
		openApp() {
			this.widget?.openApp();
		},
	},
};

VDOM.createApp(Widget).mount('#timer');
