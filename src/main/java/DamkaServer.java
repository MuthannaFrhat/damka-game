import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class DamkaServer {
    public static void main(String[] args) throws IOException {
        try (ServerSocket server = new ServerSocket(5000)) {
            System.out.println("Server listening on port 5000. Press Ctrl+C to stop.");
            while (true) {
                // Wait for a new connection. This does not read the player's message.
                Socket client = server.accept();
                // Each player gets a separate worker, so waiting does not block new clients.
                // The lambda describes the task; start() begins executing it.
                Thread worker = new Thread(() -> handleClient(client), "damka-client");
                worker.start();
            }
        }
    }

    private static void handleClient(Socket client) {
        // This variable belongs to this player's worker; null means no room yet.
        Room room = null;
        // connection is the SAME socket as client. try closes it when this block ends.
        try (Socket connection = client) {
            // Configure network reads to time out after five seconds without data.
            // The waiting happens in readLine(), not in setSoTimeout().
            connection.setSoTimeout(5000);
            // Read UTF-8 text from this socket, one line at a time.
            BufferedReader input = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            // Write text back to this player; true flushes each println() immediately.
            PrintWriter output = new PrintWriter(
                    new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8), true);

            String message;
            // Phase 1: read requests until creation or joining returns a Room.
            // A rejected request leaves room null, so the player can try again.
            // readLine() returns null when the peer closes its output normally.
            while (room == null && (message = input.readLine()) != null) {
                if ("CREATE_ROOM".equals(message)) {
                    room = Room.createRoom(connection, output);
                } else if ("JOIN_ROOM".equals(message) || message.startsWith("JOIN_ROOM ")) {
                    room = Room.joinRoom(message, connection, output);
                } else {
                    output.println("ERROR Unknown request. Use CREATE_ROOM or JOIN_ROOM <code>.");
                }
                // PrintWriter records write errors instead of throwing them.
                if (output.checkError()) {
                    throw new IOException("Could not send response to client.");
                }
            }

            if (room == null) {
                return; // The client disconnected before entering a room.
            }

            // Phase 2: the player is in a room. Zero means no read timeout.
            // Another player's worker sends ROOM_READY through the stored output.
            // This worker waits for input or disconnection; gameplay is not implemented yet.
            connection.setSoTimeout(0);
            while (input.readLine() != null) {
                // Any further request is currently rejected; the connection stays open.
                output.println("ERROR You are already in a room.");
                // PrintWriter records write errors instead of throwing them.
                if (output.checkError()) {
                    throw new IOException("Could not send response to client.");
                }
            }
        } catch (SocketTimeoutException exception) {
            System.out.println("Client did not send a request in time. Connection closed.");
        } catch (IOException exception) {
            System.out.println("Connection closed: " + exception.getMessage());
        } finally {
            // Always clean up membership after a normal disconnect or an error.
            if (room != null) {
                room.disconnect();
            }
        }
    }
}
