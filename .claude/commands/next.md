Continue the current milestone.

1. Read `CLAUDE.md` (rules, invariants, 26.1 API notes) and the current milestone spec in `docs/milestones/`.
2. Pick the next unchecked "Done when" item. Say which one in one line.
3. Implement it: core logic + unit tests in `exchange-core` first, then the thin mod layer + a GameTest.
   Check every unfamiliar Minecraft/Fabric API with `./scripts/dev.sh api` before using it.
4. Run `./scripts/dev.sh check` until green.
5. Tick the item in the milestone spec, and give James a short in-game test script for anything visual.
6. Commit with a descriptive message (do not push).
