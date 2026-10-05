package webtop.reversi;

/**
 * The rules of Reversi, as the server applies them. This is the counterpart
 * of core.ts in the Reversi app: a game is its board size and its moves in
 * coordinate notation ("d3": column letter, row number), passes are not
 * recorded, and replaying the moves from the opening position gives the
 * position. Both sides must agree on every move, so the two must stay the
 * same.
 *
 * A position is a map: size, cells (row-major, 0 empty, 1 black, 2 white),
 * turn (the player to move), over, ply (moves played so far).
 */
class ReversiRules {

	static final int EMPTY = 0;
	static final int BLACK = 1;
	static final int WHITE = 2;

	static final List<Integer> SIZES = [6, 8, 10];

	private static final List<List<Integer>> DIRECTIONS = [
		[-1, -1], [-1, 0], [-1, 1],
		[0, -1], [0, 1],
		[1, -1], [1, 0], [1, 1],
	];

	static int opponent(int player) {
		return (player == BLACK) ? WHITE : BLACK;
	}

	static int checkSize(Object value) {
		int size = (value == null) ? 8 : (value as int);
		if (!SIZES.contains(size)) {
			throw new IllegalArgumentException("Unsupported board size: ${size}".toString());
		}
		return size;
	}

	static Map initialPosition(int size) {
		List<Integer> cells = new ArrayList<Integer>(Collections.nCopies(size * size, EMPTY));
		int m = size.intdiv(2);
		cells[(m - 1) * size + (m - 1)] = WHITE;
		cells[(m - 1) * size + m] = BLACK;
		cells[m * size + (m - 1)] = BLACK;
		cells[m * size + m] = WHITE;
		return [size: size, cells: cells, turn: BLACK, over: false, ply: 0];
	}

	/** The discs the player would turn over by playing at the index; empty when the move is illegal. */
	static List<Integer> flipsFor(List<Integer> cells, int size, int index, int player) {
		if (cells[index] != EMPTY) {
			return [];
		}
		int o = opponent(player);
		int r0 = index.intdiv(size);
		int c0 = index % size;
		List<Integer> result = [];
		for (List<Integer> d : DIRECTIONS) {
			int r = r0 + d[0];
			int c = c0 + d[1];
			List<Integer> line = [];
			while (r >= 0 && r < size && c >= 0 && c < size && cells[r * size + c] == o) {
				line.add(r * size + c);
				r += d[0];
				c += d[1];
			}
			if (line && r >= 0 && r < size && c >= 0 && c < size && cells[r * size + c] == player) {
				result.addAll(line);
			}
		}
		return result;
	}

	static List<Integer> legalMoves(List<Integer> cells, int size, int player) {
		List<Integer> moves = [];
		for (int i = 0; i < cells.size(); i++) {
			if (cells[i] == EMPTY && flipsFor(cells, size, i, player)) {
				moves.add(i);
			}
		}
		return moves;
	}

	/** Plays a legal move and works out whose turn it is next, passing when needed. */
	static Map apply(Map position, int index) {
		if (position.over) {
			throw new IllegalStateException('The game is over.');
		}
		int size = position.size as int;
		int player = position.turn as int;
		List<Integer> flipped = flipsFor(position.cells as List<Integer>, size, index, player);
		if (!flipped) {
			throw new IllegalArgumentException("Illegal move: ${toCoord(index, size)}".toString());
		}
		List<Integer> cells = new ArrayList<Integer>(position.cells as List<Integer>);
		cells[index] = player;
		for (int i : flipped) {
			cells[i] = player;
		}
		Map next = [size: size, cells: cells, turn: opponent(player), over: false, ply: (position.ply as int) + 1];
		if (!legalMoves(cells, size, next.turn as int)) {
			if (legalMoves(cells, size, player)) {
				next.turn = player;
			} else {
				next.over = true;
			}
		}
		return next;
	}

	/** The position after the moves; throws on an illegal record. */
	static Map replay(int size, List<String> moves) {
		Map position = initialPosition(size);
		for (String move : moves) {
			position = apply(position, fromCoord(move, size));
		}
		return position;
	}

	static Map count(List<Integer> cells) {
		int black = 0;
		int white = 0;
		for (int c : cells) {
			if (c == BLACK) {
				black++;
			} else if (c == WHITE) {
				white++;
			}
		}
		return [black: black, white: white];
	}

	/** black, white or draw. Only meaningful once the game is over. */
	static String winner(List<Integer> cells) {
		Map n = count(cells);
		if (n.black == n.white) {
			return 'draw';
		}
		return (n.black > n.white) ? 'black' : 'white';
	}

	static String toCoord(int index, int size) {
		return ((char) (97 + (index % size))).toString() + (index.intdiv(size) + 1);
	}

	static int fromCoord(String coord, int size) {
		def m = (coord ?: '').trim().toLowerCase() =~ /^([a-z])(\d{1,2})$/;
		if (!m.matches()) {
			throw new IllegalArgumentException("Bad coordinate: ${coord}".toString());
		}
		int col = (m.group(1) as char) - ('a' as char);
		int row = (m.group(2) as int) - 1;
		if (col >= size || row < 0 || row >= size) {
			throw new IllegalArgumentException("Off the board: ${coord}".toString());
		}
		return row * size + col;
	}

}
