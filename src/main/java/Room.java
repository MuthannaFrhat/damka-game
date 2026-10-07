import java.util.HashMap;
import java.util.Random;

/** A room is kept in server memory until the server stops. */
public class Room {
    private static final HashMap<String, Room> rooms = new HashMap<>();
    private static final Random random = new Random();
    private static final String CODE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final String code;
    private boolean secondPlayerJoined = false;

    public Room(String code) {
        this.code = code;
    }

    public boolean join() {
        if (secondPlayerJoined) {
            return false;
        }
        secondPlayerJoined = true;
        return true;
    }

    public String getCode() {
        return code;
    }

    public static String joinRoom(String message) {
        if (!message.matches("JOIN_ROOM [A-Z0-9]{6}")) {
            return "ERROR Invalid room code.";
        }

        // Skip "JOIN_ROOM " to extract only the room code.
        String code = message.substring("JOIN_ROOM ".length());
        Room room = rooms.get(code);
        if (room == null) {
            return "ERROR Room not found.";
        }
        if (!room.join()) {
            return "ERROR Room is full.";
        }
        return "ROOM_JOINED " + code;
    }

    public static Room createRoom() {
        String code;
        do {
            StringBuilder candidate = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                candidate.append(CODE_CHARACTERS.charAt(random.nextInt(CODE_CHARACTERS.length())));
            }
            code = candidate.toString();
        } while (rooms.containsKey(code));

        Room room = new Room(code);
        rooms.put(code, room);
        return room;
    }
}
