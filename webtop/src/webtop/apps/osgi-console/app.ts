/**
 * OSGi Console Application
 *
 * Admin-only viewer that embeds the Felix OSGi Console (/system/console)
 * inside a single-pane translucent Webtop window. The Felix console shows and
 * changes only the node that served the window, and Webtop does not choose
 * the node, so in a cluster the status bar names it.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import { ApplicationInstance } from "../../services/webtop-service.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			// Reactive Localization snapshot — see composables/use-localization.ts.
			localization: createLocalizationSnapshot(),
			// The cluster node the console belongs to; empty when not clustered.
			nodeLabel: '',
		};
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

				this.$nextTick(() => {
					instance.notifyLaunched();
				});

				try {
					const cluster = await vm.instance.api.webtop.getCluster();
					if (cluster.enabled) {
						const self = cluster.members.find((m) => m.self);
						const nodeId = self?.nodeId || cluster.nodeId || '';
						vm.nodeLabel = (self?.hostName && self.hostName !== nodeId) ? `${self.hostName} (${nodeId})` : nodeId;
					}
				} catch (err) {
					console.warn('[OsgiConsole] Failed to read the cluster topology:', err);
				}
			};
		},
		onUnmount() {
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
			}
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
	},
};

VDOM.createApp(App).mount('#app');
