public class Player {
    private char symbol;
    private String name;
    private boolean bot;

    public Player(char symbol, String name, boolean bot) {
        this.symbol = symbol;
        this.name = name;
        this.bot = bot;
    }

    public char getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }

    public boolean isBot() {
        return bot;
    }
}