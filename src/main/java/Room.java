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

    private final Game game = new Game();
    private final String code;
    private final Socket firstPlayer;
    private final PrintWriter firstOutput;
    private Socket secondPlayer;
    private PrintWriter secondOutput;
    private Position captureContinuationPosition;
    private boolean gameOver;
    private boolean closed;

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
        output.println("PLAYER X");
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

        if (!room.join(player, output)) {
            return null;
        }
        return room;
    }

    private synchronized boolean join(Socket player, PrintWriter output) {
        if (closed || secondPlayer != null) {
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
            closeForDisconnect(firstOutput.checkError() ? firstPlayer : secondPlayer);
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
            if (gameOver) {
                requesterOutput.println("ERROR Game is over.");
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
                    gameOver = true;
                    closed = true;
                    removeRoomAfterMove = true;
                    broadcastGameOver(winnerSymbol(winner), "RULES");
                }
            }
        }

        if (disconnectedPlayer != null) {
            closeForDisconnect(disconnectedPlayer);
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
        char winner = 'X';
        synchronized (this) {
            if (closed || gameOver || secondPlayer == null) {
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
            gameOver = true;
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

            boolean resultAlreadySent = gameOver;
            if (player == firstPlayer) {
                firstOutput.println("ROOM_LEFT");
                if (secondOutput != null) {
                    secondOutput.println(resultAlreadySent
                            ? "ROOM_CLOSED" : "GAME_OVER O OPPONENT_LEFT");
                }
            } else {
                secondOutput.println("ROOM_LEFT");
                firstOutput.println(resultAlreadySent
                        ? "ROOM_CLOSED" : "GAME_OVER X OPPONENT_LEFT");
            }
            closed = true;
            gameOver = true;
        }
        removeFromRooms();
    }

    public void playerDisconnected(Socket player) {
        Socket otherPlayer = null;
        synchronized (this) {
            if (closed || (player != firstPlayer && player != secondPlayer)) {
                return;
            }
            if (player == firstPlayer) {
                otherPlayer = secondPlayer;
            } else {
                otherPlayer = firstPlayer;
            }

            closed = true;
            if (otherPlayer != null && !gameOver) {
                gameOver = true;
                broadcastGameOver(player == firstPlayer ? 'O' : 'X', "OPPONENT_DISCONNECTED");
            } else if (otherPlayer != null) {
                otherOutput(otherPlayer).println("ROOM_CLOSED OPPONENT_DISCONNECTED");
            }
        }
        removeFromRooms();
    }

    private void closeForDisconnect(Socket player) {
        playerDisconnected(player);
    }

    private void broadcastGameOver(char winner, String reason) {
        String message = "GAME_OVER " + winner + " " + reason;
        firstOutput.println(message);
        if (secondOutput != null) {
            secondOutput.println(message);
        }
    }

    private PrintWriter otherOutput(Socket player) {
        return player == firstPlayer ? firstOutput : secondOutput;
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
        synchronized (Room.class) {
            rooms.remove(code, this);
        }
    }

    public void disconnect() {
        playerDisconnected(firstPlayer);
    }
}
