import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameRulesTest {
    @Test
    void rejectedContinuationCannotSwitchToAnotherCapturingPiece() throws Exception {
        Game game = captureChainPosition();
        assertTrue(game.move(position(5, 2), position(3, 4)));
        String boardBefore = snapshot(game);

        // Both X pieces can capture, but only the piece that started may continue.
        assertFalse(game.move(position(5, 0), position(3, 2)));
        assertNotNull(game.getMoveError());
        assertEquals(boardBefore, snapshot(game));
        assertEquals('x', game.getCurrentPlayer().getSymbol());
        assertPosition(3, 4, game.getCaptureContinuationPosition());

        assertTrue(game.move(position(3, 4), position(1, 6)));
        assertNull(game.getCaptureContinuationPosition());
        assertEquals('o', game.getCurrentPlayer().getSymbol());
        assertEquals('o', game.getBoard().getPiece(4, 1).getSymbol());
    }

    @Test
    void selectionAndGeneratedMovesFollowTheCaptureChain() throws Exception {
        Game game = captureChainPosition();
        assertTrue(game.move(position(5, 2), position(3, 4)));

        assertTrue(game.canSelectPiece(position(3, 4)));
        assertFalse(game.canSelectPiece(position(5, 0)));
        assertFalse(game.canSelectPiece(position(2, 5)));
        List<Game.Move> moves = game.getValidMoves('x');
        assertEquals(1, moves.size());
        assertPosition(3, 4, moves.get(0).getFrom());
        assertPosition(1, 6, moves.get(0).getTo());
    }

    @Test
    void copyingAChainKeepsItsRulesWithoutSharingBoardChanges() throws Exception {
        Game original = captureChainPosition();
        assertTrue(original.move(position(5, 2), position(3, 4)));
        String originalBoard = snapshot(original);
        Game copy = new Game(original);

        assertPosition(3, 4, copy.getCaptureContinuationPosition());
        assertFalse(copy.move(position(5, 0), position(3, 2)));
        assertTrue(copy.move(position(3, 4), position(1, 6)));

        assertEquals(originalBoard, snapshot(original));
        assertEquals('x', original.getCurrentPlayer().getSymbol());
        assertPosition(3, 4, original.getCaptureContinuationPosition());
        assertNotNull(original.getBoard().getPiece(2, 5));
        assertNull(copy.getBoard().getPiece(2, 5));
        assertEquals('o', copy.getCurrentPlayer().getSymbol());

        // Pieces are independent too: promoting the copy must not crown the original.
        assertTrue(copy.move(position(4, 1), position(5, 2)));
        assertTrue(copy.move(position(1, 6), position(0, 7)));
        assertEquals('X', copy.getBoard().getPiece(0, 7).getSymbol());
        assertEquals('x', original.getBoard().getPiece(3, 4).getSymbol());
    }

    @Test
    void anAvailableCaptureRejectsAnOtherwiseLegalWalk() throws Exception {
        Game game = new Game();
        Piece[][] cells = clearBoard(game);
        cells[5][0] = new Piece('x');
        cells[5][4] = new Piece('x');
        cells[4][1] = new Piece('o');
        cells[0][7] = new Piece('o');
        String before = snapshot(game);

        assertFalse(game.move(position(5, 4), position(4, 5)));
        assertEquals(before, snapshot(game));
        assertEquals('x', game.getCurrentPlayer().getSymbol());
        assertNull(game.getCaptureContinuationPosition());
        assertFalse(game.canSelectPiece(position(5, 4)));
        assertTrue(game.canSelectPiece(position(5, 0)));
        List<Game.Move> moves = game.getValidMoves('x');
        assertEquals(1, moves.size());
        assertPosition(5, 0, moves.get(0).getFrom());
        assertPosition(3, 2, moves.get(0).getTo());
        assertTrue(game.move(position(5, 0), position(3, 2)));
    }

    @Test
    void openingMovesChangeTurnsAndExposeAcceptedMoveMetadata() {
        Game game = new Game();
        assertEquals('\0', game.getWinnerSymbol());
        assertTrue(game.move(position(5, 0), position(4, 1)));
        assertEquals('o', game.getCurrentPlayer().getSymbol());
        Game.Move move = game.getLastMove();
        assertPosition(5, 0, move.getFrom());
        assertPosition(4, 1, move.getTo());
        assertEquals('x', move.getMovingSymbol());
        assertNull(move.getCapturedPosition());
        assertFalse(move.isCapture());
        assertTrue(game.move(position(2, 1), position(3, 0)));
        assertEquals('x', game.getCurrentPlayer().getSymbol());
    }

    @Test
    void invalidRequestsLeaveTheCurrentPositionUsable() {
        Game game = new Game();
        String before = snapshot(game);
        assertFalse(game.move(position(5, 0), position(-1, 1)));
        assertFalse(game.move(position(2, 1), position(3, 0)));
        assertFalse(game.move(position(5, 0), position(5, 1)));
        assertEquals(before, snapshot(game));
        assertEquals('x', game.getCurrentPlayer().getSymbol());
        assertNull(game.getCaptureContinuationPosition());
        assertTrue(game.move(position(5, 0), position(4, 1)));
    }

    @Test
    void regularPiecesMoveForwardWhileKingsCanMoveBackward() throws Exception {
        Game xGame = new Game();
        Piece[][] xCells = clearBoard(xGame);
        xCells[5][2] = new Piece('x');
        xCells[0][1] = new Piece('o');
        assertFalse(xGame.move(position(5, 2), position(6, 3)));
        assertTrue(xGame.move(position(5, 2), position(4, 3)));

        Game oGame = gameOnOTurn();
        Piece[][] oCells = clearBoard(oGame);
        oCells[2][3] = new Piece('o');
        oCells[7][0] = new Piece('x');
        assertFalse(oGame.move(position(2, 3), position(1, 2)));
        assertTrue(oGame.move(position(2, 3), position(3, 2)));

        Game kingGame = new Game();
        Piece[][] kingCells = clearBoard(kingGame);
        kingCells[5][2] = new Piece('X');
        kingCells[0][1] = new Piece('o');
        assertTrue(kingGame.move(position(5, 2), position(6, 3)));
    }

    @Test
    void kingsCanCaptureBackwardButRegularPiecesCannot() throws Exception {
        Game regular = new Game();
        Piece[][] cells = clearBoard(regular);
        cells[3][2] = new Piece('x');
        cells[4][3] = new Piece('o');
        cells[0][1] = new Piece('o');
        String before = snapshot(regular);
        assertFalse(regular.move(position(3, 2), position(5, 4)));
        assertEquals(before, snapshot(regular));

        cells[3][2].makeKing();
        assertTrue(regular.move(position(3, 2), position(5, 4)));
        assertNull(regular.getBoard().getPiece(4, 3));
        assertEquals('X', regular.getBoard().getPiece(5, 4).getSymbol());
        assertTrue(regular.getLastMove().isCapture());
        assertPosition(4, 3, regular.getLastMove().getCapturedPosition());
    }

    @Test
    void bothPlayersPromoteOnTheirOppositeEdge() throws Exception {
        Game xGame = new Game();
        Piece[][] xCells = clearBoard(xGame);
        xCells[1][0] = new Piece('x');
        xCells[2][7] = new Piece('o');
        assertTrue(xGame.move(position(1, 0), position(0, 1)));
        assertEquals('X', xGame.getBoard().getPiece(0, 1).getSymbol());
        assertEquals('x', xGame.getLastMove().getMovingSymbol());

        Game oGame = gameOnOTurn();
        Piece[][] oCells = clearBoard(oGame);
        oCells[6][1] = new Piece('o');
        oCells[5][0] = new Piece('x');
        assertTrue(oGame.move(position(6, 1), position(7, 2)));
        assertEquals('O', oGame.getBoard().getPiece(7, 2).getSymbol());
        assertEquals('o', oGame.getLastMove().getMovingSymbol());
    }

    @Test
    void promotionDuringCaptureAllowsARequiredBackwardContinuation() throws Exception {
        Game game = new Game();
        Piece[][] cells = clearBoard(game);
        cells[2][1] = new Piece('x');
        cells[1][2] = new Piece('o');
        cells[1][4] = new Piece('o');
        cells[6][7] = new Piece('o');

        assertTrue(game.move(position(2, 1), position(0, 3)));
        assertEquals('X', game.getBoard().getPiece(0, 3).getSymbol());
        assertEquals('x', game.getLastMove().getMovingSymbol());
        assertEquals('x', game.getCurrentPlayer().getSymbol());
        assertPosition(0, 3, game.getCaptureContinuationPosition());
        List<Game.Move> moves = game.getValidMoves('x');
        assertEquals(1, moves.size());
        assertPosition(2, 5, moves.get(0).getTo());

        assertTrue(game.move(position(0, 3), position(2, 5)));
        assertEquals('X', game.getLastMove().getMovingSymbol());
        assertPosition(1, 4, game.getLastMove().getCapturedPosition());
        assertEquals('o', game.getCurrentPlayer().getSymbol());
        assertNull(game.getCaptureContinuationPosition());
    }

    @Test
    void winnerIdentityDoesNotDependOnDisplayText() throws Exception {
        Game xWins = new Game();
        Piece[][] xCells = clearBoard(xWins);
        xCells[1][0] = new Piece('x');
        xCells[7][6] = new Piece('o'); // O has a piece but cannot move forward.
        assertEquals('X', xWins.getWinnerSymbol());

        Game oWins = new Game();
        Piece[][] oCells = clearBoard(oWins);
        oCells[2][1] = new Piece('o'); // X has no remaining pieces.
        assertEquals('O', oWins.getWinnerSymbol());
    }

    private Game captureChainPosition() throws Exception {
        Game game = new Game();
        Piece[][] cells = clearBoard(game);
        cells[5][0] = new Piece('x');
        cells[5][2] = new Piece('x');
        cells[4][1] = new Piece('o');
        cells[4][3] = new Piece('o');
        cells[2][5] = new Piece('o');
        return game;
    }

    private Game gameOnOTurn() {
        Game game = new Game();
        assertTrue(game.move(position(5, 0), position(4, 1)));
        return game;
    }

    private Piece[][] clearBoard(Game game) throws Exception {
        // Sparse fixtures use the existing field rather than adding test setters to production.
        Field boardField = Board.class.getDeclaredField("board");
        boardField.setAccessible(true);
        Piece[][] cells = (Piece[][]) boardField.get(game.getBoard());
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                cells[row][col] = null;
            }
        }
        return cells;
    }

    private String snapshot(Game game) {
        StringBuilder result = new StringBuilder(64);
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Piece piece = game.getBoard().getPiece(row, col);
                result.append(piece == null ? '.' : piece.getSymbol());
            }
        }
        return result.toString();
    }

    private Position position(int row, int col) {
        return new Position(row, col);
    }

    private void assertPosition(int row, int col, Position actual) {
        assertNotNull(actual);
        assertEquals(row, actual.getRow());
        assertEquals(col, actual.getCol());
    }
}
