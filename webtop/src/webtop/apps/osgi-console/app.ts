/**
 * OSGi Console Application
 *
 * Admin-only viewer that embeds the Felix OSGi Console (/system/console)
 * inside a single-pane translucent Webtop window.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import { ApplicationInstance } from "../../services/webtop-service.js";

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
		};
	},
	methods: {
		onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (instance: ApplicationInstance) => {
				vm.instance = this.$markRaw(instance);

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				this.$nextTick(() => {
					instance.notifyLaunched();
				});
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
