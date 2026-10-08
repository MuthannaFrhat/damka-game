import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ServerConnectionCheck {

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            System.out.println("Usage: ServerConnectionCheck [room code]");
            return;
        }

        String request = "CREATE_ROOM";
        String responsePrefix = "ROOM_CREATED ";
        if (args.length == 1) {
            request = "JOIN_ROOM " + args[0];
            responsePrefix = "ROOM_JOINED ";
        }

        try (Socket socket = new Socket("localhost", 5000)) {

            PrintWriter output = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader input = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            output.println(request);
            String response = input.readLine();

            if (response == null) {
                throw new IOException("Server disconnected before sending a response.");
            }
            if (response.startsWith("ERROR ")) {
                System.out.println(response);
                return;
            }
            if (!response.matches(responsePrefix + "[A-Z0-9]{6}")) {
                throw new IOException("Invalid room response: " + response);
            }

            // Remove the response prefix to keep only the six-character code.
            String code = response.substring(responsePrefix.length());
            if (args.length == 1 && !code.equals(args[0])) {
                throw new IOException("Server returned a different room code.");
            }
            System.out.println(responsePrefix.trim() + ": " + code);
            System.out.println("Waiting for the other player. Press Ctrl+C to disconnect.");

            while ((response = input.readLine()) != null) {
                if (response.startsWith("PLAYER ") || response.startsWith("STATE ")) {
                    System.out.println(response);
                } else if (response.equals("ROOM_READY " + code)) {
                    System.out.println("ROOM_READY: " + code + " - both players are connected!");
                } else if (response.equals("ROOM_CLOSED " + code)) {
                    System.out.println("ROOM_CLOSED: the room has been closed.");
                    return;
                } else {
                    throw new IOException("Unexpected room message: " + response);
                }
            }
            System.out.println("Server disconnected.");
        }
    }
}
