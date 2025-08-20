package com.patrick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

public class ArenaManager {
    private World arenaWorld;

    public static final int ARENA_SIZE = 100;      // 100 x 100
    private static final int HALF = ARENA_SIZE / 2;
    private static final int PLATFORM_Y = 100;

    // stored blocks so we can remove divider cleanly
    private final List<Block> dividerBlocks = new ArrayList<>();

    public void createArena() {
        if ((arenaWorld = Bukkit.getWorld("battle_world")) != null) return;

        WorldCreator wc = new WorldCreator("battle_world");
        wc.generator(new VoidGenerator());
        wc.environment(World.Environment.NORMAL);
        arenaWorld = Bukkit.createWorld(wc);

        // 100x100 grass platform (centered on 0,0)
        for (int x = -HALF; x < HALF; x++) {
            for (int z = -HALF; z < HALF; z++) {
                arenaWorld.getBlockAt(x, PLATFORM_Y, z).setType(Material.GRASS_BLOCK);
            }
        }
        arenaWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        arenaWorld.setPVP(true);

        arenaWorld.getEntities().forEach(ent -> {
            if (ent instanceof Mob) {
                ent.remove();
            }
        });
    }

    /** Midline red stained-glass divider (Z = 0 line, 3 blocks tall, see-through) */
    public void buildDivider() {
        removeDivider();
        for (int x = -HALF; x < HALF; x++) {
            for (int h = 1; h <= 3; h++) {
                Block b = arenaWorld.getBlockAt(x, PLATFORM_Y + h, 0);
                b.setType(Material.RED_STAINED_GLASS);
                dividerBlocks.add(b);
            }
        }
    }

    public void removeDivider() {
        if (dividerBlocks.isEmpty()) return;
        for (Block b : dividerBlocks) {
            if (b.getWorld().equals(arenaWorld)) b.setType(Material.AIR);
        }
        dividerBlocks.clear();
    }

    public Location getPlatformCenter() {
        return new Location(arenaWorld, 0.5, PLATFORM_Y + 1, 0.5);
    }

    /** Player spawn area center (south side, Z = -20) */
    public Location getPlayerSideCenter() {
        return new Location(arenaWorld, 0.5, PLATFORM_Y + 1, -20.5);
    }

    /** Enemy spawn area center (north side, Z = +20) */
    public Location getEnemySideCenter() {
        return new Location(arenaWorld, 0.5, PLATFORM_Y + 1, 20.5);
    }

    public void sendToArena(Player p) {
        // save player's current location so we can restore later
        savedPlayerLocations.put(p.getUniqueId(), p.getLocation());
        p.teleport(getPlayerSideCenter());
    }

    public void sendBack(Player p) {
        Location saved = savedPlayerLocations.remove(p.getUniqueId());
        if (saved != null) {
            p.teleport(saved);
            return;
        }
        World overworld = Bukkit.getWorlds().get(0);
        p.teleport(overworld.getSpawnLocation());
    }

    // saved player locations before sending them to the arena (player UUID -> Location)
    private final Map<UUID, Location> savedPlayerLocations = new HashMap<>();

    /** Minimal void generator (deprecated API is fine for MVP) */
    static final class VoidGenerator extends ChunkGenerator {
        @Override
        public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
            return createChunkData(world); // empty chunk
        }
    }
}
