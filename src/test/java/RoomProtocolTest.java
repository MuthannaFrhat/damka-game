import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomProtocolTest {
    @TempDir
    Path temporaryDirectory;
    private ServerSocket listener;
    private Thread serverThread;
    private int port;
    private Path databasePath;
    private Set<Thread> existingThreads;
    private final AtomicInteger nextUsername = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        existingThreads = Thread.getAllStackTraces().keySet();
        databasePath = temporaryDirectory.resolve("profiles.db");
        ProfileDatabase database = new ProfileDatabase(databasePath);
        listener = new ServerSocket(0);
        port = listener.getLocalPort();
        serverThread = new Thread(() -> {
            try {
                DamkaServer.serve(listener, database);
            } catch (IOException exception) {
                if (!listener.isClosed()) {
                    throw new RuntimeException(exception);
                }
            }
        }, "room-protocol-test-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        listener.close();
        serverThread.join(2000);
        assertFalse(serverThread.isAlive());
        // Client disconnects can still be saving SQLite results after the listener stops.
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("damka-client".equals(thread.getName()) && !existingThreads.contains(thread)) {
                thread.join(5000);
                assertFalse(thread.isAlive(), "Client worker did not finish cleanup.");
            }
        }
    }

    @Test
    void validatesMovesAndBroadcastsTheSameState() throws Exception {
        try (Match match = openMatch()) {
            match.o.send("MOVE 2 1 3 0");
            assertEquals("ERROR It is not your turn.", match.o.read());

            match.x.send("MOVE 5 0 5 1");
            assertEquals("ERROR Invalid move.", match.x.read());

            String board = acceptedMove(match, 'X', "MOVE 5 0 4 1", 'O');
            assertEquals('.', board.charAt(40));
            assertEquals('x', board.charAt(33));

            board = acceptedMove(match, 'O', "MOVE 2 1 3 0", 'X');
            assertEquals('.', board.charAt(17));
            assertEquals('o', board.charAt(24));
        }
    }

    @Test
    void rejectsMalformedMovesAndKeepsTheGameUsable() throws Exception {
        try (Match match = openMatch()) {
            match.x.send("MOVE 5 0 4");
            assertEquals("ERROR Expected MOVE <fromRow> <fromCol> <toRow> <toCol>.", match.x.read());
            match.x.send("MOVE 5 0 8 1");
            assertEquals("ERROR Coordinates must be between 0 and 7.", match.x.read());
            match.x.send("MOVE 5 0 -1 1");
            assertEquals("ERROR Coordinates must be between 0 and 7.", match.x.read());
            match.x.send("MOVE 5 0 four 1");
            assertEquals("ERROR Coordinates must be integers.", match.x.read());

            // Rejected requests must leave X's turn and initial pieces intact.
            String board = acceptedMove(match, 'X', "MOVE 5 0 4 1", 'O');
            assertEquals('.', board.charAt(40));
            assertEquals('x', board.charAt(33));
        }
    }

    @Test
    void competingJoinRequestsAdmitOnlyOneSecondPlayer() throws Exception {
        try (Peer creator = connect(); Peer candidateA = connect(); Peer candidateB = connect()) {
            creator.send("CREATE_ROOM");
            String code = creator.read().substring("ROOM_CREATED ".length());
            assertEquals("PLAYER X", creator.read());

            // Send both requests before reading either result. Separate server
            // threads compete for the same room's second-player slot.
            candidateA.send("JOIN_ROOM " + code);
            candidateB.send("JOIN_ROOM " + code);
            String replyA = candidateA.read();
            String replyB = candidateB.read();
            Peer joined = replyA.startsWith("ROOM_JOINED ") ? candidateA : candidateB;
            Peer rejected = joined == candidateA ? candidateB : candidateA;
            assertEquals("ROOM_JOINED " + code, joined == candidateA ? replyA : replyB);
            assertEquals("ERROR Room is full.", joined == candidateA ? replyB : replyA);
            assertEquals("PLAYER O", joined.read());
            assertEquals("ROOM_READY " + code, joined.read());
            assertEquals("ROOM_READY " + code, creator.read());
            assertEquals(creator.read(), joined.read());
            assertEquals("TURN X " + creator.username, creator.read());
            assertEquals("TURN X " + creator.username, joined.read());

            // Rejection does not accidentally assign the third client to a room.
            rejected.send("CREATE_ROOM");
            assertTrue(rejected.read().startsWith("ROOM_CREATED "));
            assertEquals("PLAYER X", rejected.read());
        }
    }

    @Test
    void preservesForcedCaptureAndSamePieceMultiCapture() throws Exception {
        try (Match match = openMatch()) {
            acceptedMove(match, 'X', "MOVE 5 0 4 1", 'O');
            acceptedMove(match, 'O', "MOVE 2 3 3 4", 'X');
            acceptedMove(match, 'X', "MOVE 5 4 4 5", 'O');
            acceptedMove(match, 'O', "MOVE 1 2 2 3", 'X');
            acceptedMove(match, 'X', "MOVE 6 1 5 0", 'O');
            acceptedMove(match, 'O', "MOVE 3 4 4 3", 'X');

            match.x.send("MOVE 5 6 4 7");
            assertEquals("ERROR Invalid move.", match.x.read());

            match.x.send("MOVE 5 2 3 4");
            String captureMetadata = "MOVE_APPLIED 5 2 3 4 4 3";
            assertEquals(captureMetadata, match.x.read());
            assertEquals(captureMetadata, match.o.read());
            assertEquals(match.x.read(), match.o.read());
            assertEquals("TURN X " + match.x.username, match.x.read());
            assertEquals("TURN X " + match.x.username, match.o.read());

            match.x.send("MOVE 5 6 4 7");
            assertEquals("ERROR Continue capturing with the same piece.", match.x.read());

            acceptedMove(match, 'X', "MOVE 3 4 1 2", 'O');
        }
    }

    @Test
    void promotesPieceAndEndsMatchWhenOpponentHasNoLegalMove() throws Exception {
        try (Peer x = connect()) {
            x.send("CREATE_ROOM");
            String created = x.read();
            String code = created.substring("ROOM_CREATED ".length());
            assertEquals("PLAYER X", x.read());
            installPromotionPosition(code);

            try (Peer o = connect()) {
                o.send("JOIN_ROOM " + code);
                assertEquals("ROOM_JOINED " + code, o.read());
                assertEquals("PLAYER O", o.read());
                assertEquals("ROOM_READY " + code, o.read());
                assertEquals("ROOM_READY " + code, x.read());

                String board = encodePromotionPosition();
                assertEquals("BOARD " + board, o.read());
                assertEquals("TURN X " + x.username, o.read());
                assertEquals("BOARD " + board, x.read());
                assertEquals("TURN X " + x.username, x.read());

                x.send("MOVE 1 0 0 1");
                String applied = "MOVE_APPLIED 1 0 0 1 -1 -1";
                assertEquals(applied, x.read());
                assertEquals(applied, o.read());
                char[] promotedCells = board.toCharArray();
                promotedCells[1] = 'X';
                promotedCells[8] = '.';
                String promotedBoard = new String(promotedCells);
                assertEquals("BOARD " + promotedBoard, x.read());
                assertEquals("BOARD " + promotedBoard, o.read());
                assertEquals("TURN O " + o.username, x.read());
                assertEquals("TURN O " + o.username, o.read());
                assertEquals("GAME_OVER X RULES " + x.username, x.read());
                assertEquals("GAME_OVER X RULES " + x.username, o.read());
                assertEquals(1, resultCount("RULES"));

                o.send("MOVE 7 7 6 6");
                assertEquals("ERROR You are not in a room.", o.read());
                assertEquals(1, resultCount("RULES"));
            }
        }
    }

    @Test
    void naturalWinnerIsRecordedEvenWhenTheFinalMessageCannotBeDelivered() throws Exception {
        try (Peer x = connect(); Peer o = connect()) {
            x.send("CREATE_ROOM");
            String code = x.read().substring("ROOM_CREATED ".length());
            assertEquals("PLAYER X", x.read());
            installPromotionPosition(code);
            o.send("JOIN_ROOM " + code);
            assertEquals("ROOM_JOINED " + code, o.read());
            assertEquals("PLAYER O", o.read());
            assertEquals("ROOM_READY " + code, o.read());
            assertEquals("ROOM_READY " + code, x.read());
            assertEquals(x.read(), o.read());
            assertEquals("TURN X " + x.username, x.read());
            assertEquals("TURN X " + x.username, o.read());

            // Simulate a write failure exactly when X's final move is broadcast.
            Field outputField = Room.class.getDeclaredField("firstOutput");
            outputField.setAccessible(true);
            Field roomsField = Room.class.getDeclaredField("rooms");
            roomsField.setAccessible(true);
            Room room;
            synchronized (Room.class) {
                room = (Room) ((Map<?, ?>) roomsField.get(null)).get(code);
            }
            synchronized (room) {
                outputField.set(room, new PrintWriter(new StringWriter()) {
                    @Override
                    public boolean checkError() {
                        return true;
                    }
                });
            }
            x.send("MOVE 1 0 0 1");
            assertTrue(o.read().startsWith("MOVE_APPLIED "));
            assertTrue(o.read().startsWith("BOARD "));
            assertEquals("TURN O " + o.username, o.read());
            assertEquals("GAME_OVER X RULES " + x.username, o.read());
            assertEquals(1, resultCount("RULES"));
            assertEquals(0, resultCount("OPPONENT_DISCONNECTED"));
        }
    }

    @Test
    void databaseFailureIsReportedAfterGameOverAndStillAllowsARematch() throws Exception {
        try (Match match = openMatch()) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                 PreparedStatement statement = connection.prepareStatement("DROP TABLE game_results")) {
                statement.executeUpdate();
            }
            match.x.send("RESIGN");
            assertEquals("GAME_OVER O RESIGNATION " + match.o.username, match.x.read());
            assertEquals("GAME_OVER O RESIGNATION " + match.o.username, match.o.read());
            assertEquals("RESULT_ERROR The match ended, but its result could not be saved.", match.x.read());
            assertEquals("RESULT_ERROR The match ended, but its result could not be saved.", match.o.read());
            match.x.send("MOVE 5 0 4 1");
            assertEquals("ERROR You are not in a room.", match.x.read());
            match.x.send("CREATE_ROOM");
            assertTrue(match.x.read().startsWith("ROOM_CREATED "));
            assertEquals("PLAYER X", match.x.read());
        }
    }

    @Test
    void resignationAndLeavingAllowTheSameSocketsToRematch() throws Exception {
        try (Match first = openMatch()) {
            first.x.send("RESIGN");
            // O has not had a turn yet, but the server already knows the winner's username.
            assertEquals("GAME_OVER O RESIGNATION " + first.o.username, first.x.read());
            assertEquals("GAME_OVER O RESIGNATION " + first.o.username, first.o.read());
            assertEquals(1, resultCount("RESIGNATION"));

            // Swap seats in the rematch: usernames must follow the new X/O assignments.
            first.o.send("CREATE_ROOM");
            String nextRoom = first.o.read();
            assertTrue(nextRoom.startsWith("ROOM_CREATED "));
            String code = nextRoom.substring("ROOM_CREATED ".length());
            assertEquals("PLAYER X", first.o.read());
            first.x.send("JOIN_ROOM " + code);
            assertEquals("ROOM_JOINED " + code, first.x.read());
            assertEquals("PLAYER O", first.x.read());
            assertEquals("ROOM_READY " + code, first.o.read());
            assertEquals("ROOM_READY " + code, first.x.read());
            assertEquals(first.x.read(), first.o.read());
            assertEquals("TURN X " + first.o.username, first.x.read());
            assertEquals("TURN X " + first.o.username, first.o.read());
            acceptedMove(new Match(first.o, first.x), 'X', "MOVE 5 0 4 1", 'O');

            first.x.send("LEAVE_ROOM");
            assertEquals("ROOM_LEFT", first.x.read());
            assertEquals("GAME_OVER X OPPONENT_LEFT " + first.o.username, first.o.read());
            assertEquals(1, resultCount("OPPONENT_LEFT"));

            first.x.send("CREATE_ROOM");
            String thirdRoom = first.x.read();
            assertTrue(thirdRoom.startsWith("ROOM_CREATED "));
            assertEquals("PLAYER X", first.x.read());
            assertEquals(2, resultCount(null));

            // Now the creator leaves: the second player's username must be announced instead.
            String thirdCode = thirdRoom.substring("ROOM_CREATED ".length());
            first.o.send("JOIN_ROOM " + thirdCode);
            assertEquals("ROOM_JOINED " + thirdCode, first.o.read());
            assertEquals("PLAYER O", first.o.read());
            assertEquals("ROOM_READY " + thirdCode, first.x.read());
            assertEquals("ROOM_READY " + thirdCode, first.o.read());
            assertEquals(first.x.read(), first.o.read());
            assertEquals("TURN X " + first.x.username, first.x.read());
            assertEquals("TURN X " + first.x.username, first.o.read());
            first.x.send("LEAVE_ROOM");
            assertEquals("ROOM_LEFT", first.x.read());
            assertEquals("GAME_OVER O OPPONENT_LEFT " + first.o.username, first.o.read());
            assertEquals(3, resultCount(null));
        }
    }

    @Test
    void opponentDisconnectProducesAResultAndLeavesTheOtherSocketUsable() throws Exception {
        try (Match match = openMatch()) {
            match.o.close();
            assertEquals("GAME_OVER X OPPONENT_DISCONNECTED " + match.x.username, match.x.read());
            assertEquals(1, resultCount("OPPONENT_DISCONNECTED"));

            match.x.send("CREATE_ROOM");
            assertTrue(match.x.read().startsWith("ROOM_CREATED "));
            assertEquals("PLAYER X", match.x.read());
        }
        try (Match match = openMatch()) {
            match.x.close();
            assertEquals("GAME_OVER O OPPONENT_DISCONNECTED " + match.o.username, match.o.read());
            assertEquals(2, resultCount("OPPONENT_DISCONNECTED"));
            match.o.send("CREATE_ROOM");
            assertTrue(match.o.read().startsWith("ROOM_CREATED "));
            assertEquals("PLAYER X", match.o.read());
        }
    }

    @Test
    void botStillProducesAndAppliesLegalMoves() {
        Game game = new Game(false, true);
        BotPlayer bot = new BotPlayer();
        Game.Move xMove = bot.findBestMove(game, 1, 'x');
        assertNotNull(xMove);
        assertTrue(game.move(xMove.getFrom(), xMove.getTo()));

        Game.Move oMove = bot.findBestMove(game, 1, 'o');
        assertNotNull(oMove);
        assertTrue(game.move(oMove.getFrom(), oMove.getTo()));
    }

    @Test
    void botKeepsTheSamePieceDuringMultiCapture() throws Exception {
        Game game = new Game();
        Field boardField = Board.class.getDeclaredField("board");
        boardField.setAccessible(true);
        Piece[][] cells = (Piece[][]) boardField.get(game.getBoard());
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                cells[row][col] = null;
            }
        }
        cells[5][0] = new Piece('x');
        cells[5][2] = new Piece('x');
        cells[4][1] = new Piece('o');
        cells[4][3] = new Piece('o');
        cells[2][5] = new Piece('o');

        Position firstFrom = new Position(5, 2);
        Position firstTo = new Position(3, 4);
        assertTrue(game.move(firstFrom, firstTo));
        assertEquals('x', game.getCurrentPlayer().getSymbol());

        Game.Move continuation = new BotPlayer().findBestMove(game, 1, 'x');
        assertNotNull(continuation);
        assertEquals(firstTo.getRow(), continuation.getFrom().getRow());
        assertEquals(firstTo.getCol(), continuation.getFrom().getCol());
    }

    private String acceptedMove(Match match, char player, String request, char nextPlayer) throws IOException {
        Peer mover = player == 'X' ? match.x : match.o;
        Peer other = player == 'X' ? match.o : match.x;
        mover.send(request);

        String metadata = mover.read();
        assertTrue(metadata.startsWith("MOVE_APPLIED "));
        assertEquals(metadata, other.read());
        String moverBoard = mover.read();
        assertTrue(moverBoard.startsWith("BOARD "));
        assertEquals(moverBoard, other.read());
        String turn = "TURN " + nextPlayer + " " + (nextPlayer == 'X' ? match.x : match.o).username;
        assertEquals(turn, mover.read());
        assertEquals(turn, other.read());
        return moverBoard.substring("BOARD ".length());
    }

    private Match openMatch() throws Exception {
        Peer x = connect();
        Peer o = null;
        try {
            x.send("CREATE_ROOM");
            String created = x.read();
            assertTrue(created.matches("ROOM_CREATED [A-Z0-9]{6}"));
            String code = created.substring("ROOM_CREATED ".length());
            assertEquals("PLAYER X", x.read());

            o = connect();
            o.send("JOIN_ROOM " + code);
            assertEquals("ROOM_JOINED " + code, o.read());
            assertEquals("PLAYER O", o.read());
            assertEquals("ROOM_READY " + code, o.read());
            assertEquals("ROOM_READY " + code, x.read());
            String board = x.read();
            assertTrue(board.startsWith("BOARD "));
            assertEquals(board, o.read());
            assertEquals("TURN X " + x.username, x.read());
            assertEquals("TURN X " + x.username, o.read());
            return new Match(x, o);
        } catch (Exception exception) {
            x.close();
            if (o != null) {
                o.close();
            }
            throw exception;
        }
    }

    private Peer connect() throws IOException {
        String username = "testplayer" + nextUsername.incrementAndGet();
        Peer peer = new Peer(port, username);
        try {
            String encodedPassword = Base64.getEncoder().encodeToString(
                    "password123".getBytes(StandardCharsets.UTF_8));
            peer.send("SIGNUP " + username + " " + encodedPassword);
            assertEquals("AUTH_OK " + username, peer.read());
            return peer;
        } catch (IOException | AssertionError exception) {
            peer.close();
            throw exception;
        }
    }

    private int resultCount(String reason) throws Exception {
        String sql = "SELECT COUNT(*) FROM game_results WHERE mode = 'ONLINE'"
                + (reason == null ? "" : " AND reason = ?");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (reason != null) {
                statement.setString(1, reason);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private void installPromotionPosition(String code) throws Exception {
        Field roomsField = Room.class.getDeclaredField("rooms");
        roomsField.setAccessible(true);
        Room room;
        synchronized (Room.class) {
            room = (Room) ((Map<?, ?>) roomsField.get(null)).get(code);
        }
        assertNotNull(room);

        Field gameField = Room.class.getDeclaredField("game");
        gameField.setAccessible(true);
        Game game = (Game) gameField.get(room);
        Field boardField = Board.class.getDeclaredField("board");
        boardField.setAccessible(true);
        Piece[][] cells = (Piece[][]) boardField.get(game.getBoard());
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                cells[row][col] = null;
            }
        }
        cells[1][0] = new Piece('x');
        cells[7][7] = new Piece('o');
    }

    private String encodePromotionPosition() {
        StringBuilder encoded = new StringBuilder(64);
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                if (row == 1 && col == 0) {
                    encoded.append('x');
                } else if (row == 7 && col == 7) {
                    encoded.append('o');
                } else {
                    encoded.append('.');
                }
            }
        }
        return encoded.toString();
    }

    private static class Match implements AutoCloseable {
        final Peer x;
        final Peer o;

        Match(Peer x, Peer o) {
            this.x = x;
            this.o = o;
        }

        @Override
        public void close() throws IOException {
            x.close();
            o.close();
        }
    }

    private static class Peer implements AutoCloseable {
        final String username;
        final Socket socket;
        final BufferedReader input;
        final PrintWriter output;

        Peer(int port, String username) throws IOException {
            this.username = username;
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        void send(String message) {
            output.println(message);
            if (output.checkError()) {
                throw new IllegalStateException("Could not send protocol message: " + message);
            }
        }

        String read() throws IOException {
            String message = input.readLine();
            if (message == null) {
                throw new IOException("Server disconnected before sending a response.");
            }
            return message;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
