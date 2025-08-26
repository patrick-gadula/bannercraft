package com.patrick;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;

/**
 * Attempts to apply server-side entity scaling via the Attribute.SCALE if available.
 * This listener is safe to include on any server; if the SCALE attribute is
 * not present the code quietly no-ops.
 */
public class ScaleListener implements Listener {
    private final double scale;

    public ScaleListener() { this.scale = 0.5; }
    public ScaleListener(double scale) { this.scale = scale; }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent ev) {
        if (!(ev.getEntity() instanceof LivingEntity)) return;
        LivingEntity le = (LivingEntity) ev.getEntity();
        try {
            // Use valueOf to avoid compile-time dependency on an enum constant that may not exist
            Attribute a = Attribute.valueOf("SCALE");
            if (a != null) {
                var attr = le.getAttribute(a);
                if (attr != null) {
                    attr.setBaseValue(this.scale);
                }
            }
        } catch (IllegalArgumentException ignore) {
            // Attribute.SCALE not present on this server implementation
        } catch (Throwable ignore) {
            // Any other failure should not break spawning
        }
    }
}
