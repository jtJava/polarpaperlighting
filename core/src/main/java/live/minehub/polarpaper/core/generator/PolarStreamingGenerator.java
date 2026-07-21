package live.minehub.polarpaper.core.generator;

import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.userdata.WorldUserData;
import live.minehub.polarpaper.core.world.PolarWorld;
import live.minehub.polarpaper.core.world.PolarWorldAccess;
import net.kyori.adventure.builder.AbstractBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;

import java.util.Random;

public class PolarStreamingGenerator extends PolarGenerator {
    private Short version = null;
    private Integer dataVersion = null;
    private byte[] userData = new byte[0];
    private boolean deferLevelPreparation;
    private volatile boolean emptyChunkFallbackInstalled;
    private volatile boolean emptyChunkFallbackEnabled;
    public PolarStreamingGenerator(Config config, PolarSource source, PolarWorldAccess worldAccess) {
        super(config, source, worldAccess);
    }

    @Override
    public @Nullable PolarWorld getPolarWorld() {
        return null;
    }

    public byte[] getUserData() {
        return userData;
    }

    public void setUserData(byte[] userData) {
        this.userData = userData;
    }

    public void setVersion(Short version) {
        this.version = version;
    }

    public Short getVersion() {
        return version;
    }

    public Integer getDataVersion() {
        return dataVersion;
    }

    public boolean deferLevelPreparation() {
        return deferLevelPreparation;
    }

    public void deferLevelPreparation(boolean deferLevelPreparation) {
        this.deferLevelPreparation = deferLevelPreparation;
    }

    /**
     * Enables Polar's internal, pre-lit empty chunk fallback after the saved
     * chunks have been installed. The version adapter supplies the matching
     * NMS generator delegate.
     */
    public void enableEmptyChunkFallback() {
        this.emptyChunkFallbackEnabled = true;
    }

    /**
     * Called by a version adapter after it has replaced Paper's vanilla noise
     * delegate with Polar's empty chunk fallback.
     */
    public void markEmptyChunkFallbackInstalled() {
        this.emptyChunkFallbackInstalled = true;
    }

    @Override
    public boolean shouldGenerateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return this.emptyChunkFallbackEnabled && this.emptyChunkFallbackInstalled;
    }

    public void setDataVersion(Integer dataVersion) {
        this.dataVersion = dataVersion;
    }

    @Override
    public Component getInfoComponent(World world) {
        byte[] userData = getUserData();
        Vector3i offset = WorldUserData.readSchematicOffset(userData);

        TextComponent.Builder builder = Component.text()
                .append(Component.text("Info for ", NamedTextColor.AQUA))
                .append(Component.text(world.getKey().getKey(), NamedTextColor.AQUA))
                .append(Component.text(":", NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text(" Version: ", NamedTextColor.AQUA))
                .append(Component.text(getVersion(), NamedTextColor.AQUA))
                .append(Component.text(" (", NamedTextColor.AQUA))
                .append(Component.text(getDataVersion(), NamedTextColor.AQUA))
                .append(Component.text(")", NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text(" Compression: ", NamedTextColor.AQUA))
                .append(Component.text(getConfig().compression().name(), NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text(" Source: ", NamedTextColor.AQUA))
                .append(Component.text(getSource() == null ? "None" : getSource().getClass().getSimpleName(), NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text(" Generator: STREAMING", NamedTextColor.AQUA))
                .appendNewline()
                .append(Component.text(" Spawn: ", NamedTextColor.AQUA))
                .append(Component.text(getConfig().spawnString(), NamedTextColor.AQUA));

        if (offset != null) {
            builder.appendNewline();
            builder.append(Component.text(" Schematic center: ", NamedTextColor.AQUA));
            builder.append(Component.text(offset.x + ", " + offset.y + ", " + offset.z, NamedTextColor.AQUA));
        }

        return ((AbstractBuilder<TextComponent>)builder).build();
    }

    @Override
    public boolean isParallelCapable() {
        return true;
    }

}
