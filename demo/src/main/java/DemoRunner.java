import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Drives the real JavaFX screens with mouse/keyboard input and optionally records them. */
public final class DemoRunner {
    private static final String PASSWORD = "demopass123"; // Disposable demo accounts only.
    private final List<Client> clients = new ArrayList<>();
    private final List<Throwable> asynchronousErrors = new CopyOnWriteArrayList<>();
    private final double speed = Double.parseDouble(System.getProperty("damka.demo.speed", "1"));
    private final String session = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    private final Path outputDirectory = Path.of(System.getProperty("damka.demo.output", "demo/output"));
    private Robot robot;
    private Rectangle2D screen;
    private Stage background;
    private Stage caption;
    private Label chapter;
    private Label explanation;
    private ServerSocket server;
    private volatile boolean cancelled;
    private boolean singleWindow;

    private record Client(DamkaFX app, Stage stage, String username, int seat) {}

    public static void main(String[] args) throws Exception {
        DemoRunner demo = new DemoRunner();
        if (!Double.isFinite(demo.speed) || demo.speed <= 0) {
            throw new IllegalArgumentException("demoSpeed must be a positive number.");
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            demo.asynchronousErrors.add(error);
            error.printStackTrace();
        });
        Platform.startup(() -> Platform.setImplicitExit(false));
        try {
            demo.run();
        } finally {
            demo.cleanup();
        }
    }

    private void run() throws Exception {
        Files.createDirectories(outputDirectory);
        prepareDesktop();
        Path video = outputDirectory.resolve("damka-demo-" + session + ".mp4");
        boolean record = Boolean.parseBoolean(System.getProperty("damka.demo.record", "true"));
        try (DemoRecorder recorder = record ? DemoRecorder.start(video, screen) : null) {
            chapter("1 · Start the server and clients", "Two real game windows, each with its own TCP connection.");
            startServer();
            Client green = openClient("GreenPlayer", 0);
            Client red = openClient("RedPlayer", 1);
            pause(1400);

            chapter("2 · Create player accounts", "Sign up in each window. These accounts use a separate demo database.");
            signUp(green);
            signUp(red);
            pause(1000);

            chapter("3 · Create and join a room", "Green shares the room code. Red joins the same server-side game.");
            clickField(green, "createRoomButton");
            await("room creation", () -> read(green.app(), "currentRoomCode") != null);
            pause(1400);
            clickField(green, "copyRoomButton");
            String roomCode = fx(() -> (String) read(green.app(), "currentRoomCode"));
            await("room code copied", () -> roomCode.equals(Clipboard.getSystemClipboard().getString()));
            clickField(red, "roomCodeField");
            await("room field focus", () -> ((Node) read(red.app(), "roomCodeField")).isFocused());
            shortcut(KeyCode.V);
            await("room code pasted", () -> roomCode.equals(((TextInputControl) read(red.app(), "roomCodeField")).getText()));
            pause(700);
            clickField(red, "joinRoomButton");
            await("both players ready", () -> read(green.app(), "serverTurn").equals('X')
                    && read(red.app(), "serverTurn").equals('X') && sameBoards(green, red));
            pause(2000);

            playOnlineMatch(green, red);
            showProfiles(green, red);
            playHardBot(green, red);

            chapter("Demo complete", "TCP rooms · validated moves · saved profiles · Hard BOT with Minimax");
            pause(3000);
        }
        System.out.println(record ? "VIDEO SAVED: " + video.toAbsolutePath() : "DEMO FLOW PASSED (recording disabled)");
    }

    private void startServer() throws Exception {
        Path database = outputDirectory.resolve("session-" + session).resolve("demo.db");
        ProfileDatabase profiles = new ProfileDatabase(database);
        server = new ServerSocket(0);
        System.setProperty("damka.server.host", "127.0.0.1");
        System.setProperty("damka.server.port", Integer.toString(server.getLocalPort()));
        Thread listener = new Thread(() -> {
            try {
                DamkaServer.serve(server, profiles);
            } catch (Exception failure) {
                if (!server.isClosed()) asynchronousErrors.add(failure);
            }
        }, "demo-server");
        listener.setDaemon(true);
        listener.start();
    }

    private void prepareDesktop() throws Exception {
        fx(() -> {
            screen = Screen.getPrimary().getVisualBounds();
            if (screen.getWidth() < 1800 || screen.getHeight() < 900) {
                throw new IllegalStateException("Use a desktop of at least 1800 × 900 for two readable game windows.");
            }
            robot = new Robot();
            background = new Stage(StageStyle.UNDECORATED);
            StackPane paper = new StackPane();
            paper.setStyle("-fx-background-color: #f7f6f1;");
            background.setScene(new Scene(paper));
            place(background, screen.getMinX(), screen.getMinY(), screen.getWidth(), screen.getHeight());
            background.show();
            caption = new Stage(StageStyle.UNDECORATED);
            chapter = new Label("Preparing the demonstration");
            chapter.setStyle("-fx-font-size: 23px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");
            explanation = new Label("Press Esc in a demo window to stop.");
            explanation.setStyle("-fx-font-size: 15px; -fx-text-fill: #d5e7da;");
            VBox heading = new VBox(7, chapter, explanation);
            heading.setStyle("-fx-background-color: #234b3c; -fx-padding: 14 24;");
            Scene scene = new Scene(heading);
            allowEscape(scene);
            caption.setScene(scene);
            place(caption, screen.getMinX(), screen.getMinY(), screen.getWidth(), 84);
            caption.setAlwaysOnTop(true);
            caption.show();
            return null;
        });
        pause(400);
    }

    private Client openClient(String username, int seat) throws Exception {
        return fx(() -> {
            DamkaFX app = new DamkaFX();
            Stage stage = new Stage();
            Client client = new Client(app, stage, username, seat);
            clients.add(client);
            stage.sceneProperty().addListener((observable, oldScene, newScene) -> {
                allowEscape(newScene);
                Platform.runLater(() -> arrange(client));
            });
            app.start(stage);
            arrange(client);
            return client;
        });
    }

    private void arrange(Client client) {
        double y = screen.getMinY() + 92;
        double height = screen.getHeight() - 100;
        if (singleWindow && client.seat() == 0) {
            double width = Math.min(1180, screen.getWidth() - 32);
            place(client.stage(), screen.getMinX() + (screen.getWidth() - width) / 2, y, width, height);
        } else {
            double width = (screen.getWidth() - 18) / 2;
            place(client.stage(), screen.getMinX() + 6 + client.seat() * (width + 6), y, width, height);
        }
        client.stage().setTitle(client.username() + " · Damka");
    }

    private static void place(Stage stage, double x, double y, double width, double height) {
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(x);
        stage.setY(y);
    }

    private void signUp(Client client) throws Exception {
        // On Windows, the first native click can activate a window without its control.
        for (int attempt = 0; attempt < 3 && !fx(() -> signupSelected(client)); attempt++) {
            click(client, "#accountSignupTab");
            pause(250);
        }
        await(client.username() + " Create account tab", () -> signupSelected(client));
        enter(client, "#accountUsername", client.username());
        enter(client, "#accountPassword", PASSWORD);
        enter(client, "#accountConfirmPassword", PASSWORD);
        pause(800);
        if (!fx(() -> signupSelected(client))) {
            throw new IllegalStateException("The demo account form left Create account mode before submission.");
        }
        click(client, "#accountSubmit");
        await(client.username() + " authentication", () -> client.username().equals(read(client.app(), "authenticatedUsername"))
                && read(client.app(), "boardView") != null);
        fx(() -> { arrange(client); return null; });
        pause(1300);
    }

    private static boolean signupSelected(Client client) {
        Node tab = lookup(client.stage(), "#accountSignupTab");
        Node confirmation = lookup(client.stage(), "#accountConfirmPassword");
        return tab instanceof ToggleButton button && button.isSelected()
                && confirmation != null && visibleInWindow(confirmation);
    }

    private void playOnlineMatch(Client green, Client red) throws Exception {
        Game replay = new Game();
        List<DemoMoves.Step> moves = DemoMoves.onlineMatch();
        for (int index = 0; index < moves.size(); index++) {
            DemoMoves.Step move = moves.get(index);
            char player = Character.toUpperCase(replay.getCurrentPlayer().getSymbol());
            Client active = player == 'X' ? green : red;
            String color = player == 'X' ? "Green" : "Red";
            chapter("4 · A complete online match", "Move " + (index + 1) + " / " + moves.size()
                    + " · " + active.username() + " plays " + color + " · the server validates every move.");
            clickCell(active, move.fromRow(), move.fromCol());
            pause(180);
            clickCell(active, move.toRow(), move.toCol());
            if (!replay.move(new Position(move.fromRow(), move.fromCol()), new Position(move.toRow(), move.toCol()))) {
                throw new IllegalStateException("The prepared replay is no longer legal at move " + (index + 1));
            }
            String expectedBoard = encode(replay);
            await("online move " + (index + 1), () -> settled(green) && settled(red)
                    && expectedBoard.equals(read(green.app(), "serverBoardSnapshot")) && sameBoards(green, red));
            pause(450);
            System.out.println("Online move " + (index + 1) + "/" + moves.size() + " accepted in both windows.");
        }
        if (replay.getWinnerSymbol() != DemoMoves.expectedWinner()) {
            throw new IllegalStateException("The prepared match did not produce its expected rules win.");
        }
        await("rules winner in both windows", () -> (boolean) read(green.app(), "gameOver")
                && (boolean) read(red.app(), "gameOver"));
        chapter("Green wins by the game rules", "The normal starting board was played to completion, including captures and promotion.");
        pause(3000);
    }

    private void showProfiles(Client green, Client red) throws Exception {
        chapter("5 · Results saved to player profiles", "Green: one online win. Red: one online loss. Both histories contain this match.");
        for (Client client : clients) {
            clickField(client, "profileButton");
            await("profile " + client.username(), () -> read(client.app(), "profileWindow") != null
                    && ((Stage) read(client.app(), "profileWindow")).isShowing());
            fx(() -> {
                Stage profile = (Stage) read(client.app(), "profileWindow");
                place(profile, client.stage().getX() + 10, client.stage().getY() + 100,
                        client.stage().getWidth() - 20, Math.min(690, screen.getHeight() - 220));
                return null;
            });
            String statistic = client == green ? "#onlineWins" : "#onlineLosses";
            await("saved profile result", () -> {
                Stage profile = (Stage) read(client.app(), "profileWindow");
                Node value = lookup(profile, statistic);
                return value instanceof Label label && "1".equals(label.getText());
            });
            pause(700);
        }
        pause(3200);
        fx(() -> {
            for (Client client : clients) ((Stage) read(client.app(), "profileWindow")).close();
            return null;
        });
    }

    private void playHardBot(Client green, Client red) throws Exception {
        chapter("6 · Play against the Hard BOT", "Choose Hard. The BOT uses Minimax search depth 3 with alpha–beta pruning.");
        fx(() -> {
            red.stage().hide();
            singleWindow = true;
            arrange(green);
            return null;
        });
        pause(1000);
        clickField(green, "botButton");
        clickField(green, "difficultyBox");
        key(KeyCode.END);
        key(KeyCode.ENTER);
        await("Hard difficulty selected", () -> ((ComboBox<?>) read(green.app(), "difficultyBox"))
                .getSelectionModel().getSelectedIndex() == 2);
        pause(1000);
        clickField(green, "botButton"); // Start a fresh match whose recorded difficulty is Hard.
        await("Hard match started", () -> (int) read(green.app(), "botDepth") == 3
                && (int) read(green.app(), "botMatchDifficulty") == 3 && settled(green));
        pause(1300);
        for (int number = 1; number <= 8; number++) {
            Game local = fx(() -> new Game((Game) read(green.app(), "botGame")));
            if (local.getWinnerSymbol() != '\0') break;
            Game.Move previousMove = local.getLastMove();
            Game.Move move = local.getValidMoves(local.getCurrentPlayer().getSymbol()).get(0);
            chapter("Hard BOT · Minimax depth 3", "Human move " + number + " · GreenPlayer controls Green; the BOT responds as Red.");
            Position selected = fx(() -> (Position) read(green.app(), "selectedPosition"));
            if (selected == null || selected.getRow() != move.getFrom().getRow()
                    || selected.getCol() != move.getFrom().getCol()) {
                clickCell(green, move.getFrom().getRow(), move.getFrom().getCol());
            }
            pause(220);
            clickCell(green, move.getTo().getRow(), move.getTo().getCol());
            await("Hard BOT response", () -> {
                Game current = (Game) read(green.app(), "botGame");
                return current.getLastMove() != previousMove && settled(green)
                        && (current.getCurrentPlayer().getSymbol() == 'x' || (boolean) read(green.app(), "gameOver"));
            });
            pause(900);
        }
        if (!fx(() -> (int) read(green.app(), "botDepth") == 3)) {
            throw new IllegalStateException("BOT difficulty changed during the demonstration.");
        }
    }

    private void enter(Client client, String selector, String text) throws Exception {
        click(client, selector);
        await("input focus " + selector, () -> lookup(client.stage(), selector).isFocused());
        fx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
            return null;
        });
        shortcut(KeyCode.A);
        shortcut(KeyCode.V);
        // Check the result of native paste without printing password contents.
        await("input entered " + selector, () -> text.equals(((TextInputControl) lookup(client.stage(), selector)).getText()));
        pause(350);
    }

    private void click(Client client, String selector) throws Exception {
        clickNode(client, () -> lookup(client.stage(), selector));
    }

    private void clickField(Client client, String field) throws Exception {
        clickNode(client, () -> (Node) read(client.app(), field));
    }

    private void clickCell(Client client, int row, int column) throws Exception {
        // Selection redraws the cells: resolve the current cell for each click.
        clickNode(client, () -> {
            BoardView view = (BoardView) read(client.app(), "boardView");
            return ((StackPane[][]) read(view, "cells"))[row][column];
        });
    }

    private void clickNode(Client client, Callable<Node> target) throws Exception {
        focusClient(client);
        Point2D end = fx(() -> targetCenter(client, target.call()));
        Point2D start = fx(() -> robot.getMousePosition());
        for (int step = 1; step <= 8; step++) {
            double progress = step / 8.0;
            fx(() -> { robot.mouseMove(start.interpolate(end, progress)); return null; });
            pause(18);
        }
        // Activation, hover and form layout can move controls during the glide.
        fx(() -> { robot.mouseMove(targetCenter(client, target.call())); return null; });
        pause(70);
        fx(() -> { robot.mousePress(MouseButton.PRIMARY); return null; });
        pause(70);
        fx(() -> { robot.mouseRelease(MouseButton.PRIMARY); return null; });
        pause(130);
    }

    private void focusClient(Client client) throws Exception {
        if (fx(() -> client.stage().isFocused())) return;
        fx(() -> { client.stage().toFront(); client.stage().requestFocus(); return null; });
        // Native window activation must settle even during a fast rehearsal.
        Thread.sleep(200);
        if (!fx(() -> client.stage().isFocused())) {
            // A real title-bar click also works when Windows declines requestFocus().
            fx(() -> {
                robot.mouseMove(client.stage().getX() + client.stage().getWidth() / 2,
                        client.stage().getY() + 12);
                robot.mousePress(MouseButton.PRIMARY);
                return null;
            });
            Thread.sleep(80);
            fx(() -> { robot.mouseRelease(MouseButton.PRIMARY); return null; });
        }
        await(client.username() + " window focus", () -> client.stage().isFocused());
        Thread.sleep(150);
    }

    private static Point2D targetCenter(Client client, Node node) {
        client.stage().getScene().getRoot().applyCss();
        client.stage().getScene().getRoot().layout();
        if (node == null || node.isDisabled() || !visibleInWindow(node)) {
            throw new IllegalStateException("Demo control is missing, hidden or disabled.");
        }
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        if (bounds == null || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            throw new IllegalStateException("Demo control is not on screen.");
        }
        return new Point2D((bounds.getMinX() + bounds.getMaxX()) / 2, (bounds.getMinY() + bounds.getMaxY()) / 2);
    }

    private static boolean visibleInWindow(Node node) {
        if (node.getScene() == null || !node.getScene().getWindow().isShowing()) return false;
        for (Node ancestor = node; ancestor != null; ancestor = ancestor.getParent()) {
            if (!ancestor.isVisible()) return false;
        }
        return true;
    }

    private void shortcut(KeyCode code) throws Exception {
        fx(() -> { robot.keyPress(KeyCode.CONTROL); robot.keyType(code); robot.keyRelease(KeyCode.CONTROL); return null; });
        pause(120);
    }

    private void key(KeyCode code) throws Exception {
        fx(() -> { robot.keyType(code); return null; });
        pause(150);
    }

    private void chapter(String title, String detail) throws Exception {
        // Keep console logs readable with Windows terminal encodings; captions keep Unicode.
        System.out.println(consoleText(title) + " - " + consoleText(detail));
        fx(() -> { chapter.setText(title); explanation.setText(detail); return null; });
    }

    private static String consoleText(String text) {
        return text.replace('·', '-').replace('—', '-').replace('–', '-');
    }

    private void allowEscape(Scene scene) {
        if (scene != null) scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) cancelled = true;
        });
    }

    private void pause(long milliseconds) throws Exception {
        checkRunning();
        Thread.sleep(Math.max(20, Math.round(milliseconds / speed)));
    }

    private void await(String description, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            checkRunning();
            if (fx(condition)) return;
            Thread.sleep(35);
        }
        String detail = fx(() -> {
            StringBuilder messages = new StringBuilder();
            for (Client client : clients) {
                Node error = lookup(client.stage(), "#accountError");
                if (error instanceof Label label && !label.getText().isBlank()) {
                    messages.append(" ").append(client.username()).append(": ").append(label.getText());
                }
                Node tab = lookup(client.stage(), "#accountSignupTab");
                if (tab instanceof ToggleButton button) {
                    messages.append(" [").append(client.username()).append(" mode=")
                            .append(button.isSelected() ? "signup" : "login")
                            .append(", windowFocused=").append(client.stage().isFocused()).append("]");
                }
            }
            return messages.toString();
        });
        throw new IllegalStateException("Timed out waiting for " + description + "." + detail);
    }

    private void checkRunning() {
        if (cancelled) throw new IllegalStateException("Demo stopped with Esc.");
        if (!asynchronousErrors.isEmpty()) throw new IllegalStateException("The application reported an error.", asynchronousErrors.get(0));
    }

    private static boolean settled(Client client) throws Exception {
        BoardView board = (BoardView) read(client.app(), "boardView");
        return board != null && !board.isAnimating() && !(boolean) read(client.app(), "moveRequestPending");
    }

    private static boolean sameBoards(Client first, Client second) throws Exception {
        Object board = read(first.app(), "serverBoardSnapshot");
        return board != null && board.equals(read(second.app(), "serverBoardSnapshot"));
    }

    private static String encode(Game game) {
        StringBuilder board = new StringBuilder(64);
        for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++) {
            Piece piece = game.getBoard().getPiece(row, col);
            board.append(piece == null ? '.' : piece.getSymbol());
        }
        return board.toString();
    }

    private static Node lookup(Stage stage, String selector) {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        return stage.getScene().lookup(selector);
    }

    // Read private state only to find controls and wait. Robot performs all player actions.
    private static Object read(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private void cleanup() throws Exception {
        try {
            fx(() -> {
                if (robot != null) {
                    robot.mouseRelease(MouseButton.PRIMARY);
                    robot.keyRelease(KeyCode.CONTROL);
                }
                for (Client client : clients) {
                    client.app().stop();
                    client.stage().close();
                }
                if (caption != null) caption.close();
                if (background != null) background.close();
                return null;
            });
        } finally {
            if (server != null) server.close();
            Platform.exit();
        }
    }
}
