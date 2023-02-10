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

package org.mintjams.searchindex.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class QueryStatements {

	private static final String[] ESCAPES = new String[] { "\\", "\"", "`", "<", ">", "#", "%", "{", "}", "|", "^", "~",
			"[", "]", ";", "/", "?", ":", "@", "=", "+", "&", "-", "!", "(", ")", "*", " ", "¥t", "¥r", "¥n" };

	private QueryStatements() {}

	public static String escape(String value, String... excludes) {
		List<String> excludeList = null;
		if (excludes != null && excludes.length > 0) {
			excludeList = Arrays.asList(excludes);
		}

		List<String> escapes = new ArrayList<>();
		for (String e : ESCAPES) {
			if (excludeList != null && excludeList.contains(e)) {
				continue;
			}

			if (value.indexOf(e) != -1) {
				escapes.add(e);
			}
		}

		for (String e : escapes) {
			StringBuilder buf = new StringBuilder();
			for (String text = value;;) {
				int p = text.indexOf(e);
				if (p == -1) {
					buf.append(text);
					break;
				}

				buf.append(text.substring(0, p)).append("\\" + e);
				text = text.substring(p + 1);
			}
			value = buf.toString();
		}

		return value;
	}

	public static String unescape(String value) {
		List<String> escapes = new ArrayList<>();
		for (String e : ESCAPES) {
			if (value.indexOf("\\" + e) != -1) {
				escapes.add(e);
			}
		}

		Collections.reverse(escapes);
		for (String e : escapes) {
			StringBuilder buf = new StringBuilder();
			for (String text = value;;) {
				int p = text.indexOf("\\" + e);
				if (p == -1) {
					buf.append(text);
					break;
				}

				buf.append(text.substring(0, p)).append(e);
				text = text.substring(p + 2);
			}
			value = buf.toString();
		}

		return value;
	}

}
