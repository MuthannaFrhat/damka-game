import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.function.BiConsumer;

/** Draws a board snapshot and animates moves. Call its methods on the JavaFX Thread. */
public class BoardView extends StackPane {
    private static final int ROWS = 8;
    private static final double FRAME_PADDING = 12;
    private static final Color GOLD = Color.web("#E9BD63");

    private final GridPane boardGrid = new GridPane();
    private final Pane animationOverlay = new Pane();
    private final StackPane[][] cells = new StackPane[ROWS][ROWS];
    private final Rectangle[][] squares = new Rectangle[ROWS][ROWS];
    private final Circle[][] pieces = new Circle[ROWS][ROWS];
    private final BiConsumer<Integer, Integer> onCellClick;

    // This is a display copy. Game rules and accepted moves belong to Game, not this view.
    private String snapshot = ".".repeat(64);
    private Position selectedCell;
    private Circle selectionRing;
    private double tileSize = 68;
    private ParallelTransition movementAnimation;

    public BoardView(BiConsumer<Integer, Integer> onCellClick) {
        this.onCellClick = onCellClick;
        animationOverlay.setMouseTransparent(true);
        getChildren().addAll(boardGrid, animationOverlay);
        getStyleClass().add("board-frame");
        setPadding(new Insets(FRAME_PADDING));
        updateDimensions();
        drawSnapshot();
    }

    public void showBoard(String snapshot, Position selection) {
        this.snapshot = snapshot == null ? ".".repeat(64) : snapshot;
        selectedCell = selection;
        drawSnapshot();
    }

    public void resizeToFit(double availableWidth, double availableHeight) {
        double availableSide = Math.min(availableWidth, availableHeight);
        if (availableSide <= 0) {
            return;
        }
        tileSize = Math.max(30, Math.min(78, (availableSide - FRAME_PADDING * 2) / ROWS));
        updateDimensions();
        // Resize the existing nodes during a move; rebuilding would reveal the hidden source.
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < ROWS; col++) {
                cells[row][col].setPrefSize(tileSize, tileSize);
                squares[row][col].setWidth(tileSize);
                squares[row][col].setHeight(tileSize);
                if (pieces[row][col] != null) {
                    resizePiece(pieces[row][col]);
                }
            }
        }
        if (selectionRing != null) {
            selectionRing.setRadius(tileSize * 0.43);
            selectionRing.setStrokeWidth(Math.max(2, tileSize * 0.035));
        }
    }

    private void updateDimensions() {
        double boardSide = tileSize * ROWS;
        boardGrid.setPrefSize(boardSide, boardSide);
        boardGrid.setMaxSize(boardSide, boardSide);
        animationOverlay.setPrefSize(boardSide, boardSide);
        animationOverlay.setMaxSize(boardSide, boardSide);
        double frameSide = boardSide + FRAME_PADDING * 2;
        setMinSize(frameSide, frameSide);
        setPrefSize(frameSide, frameSide);
        setMaxSize(frameSide, frameSide);
    }

    private void drawSnapshot() {
        boardGrid.getChildren().clear();
        selectionRing = null;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < ROWS; col++) {
                Rectangle square = new Rectangle(tileSize, tileSize);
                square.setFill(Color.web((row + col) % 2 == 0 ? "#F3ECD9" : "#416457"));
                squares[row][col] = square;
                StackPane cell = new StackPane(square);
                cell.setPrefSize(tileSize, tileSize);
                cells[row][col] = cell;
                pieces[row][col] = null;

                if (selectedCell != null && selectedCell.getRow() == row && selectedCell.getCol() == col) {
                    selectionRing = new Circle(tileSize * 0.43, Color.TRANSPARENT);
                    selectionRing.setStroke(GOLD);
                    selectionRing.setStrokeWidth(Math.max(2, tileSize * 0.035));
                    selectionRing.setMouseTransparent(true);
                    cell.getChildren().add(selectionRing);
                }

                char symbol = snapshot.charAt(row * ROWS + col);
                if (symbol != '.') {
                    Circle piece = createPiece(symbol);
                    pieces[row][col] = piece;
                    cell.getChildren().add(piece);
                }
                int clickedRow = row;
                int clickedCol = col;
                cell.setOnMouseClicked(event -> onCellClick.accept(clickedRow, clickedCol));
                boardGrid.add(cell, col, row);
            }
        }
    }

    // The same appearance is used for stationary and moving pieces, in BOT and online games.
    private Circle createPiece(char symbol) {
        boolean playerX = Character.toLowerCase(symbol) == 'x';
        Color highlight = Color.web(playerX ? "#45685B" : "#F39375");
        Color edge = Color.web(playerX ? "#172D27" : "#C94F40");
        Circle piece = new Circle();
        piece.setFill(new RadialGradient(0, 0, 0.34, 0.28, 0.9, true,
                CycleMethod.NO_CYCLE, new Stop(0, highlight), new Stop(1, edge)));
        piece.setStroke(Character.isUpperCase(symbol) ? GOLD : edge.brighter());
        piece.setUserData(Character.isUpperCase(symbol));
        resizePiece(piece);
        DropShadow shadow = new DropShadow(5, Color.color(0.05, 0.12, 0.09, 0.35));
        shadow.setOffsetY(3);
        piece.setEffect(shadow);
        piece.setMouseTransparent(true);
        return piece;
    }

    private void resizePiece(Circle piece) {
        piece.setRadius(tileSize * 0.35);
        boolean king = Boolean.TRUE.equals(piece.getUserData());
        piece.setStrokeWidth(king ? Math.max(3, tileSize * 0.06) : 1.4);
    }

    public boolean isAnimating() {
        return movementAnimation != null;
    }

    public void animateMove(Position from, Position to, Piece movingPiece,
                            Position captured, Runnable done) {
        Circle source = pieces[from.getRow()][from.getCol()];
        if (source == null) {
            done.run();
            return;
        }
        source.setVisible(false);
        Circle moving = createPiece(movingPiece.getSymbol());
        animationOverlay.getChildren().setAll(moving);

        SimpleDoubleProperty progress = new SimpleDoubleProperty(0);
        progress.addListener((observable, previous, current) ->
                positionMovingPiece(moving, from, to, current.doubleValue()));
        positionMovingPiece(moving, from, to, 0);
        Timeline travel = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(progress, 0)),
                new KeyFrame(Duration.millis(320), new KeyValue(progress, 1, Interpolator.EASE_BOTH)));
        movementAnimation = new ParallelTransition(travel);

        if (captured != null) {
            Circle capturedPiece = pieces[captured.getRow()][captured.getCol()];
            if (capturedPiece != null) {
                FadeTransition fade = new FadeTransition(Duration.millis(240), capturedPiece);
                fade.setDelay(Duration.millis(80));
                fade.setFromValue(1);
                fade.setToValue(0);
                movementAnimation.getChildren().add(fade);
            }
        }
        movementAnimation.setOnFinished(event -> {
            animationOverlay.getChildren().clear();
            movementAnimation = null;
            done.run(); // The controller now displays the accepted, updated snapshot.
        });
        movementAnimation.play();
    }

    private void positionMovingPiece(Circle moving, Position from, Position to, double progress) {
        // Recalculate each frame: resizing/scrolling must not change the move's endpoints.
        Point2D start = cellCenterInOverlay(from);
        Point2D end = cellCenterInOverlay(to);
        resizePiece(moving);
        moving.setCenterX(start.getX() + (end.getX() - start.getX()) * progress);
        moving.setCenterY(start.getY() + (end.getY() - start.getY()) * progress);
    }

    private Point2D cellCenterInOverlay(Position position) {
        StackPane cell = cells[position.getRow()][position.getCol()];
        Bounds sceneBounds = cell.localToScene(cell.getBoundsInLocal());
        return animationOverlay.sceneToLocal(
                sceneBounds.getMinX() + sceneBounds.getWidth() / 2,
                sceneBounds.getMinY() + sceneBounds.getHeight() / 2);
    }

    public void stopAnimation() {
        if (movementAnimation != null) {
            movementAnimation.setOnFinished(null);
            movementAnimation.stop();
            movementAnimation = null;
        }
        animationOverlay.getChildren().clear();
        drawSnapshot(); // Restore pieces hidden or faded by the cancelled move.
    }
}
