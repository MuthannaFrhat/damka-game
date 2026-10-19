import java.io.PrintWriter;
import java.net.Socket;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Random;
import java.util.UUID;

/**
 * One room owns one server-side game and the two players' connections.
 * Room.class protects the room-code map. Each room's own lock protects its game
 * and orders messages from the two client threads.
 */
public class Room {
    private static final HashMap<String, Room> rooms = new HashMap<>();
    private static final Random random = new Random();
    private static final String CODE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final Game game = new Game();
    private final String code;
    private final Socket firstPlayer;
    private final PrintWriter firstOutput;
    private final String firstUsername;
    private final ProfileDatabase profiles;
    private Socket secondPlayer;
    private PrintWriter secondOutput;
    private String secondUsername;
    private String matchId;
    private boolean resultSaveFailed;
    private boolean closed;

    private Room(String code, Socket firstPlayer, PrintWriter firstOutput,
                 String firstUsername, ProfileDatabase profiles) {
        this.code = code;
        this.firstPlayer = firstPlayer;
        this.firstOutput = firstOutput;
        this.firstUsername = firstUsername;
        this.profiles = profiles;
    }

    public static Room createRoom(Socket player, PrintWriter output, String username, ProfileDatabase profiles) {
        Room room;
        synchronized (Room.class) {
            String code;
            do {
                StringBuilder candidate = new StringBuilder();
                for (int index = 0; index < 6; index++) {
                    candidate.append(CODE_CHARACTERS.charAt(random.nextInt(CODE_CHARACTERS.length())));
                }
                code = candidate.toString();
            } while (rooms.containsKey(code));

            room = new Room(code, player, output, username, profiles);
            rooms.put(code, room);
            // Keep the initial messages inside the lock so a join cannot overtake PLAYER X.
            output.println("ROOM_CREATED " + code);
            output.println("PLAYER X");
        }
        return room;
    }

    public static Room joinRoom(String message, Socket player, PrintWriter output, String username) {
        if (!message.matches("JOIN_ROOM [A-Z0-9]{6}")) {
            output.println("ERROR Invalid room code.");
            return null;
        }
        String code = message.substring("JOIN_ROOM ".length());
        Room room;
        synchronized (Room.class) {
            room = rooms.get(code);
        }
        if (room == null) {
            output.println("ERROR Room not found.");
            return null;
        }

        Socket disconnectedPlayer = null;
        // Only one joining player can claim this room's remaining seat.
        synchronized (room) {
            if (room.closed) {
                output.println("ERROR Room not found.");
                return null;
            }
            if (room.secondPlayer != null) {
                output.println("ERROR Room is full.");
                return null;
            }
            if (room.firstUsername.equals(username)) {
                output.println("ERROR The same account cannot occupy both seats.");
                return null;
            }

            room.secondPlayer = player;
            room.secondOutput = output;
            room.secondUsername = username;
            room.matchId = UUID.randomUUID().toString();
            output.println("ROOM_JOINED " + code);
            output.println("PLAYER O");
            room.firstOutput.println("ROOM_READY " + code);
            output.println("ROOM_READY " + code);
            if (!room.broadcastState()) {
                disconnectedPlayer = room.firstOutput.checkError() ? room.firstPlayer : room.secondPlayer;
            }
        }
        // Release the room lock before removing the entry from the shared map.
        if (disconnectedPlayer != null) {
            room.playerDisconnected(disconnectedPlayer);
        }
        return room;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public void handleMove(Socket requester, String message) {
        Socket disconnectedPlayer = null;
        boolean removeRoomAfterMove = false;
        synchronized (this) {
            PrintWriter requesterOutput;
            char requesterSymbol;
            if (requester == firstPlayer) {
                requesterOutput = firstOutput;
                requesterSymbol = 'x';
            } else if (requester == secondPlayer) {
                requesterOutput = secondOutput;
                requesterSymbol = 'o';
            } else {
                return;
            }

            if (closed) {
                requesterOutput.println("ERROR Room is closed.");
                return;
            }
            if (secondPlayer == null) {
                requesterOutput.println("ERROR Both players must be connected before moving.");
                return;
            }

            String[] fields = message.trim().split("\\s+");
            if (fields.length != 5 || !"MOVE".equals(fields[0])) {
                requesterOutput.println("ERROR Expected MOVE <fromRow> <fromCol> <toRow> <toCol>.");
                return;
            }

            int[] coordinates = new int[4];
            try {
                for (int index = 0; index < coordinates.length; index++) {
                    coordinates[index] = Integer.parseInt(fields[index + 1]);
                    if (coordinates[index] < 0 || coordinates[index] > 7) {
                        requesterOutput.println("ERROR Coordinates must be between 0 and 7.");
                        return;
                    }
                }
            } catch (NumberFormatException exception) {
                requesterOutput.println("ERROR Coordinates must be integers.");
                return;
            }

            if (game.getCurrentPlayer().getSymbol() != requesterSymbol) {
                requesterOutput.println("ERROR It is not your turn.");
                return;
            }

            Position from = new Position(coordinates[0], coordinates[1]);
            Position to = new Position(coordinates[2], coordinates[3]);
            if (!game.move(from, to)) {
                requesterOutput.println("ERROR " + game.getMoveError());
                return;
            }

            // Game supplies the accepted move's facts; Room only sends them.
            char winner = game.getWinnerSymbol();
            boolean delivered = broadcastMove(game.getLastMove());
            // A rules winner stays the winner even if a final message cannot be delivered.
            if (winner != '\0') {
                closed = true;
                removeRoomAfterMove = true;
                recordResult(winner, "RULES");
                broadcastGameOver(winner, "RULES");
            } else if (!delivered) {
                disconnectedPlayer = firstOutput.checkError() ? firstPlayer : secondPlayer;
            }
        }

        if (disconnectedPlayer != null) {
            playerDisconnected(disconnectedPlayer);
        } else if (removeRoomAfterMove) {
            removeFromRooms();
        }
    }

    private boolean broadcastMove(Game.Move move) {
        Position from = move.getFrom();
        Position to = move.getTo();
        Position captured = move.getCapturedPosition();
        int capturedRow = captured == null ? -1 : captured.getRow();
        int capturedCol = captured == null ? -1 : captured.getCol();
        String moveMessage = "MOVE_APPLIED " + from.getRow() + " " + from.getCol()
                + " " + to.getRow() + " " + to.getCol()
                + " " + capturedRow + " " + capturedCol;
        firstOutput.println(moveMessage);
        secondOutput.println(moveMessage);
        return broadcastState();
    }

    private boolean broadcastState() {
        String boardMessage = "BOARD " + encodeBoard();
        char turnSymbol = Character.toUpperCase(game.getCurrentPlayer().getSymbol());
        String turnUsername = turnSymbol == 'X' ? firstUsername : secondUsername;
        String turnMessage = "TURN " + turnSymbol + " " + turnUsername;
        firstOutput.println(boardMessage);
        secondOutput.println(boardMessage);
        firstOutput.println(turnMessage);
        secondOutput.println(turnMessage);
        return !firstOutput.checkError() && !secondOutput.checkError();
    }

    private String encodeBoard() {
        StringBuilder encodedBoard = new StringBuilder(64);
        Board board = game.getBoard();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Piece piece = board.getPiece(row, col);
                encodedBoard.append(piece == null ? '.' : piece.getSymbol());
            }
        }
        return encodedBoard.toString();
    }

    public boolean resign(Socket player) {
        char winner;
        synchronized (this) {
            if (closed || secondPlayer == null) {
                sendError(player, "Cannot resign from an inactive game.");
                return false;
            }
            if (player == firstPlayer) {
                winner = 'O';
            } else if (player == secondPlayer) {
                winner = 'X';
            } else {
                return false;
            }
            closed = true;
            recordResult(winner, "RESIGNATION");
            broadcastGameOver(winner, "RESIGNATION");
        }
        removeFromRooms();
        return true;
    }

    public void leave(Socket player) {
        synchronized (this) {
            if (closed || (player != firstPlayer && player != secondPlayer)) {
                sendError(player, "You are not in an active room.");
                return;
            }
            closed = true;
            if (player == firstPlayer) {
                firstOutput.println("ROOM_LEFT");
                if (secondOutput != null) {
                    recordResult('O', "OPPONENT_LEFT");
                    secondOutput.println("GAME_OVER O OPPONENT_LEFT");
                }
            } else {
                secondOutput.println("ROOM_LEFT");
                recordResult('X', "OPPONENT_LEFT");
                firstOutput.println("GAME_OVER X OPPONENT_LEFT");
            }
            notifyResultSaveFailure();
        }
        removeFromRooms();
    }

    public void playerDisconnected(Socket player) {
        synchronized (this) {
            if (closed || (player != firstPlayer && player != secondPlayer)) {
                return;
            }
            closed = true;
            // Before the second player joins there is nobody to notify.
            if (secondPlayer != null) {
                char winner = player == firstPlayer ? 'O' : 'X';
                recordResult(winner, "OPPONENT_DISCONNECTED");
                broadcastGameOver(winner, "OPPONENT_DISCONNECTED");
            }
        }
        removeFromRooms();
    }

    /** Called once while closing a started match under this room's lock. */
    private void recordResult(char winner, String reason) {
        if (matchId == null) {
            return; // A waiting room is not a completed match.
        }
        try {
            profiles.recordOnlineGame(matchId, firstUsername, secondUsername,
                    Character.toUpperCase(winner), reason, Instant.now().toString());
        } catch (SQLException exception) {
            resultSaveFailed = true;
            System.err.println("Could not save the completed match result.");
        }
    }

    private void broadcastGameOver(char winner, String reason) {
        String message = "GAME_OVER " + winner + " " + reason;
        firstOutput.println(message);
        if (secondOutput != null) {
            secondOutput.println(message);
        }
        notifyResultSaveFailure();
    }

    private void notifyResultSaveFailure() {
        if (resultSaveFailed) {
            String error = "RESULT_ERROR The match ended, but its result could not be saved.";
            firstOutput.println(error);
            if (secondOutput != null) {
                secondOutput.println(error);
            }
        }
    }

    private void sendError(Socket player, String message) {
        if (player == firstPlayer) {
            firstOutput.println("ERROR " + message);
        } else if (player == secondPlayer && secondOutput != null) {
            secondOutput.println("ERROR " + message);
        }
    }

    private void removeFromRooms() {
        // Call only after releasing this room's lock: unrelated rooms can still
        // be created or joined while a game's messages are being sent.
        synchronized (Room.class) {
            rooms.remove(code, this);
        }
    }

}
