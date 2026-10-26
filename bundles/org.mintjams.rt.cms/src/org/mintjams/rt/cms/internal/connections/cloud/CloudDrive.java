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

package org.mintjams.rt.cms.internal.connections.cloud;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A drive the user can open: their own ({@link Kind#MINE}, id
 * {@value CloudEntry#MY_DRIVE}), or a shared drive (Google) / a document
 * library of a followed site (Microsoft) they are a member of
 * ({@link Kind#SHARED}). Everyone who is a member sees a shared drive under
 * the same id, so its locations can be passed from one person to another.
 */
public final class CloudDrive {

	public enum Kind {
		MINE, SHARED
	}

	private final String fProvider;
	private final String fId;
	private final String fName;
	private final Kind fKind;
	private final String fWebUrl;

	public CloudDrive(String provider, String id, String name, Kind kind, String webUrl) {
		fProvider = provider;
		fId = id;
		fName = name;
		fKind = kind;
		fWebUrl = webUrl;
	}

	public String getProvider() {
		return fProvider;
	}

	public String getId() {
		return fId;
	}

	public String getName() {
		return fName;
	}

	public Kind getKind() {
		return fKind;
	}

	public String getWebUrl() {
		return fWebUrl;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("provider", fProvider);
		m.put("id", fId);
		m.put("name", fName);
		m.put("kind", fKind.name());
		m.put("webUrl", fWebUrl);
		return m;
	}

}
