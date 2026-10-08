import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** One background thread connects, sends the room request, then reads notifications. */
public class RoomConnection implements AutoCloseable {
    private volatile Socket socket;
    private volatile boolean closed;

    public void connect(String host, int port, String request, Consumer<String> onMessage) {
        Thread worker = new Thread(() -> {
            try {
                Socket connection = new Socket();
                socket = connection;
                if (closed) { connection.close(); return; }
                connection.connect(new java.net.InetSocketAddress(host, port));
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter output = new PrintWriter(new OutputStreamWriter(
                        connection.getOutputStream(), StandardCharsets.UTF_8), true);
                output.println(request);
                if (output.checkError()) { throw new IOException("Could not send room request"); }
                String message;
                while (!closed && (message = input.readLine()) != null) {
                    onMessage.accept(message);
                    if (message.startsWith("ERROR ") || message.startsWith("ROOM_CLOSED ")) { return; }
                }
                if (!closed) { onMessage.accept("DISCONNECTED Server closed the connection"); }
            } catch (IOException exception) {
                if (!closed) { onMessage.accept("DISCONNECTED " + exception.getMessage()); }
            } finally { close(); }
        }, "room-reader");
        worker.setDaemon(true);
        worker.start();
    }

    public void close() {
        closed = true;
        Socket connection = socket;
        if (connection != null) {
            try { connection.close(); } catch (IOException ignored) { }
        }
    }
}
