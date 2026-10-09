# Damka

A Java 17 / JavaFX checkers game with two ways to play:

- **Online:** two JavaFX clients connect to a Java TCP server. The server validates moves and owns each room's game.
- **BOT:** play locally, without a server.

Both modes use the same board and movement animation. Profiles, SQLite, and cloud deployment are future work.

## Run on Windows

Use a JDK 17 or newer. If Java is not found in a new PowerShell terminal, set these values to your installed JDK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

From the project folder, run one command in each of three terminals:

```powershell
# Terminal 1: server
.\gradlew.bat tcpServer
```

```powershell
# Terminal 2: first player
.\gradlew.bat run
```

```powershell
# Terminal 3: second player
.\gradlew.bat run
```

Set `JAVA_HOME` in each terminal if needed. Alternatively, start `Main.java` twice using VS Code's Java **Run** action.

Create a room in the first window. Enter its code and join in the second window. Select one of your pieces, then an empty destination. Captures are required when available; after a capture chain, continue with the same piece. The server decides the turn and result. Use **Resign** or **Leave room** to end a match, then create or join another.

**Play vs BOT** works without starting the server.

## Two computers on the same network

Run the server on one computer. Allow Java's TCP port through that computer's firewall for your private network. Find its LAN IPv4 address using `ipconfig`.

Start each client with the server's address:

```powershell
.\gradlew.bat run -PserverHost=192.168.1.10 -PserverPort=5000
```

Replace the example address. `localhost` refers to the computer running that client. The address and port are configuration, so they do not clutter the game window.

For VS Code's Java launcher, put `-Ddamka.server.host=192.168.1.10 -Ddamka.server.port=5000` in its `vmArgs`.

To use a different server port:

```powershell
.\gradlew.bat tcpServer --args="6000"
.\gradlew.bat run -PserverHost=192.168.1.10 -PserverPort=6000
```

On Linux/macOS, replace `.\gradlew.bat` with `bash gradlew`.

## Check the project

```powershell
.\gradlew.bat test
```

Tests exercise actual loopback TCP connections and game rules. For the visual checks, try:

1. Create/join two windows and confirm player X/O and the same board.
2. Reject an invalid move, then make a valid move; both boards update and the old error clears.
3. Resize or scroll during a move; the animated piece follows the correct cells.
4. Complete captures, a multi-capture, and promotion.
5. Resign, leave, close a client, and start another match.
6. Play against BOT without a server, then switch to online play.

Read [LEARNING.md](LEARNING.md) for the code flow and a suggested reading order.
