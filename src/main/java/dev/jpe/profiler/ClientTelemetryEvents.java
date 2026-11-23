package dev.jpe.profiler;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Physical-client instrumentation kept separate so dedicated servers never load client classes. */
@EventBusSubscriber(modid = JpeProfiler.MOD_ID, value = Dist.CLIENT)
public final class ClientTelemetryEvents {
    private static final RuntimeTelemetry TELEMETRY = JpeProfiler.TELEMETRY;

    private ClientTelemetryEvents() {}

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        TELEMETRY.beginClientTick();
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        TELEMETRY.endClientTick();
    }

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        TELEMETRY.beginFrame();
    }

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        TELEMETRY.endFrame();
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        TELEMETRY.renderStage(event.getClass().getSimpleName());
    }
}
