import javafx.application.Application;
import javafx.animation.TranslateTransition;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.scene.Scene;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

public class DamkaFX extends Application {
    private Game game;
    private GridPane boardGrid;
    private Label turnLabel;
    private Position selectedPosition;
    private boolean gameOver = false;
    private Button pvpButton;
    private Button botButton;
    private ComboBox<String> difficultyBox;
    private HBox difficultyControls;
    private Pane animationPane;
    private StackPane toastPane;
    private BotPlayer botPlayer;
    private boolean botMode = false;
    private int botDepth = 1;
    private boolean mustContinueCapture = false;

    private RoomConnection roomConnection;
    private boolean onlineMode;
    private String onlineBoard;
    private char onlineColor;
    private Label roomStatus;
    private TextField roomCode;
    private TextField serverAddress;
    private TextField serverPort;

    private final int TILE_SIZE = 70;

    @Override
    public void start(Stage stage) {
        game = new Game(false, true);
        botMode = true;

        turnLabel = new Label("Turn: " + game.getCurrentPlayer().getName());
        pvpButton = new Button("Online rooms");
        botButton = new Button("Player vs Bot");
        difficultyBox = new ComboBox<>();
        difficultyBox.getItems().addAll("Easy", "Medium", "Hard");
        difficultyBox.setValue("Easy");
        botPlayer = new BotPlayer();

        difficultyControls = new HBox(10, new Label("Difficulty:"), difficultyBox);
        difficultyControls.setVisible(false);
        difficultyControls.setManaged(false);

        HBox modeButtons = new HBox(10, pvpButton, botButton);

        roomStatus = new Label("Local BOT game");
        serverAddress = new TextField("localhost");
        serverAddress.setPrefColumnCount(9);
        serverPort = new TextField("5000");
        serverPort.setPrefColumnCount(4);
        roomCode = new TextField();
        roomCode.setPromptText("Room code");
        roomCode.setPrefColumnCount(6);
        Button create = new Button("Create room");
        Button join = new Button("Join room");
        Button leave = new Button("Leave room");
        VBox roomControls = new VBox(8,
                new HBox(8, new Label("Server:"), serverAddress, serverPort),
                new HBox(8, create, roomCode, join, leave));
        roomControls.setVisible(false);
        roomControls.setManaged(false);
        pvpButton.setOnAction(e -> {
            roomControls.setVisible(true);
            roomControls.setManaged(true);
        });
        create.setOnAction(e -> connectToRoom("CREATE_ROOM"));
        join.setOnAction(e -> {
            String code = roomCode.getText().trim().toUpperCase(java.util.Locale.ROOT);
            if (!code.matches("[A-Z0-9]{6}")) { showMessage("Enter a six-character room code"); return; }
            connectToRoom("JOIN_ROOM " + code);
        });
        leave.setOnAction(e -> {
            closeRoomConnection();
            onlineMode = true;
            botMode = false;
            drawBoard();
            turnLabel.setText("Room left — create or join another room");
            roomStatus.setText("Not connected");
        });

        botButton.setOnAction(e -> {
            closeRoomConnection();
            roomControls.setVisible(false);
            roomControls.setManaged(false);
            roomStatus.setText("Local BOT game");
            botMode = true;
            botDepth = getDepthFromDifficulty();
            game = new Game(false, true);
            selectedPosition = null;
            gameOver = false;
            mustContinueCapture = false;
            turnLabel.setText("Bot mode selected — choose difficulty");
            difficultyControls.setVisible(true);
            difficultyControls.setManaged(true);
            drawBoard();
        });
        
        difficultyBox.setOnAction(e -> {
            botDepth = getDepthFromDifficulty();
            if (botMode) {
                difficultyControls.setVisible(false);
                difficultyControls.setManaged(false);
                turnLabel.setText("Turn: " + game.getCurrentPlayer().getName());
                maybePerformBotTurn();
            }
        });

        styleButton(pvpButton, "linear-gradient(#4A90E2, #357ABD)");
        styleButton(botButton, "linear-gradient(#50E3C2, #2DB59C)");
        difficultyBox.setStyle("-fx-font-weight: bold; -fx-pref-width: 120px;");
        turnLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        boardGrid = new GridPane();
        boardGrid.setPrefSize(TILE_SIZE * 8, TILE_SIZE * 8);
        boardGrid.setMaxSize(TILE_SIZE * 8, TILE_SIZE * 8);
        boardGrid.setStyle("-fx-border-color: #888888; -fx-border-width: 4; -fx-border-radius: 10; -fx-background-radius: 10;");

        animationPane = new Pane();
        animationPane.setPickOnBounds(false);
        animationPane.setMouseTransparent(true);
        animationPane.setPrefSize(TILE_SIZE * 8, TILE_SIZE * 8);

        StackPane boardStack = new StackPane(boardGrid, animationPane);
        boardStack.setPrefSize(TILE_SIZE * 8, TILE_SIZE * 8);

        drawBoard();

        VBox root = new VBox(15, modeButtons, roomControls, roomStatus, difficultyControls, turnLabel, boardStack);
        root.setStyle("-fx-padding: 20; -fx-background-color: linear-gradient(to bottom right, #ece9e6, #ffffff);");
        modeButtons.setStyle("-fx-padding: 10;");
        difficultyControls.setStyle("-fx-padding: 6;");

        // Toast / notification overlay
        toastPane = new StackPane();
        toastPane.setPickOnBounds(false);
        toastPane.setMouseTransparent(true);

        StackPane appRoot = new StackPane(root, toastPane);
        StackPane.setAlignment(toastPane, Pos.TOP_CENTER);
        StackPane.setMargin(toastPane, new Insets(10, 0, 0, 0));

        Scene scene = new Scene(appRoot);

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

                StackPane cell = new StackPane();
                cell.setPrefSize(TILE_SIZE, TILE_SIZE);
                cell.getChildren().add(square);

                // Selection ring
                if (selectedPosition != null
                        && selectedPosition.getRow() == row
                        && selectedPosition.getCol() == col) {
                    Circle ring = new Circle(TILE_SIZE * 0.38);
                    ring.setFill(Color.TRANSPARENT);
                    ring.setStroke(Color.DODGERBLUE);
                    ring.setStrokeWidth(5);
                    ring.setMouseTransparent(true);
                    cell.getChildren().add(ring);
                }

                Piece piece;
                if (onlineMode) {
                    char symbol = onlineBoard == null ? '.' : onlineBoard.charAt(row * 8 + col);
                    piece = symbol == '.' ? null : new Piece(symbol);
                } else { piece = board.getPiece(row, col); }

                if (piece != null) {
                    double radius = TILE_SIZE * 0.35;
                    Circle circle = new Circle(radius);

                    if (piece.getSymbol() == 'x' || piece.getSymbol() == 'X') {
                        circle.setFill(Color.BLACK);
                    } else {
                        circle.setFill(Color.RED);
                    }

                    if (piece.isKing()) {
                        circle.setStroke(Color.GOLD);
                        circle.setStrokeWidth(4);
                    }

                    circle.setMouseTransparent(true);
                    DropShadow ds = new DropShadow(6, Color.color(0, 0, 0, 0.45));
                    circle.setEffect(ds);

                    cell.getChildren().add(circle);
                }

                final int finalRow = row;
                final int finalCol = col;
                cell.setOnMouseClicked(e -> handleClick(finalRow, finalCol));

                boardGrid.add(cell, col, row);
            }
        }
    }

    private void handleClick(int row, int col) {
        if (onlineMode) { return; } // Network moves are deliberately not implemented yet.
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

            Piece movingPiece = game.getBoard().getPiece(selectedPosition.getRow(), selectedPosition.getCol());
            Piece movingPieceCopy = null;
            if (movingPiece != null) {
                movingPieceCopy = new Piece(movingPiece.getSymbol());
            }

            boolean wasCapture = Math.abs(target.getRow() - selectedPosition.getRow()) == 2;
            boolean moved = game.move(selectedPosition, target);

            if (moved) {
                if (movingPieceCopy != null) {
                    animateMove(selectedPosition, target, movingPieceCopy, wasCapture);
                } else {
                    finalizeMoveOutcome(target, wasCapture);
                }
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

    private void connectToRoom(String request) {
        int port;
        try {
            port = Integer.parseInt(serverPort.getText().trim());
            if (port < 1 || port > 65535) { throw new NumberFormatException(); }
        } catch (NumberFormatException exception) { showMessage("Port must be 1-65535"); return; }
        String host = serverAddress.getText().trim();
        if (host.isEmpty()) { showMessage("Enter a server address"); return; }
        closeRoomConnection();
        onlineMode = true;
        botMode = false;
        gameOver = false;
        difficultyControls.setVisible(false);
        difficultyControls.setManaged(false);
        turnLabel.setText("Connecting...");
        roomStatus.setText("Connecting to the room server");
        drawBoard();
        RoomConnection connection = new RoomConnection();
        roomConnection = connection;
        connection.connect(host, port, request, message -> Platform.runLater(() -> {
            // Ignore callbacks from a connection the user has already left.
            if (roomConnection == connection) { receiveRoomMessage(message); }
        }));
    }

    private void receiveRoomMessage(String message) {
        if (message.startsWith("ROOM_CREATED ") || message.startsWith("ROOM_JOINED ")) {
            roomCode.setText(message.split(" ")[1]);
            roomStatus.setText("Room " + roomCode.getText() + " — waiting for both players");
        } else if (message.startsWith("PLAYER ")) {
            onlineColor = message.charAt(7);
        } else if (message.startsWith("STATE ")) {
            String[] parts = message.split(" ");
            if (parts.length != 3 || !parts[1].matches("[xo]") || !parts[2].matches("[.xoXO]{64}")) {
                showMessage("Invalid board received from server"); return;
            }
            onlineBoard = parts[2];
            roomStatus.setText("Room " + roomCode.getText() + " — You are "
                    + (onlineColor == 'x' ? "Black (X)" : "Red (O)"));
            turnLabel.setText("Turn: " + (parts[1].equals("x") ? "Black" : "Red")
                    + " — board preview only; network moves come next");
            drawBoard();
        } else if (message.startsWith("ERROR ") || message.startsWith("DISCONNECTED ")
                || message.startsWith("ROOM_CLOSED ")) {
            turnLabel.setText(message.startsWith("ROOM_CLOSED ") ? "Room closed — opponent left" : message.substring(message.indexOf(' ') + 1));
            roomStatus.setText("Not connected");
            RoomConnection connection = roomConnection;
            roomConnection = null;
            if (connection != null) { connection.close(); }
            showMessage(turnLabel.getText());
        }
    }

    private void closeRoomConnection() {
        if (roomConnection != null) { roomConnection.close(); roomConnection = null; }
        onlineMode = false;
        onlineBoard = null;
        onlineColor = 0;
        selectedPosition = null;
        mustContinueCapture = false;
        if (animationPane != null) { animationPane.getChildren().clear(); }
    }

    @Override
    public void stop() { closeRoomConnection(); }

    private int getDepthFromDifficulty() {
        String difficulty = difficultyBox.getValue();
        if ("Medium".equals(difficulty)) {
            return 2;
        }
        if ("Hard".equals(difficulty)) {
            return 3;
        }
        return 1;
    }

    private void styleButton(Button button, String gradient) {
        button.setStyle("-fx-background-color: " + gradient + ";"
                + " -fx-text-fill: white;"
                + " -fx-font-weight: bold;"
                + " -fx-background-radius: 25;"
                + " -fx-padding: 10 20;"
                + " -fx-cursor: hand;");
        DropShadow shadow = new DropShadow();
        button.setOnMouseEntered(e -> {
            button.setEffect(shadow);
        });
        button.setOnMouseExited(e -> {
            button.setEffect(null);
        });
    }

    private void animateMove(Position from, Position to, Piece movingPiece, boolean wasCapture) {
        animationPane.getChildren().clear();

        Circle animatedCircle = new Circle(TILE_SIZE / 2.5);
        if (movingPiece.getSymbol() == 'x' || movingPiece.getSymbol() == 'X') {
            animatedCircle.setFill(Color.BLACK);
        } else {
            animatedCircle.setFill(Color.RED);
        }
        if (movingPiece.isKing()) {
            animatedCircle.setStroke(Color.GOLD);
            animatedCircle.setStrokeWidth(4);
        }

        double startX = from.getCol() * TILE_SIZE + TILE_SIZE / 2.0;
        double startY = from.getRow() * TILE_SIZE + TILE_SIZE / 2.0;
        double endX = to.getCol() * TILE_SIZE + TILE_SIZE / 2.0;
        double endY = to.getRow() * TILE_SIZE + TILE_SIZE / 2.0;

        animatedCircle.setLayoutX(startX);
        animatedCircle.setLayoutY(startY);
        animationPane.getChildren().add(animatedCircle);

        TranslateTransition transition = new TranslateTransition(Duration.millis(300), animatedCircle);
        transition.setByX(endX - startX);
        transition.setByY(endY - startY);
        Game animatedGame = game;
        transition.setOnFinished(e -> {
            if (onlineMode || game != animatedGame) { return; }
            animationPane.getChildren().clear();
            finalizeMoveOutcome(to, wasCapture);
        });
        transition.play();
    }

    private void finalizeMoveOutcome(Position target, boolean wasCapture) {
        String winner = game.getWinner();

        if (winner != null) {
            gameOver = true;
            selectedPosition = null;
            mustContinueCapture = false;
            drawBoard();
            showMessage(winner);
            return;
        }

        if (wasCapture && game.canCaptureAgain(target)) {
            selectedPosition = target;
            mustContinueCapture = true;
            drawBoard();
            showMessage("You must continue capturing with the same piece");
            return;
        }

        selectedPosition = null;
        mustContinueCapture = false;
        turnLabel.setText("Turn: " + game.getCurrentPlayer().getName());
        drawBoard();
        maybePerformBotTurn();
    }

    private void maybePerformBotTurn() {
        while (botMode && !gameOver && game.getCurrentPlayer().isBot()) {
            performBotMove();
        }
    }

    private void performBotMove() {
        BotPlayer.MoveChoice botMove = botPlayer.findBestMove(game, botDepth, game.getCurrentPlayer().getSymbol());
        if (botMove == null) {
            String winner = game.getWinner();
            if (winner == null) {
                winner = "Bot has no valid move.";
            }
            gameOver = true;
            showMessage(winner);
            return;
        }

        Piece movingPiece = game.getBoard().getPiece(botMove.getFrom().getRow(), botMove.getFrom().getCol());
        Piece movingPieceCopy = null;
        if (movingPiece != null) {
            movingPieceCopy = new Piece(movingPiece.getSymbol());
        }

        boolean wasCapture = Math.abs(botMove.getTo().getRow() - botMove.getFrom().getRow()) == 2;
        boolean moved = game.move(botMove.getFrom(), botMove.getTo());
        selectedPosition = null;
        mustContinueCapture = false;

        if (moved) {
            if (movingPieceCopy != null) {
                animateMove(botMove.getFrom(), botMove.getTo(), movingPieceCopy, wasCapture);
            } else {
                finalizeMoveOutcome(botMove.getTo(), wasCapture);
            }
        } else {
            drawBoard();
            gameOver = true;
            showMessage("Bot failed to move.");
        }
    }

    private void showMessage(String message) {
        // Non-blocking toast notification
        Platform.runLater(() -> {
            Label toast = new Label(message);
            toast.setStyle("-fx-background-radius: 8; -fx-background-color: rgba(0,0,0,0.75); -fx-text-fill: white; -fx-padding: 10 14; -fx-font-weight: bold;");
            toast.setOpacity(0);
            toast.setWrapText(true);
            toast.setMaxWidth(400);
            toast.setAlignment(Pos.CENTER);

            toastPane.getChildren().add(toast);

            FadeTransition fadeIn = new FadeTransition(Duration.millis(200), toast);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);

            PauseTransition stay = new PauseTransition(Duration.millis(1400));

            FadeTransition fadeOut = new FadeTransition(Duration.millis(350), toast);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);

            SequentialTransition seq = new SequentialTransition(fadeIn, stay, fadeOut);
            seq.setOnFinished(ev -> toastPane.getChildren().remove(toast));
            seq.play();
        });
    }
}