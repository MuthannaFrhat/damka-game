import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** TCP listener plus one worker per connected player. No JavaFX on the server. */
public class DamkaServer {
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        String address = args.length > 1 ? args[1] : "0.0.0.0";
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(address, port));
            System.out.println("Damka server listening on " + address + ":" + port);
            while (true) {
                Peer player = new Peer(listener.accept());
                Thread worker = new Thread(() -> handleClient(player), "damka-player");
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    private static void handleClient(Peer player) {
        Room room = null;
        try {
            // Heartbeats arrive every 3 seconds; a dead connection expires after 15.
            player.socket.setSoTimeout(15000);
            String message;
            while ((message = player.readLine()) != null) {
                if (message.equals("PING")) { player.send("PONG"); continue; }
                if (room == null) {
                    if (message.equals("CREATE_ROOM")) { room = Room.createRoom(player); }
                    else if (message.matches("JOIN_ROOM [A-Z0-9]{6}")) {
                        room = Room.joinRoom(message.substring(10), player);
                    } else { player.send("ERROR Use CREATE_ROOM or JOIN_ROOM code"); }
                } else if (message.startsWith("MOVE ")) { room.move(player, message.split(" +")); }
                else if (message.equals("RESIGN")) { room.leave(player, "RESIGNED"); break; }
                else { player.send("ERROR Unknown request"); }
            }
        } catch (IOException exception) {
            System.out.println("Player disconnected: " + exception.getMessage());
        } finally {
            if (room != null) { room.leave(player, "DISCONNECTED"); }
            player.close();
        }
    }

    /** Owns one connection. Room stores this object to notify its two players. */
    public static class Peer {
        private final Socket socket;
        private final BufferedReader input;
        private final PrintWriter output;
        Peer(Socket socket) throws IOException {
            this.socket = socket;
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }
        // Bound message length instead of allowing an unlimited readLine allocation.
        String readLine() throws IOException {
            StringBuilder line = new StringBuilder();
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') { return line.toString(); }
                if (value != '\r') { line.append((char) value); }
                if (line.length() > 256) { throw new IOException("Message too long"); }
            }
            return null;
        }
        synchronized void send(String message) {
            output.println(message);
            if (output.checkError()) { close(); }
        }
        void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
