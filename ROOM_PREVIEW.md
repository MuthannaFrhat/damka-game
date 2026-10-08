# Learning step: display the server's initial game in JavaFX

This step creates one Game inside each Room and displays its initial board in
both clients. It does not implement online moves, resignation, accounts,
SQLite, heartbeats or read timeouts. Local BOT play remains available.

## Run in VS Code / Windows

Open three PowerShell terminals in C:\dev\damka-tcp. In each terminal, if Java
is not already available, run:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

Terminal 1:

```powershell
.\gradlew.bat tcpServer
```

Terminals 2 and 3:

```powershell
.\gradlew.bat run
```

Choose **Online rooms** in both windows. In the first choose **Create room**;
copy its six-character code into the second window and choose **Join room**.
Use localhost and port 5000 on one computer. For another computer on the same
LAN, use the server computer's LAN address and allow its inbound TCP port 5000.
The server still listens on the existing fixed port 5000.

Both windows should display the same initial board and Black's turn. The creator
is Black/X; the joiner is Red/O. Online clicks deliberately do nothing: sending
moves is the NEXT learning step. **Leave room**, closing a window, or switching
to **Player vs Bot** closes the connection; the opponent sees the room closed.

The Gradle application entry was corrected to the existing Main class so `run`
works; Main.java itself was not changed. `tcpServer` starts DamkaServer. The
Gradle percentage stays below 100% while an application is running; that is normal.

## Read the changes in this order

1. Room's `private final Game game = new Game()` creates separate game state
   for every room. A room's sockets are the recipients of that state.
2. Room sends `PLAYER x` to the creator and `PLAYER o` to the joiner.
3. After ROOM_READY, `sendInitialState()` walks the board and builds a 64-character
   string: `.` is empty, x/o are normal pieces and X/O are kings.
4. Both players receive `STATE x <board>`: current turn followed by the board,
   in row-major order. Existing ROOM_CREATED/JOINED/READY/CLOSED messages remain.
5. RoomConnection opens the socket and sends its request on one background
   thread. It then reads messages until the connection ends. Consumer<String>
   is the callback the caller supplies for each received message.
6. DamkaFX wraps that callback in Platform.runLater: only the JavaFX thread
   updates controls. `onlineBoard` is a display snapshot, not a local authority.
   `drawBoard()` reads it in online mode; local BOT mode still reads Game's Board.
7. `close()` closes the socket to release a waiting read. The volatile fields
   make close/socket changes visible across the UI and network threads. Old
   callbacks are ignored after the user switches connections or modes.

No read deadlines are imposed. A network failure without a clear close may
leave a read waiting; disconnect detection will be studied in a later step.

## Validation

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

RoomStateTest uses real TCP socket pairs to verify assigned colors, identical
initial state, full-room rejection, notification of room closure and removal.
The tests bound their OWN reads to avoid hanging a failed test; the application
has no read timeout. A separate cloud JavaFX smoke check exercised two windows,
rendered state, disabled online clicks, disconnect notification and local BOT
play. Physical two-computer LAN connectivity has not been tested here.
