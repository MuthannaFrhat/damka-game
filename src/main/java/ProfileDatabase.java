import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Server-side accounts and results. Each operation owns its JDBC connection. */
public final class ProfileDatabase {
    private static final int PASSWORD_ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String databaseUrl;

    public ProfileDatabase(Path databaseFile) throws IOException, SQLException {
        Path absoluteFile = databaseFile.toAbsolutePath().normalize();
        Files.createDirectories(absoluteFile.getParent());
        databaseUrl = "jdbc:sqlite:" + absoluteFile;
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS profiles ("
                    + "username TEXT PRIMARY KEY NOT NULL, "
                    + "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "password_salt BLOB, password_hash BLOB)");
            // Preserve names and creation dates from the earlier username-only step.
            Set<String> columns = new HashSet<>();
            try (ResultSet rows = statement.executeQuery("PRAGMA table_info(profiles)")) {
                while (rows.next()) columns.add(rows.getString("name"));
            }
            if (!columns.contains("password_salt")) {
                statement.executeUpdate("ALTER TABLE profiles ADD COLUMN password_salt BLOB");
            }
            if (!columns.contains("password_hash")) {
                statement.executeUpdate("ALTER TABLE profiles ADD COLUMN password_hash BLOB");
            }
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS game_results ("
                    + "id TEXT PRIMARY KEY NOT NULL, mode TEXT NOT NULL CHECK(mode IN ('ONLINE','BOT')), "
                    + "first_username TEXT NOT NULL REFERENCES profiles(username), "
                    + "second_username TEXT REFERENCES profiles(username), "
                    + "winner TEXT NOT NULL CHECK(winner IN ('X','O')), reason TEXT NOT NULL, "
                    + "finished_at TEXT NOT NULL, difficulty INTEGER NOT NULL, "
                    + "CHECK((mode='ONLINE' AND second_username IS NOT NULL AND difficulty=0) OR "
                    + "(mode='BOT' AND second_username IS NULL AND difficulty BETWEEN 1 AND 3)))");
        }
    }

    public boolean signUp(String username, String password) throws SQLException {
        require(validUsername(username), "Use 1–24 letters, numbers, underscores, or hyphens for the username.");
        require(validPassword(password), "Passwords must contain 8–128 characters.");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = hashPassword(password, salt);
        String sql = "INSERT INTO profiles(username,password_salt,password_hash) VALUES(?,?,?) "
                + "ON CONFLICT(username) DO UPDATE SET password_salt=excluded.password_salt, "
                + "password_hash=excluded.password_hash "
                + "WHERE profiles.password_salt IS NULL AND profiles.password_hash IS NULL";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            // Parameters keep user input separate from SQL; never store the password itself.
            statement.setString(1, username);
            statement.setBytes(2, salt);
            statement.setBytes(3, hash);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean login(String username, String password) throws SQLException {
        if (!validUsername(username) || !validPassword(password)) return false;
        byte[] salt = null;
        byte[] expected = null;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT password_salt,password_hash FROM profiles WHERE username=?")) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    salt = rows.getBytes(1);
                    expected = rows.getBytes(2);
                }
            }
        }
        // Hash unknown names too, so failed logins do the same expensive work.
        byte[] actual = hashPassword(password, salt == null ? new byte[16] : salt);
        return salt != null && expected != null && MessageDigest.isEqual(expected, actual);
    }

    public void recordOnlineGame(String id, String firstUsername, String secondUsername,
                                 char winner, String reason, String finishedAt) throws SQLException {
        require(validUsername(firstUsername) && validUsername(secondUsername)
                && !firstUsername.equals(secondUsername), "A match needs two different accounts.");
        require(winner == 'X' || winner == 'O', "Invalid winner.");
        require(reason != null && Set.of("RULES", "RESIGNATION", "OPPONENT_LEFT", "OPPONENT_DISCONNECTED").contains(reason),
                "Invalid online result reason.");
        recordResult(id, "ONLINE", firstUsername, secondUsername, winner, reason, finishedAt, 0);
    }

    public void recordBotGame(String id, String username, String outcome,
                              int difficulty, String finishedAt) throws SQLException {
        require(validUsername(username), "Invalid username.");
        require("WIN".equals(outcome) || "LOSS".equals(outcome), "Invalid BOT outcome.");
        require(difficulty >= 1 && difficulty <= 3, "Invalid BOT difficulty.");
        recordResult(id, "BOT", username, null, "WIN".equals(outcome) ? 'X' : 'O',
                "BOT_FINISHED", finishedAt, difficulty);
    }

    private void recordResult(String id, String mode, String firstUsername, String secondUsername,
                              char winner, String reason, String finishedAt, int difficulty) throws SQLException {
        try {
            require(UUID.fromString(id).toString().equals(id), "Invalid result ID.");
            Instant.parse(finishedAt);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException | NullPointerException exception) {
            throw new SQLException("Invalid result ID or finish time.", exception);
        }
        try (Connection connection = connect()) {
            requireAccount(connection, firstUsername);
            if (secondUsername != null) requireAccount(connection, secondUsername);
            // One immutable row updates history and derived statistics together.
            // A unique ID makes simultaneous retries safe without separate counters.
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO game_results(id,mode,first_username,second_username,winner,reason,finished_at,difficulty) "
                            + "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING")) {
                insert.setString(1, id);
                insert.setString(2, mode);
                insert.setString(3, firstUsername);
                insert.setString(4, secondUsername);
                insert.setString(5, String.valueOf(winner));
                insert.setString(6, reason);
                insert.setString(7, finishedAt);
                insert.setInt(8, difficulty);
                if (insert.executeUpdate() == 1) return;
            }
            try (PreparedStatement existing = connection.prepareStatement("SELECT * FROM game_results WHERE id=?")) {
                existing.setString(1, id);
                try (ResultSet rows = existing.executeQuery()) {
                    require(rows.next() && mode.equals(rows.getString("mode"))
                            && firstUsername.equals(rows.getString("first_username"))
                            && java.util.Objects.equals(secondUsername, rows.getString("second_username"))
                            && String.valueOf(winner).equals(rows.getString("winner"))
                            && reason.equals(rows.getString("reason"))
                            && finishedAt.equals(rows.getString("finished_at"))
                            && difficulty == rows.getInt("difficulty"),
                            "This result ID already belongs to a different result.");
                }
            }
        }
    }

    public ProfileData getProfile(String username) throws SQLException {
        require(validUsername(username), "Invalid username.");
        int onlineWins = 0, onlineLosses = 0, botWins = 0, botLosses = 0;
        List<ProfileData.HistoryEntry> history = new ArrayList<>();
        try (Connection connection = connect()) {
            requireAccount(connection, username);
            // Stats and history come from the SAME SELECT/read snapshot, even if
            // other client threads finish games while this request is being read.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM game_results WHERE first_username=? OR second_username=?")) {
                statement.setString(1, username);
                statement.setString(2, username);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        boolean firstPlayer = username.equals(rows.getString("first_username"));
                        boolean won = (firstPlayer ? "X" : "O").equals(rows.getString("winner"));
                        boolean bot = "BOT".equals(rows.getString("mode"));
                        if (bot) {
                            if (won) botWins++; else botLosses++;
                        } else {
                            if (won) onlineWins++; else onlineLosses++;
                        }
                        String opponent = bot ? "BOT" : rows.getString(firstPlayer ? "second_username" : "first_username");
                        history.add(new ProfileData.HistoryEntry(rows.getString("id"), rows.getString("mode"),
                                opponent, won ? "WIN" : "LOSS", rows.getString("reason"),
                                rows.getString("finished_at"), rows.getInt("difficulty")));
                    }
                }
            }
        }
        // Instant comparison handles varying timestamp precision correctly.
        history.sort(Comparator.comparing((ProfileData.HistoryEntry entry) -> Instant.parse(entry.finishedAt()))
                .reversed().thenComparing(ProfileData.HistoryEntry::id));
        return new ProfileData(username,
                new ProfileData.Stats(onlineWins + onlineLosses, onlineWins, onlineLosses),
                new ProfileData.Stats(botWins + botLosses, botWins, botLosses),
                history.subList(0, Math.min(100, history.size())));
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(databaseUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    private void requireAccount(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM profiles WHERE username=? AND password_hash IS NOT NULL AND password_salt IS NOT NULL")) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "Account not found.");
            }
        }
    }

    private static boolean validUsername(String username) {
        return username != null && username.matches("[\\p{L}\\p{N}_-]{1,24}");
    }

    private static boolean validPassword(String password) {
        return password != null && password.length() >= 8 && password.length() <= 128;
    }

    private static byte[] hashPassword(String password, byte[] salt) {
        PBEKeySpec specification = new PBEKeySpec(password.toCharArray(), salt, PASSWORD_ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing is unavailable.", exception);
        } finally {
            specification.clearPassword();
        }
    }

    private static void require(boolean condition, String message) throws SQLException {
        if (!condition) throw new SQLException(message);
    }
}
