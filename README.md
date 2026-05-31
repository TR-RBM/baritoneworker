# BaritoneWorker

A pack of **autonomous Baritone workers** for Minecraft **26.1.2** (Fabric). Each
one drives an unattended loop on a server with Essentials-style home commands
(`/home`, `/sethome`, `/delhome`) and Baritone selections (`#sel`):

| Worker | Command | What it does |
|--------|---------|--------------|
| **Miner**  | `#miner`  | Strip-mines a tunnel, hauls loot home, restocks pickaxes + food, repeats. |
| **Lumber** | `#lumber` | Roams a forest chopping logs (Baritone `mine`), hauls wood home, restocks axe + food, optionally replants. |
| **Sorter** | `#sorter` | Organizes the chests in an area so each item lands in the chest tagged for it. |
| **Mover**  | `#mover`  | Moves every chest in a source area into a destination area — a positional copy, or re-sorted. |
| **Builder**| `#builder`| Builds the schematic you've opened in **Litematica**, fetching more blocks from a supply area when it runs dry. |

The workers are independent; **run one at a time**.

## Requirements

- Minecraft **26.1.2** + Fabric Loader + Fabric API
- **Baritone** installed on the client (the `baritone-meteor` build). The mod
  compiles against the Baritone API but does **not** bundle it — it uses the
  Baritone you already have installed.

## Building

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

The jar lands in `build/libs/baritoneworker-1.1.0.jar`. Drop it in your `mods/`
folder alongside Baritone.

## Shared concepts

- **Areas** are captured from Baritone selections: aim at two corners with
  `#sel 1` / `#sel 2`, then run the worker's `area` (or `source`/`dest`) command.
  Chests, trapped chests and barrels in the box are found automatically; double
  chests are handled once.
- **Homes** are Essentials homes the worker teleports to. `/home` has a warm-up
  delay, so the workers wait for the actual position jump rather than a fixed
  timer.
- **Kept items** are never deposited (the miner keeps its pickaxe/food/torches,
  the lumber bot its axe/food/saplings). Armor and the off-hand are never touched.
- Settings persist under `config/baritoneworker/` (`miner.properties`,
  `lumber.properties`, `sorter.properties`, `mover.properties`, `sortscheme.json`).

---

## Miner — `#miner`

Mine → tunnel → return when full → deposit + restock → repeat.

1. `/sethome mine` at the tunnel face, **looking the way you want to dig**.
2. `/sethome Home` by your storage room.
3. `#sel 1` / `#sel 2` around the chest room, then `#miner area`.
4. Put spare pickaxes + food in those chests.
5. `#miner start` (or the `\` keybind).

| Command | Effect |
|---------|--------|
| `#miner start` / `stop` | run / halt |
| `#miner area [clear]` | capture the current selection as the chest area |
| `#miner pickaxe <n\|item>` | how many / which pickaxe to keep (default `2`, `diamond_pickaxe`) |
| `#miner food <n\|item>` | how much / which food to keep (default `64`, `baked_potato`) |
| `#miner freeslots <n>` | return to base at this many free slots (default `1`) |
| `#miner mine <name>` / `home <name>` | home names |
| `#miner ore on\|off` | also grab ore exposed in the tunnel walls (off by default) |
| `#miner ore exclude\|include <group>` | e.g. `exclude coal` (bundles deepslate) |

Exposed-ore mining skips any ore touching lava/water (`#miner ore fluidcheck off`
to disable). Groups: `coal iron copper gold redstone lapis diamond emerald`
(+ `nether_gold quartz debris`).

---

## Lumber — `#lumber`

The same loop, for wood: at the work home it runs Baritone's `mine` on the
selected log types, hauls the wood back, and restocks an axe + food (+ saplings
when replanting).

1. `/sethome wood` in the forest, `/sethome Home` by your storage room.
2. `#sel 1` / `#sel 2` around the chest room, then `#lumber area`.
3. Stock spare axes + food (+ saplings if replanting) in those chests.
4. `#lumber start`.

| Command | Effect |
|---------|--------|
| `#lumber start` / `stop` | run / halt |
| `#lumber area [clear]` | capture the current selection as the chest area |
| `#lumber axe <n\|item>` | how many / which axe to keep (default `1`, `diamond_axe`) |
| `#lumber food <n\|item>` | how much / which food to keep (default `64`) |
| `#lumber wood include\|exclude <flavour>` | pick wood types (default: all) |
| `#lumber replant on\|off` | replant saplings on cleared ground (default **off**) |
| `#lumber sapling <n>` | saplings to keep when replanting (default `16`) |
| `#lumber freeslots <n>` | return to base at this many free slots (default `1`) |
| `#lumber work <name>` / `home <name>` | home names |

Flavours: `oak birch spruce jungle acacia dark_oak mangrove cherry pale_oak`.

It harvests **one tree at a time**: it flood-fills the connected logs of the tree
it's on and stays locked to it — pulling Baritone back if it drifts toward a
closer tree — until every block of that tree is gone, then moves to the next. (If
a few logs are genuinely unreachable it skips them after a timeout so it can't
hang.) **Replanting is best-effort** — between trees it plants a held sapling on
cleared dirt/grass near the player; it is not a precision tree farm.

---

## Sorter — `#sorter`

Organizes the chests in one area so each item ends up in the chest tagged for it.
A chest's **tags** come from two places:

- **Signs** placed on or beside the chest — each non-blank line is a tag.
- A **scheme file**, `config/baritoneworker/sortscheme.json`, which can pin many
  chests by position and define custom groups. You can also tag the chest you're
  looking at in-game with `#sorter assign <tag…>`.

A **tag** matches an item if it is a built-in **category** (`ores`, `logs`,
`planks`, `wood`, `food`, `tools`, `weapons`, `armor`, `redstone`, `dyes`,
`building`, `misc`), a **custom group** from the scheme, or a **literal item id**
(`diamond` or `minecraft:diamond`). A chest tagged `misc` catches everything left
over.

```json
{
  "groups": { "smeltables": ["raw_iron", "raw_gold", "raw_copper"] },
  "chests":  [ { "pos": [10, 64, 20], "tags": ["ores", "smeltables"] } ]
}
```

| Command | Effect |
|---------|--------|
| `#sorter start` / `stop` | run / halt |
| `#sorter area [clear]` | capture the current selection as the chest area |
| `#sorter home <name>` | the chest-room home (default `Home`) |
| `#sorter assign <tag…>` | pin tags onto the chest you're looking at |
| `#sorter reload` | reload `sortscheme.json` from disk |
| `#sorter categories` | list the built-in category tags |

The sorter alternates pulling misplaced items into its inventory and depositing
them into their tagged chest, until a full pass moves nothing. Items with no
matching chest are left in place and reported.

---

## Mover — `#mover`

Empties every chest in a **source** area into a **destination** area, shuttling
between two homes. **Run it with an empty inventory** — it is a bulk carrier.

1. `/sethome source` by the source room, `/sethome Home` by the destination.
2. `#sel 1` / `#sel 2` around the source, run `#mover source`.
3. Select the destination, run `#mover dest`.
4. `#mover mode copy` (or `sort`), then `#mover start`.

| Command | Effect |
|---------|--------|
| `#mover start` / `stop` | run / halt |
| `#mover source [home <name>\|clear]` | capture selection as the source (or set its home) |
| `#mover dest [home <name>\|clear]` | capture selection as the destination (or set its home) |
| `#mover mode copy\|sort` | `copy` = source chest N → dest chest N; `sort` = re-sort at dest by the sorter scheme |

In **copy** mode each source chest's contents go to the matching destination
chest (overflow spills forward). In **sort** mode they're placed using the same
tag scheme the sorter uses (signs + `sortscheme.json`).

---

## Builder — `#builder`

Builds the schematic currently open in **Litematica** (requires the Litematica
mod). It runs Baritone's builder; when it runs out of blocks it resets the build
home to the spot it left off, teleports to a supply room, refills on blocks (and
food), teleports back, and resumes — the same home-dance the miner uses.

1. Open/place your schematic in Litematica.
2. `/sethome build` at the build site, `/sethome Home` by your supply room.
3. Stock the supply room with the blocks the schematic needs (+ food).
4. `#sel 1` / `#sel 2` around the supply room, then `#builder area`.
5. *(optional)* set a finish line: `#builder stopat <x> <y> <z>` (or `stopat here`).
6. `#builder start`.

| Command | Effect |
|---------|--------|
| `#builder start` / `stop` | run / halt |
| `#builder area [clear]` | capture the current selection as the supply area |
| `#builder food <n\|item>` | how much / which food to keep (default `64`) |
| `#builder work <name>` / `home <name>` | build-site and base home names (default `build` / `Home`) |
| `#builder litematic <index>` | which open Litematica placement to build (default `0`) |
| `#builder stopat here\|<x> <y> <z>\|radius <n>\|clear` | stop when the player reaches this spot |

"Materials" are simply placeable blocks — the restock grabs whatever blocks are
in the supply chests, so it works for any schematic without listing materials.
It **only withdraws** (food + blocks) and never dumps your inventory. The
`stopat` target ends an otherwise-endless `buildRepeat`; without it, a one-shot
build stops on its own when the schematic is finished.

---

## Controls

- **Keybind:** `\` (backslash) toggles the **miner** on/off. Rebind under
  *Options → Controls → Misc → "Toggle Auto-Miner"*. The other workers start via
  their `#` command.

## Source layout

```
src/client/java/com/luna0wl/baritoneworker/
├── client/BaritoneWorkerClient.java   # init: keybind, tick driver, command registration
└── worker/
    ├── common/   # Baritones, Teleporter, MenuActions, ContainerService, ItemNames, ItemCategories
    ├── miner/    # MinerWorker, MinerConfig, MinerCommand, MinerState, Ores
    ├── lumber/   # LumberWorker, LumberConfig, LumberCommand, LumberState, Woods
    ├── sorter/   # SorterWorker, SorterConfig, SorterCommand, SortState, SortScheme
    ├── mover/    # MoverWorker, MoverConfig, MoverCommand, MoverState
    └── builder/  # BuilderWorker, BuilderConfig, BuilderCommand, BuilderState
```
