// Webtop UI framework (wt-* components).
//
// One import + one call replaces the previous three manual steps for using a
// shared component (side-effect import, template fetch/injection, CSS link):
//
//     import { initUi } from '../../ui/index.js';
//     await initUi();
//     VDOM.createApp(App).mount('#app');
//
// - Component registration happens via the side-effect imports below.
// - initUi() fetches every component template in parallel and injects it into
//   the document (idempotent; subsequent calls return the same promise).
// - CSS needs no per-app link: assets/css/webtop-ui.css is @imported by
//   webtop-app.css, which every app already loads.

import { BUILD_VERSION } from '../utils/build-version.js';
import { mergeUiOptions, UiInitOptions } from './ui-config.js';

export { getUiOptions } from './ui-config.js';
export type { UiInitOptions, UiPopupAdapter, UiSelectPopupRequest } from './ui-config.js';

// Component registrations (side effects). Each module calls defineComponent
// for one wt-* tag; index.ts is the single place that decides what ships.
import './wt-badge.js';
import './wt-spinner.js';
import './wt-empty-state.js';
import './wt-inline-message.js';
import './wt-field.js';
import './wt-checkbox.js';
import './wt-search-box.js';
import './wt-select.js';
import './wt-dialog.js';
import './wt-tabs.js';
import './wt-section.js';
import './wt-property-row.js';
import './wt-tree.js';
import './wt-splitter.js';
import './wt-file-tabs.js';
import './wt-find-replace.js';
import './wt-live-indicator.js';
import './wt-dropzone.js';

/**
 * Registered component tags. One entry per component; kept in sync with the
 * side-effect imports above. The template file for each is `<tag>.html`.
 */
const COMPONENT_TAGS = [
	'wt-badge',
	'wt-spinner',
	'wt-empty-state',
	'wt-inline-message',
	'wt-field',
	'wt-checkbox',
	'wt-search-box',
	'wt-select',
	'wt-dialog',
	'wt-tabs',
	'wt-section',
	'wt-property-row',
	'wt-tree',
	'wt-splitter',
	'wt-file-tabs',
	'wt-find-replace',
	'wt-live-indicator',
	'wt-dropzone',
];

let uiReady: Promise<void> | undefined;

/**
 * Loads and injects every wt-* component template. Must resolve before an
 * application whose markup uses wt-* components is mounted — that ordering is
 * the application's responsibility, and ichigo.js warns (rather than quietly
 * rendering an empty element) when a component is compiled without its
 * template. Every app opens its readiness gate on this promise.
 *
 * Idempotent: repeated calls return the first invocation's promise (later
 * options are merged into the configuration, but templates are fetched only
 * once).
 */
export function initUi(options: UiInitOptions = {}): Promise<void> {
	mergeUiOptions(options);
	if (uiReady) {
		return uiReady;
	}

	const base = options.baseUrl ?? new URL('../../ui/', document.baseURI);
	uiReady = Promise.all(COMPONENT_TAGS.map(async (tag) => {
		const response = await fetch(new URL(`${tag}.html?v=${BUILD_VERSION}`, base));
		if (!response.ok) {
			throw new Error(`initUi: failed to load template '${tag}.html' (HTTP ${response.status})`);
		}
		const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
		for (const template of Array.from(doc.querySelectorAll('template'))) {
			document.body.appendChild(template);
		}
	})).then(() => undefined);

	return uiReady;
}
