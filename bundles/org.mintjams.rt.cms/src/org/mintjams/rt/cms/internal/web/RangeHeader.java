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

package org.mintjams.rt.cms.internal.web;

import javax.servlet.http.HttpServletRequest;

import org.mintjams.tools.lang.Strings;

public class RangeHeader {

	private String fUnit;
	private long fStart;
	private long fEnd;
	private long fLength;

	private RangeHeader(HttpServletRequest request) {
		String range = request.getHeader("Range");
		if (Strings.isEmpty(range)) {
			throw new IllegalArgumentException("Range header: " + range);
		}

		String[] keyValue = range.split("=");
		String[] ranges = keyValue[1].split("-");
		fUnit = keyValue[0];
		fStart = 0;
		if (!Strings.isEmpty(ranges[0])) {
			fStart = Long.parseLong(ranges[0]);
		}
		fEnd = -1;
		if (ranges.length > 1 && !Strings.isEmpty(ranges[1])) {
			fEnd = Long.parseLong(ranges[1]);
		}
		fLength = -1;
		if (fEnd >= 0) {
			fLength = fEnd - fStart + 1;
		}

		if (!fUnit.equals("bytes")) {
			throw new IllegalArgumentException("Range header: " + range);
		}
		if (fStart < 0) {
			throw new IllegalArgumentException("Range header: " + range);
		}
		if (fEnd >= 0) {
			if (fStart > fEnd) {
				throw new IllegalArgumentException("Range header: " + range);
			}
		}
	}

	public static boolean isRangeRequest(HttpServletRequest request) {
		return !Strings.isEmpty(request.getHeader("Range"));
	}

	public static RangeHeader create(HttpServletRequest request) {
		return new RangeHeader(request);
	}

	public String getUnit() {
		return fUnit;
	}

	public long getStart() {
		return fStart;
	}

	public long getEnd() {
		return fEnd;
	}

	public long getLength() {
		return fLength;
	}

}
