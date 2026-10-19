import javafx.geometry.Rectangle2D;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Records the demo area with FFmpeg; the Robot runner controls the application separately. */
public final class DemoRecorder implements AutoCloseable {
    private final Process process;
    private final Path output;
    private final Path log;
    private boolean closed;

    private DemoRecorder(Process process, Path output, Path log) {
        this.process = process;
        this.output = output;
        this.log = log;
    }

    /** The recording area uses desktop pixels, with an even width and height for H.264. */
    public static DemoRecorder start(Path output, Rectangle2D area) throws IOException {
        int x = (int) Math.floor(area.getMinX());
        int y = (int) Math.floor(area.getMinY());
        int width = (int) Math.floor(area.getWidth()) & ~1;
        int height = (int) Math.floor(area.getHeight()) & ~1;
        if (width < 2 || height < 2) {
            throw new IOException("The recording area must be at least 2 × 2 pixels.");
        }
        output = output.toAbsolutePath();
        Files.createDirectories(output.getParent());
        Path log = output.resolveSibling(output.getFileName() + ".ffmpeg.log");
        List<String> command = new ArrayList<>(List.of(
                System.getProperty("damka.demo.ffmpeg", "ffmpeg"),
                "-hide_banner", "-loglevel", "warning", "-y",
                "-thread_queue_size", "512"));
        String platform = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (platform.startsWith("windows")) {
            command.addAll(List.of("-f", "gdigrab", "-framerate", "25", "-draw_mouse", "1",
                    "-offset_x", Integer.toString(x), "-offset_y", Integer.toString(y),
                    "-video_size", width + "x" + height, "-i", "desktop"));
        } else if (platform.contains("linux")) {
            String display = System.getenv("DISPLAY");
            if (display == null || display.isBlank()) {
                throw new IOException("Screen recording needs an X11 desktop (DISPLAY is not set).");
            }
            command.addAll(List.of("-f", "x11grab", "-framerate", "25", "-draw_mouse", "1",
                    "-video_size", width + "x" + height, "-i", display + "+" + x + "," + y));
        } else {
            throw new IOException("The demo recorder supports Windows and Linux X11 desktops.");
        }
        command.addAll(List.of("-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-threads", "2", "-pix_fmt", "yuv420p", "-r", "25",
                "-movflags", "+faststart", output.toString()));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
        } catch (IOException failure) {
            throw new IOException("Cannot start FFmpeg. Install FFmpeg and add it to PATH, or set "
                    + "-Ddamka.demo.ffmpeg to the full path of ffmpeg.exe.", failure);
        }
        try {
            if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                throw new IOException("FFmpeg stopped before recording (exit " + process.exitValue()
                        + "). See " + log);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting screen recording.", interrupted);
        }
        return new DemoRecorder(process, output, log);
    }

    /** Ask FFmpeg to finalize the MP4, then check that recording actually succeeded. */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException stopFailure = null;
        if (process.isAlive()) {
            try {
                process.getOutputStream().write("q\n".getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush();
                process.getOutputStream().close();
            } catch (IOException failure) {
                stopFailure = failure;
            }
        }
        try {
            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                throw new IOException("FFmpeg did not finish saving the MP4. See " + log);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while finishing the recording.", interrupted);
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("Recording failed (FFmpeg exit " + process.exitValue()
                    + "). See " + log, stopFailure);
        }
    }
}
