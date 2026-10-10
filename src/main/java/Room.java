import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Random;

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
    private Socket secondPlayer;
    private PrintWriter secondOutput;
    private Position captureContinuationPosition;
    private boolean closed;

    private Room(String code, Socket firstPlayer, PrintWriter firstOutput) {
        this.code = code;
        this.firstPlayer = firstPlayer;
        this.firstOutput = firstOutput;
    }

    public static Room createRoom(Socket player, PrintWriter output) {
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

            room = new Room(code, player, output);
            rooms.put(code, room);
            // Keep the initial messages inside the lock so a join cannot overtake PLAYER X.
            output.println("ROOM_CREATED " + code);
            output.println("PLAYER X");
        }
        return room;
    }

    public static Room joinRoom(String message, Socket player, PrintWriter output) {
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
        if (!room.join(player, output)) {
            return null;
        }
        return room;
    }

    private boolean join(Socket player, PrintWriter output) {
        Socket disconnectedPlayer = null;
        synchronized (this) {
            if (closed) {
                output.println("ERROR Room not found.");
                return false;
            }
            if (secondPlayer != null) {
                output.println("ERROR Room is full.");
                return false;
            }

            secondPlayer = player;
            secondOutput = output;
            output.println("ROOM_JOINED " + code);
            output.println("PLAYER O");
            firstOutput.println("ROOM_READY " + code);
            output.println("ROOM_READY " + code);
            if (!broadcastState()) {
                disconnectedPlayer = firstOutput.checkError() ? firstPlayer : secondPlayer;
            }
        }
        // Release the room lock before removing the entry from the shared map.
        if (disconnectedPlayer != null) {
            playerDisconnected(disconnectedPlayer);
        }
        return true;
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

            int fromRow = coordinates[0];
            int fromCol = coordinates[1];
            int toRow = coordinates[2];
            int toCol = coordinates[3];
            if (captureContinuationPosition != null
                    && (captureContinuationPosition.getRow() != fromRow
                    || captureContinuationPosition.getCol() != fromCol)) {
                requesterOutput.println("ERROR Continue capturing with the same piece.");
                return;
            }

            Position from = new Position(fromRow, fromCol);
            Position to = new Position(toRow, toCol);
            if (!game.move(from, to)) {
                requesterOutput.println("ERROR Invalid move.");
                return;
            }

            if (Math.abs(toRow - fromRow) == 2 && game.canCaptureAgain(to)) {
                captureContinuationPosition = to;
            } else {
                captureContinuationPosition = null;
            }

            int capturedRow = Math.abs(toRow - fromRow) == 2 ? (fromRow + toRow) / 2 : -1;
            int capturedCol = Math.abs(toCol - fromCol) == 2 ? (fromCol + toCol) / 2 : -1;
            if (!broadcastMove(fromRow, fromCol, toRow, toCol, capturedRow, capturedCol)) {
                disconnectedPlayer = firstOutput.checkError() ? firstPlayer : secondPlayer;
            } else {
                String winner = game.getWinner();
                if (winner != null) {
                    closed = true;
                    removeRoomAfterMove = true;
                    broadcastGameOver(winnerSymbol(winner), "RULES");
                }
            }
        }

        if (disconnectedPlayer != null) {
            playerDisconnected(disconnectedPlayer);
        } else if (removeRoomAfterMove) {
            removeFromRooms();
        }
    }

    private boolean broadcastMove(int fromRow, int fromCol, int toRow, int toCol,
                                  int capturedRow, int capturedCol) {
        String moveMessage = "MOVE_APPLIED " + fromRow + " " + fromCol + " " + toRow + " " + toCol
                + " " + capturedRow + " " + capturedCol;
        firstOutput.println(moveMessage);
        secondOutput.println(moveMessage);
        return broadcastState();
    }

    private boolean broadcastState() {
        String boardMessage = "BOARD " + encodeBoard();
        String turnMessage = "TURN " + Character.toUpperCase(game.getCurrentPlayer().getSymbol());
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
            broadcastGameOver(winner, "RESIGNATION");
            closed = true;
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

            if (player == firstPlayer) {
                firstOutput.println("ROOM_LEFT");
                if (secondOutput != null) {
                    secondOutput.println("GAME_OVER O OPPONENT_LEFT");
                }
            } else {
                secondOutput.println("ROOM_LEFT");
                firstOutput.println("GAME_OVER X OPPONENT_LEFT");
            }
            closed = true;
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
                broadcastGameOver(player == firstPlayer ? 'O' : 'X', "OPPONENT_DISCONNECTED");
            }
        }
        removeFromRooms();
    }

    private void broadcastGameOver(char winner, String reason) {
        String message = "GAME_OVER " + winner + " " + reason;
        firstOutput.println(message);
        if (secondOutput != null) {
            secondOutput.println(message);
        }
    }

    private void sendError(Socket player, String message) {
        if (player == firstPlayer) {
            firstOutput.println("ERROR " + message);
        } else if (player == secondPlayer && secondOutput != null) {
            secondOutput.println("ERROR " + message);
        }
    }

    private char winnerSymbol(String winner) {
        return winner.contains("Player X") ? 'X' : 'O';
    }

    private void removeFromRooms() {
        // Call only after releasing this room's lock: unrelated rooms can still
        // be created or joined while a game's messages are being sent.
        synchronized (Room.class) {
            rooms.remove(code, this);
        }
    }

}
