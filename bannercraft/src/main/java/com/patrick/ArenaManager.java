package com.patrick;

import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

public class ArenaManager {
    private World arenaWorld;
    private static final int PLATFORM_Y = 100;
    private static final int HALF = 15; // 31x31

    public void createArena() {
        if ((arenaWorld = Bukkit.getWorld("battle_world")) != null) return;

        WorldCreator wc = new WorldCreator("battle_world");
        wc.generator(new VoidGenerator());         // simple empty chunks
        wc.environment(World.Environment.NORMAL);  // doesn't matter for void
        arenaWorld = Bukkit.createWorld(wc);

        // 31x31 glass platform at Y=100
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                arenaWorld.getBlockAt(x, PLATFORM_Y, z).setType(Material.GLASS);
            }
        }
        arenaWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        arenaWorld.setPVP(true);
    }

    public Location getPlatformCenter() {
        return new Location(arenaWorld, 0.5, PLATFORM_Y + 1, 0.5);
    }

    public void sendToArena(Player p) { p.teleport(getPlatformCenter()); }

    public void sendBack(Player p) {
        World overworld = Bukkit.getWorlds().get(0);
        p.teleport(overworld.getSpawnLocation());
    }

    // Minimal void generator; deprecation warnings are fine for MVP
    static final class VoidGenerator extends ChunkGenerator {
        @Override
        public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
            return createChunkData(world); // empty chunk
        }
    }
}
