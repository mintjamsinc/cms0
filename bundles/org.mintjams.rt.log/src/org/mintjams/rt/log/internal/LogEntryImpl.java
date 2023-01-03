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

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogLevel;
import org.osgi.service.log.LogService;

public class LogEntryImpl implements LogEntry {

	private final String fLoggerName;
	private Bundle fBundle;
	private Throwable fException;
	private LogLevel fLogLevel;
	private StackTraceElement fLocation;
	private String fMessage;
	private ServiceReference<?> fServiceReference;
	private long fSequence;
	private long fThreadID;
	private String fThreadInfo;
	private long fTime;

	public LogEntryImpl(String loggerName) {
		fLoggerName = loggerName;
		fLocation = location();
		fSequence = Activator.nextSequence();
		fThreadID = Thread.currentThread().getId();
		fThreadInfo = Thread.currentThread().getName();
		fTime = System.currentTimeMillis();
	}

	private StackTraceElement location() {
		StackTraceElement[] elements = Thread.currentThread().getStackTrace();
		if (elements.length == 0) {
			return null;
		}

		for (int i = 1; i < elements.length; i++) {
			if (!elements[i].getClassName().startsWith("org.mintjams.rt.log.")) {
				return elements[i];
			}
		}
		return elements[1];
	}

	@Override
	public Bundle getBundle() {
		return fBundle;
	}


	public LogEntryImpl setBundle(Bundle bundle) {
		fBundle = bundle;
		return this;
	}

	@Override
	public Throwable getException() {
		return fException;
	}

	public LogEntryImpl setException(Throwable exception) {
		fException = exception;
		return this;
	}

	@SuppressWarnings("deprecation")
	@Override
	public int getLevel() {
		if (LogLevel.ERROR == fLogLevel) {
			return LogService.LOG_ERROR;
		}

		if (LogLevel.WARN == fLogLevel) {
			return LogService.LOG_WARNING;
		}

		if (LogLevel.INFO == fLogLevel) {
			return LogService.LOG_INFO;
		}

		if (LogLevel.DEBUG == fLogLevel) {
			return LogService.LOG_DEBUG;
		}

		return 0;
	}

	@Override
	public StackTraceElement getLocation() {
		return fLocation;
	}

	@Override
	public LogLevel getLogLevel() {
		return fLogLevel;
	}

	public LogEntryImpl setLogLevel(LogLevel logLevel) {
		fLogLevel = logLevel;
		return this;
	}

	@Override
	public String getLoggerName() {
		return fLoggerName;
	}

	@Override
	public String getMessage() {
		return fMessage;
	}

	public LogEntryImpl setMessage(String message) {
		fMessage = message;
		return this;
	}

	@Override
	public long getSequence() {
		return fSequence;
	}

	@Override
	public ServiceReference<?> getServiceReference() {
		return fServiceReference;
	}

	public LogEntryImpl setServiceReference(ServiceReference<?> serviceReference) {
		fServiceReference = serviceReference;
		return this;
	}

	@Override
	public String getThreadInfo() {
		return fThreadInfo;
	}

	@Override
	public long getTime() {
		return fTime;
	}

	public long getThreadID() {
		return fThreadID;
	}

	@Override
	public String toString() {
		return new SimpleFormatter().format(asLogRecord());
	}

	private LogRecord asLogRecord() {
		Level level;
		switch (getLogLevel()) {
		case ERROR:
			level = Level.SEVERE;
			break;
		case WARN:
			level = Level.WARNING;
			break;
		case INFO:
			level = Level.INFO;
			break;
		case DEBUG:
			level = Level.FINE;
			break;
		case TRACE:
			level = Level.FINER;
			break;
		default:
			level = Level.FINEST;
		}
		LogRecord logRecord = new LogRecord(level, getMessage());
		logRecord.setInstant(new java.util.Date(getTime()).toInstant());
		logRecord.setLoggerName(getLoggerName());
		logRecord.setThrown(getException());
		logRecord.setThreadID(Math.toIntExact(getThreadID()));
		return logRecord;
	}

}
