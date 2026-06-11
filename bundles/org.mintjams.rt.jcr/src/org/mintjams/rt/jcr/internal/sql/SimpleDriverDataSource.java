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

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * A {@link DataSource} that connects through an explicitly provided
 * {@link Driver} instance instead of going through
 * {@code java.sql.DriverManager}. {@code DriverManager} filters drivers by
 * the caller's class loader, which makes it unreliable inside an OSGi
 * framework where the JDBC driver lives in its own bundle; handing the
 * driver instance to the connection pool directly sidesteps that entirely.
 */
public class SimpleDriverDataSource implements DataSource {

	private final Driver fDriver;
	private final String fJdbcUrl;
	private final Properties fProperties;
	private PrintWriter fLogWriter;
	private int fLoginTimeout;

	private SimpleDriverDataSource(Driver driver, String jdbcUrl, String username, String password) {
		fDriver = driver;
		fJdbcUrl = jdbcUrl;
		fProperties = new Properties();
		if (username != null) {
			fProperties.setProperty("user", username);
		}
		if (password != null) {
			fProperties.setProperty("password", password);
		}
	}

	public static SimpleDriverDataSource create(String driverClassName, String jdbcUrl, String username,
			String password) throws SQLException {
		Driver driver;
		try {
			driver = (Driver) Class.forName(driverClassName, true, SimpleDriverDataSource.class.getClassLoader())
					.getDeclaredConstructor().newInstance();
		} catch (Throwable ex) {
			throw new SQLException("The JDBC driver class could not be loaded: " + driverClassName
					+ " (is the driver bundle installed?)", ex);
		}
		if (!driver.acceptsURL(jdbcUrl)) {
			throw new SQLException("The JDBC driver '" + driverClassName + "' does not accept the URL: " + jdbcUrl);
		}
		return new SimpleDriverDataSource(driver, jdbcUrl, username, password);
	}

	@Override
	public Connection getConnection() throws SQLException {
		Connection connection = fDriver.connect(fJdbcUrl, fProperties);
		if (connection == null) {
			throw new SQLException("The JDBC driver rejected the URL: " + fJdbcUrl);
		}
		return connection;
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		Properties properties = new Properties();
		properties.putAll(fProperties);
		if (username != null) {
			properties.setProperty("user", username);
		}
		if (password != null) {
			properties.setProperty("password", password);
		}
		Connection connection = fDriver.connect(fJdbcUrl, properties);
		if (connection == null) {
			throw new SQLException("The JDBC driver rejected the URL: " + fJdbcUrl);
		}
		return connection;
	}

	@Override
	public PrintWriter getLogWriter() {
		return fLogWriter;
	}

	@Override
	public void setLogWriter(PrintWriter out) {
		fLogWriter = out;
	}

	@Override
	public void setLoginTimeout(int seconds) {
		fLoginTimeout = seconds;
	}

	@Override
	public int getLoginTimeout() {
		return fLoginTimeout;
	}

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		if (iface.isInstance(this)) {
			return iface.cast(this);
		}
		throw new SQLException("Not a wrapper for: " + iface.getName());
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) {
		return iface.isInstance(this);
	}

}
