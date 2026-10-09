/**
 * Pomodoro: what the widget and the window show of the state, as plain values
 * for their reactive data.
 */
import { formatClock, isPaused, isRunning, localDay, remainingMs, type Phase, type PomodoroData } from './timer.js';
import { minutesToNext, stageIndex, stageProgress } from './plant.js';

export interface TimerView {
	phase: Phase;
	running: boolean;
	paused: boolean;
	clock: string;
	/** 0..1 of the phase gone by. */
	progress: number;
	/** One per focus session of the set; true when done. */
	dots: boolean[];
	stage: number;
	stageProgress: number;
	minutesToNext: number | null;
	totalMinutes: number;
	todayMinutes: number;
	todaySessions: number;
}

export function timerView(data: PomodoroData, now: number): TimerView {
	const t = data.timer;
	const left = remainingMs(data, now);
	const full = (t.phase === 'shortBreak' ? data.settings.shortBreakMinutes
		: t.phase === 'longBreak' ? data.settings.longBreakMinutes
			: data.settings.focusMinutes) * 60_000;
	const every = data.settings.longBreakEvery;
	// A full set stays shown until the next focus starts a new one.
	const doneInSet = t.cycle > 0 && t.cycle % every === 0 && t.phase !== 'focus' ? every : t.cycle % every;
	const today = data.history[localDay(now)];
	return {
		phase: t.phase,
		running: isRunning(t),
		paused: isPaused(t),
		clock: formatClock(left),
		progress: t.phase === 'idle' ? 0 : Math.max(0, Math.min(1, 1 - left / full)),
		dots: Array.from({ length: every }, (_, i) => i < doneInSet),
		stage: stageIndex(data.plant.focusMinutes),
		stageProgress: stageProgress(data.plant.focusMinutes),
		minutesToNext: minutesToNext(data.plant.focusMinutes),
		totalMinutes: data.plant.focusMinutes,
		todayMinutes: today?.minutes || 0,
		todaySessions: today?.sessions || 0,
	};
}
