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

package org.mintjams.rt.jcr.internal.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.jcr.security.AccessControlPolicyIterator;

import org.mintjams.jcr.security.AccessControlPolicy;

public class JcrAccessControlPolicyIterator implements AccessControlPolicyIterator {

	private final List<AccessControlPolicy> fAccessControlPolicies;
	private long fPosition;

	private JcrAccessControlPolicyIterator(List<AccessControlPolicy> acps) {
		fAccessControlPolicies = acps;
	}

	public static JcrAccessControlPolicyIterator create() {
		return create(new AccessControlPolicy[0]);
	}

	public static JcrAccessControlPolicyIterator create(List<AccessControlPolicy> acps) {
		return new JcrAccessControlPolicyIterator(acps);
	}

	public static JcrAccessControlPolicyIterator create(AccessControlPolicy[] acps) {
		return create(Arrays.asList(acps));
	}

	public static JcrAccessControlPolicyIterator create(Collection<AccessControlPolicy> acps) {
		return create(acps.toArray(AccessControlPolicy[]::new));
	}

	@Override
	public long getPosition() {
		return fPosition;
	}

	@Override
	public long getSize() {
		return fAccessControlPolicies.size();
	}

	@Override
	public void skip(long skipNum) {
		fPosition += skipNum;
	}

	@Override
	public boolean hasNext() {
		return (fPosition < fAccessControlPolicies.size());
	}

	@Override
	public Object next() {
		return nextAccessControlPolicy();
	}

	@Override
	public AccessControlPolicy nextAccessControlPolicy() {
		AccessControlPolicy acp = fAccessControlPolicies.get(Math.toIntExact(fPosition));
		fPosition++;
		return acp;
	}

}
