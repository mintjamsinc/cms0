/**
 * Soft particle effects for games and playful UI (Reversi first).
 *
 * One transparent <canvas> is laid over the app (or over a given container)
 * and particles are drawn on it at a point: rings, dots, hearts, stars,
 * sparkles, petals and confetti, all drawn as canvas paths so nothing depends
 * on images or on how a platform renders emoji. The canvas is created on the
 * first effect and the animation loop only runs while particles are alive.
 *
 *   const fx = new Effects();
 *   fx.playAt(cell, 'place');                  // at the centre of an element
 *   fx.play('catch', event.clientX, event.clientY);
 *   fx.play('win');                            // no point: the whole view
 *   fx.on('play', e => sounds.play(e.name));   // hook for sound effects
 *   await fx.flip(disc, { onHalf: () => disc.dataset.color = 'white' });
 *
 * Motion follows the OS "reduce motion" setting by default: fewer particles
 * and no decorative element animation. Speeds are written per 60 fps frame
 * and scaled by the real frame time, so 120 Hz displays play at the same pace.
 */

export interface EffectPalette {
	primary: string;
	pink: string;
	sun: string;
	leaf: string;
	sky: string;
	lilac: string;
	cream: string;
	gray: string;
}

/** The ichigo.js hero colours. */
export const DEFAULT_EFFECT_PALETTE: EffectPalette = {
	primary: '#ED5A77',
	pink: '#FF9EBB',
	sun: '#FFC857',
	leaf: '#57B894',
	sky: '#7FB8F0',
	lilac: '#B79CF2',
	cream: '#FFF4D6',
	gray: '#B9A7AB',
};

export type MotionLevel = 'full' | 'reduced' | 'off';

export interface EffectsOptions {
	/** Element to draw over. It must be positioned (relative/absolute). Default: the whole view, position: fixed. */
	container?: HTMLElement;
	palette?: Partial<EffectPalette>;
	/** 'auto' (default) follows prefers-reduced-motion. */
	motion?: 'auto' | MotionLevel;
	/** Multiplier for particle counts; 1 is the standard amount. */
	density?: number;
	zIndex?: number;
}

/** Per-call options a preset may read. */
export interface PlayOptions {
	color?: string;
	colors?: string[];
	/** fireworks: number of bursts. */
	shots?: number;
	[key: string]: unknown;
}

export interface PlayEvent {
	name: string;
	x: number | null;
	y: number | null;
	options: PlayOptions;
}

export type ParticleShape = 'circle' | 'ring' | 'heart' | 'star' | 'sparkle' | 'petal' | 'confetti' | 'text';
export type ParticleFade = 'linear' | 'late' | 'twinkle';

/** What a preset passes to Effects.emit. Speeds are px per 60 fps frame, times are ms. */
export interface ParticleSpec {
	shape?: ParticleShape;
	x: number;
	y: number;
	vx?: number;
	vy?: number;
	gravity?: number;
	/** Velocity kept per frame (0.95 slows down quickly, 1 never). */
	drag?: number;
	size?: number;
	rot?: number;
	vr?: number;
	life?: number;
	delay?: number;
	alpha?: number;
	color?: string;
	fade?: ParticleFade;
	/** ring: start and end radius. */
	radius?: number;
	maxRadius?: number;
	lineWidth?: number;
	/** Side-to-side drift amplitude. */
	sway?: number;
	/** confetti: flutter speed. */
	flip?: number;
	/** Size wobble amplitude (0.2 = ±20 %). */
	pulse?: number;
	/** Floor to bounce on, and how many bounces. */
	bounceY?: number;
	bounces?: number;
	/** text */
	text?: string;
	font?: string;
}

interface Particle extends ParticleSpec {
	shape: ParticleShape;
	vx: number;
	vy: number;
	gravity: number;
	drag: number;
	size: number;
	rot: number;
	vr: number;
	life: number;
	delay: number;
	alpha: number;
	color: string;
	fade: ParticleFade;
	age: number;
	seed: number;
	swayPhase: number;
	flipPhase: number;
}

/** A point in the drawing area (local CSS px). */
export interface EffectPoint {
	x: number;
	y: number;
}

/** A preset draws one effect at a point, or over the whole view when the point is null. */
export type EffectPreset = (fx: Effects, at: EffectPoint | null, options: PlayOptions) => void;

const FRAME_MS = 1000 / 60;
const MAX_PARTICLES = 800;
const MAX_FRAME_MS = 50;

// ---- Shapes ----

function heartPath(g: CanvasRenderingContext2D, s: number): void {
	const h = s / 2;
	g.beginPath();
	g.moveTo(0, h * 0.75);
	g.bezierCurveTo(-h * 1.25, -h * 0.05, -h * 0.65, -h * 1.15, 0, -h * 0.45);
	g.bezierCurveTo(h * 0.65, -h * 1.15, h * 1.25, -h * 0.05, 0, h * 0.75);
	g.closePath();
}

function starPath(g: CanvasRenderingContext2D, s: number): void {
	const outer = s / 2;
	const inner = outer * 0.48;
	g.beginPath();
	for (let i = 0; i < 10; i++) {
		const r = i % 2 === 0 ? outer : inner;
		const a = (Math.PI * i) / 5 - Math.PI / 2;
		if (i === 0) g.moveTo(Math.cos(a) * r, Math.sin(a) * r);
		else g.lineTo(Math.cos(a) * r, Math.sin(a) * r);
	}
	g.closePath();
}

function sparklePath(g: CanvasRenderingContext2D, s: number): void {
	const r = s / 2;
	g.beginPath();
	g.moveTo(0, -r);
	g.quadraticCurveTo(0, 0, r, 0);
	g.quadraticCurveTo(0, 0, 0, r);
	g.quadraticCurveTo(0, 0, -r, 0);
	g.quadraticCurveTo(0, 0, 0, -r);
	g.closePath();
}

function petalPath(g: CanvasRenderingContext2D, s: number): void {
	const r = s / 2;
	g.beginPath();
	g.moveTo(0, r);
	g.bezierCurveTo(-r * 0.9, r * 0.2, -r * 0.6, -r * 0.8, -r * 0.12, -r * 0.7);
	g.lineTo(0, -r * 0.45);
	g.lineTo(r * 0.12, -r * 0.7);
	g.bezierCurveTo(r * 0.6, -r * 0.8, r * 0.9, r * 0.2, 0, r);
	g.closePath();
}

const SHAPES: Record<ParticleShape, (g: CanvasRenderingContext2D, p: Particle) => void> = {
	circle(g, p) {
		g.beginPath();
		g.arc(0, 0, p.size / 2, 0, Math.PI * 2);
		g.fill();
	},
	ring(g, p) {
		g.lineWidth = p.lineWidth || 2.5;
		g.beginPath();
		g.arc(0, 0, Math.max(0, p.radius ?? 0), 0, Math.PI * 2);
		g.stroke();
	},
	heart(g, p) {
		heartPath(g, p.size);
		g.fill();
	},
	star(g, p) {
		starPath(g, p.size);
		g.fill();
	},
	sparkle(g, p) {
		sparklePath(g, p.size);
		g.fill();
	},
	petal(g, p) {
		petalPath(g, p.size);
		g.fill();
	},
	confetti(g, p) {
		// A paper strip turning over as it falls.
		g.scale(1, Math.cos(p.flipPhase));
		g.fillRect(-p.size / 2, -p.size / 4, p.size, p.size / 2);
	},
	text(g, p) {
		g.font = `${p.size}px ${p.font || 'sans-serif'}`;
		g.textAlign = 'center';
		g.textBaseline = 'middle';
		g.fillText(p.text || '', 0, 0);
	},
};

const FADES: Record<ParticleFade, (t: number, p: Particle) => number> = {
	linear: t => 1 - t,
	late: t => (t < 0.6 ? 1 : 1 - (t - 0.6) / 0.4),
	twinkle: (t, p) => (1 - t) * Math.abs(Math.sin(p.age / 90 + p.seed)),
};

function rand(min: number, max: number): number {
	return min + Math.random() * (max - min);
}

function pick<T>(list: T[], i?: number): T {
	return list[(i == null ? Math.floor(Math.random() * list.length) : i) % list.length];
}

// ---- Presets ----

const PRESETS: Record<string, EffectPreset> = {
	/** A press: a small ring and four dots. */
	tap(fx, at, o) {
		if (!at) return;
		const { x, y } = at;
		const c = o.color || fx.palette.primary;
		fx.emit({ shape: 'ring', x, y, radius: 4, maxRadius: 22, life: 320, color: c, alpha: 0.55, lineWidth: 2 });
		const n = fx.count(4);
		for (let i = 0; i < n; i++) {
			const a = (Math.PI * 2 * i) / n + Math.PI / 4;
			fx.emit({ x, y, vx: Math.cos(a) * 1.6, vy: Math.sin(a) * 1.6, drag: 0.9, size: 4, life: 300, color: c });
		}
	},

	/** A selection: two rings spreading softly and a twinkle at the top right. */
	select(fx, at, o) {
		if (!at) return;
		const { x, y } = at;
		const c = o.color || fx.palette.primary;
		fx.emit({ shape: 'ring', x, y, radius: 10, maxRadius: 30, life: 420, color: c, alpha: 0.5 });
		fx.emit({ shape: 'ring', x, y, radius: 10, maxRadius: 30, life: 420, delay: 120, color: c, alpha: 0.35 });
		fx.emit({ shape: 'sparkle', x: x + 16, y: y - 16, size: 14, life: 520, color: fx.palette.sun, fade: 'twinkle', vr: 0.05 });
	},

	/** Something put down: a ring and colourful dots that fall a little. */
	place(fx, at, o) {
		if (!at) return;
		const { x, y } = at;
		const colors = o.colors || [fx.palette.primary, fx.palette.pink, fx.palette.sun];
		fx.emit({ shape: 'ring', x, y, radius: 6, maxRadius: 40, life: 360, color: colors[0], alpha: 0.6 });
		const n = fx.count(6);
		for (let i = 0; i < n; i++) {
			const a = (Math.PI * 2 * i) / n;
			const sp = rand(2, 3.5);
			fx.emit({ x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, gravity: 0.15, size: rand(5, 8), life: 420, color: pick(colors, i) });
		}
	},

	/** Something turned over: a few small twinkles (each Reversi disc that flips). */
	flip(fx, at, o) {
		if (!at) return;
		const { x, y } = at;
		const c = o.color || fx.palette.sun;
		const n = fx.count(5);
		for (let i = 0; i < n; i++) {
			const a = rand(0, Math.PI * 2);
			const sp = rand(0.8, 2);
			fx.emit({ shape: 'sparkle', x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 0.6, drag: 0.94, size: rand(8, 13), life: 520, color: c, fade: 'twinkle' });
		}
	},

	/** The ichigo.js hero's catch: ring, dots, a bouncing sparkle and four hearts. */
	catch(fx, at, o) {
		if (!at) return;
		const { x, y } = at;
		const p = fx.palette;
		fx.emit({ shape: 'ring', x, y, radius: 6, maxRadius: 48, life: 370, color: p.primary, alpha: 0.6 });
		const dots = [p.primary, p.pink, p.sun];
		const n = fx.count(6);
		for (let i = 0; i < n; i++) {
			const a = (Math.PI * 2 * i) / n;
			const sp = rand(2, 4);
			fx.emit({ x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, gravity: 0.2, size: rand(6, 10), life: 420, color: dots[i % dots.length] });
		}
		fx.emit({ shape: 'sparkle', x, y: y - 20, vy: -4, gravity: 0.3, bounceY: y + 5, bounces: 3, size: 20, life: 1330, color: p.sun });
		const hearts = o.colors || [p.sun, p.leaf, p.sky, p.pink];
		for (let i = 0; i < 4; i++) {
			const a = (Math.PI * 2 * i) / 4 + Math.PI / 4;
			const sp = rand(1.5, 2.5);
			fx.emit({ shape: 'heart', x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 0.5, gravity: 0.1, size: rand(14, 20), life: 830, color: hearts[i % hearts.length], pulse: 0.2 });
		}
	},

	/** Stars spinning outwards. */
	stars(fx, at, o) {
		if (!at) return;
		const { x, y } = at;
		const colors = o.colors || [fx.palette.sun, fx.palette.pink, fx.palette.sky];
		const n = fx.count(8);
		for (let i = 0; i < n; i++) {
			const a = (Math.PI * 2 * i) / n + rand(-0.2, 0.2);
			const sp = rand(3, 5);
			fx.emit({ shape: 'star', x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, drag: 0.93, gravity: 0.08, size: rand(10, 16), rot: rand(0, 6), vr: rand(-0.2, 0.2), life: 700, color: pick(colors, i), fade: 'late' });
		}
	},

	/** Hearts drifting upwards from the point, or rising from the bottom edge without one. */
	hearts(fx, at, o) {
		const colors = o.colors || [fx.palette.primary, fx.palette.pink];
		if (!at) {
			const n = fx.count(28);
			for (let i = 0; i < n; i++) {
				fx.emit({ shape: 'heart', x: rand(0, fx.width), y: fx.height + rand(10, 40), vx: rand(-0.4, 0.4), vy: rand(-3.5, -2), drag: 0.997, sway: rand(0.4, 0.9), size: rand(12, 20), life: rand(2600, 3400), delay: rand(0, 900), color: pick(colors), pulse: 0.15, fade: 'late' });
			}
			return;
		}
		const { x, y } = at;
		const n = fx.count(7);
		for (let i = 0; i < n; i++) {
			fx.emit({ shape: 'heart', x: x + rand(-14, 14), y, vx: rand(-0.4, 0.4), vy: rand(-2.2, -1.2), drag: 0.99, sway: rand(0.4, 0.9), size: rand(12, 20), life: rand(900, 1300), delay: i * 70, color: pick(colors), pulse: 0.15 });
		}
	},

	/** Cherry petals fluttering out and down from the point, or falling from the top edge without one. */
	petals(fx, at, o) {
		const colors = o.colors || ['#FFC4D6', '#FFB0C8', '#FFE0EA'];
		if (!at) {
			const n = fx.count(36);
			for (let i = 0; i < n; i++) {
				fx.emit({ shape: 'petal', x: rand(0, fx.width), y: rand(-40, -10), vx: rand(-0.8, 0.8), vy: rand(1, 2.5), drag: 0.985, gravity: 0.05, sway: rand(0.6, 1.2), size: rand(10, 15), rot: rand(0, 6), vr: rand(-0.06, 0.06), life: rand(2800, 3600), delay: rand(0, 1200), color: pick(colors), fade: 'late' });
			}
			return;
		}
		const { x, y } = at;
		const n = fx.count(10);
		for (let i = 0; i < n; i++) {
			const a = rand(-Math.PI, 0);
			const sp = rand(1.5, 3.5);
			fx.emit({ shape: 'petal', x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, drag: 0.96, gravity: 0.04, sway: rand(0.6, 1.2), size: rand(10, 15), rot: rand(0, 6), vr: rand(-0.06, 0.06), life: rand(1300, 1800), color: pick(colors), fade: 'late' });
		}
	},

	/** Confetti: a fountain from the point, or falling from the top edge without one. */
	confetti(fx, at, o) {
		const p = fx.palette;
		const colors = o.colors || [p.primary, p.pink, p.sun, p.leaf, p.sky, p.lilac];
		const n = fx.count(at ? 36 : 90);
		for (let i = 0; i < n; i++) {
			const from = at ?
				{ x: at.x, y: at.y, vx: rand(-4, 4), vy: rand(-9, -4), delay: 0 } :
				{ x: rand(0, fx.width), y: rand(-40, -10), vx: rand(-1, 1), vy: rand(1, 3), delay: rand(0, 900) };
			fx.emit({ ...from, shape: 'confetti', gravity: 0.12, drag: 0.985, sway: rand(0.3, 0.8), size: rand(8, 12), rot: rand(0, 6), vr: rand(-0.1, 0.1), flip: rand(0.1, 0.25), life: rand(2200, 3200), color: pick(colors), fade: 'late' });
		}
	},

	/** Fireworks: a few bursts around the point, or across the upper half of the view. */
	fireworks(fx, at, o) {
		const p = fx.palette;
		const colors = o.colors || [p.primary, p.sun, p.sky, p.leaf, p.lilac];
		const shots = o.shots || (at ? 3 : 5);
		for (let s = 0; s < shots; s++) {
			const cx = at ? at.x + rand(-60, 60) : rand(fx.width * 0.15, fx.width * 0.85);
			const cy = at ? at.y + rand(-50, 30) : rand(fx.height * 0.12, fx.height * 0.45);
			const delay = s * 260 + rand(0, 120);
			const c = pick(colors, s);
			fx.emit({ shape: 'ring', x: cx, y: cy, radius: 4, maxRadius: 34, life: 380, delay, color: c, alpha: 0.5 });
			const n = fx.count(16);
			for (let i = 0; i < n; i++) {
				const a = (Math.PI * 2 * i) / n;
				const sp = rand(2.5, 3.5);
				const dot = i % 2 === 1;
				fx.emit({ shape: dot ? 'circle' : 'sparkle', x: cx, y: cy, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, drag: 0.95, gravity: 0.05, size: dot ? 5 : 11, life: 900, delay, color: c, fade: dot ? 'linear' : 'twinkle' });
			}
		}
	},

	/** Not allowed: a grey ring that shrinks. */
	nope(fx, at, o) {
		if (!at) return;
		const { x, y } = at;
		const c = o.color || fx.palette.gray;
		fx.emit({ shape: 'ring', x, y, radius: 26, maxRadius: 8, life: 300, color: c, alpha: 0.7, lineWidth: 3 });
	},

	/** A win: fireworks, confetti and, at the point, the catch. */
	win(fx, at, o) {
		PRESETS.fireworks(fx, null, o);
		PRESETS.confetti(fx, null, o);
		if (at) PRESETS.catch(fx, at, o);
	},
};

export class Effects {
	readonly palette: EffectPalette;
	motion: 'auto' | MotionLevel;
	density: number;
	/** Size of the drawing area in CSS px (valid after the first effect). */
	width = 0;
	height = 0;

	private readonly container: HTMLElement | null;
	private readonly zIndex: number;
	private readonly presets: Record<string, EffectPreset> = { ...PRESETS };
	private readonly particles: Particle[] = [];
	private readonly listeners = new Map<string, Set<(e: PlayEvent) => void>>();
	private readonly reduceMotionQuery: MediaQueryList | null;
	private canvas: HTMLCanvasElement | null = null;
	private g: CanvasRenderingContext2D | null = null;
	private resizeObserver: ResizeObserver | null = null;
	private dpr = 1;
	private running = false;
	private lastTime = 0;

	constructor(options: EffectsOptions = {}) {
		this.container = options.container || null;
		this.palette = { ...DEFAULT_EFFECT_PALETTE, ...options.palette };
		this.motion = options.motion || 'auto';
		this.density = options.density ?? 1;
		this.zIndex = options.zIndex ?? 9999;
		this.reduceMotionQuery = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
	}

	/** The motion actually applied. */
	get motionLevel(): MotionLevel {
		if (this.motion !== 'auto') return this.motion;
		return this.reduceMotionQuery?.matches ? 'reduced' : 'full';
	}

	/** Registered effect names (for a demo or a settings list). */
	get names(): string[] {
		return Object.keys(this.presets);
	}

	/** Adds or replaces an effect. */
	register(name: string, preset: EffectPreset): this {
		this.presets[name] = preset;
		return this;
	}

	/** 'play' fires for every effect, even with motion off, so sounds can follow it. Returns an unsubscribe function. */
	on(type: 'play', listener: (e: PlayEvent) => void): () => void {
		let set = this.listeners.get(type);
		if (!set) {
			set = new Set();
			this.listeners.set(type, set);
		}
		set.add(listener);
		return () => this.off(type, listener);
	}

	off(type: 'play', listener: (e: PlayEvent) => void): void {
		this.listeners.get(type)?.delete(listener);
	}

	/**
	 * Plays an effect at client coordinates (MouseEvent.clientX/Y, the same
	 * space as getBoundingClientRect). Without a point, confetti, fireworks,
	 * win, hearts and petals cover the whole view; the other effects need a point.
	 */
	play(name: string, x: number | null = null, y: number | null = null, options: PlayOptions = {}): this {
		const preset = this.presets[name];
		if (!preset) throw new Error(`Unknown effect: ${name}`);
		this.fire('play', { name, x, y, options });
		if (this.motionLevel === 'off') return this;
		this.attach();
		let at: EffectPoint | null = null;
		if (x != null && y != null) {
			const r = this.container?.getBoundingClientRect();
			at = r ? { x: x - r.left, y: y - r.top } : { x, y };
		}
		preset(this, at, options);
		this.start();
		return this;
	}

	/** Plays an effect at the centre of an element. */
	playAt(el: Element, name: string, options: PlayOptions = {}): this {
		const r = el.getBoundingClientRect();
		return this.play(name, r.left + r.width / 2, r.top + r.height / 2, options);
	}

	/** A particle count scaled for the motion level and density; for presets. */
	count(n: number): number {
		const k = this.motionLevel === 'reduced' ? 0.25 : 1;
		return Math.max(1, Math.round(n * k * this.density));
	}

	/** Adds one particle; for presets. Coordinates are local to the drawing area. */
	emit(spec: ParticleSpec): void {
		const p: Particle = {
			shape: 'circle',
			vx: 0,
			vy: 0,
			gravity: 0,
			drag: 1,
			size: 6,
			rot: 0,
			vr: 0,
			life: 500,
			delay: 0,
			alpha: 1,
			color: this.palette.primary,
			fade: 'linear',
			...spec,
			age: 0,
			seed: Math.random() * 10,
			swayPhase: Math.random() * Math.PI * 2,
			flipPhase: Math.random() * Math.PI * 2,
		};
		if (p.shape === 'ring') {
			p.radius ??= 4;
			p.maxRadius ??= p.radius;
		}
		if (this.particles.length >= MAX_PARTICLES) this.particles.shift();
		this.particles.push(p);
	}

	/** Removes every particle on screen. */
	clear(): void {
		this.particles.length = 0;
		this.g?.clearRect(0, 0, this.width, this.height);
	}

	destroy(): void {
		this.clear();
		this.running = false;
		window.removeEventListener('resize', this.resize);
		this.resizeObserver?.disconnect();
		this.resizeObserver = null;
		this.canvas?.remove();
		this.canvas = null;
		this.g = null;
		this.listeners.clear();
	}

	// ---- Animating the element itself (Web Animations API) ----

	/** Swells and settles, like the hero bird after a catch. */
	pop(el: Element, scale = 1.18): Promise<void> {
		return this.animate(el, [
			{ transform: 'scale(1)' },
			{ transform: `scale(${scale})`, offset: 0.35 },
			{ transform: 'scale(0.96)', offset: 0.7 },
			{ transform: 'scale(1)' },
		], 360);
	}

	/** Hops up and lands with a squash. */
	bounce(el: Element, height = 10): Promise<void> {
		return this.animate(el, [
			{ transform: 'translateY(0)' },
			{ transform: `translateY(${-height}px)`, offset: 0.4, easing: 'ease-in' },
			{ transform: 'translateY(0) scale(1.06, 0.94)', offset: 0.75 },
			{ transform: 'translateY(0)' },
		], 420);
	}

	/** Shakes sideways (not allowed). Kept in reduced motion because it carries meaning. */
	shake(el: Element, distance = 6): Promise<void> {
		const d = distance;
		return this.animate(el, [
			{ transform: 'translateX(0)' },
			{ transform: `translateX(${-d}px)` },
			{ transform: `translateX(${d}px)` },
			{ transform: `translateX(${-d / 2}px)` },
			{ transform: `translateX(${d / 2}px)` },
			{ transform: 'translateX(0)' },
		], 320, { essential: true });
	}

	/** Dims and shrinks once (a hint). */
	pulse(el: Element): Promise<void> {
		return this.animate(el, [
			{ opacity: 1, transform: 'scale(1)' },
			{ opacity: 0.55, transform: 'scale(0.92)' },
			{ opacity: 1, transform: 'scale(1)' },
		], 600);
	}

	/**
	 * Turns over. onHalf runs when the element is edge-on, which is where the
	 * caller swaps its colour or face (a Reversi disc). Without full motion the
	 * swap happens at once.
	 */
	async flip(el: Element, { axis = 'Y', duration = 360, onHalf }: { axis?: 'X' | 'Y'; duration?: number; onHalf?: () => void } = {}): Promise<void> {
		if (this.motionLevel !== 'full') {
			onHalf?.();
			return;
		}
		const rot = `rotate${axis}`;
		const half = duration / 2;
		await this.animate(el, [
			{ transform: `perspective(400px) ${rot}(0deg) scale(1)` },
			{ transform: `perspective(400px) ${rot}(90deg) scale(1.12)` },
		], half, { easing: 'ease-in', hold: true });
		onHalf?.();
		await this.animate(el, [
			{ transform: `perspective(400px) ${rot}(-90deg) scale(1.12)` },
			{ transform: `perspective(400px) ${rot}(0deg) scale(1)` },
		], half);
	}

	private animate(el: Element, keyframes: Keyframe[], duration: number, opts: { essential?: boolean; easing?: string; hold?: boolean } = {}): Promise<void> {
		const level = this.motionLevel;
		if (!el || typeof (el as HTMLElement).animate !== 'function' || level === 'off' || (level === 'reduced' && !opts.essential)) {
			return Promise.resolve();
		}
		// hold keeps the last frame until the next animation takes over, so a
		// flip does not snap back to face-on between its two halves.
		const anim = el.animate(keyframes, { duration, easing: opts.easing || 'ease-out', fill: opts.hold ? 'forwards' : 'none' });
		return anim.finished.then(() => {
			if (opts.hold) requestAnimationFrame(() => anim.cancel());
		}, () => undefined);
	}

	// ---- Internals ----

	private fire(type: string, e: PlayEvent): void {
		const set = this.listeners.get(type);
		if (!set) return;
		for (const listener of set) {
			try {
				listener(e);
			} catch (err) {
				console.error(err);
			}
		}
	}

	private attach(): void {
		if (this.canvas) return;
		const canvas = document.createElement('canvas');
		canvas.setAttribute('aria-hidden', 'true');
		const position = this.container ? 'absolute' : 'fixed';
		canvas.style.cssText = `position:${position};left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:${this.zIndex}`;
		(this.container || document.body).appendChild(canvas);
		this.canvas = canvas;
		this.g = canvas.getContext('2d');
		if (this.container && typeof ResizeObserver === 'function') {
			this.resizeObserver = new ResizeObserver(this.resize);
			this.resizeObserver.observe(this.container);
		}
		window.addEventListener('resize', this.resize);
		this.resize();
	}

	private readonly resize = (): void => {
		if (!this.canvas) return;
		const w = this.container ? this.container.clientWidth : window.innerWidth;
		const h = this.container ? this.container.clientHeight : window.innerHeight;
		this.dpr = Math.min(window.devicePixelRatio || 1, 2);
		this.width = w;
		this.height = h;
		this.canvas.width = Math.round(w * this.dpr);
		this.canvas.height = Math.round(h * this.dpr);
	};

	private start(): void {
		if (this.running) return;
		this.running = true;
		this.lastTime = performance.now();
		requestAnimationFrame(this.frame);
	}

	private readonly frame = (now: number): void => {
		const g = this.g;
		if (!this.running || !g) return;
		const dt = Math.min(MAX_FRAME_MS, Math.max(0, now - this.lastTime));
		this.lastTime = now;
		const k = dt / FRAME_MS;
		g.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
		g.clearRect(0, 0, this.width, this.height);

		const list = this.particles;
		for (let i = list.length - 1; i >= 0; i--) {
			const p = list[i];
			if (p.delay > 0) {
				p.delay -= dt;
				continue;
			}
			p.age += dt;
			if (p.age >= p.life) {
				list.splice(i, 1);
				continue;
			}
			this.step(p, k);
			this.draw(g, p);
		}

		if (list.length === 0) {
			this.running = false;
			return;
		}
		requestAnimationFrame(this.frame);
	};

	private step(p: Particle, k: number): void {
		if (p.shape === 'ring') {
			const r = p.radius ?? 0;
			p.radius = r + ((p.maxRadius ?? r) - r) * (1 - Math.pow(1 - 0.18, k));
			return;
		}
		const drag = Math.pow(p.drag, k);
		p.vx *= drag;
		p.vy *= drag;
		p.vy += p.gravity * k;
		p.x += p.vx * k;
		p.y += p.vy * k;
		p.rot += p.vr * k;
		if (p.sway) {
			p.swayPhase += 0.08 * k;
			p.x += Math.sin(p.swayPhase) * p.sway * k;
		}
		if (p.flip) p.flipPhase += p.flip * k;
		if (p.bounceY != null && p.y >= p.bounceY && p.vy > 0) {
			p.y = p.bounceY;
			if (p.bounces) {
				p.vy *= -0.6;
				p.bounces--;
			} else {
				p.vy = 0;
				p.gravity = 0;
			}
		}
	}

	private draw(g: CanvasRenderingContext2D, p: Particle): void {
		const t = p.age / p.life;
		const a = p.alpha * Math.max(0, Math.min(1, FADES[p.fade](t, p)));
		if (a <= 0) return;
		g.save();
		g.globalAlpha = a;
		g.translate(p.x, p.y);
		if (p.rot) g.rotate(p.rot);
		if (p.pulse) {
			const s = 1 + Math.sin(p.age / 110 + p.seed) * p.pulse;
			g.scale(s, s);
		}
		g.fillStyle = p.color;
		g.strokeStyle = p.color;
		SHAPES[p.shape](g, p);
		g.restore();
	}
}
