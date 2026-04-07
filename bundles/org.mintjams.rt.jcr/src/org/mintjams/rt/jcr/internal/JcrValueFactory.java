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

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.ValueFormatException;

import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public class JcrValueFactory implements ValueFactory, Adaptable {

	private final JcrWorkspace fWorkspace;

	private JcrValueFactory(JcrWorkspace workspace) {
		fWorkspace = workspace;
	}

	public static JcrValueFactory create() {
		return new JcrValueFactory(null);
	}

	public static JcrValueFactory create(JcrWorkspace workspace) {
		return new JcrValueFactory(workspace);
	}

	@Override
	public Binary createBinary(InputStream value) throws RepositoryException {
		return JcrValue.create(value, PropertyType.BINARY).with(fWorkspace).validate().getBinary();
	}

	@Override
	public Value createValue(String value) {
		return JcrValue.create(value, PropertyType.STRING).with(fWorkspace);
	}

	@Override
	public Value createValue(long value) {
		return JcrValue.create(value, PropertyType.LONG).with(fWorkspace);
	}

	@Override
	public Value createValue(double value) {
		return JcrValue.create(value, PropertyType.DOUBLE).with(fWorkspace);
	}

	@Override
	public Value createValue(BigDecimal value) {
		return JcrValue.create(value, PropertyType.DECIMAL).with(fWorkspace);
	}

	@Override
	public Value createValue(boolean value) {
		return JcrValue.create(value, PropertyType.BOOLEAN).with(fWorkspace);
	}

	@Override
	public Value createValue(Calendar value) {
		return JcrValue.create(value, PropertyType.DATE).with(fWorkspace);
	}

	@Override
	public Value createValue(InputStream value) {
		return JcrValue.create(value, PropertyType.BINARY).with(fWorkspace);
	}

	@Override
	public Value createValue(Binary value) {
		return JcrValue.create(value, PropertyType.BINARY).with(fWorkspace);
	}

	@Override
	public Value createValue(Node value) throws RepositoryException {
		return JcrValue.create(value, PropertyType.REFERENCE).with(fWorkspace).validate();
	}

	@Override
	public Value createValue(String value, int type) throws ValueFormatException {
		try {
			return JcrValue.create(value, type).with(fWorkspace).validate();
		} catch (ValueFormatException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw (IllegalStateException) new IllegalStateException(ex.getMessage()).initCause(ex);
		}
	}

	@Override
	public Value createValue(Node value, boolean week) throws RepositoryException {
		return JcrValue.create(value, week ? PropertyType.WEAKREFERENCE : PropertyType.REFERENCE).with(fWorkspace).validate();
	}

	public Value[] createValue(String[] value) {
		List<Value> l = new ArrayList<>();
		for (String v : value) {
			l.add(JcrValue.create(v, PropertyType.STRING).with(fWorkspace));
		}
		return l.toArray(Value[]::new);
	}

	public Value[] createValue(String[] value, int type) throws RepositoryException {
		List<Value> l = new ArrayList<>();
		for (String v : value) {
			l.add(JcrValue.create(v, PropertyType.STRING).with(fWorkspace).validate());
		}
		return l.toArray(Value[]::new);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

}
