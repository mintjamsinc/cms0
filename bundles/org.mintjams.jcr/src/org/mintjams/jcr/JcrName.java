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

package org.mintjams.jcr;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class JcrName extends QName {

	private static final long serialVersionUID = 1L;

	private NamespaceProvider fNamespaceProvider;

	private JcrName(String namespaceURI, String localPart, String prefix) {
		super(namespaceURI, localPart, Strings.defaultString(prefix, XMLConstants.DEFAULT_NS_PREFIX));
	}

	private JcrName(String namespaceURI, String localPart) {
		super(namespaceURI, localPart);
	}

	public JcrName with(NamespaceProvider namespaceProvider) {
		fNamespaceProvider = namespaceProvider;
		return this;
	}

	public String getPrefix() {
		if (Strings.isEmpty(super.getPrefix()) && !Strings.isEmpty(getNamespaceURI())) {
			if (fNamespaceProvider != null) {
				try {
					return fNamespaceProvider.getPrefix(getNamespaceURI());
				} catch (Throwable ex) {
					throw Cause.create(ex).wrap(IllegalStateException.class);
				}
			}
		}

		return super.getPrefix();
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		if (!Strings.isEmpty(getPrefix())) {
			buf.append(getPrefix()).append(":");
		} else if (!Strings.isEmpty(getNamespaceURI())) {
			buf.append("{").append(getNamespaceURI()).append("}");
		}
		buf.append(getLocalPart());
		return buf.toString();
	}

	public static JcrName create(String localPart, String prefix) {
		return new JcrName(null, localPart, prefix);
	}

	public static JcrName valueOf(String qNameAsString) {
		QName qName = QName.valueOf(qNameAsString);
		String localPart = qName.getLocalPart();
		int p = localPart.indexOf(":");
		if (p != -1) {
			return new JcrName(qName.getNamespaceURI(), localPart.substring(p + 1), localPart.substring(0, p));
		}
		return new JcrName(qName.getNamespaceURI(), qName.getLocalPart());
	}

}
