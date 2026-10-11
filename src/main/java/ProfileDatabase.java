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
import java.util.HashSet;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Server-side accounts. Each operation owns its JDBC connection. */
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
