package webtop.numberplace;

import groovy.transform.CompileStatic;

/**
 * Number Place rules on the server: reading a puzzle and finding its single
 * solution. The counterpart of the app's core.ts (parseGrid, solve): the
 * two must agree on which puzzles are valid.
 *
 * A grid is 81 digits in row-major order, 0 for an empty cell. A puzzle is
 * valid when it has at least MIN_GIVENS givens, no two equal givens in a
 * row, a column or a box, and exactly one solution. The host's app makes
 * the puzzle; the server checks it here before the game starts, and keeps
 * the solution to check every number written.
 */
@CompileStatic
class NumberPlaceRules {

	static final int CELLS = 81;
	/** No puzzle with fewer givens has a single solution. */
	static final int MIN_GIVENS = 17;

	private static final int ALL = 511;
	private static final int[] ROW = new int[CELLS];
	private static final int[] COL = new int[CELLS];
	private static final int[] BOX = new int[CELLS];

	static {
		for (int i = 0; i < CELLS; i++) {
			int r = Math.floorDiv(i, 9);
			int c = i % 9;
			ROW[i] = r;
			COL[i] = c;
			BOX[i] = Math.floorDiv(r, 3) * 3 + Math.floorDiv(c, 3);
		}
	}

	/** Reads 81 digits; throws on anything else. */
	static int[] parse(String text) {
		String s = (text ?: '').trim();
		if (s.length() != CELLS) {
			throw new IllegalArgumentException('A puzzle is 81 digits.');
		}
		int[] cells = new int[CELLS];
		for (int i = 0; i < CELLS; i++) {
			char ch = s.charAt(i);
			if (ch < ('0' as char) || ch > ('9' as char)) {
				throw new IllegalArgumentException('A puzzle is 81 digits.');
			}
			cells[i] = ((int) ch) - ((int) ('0' as char));
		}
		return cells;
	}

	static String format(int[] cells) {
		StringBuilder buf = new StringBuilder(CELLS);
		for (int i = 0; i < CELLS; i++) {
			buf.append((char) (((int) ('0' as char)) + cells[i]));
		}
		return buf.toString();
	}

	/** How many cells are given. */
	static int givens(int[] cells) {
		int n = 0;
		for (int i = 0; i < CELLS; i++) {
			if (cells[i] != 0) {
				n++;
			}
		}
		return n;
	}

	/** The solution of a valid puzzle, as 81 digits; throws when the puzzle is not valid. */
	static String solution(String puzzle) {
		int[] cells = parse(puzzle);
		if (givens(cells) < MIN_GIVENS) {
			throw new IllegalArgumentException('The puzzle has too few numbers.');
		}
		Search search = new Search(cells, 2);
		if (!search.valid) {
			throw new IllegalArgumentException('The puzzle repeats a number in a row, a column or a box.');
		}
		search.run();
		if (search.count != 1) {
			throw new IllegalArgumentException('The puzzle must have exactly one solution.');
		}
		return format(search.solution);
	}

	/** Counts solutions up to a limit, filling the cell with the fewest choices first. */
	@CompileStatic
	private static class Search {
		final int[] grid;
		final int[] rows = new int[9];
		final int[] cols = new int[9];
		final int[] boxes = new int[9];
		final int[] empties;
		final int limit;
		boolean valid = true;
		int count = 0;
		int[] solution = null;

		Search(int[] cells, int limit) {
			this.grid = cells.clone();
			this.limit = limit;
			int n = 0;
			for (int i = 0; i < CELLS; i++) {
				if (grid[i] == 0) {
					n++;
				}
			}
			this.empties = new int[n];
			int k = 0;
			for (int i = 0; i < CELLS; i++) {
				int v = grid[i];
				if (v == 0) {
					empties[k++] = i;
					continue;
				}
				int bit = 1 << (v - 1);
				if (((rows[ROW[i]] | cols[COL[i]] | boxes[BOX[i]]) & bit) != 0) {
					valid = false;
				}
				rows[ROW[i]] |= bit;
				cols[COL[i]] |= bit;
				boxes[BOX[i]] |= bit;
			}
		}

		void run() {
			if (valid) {
				search(0);
			}
		}

		/** True once enough solutions were found. */
		private boolean search(int k) {
			if (k == empties.length) {
				count++;
				if (solution == null) {
					solution = grid.clone();
				}
				return count >= limit;
			}
			int best = k;
			int bestMask = 0;
			int bestCount = 10;
			for (int j = k; j < empties.length; j++) {
				int i = empties[j];
				int m = ALL & ~(rows[ROW[i]] | cols[COL[i]] | boxes[BOX[i]]);
				int n = Integer.bitCount(m);
				if (n < bestCount) {
					best = j;
					bestMask = m;
					bestCount = n;
					if (n <= 1) {
						break;
					}
				}
			}
			if (bestCount == 0) {
				return false;
			}
			int tmp = empties[k];
			empties[k] = empties[best];
			empties[best] = tmp;
			int i = empties[k];
			int r = ROW[i];
			int c = COL[i];
			int b = BOX[i];
			int m = bestMask;
			while (m != 0) {
				int bit = m & -m;
				m ^= bit;
				grid[i] = Integer.numberOfTrailingZeros(bit) + 1;
				rows[r] |= bit;
				cols[c] |= bit;
				boxes[b] |= bit;
				boolean stop = search(k + 1);
				rows[r] &= ~bit;
				cols[c] &= ~bit;
				boxes[b] &= ~bit;
				grid[i] = 0;
				if (stop) {
					return true;
				}
			}
			return false;
		}
	}

}
