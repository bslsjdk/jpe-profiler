package dev.jpe.profiler;

import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Game-bus instrumentation for both logical sides. Handlers only update in-memory counters. */
@EventBusSubscriber(modid = JpeProfiler.MOD_ID)
public final class GameTelemetryEvents {
    private static final RuntimeTelemetry TELEMETRY = JpeProfiler.TELEMETRY;

    private GameTelemetryEvents() {}

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        TELEMETRY.beginServerTick();
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        TELEMETRY.endServerTick();
    }

    @SubscribeEvent
    public static void onLevelTickPre(LevelTickEvent.Pre event) {
        TELEMETRY.beginLevelTick(event.getLevel());
    }

    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        TELEMETRY.endLevelTick();
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        TELEMETRY.entityTickCompleted();
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        TELEMETRY.entityJoined(event.loadedFromDisk());
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        TELEMETRY.entityLeft();
    }

    @SubscribeEvent
    public static void onEntitySectionChange(EntityEvent.EnteringSection event) {
        TELEMETRY.entityEnteredSection(event.didChunkChange());
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        TELEMETRY.chunkLoaded(event.isNewChunk());
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        TELEMETRY.chunkUnloaded();
    }

    @SubscribeEvent
    public static void onChunkDataLoad(ChunkDataEvent.Load event) {
        TELEMETRY.chunkDataLoaded();
    }

    @SubscribeEvent
    public static void onChunkDataSave(ChunkDataEvent.Save event) {
        TELEMETRY.chunkDataSaved();
    }

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        TELEMETRY.chunkWatched();
    }

    @SubscribeEvent
    public static void onChunkSent(ChunkWatchEvent.Sent event) {
        TELEMETRY.chunkSent();
    }

    @SubscribeEvent
    public static void onChunkUnwatch(ChunkWatchEvent.UnWatch event) {
        TELEMETRY.chunkUnwatched();
    }

    @SubscribeEvent
    public static void onChunkTicketChange(ChunkTicketLevelUpdatedEvent event) {
        TELEMETRY.chunkTicketChanged();
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        TELEMETRY.levelLoaded((Level) event.getLevel());
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        TELEMETRY.levelUnloaded((Level) event.getLevel());
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        TELEMETRY.levelSaved();
    }
}
