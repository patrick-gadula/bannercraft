package com.patrick;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class CommandListener implements Listener {
    private final BattleManager battle;

    public CommandListener(BattleManager battle) {
        this.battle = battle;
    }

    @EventHandler
    public void onItemUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()) return;
        if (!battle.isBattleRunning()) return;

        String name = item.getItemMeta().getDisplayName();
        Location base = p.getTargetBlockExact(10) != null 
            ? p.getTargetBlockExact(10).getLocation().add(0, 1, 0)
            : p.getLocation();

        // Troop Spawner
        if (name.contains("Troop")) {
            switch (e.getAction()) {
                case LEFT_CLICK_AIR:
                case LEFT_CLICK_BLOCK:
                    battle.spawnPlayerTroop(base, true);   // Infantry
                    p.sendMessage("§aSpawned Infantry!");
                    break;
                case RIGHT_CLICK_AIR:
                case RIGHT_CLICK_BLOCK:
                    battle.spawnPlayerTroop(base, false);  // Archer
                    p.sendMessage("§bSpawned Archer!");
                    break;
                default:
                    break;
            }
            e.setCancelled(true);
        }

        // Existing command sticks: Charge / Follow / Hold, etc.
        else if (name.contains("Charge")) {
            battle.orderCharge();
            p.sendMessage("§cTroops charging nearest enemies!");
        }
        else if (name.contains("Follow")) {
            battle.orderFollow(p);
            p.sendMessage("§aTroops following you!");
        }
        else if (name.contains("Hold")) {
            battle.orderHold();
            p.sendMessage("§7Troops holding position!");
        }
    }


}
