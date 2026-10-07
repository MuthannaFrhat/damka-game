import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One authoritative game. Each synchronized method finishes before another move begins. */
public class Room {
    private static final Map<String, Room> rooms = new HashMap<>();
    private final String code;
    private final DamkaServer.Peer first;
    private DamkaServer.Peer second;
    private final Game game = new Game();
    private Position forcedCapture;
    private boolean finished;

    private Room(String code, DamkaServer.Peer first) {
        this.code = code;
        this.first = first;
    }

    public static synchronized Room createRoom(DamkaServer.Peer player) {
        String code;
        do { code = UUID.randomUUID().toString().substring(0, 6).toUpperCase(); }
        while (rooms.containsKey(code));
        Room room = new Room(code, player);
        rooms.put(code, room);
        player.send("ROOM_CREATED " + code + " x");
        room.sendState();
        return room;
    }

    public static Room joinRoom(String code, DamkaServer.Peer player) {
        Room room;
        synchronized (Room.class) { room = rooms.get(code); }
        if (room == null) { player.send("ERROR Room not found"); return null; }
        synchronized (room) {
            if (room.finished || room.second != null) {
                player.send("ERROR Room unavailable"); return null;
            }
            room.second = player;
            player.send("ROOM_JOINED " + code + " o");
            room.sendState();
            return room;
        }
    }

    public synchronized void move(DamkaServer.Peer player, String[] parts) {
        if (finished || second == null) { player.send("ERROR Match is not active"); return; }
        char symbol = player == first ? 'x' : 'o';
        if (game.getCurrentPlayer().getSymbol() != symbol) {
            player.send("ERROR It is not your turn"); return;
        }
        if (parts.length != 5) { player.send("ERROR Use MOVE fromRow fromCol toRow toCol"); return; }
        try {
            int fr = Integer.parseInt(parts[1]), fc = Integer.parseInt(parts[2]);
            int tr = Integer.parseInt(parts[3]), tc = Integer.parseInt(parts[4]);
            if (!game.getBoard().isInsideBoard(fr, fc) || !game.getBoard().isInsideBoard(tr, tc)) {
                player.send("ERROR Coordinates must be between 0 and 7"); return;
            }
            if (forcedCapture != null && (fr != forcedCapture.getRow() || fc != forcedCapture.getCol())) {
                player.send("ERROR Continue capturing with the same piece"); return;
            }
            if (!game.move(new Position(fr, fc), new Position(tr, tc))) {
                player.send("ERROR Illegal move"); return;
            }
            forcedCapture = game.getCurrentPlayer().getSymbol() == symbol ? new Position(tr, tc) : null;
            String winner = game.getWinner();
            if (winner != null) {
                finished = true;
                sendState();
                end(winner.contains("Player X") ? 'x' : 'o', "NO_MOVES");
            } else { sendState(); }
        } catch (NumberFormatException exception) { player.send("ERROR Coordinates must be numbers"); }
    }

    private void sendState() {
        StringBuilder board = new StringBuilder();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Piece piece = game.getBoard().getPiece(row, col);
                board.append(piece == null ? '.' : piece.getSymbol());
            }
        }
        String state = "STATE " + (finished ? "FINISHED" : second == null ? "WAITING" : "ACTIVE")
                + " " + game.getCurrentPlayer().getSymbol()
                + " " + (forcedCapture == null ? -1 : forcedCapture.getRow())
                + " " + (forcedCapture == null ? -1 : forcedCapture.getCol()) + " " + board;
        first.send(state);
        if (second != null) { second.send(state); }
    }

    public synchronized void leave(DamkaServer.Peer player, String reason) {
        if (finished) { return; }
        finished = true;
        end(second == null ? '-' : player == first ? 'o' : 'x', reason);
    }

    private void end(char winner, String reason) {
        synchronized (Room.class) { rooms.remove(code); }
        first.send("GAME_OVER " + winner + " " + reason);
        if (second != null) { second.send("GAME_OVER " + winner + " " + reason); }
        first.close();
        if (second != null) { second.close(); }
    }
}
