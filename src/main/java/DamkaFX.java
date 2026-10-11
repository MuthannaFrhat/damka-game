import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.ScrollPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Modality;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Base64;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Coordinates UI events and server messages. All fields here belong to the JavaFX Thread. */
public class DamkaFX extends Application {
    private String username = "Guest";
    private String authenticatedUsername;
    private Stage primaryStage;
    private AccountView accountView;
    private Dialog<Void> accountDialog;
    private Button accountButton;
    private boolean authRequestPending;
    // A local match belongs to the session that started it, never a later login.
    private String botMatchUsername;
    private RoomConnection botMatchConnection;
    private int botMatchDifficulty;
    private boolean botResultRecorded;
    private String botResultId;
    // Local BOT game. Online Game/Board objects live on the server instead.
    private Game botGame;
    private BoardView boardView;
    private Label turnLabel;
    private Label boardHint;
    private Position selectedPosition;
    private boolean gameOver = false;
    private Button botButton;
    private ComboBox<String> difficultyBox;
    private HBox difficultyControls;
    private BotPlayer botPlayer;
    private int botDepth = 1;
    // Window controls. Visual styling lives in damka.css.
    private Label connectionStatusLabel;
    private HBox errorBanner;
    private Label errorLabel;
    private Label matchStatusLabel;
    private TextField roomCodeField;
    private Button joinRoomButton;
    private Button createRoomButton;
    private Button resignButton;
    private Button leaveRoomButton;
    private Label roomCodeLabel;
    private Button copyRoomButton;
    private String currentRoomCode;
    private Label playerColorLabel;
    private String myPlayerColor;
    // Online state: one connection, a display snapshot, and pending request flags.
    private RoomConnection activeRoomConnection;
    private boolean isConnecting;
    private boolean applicationClosing;
    private boolean roomRequestPending = false;
    private boolean leaveRequestPending;
    private boolean isInRoom = false;
    private boolean isOnlineMode = true;
    private boolean moveRequestPending = false;
    private boolean resignRequestPending;
    // A server snapshot is for display only; never apply a move to this String.
    private String serverBoardSnapshot;
    private char serverTurn;
    private final Queue<String> incomingMessages = new ArrayDeque<>();
    // Override with -Ddamka.server.host=192.168.1.10 -Ddamka.server.port=5000 for LAN play.
    private final String serverHost = System.getProperty("damka.server.host", "localhost");
    private final int serverPort = Integer.parseInt(System.getProperty("damka.server.port", "5000"));

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        accountView = new AccountView(this::submitAuthentication, name -> {
            username = name;
            accountView = null;
            showGameScreen(stage);
            startBotGame();
        });
        StackPane form = new StackPane(accountView);
        form.getStyleClass().addAll("app-root", "onboarding-root");
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.getStyleClass().add("content-scroll");
        Scene scene = new Scene(scroll, 460, 650);
        scene.getStylesheets().add(getClass().getResource("/damka.css").toExternalForm());
        stage.setTitle("Welcome to Damka");
        stage.setScene(scene);
        stage.setWidth(460);
        stage.setHeight(Math.min(700, Screen.getPrimary().getVisualBounds().getHeight() * 0.92));
        stage.setMinWidth(360);
        stage.setMinHeight(460);
        stage.show();
        stage.centerOnScreen();
    }

    private void showGameScreen(Stage stage) {
        botGame = new Game();
        botPlayer = new BotPlayer();
        turnLabel = new Label("Waiting for a match");
        turnLabel.getStyleClass().add("turn-label");
        botButton = new Button("Play against BOT");
        botButton.getStyleClass().add("secondary-button");
        difficultyBox = new ComboBox<>();
        difficultyBox.getItems().addAll("Easy", "Medium", "Hard");
        difficultyBox.setValue("Easy");
        difficultyControls = new HBox(10, new Label("Difficulty"), difficultyBox);
        HBox.setHgrow(difficultyBox, Priority.ALWAYS);
        difficultyBox.setMaxWidth(Double.MAX_VALUE);
        difficultyControls.getStyleClass().add("inline-settings");

        roomCodeField = new TextField();
        roomCodeField.setPromptText("Room code");
        roomCodeField.setPrefColumnCount(6);
        roomCodeField.getStyleClass().add("room-code-field");
        HBox.setHgrow(roomCodeField, Priority.ALWAYS);
        joinRoomButton = new Button("Join");
        createRoomButton = new Button("Create a room");
        createRoomButton.getStyleClass().add("primary-button");
        createRoomButton.setMaxWidth(Double.MAX_VALUE);
        resignButton = new Button("Resign");
        resignButton.getStyleClass().add("danger-button");
        leaveRoomButton = new Button("Leave room");
        leaveRoomButton.getStyleClass().add("quiet-button");
        connectionStatusLabel = new Label("Create or join a room to begin.");
        connectionStatusLabel.setWrapText(true);
        connectionStatusLabel.setMaxWidth(Double.MAX_VALUE);
        roomCodeLabel = new Label("No room yet");
        roomCodeLabel.getStyleClass().add("room-code-label");
        playerColorLabel = new Label("Your seat is waiting");
        playerColorLabel.getStyleClass().add("player-label");
        matchStatusLabel = new Label("Ready when you are");
        matchStatusLabel.getStyleClass().add("match-status");
        matchStatusLabel.setWrapText(true);
        connectionStatusLabel.getStyleClass().add("status-line");

        createRoomButton.setOnAction(e -> createRoom());
        joinRoomButton.setOnAction(e -> joinRoom());
        resignButton.setOnAction(e -> resignFromRoom());
        leaveRoomButton.setOnAction(e -> leaveRoom());

        botButton.setOnAction(e -> startBotGame());

        difficultyBox.setOnAction(e -> {
            botDepth = getDepthFromDifficulty();
            if (!isOnlineMode) {
                turnLabel.setText(botGame.getCurrentPlayer().isBot() ? "BOT’s turn" : "Your turn");
                maybePerformBotTurn();
            }
        });

        boardView = new BoardView(this::handleClick);
        boardHint = new Label("Select a piece, then choose its destination.");
        boardHint.getStyleClass().add("board-hint");
        HBox matchHeading = new HBox(12, turnLabel, matchStatusLabel);
        matchHeading.setAlignment(Pos.CENTER_LEFT);
        matchHeading.getStyleClass().add("match-heading");
        VBox boardSection = new VBox(16, matchHeading, connectionStatusLabel, boardView, boardHint);
        boardSection.setAlignment(Pos.TOP_CENTER);
        boardSection.setMinWidth(0);
        boardSection.getStyleClass().add("board-section");

        VBox sidebar = new VBox(16, buildRoomPanel(), buildBotPanel());
        sidebar.setPrefWidth(260);
        sidebar.getStyleClass().add("sidebar");
        BorderPane tableLayout = new BorderPane();
        tableLayout.setCenter(boardSection);
        tableLayout.setRight(sidebar);
        BorderPane.setMargin(sidebar, new Insets(0, 0, 0, 24));

        HBox header = buildHeader(stage);
        BorderPane content = new BorderPane();
        content.setTop(header);
        content.setCenter(tableLayout);
        content.getStyleClass().add("app-content");

        // Keep the board usable on small screens, with the room panel below it.
        tableLayout.widthProperty().addListener((observable, oldWidth, newWidth) -> {
            boolean narrowWindow = newWidth.doubleValue() < 780;
            if (narrowWindow && tableLayout.getBottom() != sidebar) {
                tableLayout.setRight(null);
                tableLayout.setBottom(sidebar);
                BorderPane.setMargin(sidebar, new Insets(24, 0, 0, 0));
            } else if (!narrowWindow && tableLayout.getRight() != sidebar) {
                tableLayout.setBottom(null);
                tableLayout.setRight(sidebar);
                BorderPane.setMargin(sidebar, new Insets(0, 0, 0, 24));
            }
        });

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");

        // The error sits over the content, so showing it never takes space from the board.
        StackPane window = new StackPane(scrollPane, buildErrorBanner());
        window.getStyleClass().add("app-root");
        StackPane.setAlignment(errorBanner, Pos.BOTTOM_CENTER);
        StackPane.setMargin(errorBanner, new Insets(20));
        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        double windowWidth = Math.min(1080, visualBounds.getWidth() * 0.92);
        double windowHeight = Math.min(820, visualBounds.getHeight() * 0.92);
        Scene scene = new Scene(window, windowWidth, windowHeight);
        scene.getStylesheets().add(getClass().getResource("/damka.css").toExternalForm());
        Runnable fitBoardToWindow = () -> {
            double availableHeight = Math.max(280, scene.getHeight() - header.getHeight() - connectionStatusLabel.getHeight() - 150);
            boardView.resizeToFit(boardSection.getWidth(), availableHeight);
        };
        boardSection.widthProperty().addListener((observable, oldWidth, newWidth) -> fitBoardToWindow.run());
        scene.heightProperty().addListener((observable, oldHeight, newHeight) -> fitBoardToWindow.run());
        header.heightProperty().addListener((observable, oldHeight, newHeight) -> fitBoardToWindow.run());
        connectionStatusLabel.heightProperty().addListener((observable, oldHeight, newHeight) -> fitBoardToWindow.run());

        stage.setScene(scene);
        stage.setWidth(windowWidth);
        stage.setHeight(windowHeight);
        stage.setMinWidth(Math.min(560, visualBounds.getWidth()));
        stage.setMinHeight(Math.min(520, visualBounds.getHeight()));
        stage.setTitle("Damka — Checkers");
        stage.show();
        stage.centerOnScreen();
        fitBoardToWindow.run();
        drawBoard();
        updateControls();
    }

    private HBox buildHeader(Stage stage) {
        Label title = new Label("damka");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("A classic game. A fresh challenge.");
        subtitle.getStyleClass().add("app-subtitle");
        VBox brand = new VBox(4, title, subtitle);
        Pane space = new Pane();
        HBox.setHgrow(space, Priority.ALWAYS);
        Label badge = new Label("CHECKERS CLUB");
        badge.getStyleClass().add("header-badge");
        accountButton = new Button();
        accountButton.setId("accountButton");
        accountButton.getStyleClass().add("secondary-button");
        accountButton.setOnAction(event -> {
            if (isInRoom || roomRequestPending || moveRequestPending || resignRequestPending
                    || leaveRequestPending || authRequestPending || boardView.isAnimating()) {
                return;
            }
            if (isAuthenticated()) {
                closeConnection();
                if (isOnlineMode) {
                    resetOnlineRoom();
                } else {
                    matchStatusLabel.setText("Practice · results are not saved");
                }
                showStatus("Signed out. Local practice is not recorded.");
                updateControls();
            } else {
                openAccountDialog(stage);
            }
        });
        HBox header = new HBox(16, brand, space, badge, accountButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        return header;
    }

    private VBox buildRoomPanel() {
        Label title = new Label("Play with a friend");
        title.getStyleClass().add("section-title");
        Label description = new Label("Share a code. Start a match.");
        description.setWrapText(true);
        description.getStyleClass().add("section-description");
        Label joinLabel = new Label("HAVE A ROOM CODE?");
        joinLabel.getStyleClass().add("eyebrow-label");
        HBox joinControls = new HBox(8, roomCodeField, joinRoomButton);
        copyRoomButton = new Button("Copy");
        copyRoomButton.getStyleClass().addAll("quiet-button", "copy-code-button");
        copyRoomButton.setOnAction(e -> copyRoomCode());
        HBox roomCodeRow = new HBox(8, roomCodeLabel, copyRoomButton);
        roomCodeRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(roomCodeLabel, Priority.ALWAYS);
        roomCodeLabel.setMaxWidth(Double.MAX_VALUE);
        VBox roomIdentity = new VBox(6, playerColorLabel, roomCodeRow);
        roomIdentity.getStyleClass().add("room-identity");
        HBox matchActions = new HBox(8, resignButton, leaveRoomButton);
        matchActions.setAlignment(Pos.CENTER_LEFT);
        VBox panel = new VBox(10, title, description, createRoomButton, joinLabel,
                joinControls, roomIdentity, matchActions);
        panel.getStyleClass().add("card");
        return panel;
    }

    private HBox buildErrorBanner() {
        errorLabel = new Label();
        errorLabel.setWrapText(true);
        errorLabel.setMinWidth(0);
        errorLabel.getStyleClass().add("error-message");
        HBox.setHgrow(errorLabel, Priority.ALWAYS);
        Button dismissButton = new Button("×");
        dismissButton.getStyleClass().add("error-dismiss");
        dismissButton.setAccessibleText("Dismiss error");
        dismissButton.setOnAction(e -> errorBanner.setVisible(false));
        errorBanner = new HBox(12, errorLabel, dismissButton);
        errorBanner.setAlignment(Pos.CENTER_LEFT);
        errorBanner.setMaxSize(440, HBox.USE_PREF_SIZE);
        errorBanner.getStyleClass().add("error-banner");
        errorBanner.setVisible(false);
        return errorBanner;
    }

    private VBox buildBotPanel() {
        Label title = new Label("A little practice");
        title.getStyleClass().add("section-title");
        Label description = new Label("Play locally against the BOT.");
        description.getStyleClass().add("section-description");
        botButton.setMaxWidth(Double.MAX_VALUE);
        VBox panel = new VBox(10, title, description, difficultyControls, botButton);
        panel.getStyleClass().addAll("card", "practice-card");
        return panel;
    }

    // Display a snapshot in either mode; this method does not apply game rules.
    private void drawBoard() {
        String snapshot = serverBoardSnapshot;
        if (!isOnlineMode) {
            StringBuilder cells = new StringBuilder(64);
            Board board = botGame.getBoard();
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    Piece piece = board.getPiece(row, col);
                    cells.append(piece == null ? '.' : piece.getSymbol());
                }
            }
            snapshot = cells.toString();
        }
        boardView.showBoard(snapshot, selectedPosition);
        boardHint.setText(isOnlineMode && snapshot == null
                ? "Your board appears when both players join."
                : "Select a piece, then choose its destination.");
    }

    private void startBotGame() {
        if (isConnecting || roomRequestPending || isInRoom) {
            return;
        }
        resetOnlineRoom(); // Keep an authenticated connection for result messages.
        isOnlineMode = false;
        botGame = new Game(false, true);
        gameOver = false;
        botDepth = getDepthFromDifficulty();
        botMatchUsername = isAuthenticated() ? authenticatedUsername : null;
        botMatchConnection = isAuthenticated() ? activeRoomConnection : null;
        botMatchDifficulty = difficultyBox.getSelectionModel().getSelectedIndex() + 1;
        botResultRecorded = false;
        botResultId = null;
        playerColorLabel.setText("You play forest · Player X");
        roomCodeLabel.setText("Local practice");
        showStatus(botMatchUsername == null
                ? "Practice match. Statistics and history are not saved."
                : "BOT results will be saved to " + botMatchUsername + ".");
        matchStatusLabel.setText(botMatchUsername == null ? "Practice · results are not saved" : "Against BOT");
        turnLabel.setText("Your turn");
        drawBoard();
        updateControls();
    }

    private void handleClick(int row, int col) {
        if (isOnlineMode) {
            handleOnlineClick(row, col);
        } else {
            handleBotClick(row, col);
        }
    }

    private void handleBotClick(int row, int col) {
        if (gameOver || boardView.isAnimating() || botGame.getCurrentPlayer().isBot()) {
            return;
        }
        Position clicked = new Position(row, col);
        Piece piece = botGame.getBoard().getPiece(row, col);
        boolean ownPiece = piece != null && piece.belongsToPlayer('x');
        Position captureContinuation = botGame.getCaptureContinuationPosition();

        if (selectedPosition != null && selectedPosition.getRow() == row && selectedPosition.getCol() == col) {
            if (captureContinuation == null) {
                selectedPosition = null;
                drawBoard();
            } else {
                showError("Continue capturing with the same piece.");
            }
            return;
        }
        if (ownPiece) {
            if (!botGame.canSelectPiece(clicked)) {
                showError(captureContinuation == null
                        ? "Select a piece with a legal move."
                        : "Continue capturing with the same piece.");
                return;
            }
            selectedPosition = clicked;
            drawBoard();
            return;
        }
        if (selectedPosition != null && piece == null) {
            applyLocalMove(selectedPosition, clicked);
        }
    }

    private void applyLocalMove(Position from, Position to) {
        if (!botGame.move(from, to)) {
            showError(botGame.getMoveError());
            return;
        }
        Game.Move move = botGame.getLastMove();
        Piece appearance = new Piece(move.getMovingSymbol());
        selectedPosition = null;
        showStatus("Move accepted.");
        boardView.animateMove(move.getFrom(), move.getTo(), appearance,
                move.getCapturedPosition(), this::finalizeMoveOutcome);
        updateControls();
    }

    private void handleOnlineClick(int row, int col) {
        if (!canInteractOnline()) {
            return;
        }

        int clickedIndex = row * 8 + col;
        char clickedSymbol = serverBoardSnapshot.charAt(clickedIndex);
        boolean clickedOwnPiece = clickedSymbol != '.'
                && Character.toUpperCase(clickedSymbol) == myPlayerColor.charAt(0);

        if (selectedPosition == null) {
            if (clickedOwnPiece) {
                selectedPosition = new Position(row, col);
                drawBoard();
            }
            return;
        }

        if (clickedOwnPiece) {
            if (selectedPosition.getRow() == row && selectedPosition.getCol() == col) {
                selectedPosition = null;
            } else {
                selectedPosition = new Position(row, col);
            }
            drawBoard();
            return;
        }

        if (clickedSymbol != '.') {
            return;
        }

        Position from = selectedPosition;
        Position to = new Position(row, col);
        selectedPosition = null;
        moveRequestPending = true;
        drawBoard();
        sendOnlineMove(from, to);
        updateControls();
    }

    private boolean canInteractOnline() {
        return isOnlineMode && isInRoom && serverBoardSnapshot != null
                && myPlayerColor != null && serverTurn == myPlayerColor.charAt(0)
                && !moveRequestPending && !leaveRequestPending && !boardView.isAnimating() && !gameOver;
    }

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

    // Successful actions clear the floating error; the status line keeps its own space.
    private void showStatus(String message) {
        errorBanner.setVisible(false);
        connectionStatusLabel.setText(message);
    }

    private void showError(String message) {
        if ("Invalid move.".equals(message)) {
            message = "Invalid move. Move diagonally to an empty square, or capture if required.";
        } else if ("A capture is required.".equals(message)) {
            message = "Capture required. Jump over an opponent’s piece.";
        }
        errorLabel.setText(message);
        errorBanner.setVisible(true);
    }

    private void copyRoomCode() {
        if (currentRoomCode == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(currentRoomCode);
        if (Clipboard.getSystemClipboard().setContent(content)) {
            copyRoomButton.setText("Copied");
        } else {
            showError("Could not copy the room code. Try again.");
        }
    }

    private void updateControls() {
        if (boardView == null) {
            return;
        }
        boolean canChooseRoom = isAuthenticated() && !isConnecting && !roomRequestPending && !isInRoom;
        createRoomButton.setDisable(!canChooseRoom);
        joinRoomButton.setDisable(!canChooseRoom);
        roomCodeField.setDisable(!canChooseRoom);
        copyRoomButton.setDisable(!isInRoom || currentRoomCode == null);
        botButton.setDisable(isConnecting || authRequestPending || roomRequestPending || isInRoom);
        accountButton.setText(isAuthenticated() ? "Sign out" : "Log in");
        accountButton.setDisable(accountDialog != null || authRequestPending || isConnecting || isInRoom || roomRequestPending
                || moveRequestPending || resignRequestPending || leaveRequestPending || boardView.isAnimating());
        difficultyBox.setDisable(isInRoom || isOnlineMode);
        resignButton.setDisable(!isInRoom || serverBoardSnapshot == null || gameOver
            || resignRequestPending || moveRequestPending || leaveRequestPending || isConnecting || boardView.isAnimating());
        leaveRoomButton.setDisable(!isInRoom || leaveRequestPending || moveRequestPending
                || resignRequestPending || isConnecting || boardView.isAnimating());
    }

    private void createRoom() {
        submitRoomRequest("CREATE_ROOM");
    }

    private void joinRoom() {
        String roomCode = roomCodeField.getText() == null ? "" : roomCodeField.getText().trim().toUpperCase();
        if (!roomCode.matches("[A-Z0-9]{6}")) {
            showError(roomCode.isEmpty()
                    ? "Enter the six-character room code."
                    : "Room codes contain six letters or numbers.");
            roomCodeField.requestFocus();
            return;
        }
        roomCodeField.setText(roomCode);
        submitRoomRequest("JOIN_ROOM " + roomCode);
    }

    private void submitRoomRequest(String request) {
        if (!isAuthenticated() || isConnecting || roomRequestPending || isInRoom) {
            return;
        }

        // Cancel local animation callbacks before switching to the server board.
        boardView.stopAnimation();

        isOnlineMode = true;
        roomRequestPending = true;
        currentRoomCode = null;
        gameOver = false;
        serverBoardSnapshot = null;
        serverTurn = '\0';
        selectedPosition = null;
        moveRequestPending = false;
        incomingMessages.clear();
        turnLabel.setText("Waiting for the server");
        matchStatusLabel.setText("Connecting to match");
        drawBoard();
        updateControls();

        showStatus("Sending room request...");
        sendNetworkCommand(request);
    }

    private boolean isAuthenticated() {
        return authenticatedUsername != null && activeRoomConnection != null
                && activeRoomConnection.isConnected() && !authRequestPending;
    }

    private void openAccountDialog(Stage stage) {
        if (accountDialog != null) {
            accountDialog.getDialogPane().getScene().getWindow().requestFocus();
            return;
        }
        accountView = new AccountView(this::submitAuthentication, null);
        accountDialog = new Dialog<>();
        accountDialog.initOwner(stage);
        accountDialog.initModality(Modality.NONE);
        accountDialog.setTitle("Your account");
        accountDialog.getDialogPane().setContent(accountView);
        accountDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        accountDialog.getDialogPane().getStyleClass().add("profile-dialog");
        accountDialog.getDialogPane().getStylesheets().add(getClass().getResource("/damka.css").toExternalForm());
        accountDialog.setOnHidden(event -> {
            if (authRequestPending) {
                closeConnection();
                authRequestPending = false;
                updateControls();
            }
            accountDialog = null;
            accountView = null;
            updateControls();
        });
        accountDialog.show();
        updateControls();
    }

    private void submitAuthentication(AccountView.Credentials credentials) {
        if (boardView != null && boardView.isAnimating()) {
            accountView.showError("Wait for the current move to finish.");
            return;
        }
        if (authRequestPending || isInRoom || roomRequestPending || moveRequestPending
                || resignRequestPending || leaveRequestPending) {
            accountView.showError("Leave the room before changing accounts.");
            return;
        }
        closeConnection();
        authRequestPending = true;
        isConnecting = true;
        accountView.setBusy(true);
        updateControls();
        String requestedUsername = credentials.username();
        String password = Base64.getEncoder().encodeToString(credentials.password().getBytes(StandardCharsets.UTF_8));
        String command = (credentials.signup() ? "SIGNUP " : "LOGIN ") + requestedUsername + " " + password;
        activeRoomConnection = new RoomConnection(serverHost, serverPort, new RoomConnection.Listener() {
            @Override
            public void onConnected(RoomConnection connection) {
                onConnectionEvent(connection, () -> {
                    if (accountView != null) {
                        accountView.showError("Checking your account...");
                    }
                });
            }

            @Override
            public void onMessage(RoomConnection connection, String message) {
                onConnectionEvent(connection, () -> {
                    if (!handleAccountMessage(connection, message, requestedUsername)) {
                        enqueueServerMessage(message);
                    }
                });
            }

            @Override
            public void onDisconnected(RoomConnection connection, String detail) {
                onConnectionEvent(connection, () -> {
                    boolean authenticationFailed = authRequestPending;
                    closeConnection();
                    authRequestPending = false;
                    if (accountView != null) {
                        accountView.setBusy(false);
                        accountView.showError("Connection ended: " + detail);
                    }
                    if (boardView != null) {
                        if (isOnlineMode) {
                            resetOnlineRoom();
                        } else {
                            matchStatusLabel.setText("Practice · results are not saved");
                        }
                        if (!authenticationFailed) {
                            showError("Connection ended: " + detail + ". BOT practice remains available.");
                        }
                        updateControls();
                    }
                });
            }
        });
        activeRoomConnection.connect(command);
    }

    // Account messages are independent of the ordered game animation queue.
    private boolean handleAccountMessage(RoomConnection connection, String message, String requestedUsername) {
        if (message.startsWith("AUTH_OK ")) {
            if (!message.equals("AUTH_OK " + requestedUsername) || !authRequestPending) {
                return true;
            }
            authenticatedUsername = username = requestedUsername;
            authRequestPending = false;
            isConnecting = false;
            if (accountView != null) {
                accountView.setBusy(false);
            }
            if (accountDialog != null) {
                accountDialog.close();
            }
            accountView = null;
            if (boardView == null) {
                showGameScreen(primaryStage);
            } else {
                isOnlineMode = true;
                resetOnlineRoom();
            }
            showStatus("Logged in as " + username + ". Create a room or play BOT.");
            updateControls();
        } else if (message.startsWith("AUTH_ERROR ")) {
            String detail = message.substring("AUTH_ERROR ".length());
            closeConnection(); // Retry uses a fresh transport and never keeps a password.
            authRequestPending = false;
            if (accountView != null) {
                accountView.setBusy(false);
                accountView.showError(detail);
            } else if (boardView != null) {
                showError(detail);
            }
            updateControls();
        } else if (message.startsWith("BOT_SAVED ")) {
            if (message.equals("BOT_SAVED " + botResultId) && !isOnlineMode && gameOver) {
                showStatus("BOT result saved to your profile.");
            }
        } else if (message.startsWith("BOT_ERROR ")) {
            String[] parts = message.split(" ", 3);
            String detail = "BOT result was not saved: " + (parts.length == 3 ? parts[2] : "Server rejected the result.");
            showError(detail);
        } else {
            return false;
        }
        return true;
    }

    private void recordBotResult() {
        if (botResultRecorded) {
            return;
        }
        botResultRecorded = true;
        if (botMatchUsername == null || !isAuthenticated() || activeRoomConnection != botMatchConnection
                || !botMatchUsername.equals(authenticatedUsername)) {
            showStatus((botGame.getWinnerSymbol() == 'X' ? "You win!" : "BOT wins.")
                    + " Practice results are not saved.");
            return;
        }
        botResultId = UUID.randomUUID().toString();
        String outcome = botGame.getWinnerSymbol() == 'X' ? "WIN" : "LOSS";
        sendNetworkCommand("BOT_RESULT " + botResultId + " " + outcome + " " + botMatchDifficulty + " " + Instant.now());
        showStatus("Match finished. Saving your BOT result...");
    }

    // Network callbacks do not touch controls. Old connections cannot change a new match.
    private void onConnectionEvent(RoomConnection connection, Runnable action) {
        Platform.runLater(() -> {
            if (!applicationClosing && connection == activeRoomConnection) {
                action.run();
            }
        });
    }

    private void sendNetworkCommand(String command) {
        if (activeRoomConnection != null && activeRoomConnection.isConnected()) {
            activeRoomConnection.send(command);
        }
    }

    private void sendOnlineMove(Position from, Position to) {
        String request = "MOVE " + from.getRow() + " " + from.getCol()
                + " " + to.getRow() + " " + to.getCol();
        sendNetworkCommand(request);
    }

    private void resignFromRoom() {
        if (!isInRoom || gameOver) {
            return;
        }
        gameOver = true;
        resignRequestPending = true;
        moveRequestPending = false;
        selectedPosition = null;
        sendNetworkCommand("RESIGN");
        showStatus("Resigning...");
        updateControls();
    }

    private void leaveRoom() {
        if (!isInRoom || leaveRequestPending) {
            return;
        }
        leaveRequestPending = true;
        moveRequestPending = false;
        selectedPosition = null;
        sendNetworkCommand("LEAVE_ROOM");
        showStatus("Leaving room...");
        updateControls();
    }

    // MOVE_APPLIED is followed by BOARD and TURN. Hold later messages during the animation.
    private void enqueueServerMessage(String message) {
        incomingMessages.add(message);
        processIncomingMessages();
    }

    private void processIncomingMessages() {
        while (!incomingMessages.isEmpty() && !boardView.isAnimating()) {
            String message = incomingMessages.remove();
            if (message.startsWith("MOVE_APPLIED ")) {
                startOnlineMoveAnimation(message);
            } else {
                handleServerMessage(message);
            }
        }
    }

    private void startOnlineMoveAnimation(String message) {
        String[] fields = message.split(" ");
        if (fields.length != 7 || serverBoardSnapshot == null) {
            return;
        }
        try {
            int fromRow = Integer.parseInt(fields[1]);
            int fromCol = Integer.parseInt(fields[2]);
            int toRow = Integer.parseInt(fields[3]);
            int toCol = Integer.parseInt(fields[4]);
            int capturedRow = Integer.parseInt(fields[5]);
            int capturedCol = Integer.parseInt(fields[6]);
            if (!isBoardCoordinate(fromRow, fromCol) || !isBoardCoordinate(toRow, toCol)
                    || (capturedRow == -1) != (capturedCol == -1)
                    || (capturedRow != -1 && !isBoardCoordinate(capturedRow, capturedCol))) {
                return;
            }
            char symbol = serverBoardSnapshot.charAt(fromRow * 8 + fromCol);
            if (symbol == '.') {
                return;
            }
            Position captured = capturedRow == -1 ? null : new Position(capturedRow, capturedCol);
            selectedPosition = null;
            boardView.animateMove(new Position(fromRow, fromCol), new Position(toRow, toCol),
                    new Piece(symbol), captured, this::processIncomingMessages);
            updateControls();
        } catch (NumberFormatException exception) {
            showError("The server sent an invalid move.");
        }
    }

    private boolean isBoardCoordinate(int row, int col) {
        return row >= 0 && row < 8 && col >= 0 && col < 8;
    }

    private void handleServerMessage(String message) {
        if (message.startsWith("BOARD ")) {
            String snapshot = message.substring("BOARD ".length());
            serverTurn = '\0';
            if (!snapshot.matches("[.xoXO]{64}")) {
                showError("The server sent an invalid board.");
                return;
            }
            serverBoardSnapshot = snapshot;
            drawBoard();
        } else if (message.startsWith("TURN ")) {
            String turn = message.substring("TURN ".length()).trim();
            if ("X".equals(turn) || "O".equals(turn)) {
                serverTurn = turn.charAt(0);
                if (moveRequestPending) {
                    moveRequestPending = false;
                    showStatus("Move accepted.");
                }
                turnLabel.setText(myPlayerColor != null && serverTurn == myPlayerColor.charAt(0)
                        ? "Your turn" : "Opponent’s turn");
            }
        } else if (message.startsWith("PLAYER ")) {
            String playerColor = message.substring("PLAYER ".length()).trim();
            if ("X".equals(playerColor) || "O".equals(playerColor)) {
                myPlayerColor = playerColor;
                playerColorLabel.setText("X".equals(playerColor)
                        ? "You play forest · Player X" : "You play coral · Player O");
            }
        } else if (message.startsWith("ROOM_CREATED ")) {
            String code = message.substring("ROOM_CREATED ".length()).trim();
            currentRoomCode = code;
            copyRoomButton.setText("Copy");
            roomCodeField.setText(code);
            roomCodeLabel.setText("Room " + code);
            isInRoom = true;
            roomRequestPending = false;
            matchStatusLabel.setText("Waiting for opponent");
            turnLabel.setText("Waiting for opponent");
            showStatus("Room created. Share the code to invite a player.");
        } else if (message.startsWith("ROOM_JOINED ")) {
            String code = message.substring("ROOM_JOINED ".length()).trim();
            currentRoomCode = code;
            copyRoomButton.setText("Copy");
            roomCodeField.setText(code);
            roomCodeLabel.setText("Room " + code);
            isInRoom = true;
            roomRequestPending = false;
            matchStatusLabel.setText("Joining opponent");
            showStatus("Joined room " + code + ".");
        } else if (message.startsWith("ROOM_READY ")) {
            isInRoom = true;
            roomRequestPending = false;
            gameOver = false;
            matchStatusLabel.setText("Online match");
            showStatus("Both players connected.");
        } else if (message.startsWith("GAME_OVER ")) {
            handleGameOver(message);
        } else if ("ROOM_LEFT".equals(message)) {
            resetOnlineRoom();
            showStatus("You left the room. Create or join another match.");
        } else if (message.startsWith("ROOM_CLOSED")) {
            resetOnlineRoom();
            showStatus("The room is closed.");
        } else if (message.startsWith("ERROR ")) {
            roomRequestPending = false;
            leaveRequestPending = false;
            if (resignRequestPending) {
                resignRequestPending = false;
                gameOver = false;
            }
            if (moveRequestPending) {
                moveRequestPending = false;
                selectedPosition = null;
                drawBoard();
            }
            showError(message.substring("ERROR ".length()));
        } else if (message.startsWith("RESULT_ERROR ")) {
            String detail = message.substring("RESULT_ERROR ".length());
            showError(detail);
        }
        // TURN follows BOARD; controls reflect the complete state once both are processed.
        updateControls();
    }

    private void handleGameOver(String message) {
        String[] fields = message.trim().split("\\s+");
        if (fields.length < 3) {
            showStatus("The server ended the match.");
            gameOver = true;
            return;
        }

        char winner = fields[1].charAt(0);
        String reason = fields[2];
        gameOver = true;
        isInRoom = false;
        resignRequestPending = false;
        moveRequestPending = false;
        selectedPosition = null;
        matchStatusLabel.setText("Player " + winner + " wins");
        turnLabel.setText("Match finished");
        drawBoard();
        if ("RESIGNATION".equals(reason)) {
            showStatus(myPlayerColor != null && winner == myPlayerColor.charAt(0)
                    ? "You win by resignation." : "You resigned. Player " + winner + " wins.");
        } else if ("OPPONENT_LEFT".equals(reason)) {
            showStatus("Opponent left. You win.");
        } else if ("OPPONENT_DISCONNECTED".equals(reason)) {
            showStatus("Opponent disconnected. You win.");
        } else {
            showStatus("Player " + winner + " wins by game rules.");
        }
    }

    private void resetOnlineRoom() {
        boardView.stopAnimation();
        isInRoom = false;
        gameOver = false;
        roomRequestPending = false;
        leaveRequestPending = false;
        resignRequestPending = false;
        myPlayerColor = null;
        currentRoomCode = null;
        copyRoomButton.setText("Copy");
        serverBoardSnapshot = null;
        serverTurn = '\0';
        moveRequestPending = false;
        selectedPosition = null;
        incomingMessages.clear();
        roomCodeLabel.setText("No room yet");
        playerColorLabel.setText("Your seat is waiting");
        turnLabel.setText("Waiting for a match");
        matchStatusLabel.setText("Ready when you are");
        drawBoard();
    }

    private void closeConnection() {
        RoomConnection connection = activeRoomConnection;
        activeRoomConnection = null;
        authenticatedUsername = null;
        isConnecting = false;
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public void stop() {
        applicationClosing = true;
        if (boardView != null) {
            boardView.stopAnimation();
        }
        closeConnection();
        if (accountDialog != null) {
            accountDialog.close();
        }
    }

    private void finalizeMoveOutcome() {
        char winner = botGame.getWinnerSymbol();

        if (winner != '\0') {
            gameOver = true;
            selectedPosition = null;
            drawBoard();
            String result = winner == 'X' ? "You win!" : "BOT wins.";
            matchStatusLabel.setText(result);
            turnLabel.setText("Match finished");
            showStatus(result);
            recordBotResult();
            updateControls();
            return;
        }

        selectedPosition = botGame.getCaptureContinuationPosition();
        if (selectedPosition != null) {
            showStatus(botGame.getCurrentPlayer().isBot()
                    ? "BOT continues its capture." : "Continue capturing with the same piece.");
        }

        turnLabel.setText(botGame.getCurrentPlayer().isBot() ? "BOT’s turn" : "Your turn");
        drawBoard();
        maybePerformBotTurn();
        updateControls();
    }

    private void maybePerformBotTurn() {
        if (!isOnlineMode && !gameOver && !boardView.isAnimating() && botGame.getCurrentPlayer().isBot()) {
            performBotMove();
        }
    }

    private void performBotMove() {
        Game.Move move = botPlayer.findBestMove(botGame, botDepth, 'o');
        if (move == null) {
            if (botGame.getWinnerSymbol() != '\0') {
                finalizeMoveOutcome();
            } else {
                showError("BOT could not choose a move.");
            }
            return;
        }
        applyLocalMove(move.getFrom(), move.getTo());
    }
}
