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
 * Absorbs the differences between the database products that can host a JCR
 * workspace. The workspace schema itself ({@code workspace-prepare.sql}) is
 * written in the portable subset of SQL that every supported product accepts;
 * everything that cannot be expressed portably goes through this interface.
 *
 * <p>A dialect is selected from the configured JDBC URL via
 * {@link Dialects#of(String)}. Adding support for another database means
 * adding an implementation of this interface and registering it in
 * {@link Dialects}.
 */
public interface DatabaseDialect {

	/**
	 * Returns the symbolic name of this dialect (e.g. {@code "h2"},
	 * {@code "postgresql"}).
	 */
	String getName();

	/**
	 * Returns an SQL fragment that evaluates to true when the array column
	 * {@code arrayExpression} contains the value produced by
	 * {@code valueExpression}. Both arguments are inserted verbatim, so the
	 * value expression is usually a bind-variable placeholder.
	 */
	String arrayContains(String arrayExpression, String valueExpression);

	/**
	 * Returns whether a statement failure aborts the surrounding transaction
	 * until it is rolled back (PostgreSQL semantics). Callers that want to
	 * recover from an expected statement failure inside a transaction must
	 * guard the statement with a savepoint when this returns {@code true}.
	 */
	boolean isTransactionAbortedOnError();

	/**
	 * Returns whether the given exception reports a unique-constraint (or
	 * unique-index) violation. Used to translate a losing concurrent insert of an
	 * already-taken node path into an {@code ItemExistsException} rather than a
	 * generic failure.
	 */
	boolean isUniqueConstraintViolation(SQLException ex);

	/**
	 * Returns whether the given exception reports that a lock could not be
	 * acquired because another transaction holds it — a lock (or lock-wait)
	 * timeout or a deadlock. Used by maintenance work that competes with user
	 * transactions (the blob cleaner) to skip the contended row and retry it
	 * on a later run instead of failing the whole pass.
	 */
	boolean isLockContention(SQLException ex);

	/**
	 * Returns whether {@code ex}, or any exception reachable from it through the
	 * {@link Throwable#getCause() cause} chain or the
	 * {@link SQLException#getNextException() SQL exception} chain, carries the
	 * given {@code SQLSTATE}. Helper for {@link #isUniqueConstraintViolation}
	 * and {@link #isLockContention}.
	 */
	static boolean hasSqlState(SQLException ex, String sqlState) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof SQLException) {
				for (SQLException e = (SQLException) t; e != null; e = e.getNextException()) {
					if (sqlState.equals(e.getSQLState())) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Wraps a physical connection with whatever JDBC-level adaptation this
	 * dialect needs (e.g. translating Java arrays to {@code java.sql.Array}
	 * values and back). Dialects that need no adaptation return the
	 * connection unchanged.
	 */
	Connection wrap(Connection connection) throws SQLException;

}
