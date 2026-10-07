import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class MultiplayerTest {
    static int port;
    @BeforeAll static void startServer() throws Exception {
        try (ServerSocket available = new ServerSocket(0)) { port = available.getLocalPort(); }
        Thread server = new Thread(() -> {
            try { DamkaServer.main(new String[]{String.valueOf(port), "127.0.0.1"}); }
            catch (IOException exception) { throw new RuntimeException(exception); }
        });
        server.setDaemon(true); server.start();
        for (int attempt = 0; attempt < 100; attempt++) {
            try (Socket probe = new Socket("127.0.0.1", port)) { return; }
            catch (IOException notReady) { Thread.sleep(20); }
        }
        fail("Server did not start");
    }
    static class Client implements AutoCloseable {
        Socket socket; BufferedReader input; PrintWriter output;
        Client() throws Exception {
            socket = new Socket("127.0.0.1", port); socket.setSoTimeout(4000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new PrintWriter(socket.getOutputStream(), true);
        }
        void send(String message) { output.println(message); }
        String read() throws Exception { String value = input.readLine(); assertNotNull(value); return value; }
        public void close() throws IOException { socket.close(); }
    }
    static String create(Client first) throws Exception {
        first.send("CREATE_ROOM"); String[] reply = first.read().split(" ");
        assertEquals("ROOM_CREATED", reply[0]); assertEquals("x", reply[2]);
        assertTrue(first.read().startsWith("STATE WAITING")); return reply[1];
    }
    static void join(Client first, Client second, String code) throws Exception {
        second.send("JOIN_ROOM " + code);
        assertEquals("ROOM_JOINED " + code + " o", second.read());
        assertEquals(first.read(), second.read());
    }
    @Test void invalidRequestsAndRoomCapacity() throws Exception {
        try (Client a = new Client(); Client b = new Client(); Client c = new Client()) {
            a.send("PING"); assertEquals("PONG", a.read());
            a.send("BAD"); assertTrue(a.read().startsWith("ERROR"));
            b.send("JOIN_ROOM ZZZZZZ"); assertTrue(b.read().startsWith("ERROR"));
            String code = create(a); join(a,b,code);
            c.send("JOIN_ROOM " + code); assertTrue(c.read().startsWith("ERROR"));
            a.send("CREATE_ROOM"); assertTrue(a.read().startsWith("ERROR"));
            b.send("RESIGN"); assertEquals("GAME_OVER x RESIGNED", a.read());
            assertEquals("GAME_OVER x RESIGNED", b.read());
            c.send("JOIN_ROOM " + code); assertTrue(c.read().startsWith("ERROR"));
        }
    }
    @Test void serverRejectsIllegalMovesAndSynchronizesBothBoards() throws Exception {
        try (Client a = new Client(); Client b = new Client()) {
            join(a,b,create(a));
            b.send("MOVE 2 1 3 0"); assertTrue(b.read().contains("not your turn"));
            a.send("MOVE -1 0 4 1"); assertTrue(a.read().startsWith("ERROR"));
            a.send("MOVE 5 0 3 0"); assertTrue(a.read().contains("Illegal move"));
            a.send("MOVE 5 0 4 1"); String state = a.read(); assertEquals(state,b.read());
            String[] parts = state.split(" "); assertEquals("o",parts[2]);
            assertEquals('.',parts[5].charAt(40)); assertEquals('x',parts[5].charAt(33));
            a.send("MOVE 5 2 4 3"); assertTrue(a.read().contains("not your turn"));
        }
    }
    @Test void disconnectEndsMatchAndRemovesRoom() throws Exception {
        try (Client a = new Client(); Client b = new Client(); Client c = new Client()) {
            String code = create(a); join(a,b,code); b.close();
            assertEquals("GAME_OVER x DISCONNECTED",a.read());
            c.send("JOIN_ROOM " + code); assertTrue(c.read().startsWith("ERROR"));
        }
    }
    @Test void roomsAreIndependent() throws Exception {
        try (Client a = new Client(); Client b = new Client(); Client c = new Client(); Client d = new Client()) {
            String one = create(a), two = create(c); assertNotEquals(one,two);
            join(a,b,one); join(c,d,two);
            a.send("RESIGN"); assertEquals("GAME_OVER o RESIGNED",b.read());
            c.send("MOVE 5 0 4 1"); assertEquals(c.read(),d.read());
        }
    }
    @Test void mandatoryCaptureMustContinueWithSamePiece() throws Exception {
        try (Client a = new Client(); Client b = new Client()) {
            String code = create(a);
            // Prepare a specific board in this room to test a two-jump capture.
            java.lang.reflect.Field registry = Room.class.getDeclaredField("rooms");
            registry.setAccessible(true);
            Room room;
            synchronized (Room.class) {
                room = (Room)((java.util.Map<?,?>)registry.get(null)).get(code);
            }
            java.lang.reflect.Field gameField = Room.class.getDeclaredField("game");
            gameField.setAccessible(true);
            synchronized (room) {
                Board board = ((Game)gameField.get(room)).getBoard();
                for(int row=0;row<8;row++) for(int col=0;col<8;col++) board.setPiece(row,col,null);
                board.setPiece(5,0,new Piece('x')); board.setPiece(5,4,new Piece('x'));
                board.setPiece(4,1,new Piece('o')); board.setPiece(2,3,new Piece('o'));
                board.setPiece(4,5,new Piece('o')); board.setPiece(0,7,new Piece('o'));
            }
            join(a,b,code);
            a.send("MOVE 5 4 4 3"); assertTrue(a.read().contains("Illegal move"));
            a.send("MOVE 5 0 3 2"); String state = a.read(); assertEquals(state,b.read());
            assertTrue(state.startsWith("STATE ACTIVE x 3 2"));
            a.send("MOVE 5 4 3 6"); assertTrue(a.read().contains("same piece"));
            a.send("MOVE 3 2 1 4"); state = a.read(); assertEquals(state,b.read());
            assertTrue(state.startsWith("STATE ACTIVE o -1 -1"));
        }
    }

    @Test void fullGameReachesWinnerWithCapturesAndPromotion() throws Exception {
        try (Client a = new Client(); Client b = new Client()) {
            join(a,b,create(a));
            Game model = new Game(); Random random = new Random(42);
            Position forced = null; int captures = 0; boolean promotion = false;
            for (int moveNumber = 0; moveNumber < 600; moveNumber++) {
                char turn = model.getCurrentPlayer().getSymbol();
                List<Game.Move> moves = model.getValidMoves(turn);
                if (forced != null) {
                    final Position required = forced;
                    moves.removeIf(m -> m.getFrom().getRow()!=required.getRow() || m.getFrom().getCol()!=required.getCol());
                }
                assertFalse(moves.isEmpty());
                Game.Move move = moves.get(random.nextInt(moves.size()));
                if (Math.abs(move.getFrom().getRow()-move.getTo().getRow())==2) { captures++; }
                (turn=='x'?a:b).send("MOVE " + move.getFrom().getRow()+" "+move.getFrom().getCol()+" "+move.getTo().getRow()+" "+move.getTo().getCol());
                assertTrue(model.move(move.getFrom(),move.getTo()));
                String state = a.read(); assertEquals(state,b.read()); assertTrue(state.startsWith("STATE"),state);
                String[] parts = state.split(" ");
                for (int i=0;i<64;i++) {
                    Piece piece = model.getBoard().getPiece(i/8,i%8);
                    assertEquals(piece==null?'.':piece.getSymbol(),parts[5].charAt(i));
                    if(piece!=null && piece.isKing()) { promotion = true; }
                }
                if (parts[1].equals("FINISHED")) {
                    assertEquals(a.read(),b.read()); assertNotNull(model.getWinner());
                    assertTrue(captures>0); assertTrue(promotion); return;
                }
                int row = Integer.parseInt(parts[3]), col = Integer.parseInt(parts[4]);
                forced = row<0?null:new Position(row,col);
            }
            fail("Match did not finish");
        }
    }
}
