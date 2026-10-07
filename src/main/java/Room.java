import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Random;

/** Room storage and membership are protected by the Room class lock. */
public class Room {
    private static final HashMap<String, Room> rooms = new HashMap<>();
    private static final Random random = new Random();
    private static final String CODE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final String code;
    private final Socket firstPlayer;
    private final PrintWriter firstOutput;
    private Socket secondPlayer;
    private PrintWriter secondOutput;

    private Room(String code, Socket firstPlayer, PrintWriter firstOutput) {
        this.code = code;
        this.firstPlayer = firstPlayer;
        this.firstOutput = firstOutput;
    }

    public String getCode() {
        return code;
    }

    public static synchronized Room createRoom(Socket player, PrintWriter output) {
        String code;
        do {
            StringBuilder candidate = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                candidate.append(CODE_CHARACTERS.charAt(random.nextInt(CODE_CHARACTERS.length())));
            }
            code = candidate.toString();
        } while (rooms.containsKey(code));

        Room room = new Room(code, player, output);
        rooms.put(code, room);
        output.println("ROOM_CREATED " + code);
        return room;
    }

    public static synchronized Room joinRoom(String message, Socket player, PrintWriter output) {
        if (!message.matches("JOIN_ROOM [A-Z0-9]{6}")) {
            output.println("ERROR Invalid room code.");
            return null;
        }
        String code = message.substring("JOIN_ROOM ".length());
        Room room = rooms.get(code);
        if (room == null) {
            output.println("ERROR Room not found.");
            return null;
        }
        if (room.secondPlayer != null) {
            output.println("ERROR Room is full.");
            return null;
        }

        room.secondPlayer = player;
        room.secondOutput = output;
        // Acknowledge joining before notifying either player that the room is ready.
        output.println("ROOM_JOINED " + code);
        room.firstOutput.println("ROOM_READY " + code);
        output.println("ROOM_READY " + code);
        if (room.firstOutput.checkError() || output.checkError()) {
            room.disconnect();
        }
        return room;
    }

    public void disconnect() {
        synchronized (Room.class) {
            // Both workers may detect the same closed room; clean it up only once.
            if (rooms.get(code) != this) {
                return;
            }
            rooms.remove(code);
            firstOutput.println("ROOM_CLOSED " + code);
            if (secondOutput != null) {
                secondOutput.println("ROOM_CLOSED " + code);
            }
            closeSocket(firstPlayer);
            if (secondPlayer != null) {
                closeSocket(secondPlayer);
            }
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException exception) {
            System.out.println("Could not close player connection: " + exception.getMessage());
        }
    }
}
