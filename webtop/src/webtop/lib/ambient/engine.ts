/**
 * Ambient sound engine.
 *
 * Every sound is made in the browser with Web Audio from noise and
 * oscillators: no recordings, so nothing to license and nothing to download.
 * A sound is a layer: a small graph of filtered noise that keeps playing, and
 * for some sounds events (a rain drop, a crackle, a bird's phrase) scheduled a
 * little ahead on the audio clock. Layers are mixed by level under one master
 * volume and a gentle compressor, so several loud layers do not clip.
 *
 * Events are scheduled LOOKAHEAD_S ahead from a coarse timer, so the sound
 * stays even when the page is in the background and its timers are slowed.
 * A layer exists only while its level is above zero; a silent sound costs
 * nothing.
 *
 * The AudioContext is made on the first play(). Browsers start audio only
 * from a user's click, so call play() from one (it can be called again later
 * without a click while the context lives).
 */

export type SoundId =
	| 'rain' | 'thunder' | 'waves' | 'stream' | 'wind' | 'fire'
	| 'crickets' | 'birds' | 'white' | 'pink' | 'brown';

export interface SoundInfo {
	id: SoundId;
	icon: string;      // Bootstrap Icons class
	label: string;     // English fallback; localized as app.<id>.sound.<id>
}

export const SOUNDS: SoundInfo[] = [
	{ id: 'rain', icon: 'bi-cloud-rain', label: 'Rain' },
	{ id: 'thunder', icon: 'bi-cloud-lightning', label: 'Thunder' },
	{ id: 'waves', icon: 'bi-water', label: 'Waves' },
	{ id: 'stream', icon: 'bi-droplet', label: 'Stream' },
	{ id: 'wind', icon: 'bi-wind', label: 'Wind' },
	{ id: 'fire', icon: 'bi-fire', label: 'Campfire' },
	{ id: 'crickets', icon: 'bi-moon-stars', label: 'Crickets' },
	{ id: 'birds', icon: 'bi-feather', label: 'Birds' },
	{ id: 'white', icon: 'bi-soundwave', label: 'White noise' },
	{ id: 'pink', icon: 'bi-soundwave', label: 'Pink noise' },
	{ id: 'brown', icon: 'bi-soundwave', label: 'Brown noise' },
];

/** Level per sound, 0..1. A missing sound is silent. */
export type Levels = Partial<Record<SoundId, number>>;

const TICK_MS = 200;
const LOOKAHEAD_S = 1.5;
const NOISE_SECONDS = 6;
const LEVEL_TIME_CONSTANT = 0.12;
const DISPOSE_AFTER_MS = 800;

// Loudness of each layer at full level, so the sliders feel alike.
const TRIM: Record<SoundId, number> = {
	rain: 1.6, thunder: 1, waves: 0.9, stream: 1, wind: 0.95, fire: 1.4,
	crickets: 0.2, birds: 0.7, white: 0.22, pink: 0.45, brown: 0.55,
};

const rand = (min: number, max: number) => min + Math.random() * (max - min);
/** Waiting time to the next event of a Poisson process with this rate per second. */
const nextGap = (ratePerSecond: number) => -Math.log(1 - Math.random()) / ratePerSecond;

type NoiseKind = 'white' | 'pink' | 'brown';

/**
 * Stereo noise buffers, made once per context and looped. The two channels
 * are independent, which makes the noise sound wide. The end is cross-faded
 * into the start so the loop has no seam (a step in brown noise would click).
 */
class NoiseBank {
	readonly white: AudioBuffer;
	readonly pink: AudioBuffer;
	readonly brown: AudioBuffer;

	constructor(ctx: BaseAudioContext) {
		this.white = NoiseBank.#make(ctx, 'white');
		this.pink = NoiseBank.#make(ctx, 'pink');
		this.brown = NoiseBank.#make(ctx, 'brown');
	}

	static #make(ctx: BaseAudioContext, kind: NoiseKind): AudioBuffer {
		const length = Math.floor(ctx.sampleRate * NOISE_SECONDS);
		const fade = Math.floor(ctx.sampleRate * 0.1);
		const buffer = ctx.createBuffer(2, length, ctx.sampleRate);
		for (let ch = 0; ch < 2; ch++) {
			const raw = new Float32Array(length + fade);
			let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0, last = 0;
			for (let i = 0; i < raw.length; i++) {
				const w = Math.random() * 2 - 1;
				if (kind === 'white') {
					raw[i] = w * 0.5;
				} else if (kind === 'pink') {
					// Paul Kellet's refined pink filter.
					b0 = 0.99886 * b0 + w * 0.0555179;
					b1 = 0.99332 * b1 + w * 0.0750759;
					b2 = 0.96900 * b2 + w * 0.1538520;
					b3 = 0.86650 * b3 + w * 0.3104856;
					b4 = 0.55000 * b4 + w * 0.5329522;
					b5 = -0.7616 * b5 - w * 0.0168980;
					raw[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11;
					b6 = w * 0.115926;
				} else {
					last = (last + 0.02 * w) / 1.02;
					raw[i] = last * 3.5;
				}
			}
			const data = buffer.getChannelData(ch);
			for (let i = 0; i < length; i++) {
				data[i] = i < fade ? raw[i] * (i / fade) + raw[length + i] * (1 - i / fade) : raw[i];
			}
		}
		return buffer;
	}
}

/** One sound: a graph into `out`, whose gain is the sound's level. */
abstract class Layer {
	readonly out: GainNode;
	protected readonly ctx: AudioContext;
	protected readonly bank: NoiseBank;
	#sources: AudioScheduledSourceNode[] = [];

	constructor(ctx: AudioContext, bank: NoiseBank, destination: AudioNode) {
		this.ctx = ctx;
		this.bank = bank;
		this.out = ctx.createGain();
		this.out.gain.value = 0;
		this.out.connect(destination);
	}

	/** Schedule this layer's events up to `horizon` (audio clock seconds). */
	tick(_now: number, _horizon: number): void { /* continuous layers have no events */ }

	dispose(): void {
		for (const s of this.#sources) {
			try { s.stop(); } catch { /* not started */ }
			s.disconnect();
		}
		this.#sources = [];
		this.out.disconnect();
	}

	/** Looping noise that plays for the layer's lifetime, from a random point. */
	protected noise(kind: NoiseKind): AudioBufferSourceNode {
		const src = this.ctx.createBufferSource();
		src.buffer = this.bank[kind];
		src.loop = true;
		src.start(0, Math.random() * NOISE_SECONDS);
		this.#sources.push(src);
		return src;
	}

	/** An oscillator that plays for the layer's lifetime. */
	protected oscillator(type: OscillatorType, frequency: number): OscillatorNode {
		const osc = this.ctx.createOscillator();
		osc.type = type;
		osc.frequency.value = frequency;
		osc.start();
		this.#sources.push(osc);
		return osc;
	}

	/** A short burst of noise starting at `t`, for events; it stops by itself. */
	protected burst(kind: NoiseKind, t: number, duration: number): AudioBufferSourceNode {
		const src = this.ctx.createBufferSource();
		src.buffer = this.bank[kind];
		// Longer than the buffer (a roll of thunder): loop it for that long.
		src.loop = duration > NOISE_SECONDS - 0.2;
		const offset = src.loop ? Math.random() * NOISE_SECONDS : Math.random() * (NOISE_SECONDS - duration - 0.1);
		src.start(t, offset, duration);
		return src;
	}

	protected filter(type: BiquadFilterType, frequency: number, q = 0.7): BiquadFilterNode {
		const f = this.ctx.createBiquadFilter();
		f.type = type;
		f.frequency.value = frequency;
		f.Q.value = q;
		return f;
	}

	protected gain(value: number): GainNode {
		const g = this.ctx.createGain();
		g.gain.value = value;
		return g;
	}

	protected pan(value: number): StereoPannerNode {
		const p = this.ctx.createStereoPanner();
		p.pan.value = value;
		return p;
	}

	/** Connect nodes in a row and return the last one. */
	protected chain(...nodes: AudioNode[]): AudioNode {
		for (let i = 0; i < nodes.length - 1; i++) nodes[i].connect(nodes[i + 1]);
		return nodes[nodes.length - 1];
	}
}

class NoiseLayer extends Layer {
	constructor(ctx: AudioContext, bank: NoiseBank, destination: AudioNode, kind: NoiseKind) {
		super(ctx, bank, destination);
		this.noise(kind).connect(this.out);
	}
}

/**
 * Gentle rain: a soft, dark patter in the distance and single drops close by.
 * The distant part stays quiet and rolled off at the top; a bright, loud
 * hiss is what makes rain sound like a downpour.
 */
class RainLayer extends Layer {
	#body = this.gain(0.85);
	#drops = this.gain(0.55);
	#nextDrop = 0;
	#nextSwell = 0;

	constructor(ctx: AudioContext, bank: NoiseBank, destination: AudioNode) {
		super(ctx, bank, destination);
		this.chain(this.#body, this.out);
		this.chain(this.noise('pink'), this.filter('highpass', 700), this.filter('lowpass', 4000), this.gain(0.28), this.#body);
		this.chain(this.#drops, this.out);
	}

	tick(now: number, horizon: number): void {
		this.#nextDrop = Math.max(this.#nextDrop, now);
		while (this.#nextDrop < horizon) {
			const t = this.#nextDrop;
			// Mostly small far drops, now and then a close one.
			const near = Math.random() < 0.15;
			const g = this.gain(0);
			g.gain.setValueAtTime(0, t);
			g.gain.linearRampToValueAtTime(near ? rand(0.4, 0.8) : rand(0.08, 0.3), t + 0.002);
			g.gain.exponentialRampToValueAtTime(0.001, t + (near ? rand(0.04, 0.07) : rand(0.015, 0.035)));
			const frequency = near ? rand(1200, 3000) : rand(2500, 5500);
			this.chain(this.burst('white', t, 0.08), this.filter('bandpass', frequency, rand(3, 8)), g, this.pan(rand(-0.9, 0.9)), this.#drops);
			this.#nextDrop += nextGap(9);
		}
		this.#nextSwell = Math.max(this.#nextSwell, now);
		if (this.#nextSwell < horizon) {
			this.#body.gain.setTargetAtTime(rand(0.75, 0.95), this.#nextSwell, 2);
			this.#nextSwell += rand(4, 8);
		}
	}
}

/** Distant thunder now and then: a dull crack rolling off into rumble. */
class ThunderLayer extends Layer {
	#next = 0;

	tick(now: number, horizon: number): void {
		if (!this.#next) this.#next = now + rand(4, 12);
		while (this.#next < horizon) {
			const t = Math.max(this.#next, now);
			const duration = rand(6, 10);
			const peak = rand(0.5, 1);
			const lp = this.filter('lowpass', 1000);
			lp.frequency.setValueAtTime(rand(600, 1200), t);
			lp.frequency.exponentialRampToValueAtTime(rand(90, 160), t + 2);
			const g = this.gain(0);
			const attack = rand(0.05, 0.5);
			g.gain.setValueAtTime(0, t);
			g.gain.linearRampToValueAtTime(peak, t + attack);
			g.gain.linearRampToValueAtTime(peak * 0.3, t + attack + 1.2);
			g.gain.linearRampToValueAtTime(peak * 0.6, t + attack + rand(1.8, 3));
			g.gain.exponentialRampToValueAtTime(0.001, t + duration);
			this.chain(this.burst('brown', t, duration), lp, g, this.pan(rand(-0.5, 0.5)), this.out);
			this.#next = t + rand(20, 60);
		}
	}
}

/**
 * Waves breaking on a beach. The filters stay put and only loudness moves:
 * a sweeping cutoff is what wind sounds like. Each wave swells as a broad,
 * full-band rush, breaks, and draws back as a long, fizzing wash of foam,
 * under a low surge and the faint surf further out.
 */
class WavesLayer extends Layer {
	#rush = this.gain(0);
	#surge = this.gain(0.1);
	#foam = this.gain(0);
	#flutter = this.gain(1);
	#next = 0;
	#nextFlutter = 0;

	constructor(ctx: AudioContext, bank: NoiseBank, destination: AudioNode) {
		super(ctx, bank, destination);
		this.chain(this.noise('pink'), this.filter('highpass', 250), this.filter('lowpass', 6500), this.#rush, this.out);
		this.chain(this.noise('brown'), this.filter('lowpass', 220), this.#surge, this.out);
		this.chain(this.noise('white'), this.filter('highpass', 2500), this.filter('lowpass', 9000), this.#flutter, this.#foam, this.out);
		this.chain(this.noise('pink'), this.filter('lowpass', 1200), this.gain(0.06), this.out);
	}

	tick(now: number, horizon: number): void {
		this.#next = Math.max(this.#next, now);
		while (this.#next < horizon) {
			const t = this.#next;
			const rise = rand(1.6, 2.6);
			const wash = rand(4, 6.5);
			const peak = rand(0.55, 1);
			const breakAt = t + rise;
			this.#rush.gain.setTargetAtTime(peak, t, rise / 2.5);
			this.#surge.gain.setTargetAtTime(0.25 + peak * 0.35, t, rise / 2);
			this.#foam.gain.setTargetAtTime(peak * 0.5, breakAt - 0.2, 0.25);
			this.#rush.gain.setTargetAtTime(0, breakAt, wash / 4);
			this.#surge.gain.setTargetAtTime(0.1, breakAt + 0.5, wash / 3);
			this.#foam.gain.setTargetAtTime(0, breakAt + 0.6, wash / 3);
			this.#next = breakAt + wash * rand(0.55, 0.85);
		}
		// The foam's grain: quick, small changes of its loudness.
		this.#nextFlutter = Math.max(this.#nextFlutter, now);
		while (this.#nextFlutter < horizon) {
			this.#flutter.gain.setTargetAtTime(rand(0.45, 1), this.#nextFlutter, 0.02);
			this.#nextFlutter += rand(0.04, 0.09);
		}
	}
}

/**
 * A brook: countless small bubbles over a soft rush of water. A bubble is a
 * short sine whose pitch rises as it closes; many of them, of all sizes, make
 * the babble (narrow resonances jumping about sound like croaking instead).
 */
class StreamLayer extends Layer {
	#bubbles = this.gain(0.5);
	#next = 0;

	constructor(ctx: AudioContext, bank: NoiseBank, destination: AudioNode) {
		super(ctx, bank, destination);
		this.chain(this.noise('pink'), this.filter('highpass', 200), this.filter('lowpass', 2500), this.gain(0.32), this.out);
		this.chain(this.noise('white'), this.filter('highpass', 4000), this.gain(0.025), this.out);
		this.chain(this.#bubbles, this.out);
	}

	tick(now: number, horizon: number): void {
		this.#next = Math.max(this.#next, now);
		while (this.#next < horizon) {
			const t = this.#next;
			// Small bubbles are many and high, large ones few and low.
			const size = Math.random() ** 2;
			const frequency = 2800 * Math.pow(600 / 2800, size);
			const duration = 0.012 + size * 0.035;
			const osc = this.ctx.createOscillator();
			osc.type = 'sine';
			osc.frequency.setValueAtTime(frequency, t);
			osc.frequency.exponentialRampToValueAtTime(frequency * rand(1.3, 1.8), t + duration);
			const g = this.gain(0);
			g.gain.setValueAtTime(0, t);
			g.gain.linearRampToValueAtTime(rand(0.05, 0.25) * (0.5 + size), t + 0.002);
			g.gain.exponentialRampToValueAtTime(0.001, t + duration);
			this.chain(osc, g, this.pan(rand(-0.7, 0.7)), this.#bubbles);
			osc.start(t);
			osc.stop(t + duration + 0.02);
			this.#next += nextGap(45);
		}
	}
}

/** Wind in gusts, its pitch wandering, with a faint whistle. */
class WindLayer extends Layer {
	#band = this.filter('bandpass', 500, 1.2);
	#whistle = this.filter('bandpass', 900, 12);
	#gust = this.gain(0.5);
	#next = 0;

	constructor(ctx: AudioContext, bank: NoiseBank, destination: AudioNode) {
		super(ctx, bank, destination);
		const pink = this.noise('pink');
		this.chain(pink, this.#band, this.#gust, this.out);
		this.chain(pink, this.#whistle, this.gain(0.08), this.#gust);
		this.chain(this.noise('brown'), this.filter('lowpass', 180), this.gain(0.35), this.out);
	}

	tick(now: number, horizon: number): void {
		this.#next = Math.max(this.#next, now);
		while (this.#next < horizon) {
			const t = this.#next;
			const tc = rand(0.8, 2.2);
			this.#band.frequency.setTargetAtTime(rand(250, 900), t, tc);
			this.#whistle.frequency.setTargetAtTime(rand(700, 1400), t, tc);
			this.#gust.gain.setTargetAtTime(rand(0.25, 1), t, rand(0.8, 2.5));
			this.#next = t + rand(1.5, 4);
		}
	}
}

/**
 * A campfire's crackles and the odd pop, nothing else. They come in spells:
 * the rate changes every second or two, from a few to a flurry.
 */
class FireLayer extends Layer {
	#rate = 7;
	#nextCrackle = 0;
	#nextPop = 0;
	#nextSpell = 0;

	#click(t: number, type: BiquadFilterType, frequency: number, duration: number, peak: number): void {
		const g = this.gain(0);
		g.gain.setValueAtTime(peak, t);
		g.gain.exponentialRampToValueAtTime(0.001, t + duration);
		this.chain(this.burst('white', t, duration + 0.01), this.filter(type, frequency, type === 'bandpass' ? 3 : 0.7), g, this.pan(rand(-0.6, 0.6)), this.out);
	}

	tick(now: number, horizon: number): void {
		this.#nextSpell = Math.max(this.#nextSpell, now);
		if (this.#nextSpell < horizon) {
			this.#rate = rand(3, 14);
			this.#nextSpell += rand(0.8, 2.5);
		}
		this.#nextCrackle = Math.max(this.#nextCrackle, now);
		while (this.#nextCrackle < horizon) {
			this.#click(this.#nextCrackle, 'highpass', rand(1500, 5000), rand(0.003, 0.012), rand(0.1, 0.7));
			this.#nextCrackle += nextGap(this.#rate);
		}
		this.#nextPop = Math.max(this.#nextPop, now);
		while (this.#nextPop < horizon) {
			this.#click(this.#nextPop, 'bandpass', rand(500, 1400), 0.03, rand(0.4, 1));
			this.#nextPop += nextGap(0.6);
		}
	}
}

/** Two crickets, left and right, chirping in short pulse groups. */
class CricketsLayer extends Layer {
	#insects: { gate: GainNode; next: number }[] = [];

	constructor(ctx: AudioContext, bank: NoiseBank, destination: AudioNode) {
		super(ctx, bank, destination);
		for (const [frequency, side] of [[4300, -0.6], [4750, 0.6]]) {
			const gate = this.gain(0);
			this.chain(this.oscillator('sine', frequency), gate, this.pan(side), this.out);
			this.#insects.push({ gate, next: 0 });
		}
	}

	tick(now: number, horizon: number): void {
		for (const insect of this.#insects) {
			insect.next = Math.max(insect.next, now + 0.01);
			while (insect.next < horizon) {
				const pulses = Math.random() < 0.5 ? 3 : 4;
				const g = insect.gate.gain;
				for (let k = 0; k < pulses; k++) {
					const start = insect.next + k * 0.045;
					g.setValueAtTime(0, start);
					g.linearRampToValueAtTime(0.5, start + 0.005);
					g.linearRampToValueAtTime(0, start + 0.022);
				}
				insect.next += pulses * 0.045 + rand(0.6, 1.4);
			}
		}
	}
}

/** Birds now and then: whistles, quick trills, a two-note call. */
class BirdsLayer extends Layer {
	#next = 0;

	#chirp(t: number, duration: number, from: number, to: number, peak: number, side: number): void {
		const osc = this.ctx.createOscillator();
		osc.type = 'sine';
		osc.frequency.setValueAtTime(from, t);
		osc.frequency.exponentialRampToValueAtTime(to, t + duration);
		const vibrato = this.ctx.createOscillator();
		vibrato.frequency.value = rand(25, 40);
		const depth = this.gain(from * 0.02);
		this.chain(vibrato, depth);
		depth.connect(osc.frequency);
		const g = this.gain(0);
		g.gain.setValueAtTime(0, t);
		g.gain.linearRampToValueAtTime(peak, t + duration * 0.2);
		g.gain.exponentialRampToValueAtTime(0.001, t + duration);
		this.chain(osc, g, this.pan(side), this.out);
		osc.start(t);
		vibrato.start(t);
		osc.stop(t + duration + 0.05);
		vibrato.stop(t + duration + 0.05);
	}

	tick(now: number, horizon: number): void {
		if (!this.#next) this.#next = now + rand(0.5, 3);
		while (this.#next < horizon) {
			let t = Math.max(this.#next, now);
			const side = rand(-0.8, 0.8);
			const kind = Math.floor(Math.random() * 3);
			if (kind === 0) {
				const notes = 2 + Math.floor(Math.random() * 3);
				for (let i = 0; i < notes; i++) {
					const d = rand(0.12, 0.25);
					const f = rand(2000, 3200);
					this.#chirp(t, d, f, f * rand(1.2, 1.6), rand(0.5, 1), side);
					t += d + rand(0.08, 0.2);
				}
			} else if (kind === 1) {
				const notes = 6 + Math.floor(Math.random() * 7);
				let f = rand(3500, 4500);
				for (let i = 0; i < notes; i++) {
					this.#chirp(t, 0.035, f, f * 0.9, 0.7, side);
					t += 0.055;
					f *= 0.98;
				}
			} else {
				this.#chirp(t, 0.2, 2700, 2500, 0.8, side);
				t += 0.32;
				this.#chirp(t, 0.22, 2100, 1950, 0.8, side);
				t += 0.22;
			}
			this.#next = t + rand(1.5, 6);
		}
	}
}

function createLayer(id: SoundId, ctx: AudioContext, bank: NoiseBank, destination: AudioNode): Layer {
	switch (id) {
		case 'rain': return new RainLayer(ctx, bank, destination);
		case 'thunder': return new ThunderLayer(ctx, bank, destination);
		case 'waves': return new WavesLayer(ctx, bank, destination);
		case 'stream': return new StreamLayer(ctx, bank, destination);
		case 'wind': return new WindLayer(ctx, bank, destination);
		case 'fire': return new FireLayer(ctx, bank, destination);
		case 'crickets': return new CricketsLayer(ctx, bank, destination);
		case 'birds': return new BirdsLayer(ctx, bank, destination);
		default: return new NoiseLayer(ctx, bank, destination, id);
	}
}

export class AmbientEngine {
	#ctx: AudioContext | null = null;
	#master: GainNode | null = null;
	#bank: NoiseBank | null = null;
	#layers = new Map<SoundId, Layer>();
	#disposeTimers = new Map<SoundId, ReturnType<typeof setTimeout>>();
	#levels: Levels = {};
	#volume = 0.8;
	#playing = false;
	#timer: ReturnType<typeof setInterval> | null = null;
	#suspendTimer: ReturnType<typeof setTimeout> | null = null;

	get playing(): boolean { return this.#playing; }
	get levels(): Levels { return { ...this.#levels }; }
	get volume(): number { return this.#volume; }

	/** Whether any sound has a level above zero. */
	get audible(): boolean {
		return SOUNDS.some((s) => (this.#levels[s.id] || 0) > 0);
	}

	/** Start (or resume) playing, fading in. Call from a user's click the first time. */
	async play(fadeSeconds = 1.5): Promise<void> {
		if (!this.#ctx) {
			const ctx = new AudioContext();
			const master = ctx.createGain();
			master.gain.value = 0;
			const compressor = ctx.createDynamicsCompressor();
			compressor.threshold.value = -14;
			compressor.ratio.value = 3;
			compressor.attack.value = 0.01;
			compressor.release.value = 0.3;
			master.connect(compressor);
			compressor.connect(ctx.destination);
			this.#ctx = ctx;
			this.#master = master;
			this.#bank = new NoiseBank(ctx);
		}
		if (this.#suspendTimer) {
			clearTimeout(this.#suspendTimer);
			this.#suspendTimer = null;
		}
		await this.#ctx.resume();
		this.#playing = true;
		for (const s of SOUNDS) this.#applyLevel(s.id);
		this.#rampMaster(this.#volume * this.#volume, fadeSeconds);
		this.#tick();
		if (!this.#timer) this.#timer = setInterval(() => this.#tick(), TICK_MS);
	}

	/** Fade out and stop; the layers are let go once the fade is over. */
	pause(fadeSeconds = 0.8): void {
		if (!this.#playing || !this.#ctx) return;
		this.#playing = false;
		this.#rampMaster(0, fadeSeconds);
		if (this.#timer) {
			clearInterval(this.#timer);
			this.#timer = null;
		}
		const ctx = this.#ctx;
		this.#suspendTimer = setTimeout(() => {
			this.#suspendTimer = null;
			if (this.#playing) return;
			for (const id of [...this.#layers.keys()]) this.#disposeLayer(id);
			ctx.suspend().catch(() => { /* closed meanwhile */ });
		}, fadeSeconds * 1000 + 100);
	}

	setLevel(id: SoundId, level: number): void {
		this.#levels[id] = Math.max(0, Math.min(1, level));
		if (this.#playing) this.#applyLevel(id);
	}

	setLevels(levels: Levels): void {
		for (const s of SOUNDS) this.setLevel(s.id, levels[s.id] || 0);
	}

	setVolume(volume: number): void {
		this.#volume = Math.max(0, Math.min(1, volume));
		if (this.#playing) this.#rampMaster(this.#volume * this.#volume, 0.1);
	}

	async destroy(): Promise<void> {
		this.#playing = false;
		if (this.#timer) clearInterval(this.#timer);
		if (this.#suspendTimer) clearTimeout(this.#suspendTimer);
		this.#timer = null;
		this.#suspendTimer = null;
		for (const id of [...this.#layers.keys()]) this.#disposeLayer(id);
		const ctx = this.#ctx;
		this.#ctx = null;
		this.#master = null;
		this.#bank = null;
		await ctx?.close().catch(() => { /* already closed */ });
	}

	#applyLevel(id: SoundId): void {
		const ctx = this.#ctx;
		if (!ctx || !this.#master || !this.#bank) return;
		const level = this.#levels[id] || 0;
		let layer = this.#layers.get(id);
		if (level > 0) {
			const pending = this.#disposeTimers.get(id);
			if (pending) {
				clearTimeout(pending);
				this.#disposeTimers.delete(id);
			}
			if (!layer) {
				layer = createLayer(id, ctx, this.#bank, this.#master);
				this.#layers.set(id, layer);
				layer.tick(ctx.currentTime, ctx.currentTime + LOOKAHEAD_S);
			}
			layer.out.gain.setTargetAtTime(level * level * TRIM[id], ctx.currentTime, LEVEL_TIME_CONSTANT);
		} else if (layer && !this.#disposeTimers.has(id)) {
			layer.out.gain.setTargetAtTime(0, ctx.currentTime, LEVEL_TIME_CONSTANT);
			this.#disposeTimers.set(id, setTimeout(() => {
				this.#disposeTimers.delete(id);
				if ((this.#levels[id] || 0) === 0) this.#disposeLayer(id);
			}, DISPOSE_AFTER_MS));
		}
	}

	#disposeLayer(id: SoundId): void {
		const pending = this.#disposeTimers.get(id);
		if (pending) {
			clearTimeout(pending);
			this.#disposeTimers.delete(id);
		}
		this.#layers.get(id)?.dispose();
		this.#layers.delete(id);
	}

	#rampMaster(value: number, seconds: number): void {
		const ctx = this.#ctx;
		const g = this.#master?.gain;
		if (!ctx || !g) return;
		const now = ctx.currentTime;
		g.cancelScheduledValues(now);
		g.setValueAtTime(g.value, now);
		g.linearRampToValueAtTime(value, now + Math.max(0.01, seconds));
	}

	#tick(): void {
		const ctx = this.#ctx;
		if (!ctx || !this.#playing) return;
		const now = ctx.currentTime;
		for (const layer of this.#layers.values()) layer.tick(now, now + LOOKAHEAD_S);
	}
}
