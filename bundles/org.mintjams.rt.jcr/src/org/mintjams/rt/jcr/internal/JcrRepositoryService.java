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
import java.util.Map;

import javax.jcr.Repository;

import org.mintjams.jcr.service.Bootstrap;
import org.mintjams.tools.io.Closer;
import org.mintjams.tools.osgi.BundleLocalization;
import org.mintjams.tools.osgi.Properties;
import org.mintjams.tools.osgi.Registration;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

@Component(name = JcrRepositoryService.COMPONENT_NAME, configurationPolicy = ConfigurationPolicy.OPTIONAL, enabled = true, immediate = true)
public class JcrRepositoryService {

	public static final String COMPONENT_NAME = "org.mintjams.rt.jcr.JcrRepositoryService";

	@Reference(cardinality = ReferenceCardinality.MANDATORY, policy = ReferencePolicy.STATIC)
	private Bootstrap fBootstrap;

	private BundleContext fBundleContext;
	private Properties fProperties;
	private final Closer fCloser = Closer.create();

	@Activate
	void activate(ComponentContext cc, BundleContext bc, Map<String, Object> config) {
		fBundleContext = bc;
		try {
			fProperties = Properties.create(config);
			open();
		} catch (Throwable ex) {
			Activator.getDefault().getLogger(getClass()).error("JCR repository service could not be started.", ex);
		}
	}

	@Deactivate
	void deactivate(ComponentContext cc, BundleContext bc) {
		try {
			close();
		} catch (Throwable ex) {
			Activator.getDefault().getLogger(getClass()).warn("An error occurred while stopping the JCR repository service.", ex);
		}
		fBundleContext = null;
	}

	private synchronized void open() {
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					((JcrBootstrap) fBootstrap).waitForReady();

					BundleLocalization localization = BundleLocalization.create(fBundleContext.getBundle());
					JcrRepositoryConfiguration configuration = JcrRepositoryConfiguration.create(fProperties).load();

					JcrRepository repository = fCloser.register(configuration.createRepository());
					Activator.getDefault().getBootstrap().setRepository(repository);
					repository.open();

					fCloser.add(Registration.newBuilder(Repository.class)
							.setService(repository)
							.setProperty(Constants.SERVICE_DESCRIPTION, localization.getString(Constants.SERVICE_DESCRIPTION))
							.setProperty(Constants.SERVICE_VENDOR, localization.getVendor())
							.setBundleContext(fBundleContext)
							.build());
				} catch (Throwable ex) {
					Activator.getDefault().getLogger(getClass()).error("JCR repository service could not be started.", ex);
				}
			}
		});
		t.setDaemon(true);
		t.start();
	}

	private synchronized void close() throws IOException {
		fCloser.close();
	}

}
