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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;

import org.mintjams.jcr.security.EveryonePrincipal;
import org.mintjams.jcr.security.Group;
import org.mintjams.jcr.security.GroupPrincipal;
import org.mintjams.jcr.security.GuestPrincipal;
import org.mintjams.jcr.security.PrincipalNotFoundException;
import org.mintjams.jcr.security.Role;
import org.mintjams.jcr.security.User;
import org.mintjams.jcr.security.UserPrincipal;
import org.mintjams.jcr.service.Bootstrap;
import org.mintjams.jcr.spi.security.Authenticator;
import org.mintjams.jcr.spi.security.IdentityProvider;
import org.mintjams.jcr.spi.security.PrincipalProvider;
import org.mintjams.searchindex.SearchIndexFactory;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.osgi.Registration;
import org.mintjams.tools.osgi.Tracker;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Activator implements BundleActivator {

	private static Activator fActivator;
	private BundleContext fBundleContext;
	private final Closer fCloser = Closer.create();
	private Tracker<LoggerFactory> fLoggerFactoryTracker;
	private Tracker<EventAdmin> fEventAdminTracker;
	private Tracker<SearchIndexFactory> fSearchIndexFactoryTracker;
	private Tracker<Authenticator> fAuthenticatorTracker;
	private Tracker<PrincipalProvider> fPrincipalProviderTracker;
	private Tracker<IdentityProvider> fIdentityProviderTracker;
	private JcrBootstrap fBootstrap;
	private final ObjectMapper fObjectMapper = new ObjectMapper();

	private Tracker.Listener<Object> fTrackerListener = new Tracker.Listener<Object>() {
		@Override
		public void on(Tracker.Event<Object> event) {
			if (event instanceof Tracker.ServiceAddingEvent) {
				try {
					open();
				} catch (Throwable ignore) {}
				return;
			}

			if (event instanceof Tracker.ServiceRemovedEvent) {
				try {
					close();
				} catch (Throwable ignore) {}
				return;
			}
		}
	};

	@Override
	public void start(BundleContext bc) throws Exception {
		fBundleContext = bc;
		fActivator = this;

		fLoggerFactoryTracker = fCloser.register(Tracker.newBuilder(LoggerFactory.class)
				.setBundleContext(fBundleContext)
				.setListener(fTrackerListener)
				.build());
		fLoggerFactoryTracker.open();

		fEventAdminTracker = fCloser.register(Tracker.newBuilder(EventAdmin.class)
				.setBundleContext(fBundleContext)
				.setListener(fTrackerListener)
				.build());
		fEventAdminTracker.open();

		fSearchIndexFactoryTracker = fCloser.register(Tracker.newBuilder(SearchIndexFactory.class)
				.setBundleContext(fBundleContext)
				.setListener(fTrackerListener)
				.build());
		fSearchIndexFactoryTracker.open();

		fAuthenticatorTracker = fCloser.register(Tracker.newBuilder(Authenticator.class)
				.setBundleContext(fBundleContext)
				.build());
		fAuthenticatorTracker.open();

		fPrincipalProviderTracker = fCloser.register(Tracker.newBuilder(PrincipalProvider.class)
				.setBundleContext(fBundleContext)
				.build());
		fPrincipalProviderTracker.open();

		fIdentityProviderTracker = fCloser.register(Tracker.newBuilder(IdentityProvider.class)
				.setBundleContext(fBundleContext)
				.build());
		fIdentityProviderTracker.open();
	}

	@Override
	public void stop(BundleContext bc) throws Exception {
		close();
		fActivator = null;
		fBundleContext = null;
	}

	private synchronized void open() throws IOException {
		if (fLoggerFactoryTracker.getTrackingCount() == 0 ||
				fEventAdminTracker.getTrackingCount() == 0 ||
				fSearchIndexFactoryTracker.getTrackingCount() == 0) {
			return;
		}

		fBootstrap = new JcrBootstrap();
		fCloser.add(Registration.newBuilder(Bootstrap.class)
				.setService(fBootstrap)
				.setBundleContext(fBundleContext)
				.build());
	}

	private synchronized void close() throws IOException {
		fCloser.close();
	}

	public static Activator getDefault() {
		return fActivator;
	}

	public BundleContext getBundleContext() {
		return fBundleContext;
	}

	public Logger getLogger(Class<?> type) {
		return fLoggerFactoryTracker.getService().getLogger(type);
	}

	public void postEvent(String topic, Map<String, ?> properties) {
		fEventAdminTracker.getService().postEvent(new Event(topic, properties));
	}

	public SearchIndexFactory getSearchIndexFactory() {
		return fSearchIndexFactoryTracker.getService();
	}

	public JcrBootstrap getBootstrap() {
		return fBootstrap;
	}

	public JcrRepository getRepository() {
		Bootstrap bootstrap = getDefault().fBootstrap;
		if (bootstrap == null) {
			throw new IllegalStateException("The repository has not started.");
		}

		JcrRepository repository = (JcrRepository) getBootstrap().getRepository();
		if (repository == null) {
			throw new IllegalStateException("The repository has not started.");
		}

		return repository;
	}

	public Collection<Authenticator> getAuthenticators() {
		return fAuthenticatorTracker.getServices();
	}

	public List<PrincipalProvider> getPrincipalProviders() {
		List<PrincipalProvider> l = new ArrayList<>();
		try {
			List<String> classNames = getRepository().getConfiguration().getPrincipalProviderServices();
			for (PrincipalProvider provider : getDefault().fPrincipalProviderTracker.getServices()) {
				if (classNames.contains(provider.getClass().getName())) {
					l.add(provider);
				}
			}
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
		return l;
	}

	public List<IdentityProvider> getIdentityProviders() {
		List<IdentityProvider> l = new ArrayList<>();
		try {
			List<String> classNames = getRepository().getConfiguration().getIdentityProviderServices();
			for (IdentityProvider provider : getDefault().fIdentityProviderTracker.getServices()) {
				if (classNames.contains(provider.getClass().getName())) {
					l.add(provider);
				}
			}
		} catch (RepositoryException ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
		return l;
	}

	public Principal getPrincipal(String name) throws PrincipalNotFoundException {
		if (name.equals(GuestPrincipal.NAME)) {
			return new GuestPrincipal();
		}
		if (name.equals(EveryonePrincipal.NAME)) {
			return new EveryonePrincipal();
		}

		for (PrincipalProvider principalProvider : getPrincipalProviders()) {
			try {
				return principalProvider.getPrincipal(name);
			} catch (PrincipalNotFoundException | UnsupportedOperationException ignore) {
				// ignore
			} catch (Throwable ex) {
				getLogger(Activator.class).warn("An error occurred while retrieving the user principal: " + name, ex);
			}
		}

		throw new PrincipalNotFoundException(name);
	}

	public Collection<GroupPrincipal> getMemberOf(Principal principal) throws PrincipalNotFoundException {
		if (!(principal instanceof UserPrincipal)) {
			throw new IllegalArgumentException("Principal must be a user principal: " + principal.getName());
		}
		if (principal instanceof GuestPrincipal) {
			return List.of();
		}

		List<GroupPrincipal> memberOf = new ArrayList<>();
		for (PrincipalProvider principalManager : getDefault().fPrincipalProviderTracker.getServices()) {
			try {
				principalManager.getMemberOf(principal)
						.stream()
						.filter(p -> !memberOf.contains(p))
						.forEach(memberOf::add);
			} catch (PrincipalNotFoundException | UnsupportedOperationException ignore) {
				// ignore
			} catch (Throwable ex) {
				getLogger(Activator.class).warn("An error occurred while retrieving the memberOf: " + principal.getName(), ex);
			}
		}
		return memberOf;
	}

	public User getUser(String identifier) {
		for (IdentityProvider provider : getIdentityProviders()) {
			try {
				return provider.getUser(identifier);
			} catch (UnsupportedOperationException ignore) {
				// ignore
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
		return null;
	}

	public Group getGroup(String identifier) {
		for (IdentityProvider provider : getIdentityProviders()) {
			try {
				return provider.getGroup(identifier);
			} catch (UnsupportedOperationException ignore) {
				// ignore
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
		return null;
	}

	public Role getRole(String identifier) {
		for (IdentityProvider provider : getIdentityProviders()) {
			try {
				return provider.getRole(identifier);
			} catch (UnsupportedOperationException ignore) {
				// ignore
			} catch (Throwable ex) {
				throw Cause.create(ex).wrap(IllegalStateException.class);
			}
		}
		return null;
	}

	public Path getTemporaryDirectoryPath() {
		Path path = null;
		try {
			path = getRepository().getConfiguration().getTmpPath();
		} catch (Throwable ignore) {}
		if (path == null) {
			path = Paths.get(System.getProperty("java.io.tmpdir"));
		}
		return path;
	}

	public String toJSON(Object value) throws IOException {
		return fObjectMapper.writeValueAsString(value);
	}

	@SuppressWarnings("unchecked")
	public <T> T parseJSON(String value) throws IOException {
		if (value == null) {
			return null;
		}
		return (T) fObjectMapper.readValue(value, new TypeReference<>() {});
	}

}
