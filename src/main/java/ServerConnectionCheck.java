import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

public class ServerConnectionCheck {

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3
                || !("signup".equalsIgnoreCase(args[0]) || "login".equalsIgnoreCase(args[0]))) {
            System.out.println("Usage: ServerConnectionCheck <signup|login> <username> [room code]");
            return;
        }
        Console console = System.console();
        char[] password;
        if (console != null) {
            password = console.readPassword("Password: ");
        } else {
            System.out.print("Password: ");
            String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            password = line == null ? null : line.toCharArray();
        }
        if (password == null) {
            return;
        }
        String encodedPassword = Base64.getEncoder().encodeToString(
                new String(password).getBytes(StandardCharsets.UTF_8));
        Arrays.fill(password, '\0');

        String request = "CREATE_ROOM";
        String responsePrefix = "ROOM_CREATED ";
        String roomCode = args.length == 3 ? args[2].trim().toUpperCase(Locale.ROOT) : null;
        if (roomCode != null) {
            request = "JOIN_ROOM " + roomCode;
            responsePrefix = "ROOM_JOINED ";
        }

        String host = System.getProperty("damka.server.host", "localhost");
        int port = Integer.parseInt(System.getProperty("damka.server.port", "5000"));
        try (Socket socket = new Socket(host, port)) {

            PrintWriter output = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader input = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            output.println(args[0].toUpperCase(Locale.ROOT) + " " + args[1] + " " + encodedPassword);
            String profileResponse = input.readLine();
            if (profileResponse == null) {
                throw new IOException("Server disconnected during authentication.");
            }
            if (!profileResponse.equals("AUTH_OK " + args[1])) {
                System.out.println(profileResponse);
                return;
            }

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
            if (roomCode != null && !code.equals(roomCode)) {
                throw new IOException("Server returned a different room code.");
            }
            System.out.println(responsePrefix.trim() + ": " + code);
            System.out.println("Waiting for the other player. Press Ctrl+C to disconnect.");

            while ((response = input.readLine()) != null) {
                // Print the current protocol, including PLAYER, BOARD, and TURN messages.
                System.out.println(response);
            }
            System.out.println("Server disconnected.");
        }
    }
}
