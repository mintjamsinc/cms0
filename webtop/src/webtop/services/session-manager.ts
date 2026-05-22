const SESSION_MIME_TYPE = 'application/vnd.webtop.desktop-session+json';

export interface SessionEnvironment {
	layoutViewport: { width: number; height: number };
	visualViewport: { width: number; height: number; scale: number };
	devicePixelRatio: number;
	coordinateSystem: 'layoutViewport';
	unit: 'px';
}

export interface WindowState {
	appId: string;
	x: number;
	y: number;
	width: number;
	height: number;
	zIndex: number;
	maximized?: boolean;
	launchOptions?: Record<string, unknown>;
}

export interface SessionData {
	displayName: string;
	savedAt: string;
	environment: SessionEnvironment;
	windows: WindowState[];
}

export interface SessionEntry {
	id: string;
	displayName: string;
	savedAt: string;
	modified: number;
	downloadUrl: string;
}

export class SessionManager {
	#api;

	/** @param {WebtopAPI} api */
	constructor(api) {
		this.#api = api;
	}

	generateSessionFilename(): string {
		const now = new Date();
		const pad = (n: number, len = 2) => String(n).padStart(len, '0');
		const ts =
			now.getFullYear().toString() +
			pad(now.getMonth() + 1) +
			pad(now.getDate()) +
			pad(now.getHours()) +
			pad(now.getMinutes()) +
			pad(now.getSeconds()) +
			pad(now.getMilliseconds(), 3);
		return `session_${ts}`;
	}

	captureEnvironment(): SessionEnvironment {
		return {
			layoutViewport: {
				width: document.documentElement.clientWidth,
				height: document.documentElement.clientHeight,
			},
			visualViewport: {
				width: window.visualViewport?.width ?? window.innerWidth,
				height: window.visualViewport?.height ?? window.innerHeight,
				scale: window.visualViewport?.scale ?? 1,
			},
			devicePixelRatio: window.devicePixelRatio,
			coordinateSystem: 'layoutViewport',
			unit: 'px',
		};
	}

	/**
	 * Scale a window state from the saved environment to the current viewport.
	 */
	scaleWindowState(win: WindowState, savedEnv: SessionEnvironment): WindowState {
		const cw = document.documentElement.clientWidth;
		const ch = document.documentElement.clientHeight;
		const sw = savedEnv.layoutViewport.width;
		const sh = savedEnv.layoutViewport.height;
		if (sw === cw && sh === ch) return win;
		const sx = cw / sw;
		const sy = ch / sh;
		return {
			...win,
			x: Math.round(win.x * sx),
			y: Math.round(win.y * sy),
			width: Math.round(win.width * sx),
			height: Math.round(win.height * sy),
		};
	}

	async saveSession(displayName: string, windows: WindowState[]): Promise<string> {
		const userId = this.#api.context.currentUser?.id;
		if (!userId) throw new Error('Not logged in');

		const data: SessionData = {
			displayName,
			savedAt: new Date().toISOString(),
			environment: this.captureEnvironment(),
			windows,
		};

		const json = JSON.stringify(data, null, 2);
		// UTF-8 safe base64 encode
		const base64 = btoa(
			encodeURIComponent(json).replace(/%([0-9A-F]{2})/g, (_, p) => String.fromCharCode(parseInt(p, 16)))
		);

		const filename = this.generateSessionFilename();
		const folderPath = `/home/users/${userId}/preferences/desktop/sessions`;
		const systemContent = this.#api.systemContent;

		const uploadInfo = await systemContent.initiateMultipartUpload();
		await systemContent.appendMultipartUploadChunk(uploadInfo.uploadId, base64);
		await systemContent.completeMultipartUpload(
			uploadInfo.uploadId,
			folderPath,
			filename,
			SESSION_MIME_TYPE,
			false,
		);

		await systemContent.setProperty(`${folderPath}/${filename}`, 'displayName', displayName);

		return filename;
	}

	async listSessions(): Promise<SessionEntry[]> {
		const userId = this.#api.context.currentUser?.id;
		if (!userId) return [];

		const path = `/home/users/${userId}/preferences/desktop/sessions`;
		let connection: any;
		try {
			connection = await this.#api.systemContent.listChildren(path);
		} catch {
			return [];
		}

		const nodes = (connection?.edges ?? [])
			.map((e: any) => e.node)
			.filter((n: any) => n?.downloadUrl && n?.mimeType === SESSION_MIME_TYPE);

		if (nodes.length === 0) return [];

		const entries = nodes.map((node: any) => {
			const props: any[] = node.properties ?? [];
			const displayNameProp = props.find((p: any) => p.name === 'displayName');
			const displayName = displayNameProp?.propertyValue?.value ?? node.name;

			return {
				id: node.name,
				displayName,
				savedAt: node.modified,
				modified: new Date(node.modified).getTime(),
				downloadUrl: node.downloadUrl,
			} as SessionEntry;
		});

		return entries.sort((a, b) => b.modified - a.modified);
	}

	async loadSession(downloadUrl: string): Promise<SessionData> {
		const res = await fetch(downloadUrl);
		if (!res.ok) throw new Error(`Failed to load session: ${res.status}`);
		return await res.json();
	}

	async deleteSession(filename: string): Promise<void> {
		const userId = this.#api.context.currentUser?.id;
		if (!userId) return;
		const path = `/home/users/${userId}/preferences/desktop/sessions/${filename}`;
		await this.#api.systemContent.deleteNode(path);
	}
}
