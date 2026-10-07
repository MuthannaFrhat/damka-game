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

    private NetworkClient network;
    private boolean onlineMode;
    private boolean onlineReady;
    private boolean movePending;
    private char mySymbol;
    private char onlineTurn;
    private Label roomLabel;
    private TextField serverHost;
    private TextField serverPort;
    private TextField roomCode;

    private final int TILE_SIZE = 70;

    @Override
    public void start(Stage stage) {
        game = new Game(false, true);
        botMode = true;

        turnLabel = new Label("Turn: " + game.getCurrentPlayer().getName());
        pvpButton = new Button("Online multiplayer");
        botButton = new Button("Player vs Bot");
        difficultyBox = new ComboBox<>();
        difficultyBox.getItems().addAll("Easy", "Medium", "Hard");
        difficultyBox.setValue("Easy");
        botPlayer = new BotPlayer();

        difficultyControls = new HBox(10, new Label("Difficulty:"), difficultyBox);
        difficultyControls.setVisible(false);
        difficultyControls.setManaged(false);

        HBox modeButtons = new HBox(10, pvpButton, botButton);

        serverHost = new TextField("localhost");
        serverHost.setPrefColumnCount(10);
        serverPort = new TextField("5000");
        serverPort.setPrefColumnCount(4);
        roomCode = new TextField();
        roomCode.setPromptText("Room code");
        roomCode.setPrefColumnCount(6);
        roomLabel = new Label("Local BOT mode");
        Button createRoom = new Button("Create room");
        Button joinRoom = new Button("Join room");
        Button resign = new Button("Resign / Leave");
        HBox networkControls = new HBox(8, new Label("Server:"), serverHost, serverPort,
                createRoom, roomCode, joinRoom, resign);
        networkControls.setVisible(false);
        networkControls.setManaged(false);
        pvpButton.setOnAction(e -> {
            networkControls.setVisible(true);
            networkControls.setManaged(true);
            showMessage("Create a room or enter your friend's code");
        });
        createRoom.setOnAction(e -> connectOnline("CREATE_ROOM"));
        joinRoom.setOnAction(e -> {
            String code = roomCode.getText().trim().toUpperCase(java.util.Locale.ROOT);
            if (!code.matches("[A-Z0-9]{6}")) { showMessage("Enter a six-character room code"); return; }
            connectOnline("JOIN_ROOM " + code);
        });
        resign.setOnAction(e -> {
            if (network != null) { network.send("RESIGN"); }
        });

        botButton.setOnAction(e -> {
            leaveOnline();
            networkControls.setVisible(false);
            networkControls.setManaged(false);
            roomLabel.setText("Local BOT mode");
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

        VBox root = new VBox(15, modeButtons, networkControls, roomLabel, difficultyControls, turnLabel, boardStack);
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
        stage.setOnCloseRequest(e -> leaveOnline());
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

                Piece piece = board.getPiece(row, col);

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
        if (onlineMode) { handleOnlineClick(row, col); return; }
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

    // Only these callbacks cross from the network thread to JavaFX's UI thread.
    private void connectOnline(String request) {
        int port;
        try {
            port = Integer.parseInt(serverPort.getText().trim());
            if (port < 1 || port > 65535) { throw new NumberFormatException(); }
        } catch (NumberFormatException exception) { showMessage("Port must be 1-65535"); return; }
        String host = serverHost.getText().trim();
        if (host.isEmpty()) { showMessage("Enter a server address"); return; }
        leaveOnline();
        onlineMode = true;
        botMode = false;
        gameOver = false;
        animationPane.getChildren().clear();
        difficultyControls.setVisible(false);
        difficultyControls.setManaged(false);
        game = new Game();
        drawBoard();
        turnLabel.setText("Connecting...");
        roomLabel.setText("Connecting to " + host + ":" + port);
        NetworkClient client = new NetworkClient();
        network = client;
        client.connect(host, port, request,
                message -> Platform.runLater(() -> {
                    if (network == client) { receiveOnline(message); }
                }),
                reason -> Platform.runLater(() -> {
                    if (network == client && !gameOver) {
                        gameOver = true;
                        onlineReady = false;
                        turnLabel.setText(reason);
                        showMessage(reason);
                    }
                }));
    }

    private void receiveOnline(String message) {
        String[] parts = message.split(" ");
        if (message.startsWith("ROOM_CREATED ") || message.startsWith("ROOM_JOINED ")) {
            mySymbol = parts[2].charAt(0);
            roomCode.setText(parts[1]);
            roomLabel.setText("Room: " + parts[1] + " — You are " + (mySymbol == 'x' ? "Black (X)" : "Red (O)"));
        } else if (message.startsWith("STATE ")) {
            onlineReady = parts[1].equals("ACTIVE");
            onlineTurn = parts[2].charAt(0);
            int row = Integer.parseInt(parts[3]), col = Integer.parseInt(parts[4]);
            selectedPosition = onlineTurn == mySymbol && row >= 0 ? new Position(row, col) : null;
            mustContinueCapture = selectedPosition != null;
            movePending = false;
            Board board = game.getBoard();
            for (int index = 0; index < 64; index++) {
                char piece = parts[5].charAt(index);
                board.setPiece(index / 8, index % 8, piece == '.' ? null : new Piece(piece));
            }
            turnLabel.setText(!onlineReady ? "Waiting for opponent..." : onlineTurn == mySymbol
                    ? mustContinueCapture ? "Your turn — continue capturing" : "Your turn" : "Opponent's turn");
            drawBoard();
        } else if (message.startsWith("GAME_OVER ")) {
            gameOver = true;
            onlineReady = false;
            movePending = false;
            String result = parts[1].equals("-") ? "Room closed" : parts[1].charAt(0) == mySymbol ? "You won" : "You lost";
            String reason = parts[2].equals("RESIGNED") ? "resignation" : parts[2].equals("DISCONNECTED") ? "disconnection" : "no legal moves";
            turnLabel.setText(result + " — " + reason);
            showMessage(turnLabel.getText());
        } else if (message.startsWith("ERROR ")) {
            movePending = false;
            showMessage(message.substring(6));
            if (mySymbol == 0) { turnLabel.setText(message.substring(6)); }
        }
    }

    private void handleOnlineClick(int row, int col) {
        if (gameOver || !onlineReady || onlineTurn != mySymbol || movePending) { return; }
        Piece piece = game.getBoard().getPiece(row, col);
        if (piece != null && piece.belongsToPlayer(mySymbol)) {
            if (!mustContinueCapture) { selectedPosition = new Position(row, col); drawBoard(); }
        } else if (selectedPosition != null) {
            movePending = true;
            network.send("MOVE " + selectedPosition.getRow() + " " + selectedPosition.getCol() + " " + row + " " + col);
        }
    }

    private void leaveOnline() {
        if (network != null) { network.close(); network = null; }
        onlineMode = false;
        onlineReady = false;
        movePending = false;
        mySymbol = 0;
        selectedPosition = null;
        mustContinueCapture = false;
    }

    @Override
    public void stop() { leaveOnline(); }

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
        if (botMode && !gameOver && game.getCurrentPlayer().isBot()) {
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