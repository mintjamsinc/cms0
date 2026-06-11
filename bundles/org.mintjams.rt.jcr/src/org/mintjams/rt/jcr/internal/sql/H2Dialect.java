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

package org.mintjams.rt.jcr.internal.sql;

import java.sql.Connection;

/**
 * Dialect for H2, both embedded (the standalone default) and server mode.
 * H2 binds Java arrays and reads array columns natively, so no JDBC
 * adaptation is required.
 */
public class H2Dialect implements DatabaseDialect {

	public static final String NAME = "h2";

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String arrayContains(String arrayExpression, String valueExpression) {
		return "ARRAY_CONTAINS(" + arrayExpression + ", " + valueExpression + ")";
	}

	@Override
	public boolean isTransactionAbortedOnError() {
		return false;
	}

	@Override
	public Connection wrap(Connection connection) {
		return connection;
	}

}
