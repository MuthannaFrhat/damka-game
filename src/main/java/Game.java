import java.util.ArrayList;
import java.util.List;

/** Owns move rules, turns, capture continuation, promotion, and the winner. */
public class Game {
    private final Board board;
    private final Player playerX;
    private final Player playerO;
    private Player currentPlayer;
    private Position captureContinuationPosition;
    private Move lastMove;
    private String moveError;

    /** A legal move also carries the facts needed to display its animation. */
    public static class Move {
        private final Position from;
        private final Position to;
        private final Position capturedPosition;
        private final char movingSymbol;

        private Move(Position from, Position to, Position capturedPosition, char movingSymbol) {
            this.from = from;
            this.to = to;
            this.capturedPosition = capturedPosition;
            this.movingSymbol = movingSymbol;
        }

        public Position getFrom() {
            return from;
        }

        public Position getTo() {
            return to;
        }

        public Position getCapturedPosition() {
            return capturedPosition;
        }

        public char getMovingSymbol() {
            return movingSymbol;
        }

        public boolean isCapture() {
            return capturedPosition != null;
        }
    }

    public Game() {
        this(false, false);
    }

    public Game(boolean xBot, boolean oBot) {
        board = new Board();
        playerX = new Player('x', "Player X", xBot);
        playerO = new Player('o', "Player O", oBot);
        currentPlayer = playerX;
    }

    public Game(Game other) {
        board = new Board(other.board);
        playerX = new Player(other.playerX.getSymbol(), other.playerX.getName(), other.playerX.isBot());
        playerO = new Player(other.playerO.getSymbol(), other.playerO.getName(), other.playerO.isBot());
        currentPlayer = other.currentPlayer == other.playerX ? playerX : playerO;
        if (other.captureContinuationPosition != null) {
            captureContinuationPosition = new Position(other.captureContinuationPosition.getRow(),
                    other.captureContinuationPosition.getCol());
        }
        lastMove = other.lastMove;
        moveError = other.moveError;
    }

    public Board getBoard() {
        return board;
    }

    public Player getCurrentPlayer() {
        return currentPlayer;
    }

    public Position getCaptureContinuationPosition() {
        return captureContinuationPosition;
    }

    public Move getLastMove() {
        return lastMove;
    }

    public String getMoveError() {
        return moveError;
    }

    public boolean move(Position from, Position to) {
        moveError = "Invalid move.";
        if (from == null || to == null) {
            return false;
        }
        if (captureContinuationPosition != null && !samePosition(from, captureContinuationPosition)) {
            moveError = "Continue capturing with the same piece.";
            return false;
        }

        // Validate against the same legal moves used by selection, winner checks, and BOT search.
        Move acceptedMove = null;
        for (Move move : getValidMoves(currentPlayer.getSymbol())) {
            if (samePosition(from, move.getFrom()) && samePosition(to, move.getTo())) {
                acceptedMove = move;
                break;
            }
        }
        if (acceptedMove == null) {
            return false;
        }

        if (acceptedMove.isCapture()) {
            board.removePiece(acceptedMove.getCapturedPosition());
        }
        board.movePiece(from, to);
        promoteIfNeeded(to);
        lastMove = acceptedMove;
        moveError = null;

        if (acceptedMove.isCapture() && canCaptureAgain(to)) {
            captureContinuationPosition = acceptedMove.getTo();
        } else {
            captureContinuationPosition = null;
            switchTurn();
        }
        return true;
    }

    public List<Move> getValidMoves(char player) {
        // Only the current player is restricted by an unfinished capture chain.
        if (captureContinuationPosition != null
                && Character.toLowerCase(player) == currentPlayer.getSymbol()) {
            return getPieceMoves(captureContinuationPosition, true);
        }

        List<Move> captures = new ArrayList<>();
        List<Move> normalMoves = new ArrayList<>();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Piece piece = board.getPiece(row, col);
                if (piece != null && piece.belongsToPlayer(player)) {
                    Position from = new Position(row, col);
                    captures.addAll(getPieceMoves(from, true));
                    normalMoves.addAll(getPieceMoves(from, false));
                }
            }
        }
        // A capture anywhere prevents ordinary moves.
        return captures.isEmpty() ? normalMoves : captures;
    }

    public boolean canSelectPiece(Position position) {
        if (position == null) {
            return false;
        }
        for (Move move : getValidMoves(currentPlayer.getSymbol())) {
            if (samePosition(position, move.getFrom())) {
                return true;
            }
        }
        return false;
    }

    public boolean playerMustCapture() {
        List<Move> moves = getValidMoves(currentPlayer.getSymbol());
        return !moves.isEmpty() && moves.get(0).isCapture();
    }

    public boolean canCaptureAgain(Position position) {
        return !getPieceMoves(position, true).isEmpty();
    }

    public boolean hasAnyValidMove(char player) {
        return !getValidMoves(player).isEmpty();
    }

    /** Uppercase player identity, or '\0' while there is no winner. */
    public char getWinnerSymbol() {
        if (!hasAnyValidMove('x')) {
            return 'O';
        }
        if (!hasAnyValidMove('o')) {
            return 'X';
        }
        return '\0';
    }

    // All direction, distance, empty-square, and opponent checks are defined here once.
    private List<Move> getPieceMoves(Position from, boolean capture) {
        List<Move> moves = new ArrayList<>();
        Piece piece = board.getPiece(from.getRow(), from.getCol());
        if (piece == null) {
            return moves;
        }
        int distance = capture ? 2 : 1;
        int forward = piece.belongsToPlayer('x') ? -1 : 1;
        for (int rowDirection = -1; rowDirection <= 1; rowDirection += 2) {
            if (!piece.isKing() && rowDirection != forward) {
                continue;
            }
            for (int colDirection = -1; colDirection <= 1; colDirection += 2) {
                int row = from.getRow() + rowDirection * distance;
                int col = from.getCol() + colDirection * distance;
                if (!board.isEmpty(row, col)) {
                    continue;
                }
                Position captured = null;
                if (capture) {
                    int middleRow = from.getRow() + rowDirection;
                    int middleCol = from.getCol() + colDirection;
                    Piece opponent = board.getPiece(middleRow, middleCol);
                    if (opponent == null || !opponent.isOpponent(piece.getSymbol())) {
                        continue;
                    }
                    captured = new Position(middleRow, middleCol);
                }
                moves.add(new Move(from, new Position(row, col), captured, piece.getSymbol()));
            }
        }
        return moves;
    }

    private void promoteIfNeeded(Position position) {
        Piece piece = board.getPiece(position.getRow(), position.getCol());
        if (piece.getSymbol() == 'x' && position.getRow() == 0
                || piece.getSymbol() == 'o' && position.getRow() == 7) {
            piece.makeKing();
        }
    }

    private void switchTurn() {
        currentPlayer = currentPlayer == playerX ? playerO : playerX;
    }

    private boolean samePosition(Position first, Position second) {
        return first.getRow() == second.getRow() && first.getCol() == second.getCol();
    }
}
