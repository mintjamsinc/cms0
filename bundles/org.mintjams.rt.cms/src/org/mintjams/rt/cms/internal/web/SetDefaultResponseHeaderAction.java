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

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.tools.util.Action;
import org.mintjams.tools.util.ActionChain;
import org.mintjams.tools.util.ActionContext;
import org.mintjams.tools.util.ActionException;

public class SetDefaultResponseHeaderAction implements Action {

	public void doAction(ActionContext context, ActionChain chain) throws ActionException {
		setHeaders(context);
		chain.doAction(context);
	}

	private void setHeaders(ActionContext context) {
		Map<String, Object> headers = Webs.getDefaultResponseHeaderConfig(context);
		if (headers == null) {
			headers = new HashMap<>();
		}

		HttpServletResponse response = Webs.getResponse(context);
		response.setDateHeader("Date", new Date().getTime());
		for (String name : headers.keySet()) {
			Object value = headers.get(name);

			if (value instanceof String) {
				response.setHeader(name, (String) value);
				continue;
			}

			if (value instanceof Number) {
				response.setIntHeader(name, ((Number) value).intValue());
				continue;
			}

			if (value instanceof java.util.Date) {
				response.setDateHeader(name, ((java.util.Date) value).getTime());
				continue;
			}

			if (value.getClass().isArray()) {
				Object[] values = (Object[]) value;
				if (values.length == 0) {
					continue;
				}

				for (int i = 0; i < values.length; i++) {
					if (values[i] instanceof String) {
						String v = (String) values[i];
						if (i == 0) {
							response.setHeader(name, v);
						} else {
							response.addHeader(name, v);
						}
						continue;
					}

					if (values[i] instanceof Number) {
						int v = ((Number) values[i]).intValue();
						if (i == 0) {
							response.setIntHeader(name, v);
						} else {
							response.addIntHeader(name, v);
						}
						continue;
					}

					if (values[i] instanceof java.util.Date) {
						long v = ((java.util.Date) value).getTime();
						if (i == 0) {
							response.setDateHeader(name, v);
						} else {
							response.addDateHeader(name, v);
						}
						continue;
					}

					CmsService.getLogger(getClass()).warn("Ignoring a default response header '" + name + "' with value '" + values[i] + "'.");
				}

				continue;
			}

			CmsService.getLogger(getClass()).warn("Ignoring a default response header '" + name + "' with value '" + value + "'.");
		}
	}

}
