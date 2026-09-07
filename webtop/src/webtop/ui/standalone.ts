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

let cssReady: Promise<void> | undefined;

/**
 * Appends the wt-ui.css <link> and resolves once it has actually applied.
 *
 * Waiting matters: wt-ui.css is nothing but @imports, so the browser resolves
 * webtop-app.css and its own imports in a serial chain that can easily finish
 * after the parallel template fetches below. A form that mounted on the bare
 * promise would paint unstyled for a frame or two. A stylesheet <link> fires
 * `load` only after its imported sheets have loaded too, so this one event is
 * the whole chain.
 *
 * Resolves on error as well: a form rendered with no styles beats a form that
 * never mounts.
 */
function injectCss(base: URL): Promise<void> {
	if (cssReady) {
		return cssReady;
	}
	cssReady = new Promise<void>((resolve) => {
		const link = document.createElement('link');
		link.rel = 'stylesheet';
		link.href = new URL(`wt-ui.css?v=${BUILD_VERSION}`, base).href;
		link.onload = link.onerror = () => resolve();
		document.head.appendChild(link);
	});
	return cssReady;
}

/**
 * Standalone variant of initUi(): injects wt-ui.css and loads the component
 * templates from the bundle's own directory. Idempotent, same as the core.
 *
 * Awaiting this is therefore enough for a form to mount: both the templates
 * and the stylesheet are in place, so nothing paints unstyled and rules a form
 * adds to wt-ui.css are guaranteed to be in effect.
 *
 * This does NOT cover the window before the stylesheet lands, so a form still
 * declares [v-cloak] in its own inline <style>: the cloak has to apply on the
 * very first paint, when the <link> is still in flight and the uncompiled
 * mustaches would otherwise be on screen.
 */
export async function initUi(options: UiInitOptions = {}): Promise<void> {
	const base = new URL('./', import.meta.url);
	await Promise.all([
		injectCss(base),
		initUiCore({ baseUrl: base, ...options }),
	]);
}
