# Minimax at depth 3: BOT → human → BOT

A compact presentation example showing how eight leaf scores determine the BOT’s first move. All scores use the BOT’s perspective: a larger score is better for the BOT.

The clean tree keeps its title, node labels, and scores. Explanations and assumptions are in this README and the PowerPoint speaker notes.

- [Main tree PNG](Minimax_Depth3_Diagram.png)
- [Matching eight-row table PNG](Minimax_Depth3_Table.png)
- [Two-page PDF: tree and table](Minimax_Depth3_Example.pdf)
- [Two editable PowerPoint slides: tree and table](Minimax_Depth3_Example.pptx)

## Put it in your presentation

In PowerPoint or Google Slides, choose **Insert → Image/Pictures**, select either PNG, and resize it using a corner handle. For editable text and shapes, open the PowerPoint file and copy the desired slide into your presentation. The PDF is convenient for viewing or sharing both pages.

## Explain it from bottom to top

1. **Read the leaves.** After the starting BOT move, a human reply, and a final simulated BOT reply, `depth=0` evaluates the resulting position. A has pairs `(3, 6)` and `(0, 3)`; B has `(6, 9)` and `(−3, 0)`.
2. **Resolve A’s BOT replies.** At `depth=1`, BOT takes MAX: `max(3, 6)=6` and `max(0, 3)=3`.
3. **Resolve B’s BOT replies.** BOT takes MAX again: `max(6, 9)=9` and `max(−3, 0)=0`.
4. **Resolve the human replies.** At `depth=2`, human takes MIN: A returns `min(6, 3)=3`; B returns `min(9, 0)=0`.
5. **Choose the first BOT move.** `findBestMove(depth=3)` compares `max(3, 0)` and chooses **A**.

These are illustrative scores, not a verified legal board position. The example assumes each move passes the turn and no terminal positions occur. In the actual code, a continuing capture can keep the same player; the copied game’s current player determines MAX/MIN. `botSymbol` stays constant throughout.
