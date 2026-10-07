# TCP multiplayer: run and understand

This implementation adds LAN multiplayer to the JavaFX game. The server owns
one Game per Room. Clients send moves, never a board. Local BOT play is available
without the server. Profiles, SQLite, BOT result uploads and cloud hosting are
not implemented in this change.

## Run on Windows / VS Code

Use JDK 17 or 21. In each PowerShell terminal, configure Java if necessary:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

From the project directory, terminal 1 runs the server:

```powershell
.\gradlew.bat tcpServer -PserverPort=5000
```

Terminal 2 runs the game:

```powershell
.\gradlew.bat run
```

Run a second game on another computer, or another terminal on this computer.
In the game choose **Play with a friend**. The default connection works on this computer. For a different host or port, open **Connection settings**.
Player A chooses **Create room** and shares the displayed code. Player B enters
it and chooses **Join room**. Creator is Black/X and moves first; joiner is Red/O.
Click your piece, then its destination. Both boards update after server approval.
Mandatory captures and same-piece continuation are enforced on the server.
**Leave match** ends the game; closing the window or switching to BOT mode
also disconnects and ends that room. A finished room cannot be rejoined.

For two computers on the same LAN, enter the server computer's LAN IPv4 address,
not localhost. Allow the selected inbound TCP port on that computer's private
network firewall. The server binds to all interfaces by default. Optional:

```powershell
.\gradlew.bat tcpServer -PserverPort=6000 -PserverBind=192.168.1.20
```

Linux/macOS: replace `.\gradlew.bat` with `bash gradlew`.
The cloud checkout uses `GRADLE_USER_HOME=/workspace/.gradle-cache` for its proxy
and retained dependencies. Do not use single-source `java DamkaServer.java` now:
the server depends on Room, Game, Board and other project classes.

## Tests

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

The automated integration tests use real TCP clients and an in-process server.
They cover room creation/joining/capacity, invalid requests, turn and coordinate
validation, synchronized boards, resignation, disconnect cleanup, room isolation,
and a full game including captures, promotion and victory. The server thread is
a daemon confined to the test JVM; it ends with that JVM.

Manual checks: try an illegal move, wait over 15 seconds without moving, resign,
close one client, and stop the server. The other UI must show the corresponding
result or connection failure. Switch to **Play against BOT** with the server stopped.
Physical two-computer LAN connectivity depends on local routing and firewall
settings and must be checked on your own computers.

## Message protocol (UTF-8, one message per line)

| Sender | Message | Meaning |
|---|---|---|
| Client | CREATE_ROOM | Create and occupy the first seat |
| Client | JOIN_ROOM ABC123 | Occupy the second seat |
| Client | MOVE 5 0 4 1 | Request a move; coordinates are 0..7 |
| Client | RESIGN | Leave/resign |
| Client | PING | Heartbeat |
| Server | PONG | Heartbeat reply |
| Server | ROOM_CREATED ABC123 x | Code and assigned color |
| Server | ROOM_JOINED ABC123 o | Join confirmation and color |
| Server | STATE ACTIVE x -1 -1 <64 characters> | Status, turn, forced capture row/column, board |
| Server | ERROR <text> | Rejected request; board remains unchanged |
| Server | GAME_OVER x RESIGNED | Winner and reason; connection closes |

STATE can be WAITING, ACTIVE or FINISHED. Board characters are `.`, `x`, `o`, `X`,
`O`, in row-major order; uppercase pieces are kings. Forced coordinates `-1 -1`
mean no mandatory continuation. A winner of `-` means an unstarted room closed.
Final STATE precedes GAME_OVER after a board victory. GAME_OVER reasons are
NO_MOVES, RESIGNED and DISCONNECTED. Old console clients must use this protocol.

## Code reading order

1. **DamkaServer.main**: ServerSocket accepts connections. A separate worker
   thread calls handleClient for each connection, leaving main free to accept
   the next player. `() -> handleClient(player)` describes that worker's task;
   `start()` begins it. Peer groups the Socket, reader and writer.
2. **Room.createRoom / joinRoom**: store two Peers in one room. The registry lock
   protects lookup/registration; a room's synchronized methods serialize its
   moves and membership changes. Separate rooms have separate game locks.
3. **Room.move**: checks readiness, sender's turn, coordinates and capture chain,
   calls Game.move, and broadcasts the complete authoritative board.
4. **NetworkClient**: one reader thread connects and reads messages; one scheduled
   writer thread sends requests and periodic heartbeats. They never access UI
   controls. `close()` cancels work and closes the socket to release pending reads.
5. **DamkaFX.connectOnline / receiveOnline / handleOnlineClick**: callbacks use
   Platform.runLater to update JavaFX safely. A click sends a request; STATE is
   what updates the board. Old connection callbacks are ignored after switching
   rooms or modes.
6. **Room.leave**: removes the room, sends the result and closes both sockets.
   finally in handleClient calls it after errors or normal EOF. Cleanup is safe
   when both workers notice the same shutdown.

## Why these timeouts?

Connect timeout: 5 seconds. Read timeout: 15 seconds. NetworkClient sends PING
approximately every 3 seconds; server replies PONG. Consequently a player can
think for minutes without timing out. If traffic stops, pending reads time out
and the UI/room closes. This is not a turn timer and it does not guarantee an
exact disconnect deadline under every network condition. Server reads also
limit incoming request length to 256 characters.

## Current limits

Trusted-LAN project, with no authentication, encryption, reconnect or persistent
identity. A second connection is a second participant; duplicate identities are
not detectable yet. Rooms disappear on server restart. Abrupt connection loss
is treated as a forfeit in an active game. Draw detection and production resource
limits are not added. The UI trusts snapshots from the selected server. Port and
address are configurable; deployment elsewhere is outside this change.
