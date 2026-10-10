import java.util.List;

public class BotPlayer {
    public Game.Move findBestMove(Game game, int depth, char botSymbol) {
        List<Game.Move> moves = game.getValidMoves(botSymbol);
        if (moves.isEmpty()) {
            return null;
        }

        int bestScore = Integer.MIN_VALUE;
        Game.Move bestMove = null;

        for (Game.Move move : moves) {
            Game simulated = new Game(game);
            simulated.move(move.getFrom(), move.getTo());
            int score = minimax(simulated, depth - 1, botSymbol, Integer.MIN_VALUE, Integer.MAX_VALUE);

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }

        return bestMove;
    }

    private int minimax(Game game, int depth, char botSymbol, int alpha, int beta) {
        char winner = game.getWinnerSymbol();
        if (winner != '\0') {
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

    private int evaluateTerminal(char winner, char botSymbol) {
        if (winner == Character.toUpperCase(botSymbol)) {
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
