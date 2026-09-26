// Charts for the dataset block (see dataset-view.ts).
//
// A chart is an aggregation of the rows the block already holds: one column
// gives the categories (the X axis, the slices), a numeric column or the row
// count gives the values, an optional choice column splits the values into
// series. What can be drawn follows from the column types: text, checkbox and
// date columns make categories (dates in day / week / month / year buckets),
// number columns make values, and a number column on the X axis is binned
// into ranges (a histogram); a scatter chart takes two number columns as
// they are. The rows are aggregated here, in the client, from the same list
// the table shows, so the chart follows every edit and live update the way
// the other views do.
//
// The drawing is Chart.js. Only the pieces the block's chart kinds need are
// registered, so the memo bundle carries just those controllers and scales.
// Colours come from the shared swatch palette: a category or series whose
// column declares a choice colour keeps that colour everywhere (the board's
// lanes, the table's chips and the chart agree), and values without one get
// a swatch from a fixed order, the same slot for the same value as long as
// the values do not change.
import {
	Chart,
	BarController,
	LineController,
	PieController,
	ScatterController,
	BarElement,
	LineElement,
	PointElement,
	ArcElement,
	LinearScale,
	CategoryScale,
	Tooltip,
	Legend,
} from 'chart.js';
import type { ChartConfiguration, TooltipItem } from 'chart.js';
import type { DatasetProperty } from '../../graphql/types.js';
import { SWATCH_COLOR_MAP } from '../../lib/color-palette.js';
import { withAlpha } from '../../lib/chart-theme.js';
import type { ChartTheme } from '../../lib/chart-theme.js';
import { Dates } from '../../utils/dates.js';

Chart.register(
	BarController, LineController, PieController, ScatterController,
	BarElement, LineElement, PointElement, ArcElement,
	LinearScale, CategoryScale, Tooltip, Legend,
);

export const CHART_KINDS = ['bar', 'hbar', 'line', 'pie', 'scatter', 'number'] as const;
export type ChartKind = typeof CHART_KINDS[number];
export const CHART_ICONS: Record<ChartKind, string> = {
	bar: 'bi-bar-chart',
	hbar: 'bi-bar-chart-steps',
	line: 'bi-graph-up',
	pie: 'bi-pie-chart',
	scatter: 'bi-dice-5',
	number: 'bi-123',
};

export const AGGREGATES = ['count', 'sum', 'avg', 'min', 'max'] as const;
export type Aggregate = typeof AGGREGATES[number];

export const BUCKETS = ['day', 'week', 'month', 'year'] as const;
export type Bucket = typeof BUCKETS[number];

// A chart as the block stores it, with the columns resolved.
export interface ChartSpec {
	kind: ChartKind;
	// The category column (the X axis, the slices); the X value of a scatter.
	x: DatasetProperty | null;
	// The value column; null counts rows.
	y: DatasetProperty | null;
	agg: Aggregate;
	// The column whose values split the data into series; null for one series.
	series: DatasetProperty | null;
	// How a date X column is bucketed.
	bucket: Bucket;
}

// What the chart needs from a row: the same shape as the block's Row.
export interface ChartRow {
	title: string;
	values: Record<string, { type: string; value: any; values: any[] | null }>;
}

export interface ChartContext {
	t(key: string, params?: Record<string, any>, fallback?: string): string;
	locale?: string;
	timeZone?: string;
}

// The aggregated data a chart draws.
export interface ChartModel {
	kind: ChartKind;
	// One entry per category, in drawing order.
	labels: string[];
	// The colour of each category, when one series is coloured by the X
	// column's choices (a bar per status, a slice per tag); null otherwise.
	categoryColors: string[] | null;
	// The series, one per value of the series column; exactly one when none.
	series: { label: string; color: string; values: (number | null)[] }[];
	// A scatter's points, per series.
	points: { label: string; color: string; data: { x: number; y: number; title: string }[] }[];
	// What the values are: "Count", "Sum · Amount".
	valueLabel: string;
	// The single figure of a number tile.
	total: number | null;
	// Rows left out: no X value (dates and numbers) or no Y value.
	skipped: number;
}

const NO_VALUE = '\u0000';
const CELL_SEPARATOR = '\u0001';
const NUMERIC_TYPES = new Set(['LONG', 'DOUBLE', 'DECIMAL']);
// The swatch a single series or a category without a choice colour draws in.
const DEFAULT_SWATCH = 'peacock';
// Swatches for series and categories without a declared colour, in an order
// whose neighbours stay apart under colour-vision deficiency (checked with a
// CVD validator against the swatch palette). Graphite is last: it is also
// what "no value" draws in.
const FALLBACK_SWATCHES = ['peacock', 'tangerine', 'blueberry', 'banana', 'basil', 'grape', 'sage', 'tomato', 'lavender', 'flamingo', 'graphite'];
const NO_VALUE_SWATCH = 'graphite';
// A date axis is filled in between its first and last bucket, so a month
// without rows shows as zero rather than vanishing; past this many buckets
// the gaps are left as they are.
const MAX_FILLED_BUCKETS = 400;
// Histogram bins to aim for; the nice bin width lands near this count.
const HISTOGRAM_BINS = 10;
const DAY_MS = 86400000;

export function isNumericColumn(column: DatasetProperty): boolean {
	return NUMERIC_TYPES.has(column.type);
}

// Columns a chart kind can put on its X axis.
export function chartXColumns(kind: ChartKind, columns: DatasetProperty[]): DatasetProperty[] {
	switch (kind) {
		case 'number': return [];
		case 'scatter': return columns.filter(isNumericColumn);
		default: return columns;
	}
}

// Columns a chart can take values from: numbers. A scatter needs one; the
// others count rows when none is chosen.
export function chartYColumns(columns: DatasetProperty[]): DatasetProperty[] {
	return columns.filter(isNumericColumn);
}

// Columns whose values can split the data into series: a known, small set of
// values, so every series has a name and a colour.
export function chartSeriesColumns(kind: ChartKind, columns: DatasetProperty[]): DatasetProperty[] {
	if (kind === 'pie' || kind === 'number') return [];
	return columns.filter(p => !p.multiple && ((p.type === 'STRING' && p.choices.length > 0) || p.type === 'BOOLEAN'));
}

function swatch(key: string | undefined): string {
	return (key && SWATCH_COLOR_MAP[key]) || SWATCH_COLOR_MAP[DEFAULT_SWATCH];
}

function stringsOf(row: ChartRow, column: DatasetProperty): string[] {
	const stored = row.values[column.name];
	if (!stored) return [];
	const raw: any[] = stored.values ? stored.values : [stored.value];
	return raw.filter(v => v != null && v !== '').map(String);
}

// The first numeric value a row stores in a column; null when none.
function numberOf(row: ChartRow, column: DatasetProperty): number | null {
	const s = stringsOf(row, column)[0];
	if (s == null) return null;
	const n = Number(s);
	return Number.isFinite(n) ? n : null;
}

// ---- categories ----

// A category dimension: the ordered keys, their labels and colours, and how a
// row's values map to keys.
interface Dimension {
	keys: string[];
	label(key: string): string;
	// The choice colour of a key, or '' when it has none.
	color(key: string): string;
	// Rows without a value: a key of their own (text, checkbox) or left out
	// (dates, numbers, where "no value" has no place on the axis).
	keysOf(row: ChartRow): string[];
}

// The colours of a dimension's keys: the declared choice colour where there
// is one, otherwise the next unused fallback swatch, "no value" in graphite.
function assignColors(dim: Dimension): Map<string, string> {
	const colors = new Map<string, string>();
	const used = new Set<string>();
	for (const key of dim.keys) {
		const declared = dim.color(key);
		if (declared && SWATCH_COLOR_MAP[declared]) {
			colors.set(key, declared);
			used.add(declared);
		} else if (key === NO_VALUE) {
			colors.set(key, NO_VALUE_SWATCH);
		}
	}
	let next = 0;
	for (const key of dim.keys) {
		if (colors.has(key)) continue;
		while (next < FALLBACK_SWATCHES.length - 1 && used.has(FALLBACK_SWATCHES[next])) next++;
		const s = FALLBACK_SWATCHES[Math.min(next, FALLBACK_SWATCHES.length - 1)];
		colors.set(key, s);
		used.add(s);
		next++;
	}
	return colors;
}

function textDimension(column: DatasetProperty, rows: ChartRow[], ctx: ChartContext, orderByTotal: ((key: string) => number) | null): Dimension {
	const choices = new Map(column.choices.map(c => [c.value, c]));
	const keys: string[] = column.choices.map(c => c.value);
	const extra: string[] = [];
	let missing = false;
	for (const row of rows) {
		const values = stringsOf(row, column);
		if (values.length === 0) { missing = true; continue; }
		for (const v of values) if (!keys.includes(v) && !extra.includes(v)) extra.push(v);
	}
	// Declared choices keep their order; free text is ordered by its total
	// (the biggest bar first), or by name while the totals are not known yet.
	if (orderByTotal && column.choices.length === 0) {
		extra.sort((a, b) => orderByTotal(b) - orderByTotal(a));
	} else {
		const collator = new Intl.Collator(ctx.locale || undefined, { numeric: true, sensitivity: 'base' });
		extra.sort((a, b) => collator.compare(a, b));
	}
	keys.push(...extra);
	if (missing) keys.push(NO_VALUE);
	return {
		keys,
		label: key => key === NO_VALUE ? ctx.t('app.memo.dataset.board.none', undefined, 'No value') : (choices.get(key)?.label || key),
		color: key => choices.get(key)?.color || '',
		keysOf: row => { const v = stringsOf(row, column); return v.length ? v : [NO_VALUE]; },
	};
}

function booleanDimension(column: DatasetProperty, rows: ChartRow[], ctx: ChartContext): Dimension {
	const keyOf = (row: ChartRow): string => {
		const v = stringsOf(row, column)[0];
		return v == null ? NO_VALUE : (v === 'true' ? 'true' : 'false');
	};
	const present = new Set(rows.map(keyOf));
	const keys = ['true', 'false', NO_VALUE].filter(k => k !== NO_VALUE || present.has(k));
	return {
		keys,
		label: key => key === 'true' ? ctx.t('app.memo.dataset.boolean.true', undefined, 'Yes')
			: key === 'false' ? ctx.t('app.memo.dataset.boolean.false', undefined, 'No')
			: ctx.t('app.memo.dataset.board.none', undefined, 'No value'),
		// "Yes" is green; "No" takes the next free swatch, so it never shares
		// graphite with "No value".
		color: key => key === 'true' ? 'sage' : '',
		keysOf: row => [keyOf(row)],
	};
}

// The bucket a date falls in, as a sortable key: the first day of the bucket
// ("yyyy-mm-dd") in the user's time zone. Weeks start on Monday.
function dateBucketKey(iso: string, bucket: Bucket, timeZone: string | undefined): string | null {
	const local = Dates.toZonedInputValue(iso, timeZone);
	if (!local) return null;
	const day = local.slice(0, 10);
	const [y, m, d] = day.split('-').map(Number);
	switch (bucket) {
		case 'year': return `${day.slice(0, 4)}-01-01`;
		case 'month': return `${day.slice(0, 7)}-01`;
		case 'week': {
			const t = Date.UTC(y, m - 1, d);
			const dow = (new Date(t).getUTCDay() + 6) % 7;
			return new Date(t - dow * DAY_MS).toISOString().slice(0, 10);
		}
		default: return day;
	}
}

function nextBucketKey(key: string, bucket: Bucket): string {
	const [y, m, d] = key.split('-').map(Number);
	switch (bucket) {
		case 'year': return new Date(Date.UTC(y + 1, 0, 1)).toISOString().slice(0, 10);
		case 'month': return new Date(Date.UTC(y, m, 1)).toISOString().slice(0, 10);
		case 'week': return new Date(Date.UTC(y, m - 1, d + 7)).toISOString().slice(0, 10);
		default: return new Date(Date.UTC(y, m - 1, d + 1)).toISOString().slice(0, 10);
	}
}

function dateDimension(column: DatasetProperty, rows: ChartRow[], bucket: Bucket, ctx: ChartContext): Dimension {
	const tz = ctx.timeZone;
	const keysOf = (row: ChartRow): string[] => {
		const keys: string[] = [];
		for (const iso of stringsOf(row, column)) {
			const key = dateBucketKey(iso, bucket, tz);
			if (key && !keys.includes(key)) keys.push(key);
		}
		return keys;
	};
	const present = new Set<string>();
	for (const row of rows) for (const k of keysOf(row)) present.add(k);
	let keys = Array.from(present).sort();
	// Fill the gaps so the axis is evenly spaced and an empty bucket reads as
	// zero, as long as the range stays reasonable.
	if (keys.length > 1) {
		const last = keys[keys.length - 1];
		const filled: string[] = [];
		for (let k = keys[0]; k <= last && filled.length <= MAX_FILLED_BUCKETS; k = nextBucketKey(k, bucket)) filled.push(k);
		if (filled.length <= MAX_FILLED_BUCKETS && filled[filled.length - 1] === last) keys = filled;
	}
	const locale = ctx.locale || undefined;
	const format = new Intl.DateTimeFormat(locale,
		bucket === 'year' ? { year: 'numeric', timeZone: 'UTC' }
		: bucket === 'month' ? { year: 'numeric', month: 'short', timeZone: 'UTC' }
		: { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' });
	return {
		keys,
		label: key => format.format(new Date(key + 'T00:00:00Z')),
		color: () => '',
		keysOf,
	};
}

// Bins of a nice width (1, 2 or 5 times a power of ten) covering the values.
function numberDimension(column: DatasetProperty, rows: ChartRow[], ctx: ChartContext): Dimension {
	const numbers = rows.map(r => numberOf(r, column)).filter((n): n is number => n != null);
	if (numbers.length === 0) return { keys: [], label: () => '', color: () => '', keysOf: () => [] };
	let min = Math.min(...numbers);
	let max = Math.max(...numbers);
	if (min === max) { min = Math.floor(min); max = min + 1; }
	const rough = (max - min) / HISTOGRAM_BINS;
	const pow = Math.pow(10, Math.floor(Math.log10(rough)));
	const frac = rough / pow;
	const width = (frac <= 1 ? 1 : frac <= 2 ? 2 : frac <= 5 ? 5 : 10) * pow;
	const start = Math.floor(min / width) * width;
	// A maximum that sits on a bin edge belongs to the bin below it.
	const count = Math.max(1, Math.ceil((max - start) / width - 1e-9));
	const digits = Math.max(0, -Math.floor(Math.log10(width)));
	const fmt = new Intl.NumberFormat(ctx.locale || undefined, { maximumFractionDigits: digits });
	const keys: string[] = [];
	for (let i = 0; i < count; i++) keys.push(String(i).padStart(4, '0'));
	const binOf = (n: number): string => {
		const i = Math.min(count - 1, Math.max(0, Math.floor((n - start) / width + 1e-9)));
		return String(i).padStart(4, '0');
	};
	return {
		keys,
		label: key => {
			const i = Number(key);
			return `${fmt.format(start + i * width)} – ${fmt.format(start + (i + 1) * width)}`;
		},
		color: () => '',
		keysOf: row => { const n = numberOf(row, column); return n == null ? [] : [binOf(n)]; },
	};
}

function dimensionOf(column: DatasetProperty, rows: ChartRow[], bucket: Bucket, ctx: ChartContext, orderByTotal: ((key: string) => number) | null): Dimension {
	switch (column.type) {
		case 'BOOLEAN': return booleanDimension(column, rows, ctx);
		case 'DATE': return dateDimension(column, rows, bucket, ctx);
		case 'LONG': case 'DOUBLE': case 'DECIMAL': return numberDimension(column, rows, ctx);
		default: return textDimension(column, rows, ctx, orderByTotal);
	}
}

// ---- the model ----

interface Cell { count: number; sum: number; min: number; max: number }

// The figure a cell shows. An empty cell is 0 for a count or a sum, and a
// gap for the statistics that have no value without rows.
function cellValue(cell: Cell | undefined, agg: Aggregate): number | null {
	if (!cell || cell.count === 0) return agg === 'count' || agg === 'sum' ? 0 : null;
	switch (agg) {
		case 'count': return cell.count;
		case 'sum': return cell.sum;
		case 'avg': return cell.sum / cell.count;
		case 'min': return cell.min;
		default: return cell.max;
	}
}

function addTo(cells: Map<string, Cell>, key: string, value: number): void {
	const cell = cells.get(key);
	if (cell) {
		cell.count++;
		cell.sum += value;
		if (value < cell.min) cell.min = value;
		if (value > cell.max) cell.max = value;
	} else {
		cells.set(key, { count: 1, sum: value, min: value, max: value });
	}
}

export function chartValueLabel(spec: ChartSpec, ctx: ChartContext): string {
	const agg = spec.y ? spec.agg : 'count';
	const aggLabel = ctx.t('app.memo.dataset.chart.agg.' + agg, undefined, agg);
	return spec.y && agg !== 'count' ? `${aggLabel} · ${spec.y.label || spec.y.key}` : aggLabel;
}

// Aggregate the rows for a chart.
export function buildChartModel(spec: ChartSpec, rows: ChartRow[], ctx: ChartContext): ChartModel {
	const agg: Aggregate = spec.y ? spec.agg : 'count';
	const label = chartValueLabel(spec, ctx);
	const model: ChartModel = { kind: spec.kind, labels: [], categoryColors: null, series: [], points: [], valueLabel: label, total: null, skipped: 0 };

	// The value a row contributes: 1 for a count, its Y value otherwise.
	const valueOf = (row: ChartRow): number | null => {
		if (!spec.y || agg === 'count') return 1;
		return numberOf(row, spec.y);
	};

	if (spec.kind === 'number') {
		const cells = new Map<string, Cell>();
		for (const row of rows) {
			const v = valueOf(row);
			if (v == null) { model.skipped++; continue; }
			addTo(cells, 'all', v);
		}
		model.total = cellValue(cells.get('all'), agg);
		return model;
	}

	if (spec.kind === 'scatter') {
		if (!spec.x || !spec.y || !isNumericColumn(spec.x) || !isNumericColumn(spec.y)) return model;
		const seriesDim = spec.series ? dimensionOf(spec.series, rows, spec.bucket, ctx, null) : null;
		const seriesColors = seriesDim ? assignColors(seriesDim) : null;
		const keys = seriesDim ? seriesDim.keys : ['all'];
		const bySeries = new Map<string, { x: number; y: number; title: string }[]>(keys.map(k => [k, []]));
		for (const row of rows) {
			const x = numberOf(row, spec.x);
			const y = numberOf(row, spec.y);
			if (x == null || y == null) { model.skipped++; continue; }
			const k = seriesDim ? seriesDim.keysOf(row)[0] : 'all';
			bySeries.get(k)?.push({ x, y, title: row.title });
		}
		for (const k of keys) {
			const data = bySeries.get(k) || [];
			if (seriesDim && data.length === 0) continue;
			model.points.push({
				label: seriesDim ? seriesDim.label(k) : label,
				color: swatch(seriesColors ? seriesColors.get(k) : DEFAULT_SWATCH),
				data,
			});
		}
		return model;
	}

	if (!spec.x) return model;

	// Two passes: the first collects each row's contribution and the totals
	// per category, the second builds the X dimension in its final order
	// (free text is ordered by total) and fills the cells.
	const seriesDim = spec.series ? dimensionOf(spec.series, rows, spec.bucket, ctx, null) : null;
	const rawX = dimensionOf(spec.x, rows, spec.bucket, ctx, null);
	const totals = new Map<string, Cell>();
	const contributions: { xKeys: string[]; sKey: string; value: number }[] = [];
	for (const row of rows) {
		const v = valueOf(row);
		const xKeys = rawX.keysOf(row);
		if (v == null || xKeys.length === 0) { model.skipped++; continue; }
		const sKey = seriesDim ? seriesDim.keysOf(row)[0] : 'all';
		contributions.push({ xKeys, sKey, value: v });
		for (const xk of xKeys) addTo(totals, xk, v);
	}
	const xDim = dimensionOf(spec.x, rows, spec.bucket, ctx, key => cellValue(totals.get(key), agg) ?? 0);
	const cells = new Map<string, Cell>();
	for (const c of contributions) for (const xk of c.xKeys) addTo(cells, xk + CELL_SEPARATOR + c.sKey, c.value);

	model.labels = xDim.keys.map(k => xDim.label(k));
	if (seriesDim) {
		const colors = assignColors(seriesDim);
		const inUse = new Set(contributions.map(c => c.sKey));
		for (const sk of seriesDim.keys) {
			// A choice nobody uses stays out of the legend.
			if (!inUse.has(sk)) continue;
			const values = xDim.keys.map(xk => cellValue(cells.get(xk + CELL_SEPARATOR + sk), agg));
			model.series.push({ label: seriesDim.label(sk), color: swatch(colors.get(sk)), values });
		}
	} else {
		const values = xDim.keys.map(xk => cellValue(cells.get(xk + CELL_SEPARATOR + 'all'), agg));
		// One series over a choice or checkbox column, and every pie: each
		// category keeps its own colour, the one its chips and lanes have.
		// Otherwise the one series is one colour.
		const byCategory = spec.x.choices.length > 0 || spec.x.type === 'BOOLEAN' || spec.kind === 'pie';
		const colors = byCategory ? assignColors(xDim) : null;
		model.categoryColors = colors ? xDim.keys.map(k => swatch(colors.get(k))) : null;
		model.series.push({ label, color: swatch(DEFAULT_SWATCH), values });
	}
	return model;
}

// ---- Chart.js configuration ----

export function formatChartNumber(value: number | null | undefined, locale: string | undefined): string {
	if (value == null || !Number.isFinite(value)) return '—';
	return new Intl.NumberFormat(locale || undefined, { maximumFractionDigits: 2 }).format(value);
}

function compactNumber(value: number, locale: string | undefined): string {
	return new Intl.NumberFormat(locale || undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}

// The configuration of one of the block's chart kinds. Each kind is typed on
// its own, so Chart.js's per-type option types check the callbacks.
export type DatasetChartConfiguration =
	| ChartConfiguration<'bar'>
	| ChartConfiguration<'line'>
	| ChartConfiguration<'pie'>
	| ChartConfiguration<'scatter'>;

export function buildChartConfig(model: ChartModel, theme: ChartTheme, ctx: ChartContext): DatasetChartConfiguration {
	const locale = ctx.locale;
	const fmt = (v: number | null | undefined) => formatChartNumber(v, locale);
	const tooltip = {
		backgroundColor: theme.tooltipBg,
		titleColor: theme.text,
		bodyColor: theme.text,
		footerColor: theme.muted,
		borderColor: theme.tooltipBorder,
		borderWidth: 1,
		padding: 8,
		boxPadding: 4,
		usePointStyle: true,
	};
	const legend = (show: boolean) => ({
		display: show,
		position: 'bottom' as const,
		labels: { color: theme.text, boxWidth: 8, boxHeight: 8, usePointStyle: true, pointStyle: 'circle' as const, font: { size: 11 }, padding: 12 },
	});
	const ticks = { color: theme.muted, font: { size: 11 } };
	const compact = (v: string | number) => compactNumber(Number(v), locale);

	if (model.kind === 'pie') {
		const s = model.series[0];
		const data = s ? s.values.map(v => v ?? 0) : [];
		const total = data.reduce((a, b) => a + b, 0);
		const percent = new Intl.NumberFormat(locale || undefined, { style: 'percent', maximumFractionDigits: 1 });
		const config: ChartConfiguration<'pie'> = {
			type: 'pie',
			data: {
				labels: model.labels,
				datasets: [{
					label: model.valueLabel,
					data,
					backgroundColor: model.categoryColors || [],
					// Slices are separated by a hairline of the surface.
					borderColor: theme.tooltipBg,
					borderWidth: 2,
					hoverOffset: 4,
				}],
			},
			options: {
				responsive: true,
				maintainAspectRatio: false,
				animation: false,
				layout: { padding: 8 },
				plugins: {
					legend: legend(true),
					tooltip: {
						...tooltip,
						callbacks: {
							label: (item: TooltipItem<'pie'>) => {
								const v = item.parsed;
								const pct = total > 0 ? ` (${percent.format(v / total)})` : '';
								return ` ${model.valueLabel}: ${fmt(v)}${pct}`;
							},
						},
					},
				},
			},
		};
		return config;
	}

	if (model.kind === 'scatter') {
		const config: ChartConfiguration<'scatter'> = {
			type: 'scatter',
			data: {
				datasets: model.points.map(p => ({
					label: p.label,
					data: p.data,
					backgroundColor: withAlpha(p.color, 0.7),
					borderColor: p.color,
					borderWidth: 1,
					pointRadius: 4,
					pointHoverRadius: 6,
					pointHitRadius: 8,
				})),
			},
			options: {
				responsive: true,
				maintainAspectRatio: false,
				animation: false,
				layout: { padding: { top: 8, right: 12, bottom: 0, left: 0 } },
				scales: {
					x: { type: 'linear', grid: { display: false }, border: { color: theme.axis }, ticks: { ...ticks, callback: compact } },
					y: { type: 'linear', grid: { color: theme.grid }, border: { display: false }, ticks: { ...ticks, maxTicksLimit: 6, callback: compact } },
				},
				plugins: {
					legend: legend(model.points.length >= 2),
					tooltip: {
						...tooltip,
						callbacks: {
							title: (items: TooltipItem<'scatter'>[]) => items.map(i => (i.raw as { title: string }).title).join(', '),
							label: (item: TooltipItem<'scatter'>) => ` ${fmt(item.parsed.x)} · ${fmt(item.parsed.y)}`,
						},
					},
				},
			},
		};
		return config;
	}

	const horizontal = model.kind === 'hbar';
	const stacked = model.series.length >= 2 && model.kind !== 'line';
	const categoryAxis = { stacked, grid: { display: false }, border: { color: theme.axis }, ticks: { ...ticks, autoSkip: true, maxRotation: 0, autoSkipPadding: 12 } };
	const valueAxis = { stacked, beginAtZero: true, grid: { color: theme.grid }, border: { display: false }, ticks: { ...ticks, maxTicksLimit: 6, callback: compact } };
	const scales = horizontal ? { x: valueAxis, y: categoryAxis } : { x: categoryAxis, y: valueAxis };
	// The value of a point, on whichever axis carries the values.
	const valueOf = (item: TooltipItem<'bar'> | TooltipItem<'line'>): number => {
		const parsed = item.parsed as { x: number; y: number };
		return horizontal ? parsed.x : parsed.y;
	};
	const common = {
		responsive: true,
		maintainAspectRatio: false,
		animation: false as const,
		indexAxis: (horizontal ? 'y' : 'x') as 'x' | 'y',
		interaction: { mode: 'index' as const, intersect: false },
		layout: { padding: { top: 8, right: 12, bottom: 0, left: 0 } },
	};

	if (model.kind === 'line') {
		const config: ChartConfiguration<'line'> = {
			type: 'line',
			data: {
				labels: model.labels,
				datasets: model.series.map(s => ({
					label: s.label,
					data: s.values,
					borderColor: s.color,
					backgroundColor: s.color,
					borderWidth: 2,
					pointRadius: s.values.length > 60 ? 0 : 3,
					pointHoverRadius: 5,
					pointHitRadius: 10,
					tension: 0.2,
					spanGaps: false,
				})),
			},
			options: {
				...common,
				scales,
				plugins: {
					legend: legend(model.series.length >= 2),
					tooltip: {
						...tooltip,
						callbacks: { label: (item: TooltipItem<'line'>) => ` ${item.dataset.label}: ${fmt(valueOf(item))}` },
					},
				},
			},
		};
		return config;
	}

	const config: ChartConfiguration<'bar'> = {
		type: 'bar',
		data: {
			labels: model.labels,
			datasets: model.series.map(s => ({
				label: s.label,
				data: s.values,
				backgroundColor: model.categoryColors || s.color,
				// Stacked segments are separated by a hairline of the surface.
				borderColor: theme.tooltipBg,
				borderWidth: stacked ? 1 : 0,
				borderRadius: 3,
				maxBarThickness: 40,
			})),
		},
		options: {
			...common,
			scales,
			plugins: {
				legend: legend(model.series.length >= 2),
				tooltip: {
					...tooltip,
					callbacks: { label: (item: TooltipItem<'bar'>) => ` ${item.dataset.label}: ${fmt(valueOf(item))}` },
				},
			},
		},
	};
	return config;
}
