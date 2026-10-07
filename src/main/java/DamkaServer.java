import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class DamkaServer {
    public static void main(String[] args) throws IOException {
        try (ServerSocket server = new ServerSocket(5000)) {
            System.out.println("Server listening on port 5000. Press Ctrl+C to stop.");
            while (true) {
                Socket client = server.accept();
                Thread worker = new Thread(() -> handleClient(client), "damka-client");
                worker.start();
            }
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
                if (room != null) {
                    output.println("ERROR You are already in a room.");
                } else if ("CREATE_ROOM".equals(message)) {
                    room = Room.createRoom(connection, output);
                } else if ("JOIN_ROOM".equals(message) || message.startsWith("JOIN_ROOM ")) {
                    room = Room.joinRoom(message, connection, output);
                } else {
                    output.println("ERROR Unknown request. Use CREATE_ROOM or JOIN_ROOM <code>.");
                }
                if (output.checkError()) {
                    throw new IOException("Could not send response to client.");
                }
            }
        } catch (IOException exception) {
            System.out.println("Connection closed: " + exception.getMessage());
        } finally {
            if (room != null) {
                room.disconnect();
            }
        }
    }
}
