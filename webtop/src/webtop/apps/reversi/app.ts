/**
 * Reversi Application
 *
 * Reversi against the computer (four levels), between two people at the
 * same desktop, or against another user of the Webtop. The rules live in
 * core.ts, the computer player in ai.ts (run in ai-worker.js), the board
 * themes and disc faces in looks.ts and the effects in lib/effects.ts. Each
 * seat is a person at this desktop, the computer, or a player elsewhere.
 *
 * A game against another user is a room of the Reversi GraphQL schema
 * (services/reversi-service-graphql.ts): the server applies the rules and
 * keeps the moves, this app sends a move and shows what happened. The host
 * invites the other user; once accepted the room is a lobby, where each
 * player picks a disc, the host also picks the board, the guest says it is
 * ready and the host starts the game. What the other player does arrives
 * as topic messages on the room's topic; the app watches
 * game/reversi/rooms/* once and reads the room again whenever a message
 * cannot be followed.
 *
 * The game in progress and the settings are kept per user in the local
 * webtop database; a local game is saved as its move list and replayed on
 * launch, a game against another user as its room id.
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
import type { PrincipalInfo, TopicMessageEvent } from "../../graphql/types.js";
import {
	ReversiServiceGraphQL,
	REVERSI_TOPICS,
	isReversiMessage,
	type ReversiMessage,
	type ReversiRoom,
	type ReversiSide,
} from "../../services/reversi-service-graphql.js";
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

type Mode = 'ai' | 'local' | 'online';
type Side = 'black' | 'white' | 'random';

interface Seat {
	/** A person at this desktop, the computer, or a player elsewhere. */
	kind: 'human' | 'ai' | 'remote';
	level: AiLevel;
	/** Disc face id (looks.ts). */
	face: string;
	/** For a game against another user: who sits here. */
	userId?: string;
	name?: string;
}

interface Settings {
	mode: Mode;
	level: AiLevel;
	side: Side;
	/** Faces of the first and second player in a game between two people. */
	faces: [string, string];
	/** The user's own face against another user. */
	face: string;
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
	/** The room of the game against another user that was open, if any. */
	roomId?: string | null;
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
const LONG_NOTICE_MS = 4500;
const FLIP_STAGGER_MS = 70;
const OPPONENT_SUGGESTIONS = 8;

// Kept outside reactive data: ichigo.js wraps stored objects in deep
// Proxies, which the game history, the worker, the canvas and the services
// (private fields) do not need.
let game: ReversiGame | null = null;
let fx: Effects | null = null;
let ai: AiClient | null = null;
let reversi: ReversiServiceGraphQL | null = null;
/** The room of the game against another user shown on the board. */
let room: ReversiRoom | null = null;
let unwatchTopics: (() => void) | null = null;
// Bumped by a new game or an undo; a running animation or an AI answer
// from an older generation is dropped.
let generation = 0;
let noticeTimer: ReturnType<typeof setTimeout> | null = null;
let saveTimer: ReturnType<typeof setTimeout> | null = null;
let searchTimer: ReturnType<typeof setTimeout> | null = null;
let searchSeq = 0;
// Set when a message for the room arrives while a move is being shown or
// sent; the room is read again once the move is done.
let pendingSync = false;

function defaultSettings(): Settings {
	return { mode: 'ai', level: 2, side: 'black', faces: [...DEFAULT_FACES], face: DEFAULT_FACES[0], theme: DEFAULT_THEME, hints: true };
}

function normalizeSettings(value: any): Settings {
	const s = defaultSettings();
	if (!value || typeof value !== 'object') return s;
	if (value.mode === 'ai' || value.mode === 'local' || value.mode === 'online') s.mode = value.mode;
	if (LEVELS.includes(value.level)) s.level = value.level;
	if (['black', 'white', 'random'].includes(value.side)) s.side = value.side;
	if (Array.isArray(value.faces) && value.faces.length === 2 && value.faces[0] !== value.faces[1] &&
		value.faces.every((f: unknown) => DISC_FACES.some(d => d.id === f))) {
		s.faces = [value.faces[0], value.faces[1]];
	}
	if (DISC_FACES.some(d => d.id === value.face)) s.face = value.face;
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
function distance(a: number, b: number, size: number): number {
	const ar = Math.floor(a / size);
	const br = Math.floor(b / size);
	return Math.max(Math.abs(ar - br), Math.abs((a % size) - (b % size)));
}

function errorText(e: unknown): string {
	return (e instanceof Error) ? e.message : String(e);
}

function playerOf(side: ReversiSide): Player {
	return side === 'black' ? BLACK : WHITE;
}

/** The other player of a room, seen from the user. */
function opponentOf(r: ReversiRoom, userId: string): { id: string; name: string } {
	const p = r.black.id === userId ? r.white : r.black;
	return { id: p.id, name: p.displayName || p.id };
}

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			visibilityListener: null as (() => void) | null,
			// Reactive Localization snapshot — see composables/use-localization.ts.
			localization: createLocalizationSnapshot(),
			isReady: false,
			userId: '',

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

			// The game against another user on the board, and the user's rooms.
			online: {
				roomId: null as string | null,
				status: '' as ReversiRoom['status'] | '',
				host: '',
				opponentId: '',
				opponentName: '',
				// The board the host chose, and whether the guest is ready.
				theme: '',
				guestReady: false,
				// A lobby request (a disc, the board, ready, start) is on its way.
				busy: false,
				// Whether the game ended by resignation, and whose.
				resignedBy: '' as string,
				invitations: [] as ReversiRoom[],
				sent: [] as ReversiRoom[],
				games: [] as ReversiRoom[],
				loading: false,
				error: '',
			},

			dialog: {
				visible: false,
				mode: 'ai' as Mode,
				level: 2 as AiLevel,
				side: 'black' as Side,
				faces: [...DEFAULT_FACES] as [string, string],
				theme: DEFAULT_THEME,
				// Choosing an opponent.
				keyword: '',
				results: [] as PrincipalInfo[],
				searching: false,
				opponent: null as PrincipalInfo | null,
				busy: false,
				error: '',
			},

			resultDialog: { visible: false },
			resignDialog: { visible: false, busy: false },
		};
	},
	computed: {
		stageStyle(): Record<string, string> {
			// Against another user the board is the one the host chose.
			const theme = (this.isOnline && this.online.theme) ? this.online.theme : this.settings.theme;
			return { ...findTheme(theme).vars };
		},
		boardStyle(): Record<string, string> {
			return { '--rv-size': String(this.size) };
		},
		mode(): Mode {
			if (this.online.roomId) return 'online';
			return this.seats[0].kind === 'human' && this.seats[1].kind === 'human' ? 'local' : 'ai';
		},
		isOnline(): boolean {
			return !!this.online.roomId;
		},
		isHost(): boolean {
			return this.isOnline && this.online.host === this.userId;
		},
		/** The room is being set up: waiting for an answer, or the lobby. */
		inLobby(): boolean {
			return this.isOnline && (this.online.status === 'waiting' || this.online.status === 'lobby');
		},
		myFace(): string {
			return this.seats.find((s: Seat) => s.kind === 'human')?.face || '';
		},
		theirFace(): string {
			return this.seats.find((s: Seat) => s.kind === 'remote')?.face || '';
		},
		/** The guest's choice is final once it is ready. */
		lobbyLocked(): boolean {
			return this.online.busy || (!this.isHost && this.online.guestReady);
		},
		canStartRoom(): boolean {
			return this.isHost && this.online.status === 'lobby' && this.online.guestReady && !this.online.busy;
		},
		/** What the lobby waits for, seen from the user. */
		lobbyNote(): string {
			const o = this.online;
			const name = o.opponentName;
			if (o.status === 'waiting') {
				return this.t('app.reversi.online.waiting', { name }, 'Waiting for {name} to accept…');
			}
			if (this.isHost) {
				return o.guestReady ?
					this.t('app.reversi.online.isReady', { name }, '{name} is ready') :
					this.t('app.reversi.online.waitingReady', { name }, 'Waiting for {name} to get ready…');
			}
			return o.guestReady ?
				this.t('app.reversi.online.waitingStart', { name }, 'Waiting for {name} to start…') :
				this.t('app.reversi.online.chooseDisc', undefined, 'Choose your disc and press Ready!');
		},
		isHumanTurn(): boolean {
			if (!this.hasGame || this.over || this.seats[this.turn - 1].kind !== 'human') return false;
			return !this.isOnline || this.online.status === 'playing';
		},
		canUndo(): boolean {
			if (!this.hasGame || this.isOnline || (this.busy && !this.thinking)) return false;
			// Against the computer an undo goes back to your own last move.
			const computerOpened = this.seats[0].kind === 'ai';
			return this.ply > (computerOpened ? 1 : 0);
		},
		canResign(): boolean {
			return this.isOnline && this.online.status === 'playing' && !this.over;
		},
		/** Invitations waiting for the user's answer. */
		pendingCount(): number {
			return this.online.invitations.length;
		},
		seatCards(): { player: Player; name: string; role: string; count: number; active: boolean; thinking: boolean; style: Record<string, string> }[] {
			return ([BLACK, WHITE] as Player[]).map((player) => {
				const seat = this.seats[player - 1];
				let role = player === BLACK ?
					this.t('app.reversi.seat.first', undefined, 'Moves first') :
					this.t('app.reversi.seat.second', undefined, 'Moves second');
				// The panel is narrow: the computer's level goes on the second line.
				if (seat.kind === 'ai') role += ` · ${this.levelLabel(seat.level)}`;
				const theirMove = this.isOnline && this.online.status === 'playing' && !this.over && this.turn === player && seat.kind === 'remote';
				return {
					player,
					name: seat.kind === 'ai' ? this.t('app.reversi.seat.computerShort', undefined, 'Computer') : this.seatName(player),
					role,
					count: player === BLACK ? this.counts.black : this.counts.white,
					active: this.hasGame && !this.over && !this.inLobby && this.turn === player,
					thinking: (this.thinking && this.turn === player && seat.kind === 'ai') || theirMove,
					style: this.faceStyle(seat.face),
				};
			});
		},
		statusText(): string {
			if (!this.hasGame) return '';
			if (this.inLobby) return this.lobbyNote;
			if (this.over) return this.resultTitle;
			if (this.thinking) return this.t('app.reversi.status.thinking', undefined, 'Thinking…');
			if (this.isHumanTurn && this.isOnline) return this.t('app.reversi.status.yourTurn', undefined, 'Your turn');
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
			if (this.mode === 'online' && seat.kind === 'human') {
				return this.t('app.reversi.result.youWin', undefined, 'You win!');
			}
			return this.t('app.reversi.result.wins', { name: this.seatName(r.winner) }, '{name} wins!');
		},
		/** Why the game ended, when it did not end on the board. */
		resultNote(): string {
			if (!this.result || !this.isOnline || !this.online.resignedBy) return '';
			if (this.online.resignedBy === this.userId) {
				return this.t('app.reversi.online.youResigned', undefined, 'You resigned');
			}
			return this.t('app.reversi.online.resigned', { name: this.online.opponentName }, '{name} resigned');
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
		/** The rooms listed in the dialog, with what the user can do about each. */
		roomCards(): { room: ReversiRoom; name: string; note: string; side: string; action: 'answer' | 'cancel' | 'resume'; current: boolean }[] {
			const o = this.online;
			const notes = {
				answer: () => this.t('app.reversi.online.invitesYou', undefined, 'Invites you to a game'),
				resume: (r: ReversiRoom) => r.status === 'lobby' ?
					this.t('app.reversi.online.lobby', undefined, 'Getting ready') :
					this.t('app.reversi.status.moves', { n: r.moves.length }, 'Move {n}'),
				cancel: () => this.t('app.reversi.online.awaiting', undefined, 'Waiting for an answer'),
			};
			const card = (r: ReversiRoom, action: 'answer' | 'cancel' | 'resume') => ({
				room: r,
				name: opponentOf(r, this.userId).name,
				note: notes[action](r),
				side: r.yourSide === 'black' ?
					this.t('app.reversi.dialog.sideFirst', undefined, 'First (black)') :
					this.t('app.reversi.dialog.sideSecond', undefined, 'Second (white)'),
				action,
				current: r.id === o.roomId,
			});
			return [
				...o.invitations.map(r => card(r, 'answer')),
				...o.games.map(r => card(r, 'resume')),
				...o.sent.map(r => card(r, 'cancel')),
			];
		},
		canStart(): boolean {
			if (this.dialog.mode !== 'online') return true;
			return !!this.dialog.opponent && !this.dialog.busy;
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

			// A message may have been missed while the desktop was asleep: the
			// room is read again when the app is looked at.
			vm.visibilityListener = () => {
				if (document.visibilityState === 'visible' && vm.online.roomId) vm.reloadRoom();
			};
			document.addEventListener('visibilitychange', vm.visibilityListener);

			window.appLaunch = async (instance: ApplicationInstance) => {
				vm.instance = this.$markRaw(instance);
				refreshLocalization(vm.localization, vm.instance);
				vm.userId = instance.currentUser?.id || '';

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
				reversi = new ReversiServiceGraphQL(instance.api.graphql);

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

				vm.watchRooms();
				vm.loadRooms();
				if (await vm.resumeRoom(saved?.roomId)) return;
				if (!vm.resumeGame(saved?.game)) {
					vm.openNewGame();
				}
			};
		},
		onUnmount() {
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
			}
			if (this.visibilityListener) {
				document.removeEventListener('visibilitychange', this.visibilityListener);
			}
			this.dispose();
		},
		dispose() {
			generation++;
			if (noticeTimer) clearTimeout(noticeTimer);
			if (searchTimer) clearTimeout(searchTimer);
			if (unwatchTopics) {
				try { unwatchTopics(); } catch { /* ignore */ }
				unwatchTopics = null;
			}
			ai?.destroy();
			ai = null;
			fx?.destroy();
			fx = null;
			reversi = null;
			room = null;
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
				...this.dialog,
				visible: true,
				mode: s.mode,
				level: s.level,
				side: s.side,
				faces: [s.faces[0], s.faces[1]],
				theme: s.theme,
				keyword: '',
				results: [],
				searching: false,
				opponent: null,
				busy: false,
				error: '',
			};
			// An invitation is answered here, so the list is fresh when it opens.
			if (this.pendingCount) this.dialog.mode = 'online';
			this.loadRooms();
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
			if (d.mode === 'online') {
				this.sendInvitation();
				return;
			}
			this.dialog.visible = false;
			this.startGame();
		},
		/** A new game with the current settings (not against another user). */
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
			this.leaveRoom();
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
			this.size = next.size;
			pendingSync = false;
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
			this.size = game.size;
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
			this.commitMove(index, true);
		},

		/**
		 * Plays a move on the board. In a game against another user, a move of
		 * the user's own is sent to the server once shown (`send`); one of the
		 * other player's is only shown.
		 */
		async commitMove(index: number, send: boolean) {
			if (!game) return;
			const gen = generation;
			const plyBefore = game.ply;
			const roomId = this.online.roomId;
			this.busy = true;
			this.hintMap = {};
			const result = game.play(index);
			await this.animateMove(result, gen);
			if (gen !== generation) return;
			if (roomId && send) {
				if (!await this.sendMove(roomId, plyBefore, toCoord(index, game.size))) return;
				if (gen !== generation) return;
			}
			this.busy = false;
			this.syncFromGame();
			this.scheduleSave();
			if (this.over) {
				this.finish();
			}
			if (pendingSync && this.online.roomId) {
				// Something arrived for the room while the move was shown.
				pendingSync = false;
				this.reloadRoom();
				return;
			}
			if (this.over) return;
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
			const size = result.position.size;
			const order = result.flipped.slice().sort((a, b) => distance(result.index, a, size) - distance(result.index, b, size));
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
			this.commitMove(move, false);
		},

		finish() {
			if (!game) return;
			const n = countDiscs(game.position.cells);
			const result: GameResult = { winner: winner(game.position), black: n.black, white: n.white };
			this.showResult(result);
		},
		showResult(result: GameResult) {
			this.result = result;
			this.resultDialog.visible = true;
			this.hintMap = {};
			this.thinking = false;
			this.busy = false;
			if (!fx) return;
			// Over the whole view: the result dialog covers the board's centre.
			const youLost = this.mode !== 'local' && result.winner !== EMPTY && this.seats[result.winner - 1].kind !== 'human';
			if (result.winner === EMPTY) {
				fx.play('hearts');
			} else if (youLost) {
				fx.play('petals');
			} else {
				const face = findFace(this.seats[result.winner - 1].face);
				fx.play('win', null, null, { colors: face.sparks });
			}
		},
		closeResult() {
			this.resultDialog.visible = false;
		},
		/** The result's "Play again": a rematch against another user, or the same settings. */
		playAgain() {
			if (this.isOnline) {
				this.rematch();
				return;
			}
			this.startGame();
		},

		// =====================================================================
		// Games against another user
		// =====================================================================

		/** Receives what happens in the user's rooms, for as long as the app runs. */
		watchRooms() {
			const hub = this.instance?.api.eventHub;
			if (!hub || unwatchTopics) return;
			try {
				unwatchTopics = hub.watchTopic(REVERSI_TOPICS, (event: TopicMessageEvent) => {
					if (isReversiMessage(event.payload)) this.onRoomMessage(event.payload);
				});
			} catch (e) {
				console.warn('[Reversi] Rooms cannot be watched:', e);
			}
		},
		async onRoomMessage(m: ReversiMessage) {
			const mine = !!room && m.roomId === room.id;
			const byMe = m.by === this.userId;
			switch (m.type) {
				case 'invited':
					await this.loadRooms();
					if (!byMe) {
						const r = this.online.invitations.find(x => x.id === m.roomId);
						if (r) {
							this.setNotice(this.t('app.reversi.online.invitedYou', { name: opponentOf(r, this.userId).name },
								'{name} invites you to a game'), LONG_NOTICE_MS);
						}
					}
					return;
				case 'accepted':
					if (mine) {
						await this.reloadRoom();
						if (!byMe) {
							this.setNotice(this.t('app.reversi.online.accepted', { name: this.online.opponentName }, '{name} accepted'), LONG_NOTICE_MS);
						}
					}
					this.loadRooms();
					return;
				case 'changed':
					if (mine) await this.reloadRoom();
					return;
				case 'ready':
					if (mine) {
						await this.reloadRoom();
						if (!byMe && m.ready) {
							this.setNotice(this.t('app.reversi.online.isReady', { name: this.online.opponentName }, '{name} is ready'), LONG_NOTICE_MS);
						}
					}
					return;
				case 'started':
					if (mine) await this.reloadRoom();
					this.loadRooms();
					return;
				case 'declined':
				case 'cancelled':
					if (mine && !byMe) {
						const name = this.online.opponentName;
						// A guest that declines from the lobby has left it.
						const left = m.type === 'declined' && this.online.status === 'lobby';
						this.leaveRoom();
						this.hasGame = false;
						this.openNewGame();
						this.setNotice(m.type === 'cancelled' ?
							this.t('app.reversi.online.cancelledYou', { name }, '{name} took the invitation back') :
							left ?
								this.t('app.reversi.online.leftYou', { name }, '{name} left') :
								this.t('app.reversi.online.declinedYou', { name }, '{name} declined'), LONG_NOTICE_MS);
					}
					this.loadRooms();
					return;
				case 'move':
					if (!mine || !game) return;
					// The echo of a move made here (or in another window of the
					// same user) is nothing new once the board has it.
					if (byMe && typeof m.ply === 'number' && m.ply < game.ply) return;
					if (this.busy) {
						pendingSync = true;
						return;
					}
					if (typeof m.ply === 'number' && m.ply === game.ply && m.move) {
						let index = -1;
						try {
							index = fromCoord(m.move, game.size);
						} catch { /* read the room instead */ }
						if (index >= 0 && legalMoves(game.position).includes(index)) {
							this.commitMove(index, false);
							return;
						}
					}
					await this.reloadRoom();
					return;
				case 'resigned':
					if (mine) await this.reloadRoom();
					this.loadRooms();
					return;
				case 'expired':
					// The room was idle for long and has been removed.
					if (mine) {
						const name = this.online.opponentName;
						this.leaveRoom();
						this.hasGame = false;
						this.openNewGame();
						this.setNotice(this.t('app.reversi.online.expired', { name },
							'The game with {name} was closed after a long time without a move'), LONG_NOTICE_MS);
					}
					this.loadRooms();
					return;
			}
		},

		/** Reads the user's rooms for the dialog and the badge. */
		async loadRooms() {
			if (!reversi) return;
			const o = this.online;
			o.loading = true;
			try {
				const rooms = await reversi.listRooms();
				const me = this.userId;
				o.invitations = rooms.filter(r => r.status === 'waiting' && r.host !== me);
				o.sent = rooms.filter(r => r.status === 'waiting' && r.host === me);
				o.games = rooms.filter(r => r.status === 'lobby' || r.status === 'playing');
				o.error = '';
			} catch (e) {
				o.error = errorText(e);
			} finally {
				o.loading = false;
			}
		},
		/** Opens the room that was on the board when the app was last closed. */
		async resumeRoom(roomId: string | null | undefined): Promise<boolean> {
			if (!roomId || !reversi) return false;
			try {
				const r = await reversi.getRoom(roomId);
				if (r.status !== 'waiting' && r.status !== 'lobby' && r.status !== 'playing') return false;
				this.enterRoom(r);
				return true;
			} catch (e) {
				console.warn('[Reversi] The last game could not be reopened:', e);
				return false;
			}
		},
		/** Shows a room on the board. */
		enterRoom(r: ReversiRoom) {
			let restored: ReversiGame;
			try {
				restored = ReversiGame.replay(r.size, r.moves);
			} catch (e) {
				this.setNotice(this.t('app.reversi.online.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				return;
			}
			const me = this.userId;
			const opponent = opponentOf(r, me);
			const seat = (p: { id: string; displayName: string | null }, face: string): Seat => p.id === me ?
				{ kind: 'human', level: this.settings.level, face, userId: me } :
				{ kind: 'remote', level: this.settings.level, face, userId: p.id, name: p.displayName || p.id };
			room = r;
			const o = this.online;
			o.roomId = r.id;
			o.opponentId = opponent.id;
			o.opponentName = opponent.name;
			o.busy = false;
			this.applyRoom(r);
			this.dialog.visible = false;
			this.beginGame(restored, [seat(r.black, r.blackFace || DEFAULT_FACES[0]), seat(r.white, r.whiteFace || DEFAULT_FACES[1])]);
			if (r.status === 'finished') this.finishRoom(r);
		},
		/** Copies the room's state and look (not its moves) to the view. */
		applyRoom(r: ReversiRoom) {
			const o = this.online;
			o.status = r.status;
			o.host = r.host;
			o.theme = r.theme || '';
			o.guestReady = !!r.guestReady;
			o.resignedBy = r.resignedBy || '';
			const faces = [r.blackFace || DEFAULT_FACES[0], r.whiteFace || DEFAULT_FACES[1]];
			if (this.seats[0].face !== faces[0] || this.seats[1].face !== faces[1]) {
				this.seats = [{ ...this.seats[0], face: faces[0] }, { ...this.seats[1], face: faces[1] }];
			}
		},
		/** Brings the board up to date with a fresh reading of the room. */
		syncRoom(r: ReversiRoom) {
			if (!game || !room || r.id !== room.id) return;
			room = r;
			this.applyRoom(r);
			if (this.busy) {
				// A move is still being shown or sent: the room is read again
				// once it is done (commitMove).
				pendingSync = true;
				return;
			}
			const known = game.moves;
			const same = r.moves.length >= known.length && known.every((m, i) => m === r.moves[i]);
			if (!same) {
				// The board and the room disagree: start over from the room.
				this.enterRoom(r);
				return;
			}
			for (let i = known.length; i < r.moves.length; i++) {
				try {
					game.play(fromCoord(r.moves[i], game.size));
				} catch (e) {
					this.enterRoom(r);
					return;
				}
			}
			this.syncFromGame();
			this.scheduleSave();
			if (r.status === 'finished') {
				this.finishRoom(r);
			} else if (r.status === 'playing' && !this.over) {
				this.updateHints();
			}
		},
		async reloadRoom() {
			const id = this.online.roomId;
			if (!id || !reversi) return;
			try {
				const r = await reversi.getRoom(id);
				if (id !== this.online.roomId) return;
				this.syncRoom(r);
			} catch (e) {
				this.setNotice(this.t('app.reversi.online.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
			}
		},
		/** The room is finished: shows the result the server recorded. */
		finishRoom(r: ReversiRoom) {
			if (!game) return;
			const n = countDiscs(game.position.cells);
			const w: Cell = r.winner === 'black' ? BLACK : r.winner === 'white' ? WHITE : EMPTY;
			if (this.result && this.result.winner === w) return;
			this.showResult({ winner: w, black: n.black, white: n.white });
		},
		/** Forgets the room on the board (the room itself goes on). */
		leaveRoom() {
			room = null;
			const o = this.online;
			o.roomId = null;
			o.status = '';
			o.host = '';
			o.opponentId = '';
			o.opponentName = '';
			o.theme = '';
			o.guestReady = false;
			o.busy = false;
			o.resignedBy = '';
		},

		/** Sends the user's move; false when it was refused, in which case the room is read again. */
		async sendMove(roomId: string, ply: number, move: string): Promise<boolean> {
			if (!reversi) return false;
			try {
				const r = await reversi.play(roomId, ply, move);
				if (room && r.id === room.id) {
					room = r;
					this.online.status = r.status;
				}
				return true;
			} catch (e) {
				this.setNotice(this.t('app.reversi.online.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				generation++;
				this.busy = false;
				await this.reloadRoom();
				return false;
			}
		},

		// --- the dialog: inviting, answering, resuming ---

		onOpponentInput() {
			const d = this.dialog;
			d.opponent = null;
			if (searchTimer) clearTimeout(searchTimer);
			searchTimer = setTimeout(() => {
				searchTimer = null;
				this.searchOpponents();
			}, 250);
		},
		async searchOpponents() {
			const d = this.dialog;
			const keyword = d.keyword.trim();
			const seq = ++searchSeq;
			if (!keyword || !this.instance) {
				d.results = [];
				d.searching = false;
				return;
			}
			d.searching = true;
			try {
				const found = await this.instance.api.content.searchPrincipals(keyword, 0, OPPONENT_SUGGESTIONS * 2);
				if (seq !== searchSeq) return;
				d.results = found.filter((p: PrincipalInfo) => !p.isGroup && !p.isService && p.identifier !== this.userId)
					.slice(0, OPPONENT_SUGGESTIONS);
			} catch (e) {
				if (seq === searchSeq) {
					d.results = [];
					d.error = errorText(e);
				}
			} finally {
				if (seq === searchSeq) d.searching = false;
			}
		},
		chooseOpponent(p: PrincipalInfo) {
			this.dialog.opponent = p;
			this.dialog.keyword = p.displayName || p.identifier;
			this.dialog.results = [];
		},
		async sendInvitation() {
			const d = this.dialog;
			if (!reversi || !d.opponent || d.busy) return;
			d.busy = true;
			d.error = '';
			try {
				const r = await reversi.invite(d.opponent.identifier, d.side, BOARD_SIZE, this.settings.face, this.settings.theme);
				this.enterRoom(r);
				this.loadRooms();
				this.setNotice(this.t('app.reversi.online.sent', { name: this.online.opponentName }, 'Invitation sent to {name}'), LONG_NOTICE_MS);
			} catch (e) {
				d.error = errorText(e);
			} finally {
				d.busy = false;
			}
		},
		async acceptRoom(r: ReversiRoom) {
			if (!reversi || this.dialog.busy) return;
			this.dialog.busy = true;
			this.dialog.error = '';
			try {
				this.enterRoom(await reversi.accept(r.id, this.settings.face));
				this.loadRooms();
			} catch (e) {
				this.dialog.error = errorText(e);
				this.loadRooms();
			} finally {
				this.dialog.busy = false;
			}
		},
		/** Declines an invitation, or takes back one the user sent. */
		async declineRoom(r: ReversiRoom) {
			if (!reversi || this.dialog.busy) return;
			this.dialog.busy = true;
			this.dialog.error = '';
			try {
				await reversi.decline(r.id);
				if (r.id === this.online.roomId) {
					this.leaveRoom();
					this.hasGame = false;
				}
				await this.loadRooms();
			} catch (e) {
				this.dialog.error = errorText(e);
				this.loadRooms();
			} finally {
				this.dialog.busy = false;
			}
		},
		resumeRoomFromList(r: ReversiRoom) {
			if (r.id === this.online.roomId) {
				this.dialog.visible = false;
				this.reloadRoom();
				return;
			}
			this.enterRoom(r);
		},
		/** Invites the same opponent again, sides swapped, with the same discs and board. */
		async rematch() {
			if (!reversi || !room) return;
			const opponentId = this.online.opponentId;
			const side: ReversiSide = room.yourSide === 'black' ? 'white' : 'black';
			const face = room.yourSide === 'black' ? room.blackFace : room.whiteFace;
			try {
				const r = await reversi.invite(opponentId, side, room.size, face, room.theme);
				this.enterRoom(r);
				this.loadRooms();
				this.setNotice(this.t('app.reversi.online.sent', { name: this.online.opponentName }, 'Invitation sent to {name}'), LONG_NOTICE_MS);
			} catch (e) {
				this.setNotice(this.t('app.reversi.online.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
			}
		},

		// --- the lobby: discs, the board, ready, start ---

		/** Sends a lobby request and shows the room it answers with. */
		async updateRoom(request: (id: string) => Promise<ReversiRoom>): Promise<boolean> {
			const id = this.online.roomId;
			if (!reversi || !id || this.online.busy) return false;
			this.online.busy = true;
			try {
				const r = await request(id);
				if (id === this.online.roomId) this.syncRoom(r);
				return true;
			} catch (e) {
				this.setNotice(this.t('app.reversi.online.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				this.reloadRoom();
				return false;
			} finally {
				this.online.busy = false;
			}
		},
		async pickRoomFace(faceId: string) {
			if (!reversi || this.lobbyLocked || faceId === this.theirFace || faceId === this.myFace) return;
			const service = reversi;
			if (await this.updateRoom(id => service.setup(id, faceId))) {
				this.settings = { ...this.settings, face: faceId };
				this.scheduleSave();
			}
		},
		async pickRoomTheme(themeId: string) {
			if (!reversi || !this.isHost || themeId === this.online.theme) return;
			const service = reversi;
			if (await this.updateRoom(id => service.setup(id, undefined, themeId))) {
				this.settings = { ...this.settings, theme: themeId };
				this.scheduleSave();
			}
		},
		async setReady(ready: boolean) {
			if (!reversi || this.isHost || this.online.status !== 'lobby') return;
			const service = reversi;
			await this.updateRoom(id => service.ready(id, ready));
		},
		async startRoom() {
			if (!reversi || !this.canStartRoom) return;
			const service = reversi;
			if (await this.updateRoom(id => service.start(id))) this.loadRooms();
		},
		/** The host takes the invitation back, or the guest leaves the lobby. */
		async leaveLobby() {
			if (!room || this.online.busy) return;
			this.online.busy = true;
			this.dialog.error = '';
			try {
				await this.declineRoom(room);
			} finally {
				this.online.busy = false;
			}
			if (this.dialog.error) {
				this.setNotice(this.t('app.reversi.online.error', { message: this.dialog.error }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				return;
			}
			if (!this.hasGame) this.openNewGame();
		},

		// --- resigning ---

		openResign() {
			if (!this.canResign) return;
			this.resignDialog = { visible: true, busy: false };
		},
		closeResign() {
			this.resignDialog.visible = false;
		},
		async confirmResign() {
			const id = this.online.roomId;
			if (!reversi || !id || this.resignDialog.busy) return;
			this.resignDialog.busy = true;
			try {
				const r = await reversi.resign(id);
				this.resignDialog.visible = false;
				this.syncRoom(r);
				this.loadRooms();
			} catch (e) {
				this.setNotice(this.t('app.reversi.online.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
			} finally {
				this.resignDialog.busy = false;
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
			if (seat.kind === 'remote') return seat.name || seat.userId || '';
			if (this.mode !== 'local') return this.t('app.reversi.seat.you', undefined, 'You');
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
		setNotice(text: string, ms = NOTICE_MS) {
			if (noticeTimer) clearTimeout(noticeTimer);
			noticeTimer = null;
			this.notice = text;
			if (text) {
				noticeTimer = setTimeout(() => {
					noticeTimer = null;
					this.notice = '';
				}, ms);
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
			// A game against another user is kept by the server; only which room
			// was open is remembered here.
			const online = !!this.online.roomId;
			const state: SavedState = {
				version: 1,
				settings: JSON.parse(JSON.stringify(this.settings)),
				game: (game && !online) ? {
					size: game.size,
					moves: game.moves.slice(),
					seats: JSON.parse(JSON.stringify(this.seats)),
				} : null,
				roomId: this.online.roomId,
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
