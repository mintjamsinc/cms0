/*
 * Copyright (c) 2021 MintJams Inc.
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

package org.mintjams.rt.jcr.internal.adapter;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.jcr.Binary;

import org.mintjams.rt.jcr.internal.JcrBinary;
import org.mintjams.tools.adapter.AbstractValueAdapter;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.adapter.UnadaptableValueException;
import org.mintjams.tools.adapter.ValueAdapters;

public class BinaryValueAdapter extends AbstractValueAdapter<Binary> {

	public BinaryValueAdapter() {
		super();
	}

	public BinaryValueAdapter(Map<String, Object> env) {
		super(env);
	}

	@Override
	public Binary adapt(Object value) {
		if (value == null) {
			return null;
		}

		Binary binaryValue = Adaptables.getAdapter(value, Binary.class);
		if (binaryValue != null) {
			return binaryValue;
		}

		byte[] byteArrayValue = Adaptables.getAdapter(value, byte[].class);
		if (byteArrayValue != null) {
			try {
				return JcrBinary.create(byteArrayValue);
			} catch (IOException ignore) {}
		}

		InputStream inputStreamValue = Adaptables.getAdapter(value, InputStream.class);
		if (inputStreamValue != null) {
			try {
				return JcrBinary.create(inputStreamValue);
			} catch (IOException ignore) {}
		}

		File fileValue = Adaptables.getAdapter(value, File.class);
		if (fileValue != null) {
			try {
				return JcrBinary.create(new BufferedInputStream(new FileInputStream(fileValue)));
			} catch (IOException ignore) {}
		}

		Path pathValue = Adaptables.getAdapter(value, Path.class);
		if (pathValue != null) {
			try {
				return JcrBinary.create(Files.newInputStream(pathValue));
			} catch (IOException ignore) {}
		}

		String stringValue = ValueAdapters.createValueAdapter(fEnv, String.class).adapt(value);
		if (stringValue != null) {
			try {
				return JcrBinary.create(stringValue.getBytes(getEncoding()));
			} catch (IOException ignore) {}
		}

		throw new UnadaptableValueException("Value cannot adapt to type \""
				+ ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0].getTypeName()
				+ "\"");
	}

	public static boolean canAdapt(Object value) {
		for (Class<?> valueType : new Class<?>[] { byte[].class, InputStream.class, File.class, Path.class, String.class }) {
			if (Adaptables.getAdapter(value, valueType) != null) {
				return true;
			}
		}

		return false;
	}

}
