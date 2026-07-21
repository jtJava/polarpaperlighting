# Polar world lighting investigation

This note summarizes the investigation and patch for excessive Starlight work while loading Polar arena worlds on Paper/Folia 1.21.11 (UniverseSpigot build `e0ec609`). It is intended as a review handoff for Emortal.

## Symptom

Spark profiles showed Paper common workers spending substantial time in:

```text
ChunkLightTask$LightTask.getAsBoolean
  StarLightInterface.lightChunk
    SkyStarLightEngine.lightChunk
      StarLightEngine.performLightIncrease
```

The supplied `crystal-nether (1).polar` file was Polar format v7, data version 4671, Zstd compressed, and contained 650 chunks. All 650 chunks contained stored light. Production diagnostics later confirmed the same pattern for every tested arena: all saved chunks contained light and Polar itself calculated zero of them during loading.

## Paper behavior

`ChunkLightTask.LightTask#getAsBoolean` takes one of two paths:

- If `fromChunk.isLightCorrect()` is true and its persisted status is at least `LIGHT`, Paper loads the existing light and checks edges.
- Otherwise Paper calls `lightChunk`, which caused the expensive profile.

Two independent causes allowed the second path.

### 1. World preparation raced Polar installation

Both version-specific `NoSaveLevelCreatorImpl` implementations called `MinecraftServer#prepareLevel` before the asynchronous Polar chunk installation completed. Paper could therefore request a chunk holder while it still contained an incomplete placeholder.

The patch marks only Polar's built-in streaming generator for deferred preparation. The normal public `Polar#createWorld(PolarGenerator, String)` signature and the version adapter signatures remain unchanged. The `PolarSource` and in-memory `PolarWorld` flows now:

1. Create and register the world without calling `prepareLevel`.
2. Restore light and install every saved chunk.
3. Run `prepareLevel` on the global region scheduler.

Production measurements showed deferred `prepareLevel` taking 0-4 ms and loading zero persistent chunks.

### 2. Player view distance generated thousands of chunks outside the saved map

Saved arena maps are often much smaller than the server view distance. Examples observed in production:

- An 80-chunk arena generated at least 1,100 fallback chunks.
- A 256-chunk arena generated at least 900 fallback chunks.
- A 650-chunk arena generated at least 600 fallback chunks.
- Four arenas generated at least 3,200 fallback chunks during a short sample.

Although these chunks contain only air, each fresh proto-chunk normally advances through Paper's LIGHT status and invokes Starlight. The aggregate cost was significant.

For the in-memory `PolarWorld` path used by the Arena server, the patch installs a rectangular boundary of empty, light-correct FULL chunks before completing world creation. The boundary extends by `world.getViewDistance() + 2` chunks around the saved chunk bounds. The extra two chunks cover generation/light dependencies beyond the client-visible area.

In production, padding was 14 chunks. Depending on arena size, 1,176-1,744 boundary chunks were installed in 22-37 ms. Total Arena world load time remained approximately 133-157 ms, and no fallback generation appeared within the tested player-visible area.

The boundary optimization currently applies only to the in-memory `PolarWorld` overload used by the live Arena server. The byte-stream `PolarSource` flow receives the preparation-race fix but does not install a finite boundary.

## Arbitrary missing-chunk fallback

The finite boundary keeps the ordinary arena view cheap, but it cannot cover a player or plugin that requests chunks beyond the padded rectangle. Polar now also replaces the vanilla NMS generator delegate for `PolarStreamingGenerator` worlds with a version-specific pre-lit fallback.

At Paper's `NOISE` generation step, the fallback creates a fresh coordinate-specific `NoUnloadLevelChunk`, marks it non-saving and light-correct, installs all-empty Starlight section maps, primes its heightmaps, and returns it as a read-only `ImposterProtoChunk`. A `LevelChunk` reports persisted status `FULL`, so Moonrise's later `ChunkLightTask` takes the existing-light path (`forceLoadInChunk` plus edge checks) rather than the full `lightChunk` path that caused the bulk propagation. Edge reconciliation can still perform bounded propagation where adjacent stored light disagrees. `ChunkFullTask` already recognizes `ImposterProtoChunk` and unwraps the contained `LevelChunk` instead of constructing a second chunk.

This is an empty-chunk *template path*, not one shared chunk object: every requested coordinate needs its own chunk because positions, holders, lifecycle state, and future block changes are coordinate-specific. Empty skylight uses Starlight's implicit representation, so allocating full-bright nibble arrays for every fallback is unnecessary.

The Bukkit generator exposes the fallback only after the version adapter is bound to the constructed `ServerLevel` and Polar has installed the saved chunks (and finite boundary, where applicable). Before that activation gate, `shouldGenerateNoise` remains false. This prevents a concurrent request from asking the fallback to use an unbound level or replacing a saved Polar chunk during installation.

## Chunk-holder insertion changes

Saved and boundary chunks must be completely initialized before Moonrise can schedule work against their holders. `PolarStreamLoader#insertChunk` now:

- Acquires `ticketLockArea` and then `schedulingLockArea`, matching Moonrise's canonical lock order.
- Keeps both locks while assigning `currentChunk`, `currentGenStatus`, the holder link, and every completion through FULL.
- Refuses insertion if the holder already has a generation task or requested generation status.
- Releases the scheduling lock before the ticket lock, including exception paths.

Stored Starlight nibble arrays and emptiness maps are restored, and `lightCorrect` is set before the holder is exposed.

An earlier approach attempted to cancel a holder generation task. It was removed because cancellation is a no-op once execution begins and the completion could later overwrite the Polar chunk. An extra `isLightCorrect` field was also removed because Paper's `ChunkAccess` field is already volatile.

## Rejected Bukkit-only fallback approach

A prototype marked fallback proto-chunks as persisted `LIGHT` inside Bukkit's `generateNoise` callback. This is not durable: `ChunkUpgradeGenericStatusTask` overwrites the proto-chunk's persisted status after each later generation step, including `INITIALIZE_LIGHT`. The final arbitrary fallback instead replaces the chunk object from the wrapped NMS generator's `fillFromNoise`, which is the first hook where Paper preserves a replacement `ChunkAccess`. Pre-installing FULL boundary chunks remains the zero-task fast path for nearby chunks.

Do not call mapped helpers such as `ChunkPos.pack(int, int)` from shared plugin code. That method compiled against the development mappings but was absent under the production UniverseSpigot runtime mappings. The final implementation uses local bit packing for coordinate-set keys.

## API compatibility

No existing public method signatures or call contracts were changed. The original `NoSaveLevelCreator`, `VersionUtil`, `TaskFutures`, and `Polar#createWorld` signatures remain intact. A small state flag was added to `PolarStreamingGenerator` so the version-specific world creators can distinguish Polar's deferred internal flow from ordinary custom-generator calls.

## Async world configuration regression

A later profile showed `SpigotWorldConfig`, `PaperConfigurations#createWorldConfig`, and the rest of `ServerLevel` construction running on the server thread even for worlds configured with `async: true`. This was a Polar regression rather than a Paper configuration-cache problem.

Commit `f809c64` originally made the option dispatch world construction to Bukkit's async scheduler and returned only registration and initialization to the server thread. Commit `6a07f99` removed the `config.async()` dispatch and moved `new ServerLevel(...)` inside the global-thread supplier. The multi-version world creators retained that behavior, leaving the config property readable and writable but functionally unused.

The follow-up fix restores the intended split without sharing configuration between worlds:

1. `Polar#createWorld(PolarGenerator, String)` checks that world's existing `Config#async` value.
2. With async loading enabled, it invokes the version-specific level creator on Polar's async scheduler.
3. The version-specific creator constructs that world's `ServerLevel` on the caller thread. Paper creates a distinct Spigot and Paper world configuration during this constructor.
4. If construction occurred asynchronously, only `addLevel`, `initWorld`, spawn setup, and optional preparation are handed to the global scheduler.

No configuration instance is cached or reused. Async construction remains opt-in because the entire `ServerLevel` constructor, not only its configuration subcalls, runs off-thread; this matches the behavior and warning attached to the original experimental option.

## Validation

- `compileJava` succeeds for core, Paper 1.21.11, Paper 26.1.2, and latest Paper modules.
- The Gradle test lifecycle succeeds; the repository currently has no test sources.
- `shadowJar` succeeds.
- `git diff --check` succeeds.
- Production logs confirmed stored light for all tested saved chunks, successful boundary installation, no holder-race exceptions, and no fallback chunks inside the padded area.
- The arbitrary NMS fallback compiles against both maintained version adapters and has been traced against Paper's `CustomChunkGenerator`, `ChunkLightTask`, `ImposterProtoChunk`, and `ChunkFullTask` implementations. It still needs a production Spark comparison beyond the finite boundary.

## Review considerations

- Pre-lit boundaries trade memory for avoiding asynchronous lighting. The live server already generated a similar number of fallback chunks after players joined, but the patch creates the full padded rectangle up front.
- `PolarStreamLoader#insertChunk` currently primes heightmaps and initializes entity-chunk state for boundary chunks too. If memory or non-lighting worker cost becomes material, a specialized empty-boundary insertion path could avoid unnecessary initialization after verifying packet and unload behavior.
- Moving beyond the padded boundary still schedules the normal chunk status pipeline, but the new NMS fallback supplies an already-light-correct FULL empty chunk. Expect lightweight existing-light loading and edge checks there instead of `SkyStarLightEngine#lightChunk`; edge mismatches can still cause bounded Starlight propagation.
- Consider generalizing boundary installation to the byte-stream `PolarSource` path if that path is used in production.
