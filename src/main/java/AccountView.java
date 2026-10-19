import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/** Account form only. The application owns networking and scene navigation. */
public final class AccountView extends VBox {
    public record Credentials(String username, String password, boolean signup) {}

    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final PasswordField confirmation = new PasswordField();
    private final Label error = new Label();
    private final Button submit = new Button("Log in");
    private final ToggleButton loginTab = new ToggleButton("Log in");
    private final ToggleButton signupTab = new ToggleButton("Create account");
    private final VBox confirmationControls;

    public AccountView(Consumer<Credentials> onSubmit) {
        setSpacing(12);
        setMaxWidth(360);
        setMaxHeight(USE_PREF_SIZE);
        getStyleClass().addAll("card", "account-card");
        Label title = new Label("damka");
        title.getStyleClass().add("app-title");
        Label welcome = new Label("Your next match starts here.");
        welcome.getStyleClass().add("section-description");
        ToggleGroup tabs = new ToggleGroup();
        loginTab.setToggleGroup(tabs);
        signupTab.setToggleGroup(tabs);
        loginTab.setId("accountLoginTab");
        signupTab.setId("accountSignupTab");
        loginTab.setSelected(true);
        HBox tabRow = new HBox(8, loginTab, signupTab);
        tabRow.getStyleClass().add("account-tabs");
        username.setId("accountUsername");
        username.setPromptText("Username");
        password.setId("accountPassword");
        password.setPromptText("At least 8 characters");
        confirmation.setId("accountConfirmPassword");
        confirmation.setPromptText("Repeat your password");
        confirmationControls = new VBox(6, new Label("Confirm password"), confirmation);
        confirmationControls.setVisible(false);
        confirmationControls.setManaged(false);
        error.setId("accountError");
        error.setWrapText(true);
        error.getStyleClass().add("error-message");
        submit.setId("accountSubmit");
        submit.setDefaultButton(true);
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.getStyleClass().add("primary-button");
        loginTab.setOnAction(event -> selectMode(false));
        signupTab.setOnAction(event -> selectMode(true));
        submit.setOnAction(event -> {
            String name = username.getText().trim();
            if (!validUsername(name)) {
                showError("Use 1–24 letters or numbers, _ or - for your username.");
            } else if (password.getText().length() < 8 || password.getText().length() > 128) {
                showError("Your password must contain 8–128 characters.");
            } else if (signupTab.isSelected() && !password.getText().equals(confirmation.getText())) {
                showError("The passwords do not match.");
            } else {
                Credentials credentials = new Credentials(name, password.getText(), signupTab.isSelected());
                clearPasswords();
                onSubmit.accept(credentials);
            }
        });
        getChildren().addAll(title, welcome, tabRow,
                new VBox(6, new Label("Username"), username),
                new VBox(6, new Label("Password"), password), confirmationControls, error, submit);
    }

    private void selectMode(boolean signup) {
        signupTab.setSelected(signup);
        loginTab.setSelected(!signup);
        confirmationControls.setVisible(signup);
        confirmationControls.setManaged(signup);
        submit.setText(signup ? "Create account" : "Log in");
        error.setText("");
    }

    private static boolean validUsername(String value) {
        return value.matches("[\\p{L}\\p{N}_-]{1,24}");
    }

    public void setBusy(boolean busy) {
        username.setDisable(busy);
        password.setDisable(busy);
        confirmation.setDisable(busy);
        loginTab.setDisable(busy);
        signupTab.setDisable(busy);
        submit.setDisable(busy);
        error.setText(busy ? "Connecting..." : "");
    }

    public void showError(String message) {
        error.setText(message);
    }

    public void clearPasswords() {
        password.clear();
        confirmation.clear();
    }
}
