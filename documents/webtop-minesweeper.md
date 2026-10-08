# Webtop Minesweeper

The **Minesweeper** app is played alone, against the computer, or **with
another user of the Webtop**, wherever that user is signed in: clearing one
board together, or against each other. A game with another user is a
**room**: one user invites the other, the other accepts, and the two play
until the game is decided or one gives up.

Rooms are kept **per workspace**, in the workspace the Webtop runs in.

## Four ways to play

| In the lobby | Rules |
|---|---|
| Play alone | Open every cell that holds no mine. Opening a mine ends the game. Flags are the player's own marks; a hint opens a cell that is surely safe (or flags a sure mine). |
| Play the computer | *Time attack* or *territory* (below), against the computer at one of four strengths. |
| Clear together | One board for two users. Both open cells and put flags down (the flags are shared); the board is cleared when every safe cell is open. A mine opened by either ends the game for both. |
| Play another user | *Time attack* or *territory* against another user, live. |

**Time attack**: each player clears the same board on a board of their own;
the first to open every safe cell wins. The other player's progress is
shown, not their board. **Territory**: one board for both; a flag put on a
mine **takes** it, and the player who found more mines wins: when every mine
is taken or opened, or as soon as the other player can no longer catch up
(or it is a draw).

Against each other a mistake does not end the game: **opening a mine**, or in
territory **putting a flag where there is no mine**, makes the player wait
five seconds. A mine opened in territory belongs to nobody.

Boards come in four difficulties: 9 × 9 with 10 mines, 12 × 12 with 22,
16 × 16 with 40 and 24 × 16 with 72. Every board **can be cleared without
guessing**: its start, a cell with no mine around it, is opened when the
game begins, and from there some cell is always surely safe or surely a
mine. A race is never won on a lucky guess. The app makes a board by placing
mines at random and keeping the first layout its solver clears (core.ts);
it takes a millisecond or so.

## The lobby and the game

As in Reversi and Number Place, the window shows one of two **scenes** next
to the players' panel: the **lobby**, where the next game is set up (and,
with another user, where the two get ready), or the **game**, the board.
The app opens in the lobby, or in the game that was in progress when it was
last closed. A game in progress is left for the lobby only after a
confirmation: a game here is ended, a game with another user goes on and is
listed in the lobby to be resumed. The clock and the computer stop while the
window is not looked at.

A click opens a cell; a click on an open number whose mines are all marked
opens the cells around it. The right button puts a flag down (in territory:
takes the cell). Under the board, *Open* and *Flag* (*Take*) choose what a
click does, for a touch screen, next to the count of mines left. The
keyboard plays too: the arrows move, Enter or Space opens, **F** puts a flag
down, **M** switches between opening and flagging.

## Playing the computer

The computer plays by reasoning, as a person would: it asks the same solver
what the board tells for sure and opens a safe cell or, in territory, takes
a mine it found, near where it played last. Its four strengths (chick,
songbird, owl, hawk) differ in pace and in how often it gets a step wrong;
a wrong step makes it wait like a person.

## Playing another user

In the lobby, *Clear together* or *Play another user*. The section lists the
user's invitations (*Accept* or *Decline*), games in progress (*Resume*) and
invitations sent (*Take back*) of that kind; below, *Invite someone*. Once
the invitation is accepted the room is getting ready: each player picks a
colour (the two differ), the host picks the difficulty and the board, the
guest presses *Ready!* and the host *Start*. The server then makes the
board.

As with Reversi, an invitation is also a **card in the direct messages** of
the two users (`apps/minesweeper/assets/cards/invitation`, posted by the
server as the host); its button launches the app with `{ roomId }`, and the
invited user accepts on the way in. A room has a **chat**, the conversation
of the room's settings file, shown under the players.

*Give up* (the flag in the toolbar) ends the game: against each other the
other player wins; together, the board ends uncleared. When a game ends,
the result offers to play again: a new invitation to the same user with
the same rules.

## Where the games are kept

```
/var/lib/games/minesweeper/rooms/<room>/.room            settings and state of the room
/var/lib/games/minesweeper/boards/<room>/<board>/oNNN    a cell opened, named by the cell
/var/lib/games/minesweeper/boards/<room>/<board>/fNNN    a flag (in territory, a mine taken)
/var/lib/games/minesweeper/mines/<room>                  where the mines of the room's board are
```

`/var/lib/games` is closed to everyone and owned by the `games-service-group`
(`provisioning/minesweeper.yml`, which provisions the same group and service
user as Reversi's). The two players are granted `jcr:read` on their room's
folder only, so that they can read its chat; the boards and the mines are
granted to nobody. **Neither player's app ever knows where the mines are**:
the server makes the board and answers each move with the board as the
player may see it. In a race neither player can see the other's board.
Every write is made by `games-service-user` after the caller was checked in
the caller's own session.

The settings file carries the players (`minesweeper:host`,
`minesweeper:guest`), the mode (`coop`, `race`, `territory`), the
difficulty, the colours, the board theme, the status (`waiting`, `lobby`,
`playing`, `finished`, `declined`, `left`, `cancelled`), the start once the
game began, each player's misses and how long it waits, the times, and once
finished the winner (`host`, `guest` or `draw`; none together) and who gave
up, if anyone.

A board is `shared` (together, territory) or the board of one player
(`host`, `guest`). It is kept as **what was done on it**: each cell opened
and each flag is a file carrying who did it and when (`oNNN`, `fNNN`, NNN the
cell). What the board shows follows from those and the mines: the start is
opened first, then each cell in the order it was opened, a mine opened shows
as exploded, and a flag stays only on a cell still covered. The cells a
player opened are those its own opening uncovered first. A cell is opened,
or a mine taken, only once: the second finds the file there, which settles
who was first when both take the same mine.

Rooms are kept and removed as in Reversi: when a user next sends an
invitation, rooms that ended more than seven days ago are removed, and so are
open rooms with no change and no move for 30 days (their players are told
with an `expired` message). A user may have at most ten rooms open.

## The board is made and played on the server

The Minesweeper GraphQL schema (`/etc/graphql/webtop/minesweeper`) is the
only way to write a room. Its logic is in `webtop.minesweeper.MinesweeperApi`
(`/usr/local/classes/webtop/minesweeper`), with the board maker and the
solver in `MinesweeperRules`, a counterpart of the app's `core.ts`: the two
must agree on the sizes of the boards.

| Operation | Who | What it checks |
|---|---|---|
| `minesweeperInvite(opponentId, mode, level, color, theme, locale)` | anyone signed in | the user exists and is somebody else; the caller's open rooms |
| `minesweeperAccept(id, color)` | the invited user | the room is waiting |
| `minesweeperDecline(id)` | either player | the room is waiting or getting ready; the host's decline is a `cancelled`, a guest's from the lobby a `left` |
| `minesweeperSetup(id, color, level, theme)` | either player; difficulty and board the host | the room is being set up; the colour is not the other player's |
| `minesweeperReady(id, ready)` | the invited user | the room is getting ready |
| `minesweeperStart(id)` | the host | the guest is ready; the board is made here |
| `minesweeperOpen(id, cell, chord)` | either player | the room is playing, the cell is on the board, the player is not waiting; with `chord`, the number's mines are all marked |
| `minesweeperFlag(id, cell, flag)` | either player | the room is playing, the cell is covered; in territory the player is not waiting and the cell is not taken |
| `minesweeperResign(id)` | either player | the room is playing |
| `minesweeperRooms`, `minesweeperRoom(id)` | a player of the room | |

`minesweeperOpen` and `minesweeperFlag` answer `{ outcome, room }`:
`opened`, `mine`, `found`, `wrong`, `flagged`, `unflagged` or `none`. The
room's `board` is the board the caller sees, one character per cell: `-`
covered, `0` to `8` open, `*` a mine opened; once the game is over, `m` for
every other mine. Its `flags` and `blasts` say who put each flag down (or
took each mine) and who opened each mine.

## Live updates

Every change to a room is announced to its two players as a **topic message**
([`topic-messages.md`](topic-messages.md)) on the room's topic,
`game/minesweeper/rooms/<room>`, with the two players as its recipients. The
app subscribes once to `game/minesweeper/rooms/*`:

```json
{ "roomId": "4f2a…", "type": "open", "by": "alice", "board": "shared", "cell": 40, "reveal": [[40, 0], [41, 1]], "opened": 12, "over": false }
```

`type` is `invited`, `accepted`, `changed`, `ready`, `started`, `declined`,
`left`, `cancelled`, `open` (with `board`, `cell`, `opened`, `over`,
`winner`; on the shared board the cells opened, `reveal`, and the mine
opened, `blast`; `mine` with `misses` and `lockMs` when a mine was opened),
`flag` (together: `cell` and `flag`), `claim` (territory: `cell`, `found`,
`over`, `winner`), `miss` (territory, a flag where there is no mine:
`misses` and `lockMs`), `resigned` (with `winner`) or `expired`. A race's
`open` does not say where: only how far the player got.

The app shows what the other player did at once when it fits the board it
shows; in every other case, at the end of the game, and when it looks at the
app again after being hidden, it reads the room. The room is the record; a
message is only what makes the app read it.
