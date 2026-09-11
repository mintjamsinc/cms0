/*
 * Copyright (c) 2022 MintJams Inc.
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

package org.mintjams.cms;

/**
 * The Clock interface defines the contract for a clock service that provides time-based events.
 * This interface includes event topics for minute, hour, day, month, and year ticks, allowing clients to subscribe to these events and receive notifications when the corresponding time intervals occur.
 * Events are published on UTC time, and the current time is provided in ISO 8601 format (yyyy-MM-dd'T'HH:mm:ss.SSSXXX) as a property of the event.
 * <p>
 * A minute tick is posted every minute, together with a tick for each larger unit that rolled over since the previous one, largest first:
 * a new year posts YEAR, MONTH, DAY, HOUR and MINUTE; a new month posts MONTH, DAY, HOUR and MINUTE.
 * All events of one tick carry the same time, truncated to the minute (seconds and milliseconds are zero), e.g. 2027-01-01T00:00:00.000Z.
 * Minutes the node did not see (e.g. while it was down or suspended) are not replayed.
 * <p>
 * The events are local to each node: in a cluster, every node posts them, so a handler that must run once for the whole cluster needs its own guard.
 */
public interface Clock {

	/**
	 * Event topic for minute tick.
	 * property: time (String) - The current time in ISO 8601 format (yyyy-MM-dd'T'HH:mm:ss.SSSXXX).
	 */
	String TOPIC_MINUTE = "org/mintjams/cms/Clock/MINUTE";

	/**
	 * Event topic for hour tick.
	 * property: time (String) - The current time in ISO 8601 format (yyyy-MM-dd'T'HH:mm:ss.SSSXXX).
	 */
	String TOPIC_HOUR = "org/mintjams/cms/Clock/HOUR";

	/**
	 * Event topic for day tick.
	 * property: time (String) - The current time in ISO 8601 format (yyyy-MM-dd'T'HH:mm:ss.SSSXXX).
	 */
	String TOPIC_DAY = "org/mintjams/cms/Clock/DAY";

	/**
	 * Event topic for month tick.
	 * property: time (String) - The current time in ISO 8601 format (yyyy-MM-dd'T'HH:mm:ss.SSSXXX).
	 */
	String TOPIC_MONTH = "org/mintjams/cms/Clock/MONTH";

	/**
	 * Event topic for year tick.
	 * property: time (String) - The current time in ISO 8601 format (yyyy-MM-dd'T'HH:mm:ss.SSSXXX).
	 */
	String TOPIC_YEAR = "org/mintjams/cms/Clock/YEAR";

}
