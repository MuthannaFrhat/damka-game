import java.util.Scanner;

public class Game {
    private Board board;
    private Player player1;
    private Player player2;
    private Player currentPlayer;
    private Scanner scanner;

    public Game() {
        board = new Board();
        player1 = new Player('x', "Player 1");
        player2 = new Player('o', "Player 2");
        currentPlayer = player1;
        scanner = new Scanner(System.in);
    }

    public void start() {
        boolean gameOver = false;

        while (!gameOver) {
            board.printBoard();
            System.out.println();

            if (!playerHasAnyMove(currentPlayer.getSymbol())) {
                if (currentPlayer.getSymbol() == 'x') {
                    System.out.println("Game over. Player 2 wins.");
                } else {
                    System.out.println("Game over. Player 1 wins.");
                }
                break;
            }

            System.out.println(currentPlayer.getName() + " turn");

            if (playerHasAnyCapture(currentPlayer.getSymbol())) {
                System.out.println("You must eat. A capture is required.");
            }

            System.out.print("Enter source position (example A3): ");
            String from = scanner.nextLine().toUpperCase();

            System.out.print("Enter destination position (example B4): ");
            String to = scanner.nextLine().toUpperCase();

            if (!isValidPositionFormat(from) || !isValidPositionFormat(to)) {
                System.out.println("Illegal move: invalid position format.");
                System.out.println();
                continue;
            }

            int fromRow = getRow(from);
            int fromCol = getCol(from);
            int toRow = getRow(to);
            int toCol = getCol(to);

            if (!isLegalMove(currentPlayer.getSymbol(), fromRow, fromCol, toRow, toCol)) {
                System.out.println("Illegal move.");
                System.out.println();
                continue;
            }

            boolean wasCapture = isCaptureMove(fromRow, fromCol, toRow, toCol);
            board.makeMove(fromRow, fromCol, toRow, toCol);
            board.promoteIfNeeded(toRow, toCol);

            if (wasCapture) {
                int currentRow = toRow;
                int currentCol = toCol;

                while (hasCaptureFrom(currentPlayer.getSymbol(), currentRow, currentCol)) {
                    board.printBoard();
                    System.out.println("You must continue eating with the same piece.");

                    System.out.print("Enter next destination for the same piece: ");
                    String nextTo = scanner.nextLine().toUpperCase();

                    if (!isValidPositionFormat(nextTo)) {
                        System.out.println("Illegal move: invalid position format.");
                        continue;
                    }

                    int nextRow = getRow(nextTo);
                    int nextCol = getCol(nextTo);

                    if (!isLegalContinuationCapture(currentPlayer.getSymbol(), currentRow, currentCol, nextRow, nextCol)) {
                        System.out.println("Illegal move. You must continue eating with the same piece.");
                        continue;
                    }

                    board.makeMove(currentRow, currentCol, nextRow, nextCol);
                    board.promoteIfNeeded(nextRow, nextCol);

                    currentRow = nextRow;
                    currentCol = nextCol;
                }
            }

            if (board.countPiecesForPlayer('x') == 0) {
                board.printBoard();
                System.out.println("Game over. Player 2 wins.");
                gameOver = true;
            } else if (board.countPiecesForPlayer('o') == 0) {
                board.printBoard();
                System.out.println("Game over. Player 1 wins.");
                gameOver = true;
            } else {
                switchPlayer();
            }
        }
    }

    private void switchPlayer() {
        if (currentPlayer == player1) {
            currentPlayer = player2;
        } else {
            currentPlayer = player1;
        }
    }

    private boolean isValidPositionFormat(String pos) {
        if (pos.length() != 2) {
            return false;
        }

        char colChar = pos.charAt(0);
        char rowChar = pos.charAt(1);

        return colChar >= 'A' && colChar <= 'H' && rowChar >= '1' && rowChar <= '8';
    }

    private int getRow(String pos) {
        return pos.charAt(1) - '1';
    }

    private int getCol(String pos) {
        return pos.charAt(0) - 'A';
    }

    private boolean isCaptureMove(int fromRow, int fromCol, int toRow, int toCol) {
        return Math.abs(toRow - fromRow) == 2 && Math.abs(toCol - fromCol) == 2;
    }

    private boolean isLegalMove(char player, int fromRow, int fromCol, int toRow, int toCol) {
        if (!board.isInsideBoard(fromRow, fromCol) || !board.isInsideBoard(toRow, toCol)) {
            return false;
        }

        Piece piece = board.getPiece(fromRow, fromCol);

        if (piece == null || !piece.belongsToPlayer(player)) {
            return false;
        }

        if (!board.isEmptyCell(toRow, toCol)) {
            return false;
        }

        int rowDiff = toRow - fromRow;
        int colDiff = toCol - fromCol;

        if (Math.abs(colDiff) != Math.abs(rowDiff)) {
            return false;
        }

        if (Math.abs(rowDiff) != 1 && Math.abs(rowDiff) != 2) {
            return false;
        }

        boolean mustCapture = playerHasAnyCapture(player);

        if (Math.abs(rowDiff) == 1) {
            if (mustCapture) {
                return false;
            }

            if (piece.isKing()) {
                return true;
            }

            if (player == 'x' && rowDiff == -1) {
                return true;
            }

            if (player == 'o' && rowDiff == 1) {
                return true;
            }

            return false;
        }

        int midRow = (fromRow + toRow) / 2;
        int midCol = (fromCol + toCol) / 2;
        Piece middlePiece = board.getPiece(midRow, midCol);

        return middlePiece != null && middlePiece.isOpponent(player);
    }

    private boolean isLegalContinuationCapture(char player, int fromRow, int fromCol, int toRow, int toCol) {
        if (!board.isInsideBoard(fromRow, fromCol) || !board.isInsideBoard(toRow, toCol)) {
            return false;
        }

        Piece piece = board.getPiece(fromRow, fromCol);

        if (piece == null || !piece.belongsToPlayer(player)) {
            return false;
        }

        if (!board.isEmptyCell(toRow, toCol)) {
            return false;
        }

        int rowDiff = toRow - fromRow;
        int colDiff = toCol - fromCol;

        if (Math.abs(rowDiff) != 2 || Math.abs(colDiff) != 2) {
            return false;
        }

        int midRow = (fromRow + toRow) / 2;
        int midCol = (fromCol + toCol) / 2;
        Piece middlePiece = board.getPiece(midRow, midCol);

        return middlePiece != null && middlePiece.isOpponent(player);
    }

    private boolean hasCaptureFrom(char player, int row, int col) {
        Piece piece = board.getPiece(row, col);

        if (piece == null || !piece.belongsToPlayer(player)) {
            return false;
        }

        int[] rowSteps = {-2, -2, 2, 2};
        int[] colSteps = {-2, 2, -2, 2};

        for (int i = 0; i < 4; i++) {
            int newRow = row + rowSteps[i];
            int newCol = col + colSteps[i];

            if (!board.isInsideBoard(newRow, newCol)) {
                continue;
            }

            if (!board.isEmptyCell(newRow, newCol)) {
                continue;
            }

            int midRow = (row + newRow) / 2;
            int midCol = (col + newCol) / 2;

            Piece middlePiece = board.getPiece(midRow, midCol);
            if (middlePiece != null && middlePiece.isOpponent(player)) {
                return true;
            }
        }

        return false;
    }

    private boolean playerHasAnyCapture(char player) {
        for (int row = 0; row < Board.SIZE; row++) {
            for (int col = 0; col < Board.SIZE; col++) {
                Piece piece = board.getPiece(row, col);
                if (piece != null && piece.belongsToPlayer(player)) {
                    if (hasCaptureFrom(player, row, col)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasNormalMoveFrom(char player, int row, int col) {
        Piece piece = board.getPiece(row, col);

        if (piece == null || !piece.belongsToPlayer(player)) {
            return false;
        }

        if (playerHasAnyCapture(player)) {
            return false;
        }

        if (piece.isKing()) {
            return board.isEmptyCell(row - 1, col - 1) ||
                    board.isEmptyCell(row - 1, col + 1) ||
                    board.isEmptyCell(row + 1, col - 1) ||
                    board.isEmptyCell(row + 1, col + 1);
        }

        if (player == 'x') {
            return board.isEmptyCell(row - 1, col - 1) ||
                    board.isEmptyCell(row - 1, col + 1);
        }

        return board.isEmptyCell(row + 1, col - 1) ||
                board.isEmptyCell(row + 1, col + 1);
    }

    private boolean playerHasAnyMove(char player) {
        for (int row = 0; row < Board.SIZE; row++) {
            for (int col = 0; col < Board.SIZE; col++) {
                Piece piece = board.getPiece(row, col);
                if (piece != null && piece.belongsToPlayer(player)) {
                    if (hasCaptureFrom(player, row, col) || hasNormalMoveFrom(player, row, col)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}