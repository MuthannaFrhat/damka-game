import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Public profile snapshot. Password hashes and salts never leave the server. */
public record ProfileData(String username, Stats online, Stats bot, List<HistoryEntry> history) {
    public record Stats(int played, int wins, int losses) {}
    public record HistoryEntry(String id, String mode, String opponent, String outcome,
                               String reason, String finishedAt, int difficulty) {}

    public ProfileData {
        history = List.copyOf(history);
    }

    /** Base64 keeps structured UTF-8 text inside one TCP line/token. */
    public String encode() {
        StringBuilder text = new StringBuilder("PROFILE\t").append(username);
        appendStats(text, "ONLINE", online);
        appendStats(text, "BOT", bot);
        for (HistoryEntry entry : history) {
            text.append("\nGAME\t").append(entry.id()).append('\t').append(entry.mode())
                    .append('\t').append(entry.opponent()).append('\t').append(entry.outcome())
                    .append('\t').append(entry.reason()).append('\t').append(entry.finishedAt())
                    .append('\t').append(entry.difficulty());
        }
        return Base64.getEncoder().encodeToString(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static ProfileData decode(String encoded) {
        String[] lines = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length < 3) throw new IllegalArgumentException("Incomplete profile.");
        String[] header = fields(lines[0], "PROFILE", 2);
        Stats online = readStats(lines[1], "ONLINE");
        Stats bot = readStats(lines[2], "BOT");
        List<HistoryEntry> history = new ArrayList<>();
        for (int index = 3; index < lines.length; index++) {
            String[] row = fields(lines[index], "GAME", 8);
            history.add(new HistoryEntry(row[1], row[2], row[3], row[4], row[5], row[6], Integer.parseInt(row[7])));
        }
        return new ProfileData(header[1], online, bot, history);
    }

    private static void appendStats(StringBuilder text, String mode, Stats stats) {
        text.append('\n').append(mode).append('\t').append(stats.played())
                .append('\t').append(stats.wins()).append('\t').append(stats.losses());
    }

    private static Stats readStats(String line, String mode) {
        String[] row = fields(line, mode, 4);
        return new Stats(Integer.parseInt(row[1]), Integer.parseInt(row[2]), Integer.parseInt(row[3]));
    }

    private static String[] fields(String line, String expected, int count) {
        String[] fields = line.split("\t", -1);
        if (fields.length != count || !expected.equals(fields[0])) {
            throw new IllegalArgumentException("Invalid profile row.");
        }
        return fields;
    }
}
