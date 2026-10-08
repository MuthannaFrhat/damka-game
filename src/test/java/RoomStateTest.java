import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class RoomStateTest {
    // A real TCP socket pair, without starting the application's fixed-port server.
    static class Pair implements AutoCloseable {
        final Socket client;
        final Socket server;
        final PrintWriter output;
        final BufferedReader input;
        Pair() throws IOException {
            try (ServerSocket listener = new ServerSocket(0)) {
                client = new Socket("localhost", listener.getLocalPort());
                server = listener.accept();
            }
            client.setSoTimeout(2000); // Only the test runner bounds its wait.
            output = new PrintWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8), true);
            input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        }
        public void close() throws IOException { client.close(); server.close(); }
    }

    @Test void bothPlayersReceiveColorAndSameInitialGame() throws Exception {
        try (Pair first = new Pair(); Pair second = new Pair()) {
            Room room = Room.createRoom(first.server, first.output);
            try {
                assertEquals("ROOM_CREATED " + room.getCode(), first.input.readLine());
                assertEquals("PLAYER x", first.input.readLine());
                assertSame(room, Room.joinRoom("JOIN_ROOM " + room.getCode(), second.server, second.output));
                assertEquals("ROOM_JOINED " + room.getCode(), second.input.readLine());
                assertEquals("PLAYER o", second.input.readLine());
                assertEquals("ROOM_READY " + room.getCode(), first.input.readLine());
                assertEquals("ROOM_READY " + room.getCode(), second.input.readLine());
                String state = first.input.readLine();
                assertEquals(state, second.input.readLine());
                String expectedBoard = ".o.o.o.oo.o.o.o..o.o.o.o................x.x.x.x..x.x.x.xx.x.x.x.";
                assertEquals("STATE x " + expectedBoard, state);
            } finally { room.disconnect(); }
        }
    }

    @Test void fullRoomRejectsThirdClientAndDisconnectNotifiesBoth() throws Exception {
        try (Pair first = new Pair(); Pair second = new Pair(); Pair third = new Pair()) {
            Room room = Room.createRoom(first.server, first.output);
            try {
                first.input.readLine(); first.input.readLine();
                Room.joinRoom("JOIN_ROOM " + room.getCode(), second.server, second.output);
                second.input.readLine(); second.input.readLine(); second.input.readLine(); second.input.readLine();
                first.input.readLine(); first.input.readLine();
                assertNull(Room.joinRoom("JOIN_ROOM " + room.getCode(), third.server, third.output));
                assertEquals("ERROR Room is full.", third.input.readLine());
                room.disconnect();
                assertEquals("ROOM_CLOSED " + room.getCode(),first.input.readLine());
                assertEquals("ROOM_CLOSED " + room.getCode(),second.input.readLine());
                assertNull(Room.joinRoom("JOIN_ROOM " + room.getCode(), third.server, third.output));
                assertEquals("ERROR Room not found.", third.input.readLine());
            } finally { room.disconnect(); }
        }
    }
}
