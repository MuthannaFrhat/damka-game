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
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
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
    private boolean onlineAnimating;
    private final java.util.ArrayDeque<String> pendingOnlineMessages = new java.util.ArrayDeque<>();
    private TranslateTransition activeAnimation;

    private final int TILE_SIZE = 70;

    @Override
    public void start(Stage stage) {
        game = new Game(false, true);
        botMode = true;

        turnLabel = new Label("Turn: " + game.getCurrentPlayer().getName());
        pvpButton = new Button("Play with a friend");
        botButton = new Button("Play against BOT");
        difficultyBox = new ComboBox<>();
        difficultyBox.getItems().addAll("Easy", "Medium", "Hard");
        difficultyBox.setValue("Easy");
        botPlayer = new BotPlayer();

        difficultyControls = new HBox(10, new Label("Difficulty:"), difficultyBox);
        difficultyControls.setVisible(false);
        difficultyControls.setManaged(false);

        VBox modeButtons = new VBox(10, pvpButton, botButton);
        pvpButton.setMaxWidth(Double.MAX_VALUE);
        botButton.setMaxWidth(Double.MAX_VALUE);

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
        Button resign = new Button("Leave match");
        Button settings = new Button("Connection settings");
        settings.getStyleClass().add("quiet-button");
        settings.setOnAction(e -> showConnectionSettings(stage));
        Label inviteHint = new Label("Create a room and share its code,\nor enter a friend's code below.");
        inviteHint.setWrapText(true);
        inviteHint.getStyleClass().add("muted");
        createRoom.setMaxWidth(Double.MAX_VALUE);
        createRoom.getStyleClass().add("primary-button");
        joinRoom.getStyleClass().add("secondary-button");
        resign.getStyleClass().add("quiet-button");
        roomCode.setPrefColumnCount(6);
        HBox joinControls = new HBox(8, roomCode, joinRoom);
        javafx.scene.layout.HBox.setHgrow(roomCode, javafx.scene.layout.Priority.ALWAYS);
        VBox networkControls = new VBox(12, inviteHint, createRoom, joinControls, resign, settings);
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

        pvpButton.getStyleClass().add("primary-button");
        botButton.getStyleClass().add("secondary-button");
        turnLabel.getStyleClass().add("turn-label");
        roomLabel.getStyleClass().add("room-label");
        roomLabel.setWrapText(true);

        boardGrid = new GridPane();
        boardGrid.setPrefSize(TILE_SIZE * 8, TILE_SIZE * 8);
        boardGrid.setMaxSize(TILE_SIZE * 8, TILE_SIZE * 8);
        boardGrid.getStyleClass().add("board-grid");

        animationPane = new Pane();
        animationPane.setPickOnBounds(false);
        animationPane.setMouseTransparent(true);
        animationPane.setPrefSize(TILE_SIZE * 8, TILE_SIZE * 8);

        StackPane boardStack = new StackPane(boardGrid, animationPane);
        boardStack.setPrefSize(TILE_SIZE * 8, TILE_SIZE * 8);

        drawBoard();

        Label title = new Label("DAMKA");
        title.getStyleClass().add("title");
        Label subtitle = new Label("A good move changes everything.");
        subtitle.getStyleClass().add("muted");
        Label playLabel = new Label("CHOOSE YOUR GAME");
        playLabel.getStyleClass().add("section-label");
        VBox sidebar = new VBox(18, title, subtitle, playLabel, modeButtons,
                networkControls, difficultyControls);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(260);
        sidebar.setMinWidth(260);
        sidebar.setMaxWidth(260);
        Label boardHint = new Label("Select a piece, then choose its destination.");
        boardHint.getStyleClass().add("muted");
        StackPane boardFrame = new StackPane(boardStack);
        boardFrame.getStyleClass().add("board-frame");
        VBox match = new VBox(12, roomLabel, turnLabel, boardFrame, boardHint);
        match.setAlignment(Pos.TOP_CENTER);
        HBox root = new HBox(28, sidebar, match);
        root.getStyleClass().add("game-root");
        difficultyControls.setAlignment(Pos.CENTER_LEFT);

        // Toast / notification overlay
        toastPane = new StackPane();
        toastPane.setPickOnBounds(false);
        toastPane.setMouseTransparent(true);

        StackPane appRoot = new StackPane(root, toastPane);
        StackPane.setAlignment(toastPane, Pos.TOP_CENTER);
        StackPane.setMargin(toastPane, new Insets(10, 0, 0, 0));

        Scene scene = new Scene(appRoot);
        scene.getStylesheets().add(getClass().getResource("/damka.css").toExternalForm());

        stage.setTitle("Damka Game");
        stage.setScene(scene);
        stage.setResizable(false);
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
                    square.setFill(Color.web("#eee4d2"));
                } else {
                    square.setFill(Color.web("#53756a"));
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
                    ring.setStroke(Color.web("#d5ae62"));
                    ring.setStrokeWidth(5);
                    ring.setMouseTransparent(true);
                    cell.getChildren().add(ring);
                }

                Piece piece = board.getPiece(row, col);

                if (piece != null) {
                    double radius = TILE_SIZE * 0.35;
                    Circle circle = new Circle(radius);

                    if (piece.getSymbol() == 'x' || piece.getSymbol() == 'X') {
                        circle.setFill(Color.web("#26353b"));
                    } else {
                        circle.setFill(Color.web("#b94e46"));
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
        if (activeAnimation != null) { return; }
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
        roomLabel.setText("Finding your match...");
        NetworkClient client = new NetworkClient();
        network = client;
        client.connect(host, port, request,
                message -> Platform.runLater(() -> {
                    if (network == client) { receiveOnline(message); }
                }),
                reason -> Platform.runLater(() -> {
                    if (network == client) { receiveOnline("CONNECTION_LOST " + reason); }
                }));
    }

    private void receiveOnline(String message) {
        // Preserve message order if the next move/result arrives during a transition.
        if (onlineAnimating) { pendingOnlineMessages.add(message); return; }
        String[] parts = message.split(" ");
        if (message.startsWith("ROOM_CREATED ") || message.startsWith("ROOM_JOINED ")) {
            mySymbol = parts[2].charAt(0);
            roomCode.setText(parts[1]);
            roomLabel.setText("Room: " + parts[1] + " — You are " + (mySymbol == 'x' ? "Black (X)" : "Red (O)"));
        } else if (message.startsWith("STATE ")) {
            Position from = null;
            Position to = null;
            Piece movingPiece = null;
            // Compare the previous board with the accepted snapshot; do not predict moves.
            for (int index = 0; index < 64; index++) {
                Piece old = game.getBoard().getPiece(index / 8, index % 8);
                char next = parts[5].charAt(index);
                if (old != null && old.belongsToPlayer(onlineTurn) && next == '.') {
                    from = new Position(index / 8, index % 8);
                    movingPiece = new Piece(old.getSymbol());
                }
                if (old == null && next != '.' && Character.toLowerCase(next) == onlineTurn) {
                    to = new Position(index / 8, index % 8);
                }
            }
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
            if (from != null && to != null) {
                onlineAnimating = true;
                animateMove(from, to, movingPiece, () -> {
                    drawBoard();
                    onlineAnimating = false;
                    while (!onlineAnimating && !pendingOnlineMessages.isEmpty()) {
                        receiveOnline(pendingOnlineMessages.remove());
                    }
                });
            } else { drawBoard(); }
        } else if (message.startsWith("GAME_OVER ")) {
            gameOver = true;
            onlineReady = false;
            movePending = false;
            String result = parts[1].equals("-") ? "Room closed" : parts[1].charAt(0) == mySymbol ? "You won" : "You lost";
            String reason = parts[2].equals("RESIGNED") ? "resignation" : parts[2].equals("DISCONNECTED") ? "disconnection" : "no legal moves";
            turnLabel.setText(result + " — " + reason);
            showMessage(turnLabel.getText());
        } else if (message.startsWith("CONNECTION_LOST ")) {
            if (!gameOver) {
                gameOver = true;
                onlineReady = false;
                turnLabel.setText(message.substring(16));
                showMessage(turnLabel.getText());
            }
        } else if (message.startsWith("ERROR ")) {
            movePending = false;
            showMessage(message.substring(6));
            if (mySymbol == 0) { turnLabel.setText(message.substring(6)); }
        }
    }

    private void handleOnlineClick(int row, int col) {
        if (gameOver || !onlineReady || onlineAnimating || onlineTurn != mySymbol || movePending) { return; }
        Piece piece = game.getBoard().getPiece(row, col);
        if (piece != null && piece.belongsToPlayer(mySymbol)) {
            if (!mustContinueCapture) { selectedPosition = new Position(row, col); drawBoard(); }
        } else if (selectedPosition != null) {
            movePending = true;
            network.send("MOVE " + selectedPosition.getRow() + " " + selectedPosition.getCol() + " " + row + " " + col);
        }
    }

    private void leaveOnline() {
        if (activeAnimation != null) { activeAnimation.stop(); activeAnimation = null; }
        if (animationPane != null) { animationPane.getChildren().clear(); }
        onlineAnimating = false;
        pendingOnlineMessages.clear();
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

    private void showConnectionSettings(Stage owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Connection settings");
        dialog.setHeaderText("Choose the computer hosting your game");
        TextField host = new TextField(serverHost.getText());
        TextField port = new TextField(serverPort.getText());
        Label help = new Label("Use localhost for this computer, or the server computer's LAN address.");
        help.setWrapText(true);
        VBox content = new VBox(10, help, new Label("Server address"), host, new Label("Port"), port);
        content.setPrefWidth(360);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/damka.css").toExternalForm());
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.showAndWait().filter(result -> result == save).ifPresent(result -> {
            try {
                int number = Integer.parseInt(port.getText().trim());
                if (number < 1 || number > 65535 || host.getText().trim().isEmpty()) {
                    throw new NumberFormatException();
                }
                serverHost.setText(host.getText().trim());
                serverPort.setText(String.valueOf(number));
                showMessage("Connection settings saved for your next match");
            } catch (NumberFormatException exception) {
                showMessage("Enter a server address and a port between 1 and 65535");
            }
        });
    }

    private void animateMove(Position from, Position to, Piece movingPiece, boolean wasCapture) {
        animateMove(from, to, movingPiece, () -> finalizeMoveOutcome(to, wasCapture));
    }

    // Both modes use the same piece, duration, easing and overlay.
    private void animateMove(Position from, Position to, Piece movingPiece, Runnable onFinished) {
        animationPane.getChildren().clear();
        StackPane sourceCell = (StackPane) boardGrid.getChildren().get(from.getRow() * 8 + from.getCol());
        sourceCell.getChildren().removeIf(node -> node instanceof Circle);

        Circle animatedCircle = new Circle(TILE_SIZE * 0.35);
        if (movingPiece.getSymbol() == 'x' || movingPiece.getSymbol() == 'X') {
            animatedCircle.setFill(Color.web("#26353b"));
        } else {
            animatedCircle.setFill(Color.web("#b94e46"));
        }
        if (movingPiece.isKing()) {
            animatedCircle.setStroke(Color.GOLD);
            animatedCircle.setStrokeWidth(4);
        }

        animatedCircle.setEffect(new DropShadow(6, Color.color(0, 0, 0, 0.45)));
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
        activeAnimation = transition;
        transition.setOnFinished(e -> {
            if (game != animatedGame || activeAnimation != transition) { return; }
            activeAnimation = null;
            animationPane.getChildren().clear();
            onFinished.run();
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