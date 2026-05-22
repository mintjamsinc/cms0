import { Bytes } from '../utils/bytes.js';
import { Dates } from '../utils/dates.js';
import { MimeTypes } from '../utils/mime-types.js';
import { Encodings } from '../utils/encodings.js';

export async function postJSON(url, data = {}, options: { headers?: any } = {}) {
	const headers = options.headers || {};
	const res = await fetch(url, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json', ...headers },
		body: JSON.stringify(data)
	});

	if (!res.ok) {
		throw new Error(`HTTP ${res.status}: ${res.statusText}`);
	}

	return { headers: Object.fromEntries(res.headers.entries()), data: await res.json() };
}

export async function download(url, options: { headers?: any } = {}) {
	const headers = options.headers || {};
	const res = await fetch(url, { headers });

	if (!res.ok) {
		throw new Error(`HTTP ${res.status}: ${res.statusText}`);
	}

	return { headers: Object.fromEntries(res.headers.entries()), data: await res.blob() };
}

export async function sha256Hex(str) {
	const encoder = new TextEncoder();
	const data = encoder.encode(str);
	const hashBuffer = await crypto.subtle.digest('SHA-256', data);
	return [...new Uint8Array(hashBuffer)].map(b => b.toString(16).padStart(2, '0')).join('');
}


export class WebtopUtil {
	#bytes: Bytes = Bytes;
	#dates: Dates = Dates;
	#mimeTypes: MimeTypes = MimeTypes;
	#encodings: Encodings = Encodings;

	get bytes() { return this.#bytes; }
	get dates() { return this.#dates; }
	get mimeTypes() { return this.#mimeTypes; }
	get encodings() { return this.#encodings; }
}
