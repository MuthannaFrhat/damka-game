import javafx.application.Application;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Scene;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.control.ScrollPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.InetSocketAddress;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DamkaFX extends Application {
    private Game game;
    private GridPane boardGrid;
    private StackPane boardStack;
    private final StackPane[][] boardCells = new StackPane[8][8];
    private final Rectangle[][] squareNodes = new Rectangle[8][8];
    private final Circle[][] selectionRings = new Circle[8][8];
    private final Circle[][] pieceNodes = new Circle[8][8];
    private Label turnLabel;
    private Position selectedPosition;
    private boolean gameOver = false;
    private Button botButton;
    private ComboBox<String> difficultyBox;
    private HBox difficultyControls;
    private Pane animationPane;
    private StackPane toastPane;
    private BotPlayer botPlayer;
    private boolean botMode = false;
    private int botDepth = 1;
    private boolean mustContinueCapture = false;
    private Position botCaptureContinuation;
    private Label connectionStatusLabel;
    private Label matchStatusLabel;
    private TextField serverAddressField;
    private TextField serverPortField;
    private TextField roomCodeField;
    private Button joinRoomButton;
    private Button createRoomButton;
    private Button resignButton;
    private Button leaveRoomButton;
    private Label roomCodeLabel;
    private Label playerColorLabel;
    private String myPlayerColor;
    private volatile Socket serverSocket;
    private volatile PrintWriter serverWriter;
    private ExecutorService networkWriter;
    private volatile int activeConnectionId = 0;
    private volatile boolean isConnecting = false;
    private volatile boolean applicationClosing = false;
    private volatile boolean roomRequestPending = false;
    private boolean leaveRequestPending;
    private volatile boolean isInRoom = false;
    private volatile String connectedHost;
    private volatile int connectedPort;
    private boolean isOnlineMode = true;
    private boolean onlineGameOver;
    private boolean moveRequestPending = false;
    private boolean moveBoardReceived = false;
        private boolean resignRequestPending;
    private String serverBoardSnapshot;
    private char serverTurn;
    private final Queue<String> incomingMessages = new ArrayDeque<>();
    private boolean processingIncomingMessages;
    private boolean animationRunning;
    private ParallelTransition activeMovementAnimation;
    private double tileSize = 68;

    private static final double MAX_TILE_SIZE = 70;

    @Override
    public void start(Stage stage) {
        game = new Game();
        botPlayer = new BotPlayer();
        networkWriter = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "damka-network-writer");
            thread.setDaemon(true);
            return thread;
        });

        turnLabel = new Label("Waiting for a match");
        turnLabel.setId("turnLabel");
        botButton = new Button("Play vs BOT");
        difficultyBox = new ComboBox<>();
        difficultyBox.getItems().addAll("Easy", "Medium", "Hard");
        difficultyBox.setValue("Easy");
        difficultyControls = new HBox(8, new Label("BOT level"), difficultyBox);
        difficultyControls.getStyleClass().add("inline-settings");

        serverAddressField = new TextField("localhost");
        serverAddressField.setPromptText("Server address");
        serverPortField = new TextField("5000");
        serverPortField.setPromptText("Port");
        serverPortField.setPrefColumnCount(5);
        roomCodeField = new TextField();
        roomCodeField.setPromptText("Room code");
        roomCodeField.setPrefColumnCount(8);
        joinRoomButton = new Button("Join");
        createRoomButton = new Button("Create room");
        resignButton = new Button("Resign");
        resignButton.setId("resignButton");
        leaveRoomButton = new Button("Leave room");
        leaveRoomButton.setId("leaveRoomButton");
        connectionStatusLabel = new Label("Choose BOT or create/join an online room.");
        roomCodeLabel = new Label("Room code: not created");
        playerColorLabel = new Label("You are not assigned a player color");
        matchStatusLabel = new Label("No active match");
        connectionStatusLabel.getStyleClass().add("status-line");

        createRoomButton.setOnAction(e -> createRoom());
        joinRoomButton.setOnAction(e -> joinRoom());
        resignButton.setOnAction(e -> resignFromRoom());
        leaveRoomButton.setOnAction(e -> leaveRoom());

        botButton.setOnAction(e -> {
            if (isInRoom) {
                connectionStatusLabel.setText("Leave the online room before starting BOT play.");
                return;
            }
            stopMovementAnimation();
            isOnlineMode = false;
            serverBoardSnapshot = null;
            serverTurn = '\0';
            moveRequestPending = false;
            moveBoardReceived = false;
            botMode = true;
            game = new Game(false, true);
            botCaptureContinuation = null;
            selectedPosition = null;
            gameOver = false;
            mustContinueCapture = false;
            onlineGameOver = false;
            matchStatusLabel.setText("Local BOT match");
            turnLabel.setText("Turn: " + game.getCurrentPlayer().getName());
            drawBoard();
            botDepth = getDepthFromDifficulty();
            maybePerformBotTurn();
            updateControls();
        });

        difficultyBox.setOnAction(e -> {
            botDepth = getDepthFromDifficulty();
            if (botMode) {
                turnLabel.setText("Turn: " + game.getCurrentPlayer().getName());
                maybePerformBotTurn();
            }
        });

        boardGrid = new GridPane();
        boardGrid.setPrefSize(tileSize * 8, tileSize * 8);
        animationPane = new Pane();
        animationPane.setPickOnBounds(false);
        animationPane.setMouseTransparent(true);
        boardStack = new StackPane(boardGrid, animationPane);
        boardStack.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        boardStack.getStyleClass().add("board-frame");
        VBox boardSection = new VBox(boardStack);
        boardSection.setAlignment(Pos.CENTER);
        boardSection.setMaxWidth(Double.MAX_VALUE);

        Label titleLabel = new Label("DAMKA");
        titleLabel.getStyleClass().add("app-title");
        Label subtitleLabel = new Label("Online table");
        subtitleLabel.getStyleClass().add("app-subtitle");
        VBox brand = new VBox(2, titleLabel, subtitleLabel);
        brand.getStyleClass().add("brand-block");

        HBox serverSettings = new HBox(8, new Label("Server"), serverAddressField,
                new Label("Port"), serverPortField, botButton, difficultyControls);
        serverSettings.setAlignment(Pos.CENTER_LEFT);
        serverSettings.getStyleClass().add("connection-settings");

        HBox roomControls = new HBox(8, createRoomButton, roomCodeField, joinRoomButton,
                resignButton, leaveRoomButton);
        roomControls.setAlignment(Pos.CENTER_LEFT);
        roomControls.getStyleClass().add("room-controls");

        HBox identity = new HBox(12, playerColorLabel, roomCodeLabel);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.getStyleClass().add("identity-row");
        HBox matchInfo = new HBox(12, turnLabel, matchStatusLabel);
        matchInfo.setAlignment(Pos.CENTER_LEFT);
        matchInfo.getStyleClass().add("match-info");

        VBox header = new VBox(12, brand, serverSettings, roomControls, identity, matchInfo, connectionStatusLabel);
        header.getStyleClass().add("app-header");

        BorderPane content = new BorderPane();
        content.setTop(header);
        content.setCenter(boardSection);
        content.getStyleClass().add("app-content");

        // Toast / notification overlay
        toastPane = new StackPane();
        toastPane.setPickOnBounds(false);
        toastPane.setMouseTransparent(true);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");

        StackPane appRoot = new StackPane(scrollPane, toastPane);
        appRoot.getStyleClass().add("app-root");
        StackPane.setAlignment(toastPane, Pos.TOP_CENTER);
        StackPane.setMargin(toastPane, new Insets(10, 0, 0, 0));

        Scene scene = new Scene(appRoot);
        scene.getStylesheets().add(getClass().getResource("/com/example/damka1/damka.css").toExternalForm());
        Runnable resizeToWindow = () -> {
            double availableHeight = Math.max(40, scene.getHeight() - header.getHeight() - 56);
            resizeBoard(Math.min(boardSection.getWidth(), availableHeight));
        };
        boardSection.widthProperty().addListener((observable, oldWidth, newWidth) -> resizeToWindow.run());
        scene.widthProperty().addListener((observable, oldWidth, newWidth) -> resizeToWindow.run());
        scene.heightProperty().addListener((observable, oldHeight, newHeight) -> resizeToWindow.run());
        header.heightProperty().addListener((observable, oldHeight, newHeight) -> resizeToWindow.run());

        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(980, visualBounds.getWidth() * 0.92));
        stage.setHeight(Math.min(900, visualBounds.getHeight() * 0.9));
        stage.setTitle("Damka | Online Checkers");
        stage.setScene(scene);
        stage.show();
        stage.centerOnScreen();
        resizeBoard(boardSection.getWidth());
        drawBoard();
        updateControls();
    }

    private void resizeBoard(double availableWidth) {
        if (availableWidth <= 0) {
            return;
        }
        double nextTileSize = Math.max(32, Math.min(MAX_TILE_SIZE, (availableWidth - 24) / 8));
        if (Math.abs(nextTileSize - tileSize) < 0.5) {
            return;
        }
        tileSize = nextTileSize;
        double boardSize = tileSize * 8;
        boardGrid.setPrefSize(boardSize, boardSize);
        boardGrid.setMaxSize(boardSize, boardSize);
        animationPane.setPrefSize(boardSize, boardSize);
        boardStack.setPrefSize(boardSize + 16, boardSize + 16);
        if (animationRunning) {
            resizeExistingBoardNodes();
        } else {
            drawBoard();
        }
    }

    private void resizeExistingBoardNodes() {
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                StackPane cell = boardCells[row][col];
                if (cell == null) {
                    continue;
                }
                cell.setPrefSize(tileSize, tileSize);
                squareNodes[row][col].setWidth(tileSize);
                squareNodes[row][col].setHeight(tileSize);
                Circle ring = selectionRings[row][col];
                if (ring != null) {
                    ring.setRadius(tileSize * 0.39);
                    ring.setStrokeWidth(Math.max(3, tileSize * 0.065));
                }
                Circle piece = pieceNodes[row][col];
                if (piece != null) {
                    piece.setRadius(tileSize * 0.35);
                    if (piece.getStroke() != null) {
                        piece.setStrokeWidth(Math.max(3, tileSize * 0.06));
                    }
                }
            }
        }
    }

    private void drawBoard() {
        boardGrid.getChildren().clear();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                boardCells[row][col] = null;
                squareNodes[row][col] = null;
                selectionRings[row][col] = null;
                pieceNodes[row][col] = null;
            }
        }

        Board board = game.getBoard();

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Rectangle square = new Rectangle(tileSize, tileSize);
                squareNodes[row][col] = square;

                if ((row + col) % 2 == 0) {
                    square.setFill(Color.web("#DCE8D5"));
                } else {
                    square.setFill(Color.web("#315B4C"));
                }

                StackPane cell = new StackPane();
                cell.setPrefSize(tileSize, tileSize);
                cell.getChildren().add(square);

                // Selection ring
                if (selectedPosition != null
                        && selectedPosition.getRow() == row
                        && selectedPosition.getCol() == col) {
                    Circle ring = new Circle(tileSize * 0.39);
                    ring.setFill(Color.TRANSPARENT);
                    ring.setStroke(Color.web("#F0B44D"));
                    ring.setStrokeWidth(Math.max(3, tileSize * 0.065));
                    ring.setMouseTransparent(true);
                    cell.getChildren().add(ring);
                    selectionRings[row][col] = ring;
                }

                Piece piece;
                if (isOnlineMode) {
                    char symbol = serverBoardSnapshot == null
                            ? '.'
                            : serverBoardSnapshot.charAt(row * 8 + col);
                    piece = symbol == '.' ? null : new Piece(symbol);
                } else {
                    piece = board.getPiece(row, col);
                }

                if (piece != null) {
                    double radius = tileSize * 0.35;
                    Circle circle = new Circle(radius);

                    if (piece.getSymbol() == 'x' || piece.getSymbol() == 'X') {
                        circle.setFill(Color.web("#172A27"));
                    } else {
                        circle.setFill(Color.web("#E76752"));
                    }

                    if (piece.isKing()) {
                        circle.setStroke(Color.web("#F0B44D"));
                        circle.setStrokeWidth(Math.max(3, tileSize * 0.06));
                    }

                    circle.setMouseTransparent(true);
                    DropShadow ds = new DropShadow(6, Color.color(0, 0, 0, 0.45));
                    circle.setEffect(ds);

                    cell.getChildren().add(circle);
                    pieceNodes[row][col] = circle;
                }

                boardCells[row][col] = cell;
                final int finalRow = row;
                final int finalCol = col;
                cell.setOnMouseClicked(e -> handleClick(finalRow, finalCol));

                boardGrid.add(cell, col, row);
            }
        }
    }

    private void handleClick(int row, int col) {
        if (isOnlineMode) {
            handleOnlineClick(row, col);
            return;
        }

        if (gameOver || animationRunning) {
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
                    Position captured = wasCapture
                        ? new Position((selectedPosition.getRow() + target.getRow()) / 2,
                            (selectedPosition.getCol() + target.getCol()) / 2)
                        : null;
                    animateMove(selectedPosition, target, movingPieceCopy, captured,
                        () -> finalizeMoveOutcome(target, wasCapture));
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
        moveBoardReceived = false;
        drawBoard();
        sendOnlineMove(from, to);
    }

    private boolean canInteractOnline() {
        return isOnlineMode && isInRoom && serverBoardSnapshot != null
                && myPlayerColor != null && serverTurn == myPlayerColor.charAt(0)
            && !moveRequestPending && !animationRunning && !onlineGameOver;
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

    private void updateControls() {
        boolean connected = serverSocket != null && !serverSocket.isClosed();
        boolean canChooseRoom = !isConnecting && !roomRequestPending && !isInRoom;
        createRoomButton.setDisable(!canChooseRoom);
        joinRoomButton.setDisable(!canChooseRoom);
        roomCodeField.setDisable(!canChooseRoom);
        botButton.setDisable(isConnecting || isInRoom);
        difficultyBox.setDisable(isInRoom || !botMode);
        resignButton.setDisable(!isInRoom || serverBoardSnapshot == null || onlineGameOver
            || resignRequestPending || isConnecting || animationRunning);
        leaveRoomButton.setDisable(!isInRoom || leaveRequestPending || isConnecting || animationRunning);
        serverAddressField.setDisable(isConnecting || (connected && isInRoom));
        serverPortField.setDisable(isConnecting || (connected && isInRoom));
    }

    private void createRoom() {
        submitRoomRequest("CREATE_ROOM");
    }

    private void joinRoom() {
        String roomCode = roomCodeField.getText() == null ? "" : roomCodeField.getText().trim().toUpperCase();
        if (!roomCode.matches("[A-Z0-9]{6}")) {
            connectionStatusLabel.setText(roomCode.isEmpty()
                    ? "Enter the six-character room code."
                    : "Room codes contain six letters or numbers.");
            roomCodeField.requestFocus();
            return;
        }
        roomCodeField.setText(roomCode);
        submitRoomRequest("JOIN_ROOM " + roomCode);
    }

    private void submitRoomRequest(String request) {
        if (isConnecting || roomRequestPending || isInRoom) {
            return;
        }

        String host = serverAddressField.getText() == null ? "" : serverAddressField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(serverPortField.getText().trim());
            if (host.isEmpty() || port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            connectionStatusLabel.setText("Enter a server address and port from 1 to 65535.");
            return;
        }

        isOnlineMode = true;
        botMode = false;
        roomRequestPending = true;
        onlineGameOver = false;
        serverBoardSnapshot = null;
        serverTurn = '\0';
        selectedPosition = null;
        moveRequestPending = false;
        moveBoardReceived = false;
        incomingMessages.clear();
        turnLabel.setText("Waiting for the server");
        matchStatusLabel.setText("Connecting to match");
        drawBoard();
        updateControls();

        boolean connected = serverSocket != null && !serverSocket.isClosed();
        if (connected && host.equals(connectedHost) && port == connectedPort) {
            connectionStatusLabel.setText("Sending room request...");
            sendNetworkCommand(request);
            return;
        }

        if (connected) {
            closeActiveSocket();
        }
        connectAndSend(host, port, request);
    }

    private void connectAndSend(String host, int port, String request) {
        final int connectionId = ++activeConnectionId;
        isConnecting = true;
        connectionStatusLabel.setText("Connecting to " + host + ":" + port + "...");
        updateControls();

        networkWriter.execute(() -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), 5000);
                PrintWriter writer = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                if (applicationClosing || connectionId != activeConnectionId) {
                    closeSocketQuietly(socket);
                    return;
                }

                serverSocket = socket;
                serverWriter = writer;
                connectedHost = host;
                connectedPort = port;
                writer.println(request);
                if (writer.checkError()) {
                    throw new IOException("Could not send the room request.");
                }

                Platform.runLater(() -> {
                    if (connectionId != activeConnectionId) {
                        closeSocketQuietly(socket);
                        return;
                    }
                    isConnecting = false;
                    updateControls();
                    connectionStatusLabel.setText("Connected to " + host + ":" + port + ". Waiting for server...");
                });
                startReader(socket, reader, connectionId);
            } catch (IOException exception) {
                closeSocketQuietly(socket);
                String detail = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                Platform.runLater(() -> handleConnectionFailure(connectionId, detail));
            }
        });
    }

    private void startReader(Socket socket, BufferedReader reader, int connectionId) {
        Thread readerThread = new Thread(() -> {
            try {
                String message;
                while (!applicationClosing && (message = reader.readLine()) != null) {
                    final String received = message;
                    Platform.runLater(() -> enqueueServerMessage(received, connectionId));
                }
            } catch (IOException exception) {
                if (!applicationClosing) {
                    Platform.runLater(() -> handleConnectionFailure(connectionId, "Connection lost."));
                }
            } finally {
                closeSocketQuietly(socket);
                if (!applicationClosing) {
                    Platform.runLater(() -> handleConnectionClosed(connectionId));
                }
            }
        }, "damka-server-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void sendNetworkCommand(String command) {
        final int connectionId = activeConnectionId;
        final PrintWriter writer = serverWriter;
        if (writer == null || serverSocket == null || serverSocket.isClosed()) {
            handleConnectionFailure(connectionId, "No active server connection.");
            return;
        }

        networkWriter.execute(() -> {
            writer.println(command);
            if (writer.checkError()) {
                Platform.runLater(() -> handleConnectionFailure(connectionId, "Could not send the request."));
            }
        });
    }

    private void sendOnlineMove(Position from, Position to) {
        String request = "MOVE " + from.getRow() + " " + from.getCol()
                + " " + to.getRow() + " " + to.getCol();
        sendNetworkCommand(request);
    }

    private void resignFromRoom() {
        if (!isInRoom || onlineGameOver) {
            return;
        }
        onlineGameOver = true;
        resignRequestPending = true;
        moveRequestPending = false;
        selectedPosition = null;
        sendNetworkCommand("RESIGN");
        connectionStatusLabel.setText("Resigning...");
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
        connectionStatusLabel.setText("Leaving room...");
        updateControls();
    }

    private void enqueueServerMessage(String message, int connectionId) {
        if (connectionId != activeConnectionId) {
            return;
        }
        incomingMessages.add(message);
        processIncomingMessages(connectionId);
    }

    private void processIncomingMessages(int connectionId) {
        if (animationRunning || connectionId != activeConnectionId) {
            return;
        }
        while (!incomingMessages.isEmpty() && !animationRunning) {
            String message = incomingMessages.remove();
            if (message.startsWith("MOVE_APPLIED ")) {
                if (startOnlineMoveAnimation(message, connectionId)) {
                    return;
                }
            } else {
                handleServerMessage(message, connectionId);
            }
        }
    }

    private boolean startOnlineMoveAnimation(String message, int connectionId) {
        String[] fields = message.trim().split("\\s+");
        if (fields.length != 7 || serverBoardSnapshot == null) {
            return false;
        }

        try {
            int fromRow = Integer.parseInt(fields[1]);
            int fromCol = Integer.parseInt(fields[2]);
            int toRow = Integer.parseInt(fields[3]);
            int toCol = Integer.parseInt(fields[4]);
            int capturedRow = Integer.parseInt(fields[5]);
            int capturedCol = Integer.parseInt(fields[6]);
            if (!isBoardCoordinate(fromRow, fromCol) || !isBoardCoordinate(toRow, toCol)
                    || (capturedRow != -1 && !isBoardCoordinate(capturedRow, capturedCol))) {
                return false;
            }

            char symbol = serverBoardSnapshot.charAt(fromRow * 8 + fromCol);
            if (symbol == '.' || (capturedRow == -1) != (capturedCol == -1)) {
                return false;
            }
            Position captured = capturedRow == -1 ? null : new Position(capturedRow, capturedCol);
            animationRunning = true;
            selectedPosition = null;
            animateMove(new Position(fromRow, fromCol), new Position(toRow, toCol), new Piece(symbol), captured,
                    () -> {
                        animationRunning = false;
                        processIncomingMessages(connectionId);
                    });
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean isBoardCoordinate(int row, int col) {
        return row >= 0 && row < 8 && col >= 0 && col < 8;
    }

    private void handleServerMessage(String message, int connectionId) {
        if (connectionId != activeConnectionId) {
            return;
        }

        if (message.startsWith("BOARD ")) {
            String snapshot = message.substring("BOARD ".length());
            serverTurn = '\0';
            if (!snapshot.matches("[.xoXO]{64}")) {
                connectionStatusLabel.setText("The server sent an invalid board.");
                return;
            }
            serverBoardSnapshot = snapshot;
            if (moveRequestPending) {
                moveBoardReceived = true;
            }
            drawBoard();
        } else if (message.startsWith("TURN ")) {
            String turn = message.substring("TURN ".length()).trim();
            if ("X".equals(turn) || "O".equals(turn)) {
                serverTurn = turn.charAt(0);
                if (moveRequestPending && moveBoardReceived) {
                    moveRequestPending = false;
                    moveBoardReceived = false;
                    connectionStatusLabel.setText("Move accepted.");
                }
                turnLabel.setText("Player " + serverTurn + " to move");
            }
        } else if (message.startsWith("PLAYER ")) {
            String playerColor = message.substring("PLAYER ".length()).trim();
            if ("X".equals(playerColor) || "O".equals(playerColor)) {
                myPlayerColor = playerColor;
                playerColorLabel.setText("You are player " + playerColor);
            }
        } else if (message.startsWith("ROOM_CREATED ")) {
            String code = message.substring("ROOM_CREATED ".length()).trim();
            roomCodeField.setText(code);
            roomCodeLabel.setText("Room " + code);
            isInRoom = true;
            roomRequestPending = false;
            matchStatusLabel.setText("Waiting for opponent");
            connectionStatusLabel.setText("Room created. Share the code to invite a player.");
            updateControls();
        } else if (message.startsWith("ROOM_JOINED ")) {
            String code = message.substring("ROOM_JOINED ".length()).trim();
            roomCodeField.setText(code);
            roomCodeLabel.setText("Room " + code);
            isInRoom = true;
            roomRequestPending = false;
            matchStatusLabel.setText("Joining opponent");
            connectionStatusLabel.setText("Joined room " + code + ".");
            updateControls();
        } else if (message.startsWith("ROOM_READY ")) {
            isInRoom = true;
            roomRequestPending = false;
            onlineGameOver = false;
            matchStatusLabel.setText("Online match");
            connectionStatusLabel.setText("Both players connected.");
            updateControls();
        } else if (message.startsWith("GAME_OVER ")) {
            handleGameOver(message);
        } else if ("ROOM_LEFT".equals(message)) {
            resetOnlineRoom();
            connectionStatusLabel.setText("You left the room. Create or join another match.");
            updateControls();
        } else if (message.startsWith("ROOM_CLOSED")) {
            resetOnlineRoom();
            connectionStatusLabel.setText("The room is closed.");
            updateControls();
        } else if (message.startsWith("ERROR ")) {
            roomRequestPending = false;
            leaveRequestPending = false;
            if (resignRequestPending) {
                resignRequestPending = false;
                onlineGameOver = false;
            }
            if (moveRequestPending) {
                moveRequestPending = false;
                moveBoardReceived = false;
                selectedPosition = null;
                drawBoard();
            }
            connectionStatusLabel.setText(message.substring("ERROR ".length()));
            updateControls();
        }
    }

    private void handleGameOver(String message) {
        String[] fields = message.trim().split("\\s+");
        if (fields.length < 3) {
            connectionStatusLabel.setText("The server ended the match.");
            onlineGameOver = true;
            return;
        }

        char winner = fields[1].charAt(0);
        String reason = fields[2];
        onlineGameOver = true;
        isInRoom = false;
        resignRequestPending = false;
        moveRequestPending = false;
        moveBoardReceived = false;
        selectedPosition = null;
        matchStatusLabel.setText("Player " + winner + " wins");
        if ("RESIGNATION".equals(reason)) {
            connectionStatusLabel.setText(myPlayerColor != null && winner == myPlayerColor.charAt(0)
                    ? "You win by resignation." : "You resigned. Player " + winner + " wins.");
            isInRoom = false;
        } else if ("OPPONENT_LEFT".equals(reason)) {
            connectionStatusLabel.setText("Opponent left. You win.");
            isInRoom = false;
        } else if ("OPPONENT_DISCONNECTED".equals(reason)) {
            connectionStatusLabel.setText("Opponent disconnected. You win.");
            isInRoom = false;
        } else {
            connectionStatusLabel.setText("Player " + winner + " wins by game rules.");
        }
        updateControls();
    }

    private void resetOnlineRoom() {
        stopMovementAnimation();
        isInRoom = false;
        onlineGameOver = false;
        roomRequestPending = false;
        leaveRequestPending = false;
        resignRequestPending = false;
        myPlayerColor = null;
        serverBoardSnapshot = null;
        serverTurn = '\0';
        moveRequestPending = false;
        moveBoardReceived = false;
        selectedPosition = null;
        incomingMessages.clear();
        roomCodeLabel.setText("Room code: not created");
        playerColorLabel.setText("Choose a room");
        turnLabel.setText("Waiting for a match");
        matchStatusLabel.setText("No active match");
        drawBoard();
    }

    private void handleConnectionFailure(int connectionId, String detail) {
        if (connectionId != activeConnectionId) {
            return;
        }
        isConnecting = false;
        closeActiveSocket();
        resetOnlineRoom();
        connectionStatusLabel.setText("Connection failed: " + detail);
        updateControls();
    }

    private void handleConnectionClosed(int connectionId) {
        if (connectionId != activeConnectionId) {
            return;
        }
        serverSocket = null;
        serverWriter = null;
        connectedHost = null;
        connectedPort = 0;
        isConnecting = false;
        resetOnlineRoom();
        connectionStatusLabel.setText("Server connection closed. You can reconnect by creating or joining a room.");
        updateControls();
    }

    private void closeActiveSocket() {
        Socket socket = serverSocket;
        serverSocket = null;
        serverWriter = null;
        connectedHost = null;
        connectedPort = 0;
        activeConnectionId++;
        closeSocketQuietly(socket);
    }

    private void closeSocketQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Socket close is best-effort during cleanup.
            }
        }
    }

    @Override
    public void stop() {
        applicationClosing = true;
        activeConnectionId++;
        stopMovementAnimation();
        closeActiveSocket();
        if (networkWriter != null) {
            networkWriter.shutdownNow();
        }
    }

    private void animateMove(Position from, Position to, Piece movingPiece,
                             Position capturedPosition, Runnable onFinished) {
        Circle sourcePiece = pieceNodes[from.getRow()][from.getCol()];
        Circle capturedPiece = capturedPosition == null
                ? null : pieceNodes[capturedPosition.getRow()][capturedPosition.getCol()];
        if (sourcePiece == null || boardCells[from.getRow()][from.getCol()] == null
                || boardCells[to.getRow()][to.getCol()] == null) {
            onFinished.run();
            return;
        }

        animationPane.getChildren().clear();
        sourcePiece.setVisible(false);
        if (capturedPiece != null) {
            capturedPiece.setOpacity(1);
        }

        Circle animatedCircle = new Circle(tileSize * 0.35);
        if (movingPiece.getSymbol() == 'x' || movingPiece.getSymbol() == 'X') {
            animatedCircle.setFill(Color.web("#172A27"));
        } else {
            animatedCircle.setFill(Color.web("#E76752"));
        }
        if (movingPiece.isKing()) {
            animatedCircle.setStroke(Color.web("#F0B44D"));
            animatedCircle.setStrokeWidth(Math.max(3, tileSize * 0.06));
        }

        animatedCircle.setMouseTransparent(true);
        animationPane.getChildren().add(animatedCircle);

        SimpleDoubleProperty progress = new SimpleDoubleProperty(0);
        progress.addListener((observable, previous, current) ->
                updateAnimatedPosition(animatedCircle, from, to, current.doubleValue()));
        updateAnimatedPosition(animatedCircle, from, to, 0);

        Timeline travel = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(progress, 0)),
                new KeyFrame(Duration.millis(320), new KeyValue(progress, 1)));
        if (capturedPiece != null) {
            FadeTransition captureFade = new FadeTransition(Duration.millis(240), capturedPiece);
            captureFade.setDelay(Duration.millis(80));
            captureFade.setFromValue(1);
            captureFade.setToValue(0);
            activeMovementAnimation = new ParallelTransition(travel, captureFade);
        } else {
            activeMovementAnimation = new ParallelTransition(travel);
        }
        animationRunning = true;
        activeMovementAnimation.setOnFinished(event -> {
            animationPane.getChildren().clear();
            activeMovementAnimation = null;
            animationRunning = false;
            onFinished.run();
        });
        activeMovementAnimation.play();
    }

    private void updateAnimatedPosition(Circle animatedCircle, Position from, Position to, double progress) {
        Point2D start = getCellCenterInOverlay(from);
        Point2D end = getCellCenterInOverlay(to);
        animatedCircle.setRadius(tileSize * 0.35);
        animatedCircle.setCenterX(start.getX() + (end.getX() - start.getX()) * progress);
        animatedCircle.setCenterY(start.getY() + (end.getY() - start.getY()) * progress);
    }

    private Point2D getCellCenterInOverlay(Position position) {
        StackPane cell = boardCells[position.getRow()][position.getCol()];
        Bounds sceneBounds = cell.localToScene(cell.getBoundsInLocal());
        Point2D sceneCenter = new Point2D(
                sceneBounds.getMinX() + sceneBounds.getWidth() / 2,
                sceneBounds.getMinY() + sceneBounds.getHeight() / 2);
        return animationPane.sceneToLocal(sceneCenter);
    }

    private void stopMovementAnimation() {
        if (activeMovementAnimation != null) {
            activeMovementAnimation.stop();
            activeMovementAnimation = null;
        }
        animationPane.getChildren().clear();
        animationRunning = false;
    }

    private void finalizeMoveOutcome(Position target, boolean wasCapture) {
        if (botMode && game.getCurrentPlayer().isBot() && wasCapture && game.canCaptureAgain(target)) {
            botCaptureContinuation = target;
        } else {
            botCaptureContinuation = null;
        }

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
            maybePerformBotTurn();
            return;
        }

        selectedPosition = null;
        mustContinueCapture = false;
        turnLabel.setText("Turn: " + game.getCurrentPlayer().getName());
        drawBoard();
        maybePerformBotTurn();
    }

    private void maybePerformBotTurn() {
        if (botMode && !gameOver && !animationRunning && game.getCurrentPlayer().isBot()) {
            performBotMove();
        }
    }

    private void performBotMove() {
        BotPlayer.MoveChoice botMove = botPlayer.findBestMove(
            game, botDepth, game.getCurrentPlayer().getSymbol(), botCaptureContinuation);
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
            Position captured = wasCapture
                ? new Position((botMove.getFrom().getRow() + botMove.getTo().getRow()) / 2,
                    (botMove.getFrom().getCol() + botMove.getTo().getCol()) / 2)
                : null;
            animateMove(botMove.getFrom(), botMove.getTo(), movingPieceCopy, captured,
                () -> finalizeMoveOutcome(botMove.getTo(), wasCapture));
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