package live.minehub.polarpaper;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.generator.PolarGenerator;
import live.minehub.polarpaper.core.generator.PolarStreamingGenerator;
import live.minehub.polarpaper.core.source.BytesPolarSource;
import live.minehub.polarpaper.core.source.FilePolarSource;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.util.TaskFutures;
import live.minehub.polarpaper.core.world.*;
import live.minehub.polarpaper.nms.VersionUtil;
import live.minehub.polarpaper.util.EntitiesWorldAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@SuppressWarnings("unused")
public class Polar {

    private static final Logger LOGGER = LoggerFactory.getLogger(Polar.class);

    private static final Set<NamespacedKey> LOADING_WORLDS = new CopyOnWriteArraySet<>();
    private static final Map<NamespacedKey, ScheduledTask> AUTOSAVE_TASK_MAP = new ConcurrentHashMap<>();

    private Polar() {

    }

    public static FilePolarSource getDefaultFolderSource(String worldName) {
        Path pluginFolder = PolarPaper.getPlugin().getDataPath();
        Path worldsFolder = pluginFolder.resolve("worlds");
        Path path = worldsFolder.resolve(worldName + ".polar");
        return new FilePolarSource(path);
    }

    public static boolean isLoading(NamespacedKey worldKey) {
        return LOADING_WORLDS.contains(worldKey);
    }

    public static void setLoading(NamespacedKey worldKey, boolean loading) {
        if (loading) {
            LOADING_WORLDS.add(worldKey);
        } else {
            LOADING_WORLDS.remove(worldKey);
        }
    }

    /**
     * Load a polar world using the source defined in the config
     *
     * @param worldName The name of the world to load
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see Polar#getDefaultFolderSource(String)
     */
    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource source, @NotNull String worldName) {
        return createWorld(source, worldName, VersionUtil.getPolarFeaturesWorldAccess());
    }

    /**
     * Load a polar world with config read from config.yml and with the default PolarWorldAccess
     *
     * @param worldName The name for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     */
    public static CompletableFuture<@Nullable World> createWorld(PolarWorld polarWorld, @NotNull String worldName) {
        return createWorld(polarWorld, worldName, VersionUtil.getPolarFeaturesWorldAccess());
    }

    /**
     * Load a polar world with config read from config.yml
     *
     * @param polarSource The source to load the polar world from
     * @param worldName The name for the polar world
     * @param worldAccess Describes how userdata should be handled (default PolarWorldAccess.POLAR_PAPER_FEATURES)
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see Polar#getDefaultFolderSource(String)
     * @see BytesPolarSource
     * @see EntitiesWorldAccess
     */
    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource polarSource, @NotNull String worldName, @NotNull PolarWorldAccess worldAccess) {
        FileConfiguration fileConfig = PolarPaper.getPlugin().getConfig();
        Config config = Config.readFromConfig(fileConfig, worldName); // If world not in config, use defaults
        return createWorld(polarSource, worldName, config, worldAccess);
    }

    /**
     * Creates a polar world with config read from config.yml
     *
     * @param worldName The name for the polar world
     * @param worldAccess Describes how userdata should be handled (default PolarWorldAccess.POLAR_PAPER_FEATURES)
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see EntitiesWorldAccess
     */
    public static CompletableFuture<@Nullable World> createWorld(@NotNull PolarWorld polarWorld, @NotNull String worldName, @NotNull PolarWorldAccess worldAccess) {
        FileConfiguration fileConfig = PolarPaper.getPlugin().getConfig();
        Config config = Config.readFromConfig(fileConfig, worldName); // If world not in config, use defaults
        return createWorld(polarWorld, worldName, config, worldAccess);
    }

    /**
     * Creates a polar world with the default PolarWorldAccess
     *
     * @param polarSource The source to load the polar world from
     * @param worldName The name for the polar world
     * @param config Custom config for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see Polar#getDefaultFolderSource(String)
     * @see BytesPolarSource
     */
    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource polarSource, @NotNull String worldName, @NotNull Config config) {
        return createWorld(polarSource, worldName, config, VersionUtil.getPolarFeaturesWorldAccess());
    }

    /**
     * Creates a polar world with the default PolarWorldAccess
     *
     * @param worldName The name for the polar world
     * @param config Custom config for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     */
    public static CompletableFuture<@Nullable World> createWorld(@NotNull PolarWorld polarWorld, @NotNull String worldName, @NotNull Config config) {
        return createWorld(polarWorld, worldName, config, VersionUtil.getPolarFeaturesWorldAccess());
    }

    /**
     * Creates a polar world
     *
     * @param source The source to load the polar world from
     * @param worldName The name for the polar world
     * @param config Custom config for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see Polar#getDefaultFolderSource(String)
     * @see BytesPolarSource
     */
    public static CompletableFuture<@Nullable World> createWorld(@Nullable PolarSource source, @NotNull String worldName, @NotNull Config config, @NotNull PolarWorldAccess worldAccess) {
        byte[] worldBytes;
        try {
            worldBytes = source == null ? null : source.readBytes();
        } catch (Exception e) {
            LOGGER.error("Failed to load world " + worldName, e);
            return null;
        }

        PolarStreamingGenerator generator = new PolarStreamingGenerator(config, source, worldAccess);
        generator.deferLevelPreparation(true);
        return createWorld(generator, worldName).thenComposeAsync(world -> {
            if (world == null) return CompletableFuture.completedFuture(null);
            CompletableFuture<@Nullable World> loadedWorld;
            if (worldBytes != null && worldBytes.length > 0) {
                loadedWorld = PolarStreamLoader.stream(worldBytes, world, worldAccess)
                        .handle((_, ex) -> {
                            if (ex != null) {
                                LOGGER.error("Failed to load world " + worldName, ex);
                                return null;
                            }

                            return world;
                        });
            } else {
                loadedWorld = CompletableFuture.completedFuture(world);
            }
            return loadedWorld.thenCompose(loaded -> {
                if (loaded == null) return CompletableFuture.completedFuture(null);
                generator.enableEmptyChunkFallback();
                return prepareWorld(loaded);
            });
        }).whenComplete((result, ex) -> {
            if (ex != null || result == null) return;
            setLoading(result.getKey(), false);
            startAutoSaveTask(result, config);
        });
    }

    /**
     * Creates a polar world
     *
     * @param worldName The name for the polar world
     * @param config Custom config for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     */
    public static CompletableFuture<@Nullable World> createWorld(@NotNull PolarWorld polarWorld, @NotNull String worldName, @NotNull Config config, @NotNull PolarWorldAccess worldAccess) {
        PolarStreamingGenerator generator = new PolarStreamingGenerator(config, null, worldAccess);
        generator.setUserData(polarWorld.userData());
        generator.deferLevelPreparation(true);
        return createWorld(generator, worldName).thenComposeAsync(world -> {
            if (world == null) return CompletableFuture.completedFuture(null);
            ServerLevel level = ((CraftWorld) world).getHandle();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (PolarChunk chunk : polarWorld.chunks()) {
                NoUnloadLevelChunk levelChunk = chunk.createLevelChunk(level);

                futures.add(TaskFutures.runRegion(PolarPaper.getPlugin(), world, chunk.x(), chunk.z(), () -> {
                    for (PolarChunk.BlockEntity blockEntity : chunk.blockEntities()) {
                        PolarStreamLoader.addBlockEntity(blockEntity, levelChunk);
                    }
                    PolarStreamLoader.insertChunk(level, levelChunk);
                    worldAccess.loadChunkData(world, levelChunk, chunk.userData());
                    return true;
                }).handle((success, ex) -> {
                    if (ex != null) {
                        LOGGER.error("Failed to stream chunks in " + worldName, ex);
                        return null;
                    }
                    return null;
                }));
            }

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenComposeAsync(_ -> installPreLitBoundary(world, polarWorld))
                    .thenCompose(_ -> {
                        generator.enableEmptyChunkFallback();
                        return prepareWorld(world);
                    });
        }).whenComplete((world, ex) -> {
            if (world != null) {
                setLoading(world.getKey(), false);
                startAutoSaveTask(world, config);
            }
            if (ex != null) LOGGER.error("Failed to load world " + worldName, ex);
        });
    }

    /**
     * Creates a polar world
     *
     * @param generator Generator for the world
     * @param worldName The name for the polar world
     * @return CompletableFuture with the created bukkit world (completes immediately if not async)
     * @see EntitiesWorldAccess
     * @see PolarStreamingGenerator
     */
    public static CompletableFuture<@Nullable World> createWorld(@NotNull PolarGenerator generator, @NotNull String worldName) {
        worldName = worldName.toLowerCase().replace(" ", "_");

        NamespacedKey worldKey = NamespacedKey.fromString(worldName, PolarPaper.getPlugin());
        if (worldKey == null) {
            LOGGER.warn("Invalid world name '{}'", worldName);
            return CompletableFuture.completedFuture(null);
        }

        if (Bukkit.getWorld(worldKey) != null) {
            LOGGER.warn("A world with the name '{}' already exists, skipping.", worldName);
            return CompletableFuture.completedFuture(null);
        }

        Config config = generator.getConfig();

        WorldCreator worldCreator = WorldCreator.ofKey(worldKey)
                .type(config.worldType())
                .environment(config.environment())
                .generator(generator);

        CompletableFuture<@Nullable World> levelFuture;
        if (config.async()) {
            // Constructing ServerLevel also creates the per-world Spigot/Paper configuration.
            // Keep that work on Polar's async scheduler; the version adapter hands the
            // thread-bound registration and initialization back to the global scheduler.
            levelFuture = TaskFutures.runAsync(PolarPaper.getPlugin(),
                            () -> VersionUtil.createNoSaveLevel(worldCreator, config.spawn(), config.difficulty(), config.gamerules(), config.time()))
                    .thenCompose(future -> future);
        } else {
            levelFuture = VersionUtil.createNoSaveLevel(worldCreator, config.spawn(), config.difficulty(), config.gamerules(), config.time());
        }

        return levelFuture
                .whenComplete((world, ex) -> {
                    if (ex != null || world == null) {
                        if (ex == null) {
                            LOGGER.error("An error occurred loading polar world '" + worldKey.getKey() + "', skipping.");
                        } else {
                            LOGGER.error("An error occurred loading polar world '" + worldKey.getKey() + "', skipping.", ex);
                        }
                        return;
                    }

                    // Since saving is disabled in the level anyway, setAutoSave is now essentially setting whether
                    // chunks should be allowed to unload and be removed from memory
                    world.setAutoSave(false);
                });
    }

    private static CompletableFuture<@Nullable World> prepareWorld(@Nullable World world) {
        if (world == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<@Nullable World> future = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(PolarPaper.getPlugin(), () -> {
            try {
                CraftWorld craftWorld = (CraftWorld) world;
                craftWorld.getHandle().getServer().prepareLevel(craftWorld.getHandle());
                future.complete(world);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static CompletableFuture<Void> installPreLitBoundary(@NotNull World world, @NotNull PolarWorld polarWorld) {
        if (polarWorld.numChunks() == 0) return CompletableFuture.completedFuture(null);

        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        Set<Long> savedChunks = new HashSet<>(polarWorld.numChunks());
        for (PolarChunk chunk : polarWorld.chunks()) {
            minChunkX = Math.min(minChunkX, chunk.x());
            maxChunkX = Math.max(maxChunkX, chunk.x());
            minChunkZ = Math.min(minChunkZ, chunk.z());
            maxChunkZ = Math.max(maxChunkZ, chunk.z());
            savedChunks.add(chunkKey(chunk.x(), chunk.z()));
        }

        int padding = world.getViewDistance() + 2;
        int expectedBoundaryChunks = (maxChunkX - minChunkX + 1 + padding * 2)
                * (maxChunkZ - minChunkZ + 1 + padding * 2) - savedChunks.size();
        List<CompletableFuture<Void>> futures = new ArrayList<>(Math.max(0, expectedBoundaryChunks));
        ServerLevel level = ((CraftWorld) world).getHandle();

        for (int chunkX = minChunkX - padding; chunkX <= maxChunkX + padding; chunkX++) {
            for (int chunkZ = minChunkZ - padding; chunkZ <= maxChunkZ + padding; chunkZ++) {
                if (savedChunks.contains(chunkKey(chunkX, chunkZ))) continue;

                NoUnloadLevelChunk emptyChunk = new NoUnloadLevelChunk(level, new ChunkPos(chunkX, chunkZ));
                boolean[] emptySections = new boolean[emptyChunk.getSectionsCount()];
                Arrays.fill(emptySections, true);
                emptyChunk.starlight$setBlockEmptinessMap(emptySections.clone());
                emptyChunk.starlight$setSkyEmptinessMap(emptySections);
                emptyChunk.setLightCorrect(true);

                int finalChunkX = chunkX;
                int finalChunkZ = chunkZ;
                futures.add(TaskFutures.runRegion(PolarPaper.getPlugin(), world, finalChunkX, finalChunkZ, () -> {
                    PolarStreamLoader.insertChunk(level, emptyChunk);
                    return null;
                }));
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static void stopAutoSaveTask(NamespacedKey worldKey) {
        ScheduledTask prevTask = AUTOSAVE_TASK_MAP.get(worldKey);
        if (prevTask != null) prevTask.cancel();
    }

    public static void startAutoSaveTask(World world, Config config) {
        startAutoSaveTask(world, config.autoSaveIntervalTicks(), config.announceAutosave());
    }

    public static void startAutoSaveTask(World world, int autosaveIntervalTicks, boolean announceAutosave) {
        stopAutoSaveTask(world.getKey());

        if (autosaveIntervalTicks == -1) return;

        ScheduledTask autosaveTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(PolarPaper.getPlugin(), t -> {
            long before = System.nanoTime();
            String savingMsg = String.format("Autosaving '%s'...", world.getKey().getKey());
            LOGGER.info(savingMsg);
            if (announceAutosave) for (Player plr : Bukkit.getOnlinePlayers()) {
                if (!plr.hasPermission("polar.notifications")) continue;
                plr.sendMessage(Component.text(savingMsg, NamedTextColor.AQUA));
            }

            updateConfig(world, world.getKey().getKey()); // config should only be updated synchronously
            saveWorld(world)
                    .whenComplete((_, e) -> {
                        if (e != null) {
                            String errorMsg = String.format("Failed to save '%s', please check logs for error", world.getKey().getKey());
                            LOGGER.error(errorMsg, e);
                            for (Player plr : Bukkit.getOnlinePlayers()) {
                                if (!plr.hasPermission("polar.notifications")) continue;
                                plr.sendMessage(Component.text(errorMsg, NamedTextColor.RED));
                            }
                            return;
                        }

                        int ms = (int) ((System.nanoTime() - before) / 1_000_000);
                        String savedMsg = String.format("Saved '%s' in %sms", world.getKey().getKey(), ms);
                        LOGGER.info(savedMsg);
                        if (announceAutosave) for (Player plr : Bukkit.getOnlinePlayers()) {
                            if (!plr.hasPermission("polar.notifications")) continue;
                            plr.sendMessage(Component.text(savedMsg, NamedTextColor.AQUA));
                        }
                    });
        }, autosaveIntervalTicks, autosaveIntervalTicks);

        AUTOSAVE_TASK_MAP.put(world.getKey(), autosaveTask);
    }

    @SuppressWarnings("unchecked")
    private static <T> void setGameRule(World world, GameRule<?> rule, Object value) {
        world.setGameRule((GameRule<T>) rule, (T)value);
    }

    /**
     * Writes this world's properties to config (e.g. gamerules)
     * Should only be called synchronously
     */
    public static Config updateConfig(World world, String worldName) {
        PolarPaper.getPlugin().reloadConfig();
        FileConfiguration fileConfig = PolarPaper.getPlugin().getConfig();
        Config defaultConfig = Config.getDefaultConfig(fileConfig);
        Config newConfig = Config.readFromConfig(fileConfig, worldName, defaultConfig.toBuilder()).toBuilder().fromWorld(world).build(); // If world not in config, use defaults

        Config.writeToConfig(PolarPaper.getConfigPath(), fileConfig, worldName, newConfig);

        return newConfig;
    }

    /**
     * Reads the config for the world and updates the world's properties (e.g. gamerules)
     */
    public static void reloadConfig(World world) {
        PolarPaper.getPlugin().reloadConfig();

        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator == null) return;

        Config config = Config.readFromConfig(PolarPaper.getPlugin().getConfig(), world);

        generator.setConfig(config);

        world.setDifficulty(org.bukkit.Difficulty.valueOf(config.difficulty().name()));

        for (Map.Entry<String, Object> gamerule : config.gamerules().entrySet()) {
            NamespacedKey key = NamespacedKey.fromString(gamerule.getKey());
            if (key == null) continue;
            GameRule<?> rule = org.bukkit.Registry.GAME_RULE.get(key);
            if (rule == null) {
                LOGGER.warn("Invalid gamerule: {}", key.asMinimalString());
                continue;
            }
            setGameRule(world, rule, gamerule.getValue());
        }

        Polar.startAutoSaveTask(world, config);
    }

    /**
     * Saves a polar world asynchronously using the source used to load it
     * <br>
     * Will not save if a source was not used to load the world
     *
     * @param world The bukkit world (needs to be a polar world)
     * @see PolarGenerator#getSource()
     */
    public static CompletableFuture<Void> saveWorld(World world) {
        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator == null) return CompletableFuture.completedFuture(null);
        PolarSource source = generator.getSource();
        if (source == null) return CompletableFuture.completedFuture(null);
        return saveWorld(world, source);
    }

    /**
     * Saves a polar world asynchronously using the given source
     *
     * @param world The bukkit world (needs to be a polar world)
     * @param polarSource The source to use to save the polar world
     * @see Polar#getDefaultFolderSource(String)
     * @see BytesPolarSource
     */
    @SuppressWarnings("unused")
    public static CompletableFuture<Void> saveWorld(World world, PolarSource polarSource) {
        PolarGenerator generator = PolarGenerator.fromWorld(world);
        if (generator == null) return CompletableFuture.completedFuture(null);
        Collection<PolarChunk> extraChunks = generator.getPolarWorld() == null ? List.of() : generator.getPolarWorld().chunks();
        return saveWorld(world, extraChunks, polarSource, generator.getWorldAccess(), BlockSelector.ALL, generator.getConfig());
    }

    /**
     * Updates and saves a polar world asynchronously using the given source
     * <br>
     * The future is completed exceptionally if saving failed
     *
     * @param world The bukkit world to retrieve new chunks from
     * @param extraChunks Extra chunks to include in the saved file
     * @param polarSource The source to use to save the polar world
     * @param polarWorldAccess Describes how userdata should be handled (default PolarWorldAccess.POLAR_PAPER_FEATURES)
     * @param blockSelector Used to filter which blocks should be updated (essentially a crop)
     * @param config Custom config for the polar world
     * @see EntitiesWorldAccess
     * @see BlockSelector#ALL
     */
    public static CompletableFuture<Void> saveWorld(World world, Collection<PolarChunk> extraChunks, PolarSource polarSource, PolarWorldAccess polarWorldAccess, BlockSelector blockSelector, Config config) {
        if (Polar.isLoading(world.getKey())) return CompletableFuture.failedFuture(new IllegalStateException(world.getKey() + " is still loading"));

        CompletableFuture<PolarWorld> future;
        try {
            future = PolarWorld.convert(world, polarWorldAccess, blockSelector, config, extraChunks, false);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        return future.thenAcceptAsync(newPolarWorld -> {
            byte[] worldBytes = PolarWriter.write(newPolarWorld);
            try {
                polarSource.saveBytes(worldBytes);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

}
