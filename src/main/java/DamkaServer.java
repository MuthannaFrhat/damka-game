import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

public class DamkaServer {
    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: DamkaServer [port]");
        }
        int port = args.length == 0 ? 5000 : Integer.parseInt(args[0]);
        try (ServerSocket server = new ServerSocket(port)) {
            serve(server);
        }
    }

    static void serve(ServerSocket server) throws IOException {
        System.out.println("Server listening on port " + server.getLocalPort() + ". Press Ctrl+C to stop.");
        while (!server.isClosed()) {
            Socket client;
            try {
                // accept() waits here until a client connects.
                client = server.accept();
            } catch (SocketException exception) {
                if (server.isClosed()) {
                    return;
                }
                throw exception;
            }

            // One reader per client; the listener can accept the next player.
            Thread worker = new Thread(() -> handleClient(client), "damka-client");
            worker.start();
        }
    }

    private static void handleClient(Socket client) {
        Room room = null;
        try (Socket connection = client) {
            BufferedReader input = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter output = new PrintWriter(
                    new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8), true);

            String message;
            while ((message = input.readLine()) != null) {
                // A finished room leaves the TCP connection available for a rematch.
                if (room != null && room.isClosed()) {
                    room = null;
                }

                if (room == null) {
                    if ("CREATE_ROOM".equals(message)) {
                        room = Room.createRoom(connection, output);
                    } else if ("JOIN_ROOM".equals(message) || message.startsWith("JOIN_ROOM ")) {
                        room = Room.joinRoom(message, connection, output);
                    } else if (message.startsWith("MOVE")) {
                        output.println("ERROR You are not in a room.");
                    } else if ("RESIGN".equals(message) || "LEAVE_ROOM".equals(message)) {
                        output.println("ERROR You are not in a room.");
                    } else {
                        output.println("ERROR Unknown request. Use CREATE_ROOM or JOIN_ROOM <code>.");
                    }
                } else if (message.startsWith("MOVE")) {
                    room.handleMove(connection, message);
                } else if ("RESIGN".equals(message)) {
                    if (room.resign(connection)) {
                        room = null;
                    }
                } else if ("LEAVE_ROOM".equals(message)) {
                    room.leave(connection);
                    room = null;
                } else {
                    output.println("ERROR You are already in a room.");
                }
                if (output.checkError()) {
                    throw new IOException("Could not send response to client.");
                }
            }
        } catch (IOException exception) {
            System.out.println("Connection closed: " + exception.getMessage());
        } finally {
            // try-with-resources closes this socket; the room notifies its opponent.
            if (room != null) {
                room.playerDisconnected(client);
            }
        }
    }
}
