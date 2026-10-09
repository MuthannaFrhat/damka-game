# Damka

A Java 17 / JavaFX checkers game with two ways to play:

- **Online:** two JavaFX clients connect to a Java TCP server. The server validates moves and owns each room's game.
- **BOT:** play locally, without a server.

Both modes use the same board and movement animation. Profiles, SQLite, and cloud deployment are future work.

## Run in VS Code

Use the Java extensions and a JDK compatible with the project (Java 17 or 21). Open the project folder containing `build.gradle` and let VS Code finish importing the Gradle project.

For a game on one computer, no terminal commands are needed:

1. Open `src/main/java/DamkaServer.java` and click **Run** above `main` once. Leave it running.
2. Open `src/main/java/Main.java` and click **Run** above `main` to open the first player window.
3. Click **Run** above `Main.main` again to open the second player window.

The Java extension uses its configured runtime and the project's JavaFX dependencies. If VS Code reports build errors, fix them first rather than continuing with an old compiled version. If the JavaFX dependencies have not been imported, refresh the Gradle project or use the Gradle commands below.

Create a room in the first window. Enter its code and join in the second window. Select one of your pieces, then an empty destination. Captures are required when available; after a capture chain, continue with the same piece. The server decides the turn and result. Use **Resign** or **Leave room** to end a match, then create or join another.

**Play vs BOT** only needs `Main.java`; it works without starting the server.

## Run from Windows terminals

Gradle provides an alternative launcher and useful build diagnostics. PowerShell needs to know where Java is installed; this is separate from running with VS Code's Java **Run** action. If Java is not found in a new terminal, set these values to your installed JDK:

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

Those PowerShell settings last only for that terminal. To set them once for future terminals, open Windows **Edit environment variables for your account**, add a user `JAVA_HOME` pointing to the JDK folder, and add `%JAVA_HOME%\bin` to the user `Path`. Restart VS Code afterward.

VS Code's `java.jdt.ls.java.home` selects the JDK for the Java language server, not PowerShell. Recent Java extensions require Java 21 for that service; the game still targets Java 17. Changing that setting does not configure terminal `JAVA_HOME` or `Path`.

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
