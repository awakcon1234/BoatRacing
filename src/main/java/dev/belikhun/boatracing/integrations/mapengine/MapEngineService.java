package dev.belikhun.boatracing.integrations.mapengine;

import de.pianoman911.mapengine.api.MapEngineApi;
import org.bukkit.Bukkit;

/**
 * Tiny wrapper to access MapEngine via Bukkit ServicesManager.
 *
 * MapEngine is a separate plugin at runtime.
 */
public final class MapEngineService {
    private static volatile MapEngineApi cached;

    private MapEngineService() {}

    /**
     * @return MapEngineApi instance if MapEngine is installed + enabled; otherwise null.
     */
    public static MapEngineApi get() {
        MapEngineApi api = cached;
        if (api != null) return api;

        try {
            api = Bukkit.getServicesManager().load(MapEngineApi.class);
        } catch (Throwable ignored) {
            api = null;
        }

        if (api != null) cached = api;
        return api;
    }

    public static boolean isAvailable() {
        return get() != null;
    }
}
