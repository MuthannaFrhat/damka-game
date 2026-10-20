# Damka

A checkers game built with Java 17 and JavaFX. Two players can play over a local network through a Java TCP server, or one player can play locally against a BOT.

## Features

- Online rooms with a code for creating and joining a match.
- Server-controlled multiplayer boards, move validation, turns, and results.
- BOT games with three difficulty levels, available after login.
- Sign-up, login, and persistent profiles with separate online and BOT statistics.
- Match history stored in SQLite on the server.
- Shared board animation, room-code copying, and clear game messages.

## Requirements

- JDK 17 or 21. The project targets Java 17.
- For VS Code: the Java extensions and Gradle project import.

The Gradle wrapper is included. Gradle downloads the JavaFX, SQLite, and test dependencies automatically.

## Run in VS Code

Open the folder containing `build.gradle` and wait for the Java project import to finish.

1. Open `src/main/java/DamkaServer.java` and click **Run** above `main`.
2. Open `src/main/java/Main.java` and click **Run** to start the first player.
3. Run `Main` again to start the second player.

Keep the server running for both game modes. Create an account or log in before choosing an online room or a BOT game. Signing out or losing the server connection returns the app to the login screen.

## Run from terminals

Run these commands from the project folder. Java must be available through `JAVA_HOME` and `PATH`.

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

On Linux or macOS, use `bash gradlew` instead of `.\gradlew.bat`.

## Play an online match

Create an account or log in with a different username in each window. The first player creates a room and shares its code using **Copy**. The second player enters the code and joins.

The creator plays Green and moves first; the joining player plays Red. A prominent **Player: Alice** label at the top identifies the account that owns each window. Both windows show the current player's username and color, for example **Turn: Alice · Green**, with a matching green or red accent. Select a piece and then its destination. Captures are mandatory when available, and a capture chain continues with the same piece. Use **Resign** to concede or **Leave room** to exit. Leaving or disconnecting during an active match ends it in the other player's favor.

## Play on two computers

Connect both computers to the same local network. Run the server on one computer and allow its TCP port through that computer's firewall. The default port is `5000`.

Start each client with the server computer's LAN address:

```powershell
.\gradlew.bat run -PserverHost=192.168.1.10 -PserverPort=5000
```

Replace the example address with your server's IPv4 address. `localhost` works when the client and server are on the same computer.

For VS Code's Java launcher, set `-Ddamka.server.host=192.168.1.10 -Ddamka.server.port=5000` in its `vmArgs`. To change the server port, run `.\gradlew.bat tcpServer --args="6000"` and configure both clients to use that port.

The TCP connection is unencrypted; this version is intended for a local network.

## Accounts and profiles

Usernames are case-sensitive. Passwords are stored as salted hashes. Accounts and match results persist in `data/damka.db` on the server; the database is excluded from Git.

**Profile** displays separate online and BOT statistics and the latest 100 matches. Statistics include all recorded matches. Use **Refresh** to reload the data, then close the profile window to return to the game.

Online results are validated and recorded by the server. BOT moves are calculated locally, and completed results are reported to the server for the logged-in account. Signing out or disconnecting ends the current BOT session; unfinished games are not recorded or resumed after login.

## Automated demo

Run `.\gradlew.bat demo` on Windows to record a complete online win, saved profiles and Hard BOT play. Recordings are saved to `demo/output/` and excluded from Git. See the [demo instructions](demo/README.md) for FFmpeg installation, output files and rehearsal options.

## Tests

```powershell
.\gradlew.bat test
```

The tests cover game rules, room messages over TCP, account authentication, and SQLite result storage.
