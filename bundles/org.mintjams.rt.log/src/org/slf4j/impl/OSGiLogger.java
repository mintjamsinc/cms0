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

package org.slf4j.impl;

import org.mintjams.rt.log.internal.Activator;
import org.mintjams.rt.log.internal.LogEntryImpl;
import org.osgi.service.log.LogLevel;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

public class OSGiLogger extends MarkerIgnoringBase {

	private static final long serialVersionUID = 1L;

	protected OSGiLogger(String name) {
		this.name = name;
	}

	private boolean isEnabled(LogLevel logLevel) {
		return Activator.getLoggerAdmin().getLoggerContext((String) null).getEffectiveLogLevel(name).implies(logLevel);
	}

	@Override
	public boolean isTraceEnabled() {
		return isEnabled(LogLevel.TRACE);
	}

	@Override
	public boolean isDebugEnabled() {
		return isEnabled(LogLevel.DEBUG);
	}

	@Override
	public boolean isInfoEnabled() {
		return isEnabled(LogLevel.INFO);
	}

	@Override
	public boolean isWarnEnabled() {
		return isEnabled(LogLevel.WARN);
	}

	@Override
	public boolean isErrorEnabled() {
		return isEnabled(LogLevel.ERROR);
	}

	@Override
	public void trace(String msg) {
		log(LogLevel.TRACE, msg, null);
	}

	@Override
	public void trace(String format, Object arg) {
		formatAndLog(LogLevel.TRACE, format, arg, null);
	}

	@Override
	public void trace(String format, Object arg1, Object arg2) {
		formatAndLog(LogLevel.TRACE, format, arg1, arg2);
	}

	@Override
	public void trace(String format, Object... argArray) {
		formatAndLog(LogLevel.TRACE, format, argArray);
	}

	@Override
	public void trace(String msg, Throwable t) {
		log(LogLevel.TRACE, msg, t);
	}

	@Override
	public void debug(String msg) {
		log(LogLevel.DEBUG, msg, null);
	}

	@Override
	public void debug(String format, Object arg) {
		formatAndLog(LogLevel.DEBUG, format, arg, null);
	}

	@Override
	public void debug(String format, Object arg1, Object arg2) {
		formatAndLog(LogLevel.DEBUG, format, arg1, arg2);
	}

	@Override
	public void debug(String format, Object... argArray) {
		formatAndLog(LogLevel.DEBUG, format, argArray);
	}

	@Override
	public void debug(String msg, Throwable t) {
		log(LogLevel.DEBUG, msg, t);
	}

	@Override
	public void info(String msg) {
		log(LogLevel.INFO, msg, null);
	}

	@Override
	public void info(String format, Object arg) {
		formatAndLog(LogLevel.INFO, format, arg, null);
	}

	@Override
	public void info(String format, Object arg1, Object arg2) {
		formatAndLog(LogLevel.INFO, format, arg1, arg2);
	}

	@Override
	public void info(String format, Object... argArray) {
		formatAndLog(LogLevel.INFO, format, argArray);
	}

	@Override
	public void info(String msg, Throwable t) {
		log(LogLevel.INFO, msg, t);
	}

	@Override
	public void warn(String msg) {
		log(LogLevel.WARN, msg, null);
	}

	@Override
	public void warn(String format, Object arg) {
		formatAndLog(LogLevel.WARN, format, arg, null);
	}

	@Override
	public void warn(String format, Object arg1, Object arg2) {
		formatAndLog(LogLevel.WARN, format, arg1, arg2);
	}

	@Override
	public void warn(String format, Object... argArray) {
		formatAndLog(LogLevel.WARN, format, argArray);
	}

	@Override
	public void warn(String msg, Throwable t) {
		log(LogLevel.WARN, msg, t);
	}

	@Override
	public void error(String msg) {
		log(LogLevel.ERROR, msg, null);
	}

	@Override
	public void error(String format, Object arg) {
		formatAndLog(LogLevel.ERROR, format, arg, null);
	}

	@Override
	public void error(String format, Object arg1, Object arg2) {
		formatAndLog(LogLevel.ERROR, format, arg1, arg2);
	}

	@Override
	public void error(String format, Object... argArray) {
		formatAndLog(LogLevel.ERROR, format, argArray);
	}

	@Override
	public void error(String msg, Throwable t) {
		log(LogLevel.ERROR, msg, t);
	}

	private void log(LogLevel logLevel, String message, Throwable t) {
		Activator.getLogQueue().add(new LogEntryImpl(name)
				.setLogLevel(logLevel)
				.setMessage(message)
				.setException(t));
	}

	private void formatAndLog(LogLevel level, String format, Object arg1, Object arg2) {
		log(level, MessageFormatter.format(format, arg1, arg2).getMessage(), null);
	}

	private void formatAndLog(LogLevel level, String format, Object... argArray) {
		log(level, MessageFormatter.arrayFormat(format, argArray).getMessage(), null);
	}

}
