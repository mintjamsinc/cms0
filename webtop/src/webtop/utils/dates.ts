// Copyright (c) 2021 MintJams Inc. Licensed under MIT License.

interface DatesFormatOptions {
	format?: 'datetime' | 'date' | 'time' | 'friendly' | string;
	locale?: string | string[];
	// IANA time zone name (e.g. 'Asia/Tokyo'). When omitted the runtime
	// (OS) time zone is used. Comes from the user's Localization preference.
	timeZone?: string;
}

export class Dates {
	static format(value: Date | number | string | null | undefined, options: DatesFormatOptions = {}): string | undefined {
		if (value === undefined || value === null) {
			return undefined;
		}

		const format = options.format ?? 'datetime'; // 日付の表示形式を指定する
		const locale = options.locale ?? undefined; // ロケール指定を追加
		const timeZone = options.timeZone || undefined; // タイムゾーン指定（未指定ならOS）

		let dateValue: Date | undefined;
		// Use Object.prototype.toString for cross-realm Date detection (iframe compatibility)
		if (Object.prototype.toString.call(value) === '[object Date]') {
			dateValue = value as Date;
		} else {
			dateValue = Dates.toDate(value as any);
		}
		if (!dateValue) {
			return undefined;
		}

		// 無効な日付の場合
		if (isNaN(dateValue.getTime())) {
			return undefined;
		}

		try {
			if (format === 'friendly') {
				return Dates.formatFriendly(dateValue, locale, timeZone);
			}

			let options: Intl.DateTimeFormatOptions = {};
			switch (format) {
				case 'datetime':
					options = {
						year: 'numeric',
						month: 'short',
						day: 'numeric',
						hour: 'numeric',
						minute: 'numeric',
						weekday: 'short'
					};
					break;
				case 'date':
					options = {
						year: 'numeric',
						month: 'short',
						day: 'numeric'
					};
					break;
				case 'time':
					options = {
						hour: 'numeric',
						minute: 'numeric'
					};
					break;
				default:
					// 未知の valueType の場合はデフォルトの datetime を使用する
					options = {
						year: 'numeric',
						month: 'short',
						day: 'numeric',
						hour: 'numeric',
						minute: 'numeric',
						weekday: 'short'
					};
					break;
			}
			if (timeZone) {
				options.timeZone = timeZone;
			}
			return dateValue.toLocaleString(locale, options);
		} catch (e) {
			console.error("日付のフォーマット中にエラーが発生しました:", e);
			return undefined;
		}
	}

	/**
	 * 相対的な時間（例: "5分前"、"明日"）を整形します。
	 * @param {Date} date - 整形するDateオブジェクト。
	 * @param {string} locale - 使用するロケール文字列 (例: 'ja-JP', 'en-US')。
	 * @returns {string} 整形された相対時間文字列。
	 */
	static formatFriendly(date: Date, locale: string | string[] = 'ja', timeZone?: string): string {
		const now = new Date();
		const diffSeconds = Math.floor((date.getTime() - now.getTime()) / 1000); // 未来は正の値、過去は負の値
		const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });

		if (Math.abs(diffSeconds) < 60) {
			return rtf.format(Math.round(diffSeconds), 'second');
		} else if (Math.abs(diffSeconds) < 3600) {
			return rtf.format(Math.round(diffSeconds / 60), 'minute');
		} else if (Math.abs(diffSeconds) < 86400) {
			return rtf.format(Math.round(diffSeconds / 3600), 'hour');
		} else if (Math.abs(diffSeconds) < 86400 * 7) {
			const diffDays = Math.round(diffSeconds / 86400);
			return rtf.format(diffDays, 'day');
		} else {
			// 1週間以上未来／過去の場合は日付+時間で表示
			const absOptions: Intl.DateTimeFormatOptions = {
				year: 'numeric',
				month: 'long',
				day: 'numeric',
				hour: '2-digit',
				minute: '2-digit',
			};
			if (timeZone) {
				absOptions.timeZone = timeZone;
			}
			return date.toLocaleString(locale, absOptions);
		}
	}

	/**
	 * 相対的な日付（例: "昨日"、"明日"、"来週の月曜日"）を整形します。
	 * @param {Date} date - 整形するDateオブジェクト。
	 * @param {string} locale - 使用するロケール文字列 (例: 'ja', 'en')。
	 * @returns {string} 整形された相対日付文字列。
	 */
	static formatFriendlyCalendar(date: Date, locale: string | string[] = 'en'): string {
		const now = new Date();

		// 日付のみに切り捨て
		const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
		const target = new Date(date.getFullYear(), date.getMonth(), date.getDate());

		const diffDays = Math.round((target.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));

		// 曜日名取得（例：「月曜日」）
		const weekdayName = new Intl.DateTimeFormat(locale, { weekday: 'long' }).format(date);

		const translations = {
			ja: {
				today: '今日',
				yesterday: '昨日',
				tomorrow: '明日',
				thisWeek: '今週の',
				nextWeek: '来週の',
				lastWeek: '先週の'
			},
			en: {
				today: 'Today',
				yesterday: 'Yesterday',
				tomorrow: 'Tomorrow',
				thisWeek: 'This ', // 'This Monday'
				nextWeek: 'Next ', // 'Next Monday'
				lastWeek: 'Last ' // 'Last Monday'
			},
		};

		const localeKey = Array.isArray(locale) ? locale[0] : locale;
		const t = translations[localeKey.split('-')[0]] || translations['en']; // デフォルトはen

		if (diffDays === 0) {
			return t.today;
		} else if (diffDays === -1) {
			return t.yesterday;
		} else if (diffDays === 1) {
			return t.tomorrow;
		} else if (diffDays >= 2 && diffDays <= 6) {
			// 英語の場合 "This Monday" のように曜日が後に来るため、スペースで調整
			return `${t.thisWeek}${weekdayName}`;
		} else if (diffDays >= 7 && diffDays <= 13) {
			return `${t.nextWeek}${weekdayName}`;
		} else if (diffDays <= -2 && diffDays >= -6) {
			return `${t.thisWeek}${weekdayName}`;
		} else if (diffDays <= -7 && diffDays >= -13) {
			return `${t.lastWeek}${weekdayName}`;
		} else {
			// それ以外は普通の表現に戻す
			return date.toLocaleDateString(locale, {
				year: 'numeric',
				month: 'long',
				day: 'numeric',
				weekday: 'long',
			});
		}
	}

	static today(): Date {
		let today = new Date();
		today.setHours(0);
		today.setMinutes(0);
		today.setSeconds(0);
		today.setMilliseconds(0);
		return today;
	}

	static toDate(value: any): Date | undefined {
		if (value === undefined || value === null) {
			return undefined;
		}

		// Use Object.prototype.toString for cross-realm Date detection (iframe compatibility)
		if (Object.prototype.toString.call(value) === '[object Date]') {
			// Check for invalid Date object
			return isNaN(value.getTime()) ? undefined : value;
		}

		// 数値 (タイムスタンプ) の場合
		if (typeof value === 'number' || value instanceof Number) {
			const timestamp = typeof value === 'number' ? value : value.valueOf();
			const date = new Date(timestamp);
			return isNaN(date.getTime()) ? undefined : date;
		}

		// 文字列の場合
		if (typeof value === 'string') {
			// ISO 8601形式などの標準的な日付文字列をパース
			const date = new Date(value);

			// Dateコンストラクタでパースできなかった場合、isNaN(date.getTime()) が true になる
			if (!isNaN(date.getTime())) {
				return date;
			}

			// ここに到達するということは、Dateコンストラクタで直接パースできなかった形式の日付

			// ISO 8601 ではないが、スラッシュ区切りの日付文字列 (例: "2023/10/26 15:30:00")
			if (value.match(/^\d{4}\/\d{2}\/\d{2}( \d{2}:\d{2}(:\d{2})?)?$/)) {
				// スラッシュをハイフンに置換してISO 8601に近い形式にする
				const isoLike = value.replace(/\//g, '-');
				const parsedDate = new Date(isoLike);
				if (!isNaN(parsedDate.getTime())) {
					return parsedDate;
				}
			}

			// どのようなパースも失敗した場合
			return undefined;
		}

		// その他の予期せぬ型の場合
		return undefined;
	}

	/**
	 * Offset (in milliseconds) of an IANA time zone at a given instant.
	 * Returns `wallClock(timeZone) - UTC`, so a positive value means the zone
	 * is ahead of UTC (e.g. +9h for 'Asia/Tokyo'). DST is handled because the
	 * offset is resolved for the specific instant.
	 */
	static getTimeZoneOffsetMs(timeZone: string, date: Date): number {
		const dtf = new Intl.DateTimeFormat('en-US', {
			timeZone,
			hourCycle: 'h23',
			year: 'numeric',
			month: '2-digit',
			day: '2-digit',
			hour: '2-digit',
			minute: '2-digit',
			second: '2-digit',
		});
		const map: Record<string, number> = {};
		for (const p of dtf.formatToParts(date)) {
			if (p.type !== 'literal') map[p.type] = Number(p.value);
		}
		const asUTC = Date.UTC(map.year, map.month - 1, map.day, map.hour, map.minute, map.second);
		return asUTC - date.getTime();
	}

	/**
	 * Format an instant as the `YYYY-MM-DDTHH:mm` value expected by
	 * `<input type="datetime-local">`, expressing the wall-clock time in the
	 * given time zone (the user's Localization preference). When `timeZone`
	 * is empty the runtime (OS) time zone is used.
	 */
	static toZonedInputValue(value: Date | number | string | null | undefined, timeZone?: string): string {
		const date = Dates.toDate(value as any);
		if (!date) return '';
		const dtf = new Intl.DateTimeFormat('en-CA', {
			timeZone: timeZone || undefined,
			hourCycle: 'h23',
			year: 'numeric',
			month: '2-digit',
			day: '2-digit',
			hour: '2-digit',
			minute: '2-digit',
		});
		const map: Record<string, string> = {};
		for (const p of dtf.formatToParts(date)) {
			if (p.type !== 'literal') map[p.type] = p.value;
		}
		return `${map.year}-${map.month}-${map.day}T${map.hour}:${map.minute}`;
	}

	/**
	 * Inverse of {@link toZonedInputValue}: interpret a `datetime-local`
	 * wall-clock string (`YYYY-MM-DDTHH:mm`) as a time in the given time zone
	 * and return the absolute instant. When `timeZone` is empty the runtime
	 * (OS) time zone is used. A two-pass offset resolution keeps DST
	 * transitions correct.
	 */
	static fromZonedInputValue(localStr: string | null | undefined, timeZone?: string): Date | undefined {
		if (!localStr) return undefined;
		const m = String(localStr).match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/);
		if (!m) {
			const d = new Date(localStr);
			return isNaN(d.getTime()) ? undefined : d;
		}
		const [, y, mo, d, h, mi, s] = m;
		const year = Number(y), month = Number(mo), day = Number(d);
		const hour = Number(h), minute = Number(mi), second = s ? Number(s) : 0;

		if (!timeZone) {
			// Interpret as OS local wall-clock.
			return new Date(year, month - 1, day, hour, minute, second);
		}

		const naiveUTC = Date.UTC(year, month - 1, day, hour, minute, second);
		const offset = Dates.getTimeZoneOffsetMs(timeZone, new Date(naiveUTC));
		let instant = naiveUTC - offset;
		// Re-resolve the offset at the computed instant to correct DST edges.
		const offset2 = Dates.getTimeZoneOffsetMs(timeZone, new Date(instant));
		if (offset2 !== offset) {
			instant = naiveUTC - offset2;
		}
		return new Date(instant);
	}
}
