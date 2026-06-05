import java.util.ArrayList;
import java.util.List;

public class Game {
    private Board board;
    private Player playerX;
    private Player playerO;
    private Player currentPlayer;

    public static class Move {
        private final Position from;
        private final Position to;

        public Move(Position from, Position to) {
            this.from = from;
            this.to = to;
        }

        public Position getFrom() {
            return from;
        }

        public Position getTo() {
            return to;
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
    }

    public Board getBoard() {
        return board;
    }

    public Player getCurrentPlayer() {
        return currentPlayer;
    }

    public boolean move(Position from, Position to) {

        Piece piece = board.getPiece(from.getRow(), from.getCol());

        if (piece == null) {
            return false;
        }

        if (!piece.belongsToPlayer(currentPlayer.getSymbol())) {
            return false;
        }

        if (!board.isEmpty(to.getRow(), to.getCol())) {
            return false;
        }

        int rowDiff = to.getRow() - from.getRow();
        int colDiff = to.getCol() - from.getCol();

        // Must move diagonally
        if (Math.abs(rowDiff) != Math.abs(colDiff)) {
            return false;
        }

        // Normal move: one step
        if (Math.abs(rowDiff) == 1) {
            if (playerMustCapture()) {
                return false;
            }

            if (!piece.isKing()) {
                if (piece.getSymbol() == 'x' && rowDiff != -1) {
                    return false;
                }

                if (piece.getSymbol() == 'o' && rowDiff != 1) {
                    return false;
                }
            }

            board.movePiece(from, to);
            switchTurn();
            return true;
        }

        // Capture move: two steps
        if (Math.abs(rowDiff) == 2) {
            int middleRow = (from.getRow() + to.getRow()) / 2;
            int middleCol = (from.getCol() + to.getCol()) / 2;

            Piece middlePiece = board.getPiece(middleRow, middleCol);

            if (middlePiece == null) {
                return false;
            }

            if (!middlePiece.isOpponent(piece.getSymbol())) {
                return false;
            }

            if (!piece.isKing()) {
                if (piece.getSymbol() == 'x' && rowDiff != -2) {
                    return false;
                }

                if (piece.getSymbol() == 'o' && rowDiff != 2) {
                    return false;
                }
            }

            board.removePiece(new Position(middleRow, middleCol));
            board.movePiece(from, to);

            // Only switch if no additional capture exists
            if (!canCaptureAgain(to)) {
                switchTurn();
            }

            return true;
        }

        return false;
    }

    private void switchTurn() {
        if (currentPlayer == playerX) {
            currentPlayer = playerO;
        } else {
            currentPlayer = playerX;
        }
    }

    public boolean hasPieces(char player) {
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Piece piece = board.getPiece(row, col);

                if (piece != null && piece.belongsToPlayer(player)) {
                    return true;
                }
            }
        }

        return false;
    }

    public String getWinner() {
        if (!hasPieces('x') || !hasAnyValidMove('x')) {
            return "Player O wins!";
        }

        if (!hasPieces('o') || !hasAnyValidMove('o')) {
            return "Player X wins!";
        }

        return null;
    }

    public List<Move> getValidMoves(char player) {
        List<Move> captureMoves = new ArrayList<>();
        List<Move> normalMoves = new ArrayList<>();

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Piece piece = board.getPiece(row, col);

                if (piece == null || !piece.belongsToPlayer(player)) {
                    continue;
                }

                int[] rowDirections = {-1, -1, 1, 1};
                int[] colDirections = {-1, 1, -1, 1};

                for (int i = 0; i < 4; i++) {
                    int newRow = row + rowDirections[i];
                    int newCol = col + colDirections[i];

                    if (!board.isInsideBoard(newRow, newCol)) {
                        continue;
                    }

                    if (!board.isEmpty(newRow, newCol)) {
                        continue;
                    }

                    if (piece.isKing()
                            || piece.getSymbol() == 'x' && newRow < row
                            || piece.getSymbol() == 'o' && newRow > row) {
                        normalMoves.add(new Move(new Position(row, col), new Position(newRow, newCol)));
                    }
                }

                int[] captureRows = {-2, -2, 2, 2};
                int[] captureCols = {-2, 2, -2, 2};

                for (int i = 0; i < 4; i++) {
                    int newRow = row + captureRows[i];
                    int newCol = col + captureCols[i];

                    int middleRow = row + captureRows[i] / 2;
                    int middleCol = col + captureCols[i] / 2;

                    if (!board.isInsideBoard(newRow, newCol)) {
                        continue;
                    }

                    if (!board.isEmpty(newRow, newCol)) {
                        continue;
                    }

                    Piece middlePiece = board.getPiece(middleRow, middleCol);

                    if (middlePiece != null && middlePiece.isOpponent(player)) {
                        if (piece.isKing()
                                || piece.getSymbol() == 'x' && newRow < row
                                || piece.getSymbol() == 'o' && newRow > row) {
                            captureMoves.add(new Move(new Position(row, col), new Position(newRow, newCol)));
                        }
                    }
                }
            }
        }

        return captureMoves.isEmpty() ? normalMoves : captureMoves;
    }

    public boolean playerMustCapture() {
        char playerSymbol = currentPlayer.getSymbol();

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Piece piece = board.getPiece(row, col);

                // Skip empty squares or opponent pieces
                if (piece == null || !piece.belongsToPlayer(playerSymbol)) {
                    continue;
                }

                // All 4 diagonal jump directions
                int[] rowDirections = {-2, -2, 2, 2};
                int[] colDirections = {-2, 2, -2, 2};

                for (int i = 0; i < 4; i++) {
                    int newRow = row + rowDirections[i];
                    int newCol = col + colDirections[i];

                    int middleRow = row + rowDirections[i] / 2;
                    int middleCol = col + colDirections[i] / 2;

                    // Check board limits
                    if (!board.isInsideBoard(newRow, newCol)) {
                        continue;
                    }

                    // Landing square must be empty
                    if (!board.isEmpty(newRow, newCol)) {
                        continue;
                    }

                    Piece middlePiece = board.getPiece(middleRow, middleCol);

                    // There must be opponent in middle
                    if (middlePiece != null && middlePiece.isOpponent(playerSymbol)) {

                        // Regular pieces only forward
                        if (!piece.isKing()) {
                            if (piece.getSymbol() == 'x' && newRow > row) {
                                continue;
                            }

                            if (piece.getSymbol() == 'o' && newRow < row) {
                                continue;
                            }
                        }

                        return true;
                    }
                }
            }
        }

        return false;
    }

    public boolean canCaptureAgain(Position position) {
        Piece piece = board.getPiece(position.getRow(), position.getCol());

        if (piece == null) {
            return false;
        }

        int row = position.getRow();
        int col = position.getCol();

        // All 4 diagonal capture directions
        int[] rowDirections = {-2, -2, 2, 2};
        int[] colDirections = {-2, 2, -2, 2};

        for (int i = 0; i < 4; i++) {
            int newRow = row + rowDirections[i];
            int newCol = col + colDirections[i];

            int middleRow = row + rowDirections[i] / 2;
            int middleCol = col + colDirections[i] / 2;

            // Must stay inside board
            if (!board.isInsideBoard(newRow, newCol)) {
                continue;
            }

            // Landing square must be empty
            if (!board.isEmpty(newRow, newCol)) {
                continue;
            }

            Piece middlePiece = board.getPiece(middleRow, middleCol);

            // Must capture opponent
            if (middlePiece != null && middlePiece.isOpponent(piece.getSymbol())) {

                // Regular pieces only forward
                if (!piece.isKing()) {
                    if (piece.getSymbol() == 'x' && newRow > row) {
                        continue;
                    }

                    if (piece.getSymbol() == 'o' && newRow < row) {
                        continue;
                    }
                }

                return true;
            }
        }

        return false;
    }
    public boolean hasAnyValidMove(char player) {
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Piece piece = board.getPiece(row, col);

                if (piece == null || !piece.belongsToPlayer(player)) {
                    continue;
                }

                int[] rowDirections = {-1, -1, 1, 1};
                int[] colDirections = {-1, 1, -1, 1};

                for (int i = 0; i < 4; i++) {
                    int newRow = row + rowDirections[i];
                    int newCol = col + colDirections[i];

                    if (!board.isInsideBoard(newRow, newCol)) {
                        continue;
                    }

                    if (!board.isEmpty(newRow, newCol)) {
                        continue;
                    }

                    if (piece.isKing()
                            || piece.getSymbol() == 'x' && newRow < row
                            || piece.getSymbol() == 'o' && newRow > row) {
                        return true;
                    }
                }

                int[] captureRows = {-2, -2, 2, 2};
                int[] captureCols = {-2, 2, -2, 2};

                for (int i = 0; i < 4; i++) {
                    int newRow = row + captureRows[i];
                    int newCol = col + captureCols[i];

                    int middleRow = row + captureRows[i] / 2;
                    int middleCol = col + captureCols[i] / 2;

                    if (!board.isInsideBoard(newRow, newCol)) {
                        continue;
                    }

                    if (!board.isEmpty(newRow, newCol)) {
                        continue;
                    }

                    Piece middlePiece = board.getPiece(middleRow, middleCol);

                    if (middlePiece != null && middlePiece.isOpponent(player)) {
                        if (piece.isKing()
                                || piece.getSymbol() == 'x' && newRow < row
                                || piece.getSymbol() == 'o' && newRow > row) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }
}