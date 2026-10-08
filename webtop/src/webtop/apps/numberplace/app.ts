/**
 * Number Place Application
 *
 * Number Place (sudoku) alone, against the computer, or with another user
 * of the Webtop, cooperating or against each other. The rules, the solver
 * and the puzzle maker live in core.ts (puzzles are made in gen-worker.js),
 * the computer opponent in ai.ts, the board themes and player colours in
 * looks.ts and the effects in lib/effects.ts.
 *
 * What is on the board is played by one of four kinds of rules (`kind`):
 *
 *   solo       alone: any number may be written, mistakes are shown on
 *              request, undo and hints are there
 *   race       time attack: each player solves the same puzzle on a board
 *              of its own; the first to fill it wins
 *   territory  one board for both: a cell filled right is the player's;
 *              the one with more cells when the board is full wins
 *   coop       one board for both, filled together
 *
 * Outside solo, a number is checked as it is written: a right one fills
 * the cell, a wrong one makes the player wait a few seconds (LOCK_MS).
 * Against the computer the race or the territory is played here; with
 * another user, it is a room of the Number Place GraphQL schema
 * (services/numberplace-service-graphql.ts): the server keeps the solution
 * and checks every number, and this app sends a number and shows what
 * happened. The host invites the other user; once accepted the room is a
 * lobby, where each player picks a colour, the host also picks the
 * difficulty and the board, the guest says it is ready and the host starts
 * the game with a puzzle it makes. What the other player does arrives as
 * topic messages on the room's topic; the app watches
 * game/numberplace/rooms/* once and reads the room again whenever a
 * message cannot be followed.
 *
 * The window shows one of two scenes next to the players' panel: the
 * lobby, where the next game is set up (and, with another user, where the
 * two players get ready), or the game, the board. Which one is shown
 * follows the state (`scene`), as in Reversi. A room has a chat, shown in
 * the players' panel from the invitation on, and an invitation is also a
 * card in the direct messages of the two (assets/cards/invitation), whose
 * button launches this app with `{ roomId }`.
 *
 * The game in progress and the settings are kept per user in the local
 * webtop database: a game alone or against the computer as it stands, a
 * game with another user as its room id.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import { ApplicationInstance } from "../../services/webtop-service.js";
import { initUi, type WtAutocompleteItem } from "../../ui/index.js";
import { createShellPopupAdapter } from "../../ui/shell-popup-adapter.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";
import type { PrincipalInfo, TopicMessageEvent } from "../../graphql/types.js";
import {
	NumberPlaceServiceGraphQL,
	NUMBERPLACE_TOPICS,
	isNumberPlaceMessage,
	type NumberPlaceMessage,
	type NumberPlaceMode,
	type NumberPlacePlayer,
	type NumberPlaceRoom,
} from "../../services/numberplace-service-graphql.js";
import { ChatServiceGraphQL } from "../../services/chat-service-graphql.js";
// Side-effect import: registers the <wt-chat-thread> the room's chat is shown in.
import { loadChatThreadTemplate } from "../../components/wt-chat-thread.js";
import { Effects } from '../../lib/effects.js';
import {
	CELLS,
	LEVELS,
	BOX_CELLS,
	PEERS,
	candidates,
	conflicts,
	formatGrid,
	parseGrid,
	unitsOf,
	rowOf,
	colOf,
	boxOf,
	type Level,
	type Puzzle,
} from './core.js';
import { nextStep, AI_LEVELS, type AiLevel } from './ai.js';
import { GenClient } from './gen-client.js';
import { BOARD_THEMES, PLAYER_COLORS, DEFAULT_THEME, DEFAULT_COLORS, findTheme, findColor, otherColor } from './looks.js';

/** The lobby's four ways to play. */
type Mode = 'solo' | 'ai' | 'coop' | 'versus';
/** The rules of a game against the computer or another user. */
type Rule = 'race' | 'territory';
/** The rules the board is played by. */
type Kind = 'solo' | 'race' | 'territory' | 'coop';

interface Seat {
	/** The user at this desktop, the computer, or a player elsewhere. */
	kind: 'human' | 'ai' | 'remote';
	/** Player colour id (looks.ts). */
	color: string;
	/** For a game with another user: who sits here. */
	userId?: string;
	name?: string;
	/** Cells filled: on the player's own board in a race, taken in territory, written in cooperation. */
	filled: number;
	/** Wrong numbers so far. */
	misses: number;
	/** Until when (Date.now()) the player waits after a wrong number. */
	lockUntil: number;
}

interface Settings {
	mode: Mode;
	level: Level;
	aiLevel: AiLevel;
	rule: Rule;
	/** The user's colour. */
	color: string;
	theme: string;
	/** Alone: wrong numbers are marked as soon as they are written. */
	showMistakes: boolean;
}

/** A game alone or against the computer, as it stands. */
interface SavedGame {
	kind: 'solo' | 'race' | 'territory';
	level: Level;
	aiLevel: AiLevel;
	givens: string;
	solution: string;
	/** The numbers written on the user's board (the shared one in territory), 0 elsewhere. */
	values: string;
	/** Who wrote each number: 1 the user, 2 the computer, 0 nobody. */
	owners: string;
	notes: number[];
	/** In a race: the computer's own board, givens included. */
	aiBoard: string | null;
	colors: [string, string];
	elapsed: number;
	mistakes: number;
	hints: number;
	aiMisses: number;
}

interface SavedState {
	version: 1;
	settings: Settings;
	game: SavedGame | null;
	/** The room of the game with another user that was open, if any. */
	roomId?: string | null;
}

interface GameResult {
	/** The seat that won, -1 for a draw; null alone and in cooperation. */
	winner: number | null;
	/** Whether the puzzle was solved, rather than given up. */
	solved: boolean;
	timeMs: number;
	/** What each seat filled. */
	scores: number[];
}

/** A cell as it was before a change, for undo. */
interface Change {
	i: number;
	value: number;
	owner: number;
	notes: number;
}

const APP_ID = 'numberplace';
const STATE_KEY = 'state';
/** How long a player waits after a wrong number (the server's NumberPlaceApi.LOCK_MS too). */
const LOCK_MS = 3000;
const NOTICE_MS = 1800;
const LONG_NOTICE_MS = 4500;
const TICK_MS = 250;
const OPPONENT_SUGGESTIONS = 8;
const RULES: Rule[] = ['race', 'territory'];
const MODES: Mode[] = ['solo', 'ai', 'coop', 'versus'];

// Kept outside reactive data: ichigo.js wraps stored objects in deep
// Proxies, which the workers, the canvas and the services (private fields)
// do not need.
let fx: Effects | null = null;
let gen: GenClient | null = null;
let service: NumberPlaceServiceGraphQL | null = null;
/** The room of the game with another user shown on the board. */
let room: NumberPlaceRoom | null = null;
let unwatchTopics: (() => void) | null = null;
/** The solution of a game alone or against the computer. */
let solution: number[] | null = null;
/** In a race against the computer: the computer's own board. */
let aiBoard: number[] | null = null;
/** Undo steps of a game alone, each the cells one action changed. */
let history: Change[][] = [];
// Bumped by a new game; a step of the computer from an older one is dropped.
let generation = 0;
let noticeTimer: ReturnType<typeof setTimeout> | null = null;
let saveTimer: ReturnType<typeof setTimeout> | null = null;
let tickTimer: ReturnType<typeof setInterval> | null = null;
let aiTimer: ReturnType<typeof setTimeout> | null = null;
// The clock: time played before `clockSince`, and since when it runs (null: stopped).
let clockBase = 0;
let clockSince: number | null = null;

function emptyGrid(): number[] {
	return new Array(CELLS).fill(0);
}

function defaultSettings(): Settings {
	return { mode: 'solo', level: 2, aiLevel: 2, rule: 'race', color: DEFAULT_COLORS[0], theme: DEFAULT_THEME, showMistakes: true };
}

function normalizeSettings(value: any): Settings {
	const s = defaultSettings();
	if (!value || typeof value !== 'object') return s;
	if (MODES.includes(value.mode)) s.mode = value.mode;
	if (LEVELS.includes(value.level)) s.level = value.level;
	if (AI_LEVELS.includes(value.aiLevel)) s.aiLevel = value.aiLevel;
	if (RULES.includes(value.rule)) s.rule = value.rule;
	if (PLAYER_COLORS.some(c => c.id === value.color)) s.color = value.color;
	if (BOARD_THEMES.some(t => t.id === value.theme)) s.theme = value.theme;
	if (typeof value.showMistakes === 'boolean') s.showMistakes = value.showMistakes;
	return s;
}

function newSeat(kind: Seat['kind'], color: string, extra: Partial<Seat> = {}): Seat {
	return { kind, color, filled: 0, misses: 0, lockUntil: 0, ...extra };
}

function errorText(e: unknown): string {
	return (e instanceof Error) ? e.message : String(e);
}

function bitOf(digit: number): number {
	return 1 << (digit - 1);
}

function cellElement(index: number): HTMLElement | null {
	return document.querySelector(`.np-cell[data-i="${index}"]`);
}

/** mm:ss, or h:mm:ss past the hour. */
function formatTime(ms: number): string {
	const total = Math.max(0, Math.floor(ms / 1000));
	const h = Math.floor(total / 3600);
	const m = Math.floor((total % 3600) / 60);
	const s = total % 60;
	const mm = String(m).padStart(2, '0');
	const ss = String(s).padStart(2, '0');
	return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

function isLevel(value: unknown): value is Level {
	return LEVELS.includes(value as Level);
}

/** The other player of a room, seen from the user. */
function opponentOf(r: NumberPlaceRoom): { id: string; name: string } {
	const p = r.you === 'host' ? r.guest : r.host;
	return { id: p.id, name: p.displayName || p.id };
}

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			visibilityListener: null as (() => void) | null,
			keyListener: null as ((event: KeyboardEvent) => void) | null,
			// Reactive Localization snapshot — see composables/use-localization.ts.
			localization: createLocalizationSnapshot(),
			isReady: false,
			userId: '',
			// What the room's <wt-chat-thread> works with; marked raw (see appLaunch).
			threadApi: null as any,

			settings: defaultSettings(),

			// The cells of each box, box by box: the board is drawn box by box.
			boxes: BOX_CELLS,
			// The board as shown: the user's own board, or the shared one.
			kind: 'solo' as Kind,
			level: 2 as Level,
			aiLevel: 2 as AiLevel,
			givens: emptyGrid(),
			values: emptyGrid(),
			// Who wrote each number: 1 the user's seat, 2 the other seat, 0 nobody.
			owners: emptyGrid(),
			notes: emptyGrid(),
			// The solution of a game here, for marking mistakes; '' with another user.
			solutionText: '',
			selected: -1,
			notesMode: false,
			seats: [newSeat('human', DEFAULT_COLORS[0])] as Seat[],
			hasGame: false,
			over: false,
			mistakes: 0,
			hints: 0,
			// Undo steps left (alone); the steps themselves are `history`.
			undoCount: 0,
			// Shown time, in whole seconds, and the time now, while someone waits.
			clockSec: 0,
			now: Date.now(),
			// With another user: the number on its way to the server.
			pending: null as { cell: number; digit: number } | null,
			// A puzzle is being made for the game about to start.
			preparing: false,

			notice: '',
			result: null as GameResult | null,

			// The game with another user on the board, and the user's rooms.
			online: {
				roomId: null as string | null,
				status: '' as NumberPlaceRoom['status'] | '',
				mode: 'coop' as NumberPlaceMode,
				host: '',
				opponentId: '',
				opponentName: '',
				// The board and difficulty the host chose, and whether the guest is ready.
				theme: '',
				level: 2 as Level,
				guestReady: false,
				// A lobby request (a colour, the difficulty, ready, start) is on its way.
				busy: false,
				// Whether the game ended by giving up, and who did.
				resignedBy: '' as string,
				// The room's settings file, whose Chat conversation is the room's chat.
				chatFileId: '',
				invitations: [] as NumberPlaceRoom[],
				sent: [] as NumberPlaceRoom[],
				games: [] as NumberPlaceRoom[],
				loading: false,
				error: '',
			},

			// The lobby's form: what the next game will be.
			setup: {
				mode: 'solo' as Mode,
				level: 2 as Level,
				aiLevel: 2 as AiLevel,
				rule: 'race' as Rule,
				color: DEFAULT_COLORS[0],
				theme: DEFAULT_THEME,
				// The user chosen to invite.
				opponent: null as PrincipalInfo | null,
				busy: false,
				error: '',
			},

			resultDialog: { visible: false },
			resignDialog: { visible: false, busy: false },
			// Asks before a game in progress is left for the lobby.
			leaveDialog: { visible: false },
			// Asks before a game in progress is left for the room an invitation card names.
			joinDialog: { visible: false, room: null as NumberPlaceRoom | null },
		};
	},
	computed: {
		stageStyle(): Record<string, string> {
			// With another user the board is the one the host chose.
			const theme = (this.isOnline && this.online.theme) ? this.online.theme : this.settings.theme;
			return { ...findTheme(theme).vars };
		},
		isOnline(): boolean {
			return !!this.online.roomId;
		},
		isHost(): boolean {
			return this.isOnline && this.online.host === this.userId;
		},
		/** Against the computer. */
		isAi(): boolean {
			return !this.isOnline && this.seats.length > 1 && this.seats[1].kind === 'ai';
		},
		/** A number is checked as it is written (everything but solo). */
		checked(): boolean {
			return this.kind !== 'solo';
		},
		/** The room is being set up: waiting for an answer, or the lobby. */
		inLobby(): boolean {
			return this.isOnline && (this.online.status === 'waiting' || this.online.status === 'lobby');
		},
		/** What fills the main area: the lobby (setting up, or getting ready in a room) or the game. */
		scene(): 'lobby' | 'game' {
			if (this.isOnline) return this.inLobby ? 'lobby' : 'game';
			return this.hasGame ? 'game' : 'lobby';
		},
		/** The room's chat, for the panel: the conversation of the room's settings file. */
		chatRef(): { fileId: string } | null {
			return this.online.chatFileId ? { fileId: this.online.chatFileId } : null;
		},
		/** Numbers can be written now. */
		canPlay(): boolean {
			if (!this.hasGame || this.over || this.scene !== 'game') return false;
			return !this.isOnline || this.online.status === 'playing';
		},
		/** The board as seen: givens and the numbers written. */
		board(): number[] {
			return this.givens.map((g: number, i: number) => g || this.values[i]);
		},
		/** How many cells there were to fill. */
		empties(): number {
			return this.givens.filter((g: number) => !g).length;
		},
		/** Seconds the user still waits after a wrong number; 0 when it does not. */
		myLock(): number {
			const seat = this.seats[0];
			return seat ? Math.max(0, Math.ceil((seat.lockUntil - this.now) / 1000)) : 0;
		},
		/** Alone: the cells whose number clashes with another in a row, a column or a box. */
		conflictSet(): Set<number> {
			return this.kind === 'solo' ? conflicts(this.board) : new Set<number>();
		},
		cellViews(): { i: number; digit: number; notes: (number | '')[] | null; cls: Record<string, boolean>; style: Record<string, string>; label: string }[] {
			const sel = this.selected;
			const board = this.board;
			const selDigit = sel >= 0 ? board[sel] : 0;
			const showWrong = this.kind === 'solo' && this.settings.showMistakes && !!this.solutionText;
			const conflictSet = this.conflictSet;
			const pending = this.pending;
			return board.map((digit: number, i: number) => {
				const given = !!this.givens[i];
				const owner = this.owners[i];
				const seat = owner ? this.seats[owner - 1] : null;
				const color = seat ? findColor(seat.color) : null;
				const peer = sel >= 0 && sel !== i && (rowOf(sel) === rowOf(i) || colOf(sel) === colOf(i) || boxOf(sel) === boxOf(i));
				const style: Record<string, string> = {};
				if (!given && color) style.color = color.ink;
				if (this.kind === 'territory' && color) style['--np-tint'] = color.tint;
				const mask = this.notes[i];
				const isPending = !!pending && pending.cell === i;
				return {
					i,
					digit: isPending ? pending!.digit : digit,
					notes: (!digit && !isPending && mask) ? [1, 2, 3, 4, 5, 6, 7, 8, 9].map(d => (mask & bitOf(d)) ? d : '') : null,
					cls: {
						'is-given': given,
						'is-selected': sel === i,
						'is-peer': peer,
						'is-same': !!selDigit && digit === selDigit && sel !== i,
						'is-conflict': !given && conflictSet.has(i),
						'is-wrong': showWrong && !given && !!digit && String(digit) !== this.solutionText[i],
						'is-owned': this.kind === 'territory' && !!owner,
						'is-pending': isPending,
					},
					style,
					label: this.cellLabel(i, digit),
				};
			});
		},
		/** The number pad: each digit with how many are still to be written. */
		padDigits(): { digit: number; left: number }[] {
			const counts = new Array(10).fill(0);
			for (const v of this.board) counts[v]++;
			return [1, 2, 3, 4, 5, 6, 7, 8, 9].map(digit => ({ digit, left: Math.max(0, 9 - counts[digit]) }));
		},
		canUndo(): boolean {
			return this.kind === 'solo' && this.canPlay && this.undoCount > 0;
		},
		canResign(): boolean {
			return this.isOnline && this.online.status === 'playing' && !this.over;
		},
		/** Invitations waiting for the user's answer, for one of the online sections or both. */
		pendingCount(): number {
			return this.online.invitations.length;
		},
		pendingCoop(): number {
			return this.online.invitations.filter((r: NumberPlaceRoom) => r.mode === 'coop').length;
		},
		pendingVersus(): number {
			return this.online.invitations.filter((r: NumberPlaceRoom) => r.mode !== 'coop').length;
		},
		joinNote(): string {
			const r = this.joinDialog.room;
			const name = r ? opponentOf(r).name : '';
			return this.t('app.numberplace.online.joinConfirm', { name }, 'End this game and join {name}?');
		},
		leaveNote(): string {
			if (this.isOnline) {
				return this.t('app.numberplace.leave.online', { name: this.online.opponentName },
					'Leave the board and go back to the lobby? The game with {name} goes on and can be resumed from the lobby.');
			}
			return this.t('app.numberplace.leave.game', undefined, 'End this game and go back to the lobby?');
		},
		myColor(): string {
			return this.seats[0]?.color || '';
		},
		theirColor(): string {
			return this.seats[1]?.color || '';
		},
		/** The guest's choice is final once it is ready. */
		lobbyLocked(): boolean {
			return this.online.busy || (!this.isHost && this.online.guestReady);
		},
		canStartRoom(): boolean {
			return this.isHost && this.online.status === 'lobby' && this.online.guestReady && !this.online.busy && !this.preparing;
		},
		/** What the lobby waits for, seen from the user. */
		lobbyNote(): string {
			const o = this.online;
			const name = o.opponentName;
			if (this.preparing) return this.t('app.numberplace.status.preparing', undefined, 'Making a puzzle…');
			if (o.status === 'waiting') {
				return this.t('app.numberplace.online.waiting', { name }, 'Waiting for {name} to accept…');
			}
			if (this.isHost) {
				return o.guestReady ?
					this.t('app.numberplace.online.isReady', { name }, '{name} is ready') :
					this.t('app.numberplace.online.waitingReady', { name }, 'Waiting for {name} to get ready…');
			}
			return o.guestReady ?
				this.t('app.numberplace.online.waitingStart', { name }, 'Waiting for {name} to start…') :
				this.t('app.numberplace.online.chooseColor', undefined, 'Choose your colour and press Ready!');
		},
		/**
		 * The seats the panel shows: the game's or the room's; in the lobby,
		 * those of the game being set up, as the form is filled in.
		 */
		panelSeats(): Seat[] {
			if (this.hasGame) return this.seats;
			const s = this.setup;
			const me = newSeat('human', s.color, { userId: this.userId });
			if (s.mode === 'solo') return [me];
			if (s.mode === 'ai') return [me, newSeat('ai', otherColor(s.color))];
			return [me, newSeat('remote', otherColor(s.color), {
				userId: s.opponent?.identifier,
				name: s.opponent ? (s.opponent.displayName || s.opponent.identifier) : '',
			})];
		},
		seatCards(): { key: number; name: string; role: string; count: string; progress: number; thinking: boolean; waiting: boolean; style: Record<string, string>; vars: Record<string, string> }[] {
			const seats = this.panelSeats;
			const live = this.hasGame && !this.inLobby;
			const playing = live && this.canPlay;
			const empties = this.empties || 1;
			const coop = this.hasGame ? this.kind === 'coop' : this.setup.mode === 'coop';
			return seats.map((seat: Seat, k: number) => {
				const wait = Math.max(0, Math.ceil((seat.lockUntil - this.now) / 1000));
				const waiting = playing && wait > 0;
				let role: string;
				if (waiting) {
					role = this.t('app.numberplace.seat.waiting', { n: wait }, 'Waiting… {n}');
				} else if (seat.kind === 'ai') {
					role = this.aiLevelLabel(this.hasGame ? this.aiLevel : this.setup.aiLevel);
				} else if (seats.length === 1) {
					role = this.levelLabel(this.hasGame ? this.level : this.setup.level);
				} else if (seat.misses) {
					role = this.t('app.numberplace.seat.misses', { n: seat.misses }, '{n} wrong');
				} else {
					role = seat.kind !== 'remote' ? '' : coop ?
						this.t('app.numberplace.seat.partner', undefined, 'Partner') :
						this.t('app.numberplace.seat.opponent', undefined, 'Opponent');
				}
				return {
					key: k,
					name: this.seatName(seats, k),
					role,
					count: live ? String(seat.filled) : '',
					progress: live ? Math.min(1, seat.filled / empties) : 0,
					thinking: playing && seat.kind === 'ai' && !waiting,
					waiting,
					style: this.colorStyle(seat.color),
					vars: { '--np-player': findColor(seat.color).ink },
				};
			});
		},
		statusText(): string {
			if (this.preparing) return this.t('app.numberplace.status.preparing', undefined, 'Making a puzzle…');
			if (!this.hasGame) return '';
			if (this.inLobby) return this.lobbyNote;
			if (this.over) return this.resultTitle;
			if (this.myLock) return this.t('app.numberplace.status.locked', { n: this.myLock }, 'Wrong number! Wait {n} s');
			if (this.notesMode) return this.t('app.numberplace.status.notes', undefined, 'Writing notes');
			switch (this.kind) {
				case 'race': return this.t('app.numberplace.status.race', undefined, 'The first to fill the board wins');
				case 'territory': return this.t('app.numberplace.status.territory', undefined, 'The cells you fill right are yours');
				case 'coop': return this.t('app.numberplace.status.coop', undefined, 'Fill the board together');
				default: {
					const left = this.board.filter((v: number) => !v).length;
					return this.t('app.numberplace.status.left', { n: left }, '{n} cells to go');
				}
			}
		},
		clockText(): string {
			return formatTime(this.clockSec * 1000);
		},
		resultTitle(): string {
			const r = this.result as GameResult | null;
			if (!r) return '';
			if (r.winner === null) {
				if (!r.solved) {
					return this.online.resignedBy && this.online.resignedBy !== this.userId ?
						this.t('app.numberplace.result.theyGaveUp', { name: this.online.opponentName }, '{name} gave up') :
						this.t('app.numberplace.result.gaveUp', undefined, 'You gave up');
				}
				return this.kind === 'coop' ?
					this.t('app.numberplace.result.solvedTogether', undefined, 'Solved together!') :
					this.t('app.numberplace.result.solved', undefined, 'Solved!');
			}
			if (r.winner < 0) return this.t('app.numberplace.result.draw', undefined, "It's a draw");
			const seat = this.seats[r.winner];
			if (seat.kind === 'human') return this.t('app.numberplace.result.youWin', undefined, 'You win!');
			if (seat.kind === 'ai') return this.t('app.numberplace.result.computerWins', undefined, 'The computer wins');
			return this.t('app.numberplace.result.wins', { name: this.seatName(this.seats, r.winner) }, '{name} wins!');
		},
		/** Why the game ended, when it did not end on the board. */
		resultNote(): string {
			const r = this.result as GameResult | null;
			if (!r || !this.isOnline || !this.online.resignedBy || r.winner === null) return '';
			if (this.online.resignedBy === this.userId) {
				return this.t('app.numberplace.online.youResigned', undefined, 'You gave up');
			}
			return this.t('app.numberplace.online.resigned', { name: this.online.opponentName }, '{name} gave up');
		},
		resultTime(): string {
			return this.result ? formatTime(this.result.timeMs) : '';
		},
		levelOptions(): { level: Level; label: string; pips: number[] }[] {
			return LEVELS.map(level => ({ level, label: this.levelLabel(level), pips: LEVELS.slice(0, level) }));
		},
		aiLevelOptions(): { level: AiLevel; label: string; pips: number[] }[] {
			return AI_LEVELS.map(level => ({ level, label: this.aiLevelLabel(level), pips: AI_LEVELS.slice(0, level) }));
		},
		themeOptions(): { id: string; label: string; style: Record<string, string> }[] {
			return BOARD_THEMES.map(theme => ({
				id: theme.id,
				label: this.t(`app.numberplace.board.${theme.id}`, undefined, theme.label),
				style: {
					background: `linear-gradient(135deg, ${theme.vars['--np-cell']} 50%, ${theme.vars['--np-cell-alt']} 50%)`,
					boxShadow: `inset 0 0 0 4px ${theme.vars['--np-frame']}, inset 0 0 0 5px ${theme.vars['--np-frame-edge']}`,
				},
			}));
		},
		colorOptions(): { id: string; label: string; style: Record<string, string> }[] {
			return PLAYER_COLORS.map(c => ({
				id: c.id,
				label: this.t(`app.numberplace.color.${c.id}`, undefined, c.label),
				style: this.colorStyle(c.id),
			}));
		},
		/** What the chosen way to play is, in a line. */
		modeNote(): string {
			const notes: Record<Mode, [string, string]> = {
				solo: ['app.numberplace.mode.soloNote', 'Solve a puzzle at your own pace.'],
				ai: ['app.numberplace.mode.aiNote', 'Play the computer on the same puzzle.'],
				coop: ['app.numberplace.mode.coopNote', 'Fill one board together with another user.'],
				versus: ['app.numberplace.mode.versusNote', 'Play another user over the network, live.'],
			};
			const [key, fallback] = notes[this.setup.mode as Mode];
			return this.t(key, undefined, fallback);
		},
		ruleNote(): string {
			return this.setup.rule === 'race' ?
				this.t('app.numberplace.rule.raceNote', undefined, 'Each solves the same puzzle on a board of their own; the first to fill it wins.') :
				this.t('app.numberplace.rule.territoryNote', undefined, 'One board for both: a cell you fill right is yours; the one with more cells wins.');
		},
		/** The rooms listed in the lobby's online section, with what the user can do about each. */
		roomCards(): { room: NumberPlaceRoom; name: string; note: string; action: 'answer' | 'cancel' | 'resume' }[] {
			const o = this.online;
			const coop = this.setup.mode === 'coop';
			const fits = (r: NumberPlaceRoom) => (r.mode === 'coop') === coop;
			const notes = {
				answer: () => this.t('app.numberplace.online.invitesYou', undefined, 'Invites you to a game'),
				resume: (r: NumberPlaceRoom) => r.status === 'lobby' ?
					this.t('app.numberplace.online.lobby', undefined, 'Getting ready') :
					this.t('app.numberplace.online.playing', undefined, 'Playing'),
				cancel: () => this.t('app.numberplace.online.awaiting', undefined, 'Waiting for an answer'),
			};
			const card = (r: NumberPlaceRoom, action: 'answer' | 'cancel' | 'resume') => ({
				room: r,
				name: opponentOf(r).name,
				note: `${notes[action](r)} · ${this.roomModeLabel(r.mode)} · ${this.levelLabel(isLevel(r.level) ? r.level : 2)}`,
				action,
			});
			return [
				...o.invitations.filter(fits).map(r => card(r, 'answer')),
				...o.games.filter(fits).map(r => card(r, 'resume')),
				...o.sent.filter(fits).map(r => card(r, 'cancel')),
			];
		},
		isOnlineSetup(): boolean {
			return this.setup.mode === 'coop' || this.setup.mode === 'versus';
		},
		canStart(): boolean {
			if (this.preparing || this.setup.busy) return false;
			return !this.isOnlineSetup || !!this.setup.opponent;
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
				if (type === 'app-reopen') {
					// The app is a singleton: a card's button reaches it here.
					vm.openInvitedRoom(payload.options?.roomId);
					return;
				}
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);

			// Alone or against the computer, the clock and the computer stop
			// while the window is not looked at. With another user, a message
			// may have been missed meanwhile: the room is read again.
			vm.visibilityListener = () => {
				if (document.visibilityState === 'visible') {
					if (vm.online.roomId) vm.reloadRoom();
					else vm.resumeLocal();
				} else if (!vm.online.roomId) {
					vm.pauseLocal();
				}
			};
			document.addEventListener('visibilitychange', vm.visibilityListener);

			vm.keyListener = (event: KeyboardEvent) => vm.onKey(event);
			window.addEventListener('keydown', vm.keyListener);

			window.appLaunch = async (instance: ApplicationInstance, options?: { roomId?: string }) => {
				vm.instance = this.$markRaw(instance);
				refreshLocalization(vm.localization, vm.instance);
				vm.userId = instance.currentUser?.id || '';

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				// --- Readiness gate --- (see index.html)
				try {
					await Promise.all([
						initUi({ popupAdapter: createShellPopupAdapter(instance) }),
						loadChatThreadTemplate(),
					]);
				} catch (e) {
					console.warn('[NumberPlace] Failed to load component templates:', e);
				}

				fx = new Effects();
				gen = new GenClient(new URL('./gen-worker.js?v=__BUILD_VERSION__', import.meta.url));
				service = new NumberPlaceServiceGraphQL(instance.api.graphql);
				// Marked raw so the reactive system never Proxy-wraps the services:
				// they carry private fields, which throw when called through a Proxy.
				vm.threadApi = this.$markRaw({
					chat: new ChatServiceGraphQL(instance.api.graphql),
					eventHub: instance.api.eventHub,
					// What attaching files needs: the content service of this
					// workspace, and whose home the uploads go to.
					content: instance.api.content,
					workspace: instance.api.workspace,
					userId: vm.userId,
				});

				instance.setBeforeCloseCallback(async () => {
					await vm.flushSave();
					vm.dispose();
					return true;
				});

				const saved = await vm.loadState();
				vm.settings = normalizeSettings(saved?.settings);

				vm.isReady = true;
				await new Promise<void>((resolve) => vm.$nextTick(() => resolve()));

				tickTimer = setInterval(() => vm.tick(), TICK_MS);
				vm.watchRooms();
				const roomsLoaded = vm.loadRooms();
				// The window is shown once the first scene is in place, so it
				// does not pass through the lobby on its way to a room.
				try {
					// Launched from an invitation card: that room, before anything saved.
					if (!await vm.openInvitedRoom(options?.roomId) && !await vm.resumeRoom(saved?.roomId) &&
						!vm.resumeGame(saved?.game)) {
						// The lobby opens on an online section when an invitation is waiting.
						await roomsLoaded;
						vm.showLobby();
					}
				} finally {
					await new Promise<void>((resolve) => vm.$nextTick(() => resolve()));
					instance.notifyLaunched();
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
			if (this.keyListener) {
				window.removeEventListener('keydown', this.keyListener);
			}
			this.dispose();
		},
		dispose() {
			generation++;
			if (noticeTimer) clearTimeout(noticeTimer);
			if (tickTimer) clearInterval(tickTimer);
			tickTimer = null;
			this.stopAi();
			if (unwatchTopics) {
				try { unwatchTopics(); } catch { /* ignore */ }
				unwatchTopics = null;
			}
			gen?.destroy();
			gen = null;
			fx?.destroy();
			fx = null;
			service = null;
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
		// The lobby: setting up the next game, and leaving the board for it
		// =====================================================================

		/**
		 * Fills the lobby's form from the settings. The section opened is the
		 * one asked for, else an online one when an invitation is waiting (it
		 * is answered there), else the last one used.
		 */
		showLobby(mode?: Mode) {
			const s = this.settings;
			const invited: Mode | null = this.pendingVersus ? 'versus' : this.pendingCoop ? 'coop' : null;
			this.setup = {
				mode: mode ?? invited ?? s.mode,
				level: s.level,
				aiLevel: s.aiLevel,
				rule: s.rule,
				color: s.color,
				theme: s.theme,
				opponent: null,
				busy: false,
				error: '',
			};
			this.loadRooms();
		},
		/**
		 * Ends what is on the board and shows the lobby. A game here is
		 * dropped; a room is only left (it goes on, and is listed in the
		 * lobby to be resumed).
		 */
		goToLobby(mode?: Mode) {
			generation++;
			this.stopAi();
			fx?.clear();
			gen?.cancel();
			solution = null;
			aiBoard = null;
			history = [];
			this.undoCount = 0;
			this.stopClock();
			const back: Mode | undefined = mode ?? (this.isOnline ? (this.online.mode === 'coop' ? 'coop' : 'versus') : undefined);
			this.leaveRoom();
			this.hasGame = false;
			this.over = false;
			this.result = null;
			this.pending = null;
			this.preparing = false;
			this.selected = -1;
			this.notesMode = false;
			this.resultDialog.visible = false;
			this.leaveDialog.visible = false;
			this.setNotice('');
			this.showLobby(back);
			this.scheduleSave();
		},
		/**
		 * The toolbar's lobby button: a game in progress is left only after
		 * asking. A game with a result is over even when the board is not (a
		 * room given up).
		 */
		requestLobby() {
			if (this.scene === 'game' && !this.over && !this.result) {
				this.leaveDialog.visible = true;
				return;
			}
			this.goToLobby();
		},
		closeLeave() {
			this.leaveDialog.visible = false;
		},
		confirmLeave() {
			this.goToLobby();
		},
		/** The lobby's Start (or Invite): the form becomes the settings. */
		async startFromLobby() {
			const d = this.setup;
			if (!this.canStart) return;
			this.settings = { ...this.settings, mode: d.mode, level: d.level, aiLevel: d.aiLevel, rule: d.rule, color: d.color, theme: d.theme };
			this.scheduleSave();
			if (this.isOnlineSetup) {
				this.sendInvitation();
				return;
			}
			await this.startGame();
		},

		// =====================================================================
		// A game alone or against the computer: starting and resuming
		// =====================================================================

		/** A new game with the current settings, once its puzzle is made. */
		async startGame() {
			if (!gen || this.preparing) return;
			const s = this.settings;
			const gen0 = ++generation;
			this.preparing = true;
			let puzzle: Puzzle;
			try {
				puzzle = await gen.generate(s.level);
			} catch (e) {
				if (gen0 === generation) {
					this.preparing = false;
					if (errorText(e) !== 'cancelled') this.setNotice(this.t('app.numberplace.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				}
				return;
			}
			if (gen0 !== generation) return;
			this.preparing = false;
			this.leaveRoom();
			const seats: Seat[] = s.mode === 'ai' ?
				[newSeat('human', s.color), newSeat('ai', otherColor(s.color))] :
				[newSeat('human', s.color)];
			this.beginLocal({
				kind: s.mode === 'ai' ? s.rule : 'solo',
				level: puzzle.level,
				aiLevel: s.aiLevel,
				givens: puzzle.givens,
				solution: puzzle.solution,
				values: formatGrid(emptyGrid()),
				owners: formatGrid(emptyGrid()),
				notes: emptyGrid(),
				aiBoard: s.mode === 'ai' && s.rule === 'race' ? puzzle.givens : null,
				colors: [seats[0].color, seats[1]?.color || otherColor(seats[0].color)],
				elapsed: 0,
				mistakes: 0,
				hints: 0,
				aiMisses: 0,
			}, seats.length > 1);
		},
		/** Puts a saved game back on the board; false when there is none worth resuming. */
		resumeGame(saved: SavedGame | null | undefined): boolean {
			if (!saved || !['solo', 'race', 'territory'].includes(saved.kind) || !isLevel(saved.level)) return false;
			try {
				const givens = parseGrid(saved.givens);
				const sol = parseGrid(saved.solution);
				const values = parseGrid(saved.values);
				parseGrid(saved.owners);
				if (saved.aiBoard) parseGrid(saved.aiBoard);
				if (!Array.isArray(saved.notes) || saved.notes.length !== CELLS) return false;
				if (sol.some((v, i) => !v || (givens[i] && givens[i] !== v))) return false;
				if (values.some((v, i) => v && givens[i])) return false;
			} catch (e) {
				console.warn('[NumberPlace] Saved game could not be read:', e);
				return false;
			}
			const colors = Array.isArray(saved.colors) && saved.colors.length === 2 &&
				saved.colors.every(c => PLAYER_COLORS.some(p => p.id === c)) ? saved.colors : [...DEFAULT_COLORS] as [string, string];
			const vsAi = saved.kind !== 'solo';
			return this.beginLocal({ ...saved, colors: [colors[0], colors[1]] }, vsAi);
		},
		/** Shows a game here; false when it is already over. */
		beginLocal(g: SavedGame, vsAi: boolean): boolean {
			generation++;
			this.stopAi();
			fx?.clear();
			solution = parseGrid(g.solution);
			const values = parseGrid(g.values);
			const owners = parseGrid(g.owners);
			const givens = parseGrid(g.givens);
			aiBoard = g.aiBoard ? parseGrid(g.aiBoard) : null;
			if (g.kind === 'race' && !aiBoard) aiBoard = givens.slice();
			history = [];
			this.undoCount = 0;
			const seats: Seat[] = [newSeat('human', g.colors[0], { misses: g.kind === 'solo' ? 0 : (g.mistakes || 0) })];
			if (vsAi) seats.push(newSeat('ai', g.colors[1], { misses: g.aiMisses || 0 }));
			this.kind = g.kind;
			this.level = g.level;
			this.aiLevel = AI_LEVELS.includes(g.aiLevel) ? g.aiLevel : 2;
			this.givens = givens;
			this.values = values;
			this.owners = owners;
			this.notes = g.notes.map(n => (typeof n === 'number' ? n & 511 : 0));
			this.solutionText = g.solution;
			this.seats = seats;
			this.mistakes = g.mistakes || 0;
			this.hints = g.hints || 0;
			this.countFilled();
			this.hasGame = true;
			this.over = false;
			this.result = null;
			this.pending = null;
			this.selected = -1;
			this.notesMode = false;
			this.resultDialog.visible = false;
			this.setNotice('');
			clockBase = g.elapsed || 0;
			clockSince = null;
			if (this.localOutcome()) {
				// Saved just as it ended: nothing to resume.
				solution = null;
				aiBoard = null;
				this.hasGame = false;
				return false;
			}
			this.resumeLocal();
			this.scheduleSave();
			return true;
		},
		/** The filled counts of the seats, from the boards. */
		countFilled() {
			if (this.isOnline) return;
			const mine = this.values.filter((v: number, i: number) => v && this.owners[i] !== 2).length;
			this.seats[0].filled = mine;
			if (this.seats[1]) {
				this.seats[1].filled = this.kind === 'race' && aiBoard ?
					aiBoard.filter((v, i) => v && !this.givens[i]).length :
					this.owners.filter((o: number) => o === 2).length;
			}
		},
		/** The clock and the computer go on (a game here, shown and not over). */
		resumeLocal() {
			if (this.isOnline || !this.hasGame || this.over || document.visibilityState !== 'visible') return;
			this.startClock();
			this.scheduleAi();
		},
		pauseLocal() {
			if (this.isOnline || !this.hasGame) return;
			this.stopClock();
			this.stopAi();
			this.scheduleSave();
		},

		// =====================================================================
		// The clock
		// =====================================================================

		clockMs(): number {
			return clockBase + (clockSince !== null ? Date.now() - clockSince : 0);
		},
		startClock() {
			if (clockSince === null) clockSince = Date.now();
			this.tick();
		},
		stopClock() {
			if (clockSince !== null) {
				clockBase += Date.now() - clockSince;
				clockSince = null;
			}
			this.tick();
		},
		/** Moves the shown time on, and the countdowns of whoever waits. */
		tick() {
			const sec = Math.floor(this.clockMs() / 1000);
			if (sec !== this.clockSec) this.clockSec = sec;
			const now = Date.now();
			if (this.seats.some((s: Seat) => s.lockUntil > now - TICK_MS * 2)) this.now = now;
		},

		// =====================================================================
		// The board: choosing a cell and writing in it
		// =====================================================================

		onCellClick(index: number) {
			if (!this.hasGame || this.scene !== 'game') return;
			this.selected = index;
		},
		/** The keyboard: digits write, arrows move, Backspace erases, N switches notes. */
		onKey(event: KeyboardEvent) {
			if (!this.canPlay || this.anyDialog()) return;
			const target = event.target as HTMLElement | null;
			if (target && (target.closest('input, textarea, select, [contenteditable="true"], wt-chat-thread'))) return;
			const key = event.key;
			if (event.altKey || event.metaKey || event.ctrlKey) {
				if ((key === 'z' || key === 'Z') && (event.ctrlKey || event.metaKey)) {
					event.preventDefault();
					this.undo();
				}
				return;
			}
			// Shift with a digit writes a note whatever the mode (the key then
			// reads as a symbol, so the physical key is looked at).
			if (event.shiftKey && /^(Digit|Numpad)[1-9]$/.test(event.code)) {
				event.preventDefault();
				this.writeNote(Number(event.code.slice(-1)));
				return;
			}
			if (/^[1-9]$/.test(key)) {
				event.preventDefault();
				this.input(Number(key));
				return;
			}
			if (key === 'Backspace' || key === 'Delete' || key === '0') {
				event.preventDefault();
				this.erase();
				return;
			}
			if (key === 'n' || key === 'N') {
				event.preventDefault();
				this.toggleNotesMode();
				return;
			}
			const moves: Record<string, [number, number]> = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] };
			const move = moves[key];
			if (move) {
				event.preventDefault();
				const at = this.selected < 0 ? 40 : this.selected;
				const r = (rowOf(at) + move[0] + 9) % 9;
				const c = (colOf(at) + move[1] + 9) % 9;
				this.selected = this.selected < 0 ? 40 : r * 9 + c;
			}
		},
		anyDialog(): boolean {
			return (!!this.result && this.resultDialog.visible) || this.resignDialog.visible || this.leaveDialog.visible || this.joinDialog.visible;
		},
		toggleNotesMode() {
			this.notesMode = !this.notesMode;
		},
		/** The pad's digit: a number, or a note in notes mode. */
		input(digit: number) {
			if (!this.canPlay) return;
			if (this.selected < 0) {
				this.setNotice(this.t('app.numberplace.status.pickCell', undefined, 'Choose a cell first'));
				return;
			}
			if (this.notesMode) {
				this.writeNote(digit);
				return;
			}
			this.write(this.selected, digit);
		},
		/** Turns a note on or off in the chosen cell. */
		writeNote(digit: number) {
			const i = this.selected;
			if (!this.canPlay || i < 0) return;
			if (this.board[i] || (this.pending && this.pending.cell === i)) {
				this.nope(i);
				return;
			}
			this.remember([i]);
			this.notes[i] = this.notes[i] ^ bitOf(digit);
			this.scheduleSave();
		},
		/** Erases the chosen cell: its number (alone) or its notes. */
		erase() {
			const i = this.selected;
			if (!this.canPlay || i < 0 || this.givens[i]) return;
			if (this.values[i]) {
				// Outside solo a number is always right, and stays.
				if (this.checked) return;
				this.remember([i]);
				this.values[i] = 0;
				this.owners[i] = 0;
			} else if (this.notes[i]) {
				this.remember([i]);
				this.notes[i] = 0;
			} else {
				return;
			}
			this.countFilled();
			this.scheduleSave();
		},
		/** Writes a number: freely alone, checked otherwise. */
		write(i: number, digit: number) {
			if (this.givens[i]) {
				this.nope(i);
				return;
			}
			if (this.kind === 'solo') {
				this.writeSolo(i, digit);
			} else if (this.isOnline) {
				this.writeOnline(i, digit);
			} else {
				this.writeChecked(i, digit);
			}
		},
		writeSolo(i: number, digit: number) {
			if (!solution) return;
			const peers = PEERS[i].filter(p => this.notes[p] & bitOf(digit));
			this.remember([i, ...peers]);
			if (this.values[i] === digit) {
				// The same number again takes it out.
				this.values[i] = 0;
				this.owners[i] = 0;
				this.countFilled();
				this.scheduleSave();
				return;
			}
			this.fill(i, digit, 1);
			if (digit !== solution[i]) {
				this.mistakes++;
				if (this.settings.showMistakes) this.nope(i);
			}
			this.afterLocalFill(i);
		},
		/** Against the computer: a right number fills the cell, a wrong one makes the user wait. */
		writeChecked(i: number, digit: number) {
			if (!solution) return;
			const seat = this.seats[0];
			if (this.values[i]) {
				this.nope(i);
				return;
			}
			if (this.myLock) {
				this.nope(i);
				return;
			}
			if (digit !== solution[i]) {
				seat.misses++;
				this.mistakes = seat.misses;
				seat.lockUntil = Date.now() + LOCK_MS;
				this.now = Date.now();
				this.nope(i);
				this.scheduleSave();
				return;
			}
			this.fill(i, digit, 1);
			this.afterLocalFill(i);
		},
		/** A number was written here (alone or against the computer): effects, the end, saving. */
		afterLocalFill(i: number) {
			this.countFilled();
			this.celebrate(i, 0);
			const outcome = this.localOutcome();
			if (outcome) {
				this.finishLocal(outcome);
			} else if (this.kind === 'solo' && this.board.every((v: number) => v)) {
				this.setNotice(this.t('app.numberplace.status.notYet', undefined, 'Something is still wrong'), LONG_NOTICE_MS);
			}
			this.scheduleSave();
		},
		/** Puts a number in a cell for a seat (1 or 2), clearing the notes it settles. */
		fill(i: number, digit: number, owner: number) {
			this.values[i] = digit;
			this.owners[i] = owner;
			this.notes[i] = 0;
			const bit = bitOf(digit);
			for (const p of PEERS[i]) {
				if (this.notes[p] & bit) this.notes[p] = this.notes[p] & ~bit;
			}
		},
		/** The sparkle of a filled cell, and a wave over a row, column or box it completes. */
		async celebrate(i: number, seatIndex: number) {
			const seat = this.seats[seatIndex];
			const color = findColor(seat ? seat.color : DEFAULT_COLORS[0]);
			await new Promise<void>((resolve) => this.$nextTick(() => resolve()));
			const el = cellElement(i);
			if (!fx || !el) return;
			fx.playAt(el, 'place', { colors: color.sparks });
			const digit = el.querySelector('.np-digit');
			if (digit) fx.pop(digit, 1.18);
			const board = this.board;
			const right = (j: number) => !!board[j] && (!this.solutionText || this.kind !== 'solo' || String(board[j]) === this.solutionText[j]);
			for (const unit of unitsOf(i)) {
				if (!unit.every(right)) continue;
				unit.forEach((j, k) => setTimeout(() => {
					const cell = cellElement(j);
					if (cell && fx) fx.pulse(cell);
				}, k * 35));
			}
		},
		/** A refused number: the cell shakes. */
		nope(i: number) {
			const el = cellElement(i);
			if (!el || !fx) return;
			fx.playAt(el, 'nope');
			fx.shake(el);
		},
		/** Remembers cells as they are, for undo (alone only). */
		remember(cells: number[]) {
			if (this.kind !== 'solo') return;
			history.push(cells.map(i => ({ i, value: this.values[i], owner: this.owners[i], notes: this.notes[i] })));
			this.undoCount = history.length;
		},
		undo() {
			if (!this.canUndo) return;
			const step = history.pop();
			if (!step) return;
			for (const c of step) {
				this.values[c.i] = c.value;
				this.owners[c.i] = c.owner;
				this.notes[c.i] = c.notes;
			}
			this.undoCount = history.length;
			this.countFilled();
			this.scheduleSave();
		},
		/** Alone: writes the right number in the chosen cell, or in the cell easiest to see. */
		hint() {
			if (!this.canPlay || this.kind !== 'solo' || !solution) return;
			let i = this.selected;
			if (i < 0 || this.givens[i] || this.values[i] === solution[i]) {
				// A wrong number first; else the cell with the fewest candidates.
				i = this.values.findIndex((v: number, j: number) => v && v !== solution![j]);
				if (i < 0) {
					let fewest = 10;
					const board = this.board;
					for (let j = 0; j < CELLS; j++) {
						if (board[j]) continue;
						let n = 0;
						for (let m = candidates(board, j); m; m &= m - 1) n++;
						if (n < fewest) {
							fewest = n;
							i = j;
						}
					}
				}
			}
			if (i < 0) return;
			this.selected = i;
			const peers = PEERS[i].filter(p => this.notes[p] & bitOf(solution![i]));
			this.remember([i, ...peers]);
			this.hints++;
			this.fill(i, solution[i], 1);
			this.afterLocalFill(i);
		},
		toggleMistakes() {
			this.settings = { ...this.settings, showMistakes: !this.settings.showMistakes };
			this.scheduleSave();
		},

		// =====================================================================
		// The computer
		// =====================================================================

		stopAi() {
			if (aiTimer) clearTimeout(aiTimer);
			aiTimer = null;
		},
		/** Plans the computer's next step, when it is playing. */
		scheduleAi() {
			this.stopAi();
			if (!this.isAi || this.over || !solution || document.visibilityState !== 'visible') return;
			const board = this.kind === 'race' ? aiBoard : this.board;
			if (!board) return;
			const step = nextStep(board, solution, this.aiLevel, this.level);
			if (!step) return;
			const seat = this.seats[1];
			const wait = Math.max(step.delay, seat.lockUntil - Date.now());
			const gen0 = generation;
			aiTimer = setTimeout(() => {
				aiTimer = null;
				if (gen0 === generation) this.aiPlay(step.cell, step.digit, step.miss);
			}, wait);
		},
		aiPlay(cell: number, digit: number, miss: boolean) {
			if (this.over || !solution) return;
			const seat = this.seats[1];
			if (miss) {
				seat.misses++;
				seat.lockUntil = Date.now() + LOCK_MS;
				this.now = Date.now();
				this.scheduleSave();
				this.scheduleAi();
				return;
			}
			if (this.kind === 'race') {
				if (!aiBoard || aiBoard[cell]) {
					this.scheduleAi();
					return;
				}
				aiBoard[cell] = digit;
			} else {
				// The user may have taken the cell meanwhile.
				if (this.board[cell]) {
					this.scheduleAi();
					return;
				}
				this.fill(cell, digit, 2);
				this.celebrate(cell, 1);
			}
			this.countFilled();
			const outcome = this.localOutcome();
			if (outcome) {
				this.finishLocal(outcome);
			} else {
				this.scheduleAi();
			}
			this.scheduleSave();
		},

		// =====================================================================
		// The end of a game here
		// =====================================================================

		/** How a game here ended, or null while it goes on. */
		localOutcome(): GameResult | null {
			if (!solution) return null;
			const sol = solution;
			const board = this.board;
			const scores = this.seats.map((s: Seat) => s.filled);
			const time = this.clockMs();
			switch (this.kind) {
				case 'solo':
					return board.every((v: number, i: number) => v === sol[i]) ? { winner: null, solved: true, timeMs: time, scores } : null;
				case 'race':
					if (board.every((v: number) => v)) return { winner: 0, solved: true, timeMs: time, scores };
					if (aiBoard && aiBoard.every(v => v)) return { winner: 1, solved: true, timeMs: time, scores };
					return null;
				default: {
					if (!board.every((v: number) => v)) return null;
					const winner = scores[0] === scores[1] ? -1 : scores[0] > scores[1] ? 0 : 1;
					return { winner, solved: true, timeMs: time, scores };
				}
			}
		},
		finishLocal(result: GameResult) {
			this.stopAi();
			this.stopClock();
			this.over = true;
			this.selected = -1;
			this.showResult({ ...result, timeMs: this.clockMs() });
		},
		showResult(result: GameResult) {
			this.result = result;
			this.resultDialog.visible = true;
			this.pending = null;
			if (!fx) return;
			// Over the whole view: the result dialog covers the board's centre.
			if (result.winner === null) {
				if (result.solved) fx.play('win', null, null, { colors: findColor(this.seats[0].color).sparks });
				else fx.play('petals');
			} else if (result.winner < 0) {
				fx.play('hearts');
			} else if (this.seats[result.winner].kind === 'human') {
				fx.play('win', null, null, { colors: findColor(this.seats[result.winner].color).sparks });
			} else {
				fx.play('petals');
			}
		},
		closeResult() {
			this.resultDialog.visible = false;
		},
		/** The result's "Play again": a rematch with another user, or the same settings. */
		playAgain() {
			if (this.isOnline) {
				this.rematch();
				return;
			}
			this.resultDialog.visible = false;
			this.startGame();
		},

		// =====================================================================
		// Games with another user
		// =====================================================================

		/** Receives what happens in the user's rooms, for as long as the app runs. */
		watchRooms() {
			const hub = this.instance?.api.eventHub;
			if (!hub || unwatchTopics) return;
			try {
				unwatchTopics = hub.watchTopic(NUMBERPLACE_TOPICS, (event: TopicMessageEvent) => {
					if (isNumberPlaceMessage(event.payload)) this.onRoomMessage(event.payload);
				});
			} catch (e) {
				console.warn('[NumberPlace] Rooms cannot be watched:', e);
			}
		},
		async onRoomMessage(m: NumberPlaceMessage) {
			const mine = !!room && m.roomId === room.id;
			const byMe = m.by === this.userId;
			switch (m.type) {
				case 'invited':
					await this.loadRooms();
					if (!byMe) {
						const r = this.online.invitations.find((x: NumberPlaceRoom) => x.id === m.roomId);
						if (r) {
							this.setNotice(this.t('app.numberplace.online.invitedYou', { name: opponentOf(r).name },
								'{name} invites you to a game'), LONG_NOTICE_MS);
						}
					}
					return;
				case 'accepted':
					if (mine) {
						await this.reloadRoom();
						if (!byMe) {
							this.setNotice(this.t('app.numberplace.online.accepted', { name: this.online.opponentName }, '{name} accepted'), LONG_NOTICE_MS);
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
							this.setNotice(this.t('app.numberplace.online.isReady', { name: this.online.opponentName }, '{name} is ready'), LONG_NOTICE_MS);
						}
					}
					return;
				case 'started':
					if (mine) await this.reloadRoom();
					this.loadRooms();
					return;
				case 'declined':
				case 'left':
				case 'cancelled':
					// The room shown is over, whoever ended it: the other player,
					// or this user elsewhere (the invitation's card in the chat,
					// another window). Ended here, the room was already left.
					if (mine) {
						const name = this.online.opponentName;
						this.goToLobby();
						if (!byMe) {
							this.setNotice(m.type === 'cancelled' ?
								this.t('app.numberplace.online.cancelledYou', { name }, '{name} took the invitation back') :
								m.type === 'left' ?
									this.t('app.numberplace.online.leftYou', { name }, '{name} left') :
									this.t('app.numberplace.online.declinedYou', { name }, '{name} declined'), LONG_NOTICE_MS);
						}
					}
					this.loadRooms();
					return;
				case 'fill':
					if (!mine || !room) return;
					this.onFillMessage(m, byMe);
					return;
				case 'miss':
					if (!mine || byMe || !room) return;
					if (this.seats[1]) {
						this.seats[1].misses = m.misses ?? this.seats[1].misses + 1;
						this.seats[1].lockUntil = Date.now() + (m.lockMs ?? LOCK_MS);
						this.now = Date.now();
					}
					return;
				case 'resigned':
					if (mine) await this.reloadRoom();
					this.loadRooms();
					return;
				case 'expired':
					// The room was idle for long and has been removed.
					if (mine) {
						const name = this.online.opponentName;
						this.goToLobby();
						this.setNotice(this.t('app.numberplace.online.expired', { name },
							'The game with {name} was closed after a long time without a move'), LONG_NOTICE_MS);
					}
					this.loadRooms();
					return;
			}
		},
		/**
		 * A cell was filled in the room shown. On the shared board the other
		 * player's number is shown at once when the cell is still empty here;
		 * in a race only how far the other player got is told. The end of the
		 * game, and anything that does not fit, is read from the room.
		 */
		onFillMessage(m: NumberPlaceMessage, byMe: boolean) {
			const seat = this.seats[byMe ? 0 : 1];
			if (seat && typeof m.filled === 'number') seat.filled = m.filled;
			if (m.over) {
				this.reloadRoom();
				return;
			}
			if (byMe || this.kind === 'race') return;
			const cell = m.cell;
			const digit = m.digit;
			if (typeof cell !== 'number' || typeof digit !== 'number' || cell < 0 || cell >= CELLS || digit < 1 || digit > 9) {
				this.reloadRoom();
				return;
			}
			if (this.givens[cell] || (this.values[cell] && this.values[cell] !== digit)) {
				this.reloadRoom();
				return;
			}
			if (this.values[cell] === digit) return;
			this.fill(cell, digit, 2);
			this.celebrate(cell, 1);
		},

		/** Reads the user's rooms for the lobby and the badge. */
		async loadRooms() {
			if (!service) return;
			const o = this.online;
			o.loading = true;
			try {
				const rooms = await service.listRooms();
				o.invitations = rooms.filter(r => r.status === 'waiting' && r.you === 'guest');
				o.sent = rooms.filter(r => r.status === 'waiting' && r.you === 'host');
				o.games = rooms.filter(r => r.status === 'lobby' || r.status === 'playing');
				o.error = '';
			} catch (e) {
				o.error = errorText(e);
			} finally {
				o.loading = false;
			}
		},
		/**
		 * Opens the room an invitation card names. The invited user accepts
		 * on the way in, so the card's button leads straight to getting
		 * ready; the host, or either player later, is taken to the room as
		 * it is. A game here in progress is not dropped for it unasked.
		 * False when there is no such room to open any more.
		 */
		async openInvitedRoom(roomId: string | null | undefined): Promise<boolean> {
			if (!roomId || !service) return false;
			let r: NumberPlaceRoom;
			try {
				r = await service.getRoom(roomId);
			} catch (e) {
				this.setNotice(this.t('app.numberplace.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				return false;
			}
			if (r.status !== 'waiting' && r.status !== 'lobby' && r.status !== 'playing') {
				this.setNotice(this.t('app.numberplace.online.gone', undefined, 'This invitation is no longer open'), LONG_NOTICE_MS);
				return false;
			}
			if (this.hasGame && !this.over && !this.isOnline) {
				this.joinDialog = { visible: true, room: r };
				return true;
			}
			return this.joinRoom(r);
		},
		/** Accepts the room, when it is an invitation to the user, and shows it. */
		async joinRoom(r: NumberPlaceRoom): Promise<boolean> {
			if (!service) return false;
			try {
				if (r.status === 'waiting' && r.you === 'guest') {
					r = await service.accept(r.id, this.settings.color);
				}
				if (r.id === this.online.roomId) {
					this.syncRoom(r);
				} else {
					this.enterRoom(r);
				}
				this.loadRooms();
				return true;
			} catch (e) {
				this.setNotice(this.t('app.numberplace.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				return false;
			}
		},
		closeJoin() {
			this.joinDialog = { visible: false, room: null };
		},
		confirmJoin() {
			const r = this.joinDialog.room;
			this.joinDialog = { visible: false, room: null };
			if (r) this.joinRoom(r);
		},
		/** Opens the room that was on the board when the app was last closed. */
		async resumeRoom(roomId: string | null | undefined): Promise<boolean> {
			if (!roomId || !service) return false;
			try {
				const r = await service.getRoom(roomId);
				if (r.status !== 'waiting' && r.status !== 'lobby' && r.status !== 'playing') return false;
				this.enterRoom(r);
				return true;
			} catch (e) {
				console.warn('[NumberPlace] The last game could not be reopened:', e);
				return false;
			}
		},
		/** Shows a room: getting ready in the lobby, or its board. */
		enterRoom(r: NumberPlaceRoom) {
			generation++;
			this.stopAi();
			fx?.clear();
			gen?.cancel();
			solution = null;
			aiBoard = null;
			history = [];
			this.undoCount = 0;
			room = r;
			const o = this.online;
			const opponent = opponentOf(r);
			o.roomId = r.id;
			o.opponentId = opponent.id;
			o.opponentName = opponent.name;
			o.busy = false;
			const me = r.you === 'host' ? r.host : r.guest;
			const them = r.you === 'host' ? r.guest : r.host;
			this.seats = [
				newSeat('human', me.color, { userId: me.id }),
				newSeat('remote', them.color, { userId: them.id, name: them.displayName || them.id }),
			];
			this.kind = r.mode;
			this.solutionText = '';
			this.mistakes = 0;
			this.hints = 0;
			this.hasGame = true;
			this.over = false;
			this.result = null;
			this.pending = null;
			this.preparing = false;
			this.selected = -1;
			this.notesMode = false;
			this.resultDialog.visible = false;
			this.setNotice('');
			this.loadBoard(r);
			this.applyRoom(r);
			this.scheduleSave();
			if (r.status === 'finished') this.finishRoom(r);
		},
		/** Puts the room's puzzle and its filled cells on the board, notes cleared. */
		loadBoard(r: NumberPlaceRoom) {
			let givens = emptyGrid();
			try {
				if (r.givens) givens = parseGrid(r.givens);
			} catch (e) {
				console.warn('[NumberPlace] The room has no readable puzzle:', e);
			}
			const values = emptyGrid();
			const owners = emptyGrid();
			for (const c of r.cells || []) {
				if (c.cell < 0 || c.cell >= CELLS || givens[c.cell]) continue;
				values[c.cell] = c.digit;
				owners[c.cell] = c.by === this.userId ? 1 : 2;
			}
			this.givens = givens;
			this.values = values;
			this.owners = owners;
			this.notes = emptyGrid();
			this.level = isLevel(r.level) ? r.level : 2;
		},
		/** Copies the room's state, players and clock (not its cells) to the view. */
		applyRoom(r: NumberPlaceRoom) {
			const o = this.online;
			o.status = r.status;
			o.mode = r.mode;
			o.host = r.host.id;
			o.theme = r.theme || '';
			o.level = isLevel(r.level) ? r.level : 2;
			o.guestReady = !!r.guestReady;
			o.resignedBy = r.resignedBy || '';
			o.chatFileId = r.chatFileId || '';
			this.level = o.level;
			const players: [NumberPlacePlayer, NumberPlacePlayer] = r.you === 'host' ? [r.host, r.guest] : [r.guest, r.host];
			const now = Date.now();
			players.forEach((p, k) => {
				const seat = this.seats[k];
				if (!seat) return;
				seat.color = p.color;
				seat.filled = p.filled;
				seat.misses = p.misses;
				seat.lockUntil = p.lockMs > 0 ? now + p.lockMs : 0;
				if (k === 1) seat.name = p.displayName || p.id;
			});
			this.now = now;
			this.mistakes = players[0].misses;
			clockBase = r.elapsedMs || 0;
			clockSince = r.status === 'playing' ? now : null;
			this.tick();
		},
		/** Brings the board up to date with a fresh reading of the room. */
		syncRoom(r: NumberPlaceRoom) {
			if (!room || r.id !== room.id) return;
			const started = !room.givens && !!r.givens;
			room = r;
			if (started) {
				this.loadBoard(r);
			} else {
				const seen = new Set<number>();
				for (const c of r.cells || []) {
					if (c.cell < 0 || c.cell >= CELLS || this.givens[c.cell]) continue;
					seen.add(c.cell);
					if (this.values[c.cell] !== c.digit) this.fill(c.cell, c.digit, c.by === this.userId ? 1 : 2);
				}
				// A number here the room does not have: the board starts over from the room.
				if (this.values.some((v: number, i: number) => v && !seen.has(i))) this.loadBoard(r);
			}
			this.applyRoom(r);
			this.scheduleSave();
			if (r.status === 'finished') this.finishRoom(r);
		},
		async reloadRoom() {
			const id = this.online.roomId;
			if (!id || !service) return;
			try {
				const r = await service.getRoom(id);
				if (id !== this.online.roomId) return;
				this.syncRoom(r);
			} catch (e) {
				this.setNotice(this.t('app.numberplace.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
			}
		},
		/** The room is finished: shows the result the server recorded. */
		finishRoom(r: NumberPlaceRoom) {
			if (this.result) return;
			this.over = true;
			this.selected = -1;
			const winner = r.winner === null ? null : r.winner === 'draw' ? -1 : r.winner === r.you ? 0 : 1;
			const solved = r.mode !== 'coop' || !r.resignedBy;
			this.showResult({ winner, solved, timeMs: r.elapsedMs || 0, scores: this.seats.map((s: Seat) => s.filled) });
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
			o.chatFileId = '';
		},
		/**
		 * Sends the user's number. The cell shows it while it is on its way;
		 * a right one fills the cell, a wrong one makes the user wait, and a
		 * refusal (the other player was quicker) reads the room again.
		 */
		async writeOnline(i: number, digit: number) {
			const id = this.online.roomId;
			if (!service || !id || this.pending || this.values[i] || this.myLock) {
				this.nope(i);
				return;
			}
			this.pending = { cell: i, digit };
			try {
				const move = await service.play(id, i, digit);
				if (id !== this.online.roomId) return;
				this.pending = null;
				if (move.correct) {
					this.syncRoom(move.room);
					this.celebrate(i, 0);
				} else {
					this.nope(i);
					this.syncRoom(move.room);
				}
			} catch (e) {
				this.pending = null;
				this.nope(i);
				this.setNotice(this.t('app.numberplace.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				await this.reloadRoom();
			}
		},

		// --- the lobby's online sections: inviting, answering, resuming ---

		/** Users matching the keyword, for <wt-autocomplete>: not groups, not service accounts, not oneself. */
		async findOpponents(keyword: string): Promise<WtAutocompleteItem[]> {
			if (!this.instance) return [];
			const found = await this.instance.api.content.searchPrincipals(keyword, 0, OPPONENT_SUGGESTIONS * 2);
			return found.filter((p: PrincipalInfo) => !p.isGroup && !p.isService && p.identifier !== this.userId)
				.slice(0, OPPONENT_SUGGESTIONS)
				.map((p: PrincipalInfo) => ({ value: p, label: p.displayName || p.identifier, description: p.identifier, icon: 'bi bi-person' }));
		},
		chooseOpponent(p: PrincipalInfo) {
			this.setup.opponent = p;
		},
		async sendInvitation() {
			const d = this.setup;
			if (!service || !d.opponent || d.busy) return;
			d.busy = true;
			d.error = '';
			const mode: NumberPlaceMode = d.mode === 'coop' ? 'coop' : d.rule;
			try {
				const r = await service.invite(d.opponent.identifier, mode, d.level, this.settings.color, this.settings.theme, this.localization.locale);
				this.enterRoom(r);
				this.loadRooms();
				this.setNotice(this.t('app.numberplace.online.sent', { name: this.online.opponentName }, 'Invitation sent to {name}'), LONG_NOTICE_MS);
			} catch (e) {
				d.error = errorText(e);
			} finally {
				d.busy = false;
			}
		},
		async acceptRoom(r: NumberPlaceRoom) {
			if (!service || this.setup.busy) return;
			this.setup.busy = true;
			this.setup.error = '';
			try {
				this.enterRoom(await service.accept(r.id, this.settings.color));
				this.loadRooms();
			} catch (e) {
				this.setup.error = errorText(e);
				this.loadRooms();
			} finally {
				this.setup.busy = false;
			}
		},
		/** Declines an invitation, or takes back one the user sent; the room on the board, if it is, is left. */
		async declineRoom(r: NumberPlaceRoom) {
			if (!service || this.setup.busy) return;
			this.setup.busy = true;
			this.setup.error = '';
			try {
				await service.decline(r.id);
				if (r.id === this.online.roomId) this.goToLobby();
				await this.loadRooms();
			} catch (e) {
				this.setup.error = errorText(e);
				this.loadRooms();
			} finally {
				this.setup.busy = false;
			}
		},
		resumeRoomFromList(r: NumberPlaceRoom) {
			if (r.id === this.online.roomId) {
				this.reloadRoom();
				return;
			}
			this.enterRoom(r);
		},
		/** Invites the same user again, with the same rules, difficulty, colours and board. */
		async rematch() {
			if (!service || !room) return;
			const opponentId = this.online.opponentId;
			const me = room.you === 'host' ? room.host : room.guest;
			try {
				const r = await service.invite(opponentId, room.mode, room.level, me.color, room.theme, this.localization.locale);
				this.enterRoom(r);
				this.loadRooms();
				this.setNotice(this.t('app.numberplace.online.sent', { name: this.online.opponentName }, 'Invitation sent to {name}'), LONG_NOTICE_MS);
			} catch (e) {
				this.setNotice(this.t('app.numberplace.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
			}
		},

		// --- the lobby of a room: colours, the difficulty, the board, ready, start ---

		/** Sends a lobby request and shows the room it answers with. */
		async updateRoom(request: (id: string) => Promise<NumberPlaceRoom>): Promise<boolean> {
			const id = this.online.roomId;
			if (!service || !id || this.online.busy) return false;
			this.online.busy = true;
			try {
				const r = await request(id);
				if (id === this.online.roomId) this.syncRoom(r);
				return true;
			} catch (e) {
				this.setNotice(this.t('app.numberplace.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				this.reloadRoom();
				return false;
			} finally {
				this.online.busy = false;
			}
		},
		async pickRoomColor(colorId: string) {
			if (!service || this.lobbyLocked || colorId === this.theirColor || colorId === this.myColor) return;
			const s = service;
			if (await this.updateRoom(id => s.setup(id, { color: colorId }))) {
				this.settings = { ...this.settings, color: colorId };
				this.scheduleSave();
			}
		},
		async pickRoomLevel(level: Level) {
			if (!service || !this.isHost || level === this.online.level) return;
			const s = service;
			if (await this.updateRoom(id => s.setup(id, { level }))) {
				this.settings = { ...this.settings, level };
				this.scheduleSave();
			}
		},
		async pickRoomTheme(themeId: string) {
			if (!service || !this.isHost || themeId === this.online.theme) return;
			const s = service;
			if (await this.updateRoom(id => s.setup(id, { theme: themeId }))) {
				this.settings = { ...this.settings, theme: themeId };
				this.scheduleSave();
			}
		},
		async setReady(ready: boolean) {
			if (!service || this.isHost || this.online.status !== 'lobby') return;
			const s = service;
			await this.updateRoom(id => s.ready(id, ready));
		},
		/** The host starts the game with a puzzle of the room's difficulty, made here. */
		async startRoom() {
			if (!service || !gen || !this.canStartRoom) return;
			const s = service;
			const id = this.online.roomId;
			this.preparing = true;
			let puzzle: Puzzle;
			try {
				puzzle = await gen.generate(this.online.level);
			} catch (e) {
				this.preparing = false;
				if (errorText(e) !== 'cancelled') this.setNotice(this.t('app.numberplace.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				return;
			}
			this.preparing = false;
			if (id !== this.online.roomId) return;
			if (await this.updateRoom(roomId => s.start(roomId, puzzle.givens))) this.loadRooms();
		},
		/** The host takes the invitation back, or the guest leaves the lobby; either way the lobby is shown. */
		async leaveLobby() {
			if (!room || this.online.busy) return;
			this.online.busy = true;
			this.setup.error = '';
			try {
				await this.declineRoom(room);
			} finally {
				this.online.busy = false;
			}
			if (this.setup.error) {
				this.setNotice(this.t('app.numberplace.error', { message: this.setup.error }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
			}
		},

		// --- giving up ---

		openResign() {
			if (!this.canResign) return;
			this.resignDialog = { visible: true, busy: false };
		},
		closeResign() {
			this.resignDialog.visible = false;
		},
		async confirmResign() {
			const id = this.online.roomId;
			if (!service || !id || this.resignDialog.busy) return;
			this.resignDialog.busy = true;
			try {
				const r = await service.resign(id);
				this.resignDialog.visible = false;
				this.syncRoom(r);
				this.loadRooms();
			} catch (e) {
				this.setNotice(this.t('app.numberplace.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
			} finally {
				this.resignDialog.busy = false;
			}
		},

		// =====================================================================
		// Display helpers
		// =====================================================================

		/** The name of a seat among the given seats (the game's, or the lobby's preview). */
		seatName(seats: Seat[], k: number): string {
			const seat = seats[k];
			if (!seat) return '';
			if (seat.kind === 'ai') return this.t('app.numberplace.seat.computer', undefined, 'Computer');
			if (seat.kind === 'remote') {
				return seat.name || seat.userId || this.t('app.numberplace.seat.someone', undefined, 'Someone');
			}
			return this.t('app.numberplace.seat.you', undefined, 'You');
		},
		levelLabel(level: Level): string {
			const names: Record<Level, string> = { 1: 'Easy', 2: 'Medium', 3: 'Hard', 4: 'Expert' };
			return this.t(`app.numberplace.level.${level}`, undefined, names[level]);
		},
		aiLevelLabel(level: AiLevel): string {
			const names: Record<AiLevel, string> = { 1: 'Chick', 2: 'Songbird', 3: 'Owl', 4: 'Hawk' };
			return this.t(`app.numberplace.strength.${level}`, undefined, names[level]);
		},
		ruleLabel(rule: Rule): string {
			return rule === 'race' ?
				this.t('app.numberplace.rule.race', undefined, 'Time attack') :
				this.t('app.numberplace.rule.territory', undefined, 'Territory');
		},
		/** What a room is: cooperation, or one of the rules against each other. */
		roomModeLabel(mode: NumberPlaceMode): string {
			return mode === 'coop' ? this.t('app.numberplace.mode.coopShort', undefined, 'Together') : this.ruleLabel(mode);
		},
		colorStyle(colorId: string): Record<string, string> {
			const c = findColor(colorId);
			return { background: c.swatch, '--np-player': c.ink };
		},
		cellLabel(i: number, digit: number): string {
			const at = this.t('app.numberplace.cell', { row: rowOf(i) + 1, col: colOf(i) + 1 }, 'Row {row}, column {col}');
			return digit ? `${at}: ${digit}` : at;
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
				console.warn('[NumberPlace] Saved state could not be read:', e);
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
			if (saveTimer) {
				clearTimeout(saveTimer);
				saveTimer = null;
			}
			await this.writeState();
		},
		async writeState() {
			const userId = this.instance?.currentUser?.id;
			if (!userId) return;
			// A game with another user is kept by the server; only which room
			// was open is remembered here. A game that is over is not kept.
			const local = this.hasGame && !this.isOnline && !this.over && !!solution;
			const state: SavedState = {
				version: 1,
				settings: JSON.parse(JSON.stringify(this.settings)),
				game: local ? {
					kind: this.kind as SavedGame['kind'],
					level: this.level,
					aiLevel: this.aiLevel,
					givens: formatGrid(this.givens),
					solution: formatGrid(solution!),
					values: formatGrid(this.values),
					owners: formatGrid(this.owners),
					notes: this.notes.slice(),
					aiBoard: aiBoard ? formatGrid(aiBoard) : null,
					colors: [this.seats[0].color, this.seats[1]?.color || otherColor(this.seats[0].color)],
					elapsed: this.clockMs(),
					mistakes: this.kind === 'solo' ? this.mistakes : this.seats[0].misses,
					hints: this.hints,
					aiMisses: this.seats[1]?.misses || 0,
				} : null,
				roomId: this.online.roomId,
			};
			try {
				await this.instance.api.db.setUserSetting(userId, APP_ID, STATE_KEY, state);
			} catch (e) {
				console.warn('[NumberPlace] State could not be saved:', e);
			}
		},
	},
};

VDOM.createApp(App).mount('#app');
