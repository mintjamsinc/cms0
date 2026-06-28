/**
 * Shared swatch colour palette.
 *
 * The 11 Google-Calendar-style swatches (Tomato … Graphite), used by any app
 * that needs a small, consistent set of named colours. Originally the EIP
 * Console's elapsed-band picker; extracted here so the Memo app's text /
 * highlight colour menus draw from the exact same variations and the two never
 * drift apart.
 *
 * `key`   — stable identifier persisted in settings / documents.
 * `label` — English fallback; localized via `app.<id>.color.<key>` at the call
 *           site.
 * `value` — concrete hex (theme-independent, so a saved choice renders the same
 *           on light and dark backgrounds).
 */
export interface SwatchColor {
	key: string;
	label: string;
	value: string;
}

export const SWATCH_COLORS: SwatchColor[] = [
	{ key: 'tomato',    label: 'Tomato',    value: '#D50000' },
	{ key: 'tangerine', label: 'Tangerine', value: '#F4511E' },
	{ key: 'banana',    label: 'Banana',    value: '#F6BF26' },
	{ key: 'basil',     label: 'Basil',     value: '#0B8043' },
	{ key: 'sage',      label: 'Sage',      value: '#33B679' },
	{ key: 'peacock',   label: 'Peacock',   value: '#039BE5' },
	{ key: 'blueberry', label: 'Blueberry', value: '#3F51B5' },
	{ key: 'lavender',  label: 'Lavender',  value: '#7986CB' },
	{ key: 'grape',     label: 'Grape',     value: '#8E24AA' },
	{ key: 'flamingo',  label: 'Flamingo',  value: '#E67C73' },
	{ key: 'graphite',  label: 'Graphite',  value: '#616161' },
];

export const SWATCH_COLOR_MAP: Record<string, string> =
	SWATCH_COLORS.reduce((m, c) => { m[c.key] = c.value; return m; }, {} as Record<string, string>);

/**
 * Pale companion palette for text-highlight backgrounds.
 *
 * Same 11 keys / labels as SWATCH_COLORS, but each `value` is a soft tint of
 * the matching base hue (Material-Design ~100 level) so highlighted text stays
 * readable with the default dark body text. Use this for "marker pen" style
 * highlights; use SWATCH_COLORS for foreground text colour.
 */
export const SWATCH_HIGHLIGHT_COLORS: SwatchColor[] = [
	{ key: 'tomato',    label: 'Tomato',    value: '#FFCDD2' },
	{ key: 'tangerine', label: 'Tangerine', value: '#FFCCBC' },
	{ key: 'banana',    label: 'Banana',    value: '#FFF59D' },
	{ key: 'basil',     label: 'Basil',     value: '#C8E6C9' },
	{ key: 'sage',      label: 'Sage',      value: '#DCEDC8' },
	{ key: 'peacock',   label: 'Peacock',   value: '#B3E5FC' },
	{ key: 'blueberry', label: 'Blueberry', value: '#C5CAE9' },
	{ key: 'lavender',  label: 'Lavender',  value: '#D1D9FF' },
	{ key: 'grape',     label: 'Grape',     value: '#E1BEE7' },
	{ key: 'flamingo',  label: 'Flamingo',  value: '#F8BBD0' },
	{ key: 'graphite',  label: 'Graphite',  value: '#E0E0E0' },
];

export const SWATCH_HIGHLIGHT_COLOR_MAP: Record<string, string> =
	SWATCH_HIGHLIGHT_COLORS.reduce((m, c) => { m[c.key] = c.value; return m; }, {} as Record<string, string>);
