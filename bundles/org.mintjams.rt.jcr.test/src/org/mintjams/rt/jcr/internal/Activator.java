package org.mintjams.rt.jcr.internal;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Bundle activator for JCR test bundle.
 */
public class Activator implements BundleActivator {

	private static BundleContext context;

	public static BundleContext getContext() {
		return context;
	}

	@Override
	public void start(BundleContext bundleContext) throws Exception {
		Activator.context = bundleContext;
		System.out.println("JCR Test Bundle started");
	}

	@Override
	public void stop(BundleContext bundleContext) throws Exception {
		System.out.println("JCR Test Bundle stopped");
		Activator.context = null;
	}
}
