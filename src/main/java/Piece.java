public class Piece {
    private char symbol;

    public Piece(char symbol) {
        this.symbol = symbol;
    }

    public char getSymbol() {
        return symbol;
    }

    public boolean isKing() {
        return Character.isUpperCase(symbol);
    }

    public boolean belongsToPlayer(char player) {
        return Character.toLowerCase(symbol) == Character.toLowerCase(player);
    }

    public boolean isOpponent(char player) {
        return Character.toLowerCase(symbol) != Character.toLowerCase(player);
    }

    public void makeKing() {
        symbol = Character.toUpperCase(symbol);
    }
}