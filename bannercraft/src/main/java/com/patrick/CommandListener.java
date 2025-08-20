package com.patrick;

import java.util.List;

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
    public void onStickUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()) return;
        if (!battle.isBattleRunning()) return;

        String name = item.getItemMeta().getDisplayName();
        Location base = p.getLocation();

        if (name.contains("Infantry")) {
            arrangeFormation(battle.getInfantry(), base, Formation.SHIELD_WALL);
            p.sendMessage("§aInfantry in Shield Wall!");
        }

        if (name.contains("Archer")) {
            arrangeFormation(battle.getArchers(), base, Formation.LINE);
            p.sendMessage("§bArchers in Line!");
        }
    }

    private <T extends org.bukkit.entity.LivingEntity> void arrangeFormation(List<T> troops, Location base, Formation f) {
        List<org.bukkit.util.Vector> offsets = f.getOffsets(troops.size());
        for (int i = 0; i < troops.size(); i++) {
            Location target = base.clone().add(offsets.get(i));
            troops.get(i).teleport(target);
        }
    }
}
