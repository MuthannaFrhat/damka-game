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
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5000);
                    handleClient(client);
                } catch (SocketTimeoutException exception) {
                    System.out.println("Client did not send data in time. Connection closed.");
                } catch (IOException exception) {
                    System.out.println("Connection error: " + exception.getMessage());
                }
            }
        }
    }

    private static void handleClient(Socket client) throws IOException {
        BufferedReader input = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter output = new PrintWriter(
                new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true);

        String message;
        while ((message = input.readLine()) != null) {
            if ("CREATE_ROOM".equals(message)) {
                Room room = Room.createRoom();
                output.println("ROOM_CREATED " + room.getCode());
            } else if ("JOIN_ROOM".equals(message) || message.startsWith("JOIN_ROOM ")) {
                output.println(Room.joinRoom(message));
            } else {
                output.println("ERROR Unknown request. Use CREATE_ROOM or JOIN_ROOM <code>.");
            }

            // PrintWriter suppresses write exceptions, so explicitly check delivery errors.
            if (output.checkError()) {
                throw new IOException("Could not send response to client.");
            }
        }
    }
}
