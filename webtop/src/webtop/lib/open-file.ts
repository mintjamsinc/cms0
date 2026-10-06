/**
 * Opening a repository file from inside an app: in the editor registered for
 * its type, or in the Content Browser at the place where it lies. Both go
 * through the shell, which owns the windows, with the same messages the
 * Content Browser itself sends for a double-click.
 */

interface ShellApp {
	id: string;
	relPath?: string;
	editor?: boolean;
	contentTypes?: string[];
}

const CONTENT_BROWSER = 'content-browser';
const CHAT = 'chat';

function shellApps(): ShellApp[] {
	return ((window.parent as any)?.Webtop?.apps || []) as ShellApp[];
}

/** The editor registered for the type ("text/*" matches any text type), or null. */
export function findEditorForMimeType(mimeType: string | null | undefined): ShellApp | null {
	const type = mimeType || '';
	for (const app of shellApps()) {
		if (!app.editor) continue;
		for (const pattern of app.contentTypes || []) {
			if (pattern.endsWith('/*') ? type.startsWith(pattern.slice(0, -1)) : pattern === type) {
				return app;
			}
		}
	}
	return null;
}

/** Opens the file in its editor. False when no editor is registered for its type. */
export function openFileInEditor(path: string, mimeType: string | null | undefined): boolean {
	const editor = findEditorForMimeType(mimeType);
	if (!editor) return false;
	window.parent.postMessage({
		type: 'open-file-with-app',
		appId: editor.id,
		filePath: path,
		mimeType: mimeType || '',
	}, window.location.origin);
	return true;
}

/**
 * Opens a Content Browser on the folder the item lies in, with the item
 * selected; a folder is opened itself. False when there is no Content Browser.
 */
export function revealInContentBrowser(path: string, isCollection = false): boolean {
	const browser = shellApps().find((app) => app.relPath === CONTENT_BROWSER);
	if (!browser) return false;
	const index = path.lastIndexOf('/');
	const parent = index > 0 ? path.substring(0, index) : '/';
	window.parent.postMessage({
		type: 'open-app',
		appId: browser.id,
		options: isCollection ? { initialPath: path } : { initialPath: parent, select: path },
	}, window.location.origin);
	return true;
}

/**
 * Opens a conversation in the Chat app: `{ channelId }` or `{ fileId }`. The
 * app is a single window, which is brought to the front and shown the
 * conversation. False when there is no Chat app.
 */
export function openConversationInChat(ref: { channelId?: string; fileId?: string }): boolean {
	return openApp(CHAT, ref.channelId ? { channelId: ref.channelId } : { fileId: ref.fileId });
}

/**
 * Launches an app of the Webtop by the name of its folder (apps/<name>) with
 * launch options, which reach its appLaunch, or, for a singleton already
 * running, its window as an `app-reopen` message. False when there is no
 * such app.
 */
export function openApp(relPath: string, options?: Record<string, unknown>): boolean {
	const app = shellApps().find((a) => a.relPath === relPath);
	if (!app) return false;
	window.parent.postMessage({ type: 'open-app', appId: app.id, options: options || {} }, window.location.origin);
	return true;
}

/** The address a repository file is served from. */
export function downloadUrl(workspace: string, path: string, attachment = false): string {
	const encoded = path.split('/').map(encodeURIComponent).join('/');
	return `/bin/download.cgi/${encodeURIComponent(workspace)}${encoded}${attachment ? '?attachment' : ''}`;
}
