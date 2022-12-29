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

package org.mintjams.rt.jcr.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Calendar;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.xml.namespace.QName;

import org.mintjams.rt.jcr.internal.adapter.BinaryValueAdapter;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.UnadaptableValueException;
import org.mintjams.tools.collections.AdaptableList;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class JcrValue implements Value, Adaptable {

	public static final String STRING_NS_URI = "http://www.mintjams.jp/jcr/1.0/value/string";
	public static final String BINARY_NS_URI = "http://www.mintjams.jp/jcr/1.0/value/file";

	private final Object fValue;
	private final int fType;
	private Adaptable fAdaptable;

	private JcrValue(Object value, int type) {
		fValue = value;
		fType = type;
	}

	public static JcrValue create(Object value, int type) {
		return new JcrValue(value, type);
	}

	@Override
	public Binary getBinary() throws RepositoryException {
		validate();
		try {
			Binary v = adapt(Binary.class);
			if (v == null) {
				try {
					return JcrBinary.create(new byte[0]);
				} catch (IOException ex) {
					throw (RepositoryException) new RepositoryException(ex.getMessage()).initCause(ex);
				}
			}
			return v;
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class);
		}
	}

	@Override
	public boolean getBoolean() throws ValueFormatException, RepositoryException {
		validate();
		try {
			Boolean v = adapt(Boolean.class);
			return (v == null) ? Boolean.FALSE : v;
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class);
		}
	}

	@Override
	public Calendar getDate() throws ValueFormatException, RepositoryException {
		validate();
		try {
			return adapt(Calendar.class);
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class);
		}
	}

	@Override
	public BigDecimal getDecimal() throws ValueFormatException, RepositoryException {
		validate();
		try {
			BigDecimal v = adapt(BigDecimal.class);
			return (v == null) ? new BigDecimal(0) : v;
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class);
		}
	}

	@Override
	public double getDouble() throws ValueFormatException, RepositoryException {
		validate();
		try {
			Double v = adapt(Double.class);
			return (v == null) ? 0 : v;
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class);
		}
	}

	@Override
	public long getLong() throws ValueFormatException, RepositoryException {
		validate();
		try {
			Long v = adapt(Long.class);
			return (v == null) ? 0 : v;
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class);
		}
	}

	@Override
	public InputStream getStream() throws RepositoryException {
		validate();
		try {
			InputStream v = adapt(InputStream.class);
			return (v == null) ? new ByteArrayInputStream(new byte[0]) : v;
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class);
		}
	}

	@Override
	public String getString() throws ValueFormatException, IllegalStateException, RepositoryException {
		validate();
		try {
			String v = adapt(String.class);
			return (v == null) ? "" : v;
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class);
		}
	}

	public long getLength() throws ValueFormatException, IllegalStateException, RepositoryException {
		validate();
		if (fValue instanceof QName) {
			QName qName = (QName) fValue;
			if (STRING_NS_URI.equals(qName.getNamespaceURI())) {
				return qName.getLocalPart().length();
			}

			try {
				return fAdaptable.adaptTo(WorkspaceQuery.class).files().getSize(qName.getLocalPart());
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(ValueFormatException.class);
			}
		}

		try {
			return adapt(String.class).length();
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class);
		}
	}

	@Override
	public int getType() {
		return fType;
	}

	public JcrValue validate() throws RepositoryException {
		if (fValue == null) {
			if (fType == PropertyType.DATE || fType == PropertyType.NAME || fType == PropertyType.PATH
					|| fType == PropertyType.URI || fType == PropertyType.REFERENCE
					|| fType == PropertyType.WEAKREFERENCE) {
				throw new ValueFormatException("Property value cannot be converted to a " + PropertyType.nameFromValue(fType) + ": " + fValue);
			}

			return this;
		}

		if (fType == PropertyType.BINARY) {
			if (fValue instanceof QName) {
				// valid
			} else if (!BinaryValueAdapter.canAdapt(fValue)) {
				throw new ValueFormatException("Property value cannot be converted to a " + PropertyType.nameFromValue(fType) + ": " + fValue);
			}

			return this;
		}

		if (fType == PropertyType.BOOLEAN || fType == PropertyType.DATE || fType == PropertyType.DECIMAL
				|| fType == PropertyType.DOUBLE || fType == PropertyType.LONG || fType == PropertyType.STRING
				|| fType == PropertyType.NAME || fType == PropertyType.PATH || fType == PropertyType.URI
				|| fType == PropertyType.REFERENCE || fType == PropertyType.WEAKREFERENCE) {
			try {
				adapt(getAdapterType());
			} catch (UnadaptableValueException ex) {
				throw (ValueFormatException) new ValueFormatException("Property value cannot be converted to a " + PropertyType.nameFromValue(fType) + ": " + fValue).initCause(ex);
			}

			return this;
		}

		throw new IllegalStateException("Invalid property type: " + fType);
	}

	public JcrValue with(Adaptable adaptable) {
		fAdaptable = adaptable;
		return this;
	}

	public JcrValue as(int type) throws ValueFormatException {
		if (fType == type) {
			return this;
		}

		try {
			return JcrValue.create(adapt(getAdapterType(type)), type).with(fAdaptable);
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class);
		}
	}

	public <AdapterType> AdapterType adapt(Class<AdapterType> adapterType) throws ValueFormatException {
		Object v;
		if (fValue instanceof QName) {
			QName qName = (QName) fValue;
			if (STRING_NS_URI.equals(qName.getNamespaceURI())) {
				v = qName.getLocalPart();
			} else {
				try {
					v = fAdaptable.adaptTo(WorkspaceQuery.class).files().getInputStream(qName.getLocalPart());
				} catch (IOException ex) {
					throw Cause.create(ex).wrap(ValueFormatException.class);
				}
			}
		} else {
			v = fValue;
		}

		String encoding = StandardCharsets.UTF_8.name();
		try {
			Node contentNode = getContentNode();
			if (contentNode != null) {
				encoding = Strings.defaultIfEmpty(contentNode.getProperty(JcrProperty.JCR_ENCODING_NAME).getString(), encoding);
			}
		} catch (PathNotFoundException ignore) {
			// ignore
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class);
		}

		if (adapterType.equals(Node.class)) {
			if (fValue instanceof Node) {
				return (AdapterType) fValue;
			}
			throw new ValueFormatException("Property value cannot be converted to a Node.");
		}

		return AdaptableList.<Object>newBuilder()
				.setValueAdapter(Binary.class, BinaryValueAdapter.class)
				.setEncoding(encoding)
				.add(v).build().adapt(0, adapterType).getValue();
	}

	private Class<?> getAdapterType() {
		return getAdapterType(fType);
	}

	private Class<?> getAdapterType(int type) {
		if (type == PropertyType.BINARY) {
			return Binary.class;
		}

		if (type == PropertyType.BOOLEAN) {
			return Boolean.class;
		}

		if (type == PropertyType.DATE) {
			return java.util.Calendar.class;
		}

		if (type == PropertyType.DECIMAL) {
			return BigDecimal.class;
		}

		if (type == PropertyType.DOUBLE) {
			return Double.class;
		}

		if (type == PropertyType.LONG) {
			return Long.class;
		}

		if (type == PropertyType.NAME) {
			return String.class;
		}

		if (type == PropertyType.PATH || type == PropertyType.WEAKREFERENCE) {
			return String.class;
		}

		if (type == PropertyType.REFERENCE) {
			return String.class;
		}

		if (type == PropertyType.STRING) {
			return String.class;
		}

		if (type == PropertyType.URI) {
			return URI.class;
		}

		throw new IllegalStateException("Invalid property type: " + type);
	}

	private Node getContentNode() throws RepositoryException {
		if (fAdaptable == null) {
			return null;
		}

		Node node = fAdaptable.adaptTo(Node.class);
		if (node == null) {
			return null;
		}

		if (JcrNode.JCR_CONTENT_NAME.equals(node.getName())) {
			return node;
		}

		Node contentNode = node.getNode(JcrNode.JCR_CONTENT_NAME);
		if (contentNode != null) {
			return node;
		}

		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		if (adapterType.equals(Path.class)) {
			if (fValue instanceof QName) {
				QName qName = (QName) fValue;
				if (BINARY_NS_URI.equals(qName.getNamespaceURI())) {
					return (AdapterType) fAdaptable.adaptTo(WorkspaceQuery.class).files().getPath(qName.getLocalPart());
				}
			}

			return null;
		}

		return fAdaptable.adaptTo(adapterType);
	}

}
