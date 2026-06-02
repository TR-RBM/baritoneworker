# BaritoneWorker

A pack of **autonomous [Baritone](https://github.com/cabaletta/baritone) workers** for
Minecraft **26.1.2** on [Fabric](https://fabricmc.net/). Each one drives an unattended
loop on a server using Essentials-style home commands ([`/home`, `/sethome`,
`/delhome`](https://essentialsx.net/)). The workers capture their own areas (no
`#sel` needed) and respect Baritone's manual `#pause`/`#resume`, so core Baritone
commands and the workers no longer trip over each other.

| Worker | Command | What it does |
|--------|---------|--------------|
| **Miner**  | `#miner`  | Strip-mines a tunnel, hauls loot home, restocks pickaxes + food, repeats. |
| **Lumber** | `#lumber` | Roams a forest chopping logs (Baritone `mine`), hauls wood home, restocks axe + food, optionally replants. |
| **Sorter** | `#sorter` | Organizes the chests in an area so each item lands in the chest tagged for it. |
| **Mover**  | `#mover`  | Moves every chest in a source area into a destination area — a positional copy, or re-sorted. |
| **Builder**| `#builder`| Builds a [Litematica](https://modrinth.com/mod/litematica) schematic (or a schematic file), fetching only the blocks it still needs from a supply area when it runs dry. |
| **Digger** | `#digger` | Excavates a whole selected region (terraforming / clearing huge chunks), hauls the spoil to chests, restocks tools, buckets lava and drains water. |

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

- **Areas** are captured by the mod itself — stand on one corner of the room and run
  the worker's `corner1`, then the opposite corner and `corner2` (the mover and any
  second area use `source corner1`/`restock corner1`/… ). This is independent of
  Baritone's own `#sel`, so `#sel` / `#sel ca` stay free for your own use. Chests,
  trapped chests and barrels in the box are found automatically; double chests are
  handled once. `area status` shows the current box; `area clear` forgets it.
- **Respecting `#pause`.** Every worker watches Baritone's pathing controller; when
  you manually `#pause` Baritone the worker cleanly suspends (it won't re-issue
  `tunnel`/`mine`/`build` underneath you), and on `#resume` it picks up where it left
  off. The builder's own *"out of materials"* pause is unaffected.
- **Equipment / keep-list.** The miner, lumber and builder share one flexible
  loadout: `keep add <item|category> <count>`. Entries can be exact ids
  (`diamond_pickaxe`) or categories (`pickaxe`, `axe`, `shovel`, `sword`, `food`, …),
  and they mix — e.g. `keep add stone_pickaxe 1` + `keep add diamond_pickaxe 1` keeps
  one of each, while `keep add pickaxe 2` keeps any two pickaxes. Anything **not** on
  the keep-list is deposited; anything below its count is restocked. `keep list`
  shows it, `keep remove`/`keep clear` edit it.
- **Separate restock area (optional).** By default a worker deposits loot and
  restocks supplies in the same chest area. Set a distinct supply room with
  `restock corner1` / `restock corner2` and the worker deposits in the chest area but
  withdraws its keep-list items from the restock area instead (optionally teleporting
  there first with `restock home <name>`). Leave it unset to keep the single-area
  behaviour.
- **Homes** are Essentials homes the worker teleports to. `/home` has a warm-up
  delay, so the workers wait for the actual position jump rather than a fixed
  timer.
- **Kept items** are never deposited — they're the worker's keep-list (see above; the
  lumber bot also keeps saplings while replanting). Armor and the off-hand are never touched.
- **Restocking is wall-safe and never skips a chest.** While a worker services its
  supply chests it temporarily disables Baritone's block-breaking, so it won't tear
  through walls to reach a chest. It also won't quietly skip one: if a chest genuinely
  can't be reached without breaking blocks, the worker retries and then **stops and
  tells you which chest to clear**, rather than moving past it. **Ender chests** are
  ignored by default — turn them on per worker with `ender on` (e.g. `#miner ender on`).
  The miner, lumber and builder share one chest-finding/restock module
  (`common/ChestRoute`), so the same behaviour applies to all three.
- **Chunk-load tolerant.** Right after teleporting home the surrounding chunks may
  not have loaded yet, so the first chest scan can momentarily come back empty. The
  workers wait and rescan for a few seconds before concluding an area has no chests,
  instead of bailing on the very first tick.
- **Sweep vs. service.** The builder keeps re-scanning the supply area for more
  chests while it still needs materials (it has to gather everything). The miner and
  lumber bot don't: they service the chests they found, then head straight back to
  work — they won't keep walking the whole room after they've deposited and restocked.
- **Sort on deposit (optional).** Turn it on per worker with `sorter on` (e.g.
  `#miner sorter on` / `#lumber sorter on`). When on, the worker doesn't just dump its
  haul into the first free chest — as it visits each chest it deposits only the items
  that chest is tagged for, using the **destination chests' own tags** (signs +
  `sortscheme.json`, the same scheme the `#sorter` uses). Items no chest is tagged for
  fall back to any chest so nothing gets stuck; kept items (and the lumber bot's
  saplings) are never deposited. It's one pass — no extra trips — and is shared through
  a small `common/SortPlan` helper, so adding it to a future worker is a one-liner.
  Default **off**.
- Settings persist under `config/baritoneworker/` (`builder.properties`,
  `miner.properties`, `lumber.properties`, `sorter.properties`, `mover.properties`,
  `digger.properties`, `sortscheme.json`).

---

## Miner — `#miner`

Mine → tunnel → return when full → deposit + restock → repeat.

1. `/sethome mine` at the tunnel face, **looking the way you want to dig**.
2. `/sethome Home` by your storage room.
3. Stand on a corner of the chest room and `#miner corner1`, then the opposite corner and `#miner corner2`.
4. Put spare pickaxes + shovels + food in those chests.
5. `#miner start` (or the `\` keybind).

| Command | Effect |
|---------|--------|
| `#miner start` / `stop` | run / halt |
| `#miner corner1` / `corner2` | capture the chest (deposit) area by standing on its corners |
| `#miner area clear\|status` | manage the chest area |
| `#miner restock corner1\|corner2\|clear\|status` | a separate supply area to restock from |
| `#miner restock home <name\|clear>` | teleport to this home before restocking (default: base home) |
| `#miner keep add <item\|category> <n>` | keep & restock, e.g. `keep add pickaxe 2`, `keep add diamond_pickaxe 1`, `keep add food 64` |
| `#miner keep remove <item\|category>` / `keep clear` / `keep list` | edit the keep-list |
| `#miner freeslots <n>` | return to base at this many free slots (default `1`) |
| `#miner mine <name>` / `home <name>` | home names |
| `#miner ender on\|off` | also service ender chests in the area (default off) |
| `#miner sorter on\|off` | deposit each item into the chest tagged for it (signs/sortscheme; default off) |
| `#miner ore on\|off` | also grab ore exposed in the tunnel walls (off by default) |
| `#miner ore exclude\|include <group>` | e.g. `exclude coal` (bundles deepslate) |

Defaults keep `diamond_pickaxe ×2`, `diamond_shovel ×1`, `baked_potato ×64`, `torch ×64`.
Old `miner.properties` files migrate to the keep-list automatically on first load.

Exposed-ore mining skips any ore touching lava/water (`#miner ore fluidcheck off`
to disable). Groups: `coal iron copper gold redstone lapis diamond emerald`
(+ `nether_gold quartz debris`).

---

## Lumber — `#lumber`

The same loop, for wood: at the work home it runs Baritone's `mine` on the
selected log types, hauls the wood back, and restocks an axe + food (+ saplings
when replanting).

1. `/sethome wood` in the forest, `/sethome Home` by your storage room.
2. Stand on a corner of the chest room and `#lumber corner1`, then the opposite corner and `#lumber corner2`.
3. Stock spare axes + food (+ saplings if replanting) in those chests.
4. `#lumber start`.

| Command | Effect |
|---------|--------|
| `#lumber start` / `stop` | run / halt |
| `#lumber corner1` / `corner2` | capture the chest (deposit) area by standing on its corners |
| `#lumber area clear\|status` | manage the chest area |
| `#lumber restock corner1\|corner2\|clear\|status` / `restock home <name\|clear>` | a separate supply area to restock from |
| `#lumber keep add <item\|category> <n>` | keep & restock, e.g. `keep add axe 1`, `keep add food 64` |
| `#lumber keep remove <item\|category>` / `keep clear` / `keep list` | edit the keep-list |
| `#lumber wood include\|exclude <flavour>` | pick wood types (default: all) |
| `#lumber replant on\|off` | replant saplings on cleared ground (default **off**) |
| `#lumber sapling <n>` | saplings to keep when replanting (default `16`) |
| `#lumber freeslots <n>` | return to base at this many free slots (default `1`) |
| `#lumber work <name>` / `home <name>` | home names |
| `#lumber ender on\|off` | also service ender chests in the area (default off) |
| `#lumber sorter on\|off` | deposit each item into the chest tagged for it (signs/sortscheme; default off) |

Saplings are kept/restocked automatically while `replant` is on (they follow the
selected wood flavours), separately from the keep-list.

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
`building`, `nether`, `ender`, `overworld`, `misc`), a **custom group** from the
scheme, or a **literal item id** (`diamond` or `minecraft:diamond`). A chest tagged
`misc` catches everything left over.

The **dimension** tags `nether` / `ender` / `overworld` split items by where they
come from: `nether` is nether-only blocks (netherrack, quartz, blackstone, basalt,
soul/crimson/warped, glowstone, netherite…), `ender` is End-only blocks (end stone,
purpur, chorus, shulker boxes, elytra…), and `overworld` is everything else.
**Overworld always wins** ties — anything obtainable in more than one dimension
(gravel, magma block, gold, obsidian…) counts as overworld. Tag three chests
`nether`, `ender`, `overworld` for a clean dimensional split.

```json
{
  "groups": { "smeltables": ["raw_iron", "raw_gold", "raw_copper"] },
  "chests":  [ { "pos": [10, 64, 20], "tags": ["ores", "smeltables"] } ]
}
```

| Command | Effect |
|---------|--------|
| `#sorter start` / `stop` | run / halt |
| `#sorter corner1` / `corner2` | capture the chest area by standing on its corners |
| `#sorter area clear\|status` | manage the chest area |
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
2. Stand on the source room's corners: `#mover source corner1`, then `corner2`.
3. Do the same for the destination: `#mover dest corner1` / `corner2`.
4. `#mover mode copy` (or `sort`), then `#mover start`.

| Command | Effect |
|---------|--------|
| `#mover start` / `stop` | run / halt |
| `#mover source corner1\|corner2\|home <name>\|clear\|status` | capture/manage the source area |
| `#mover dest corner1\|corner2\|home <name>\|clear\|status` | capture/manage the destination area |
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
4. Stand on a corner of the supply room and `#builder corner1`, then the opposite corner and `#builder corner2`.
5. *(optional, for an endless `buildRepeat`)* set a finish line: `#builder stopat here`.
6. `#builder start`.

### Commands

| Command | Effect |
|---------|--------|
| `#builder start` / `stop` | run / halt |
| `#builder corner1` / `corner2` | capture the supply area by standing on its corners |
| `#builder area clear\|status` | manage the supply area |
| `#builder keep add <item\|category> <n>` | extra items to withdraw alongside build blocks (e.g. `keep add food 64`) |
| `#builder keep remove <item\|category>` / `keep clear` / `keep list` | edit the keep-list |
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

## Digger — `#digger`

Excavates a whole selected region empty — for terraforming, clearing a perimeter, or
digging out huge chunks — then hauls the spoil to chests and restocks its tools, all
unattended. It is the automation equivalent of Baritone's `#sel cleararea`, but it
**breaks each block itself** (aiming at one block and holding the break until it is
actually gone before moving on), so it doesn't suffer the jittery, never-finishing
block-breaking Baritone has in this version. Break time follows the real block — instant
for sand/gravel/dirt, longer for stone/obsidian — because it waits for the block to
disappear rather than a fixed timer.

It digs **top-down, one layer at a time**, so it always stands on solid ground and never
drops itself into a pit. Mined drops are picked up automatically as it moves through the
cleared space.

### Setup

1. `#sel 1` / `#sel 2` around the region you want gone, then `#digger area`.
2. `/sethome dig` at a safe spot by the region, `/sethome Home` by your chests.
3. Select your tool chests and run `#digger supply`. Stock them with spare **pickaxes,
   shovels, food and empty buckets**.
4. *(optional)* For a separate, large spoil dump: `/sethome dump` by those chests,
   `#sel` them, `#digger dump`, then `#digger dump home dump`. Skip this to dump the
   spoil straight into the supply chests (one-home mode).
5. `#digger start`.

### Commands

| Command | Effect |
|---------|--------|
| `#digger start` / `stop` | run / halt |
| `#digger area [clear]` | capture the current selection as the region to excavate |
| `#digger supply [clear \| home <name>]` | tool/food/bucket chests (capture selection, or set their home) |
| `#digger dump [clear \| home <name>]` | spoil chests (optional; defaults to the supply chests) |
| `#digger pickaxe <n\|item>` / `shovel <n\|item>` / `food <n\|item>` | how many / which to keep stocked |
| `#digger buckets <n>` | empty buckets to keep for fluids (default `4`) |
| `#digger freeslots <n>` | haul spoil out at this many free slots (default `1`) |
| `#digger fluid on\|off` | bucket lava (and collect it) and drain water (default **on**) |
| `#digger breakmove on\|off` | let Baritone break blocks to reposition between spots (default **on**) |
| `#digger sethome on\|off` | move the `dig` home to the work face each trip (default **on**) |
| `#digger ender on\|off` | also service ender chests in the chest areas (default off) |
| `#digger work <name>` | the dig-site home name |

### Lava and water

With `fluid on` (the default) the digger handles fluids before they flood the dig:

- **Lava** is scooped with a bucket and **collected** — the lava buckets ride home as loot
  and are dropped into the dump chests.
- **Water** is scooped to **drain** it — removing the source block makes the flow recede;
  the water buckets are also deposited (refill empty buckets from the supply chests).

So stock plenty of **empty buckets**; filled buckets are treated as spoil and emptied at
the chests, and the digger restocks empties on its next supply run. With `fluid off` it
never opens a block next to a fluid source — it leaves those spots and reports them
instead, so it can't drown the dig.

### One home or two

It works with a single home/area (deposit spoil **and** withdraw tools from the same
chests) or two (withdraw at the `supply` home, dump spoil at the `dump` home). Set a dump
area to use two; leave it unset for one. Restocking is **wall-safe and never skips a
chest** — see **Shared concepts** above. If a tool, food or bucket runs out, or the
inventory fills up, it hauls out, services the chests and resumes exactly where it left off
(the `dig` home advances to the current face each trip, unless you set `sethome off`).

> Blocks it genuinely can't reach without breaking (with `breakmove off`), can't break
> (bedrock), or fluids it can't drain are **left in place and reported** rather than
> skipped silently.

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
    ├── common/   # Baritones, Teleporter, MenuActions, ContainerService, ChestRoute, SortPlan,
    │             # WorkerEquip (keep-list), AreaSelection (corner capture), ItemNames, ItemCategories
    ├── miner/    # MinerWorker, MinerConfig, MinerCommand, MinerState, Ores
    ├── lumber/   # LumberWorker, LumberConfig, LumberCommand, LumberState, Woods
    ├── sorter/   # SorterWorker, SorterConfig, SorterCommand, SortState, SortScheme
    ├── mover/    # MoverWorker, MoverConfig, MoverCommand, MoverState
    ├── builder/  # BuilderWorker, BuilderConfig, BuilderCommand, BuilderState
    └── digger/   # DiggerWorker, DiggerConfig, DiggerCommand, DiggerState
```

## License

[CC0-1.0](LICENSE) — public domain dedication. Do whatever you want with this code; no
attribution required.
