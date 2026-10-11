import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProfileDatabaseTest {
    @TempDir Path temporaryDirectory;
    private static final String PASSWORD = "correct password123";
    private static final String FINISHED = "2026-10-11T10:00:00Z";

    @Test
    void accountsPersistWithCaseSensitiveUnicodeNamesAndSaltedPasswordHashes() throws Exception {
        Path path = temporaryDirectory.resolve("server-data/profiles.db");
        ProfileDatabase database = new ProfileDatabase(path);
        assertTrue(database.signUp("Alice", PASSWORD));
        assertTrue(database.signUp("alice", PASSWORD));
        assertTrue(database.signUp("لاعب_א", PASSWORD));
        assertFalse(database.signUp("Alice", "different password"));
        assertTrue(Files.exists(path));

        ProfileDatabase reopened = new ProfileDatabase(path);
        assertTrue(reopened.login("Alice", PASSWORD));
        assertTrue(reopened.login("alice", PASSWORD));
        assertTrue(reopened.login("لاعب_א", PASSWORD));
        assertFalse(reopened.login("Alice", "wrong password"));
        assertFalse(reopened.login("Alice", "different password"));
        assertFalse(reopened.login("ALICE", PASSWORD));

        try (Connection connection = connect(path);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT password_salt,password_hash FROM profiles ORDER BY username")) {
            List<String> hashes = new ArrayList<>();
            while (rows.next()) {
                assertEquals(16, rows.getBytes(1).length);
                assertEquals(32, rows.getBytes(2).length);
                hashes.add(java.util.Base64.getEncoder().encodeToString(rows.getBytes(2)));
            }
            assertEquals(3, hashes.size());
            assertEquals(3, new java.util.HashSet<>(hashes).size(), "The same password must have different salted hashes.");
        }
    }

    @Test
    void rejectsInvalidCredentialsWithoutCreatingAccounts() throws Exception {
        ProfileDatabase database = database();
        for (String name : new String[] {"", "a b", "tab\tname", "a".repeat(25), "quote'"}) {
            assertThrows(SQLException.class, () -> database.signUp(name, PASSWORD));
            assertFalse(database.login(name, PASSWORD));
        }
        assertThrows(SQLException.class, () -> database.signUp("Valid", "short"));
        assertThrows(SQLException.class, () -> database.signUp("Valid", "a".repeat(129)));
        assertThrows(SQLException.class, () -> database.signUp("Valid", null));
        assertFalse(database.login(null, PASSWORD));
        assertFalse(database.login("Valid", null));
        assertThrows(SQLException.class, () -> database.getProfile("Valid"));
    }

    @Test
    void migrationAllowsClaimingLegacyNamesButPreservesTheirCreationDate() throws Exception {
        Path path = temporaryDirectory.resolve("legacy.db");
        try (Connection connection = connect(path); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE profiles(username TEXT PRIMARY KEY, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO profiles VALUES('Legacy','2001-01-01 00:00:00')");
        }
        ProfileDatabase database = new ProfileDatabase(path);
        assertFalse(database.login("Legacy", PASSWORD));
        assertTrue(database.signUp("Legacy", PASSWORD));
        assertFalse(database.signUp("Legacy", "second password"));
        assertTrue(new ProfileDatabase(path).login("Legacy", PASSWORD));
        try (Connection connection = connect(path); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT created_at FROM profiles WHERE username='Legacy'")) {
            assertTrue(rows.next());
            assertEquals("2001-01-01 00:00:00", rows.getString(1));
        }
    }

    @Test
    void resultsAreExactlyOnceAndStatisticsBelongToEachParticipant() throws Exception {
        ProfileDatabase database = database();
        database.signUp("First", PASSWORD);
        database.signUp("Second", PASSWORD);
        database.signUp("Other", PASSWORD);
        String match = id();
        database.recordOnlineGame(match, "First", "Second", 'X', "RULES", FINISHED);
        database.recordOnlineGame(match, "First", "Second", 'X', "RULES", FINISHED);
        database.recordOnlineGame(id(), "First", "Second", 'O', "RESIGNATION", FINISHED);
        String bot = id();
        database.recordBotGame(bot, "First", "WIN", 2, FINISHED);
        database.recordBotGame(bot, "First", "WIN", 2, FINISHED);
        database.recordBotGame(id(), "First", "LOSS", 3, FINISHED);

        ProfileData first = database.getProfile("First");
        assertEquals(new ProfileData.Stats(2, 1, 1), first.online());
        assertEquals(new ProfileData.Stats(2, 1, 1), first.bot());
        assertEquals(4, first.history().size());
        assertEquals(new ProfileData.Stats(2, 1, 1), database.getProfile("Second").online());
        assertEquals(new ProfileData.Stats(0, 0, 0), database.getProfile("Other").online());
        assertThrows(SQLException.class, () -> database.recordOnlineGame(match, "First", "Second", 'O', "RULES", FINISHED));
        assertThrows(SQLException.class, () -> database.recordOnlineGame(match, "Second", "First", 'X', "RULES", FINISHED));
        assertThrows(SQLException.class, () -> database.recordBotGame(bot, "Other", "WIN", 2, FINISHED));
        assertThrows(SQLException.class, () -> database.recordBotGame(bot, "First", "WIN", 3, FINISHED));
        assertThrows(SQLException.class, () -> database.recordBotGame(match, "First", "WIN", 2, FINISHED));
        assertEquals(first, database.getProfile("First"));
    }

    @Test
    void validatesResultIdsParticipantsTimestampsAndDifficulty() throws Exception {
        ProfileDatabase database = database();
        database.signUp("First", PASSWORD);
        database.signUp("Second", PASSWORD);
        assertThrows(SQLException.class, () -> database.recordBotGame("not-uuid", "First", "WIN", 1, FINISHED));
        assertThrows(SQLException.class, () -> database.recordBotGame("1-1-1-1-1", "First", "WIN", 1, FINISHED));
        String mixedCase = "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF";
        assertThrows(SQLException.class, () -> database.recordBotGame(mixedCase, "First", "WIN", 1, FINISHED));
        assertThrows(SQLException.class, () -> database.recordBotGame(id(), "First", "DRAW", 1, FINISHED));
        assertThrows(SQLException.class, () -> database.recordBotGame(id(), "First", "WIN", 4, FINISHED));
        assertThrows(SQLException.class, () -> database.recordBotGame(id(), "First", "WIN", 1, "bad time"));
        assertThrows(SQLException.class, () -> database.recordBotGame(id(), "Unknown", "WIN", 1, FINISHED));
        assertThrows(SQLException.class, () -> database.recordOnlineGame(id(), "First", "First", 'X', "RULES", FINISHED));
        assertThrows(SQLException.class, () -> database.recordOnlineGame(id(), "First", "Second", 'Y', "RULES", FINISHED));
        assertThrows(SQLException.class, () -> database.recordOnlineGame(id(), "First", "Second", 'X', "FAKE", FINISHED));
        assertThrows(SQLException.class, () -> database.recordOnlineGame(id(), "First", "Second", 'X', null, FINISHED));
        assertEquals(0, database.getProfile("First").bot().played());
    }

    @Test
    void historyIncludesLatest100ButStatsIncludeAllGamesAndUnicodeRoundTrips() throws Exception {
        ProfileDatabase database = database();
        database.signUp("لاعب_א", PASSWORD);
        for (int index = 0; index < 105; index++) {
            database.recordBotGame(id(), "لاعب_א", index % 2 == 0 ? "WIN" : "LOSS", 1,
                    Instant.parse(FINISHED).plusSeconds(index).toString());
        }
        // Fractional timestamps sort after their whole-second counterpart.
        String latest = id();
        database.recordBotGame(latest, "لاعب_א", "WIN", 3,
                Instant.parse(FINISHED).plusSeconds(104).plusNanos(1).toString());
        ProfileData profile = database.getProfile("لاعب_א");
        assertEquals(new ProfileData.Stats(106, 54, 52), profile.bot());
        assertEquals(100, profile.history().size());
        assertEquals(latest, profile.history().get(0).id());
        assertEquals(profile, ProfileData.decode(profile.encode()));
        assertFalse(profile.encode().contains("\n"));
        assertFalse(profile.encode().contains(" "));
        assertThrows(IllegalArgumentException.class, () -> ProfileData.decode("not base64!"));
        assertThrows(UnsupportedOperationException.class, () -> profile.history().clear());
    }

    @Test
    void concurrentResultRetriesProduceOneRowAndConsistentSnapshots() throws Exception {
        ProfileDatabase database = database();
        database.signUp("Concurrent", PASSWORD);
        String result = id();
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                String game = index < 12 ? result : id();
                writes.add(workers.submit(() -> {
                    database.recordBotGame(game, "Concurrent", "WIN", 1, FINISHED);
                    return null;
                }));
            }
            for (int index = 0; index < 8; index++) {
                ProfileData snapshot = database.getProfile("Concurrent");
                assertEquals(snapshot.bot().played(), snapshot.history().size());
                assertEquals(snapshot.bot().wins(), snapshot.history().size());
            }
            for (Future<?> write : writes) write.get(15, TimeUnit.SECONDS);
            assertEquals(new ProfileData.Stats(13, 13, 0), database.getProfile("Concurrent").bot());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentSignupCannotReplaceTheWinningPassword() throws Exception {
        ProfileDatabase database = database();
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                String password = "password123-" + index;
                attempts.add(workers.submit(() -> database.signUp("Single", password)));
            }
            int successful = 0;
            for (int index = 0; index < attempts.size(); index++) {
                boolean signedUp = attempts.get(index).get(20, TimeUnit.SECONDS);
                if (signedUp) successful++;
                assertEquals(signedUp, database.login("Single", "password123-" + index));
            }
            assertEquals(1, successful);
        } finally {
            workers.shutdownNow();
        }
    }

    private ProfileDatabase database() throws Exception {
        return new ProfileDatabase(temporaryDirectory.resolve("profiles.db"));
    }

    private Connection connect(Path path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }
}
