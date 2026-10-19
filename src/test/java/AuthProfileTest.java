import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Base64;
import java.util.UUID;
import java.util.Set;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuthProfileTest {
    @TempDir
    Path temporaryDirectory;
    private Path databasePath;
    private RunningServer server;

    @BeforeEach
    void startServer() throws Exception {
        databasePath = temporaryDirectory.resolve("profiles.db");
        server = new RunningServer(databasePath);
    }

    @AfterEach
    void stopServer() throws Exception {
        server.close();
    }

    @Test
    void requestsRequireAuthenticationButTheSocketCanRecover() throws Exception {
        try (Peer peer = connect()) {
            peer.send("CREATE_ROOM");
            assertEquals("ERROR Log in first.", peer.read());
            peer.send("JOIN_ROOM ABC123");
            assertEquals("ERROR Log in first.", peer.read());
            assertEquals(0, profileCount());

            signUp(peer, "new_player");
            assertTrue(createRoom(peer).matches("[A-Z0-9]{6}"));
            assertEquals(1, profileCount());
        }
    }

    @Test
    void invalidNamesAreRejectedAndTwentyFourCharactersAreAllowed() throws Exception {
        try (Peer peer = connect()) {
            for (String invalid : new String[]{"", "bad name", "name!", "a".repeat(25), "😀"}) {
                peer.send("SIGNUP " + invalid + " " + encodedPassword("password123"));
                assertTrue(peer.read().startsWith("AUTH_ERROR "), "Accepted invalid username: " + invalid);
            }
            assertEquals(0, profileCount());
            signUp(peer, "a".repeat(24));
            assertEquals(1, profileCount());
        }
    }

    @Test
    void unicodeLettersNumbersAndHyphensSurviveTheTcpAndDatabaseRoundTrip() throws Exception {
        String username = "علي_7-א";
        String opponentUsername = "דנה_8-ب";
        try (Peer peer = connect(); Peer opponent = connect()) {
            // The server trims only the outside whitespace, preserving the name itself.
            peer.send("SIGNUP   " + username + "  " + encodedPassword("password123"));
            assertEquals("AUTH_OK " + username, peer.read());
            peer.username = username;
            assertNotNull(createdAt(username));
            signUp(opponent, opponentUsername);
            joinRoom(peer, opponent, createRoom(peer));
            moveAndCheckTurn(peer, opponent, "MOVE 5 0 4 1", "TURN O " + opponentUsername);
            moveAndCheckTurn(opponent, peer, "MOVE 2 1 3 0", "TURN X " + username);
            peer.send("RESIGN");
            assertEquals("GAME_OVER O RESIGNATION " + opponentUsername, peer.read());
            assertEquals("GAME_OVER O RESIGNATION " + opponentUsername, opponent.read());
        }
    }

    @Test
    void aConnectionsUsernameCannotChangeDuringARematch() throws Exception {
        try (Peer peer = connect()) {
            signUp(peer, "Alice");
            createRoom(peer);
            peer.send("LEAVE_ROOM");
            assertEquals("ROOM_LEFT", peer.read());
            peer.send("LOGIN Bob " + encodedPassword("password123"));
            assertEquals("AUTH_ERROR You are already logged in on this connection.", peer.read());
            createRoom(peer);
            assertEquals(1, profileCount());
            assertNotNull(createdAt("Alice"));
            assertNull(createdAt("Bob"));
        }
    }

    @Test
    void oneProfileCannotJoinBothSeatsAndRejectionLeavesTheSeatAvailable() throws Exception {
        try (Peer creator = connect(); Peer duplicate = connect(); Peer opponent = connect()) {
            signUp(creator, "Alice");
            login(duplicate, "Alice", "password123");
            signUp(opponent, "Bob");
            String code = createRoom(creator);

            duplicate.send("JOIN_ROOM " + code);
            assertEquals("ERROR The same account cannot occupy both seats.", duplicate.read());
            joinRoom(creator, opponent, code);
            assertEquals(2, profileCount());
        }
    }

    @Test
    void usernamesDifferingInCaseRemainSeparatePlayers() throws Exception {
        try (Peer creator = connect(); Peer opponent = connect()) {
            signUp(creator, "Alice");
            signUp(opponent, "alice");
            joinRoom(creator, opponent, createRoom(creator));
            moveAndCheckTurn(creator, opponent, "MOVE 5 0 4 1", "TURN O alice");
            moveAndCheckTurn(opponent, creator, "MOVE 2 1 3 0", "TURN X Alice");
            opponent.send("RESIGN");
            assertEquals("GAME_OVER X RESIGNATION Alice", creator.read());
            assertEquals("GAME_OVER X RESIGNATION Alice", opponent.read());
            assertEquals(2, profileCount());
            assertNotNull(createdAt("Alice"));
            assertNotNull(createdAt("alice"));
        }
    }

    @Test
    void profilesRemainAfterDisconnectAndServerRestart() throws Exception {
        String creationTime;
        try (Peer peer = connect()) {
            signUp(peer, "returning_player");
            createRoom(peer);
            creationTime = createdAt("returning_player");
            assertNotNull(creationTime);
        }
        server.close();
        server = new RunningServer(databasePath);
        assertEquals(1, profileCount());

        try (Peer returning = connect()) {
            login(returning, "returning_player", "password123");
            createRoom(returning);
            assertEquals(1, profileCount());
            assertEquals(creationTime, createdAt("returning_player"));
        }
    }

    @Test
    void concurrentProfilesCanPlayInIndependentRooms() throws Exception {
        try (Peer a = connect(); Peer b = connect(); Peer c = connect(); Peer d = connect()) {
            // Send all handshakes before reading replies, exercising concurrent server saves.
            a.send("SIGNUP playerA " + encodedPassword("password123"));
            b.send("SIGNUP playerB " + encodedPassword("password123"));
            c.send("SIGNUP playerC " + encodedPassword("password123"));
            d.send("SIGNUP playerD " + encodedPassword("password123"));
            assertEquals("AUTH_OK playerA", a.read());
            assertEquals("AUTH_OK playerB", b.read());
            assertEquals("AUTH_OK playerC", c.read());
            assertEquals("AUTH_OK playerD", d.read());
            a.username = "playerA";
            b.username = "playerB";
            c.username = "playerC";
            d.username = "playerD";
            String firstCode = createRoom(a);
            String secondCode = createRoom(c);
            assertNotEquals(firstCode, secondCode);
            joinRoom(a, b, firstCode);
            joinRoom(c, d, secondCode);
            assertEquals(4, profileCount());
        }
    }

    @Test
    void failedAuthenticationCanRetryWithoutCreatingAnotherAccount() throws Exception {
        try (Peer owner = connect(); Peer returning = connect()) {
            signUp(owner, "Alice");
            returning.send("SIGNUP Alice " + encodedPassword("different123"));
            assertEquals("AUTH_ERROR That username already exists. Log in instead.", returning.read());
            returning.send("LOGIN Alice " + encodedPassword("wrongpassword"));
            assertEquals("AUTH_ERROR Incorrect username or password.", returning.read());
            returning.send("CREATE_ROOM");
            assertEquals("ERROR Log in first.", returning.read());
            login(returning, "Alice", "password123");
            assertEquals(1, profileCount());
            createRoom(returning);
        }
    }

    @Test
    void malformedPasswordsDoNotDisconnectTheClientOrCreateProfiles() throws Exception {
        try (Peer peer = connect()) {
            for (String request : new String[]{"SIGNUP", "LOGIN Alice", "SIGNUP Alice ***",
                    "SIGNUP Alice /w==", "SIGNUP Alice " + encodedPassword("short"),
                    "SIGNUP Alice " + encodedPassword("a".repeat(129))}) {
                peer.send(request);
                assertTrue(peer.read().startsWith("AUTH_ERROR "));
            }
            assertEquals(0, profileCount());
            String password = "مسافة كلمة سر 123";
            peer.send("SIGNUP Alice " + encodedPassword(password));
            assertEquals("AUTH_OK Alice", peer.read());
            try (Peer second = connect()) {
                login(second, "Alice", password);
            }
        }
    }

    @Test
    void profileRequestsWorkBeforeAndDuringAMatchAndReflectTheRecordedResult() throws Exception {
        try (Peer alice = connect(); Peer bob = connect()) {
            alice.send("PROFILE_REQUEST");
            assertEquals("ERROR Log in first.", alice.read());
            signUp(alice, "Alice");
            signUp(bob, "Bob");
            ProfileData emptyProfile = requestProfile(alice);
            assertEquals("Alice", emptyProfile.username());
            assertEquals(new ProfileData.Stats(0, 0, 0), emptyProfile.online());
            String code = createRoom(alice);
            assertEquals(emptyProfile, requestProfile(alice));
            joinRoom(alice, bob, code);
            assertEquals(emptyProfile, requestProfile(alice));
            alice.send("RESIGN");
            assertEquals("GAME_OVER O RESIGNATION Bob", alice.read());
            assertEquals("GAME_OVER O RESIGNATION Bob", bob.read());
            ProfileData finishedProfile = requestProfile(alice);
            assertNotEquals(emptyProfile, finishedProfile);
            assertEquals(new ProfileData.Stats(1, 0, 1), finishedProfile.online());
            assertEquals(new ProfileData.Stats(0, 0, 0), finishedProfile.bot());
            assertEquals(1, finishedProfile.history().size());
            ProfileData.HistoryEntry entry = finishedProfile.history().get(0);
            assertEquals("RESIGNATION", entry.reason());
            assertEquals("Bob", entry.opponent());
            assertEquals("LOSS", entry.outcome());
            assertEquals(new ProfileData.Stats(1, 1, 0), requestProfile(bob).online());
            assertEquals(1, gameCount());
        }
    }

    @Test
    void botResultsAreIdempotentAndAlwaysBelongToTheAuthenticatedAccount() throws Exception {
        String id = UUID.randomUUID().toString();
        String result = "BOT_RESULT " + id + " WIN 3 " + Instant.now();
        try (Peer alice = connect(); Peer bob = connect()) {
            alice.send(result);
            assertEquals("ERROR Log in first.", alice.read());
            signUp(alice, "Alice");
            signUp(bob, "Bob");
            alice.send(result);
            assertEquals("BOT_SAVED " + id, alice.read());
            alice.send(result);
            assertEquals("BOT_SAVED " + id, alice.read());
            bob.send(result);
            assertTrue(bob.read().startsWith("BOT_ERROR " + id + " "));
            assertEquals(1, gameCount());
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT first_username, mode, winner, difficulty FROM game_results WHERE id = ?")) {
                statement.setString(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("Alice", rows.getString("first_username"));
                    assertEquals("BOT", rows.getString("mode"));
                    assertEquals("X", rows.getString("winner"));
                    assertEquals(3, rows.getInt("difficulty"));
                }
            }
            ProfileData aliceProfile = requestProfile(alice);
            assertEquals(new ProfileData.Stats(1, 1, 0), aliceProfile.bot());
            assertEquals(new ProfileData.Stats(0, 0, 0), aliceProfile.online());
            assertEquals("BOT", aliceProfile.history().get(0).opponent());
            assertEquals("WIN", aliceProfile.history().get(0).outcome());
            assertTrue(requestProfile(bob).history().isEmpty());
        }
    }

    @Test
    void invalidBotResultsAreRejectedWithoutSavingAndTheSessionCanRecover() throws Exception {
        try (Peer peer = connect()) {
            signUp(peer, "Alice");
            String id = "123e4567-e89b-12d3-a456-426614174000";
            String time = Instant.now().toString();
            String[] invalid = {
                    "BOT_RESULT", "BOT_RESULT not-a-uuid WIN 1 " + time,
                    "BOT_RESULT " + id.toUpperCase() + " WIN 1 " + time,
                    "BOT_RESULT " + id + " DRAW 1 " + time,
                    "BOT_RESULT " + id + " WIN 0 " + time,
                    "BOT_RESULT " + id + " WIN 4 " + time,
                    "BOT_RESULT " + id + " WIN one " + time,
                    "BOT_RESULT " + id + " WIN 1 not-a-time",
                    "BOT_RESULT " + id + " WIN 1 " + Instant.now().plusSeconds(600),
                    "BOT_RESULT " + id + " WIN 1 " + time + " Bob"
            };
            for (String request : invalid) {
                peer.send(request);
                assertTrue(peer.read().startsWith("BOT_ERROR "), "Accepted invalid result: " + request);
            }
            assertEquals(0, gameCount());
            peer.send("BOT_RESULT " + id + " LOSS 1 " + time);
            assertEquals("BOT_SAVED " + id, peer.read());
            peer.send("BOT_RESULT " + id + " WIN 1 " + time);
            assertTrue(peer.read().startsWith("BOT_ERROR " + id + " "));
            assertEquals(1, gameCount());
        }
    }

    @Test
    void leavingAWaitingRoomDoesNotCreateACompletedGame() throws Exception {
        try (Peer peer = connect()) {
            signUp(peer, "Alice");
            createRoom(peer);
            peer.send("LEAVE_ROOM");
            assertEquals("ROOM_LEFT", peer.read());
            assertEquals(0, gameCount());
            assertTrue(requestProfile(peer).history().isEmpty());
        }
    }

    private ProfileData requestProfile(Peer peer) throws IOException {
        peer.send("PROFILE_REQUEST");
        String response = peer.read();
        assertTrue(response.startsWith("PROFILE "));
        return ProfileData.decode(response.substring("PROFILE ".length()));
    }

    private int gameCount() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM game_results");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private Peer connect() throws IOException {
        return new Peer(server.listener.getLocalPort());
    }

    private void signUp(Peer peer, String username) throws IOException {
        peer.send("SIGNUP " + username + " " + encodedPassword("password123"));
        assertEquals("AUTH_OK " + username, peer.read());
        peer.username = username;
    }

    private void login(Peer peer, String username, String password) throws IOException {
        peer.send("LOGIN " + username + " " + encodedPassword(password));
        assertEquals("AUTH_OK " + username, peer.read());
        peer.username = username;
    }

    private static String encodedPassword(String password) {
        return Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8));
    }

    private String createRoom(Peer peer) throws IOException {
        String prefix = "ROOM_CREATED ";
        peer.send("CREATE_ROOM");
        String response = peer.read();
        assertTrue(response.startsWith(prefix));
        assertEquals("PLAYER X", peer.read());
        return response.substring(prefix.length());
    }

    private void joinRoom(Peer creator, Peer opponent, String code) throws IOException {
        opponent.send("JOIN_ROOM " + code);
        assertEquals("ROOM_JOINED " + code, opponent.read());
        assertEquals("PLAYER O", opponent.read());
        assertEquals("ROOM_READY " + code, creator.read());
        assertEquals("ROOM_READY " + code, opponent.read());
        String board = creator.read();
        assertTrue(board.startsWith("BOARD "));
        assertEquals(board, opponent.read());
        assertEquals("TURN X " + creator.username, creator.read());
        assertEquals("TURN X " + creator.username, opponent.read());
    }

    private void moveAndCheckTurn(Peer mover, Peer opponent, String request, String expectedTurn) throws IOException {
        mover.send(request);
        String move = mover.read();
        assertTrue(move.startsWith("MOVE_APPLIED "));
        assertEquals(move, opponent.read());
        String board = mover.read();
        assertTrue(board.startsWith("BOARD "));
        assertEquals(board, opponent.read());
        assertEquals(expectedTurn, mover.read());
        assertEquals(expectedTurn, opponent.read());
    }

    private int profileCount() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM profiles");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private String createdAt(String username) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT created_at FROM profiles WHERE username = ?")) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static class RunningServer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread thread;
        private final AtomicReference<Exception> failure = new AtomicReference<>();
        private final Set<Thread> existingThreads = Thread.getAllStackTraces().keySet();

        RunningServer(Path databasePath) throws Exception {
            ProfileDatabase database = new ProfileDatabase(databasePath);
            listener = new ServerSocket(0);
            thread = new Thread(() -> {
                try {
                    DamkaServer.serve(listener, database);
                } catch (Exception exception) {
                    if (!listener.isClosed()) {
                        failure.set(exception);
                    }
                }
            }, "auth-profile-test-server");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() throws Exception {
            listener.close();
            thread.join(2000);
            assertFalse(thread.isAlive());
            assertNull(failure.get());
            // Wait for final result writes before JUnit removes the temporary database.
            for (Thread client : Thread.getAllStackTraces().keySet()) {
                if ("damka-client".equals(client.getName()) && !existingThreads.contains(client)) {
                    client.join(5000);
                    assertFalse(client.isAlive(), "Client worker did not finish cleanup.");
                }
            }
        }
    }

    private static class Peer implements AutoCloseable {
        private String username;
        private final Socket socket;
        private final BufferedReader input;
        private final PrintWriter output;

        Peer(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(5000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        void send(String message) {
            output.println(message);
            assertFalse(output.checkError(), "Could not send " + message);
        }

        String read() throws IOException {
            String message = input.readLine();
            assertNotNull(message, "Server closed the socket before its reply.");
            return message;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
