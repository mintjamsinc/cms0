/**
 * Audio engine for the Radio app.
 *
 * A live stream is played by an HTMLAudioElement whose src is the station's
 * stream URL; the browser decodes MP3 / AAC / Ogg itself and no server sits
 * in between. Two elements are kept:
 *
 * - the CORS element (`crossOrigin = "anonymous"`) is routed through the Web
 *   Audio API so an AnalyserNode can feed the spectrum view. That only works
 *   when the station answers with CORS headers; otherwise the load fails.
 * - the plain element has no crossOrigin and is not connected to Web Audio,
 *   so it plays any station, just without the spectrum.
 *
 * A station is first tried on the CORS element and, if that fails, on the
 * plain one. (A media element can only be connected to Web Audio once, and a
 * non-CORS stream through such a connection is silenced by the browser, which
 * is why the fallback needs its own element.)
 *
 * HLS stations (an .m3u8 playlist of short segments) are played natively
 * where the browser can (Safari), and otherwise through hls.js, which
 * downloads the segments itself and feeds them to the CORS element through
 * Media Source Extensions. hls.js fetches with XHR, so it needs the station
 * to allow cross-origin reads; there is no non-CORS fallback for HLS, but
 * the spectrum view works whenever HLS plays (the element's source is then a
 * same-origin MediaSource).
 *
 * Nothing here is reactive: the app keeps the player outside its reactive
 * data (ichigo.js deep-proxies stored objects) and listens to `onState`.
 */

import Hls, { ErrorTypes, Events, type ErrorData } from 'hls.js/light';
import type { Station } from './library.js';

export type PlayerState = 'idle' | 'loading' | 'playing' | 'stopped' | 'error';
export type PlayerErrorKind = 'generic' | 'network' | 'unsupported' | 'mixedContent' | 'hls' | 'hlsNetwork';

/** Whether a station streams HLS (directory flag, or an .m3u8 address). */
export function isHlsStation(station: Station): boolean {
	return !!station.hls || /\.m3u8(\?|#|$)/i.test(station.url || '');
}

export interface PlayerStateDetail {
	station: Station | null;
	error?: PlayerErrorKind;
	/** True while the spectrum analyser has signal for the current station. */
	analyser: boolean;
}

export interface PlayerEvents {
	onState(state: PlayerState, detail: PlayerStateDetail): void;
}

interface PlayError {
	kind: PlayerErrorKind;
}

const CONNECT_TIMEOUT_MS = 15000;
const BAR_COUNT = 48;

export class RadioPlayer {
	#events: PlayerEvents;
	#corsEl: HTMLAudioElement;
	#plainEl: HTMLAudioElement;
	#active: HTMLAudioElement | null = null;
	#station: Station | null = null;
	#attempt = 0;
	#volume = 0.8;
	#muted = false;

	// hls.js instance while an HLS station plays through it, and whether a
	// mid-stream recovery was already spent (a second fatal error stops).
	#hls: Hls | null = null;
	#hlsRecoveries = 0;

	#ctx: AudioContext | null = null;
	#analyser: AnalyserNode | null = null;
	#spectrum: Uint8Array<ArrayBuffer> | null = null;

	#canvas: HTMLCanvasElement | null = null;
	#raf = 0;
	#colors = { from: '#8b5cf6', to: '#06b6d4', idle: 'rgba(128, 128, 128, 0.25)' };

	constructor(events: PlayerEvents) {
		this.#events = events;
		this.#corsEl = this.#createElement(true);
		this.#plainEl = this.#createElement(false);
	}

	get station(): Station | null {
		return this.#station;
	}

	get analyserAvailable(): boolean {
		return this.#active === this.#corsEl && !!this.#analyser;
	}

	get volume(): number {
		return this.#volume;
	}

	get muted(): boolean {
		return this.#muted;
	}

	setVolume(volume: number): void {
		this.#volume = Math.min(1, Math.max(0, volume));
		this.#corsEl.volume = this.#volume;
		this.#plainEl.volume = this.#volume;
	}

	setMuted(muted: boolean): void {
		this.#muted = muted;
		this.#corsEl.muted = muted;
		this.#plainEl.muted = muted;
	}

	/** Canvas the spectrum is drawn on; null detaches. */
	attachCanvas(canvas: HTMLCanvasElement | null): void {
		this.#canvas = canvas;
		if (canvas) {
			this.#startDrawing();
		} else {
			this.#stopDrawing();
		}
	}

	setColors(colors: Partial<{ from: string; to: string; idle: string }>): void {
		this.#colors = { ...this.#colors, ...colors };
	}

	async play(station: Station): Promise<void> {
		const attempt = ++this.#attempt;
		this.#detach();
		this.#station = station;

		const url = station.url;
		if (window.location.protocol === 'https:' && /^http:/i.test(url)) {
			this.#fail('mixedContent');
			return;
		}
		const hls = isHlsStation(station);
		const nativeHls = hls && !!this.#plainEl.canPlayType('application/vnd.apple.mpegurl');
		if (hls && !nativeHls && !Hls.isSupported()) {
			this.#fail('hls');
			return;
		}

		this.#emit('loading');

		if (hls && !nativeHls) {
			try {
				await this.#tryHls(this.#corsEl, url, attempt);
				if (attempt !== this.#attempt) return;
				this.#active = this.#corsEl;
				await this.#connectAnalyser();
			} catch (err) {
				if (attempt !== this.#attempt) return;
				this.#fail((err as PlayError).kind || 'hlsNetwork');
				return;
			}
			this.#updateMediaSession();
			this.#emit('playing');
			return;
		}

		try {
			await this.#tryElement(this.#corsEl, url, attempt);
			if (attempt !== this.#attempt) return;
			this.#active = this.#corsEl;
			await this.#connectAnalyser();
		} catch (err) {
			if (attempt !== this.#attempt) return;
			try {
				await this.#tryElement(this.#plainEl, url, attempt);
				if (attempt !== this.#attempt) return;
				this.#active = this.#plainEl;
			} catch (err2) {
				if (attempt !== this.#attempt) return;
				// Native HLS reports an unreachable or refused playlist as a
				// format error; say what is actually likely.
				this.#fail(hls ? 'hlsNetwork' : ((err2 as PlayError).kind || 'generic'));
				return;
			}
		}

		this.#updateMediaSession();
		this.#emit('playing');
	}

	stop(): void {
		this.#attempt++;
		this.#detach();
		if ('mediaSession' in navigator) {
			navigator.mediaSession.playbackState = 'paused';
		}
		this.#emit('stopped');
	}

	/** Updates the lock-screen / media-key display with the current track. */
	setNowPlaying(title: string): void {
		this.#updateMediaSession(title);
	}

	destroy(): void {
		this.#attempt++;
		this.#detach();
		this.#stopDrawing();
		this.#station = null;
		if (this.#ctx) {
			this.#ctx.close().catch(() => { /* ignored */ });
			this.#ctx = null;
			this.#analyser = null;
		}
	}

	// -------------------------------------------------------------------------

	#createElement(cors: boolean): HTMLAudioElement {
		const el = new Audio();
		el.preload = 'none';
		if (cors) {
			el.crossOrigin = 'anonymous';
		}
		el.volume = this.#volume;
		// Events after a successful start: a live stream that drops fires
		// error or ended; rebuffering shows as waiting → playing.
		el.addEventListener('waiting', () => {
			if (this.#active === el) this.#emit('loading');
		});
		el.addEventListener('playing', () => {
			if (this.#active === el) this.#emit('playing');
		});
		el.addEventListener('ended', () => {
			if (this.#active === el) this.stop();
		});
		el.addEventListener('error', () => {
			if (this.#active === el) {
				const kind = this.#errorKind(el);
				this.#active = null;
				this.#fail(kind);
			}
		});
		return el;
	}

	#tryElement(el: HTMLAudioElement, url: string, attempt: number): Promise<void> {
		return new Promise<void>((resolve, reject) => {
			let settled = false;
			const finish = (fn: () => void) => {
				if (settled) return;
				settled = true;
				clearTimeout(timer);
				el.removeEventListener('playing', onPlaying);
				el.removeEventListener('error', onError);
				fn();
			};
			const onPlaying = () => finish(resolve);
			const onError = () => finish(() => reject({ kind: this.#errorKind(el) } as PlayError));
			const timer = setTimeout(() => {
				finish(() => {
					this.#reset(el);
					reject({ kind: 'network' } as PlayError);
				});
			}, CONNECT_TIMEOUT_MS);

			el.addEventListener('playing', onPlaying);
			el.addEventListener('error', onError);
			el.src = url;
			el.load();
			el.play().catch((err: DOMException) => {
				if (attempt !== this.#attempt) {
					finish(() => reject({ kind: 'generic' } as PlayError));
					return;
				}
				// NotSupportedError: the element gave up before an error event;
				// AbortError: superseded by a newer load (handled by attempt).
				finish(() => reject({ kind: err?.name === 'NotSupportedError' ? 'unsupported' : 'generic' } as PlayError));
			});
		});
	}

	/**
	 * Starts an HLS stream through hls.js on the given element. Resolves on
	 * the first `playing`; rejects on a fatal error before that or on timeout.
	 * Once playing, fatal errors get one recovery attempt each (reload for
	 * network, decoder reset for media) before playback fails.
	 */
	#tryHls(el: HTMLAudioElement, url: string, attempt: number): Promise<void> {
		return new Promise<void>((resolve, reject) => {
			const hls = new Hls({
				// A live radio stream: stay near the live edge, keep the
				// back buffer small (nothing seeks back in a radio stream).
				lowLatencyMode: false,
				backBufferLength: 30,
				manifestLoadingMaxRetry: 2,
				levelLoadingMaxRetry: 2,
				fragLoadingMaxRetry: 3,
			});
			this.#hls = hls;
			this.#hlsRecoveries = 0;

			let started = false;
			const settle = (fn: () => void) => {
				if (started) return;
				started = true;
				clearTimeout(timer);
				el.removeEventListener('playing', onPlaying);
				fn();
			};
			const onPlaying = () => settle(resolve);
			const timer = setTimeout(() => {
				settle(() => reject({ kind: 'hlsNetwork' } as PlayError));
			}, CONNECT_TIMEOUT_MS);

			hls.on(Events.ERROR, (_event, data: ErrorData) => {
				if (!data.fatal || this.#hls !== hls) return;
				if (!started) {
					settle(() => reject({ kind: data.type === ErrorTypes.MEDIA_ERROR ? 'unsupported' : 'hlsNetwork' } as PlayError));
					return;
				}
				// Playing already: one recovery per error type, then give up.
				if (this.#hlsRecoveries < 1 && data.type === ErrorTypes.NETWORK_ERROR) {
					this.#hlsRecoveries++;
					hls.startLoad();
					return;
				}
				if (this.#hlsRecoveries < 1 && data.type === ErrorTypes.MEDIA_ERROR) {
					this.#hlsRecoveries++;
					hls.recoverMediaError();
					return;
				}
				if (this.#active === el) {
					this.#active = null;
					this.#fail(data.type === ErrorTypes.MEDIA_ERROR ? 'unsupported' : 'hlsNetwork');
				}
			});
			// A fragment that loads again after a recovery clears the budget,
			// so a long session is not stopped by two unrelated hiccups.
			hls.on(Events.FRAG_BUFFERED, () => {
				this.#hlsRecoveries = 0;
			});
			hls.on(Events.MANIFEST_PARSED, () => {
				if (attempt !== this.#attempt || this.#hls !== hls) return;
				el.play().catch((err: DOMException) => {
					if (attempt !== this.#attempt) return;
					settle(() => reject({ kind: err?.name === 'NotSupportedError' ? 'unsupported' : 'generic' } as PlayError));
				});
			});

			el.addEventListener('playing', onPlaying);
			hls.attachMedia(el);
			hls.loadSource(url);
		});
	}

	#destroyHls(): void {
		if (this.#hls) {
			try {
				this.#hls.destroy();
			} catch { /* ignored */ }
			this.#hls = null;
		}
	}

	#errorKind(el: HTMLAudioElement): PlayerErrorKind {
		const code = el.error?.code;
		if (code === MediaError.MEDIA_ERR_NETWORK) return 'network';
		if (code === MediaError.MEDIA_ERR_DECODE || code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) return 'unsupported';
		return 'generic';
	}

	#reset(el: HTMLAudioElement): void {
		try {
			el.pause();
		} catch { /* ignored */ }
		el.removeAttribute('src');
		el.load();
	}

	#detach(): void {
		this.#active = null;
		// hls.js first: destroying it detaches its MediaSource from the element.
		this.#destroyHls();
		this.#reset(this.#corsEl);
		this.#reset(this.#plainEl);
	}

	#fail(kind: PlayerErrorKind): void {
		this.#detach();
		if ('mediaSession' in navigator) {
			navigator.mediaSession.playbackState = 'none';
		}
		this.#emit('error', kind);
	}

	#emit(state: PlayerState, error?: PlayerErrorKind): void {
		this.#events.onState(state, {
			station: this.#station,
			error,
			analyser: state === 'playing' && this.analyserAvailable,
		});
	}

	async #connectAnalyser(): Promise<void> {
		if (!this.#ctx) {
			const Ctor = window.AudioContext || (window as any).webkitAudioContext;
			if (!Ctor) return;
			try {
				const ctx: AudioContext = new Ctor();
				const source = ctx.createMediaElementSource(this.#corsEl);
				const analyser = ctx.createAnalyser();
				analyser.fftSize = 256;
				analyser.smoothingTimeConstant = 0.82;
				source.connect(analyser);
				analyser.connect(ctx.destination);
				this.#ctx = ctx;
				this.#analyser = analyser;
				this.#spectrum = new Uint8Array(new ArrayBuffer(analyser.frequencyBinCount));
			} catch (err) {
				console.warn('[Radio] Web Audio is unavailable; playing without the spectrum view.', err);
				return;
			}
		}
		if (this.#ctx.state === 'suspended') {
			await this.#ctx.resume().catch(() => { /* stays suspended until the next gesture */ });
		}
	}

	#updateMediaSession(title?: string): void {
		if (!('mediaSession' in navigator) || !this.#station) return;
		const station = this.#station;
		try {
			navigator.mediaSession.metadata = new MediaMetadata({
				title: title || station.name,
				artist: title ? station.name : (station.tags.slice(0, 3).join(' · ') || ''),
				artwork: station.favicon ? [{ src: station.favicon }] : [],
			});
			navigator.mediaSession.playbackState = 'playing';
		} catch { /* ignored */ }
	}

	// -------------------------------------------------------------------------
	// Spectrum view

	#startDrawing(): void {
		if (this.#raf) return;
		const frame = () => {
			this.#raf = 0;
			if (!this.#canvas) return;
			this.#draw();
			this.#raf = requestAnimationFrame(frame);
		};
		this.#raf = requestAnimationFrame(frame);
	}

	#stopDrawing(): void {
		if (this.#raf) {
			cancelAnimationFrame(this.#raf);
			this.#raf = 0;
		}
	}

	#draw(): void {
		const canvas = this.#canvas;
		if (!canvas) return;
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
		const barWidth = (width - gap * (BAR_COUNT - 1)) / BAR_COUNT;
		const live = this.analyserAvailable && this.#active && !this.#active.paused;

		if (live && this.#analyser && this.#spectrum) {
			this.#analyser.getByteFrequencyData(this.#spectrum);
			const gradient = g.createLinearGradient(0, 0, width, 0);
			gradient.addColorStop(0, this.#colors.from);
			gradient.addColorStop(1, this.#colors.to);
			g.fillStyle = gradient;
			// Only the lower ~70% of the bins carry audible energy for music;
			// map the bars onto that range so the view is not half empty.
			const usable = Math.floor(this.#spectrum.length * 0.7);
			for (let i = 0; i < BAR_COUNT; i++) {
				const bin = Math.floor((i / BAR_COUNT) * usable);
				const value = this.#spectrum[bin] / 255;
				const h = Math.max(2, value * height);
				const x = i * (barWidth + gap);
				g.beginPath();
				g.roundRect(x, height - h, barWidth, h, barWidth / 2);
				g.fill();
			}
			return;
		}

		g.fillStyle = this.#colors.idle;
		for (let i = 0; i < BAR_COUNT; i++) {
			const x = i * (barWidth + gap);
			g.beginPath();
			g.roundRect(x, height - 3, barWidth, 3, 1.5);
			g.fill();
		}
	}
}
