/**
 * Radio Browser directory client.
 *
 * Radio Browser (radio-browser.info) is a public, community-maintained
 * directory of internet radio stations with a CORS-enabled JSON API. The app
 * only reads from it; the one write is the click counter the API asks
 * clients to bump when a station is played, which feeds its popularity
 * ordering.
 *
 * The API is served by several mirrors. The client asks the round-robin
 * host for the current mirror list and settles on one; when a call fails it
 * moves to the next mirror before giving up.
 */

import type { Station } from './library.js';

const SERVER_LIST_URL = 'https://all.api.radio-browser.info/json/servers';
const FALLBACK_SERVERS = ['de1.api.radio-browser.info', 'at1.api.radio-browser.info', 'nl1.api.radio-browser.info'];
const REQUEST_TIMEOUT_MS = 10000;

/** Directory ordering: community votes ("popular") or play clicks. */
export type StationOrder = 'votes' | 'clickcount';

/** One entry of a facet list (tags, countries, languages) with its station count. */
export interface DirectoryFacet {
	/** Value passed back to the search (tag name, ISO 3166-1 code, language name). */
	value: string;
	/** Directory label (English for countries and languages). */
	name: string;
	/** ISO code when the directory has one (countries, languages). */
	code: string;
	stationcount: number;
}

export interface StationQuery {
	name?: string;
	countryCode?: string;
	language?: string;
	tag?: string;
	order: StationOrder;
	limit?: number;
}

interface ApiStation {
	stationuuid: string;
	name: string;
	url: string;
	url_resolved: string;
	homepage: string;
	favicon: string;
	tags: string;
	country: string;
	countrycode: string;
	language: string;
	codec: string;
	bitrate: number;
	hls: number;
	clickcount: number;
	votes: number;
}

export class RadioBrowserClient {
	#servers: string[] = [];
	#serverIndex = 0;
	#ready: Promise<void> | null = null;

	/** Host name of the mirror currently in use; empty until the first call. */
	get server(): string {
		return this.#servers[this.#serverIndex] || '';
	}

	async #ensureServers(): Promise<void> {
		if (!this.#ready) {
			this.#ready = (async () => {
				try {
					const res = await fetchWithTimeout(SERVER_LIST_URL);
					const list: { name: string }[] = await res.json();
					const names = Array.from(new Set(list.map((s) => s.name).filter(Boolean)));
					this.#servers = names.length ? shuffle(names) : [...FALLBACK_SERVERS];
				} catch {
					this.#servers = [...FALLBACK_SERVERS];
				}
				this.#serverIndex = 0;
			})();
		}
		await this.#ready;
	}

	async #get<T>(path: string): Promise<T> {
		await this.#ensureServers();
		let lastError: unknown = null;
		for (let attempt = 0; attempt < this.#servers.length; attempt++) {
			const host = this.#servers[this.#serverIndex];
			try {
				const res = await fetchWithTimeout(`https://${host}${path}`);
				if (!res.ok) {
					throw new Error(`HTTP ${res.status}`);
				}
				return (await res.json()) as T;
			} catch (err) {
				lastError = err;
				this.#serverIndex = (this.#serverIndex + 1) % this.#servers.length;
			}
		}
		throw lastError instanceof Error ? lastError : new Error('Directory unavailable');
	}

	/**
	 * Stations matching every given condition, best first by `order`. An
	 * empty query lists the whole directory in that order.
	 */
	async searchStations(query: StationQuery): Promise<Station[]> {
		const q = new URLSearchParams({
			order: query.order,
			reverse: 'true',
			hidebroken: 'true',
			limit: String(query.limit ?? 80),
		});
		if (query.name) q.set('name', query.name);
		if (query.countryCode) q.set('countrycode', query.countryCode);
		if (query.language) {
			q.set('language', query.language);
			q.set('languageExact', 'true');
		}
		if (query.tag) {
			q.set('tag', query.tag);
			q.set('tagExact', 'true');
		}
		const rows = await this.#get<ApiStation[]>(`/json/stations/search?${q.toString()}`);
		return rows.map(toStation);
	}

	async tags(limit = 30): Promise<DirectoryFacet[]> {
		const q = new URLSearchParams({ order: 'stationcount', reverse: 'true', limit: String(limit), hidebroken: 'true' });
		const rows = await this.#get<{ name: string; stationcount: number }[]>(`/json/tags?${q.toString()}`);
		return rows
			.filter((t) => t.name && t.name.length <= 24)
			.map((t) => ({ value: t.name, name: t.name, code: '', stationcount: t.stationcount }));
	}

	async countries(): Promise<DirectoryFacet[]> {
		const q = new URLSearchParams({ order: 'stationcount', reverse: 'true', hidebroken: 'true' });
		const rows = await this.#get<{ name: string; iso_3166_1: string; stationcount: number }[]>(`/json/countries?${q.toString()}`);
		return rows
			.filter((c) => /^[A-Z]{2}$/.test(c.iso_3166_1 || '') && c.stationcount > 0)
			.map((c) => ({ value: c.iso_3166_1, name: c.name, code: c.iso_3166_1, stationcount: c.stationcount }));
	}

	async languages(limit = 60): Promise<DirectoryFacet[]> {
		const q = new URLSearchParams({ order: 'stationcount', reverse: 'true', hidebroken: 'true', limit: String(limit) });
		const rows = await this.#get<{ name: string; iso_639: string | null; stationcount: number }[]>(`/json/languages?${q.toString()}`);
		return rows
			.filter((l) => l.name && l.stationcount > 0)
			.map((l) => ({ value: l.name, name: l.name, code: l.iso_639 || '', stationcount: l.stationcount }));
	}

	/**
	 * Tells the directory a station was played. Fire-and-forget: the app
	 * never waits for it and a failure is of no consequence to playback.
	 */
	countClick(stationId: string): void {
		this.#get(`/json/url/${encodeURIComponent(stationId)}`).catch(() => { /* ignored */ });
	}
}

function toStation(s: ApiStation): Station {
	const url = (s.url_resolved || s.url || '').trim();
	return {
		id: s.stationuuid,
		name: (s.name || '').trim(),
		url,
		homepage: (s.homepage || '').trim(),
		favicon: (s.favicon || '').trim(),
		tags: (s.tags || '').split(',').map((t) => t.trim()).filter(Boolean).slice(0, 8),
		country: s.country || '',
		countryCode: s.countrycode || '',
		language: (s.language || '').split(',').map((l) => l.trim()).filter(Boolean)[0] || '',
		codec: (s.codec || '').toUpperCase(),
		bitrate: Number(s.bitrate) || 0,
		hls: s.hls === 1,
		votes: Number(s.votes) || 0,
		clicks: Number(s.clickcount) || 0,
		source: 'directory',
	};
}

function shuffle<T>(list: T[]): T[] {
	const out = [...list];
	for (let i = out.length - 1; i > 0; i--) {
		const j = Math.floor(Math.random() * (i + 1));
		[out[i], out[j]] = [out[j], out[i]];
	}
	return out;
}

async function fetchWithTimeout(url: string): Promise<Response> {
	const controller = new AbortController();
	const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
	try {
		return await fetch(url, { signal: controller.signal, headers: { 'Accept': 'application/json' } });
	} finally {
		clearTimeout(timer);
	}
}
