package com.patrick;

import java.util.UUID;

import org.bukkit.Location;

/**
 * Small data carrier for a battle instance. Kept minimal for now.
 */
public class BattleInstance {
    private final UUID id = UUID.randomUUID();
    private final boolean admin;
    private final Location center;

    public BattleInstance(boolean admin, Location center) {
        this.admin = admin;
        this.center = center;
    }

    public UUID getId() { return id; }
    public boolean isAdmin() { return admin; }
    public Location getCenter() { return center; }
}
