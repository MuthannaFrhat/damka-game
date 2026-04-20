public class Piece {
    private char symbol; // x, o, X, O

    public Piece(char symbol) {
        this.symbol = symbol;
    }

    public char getSymbol() {
        return symbol;
    }

    public void setSymbol(char symbol) {
        this.symbol = symbol;
    }

    public boolean isKing() {
        return symbol == 'X' || symbol == 'O';
    }

    public boolean belongsToPlayer(char player) {
        if (player == 'x') {
            return symbol == 'x' || symbol == 'X';
        }
        return symbol == 'o' || symbol == 'O';
    }

    public boolean isOpponent(char player) {
        if (player == 'x') {
            return symbol == 'o' || symbol == 'O';
        }
        return symbol == 'x' || symbol == 'X';
    }
}