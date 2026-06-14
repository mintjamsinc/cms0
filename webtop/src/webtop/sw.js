// =============================================================================
// webtop Service Worker.
//
// Sole responsibility (today): make the text-editor preview reproduce published
// pages faithfully. Published content is authored with site-root-absolute asset
// references (e.g. `/docs/css/docs.css`, `/img/logo.svg`) that assume the site
// is mounted at the origin root — true for the public site, but NOT for the
// webtop preview, which serves the same files under
// `/bin/cms.cgi/{workspace}{documentRoot}/...`. An HTML `<base>` cannot remap
// root-absolute URLs (by spec they resolve against the origin, ignoring the base
// path), so the preview routes its rendered document through this worker, which:
//
//   1. serves the rendered (unsaved-buffer) HTML for a synthetic in-scope URL,
//      so the preview iframe is a real, same-origin, SW-controlled document
//      (blob:/srcdoc documents are NOT controlled and cannot be intercepted);
//   2. rewrites that document's site-root-absolute subresource requests to the
//      preview's mount prefix, so CSS, images, scripts and even runtime fetch()
//      calls resolve exactly as they do on the published site.
//
// SAFETY MODEL: the worker is registered with its natural webtop-path scope and
// stays completely out of all real webtop/CMS traffic. Every CMS request is
// served under `/bin/cms.cgi/...`; this worker passes those through untouched
// and only ever acts on (a) the synthetic preview document path and (b) a
// preview document's site-root-absolute (non-`/bin/`) subresource requests,
// gated on the initiating client actually being a preview document. A bug here
// therefore cannot affect the desktop, GraphQL, content delivery or app loading.
// =============================================================================

// Path segment that marks the synthetic preview document. The preview app loads
// its iframe from `.../apps/text-editor-preview/__sw-preview__/{key}?base=...`.
const PREVIEW_PATH = '/__sw-preview__/';

// Cache holding the rendered HTML per synthetic preview URL. Using the Cache API
// (rather than an in-memory map) keeps a preview servable across worker
// restarts, which the browser may do at any time between renders.
const PREVIEW_CACHE = 'webtop-text-preview-v1';

// All CMS-served traffic (webtop assets, GraphQL, content, the rewrite targets
// themselves) lives under this prefix; site-root-absolute asset references do
// not. Synchronously skipping it keeps the worker out of normal webtop traffic.
const CMS_PREFIX = '/bin/';

self.addEventListener('install', () => {
	// Activate immediately so a freshly opened preview iframe is controlled
	// without requiring a reload.
	self.skipWaiting();
});

self.addEventListener('activate', (event) => {
	// Claim existing clients (the shell that registered us, already-open app
	// iframes) so control is established up-front. Also drop stale caches.
	event.waitUntil((async () => {
		await self.clients.claim();
		const names = await caches.keys();
		await Promise.all(
			names.filter((n) => n.startsWith('webtop-text-preview-') && n !== PREVIEW_CACHE)
				.map((n) => caches.delete(n)),
		);
	})());
});

self.addEventListener('message', (event) => {
	const msg = event.data || {};
	if (msg.type === 'sw-preview-put') {
		// Store/replace the rendered HTML for a preview's synthetic URL, then
		// acknowledge so the preview only navigates its iframe once the document
		// is retrievable.
		event.waitUntil((async () => {
			try {
				const cache = await caches.open(PREVIEW_CACHE);
				await cache.put(
					new Request(msg.url),
					new Response(msg.html, {
						headers: {
							'Content-Type': 'text/html; charset=UTF-8',
							'Cache-Control': 'no-store',
						},
					}),
				);
			} finally {
				if (event.source) {
					event.source.postMessage({ type: 'sw-preview-ack', key: msg.key });
				}
			}
		})());
	} else if (msg.type === 'sw-preview-del') {
		event.waitUntil((async () => {
			const cache = await caches.open(PREVIEW_CACHE);
			await cache.delete(new Request(msg.url), { ignoreSearch: true });
		})());
	}
});

self.addEventListener('fetch', (event) => {
	const request = event.request;
	let url;
	try {
		url = new URL(request.url);
	} catch {
		return;
	}

	// Leave every cross-origin request (fonts, CDNs, external links) untouched.
	if (url.origin !== self.location.origin) {
		return;
	}

	// (1) Navigation to a synthetic preview document → serve the stored HTML.
	if (url.pathname.indexOf(PREVIEW_PATH) !== -1) {
		event.respondWith(servePreviewDocument(url));
		return;
	}

	// (2) Everything the CMS serves lives under `/bin/`; pass it through so the
	// worker never interferes with the desktop, APIs, content or app loading.
	if (url.pathname.startsWith(CMS_PREFIX)) {
		return;
	}

	// (3) A remaining same-origin, root-absolute request is a candidate site
	// asset. Rewrite it to the preview mount ONLY when it actually originates
	// from a preview document; otherwise pass it through unchanged.
	if (!url.pathname.startsWith('/')) {
		return;
	}
	event.respondWith(rewriteIfPreviewAsset(event, url));
});

async function servePreviewDocument(url) {
	const cache = await caches.open(PREVIEW_CACHE);
	// The synthetic URL carries a cache-busting `seq`; match on path only.
	const hit = await cache.match(new Request(url.href), { ignoreSearch: true });
	if (hit) {
		return hit;
	}
	return new Response(
		'<!doctype html><meta charset="utf-8"><body>Preview is not ready.</body>',
		{ status: 404, headers: { 'Content-Type': 'text/html; charset=UTF-8' } },
	);
}

async function rewriteIfPreviewAsset(event, url) {
	const request = event.request;
	let base = '';
	try {
		const client = await self.clients.get(event.clientId);
		if (client) {
			const clientUrl = new URL(client.url);
			if (clientUrl.pathname.indexOf(PREVIEW_PATH) !== -1) {
				base = clientUrl.searchParams.get('base') || '';
			}
		}
	} catch {
		/* no resolvable client — treat as non-preview */
	}

	// Rewrite only genuine site-absolute references (those not already under the
	// mount). `base` is e.g. `/bin/cms.cgi/{workspace}/content/public`.
	if (base && !url.pathname.startsWith(base + '/') && url.pathname !== base) {
		const target = url.origin + base + url.pathname + url.search;
		return fetch(new Request(target, {
			method: request.method,
			headers: request.headers,
			body: request.method === 'GET' || request.method === 'HEAD' ? undefined : await safeBody(request),
			credentials: request.credentials,
			redirect: request.redirect,
			mode: request.mode === 'navigate' ? 'same-origin' : request.mode,
			referrerPolicy: request.referrerPolicy,
		}));
	}
	return fetch(request);
}

// Reads a non-GET request body once so it can be forwarded to the rewritten URL.
// Most preview subresources are GETs; this exists so a form POST or fetch() with
// a body still works when its target is rewritten.
async function safeBody(request) {
	try {
		return await request.clone().arrayBuffer();
	} catch {
		return undefined;
	}
}
