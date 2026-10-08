package webtop.minesweeper;

import groovy.transform.CompileStatic;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minesweeper rules on the server: the sizes of the boards, the counts
 * around each cell, opening an area, and making a board that can be cleared
 * without guessing. The counterpart of the app's core.ts (SIZES, flood,
 * deduce, generate): with another user the server makes the board, so that
 * neither player's app knows where the mines are, and the two must agree
 * on the sizes.
 *
 * A board is width by height cells in row-major order. Its layout is one
 * character per cell, '1' for a mine and '0' for none; its start is a cell
 * with no mine around it, opened when the game begins. What a player sees
 * is written one character per cell too: '-' covered, '0' to '8' open, '*'
 * a mine opened and, once the game is over, 'm' every other mine.
 */
@CompileStatic
class MinesweeperRules {

	static final int COVERED = -1;
	static final int EXPLODED = 9;
	static final int MINE = 10;

	/** Width, height and mines of each difficulty, 1 to 4 (core.ts SIZES). */
	private static final int[][] SIZES = [[9, 9, 10], [12, 12, 22], [16, 16, 40], [24, 16, 72]] as int[][];

	/** How many layouts are tried before giving up. */
	private static final int MAX_TRIES = 20000;

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Map<String, int[][]> NEIGHBORS = new ConcurrentHashMap<String, int[][]>();

	static int width(int level) {
		return SIZES[checkLevel(level) - 1][0];
	}

	static int height(int level) {
		return SIZES[checkLevel(level) - 1][1];
	}

	static int mines(int level) {
		return SIZES[checkLevel(level) - 1][2];
	}

	static int cells(int level) {
		return width(level) * height(level);
	}

	private static int checkLevel(int level) {
		if (level < 1 || level > SIZES.length) {
			throw new IllegalArgumentException("Unknown difficulty: ${level}".toString());
		}
		return level;
	}

	/** The cells around each cell (up to eight), for a board of that size. */
	static int[][] neighbors(int width, int height) {
		String key = "${width}x${height}".toString();
		int[][] table = NEIGHBORS.get(key);
		if (table != null) {
			return table;
		}
		table = new int[width * height][];
		for (int i = 0; i < width * height; i++) {
			int r = Math.floorDiv(i, width);
			int c = i % width;
			List<Integer> list = new ArrayList<Integer>();
			for (int dr = -1; dr <= 1; dr++) {
				for (int dc = -1; dc <= 1; dc++) {
					if (dr == 0 && dc == 0) {
						continue;
					}
					int rr = r + dr;
					int cc = c + dc;
					if (rr >= 0 && rr < height && cc >= 0 && cc < width) {
						list.add(rr * width + cc);
					}
				}
			}
			table[i] = toArray(list);
		}
		NEIGHBORS.put(key, table);
		return table;
	}

	/** Reads a layout of that many cells into 1 (mine) and 0; throws on anything else. */
	static int[] parseLayout(String text, int cells) {
		String s = (text ?: '').trim();
		if (s.length() != cells) {
			throw new IllegalArgumentException('The board does not fit its size.');
		}
		int[] mines = new int[cells];
		for (int i = 0; i < cells; i++) {
			char ch = s.charAt(i);
			if (ch == ('1' as char)) {
				mines[i] = 1;
			} else if (ch != ('0' as char)) {
				throw new IllegalArgumentException('The board does not fit its size.');
			}
		}
		return mines;
	}

	static String formatLayout(int[] mines) {
		StringBuilder buf = new StringBuilder(mines.length);
		for (int m : mines) {
			buf.append(m != 0 ? '1' : '0');
		}
		return buf.toString();
	}

	/** Writes what a player sees; covered mines show as 'm' when `showMines`. */
	static String formatView(int[] view, int[] mines, boolean showMines) {
		StringBuilder buf = new StringBuilder(view.length);
		for (int i = 0; i < view.length; i++) {
			int v = view[i];
			if (v == COVERED) {
				buf.append((showMines && mines[i] != 0) ? 'm' : '-');
			} else if (v == EXPLODED) {
				buf.append('*');
			} else {
				buf.append((char) (((int) ('0' as char)) + v));
			}
		}
		return buf.toString();
	}

	/** How many mines touch each cell. */
	static int[] counts(int width, int height, int[] mines) {
		int[][] nb = neighbors(width, height);
		int[] counts = new int[mines.length];
		for (int i = 0; i < mines.length; i++) {
			int n = 0;
			for (int j : nb[i]) {
				n += mines[j];
			}
			counts[i] = n;
		}
		return counts;
	}

	/** A board with every cell covered. */
	static int[] coveredView(int cells) {
		int[] view = new int[cells];
		Arrays.fill(view, COVERED);
		return view;
	}

	/**
	 * Opens a safe cell on `view` and, when no mine touches it, the cells
	 * around it, spreading through every such cell. Returns the cells it
	 * opened (none when the cell was open already).
	 */
	static List<Integer> flood(int width, int height, int[] counts, int[] view, int start) {
		List<Integer> opened = new ArrayList<Integer>();
		if (view[start] != COVERED) {
			return opened;
		}
		int[][] nb = neighbors(width, height);
		Deque<Integer> stack = new ArrayDeque<Integer>();
		view[start] = counts[start];
		opened.add(start);
		stack.push(start);
		while (!stack.isEmpty()) {
			int i = stack.pop();
			if (counts[i] != 0) {
				continue;
			}
			for (int j : nb[i]) {
				if (view[j] != COVERED) {
					continue;
				}
				view[j] = counts[j];
				opened.add(j);
				stack.push(j);
			}
		}
		return opened;
	}

	/** How many cells of a view are open (a count, not a mine). */
	static int openCount(int[] view) {
		int n = 0;
		for (int v : view) {
			if (v >= 0 && v <= 8) {
				n++;
			}
		}
		return n;
	}

	/**
	 * What can be told for sure from what a player sees (core.ts deduce):
	 * adds to `safe` the covered cells that surely hold no mine and to
	 * `sure` those that surely hold one. `known` marks the cells known to be
	 * mines besides the exploded ones. Returns whether anything was found.
	 */
	static boolean deduce(int width, int height, int[] view, boolean[] known, int totalMines, Set<Integer> safe, Set<Integer> sure) {
		int[][] nb = neighbors(width, height);
		int cells = width * height;
		List<int[]> groups = new ArrayList<int[]>();
		List<Integer> needs = new ArrayList<Integer>();
		for (int i = 0; i < cells; i++) {
			int v = view[i];
			if (v < 0 || v > 8) {
				continue;
			}
			int need = v;
			List<Integer> around = new ArrayList<Integer>();
			for (int j : nb[i]) {
				if (isMine(view, known, j)) {
					need--;
				} else if (view[j] == COVERED) {
					around.add(j);
				}
			}
			if (around.isEmpty()) {
				continue;
			}
			if (need <= 0) {
				safe.addAll(around);
			} else if (need >= around.size()) {
				sure.addAll(around);
			} else {
				groups.add(toArray(around));
				needs.add(need);
			}
		}
		if (!safe.isEmpty() || !sure.isEmpty()) {
			return true;
		}

		// Two numbers at a time, among those that share a cell.
		Map<Integer, List<Integer>> byCell = new HashMap<Integer, List<Integer>>();
		for (int k = 0; k < groups.size(); k++) {
			for (int c : groups.get(k)) {
				List<Integer> list = byCell.get(c);
				if (list == null) {
					list = new ArrayList<Integer>();
					byCell.put(c, list);
				}
				list.add(k);
			}
		}
		for (int ka = 0; ka < groups.size(); ka++) {
			int[] a = groups.get(ka);
			Set<Integer> inA = new HashSet<Integer>();
			Set<Integer> others = new LinkedHashSet<Integer>();
			for (int c : a) {
				inA.add(c);
				for (int kb : byCell.get(c)) {
					if (kb != ka) {
						others.add(kb);
					}
				}
			}
			for (int kb : others) {
				int[] b = groups.get(kb);
				List<Integer> onlyB = new ArrayList<Integer>();
				Set<Integer> inB = new HashSet<Integer>();
				for (int c : b) {
					inB.add(c);
					if (!inA.contains(c)) {
						onlyB.add(c);
					}
				}
				if (needs.get(kb) - needs.get(ka) != onlyB.size()) {
					continue;
				}
				sure.addAll(onlyB);
				for (int c : a) {
					if (!inB.contains(c)) {
						safe.add(c);
					}
				}
			}
		}
		if (!safe.isEmpty() || !sure.isEmpty()) {
			return true;
		}

		// The count of mines.
		int knownMines = 0;
		List<Integer> unknown = new ArrayList<Integer>();
		for (int j = 0; j < cells; j++) {
			if (isMine(view, known, j)) {
				knownMines++;
			} else if (view[j] == COVERED) {
				unknown.add(j);
			}
		}
		int left = totalMines - knownMines;
		if (!unknown.isEmpty() && left <= 0) {
			safe.addAll(unknown);
			return true;
		}
		if (!unknown.isEmpty() && left == unknown.size()) {
			sure.addAll(unknown);
			return true;
		}
		return false;
	}

	/** Whether the board can be cleared from its start by reasoning alone. */
	static boolean solvable(int width, int height, int[] mines, int start) {
		int cells = width * height;
		int[] counts = counts(width, height, mines);
		int total = 0;
		for (int m : mines) {
			total += m;
		}
		int[] view = coveredView(cells);
		boolean[] known = new boolean[cells];
		int open = flood(width, height, counts, view, start).size();
		while (open < cells - total) {
			Set<Integer> safe = new LinkedHashSet<Integer>();
			Set<Integer> sure = new LinkedHashSet<Integer>();
			if (!deduce(width, height, view, known, total, safe, sure)) {
				return false;
			}
			for (int j : sure) {
				known[j] = true;
			}
			for (int j : safe) {
				if (mines[j] != 0) {
					return false;
				}
				open += flood(width, height, counts, view, j).size();
			}
		}
		return true;
	}

	/**
	 * A new board of the given difficulty that can be cleared without
	 * guessing: `layout` and `start`. The start is away from the edge, and
	 * neither it nor the cells around it hold a mine.
	 */
	static Map<String, Object> generate(int level) {
		int width = width(level);
		int height = height(level);
		int count = mines(level);
		int cells = width * height;
		int[][] nb = neighbors(width, height);
		List<Integer> inner = new ArrayList<Integer>();
		for (int i = 0; i < cells; i++) {
			if (nb[i].length == 8) {
				inner.add(i);
			}
		}
		for (int attempt = 0; attempt < MAX_TRIES; attempt++) {
			int start = inner.get(RANDOM.nextInt(inner.size()));
			Set<Integer> keep = new HashSet<Integer>();
			keep.add(start);
			for (int j : nb[start]) {
				keep.add(j);
			}
			int[] free = new int[cells - keep.size()];
			int n = 0;
			for (int i = 0; i < cells; i++) {
				if (!keep.contains(i)) {
					free[n++] = i;
				}
			}
			// The first `count` cells of a partial shuffle are the mines.
			for (int k = 0; k < count; k++) {
				int j = k + RANDOM.nextInt(free.length - k);
				int tmp = free[k];
				free[k] = free[j];
				free[j] = tmp;
			}
			int[] mines = new int[cells];
			for (int k = 0; k < count; k++) {
				mines[free[k]] = 1;
			}
			if (solvable(width, height, mines, start)) {
				Map<String, Object> board = new LinkedHashMap<String, Object>();
				board.put('layout', formatLayout(mines));
				board.put('start', start);
				return board;
			}
		}
		throw new IllegalStateException('No board could be made.');
	}

	private static boolean isMine(int[] view, boolean[] known, int j) {
		return known[j] || view[j] == EXPLODED || view[j] == MINE;
	}

	private static int[] toArray(List<Integer> list) {
		int[] array = new int[list.size()];
		for (int k = 0; k < array.length; k++) {
			array[k] = list.get(k);
		}
		return array;
	}

}
