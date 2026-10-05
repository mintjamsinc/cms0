/**
 * Reversi Application
 *
 * Reversi against the computer (four levels) or between two people at the
 * same desktop. The rules live in core.ts, the computer player in ai.ts
 * (run in ai-worker.js), the board themes and disc faces in looks.ts and the
 * effects in lib/effects.ts. Each seat is either a person or the computer,
 * so another kind of seat (a player on another desktop) can be added without
 * touching the rules or the board.
 *
 * The game in progress and the settings are kept per user in the local
 * webtop database; a game is saved as its move list and replayed on launch.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import { ApplicationInstance } from "../../services/webtop-service.js";
import { initUi } from "../../ui/index.js";
import { createShellPopupAdapter } from "../../ui/shell-popup-adapter.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";
import { Effects } from '../../lib/effects.js';
import {
	ReversiGame,
	BLACK,
	WHITE,
	EMPTY,
	legalMoves,
	countDiscs,
	winner,
	toCoord,
	fromCoord,
	initialPosition,
	type Cell,
	type MoveResult,
	type Player,
} from './core.js';
import { AiClient } from './ai-client.js';
import { type AiLevel } from './ai.js';
import { BOARD_THEMES, DISC_FACES, DEFAULT_THEME, DEFAULT_FACES, findTheme, findFace } from './looks.js';

type Mode = 'ai' | 'local';
type Side = 'black' | 'white' | 'random';

interface Seat {
	kind: 'human' | 'ai';
	level: AiLevel;
	/** Disc face id (looks.ts). */
	face: string;
}

interface Settings {
	mode: Mode;
	level: AiLevel;
	side: Side;
	/** Faces of the first and second player in a game between two people. */
	faces: [string, string];
	theme: string;
	hints: boolean;
}

interface SavedGame {
	size: number;
	moves: string[];
	seats: [Seat, Seat];
}

interface SavedState {
	version: 1;
	settings: Settings;
	game: SavedGame | null;
}

interface GameResult {
	winner: Cell;
	black: number;
	white: number;
}

const APP_ID = 'reversi';
const STATE_KEY = 'state';
const BOARD_SIZE = 8;
const LEVELS: AiLevel[] = [1, 2, 3, 4];
/** The computer never answers faster than this, so its move can be followed. */
const MIN_THINK_MS = 450;
const NOTICE_MS = 1800;
const FLIP_STAGGER_MS = 70;

// Kept outside reactive data: ichigo.js wraps stored objects in deep
// Proxies, which the game history, the worker and the canvas do not need.
let game: ReversiGame | null = null;
let fx: Effects | null = null;
let ai: AiClient | null = null;
// Bumped by a new game or an undo; a running animation or an AI answer
// from an older generation is dropped.
let generation = 0;
let noticeTimer: ReturnType<typeof setTimeout> | null = null;
let saveTimer: ReturnType<typeof setTimeout> | null = null;

function defaultSettings(): Settings {
	return { mode: 'ai', level: 2, side: 'black', faces: [...DEFAULT_FACES], theme: DEFAULT_THEME, hints: true };
}

function normalizeSettings(value: any): Settings {
	const s = defaultSettings();
	if (!value || typeof value !== 'object') return s;
	if (value.mode === 'ai' || value.mode === 'local') s.mode = value.mode;
	if (LEVELS.includes(value.level)) s.level = value.level;
	if (['black', 'white', 'random'].includes(value.side)) s.side = value.side;
	if (Array.isArray(value.faces) && value.faces.length === 2 && value.faces[0] !== value.faces[1] &&
		value.faces.every((f: unknown) => DISC_FACES.some(d => d.id === f))) {
		s.faces = [value.faces[0], value.faces[1]];
	}
	if (BOARD_THEMES.some(t => t.id === value.theme)) s.theme = value.theme;
	if (typeof value.hints === 'boolean') s.hints = value.hints;
	return s;
}

function isSeat(value: any): value is Seat {
	return !!value && (value.kind === 'human' || value.kind === 'ai') && LEVELS.includes(value.level) &&
		DISC_FACES.some(d => d.id === value.face);
}

function delay(ms: number): Promise<void> {
	return new Promise(resolve => setTimeout(resolve, ms));
}

function cellElement(index: number): HTMLElement | null {
	return document.querySelector(`.rv-cell[data-i="${index}"]`);
}

function discElement(index: number): HTMLElement | null {
	return cellElement(index)?.querySelector('.rv-disc') || null;
}

/** Squares between two indexes, counted the way a king moves. */
function distance(a: number, b: number): number {
	const ar = Math.floor(a / BOARD_SIZE);
	const br = Math.floor(b / BOARD_SIZE);
	return Math.max(Math.abs(ar - br), Math.abs((a % BOARD_SIZE) - (b % BOARD_SIZE)));
}

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			// Reactive Localization snapshot — see composables/use-localization.ts.
			localization: createLocalizationSnapshot(),
			isReady: false,

			settings: defaultSettings(),

			// The board as shown. It follows the game, but a move's discs turn
			// over one by one while it animates.
			size: BOARD_SIZE,
			// The opening position until a game starts, so the board is not empty
			// behind the new game dialog.
			cells: initialPosition(BOARD_SIZE).cells,
			turn: BLACK as Player,
			over: false,
			ply: 0,
			counts: { black: 2, white: 2 },
			lastMove: -1,
			hintMap: {} as Record<number, boolean>,
			seats: [
				{ kind: 'human', level: 2, face: DEFAULT_FACES[0] },
				{ kind: 'ai', level: 2, face: DEFAULT_FACES[1] },
			] as [Seat, Seat],
			hasGame: false,

			busy: false,
			thinking: false,
			notice: '',
			result: null as GameResult | null,

			dialog: {
				visible: false,
				mode: 'ai' as Mode,
				level: 2 as AiLevel,
				side: 'black' as Side,
				faces: [...DEFAULT_FACES] as [string, string],
				theme: DEFAULT_THEME,
			},
		};
	},
	computed: {
		stageStyle(): Record<string, string> {
			return { ...findTheme(this.settings.theme).vars };
		},
		boardStyle(): Record<string, string> {
			return { '--rv-size': String(this.size) };
		},
		mode(): Mode {
			return this.seats[0].kind === 'human' && this.seats[1].kind === 'human' ? 'local' : 'ai';
		},
		isHumanTurn(): boolean {
			return this.hasGame && !this.over && this.seats[this.turn - 1].kind === 'human';
		},
		canUndo(): boolean {
			if (!this.hasGame || (this.busy && !this.thinking)) return false;
			// Against the computer an undo goes back to your own last move.
			const computerOpened = this.seats[0].kind === 'ai';
			return this.ply > (computerOpened ? 1 : 0);
		},
		seatCards(): { player: Player; name: string; role: string; count: number; active: boolean; thinking: boolean; style: Record<string, string> }[] {
			return ([BLACK, WHITE] as Player[]).map((player) => {
				const seat = this.seats[player - 1];
				let role = player === BLACK ?
					this.t('app.reversi.seat.first', undefined, 'Moves first') :
					this.t('app.reversi.seat.second', undefined, 'Moves second');
				// The panel is narrow: the computer's level goes on the second line.
				if (seat.kind === 'ai') role += ` · ${this.levelLabel(seat.level)}`;
				return {
					player,
					name: seat.kind === 'ai' ? this.t('app.reversi.seat.computerShort', undefined, 'Computer') : this.seatName(player),
					role,
					count: player === BLACK ? this.counts.black : this.counts.white,
					active: this.hasGame && !this.over && this.turn === player,
					thinking: this.thinking && this.turn === player && seat.kind === 'ai',
					style: this.faceStyle(seat.face),
				};
			});
		},
		statusText(): string {
			if (!this.hasGame) return '';
			if (this.over) return this.resultTitle;
			if (this.thinking) return this.t('app.reversi.status.thinking', undefined, 'Thinking…');
			return this.t('app.reversi.status.turn', { name: this.seatName(this.turn) }, "{name}'s turn");
		},
		resultTitle(): string {
			const r = this.result;
			if (!r) return '';
			if (r.winner === EMPTY) return this.t('app.reversi.result.draw', undefined, "It's a draw");
			const seat = this.seats[r.winner - 1];
			if (this.mode === 'ai') {
				return seat.kind === 'human' ?
					this.t('app.reversi.result.youWin', undefined, 'You win!') :
					this.t('app.reversi.result.youLose', undefined, 'The computer wins');
			}
			return this.t('app.reversi.result.wins', { name: this.seatName(r.winner) }, '{name} wins!');
		},
		levelOptions(): { level: AiLevel; label: string; pips: number[] }[] {
			const names: Record<AiLevel, string> = { 1: 'Chick', 2: 'Songbird', 3: 'Owl', 4: 'Hawk' };
			return LEVELS.map(level => ({
				level,
				label: this.t(`app.reversi.level.${level}`, undefined, names[level]),
				pips: LEVELS.slice(0, level),
			}));
		},
		themeOptions(): { id: string; label: string; style: Record<string, string> }[] {
			return BOARD_THEMES.map(theme => ({
				id: theme.id,
				label: this.t(`app.reversi.board.${theme.id}`, undefined, theme.label),
				style: {
					background: theme.vars['--rv-cell'],
					boxShadow: `inset 0 0 0 4px ${theme.vars['--rv-frame']}, inset 0 0 0 5px ${theme.vars['--rv-frame-edge']}`,
				},
			}));
		},
		faceOptions(): { id: string; label: string; style: Record<string, string> }[] {
			return DISC_FACES.map(face => ({
				id: face.id,
				label: this.t(`app.reversi.disc.${face.id}`, undefined, face.label),
				style: this.faceStyle(face.id),
			}));
		},
	},
	methods: {
		/** Reactive i18n lookup; repaints on language change. */
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},

		onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) {
					return;
				}
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (instance: ApplicationInstance) => {
				vm.instance = this.$markRaw(instance);
				refreshLocalization(vm.localization, vm.instance);

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				// --- Readiness gate --- (see index.html)
				try {
					await initUi({ popupAdapter: createShellPopupAdapter(instance) });
				} catch (e) {
					console.warn('[Reversi] Failed to load component templates:', e);
				}

				fx = new Effects();
				ai = new AiClient(new URL('./ai-worker.js?v=__BUILD_VERSION__', import.meta.url));

				instance.setBeforeCloseCallback(async () => {
					await vm.flushSave();
					vm.dispose();
					return true;
				});

				const saved = await vm.loadState();
				vm.settings = normalizeSettings(saved?.settings);

				vm.isReady = true;
				await new Promise<void>((resolve) => vm.$nextTick(() => resolve()));

				this.$nextTick(() => {
					instance.notifyLaunched();
				});

				if (!vm.resumeGame(saved?.game)) {
					vm.openNewGame();
				}
			};
		},
		onUnmount() {
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
			}
			this.dispose();
		},
		dispose() {
			generation++;
			if (noticeTimer) clearTimeout(noticeTimer);
			ai?.destroy();
			ai = null;
			fx?.destroy();
			fx = null;
		},

		// =====================================================================
		// Window controls
		// =====================================================================

		onMinimizeWindow() {
			this.instance?.minimize();
		},
		onToggleMaximizeWindow() {
			this.instance?.toggleMaximize();
		},
		onCloseWindow() {
			this.instance?.requestClose();
		},

		// =====================================================================
		// Starting, resuming and undoing
		// =====================================================================

		openNewGame() {
			const s = this.settings;
			this.dialog = {
				visible: true,
				mode: s.mode,
				level: s.level,
				side: s.side,
				faces: [s.faces[0], s.faces[1]],
				theme: s.theme,
			};
		},
		closeNewGame() {
			if (this.hasGame) this.dialog.visible = false;
		},
		pickFace(seatIndex: 0 | 1, faceId: string) {
			const faces: [string, string] = [this.dialog.faces[0], this.dialog.faces[1]];
			const other = seatIndex === 0 ? 1 : 0;
			// Picking the other player's face swaps the two.
			if (faces[other] === faceId) faces[other] = faces[seatIndex];
			faces[seatIndex] = faceId;
			this.dialog.faces = faces;
		},
		confirmNewGame() {
			const d = this.dialog;
			this.settings = { ...this.settings, mode: d.mode, level: d.level, side: d.side, faces: [d.faces[0], d.faces[1]], theme: d.theme };
			this.dialog.visible = false;
			this.startGame();
		},
		/** A new game with the current settings. */
		startGame() {
			const s = this.settings;
			let seats: [Seat, Seat];
			if (s.mode === 'local') {
				seats = [
					{ kind: 'human', level: s.level, face: s.faces[0] },
					{ kind: 'human', level: s.level, face: s.faces[1] },
				];
			} else {
				const side = s.side === 'random' ? (Math.random() < 0.5 ? 'black' : 'white') : s.side;
				const human: Seat = { kind: 'human', level: s.level, face: '' };
				const computer: Seat = { kind: 'ai', level: s.level, face: '' };
				seats = side === 'black' ? [human, computer] : [computer, human];
				seats[0].face = DEFAULT_FACES[0];
				seats[1].face = DEFAULT_FACES[1];
			}
			this.beginGame(new ReversiGame(BOARD_SIZE), seats);
		},
		/** Replays a saved game; false when there is none worth resuming. */
		resumeGame(saved: SavedGame | null | undefined): boolean {
			if (!saved || saved.size !== BOARD_SIZE || !Array.isArray(saved.moves) ||
				!Array.isArray(saved.seats) || saved.seats.length !== 2 || !saved.seats.every(isSeat)) {
				return false;
			}
			let restored: ReversiGame;
			try {
				restored = ReversiGame.replay(saved.size, saved.moves);
			} catch (e) {
				console.warn('[Reversi] Saved game could not be replayed:', e);
				return false;
			}
			if (restored.position.over) return false;
			this.beginGame(restored, [saved.seats[0], saved.seats[1]]);
			return true;
		},
		beginGame(next: ReversiGame, seats: [Seat, Seat]) {
			generation++;
			ai?.cancel();
			fx?.clear();
			game = next;
			this.seats = seats;
			this.hasGame = true;
			this.busy = false;
			this.thinking = false;
			this.result = null;
			this.setNotice('');
			this.syncFromGame();
			this.scheduleSave();
			this.advance();
		},
		undo() {
			if (!game || !this.canUndo) return;
			generation++;
			ai?.cancel();
			fx?.clear();
			if (this.mode === 'ai') {
				// Take back moves up to and including your own last one.
				while (game.ply > 0) {
					const mover = game.playerAt(game.ply - 1);
					game.undo(1);
					if (this.seats[mover - 1].kind === 'human') break;
				}
			} else {
				game.undo(1);
			}
			this.busy = false;
			this.thinking = false;
			this.result = null;
			this.setNotice('');
			this.syncFromGame();
			this.scheduleSave();
			this.advance();
		},

		// =====================================================================
		// Playing
		// =====================================================================

		/** Copies the game's position to the board as shown. */
		syncFromGame() {
			if (!game) return;
			const pos = game.position;
			this.cells = pos.cells.slice();
			this.turn = pos.turn;
			this.over = pos.over;
			this.ply = game.ply;
			const n = countDiscs(pos.cells);
			this.counts = { black: n.black, white: n.white };
			this.lastMove = game.ply ? fromCoord(game.moves[game.ply - 1], game.size) : -1;
			this.updateHints();
		},
		updateHints() {
			const map: Record<number, boolean> = {};
			if (game && this.settings.hints && this.isHumanTurn && !this.busy) {
				for (const i of legalMoves(game.position)) map[i] = true;
			}
			this.hintMap = map;
		},
		toggleHints() {
			this.settings = { ...this.settings, hints: !this.settings.hints };
			this.updateHints();
			this.scheduleSave();
		},
		setTheme(id: string) {
			this.settings = { ...this.settings, theme: id };
			this.scheduleSave();
		},

		onCellClick(index: number) {
			if (!game || !this.isHumanTurn || this.busy) return;
			if (!legalMoves(game.position).includes(index)) {
				const el = cellElement(index);
				if (el && fx) {
					fx.playAt(el, 'nope');
					fx.shake(el);
				}
				return;
			}
			this.commitMove(index);
		},

		async commitMove(index: number) {
			if (!game) return;
			const gen = generation;
			this.busy = true;
			this.hintMap = {};
			const result = game.play(index);
			await this.animateMove(result, gen);
			if (gen !== generation) return;
			this.busy = false;
			this.syncFromGame();
			this.scheduleSave();
			if (this.over) {
				this.finish();
				return;
			}
			if (result.passed) {
				this.setNotice(this.t('app.reversi.status.pass', { name: this.seatName(result.passed) }, '{name} has no move and passes'));
				// Let a pass be read before the computer moves again.
				if (this.seats[this.turn - 1].kind === 'ai') {
					await delay(900);
					if (gen !== generation) return;
				}
			}
			this.advance();
		},

		/** Places the disc, then turns the captured discs over one by one. */
		async animateMove(result: MoveResult, gen: number) {
			const player = result.player;
			const face = findFace(this.seats[player - 1].face);
			this.cells[result.index] = player;
			this.lastMove = result.index;
			await new Promise<void>((resolve) => this.$nextTick(() => resolve()));
			const cell = cellElement(result.index);
			const disc = discElement(result.index);
			if (cell && fx) fx.playAt(cell, 'place', { colors: face.sparks });
			if (disc && fx) fx.pop(disc, 1.12);

			if (!fx || fx.motionLevel !== 'full') {
				for (const i of result.flipped) this.cells[i] = player;
				return;
			}
			await delay(120);
			const order = result.flipped.slice().sort((a, b) => distance(result.index, a) - distance(result.index, b));
			await Promise.all(order.map((i, k) => delay(k * FLIP_STAGGER_MS).then(() => {
				if (gen !== generation || !fx) return;
				const el = cellElement(i);
				if (el) fx.playAt(el, 'flip', { color: face.sparks[0] });
				return fx.flip(discElement(i) as Element, {
					onHalf: () => {
						if (gen === generation) this.cells[i] = player;
					},
				});
			})));
		},

		/** Lets the computer move when it is its turn. */
		async advance() {
			if (!game) return;
			if (this.over) {
				this.finish();
				return;
			}
			const seat = this.seats[this.turn - 1];
			if (seat.kind !== 'ai' || !ai) {
				this.updateHints();
				return;
			}
			const gen = generation;
			const started = performance.now();
			this.thinking = true;
			let move: number;
			try {
				const answer = await ai.choose({
					size: game.size,
					cells: game.position.cells.slice(),
					player: this.turn,
					level: seat.level,
				});
				move = answer.move;
			} catch {
				// Cancelled by a new game or an undo.
				return;
			}
			const wait = MIN_THINK_MS - (performance.now() - started);
			if (wait > 0) await delay(wait);
			if (gen !== generation) return;
			this.thinking = false;
			if (move < 0) return;
			this.commitMove(move);
		},

		finish() {
			if (!game) return;
			const n = countDiscs(game.position.cells);
			const result: GameResult = { winner: winner(game.position), black: n.black, white: n.white };
			this.result = result;
			this.hintMap = {};
			this.thinking = false;
			const board = document.querySelector('.rv-board');
			if (!board || !fx) return;
			const youLost = this.mode === 'ai' && result.winner !== EMPTY && this.seats[result.winner - 1].kind === 'ai';
			if (result.winner === EMPTY) {
				fx.playAt(board, 'hearts');
			} else if (youLost) {
				fx.playAt(board, 'petals');
			} else {
				const r = board.getBoundingClientRect();
				const face = findFace(this.seats[result.winner - 1].face);
				fx.play('win', r.left + r.width / 2, r.top + r.height / 2, { colors: face.sparks });
			}
		},

		// =====================================================================
		// Display helpers
		// =====================================================================

		seatName(player: Player): string {
			const seat = this.seats[player - 1];
			if (seat.kind === 'ai') {
				return this.t('app.reversi.seat.computer', { level: this.levelLabel(seat.level) }, 'Computer ({level})');
			}
			if (this.mode === 'ai') return this.t('app.reversi.seat.you', undefined, 'You');
			return this.t('app.reversi.seat.player', { n: player }, 'Player {n}');
		},
		levelLabel(level: AiLevel): string {
			const option = this.levelOptions.find((o: { level: AiLevel }) => o.level === level);
			return option ? option.label : String(level);
		},
		faceStyle(faceId: string): Record<string, string> {
			const face = findFace(faceId);
			return { background: face.background, '--rv-rim': face.rim };
		},
		discStyle(cell: Cell): Record<string, string> {
			return this.faceStyle(this.seats[cell - 1].face);
		},
		cellLabel(index: number, cell: Cell): string {
			const coord = toCoord(index, this.size).toUpperCase();
			if (cell === EMPTY) return coord;
			return `${coord} ${this.seatName(cell)}`;
		},
		setNotice(text: string) {
			if (noticeTimer) clearTimeout(noticeTimer);
			noticeTimer = null;
			this.notice = text;
			if (text) {
				noticeTimer = setTimeout(() => {
					noticeTimer = null;
					this.notice = '';
				}, NOTICE_MS);
			}
		},

		// =====================================================================
		// Saving
		// =====================================================================

		async loadState(): Promise<SavedState | null> {
			const userId = this.instance?.currentUser?.id;
			if (!userId) return null;
			try {
				const value = await this.instance.api.db.getUserSetting(userId, APP_ID, STATE_KEY);
				return value && value.version === 1 ? value : null;
			} catch (e) {
				console.warn('[Reversi] Saved state could not be read:', e);
				return null;
			}
		},
		scheduleSave() {
			if (saveTimer) clearTimeout(saveTimer);
			saveTimer = setTimeout(() => {
				saveTimer = null;
				this.writeState();
			}, 300);
		},
		async flushSave() {
			if (!saveTimer) return;
			clearTimeout(saveTimer);
			saveTimer = null;
			await this.writeState();
		},
		async writeState() {
			const userId = this.instance?.currentUser?.id;
			if (!userId) return;
			const state: SavedState = {
				version: 1,
				settings: JSON.parse(JSON.stringify(this.settings)),
				game: game ? {
					size: game.size,
					moves: game.moves.slice(),
					seats: JSON.parse(JSON.stringify(this.seats)),
				} : null,
			};
			try {
				await this.instance.api.db.setUserSetting(userId, APP_ID, STATE_KEY, state);
			} catch (e) {
				console.warn('[Reversi] State could not be saved:', e);
			}
		},
	},
};

VDOM.createApp(App).mount('#app');
