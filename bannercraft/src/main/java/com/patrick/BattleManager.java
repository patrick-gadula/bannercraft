package com.patrick;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.destroystokyo.paper.entity.Pathfinder;

public class BattleManager implements Listener {
    private final BannerCraft plugin;
    private final ArenaManager arena = new ArenaManager();

    private final List<Zombie> infantry = new ArrayList<>();
    private final List<Skeleton> archers = new ArrayList<>();

    private boolean battleRunning = false;
    private int formationTaskId = -1;

    // formation state
    private Formation infFormation = Formation.SHIELD_WALL;
    private Formation archFormation = Formation.LINE;
    private Location infAnchor, archAnchor;

    public BattleManager(BannerCraft plugin) { this.plugin = plugin; }

    public void startBattle(Player p) {
        if (battleRunning) { p.sendMessage("§cBattle already running!"); return; }
        battleRunning = true;

        arena.createArena();
        arena.sendToArena(p);
        giveCommandSticks(p);

        // spectator setup window
        p.setGameMode(GameMode.SPECTATOR);

        // spawn + anchors
        Location center = arena.getPlatformCenter();
        infAnchor  = center.clone().add(0, 0, -5);
        archAnchor = center.clone().add(0, 0, -9);

        spawnPlayerTroops(center);
        spawnEnemyTroops(center.clone().add(10, 0, 10));

        // freeze everyone for 30s
        setFrozen(true);
        p.sendMessage("§eTop-down setup: 30s. Troops frozen.");

        // after 30s -> drop player + start formation ticker
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.setGameMode(GameMode.SURVIVAL);
            setFrozen(false);
            startFormationTicker();
            p.sendMessage("§aBattle begins!");
        }, 20L * 30);
    }

    public void stopBattle() {
        battleRunning = false;
        stopFormationTicker();

        infantry.forEach(e -> { if (!e.isDead()) e.remove(); });
        archers.forEach(e -> { if (!e.isDead()) e.remove(); });
        infantry.clear(); archers.clear();

        for (Player pl : Bukkit.getOnlinePlayers()) arena.sendBack(pl);
    }

    private void giveCommandSticks(Player p) {
        ItemStack infStick = new ItemStack(Material.STICK);
        ItemMeta im = infStick.getItemMeta(); im.setDisplayName("§aInfantry Command Stick"); infStick.setItemMeta(im);
        ItemStack archStick = new ItemStack(Material.BLAZE_ROD);
        ItemMeta am = archStick.getItemMeta(); am.setDisplayName("§bArcher Command Stick"); archStick.setItemMeta(am);
        p.getInventory().addItem(infStick, archStick);
    }

    private void spawnPlayerTroops(Location center) {
        for (int i = 0; i < 5; i++) {
            Zombie z = center.getWorld().spawn(center.clone().add(i, 0, 2), Zombie.class);
            z.setCustomName("Infantry");
            z.setShouldBurnInDay(false);
            prepareMob(z);
            infantry.add(z);
        }
        for (int i = 0; i < 3; i++) {
            Skeleton s = center.getWorld().spawn(center.clone().add(i, 0, -2), Skeleton.class);
            s.setCustomName("Archer");
            s.setShouldBurnInDay(false);
            prepareMob(s);
            archers.add(s);
        }
        // initial placement
        placeGroup(infantry, infAnchor, infFormation);
        placeGroup(archers,  archAnchor, archFormation);
    }

    private void spawnEnemyTroops(Location center) {
        // simple AI army; also prevented from burning
        for (int i = 0; i < 5; i++) {
            Zombie z = center.getWorld().spawn(center.clone().add(i, 0, 2), Zombie.class);
            z.setCustomName("Enemy Infantry");
            z.setShouldBurnInDay(false);
            prepareMob(z);
        }
        for (int i = 0; i < 3; i++) {
            Skeleton s = center.getWorld().spawn(center.clone().add(i, 0, -2), Skeleton.class);
            s.setCustomName("Enemy Archer");
            s.setShouldBurnInDay(false);
            prepareMob(s);
        }
    }

    private void prepareMob(Mob mob) {
        // No wandering/look goals; we'll drive movement ourselves
        Bukkit.getMobGoals().removeAllGoals(mob); // Paper MobGoals API
        mob.setCollidable(false);
        mob.setCanPickupItems(false);
        mob.setTarget(null);
    }

    private void setFrozen(boolean frozen) {
        infantry.forEach(m -> m.setAI(!frozen));
        archers.forEach(m -> m.setAI(!frozen));
    }

    private void startFormationTicker() {
        stopFormationTicker();
        formationTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!battleRunning) return;
            moveGroupInFormation(infantry, infAnchor, infFormation);
            moveGroupInFormation(archers,  archAnchor, archFormation);
        }, 10L, 10L); // every 0.5s
    }

    private void stopFormationTicker() {
        if (formationTaskId != -1) {
            Bukkit.getScheduler().cancelTask(formationTaskId);
            formationTaskId = -1;
        }
    }

    // hard place (teleport) — for setup & quick re-formation
    private <T extends LivingEntity> void placeGroup(List<T> troops, Location anchor, Formation f) {
        var offsets = f.getOffsets(troops.size());
        for (int i = 0; i < troops.size(); i++) {
            troops.get(i).teleport(anchor.clone().add(offsets.get(i)));
        }
    }

    // walking in formation — uses Paper pathfinder (no NMS)
    private <T extends Mob> void moveGroupInFormation(List<T> troops, Location anchor, Formation f) {
        var offsets = f.getOffsets(troops.size());
        for (int i = 0; i < troops.size(); i++) {
            T mob = troops.get(i);
            if (mob.isDead()) continue;

            Location slot = anchor.clone().add(offsets.get(i));
            if (mob.getLocation().distanceSquared(slot) > 1.5) {
                Pathfinder pf = mob.getPathfinder();
                pf.moveTo(slot, 1.0D);  // walk to slot (Paper API)
                mob.setTarget(null);    // keep discipline
            }
        }
    }

    public List<Zombie> getInfantry() { return infantry; }
    public List<Skeleton> getArchers() { return archers; }
    public boolean isBattleRunning() { return battleRunning; }
}
