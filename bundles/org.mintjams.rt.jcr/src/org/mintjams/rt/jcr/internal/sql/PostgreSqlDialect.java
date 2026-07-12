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
import java.sql.SQLException;

/**
 * Dialect for PostgreSQL, the recommended database for clustered
 * deployments. PostgreSQL's JDBC driver neither accepts plain Java arrays
 * for array columns nor returns them when reading, so connections are
 * wrapped with {@link JdbcArrayAdapter} to translate in both directions.
 */
public class PostgreSqlDialect implements DatabaseDialect {

	public static final String NAME = "postgresql";

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String arrayContains(String arrayExpression, String valueExpression) {
		return "(" + valueExpression + " = ANY(" + arrayExpression + "))";
	}

	@Override
	public boolean isTransactionAbortedOnError() {
		return true;
	}

	@Override
	public boolean isUniqueConstraintViolation(SQLException ex) {
		// PostgreSQL reports unique_violation as SQLSTATE 23505.
		return DatabaseDialect.hasSqlState(ex, "23505");
	}

	@Override
	public boolean isLockContention(SQLException ex) {
		// PostgreSQL reports lock_not_available as SQLSTATE 55P03 (lock_timeout
		// or NOWAIT/SKIP LOCKED), deadlock_detected as 40P01, and
		// serialization_failure as 40001.
		return DatabaseDialect.hasSqlState(ex, "55P03") || DatabaseDialect.hasSqlState(ex, "40P01")
				|| DatabaseDialect.hasSqlState(ex, "40001");
	}

	@Override
	public Connection wrap(Connection connection) throws SQLException {
		return JdbcArrayAdapter.wrap(connection);
	}

}
