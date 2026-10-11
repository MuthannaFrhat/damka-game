# Damka

A Java 17 / JavaFX checkers game with two ways to play:

- **Online:** two JavaFX clients connect to a Java TCP server. The server validates moves and owns each room's game.
- **BOT:** play locally, without a server.

Both modes use the same board and movement animation. Accounts, match history, and per-player statistics are stored in SQLite on the server. Logged-in BOT results are shown separately from server-validated online matches; offline practice is not recorded. Cloud deployment is outside the current scope.

## Run in VS Code

Use the Java extensions and a JDK compatible with the project (Java 17 or 21). Open the project folder containing `build.gradle` and let VS Code finish importing the Gradle project.

For a game on one computer, no terminal commands are needed:

1. Open `src/main/java/DamkaServer.java` and click **Run** above `main` once. Leave it running.
2. Open `src/main/java/Main.java` and click **Run** above `main` to open the first player window.
3. Click **Run** above `Main.main` again to open the second player window.

The Java extension uses its configured runtime and the project's JavaFX dependencies. If VS Code reports build errors, fix them first rather than continuing with an old compiled version. If the JavaFX dependencies have not been imported, refresh the Gradle project or use the Gradle commands below.

Create an account or log in in each player window, using different usernames. Create a room in the first window. Click **Copy** beside the room code, paste it in the second window, and join. Select one of your pieces, then an empty destination. Captures are required when available; after a capture chain, continue with the same piece. Errors appear in a small floating card; close it with **×** or complete a successful action. Showing an error does not resize the board. The server decides the turn and result. Use **Resign** or **Leave room** to end a match, then create or join another.

For offline practice, start only `Main.java` and choose **Play BOT offline** on the welcome screen. No server is needed. These matches do not change history or statistics.

## الحساب والملف الشخصي

شاشة البداية تقدم إنشاء حساب وتسجيل الدخول، أو اللعب ضد BOT دون اتصال. اسم المستخدم من 1 إلى 24 حرفًا أو رقمًا، ويمكن استخدام `_` و`-` والحروف العربية. الأسماء تميّز حالة الحروف؛ `Alice` و`alice` حسابان مختلفان. كلمة المرور من 8 إلى 128 حرفًا.

السيرفر يحفظ ملحًا عشوائيًا ونتيجة PBKDF2 لكلمة المرور؛ لا يحفظ كلمة المرور الأصلية. بعد نجاح الدخول يصبح الحساب مرتبطًا بالـSocket، وتستخدم الغرف والملف الشخصي هذه الهوية. لا يستطيع الحساب نفسه شغل مقعدي الغرفة.

ملف SQLite هو `data/damka.db` في مجلد تشغيل السيرفر، وليس على كل عميل. يبقى بعد إغلاق اللاعبين أو إعادة تشغيل السيرفر. يمكن تغيير مساره باستخدام خيار تشغيل Java الخاص بالسيرفر `-Ddamka.database.path=C:/dev/damka-data/profiles.db`.

نافذة **Profile** تعرض إحصائيات PvP وBOT منفصلة وآخر 100 مباراة، مع زر تحديث. الإحصائيات تشمل كل النتائج المحفوظة، وليس آخر 100 فقط. تُحفظ المباراة الشبكية مرة واحدة عند الفوز بالقواعد أو الاستسلام أو مغادرة الخصم أو انقطاعه. مغادرة غرفة تنتظر لاعبًا ثانيًا لا تُحتسب مباراة.

نتائج BOT تأتي من العميل؛ السيرفر لا يعيد تشغيل المباراة للتحقق منها. تُحفظ فقط عندما تبدأ المباراة وتنتهي أثناء تسجيل الدخول إلى الحساب نفسه. اللعب دون حساب أو بعد فقد الاتصال يُعامل كتدريب غير مسجّل؛ لا توجد قائمة انتظار محلية أو رفع لاحق لهذه النتائج.

قاعدة البيانات القديمة التي تحتوي أسماء دون كلمات مرور تُرحّل تلقائيًا. يمكن إنشاء حساب بالاسم القديم لتعيين كلمة مرور مع الاحتفاظ بتاريخ إنشاء الملف.

اتصال TCP الحالي غير مشفّر، وBase64 هي طريقة لتمثيل النص وليست تشفيرًا. التشغيل الحالي مخصص للشبكة المحلية؛ اقرأ [LEARNING.md](LEARNING.md) لشرح الحساب والحفظ.

## Run from Windows terminals

Gradle provides an alternative launcher and useful build diagnostics. PowerShell needs to know where Java is installed; this is separate from running with VS Code's Java **Run** action. If Java is not found in a new terminal, set these values to your installed JDK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

From the project folder, run one command in each of three terminals:

```powershell
# Terminal 1: server
.\gradlew.bat tcpServer
```

```powershell
# Terminal 2: first player
.\gradlew.bat run
```

```powershell
# Terminal 3: second player
.\gradlew.bat run
```

Those PowerShell settings last only for that terminal. To set them once for future terminals, open Windows **Edit environment variables for your account**, add a user `JAVA_HOME` pointing to the JDK folder, and add `%JAVA_HOME%\bin` to the user `Path`. Restart VS Code afterward.

VS Code's `java.jdt.ls.java.home` selects the JDK for the Java language server, not PowerShell. Recent Java extensions require Java 21 for that service; the game still targets Java 17. Changing that setting does not configure terminal `JAVA_HOME` or `Path`.

## Two computers on the same network

Run the server on one computer. Allow Java's TCP port through that computer's firewall for your private network. Find its LAN IPv4 address using `ipconfig`.

Start each client with the server's address:

```powershell
.\gradlew.bat run -PserverHost=192.168.1.10 -PserverPort=5000
```

Replace the example address. `localhost` refers to the computer running that client. The address and port are configuration, so they do not clutter the game window.

For VS Code's Java launcher, put `-Ddamka.server.host=192.168.1.10 -Ddamka.server.port=5000` in its `vmArgs`.

To use a different server port:

```powershell
.\gradlew.bat tcpServer --args="6000"
.\gradlew.bat run -PserverHost=192.168.1.10 -PserverPort=6000
```

On Linux/macOS, replace `.\gradlew.bat` with `bash gradlew`.

## Check the project

```powershell
.\gradlew.bat test
```

Tests exercise actual loopback TCP connections, game rules, account authentication, and temporary SQLite databases. For the visual checks, try:

1. Sign up/log in with two accounts, create/join a room, and confirm player X/O and the same board.
2. Reject an invalid move, then make a valid move; both boards update and the old error clears.
3. Resize or scroll during a move; the animated piece follows the correct cells.
4. Complete captures, a multi-capture, and promotion.
5. Resign, leave, close a client, and start another match.
6. Refresh **Profile** after a match and confirm the result appears once with the correct PvP statistics.
7. Complete a logged-in BOT match and check its separate statistics. Offline practice must not appear in history, including after a later login.
8. Restart the server and confirm accounts and history remain. A wrong password must not open another player's profile.

Read [LEARNING.md](LEARNING.md) for the code flow and a suggested reading order.
