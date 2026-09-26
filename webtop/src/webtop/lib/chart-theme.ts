/**
 * Theme colours for a Chart.js canvas.
 *
 * A canvas cannot read CSS variables, so the few colours a chart's chrome
 * needs (axis, grid, tick labels, tooltip) are resolved from the shell's
 * theme variables at draw time and re-read when the theme switches. The
 * variables are tripped through a probe element so Chart.js's colour parser
 * only ever sees a computed `rgb()` / `rgba()` string, whatever form the
 * stylesheet writes them in.
 *
 * Originally the EIP Console's; extracted here so the Memo app's dataset
 * charts use the exact same tints and the two never drift apart.
 */
export interface ChartTheme {
	/** Tick labels and secondary text. */
	muted: string;
	/** Primary text (tooltip title and body). */
	text: string;
	/** Axis line. */
	axis: string;
	/** Gridlines: a hairline one shade off the surface. */
	grid: string;
	tooltipBg: string;
	tooltipBorder: string;
}

export function resolveChartTheme(): ChartTheme {
	const muted = resolveCssColor('--text-muted-color', 'rgba(128, 128, 128, 0.5)');
	return {
		muted,
		text: resolveCssColor('--body-color', '#1a1b1f'),
		axis: withAlpha(muted, 0.4),
		grid: withAlpha(muted, 0.15),
		tooltipBg: resolveCssColor('--body-bg', '#ffffff'),
		tooltipBorder: withAlpha(muted, 0.35),
	};
}

/**
 * Resolve a CSS variable to a computed colour string. The value is applied to
 * a hidden probe so `var()` chains, named colours and hex all come back as
 * `rgb()` / `rgba()`.
 */
export function resolveCssColor(varName: string, fallback: string): string {
	try {
		const raw = getComputedStyle(document.documentElement).getPropertyValue(varName).trim();
		if (!raw) return fallback;
		const probe = document.createElement('span');
		probe.style.display = 'none';
		probe.style.color = raw;
		document.body.appendChild(probe);
		const resolved = getComputedStyle(probe).color;
		probe.remove();
		return resolved || fallback;
	} catch {
		return fallback;
	}
}

/**
 * Re-alpha a computed `rgb()` / `rgba()` colour, or a `#rrggbb` hex. Used to
 * derive the axis and grid tints from the single muted text colour, and the
 * translucent fills of a chart's marks from their solid swatch.
 */
export function withAlpha(color: string, alpha: number): string {
	const hex = /^#([0-9a-f]{6})$/i.exec(color.trim());
	if (hex) {
		const n = parseInt(hex[1], 16);
		return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha.toFixed(3)})`;
	}
	const m = /^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,/\s]+([\d.]+))?\s*\)$/i.exec(color);
	if (!m) return color;
	const baseAlpha = m[4] !== undefined ? Number(m[4]) : 1;
	return `rgba(${m[1]}, ${m[2]}, ${m[3]}, ${(baseAlpha * alpha).toFixed(3)})`;
}
