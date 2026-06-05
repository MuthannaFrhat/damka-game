import java.util.List;

public class BotPlayer {
    public static class MoveChoice {
        private final Position from;
        private final Position to;

        public MoveChoice(Position from, Position to) {
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

    public MoveChoice findBestMove(Game game, int depth, char botSymbol) {
        List<Game.Move> moves = game.getValidMoves(botSymbol);
        if (moves.isEmpty()) {
            return null;
        }

        int bestScore = Integer.MIN_VALUE;
        MoveChoice bestMove = null;

        for (Game.Move move : moves) {
            Game simulated = new Game(game);
            simulated.move(move.getFrom(), move.getTo());
            int score = minimax(simulated, depth - 1, botSymbol, Integer.MIN_VALUE, Integer.MAX_VALUE);

            if (score > bestScore) {
                bestScore = score;
                bestMove = new MoveChoice(move.getFrom(), move.getTo());
            }
        }

        return bestMove;
    }

    private int minimax(Game game, int depth, char botSymbol, int alpha, int beta) {
        String winner = game.getWinner();
        if (winner != null) {
            return evaluateTerminal(winner, botSymbol);
        }

        if (depth <= 0) {
            return evaluateBoard(game, botSymbol);
        }

        char currentPlayer = game.getCurrentPlayer().getSymbol();
        List<Game.Move> moves = game.getValidMoves(currentPlayer);

        if (moves.isEmpty()) {
            return evaluateBoard(game, botSymbol);
        }

        if (currentPlayer == botSymbol) {
            int maxEval = Integer.MIN_VALUE;
            for (Game.Move move : moves) {
                Game simulated = new Game(game);
                simulated.move(move.getFrom(), move.getTo());
                int eval = minimax(simulated, depth - 1, botSymbol, alpha, beta);
                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);
                if (beta <= alpha) {
                    break;
                }
            }
            return maxEval;
        } else {
            int minEval = Integer.MAX_VALUE;
            for (Game.Move move : moves) {
                Game simulated = new Game(game);
                simulated.move(move.getFrom(), move.getTo());
                int eval = minimax(simulated, depth - 1, botSymbol, alpha, beta);
                minEval = Math.min(minEval, eval);
                beta = Math.min(beta, eval);
                if (beta <= alpha) {
                    break;
                }
            }
            return minEval;
        }
    }

    private int evaluateTerminal(String winner, char botSymbol) {
        if (winner.contains("Player " + Character.toUpperCase(botSymbol))) {
            return 10000;
        }
        return -10000;
    }

    private int evaluateBoard(Game game, char botSymbol) {
        char opponent = botSymbol == 'x' ? 'o' : 'x';
        int score = 0;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Piece piece = game.getBoard().getPiece(row, col);
                if (piece == null) {
                    continue;
                }

                int value = piece.isKing() ? 6 : 3;
                if (piece.belongsToPlayer(botSymbol)) {
                    score += value;
                } else if (piece.belongsToPlayer(opponent)) {
                    score -= value;
                }
            }
        }

        return score;
    }
}