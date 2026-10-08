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

package org.mintjams.rt.cms.internal.pkg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A semantic version ({@code 1.2.3}, {@code 1.2.3-beta.1}) and the simple
 * constraints packages declare against one ({@code >=1.2}, {@code >=1.2 <2}).
 *
 * <p>Missing components read as zero, so {@code 1.2} equals {@code 1.2.0}; a
 * build suffix ({@code +sha}) is ignored; a leading {@code v} is accepted. A
 * pre-release sorts before the release it precedes, and pre-release
 * identifiers compare numerically when both are numbers and as text otherwise,
 * as the specification says.
 */
public final class Semver implements Comparable<Semver> {

	private final int[] fNumbers;
	private final String[] fPreRelease;
	private final String fText;

	private Semver(int[] numbers, String[] preRelease, String text) {
		fNumbers = numbers;
		fPreRelease = preRelease;
		fText = text;
	}

	/**
	 * Parses a version; throws {@link IllegalArgumentException} when the text is
	 * not one.
	 */
	public static Semver parse(String text) {
		if (text == null) {
			throw new IllegalArgumentException("A version is required.");
		}
		String s = text.trim();
		if (s.startsWith("v") || s.startsWith("V")) {
			s = s.substring(1);
		}
		int plus = s.indexOf('+');
		if (plus >= 0) {
			s = s.substring(0, plus);
		}
		String pre = null;
		int dash = s.indexOf('-');
		if (dash >= 0) {
			pre = s.substring(dash + 1);
			s = s.substring(0, dash);
		}
		if (s.isEmpty()) {
			throw new IllegalArgumentException("Invalid version: " + text);
		}
		String[] parts = s.split("\\.", -1);
		if (parts.length > 3) {
			throw new IllegalArgumentException("Invalid version: " + text);
		}
		int[] numbers = new int[3];
		for (int i = 0; i < parts.length; i++) {
			if (parts[i].isEmpty() || !parts[i].chars().allMatch(Character::isDigit) || parts[i].length() > 9) {
				throw new IllegalArgumentException("Invalid version: " + text);
			}
			numbers[i] = Integer.parseInt(parts[i]);
		}
		String[] preRelease = new String[0];
		if (pre != null) {
			if (pre.isEmpty()) {
				throw new IllegalArgumentException("Invalid version: " + text);
			}
			preRelease = pre.split("\\.", -1);
			for (String id : preRelease) {
				if (id.isEmpty() || !id.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-')) {
					throw new IllegalArgumentException("Invalid version: " + text);
				}
			}
		}
		return new Semver(numbers, preRelease, text.trim());
	}

	/** Returns the parsed version, or {@code null} when the text is not one. */
	public static Semver tryParse(String text) {
		try {
			return parse(text);
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	public boolean isPreRelease() {
		return fPreRelease.length > 0;
	}

	@Override
	public int compareTo(Semver other) {
		for (int i = 0; i < 3; i++) {
			int c = Integer.compare(fNumbers[i], other.fNumbers[i]);
			if (c != 0) {
				return c;
			}
		}
		if (fPreRelease.length == 0 || other.fPreRelease.length == 0) {
			// A release is newer than any of its pre-releases.
			return Integer.compare(other.fPreRelease.length, fPreRelease.length);
		}
		int n = Math.min(fPreRelease.length, other.fPreRelease.length);
		for (int i = 0; i < n; i++) {
			String a = fPreRelease[i];
			String b = other.fPreRelease[i];
			boolean na = a.chars().allMatch(Character::isDigit);
			boolean nb = b.chars().allMatch(Character::isDigit);
			int c;
			if (na && nb) {
				c = Long.compare(Long.parseLong(a), Long.parseLong(b));
			} else if (na) {
				c = -1;
			} else if (nb) {
				c = 1;
			} else {
				c = a.compareTo(b);
			}
			if (c != 0) {
				return c;
			}
		}
		return Integer.compare(fPreRelease.length, other.fPreRelease.length);
	}

	@Override
	public boolean equals(Object obj) {
		return (obj instanceof Semver) && compareTo((Semver) obj) == 0;
	}

	@Override
	public int hashCode() {
		return fNumbers[0] * 31 * 31 + fNumbers[1] * 31 + fNumbers[2];
	}

	@Override
	public String toString() {
		return fText;
	}

	// =========================================================================
	// Constraints
	// =========================================================================

	/**
	 * Tells whether a constraint text is well-formed: terms separated by spaces
	 * or commas, each an operator ({@code >=}, {@code >}, {@code <=}, {@code <},
	 * {@code =}, {@code ==}, {@code !=}) followed by a version, or a bare
	 * version meaning equality. An empty text or {@code *} matches everything.
	 */
	public static boolean isValidConstraint(String constraint) {
		try {
			parseConstraint(constraint);
			return true;
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}

	/** Tells whether the version meets every term of the constraint. */
	public static boolean satisfies(Semver version, String constraint) {
		for (Term term : parseConstraint(constraint)) {
			if (!term.test(version)) {
				return false;
			}
		}
		return true;
	}

	private static List<Term> parseConstraint(String constraint) {
		List<Term> terms = new ArrayList<>();
		if (constraint == null) {
			return terms;
		}
		String s = constraint.trim();
		if (s.isEmpty() || s.equals("*")) {
			return terms;
		}
		for (String token : s.split("[\\s,]+")) {
			if (token.isEmpty()) {
				continue;
			}
			String op = "=";
			String rest = token;
			for (String candidate : new String[] { ">=", "<=", "==", "!=", ">", "<", "=" }) {
				if (token.startsWith(candidate)) {
					op = candidate.equals("==") ? "=" : candidate;
					rest = token.substring(candidate.length());
					break;
				}
			}
			terms.add(new Term(op, parse(rest)));
		}
		return terms;
	}

	private static final class Term {
		private final String fOperator;
		private final Semver fVersion;

		Term(String operator, Semver version) {
			fOperator = operator.toLowerCase(Locale.ROOT);
			fVersion = version;
		}

		boolean test(Semver candidate) {
			int c = candidate.compareTo(fVersion);
			switch (fOperator) {
			case ">=":
				return c >= 0;
			case ">":
				return c > 0;
			case "<=":
				return c <= 0;
			case "<":
				return c < 0;
			case "!=":
				return c != 0;
			default:
				return c == 0;
			}
		}
	}

}
