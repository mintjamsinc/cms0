// Copyright (c) 2021 MintJams Inc. Licensed under MIT License.

interface BytesFormatOptions {
	zeroBytesDisplay?: string;
	si?: boolean;
	decimals?: number;
	short?: boolean;
	locale?: string | string[];
}

export class Bytes {
	static format(bytes: number, options: BytesFormatOptions = {}): string {
		if (typeof bytes !== 'number' || isNaN(bytes)) {
			return 'Invalid size'; // または throw new Error('Bytes must be a number.');
		}

		const zeroBytesDisplay = options.zeroBytesDisplay ?? 'Empty'; // 0バイトの場合の表示。'N/A'などを指定する
		const si = options.si ?? false; // trueなら1000ベース
		const decimals = options.decimals ?? 1;
		const short = options.short ?? false;
		const locale = options.locale ?? undefined; // ロケール指定を追加

		if (bytes === 0) {
			return zeroBytesDisplay ?? (short ? '0B' : '0 bytes');
		}

		const thresh = si ? 1000 : 1024;
		if (Math.abs(bytes) < thresh) {
			// shortがtrueの場合、Intl.NumberFormatで'byte'単位を直接指定できる
			if (short) {
				return new Intl.NumberFormat(locale, {
					style: 'unit',
					unit: 'byte',
					unitDisplay: 'narrow' // 'B' のように表示
				}).format(bytes);
			}
			return new Intl.NumberFormat(locale, {
				style: 'unit',
				unit: 'byte',
				unitDisplay: 'long' // 'bytes' のように表示
			}).format(bytes);
		}

		const units = /*si*/true // Intl.NumberFormatで使える単位名に固定する
			? ['kilobyte', 'megabyte', 'gigabyte', 'terabyte', 'petabyte', 'exabyte', 'zettabyte', 'yottabyte']
			: ['kibibyte', 'mebibyte', 'gibibyte', 'tebibyte', 'pebibyte', 'exbibyte', 'zebibyte', 'yobibyte'];

		let u = -1;
		do {
			bytes /= thresh;
			++u;
		} while (Math.abs(bytes) >= thresh && u < units.length - 1);

		// Intl.NumberFormatで単位を直接指定し、decimalsも指定できる
		return new Intl.NumberFormat(locale, {
			style: 'unit',
			unit: units[u],
			unitDisplay: short ? 'narrow' : 'long', // shortがtrueなら短縮形、そうでなければ長い形
			maximumFractionDigits: decimals,
			minimumFractionDigits: decimals // 小数点以下の桁数を保証
		}).format(bytes);
	}
}
