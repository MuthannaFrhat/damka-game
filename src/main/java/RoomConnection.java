import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transfers text messages. The game protocol and JavaFX controls belong elsewhere. */
public final class RoomConnection implements AutoCloseable {
    public interface Listener {
        void onConnected(RoomConnection connection);
        void onMessage(RoomConnection connection, String message);
        void onDisconnected(RoomConnection connection, String detail);
    }

    private final String host;
    private final int port;
    private final Listener listener;
    // Allocate before connecting so close() also cancels a connection in progress.
    private final Socket socket = new Socket();
    private boolean started;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile PrintWriter output;
    private volatile String sendFailure;
    // One writer thread preserves command order and keeps writes away from JavaFX.
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "damka-server-writer");
        thread.setDaemon(true);
        return thread;
    });

    public RoomConnection(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    /** Call once from the owner thread: connect, send the request, then listen. */
    public void connect(String roomRequest) {
        if (started) {
            throw new IllegalStateException("This connection has already been started.");
        }
        started = true;
        if (closed.get()) {
            return;
        }
        Thread reader = new Thread(() -> readMessages(roomRequest), "damka-server-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readMessages(String roomRequest) {
        String detail = "The server closed the connection.";
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            output = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);
            output.println(roomRequest);
            if (output.checkError()) {
                throw new IOException("Could not send the room request.");
            }
            if (!closed.get()) {
                listener.onConnected(this);
            }
            String message;
            while (!closed.get() && (message = input.readLine()) != null) {
                if (!closed.get()) {
                    listener.onMessage(this, message);
                }
            }
        } catch (IOException exception) {
            detail = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
        } finally {
            // Only the reader or close() wins this change, so we notify once.
            boolean notify = closed.compareAndSet(false, true);
            closeTransport();
            if (notify) {
                listener.onDisconnected(this, sendFailure == null ? detail : sendFailure);
            }
        }
    }

    public boolean isConnected() {
        return output != null && !closed.get() && !socket.isClosed();
    }

    /** Queue a command; the reader still receives its response independently. */
    public void send(String command) {
        if (closed.get()) {
            return;
        }
        try {
            writer.execute(() -> {
                if (closed.get()) {
                    return;
                }
                PrintWriter currentOutput = output;
                if (currentOutput == null) {
                    failSend("No active server connection.");
                    return;
                }
                currentOutput.println(command);
                if (currentOutput.checkError()) {
                    failSend("Could not send the request.");
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Closing concurrently may stop the writer before it accepts this task.
        }
    }

    private void failSend(String detail) {
        sendFailure = detail;
        // Closing unblocks readLine(); the reader reports this error exactly once.
        closeTransport();
    }

    @Override
    public void close() {
        closed.set(true);
        closeTransport();
    }

    private void closeTransport() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Cleanup must still stop the writer even if socket closure fails.
        }
        writer.shutdownNow();
    }
}
