import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Network threads never touch JavaFX. The UI supplies callbacks using Platform.runLater. */
public class NetworkClient implements AutoCloseable {
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "damka-writer"); thread.setDaemon(true); return thread;
    });
    private volatile Socket socket;
    private volatile boolean closed;
    private PrintWriter output;

    public void connect(String host, int port, String request, Consumer<String> onMessage,
                        Consumer<String> onDisconnect) {
        Thread reader = new Thread(() -> {
            try {
                Socket connection = new Socket();
                socket = connection;
                if (closed) { connection.close(); return; }
                connection.connect(new InetSocketAddress(host, port), 5000);
                connection.setSoTimeout(15000);
                output = new PrintWriter(new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                send(request);
                writer.scheduleAtFixedRate(() -> write("PING"), 3, 3, TimeUnit.SECONDS);
                String message;
                while (!closed && (message = input.readLine()) != null) {
                    if (!message.equals("PONG")) { onMessage.accept(message); }
                }
                if (!closed) { onDisconnect.accept("Server closed the connection"); }
            } catch (IOException | RejectedExecutionException exception) {
                if (!closed) { onDisconnect.accept("Connection lost: " + exception.getMessage()); }
            } finally { close(); }
        }, "damka-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public void send(String message) {
        if (!closed) {
            try { writer.execute(() -> write(message)); }
            catch (RejectedExecutionException ignored) { }
        }
    }
    private void write(String message) {
        if (!closed && output != null) {
            output.println(message);
            if (output.checkError()) { Socket connection = socket;
                if (connection != null) { try { connection.close(); } catch (IOException ignored) { } }
            }
        }
    }
    public void close() {
        closed = true;
        writer.shutdownNow();
        Socket connection = socket;
        if (connection != null) { try { connection.close(); } catch (IOException ignored) { } }
    }
}
