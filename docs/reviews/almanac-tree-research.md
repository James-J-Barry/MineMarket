# Almanac as a pannable upgrade tree: research and proposal

James wants the Almanac's Upgrades tab to become a pannable tree of icons, like the advancements screen, instead of a
list. This note covers how vanilla and other mods build such screens, what 26.1 gives us, and a proposed design.

## How others do it

| Mod | Layout | Navigation | Node states | Worth taking |
|---|---|---|---|---|
| **Vanilla advancements** (`AdvancementsScreen`, `AdvancementTab`, `AdvancementWidget`) | Auto-laid-out tree (`TreeNodePosition`), one tree per tab, tiled background | Left-drag to pan, clamped to the tree's bounds; no zoom | Obtained / not obtained frame sprites; task, goal and challenge frame shapes | The whole pattern: viewport with scissor, `scrollX/Y`, bounds clamp, connector lines drawn first (black outline, then white), widgets on top, hover tooltip box |
| **Better Advancements / Reliable Advancements / Advancement Enhancement** | Vanilla tree, bigger resizable window, nodes can be dragged and positions saved | Middle-drag pan, Shift-scroll horizontal, Ctrl-scroll zoom | Configurable colours for completed/incomplete lines, icons and titles; arrow or straight connectors | Zoom and a window that uses more of the screen; coloured connectors that show what's done |
| **Pufferfish's Skills** (JSON-driven skill trees) | Hand-placed: each skill has `x, y` in `skills.json`, links in `connections.json`, laid out with a web editor | Drag to pan, scroll to zoom | **Locked, Available, Affordable, Unlocked, Excluded**; only Available and Unlocked need textures, the rest are darkened fallbacks | The five states map almost 1:1 onto ours; hand-placed coordinates in data; "affordable" as its own highlight |
| **FTB Quests** | Hand-placed nodes per chapter, chapter list down the side, dependency lines | Left-drag on empty space; scroll vertical, Shift-scroll horizontal, Ctrl-scroll zoom; bounded panning in survival | Locked / available / complete, with shapes per quest; clicking opens a detail panel | Click a node for a detail panel with the action button; bounded pan so you can't get lost; chapters ≈ our tiers |
| **Thaumcraft Thaumonomicon** | Hand-placed research map on a parchment background | Drag to pan | Researched / can research / hidden; unknown research shown as a mystery or not at all | Fog of war: you see what's next, not the whole endgame, so the tree keeps its surprises |

The control schemes vary, but **left-drag on empty space to pan** is common to vanilla and FTB, and **scroll to zoom** is
Pufferfish's default. Every mod that zooms keeps it to a few steps, because GUI text and item icons blur at odd scales.

## What 26.1 gives us (checked with `dev.sh api`)

- `GuiGraphicsExtractor.enableScissor(x1, y1, x2, y2)` / `disableScissor()` clip the tree to its viewport.
- `GuiGraphicsExtractor.pose()` is a `Matrix3x2fStack`, so pan is a translate and zoom is a scale around the viewport.
- `blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, w, h)` with vanilla's sprites:
  `advancements/task_frame_obtained|unobtained`, `goal_frame_*`, `challenge_frame_*` and `box_obtained|unobtained`.
  We can use them as they are, or put our own sprites under `textures/gui/sprites/almanac/` for a ledger look.
- `mouseDragged(MouseButtonEvent, dx, dy)`, `mouseReleased`, `mouseScrolled(mx, my, sx, sy)` (vanilla's screen overrides
  exactly these).
- `item(stack, x, y)` for icons; tooltips as vanilla does in `AdvancementTab.extractTooltips`.

No networking changes are needed. `AlmanacMenu` already syncs each node's state (owned, available, no cash, needs
quest, locked) and this player's price per node. Selecting a node is already a button click with the node's index.
The tree is a new way of drawing the same data.

## Proposal

**Layout: tiers as columns, placed by hand.** Tiers run left to right, eight columns in historical order, with a
labelled band per tier ("Tier 3: Exchanges"). Each node gets `x, y` grid cells and an `icon` item in `nodes.csv`
(optional columns; a missing position falls back to *tier column, next free row*, and a missing icon to the node's first
blueprint item). Hand placement follows Pufferfish and FTB. Our tree is small (24 nodes), and positions in the CSV keep
the art under design control.

```
 TIER 1       ┊ TIER 2               ┊ TIER 3                 ┊ TIER 4
              ┊                      ┊                        ┊
 (Bill Clip)  ┊ (Vault)─┬─(CD)       ┊ (Trading Floor)─(Tape) ┊ (Stock Exchange)─┬─(Newsfeed)
 (Price Brd)  ┊    │    └─(Loan)     ┊ (Newsstand)            ┊                  └─(Binder)
 (License)◆   ┊    │                 ┊                        ┊ (Safe Deposit)
 (Crate)      ┊    └───────────────────────────────────────────── ... ─(Digital Records, T5)
          2/4 🔒                 2/3 🔒                    2/3 🔒
```

- **Tier gate** ("the first node in a tier needs half of the previous tier") drawn as a dotted divider between columns,
  with a lock and "2 of 4" until it opens. That makes the rule visible without explaining it in text.
- **Connectors** as elbow lines, like vanilla: dark outline, then a fill of grey (locked), white (available) or gold
  (owned). Long cross-tier links (Vault → Digital Records) route along the row gaps.

**Node states** (Pufferfish's set, mapped onto the menu's existing states):

| State | Frame | Icon | Label |
|---|---|---|---|
| Owned | gold (obtained frame) | full colour | — |
| Affordable (available and you have the cash) | bright frame, slow pulse | full colour | price in green |
| Available, short of cash | plain frame | full colour | price in red |
| Needs a quest | plain frame, darkened | full colour + small quest mark | quest name on hover |
| Locked (parents or tier gate) | dark frame | darkened silhouette | — |
| Beyond the horizon (2+ tiers past what you can buy) | faint outline | `?` | "Keep going" |

The last row is the Thaumonomicon's fog of war. It keeps the endgame a surprise, which fits "fun first" and "hint, don't
spell out", and it keeps the screen from looking overwhelming at the start.

**Navigation:** left-drag on empty space to pan (vanilla and FTB); scroll wheel to zoom in three steps (50%, 75%, 100%),
centred on the cursor; arrow keys or WASD to nudge. Panning is clamped to the tree's bounds. On open, the view centres on
the right edge of what you own, so the next buys are on screen, and the view is remembered while the game runs.

**Selecting and buying:** hover shows a tooltip (title, price, one-line concept, what it grants). Click selects and
opens the detail panel on the right: cost, prerequisites, grants, one-line concept, **Buy**. That is today's detail panel
moved beside the tree, as FTB does. On a buy, the node turns gold and its outgoing connectors light up, with the existing
unlock Feedback cue.

**Window:** 220×170 is too small for a map. Proposal: the Upgrades tab uses most of the screen (vanilla's advancements
window is 252×140 inside; FTB goes full-screen). Guides and Quests keep the book size, or take the same frame for
consistency.

## Build plan

1. **Core:** `UnlockNode` gains `x`, `y`, `icon` (optional CSV columns, with fallbacks). Unit tests check that every node
   has a cell, no two nodes share one, and every parent sits left of or above its child.
2. **Screen:** `AlmanacTreeView` (client), with a viewport, pan/zoom state, connector pass, node pass, tooltip pass and
   hit-testing in tree coordinates. `AlmanacScreen`'s Upgrades tab hosts it plus the detail panel.
3. **Art:** frame sprites (vanilla ones first; our own later), a parchment tile background, tier band labels.
4. **Tests:** layout checks in core; a GameTest that buying through the menu still works by node index. GUI feel needs
   James in-game.

## Decisions for James

1. Window size: most of the screen (recommended), or keep the book size and rely on panning.
2. Fog of war beyond the next tier: yes (recommended) or show the whole tree.
3. Zoom: three steps (recommended), or pan only as vanilla does.
4. Frames: vanilla advancement frames to start (recommended, fast), or custom ledger-style sprites now.

## Sources

- [Pufferfish's Skills: skills.json](https://puffish.net/skillsmod/docs/creators/configuration/files/skills),
  [frames](https://puffish.net/skillsmod/docs/creators/configuration/appearance/frame),
  [connections](https://puffish.net/skillsmod/docs/creators/configuration/connection),
  [editor](https://puffish.net/skillsmod/docs/creators/editor)
- [Reliable Advancements (Better Advancements fork)](https://github.com/evanbones/Reliable-Advancements),
  [Advancement Enhancement](https://www.curseforge.com/minecraft/mc-mods/advancement-enhancement)
- [FTB Quests: navigating the quest book](https://docs.feed-the-beast.com/docs/mods/suite/Quests/Player/Questbook/Navigating/),
  [FTB Quests changelog (scroll/zoom controls)](https://github.com/FTBTeam/FTB-Quests/blob/main/CHANGELOG.md)
- [Thaumcraft 4: Thaumonomicon research](https://thaumcraft-4.fandom.com/wiki/Thaumonomicon_(research))
- Vanilla 26.1.2: `net.minecraft.client.gui.screens.advancements.*`, `GuiGraphicsExtractor` (via `dev.sh api`)
