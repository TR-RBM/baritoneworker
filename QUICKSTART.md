# BaritoneWorker — Quick Start

Five autonomous Baritone workers for **MC 26.1.2** (Fabric): **miner**, **lumber**,
**sorter**, **mover**, **builder**. Run one at a time.

## Needs
- Fabric Loader + Fabric API
- **Baritone** installed (the `baritone-meteor` build) — provided at runtime, not bundled.

## Install
Build with `JAVA_HOME=<jdk-25> ./gradlew build`, then drop
`build/libs/baritoneworker-1.3.0.jar` into your `mods/` folder next to Baritone.

## Shared setup
- Capture an area the mod's own way: stand on one corner and run the worker's
  `corner1`, then the opposite corner and `corner2` (mover/second areas use
  `source corner1` / `restock corner1` / …). No `#sel` — and a manual `#pause`
  cleanly suspends the worker, `#resume` continues it.
- Set Essentials homes (`/sethome <name>`) for the spots each worker teleports to.
- Equipment (miner/lumber/builder): `keep add <item|category> <n>` — mix exact ids
  and categories (`keep add pickaxe 2`, `keep add stone_pickaxe 1`, `keep add food 64`).
  `keep list` shows it. Optional separate supply room: `restock corner1`/`corner2`.
- Settings live in `config/baritoneworker/`.
- Restocking (miner/lumber/builder) is wall-safe and never skips a chest: it won't
  break blocks to reach one, and if a chest can't be reached it stops and says which.
  Ender chests are ignored unless you opt in with `<worker> ender on`.

## Miner
1. `/sethome mine` at the tunnel face, looking down the tunnel; `/sethome Home` by the chests.
2. `#miner corner1`/`corner2` on the chest room corners. Stock pickaxes + shovels + food.
3. `\` (backslash) or `#miner start`. Optional: `#miner ore on` to grab wall ore.

## Lumber
1. `/sethome wood` in the forest; `/sethome Home` by the chests.
2. `#lumber corner1`/`corner2` on the chest room corners. Stock axes + food.
3. `#lumber start`. Pick types with `#lumber wood exclude <flavour>`; replant with `#lumber replant on`.

## Sorter
1. `/sethome Home` by the chest room; `#sorter corner1`/`corner2` on its corners.
2. Tag chests: place a **sign** (one tag per line), or look at a chest and
   `#sorter assign <tag…>`, or edit `config/baritoneworker/sortscheme.json`.
   Tags = a category (`ores`, `logs`, `food`…), a scheme group, or an item id.
3. `#sorter start`.

## Mover
1. `/sethome source` by the source room; `/sethome Home` by the destination.
2. `#mover source corner1`/`corner2` on the source; `#mover dest corner1`/`corner2` on the destination.
3. `#mover mode copy` (chest N→N) or `sort` (re-sort at dest), then **with an empty
   inventory** `#mover start`.

## Builder
1. Open your schematic in **Litematica**. `/sethome build` at the site; `/sethome Home` by the supply room.
2. Stock the supply room with the schematic's blocks (+ food). `#builder corner1`/`corner2` on its corners.
3. Optional finish line: `#builder stopat <x> <y> <z>`. Then `#builder start` — it refills blocks when it runs dry and resumes.

Full details in `README.md`.
