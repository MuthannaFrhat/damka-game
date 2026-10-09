import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomProtocolTest {
    private ServerSocket listener;
    private Thread serverThread;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        listener = new ServerSocket(0);
        port = listener.getLocalPort();
        serverThread = new Thread(() -> {
            try {
                DamkaServer.serve(listener);
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
    }

    @Test
    void validatesMovesAndBroadcastsTheSameState() throws Exception {
        try (Match match = openMatch()) {
            match.o.send("MOVE 2 1 3 0");
            assertEquals("ERROR It is not your turn.", match.o.read());

            match.x.send("MOVE 5 0 5 1");
            assertEquals("ERROR Invalid move.", match.x.read());

            String board = acceptedMove(match, 'X', "MOVE 5 0 4 1", "TURN O");
            assertEquals('.', board.charAt(40));
            assertEquals('x', board.charAt(33));

            board = acceptedMove(match, 'O', "MOVE 2 1 3 0", "TURN X");
            assertEquals('.', board.charAt(17));
            assertEquals('o', board.charAt(24));
        }
    }

    @Test
    void preservesForcedCaptureAndSamePieceMultiCapture() throws Exception {
        try (Match match = openMatch()) {
            acceptedMove(match, 'X', "MOVE 5 0 4 1", "TURN O");
            acceptedMove(match, 'O', "MOVE 2 3 3 4", "TURN X");
            acceptedMove(match, 'X', "MOVE 5 4 4 5", "TURN O");
            acceptedMove(match, 'O', "MOVE 1 2 2 3", "TURN X");
            acceptedMove(match, 'X', "MOVE 6 1 5 0", "TURN O");
            acceptedMove(match, 'O', "MOVE 3 4 4 3", "TURN X");

            match.x.send("MOVE 5 6 4 7");
            assertEquals("ERROR Invalid move.", match.x.read());

            match.x.send("MOVE 5 2 3 4");
            String captureMetadata = "MOVE_APPLIED 5 2 3 4 4 3";
            assertEquals(captureMetadata, match.x.read());
            assertEquals(captureMetadata, match.o.read());
            assertEquals(match.x.read(), match.o.read());
            assertEquals("TURN X", match.x.read());
            assertEquals("TURN X", match.o.read());

            match.x.send("MOVE 5 6 4 7");
            assertEquals("ERROR Continue capturing with the same piece.", match.x.read());

            acceptedMove(match, 'X', "MOVE 3 4 1 2", "TURN O");
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
                assertEquals("TURN X", o.read());
                assertEquals("BOARD " + board, x.read());
                assertEquals("TURN X", x.read());

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
                assertEquals("TURN O", x.read());
                assertEquals("TURN O", o.read());
                assertEquals("GAME_OVER X RULES", x.read());
                assertEquals("GAME_OVER X RULES", o.read());

                o.send("MOVE 7 7 6 6");
                assertEquals("ERROR You are not in a room.", o.read());
            }
        }
    }

    @Test
    void resignationAndLeavingAllowTheSameSocketsToRematch() throws Exception {
        try (Match first = openMatch()) {
            first.x.send("RESIGN");
            assertEquals("GAME_OVER O RESIGNATION", first.x.read());
            assertEquals("GAME_OVER O RESIGNATION", first.o.read());

            first.x.send("CREATE_ROOM");
            String nextRoom = first.x.read();
            assertTrue(nextRoom.startsWith("ROOM_CREATED "));
            String code = nextRoom.substring("ROOM_CREATED ".length());
            assertEquals("PLAYER X", first.x.read());
            first.o.send("JOIN_ROOM " + code);
            assertEquals("ROOM_JOINED " + code, first.o.read());
            assertEquals("PLAYER O", first.o.read());
            assertEquals("ROOM_READY " + code, first.o.read());
            assertEquals("ROOM_READY " + code, first.x.read());
            assertEquals(first.x.read(), first.o.read());
            assertEquals("TURN X", first.x.read());
            assertEquals("TURN X", first.o.read());

            first.o.send("LEAVE_ROOM");
            assertEquals("ROOM_LEFT", first.o.read());
            assertEquals("GAME_OVER X OPPONENT_LEFT", first.x.read());

            first.x.send("CREATE_ROOM");
            String thirdRoom = first.x.read();
            assertTrue(thirdRoom.startsWith("ROOM_CREATED "));
            assertEquals("PLAYER X", first.x.read());
        }
    }

    @Test
    void opponentDisconnectProducesAResultAndLeavesTheOtherSocketUsable() throws Exception {
        try (Match match = openMatch()) {
            match.o.close();
            assertEquals("GAME_OVER X OPPONENT_DISCONNECTED", match.x.read());

            match.x.send("CREATE_ROOM");
            assertTrue(match.x.read().startsWith("ROOM_CREATED "));
            assertEquals("PLAYER X", match.x.read());
        }
    }

    @Test
    void botStillProducesAndAppliesLegalMoves() {
        Game game = new Game(false, true);
        BotPlayer bot = new BotPlayer();
        BotPlayer.MoveChoice xMove = bot.findBestMove(game, 1, 'x');
        assertNotNull(xMove);
        assertTrue(game.move(xMove.getFrom(), xMove.getTo()));

        BotPlayer.MoveChoice oMove = bot.findBestMove(game, 1, 'o');
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

        BotPlayer.MoveChoice continuation = new BotPlayer().findBestMove(
                game, 1, 'x', firstTo);
        assertNotNull(continuation);
        assertEquals(firstTo.getRow(), continuation.getFrom().getRow());
        assertEquals(firstTo.getCol(), continuation.getFrom().getCol());
    }

    private String acceptedMove(Match match, char player, String request, String turn) throws IOException {
        Peer mover = player == 'X' ? match.x : match.o;
        Peer other = player == 'X' ? match.o : match.x;
        mover.send(request);

        String metadata = mover.read();
        assertTrue(metadata.startsWith("MOVE_APPLIED "));
        assertEquals(metadata, other.read());
        String moverBoard = mover.read();
        assertTrue(moverBoard.startsWith("BOARD "));
        assertEquals(moverBoard, other.read());
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
            assertEquals("TURN X", x.read());
            assertEquals("TURN X", o.read());
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
        return new Peer(port);
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
        final Socket socket;
        final BufferedReader input;
        final PrintWriter output;

        Peer(int port) throws IOException {
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
