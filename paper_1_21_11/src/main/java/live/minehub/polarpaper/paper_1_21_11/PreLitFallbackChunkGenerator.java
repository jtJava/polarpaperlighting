package live.minehub.polarpaper.paper_1_21_11;

import com.mojang.serialization.MapCodec;
import live.minehub.polarpaper.core.world.NoUnloadLevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Replaces newly generated void chunks with pre-lit full chunks at the first
 * generation step which permits a generator to return a different chunk.
 */
final class PreLitFallbackChunkGenerator extends ChunkGenerator {
    private final ChunkGenerator delegate;
    private volatile ServerLevel level;

    PreLitFallbackChunkGenerator(ChunkGenerator delegate) {
        super(delegate.getBiomeSource(), delegate.generationSettingsGetter);
        this.delegate = delegate;
    }

    void bind(ServerLevel level) {
        if (this.level != null && this.level != level) {
            throw new IllegalStateException("Fallback chunk generator is already bound to another level");
        }
        this.level = level;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        // Polar levels do not serialize their vanilla generator state.
        return MapCodec.unit(this);
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk) {
        this.delegate.applyCarvers(region, seed, randomState, biomeManager, structureManager, chunk);
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState randomState,
                             ChunkAccess protoChunk) {
        this.delegate.buildSurface(level, structureManager, randomState, protoChunk);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion worldGenRegion) {
        this.delegate.spawnOriginalMobs(worldGenRegion);
    }

    @Override
    public int getGenDepth() {
        return this.delegate.getGenDepth();
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                         StructureManager structureManager, ChunkAccess centerChunk) {
        if (!(centerChunk instanceof ProtoChunk protoChunk)) {
            return this.delegate.fillFromNoise(blender, randomState, structureManager, centerChunk);
        }

        ServerLevel boundLevel = Objects.requireNonNull(this.level, "Fallback chunk generator is not bound to a level");
        NoUnloadLevelChunk emptyChunk = new NoUnloadLevelChunk(boundLevel, protoChunk, null);
        emptyChunk.needsDecoration = false;
        emptyChunk.mustNotSave = true;

        boolean[] emptySections = new boolean[emptyChunk.getSectionsCount()];
        Arrays.fill(emptySections, true);
        emptyChunk.starlight$setBlockEmptinessMap(emptySections.clone());
        emptyChunk.starlight$setSkyEmptinessMap(emptySections);
        emptyChunk.setLightCorrect(true);
        Heightmap.primeHeightmaps(emptyChunk, EnumSet.allOf(Heightmap.Types.class));

        return CompletableFuture.completedFuture(new ImposterProtoChunk(emptyChunk, false));
    }

    @Override
    public int getSeaLevel() {
        return this.delegate.getSeaLevel();
    }

    @Override
    public int getMinY() {
        return this.delegate.getMinY();
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor,
                             RandomState randomState) {
        return this.delegate.getBaseHeight(x, z, type, heightAccessor, randomState);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
        return this.delegate.getBaseColumn(x, z, heightAccessor, randomState);
    }

    @Override
    public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos) {
        this.delegate.addDebugScreenInfo(result, randomState, feetPos);
    }
}
