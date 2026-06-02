# BaritoneWorker

A pack of **autonomous [Baritone](https://github.com/cabaletta/baritone) workers** for
Minecraft **26.1.2** on [Fabric](https://fabricmc.net/). Each one drives an unattended
loop on a server using Essentials-style home commands ([`/home`, `/sethome`,
`/delhome`](https://essentialsx.net/)) and Baritone selections (`#sel`).

| Worker | Command | What it does |
|--------|---------|--------------|
| **Miner**  | `#miner`  | Strip-mines a tunnel, hauls loot home, restocks pickaxes + food, repeats. |
| **Lumber** | `#lumber` | Roams a forest chopping logs (Baritone `mine`), hauls wood home, restocks axe + food, optionally replants. |
| **Sorter** | `#sorter` | Organizes the chests in an area so each item lands in the chest tagged for it. |
| **Mover**  | `#mover`  | Moves every chest in a source area into a destination area — a positional copy, or re-sorted. |
| **Builder**| `#builder`| Builds a [Litematica](https://modrinth.com/mod/litematica) schematic (or a schematic file), fetching only the blocks it still needs from a supply area when it runs dry. |

The workers are independent; **run one at a time**.

## Requirements

| | |
|---|---|
| **Minecraft** | [`26.1.2`](https://www.minecraft.net/) |
| **Mod loader** | [Fabric Loader](https://fabricmc.net/use/installer/) `≥ 0.19.2` |
| **Fabric API** | [fabric-api](https://modrinth.com/mod/fabric-api) (matching `26.1.2`) |
| **Java** | [`25+`](https://adoptium.net/) (Temurin works well) |
| **Baritone** | [Baritone](https://github.com/cabaletta/baritone) installed on the client — the `baritone-meteor` build. |
| **Litematica** *(builder only)* | [Litematica](https://modrinth.com/mod/litematica) — needed only to build an *open placement*; building from a schematic **file** doesn't require it. |
| **Server homes** | An Essentials-style `/home` / `/sethome` / `/delhome` plugin such as [EssentialsX](https://essentialsx.net/). |

The mod compiles against the Baritone **API** but does **not** bundle it — it uses the
Baritone you already have installed.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft `26.1.2`.
2. Drop these in your `mods/` folder:
   - [Fabric API](https://modrinth.com/mod/fabric-api)
   - [Baritone](https://github.com/cabaletta/baritone) (the `baritone-meteor` build)
   - *(for the builder's open-placement mode)* [Litematica](https://modrinth.com/mod/litematica)
   - `baritoneworker-<version>.jar` (see **Building** below, or grab a release)
3. Launch with the Fabric profile.

## Building

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

The jar lands in `build/libs/baritoneworker-<version>.jar`. Drop it in your `mods/`
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
- **Restocking is wall-safe and never skips a chest.** While a worker services its
  supply chests it temporarily disables Baritone's block-breaking, so it won't tear
  through walls to reach a chest. It also won't quietly skip one: if a chest genuinely
  can't be reached without breaking blocks, the worker retries and then **stops and
  tells you which chest to clear**, rather than moving past it. **Ender chests** are
  ignored by default — turn them on per worker with `ender on` (e.g. `#miner ender on`).
  The miner, lumber and builder share one chest-finding/restock module
  (`common/ChestRoute`), so the same behaviour applies to all three.
- Settings persist under `config/baritoneworker/` (`builder.properties`,
  `miner.properties`, `lumber.properties`, `sorter.properties`, `mover.properties`,
  `sortscheme.json`).

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
| `#miner ender on\|off` | also service ender chests in the area (default off) |
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
| `#lumber ender on\|off` | also service ender chests in the area (default off) |

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

Builds a [Litematica](https://modrinth.com/mod/litematica) schematic with Baritone,
hands-free. When Baritone runs out of materials (it prints *"Missing materials …
Pausing"*) the worker teleports to a supply room, refills with **only the blocks the
build still needs**, teleports back, and resumes — until the schematic is finished,
a `stopat` target is reached, or the supply runs out.

**Two build sources:**
- the schematic currently **open in Litematica** (default), or
- a schematic **file** from your `schematics/` folder (set with `file`, just like
  Baritone's own `#build` — Litematica not required).

### Setup

1. Open/place your schematic in Litematica (or pick a file with `#builder file <name>`).
2. `/sethome build` at the build site, `/sethome Home` by your supply room.
3. Stock the supply room with the blocks the schematic needs (+ food).
4. `#sel 1` / `#sel 2` around the supply room, then `#builder area`.
5. *(optional, for an endless `buildRepeat`)* set a finish line: `#builder stopat here`.
6. `#builder start`.

### Commands

| Command | Effect |
|---------|--------|
| `#builder start` / `stop` | run / halt |
| `#builder area [clear]` | capture the current selection as the supply area |
| `#builder food <n\|item>` | how much / which food to keep (default `64`) |
| `#builder work <name>` / `home <name>` | build-site and base home names (default `build` / `Home`) |
| `#builder litematic <index>` | which open Litematica placement to build (default `0`) |
| `#builder file <name\|clear>` | build a schematic file from `schematics/` (`clear` = open placement) |
| `#builder origin here \| <x> <y> <z> \| clear` | fixed corner for a file build (`clear` = auto-anchor) |
| `#builder stopat here \| <x> <y> <z> \| radius <n> \| clear` | stop when the player reaches this spot |
| `#builder sethome on\|off` | move the `build` home to where it stops each trip (default **off**) |
| `#builder builds <n\|infinite>` | `buildRepeat`: builds' worth of materials to carry per trip (default `1`) |
| `#builder ender on\|off` | also restock from ender chests in the supply area (default off) |
| `#builder <file.litematic>` | shortcut: set the file **and** start, like `#build` |

### Recipe-aware restocking

The builder reads the schematic and **only fetches the blocks it actually needs** — it
won't grab block types the build doesn't use. It also subtracts what's **already placed
in the world**, so a repeated or half-finished build only pulls what's still missing and
never re-hauls materials it already used. (If it can't read the schematic for some reason
it falls back to grabbing any block, and tells you.)

### `buildRepeat` and `builds`

Baritone's [`buildRepeat`](https://github.com/cabaletta/baritone/blob/master/SETTINGS.md)
setting tiles a schematic over and over. By default each supply trip carries **one tile's**
worth of blocks; raise it to make fewer round-trips:

```text
#builder builds 1          # one tile per trip (default)
#builder builds 10         # ten tiles' worth of blocks per trip — fewer supply runs
#builder builds infinite   # fill the bag with as many needed blocks as fit
```

A repeating build never finishes on its own — end it with `#builder stopat here` (run it
standing where you want it to stop), or by setting Baritone's `buildRepeatCount`.

### `sethome` — should it move the build home?

Unlike the miner (whose tunnel face advances), a build site stays put, so by default the
worker **keeps your existing `build` home** and just teleports back to it each trip. Turn
this on only if your build crawls far from the start and you want the home to follow it:

```text
#builder sethome off        # default — keep your '/sethome build' spot
#builder sethome on         # /delhome + /sethome 'build' wherever it stops each trip
```

> ⚠️ With `sethome on`, the home is re-set wherever Baritone happened to pause — often
> mid-air on the structure. Leave it **off** unless you specifically need the home to
> track a far-roaming build.

The builder **only withdraws** (food + needed blocks) and never dumps your inventory. When
restocking it won't break walls to reach a chest and never skips one — see
**Shared concepts** above. To also pull from ender chests in the supply room, use
`#builder ender on`.

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
    ├── common/   # Baritones, Teleporter, MenuActions, ContainerService, ChestRoute, ItemNames, ItemCategories
    ├── miner/    # MinerWorker, MinerConfig, MinerCommand, MinerState, Ores
    ├── lumber/   # LumberWorker, LumberConfig, LumberCommand, LumberState, Woods
    ├── sorter/   # SorterWorker, SorterConfig, SorterCommand, SortState, SortScheme
    ├── mover/    # MoverWorker, MoverConfig, MoverCommand, MoverState
    └── builder/  # BuilderWorker, BuilderConfig, BuilderCommand, BuilderState
```

## License

[CC0-1.0](LICENSE) — public domain dedication. Do whatever you want with this code; no
attribution required.
