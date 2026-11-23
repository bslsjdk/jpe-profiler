package dev.jpe.profiler;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the independent JPE diagnostics mod. */
@Mod(JpeProfiler.MOD_ID)
public final class JpeProfiler {
    public static final String MOD_ID = "jpeprofiler";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final RuntimeTelemetry TELEMETRY = new RuntimeTelemetry();

    public JpeProfiler() {
        LOGGER.info("JPE Profiler {} initialized; runtime telemetry output: {}",
                "0.2.0", TELEMETRY.outputDirectory());
        Runtime.getRuntime().addShutdownHook(
                new Thread(TELEMETRY::close, "jpe-profiler-shutdown"));
    }
}
