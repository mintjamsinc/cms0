/*
 * Copyright (c) 2026 MintJams Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.mintjams.rt.cms.internal;

import java.io.Closeable;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.mintjams.cms.Clock;
import org.mintjams.rt.cms.internal.util.ISO8601;

/**
 * Posts the {@link Clock} events: a tick on every UTC minute, together with a
 * tick for each larger unit that rolled over since the previous one, largest
 * first — a new year posts YEAR, MONTH, DAY, HOUR and MINUTE; a new month
 * posts MONTH, DAY, HOUR and MINUTE. Every event of a tick carries the same
 * {@code time}: the minute it fired in, with seconds and milliseconds zero.
 *
 * <p>Each tick is scheduled afresh against the wall clock rather than at a
 * fixed rate, so ticks stay on the minute boundary however far the monotonic
 * timer drifts from it. Minutes this node did not see (the host was suspended,
 * the clock was stepped forward) are not replayed: the next tick carries the
 * minute it actually fires in, plus every unit that changed meanwhile. A tick
 * that finds its minute already ticked — it woke a moment early, or the clock
 * was stepped back within the minute — posts nothing.
 *
 * <p>The events are local to this node: in a cluster, every node ticks.
 */
public class CmsClock implements Closeable {

	private ScheduledExecutorService fScheduler;
	/** The minute of the last tick; touched only by the scheduler thread once open. */
	private ZonedDateTime fLastTick;

	public synchronized CmsClock open() {
		if (fScheduler != null) {
			return this;
		}

		// The minute already running when the clock opens was not seen to begin,
		// so it is not ticked; the first tick is the next boundary.
		fLastTick = currentMinute();
		fScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "cms-clock");
			thread.setDaemon(true);
			return thread;
		});
		scheduleNextTick();
		return this;
	}

	@Override
	public synchronized void close() throws IOException {
		if (fScheduler != null) {
			fScheduler.shutdownNow();
			fScheduler = null;
		}
	}

	private synchronized void scheduleNextTick() {
		if (fScheduler == null) {
			return;
		}

		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		long delay = ChronoUnit.MILLIS.between(now, now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1));
		fScheduler.schedule(this::tick, Math.max(delay, 1L), TimeUnit.MILLISECONDS);
	}

	private void tick() {
		try {
			ZonedDateTime minute = currentMinute();
			if (!minute.equals(fLastTick)) {
				postTick(minute, fLastTick);
				fLastTick = minute;
			}
		} catch (Throwable ex) {
			CmsService.getLogger(getClass()).warn("Could not post the clock events.", ex);
		} finally {
			scheduleNextTick();
		}
	}

	private static void postTick(ZonedDateTime minute, ZonedDateTime previous) {
		boolean year = (minute.getYear() != previous.getYear());
		boolean month = year || (minute.getMonthValue() != previous.getMonthValue());
		boolean day = month || (minute.getDayOfMonth() != previous.getDayOfMonth());
		boolean hour = day || (minute.getHour() != previous.getHour());

		String time = ISO8601.format(minute.toInstant());
		if (year) {
			post(Clock.TOPIC_YEAR, time);
		}
		if (month) {
			post(Clock.TOPIC_MONTH, time);
		}
		if (day) {
			post(Clock.TOPIC_DAY, time);
		}
		if (hour) {
			post(Clock.TOPIC_HOUR, time);
		}
		post(Clock.TOPIC_MINUTE, time);
	}

	private static void post(String topic, String time) {
		Map<String, Object> properties = new HashMap<>();
		properties.put("time", time);
		CmsService.postEvent(topic, properties);
	}

	private static ZonedDateTime currentMinute() {
		return ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
	}

}
