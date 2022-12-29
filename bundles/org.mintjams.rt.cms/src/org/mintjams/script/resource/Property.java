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

package org.mintjams.script.resource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.Value;

import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.cms.internal.script.Mask;
import org.mintjams.rt.cms.internal.script.resource.JcrAction;
import org.mintjams.tools.io.IOs;

public class Property {

	private final javax.jcr.Property fJcrProperty;
	private final Resource fResource;

	protected Property(javax.jcr.Property jcrProperty, Resource resource) {
		fJcrProperty = jcrProperty;
		fResource = resource;
	}

	private javax.jcr.Property getProperty() {
		return fJcrProperty;
	}

	public String getName() throws ResourceException {
		try {
			return fJcrProperty.getName();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public Object getValue() throws ResourceException {
		if (getType() == javax.jcr.PropertyType.STRING) {
			return getValue(String.class);
		}
		if (getType() == javax.jcr.PropertyType.BINARY) {
			return getValue(InputStream.class);
		}
		if (getType() == javax.jcr.PropertyType.LONG) {
			return getValue(Long.class);
		}
		if (getType() == javax.jcr.PropertyType.DOUBLE) {
			return getValue(Double.class);
		}
		if (getType() == javax.jcr.PropertyType.DECIMAL) {
			return getValue(BigDecimal.class);
		}
		if (getType() == javax.jcr.PropertyType.DATE) {
			Calendar v = getValue(Calendar.class);
			return (v != null) ? v.getTime() : null;
		}
		if (getType() == javax.jcr.PropertyType.BOOLEAN) {
			return getValue(Boolean.class);
		}
		if (getType() == javax.jcr.PropertyType.NAME) {
			return getValue(String.class);
		}
		if (getType() == javax.jcr.PropertyType.PATH) {
			return getValue(String.class);
		}
		if (getType() == javax.jcr.PropertyType.REFERENCE) {
			return getValue(Resource.class);
		}
		if (getType() == javax.jcr.PropertyType.WEAKREFERENCE) {
			return getValue(Resource.class);
		}
		if (getType() == javax.jcr.PropertyType.URI) {
			return getValue(String.class);
		}
		return null;
	}

	public Object[] getValues() throws ResourceException {
		if (getType() == javax.jcr.PropertyType.STRING) {
			return getValue(String[].class);
		}
		if (getType() == javax.jcr.PropertyType.BINARY) {
			return getValue(InputStream[].class);
		}
		if (getType() == javax.jcr.PropertyType.LONG) {
			return getValue(Long[].class);
		}
		if (getType() == javax.jcr.PropertyType.DOUBLE) {
			return getValue(Double[].class);
		}
		if (getType() == javax.jcr.PropertyType.DECIMAL) {
			return getValue(BigDecimal[].class);
		}
		if (getType() == javax.jcr.PropertyType.DATE) {
			return getValue(java.util.Date[].class);
		}
		if (getType() == javax.jcr.PropertyType.BOOLEAN) {
			return getValue(Boolean[].class);
		}
		if (getType() == javax.jcr.PropertyType.NAME) {
			return getValue(String[].class);
		}
		if (getType() == javax.jcr.PropertyType.PATH) {
			return getValue(String[].class);
		}
		if (getType() == javax.jcr.PropertyType.REFERENCE) {
			return getValue(Resource[].class);
		}
		if (getType() == javax.jcr.PropertyType.WEAKREFERENCE) {
			return getValue(Resource[].class);
		}
		if (getType() == javax.jcr.PropertyType.URI) {
			return getValue(String[].class);
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public <ValueType> ValueType getValue(Class<ValueType> type) throws ResourceException {
		try {
			if (type == String.class) {
				return (ValueType) Mask.unmask(getProperty().getString(), getName());
			}

			if (type == BigDecimal.class) {
				return (ValueType) getProperty().getDecimal();
			}

			if (type == Double.class) {
				return (ValueType) Double.valueOf(getProperty().getDouble());
			}

			if (type == Long.class) {
				return (ValueType) Long.valueOf(getProperty().getLong());
			}

			if (type == Integer.class) {
				return (ValueType) Integer.valueOf(getProperty().getDecimal().intValue());
			}

			if (type == Calendar.class) {
				return (ValueType) getProperty().getDate();
			}

			if (type == java.util.Date.class) {
				return (ValueType) getProperty().getDate().getTime();
			}

			if (type == Boolean.class) {
				return (ValueType) Boolean.valueOf(getProperty().getBoolean());
			}

			if (type == Value.class) {
				return (ValueType) getProperty().getValue();
			}

			if (type == Value[].class) {
				if (getProperty().isMultiple()) {
					return (ValueType) getProperty().getValues();
				}
				return (ValueType) new Value[] { getProperty().getValue() };
			}

			if (type == Binary.class) {
				return (ValueType) getProperty().getBinary();
			}

			if (type == Node.class) {
				return (ValueType) getProperty().getNode();
			}

			if (type == BigDecimal[].class) {
				if (getProperty().isMultiple()) {
					final Value[] values = getProperty().getValues();
					final BigDecimal[] result = new BigDecimal[values.length];
					for (int i = 0; i < values.length; i++) {
						result[i] = values[i].getDecimal();
					}
					return (ValueType) result;
				}
				return (ValueType) new BigDecimal[] { getProperty().getDecimal() };
			}

			if (type == Double[].class) {
				if (getProperty().isMultiple()) {
					final Value[] values = getProperty().getValues();
					final Double[] result = new Double[values.length];
					for (int i = 0; i < values.length; i++) {
						result[i] = values[i].getDouble();
					}
					return (ValueType) result;
				}
				return (ValueType) new Double[] { getProperty().getDouble() };
			}

			if (type == String[].class) {
				if (getProperty().isMultiple()) {
					final Value[] values = getProperty().getValues();
					final String[] result = new String[values.length];
					for (int i = 0; i < values.length; i++) {
						result[i] = Mask.unmask(values[i].getString(), getName());
					}
					return (ValueType) result;
				}
				return (ValueType) new String[] { Mask.unmask(getProperty().getString(), getName()) };
			}

			if (type == Long[].class) {
				if (getProperty().isMultiple()) {
					final Value[] values = getProperty().getValues();
					final Long[] result = new Long[values.length];
					for (int i = 0; i < values.length; i++) {
						result[i] = values[i].getLong();
					}
					return (ValueType) result;
				}
				return (ValueType) new Long[] { getProperty().getLong() };
			}

			if (type == Integer[].class) {
				if (getProperty().isMultiple()) {
					final Value[] values = getProperty().getValues();
					final Integer[] result = new Integer[values.length];
					for (int i = 0; i < values.length; i++) {
						result[i] = values[i].getDecimal().intValue();
					}
					return (ValueType) result;
				}
				return (ValueType) new Integer[] { getProperty().getDecimal().intValue() };
			}

			if (type == Calendar[].class) {
				if (getProperty().isMultiple()) {
					final Value[] values = getProperty().getValues();
					final Calendar[] result = new Calendar[values.length];
					for (int i = 0; i < values.length; i++) {
						result[i] = values[i].getDate();
					}
					return (ValueType) result;
				}
				return (ValueType) new Calendar[] { getProperty().getDate() };
			}

			if (type == java.util.Date[].class) {
				if (getProperty().isMultiple()) {
					final Value[] values = getProperty().getValues();
					final java.util.Date[] result = new java.util.Date[values.length];
					for (int i = 0; i < values.length; i++) {
						result[i] = values[i].getDate().getTime();
					}
					return (ValueType) result;
				}
				return (ValueType) new java.util.Date[] { getProperty().getDate().getTime() };
			}

			if (type == Boolean[].class) {
				if (getProperty().isMultiple()) {
					final Value[] values = getProperty().getValues();
					final Boolean[] result = new Boolean[values.length];
					for (int i = 0; i < values.length; i++) {
						result[i] = values[i].getBoolean();
					}
					return (ValueType) result;
				}
				return (ValueType) new Boolean[] { getProperty().getBoolean() };
			}

			if (type == byte[].class) {
				try (InputStream in = getProperty().getBinary().getStream()) {
					return (ValueType) IOs.toByteArray(in);
				}
			}

			if (type == InputStream.class) {
				return (ValueType) getProperty().getBinary().getStream();
			}

			if (type == Resource.class) {
				return (ValueType) new ResourceImpl(getProperty().getNode(), fResource.getSession());
			}
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}

		return null;
	}

	@Override
	public String toString() {
		try {
			return getValue(String.class);
		} catch (Throwable ex) {
			throw (IllegalStateException) new IllegalStateException(ex.getMessage()).initCause(ex);
		}
	}

	public boolean getBoolean() throws ResourceException {
		return getValue(Boolean.class);
	}

	public boolean[] getBooleanArray() throws ResourceException {
		Boolean[] values = getValue(Boolean[].class);
		boolean[] result = new boolean[values.length];
		for (int i = 0; i < values.length; i++) {
			result[i] = values[i];
		}
		return result;
	}

	public Calendar getDate() throws ResourceException {
		return getValue(Calendar.class);
	}

	public Calendar[] getDateArray() throws ResourceException {
		return getValue(Calendar[].class);
	}

	public BigDecimal getDecimal() throws ResourceException {
		return getValue(BigDecimal.class);
	}

	public BigDecimal[] getDecimalArray() throws ResourceException {
		return getValue(BigDecimal[].class);
	}

	public double getDouble() throws ResourceException {
		return getValue(Double.class);
	}

	public double[] getDoubleArray() throws ResourceException {
		return Arrays.stream(getValue(Double[].class)).mapToDouble(e -> e).toArray();
	}

	public long getLong() throws ResourceException {
		return getValue(Long.class);
	}

	public long[] getLongArray() throws ResourceException {
		return Arrays.stream(getValue(Long[].class)).mapToLong(e -> e).toArray();
	}

	public int getInt() throws ResourceException {
		return getValue(Integer.class);
	}

	public int[] getIntArray() throws ResourceException {
		return Arrays.stream(getValue(Integer[].class)).mapToInt(e -> e).toArray();
	}

	public String getString() throws ResourceException {
		return getValue(String.class);
	}

	public String[] getStringArray() throws ResourceException {
		return getValue(String[].class);
	}

	public byte[] getByteArray() throws ResourceException {
		return getValue(byte[].class);
	}

	public InputStream getStream() throws ResourceException {
		return getValue(InputStream.class);
	}

	public Resource getResource() throws ResourceException {
		return getValue(Resource.class);
	}

	public long getLength() throws ResourceException {
		try {
			return getProperty().getLength();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public boolean isMultiple() throws ResourceException {
		try {
			return getProperty().isMultiple();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public boolean isMasked() throws ResourceException {
		try {
			if (getProperty().getType() == PropertyType.STRING) {
				if (getProperty().isMultiple()) {
					return Mask.isMasked(getProperty().getValues()[0].getString());
				}
				return Mask.isMasked(getProperty().getString());
			}
			return false;
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public int getType() throws ResourceException {
		try {
			return getProperty().getType();
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
	}

	public String getTypeName() throws ResourceException {
		return PropertyType.nameFromValue(getType());
	}

	public Property setValue(String value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(String[] value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(String value, boolean mask) throws ResourceException {
		fResource.setProperty(getName(), value, mask);
		return this;
	}

	public Property setValue(String[] value, boolean mask) throws ResourceException {
		fResource.setProperty(getName(), value, mask);
		return this;
	}

	public Property setValue(BigDecimal value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(BigDecimal[] value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(double value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(double[] value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(long value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(long[] value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(int value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(int[] value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(Calendar value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(Calendar[] value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(java.util.Date value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(java.util.Date[] value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(boolean value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(boolean[] value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(byte[] value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(InputStream value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property setValue(Resource value) throws ResourceException {
		fResource.setProperty(getName(), value);
		return this;
	}

	public Property remove() throws ResourceException {
		try {
			Node fileNode = JCRs.getFileNode(getProperty());
			JcrAction.create(fileNode).addLockToken().checkLock();
			JCRs.removePropertyIfExists(fileNode, getName());
		} catch (Throwable ex) {
			throw ResourceException.wrap(ex);
		}
		return this;
	}

}
