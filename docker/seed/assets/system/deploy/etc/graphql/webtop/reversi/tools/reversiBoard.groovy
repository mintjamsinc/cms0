// The MCP tool reversi_board (tools.yml): the position of a room as text,
// for a client that wants to see the board before it plays.
//
// Runs as the caller, like a resolver: ReversiApi only shows a room to its
// players. The position is replayed from the recorded moves with the same
// rules the app applies, so the legal moves listed here are the ones
// reversiPlay accepts.
import webtop.reversi.ReversiApi;
import webtop.reversi.ReversiRules;

Map room = ReversiApi.create(context).room(args.id as String);
int size = room.size as int;
List<String> moves = (room.moves ?: []) as List<String>;
Map position = ReversiRules.replay(size, moves);
List<Integer> cells = position.cells as List<Integer>;
int turn = position.turn as int;
// A room ends by the board (no move left) or by its status: a resignation,
// a declined invitation or a cancelled room leave the board playable.
boolean ended = !(room.status in ['waiting', 'lobby', 'playing']);
boolean over = ended || (position.over as boolean);

StringBuilder sb = new StringBuilder();
sb.append('   ');
for (int c = 0; c < size; c++) {
	sb.append(((char) (97 + c)).toString()).append(' ');
}
sb.append('\n');
for (int r = 0; r < size; r++) {
	sb.append(String.format('%2d ', r + 1));
	for (int c = 0; c < size; c++) {
		int v = cells[r * size + c];
		sb.append((v == ReversiRules.BLACK) ? 'X' : ((v == ReversiRules.WHITE) ? 'O' : '.')).append(' ');
	}
	sb.append('\n');
}

String side = (turn == ReversiRules.BLACK) ? 'black' : 'white';
List<String> legal = over ? [] :
	ReversiRules.legalMoves(cells, size, turn).collect { ReversiRules.toCoord(it as int, size) };
return [
	id: room.id,
	status: room.status,
	size: size,
	yourSide: room.yourSide,
	board: sb.toString(),
	legend: 'X black, O white, . empty; columns a.., rows 1..',
	ply: moves.size(),
	turn: over ? null : side,
	yourTurn: !over && room.status == 'playing' && side == room.yourSide,
	legalMoves: legal,
	count: ReversiRules.count(cells),
	over: over,
	winner: room.winner,
];
