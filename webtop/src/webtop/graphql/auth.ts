/**
 * Staying signed in.
 *
 * The GraphQL endpoint serves guests too: a call without authentication is
 * simply run as the guest. Once the shell knows who the user is, every call
 * carries {@link AUTH_HEADER}, and the server answers 401 instead when the
 * authentication is gone (the auth token expired, the user signed out in
 * another tab). The caller then waits for the shell to sign the user in
 * again ({@link reauthenticate}) and repeats the call.
 *
 * Apps run in iframes with their own copy of this module, so the shell is
 * found by walking up the window chain.
 */

export const AUTH_HEADER = 'X-MintJams-Auth';

interface Shell {
	currentUser?: { isAnonymous: boolean } | null;
	reauthenticate(): Promise<boolean>;
}

/** The Webtop shell this window lives in, or null (a page opened on its own). */
function findShell(): Shell | null {
	let w: Window = window;
	for (let depth = 0; depth < 8; depth++) {
		try {
			const candidate = (w as any).Webtop;
			if (candidate && typeof candidate.reauthenticate === 'function') {
				return candidate as Shell;
			}
		} catch {
			// A cross-origin ancestor: not the shell.
		}
		let parent: Window;
		try {
			parent = w.parent;
		} catch {
			break;
		}
		if (!parent || parent === w) break;
		w = parent;
	}
	return null;
}

/**
 * The headers of a call that must run as the signed-in user: empty until the
 * shell has identified the user, so the first `me` of a visitor who is not
 * signed in still gets its ordinary answer.
 */
export function authHeaders(): Record<string, string> {
	const user = findShell()?.currentUser;
	return user && !user.isAnonymous ? { [AUTH_HEADER]: 'required' } : {};
}

/**
 * Asks the shell to sign the user in again. Resolves true when the same user
 * is signed in again and the call can be repeated; false when there is no
 * shell to ask.
 */
export async function reauthenticate(): Promise<boolean> {
	const shell = findShell();
	if (!shell) return false;
	try {
		return await shell.reauthenticate();
	} catch {
		return false;
	}
}
