/*
 * Copyright (c) 2026 MintJams Inc.
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

package org.mintjams.rt.cms.internal.pkg.checks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.mintjams.rt.cms.internal.pkg.Finding;
import org.mintjams.rt.cms.internal.pkg.InspectionContext;
import org.mintjams.rt.cms.internal.pkg.PackageCheck;
import org.mintjams.rt.cms.internal.pkg.PackageContents;
import org.mintjams.rt.cms.internal.provisioning.Provisioner;

/**
 * The provisioning descriptors are mappings of the known sections, and each
 * entry carries what the provisioner will insist on, so the descriptors do
 * not fail halfway through an installation.
 */
public final class ProvisioningCheck implements PackageCheck {

	@Override
	public String getId() {
		return "provisioning";
	}

	@Override
	public void check(InspectionContext context, List<Finding> findings) {
		PackageContents contents = context.getContents();
		if (contents == null) {
			return;
		}
		for (Map.Entry<String, String> e : contents.getProvisioning().entrySet()) {
			String name = e.getKey();
			Map<String, Object> document;
			try {
				document = Provisioner.load(new ByteArrayInputStream(e.getValue().getBytes(StandardCharsets.UTF_8)), name);
			} catch (IOException | RuntimeException ex) {
				findings.add(Finding.error("provisioning.invalid",
						"The descriptor could not be read: " + name + " (" + ex.getMessage() + ")", name));
				continue;
			}
			if (document == null) {
				findings.add(Finding.warning("provisioning.empty", "The descriptor is empty: " + name, name));
				continue;
			}
			for (String key : document.keySet()) {
				if (!Provisioner.SECTIONS.contains(key)) {
					findings.add(Finding.warning("provisioning.unknownSection",
							"The descriptor has a section the provisioner does not know and ignores: " + key, name));
				}
			}
			for (String section : Provisioner.SECTIONS) {
				Object value = document.get(section);
				if (value == null) {
					continue;
				}
				if (!(value instanceof List)) {
					findings.add(Finding.error("provisioning.invalid",
							"The '" + section + "' section must be a list: " + name, name));
					continue;
				}
				int index = 0;
				for (Object item : (List<?>) value) {
					index++;
					if (!(item instanceof Map)) {
						findings.add(Finding.error("provisioning.invalid",
								"Entry " + index + " of '" + section + "' must be a mapping: " + name, name));
						continue;
					}
					checkEntry(section, (Map<?, ?>) item, index, name, findings);
				}
			}
		}
	}

	private static void checkEntry(String section, Map<?, ?> entry, int index, String name, List<Finding> findings) {
		String where = " (" + section + " entry " + index + " in " + name + ")";
		switch (section) {
		case "namespaces":
			if (isBlank(entry.get("prefix")) || isBlank(entry.get("uri"))) {
				findings.add(Finding.error("provisioning.invalid", "A namespace needs a prefix and a uri" + where, name));
			}
			break;
		case "roles":
		case "groups":
			if (isBlank(entry.get("id"))) {
				findings.add(Finding.error("provisioning.invalid", "An id is required" + where, name));
			}
			break;
		case "users":
			if (isBlank(entry.get("id"))) {
				findings.add(Finding.error("provisioning.invalid", "An id is required" + where, name));
			}
			if (!Boolean.TRUE.equals(entry.get("service")) && !"true".equalsIgnoreCase(String.valueOf(entry.get("service")))
					&& isBlank(entry.get("password"))) {
				findings.add(Finding.error("provisioning.userPassword",
						"A user that is not a service account needs a password" + where, name));
			}
			break;
		case "nodes":
			Object path = entry.get("path");
			if (isBlank(path) || !String.valueOf(path).startsWith("/")) {
				findings.add(Finding.error("provisioning.invalid", "A node needs an absolute path" + where, name));
				break;
			}
			String reserved = ReservedPathCheck.reservedRoot(String.valueOf(path).trim());
			if (reserved != null) {
				findings.add(Finding.error("provisioning.reservedPath",
						"A package may not provision nodes under " + reserved + ": " + path, name));
			}
			Object acl = entry.get("acl");
			if (acl != null && !(acl instanceof List)) {
				findings.add(Finding.error("provisioning.invalid", "The acl must be a list" + where, name));
			}
			break;
		default:
			break;
		}
	}

	private static boolean isBlank(Object value) {
		return value == null || value.toString().trim().isEmpty();
	}

}
