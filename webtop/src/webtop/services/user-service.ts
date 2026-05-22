import { sha256Hex } from './webtop-util.js';
import { Identicon } from '../lib/identicon.js';
import type { IdpUser, IdpGroup } from '../graphql/types.js';

export class User {
	#data: IdpUser | null;

	constructor(data: IdpUser | null) {
		this.#data = data;
	}

	get id(): string {
		return this.#data?.username ?? '';
	}

	get isAnonymous(): boolean {
		return this.#data === null;
	}

	get isGroup(): boolean {
		return false;
	}

	get isAdmin(): boolean {
		return !!this.#data?.roles?.some(
			r => r.name === 'administrator' || r.roleId === 'administrator'
		);
	}

	get fullName(): string {
		return this.#data?.displayName ?? this.#data?.username ?? '';
	}

	get mail(): string {
		return this.#data?.mail ?? '';
	}

	get groups(): IdpGroup[] {
		return this.#data?.effectiveGroups ?? [];
	}

	get lastModified(): Date {
		return this.#data?.lastModified ? new Date(this.#data.lastModified) : new Date();
	}

	async getPhotoURL(): Promise<string> {
		const hash = await sha256Hex(this.id || 'anonymous');
		return new Identicon(hash, {
			background: [245, 245, 245, 255],
			margin: 0.2,
			format: 'svg',
		}).dataURL;
	}
}
