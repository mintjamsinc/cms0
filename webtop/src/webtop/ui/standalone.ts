// BPMN-form distribution bundle (built to dist/webtop/ui/wt-ui.esm.js).
//
// Forms import this ONE module: it bundles ichigo.js and registers every
// wt-* component, so a form must NOT import ichigo.js separately (that would
// double-load the framework and double-define the custom elements). Usage
// from a form, resolved through the Tasks form host so the form needs no
// knowledge of webtop's deploy location:
//
//     const { VDOM, initUi } =
//         await import(new URL('ui/wt-ui.esm.js', host.webtopBaseUrl).href);
//     await initUi();
//     VDOM.createApp(App).mount('#app');
//
// initUi() here additionally injects the matching stylesheet (wt-ui.css,
// deployed next to this bundle) and fetches the component templates relative
// to import.meta.url.

import { BUILD_VERSION } from '../utils/build-version.js';
import { initUi as initUiCore } from './index.js';
import type { UiInitOptions } from './ui-config.js';

export { VDOM, defineComponent } from '@mintjamsinc/ichigojs';
export { getUiOptions } from './ui-config.js';
export type { UiInitOptions, UiPopupAdapter, UiSelectPopupRequest } from './ui-config.js';

let cssInjected = false;

/**
 * Standalone variant of initUi(): injects wt-ui.css and loads the component
 * templates from the bundle's own directory. Idempotent, same as the core.
 */
export function initUi(options: UiInitOptions = {}): Promise<void> {
	const base = new URL('./', import.meta.url);
	if (!cssInjected) {
		cssInjected = true;
		const link = document.createElement('link');
		link.rel = 'stylesheet';
		link.href = new URL(`wt-ui.css?v=${BUILD_VERSION}`, base).href;
		document.head.appendChild(link);
	}
	return initUiCore({ baseUrl: base, ...options });
}
