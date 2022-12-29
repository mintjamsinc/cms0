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
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.UUID;

import javax.jcr.AccessDeniedException;
import javax.jcr.Binary;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Item;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.ItemVisitor;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.PropertyDefinition;
import javax.jcr.security.Privilege;
import javax.jcr.version.VersionException;
import javax.xml.namespace.QName;

import org.mintjams.jcr.JcrName;
import org.mintjams.jcr.JcrPath;
import org.mintjams.jcr.NamespaceProvider;
import org.mintjams.jcr.UncheckedRepositoryException;
import org.mintjams.jcr.util.JCRs;
import org.mintjams.rt.jcr.internal.nodetype.JcrNodeTypeManager;
import org.mintjams.tools.adapter.Adaptable;
import org.mintjams.tools.adapter.Adaptables;
import org.mintjams.tools.adapter.UnadaptableValueException;
import org.mintjams.tools.collections.AdaptableMap;
import org.mintjams.tools.lang.Cause;

public class JcrProperty implements org.mintjams.jcr.Property, Adaptable {

	public static final String DEFAULT_MIMETYPE = "application/octet-stream";

	private final AdaptableMap<String, Object> fItemData;
	private final JcrNode fNode;

	private JcrProperty(AdaptableMap<String, Object> itemData, JcrNode node) {
		fItemData = itemData;
		fNode = node;
	}

	public static JcrProperty create(AdaptableMap<String, Object> itemData, JcrNode node) {
		return new JcrProperty(itemData, node);
	}

	@Override
	public void accept(ItemVisitor visitor) throws RepositoryException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Item getAncestor(int depth) throws ItemNotFoundException, AccessDeniedException, RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getDepth() throws RepositoryException {
		String path = getPath();
		if (path.equals("/")) {
			return 0;
		}

		return path.substring(1).split("/").length;
	}

	@Override
	public String getName() throws RepositoryException {
		return JcrName.valueOf(fItemData.getString("item_name")).with(adaptTo(NamespaceProvider.class)).toString();
	}

	@Override
	public Node getParent() throws ItemNotFoundException, AccessDeniedException, RepositoryException {
		return fNode;
	}

	@Override
	public String getPath() throws RepositoryException {
		return fNode.getPath() + "/" + getName();
	}

	@Override
	public Session getSession() throws RepositoryException {
		return fNode.getSession();
	}

	@Override
	public boolean isModified() {
		try {
			return getWorkspaceQuery().items().propertyIsModified(getParent().getIdentifier(), getName());
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public boolean isNew() {
		try {
			return getWorkspaceQuery().items().propertyIsNew(getParent().getIdentifier(), getName());
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public boolean isNode() {
		return false;
	}

	@Override
	public boolean isSame(Item otherItem) throws RepositoryException {
		return equals(otherItem);
	}

	@Override
	public void refresh(boolean keepChanges) throws InvalidItemStateException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public void remove() throws VersionException, LockException, ConstraintViolationException, AccessDeniedException,
			RepositoryException {
		fNode.checkWritable();
		adaptTo(JcrSession.class).checkPrivileges(fNode.getPath(), Privilege.JCR_MODIFY_PROPERTIES);
		if (adaptTo(JcrNodeTypeManager.class).isProtectedProperty(getName())) {
			throw new ConstraintViolationException("The property \"'" + getName() + "\" is protected.");
		}
		try {
			getWorkspaceQuery().items().removeProperty(fNode.getIdentifier(), getName());
		} catch (IOException | SQLException ex) {
			throw Cause.create(ex).wrap(RepositoryException.class);
		}
	}

	@Override
	public void save() throws AccessDeniedException, ItemExistsException, ConstraintViolationException,
			InvalidItemStateException, ReferentialIntegrityException, VersionException, LockException,
			NoSuchNodeTypeException, RepositoryException {
		throw new UnsupportedRepositoryOperationException();
	}

	@Override
	public Binary getBinary() throws ValueFormatException, RepositoryException {
		return getValue().getBinary();
	}

	@Override
	public boolean getBoolean() throws ValueFormatException, RepositoryException {
		return getValue().getBoolean();
	}

	@Override
	public Calendar getDate() throws ValueFormatException, RepositoryException {
		return getValue().getDate();
	}

	@Override
	public BigDecimal getDecimal() throws ValueFormatException, RepositoryException {
		return getValue().getDecimal();
	}

	@Override
	public PropertyDefinition getDefinition() throws RepositoryException {
		return JCRs.findPropertyDefinition(getParent(), getName());
	}

	@Override
	public double getDouble() throws ValueFormatException, RepositoryException {
		return getValue().getDouble();
	}

	@Override
	public long getLength() throws ValueFormatException, RepositoryException {
		return Adaptables.getAdapter(getValue(), JcrValue.class).getLength();
	}

	@Override
	public long[] getLengths() throws ValueFormatException, RepositoryException {
		Value[] values = getValues();
		long[] lengths = new long[values.length];
		for (int i = 0; i < values.length; i++) {
			lengths[i] = Adaptables.getAdapter(values[i], JcrValue.class).getLength();
		}
		return lengths;
	}

	@Override
	public long getLong() throws ValueFormatException, RepositoryException {
		return getValue().getLong();
	}

	@Override
	public Node getNode() throws ItemNotFoundException, ValueFormatException, RepositoryException {
		if (!isMultiple()) {
			if (getType() == PropertyType.STRING || getType() == PropertyType.REFERENCE
					|| getType() == PropertyType.PATH || getType() == PropertyType.WEAKREFERENCE) {
				String idOrPath = getString();
				try {
					return getNodeByIdentifier(UUID.fromString(idOrPath).toString());
				} catch (ItemNotFoundException ex) {
					throw ex;
				} catch (Throwable ignore) {}
				try {
					return getNodeByPath(idOrPath);
				} catch (Throwable ignore) {}
			}
		}
		throw new ValueFormatException("Property cannot be converted to referring type (REFERENCE, WEAKREFERENCE or PATH).");
	}

	private Node getNodeByIdentifier(String id) throws ItemNotFoundException, RepositoryException {
		return adaptTo(JcrSession.class).getNodeByIdentifier(id);
	}

	private Node getNodeByPath(String path) throws PathNotFoundException, RepositoryException {
		if (path.startsWith("/")) {
			return adaptTo(JcrSession.class).getNode(path);
		}
		return adaptTo(JcrSession.class).getNode(JcrPath.valueOf(fNode.getPath()).resolve(path).toString());
	}

	@Override
	public Property getProperty() throws ItemNotFoundException, ValueFormatException, RepositoryException {
		if (getType() == PropertyType.PATH) {
			JcrPath path = JcrPath.valueOf(getString());
			return getNodeByPath(path.getParent().toString()).getProperty(path.getName().toString());
		}
		if (!isMultiple()) {
			if (getType() == PropertyType.STRING) {
				try {
					JcrPath path = JcrPath.valueOf(getString());
					return getNodeByPath(path.getParent().toString()).getProperty(path.getName().toString());
				} catch (ItemNotFoundException ex) {
					throw ex;
				} catch (Throwable ignore) {}
			}
		}
		throw new ValueFormatException("Property cannot be converted to a PATH.");
	}

	@SuppressWarnings("deprecation")
	@Override
	public InputStream getStream() throws ValueFormatException, RepositoryException {
		return getValue().getStream();
	}

	@Override
	public String getString() throws ValueFormatException, RepositoryException {
		return getValue().getString();
	}

	@Override
	public int getType() throws RepositoryException {
		return fItemData.getInteger("property_type");
	}

	@Override
	public Value getValue() throws ValueFormatException, RepositoryException {
		if (isMultiple()) {
			throw new ValueFormatException("Property '" + getName() + "' is multi-valued.");
		}

		try {
			Object[] values = fItemData.adapt("property_value", Object[].class).getValue();
			if (values == null || values.length == 0) {
				return null;
			}

			return JcrValue.create(QName.valueOf(values[0].toString()), getType()).with(this);
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class, false);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(RepositoryException.class, false);
		}
	}

	@Override
	public Value[] getValues() throws ValueFormatException, RepositoryException {
		if (!isMultiple()) {
			throw new ValueFormatException("Property '" + getName() + "' is single-valued.");
		}

		try {
			Object[] values = fItemData.adapt("property_value", Object[].class).getValue();
			if (values == null || values.length == 0) {
				return new Value[0];
			}

			try {
				return Arrays.stream(values).map(e -> {
					try {
						return JcrValue.create(QName.valueOf(e.toString()), getType()).with(this);
					} catch (RepositoryException ex) {
						throw new UncheckedRepositoryException(ex);
					}
				}).toArray(JcrValue[]::new);
			} catch (UncheckedRepositoryException ex) {
				throw ex.getCause();
			}
		} catch (UnadaptableValueException ex) {
			throw Cause.create(ex).wrap(ValueFormatException.class, false);
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(RepositoryException.class, false);
		}
	}

	@Override
	public boolean isMultiple() throws RepositoryException {
		return fItemData.getBoolean("is_multiple");
	}

	@Override
	public void setValue(Value value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), value, getType());
	}

	@Override
	public void setValue(Value[] values) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), values, getType());
	}

	@Override
	public void setValue(String value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), value);
	}

	@Override
	public void setValue(String[] values) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), values);
	}

	@Override
	public void setValue(InputStream value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), value);
	}

	@Override
	public void setValue(Binary value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), value);
	}

	@Override
	public void setValue(long value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), value);
	}

	@Override
	public void setValue(double value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), value);
	}

	@Override
	public void setValue(BigDecimal value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), value);
	}

	@Override
	public void setValue(Calendar value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), value);
	}

	@Override
	public void setValue(boolean value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), value);
	}

	@Override
	public void setValue(Node value) throws ValueFormatException, VersionException, LockException,
			ConstraintViolationException, RepositoryException {
		fNode.setProperty(getName(), value);
	}

	public AdaptableMap<String, Object> getRawData() {
		return fItemData;
	}

	private WorkspaceQuery getWorkspaceQuery() {
		return adaptTo(WorkspaceQuery.class);
	}

	@Override
	public <AdapterType> AdapterType adaptTo(Class<AdapterType> adapterType) {
		return Adaptables.getAdapter(fNode, adapterType);
	}

	@Override
	public int hashCode() {
		try {
			return getPath().hashCode();
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof JcrProperty)) {
			return false;
		}
		return (hashCode() == obj.hashCode());
	}

	@Override
	public String toString() {
		try {
			return getPath();
		} catch (Throwable ex) {
			throw Cause.create(ex).wrap(IllegalStateException.class);
		}
	}

}
