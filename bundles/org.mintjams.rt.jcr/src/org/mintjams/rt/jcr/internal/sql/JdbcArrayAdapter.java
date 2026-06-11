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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Wraps JDBC objects so that the rest of the repository can keep binding
 * plain Java arrays to array columns and reading them back as plain Java
 * arrays, regardless of how the underlying driver represents SQL arrays.
 *
 * <p>H2 accepts {@code setObject(i, String[])} and returns {@code Object[]}
 * from {@code getObject()}, which is what the data access layer expects.
 * Drivers such as PostgreSQL's require {@code java.sql.Array} on both sides
 * instead. This adapter performs that translation at the JDBC boundary so
 * that no SQL-issuing code has to know which driver is in use:
 *
 * <ul>
 * <li>{@code PreparedStatement.setObject} with a non-primitive Java array is
 * rewritten to {@code setArray} with a {@code varchar} SQL array.</li>
 * <li>{@code ResultSet.getObject} returning a {@code java.sql.Array} is
 * unwrapped to the plain Java array it represents.</li>
 * </ul>
 */
public final class JdbcArrayAdapter {

	private JdbcArrayAdapter() {}

	public static Connection wrap(Connection connection) {
		if (Proxy.isProxyClass(connection.getClass())
				&& Proxy.getInvocationHandler(connection) instanceof ConnectionHandler) {
			return connection;
		}
		return (Connection) Proxy.newProxyInstance(JdbcArrayAdapter.class.getClassLoader(),
				new Class<?>[] { Connection.class }, new ConnectionHandler(connection));
	}

	private static Object invokeTarget(Object target, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException ex) {
			throw ex.getCause();
		}
	}

	private static boolean isAdaptableArray(Object value) {
		return (value != null && value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive());
	}

	private static java.sql.Array toSqlArray(Connection connection, Object value) throws java.sql.SQLException {
		Object[] values = (Object[]) value;
		String[] elements = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			elements[i] = (values[i] == null) ? null : values[i].toString();
		}
		return connection.createArrayOf("varchar", elements);
	}

	private static Object toJavaArray(Object value) throws java.sql.SQLException {
		if (value instanceof java.sql.Array) {
			return ((java.sql.Array) value).getArray();
		}
		return value;
	}

	private static class ConnectionHandler implements InvocationHandler {
		private final Connection fConnection;

		private ConnectionHandler(Connection connection) {
			fConnection = connection;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			Object result = invokeTarget(fConnection, method, args);

			String name = method.getName();
			if (name.equals("prepareStatement")) {
				return Proxy.newProxyInstance(JdbcArrayAdapter.class.getClassLoader(),
						new Class<?>[] { PreparedStatement.class },
						new StatementHandler(fConnection, (Statement) result, (Connection) proxy));
			}
			if (name.equals("prepareCall")) {
				return Proxy.newProxyInstance(JdbcArrayAdapter.class.getClassLoader(),
						new Class<?>[] { CallableStatement.class },
						new StatementHandler(fConnection, (Statement) result, (Connection) proxy));
			}
			if (name.equals("createStatement")) {
				return Proxy.newProxyInstance(JdbcArrayAdapter.class.getClassLoader(),
						new Class<?>[] { Statement.class },
						new StatementHandler(fConnection, (Statement) result, (Connection) proxy));
			}

			return result;
		}
	}

	private static class StatementHandler implements InvocationHandler {
		private final Connection fConnection;
		private final Statement fStatement;
		private final Connection fConnectionProxy;

		private StatementHandler(Connection connection, Statement statement, Connection connectionProxy) {
			fConnection = connection;
			fStatement = statement;
			fConnectionProxy = connectionProxy;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			String name = method.getName();

			if (name.equals("setObject") && args != null && args.length >= 2
					&& args[0] instanceof Integer && isAdaptableArray(args[1])) {
				((PreparedStatement) fStatement).setArray((Integer) args[0], toSqlArray(fConnection, args[1]));
				return null;
			}

			if (name.equals("getConnection")) {
				return fConnectionProxy;
			}

			Object result = invokeTarget(fStatement, method, args);

			if (result instanceof ResultSet
					&& (name.equals("executeQuery") || name.equals("getResultSet") || name.equals("getGeneratedKeys"))) {
				return Proxy.newProxyInstance(JdbcArrayAdapter.class.getClassLoader(),
						new Class<?>[] { ResultSet.class },
						new ResultSetHandler((ResultSet) result, (Statement) proxy));
			}

			return result;
		}
	}

	private static class ResultSetHandler implements InvocationHandler {
		private final ResultSet fResultSet;
		private final Statement fStatementProxy;

		private ResultSetHandler(ResultSet resultSet, Statement statementProxy) {
			fResultSet = resultSet;
			fStatementProxy = statementProxy;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			String name = method.getName();

			if (name.equals("getStatement")) {
				return fStatementProxy;
			}

			Object result = invokeTarget(fResultSet, method, args);

			if (name.equals("getObject")) {
				// Callers that explicitly ask for java.sql.Array keep the driver object.
				if (args != null && args.length == 2 && args[1] instanceof Class
						&& java.sql.Array.class.isAssignableFrom((Class<?>) args[1])) {
					return result;
				}
				return toJavaArray(result);
			}

			return result;
		}
	}

}
