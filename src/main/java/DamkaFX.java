import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

public class DamkaFX extends Application {
    private Game game;
    private GridPane boardGrid;
    private Label turnLabel;
    private Position selectedPosition;
    private boolean gameOver = false;
    private Button pvpButton;
    private Button botButton;
    private boolean mustContinueCapture = false;

    private final int TILE_SIZE = 70;

    @Override
    public void start(Stage stage) {
        game = new Game();

        turnLabel = new Label("Turn: " + game.getCurrentPlayer().getName());
        pvpButton = new Button("Player vs Player");
        botButton = new Button("Player vs Bot");
        HBox modeButtons = new HBox(10, pvpButton, botButton);

        pvpButton.setOnAction(e -> {
            game = new Game();
            selectedPosition = null;
            gameOver = false;
            turnLabel.setText("Turn: " + game.getCurrentPlayer().getName());
            drawBoard();
        });

        botButton.setOnAction(e -> {
            showMessage("Bot mode coming later");
        });
        boardGrid = new GridPane();

        drawBoard();

        VBox root = new VBox(10, modeButtons, turnLabel, boardGrid);
        Scene scene = new Scene(root);

        stage.setTitle("Damka Game");
        stage.setScene(scene);
        stage.show();
    }

    private void drawBoard() {
        boardGrid.getChildren().clear();

        Board board = game.getBoard();

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Rectangle square = new Rectangle(TILE_SIZE, TILE_SIZE);

                if ((row + col) % 2 == 0) {
                    square.setFill(Color.BEIGE);
                } else {
                    square.setFill(Color.SADDLEBROWN);
                }

                if (selectedPosition != null
                        && selectedPosition.getRow() == row
                        && selectedPosition.getCol() == col) {
                    square.setStroke(Color.YELLOW);
                    square.setStrokeWidth(4);
                }

                int finalRow = row;
                int finalCol = col;

                square.setOnMouseClicked(e -> handleClick(finalRow, finalCol));
                boardGrid.add(square, col, row);

                Piece piece = board.getPiece(row, col);

                if (piece != null) {
                    Circle circle = new Circle(TILE_SIZE / 2.5);

                    if (piece.getSymbol() == 'x' || piece.getSymbol() == 'X') {
                        circle.setFill(Color.BLACK);
                    } else {
                        circle.setFill(Color.RED);
                    }
                    if (piece.isKing()) {
                        circle.setStroke(Color.GOLD);
                        circle.setStrokeWidth(4);
                    }
                    circle.setOnMouseClicked(e -> handleClick(finalRow, finalCol));
                    boardGrid.add(circle, col, row);
                }
            }
        }
    }

    private void handleClick(int row, int col) {
        if (gameOver) {
            return;
        }

        if (selectedPosition == null) {
            Piece piece = game.getBoard().getPiece(row, col);

            if (piece != null && piece.belongsToPlayer(game.getCurrentPlayer().getSymbol())) {
                Position clickedPosition = new Position(row, col);

                if (game.playerMustCapture() && !game.canCaptureAgain(clickedPosition)) {
                    showMessage("You must select a piece that can capture");
                    return;
                }

                selectedPosition = clickedPosition;
                drawBoard();
            }

        } else {

            // Click same selected piece again = cancel selection
            if (selectedPosition.getRow() == row && selectedPosition.getCol() == col) {
                if (mustContinueCapture) {
                    showMessage("You must continue capturing with the same piece");
                    return;
                }

                selectedPosition = null;
                drawBoard();
                return;
            }

            Piece clickedPiece = game.getBoard().getPiece(row, col);

            // Click another own piece = change selection
            if (!mustContinueCapture
                    && clickedPiece != null
                    && clickedPiece.belongsToPlayer(game.getCurrentPlayer().getSymbol())) {

                Position newSelection = new Position(row, col);

                if (game.playerMustCapture() && !game.canCaptureAgain(newSelection)) {
                    showMessage("You must select a piece that can capture");
                    return;
                }

                selectedPosition = newSelection;
                drawBoard();
                return;
            }

            Position target = new Position(row, col);

            boolean wasCapture = Math.abs(target.getRow() - selectedPosition.getRow()) == 2;

            boolean moved = game.move(selectedPosition, target);

            if (moved) {
                String winner = game.getWinner();

                if (winner != null) {
                    gameOver = true;
                    selectedPosition = null;
                    mustContinueCapture = false;
                    showMessage(winner);
                } else if (wasCapture && game.canCaptureAgain(target)) {
                    selectedPosition = target;
                    mustContinueCapture = true;
                    showMessage("You must continue capturing with the same piece");
                } else {
                    selectedPosition = null;
                    mustContinueCapture = false;
                    turnLabel.setText("Turn: " + game.getCurrentPlayer().getName());
                }

                drawBoard();
            } else {
                if (mustContinueCapture) {
                    showMessage("You must continue capturing with the same piece");
                } else if (game.playerMustCapture()) {
                    showMessage("You must capture");
                } else {
                    showMessage("Invalid move");
                }

                drawBoard();
            }
        }
    }

    private void showMessage(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Damka");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}