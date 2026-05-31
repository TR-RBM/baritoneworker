# BaritoneWorker — Quick Start

Five autonomous Baritone workers for **MC 26.1.2** (Fabric): **miner**, **lumber**,
**sorter**, **mover**, **builder**. Run one at a time.

## Needs
- Fabric Loader + Fabric API
- **Baritone** installed (the `baritone-meteor` build) — provided at runtime, not bundled.

## Install
Build with `JAVA_HOME=<jdk-25> ./gradlew build`, then drop
`build/libs/baritoneworker-1.1.0.jar` into your `mods/` folder next to Baritone.

## Shared setup
- Capture an area: `#sel 1`, `#sel 2` (Baritone selection), then the worker's
  `area` / `source` / `dest` command.
- Set Essentials homes (`/sethome <name>`) for the spots each worker teleports to.
- Settings live in `config/baritoneworker/`.

## Miner
1. `/sethome mine` at the tunnel face, looking down the tunnel; `/sethome Home` by the chests.
2. `#sel 1`/`#sel 2` the chest room → `#miner area`. Stock pickaxes + food.
3. `\` (backslash) or `#miner start`. Optional: `#miner ore on` to grab wall ore.

## Lumber
1. `/sethome wood` in the forest; `/sethome Home` by the chests.
2. `#sel 1`/`#sel 2` the chest room → `#lumber area`. Stock axes + food.
3. `#lumber start`. Pick types with `#lumber wood exclude <flavour>`; replant with `#lumber replant on`.

## Sorter
1. `/sethome Home` by the chest room; `#sel 1`/`#sel 2` → `#sorter area`.
2. Tag chests: place a **sign** (one tag per line), or look at a chest and
   `#sorter assign <tag…>`, or edit `config/baritoneworker/sortscheme.json`.
   Tags = a category (`ores`, `logs`, `food`…), a scheme group, or an item id.
3. `#sorter start`.

## Mover
1. `/sethome source` by the source room; `/sethome Home` by the destination.
2. `#sel 1`/`#sel 2` the source → `#mover source`; select the destination → `#mover dest`.
3. `#mover mode copy` (chest N→N) or `sort` (re-sort at dest), then **with an empty
   inventory** `#mover start`.

## Builder
Builds a schematic **file** (from `schematics/`, like `#build`) or the placement
open in **Litematica**.

1. `/sethome build` at the site; `/sethome Home` by the supply room.
2. *(optional, for hands-free restock)* stock the supply room with the schematic's
   blocks (+ food), then `#sel 1`/`#sel 2` → `#builder area`. With blocks in your
   inventory you can skip this — it just stops when it runs dry.
3. **File:** drop `ZMinus.litematic` in `schematics/`, stand where it should start,
   and run `#builder ZMinus.litematic` (anchored at the block you're standing on).
   **Litematica:** open the placement instead and run `#builder start`.
4. Optional finish line: `#builder stopat <x> <y> <z>`.

Full details in `README.md`.
