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

package org.mintjams.rt.cms.internal.script.engine;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.lang3.StringUtils;
import org.mintjams.rt.cms.internal.CmsService;

public class ScriptCache {

	private final String fName;
	private final int fMaxEntries;
	private final ConcurrentMap<String, ResourceScript> fCache;
	private final ConcurrentLinkedDeque<String> fOrder;

	public ScriptCache(String name, int maxEntries) {
		fName = name;
		fMaxEntries = maxEntries;
		fCache = new ConcurrentHashMap<>();
		fOrder = new ConcurrentLinkedDeque<>();
	}

	public String getName() {
		return fName;
	}

	public ResourceScript getScript(String scriptName) {
		if (StringUtils.equals(scriptName, ResourceScript.NO_SCRIPT_NAME)) {
			return null;
		}
		ResourceScript script = fCache.get(scriptName);
		if (script != null) {
			fOrder.remove(scriptName);
			fOrder.addLast(scriptName);
		}
		return script;
	}

	public boolean registerScript(ResourceScript script) {
		if (StringUtils.equals(script.getScriptName(), ResourceScript.NO_SCRIPT_NAME)) {
			return false;
		}
		fCache.put(script.getScriptName(), script);
		fOrder.remove(script.getScriptName());
		fOrder.addLast(script.getScriptName());
		while (fOrder.size() > fMaxEntries) {
			String eldest = fOrder.pollFirst();
			if (eldest != null) {
				fCache.remove(eldest);
			}
		}
		CmsService.getLogger(getClass()).debug(fName + " script cached: " + script.getScriptName() + " (" + fCache.size() + ")");
		return true;
	}

	public boolean removeScript(String scriptName) {
		fOrder.remove(scriptName);
		return (fCache.remove(scriptName) != null);
	}

	public void clear() {
		if (fCache != null) {
			fCache.clear();
			fOrder.clear();
		}
	}

}
