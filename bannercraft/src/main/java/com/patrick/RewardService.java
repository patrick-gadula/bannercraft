package com.patrick;

import java.util.Random;

import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

public class RewardService {
    private static final Random rnd = new Random();

    // Placeholder: 3% chance to drop a basic item (stone) per mob
    public static ItemStack rollDrop(Entity e) {
        if (rnd.nextInt(100) < 3) {
            return new ItemStack(org.bukkit.Material.STONE);
        }
        return null;
    }
}
