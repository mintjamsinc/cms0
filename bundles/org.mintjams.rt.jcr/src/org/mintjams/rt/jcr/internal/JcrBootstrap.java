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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.Repository;

import org.mintjams.jcr.service.Bootstrap;
import org.mintjams.jcr.service.ServiceMonitor;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public class JcrBootstrap implements Bootstrap, Adaptable {

	private final String[] STANDARD_SERVICES = new String[] {
			"org.mintjams.rt.log",
			"org.mintjams.rt.log.stdout",
			"org.mintjams.rt.log.file"
	};

	private JcrRepository fRepository;
	private final Map<String, ServiceMonitor> fServiceMonitors = new HashMap<>();
	private final Object fLock = new Object();

	private final ServiceMonitor.Listener fServiceMonitorListener = new ServiceMonitor.Listener() {
		@Override
		public void onStatusChanged(ServiceMonitor monitor) {
			synchronized (fLock) {
				if (!isReady()) {
					return;
				}

				try {
					fLock.notifyAll();
				} catch (Throwable ignore) {}
			}
		}
	};

	private boolean isReady() {
		synchronized (fLock) {
			for (String name : STANDARD_SERVICES) {
				if (!fServiceMonitors.containsKey(name)) {
					return false;
				}
				if (!fServiceMonitors.get(name).getStatus().equals(ServiceMonitor.STATUS_RUNNING)) {
					return false;
				}
			}
		}
		return true;
	}

	public void waitForReady() {
		synchronized (fLock) {
			if (!isReady()) {
				try {
					fLock.wait(20000);
				} catch (Throwable ignore) {}
			}
		}

		if (!isReady()) {
			throw new IllegalStateException("Failed to launch.");
		}
	}

	@Override
	public Path getRepositoryPath() {
		if (fRepository == null) {
			return null;
		}
		return fRepository.getConfiguration().getRepositoryPath();
	}

	public void setRepository(JcrRepository repository) {
		fRepository = repository;
	}

	@Override
	public Repository getRepository() {
		return fRepository;
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fRepository, adapterType);
	}

	@Override
	public void setServiceMonitor(ServiceMonitor monitor) {
		fServiceMonitors.put(monitor.getName(), monitor);
		monitor.setListener(fServiceMonitorListener);
		fServiceMonitorListener.onStatusChanged(monitor);
	}

}
