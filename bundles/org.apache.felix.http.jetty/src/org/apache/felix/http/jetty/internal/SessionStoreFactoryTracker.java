/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.felix.http.jetty.internal;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.eclipse.jetty.server.session.SessionCacheFactory;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

/**
 * MintJams addition (not part of upstream Apache Felix HTTP Jetty).
 *
 * <p>Tracks {@link SessionDataStoreFactory} and {@link SessionCacheFactory}
 * services so that {@link JettyService} can add them as beans of the Jetty
 * {@code Server} before the server is started. Jetty's {@code SessionHandler}
 * resolves both factories from the server beans in {@code doStart()}; a bean
 * added later is never seen, which is why the factories have to be known
 * before {@code server.start()}.
 *
 * <p>The highest ranking service of each kind is used. Whenever the chosen
 * service of either kind changes, the {@link Listener} is notified; the
 * listener decides whether Jetty has to be (re)started.
 */
public class SessionStoreFactoryTracker extends ServiceTracker<Object, Object>
{
    /** Receives a notification when the chosen factory of either kind changes. */
    public interface Listener
    {
        void sessionStoreFactoriesChanged();
    }

    private final Listener listener;

    /** Tracked services, ordered by service ranking (highest last). */
    private final Map<ServiceReference<Object>, Object> tracked = new TreeMap<>();

    private volatile SessionDataStoreFactory dataStoreFactory;
    private volatile SessionCacheFactory cacheFactory;

    public SessionStoreFactoryTracker(final BundleContext context, final Listener listener)
            throws InvalidSyntaxException
    {
        super(context, createFilter(context), null);
        this.listener = listener;
    }

    private static Filter createFilter(final BundleContext context) throws InvalidSyntaxException
    {
        return context.createFilter("(|(" + Constants.OBJECTCLASS + "=" + SessionDataStoreFactory.class.getName()
                + ")(" + Constants.OBJECTCLASS + "=" + SessionCacheFactory.class.getName() + "))");
    }

    /** The highest ranking {@link SessionDataStoreFactory} service, or {@code null}. */
    public SessionDataStoreFactory getSessionDataStoreFactory()
    {
        return this.dataStoreFactory;
    }

    /** The highest ranking {@link SessionCacheFactory} service, or {@code null}. */
    public SessionCacheFactory getSessionCacheFactory()
    {
        return this.cacheFactory;
    }

    @Override
    public Object addingService(final ServiceReference<Object> reference)
    {
        final Object service = super.addingService(reference);
        if (service == null)
        {
            return null;
        }
        if (!(service instanceof SessionDataStoreFactory) && !(service instanceof SessionCacheFactory))
        {
            super.removedService(reference, service);
            return null;
        }

        final boolean changed;
        synchronized (this.tracked)
        {
            this.tracked.put(reference, service);
            changed = recompute();
        }
        if (changed)
        {
            this.listener.sessionStoreFactoriesChanged();
        }
        return service;
    }

    @Override
    public void modifiedService(final ServiceReference<Object> reference, final Object service)
    {
        // The service ranking may have changed: the TreeMap order follows the
        // reference's current properties, so re-insert and recompute.
        final boolean changed;
        synchronized (this.tracked)
        {
            this.tracked.remove(reference);
            this.tracked.put(reference, service);
            changed = recompute();
        }
        if (changed)
        {
            this.listener.sessionStoreFactoriesChanged();
        }
    }

    @Override
    public void removedService(final ServiceReference<Object> reference, final Object service)
    {
        final boolean changed;
        synchronized (this.tracked)
        {
            this.tracked.remove(reference);
            changed = recompute();
        }
        super.removedService(reference, service);
        if (changed)
        {
            this.listener.sessionStoreFactoriesChanged();
        }
    }

    /** Must be called with {@link #tracked} held. Returns whether a chosen factory changed. */
    private boolean recompute()
    {
        SessionDataStoreFactory dataStore = null;
        SessionCacheFactory cache = null;
        // TreeMap iterates lowest ranking first; the last match of each kind wins.
        for (final Object service : this.tracked.values())
        {
            if (service instanceof SessionDataStoreFactory)
            {
                dataStore = (SessionDataStoreFactory) service;
            }
            if (service instanceof SessionCacheFactory)
            {
                cache = (SessionCacheFactory) service;
            }
        }
        final boolean changed = !Objects.equals(dataStore, this.dataStoreFactory)
                || !Objects.equals(cache, this.cacheFactory);
        this.dataStoreFactory = dataStore;
        this.cacheFactory = cache;
        return changed;
    }
}
