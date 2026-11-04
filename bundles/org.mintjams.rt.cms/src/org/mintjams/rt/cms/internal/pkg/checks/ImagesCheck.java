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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mintjams.rt.cms.internal.pkg.Finding;
import org.mintjams.rt.cms.internal.pkg.InspectionContext;
import org.mintjams.rt.cms.internal.pkg.PackageCheck;
import org.mintjams.rt.cms.internal.pkg.PackageContents;
import org.mintjams.rt.cms.internal.pkg.PackageManifest;
import org.mintjams.rt.cms.internal.pkg.PackageSource;

/**
 * The images the manifest assigns to roles ({@code images}, and the
 * {@code images} of every locale block) are well-formed, are image entries
 * under {@code images/} of the package, are there, and are not too large.
 * An image in the package that no role refers to is reported as a warning:
 * it is carried for nothing.
 */
public final class ImagesCheck implements PackageCheck {

	/** More screenshots than this are refused. */
	public static final int SCREENSHOTS_MAX = 10;

	@Override
	public String getId() {
		return "images";
	}

	@Override
	public void check(InspectionContext context, List<Finding> findings) {
		PackageManifest manifest = context.getManifest();
		PackageContents contents = context.getContents();
		if (manifest == null || contents == null) {
			return;
		}
		Map<String, PackageContents.Entry> entries = contents.getImageEntries();
		Set<String> referenced = new HashSet<>();

		checkImages(manifest.getImagesValue(), "images", entries, referenced, findings);
		Object locales = manifest.getLocalesValue();
		if (locales instanceof Map) {
			for (Map.Entry<?, ?> e : ((Map<?, ?>) locales).entrySet()) {
				if (e.getValue() instanceof Map) {
					Object images = ((Map<?, ?>) e.getValue()).get("images");
					checkImages(images, "locales." + e.getKey() + ".images", entries, referenced, findings);
				}
			}
		}

		for (String name : entries.keySet()) {
			// A file that is not an image is the layout check's finding already.
			if (!referenced.contains(name) && PackageSource.isImageName(name)) {
				findings.add(Finding.warning("images.unused",
						"The image is not assigned to any role in the manifest: " + name, name));
			}
		}
	}

	private static void checkImages(Object value, String where, Map<String, PackageContents.Entry> entries,
			Set<String> referenced, List<Finding> findings) {
		if (value == null) {
			return;
		}
		if (!(value instanceof Map)) {
			findings.add(Finding.error("images.invalid",
					"The " + where + " must be a mapping of icon, thumbnail, main and screenshots to entries under images/."));
			return;
		}
		Map<?, ?> images = (Map<?, ?>) value;
		for (String role : PackageManifest.SINGLE_IMAGE_ROLES) {
			Object entry = images.get(role);
			if (entry == null) {
				continue;
			}
			if (!(entry instanceof String)) {
				findings.add(Finding.error("images.invalid",
						"The " + where + "." + role + " must be the name of an entry under images/."));
				continue;
			}
			checkEntry(((String) entry).trim(), where + "." + role, entries, referenced, findings);
		}
		Object screenshots = images.get(PackageManifest.IMAGE_SCREENSHOTS);
		if (screenshots == null) {
			return;
		}
		if (!(screenshots instanceof List)) {
			findings.add(Finding.error("images.invalid",
					"The " + where + ".screenshots must be a list of entries under images/, each a name or a mapping with path and caption."));
			return;
		}
		List<?> list = (List<?>) screenshots;
		if (list.size() > SCREENSHOTS_MAX) {
			findings.add(Finding.error("images.screenshots",
					"At most " + SCREENSHOTS_MAX + " screenshots may be declared in " + where + ": " + list.size()));
		}
		int i = 0;
		for (Object o : list) {
			String item = where + ".screenshots[" + i + "]";
			i++;
			Map<String, Object> shot = PackageManifest.screenshot(o);
			if (shot == null) {
				findings.add(Finding.error("images.invalid",
						"The " + item + " must be the name of an entry under images/ or a mapping with path and caption."));
				continue;
			}
			checkEntry((String) shot.get("path"), item, entries, referenced, findings);
		}
	}

	private static void checkEntry(String name, String where, Map<String, PackageContents.Entry> entries,
			Set<String> referenced, List<Finding> findings) {
		if (!name.startsWith(PackageSource.IMAGES_PREFIX) || !LayoutCheck.isWellFormed(name)
				|| !PackageSource.isImageName(name)) {
			findings.add(Finding.error("images.path",
					"The " + where + " must name an image (png, jpg, jpeg, gif, webp, svg) under images/: " + name, name));
			return;
		}
		referenced.add(name);
		PackageContents.Entry entry = entries.get(name);
		if (entry == null) {
			findings.add(Finding.error("images.missing",
					"The " + where + " names an image the package does not hold: " + name, name));
			return;
		}
		if (entry.getSize() > PackageSource.IMAGE_SIZE_LIMIT) {
			findings.add(Finding.error("images.size",
					"The image is larger than " + (PackageSource.IMAGE_SIZE_LIMIT / 1024 / 1024) + " MiB: " + name, name));
		}
	}

}
