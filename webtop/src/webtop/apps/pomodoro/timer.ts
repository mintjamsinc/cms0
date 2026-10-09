/**
 * Pomodoro: the timer, the plant's growth and the record, as one state.
 *
 * The state is kept in one file per user (store.ts) and shown by the widget
 * on the desktop and the app's window alike; either can start, pause or skip.
 * Every function here takes a state and returns the next one: the frames
 * write the whole state, so two frames that see the same phase end at the
 * same time write the same result instead of counting it twice.
 *
 * A running phase is kept as its end time (`endsAt`), never as ticks counted:
 * timers of a page in the background are slowed down, the clock is not.
 */

export type Phase = 'idle' | 'focus' | 'shortBreak' | 'longBreak';

export interface Settings {
	focusMinutes: number;
	shortBreakMinutes: number;
	longBreakMinutes: number;
	longBreakEvery: number;      // focus sessions before a long break
	autoStartBreaks: boolean;
	ambienceMixId: string | null; // a mix of the Ambience app played during focus
	ambienceVolume: number;       // 0..1
}

export interface TimerState {
	phase: Phase;
	startedAt: string | null;     // ISO; names the phase (a notice's key)
	endsAt: string | null;        // ISO, while running
	pausedRemainingMs: number | null; // while paused
	cycle: number;                // focus sessions finished in this set
}

export interface DayRecord {
	minutes: number;
	sessions: number;
}

export interface PomodoroData {
	version: 1;
	updatedAt: string;
	writer?: string;
	settings: Settings;
	timer: TimerState;
	plant: { focusMinutes: number };
	history: Record<string, DayRecord>; // local date YYYY-MM-DD
}

export type PhaseEvent = { type: 'focusDone' | 'breakDone'; startedAt: string; phase: Phase };

const HISTORY_DAYS = 60;

export const DEFAULT_SETTINGS: Settings = {
	focusMinutes: 25,
	shortBreakMinutes: 5,
	longBreakMinutes: 15,
	longBreakEvery: 4,
	autoStartBreaks: true,
	ambienceMixId: null,
	ambienceVolume: 0.6,
};

export function emptyData(): PomodoroData {
	return {
		version: 1,
		updatedAt: '',
		settings: { ...DEFAULT_SETTINGS },
		timer: idleTimer(0),
		plant: { focusMinutes: 0 },
		history: {},
	};
}

function idleTimer(cycle: number): TimerState {
	return { phase: 'idle', startedAt: null, endsAt: null, pausedRemainingMs: null, cycle };
}

const clampInt = (v: unknown, min: number, max: number, fallback: number) =>
	typeof v === 'number' && Number.isFinite(v) ? Math.max(min, Math.min(max, Math.round(v))) : fallback;

export function normalizeData(value: unknown): PomodoroData | null {
	if (!value || typeof value !== 'object' || (value as PomodoroData).version !== 1) return null;
	const v = value as PomodoroData;
	const s = (v.settings || {}) as Partial<Settings>;
	const t = (v.timer || {}) as Partial<TimerState>;
	const phase: Phase = ['focus', 'shortBreak', 'longBreak'].includes(t.phase as string) ? t.phase as Phase : 'idle';
	const history: Record<string, DayRecord> = {};
	for (const [day, r] of Object.entries(v.history || {})) {
		if (/^\d{4}-\d{2}-\d{2}$/.test(day) && r && typeof r === 'object') {
			history[day] = { minutes: clampInt(r.minutes, 0, 24 * 60, 0), sessions: clampInt(r.sessions, 0, 200, 0) };
		}
	}
	return {
		version: 1,
		updatedAt: typeof v.updatedAt === 'string' ? v.updatedAt : '',
		writer: typeof v.writer === 'string' ? v.writer : undefined,
		settings: {
			focusMinutes: clampInt(s.focusMinutes, 1, 120, DEFAULT_SETTINGS.focusMinutes),
			shortBreakMinutes: clampInt(s.shortBreakMinutes, 1, 60, DEFAULT_SETTINGS.shortBreakMinutes),
			longBreakMinutes: clampInt(s.longBreakMinutes, 1, 120, DEFAULT_SETTINGS.longBreakMinutes),
			longBreakEvery: clampInt(s.longBreakEvery, 2, 12, DEFAULT_SETTINGS.longBreakEvery),
			autoStartBreaks: typeof s.autoStartBreaks === 'boolean' ? s.autoStartBreaks : DEFAULT_SETTINGS.autoStartBreaks,
			ambienceMixId: typeof s.ambienceMixId === 'string' && s.ambienceMixId ? s.ambienceMixId : null,
			ambienceVolume: typeof s.ambienceVolume === 'number' ? Math.max(0, Math.min(1, s.ambienceVolume)) : DEFAULT_SETTINGS.ambienceVolume,
		},
		timer: phase === 'idle'
			? idleTimer(clampInt(t.cycle, 0, 99, 0))
			: {
				phase,
				startedAt: typeof t.startedAt === 'string' ? t.startedAt : null,
				endsAt: typeof t.endsAt === 'string' ? t.endsAt : null,
				pausedRemainingMs: typeof t.pausedRemainingMs === 'number' ? Math.max(0, t.pausedRemainingMs) : null,
				cycle: clampInt(t.cycle, 0, 99, 0),
			},
		plant: { focusMinutes: clampInt(v.plant?.focusMinutes, 0, 10_000_000, 0) },
		history,
	};
}

export function isRunning(t: TimerState): boolean {
	return t.phase !== 'idle' && !!t.endsAt && t.pausedRemainingMs === null;
}

export function isPaused(t: TimerState): boolean {
	return t.phase !== 'idle' && t.pausedRemainingMs !== null;
}

export function phaseMinutes(settings: Settings, phase: Phase): number {
	if (phase === 'shortBreak') return settings.shortBreakMinutes;
	if (phase === 'longBreak') return settings.longBreakMinutes;
	return settings.focusMinutes;
}

/** Milliseconds left in the phase; the full focus length when idle. */
export function remainingMs(data: PomodoroData, now: number): number {
	const t = data.timer;
	if (t.phase === 'idle') return data.settings.focusMinutes * 60_000;
	if (t.pausedRemainingMs !== null) return t.pausedRemainingMs;
	return Math.max(0, Date.parse(t.endsAt || '') - now);
}

export function localDay(time: number): string {
	const d = new Date(time);
	return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function touch(data: PomodoroData, timer: TimerState, extra?: Partial<PomodoroData>): PomodoroData {
	return { ...data, ...extra, timer, updatedAt: new Date().toISOString() };
}

function startPhase(phase: Phase, at: number, settings: Settings, cycle: number): TimerState {
	return {
		phase,
		startedAt: new Date(at).toISOString(),
		endsAt: new Date(at + phaseMinutes(settings, phase) * 60_000).toISOString(),
		pausedRemainingMs: null,
		cycle,
	};
}

/** Start focusing (from idle), or carry on a paused phase. */
export function start(data: PomodoroData, now: number): PomodoroData {
	const t = data.timer;
	if (isPaused(t)) {
		return touch(data, { ...t, endsAt: new Date(now + (t.pausedRemainingMs || 0)).toISOString(), pausedRemainingMs: null });
	}
	if (t.phase === 'idle') return touch(data, startPhase('focus', now, data.settings, t.cycle));
	return data;
}

export function pause(data: PomodoroData, now: number): PomodoroData {
	if (!isRunning(data.timer)) return data;
	return touch(data, { ...data.timer, pausedRemainingMs: remainingMs(data, now) });
}

/** Back to idle; the set (the count towards a long break) starts over. */
export function reset(data: PomodoroData): PomodoroData {
	return touch(data, idleTimer(0));
}

/**
 * Leave the phase early. A focus left early goes to its break without
 * growing the plant; a break left early ends the break.
 */
export function skip(data: PomodoroData, now: number): PomodoroData {
	const t = data.timer;
	if (t.phase === 'focus') {
		const next = breakAfter(t.cycle + 1, data.settings);
		return touch(data, startPhase(next, now, data.settings, t.cycle + 1));
	}
	if (t.phase !== 'idle') return touch(data, idleTimer(t.phase === 'longBreak' ? 0 : t.cycle));
	return data;
}

function breakAfter(cycle: number, settings: Settings): Phase {
	return cycle > 0 && cycle % settings.longBreakEvery === 0 ? 'longBreak' : 'shortBreak';
}

/**
 * Finish the phases whose end has passed. A finished focus grows the plant
 * and is recorded on the day it ended; its break starts at once when breaks
 * start by themselves (from the focus's end, so a late look does not lengthen
 * it). A finished break leaves the timer idle, ready for the next focus.
 */
export function advance(data: PomodoroData, now: number): { data: PomodoroData; events: PhaseEvent[] } {
	let next = data;
	const events: PhaseEvent[] = [];
	for (let guard = 0; guard < 3; guard++) {
		const t = next.timer;
		if (!isRunning(t)) break;
		const endsAt = Date.parse(t.endsAt || '');
		if (!(endsAt <= now)) break;
		if (t.phase === 'focus') {
			const minutes = next.settings.focusMinutes;
			const day = localDay(endsAt);
			const record = next.history[day] || { minutes: 0, sessions: 0 };
			const history = pruneHistory({ ...next.history, [day]: { minutes: record.minutes + minutes, sessions: record.sessions + 1 } }, now);
			const cycle = t.cycle + 1;
			const timer = next.settings.autoStartBreaks
				? startPhase(breakAfter(cycle, next.settings), endsAt, next.settings, cycle)
				: idleTimer(cycle);
			events.push({ type: 'focusDone', startedAt: t.startedAt || '', phase: 'focus' });
			next = touch(next, timer, { plant: { focusMinutes: next.plant.focusMinutes + minutes }, history });
		} else {
			events.push({ type: 'breakDone', startedAt: t.startedAt || '', phase: t.phase });
			next = touch(next, idleTimer(t.phase === 'longBreak' ? 0 : t.cycle));
		}
	}
	return { data: next, events };
}

function pruneHistory(history: Record<string, DayRecord>, now: number): Record<string, DayRecord> {
	const oldest = localDay(now - HISTORY_DAYS * 86_400_000);
	const out: Record<string, DayRecord> = {};
	for (const [day, r] of Object.entries(history)) if (day >= oldest) out[day] = r;
	return out;
}

export function formatClock(ms: number): string {
	const total = Math.ceil(ms / 1000);
	const m = Math.floor(total / 60);
	const s = total % 60;
	return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}
