import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;

/** Optional console client: create/join, then type MOVE r c r c or RESIGN. */
public class ServerConnectionCheck {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        String request = args.length > 2 ? "JOIN_ROOM " + args[2] : "CREATE_ROOM";
        CountDownLatch done = new CountDownLatch(1);
        try (NetworkClient client = new NetworkClient()) {
            client.connect(host, port, request, message -> {
                System.out.println(message);
                if (message.startsWith("GAME_OVER ")) { client.close(); done.countDown(); }
            }, reason -> { System.out.println(reason); done.countDown(); });
            Thread keyboard = new Thread(() -> {
                try {
                    BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    while ((line = input.readLine()) != null) { client.send(line); }
                    client.close();
                    done.countDown();
                } catch (Exception exception) { done.countDown(); }
            }, "console-keyboard");
            keyboard.setDaemon(true);
            keyboard.start();
            done.await();
        }
    }
}
