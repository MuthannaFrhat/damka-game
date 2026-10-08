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
    private volatile Socket socketToServer;
    private volatile boolean isClosed;

    public void connect(String serverAddress, int serverPort, String roomRequest, Consumer<String> onServerMessage) {
        Thread readerThread = new Thread(() -> {
            try {
                Socket connectedSocket = new Socket();
                socketToServer = connectedSocket;
                if (isClosed) { connectedSocket.close(); return; }
                connectedSocket.connect(new java.net.InetSocketAddress(serverAddress, serverPort));
                BufferedReader serverReader = new BufferedReader(new InputStreamReader(
                        connectedSocket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter serverWriter = new PrintWriter(new OutputStreamWriter(
                        connectedSocket.getOutputStream(), StandardCharsets.UTF_8), true);
                serverWriter.println(roomRequest);
                if (serverWriter.checkError()) { throw new IOException("Could not send room request"); }
                String serverMessage;
                while (!isClosed && (serverMessage = serverReader.readLine()) != null) {
                    onServerMessage.accept(serverMessage);
                    if (serverMessage.startsWith("ERROR ") || serverMessage.startsWith("ROOM_CLOSED ")) { return; }
                }
                if (!isClosed) { onServerMessage.accept("DISCONNECTED Server closed the connection"); }
            } catch (IOException exception) {
                if (!isClosed) { onServerMessage.accept("DISCONNECTED " + exception.getMessage()); }
            } finally { close(); }
        }, "room-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void close() {
        isClosed = true;
        Socket connectedSocket = socketToServer;
        if (connectedSocket != null) {
            try { connectedSocket.close(); } catch (IOException ignored) { }
        }
    }
}
