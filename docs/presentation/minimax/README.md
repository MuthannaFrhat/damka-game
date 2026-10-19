# Minimax and Alpha–Beta: a real board walkthrough

This English learning presentation follows a six-piece checkers position through the project’s actual `Game` and `BotPlayer` logic. It includes legal board moves, real recursive calls, per-call variables, returned scores and the precise branches skipped by alpha–beta.

Start with the slides, then replay the calculation one step at a time in the interactive file.

- [PowerPoint: 16 slides with editable board diagrams and detailed English notes](Minimax_Real_Board.pptx)
- [PDF: the same 16 slides](Minimax_Real_Board.pdf)
- [Interactive walkthrough: 64 teaching steps](Minimax_Interactive.html)

## Open the interactive walkthrough

After pulling the repository, open `Minimax_Interactive.html` in a browser from your local folder. GitHub displays the HTML source; open the local file to use its controls. It works offline and needs no server or installation.

From the project folder in PowerShell:

```powershell
Start-Process .\docs\presentation\minimax\Minimax_Interactive.html
```

Use **Next**, **Previous** or the arrow keys. Each step shows the current simulated board, which callers are paused, the active call’s own variables, and the relevant Java code. **Jump to strict cutoff** moves to the example where `alpha = 3` and `beta = 0`.

## The example

The BOT is `o`, the human is `x`, and the BOT starts with depth 3. Coordinates are zero-based `(row, column)`, with row 0 at the top. All pieces in this example are ordinary pieces.

- BOT pieces: `(1,2)`, `(2,3)`, `(3,0)`.
- Human pieces: `(3,2)`, `(4,1)`, `(5,2)`.
- Candidate A: `(1,2) → (2,1)`, returned score `−3`.
- Candidate B: `(2,3) → (3,4)`, returned score `0`; the BOT chooses B.

This is a custom teaching position verified using the project’s rules, rather than a recorded match. Every shown move and resulting board was checked against `Game.move()`. Traced returns were compared with production `BotPlayer.minimax()` and the chosen move with `findBestMove()`. A separate rules traversal also confirmed the tree. There are no kings, terminal positions or continuing captures in this example.

With pruning, the search makes **15 recursive calls and 8 leaf evaluations**, versus **18 calls and 11 evaluations** without pruning. Both choose the same move. Counts exclude the `findBestMove()` wrapper. A skipped subtree’s returned value is shown as a sufficient bound until its exact value is known; unvisited leaf scores are hidden during the walkthrough.

The source code’s starting values are `Integer.MIN_VALUE` and `Integer.MAX_VALUE`, written as `−∞` and `+∞` in the diagrams. Each initial BOT candidate starts with fresh limits in this implementation. The current copied game controls MAX/MIN selection. All moves during the search are simulations; `DamkaFX` applies the chosen move afterward.
