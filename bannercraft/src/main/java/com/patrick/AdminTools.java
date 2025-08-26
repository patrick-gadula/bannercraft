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
    // track whether a player currently has Admin hotbar active
    private final java.util.Set<UUID> adminHotbarActive = new java.util.HashSet<>();

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

        // provide a way to switch to the normal setup tools
        ItemStack normal = new ItemStack(Material.STICK);
        ItemMeta mn = normal.getItemMeta(); mn.setDisplayName("§aSwitch: Normal Tools"); normal.setItemMeta(mn);
        p.getInventory().setItem(7, normal);
    adminHotbarActive.add(p.getUniqueId());
    }

    /** Give the normal (non-admin) formation setup tools hotbar */
    public void giveNormalHotbar(Player p) {
        p.getInventory().clear();
        // reuse BattleManager.giveCommandSticks to provide primary selectors
        battle.giveCommandSticks(p);
        // provide subclass items in slot 5/6 so they can continue setup (optional)
        ItemStack back = new ItemStack(Material.GRAY_DYE);
        ItemMeta bm = back.getItemMeta(); bm.setDisplayName("§7Back"); back.setItemMeta(bm);
        p.getInventory().setItem(8, back);
    // provide a way to switch back to Admin tools
    ItemStack admin = new ItemStack(Material.STICK);
    ItemMeta am = admin.getItemMeta(); am.setDisplayName("§aSwitch: Admin Tools"); admin.setItemMeta(am);
    p.getInventory().setItem(7, admin);
    // keep adminHotbarActive as true so admin can always return to admin tools
    adminHotbarActive.add(p.getUniqueId());
    }

    public boolean isAdminHotbarActive(UUID playerId) {
        return adminHotbarActive.contains(playerId);
    }

    public boolean isSpawningOnEnemy(UUID playerId) {
        return spawnOnEnemy.getOrDefault(playerId, false);
    }

    public void returnToAdminHotbar(Player p) {
        giveAdminHotbar(p);
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

        // Restrict admin tools to the arena world only
        if (!arena.isArenaWorld(p.getWorld())) {
            p.sendMessage("§cAdmin tools can only be used inside the battle arena.");
            return;
        }

        boolean placeOnEnemy = spawnOnEnemy.getOrDefault(p.getUniqueId(), false);
        Location target = placeOnEnemy ? arena.getEnemySideCenter() : arena.getPlayerSideCenter();

        if (dn.contains("Axe Zombie")) {
            battle.spawnAdminZombie(target, BattleManager.InfantryType.AXE, placeOnEnemy);
            p.sendMessage("Spawned Axe Zombie at " + (placeOnEnemy ? "enemy" : "player") + " side.");
            return;
        }
        if (dn.contains("Sword+Shield")) {
            battle.spawnAdminZombie(target, BattleManager.InfantryType.SWORD_SHIELD, placeOnEnemy);
            p.sendMessage("Spawned Sword+Shield Zombie.");
            return;
        }
        if (dn.contains("Spear Zombie")) {
            battle.spawnAdminZombie(target, BattleManager.InfantryType.SPEAR_SHIELD, placeOnEnemy);
            p.sendMessage("Spawned Spear Zombie.");
            return;
        }
        if (dn.contains("Mounted")) {
            battle.spawnAdminZombie(target, BattleManager.InfantryType.MOUNTED_SPEAR, placeOnEnemy);
            p.sendMessage("Spawned Mounted Spear Zombie.");
            return;
        }

        if (dn.contains("Swap Side")) {
            spawnOnEnemy.put(p.getUniqueId(), !placeOnEnemy);
            boolean nowEnemy = spawnOnEnemy.get(p.getUniqueId());
            p.sendMessage("Spawn side toggled: now spawning on " + (nowEnemy ? "enemy" : "player") + " side.");
            try {
                // teleport admin to the selected side so they can place troops directly
                Location tp = nowEnemy ? arena.getEnemySideCenter() : arena.getPlayerSideCenter();
                if (tp != null) {
                    p.teleport(tp);
                    p.setAllowFlight(true);
                    p.setFlying(true);
                }
            } catch (Throwable ignore) {}
            return;
        }

        if (dn.contains("Normal Tools")) {
            giveNormalHotbar(p);
            p.sendMessage("Switched to normal formation tools.");
            return;
        }
        if (dn.contains("Admin Tools")) {
            giveAdminHotbar(p);
            p.sendMessage("Returned to admin tools.");
            return;
        }
    }
}
