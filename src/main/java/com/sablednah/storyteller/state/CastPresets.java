package com.sablednah.storyteller.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Saved cast members — "the innkeeper", "the ambush leader" — kept by name so
 * a Storyteller can bring one back instantly rather than re-typing an entity
 * type, a display name and a behaviour every time the story needs them again.
 *
 * <p>Mirrors LegendQuest's own {@code Parties}: one record per save, stored
 * on the overworld, a {@code SavedDataType} rather than in-memory — a cast
 * built up over a campaign is worth more than a server restart.</p>
 */
public final class CastPresets extends SavedData {

    public record Preset(Identifier entityType, Optional<String> name, Optional<String> behaviour) {
        public static final Codec<Preset> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("entity_type").forGetter(Preset::entityType),
                Codec.STRING.optionalFieldOf("name").forGetter(Preset::name),
                Codec.STRING.optionalFieldOf("behaviour").forGetter(Preset::behaviour))
                .apply(i, Preset::new));

        public Optional<EntityType<?>> resolveType() {
            return BuiltInRegistries.ENTITY_TYPE.getOptional(entityType);
        }
    }

    private record Named(String name, Preset preset) {
        static final Codec<Named> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Named::name),
                Preset.CODEC.fieldOf("preset").forGetter(Named::preset))
                .apply(i, Named::new));
    }

    private static final Codec<CastPresets> CODEC = Named.CODEC.listOf()
            .xmap(CastPresets::new, cp -> cp.presets.entrySet().stream()
                    .map(e -> new Named(e.getKey(), e.getValue())).toList())
            .fieldOf("cast").codec();

    public static final SavedDataType<CastPresets> TYPE =
            new SavedDataType<>(Identifier.fromNamespaceAndPath(
                    com.sablednah.storyteller.StoryTeller.MODID, "cast"), CastPresets::new, CODEC, null);

    /** Lowercased preset name → preset. */
    private final Map<String, Preset> presets;

    private CastPresets() {
        this.presets = new LinkedHashMap<>();
    }

    private CastPresets(List<Named> entries) {
        this.presets = new LinkedHashMap<>();
        entries.forEach(n -> presets.put(n.name().toLowerCase(Locale.ROOT), n.preset()));
    }

    public static CastPresets get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public void save(String name, Preset preset) {
        presets.put(name.toLowerCase(Locale.ROOT), preset);
        setDirty();
    }

    public Optional<Preset> get(String name) {
        return Optional.ofNullable(presets.get(name.toLowerCase(Locale.ROOT)));
    }

    public boolean remove(String name) {
        boolean removed = presets.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) setDirty();
        return removed;
    }

    public List<String> names() {
        return new ArrayList<>(presets.keySet());
    }
}
