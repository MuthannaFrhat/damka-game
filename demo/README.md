# Automated demo and video recording

Run one command to open the real game, complete an online match and demonstrate the Hard BOT. The demo uses JavaFX Robot for mouse clicks and keyboard input, and FFmpeg for MP4 recording.

## Run on Windows

You need the project's Java 17+ setup, a desktop of at least **1800 × 900** (1920 × 1080 recommended), and FFmpeg on your PATH.

To install FFmpeg with Windows Package Manager:

```powershell
winget install --exact --id Gyan.FFmpeg
```

Open a new terminal after installation, then check:

```powershell
ffmpeg -version
```

From the project folder, run:

```powershell
.\gradlew.bat demo
```

Let the script control the mouse and keyboard while it runs. Press **Esc** in a demo window to stop early. The runner starts its own server on an available port and closes its windows and connections when finished.

Find the completed recording here:

```text
demo/output/damka-demo-YYYYMMDD-HHMMSS.mp4
```

Open that folder with:

```powershell
explorer .\demo\output
```

## What the video shows

1. A server and two real JavaFX client windows.
2. Account creation for `GreenPlayer` and `RedPlayer`.
3. Creating a room, copying its code and joining from the other window.
4. A complete **39-move online match** from the normal starting board, including captures, capture chains and promotion. The result window announces **GreenPlayer wins!**; the Robot clicks OK to continue.
5. The saved win, loss and match history in both profiles.
6. A fresh **Hard BOT** match, with eight human moves and the BOT's actual responses using Minimax depth 3.

Chapter captions explain the steps while the app's own player, turn, board and result displays remain visible. The MP4 has no audio, so you can explain it during your presentation.

Every run creates its own disposable SQLite database inside `demo/output/session-.../`. Your normal accounts and `data/damka.db` are separate. Generated videos, databases and logs are ignored by Git.

## Useful commands

Check the automated flow without recording or installing FFmpeg:

```powershell
.\gradlew.bat demo -PdemoRecord=false
```

Run a faster rehearsal:

```powershell
.\gradlew.bat demo -PdemoRecord=false -PdemoSpeed=4
```

Use a specific FFmpeg executable or output folder:

```powershell
.\gradlew.bat demo "-Pffmpeg=C:\tools\ffmpeg\bin\ffmpeg.exe" "-PdemoOutput=C:\Videos\Damka"
```

On Linux, use `bash gradlew demo` from an X11 desktop. Recording currently supports Windows and Linux X11. The Gradle task sets JavaFX's Windows scale to 100% for this demo process so the Robot and recording use matching coordinates.

## How the code fits together

- **`DemoRunner.java`** controls the flow, positions the windows, moves the mouse, pastes input and waits for the app to finish each action.
- **`DemoMoves.java`** contains the online replay's coordinates. Each move is sent through normal board clicks and validated by the real server.
- **`DemoRecorder.java`** starts FFmpeg, records the screen area and finishes the MP4 cleanly.

The demo code is a separate Gradle source set. It reads UI state to locate controls and wait for updates; player actions go through Robot. Game rules, network handling and BOT decisions run through the same application code as normal play.
