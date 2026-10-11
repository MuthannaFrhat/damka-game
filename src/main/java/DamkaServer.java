import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Base64;

public class DamkaServer {
    public static void main(String[] args) throws IOException, SQLException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: DamkaServer [port]");
        }
        int port = args.length == 0 ? 5000 : Integer.parseInt(args[0]);
        ProfileDatabase profiles = new ProfileDatabase(
                Path.of(System.getProperty("damka.database.path", "data/damka.db")));
        try (ServerSocket server = new ServerSocket(port)) {
            serve(server, profiles);
        }
    }

    static void serve(ServerSocket server, ProfileDatabase profiles) throws IOException {
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
            Thread worker = new Thread(() -> handleClient(client, profiles), "damka-client");
            worker.start();
        }
    }

    private static void handleClient(Socket client, ProfileDatabase profiles) {
        Room room = null;
        String username = null;
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

                if (isAuthenticationRequest(message)) {
                    if (username != null) {
                        output.println("AUTH_ERROR You are already logged in on this connection.");
                    } else {
                        username = authenticate(message, profiles, output);
                    }
                } else if (username == null) {
                    output.println("ERROR Log in first.");
                } else if (room == null) {
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

    private static boolean isAuthenticationRequest(String message) {
        return "SIGNUP".equals(message) || message.startsWith("SIGNUP ")
                || "LOGIN".equals(message) || message.startsWith("LOGIN ");
    }

    /** Authentication fixes the identity for this socket; later requests cannot name another user. */
    private static String authenticate(String message, ProfileDatabase profiles, PrintWriter output) {
        String[] fields = message.trim().split("\\s+");
        if (fields.length != 3) {
            output.println("AUTH_ERROR Expected SIGNUP or LOGIN, username, and encoded password.");
            return null;
        }
        String requestedName = fields[1];
        if (!requestedName.matches("[\\p{L}\\p{N}_-]{1,24}")) {
            output.println("AUTH_ERROR Use 1–24 letters, numbers, underscores or hyphens for your username.");
            return null;
        }
        String password;
        try {
            byte[] bytes = Base64.getDecoder().decode(fields[2]);
            password = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            output.println("AUTH_ERROR The password encoding is invalid.");
            return null;
        }
        if (password.length() < 8 || password.length() > 128) {
            output.println("AUTH_ERROR Passwords must contain 8–128 characters.");
            return null;
        }
        try {
            boolean accepted = "SIGNUP".equals(fields[0])
                    ? profiles.signUp(requestedName, password) : profiles.login(requestedName, password);
            if (!accepted) {
                output.println("AUTH_ERROR " + ("SIGNUP".equals(fields[0])
                        ? "That username already exists. Log in instead."
                        : "Incorrect username or password."));
                return null;
            }
            output.println("AUTH_OK " + requestedName);
            return requestedName;
        } catch (SQLException exception) {
            output.println("AUTH_ERROR Could not access profiles. Please try again.");
            System.err.println("Profile database unavailable during authentication.");
            return null;
        }
    }

}
