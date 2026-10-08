/**
 * Minesweeper Application
 *
 * Minesweeper alone, against the computer, or with another user of the
 * Webtop, cooperating or against each other. The rules, the solver and the
 * board maker live in core.ts, the computer opponent in ai.ts, the board
 * themes and player colours in looks.ts and the effects in lib/effects.ts.
 *
 * Every board can be cleared without guessing, from a start that is opened
 * when the game begins. What is on the board is played by one of four
 * kinds of rules (`kind`):
 *
 *   solo       alone: opening a mine ends the game; clear every safe cell
 *   race       time attack: each player clears the same board on a board
 *              of its own; the first to clear it wins
 *   territory  one board for both: a flag put on a mine takes it; the one
 *              with more mines when they are all found (or when the other
 *              cannot catch up) wins
 *   coop       one board for both, cleared together; a mine ends the game
 *
 * Against each other a mine opened, or in territory a flag put where there
 * is no mine, makes the player wait a few seconds (LOCK_MS) instead of
 * ending the game. Against the computer the race or the territory is played
 * here; with another user, it is a room of the Minesweeper GraphQL schema
 * (services/minesweeper-service-graphql.ts): the server makes the board and
 * keeps where the mines are, and this app asks to open a cell or put a
 * flag down and shows the board it answers with. The host invites the
 * other user; once accepted the room is a lobby, where each player picks a
 * colour, the host also picks the difficulty and the board, the guest says
 * it is ready and the host starts the game. What the other player does
 * arrives as topic messages on the room's topic; the app watches
 * game/minesweeper/rooms/* once and reads the room again whenever a
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
	MinesweeperServiceGraphQL,
	MINESWEEPER_TOPICS,
	isMinesweeperMessage,
	type MinesweeperMessage,
	type MinesweeperMode,
	type MinesweeperMove,
	type MinesweeperPlayer,
	type MinesweeperRoom,
} from "../../services/minesweeper-service-graphql.js";
import { ChatServiceGraphQL } from "../../services/chat-service-graphql.js";
// Side-effect import: registers the <wt-chat-thread> the room's chat is shown in.
import { loadChatThreadTemplate } from "../../components/wt-chat-thread.js";
import { Effects, type EffectPreset } from '../../lib/effects.js';
import {
	LEVELS,
	SIZES,
	COVERED,
	EXPLODED,
	MINE,
	countsOf,
	deduce,
	flood,
	formatLayout,
	formatView,
	generate,
	neighborTable,
	openCount,
	parseLayout,
	parseView,
	type Level,
} from './core.js';
import { nextStep, AI_LEVELS, type AiLevel, type AiStep } from './ai.js';
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
	/** Safe cells opened: on the player's own board in a race, by the player together. */
	opened: number;
	/** Territory: the mines the player took. */
	found: number;
	/** Mines opened and, in territory, flags put where there was no mine. */
	misses: number;
	/** Until when (Date.now()) the player waits after a miss. */
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
}

/** A game alone or against the computer, as it stands. */
interface SavedGame {
	kind: 'solo' | 'race' | 'territory';
	level: Level;
	aiLevel: AiLevel;
	/** Where the mines are (core.ts layout) and the cell opened at the start. */
	layout: string;
	start: number;
	/** The user's board (the shared one in territory), as core.ts formatView writes it. */
	view: string;
	/** Per cell: 0 nothing, 1 the user's flag (or mine taken, or mine opened), 2 the computer's. */
	marks: string;
	/** In a race: the computer's own board, and the mines it found. */
	aiView: string | null;
	aiKnown: string | null;
	colors: [string, string];
	elapsed: number;
	misses: number;
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
	/** Whether the board was played to its end, rather than lost to a mine or given up. */
	cleared: boolean;
	/** Alone or together: a mine went off. */
	blast: boolean;
	timeMs: number;
	/** What each seat scored: cells opened, or in territory mines taken. */
	scores: number[];
}

interface CellView {
	i: number;
	/** The count shown, '' for none. */
	n: number | '';
	flag: boolean;
	mine: boolean;
	cls: Record<string, boolean>;
	style: Record<string, string>;
	label: string;
}

const APP_ID = 'minesweeper';
const STATE_KEY = 'state';
/** How long a player waits after a miss (the server's MinesweeperApi.LOCK_MS too). */
const LOCK_MS = 5000;
const NOTICE_MS = 1800;
const LONG_NOTICE_MS = 4500;
const TICK_MS = 250;
const OPPONENT_SUGGESTIONS = 8;
const RULES: Rule[] = ['race', 'territory'];
const MODES: Mode[] = ['solo', 'ai', 'coop', 'versus'];

/** A mine going off: a flash, a ring and dark seeds thrown about. */
const BOOM: EffectPreset = (fx, at) => {
	if (!at) return;
	const { x, y } = at;
	fx.emit({ shape: 'circle', x, y, size: 34, life: 220, color: '#FFF4D6', alpha: 0.9 });
	fx.emit({ shape: 'ring', x, y, radius: 8, maxRadius: 64, life: 420, color: '#D83A5D', alpha: 0.65, lineWidth: 4 });
	fx.emit({ shape: 'ring', x, y, radius: 6, maxRadius: 44, life: 360, delay: 90, color: '#FFC857', alpha: 0.6, lineWidth: 3 });
	const colors = ['#4D2C31', '#D83A5D', '#FFC857', '#ED5A77'];
	const n = fx.count(14);
	for (let k = 0; k < n; k++) {
		const a = (Math.PI * 2 * k) / n + Math.random() * 0.3;
		const sp = 2.5 + Math.random() * 3;
		fx.emit({ x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 1, gravity: 0.2, drag: 0.97, size: 4 + Math.random() * 5, life: 620, color: colors[k % colors.length] });
	}
};

// Kept outside reactive data: ichigo.js wraps stored objects in deep
// Proxies, which the canvas and the services (private fields) do not need.
let fx: Effects | null = null;
let service: MinesweeperServiceGraphQL | null = null;
/** The room of the game with another user shown on the board. */
let room: MinesweeperRoom | null = null;
let unwatchTopics: (() => void) | null = null;
/** Where the mines are, in a game alone or against the computer; the counts around each cell. */
let mines: number[] | null = null;
let counts: number[] | null = null;
let startCell = -1;
/** In a race against the computer: the computer's own board, and the mines it found. */
let aiView: number[] | null = null;
let aiKnown: boolean[] | null = null;
/** The cell the computer played last. */
let aiLast = -1;
// Bumped by a new game; a step of the computer from an older one is dropped.
let generation = 0;
let noticeTimer: ReturnType<typeof setTimeout> | null = null;
let saveTimer: ReturnType<typeof setTimeout> | null = null;
let tickTimer: ReturnType<typeof setInterval> | null = null;
let aiTimer: ReturnType<typeof setTimeout> | null = null;
// The clock: time played before `clockSince`, and since when it runs (null: stopped).
let clockBase = 0;
let clockSince: number | null = null;

function covered(cells: number): number[] {
	return new Array(cells).fill(COVERED);
}

function zeros(cells: number): number[] {
	return new Array(cells).fill(0);
}

function defaultSettings(): Settings {
	return { mode: 'solo', level: 1, aiLevel: 2, rule: 'race', color: DEFAULT_COLORS[0], theme: DEFAULT_THEME };
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
	return s;
}

function newSeat(kind: Seat['kind'], color: string, extra: Partial<Seat> = {}): Seat {
	return { kind, color, opened: 0, found: 0, misses: 0, lockUntil: 0, ...extra };
}

function errorText(e: unknown): string {
	return (e instanceof Error) ? e.message : String(e);
}

function cellElement(index: number): HTMLElement | null {
	return document.querySelector(`.ms-cell[data-i="${index}"]`);
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
function opponentOf(r: MinesweeperRoom): { id: string; name: string } {
	const p = r.you === 'host' ? r.guest : r.host;
	return { id: p.id, name: p.displayName || p.id };
}

/**
 * Territory: who has won, when it is settled; null while it is not. Every
 * mine is taken or opened, or one player has more than the other could
 * still reach.
 */
function territoryWinner(a: number, b: number, left: number): number | null {
	if (left <= 0) return a === b ? -1 : a > b ? 0 : 1;
	if (a > b + left) return 0;
	if (b > a + left) return 1;
	return null;
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

			// The board as shown: the user's own board, or the shared one.
			kind: 'solo' as Kind,
			level: 1 as Level,
			aiLevel: 2 as AiLevel,
			width: SIZES[1].width,
			height: SIZES[1].height,
			mineCount: SIZES[1].mines,
			// What each cell shows (core.ts: COVERED, 0-8, EXPLODED, MINE).
			view: covered(SIZES[1].width * SIZES[1].height),
			// Per cell: 0 nothing; 1 the user's seat, 2 the other seat: whose
			// flag it is (or mine taken in territory), or who opened the mine.
			marks: zeros(SIZES[1].width * SIZES[1].height),
			// The keyboard's cell, shown once the keyboard (or a hint) is used.
			cursor: -1,
			// A click puts a flag down instead of opening (for touch screens).
			flagMode: false,
			seats: [newSeat('human', DEFAULT_COLORS[0])] as Seat[],
			hasGame: false,
			over: false,
			hints: 0,
			// Shown time, in whole seconds, and the time now, while someone waits.
			clockSec: 0,
			now: Date.now(),
			// With another user: the cell of the request on its way to the server, -1 for none.
			pending: -1,

			notice: '',
			result: null as GameResult | null,

			// The game with another user on the board, and the user's rooms.
			online: {
				roomId: null as string | null,
				status: '' as MinesweeperRoom['status'] | '',
				mode: 'coop' as MinesweeperMode,
				host: '',
				opponentId: '',
				opponentName: '',
				// The board and difficulty the host chose, and whether the guest is ready.
				theme: '',
				level: 1 as Level,
				guestReady: false,
				// A lobby request (a colour, the difficulty, ready, start) is on its way.
				busy: false,
				// Whether the game ended by giving up, and who did.
				resignedBy: '' as string,
				// Together: who opened the mine that ended the game.
				blastBy: '' as string,
				// The room's settings file, whose Chat conversation is the room's chat.
				chatFileId: '',
				invitations: [] as MinesweeperRoom[],
				sent: [] as MinesweeperRoom[],
				games: [] as MinesweeperRoom[],
				loading: false,
				error: '',
			},

			// The lobby's form: what the next game will be.
			setup: {
				mode: 'solo' as Mode,
				level: 1 as Level,
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
			joinDialog: { visible: false, room: null as MinesweeperRoom | null },
		};
	},
	computed: {
		stageStyle(): Record<string, string> {
			// With another user the board is the one the host chose.
			const theme = (this.isOnline && this.online.theme) ? this.online.theme : this.settings.theme;
			return { ...findTheme(theme).vars, '--ms-cols': String(this.width), '--ms-rows': String(this.height) };
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
		/** Cells can be opened now. */
		canPlay(): boolean {
			if (!this.hasGame || this.over || this.scene !== 'game') return false;
			return !this.isOnline || this.online.status === 'playing';
		},
		/** How many cells hold no mine. */
		safeCells(): number {
			return this.width * this.height - this.mineCount;
		},
		/** Seconds the user still waits after a miss; 0 when it does not. */
		myLock(): number {
			const seat = this.seats[0];
			return seat ? Math.max(0, Math.ceil((seat.lockUntil - this.now) / 1000)) : 0;
		},
		/** The mines not yet flagged, taken or opened. */
		minesLeft(): number {
			let marked = 0;
			const view = this.view;
			const marks = this.marks;
			for (let i = 0; i < view.length; i++) {
				// Flagged or taken (covered, or a mine shown at the end), or opened.
				if (marks[i] && (view[i] < 0 || view[i] > 8)) marked++;
			}
			return this.mineCount - marked;
		},
		cellViews(): CellView[] {
			const view: number[] = this.view;
			const marks: number[] = this.marks;
			const width: number = this.width;
			const territory = this.kind === 'territory';
			const cursor: number = this.cursor;
			const pending: number = this.pending;
			const over: boolean = this.over;
			const colors = this.seats.map((s: Seat) => findColor(s.color));
			return view.map((v: number, i: number) => {
				const m = marks[i];
				const isCovered = v === COVERED;
				const color = m ? colors[m - 1] || colors[0] : null;
				const flag = !!m && (isCovered || v === MINE);
				const style: Record<string, string> = {};
				if (color) {
					style['--ms-player'] = color.ink;
					style['--ms-tint'] = color.tint;
				}
				return {
					i,
					n: v >= 1 && v <= 8 ? v : '',
					flag,
					mine: (v === EXPLODED || v === MINE) && !flag,
					cls: {
						'is-covered': isCovered || (v === MINE && flag),
						'is-open': v >= 0 && v <= 8,
						'is-alt': (Math.floor(i / width) + (i % width)) % 2 === 1,
						[`is-n${v}`]: v >= 1 && v <= 8,
						'is-flag': flag,
						'is-taken': territory && flag,
						'is-blast': v === EXPLODED,
						'is-mine': v === MINE && !flag,
						// Over, a flag on a cell with no mine was wrong.
						'is-wrong': over && isCovered && !!m,
						'is-cursor': cursor === i,
						'is-pending': pending === i,
					},
					style,
					label: this.cellLabel(i, v, flag),
				};
			});
		},
		canHint(): boolean {
			return this.kind === 'solo' && this.canPlay;
		},
		canResign(): boolean {
			return this.isOnline && this.online.status === 'playing' && !this.over;
		},
		/** Invitations waiting for the user's answer, for one of the online sections or both. */
		pendingCount(): number {
			return this.online.invitations.length;
		},
		pendingCoop(): number {
			return this.online.invitations.filter((r: MinesweeperRoom) => r.mode === 'coop').length;
		},
		pendingVersus(): number {
			return this.online.invitations.filter((r: MinesweeperRoom) => r.mode !== 'coop').length;
		},
		joinNote(): string {
			const r = this.joinDialog.room;
			const name = r ? opponentOf(r).name : '';
			return this.t('app.minesweeper.online.joinConfirm', { name }, 'End this game and join {name}?');
		},
		leaveNote(): string {
			if (this.isOnline) {
				return this.t('app.minesweeper.leave.online', { name: this.online.opponentName },
					'Leave the board and go back to the lobby? The game with {name} goes on and can be resumed from the lobby.');
			}
			return this.t('app.minesweeper.leave.game', undefined, 'End this game and go back to the lobby?');
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
			return this.isHost && this.online.status === 'lobby' && this.online.guestReady && !this.online.busy;
		},
		/** What the lobby waits for, seen from the user. */
		lobbyNote(): string {
			const o = this.online;
			const name = o.opponentName;
			if (o.status === 'waiting') {
				return this.t('app.minesweeper.online.waiting', { name }, 'Waiting for {name} to accept…');
			}
			if (this.isHost) {
				return o.guestReady ?
					this.t('app.minesweeper.online.isReady', { name }, '{name} is ready') :
					this.t('app.minesweeper.online.waitingReady', { name }, 'Waiting for {name} to get ready…');
			}
			return o.guestReady ?
				this.t('app.minesweeper.online.waitingStart', { name }, 'Waiting for {name} to start…') :
				this.t('app.minesweeper.online.chooseColor', undefined, 'Choose your colour and press Ready!');
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
			const territory = this.hasGame ? this.kind === 'territory' : (this.setup.mode !== 'solo' && this.setup.mode !== 'coop' && this.setup.rule === 'territory');
			const coop = this.hasGame ? this.kind === 'coop' : this.setup.mode === 'coop';
			const whole = (territory ? this.mineCount : this.safeCells) || 1;
			return seats.map((seat: Seat, k: number) => {
				const wait = Math.max(0, Math.ceil((seat.lockUntil - this.now) / 1000));
				const waiting = playing && wait > 0;
				const score = territory ? seat.found : seat.opened;
				let role: string;
				if (waiting) {
					role = this.t('app.minesweeper.seat.waiting', { n: wait }, 'Waiting… {n}');
				} else if (seat.kind === 'ai') {
					role = this.aiLevelLabel(this.hasGame ? this.aiLevel : this.setup.aiLevel);
				} else if (seats.length === 1) {
					role = this.levelLabel(this.hasGame ? this.level : this.setup.level);
				} else if (seat.misses) {
					role = this.t('app.minesweeper.seat.misses', { n: seat.misses }, '{n} misses');
				} else {
					role = seat.kind !== 'remote' ? '' : coop ?
						this.t('app.minesweeper.seat.partner', undefined, 'Partner') :
						this.t('app.minesweeper.seat.opponent', undefined, 'Opponent');
				}
				return {
					key: k,
					name: this.seatName(seats, k),
					role,
					count: live ? String(score) : '',
					progress: live ? Math.min(1, score / whole) : 0,
					thinking: playing && seat.kind === 'ai' && !waiting,
					waiting,
					style: this.colorStyle(seat.color),
					vars: { '--ms-player': findColor(seat.color).ink },
				};
			});
		},
		statusText(): string {
			if (!this.hasGame) return '';
			if (this.inLobby) return this.lobbyNote;
			if (this.over) return this.resultTitle;
			if (this.myLock) return this.t('app.minesweeper.status.locked', { n: this.myLock }, 'Ouch! Wait {n} s');
			if (this.flagMode) {
				return this.kind === 'territory' ?
					this.t('app.minesweeper.status.claiming', undefined, 'Taking mines') :
					this.t('app.minesweeper.status.flagging', undefined, 'Putting flags down');
			}
			switch (this.kind) {
				case 'race': return this.t('app.minesweeper.status.race', undefined, 'The first to clear the board wins');
				case 'territory': return this.t('app.minesweeper.status.territory', undefined, 'Flag a mine to take it');
				case 'coop': return this.t('app.minesweeper.status.coop', undefined, 'Clear the board together');
				default: return this.t('app.minesweeper.status.left', { n: this.safeCells - (this.seats[0]?.opened || 0) }, '{n} cells to go');
			}
		},
		clockText(): string {
			return formatTime(this.clockSec * 1000);
		},
		resultTitle(): string {
			const r = this.result as GameResult | null;
			if (!r) return '';
			if (r.winner === null) {
				if (r.blast) {
					const by = this.online.blastBy;
					return this.isOnline && by && by !== this.userId ?
						this.t('app.minesweeper.result.theyBlast', { name: this.online.opponentName }, '{name} opened a mine') :
						this.t('app.minesweeper.result.blast', undefined, 'You opened a mine');
				}
				if (!r.cleared) {
					return this.online.resignedBy && this.online.resignedBy !== this.userId ?
						this.t('app.minesweeper.result.theyGaveUp', { name: this.online.opponentName }, '{name} gave up') :
						this.t('app.minesweeper.result.gaveUp', undefined, 'You gave up');
				}
				return this.kind === 'coop' ?
					this.t('app.minesweeper.result.clearedTogether', undefined, 'Cleared together!') :
					this.t('app.minesweeper.result.cleared', undefined, 'Cleared!');
			}
			if (r.winner < 0) return this.t('app.minesweeper.result.draw', undefined, "It's a draw");
			const seat = this.seats[r.winner];
			if (seat.kind === 'human') return this.t('app.minesweeper.result.youWin', undefined, 'You win!');
			if (seat.kind === 'ai') return this.t('app.minesweeper.result.computerWins', undefined, 'The computer wins');
			return this.t('app.minesweeper.result.wins', { name: this.seatName(this.seats, r.winner) }, '{name} wins!');
		},
		/** Why the game ended, when it did not end on the board. */
		resultNote(): string {
			const r = this.result as GameResult | null;
			if (!r || !this.isOnline || !this.online.resignedBy || r.winner === null) return '';
			if (this.online.resignedBy === this.userId) {
				return this.t('app.minesweeper.online.youResigned', undefined, 'You gave up');
			}
			return this.t('app.minesweeper.online.resigned', { name: this.online.opponentName }, '{name} gave up');
		},
		resultTime(): string {
			return this.result ? formatTime(this.result.timeMs) : '';
		},
		levelOptions(): { level: Level; label: string; size: string; pips: number[] }[] {
			return LEVELS.map(level => ({ level, label: this.levelLabel(level), size: this.sizeLabel(level), pips: LEVELS.slice(0, level) }));
		},
		aiLevelOptions(): { level: AiLevel; label: string; pips: number[] }[] {
			return AI_LEVELS.map(level => ({ level, label: this.aiLevelLabel(level), pips: AI_LEVELS.slice(0, level) }));
		},
		themeOptions(): { id: string; label: string; style: Record<string, string> }[] {
			return BOARD_THEMES.map(theme => ({
				id: theme.id,
				label: this.t(`app.minesweeper.board.${theme.id}`, undefined, theme.label),
				style: {
					background: `linear-gradient(135deg, ${theme.vars['--ms-tile']} 50%, ${theme.vars['--ms-open']} 50%)`,
					boxShadow: `inset 0 0 0 4px ${theme.vars['--ms-frame']}, inset 0 0 0 5px ${theme.vars['--ms-frame-edge']}`,
				},
			}));
		},
		colorOptions(): { id: string; label: string; style: Record<string, string> }[] {
			return PLAYER_COLORS.map(c => ({
				id: c.id,
				label: this.t(`app.minesweeper.color.${c.id}`, undefined, c.label),
				style: this.colorStyle(c.id),
			}));
		},
		/** What the chosen way to play is, in a line. */
		modeNote(): string {
			const notes: Record<Mode, [string, string]> = {
				solo: ['app.minesweeper.mode.soloNote', 'Clear a board at your own pace.'],
				ai: ['app.minesweeper.mode.aiNote', 'Play the computer on the same board.'],
				coop: ['app.minesweeper.mode.coopNote', 'Clear one board together with another user.'],
				versus: ['app.minesweeper.mode.versusNote', 'Play another user over the network, live.'],
			};
			const [key, fallback] = notes[this.setup.mode as Mode];
			return this.t(key, undefined, fallback);
		},
		ruleNote(): string {
			return this.setup.rule === 'race' ?
				this.t('app.minesweeper.rule.raceNote', undefined, 'Each clears the same board on a board of their own; the first to clear it wins.') :
				this.t('app.minesweeper.rule.territoryNote', undefined, 'One board for both: a flag you put on a mine takes it; the one who finds more mines wins.');
		},
		rulePenalty(): string {
			return this.setup.rule === 'race' ?
				this.t('app.minesweeper.rule.racePenalty', undefined, 'Opening a mine makes you wait 5 seconds.') :
				this.t('app.minesweeper.rule.territoryPenalty', undefined, 'Opening a mine, or a flag where there is none, makes you wait 5 seconds.');
		},
		/** The rooms listed in the lobby's online section, with what the user can do about each. */
		roomCards(): { room: MinesweeperRoom; name: string; note: string; action: 'answer' | 'cancel' | 'resume' }[] {
			const o = this.online;
			const coop = this.setup.mode === 'coop';
			const fits = (r: MinesweeperRoom) => (r.mode === 'coop') === coop;
			const notes = {
				answer: () => this.t('app.minesweeper.online.invitesYou', undefined, 'Invites you to a game'),
				resume: (r: MinesweeperRoom) => r.status === 'lobby' ?
					this.t('app.minesweeper.online.lobby', undefined, 'Getting ready') :
					this.t('app.minesweeper.online.playing', undefined, 'Playing'),
				cancel: () => this.t('app.minesweeper.online.awaiting', undefined, 'Waiting for an answer'),
			};
			const card = (r: MinesweeperRoom, action: 'answer' | 'cancel' | 'resume') => ({
				room: r,
				name: opponentOf(r).name,
				note: `${notes[action](r)} · ${this.roomModeLabel(r.mode)} · ${this.levelLabel(isLevel(r.level) ? r.level : 1)}`,
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
			if (this.setup.busy) return false;
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
					console.warn('[Minesweeper] Failed to load component templates:', e);
				}

				fx = new Effects();
				fx.register('boom', BOOM);
				service = new MinesweeperServiceGraphQL(instance.api.graphql);
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
			this.dropLocal();
			this.stopClock();
			const back: Mode | undefined = mode ?? (this.isOnline ? (this.online.mode === 'coop' ? 'coop' : 'versus') : undefined);
			this.leaveRoom();
			this.hasGame = false;
			this.over = false;
			this.result = null;
			this.pending = -1;
			this.cursor = -1;
			this.flagMode = false;
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
		startFromLobby() {
			const d = this.setup;
			if (!this.canStart) return;
			this.settings = { ...this.settings, mode: d.mode, level: d.level, aiLevel: d.aiLevel, rule: d.rule, color: d.color, theme: d.theme };
			this.scheduleSave();
			if (this.isOnlineSetup) {
				this.sendInvitation();
				return;
			}
			this.startGame();
		},

		// =====================================================================
		// A game alone or against the computer: starting and resuming
		// =====================================================================

		/** Forgets the mines and the computer's board of a game here. */
		dropLocal() {
			mines = null;
			counts = null;
			startCell = -1;
			aiView = null;
			aiKnown = null;
			aiLast = -1;
		},
		/** A new game with the current settings. A board takes a moment at most, so it is made here. */
		startGame() {
			const s = this.settings;
			let board;
			try {
				board = generate(s.level);
			} catch (e) {
				this.setNotice(this.t('app.minesweeper.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				return;
			}
			this.leaveRoom();
			const seats: Seat[] = s.mode === 'ai' ?
				[newSeat('human', s.color), newSeat('ai', otherColor(s.color))] :
				[newSeat('human', s.color)];
			const cells = board.width * board.height;
			const layout = parseLayout(board.layout, cells);
			const view = covered(cells);
			flood(board.width, board.height, countsOf(board.width, board.height, layout), view, board.start);
			const race = s.mode === 'ai' && s.rule === 'race';
			this.beginLocal({
				kind: s.mode === 'ai' ? s.rule : 'solo',
				level: board.level,
				aiLevel: s.aiLevel,
				layout: board.layout,
				start: board.start,
				view: formatView(view),
				marks: '0'.repeat(cells),
				aiView: race ? formatView(view) : null,
				aiKnown: race ? '0'.repeat(cells) : null,
				colors: [seats[0].color, seats[1]?.color || otherColor(seats[0].color)],
				elapsed: 0,
				misses: 0,
				hints: 0,
				aiMisses: 0,
			}, seats.length > 1);
		},
		/** Puts a saved game back on the board; false when there is none worth resuming. */
		resumeGame(saved: SavedGame | null | undefined): boolean {
			if (!saved || !['solo', 'race', 'territory'].includes(saved.kind) || !isLevel(saved.level)) return false;
			const { width, height, mines: count } = SIZES[saved.level];
			const cells = width * height;
			try {
				const layout = parseLayout(saved.layout, cells);
				const view = parseView(saved.view, cells);
				if (layout.reduce((n, m) => n + m, 0) !== count) return false;
				if (!Number.isInteger(saved.start) || saved.start < 0 || saved.start >= cells || layout[saved.start]) return false;
				if (typeof saved.marks !== 'string' || saved.marks.length !== cells || !/^[012]+$/.test(saved.marks)) return false;
				if (view.some((v, i) => (v === EXPLODED) !== (!!layout[i] && v !== COVERED))) return false;
				if (saved.kind === 'race') {
					if (!saved.aiView || !saved.aiKnown || !/^[01]+$/.test(saved.aiKnown) || saved.aiKnown.length !== cells) return false;
					parseView(saved.aiView, cells);
				}
			} catch (e) {
				console.warn('[Minesweeper] Saved game could not be read:', e);
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
			const { width, height, mines: count } = SIZES[g.level];
			const cells = width * height;
			mines = parseLayout(g.layout, cells);
			counts = countsOf(width, height, mines);
			startCell = g.start;
			aiView = g.aiView ? parseView(g.aiView, cells) : null;
			aiKnown = g.aiKnown ? Array.from(g.aiKnown, ch => ch === '1') : null;
			aiLast = -1;
			const seats: Seat[] = [newSeat('human', g.colors[0], { misses: g.misses || 0 })];
			if (vsAi) seats.push(newSeat('ai', g.colors[1], { misses: g.aiMisses || 0 }));
			this.kind = g.kind;
			this.level = g.level;
			this.aiLevel = AI_LEVELS.includes(g.aiLevel) ? g.aiLevel : 2;
			this.width = width;
			this.height = height;
			this.mineCount = count;
			this.view = parseView(g.view, cells);
			this.marks = Array.from(g.marks, ch => Number(ch));
			this.seats = seats;
			this.hints = g.hints || 0;
			this.countLocal();
			this.hasGame = true;
			this.over = false;
			this.result = null;
			this.pending = -1;
			this.cursor = -1;
			this.flagMode = false;
			this.resultDialog.visible = false;
			this.setNotice('');
			clockBase = g.elapsed || 0;
			clockSince = null;
			if (this.localOutcome() || (g.kind === 'solo' && this.view.includes(EXPLODED))) {
				// Saved just as it ended: nothing to resume.
				this.dropLocal();
				this.hasGame = false;
				return false;
			}
			this.resumeLocal();
			this.scheduleSave();
			return true;
		},
		/** The scores of the seats, from the boards. */
		countLocal() {
			if (this.isOnline) return;
			const view: number[] = this.view;
			const marks: number[] = this.marks;
			this.seats.forEach((seat: Seat, k: number) => {
				if (this.kind === 'territory') {
					seat.found = view.filter((v, i) => v === COVERED && marks[i] === k + 1).length;
				} else {
					seat.opened = openCount(k === 0 ? view : (aiView || []));
				}
			});
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
		// The board: opening cells and putting flags down
		// =====================================================================

		/** A click opens (a number: the cells around it), or puts a flag down in flag mode. */
		onCellClick(index: number) {
			if (!this.canPlay) return;
			if (this.flagMode) this.flagCell(index);
			else this.openCell(index);
		},
		/** The right button (or a long press) puts a flag down or picks it up. */
		onCellFlag(index: number) {
			if (!this.canPlay) return;
			this.flagCell(index);
		},
		/** The keyboard: arrows move, Enter or Space opens, F puts a flag down, M switches flag mode. */
		onKey(event: KeyboardEvent) {
			if (!this.canPlay || this.anyDialog()) return;
			const target = event.target as HTMLElement | null;
			if (target && (target.closest('input, textarea, select, [contenteditable="true"], wt-chat-thread'))) return;
			if (event.altKey || event.metaKey || event.ctrlKey) return;
			const key = event.key;
			const at = this.cursor < 0 ? this.startCellShown() : this.cursor;
			if (key === 'Enter' || key === ' ') {
				event.preventDefault();
				if (this.cursor < 0) this.cursor = at;
				else this.openCell(at);
				return;
			}
			if (key === 'f' || key === 'F') {
				event.preventDefault();
				if (this.cursor < 0) this.cursor = at;
				else this.flagCell(at);
				return;
			}
			if (key === 'm' || key === 'M') {
				event.preventDefault();
				this.toggleFlagMode();
				return;
			}
			const moves: Record<string, [number, number]> = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] };
			const move = moves[key];
			if (move) {
				event.preventDefault();
				if (this.cursor < 0) {
					this.cursor = at;
					return;
				}
				const r = (Math.floor(at / this.width) + move[0] + this.height) % this.height;
				const c = (at % this.width + move[1] + this.width) % this.width;
				this.cursor = r * this.width + c;
			}
		},
		/** Where the keyboard starts: the cell opened at the start, or the middle. */
		startCellShown(): number {
			if (startCell >= 0) return startCell;
			if (room && typeof room.start === 'number') return room.start;
			return Math.floor(this.height / 2) * this.width + Math.floor(this.width / 2);
		},
		anyDialog(): boolean {
			return (!!this.result && this.resultDialog.visible) || this.resignDialog.visible || this.leaveDialog.visible || this.joinDialog.visible;
		},
		toggleFlagMode() {
			this.flagMode = !this.flagMode;
		},
		/** Opens a covered cell, or the cells around an open number whose mines are all marked. */
		openCell(i: number) {
			const v = this.view[i];
			const chord = v >= 1 && v <= 8;
			if (!chord && (v !== COVERED || this.marks[i])) {
				if (v === COVERED) this.nope(i);
				return;
			}
			if (chord && !this.chordTargets(i).length) {
				this.nope(i);
				return;
			}
			if (this.isOnline) this.openOnline(i, chord);
			else this.openLocal(i, chord);
		},
		/**
		 * The covered cells around an open number whose mines are all
		 * marked (flagged, taken or opened), or none.
		 */
		chordTargets(i: number): number[] {
			const view: number[] = this.view;
			const marks: number[] = this.marks;
			const around = neighborTable(this.width, this.height)[i];
			let marked = 0;
			const targets: number[] = [];
			for (const j of around) {
				if (view[j] === EXPLODED || (view[j] === COVERED && marks[j])) marked++;
				else if (view[j] === COVERED) targets.push(j);
			}
			return marked === view[i] ? targets : [];
		},
		/** Puts a flag down or picks it up; in territory, takes the cell for a mine. */
		flagCell(i: number) {
			if (this.view[i] !== COVERED) return;
			if (this.kind === 'territory') {
				if (this.marks[i]) {
					this.nope(i);
					return;
				}
				if (this.isOnline) this.claimOnline(i);
				else this.claimLocal(i);
				return;
			}
			if (this.isOnline) {
				this.flagOnline(i, !this.marks[i]);
				return;
			}
			const marks = this.marks.slice();
			marks[i] = marks[i] ? 0 : 1;
			this.marks = marks;
			if (marks[i]) this.bounce(i);
			this.scheduleSave();
		},

		// --- alone or against the computer ---

		/** Opens cells on the user's board here. A mine ends a game alone, and makes the user wait otherwise. */
		openLocal(i: number, chord: boolean) {
			if (!mines || !counts) return;
			if (this.kind !== 'solo' && this.myLock) {
				this.nope(i);
				return;
			}
			const targets = chord ? this.chordTargets(i) : [i];
			const view: number[] = this.view.slice();
			const marks: number[] = this.marks.slice();
			let blast = -1;
			let opened = 0;
			for (const t of targets) {
				if (view[t] !== COVERED) continue;
				if (mines[t]) {
					view[t] = EXPLODED;
					marks[t] = 1;
					blast = t;
					continue;
				}
				for (const j of flood(this.width, this.height, counts, view, t)) {
					marks[j] = 0;
					opened++;
				}
			}
			this.view = view;
			this.marks = marks;
			this.countLocal();
			if (blast >= 0) {
				this.boom(blast);
				if (this.kind === 'solo') {
					this.finishLocal({ winner: null, cleared: false, blast: true, timeMs: this.clockMs(), scores: [this.seats[0].opened] });
					this.scheduleSave();
					return;
				}
				this.missLocal(0);
			} else if (opened) {
				this.celebrate(i, 0, opened);
			}
			this.afterLocalMove();
		},
		/** Territory against the computer: a flag on a mine takes it, elsewhere it makes the user wait. */
		claimLocal(i: number) {
			if (!mines) return;
			if (this.myLock) {
				this.nope(i);
				return;
			}
			if (!mines[i]) {
				this.nope(i);
				this.missLocal(0);
				this.scheduleSave();
				return;
			}
			const marks = this.marks.slice();
			marks[i] = 1;
			this.marks = marks;
			this.countLocal();
			this.celebrateTake(i, 0);
			this.afterLocalMove();
		},
		/** A seat (0 the user, 1 the computer) opened a mine or took a cell with none: it waits. */
		missLocal(k: number) {
			const seat = this.seats[k];
			if (!seat) return;
			seat.misses++;
			seat.lockUntil = Date.now() + LOCK_MS;
			this.now = Date.now();
		},
		/** After a move here: the end, or the computer goes on. */
		afterLocalMove() {
			const outcome = this.localOutcome();
			if (outcome) this.finishLocal(outcome);
			this.scheduleSave();
		},
		/** Alone: opens a cell that is surely safe, or flags a sure mine when no cell is. */
		hint() {
			if (!this.canHint || !mines) return;
			const view: number[] = this.view.slice();
			const marks: number[] = this.marks;
			// The user's flags are not trusted: the hint reasons from the numbers.
			const known: boolean[] = view.map(v => v === EXPLODED);
			for (;;) {
				const d = deduce(this.width, this.height, view, known, this.mineCount);
				if (d.safe.length) {
					const cell = this.nearestTo(d.safe);
					this.hints++;
					this.cursor = cell;
					if (marks[cell]) {
						const fixed = this.marks.slice();
						fixed[cell] = 0;
						this.marks = fixed;
					}
					this.openLocal(cell, false);
					return;
				}
				if (!d.mines.length) return;
				const unflagged = d.mines.filter(j => !marks[j]);
				if (unflagged.length) {
					const cell = this.nearestTo(unflagged);
					this.hints++;
					this.cursor = cell;
					const flagged = this.marks.slice();
					flagged[cell] = 1;
					this.marks = flagged;
					this.bounce(cell);
					this.scheduleSave();
					return;
				}
				for (const j of d.mines) known[j] = true;
			}
		},
		/** The cell of the list nearest to the keyboard's cell. */
		nearestTo(cells: number[]): number {
			const from = this.cursor < 0 ? this.startCellShown() : this.cursor;
			const w = this.width;
			let best = cells[0];
			let bestD = Infinity;
			for (const i of cells) {
				const d = Math.max(Math.abs(Math.floor(i / w) - Math.floor(from / w)), Math.abs((i % w) - (from % w)));
				if (d < bestD) {
					best = i;
					bestD = d;
				}
			}
			return best;
		},

		// =====================================================================
		// Effects
		// =====================================================================

		/** Cells opened: a sparkle in the player's colour, bigger for a wide area. */
		async celebrate(i: number, seatIndex: number, opened: number) {
			const seat = this.seats[seatIndex];
			const color = findColor(seat ? seat.color : DEFAULT_COLORS[0]);
			await new Promise<void>((resolve) => this.$nextTick(() => resolve()));
			const el = cellElement(i);
			if (!fx || !el) return;
			fx.playAt(el, opened > 8 ? 'catch' : 'place', { colors: color.sparks });
		},
		/** A mine taken in territory: stars in the colour of whoever took it. */
		async celebrateTake(i: number, seatIndex: number) {
			const seat = this.seats[seatIndex];
			const color = findColor(seat ? seat.color : DEFAULT_COLORS[0]);
			await new Promise<void>((resolve) => this.$nextTick(() => resolve()));
			const el = cellElement(i);
			if (!fx || !el) return;
			fx.playAt(el, 'stars', { colors: color.sparks });
			fx.pop(el, 1.2);
		},
		/** A mine goes off: the burst, and the board shakes. */
		async boom(i: number) {
			await new Promise<void>((resolve) => this.$nextTick(() => resolve()));
			const el = cellElement(i);
			if (!fx || !el) return;
			fx.playAt(el, 'boom');
			const board = document.querySelector('.ms-board');
			if (board) fx.shake(board, 8);
		},
		/** A flag put down. */
		async bounce(i: number) {
			await new Promise<void>((resolve) => this.$nextTick(() => resolve()));
			const flag = cellElement(i)?.querySelector('.ms-flag');
			if (fx && flag) fx.bounce(flag, 6);
		},
		/** A refused click: the cell shakes. */
		nope(i: number) {
			const el = cellElement(i);
			if (!el || !fx) return;
			fx.playAt(el, 'nope');
			fx.shake(el);
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
			if (!this.isAi || this.over || !mines || document.visibilityState !== 'visible') return;
			const territory = this.kind === 'territory';
			const view: number[] | null = territory ? this.view.slice() : aiView;
			if (!view) return;
			const marks: number[] = this.marks;
			// In territory the mines taken and opened are known to both; in a race, the computer's own findings.
			const known = territory ? view.map((v, i) => v === EXPLODED || (v === COVERED && !!marks[i])) : (aiKnown || []);
			const step = nextStep({
				width: this.width,
				height: this.height,
				view,
				known,
				mines,
				totalMines: this.mineCount,
				claims: territory,
				last: aiLast,
			}, this.aiLevel);
			if (!step) return;
			const seat = this.seats[1];
			const wait = Math.max(step.delay, seat.lockUntil - Date.now());
			const gen0 = generation;
			aiTimer = setTimeout(() => {
				aiTimer = null;
				if (gen0 === generation) this.aiPlay(step);
			}, wait);
		},
		aiPlay(step: AiStep) {
			if (this.over || !mines || !counts) return;
			const seat = this.seats[1];
			aiLast = step.cell;
			if (aiKnown) for (const j of step.found) aiKnown[j] = true;
			const cell = step.cell;
			if (this.kind === 'territory') {
				// The user may have taken or opened the cell meanwhile.
				if (this.view[cell] !== COVERED || this.marks[cell]) {
					this.scheduleAi();
					return;
				}
				const view: number[] = this.view.slice();
				const marks: number[] = this.marks.slice();
				if (step.action === 'claim') {
					if (mines[cell]) {
						marks[cell] = 2;
						this.marks = marks;
						this.celebrateTake(cell, 1);
					} else {
						this.missLocal(1);
					}
				} else if (mines[cell]) {
					view[cell] = EXPLODED;
					marks[cell] = 2;
					this.view = view;
					this.marks = marks;
					this.boom(cell);
					this.missLocal(1);
				} else {
					for (const j of flood(this.width, this.height, counts, view, cell)) marks[j] = 0;
					this.view = view;
					this.marks = marks;
				}
			} else if (aiView && aiKnown) {
				if (aiView[cell] === COVERED) {
					if (mines[cell]) {
						aiView[cell] = EXPLODED;
						aiKnown[cell] = true;
						this.missLocal(1);
					} else {
						flood(this.width, this.height, counts, aiView, cell);
					}
				}
			}
			this.countLocal();
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
			if (!mines) return null;
			const time = this.clockMs();
			const safe = this.safeCells;
			switch (this.kind) {
				case 'solo': {
					const opened = this.seats[0].opened;
					return opened >= safe ? { winner: null, cleared: true, blast: false, timeMs: time, scores: [opened] } : null;
				}
				case 'race': {
					const scores = this.seats.map((s: Seat) => s.opened);
					if (scores[0] >= safe) return { winner: 0, cleared: true, blast: false, timeMs: time, scores };
					if (scores[1] >= safe) return { winner: 1, cleared: true, blast: false, timeMs: time, scores };
					return null;
				}
				default: {
					const scores = this.seats.map((s: Seat) => s.found);
					const blasts = this.view.filter((v: number) => v === EXPLODED).length;
					const winner = territoryWinner(scores[0], scores[1], this.mineCount - scores[0] - scores[1] - blasts);
					return winner === null ? null : { winner, cleared: true, blast: false, timeMs: time, scores };
				}
			}
		},
		finishLocal(result: GameResult) {
			this.stopAi();
			this.stopClock();
			this.over = true;
			this.cursor = -1;
			this.flagMode = false;
			// The mines that were not found are shown.
			if (mines) {
				const m = mines;
				this.view = this.view.map((v: number, i: number) => (v === COVERED && m[i] ? MINE : v));
			}
			this.showResult({ ...result, timeMs: this.clockMs() });
		},
		showResult(result: GameResult) {
			this.result = result;
			this.resultDialog.visible = true;
			this.pending = -1;
			if (!fx) return;
			// Over the whole view: the result dialog covers the board's centre.
			if (result.winner === null) {
				if (result.cleared) fx.play('win', null, null, { colors: findColor(this.seats[0].color).sparks });
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
				unwatchTopics = hub.watchTopic(MINESWEEPER_TOPICS, (event: TopicMessageEvent) => {
					if (isMinesweeperMessage(event.payload)) this.onRoomMessage(event.payload);
				});
			} catch (e) {
				console.warn('[Minesweeper] Rooms cannot be watched:', e);
			}
		},
		async onRoomMessage(m: MinesweeperMessage) {
			const mine = !!room && m.roomId === room.id;
			const byMe = m.by === this.userId;
			switch (m.type) {
				case 'invited':
					await this.loadRooms();
					if (!byMe) {
						const r = this.online.invitations.find((x: MinesweeperRoom) => x.id === m.roomId);
						if (r) {
							this.setNotice(this.t('app.minesweeper.online.invitedYou', { name: opponentOf(r).name },
								'{name} invites you to a game'), LONG_NOTICE_MS);
						}
					}
					return;
				case 'accepted':
					if (mine) {
						await this.reloadRoom();
						if (!byMe) {
							this.setNotice(this.t('app.minesweeper.online.accepted', { name: this.online.opponentName }, '{name} accepted'), LONG_NOTICE_MS);
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
							this.setNotice(this.t('app.minesweeper.online.isReady', { name: this.online.opponentName }, '{name} is ready'), LONG_NOTICE_MS);
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
								this.t('app.minesweeper.online.cancelledYou', { name }, '{name} took the invitation back') :
								m.type === 'left' ?
									this.t('app.minesweeper.online.leftYou', { name }, '{name} left') :
									this.t('app.minesweeper.online.declinedYou', { name }, '{name} declined'), LONG_NOTICE_MS);
						}
					}
					this.loadRooms();
					return;
				case 'open':
					if (mine && room) this.onOpenMessage(m, byMe);
					return;
				case 'flag':
					// Together: the other player's flags are on the shared board.
					if (!mine || byMe || this.kind !== 'coop' || typeof m.cell !== 'number') return;
					if (this.view[m.cell] === COVERED) {
						const marks = this.marks.slice();
						marks[m.cell] = m.flag ? 2 : 0;
						this.marks = marks;
					}
					return;
				case 'claim':
					if (mine && room) this.onClaimMessage(m, byMe);
					return;
				case 'miss':
					if (!mine || byMe || !room) return;
					this.onMissMessage(m);
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
						this.setNotice(this.t('app.minesweeper.online.expired', { name },
							'The game with {name} was closed after a long time without a move'), LONG_NOTICE_MS);
					}
					this.loadRooms();
					return;
			}
		},
		/**
		 * Cells were opened in the room shown. On the shared board the cells
		 * the other player opened are shown at once, and a mine it opened; in
		 * a race only how far the other player got is told. The end of the
		 * game, and anything that does not fit, is read from the room.
		 */
		onOpenMessage(m: MinesweeperMessage, byMe: boolean) {
			const seat = this.seats[byMe ? 0 : 1];
			if (seat && typeof m.opened === 'number') seat.opened = m.opened;
			if (!byMe && m.mine) this.onMissMessage(m);
			if (m.over) {
				this.reloadRoom();
				return;
			}
			if (byMe || this.kind === 'race') return;
			const cells = this.width * this.height;
			const reveal = Array.isArray(m.reveal) ? m.reveal : [];
			const view: number[] = this.view.slice();
			const marks: number[] = this.marks.slice();
			for (const pair of reveal) {
				const [cell, n] = Array.isArray(pair) ? pair : [-1, -1];
				if (typeof cell !== 'number' || typeof n !== 'number' || cell < 0 || cell >= cells || n < 0 || n > 8) {
					this.reloadRoom();
					return;
				}
				if (view[cell] === COVERED) {
					view[cell] = n;
					marks[cell] = 0;
				} else if (view[cell] !== n) {
					this.reloadRoom();
					return;
				}
			}
			if (typeof m.blast === 'number' && m.blast >= 0 && m.blast < cells) {
				view[m.blast] = EXPLODED;
				marks[m.blast] = 2;
			}
			this.view = view;
			this.marks = marks;
			if (typeof m.blast === 'number') this.boom(m.blast);
			else if (typeof m.cell === 'number' && reveal.length) this.celebrate(m.cell, 1, reveal.length);
		},
		/** Territory: a mine was taken in the room shown. */
		onClaimMessage(m: MinesweeperMessage, byMe: boolean) {
			const seat = this.seats[byMe ? 0 : 1];
			if (seat && typeof m.found === 'number') seat.found = m.found;
			if (m.over) {
				this.reloadRoom();
				return;
			}
			if (byMe || typeof m.cell !== 'number' || m.cell < 0 || m.cell >= this.view.length) return;
			if (this.view[m.cell] !== COVERED) {
				this.reloadRoom();
				return;
			}
			const marks = this.marks.slice();
			marks[m.cell] = 2;
			this.marks = marks;
			this.celebrateTake(m.cell, 1);
		},
		/** The other player opened a mine or took a cell with none: it waits. */
		onMissMessage(m: MinesweeperMessage) {
			const seat = this.seats[1];
			if (!seat) return;
			seat.misses = m.misses ?? seat.misses + 1;
			seat.lockUntil = Date.now() + (m.lockMs ?? LOCK_MS);
			this.now = Date.now();
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
			let r: MinesweeperRoom;
			try {
				r = await service.getRoom(roomId);
			} catch (e) {
				this.setNotice(this.t('app.minesweeper.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				return false;
			}
			if (r.status !== 'waiting' && r.status !== 'lobby' && r.status !== 'playing') {
				this.setNotice(this.t('app.minesweeper.online.gone', undefined, 'This invitation is no longer open'), LONG_NOTICE_MS);
				return false;
			}
			if (this.hasGame && !this.over && !this.isOnline) {
				this.joinDialog = { visible: true, room: r };
				return true;
			}
			return this.joinRoom(r);
		},
		/** Accepts the room, when it is an invitation to the user, and shows it. */
		async joinRoom(r: MinesweeperRoom): Promise<boolean> {
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
				this.setNotice(this.t('app.minesweeper.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
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
				console.warn('[Minesweeper] The last game could not be reopened:', e);
				return false;
			}
		},
		/** Shows a room: getting ready in the lobby, or its board. */
		enterRoom(r: MinesweeperRoom) {
			generation++;
			this.stopAi();
			fx?.clear();
			this.dropLocal();
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
			this.hints = 0;
			this.hasGame = true;
			this.over = false;
			this.result = null;
			this.pending = -1;
			this.cursor = -1;
			this.flagMode = false;
			this.resultDialog.visible = false;
			this.setNotice('');
			this.loadBoard(r);
			this.applyRoom(r);
			this.scheduleSave();
			if (r.status === 'finished') this.finishRoom(r);
		},
		/** Puts the room's board, its flags and its mines opened on the board. */
		loadBoard(r: MinesweeperRoom) {
			const level: Level = isLevel(r.level) ? r.level : 1;
			const width = r.width || SIZES[level].width;
			const height = r.height || SIZES[level].height;
			const cells = width * height;
			let view = covered(cells);
			try {
				if (r.board) view = parseView(r.board, cells);
			} catch (e) {
				console.warn('[Minesweeper] The room has no readable board:', e);
			}
			const marks = zeros(cells);
			for (const f of [...(r.flags || []), ...(r.blasts || [])]) {
				if (f.cell >= 0 && f.cell < cells) marks[f.cell] = f.by === this.userId ? 1 : 2;
			}
			this.width = width;
			this.height = height;
			this.mineCount = r.mines || SIZES[level].mines;
			this.level = level;
			this.view = view;
			this.marks = marks;
		},
		/** Copies the room's state, players and clock (not its board) to the view. */
		applyRoom(r: MinesweeperRoom) {
			const o = this.online;
			o.status = r.status;
			o.mode = r.mode;
			o.host = r.host.id;
			o.theme = r.theme || '';
			o.level = isLevel(r.level) ? r.level : 1;
			o.guestReady = !!r.guestReady;
			o.resignedBy = r.resignedBy || '';
			o.blastBy = r.mode === 'coop' && r.blasts?.length ? r.blasts[0].by : '';
			o.chatFileId = r.chatFileId || '';
			this.level = o.level;
			const players: [MinesweeperPlayer, MinesweeperPlayer] = r.you === 'host' ? [r.host, r.guest] : [r.guest, r.host];
			const now = Date.now();
			players.forEach((p, k) => {
				const seat = this.seats[k];
				if (!seat) return;
				seat.color = p.color;
				seat.opened = p.opened;
				seat.found = p.found;
				seat.misses = p.misses;
				seat.lockUntil = p.lockMs > 0 ? now + p.lockMs : 0;
				if (k === 1) seat.name = p.displayName || p.id;
			});
			this.now = now;
			clockBase = r.elapsedMs || 0;
			clockSince = r.status === 'playing' ? now : null;
			this.tick();
		},
		/** Brings the board up to date with a fresh reading of the room. */
		syncRoom(r: MinesweeperRoom) {
			if (!room || r.id !== room.id) return;
			room = r;
			this.loadBoard(r);
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
				this.setNotice(this.t('app.minesweeper.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
			}
		},
		/** The room is finished: shows the result the server recorded. */
		finishRoom(r: MinesweeperRoom) {
			if (this.result) return;
			this.over = true;
			this.cursor = -1;
			this.flagMode = false;
			const winner = r.winner === null ? null : r.winner === 'draw' ? -1 : r.winner === r.you ? 0 : 1;
			const blast = r.mode === 'coop' && !!r.blasts?.length;
			const cleared = !r.resignedBy && !blast;
			const scores = this.seats.map((s: Seat) => (r.mode === 'territory' ? s.found : s.opened));
			this.showResult({ winner, cleared, blast, timeMs: r.elapsedMs || 0, scores });
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
			o.blastBy = '';
			o.chatFileId = '';
		},
		/**
		 * Sends a request about one cell and shows the board it answers with.
		 * The cell shows the request on its way; a refusal reads the room again.
		 */
		async sendMove(i: number, request: (id: string) => Promise<MinesweeperMove>): Promise<MinesweeperMove | null> {
			const id = this.online.roomId;
			if (!service || !id || this.pending >= 0) {
				this.nope(i);
				return null;
			}
			this.pending = i;
			try {
				const move = await request(id);
				if (id !== this.online.roomId) return null;
				this.pending = -1;
				this.syncRoom(move.room);
				return move;
			} catch (e) {
				this.pending = -1;
				this.nope(i);
				this.setNotice(this.t('app.minesweeper.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
				await this.reloadRoom();
				return null;
			}
		},
		async openOnline(i: number, chord: boolean) {
			if (this.myLock) {
				this.nope(i);
				return;
			}
			const s = service;
			if (!s) return;
			const move = await this.sendMove(i, id => s.open(id, i, chord));
			if (!move) return;
			if (move.outcome === 'mine') {
				const blast = (move.room.blasts || []).filter(b => b.by === this.userId).map(b => b.cell);
				this.boom(blast.includes(i) ? i : (blast[blast.length - 1] ?? i));
			} else if (move.outcome === 'opened') {
				this.celebrate(i, 0, 1);
			} else {
				this.nope(i);
			}
		},
		/** Together or in a race: a flag down or up, shown before the server answers. */
		async flagOnline(i: number, on: boolean) {
			const s = service;
			if (!s || this.pending >= 0) return;
			const marks = this.marks.slice();
			marks[i] = on ? 1 : 0;
			this.marks = marks;
			if (on) this.bounce(i);
			await this.sendMove(i, id => s.flag(id, i, on));
		},
		/** Territory: a flag put on a cell takes it when it holds a mine; the server tells. */
		async claimOnline(i: number) {
			if (this.myLock) {
				this.nope(i);
				return;
			}
			const s = service;
			if (!s) return;
			const move = await this.sendMove(i, id => s.flag(id, i, true));
			if (!move) return;
			if (move.outcome === 'found') this.celebrateTake(i, 0);
			else this.nope(i);
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
			const mode: MinesweeperMode = d.mode === 'coop' ? 'coop' : d.rule;
			try {
				const r = await service.invite(d.opponent.identifier, mode, d.level, this.settings.color, this.settings.theme, this.localization.locale);
				this.enterRoom(r);
				this.loadRooms();
				this.setNotice(this.t('app.minesweeper.online.sent', { name: this.online.opponentName }, 'Invitation sent to {name}'), LONG_NOTICE_MS);
			} catch (e) {
				d.error = errorText(e);
			} finally {
				d.busy = false;
			}
		},
		async acceptRoom(r: MinesweeperRoom) {
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
		async declineRoom(r: MinesweeperRoom) {
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
		resumeRoomFromList(r: MinesweeperRoom) {
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
				this.setNotice(this.t('app.minesweeper.online.sent', { name: this.online.opponentName }, 'Invitation sent to {name}'), LONG_NOTICE_MS);
			} catch (e) {
				this.setNotice(this.t('app.minesweeper.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
			}
		},

		// --- the lobby of a room: colours, the difficulty, the board, ready, start ---

		/** Sends a lobby request and shows the room it answers with. */
		async updateRoom(request: (id: string) => Promise<MinesweeperRoom>): Promise<boolean> {
			const id = this.online.roomId;
			if (!service || !id || this.online.busy) return false;
			this.online.busy = true;
			try {
				const r = await request(id);
				if (id === this.online.roomId) this.syncRoom(r);
				return true;
			} catch (e) {
				this.setNotice(this.t('app.minesweeper.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
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
		/** The host starts the game; the server makes the board. */
		async startRoom() {
			if (!service || !this.canStartRoom) return;
			const s = service;
			if (await this.updateRoom(id => s.start(id))) this.loadRooms();
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
				this.setNotice(this.t('app.minesweeper.error', { message: this.setup.error }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
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
				this.setNotice(this.t('app.minesweeper.error', { message: errorText(e) }, 'Something went wrong: {message}'), LONG_NOTICE_MS);
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
			if (seat.kind === 'ai') return this.t('app.minesweeper.seat.computer', undefined, 'Computer');
			if (seat.kind === 'remote') {
				return seat.name || seat.userId || this.t('app.minesweeper.seat.someone', undefined, 'Someone');
			}
			return this.t('app.minesweeper.seat.you', undefined, 'You');
		},
		levelLabel(level: Level): string {
			const names: Record<Level, string> = { 1: 'Easy', 2: 'Medium', 3: 'Hard', 4: 'Expert' };
			return this.t(`app.minesweeper.level.${level}`, undefined, names[level]);
		},
		/** The board of a difficulty: "16 × 16 · 40 mines". */
		sizeLabel(level: Level): string {
			const s = SIZES[level];
			return this.t('app.minesweeper.size', { width: s.width, height: s.height, mines: s.mines }, '{width} × {height} · {mines} mines');
		},
		aiLevelLabel(level: AiLevel): string {
			const names: Record<AiLevel, string> = { 1: 'Chick', 2: 'Songbird', 3: 'Owl', 4: 'Hawk' };
			return this.t(`app.minesweeper.strength.${level}`, undefined, names[level]);
		},
		ruleLabel(rule: Rule): string {
			return rule === 'race' ?
				this.t('app.minesweeper.rule.race', undefined, 'Time attack') :
				this.t('app.minesweeper.rule.territory', undefined, 'Territory');
		},
		/** What a room is: cooperation, or one of the rules against each other. */
		roomModeLabel(mode: MinesweeperMode): string {
			return mode === 'coop' ? this.t('app.minesweeper.mode.coopShort', undefined, 'Together') : this.ruleLabel(mode);
		},
		colorStyle(colorId: string): Record<string, string> {
			const c = findColor(colorId);
			return { background: c.swatch, '--ms-player': c.ink };
		},
		cellLabel(i: number, v: number, flag: boolean): string {
			const at = this.t('app.minesweeper.cell', { row: Math.floor(i / this.width) + 1, col: (i % this.width) + 1 }, 'Row {row}, column {col}');
			if (flag) return `${at}: ${this.t('app.minesweeper.cellFlag', undefined, 'flag')}`;
			if (v === EXPLODED || v === MINE) return `${at}: ${this.t('app.minesweeper.cellMine', undefined, 'mine')}`;
			if (v >= 0) return `${at}: ${v}`;
			return at;
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
				console.warn('[Minesweeper] Saved state could not be read:', e);
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
			const local = this.hasGame && !this.isOnline && !this.over && !!mines;
			const state: SavedState = {
				version: 1,
				settings: JSON.parse(JSON.stringify(this.settings)),
				game: local ? {
					kind: this.kind as SavedGame['kind'],
					level: this.level,
					aiLevel: this.aiLevel,
					layout: formatLayout(mines!),
					start: startCell,
					view: formatView(this.view),
					marks: this.marks.join(''),
					aiView: aiView ? formatView(aiView) : null,
					aiKnown: aiKnown ? aiKnown.map(k => (k ? '1' : '0')).join('') : null,
					colors: [this.seats[0].color, this.seats[1]?.color || otherColor(this.seats[0].color)],
					elapsed: this.clockMs(),
					misses: this.seats[0].misses,
					hints: this.hints,
					aiMisses: this.seats[1]?.misses || 0,
				} : null,
				roomId: this.online.roomId,
			};
			try {
				await this.instance.api.db.setUserSetting(userId, APP_ID, STATE_KEY, state);
			} catch (e) {
				console.warn('[Minesweeper] State could not be saved:', e);
			}
		},
	},
};

VDOM.createApp(App).mount('#app');
