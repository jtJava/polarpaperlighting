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

The boundary optimization currently applies only to the in-memory `PolarWorld` overload used by the live Arena server. The byte-stream `PolarSource` flow receives the preparation-race fix but does not currently install boundary chunks.

## Chunk-holder insertion changes

Saved and boundary chunks must be completely initialized before Moonrise can schedule work against their holders. `PolarStreamLoader#insertChunk` now:

- Acquires `ticketLockArea` and then `schedulingLockArea`, matching Moonrise's canonical lock order.
- Keeps both locks while assigning `currentChunk`, `currentGenStatus`, the holder link, and every completion through FULL.
- Refuses insertion if the holder already has a generation task or requested generation status.
- Releases the scheduling lock before the ticket lock, including exception paths.

Stored Starlight nibble arrays and emptiness maps are restored, and `lightCorrect` is set before the holder is exposed.

An earlier approach attempted to cancel a holder generation task. It was removed because cancellation is a no-op once execution begins and the completion could later overwrite the Polar chunk. An extra `isLightCorrect` field was also removed because Paper's `ChunkAccess` field is already volatile.

## Rejected fallback approach

A prototype marked fallback proto-chunks as persisted `LIGHT` inside Bukkit's `generateNoise` callback. This is not durable: `ChunkUpgradeGenericStatusTask` overwrites the proto-chunk's persisted status after each later generation step, including `INITIALIZE_LIGHT`. Pre-installing FULL boundary chunks avoids that race.

Do not call mapped helpers such as `ChunkPos.pack(int, int)` from shared plugin code. That method compiled against the development mappings but was absent under the production UniverseSpigot runtime mappings. The final implementation uses local bit packing for coordinate-set keys.

## API compatibility

No existing public method signatures or call contracts were changed. The original `NoSaveLevelCreator`, `VersionUtil`, `TaskFutures`, and `Polar#createWorld` signatures remain intact. A small state flag was added to `PolarStreamingGenerator` so the version-specific world creators can distinguish Polar's deferred internal flow from ordinary custom-generator calls.

## Validation

- `compileJava` succeeds for core, Paper 1.21.11, Paper 26.1.2, and latest Paper modules.
- The Gradle test lifecycle succeeds; the repository currently has no test sources.
- `shadowJar` succeeds.
- `git diff --check` succeeds.
- Production logs confirmed stored light for all tested saved chunks, successful boundary installation, no holder-race exceptions, and no fallback chunks inside the padded area.

## Review considerations

- Pre-lit boundaries trade memory for avoiding asynchronous lighting. The live server already generated a similar number of fallback chunks after players joined, but the patch creates the full padded rectangle up front.
- `PolarStreamLoader#insertChunk` currently primes heightmaps and initializes entity-chunk state for boundary chunks too. If memory or non-lighting worker cost becomes material, a specialized empty-boundary insertion path could avoid unnecessary initialization after verifying packet and unload behavior.
- The padding assumes arena players remain within the saved map bounds. Moving farther than the padded boundary will resume normal empty chunk generation and lighting.
- Consider generalizing boundary installation to the byte-stream `PolarSource` path if that path is used in production.
