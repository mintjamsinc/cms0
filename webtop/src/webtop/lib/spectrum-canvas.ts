/**
 * Bar spectrum drawn on a <canvas>, shared by the players that visualise
 * audio (the Radio app, the Inspector's audio preview).
 *
 * The caller owns the AnalyserNode and the animation loop; this module only
 * draws one frame from the analyser's frequency data. Without data it draws
 * the idle baseline (a row of flat bars), so a stopped player still shows
 * where the spectrum will appear.
 */

export interface SpectrumColors {
	/** Left end of the bar gradient. */
	from: string;
	/** Right end of the bar gradient. */
	to: string;
	/** The flat bars drawn while nothing plays. */
	idle: string;
}

export const DEFAULT_SPECTRUM_COLORS: SpectrumColors = {
	from: '#8b5cf6',
	to: '#06b6d4',
	idle: 'rgba(128, 128, 128, 0.25)',
};

export const SPECTRUM_BAR_COUNT = 48;

/**
 * Draws one frame. `data` is the analyser's byte frequency data (the array
 * filled by getByteFrequencyData), or null for the idle baseline. The canvas
 * is resized to its CSS box at the device pixel ratio when needed.
 */
export function drawSpectrum(canvas: HTMLCanvasElement, data: Uint8Array | null, colors: SpectrumColors = DEFAULT_SPECTRUM_COLORS): void {
	const dpr = window.devicePixelRatio || 1;
	const width = canvas.clientWidth;
	const height = canvas.clientHeight;
	if (!width || !height) return;
	if (canvas.width !== Math.round(width * dpr) || canvas.height !== Math.round(height * dpr)) {
		canvas.width = Math.round(width * dpr);
		canvas.height = Math.round(height * dpr);
	}
	const g = canvas.getContext('2d');
	if (!g) return;
	g.setTransform(dpr, 0, 0, dpr, 0, 0);
	g.clearRect(0, 0, width, height);

	const gap = 2;
	const barWidth = (width - gap * (SPECTRUM_BAR_COUNT - 1)) / SPECTRUM_BAR_COUNT;

	if (data) {
		const gradient = g.createLinearGradient(0, 0, width, 0);
		gradient.addColorStop(0, colors.from);
		gradient.addColorStop(1, colors.to);
		g.fillStyle = gradient;
		// Only the lower ~70% of the bins carry audible energy for music;
		// map the bars onto that range so the view is not half empty.
		const usable = Math.floor(data.length * 0.7);
		for (let i = 0; i < SPECTRUM_BAR_COUNT; i++) {
			const bin = Math.floor((i / SPECTRUM_BAR_COUNT) * usable);
			const value = data[bin] / 255;
			const h = Math.max(2, value * height);
			const x = i * (barWidth + gap);
			g.beginPath();
			g.roundRect(x, height - h, barWidth, h, barWidth / 2);
			g.fill();
		}
		return;
	}

	g.fillStyle = colors.idle;
	for (let i = 0; i < SPECTRUM_BAR_COUNT; i++) {
		const x = i * (barWidth + gap);
		g.beginPath();
		g.roundRect(x, height - 3, barWidth, 3, 1.5);
		g.fill();
	}
}
