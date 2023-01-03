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

package org.mintjams.rt.log.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Vector;

import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogListener;
import org.osgi.service.log.LogReaderService;

public class LogReaderServiceImpl implements LogReaderService, Closeable {

	private final List<LogListener> fLogListeners = new ArrayList<>();

	@Override
	public void addLogListener(LogListener listener) {
		synchronized (fLogListeners) {
			if (fLogListeners.contains(listener)) {
				return;
			}

			fLogListeners.add(listener);
		}

		Activator.getDefault().onLogListenerAdded(listener);
	}

	@Override
	public Enumeration<LogEntry> getLog() {
		return new Vector<LogEntry>().elements();
	}

	@Override
	public void removeLogListener(LogListener listener) {
		synchronized (fLogListeners) {
			fLogListeners.remove(listener);
		}
	}

	public LogListener[] getLogListeners() {
		synchronized (fLogListeners) {
			return fLogListeners.toArray(LogListener[]::new);
		}
	}

	@Override
	public void close() throws IOException {
		synchronized (fLogListeners) {
			fLogListeners.clear();
		}
	}

}
