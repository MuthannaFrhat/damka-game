import java.util.List;

/** A repeatable match for the demo, played through normal board clicks. */
public final class DemoMoves {
    /** Board coordinates are zero-based: row 0 is the top row. */
    public record Step(int fromRow, int fromCol, int toRow, int toCol) {}

    private DemoMoves() {}

    /**
     * Starts with the normal board and ends with a Green win under Game's rules.
     * A capture chain can keep the same player's turn for consecutive steps.
     */
    public static List<Step> onlineMatch() {
        return List.of(
                new Step(5, 0, 4, 1),
                new Step(2, 5, 3, 4),
                new Step(5, 6, 4, 7),
                new Step(3, 4, 4, 3),
                new Step(5, 4, 3, 2),
                new Step(2, 1, 4, 3),
                new Step(5, 2, 3, 4),
                new Step(2, 3, 4, 5),
                new Step(6, 1, 5, 0),
                new Step(1, 4, 2, 5),
                new Step(6, 5, 5, 6),
                new Step(2, 7, 3, 6),
                new Step(5, 6, 3, 4),
                new Step(2, 5, 4, 3),
                new Step(4, 7, 2, 5),
                new Step(1, 6, 3, 4),
                new Step(7, 0, 6, 1),
                new Step(4, 3, 5, 2),
                new Step(6, 1, 4, 3),
                new Step(4, 3, 2, 5),
                new Step(0, 3, 1, 4),
                new Step(2, 5, 0, 3),
                new Step(0, 3, 2, 1),
                new Step(1, 0, 3, 2),
                new Step(4, 1, 2, 3),
                new Step(0, 7, 1, 6),
                new Step(6, 3, 5, 2),
                new Step(1, 6, 2, 5),
                new Step(7, 6, 6, 5),
                new Step(0, 1, 1, 2),
                new Step(2, 3, 0, 1),
                new Step(2, 5, 3, 4),
                new Step(0, 1, 1, 2),
                new Step(0, 5, 1, 6),
                new Step(6, 7, 5, 6),
                new Step(3, 4, 4, 3),
                new Step(5, 2, 3, 4),
                new Step(1, 6, 2, 5),
                new Step(3, 4, 1, 6));
    }

    public static char expectedWinner() {
        return 'X';
    }
}
