# Webtop Reversi

The **Reversi** app plays Reversi against the computer (four levels), between
two people at the same desktop, or **against another user of the Webtop**,
wherever that user is signed in. A game against another user is a **room**:
one user invites the other, the other accepts, and the two move in turn until
the game is over or one resigns.

Rooms are kept **per workspace**, in the workspace the Webtop runs in.

## Playing another user

*New game* → *Another user*. The dialog lists, for the user:

| | What it is | What can be done |
|---|---|---|
| Invitations | games another user proposed | *Accept* (the game begins) or *Decline* |
| Games in progress | games to go back to | *Resume* |
| Invitations sent | games waiting for the other user's answer | *Take back* |

Below the list, *Invite someone*: search a user by name, choose the side to
play (first, second or either) and press *Invite*. The board shows the
opening position until the invitation is answered; a declined or withdrawn
invitation is announced and the dialog returns.

During a game the user moves when it is their turn; the other player's move
appears as it is played. *Resign* (the flag in the toolbar) gives the game up.
When a game ends, the result card offers a **rematch**: a new invitation to
the same opponent with the sides swapped. *Undo* is not available against
another user.

An invitation that arrives while the app is open is announced and marked on
the *New game* button. A game in progress is reopened when the app is
launched again.

## Where the games are kept

```
/var/lib/games/reversi/rooms/<room>/.room        settings and state of the room
/var/lib/games/reversi/rooms/<room>/moves/NNN    one file per move, named by its ply
```

`/var/lib/games` is closed to everyone and owned by the `games-service-group`
(`provisioning/reversi.yml`). The two players are granted `jcr:read` on
their room's folder. Every write is made by `games-service-user` after the
caller was checked in the caller's own session, so a game cannot be changed
from the Content Browser.

The settings file carries the players (`reversi:black`, `reversi:white`), the
host, the status (`waiting`, `playing`, `finished`, `declined`, `cancelled`),
the board size and the times; once finished, the winner (`black`, `white` or
`draw`) and who resigned, if anyone. A move is a file whose content is the
move in coordinate notation (`d3`) and whose name is its ply (`000`, `001`,
…). Two moves cannot be written for the same ply, which is what keeps a
double click or a stale client from playing twice.

The settings file also keeps the time of its last change
(`reversi:updatedAt`), and each move the time it was played
(`reversi:playedAt`). When a user next sends an invitation, rooms that ended
more than seven days ago are removed, and so are rooms still waiting,
getting ready or playing with no change and no move for 30 days; the
players of such a room are told with an `expired` message. A user may have
at most ten rooms waiting or playing at once.

## The rules are applied on the server

The Reversi GraphQL schema (`/etc/graphql/webtop/reversi`) is the only way to
write a room. Its logic is in `webtop.reversi.ReversiApi`
(`/usr/local/classes/webtop/reversi`), with the rules in `ReversiRules`, a
counterpart of the app's `core.ts`: the two must agree on every move.

| Operation | Who | What it checks |
|---|---|---|
| `reversiInvite(opponentId, side, size)` | anyone signed in | the opponent exists and is somebody else; the caller's open rooms |
| `reversiAccept(id)` | the invited user | the room is waiting |
| `reversiDecline(id)` | either player | the room is waiting; the host's decline is a `cancelled` |
| `reversiPlay(id, ply, move)` | the player to move | the room is playing, `ply` is the number of moves played, the move is legal |
| `reversiResign(id)` | either player | the room is playing |
| `reversiRooms`, `reversiRoom(id)` | a player of the room | |

`reversiPlay` replays the moves of the room, refuses a `ply` other than the
current one ("The game has moved on"), works out whose turn it is (a player
with no legal move passes automatically, as in the app) and, when the game
is over after the move, records the winner.

## Live updates

Every change to a room is announced to its two players as a **topic message**
([`topic-messages.md`](topic-messages.md)) on the room's topic,
`game/reversi/rooms/<room>`, with the two players as its recipients. The app
subscribes once to `game/reversi/rooms/*` and receives the messages of every
room it plays in:

```json
{ "roomId": "4f2a…", "type": "move", "by": "alice", "ply": 12, "move": "d3", "over": false }
```

`type` is `invited`, `started`, `declined`, `cancelled`, `move` (with `ply`,
`move`, `over` and, when over, `winner`), `resigned` (with `winner`) or
`expired` (the room was idle and has been removed).

The app shows a `move` directly when it is the next move of the game on the
board and is legal there; in every other case, and when it looks at the app
again after being hidden, it reads the room and brings the board up to date.
The room is the record; a message is only what makes the app read it.

In a cluster, the message reaches the other player's node over the cluster
signal bus within its poll interval (2 seconds); the repository is shared,
so the room read on any node is the same.
