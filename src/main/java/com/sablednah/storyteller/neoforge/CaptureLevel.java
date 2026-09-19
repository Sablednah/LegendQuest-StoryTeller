package com.sablednah.storyteller.neoforge;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * A level that a structure can be built into without anything being built.
 *
 * <p><b>Why this exists.</b> Five vanilla structures — buried treasure,
 * mineshaft, nether fortress, ocean monument and stronghold — have no templates
 * at all. Their pieces lay blocks down one call at a time in code, so there was
 * nothing for the ghost to read and they could only ever be drawn as an
 * outline. This lets those pieces do exactly what they would do, into a map,
 * and hands back the blocks they laid. Every structure then draws the same way:
 * parity, which is what Sable asked for.</p>
 *
 * <p><b>Reads answer from the capture first.</b> Pieces look at what they have
 * already placed — a stronghold corridor asks whether its own wall is there
 * before it cuts a door through it — so a recorder that forwarded every read to
 * the real world would give the piece a different world from the one it is
 * building, and the preview would diverge from what actually lands. Captured
 * positions answer from the map; everything else falls through to the real
 * level, which is right: the structure is being previewed <em>into</em> this
 * terrain.</p>
 *
 * <p><b>Nothing may escape.</b> A preview that edited the world would be the
 * worst bug this mod could have, so every mutating door is shut, and shut
 * deliberately rather than by forwarding and hoping:</p>
 *
 * <ul>
 *   <li>{@code setBlock}, {@code removeBlock} and {@code destroyBlock} record.</li>
 *   <li>{@code getBlockEntity} returns <b>null</b>. This one is not tidiness:
 *       igloo, ocean ruin and mineshaft fetch a block entity and call
 *       {@code setLootTable} on it, so forwarding it would let a preview
 *       re-roll the loot of a real chest that happens to be standing there.</li>
 *   <li>{@code addFreshEntity} refuses, so the mansion's mobs and the ocean
 *       ruin's drowned are not spawned by looking at them.</li>
 *   <li>Ticks go to {@link BlackholeTickAccess}, vanilla's own bin for exactly
 *       this; sounds, particles, level events and game events are dropped.</li>
 * </ul>
 *
 * <p><b>The one door left open, named rather than hidden:</b> {@code getChunk}
 * hands back the real chunk, because reads need it. {@code StructurePiece.placeBlock}
 * calls {@code markPosForPostprocessing} on it for fences, torches, ladders and
 * iron bars, so a preview can mark a handful of positions in a real chunk for
 * shape-updating on next load. No block changes and nothing is lost, but it is
 * a write, and it is written down here because the alternative — wrapping
 * ChunkAccess too — is a second interface of this size for a bounded and
 * harmless effect. If it ever stops being harmless, that is the fix.</p>
 */
final class CaptureLevel implements WorldGenLevel {

    private final ServerLevel real;
    private final Long2ObjectMap<BlockState> captured = new Long2ObjectOpenHashMap<>();

    CaptureLevel(ServerLevel real) {
        this.real = real;
    }

    /** What the structure laid down, keyed by {@link BlockPos#asLong}. */
    Long2ObjectMap<BlockState> captured() {
        return captured;
    }

    // --- writes: recorded, never performed ----------------------------------

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
        captured.put(pos.asLong(), state);
        return true;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean isMoving) {
        captured.put(pos.asLong(), Blocks.AIR.defaultBlockState());
        return true;
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean dropBlock, Entity breaker, int recursion) {
        captured.put(pos.asLong(), Blocks.AIR.defaultBlockState());
        return true;
    }

    @Override
    public boolean addFreshEntity(Entity entity) {
        return false;
    }

    // --- reads: the capture first, then the real world ----------------------

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState mine = captured.get(pos.asLong());
        return mine != null ? mine : real.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        BlockState mine = captured.get(pos.asLong());
        return mine != null ? mine.getFluidState() : real.getFluidState(pos);
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> test) {
        return test.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> test) {
        return test.test(getFluidState(pos));
    }

    /** Null on purpose -- see the class note: this is what stops a preview re-rolling real loot. */
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    // --- dropped on the floor -----------------------------------------------

    @Override
    public void playSound(Entity source, BlockPos pos, SoundEvent sound, SoundSource category,
            float volume, float pitch) {
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z,
            double dx, double dy, double dz) {
    }

    @Override
    public void levelEvent(Entity source, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 position, GameEvent.Context context) {
    }

    @Override
    public void setCurrentlyGenerating(Supplier<String> what) {
    }

    @Override
    public LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public long nextSubTickCount() {
        return 0L;
    }

    // --- everything else is the real level ----------------------------------

    @Override
    public ChunkAccess getChunk(int x, int z) {
        return real.getChunk(x, z);
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean requireChunk) {
        return real.getChunk(x, z, status, requireChunk);
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return real.hasChunk(x, z);
    }

    @Override
    public boolean ensureCanWrite(BlockPos pos) {
        return true;
    }

    @Override
    public Player getNearestPlayer(double x, double y, double z, double distance, Predicate<Entity> test) {
        return real.getNearestPlayer(x, y, z, distance, test);
    }

    @Override
    public int getSkyDarken() {
        return real.getSkyDarken();
    }

    @Override
    public BiomeManager getBiomeManager() {
        return real.getBiomeManager();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return real.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return real.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return real.getLightEngine();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return real.getWorldBorder();
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public ServerLevel getLevel() {
        return real;
    }

    @Override
    public RegistryAccess registryAccess() {
        return real.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return real.enabledFeatures();
    }

    @Override
    public LevelData getLevelData() {
        return real.getLevelData();
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return real.getCurrentDifficultyAt(pos);
    }

    @Override
    public MinecraftServer getServer() {
        return real.getServer();
    }

    @Override
    public ChunkSource getChunkSource() {
        return real.getChunkSource();
    }

    @Override
    public long getSeed() {
        return real.getSeed();
    }

    @Override
    public int getSeaLevel() {
        return real.getSeaLevel();
    }

    @Override
    public RandomSource getRandom() {
        return real.getRandom();
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return real.getHeight(type, x, z);
    }

    @Override
    public DimensionType dimensionType() {
        return real.dimensionType();
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB area,
            Predicate<? super T> filter) {
        return real.getEntities(test, area, filter);
    }

    @Override
    public List<Entity> getEntities(Entity except, AABB area, Predicate<? super Entity> filter) {
        return real.getEntities(except, area, filter);
    }

    @Override
    public List<Player> players() {
        // A copy, because ServerLevel answers List<ServerPlayer> and that is not
        // a List<Player>. Nothing in structure generation asks for this, so the
        // allocation is theoretical; the alternative is an unchecked cast, and
        // this file is not the place to be clever.
        return new java.util.ArrayList<>(real.players());
    }

    @Override
    public int getMinY() {
        return real.getMinY();
    }

    @Override
    public int getHeight() {
        return real.getHeight();
    }

    @Override
    public EnvironmentAttributeReader environmentAttributes() {
        return real.environmentAttributes();
    }
}
