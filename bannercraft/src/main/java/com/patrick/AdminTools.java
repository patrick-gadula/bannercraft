package com.patrick;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Administrator testing tools: spawn troop eggs, swap sides, and create/reset arena.
 */
public class AdminTools implements Listener {
    private final BannerCraft plugin;
    private final ArenaManager arena;
    private final BattleManager battle;

    // toggle to determine whether spawn items place troops on player side or enemy side
    private final java.util.Map<UUID, Boolean> spawnOnEnemy = new java.util.HashMap<>();

    public AdminTools(BannerCraft plugin, ArenaManager arena, BattleManager battle) {
        this.plugin = plugin;
        this.arena = arena;
        this.battle = battle;
    }

    /** Give admin hotbar spawn items (call from a command) */
    public void giveAdminHotbar(Player p) {
        p.getInventory().clear();

        ItemStack zAxe = new ItemStack(Material.ZOMBIE_SPAWN_EGG);
        ItemMeta ma = zAxe.getItemMeta(); ma.setDisplayName("§cSpawn: Axe Zombie"); zAxe.setItemMeta(ma);

        ItemStack zSword = new ItemStack(Material.ZOMBIE_SPAWN_EGG);
        ItemMeta ms = zSword.getItemMeta(); ms.setDisplayName("§cSpawn: Sword+Shield Zombie"); zSword.setItemMeta(ms);

        ItemStack zSpear = new ItemStack(Material.ZOMBIE_SPAWN_EGG);
        ItemMeta mp = zSpear.getItemMeta(); mp.setDisplayName("§cSpawn: Spear Zombie"); zSpear.setItemMeta(mp);

        ItemStack zHorse = new ItemStack(Material.ZOMBIE_SPAWN_EGG);
        ItemMeta mh = zHorse.getItemMeta(); mh.setDisplayName("§cSpawn: Mounted Spear Zombie"); zHorse.setItemMeta(mh);

        ItemStack swap = new ItemStack(Material.BARRIER);
        ItemMeta msw = swap.getItemMeta(); msw.setDisplayName("§7Swap Side"); swap.setItemMeta(msw);

        p.getInventory().setItem(0, zAxe);
        p.getInventory().setItem(1, zSword);
        p.getInventory().setItem(2, zSpear);
        p.getInventory().setItem(3, zHorse);
        p.getInventory().setItem(8, swap);
    }

    @EventHandler
    public void onAdminInteract(PlayerInteractEvent ev) {
        if (ev.getAction() != Action.RIGHT_CLICK_AIR && ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = ev.getPlayer();
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it == null) return;
        ItemMeta im = it.getItemMeta();
        if (im == null || !im.hasDisplayName()) return;
        String dn = im.getDisplayName();
        ev.setCancelled(true);

        boolean placeOnEnemy = spawnOnEnemy.getOrDefault(p.getUniqueId(), false);
        Location target = placeOnEnemy ? arena.getEnemySideCenter() : arena.getPlayerSideCenter();

        if (dn.contains("Axe Zombie")) {
            battle.spawnAdminZombie(target, BattleManager.InfantryType.AXE);
            p.sendMessage("Spawned Axe Zombie at " + (placeOnEnemy ? "enemy" : "player") + " side.");
            return;
        }
        if (dn.contains("Sword+Shield")) {
            battle.spawnAdminZombie(target, BattleManager.InfantryType.SWORD_SHIELD);
            p.sendMessage("Spawned Sword+Shield Zombie.");
            return;
        }
        if (dn.contains("Spear Zombie")) {
            battle.spawnAdminZombie(target, BattleManager.InfantryType.SPEAR_SHIELD);
            p.sendMessage("Spawned Spear Zombie.");
            return;
        }
        if (dn.contains("Mounted")) {
            battle.spawnAdminZombie(target, BattleManager.InfantryType.MOUNTED_SPEAR);
            p.sendMessage("Spawned Mounted Spear Zombie.");
            return;
        }

        if (dn.contains("Swap Side")) {
            spawnOnEnemy.put(p.getUniqueId(), !placeOnEnemy);
            p.sendMessage("Spawn side toggled: now spawning on " + (spawnOnEnemy.get(p.getUniqueId()) ? "enemy" : "player") + " side.");
            return;
        }
    }
}
