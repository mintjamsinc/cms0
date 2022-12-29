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

package org.mintjams.rt.jcr.internal.observation;

import javax.jcr.RepositoryException;
import javax.jcr.observation.EventJournal;
import javax.jcr.observation.EventListener;
import javax.jcr.observation.EventListenerIterator;
import javax.jcr.observation.ObservationManager;

import org.mintjams.rt.jcr.internal.JcrWorkspace;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;

public class JcrObservationManager implements ObservationManager, Adaptable {

	private final JcrWorkspace fWorkspace;

	private JcrObservationManager(JcrWorkspace workspace) {
		fWorkspace = workspace;
	}

	public static JcrObservationManager create(JcrWorkspace workspace) {
		return new JcrObservationManager(workspace);
	}

	@Override
	public void addEventListener(EventListener listener, int eventTypes, String absPath, boolean isDeep, String[] uuid,
			String[] nodeTypeName, boolean noLocal) throws RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public EventJournal getEventJournal() throws RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public EventJournal getEventJournal(int eventTypes, String absPath, boolean isDeep, String[] uuid,
			String[] nodeTypeName) throws RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public EventListenerIterator getRegisteredEventListeners() throws RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void removeEventListener(EventListener listener) throws RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setUserData(String userData) throws RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fWorkspace, adapterType);
	}

}
