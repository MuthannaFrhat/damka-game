public class Player {
    private char symbol;   // x or o
    private String name;   // Player 1 / Player 2

    public Player(char symbol, String name) {
        this.symbol = symbol;
        this.name = name;
    }

    public char getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }
}