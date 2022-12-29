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

package org.mintjams.rt.cms.internal.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

public class Systems {

	private static final Collection<String> STANDARD_ARCHS = Collections.unmodifiableCollection(Arrays.asList(
			new String[] { "x86_64" }));

	public static boolean isOSMatch(String osName) {
		return System.getProperty("os.name").toLowerCase().startsWith(osName.toLowerCase());
	}

	public static boolean isWindows() {
		return isOSMatch("Windows");
	}

	public static boolean isLinux() {
		return isOSMatch("Linux");
	}

	public static String getOSName() {
		if (isLinux()) {
			return "linux";
		}
		if (isWindows()) {
			return "win32";
		}
		throw new IllegalStateException(System.getProperty("os.name"));
	}

	public static String getOSArch() {
		String arch = System.getProperty("os.arch").toLowerCase();
		if (arch.equals("amd64")) {
			arch = "x86_64";
		}
		if (!STANDARD_ARCHS.contains(arch)) {
			throw new IllegalStateException(System.getProperty("os.arch"));
		}
		return arch;
	}

}
