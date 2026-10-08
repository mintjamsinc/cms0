# Webtop Number Place

The **Number Place** app (sudoku) is played alone, against the computer, or
**with another user of the Webtop**, wherever that user is signed in: solving
one board together, or against each other. A game with another user is a
**room**: one user invites the other, the other accepts, and the two play
until the board is full or one gives up.

Rooms are kept **per workspace**, in the workspace the Webtop runs in.

## Four ways to play

| In the lobby | Rules |
|---|---|
| Play alone | Any number may be written. Wrong numbers are marked on request (the toolbar's check mark); undo and hints are there. The puzzle is solved when every cell holds the right number. |
| Play the computer | *Time attack* or *territory* (below), against the computer at one of four strengths. |
| Solve together | One board for two users; each fills cells as they please, and the puzzle is solved when the board is full. |
| Play another user | *Time attack* or *territory* against another user, live. |

**Time attack**: each player solves the same puzzle on a board of their own;
the first to fill it wins. The other player's progress is shown, not their
numbers. **Territory**: one board for both; a cell filled right belongs to
whoever filled it, and when the board is full the player with more cells
wins (or it is a draw).

Outside *play alone* a number is **checked as it is written**: a right one
fills the cell, a wrong one fills nothing and makes the player wait three
seconds before writing again. A filled cell cannot be erased; notes can.

Puzzles come in four difficulties. They are made in the app (in a Web
Worker) from a random solved grid, and always have exactly one solution: an
easy or medium one is solved with singles alone, a hard one needs locked
candidates or pairs, an expert one more than that.

## The lobby and the game

As in Reversi, the window shows one of two **scenes** next to the players'
panel: the **lobby**, where the next game is set up (and, with another user,
where the two get ready), or the **game**, the board with its number pad.
The app opens in the lobby, or in the game that was in progress when it was
last closed. A game in progress is left for the lobby only after a
confirmation: a game here is ended, a game with another user goes on and is
listed in the lobby to be resumed. The clock and the computer stop while the
window is not looked at.

The keyboard plays too: digits write, the arrows move, Backspace erases,
**N** switches to notes, and Shift with a digit writes a note at once.

## Playing another user

In the lobby, *Solve together* or *Play another user*. The section lists the
user's invitations (*Accept* or *Decline*), games in progress (*Resume*) and
invitations sent (*Take back*) of that kind; below, *Invite someone*. Once
the invitation is accepted the room is getting ready: each player picks a
colour (the two differ), the host picks the difficulty and the board, the
guest presses *Ready!* and the host *Start*. The host's app then makes the
puzzle, which the server checks before the game begins.

As with Reversi, an invitation is also a **card in the direct messages** of
the two users (`apps/numberplace/assets/cards/invitation`, posted by the
server as the host); its button launches the app with `{ roomId }`, and the
invited user accepts on the way in. A room has a **chat**, the conversation
of the room's settings file, shown under the players.

*Give up* (the flag in the toolbar) ends the game: against each other the
other player wins; together, the puzzle ends unsolved. When a game ends,
the result offers to play again: a new invitation to the same user with
the same rules.

## Where the games are kept

```
/var/lib/games/numberplace/rooms/<room>/.room           settings and state of the room
/var/lib/games/numberplace/boards/<room>/<board>/NN     one file per filled cell, named by the cell
/var/lib/games/numberplace/solutions/<room>             the solution of the room's puzzle
```

`/var/lib/games` is closed to everyone and owned by the `games-service-group`
(`provisioning/numberplace.yml`, which provisions the same group and service
user as Reversi's). The two players are granted `jcr:read` on their room's
folder only, so that they can read its chat; the boards and the solution are
granted to nobody. In a race neither player can read the other's numbers,
and a number is known to be right only once the server says so. Every write
is made by `games-service-user` after the caller was checked in the caller's
own session.

The settings file carries the players (`numberplace:host`,
`numberplace:guest`), the mode (`coop`, `race`, `territory`), the
difficulty, the colours, the board, the status (`waiting`, `lobby`,
`playing`, `finished`, `declined`, `left`, `cancelled`), the puzzle once the
game started, each player's wrong numbers and how long it waits, the times,
and once finished the winner (`host`, `guest` or `draw`; none together) and
who gave up, if anyone. A board is `shared` (together, territory) or the
board of one player (`host`, `guest`). A filled cell is a file whose content
is the digit, named by the cell (`00` to `80`): two numbers cannot be
written in one cell, which settles who was first when both fill it.

Rooms are kept and removed as in Reversi: when a user next sends an
invitation, rooms that ended more than seven days ago are removed, and so are
open rooms with no change and no filled cell for 30 days (their players are
told with an `expired` message). A user may have at most ten rooms open.

## The numbers are checked on the server

The Number Place GraphQL schema (`/etc/graphql/webtop/numberplace`) is the
only way to write a room. Its logic is in `webtop.numberplace.NumberPlaceApi`
(`/usr/local/classes/webtop/numberplace`), with the solver in
`NumberPlaceRules`, a counterpart of the app's `core.ts`: the two must agree
on which puzzles are valid.

| Operation | Who | What it checks |
|---|---|---|
| `numberplaceInvite(opponentId, mode, level, color, theme, locale)` | anyone signed in | the user exists and is somebody else; the caller's open rooms |
| `numberplaceAccept(id, color)` | the invited user | the room is waiting |
| `numberplaceDecline(id)` | either player | the room is waiting or getting ready; the host's decline is a `cancelled`, a guest's from the lobby a `left` |
| `numberplaceSetup(id, color, level, theme)` | either player; difficulty and board the host | the room is being set up; the colour is not the other player's |
| `numberplaceReady(id, ready)` | the invited user | the room is getting ready |
| `numberplaceStart(id, givens)` | the host | the guest is ready; the puzzle has at least 17 givens, no clash and exactly one solution |
| `numberplacePlay(id, cell, digit)` | either player | the room is playing, the cell is not given and not filled on the board played, the player is not waiting |
| `numberplaceResign(id)` | either player | the room is playing |
| `numberplaceRooms`, `numberplaceRoom(id)` | a player of the room | |

`numberplacePlay` answers `{ correct, room }`. A wrong number counts as a
miss and makes the player wait 3 seconds (refused meanwhile). A right one
fills the cell; when the board played is full the game is over: in a race
the player who filled it wins, in territory the one with more cells.

## Live updates

Every change to a room is announced to its two players as a **topic message**
([`topic-messages.md`](topic-messages.md)) on the room's topic,
`game/numberplace/rooms/<room>`, with the two players as its recipients. The
app subscribes once to `game/numberplace/rooms/*`:

```json
{ "roomId": "4f2a…", "type": "fill", "by": "alice", "board": "shared", "cell": 40, "digit": 7, "filled": 12, "over": false }
```

`type` is `invited`, `accepted`, `changed`, `ready`, `started`, `declined`,
`left`, `cancelled`, `fill` (with `board`, `filled`, `over`, `winner`, and on
the shared board `cell` and `digit`), `miss` (with `misses` and `lockMs`),
`resigned` (with `winner`) or `expired`. A race's `fill` does not carry the
number, only how far the player got.

The app shows the other player's number at once when the cell is still empty
on its board; in every other case, and when it looks at the app again after
being hidden, it reads the room. The room is the record; a message is only
what makes the app read it.
