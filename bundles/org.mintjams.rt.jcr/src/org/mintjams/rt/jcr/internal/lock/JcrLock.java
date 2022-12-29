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

package org.mintjams.rt.jcr.internal.lock;

import java.io.IOException;
import java.sql.SQLException;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.lock.LockException;

import org.mintjams.jcr.security.Privilege;
import org.mintjams.rt.jcr.internal.SessionIdentifier;
import org.mintjams.rt.jcr.internal.WorkspaceQuery;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;
import org.mintjams.tools.lang.Strings;

public class JcrLock implements org.mintjams.jcr.lock.Lock, Adaptable {

	private final AdaptableMap<String, Object> fLockData;
	private final Session fSession;

	private JcrLock(AdaptableMap<String, Object> itemData, Session session) {
		fLockData = itemData;
		fSession = session;
	}

	public static JcrLock create(AdaptableMap<String, Object> itemData, Session session) {
		return new JcrLock(itemData, session);
	}

	@Override
	public String getLockOwner() {
		String info = fLockData.getString("owner_info");
		if (Strings.isNotEmpty(info)) {
			info = fLockData.getString("principal_name");
		}
		return info;
	}

	@Override
	public String getLockToken() {
		return fLockData.getString("lock_token");
	}

	@Override
	public Node getNode() {
		try {
			return fSession.getNodeByIdentifier(fLockData.getString("item_id"));
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public long getSecondsRemaining() throws RepositoryException {
		return Long.MAX_VALUE;
	}

	@Override
	public boolean isDeep() {
		return fLockData.getBoolean("is_deep");
	}

	@Override
	public boolean isLive() throws RepositoryException {
		return (getSecondsRemaining() > 0);
	}

	@Override
	public boolean isLockOwningSession() {
		return adaptTo(SessionIdentifier.class).toString().equals(fLockData.getString("session_id"));
	}

	@Override
	public boolean isSessionScoped() {
		return Strings.isNotEmpty(fLockData.getString("session_id"));
	}

	@Override
	public void refresh() throws LockException, RepositoryException {
		String absPath = fSession.getNodeByIdentifier(fLockData.getString("item_id")).getPath();
		adaptTo(org.mintjams.jcr.Session.class).checkPrivileges(absPath, Privilege.JCR_LOCK_MANAGEMENT);
		try {
			getWorkspaceQuery().items().refreshLock(absPath);
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public boolean isLockOwner() {
		String sessionID = fLockData.getString("session_id");
		if (Strings.isEmpty(sessionID)) {
			if (fLockData.getString("principal_name").equals(fSession.getUserID())) {
				return true;
			}
		} else {
			if (sessionID.equals(adaptTo(SessionIdentifier.class).toString())) {
				return true;
			}
		}
		return false;
	}

	private WorkspaceQuery getWorkspaceQuery() {
		return adaptTo(WorkspaceQuery.class);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fSession, adapterType);
	}

}
