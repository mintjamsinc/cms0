// Copyright (c) 2021 MintJams Inc. Licensed under MIT License.

export class Encodings {
	// Representative character encodings. The list intentionally focuses on
	// names that are commonly seen in JCR-like content repositories. Each
	// entry is the canonical (preferred MIME) name as registered with IANA.
	static #encodings: { name: string; description: string }[] = [
		// Unicode
		{ name: 'UTF-8', description: 'Unicode (UTF-8)' },
		{ name: 'UTF-16', description: 'Unicode (UTF-16)' },
		{ name: 'UTF-16BE', description: 'Unicode (UTF-16 Big Endian)' },
		{ name: 'UTF-16LE', description: 'Unicode (UTF-16 Little Endian)' },
		{ name: 'UTF-32', description: 'Unicode (UTF-32)' },
		{ name: 'UTF-32BE', description: 'Unicode (UTF-32 Big Endian)' },
		{ name: 'UTF-32LE', description: 'Unicode (UTF-32 Little Endian)' },
		{ name: 'US-ASCII', description: 'ASCII' },

		// Western (ISO-8859 / Windows)
		{ name: 'ISO-8859-1', description: 'Western European (ISO-8859-1, Latin-1)' },
		{ name: 'ISO-8859-2', description: 'Central European (ISO-8859-2, Latin-2)' },
		{ name: 'ISO-8859-3', description: 'South European (ISO-8859-3, Latin-3)' },
		{ name: 'ISO-8859-4', description: 'North European (ISO-8859-4, Latin-4)' },
		{ name: 'ISO-8859-5', description: 'Cyrillic (ISO-8859-5)' },
		{ name: 'ISO-8859-6', description: 'Arabic (ISO-8859-6)' },
		{ name: 'ISO-8859-7', description: 'Greek (ISO-8859-7)' },
		{ name: 'ISO-8859-8', description: 'Hebrew (ISO-8859-8)' },
		{ name: 'ISO-8859-9', description: 'Turkish (ISO-8859-9, Latin-5)' },
		{ name: 'ISO-8859-10', description: 'Nordic (ISO-8859-10, Latin-6)' },
		{ name: 'ISO-8859-13', description: 'Baltic (ISO-8859-13, Latin-7)' },
		{ name: 'ISO-8859-15', description: 'Western European (ISO-8859-15, Latin-9)' },
		{ name: 'ISO-8859-16', description: 'South-Eastern European (ISO-8859-16, Latin-10)' },
		{ name: 'windows-1250', description: 'Central European (Windows-1250)' },
		{ name: 'windows-1251', description: 'Cyrillic (Windows-1251)' },
		{ name: 'windows-1252', description: 'Western European (Windows-1252)' },
		{ name: 'windows-1253', description: 'Greek (Windows-1253)' },
		{ name: 'windows-1254', description: 'Turkish (Windows-1254)' },
		{ name: 'windows-1255', description: 'Hebrew (Windows-1255)' },
		{ name: 'windows-1256', description: 'Arabic (Windows-1256)' },
		{ name: 'windows-1257', description: 'Baltic (Windows-1257)' },
		{ name: 'windows-1258', description: 'Vietnamese (Windows-1258)' },

		// Japanese
		{ name: 'Shift_JIS', description: 'Japanese (Shift_JIS)' },
		{ name: 'windows-31j', description: 'Japanese (Windows-31J / MS932)' },
		{ name: 'EUC-JP', description: 'Japanese (EUC-JP)' },
		{ name: 'ISO-2022-JP', description: 'Japanese (ISO-2022-JP)' },

		// Chinese
		{ name: 'GB2312', description: 'Simplified Chinese (GB2312)' },
		{ name: 'GBK', description: 'Simplified Chinese (GBK)' },
		{ name: 'GB18030', description: 'Simplified Chinese (GB18030)' },
		{ name: 'Big5', description: 'Traditional Chinese (Big5)' },
		{ name: 'Big5-HKSCS', description: 'Traditional Chinese (Big5-HKSCS)' },

		// Korean
		{ name: 'EUC-KR', description: 'Korean (EUC-KR)' },
		{ name: 'ISO-2022-KR', description: 'Korean (ISO-2022-KR)' },

		// Other
		{ name: 'KOI8-R', description: 'Cyrillic (KOI8-R)' },
		{ name: 'KOI8-U', description: 'Ukrainian (KOI8-U)' },
		{ name: 'TIS-620', description: 'Thai (TIS-620)' },
		{ name: 'IBM437', description: 'OEM US (IBM437)' },
		{ name: 'IBM850', description: 'OEM Multilingual (IBM850)' },
	];

	/** Return all known encoding names. */
	static all(): string[] {
		return Encodings.#encodings.map(e => e.name);
	}

	/**
	 * Filter encodings by a partial, case-insensitive query against either
	 * the encoding name or its description. When the query is empty the
	 * full list is returned.
	 */
	static filterEncodings(query?: string): string[] {
		if (!query) {
			return Encodings.all();
		}
		const q = query.toLowerCase();
		const result: string[] = [];
		for (const e of Encodings.#encodings) {
			if (e.name.toLowerCase().indexOf(q) !== -1 ||
				e.description.toLowerCase().indexOf(q) !== -1) {
				result.push(e.name);
			}
		}
		return result;
	}

	/** Get a human-readable description of an encoding name. */
	static description(name: string): string | undefined {
		if (!name) return undefined;
		const target = name.toLowerCase();
		for (const e of Encodings.#encodings) {
			if (e.name.toLowerCase() === target) {
				return e.description;
			}
		}
		return undefined;
	}
}
