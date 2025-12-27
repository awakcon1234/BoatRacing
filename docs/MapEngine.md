# MapEngine integration (BoatRacing)

This project uses **MapEngine** as the future foundation for “GUI-like” rendering using Minecraft maps.

BoatRacing currently does **not** ship any MapEngine-backed gameplay yet; this document exists to:
- keep the build wired correctly (`compileOnly`)
- standardize how we obtain and use `MapEngineApi`
- provide copy/paste examples for the next tasks

## Build + runtime requirements

- Build dependency (already added):
  - Maven repo: `https://repo.minceraft.dev/releases/`
  - Artifact: `de.pianoman911:mapengine-api:1.8.11`
  - Scope: `compileOnly` (runtime comes from the MapEngine plugin)

- Server runtime:
  - You must install the **MapEngine** plugin jar on the server.
  - BoatRacing declares `softdepend: [MapEngine]` in `plugin.yml` so it loads after MapEngine when present.

If we later introduce **hard-required** MapEngine features, switch `softdepend` to `depend`.

## Getting the API

MapEngine exposes `MapEngineApi` via Bukkit Services:

```java
MapEngineApi api = Bukkit.getServicesManager().load(MapEngineApi.class);
if (api == null) {
    // MapEngine not installed/enabled
}
```

BoatRacing provides a small wrapper:
- [src/main/java/dev/belikhun/boatracing/integrations/mapengine/MapEngineService.java](../src/main/java/dev/belikhun/boatracing/integrations/mapengine/MapEngineService.java)

## Two display types (what to use when)

### 1) In-world multi-map screen (`IMapDisplay`)

Use this for “screens” made of item frames (multiple 128x128 maps tiled).

Creation API (from MapEngine Javadoc 1.8.11):

```java
IMapDisplay display = api.displayProvider().createBasic(cornerA, cornerB, facing);

display.spawn(viewer);   // show the display to a specific player
// display.despawn(viewer);
```

Notes:
- `IMapDisplay` is in `de.pianoman911.mapengine.api.clientside`.
- `spawn(player)` defaults to z-index 0; you can also use `spawn(player, z)`.
- When you are done with a display, call `despawn(player)` for all viewers and then `destroy()`.

Key points:
- It is *client-side*: MapEngine sends packets to a viewer.
- Great for spectator screens, track info boards, admin dashboards.

### 2) Holdable map (`IHoldableDisplay`)

Use this for a “HUD-like” single map that lives in the player’s hand.

Important difference:
- You typically **do not** call `spawn()`.
- You give the player an `ItemStack` created by MapEngine:

```java
IHoldableDisplay display = api.displayProvider().createHoldableDisplay();
ItemStack mapItem = display.itemStack(0);
player.getInventory().addItem(mapItem);
```

Notes:
- `IHoldableDisplay` is in `de.pianoman911.mapengine.api.clientside`.
- It is also an `IDisplay`, so it has `destroy()` for cleanup.

## Rendering: the pipeline + drawing space

The basic workflow:

1. Create a display (`IMapDisplay` or `IHoldableDisplay`)
2. Create a drawing input:

```java
IDrawingSpace input = api.pipeline().createDrawingSpace(display);
```

This comes from MapEngine Javadoc:
- `MapEngineApi.pipeline()` returns an `IPipelineProvider`.
- `IDrawingSpace` extends `IPipelineInput`, which provides `ctx()` and `flush()`.

3. Configure the pipeline context:

```java
input.ctx().addReceiver(viewer);
input.ctx().converter(Converter.FLOYD_STEINBERG); // optional
input.ctx().buffering(true);                      // optional
```

(`ctx().receivers().add(viewer)` is also valid; `addReceiver(...)` is the explicit API.)

4. Draw + flush:

```java
input.image(image, 0, 0);
input.flush();
```

## BoatRacing examples

BoatRacing includes reference code:
- [src/main/java/dev/belikhun/boatracing/integrations/mapengine/MapEngineExamples.java](../src/main/java/dev/belikhun/boatracing/integrations/mapengine/MapEngineExamples.java)

### Example: holdable “demo HUD” item

- Builds a 128x128 `BufferedImage`
- Draws simple text/shapes using Java2D
- Sends to the player via MapEngine pipeline
- Returns the `ItemStack` you can give to the player

### Example: in-world frame display

- Creates an `IMapDisplay` over an item-frame rectangle
- Calls `spawn(viewer)`

## Integration notes for the next task

- Treat MapEngine as **optional** until we officially require it.
- Keep any player-facing text Vietnamese, but dev docs/code comments can stay English.
- For per-race UI, always resolve race via `RaceService.findRaceFor(playerId)`; avoid global state.
