public class Board {
    public static final int SIZE = 8;
    private Piece[][] board;

    public Board() {
        board = new Piece[SIZE][SIZE];
        initializeBoard();
    }

    public void initializeBoard() {
        int row;
        int col;

        for (row = 0; row < SIZE; row++) {
            for (col = 0; col < SIZE; col++) {
                board[row][col] = null;
            }
        }

        for (row = 0; row < 3; row++) {
            for (col = 0; col < SIZE; col++) {
                if ((row + col) % 2 == 1) {
                    board[row][col] = new Piece('o');
                }
            }
        }

        for (row = 5; row < SIZE; row++) {
            for (col = 0; col < SIZE; col++) {
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

    public void setPiece(int row, int col, Piece piece) {
        if (isInsideBoard(row, col)) {
            board[row][col] = piece;
        }
    }

    public boolean isInsideBoard(int row, int col) {
        return row >= 0 && row < SIZE && col >= 0 && col < SIZE;
    }

    public boolean isEmptyCell(int row, int col) {
        return isInsideBoard(row, col) && board[row][col] == null;
    }

    public void printBoard() {
        int row;
        int col;

        System.out.println("   A B C D E F G H");
        for (row = 0; row < SIZE; row++) {
            System.out.print((row + 1) + "  ");
            for (col = 0; col < SIZE; col++) {
                if (board[row][col] == null) {
                    System.out.print(". ");
                } else {
                    char symbol = board[row][col].getSymbol();
                    if (symbol == 'X' || symbol == 'O') {
                        System.out.print("& ");
                    } else {
                        System.out.print(symbol + " ");
                    }
                }
            }
            System.out.println();
        }
    }

    public void makeMove(int fromRow, int fromCol, int toRow, int toCol) {
        Piece piece = board[fromRow][fromCol];
        board[toRow][toCol] = piece;
        board[fromRow][fromCol] = null;

        if (Math.abs(toRow - fromRow) == 2 && Math.abs(toCol - fromCol) == 2) {
            int midRow = (fromRow + toRow) / 2;
            int midCol = (fromCol + toCol) / 2;
            board[midRow][midCol] = null;
        }
    }

    public void promoteIfNeeded(int row, int col) {
        if (board[row][col] == null) {
            return;
        }

        if (board[row][col].getSymbol() == 'x' && row == 0) {
            board[row][col].setSymbol('X');
        } else if (board[row][col].getSymbol() == 'o' && row == 7) {
            board[row][col].setSymbol('O');
        }
    }

    public int countPiecesForPlayer(char player) {
        int row;
        int col;
        int count = 0;

        for (row = 0; row < SIZE; row++) {
            for (col = 0; col < SIZE; col++) {
                if (board[row][col] != null && board[row][col].belongsToPlayer(player)) {
                    count++;
                }
            }
        }

        return count;
    }
}