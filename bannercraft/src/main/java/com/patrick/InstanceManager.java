package com.patrick;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;

public class InstanceManager {
    private final Map<UUID, BattleInstance> instances = new HashMap<>();

    public BattleInstance createAdminInstance(Location center) {
        BattleInstance bi = new BattleInstance(true, center);
        instances.put(bi.getId(), bi);
        return bi;
    }

    public BattleInstance get(UUID id) { return instances.get(id); }
}
