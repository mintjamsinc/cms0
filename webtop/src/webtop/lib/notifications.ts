/**
 * Notices for the reader: what the shell shows as a toast at the corner of
 * the desktop and keeps in its notification center. A notice tells that
 * something happened — a message for the reader, a task — and where to go:
 * a click opens an app with launch options.
 *
 * A notice reaches the shell in one of two ways:
 *
 *   - as a topic message on `webtop/notifications` (NOTIFICATIONS_TOPIC),
 *     published by a script for named recipients:
 *
 *       EventAdminAPI.publish('webtop/notifications', [
 *           app: 'chat',
 *           title: [id: 'app.chat.notify.direct.title', params: [author: name], fallback: name],
 *           body: text,
 *           options: [channelId: id, messageId: messageId],
 *           context: 'chat:channel:' + id,
 *           key: 'chat:channel:' + id,
 *       ], [recipients: [peer]]);
 *
 *   - from an app, for something that happened in the browser only, with
 *     {@link notify}.
 *
 * `title` and `body` are text, or an i18n message to resolve for the reader
 * (`{ id, params, fallback }`), looked up in the scope of `app` first — the
 * app's own bundle — then the global bundles, so a notice published once
 * reads right in every recipient's language.
 *
 * `context` names what the notice is about. An app tells the shell what it
 * is showing with {@link setAppContext}; a notice whose context the active
 * window is showing is not raised, since the reader is looking at it.
 */

export const NOTIFICATIONS_TOPIC = 'webtop/notifications';

export interface NoticeText {
	id: string;
	params?: Record<string, unknown>;
	fallback?: string;
}

export interface Notice {
	/** The app (its folder name under apps/) a click opens; the i18n scope of the texts. */
	app?: string;
	title: string | NoticeText;
	body?: string | NoticeText;
	/** Bootstrap icon class, shown when the app has no icon. */
	icon?: string;
	/** Launch options for the app. */
	options?: Record<string, unknown>;
	/** What the notice is about; see {@link setAppContext}. */
	context?: string;
	/** Notices with the same key supersede one another. */
	key?: string;
}

const MAX_TEXT = 500;
const MAX_KEY = 255;

function isRecord(x: unknown): x is Record<string, unknown> {
	return typeof x === 'object' && x !== null && !Array.isArray(x);
}

function text(x: unknown): string | NoticeText | null {
	if (typeof x === 'string') return x.length > MAX_TEXT ? x.slice(0, MAX_TEXT) : x;
	if (isRecord(x) && typeof x.id === 'string' && x.id) {
		const fallback = typeof x.fallback === 'string' ? x.fallback : undefined;
		const params = isRecord(x.params) ? x.params : undefined;
		return { id: x.id, params, fallback };
	}
	return null;
}

function short(x: unknown): string | undefined {
	return typeof x === 'string' && x && x.length <= MAX_KEY ? x : undefined;
}

/**
 * The notice a payload describes, or null when it is no notice: a title is
 * required, everything else is taken when well-formed and left out
 * otherwise. Payloads come from other users and from other apps, so
 * nothing in them is trusted beyond its shape.
 */
export function toNotice(payload: unknown): Notice | null {
	if (!isRecord(payload)) return null;
	const title = text(payload.title);
	if (title === null) return null;
	const notice: Notice = { title };
	const body = text(payload.body);
	if (body !== null) notice.body = body;
	const app = short(payload.app);
	if (app && /^[A-Za-z0-9_.-]+$/.test(app)) notice.app = app;
	const icon = short(payload.icon);
	if (icon && /^bi-[a-z0-9-]+$/.test(icon)) notice.icon = icon;
	if (isRecord(payload.options)) notice.options = payload.options;
	const context = short(payload.context);
	if (context) notice.context = context;
	const key = short(payload.key);
	if (key) notice.key = key;
	return notice;
}

/**
 * Raises a notice from an app, for something that happened in this browser
 * only (a long job finished, a download is ready). The app is the sender's
 * unless `app` names another.
 */
export function notify(notice: Notice): void {
	window.parent.postMessage({ type: 'notify', ...notice }, window.location.origin);
}

/**
 * Tells the shell what the app's window is showing — a conversation, a
 * room, a file — so that a notice about it is not raised while the reader
 * is looking at it. Call it whenever that changes; an empty key clears it.
 */
export function setAppContext(key: string | null | undefined): void {
	window.parent.postMessage({ type: 'app-context', key: key || '' }, window.location.origin);
}
