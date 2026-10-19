import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/** Displays server profile data. Fetching and authentication stay in DamkaFX. */
public final class ProfileWindow extends Stage {
    private final Label username = label("profileUsername");
    private final Label onlinePlayed = label("onlinePlayed");
    private final Label onlineWins = label("onlineWins");
    private final Label onlineLosses = label("onlineLosses");
    private final Label botPlayed = label("botPlayed");
    private final Label botWins = label("botWins");
    private final Label botLosses = label("botLosses");
    private final Label status = label("profileStatus");
    private final Button refresh = new Button("Refresh");
    private final TableView<ProfileData.HistoryEntry> history = new TableView<>();

    public ProfileWindow(Stage owner, Runnable onRefresh) {
        initOwner(owner);
        setTitle("Your profile");
        username.getStyleClass().add("profile-username");
        refresh.setId("profileRefresh");
        refresh.getStyleClass().add("secondary-button");
        refresh.setOnAction(event -> onRefresh.run());
        HBox heading = new HBox(20, username, refresh);
        heading.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(username, Priority.ALWAYS);
        username.setMaxWidth(Double.MAX_VALUE);
        HBox statistics = new HBox(16,
                statisticsCard("Online matches", onlinePlayed, onlineWins, onlineLosses),
                statisticsCard("BOT matches", botPlayed, botWins, botLosses));
        for (javafx.scene.Node card : statistics.getChildren()) {
            HBox.setHgrow(card, Priority.ALWAYS);
        }
        Label note = new Label("BOT results are reported by the client and counted separately from online matches.");
        note.setWrapText(true);
        note.getStyleClass().add("section-description");
        Label historyTitle = new Label("Match history");
        historyTitle.getStyleClass().add("section-title");
        history.setId("profileHistory");
        history.setPlaceholder(new Label("No completed matches yet."));
        history.getColumns().add(column("Mode", entry -> "BOT".equals(entry.mode()) ? "BOT" : "Online", 75));
        history.getColumns().add(column("Opponent", ProfileData.HistoryEntry::opponent, 110));
        history.getColumns().add(column("Result", entry -> "WIN".equals(entry.outcome()) ? "Win" : "Loss", 70));
        history.getColumns().add(column("Reason", entry -> reasonName(entry.reason()), 160));
        history.getColumns().add(column("Difficulty", entry -> "BOT".equals(entry.mode())
                ? difficultyName(entry.difficulty()) : "—", 80));
        history.getColumns().add(column("Finished", entry -> formatTime(entry.finishedAt()), 170));
        VBox.setVgrow(history, Priority.ALWAYS);
        status.setWrapText(true);
        status.getStyleClass().add("status-line");
        VBox root = new VBox(18, heading, statistics, note, historyTitle, history, status);
        root.setPadding(new Insets(26));
        root.getStyleClass().add("app-root");
        Scene scene = new Scene(root, 800, 590);
        scene.getStylesheets().add(getClass().getResource("/damka.css").toExternalForm());
        setScene(scene);
        setMinWidth(560);
        setMinHeight(440);
    }

    private static Label label(String id) {
        Label label = new Label("—");
        label.setId(id);
        return label;
    }

    private VBox statisticsCard(String title, Label played, Label wins, Label losses) {
        Label heading = new Label(title);
        heading.getStyleClass().add("section-title");
        HBox totals = new HBox(24, statistic("Played", played), statistic("Wins", wins), statistic("Losses", losses));
        VBox card = new VBox(14, heading, totals);
        card.setMaxWidth(Double.MAX_VALUE);
        card.getStyleClass().add("card");
        return card;
    }

    private VBox statistic(String title, Label value) {
        value.getStyleClass().add("statistic-value");
        Label caption = new Label(title);
        caption.getStyleClass().add("section-description");
        return new VBox(4, value, caption);
    }

    private TableColumn<ProfileData.HistoryEntry, String> column(String title,
            Function<ProfileData.HistoryEntry, String> value, double width) {
        TableColumn<ProfileData.HistoryEntry, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private static String difficultyName(int difficulty) {
        return switch (difficulty) {
            case 1 -> "Easy";
            case 2 -> "Medium";
            case 3 -> "Hard";
            default -> "—";
        };
    }

    private static String formatTime(String value) {
        try {
            return DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
                    .withZone(ZoneId.systemDefault()).format(Instant.parse(value));
        } catch (java.time.DateTimeException exception) {
            return value;
        }
    }

    private static String reasonName(String reason) {
        return switch (reason) {
            case "RULES" -> "Game rules";
            case "RESIGNATION" -> "Resignation";
            case "OPPONENT_LEFT" -> "Player left";
            case "OPPONENT_DISCONNECTED" -> "Disconnected";
            case "BOT_FINISHED" -> "Game completed";
            default -> reason.replace('_', ' ');
        };
    }

    public void showLoading(String name) {
        username.setText(name);
        status.setText("Loading your profile...");
        refresh.setDisable(true);
    }

    public void showData(ProfileData data) {
        username.setText(data.username());
        onlinePlayed.setText(Integer.toString(data.online().played()));
        onlineWins.setText(Integer.toString(data.online().wins()));
        onlineLosses.setText(Integer.toString(data.online().losses()));
        botPlayed.setText(Integer.toString(data.bot().played()));
        botWins.setText(Integer.toString(data.bot().wins()));
        botLosses.setText(Integer.toString(data.bot().losses()));
        history.getItems().setAll(data.history());
        status.setText("Profile is up to date.");
        refresh.setDisable(false);
    }

    public void showError(String message) {
        status.setText(message);
        refresh.setDisable(false);
    }

}
