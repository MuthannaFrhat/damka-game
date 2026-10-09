import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RoomConnectionTest {
    @Test
    void sendsInitialRequestAndCommandsInOrderAndReceivesReplies() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Events events = new Events();
            BlockingQueue<String> requests = new LinkedBlockingQueue<>();
            AtomicReference<Exception> serverFailure = new AtomicReference<>();
            CountDownLatch serverFinished = new CountDownLatch(1);
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader input = reader(socket);
                    PrintWriter output = new PrintWriter(new OutputStreamWriter(
                            socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    requests.add(input.readLine());
                    output.println("ROOM_CREATED ABC123");
                    requests.add(input.readLine());
                    requests.add(input.readLine());
                    output.println("LEFT_ROOM ABC123");
                } catch (Exception exception) {
                    serverFailure.set(exception);
                } finally {
                    serverFinished.countDown();
                }
            }, "connection-test-peer");
            peer.setDaemon(true);
            peer.start();

            try (RoomConnection connection = new RoomConnection("localhost", server.getLocalPort(), events)) {
                connection.connect("CREATE_ROOM");
                assertEquals("CONNECTED", events.next());
                assertEquals("CREATE_ROOM", requests.poll(2, TimeUnit.SECONDS));
                assertEquals("ROOM_CREATED ABC123", events.next());
                connection.send("MOVE 5 0 4 1");
                connection.send("LEAVE_ROOM");
                assertEquals("MOVE 5 0 4 1", requests.poll(2, TimeUnit.SECONDS));
                assertEquals("LEAVE_ROOM", requests.poll(2, TimeUnit.SECONDS));
                assertEquals("LEFT_ROOM ABC123", events.next());
                assertTrue(events.next().startsWith("DISCONNECTED "));
                assertSame(connection, events.source.get());
                assertFalse(connection.isConnected());
                assertEquals(1, events.disconnects.get());
                assertTrue(serverFinished.await(2, TimeUnit.SECONDS));
                assertNull(serverFailure.get());
            }
        }
    }

    @Test
    void reportsConnectionFailureOnce() throws Exception {
        int unavailablePort;
        try (ServerSocket reservation = new ServerSocket(0)) {
            unavailablePort = reservation.getLocalPort();
        }
        Events events = new Events();
        try (RoomConnection connection = new RoomConnection("localhost", unavailablePort, events)) {
            connection.connect("CREATE_ROOM");
            assertTrue(events.next().startsWith("DISCONNECTED "));
            assertEquals(1, events.disconnects.get());
            assertFalse(connection.isConnected());
        }
    }

    @Test
    void intentionalCloseUnblocksReaderWithoutReportingFailure() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Events events = new Events();
            RoomConnection connection = new RoomConnection("localhost", server.getLocalPort(), events);
            try {
                connection.connect("CREATE_ROOM");
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader input = reader(socket);
                    assertEquals("CREATE_ROOM", input.readLine());
                    assertEquals("CONNECTED", events.next());
                    connection.close();
                    connection.close();
                    assertNull(input.readLine());
                    assertFalse(connection.isConnected());
                    assertEquals(0, events.disconnects.get());
                }
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void closeBeforeStartCannotOpenALateConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Events events = new Events();
            RoomConnection connection = new RoomConnection("localhost", server.getLocalPort(), events);
            connection.close();
            connection.connect("CREATE_ROOM");
            server.setSoTimeout(100);
            assertThrows(SocketTimeoutException.class, server::accept);
            assertFalse(connection.isConnected());
            assertTrue(events.messages.isEmpty());
        }
    }

    private static BufferedReader reader(Socket socket) throws Exception {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static class Events implements RoomConnection.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final AtomicInteger disconnects = new AtomicInteger();
        final AtomicReference<RoomConnection> source = new AtomicReference<>();

        String next() throws Exception {
            String message = messages.poll(2, TimeUnit.SECONDS);
            assertNotNull(message, "Expected a connection event.");
            return message;
        }

        @Override
        public void onConnected(RoomConnection connection) {
            source.set(connection);
            messages.add("CONNECTED");
        }

        @Override
        public void onMessage(RoomConnection connection, String message) {
            source.set(connection);
            messages.add(message);
        }

        @Override
        public void onDisconnected(RoomConnection connection, String detail) {
            source.set(connection);
            disconnects.incrementAndGet();
            messages.add("DISCONNECTED " + detail);
        }
    }
}
