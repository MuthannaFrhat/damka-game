public class Board {
    private Piece[][] board;

    public Board() {
        board = new Piece[8][8];
        initializeBoard();
    }

    public Board(Board other) {
        board = new Piece[8][8];
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Piece otherPiece = other.board[row][col];
                if (otherPiece != null) {
                    board[row][col] = new Piece(otherPiece.getSymbol());
                } else {
                    board[row][col] = null;
                }
            }
        }
    }

    private void initializeBoard() {
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                board[row][col] = null;
            }
        }

        // Player o at top
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 8; col++) {
                if ((row + col) % 2 == 1) {
                    board[row][col] = new Piece('o');
                }
            }
        }

        // Player x at bottom
        for (int row = 5; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                if ((row + col) % 2 == 1) {
                    board[row][col] = new Piece('x');
                }
            }
        }
    }

    public Piece getPiece(int row, int col) {
        if (!isInsideBoard(row, col)) {
            return null;
        }
        return board[row][col];
    }

    public boolean isEmpty(int row, int col) {
        return isInsideBoard(row, col) && board[row][col] == null;
    }

    public boolean isInsideBoard(int row, int col) {
        return row >= 0 && row < 8 && col >= 0 && col < 8;
    }

    public void movePiece(Position from, Position to) {
        Piece piece = board[from.getRow()][from.getCol()];

        board[to.getRow()][to.getCol()] = piece;
        board[from.getRow()][from.getCol()] = null;
    }

    public void removePiece(Position position) {
        board[position.getRow()][position.getCol()] = null;
    }
}
