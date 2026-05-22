// Copyright (c) 2024 MintJams Inc. Licensed under MIT License.

declare const Buffer: any;

interface IdenticonOptions {
	background?: [number, number, number, number];
	margin?: number;
	size?: number;
	saturation?: number;
	brightness?: number;
	foreground?: [number, number, number, number];
	format?: string;
}

const CRC32_TABLE = (() => {
	const table = new Uint32Array(256);
	for (let i = 0; i < 256; i++) {
		let c = i;
		for (let k = 0; k < 8; k++) {
			c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
		}
		table[i] = c >>> 0;
	}
	return table;
})();

function crc32(buf: Uint8Array): number {
	let c = 0xffffffff;
	for (const b of buf) {
		c = CRC32_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
	}
	return (c ^ 0xffffffff) >>> 0;
}

function adler32(buf: Uint8Array): number {
	let a = 1;
	let b = 0;
	for (const v of buf) {
		a = (a + v) % 65521;
		b = (b + a) % 65521;
	}
	return ((b << 16) | a) >>> 0;
}

function concatBytes(...arrays: Uint8Array[]): Uint8Array {
	const length = arrays.reduce((s, a) => s + a.length, 0);
	const out = new Uint8Array(length);
	let offset = 0;
	for (const a of arrays) {
		out.set(a, offset);
		offset += a.length;
	}
	return out;
}

function createChunk(type: string, data: Uint8Array): Uint8Array {
	const text = new TextEncoder().encode(type);
	const crc = crc32(concatBytes(text, data));
	const chunk = new Uint8Array(8 + data.length + 4);
	const dv = new DataView(chunk.buffer);
	dv.setUint32(0, data.length);
	chunk.set(text, 4);
	chunk.set(data, 8);
	dv.setUint32(8 + data.length, crc);
	return chunk;
}

function deflateRaw(data: Uint8Array): Uint8Array {
	const header = new Uint8Array([0x78, 0x01]);
	const len = data.length;
	const block = new Uint8Array(len + 5);
	block[0] = 0x01;
	block[1] = len & 0xff;
	block[2] = (len >> 8) & 0xff;
	block[3] = (~len) & 0xff;
	block[4] = ((~len) >> 8) & 0xff;
	block.set(data, 5);
	const adler = adler32(data);
	const out = new Uint8Array(header.length + block.length + 4);
	out.set(header, 0);
	out.set(block, header.length);
	out[header.length + block.length] = (adler >> 24) & 0xff;
	out[header.length + block.length + 1] = (adler >> 16) & 0xff;
	out[header.length + block.length + 2] = (adler >> 8) & 0xff;
	out[header.length + block.length + 3] = adler & 0xff;
	return out;
}

function bufferToBinary(buf: Uint8Array): string {
	if (typeof Buffer !== 'undefined') {
		return Buffer.from(buf).toString('binary');
	}
	let out = '';
	for (const b of buf) {
		out += String.fromCharCode(b);
	}
	return out;
}

interface Image {
	dump: string;
	readonly base64: string;
	readonly dataURL: string;
}

class PNGImage implements Image {
	width: number;
	height: number;
	depth: number;
	buffer: number[];
	palette: number[][];
	private colorMap: { [key: string]: number } = {};

	constructor(width: number, height: number, depth: number) {
		this.width = width;
		this.height = height;
		this.depth = depth;
		this.buffer = new Array(width * height).fill(0);
		this.palette = [];
	}

	color(r: number, g: number, b: number, a = 255): number {
		const key = [r, g, b, a].map(Math.round).join(',');
		let idx = this.colorMap[key];
		if (idx === undefined) {
			idx = this.palette.length;
			this.palette.push([Math.round(r), Math.round(g), Math.round(b), Math.round(a)]);
			this.colorMap[key] = idx;
		}
		return idx;
	}

	index(x: number, y: number): number {
		return y * this.width + x;
	}

	get dump(): string {
		const width = this.width;
		const height = this.height;
		const scan = new Uint8Array((width + 1) * height);
		let p = 0;
		for (let y = 0; y < height; y++) {
			scan[p++] = 0;
			for (let x = 0; x < width; x++) {
				scan[p++] = this.buffer[this.index(x, y)];
			}
		}

		const plte = new Uint8Array(this.palette.length * 3);
		const trns = new Uint8Array(this.palette.length);
		let hasAlpha = false;
		for (let i = 0; i < this.palette.length; i++) {
			const [r, g, b, a] = this.palette[i];
			plte[i * 3] = r;
			plte[i * 3 + 1] = g;
			plte[i * 3 + 2] = b;
			trns[i] = a;
			if (a !== 255) {
				hasAlpha = true;
			}
		}

		const ihdr = new Uint8Array(13);
		const dv = new DataView(ihdr.buffer);
		dv.setUint32(0, width);
		dv.setUint32(4, height);
		ihdr[8] = 8;
		ihdr[9] = 3;
		ihdr[10] = 0;
		ihdr[11] = 0;
		ihdr[12] = 0;

		const chunks: Uint8Array[] = [];
		chunks.push(createChunk('IHDR', ihdr));
		chunks.push(createChunk('PLTE', plte));
		if (hasAlpha) {
			chunks.push(createChunk('tRNS', trns));
		}
		chunks.push(createChunk('IDAT', deflateRaw(scan)));
		chunks.push(createChunk('IEND', new Uint8Array(0)));

		const signature = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
		const total = signature.length + chunks.reduce((s, c) => s + c.length, 0);
		const data = new Uint8Array(total);
		let offset = 0;
		data.set(signature, offset);
		offset += signature.length;
		for (const c of chunks) {
			data.set(c, offset);
			offset += c.length;
		}

		return bufferToBinary(data);
	}

	get base64(): string {
		if (typeof btoa === 'function') {
			return btoa(this.dump);
		} else if (typeof Buffer !== 'undefined') {
			return Buffer.from(this.dump, 'binary').toString('base64');
		}
		throw new Error('Cannot generate base64 output');
	}

	get dataURL(): string {
		return `data:image/png;base64,${this.base64}`;
	}
}

class SVGImage implements Image {
	size: number;
	foreground: string;
	background: string;
	rectangles: { x: number; y: number; w: number; h: number; color: string }[];

	constructor(size: number, foreground: [number, number, number, number], background: [number, number, number, number]) {
		this.size = size;
		this.foreground = this.color(...foreground);
		this.background = this.color(...background);
		this.rectangles = [];
	}

	color(r: number, g: number, b: number, a?: number): string {
		const values = [r, g, b].map(Math.round);
		values.push(a >= 0 && a <= 255 ? a / 255 : 1);
		return 'rgba(' + values.join(',') + ')';
	}

	get dump(): string {
		const stroke = this.size * 0.005;
		let xml = "<svg xmlns='http://www.w3.org/2000/svg'" +
			` width='${this.size}' height='${this.size}'` +
			` style='background-color:${this.background};'>` +
			`<g style='fill:${this.foreground}; stroke:${this.foreground}; stroke-width:${stroke};'>`;

		for (const rect of this.rectangles) {
			if (rect.color == this.background) {
				continue;
			}
			xml += `<rect x='${rect.x}' y='${rect.y}' width='${rect.w}' height='${rect.h}'/>`;
		}
		xml += '</g></svg>';
		return xml;
	}

	get base64(): string {
		if (typeof btoa === 'function') {
			return btoa(this.dump);
		} else if (typeof Buffer !== 'undefined') {
			return Buffer.from(this.dump, 'binary').toString('base64');
		}
		throw new Error('Cannot generate base64 output');
	}

	get dataURL(): string {
		return `data:image/svg+xml;base64,${this.base64}`;
	}
}

export class Identicon {
	#defaults = {
		background: [240, 240, 240, 255] as [number, number, number, number],
		margin: 0.08,
		size: 64,
		saturation: 0.7,
		brightness: 0.5,
		format: 'png'
	};

	#options: IdenticonOptions;
	#background: [number, number, number, number];
	#foreground: [number, number, number, number];
	#hash: string;
	#margin: number;
	#size: number;
	#format: string;

	constructor(hash: string, options?: IdenticonOptions | number, margin?: number) {
		if (typeof hash !== 'string' || hash.length < 15) {
			throw new Error('A hash of at least 15 characters is required.');
		}

		if (typeof options === 'number') {
			options = { size: options, margin } as IdenticonOptions;
		}

		this.#options = typeof options === 'object' ? options : {};

		this.#hash = hash;
		this.#background = this.#options.background ?? this.#defaults.background;
		this.#size = this.#options.size ?? this.#defaults.size;
		this.#format = this.#options.format ?? this.#defaults.format;
		this.#margin = this.#options.margin !== undefined ? this.#options.margin : this.#defaults.margin;

		const hue = parseInt(this.#hash.slice(-7), 16) / 0xfffffff;
		const saturation = this.#options.saturation ?? this.#defaults.saturation;
		const brightness = this.#options.brightness ?? this.#defaults.brightness;
		this.#foreground = this.#options.foreground ?? this.#hsl2rgb(hue, saturation, brightness);
	}

	#image(): any {
		return this.isSVG ? new SVGImage(this.#size, this.#foreground, this.#background) : new PNGImage(this.#size, this.#size, 256);
	}

	#rectangle(x: number, y: number, w: number, h: number, color: any, image: any): void {
		if (image instanceof SVGImage) {
			image.rectangles.push({ x, y, w, h, color });
		} else {
			for (let i = x; i < x + w; i++) {
				for (let j = y; j < y + h; j++) {
					image.buffer[image.index(i, j)] = color;
				}
			}
		}
	}

	#hsl2rgb(h: number, s: number, b: number): [number, number, number, number] {
		h *= 6;
		const palette = [
			b += s *= b < 0.5 ? b : 1 - b,
			b - (h % 1) * s * 2,
			b -= s *= 2,
			b,
			b + (h % 1) * s,
			b + s
		];
		return [
			palette[~~h % 6] * 255,
			palette[(h | 16) % 6] * 255,
			palette[(h | 8) % 6] * 255,
			255
		];
	}

	#render(): any {
		const image = this.#image();
		const size = this.#size;
		const baseMargin = Math.floor(size * this.#margin);
		const cell = Math.floor((size - baseMargin * 2) / 5);
		const margin = Math.floor((size - cell * 5) / 2);
		const bg = image.color.apply(image, this.#background);
		const fg = image.color.apply(image, this.#foreground);

		for (let i = 0; i < 15; i++) {
			const color = parseInt(this.#hash.charAt(i), 16) % 2 ? bg : fg;
			if (i < 5) {
				this.#rectangle(2 * cell + margin, i * cell + margin, cell, cell, color, image);
			} else if (i < 10) {
				this.#rectangle(1 * cell + margin, (i - 5) * cell + margin, cell, cell, color, image);
				this.#rectangle(3 * cell + margin, (i - 5) * cell + margin, cell, cell, color, image);
			} else {
				this.#rectangle(0 * cell + margin, (i - 10) * cell + margin, cell, cell, color, image);
				this.#rectangle(4 * cell + margin, (i - 10) * cell + margin, cell, cell, color, image);
			}
		}

		return image;
	}

	dump(): string {
		return this.#render().dump;
	}

	get base64(): string {
		return this.#render().base64;
	}

	get dataURL(): string {
		return this.#render().dataURL;
	}

	get isSVG(): boolean {
		return /svg/i.test(this.#format);
	}
}
