package com.patrick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class BattleManager implements Listener {
    private final BannerCraft plugin;
    private final ArenaManager arena = new ArenaManager();

    private final List<Zombie> infantry = new ArrayList<>();
    private final List<Skeleton> archers = new ArrayList<>();

    // Enemies
    private final List<Zombie> enemyInfantry = new ArrayList<>();
    private final List<Skeleton> enemyArchers = new ArrayList<>();

    private boolean battleRunning = false;
    private int formationTaskId = -1;
    private int combatTaskId = -1;

    // formation state
    private Formation infFormation = Formation.SHIELD_WALL;
    private Formation archFormation = Formation.LINE;
    private Location infAnchor, archAnchor;

    private boolean frozen = false;

    // per-skeleton shot cooldown (ticks)
    private final Map<UUID, Integer> archerCd = new HashMap<>();

    // per-group reformation hold (ticks remaining) — during this, combat is paused for that group
    private int infReformTicks = 0;
    private int archReformTicks = 0;

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
        // enemies inside platform, opposite side
        spawnEnemyTroops(center.clone().add(0, 0, 3));

        // freeze everyone for 30s
        setFrozen(true);
        p.sendMessage("§eTop-down setup: 30s. Troops frozen.");

        // after 30s -> drop player + start combat (NO formation ticker)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.setGameMode(GameMode.SURVIVAL);
            setFrozen(false);
            startCombatTicker();
            p.sendMessage("§aBattle begins!");
        }, 20L * 30);
    }

    public void stopBattle() {
        battleRunning = false;
        stopFormationTicker();
        stopCombatTicker();

        infantry.forEach(e -> { if (!e.isDead()) e.remove(); });
        archers.forEach(e -> { if (!e.isDead()) e.remove(); });
        enemyInfantry.forEach(e -> { if (!e.isDead()) e.remove(); });
        enemyArchers.forEach(e -> { if (!e.isDead()) e.remove(); });

        infantry.clear(); archers.clear();
        enemyInfantry.clear(); enemyArchers.clear();
        archerCd.clear();
        infReformTicks = archReformTicks = 0;

        // remove command sticks and sweep only our named battle mobs across all worlds
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.getInventory().remove(Material.STICK);
            pl.getInventory().remove(Material.BLAZE_ROD);
            arena.sendBack(pl);
        }
        for (World w : Bukkit.getWorlds()) {
            w.getEntities().forEach(ent -> {
                if (ent instanceof Zombie || ent instanceof Skeleton) {
                    String n = ent.getCustomName();
                    if ("Infantry".equals(n) || "Archer".equals(n)
                            || "Enemy Infantry".equals(n) || "Enemy Archer".equals(n)) {
                        ent.remove();
                    }
                }
            });
        }
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
        placeGroup(infantry, infAnchor, infFormation);
        placeGroup(archers,  archAnchor, archFormation);
    }

    private void spawnEnemyTroops(Location center) {
        for (int i = 0; i < 5; i++) {
            Zombie z = center.getWorld().spawn(center.clone().add(i, 0, 2), Zombie.class);
            z.setCustomName("Enemy Infantry");
            z.setShouldBurnInDay(false);
            prepareMob(z);
            enemyInfantry.add(z);
        }
        for (int i = 0; i < 3; i++) {
            Skeleton s = center.getWorld().spawn(center.clone().add(i, 0, -2), Skeleton.class);
            s.setCustomName("Enemy Archer");
            s.setShouldBurnInDay(false);
            prepareMob(s);
            enemyArchers.add(s);
        }
        placeGroup(enemyInfantry, center.clone().add(0, 0, 3), Formation.SHIELD_WALL);
        placeGroup(enemyArchers,  center.clone().add(0, 0, -3), Formation.LINE);
    }

    private void prepareMob(Mob mob) {
        Bukkit.getMobGoals().removeAllGoals(mob);
        mob.setCollidable(true);
        mob.setCanPickupItems(false);
        mob.setTarget(null);
        mob.setInvulnerable(false);
        mob.setRemoveWhenFarAway(false); 
    }

    private void setFrozen(boolean frozen) {
        this.frozen = frozen;
        infantry.forEach(m -> m.setAI(!frozen));
        archers.forEach(m -> m.setAI(!frozen));
        enemyInfantry.forEach(m -> m.setAI(!frozen));
        enemyArchers.forEach(m -> m.setAI(!frozen));
    }


    // only used during setup if you decide to preview walking, not used in combat
    private void startFormationTicker() {
        stopFormationTicker();
        formationTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!battleRunning) return;
            moveGroupInFormation(infantry, infAnchor, infFormation);
            moveGroupInFormation(archers,  archAnchor, archFormation);
        }, 10L, 10L);
    }

    private void stopFormationTicker() {
        if (formationTaskId != -1) {
            Bukkit.getScheduler().cancelTask(formationTaskId);
            formationTaskId = -1;
        }
    }

    private void startCombatTicker() {
        stopCombatTicker();
        combatTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!battleRunning) return;

            // If a group is reforming, drive them to new slots and skip their combat this tick
            boolean infReforming  = infReformTicks  > 0;
            boolean archReforming = archReformTicks > 0;

            if (infReforming) {
                moveGroupInFormation(infantry, infAnchor, infFormation);
                infReformTicks = Math.max(0, infReformTicks - 10);
            }
            if (archReforming) {
                moveGroupInFormation(archers, archAnchor, archFormation);
                archReformTicks = Math.max(0, archReformTicks - 10);
            }

            // Allies act (skip groups that are reforming)
            autoCombat(infReforming  ? List.of() : infantry,
                       archReforming ? List.of() : archers,
                       enemyInfantry, enemyArchers);

            // Enemies act (simple mirror; no reformation for them)
            autoCombat(enemyInfantry, enemyArchers, infantry, archers);

            checkWinLose();

            // tick down archer cooldowns (loop runs every 10 ticks)
            archerCd.replaceAll((id, cd) -> Math.max(0, cd - 10));
        }, 10L, 10L);
    }

    private void stopCombatTicker() {
        if (combatTaskId != -1) {
            Bukkit.getScheduler().cancelTask(combatTaskId);
            combatTaskId = -1;
        }
    }

    // snap placement (teleport) — only for initial setup
    private <T extends LivingEntity> void placeGroup(List<T> troops, Location anchor, Formation f) {
        var offsets = f.getOffsets(troops.size());
        for (int i = 0; i < troops.size(); i++) {
            troops.get(i).teleport(anchor.clone().add(offsets.get(i)));
        }
    }

    // gentle walking to slots (call once when reforming)
    private <T extends Mob> void moveGroupInFormation(List<T> troops, Location anchor, Formation f) {
        var offsets = f.getOffsets(troops.size());
        for (int i = 0; i < troops.size(); i++) {
            T mob = troops.get(i);
            if (mob.isDead()) continue;
            Location slot = anchor.clone().add(offsets.get(i));
            if (mob.getLocation().distanceSquared(slot) > 1.5) {
                mob.getPathfinder().moveTo(slot, 1.05D);
                mob.setTarget(null);
            }
        }
    }

    // ===== Formation setters (do NOT anchor to player; use current centroid) =====
    public void setInfantryFormation(Formation f, Location ignored) {
        this.infFormation = f;
        this.infAnchor = centroid(infantry);
        if (frozen) {
            // during setup: snap instantly so the player sees the change
            placeGroup(infantry, infAnchor, infFormation);
            infReformTicks = 0;
        } else {
            // mid-battle: walk into the new shape and pause their combat briefly
            infReformTicks = 80; // ~4s
            moveGroupInFormation(infantry, infAnchor, infFormation);
        }
    }

    public void setArcherFormation(Formation f, Location ignored) {
        this.archFormation = f;
        this.archAnchor = centroid(archers);
        if (frozen) {
            placeGroup(archers, archAnchor, archFormation);
            archReformTicks = 0;
        } else {
            archReformTicks = 80;
            moveGroupInFormation(archers, archAnchor, archFormation);
        }
    }

    private Location centroid(List<? extends LivingEntity> list) {
        if (list.isEmpty()) return arena.getPlatformCenter();
        World w = list.get(0).getWorld();
        double sx = 0, sy = 0, sz = 0;
        for (LivingEntity e : list) {
            Location l = e.getLocation();
            sx += l.getX(); sy += l.getY(); sz += l.getZ();
        }
        int n = list.size();
        return new Location(w, sx / n, sy / n, sz / n);
    }

    // ===== Simple auto-combat (melee hits + archer kiting & shots) =====
    private void autoCombat(List<Zombie> meleeAllies, List<Skeleton> rangedAllies,
                            List<Zombie> meleeEnemies, List<Skeleton> rangedEnemies) {

        List<LivingEntity> enemies = new ArrayList<>();
        meleeEnemies.stream().filter(e -> !e.isDead()).forEach(enemies::add);
        rangedEnemies.stream().filter(e -> !e.isDead()).forEach(enemies::add);
        if (enemies.isEmpty()) return;

        // melee: close gap and attack
        for (Zombie z : meleeAllies) {
            if (z.isDead()) continue;
            LivingEntity tgt = nearest(z.getLocation(), enemies);
            if (tgt == null) continue;
            double d2 = z.getLocation().distanceSquared(tgt.getLocation());
            if (d2 > 3.5) {
                z.getPathfinder().moveTo(tgt.getLocation(), 1.2D);
            } else {
                tgt.damage(2.0, z);
            }
        }

        // archers: keep ~10–18 blocks and shoot with cooldown
        for (Skeleton s : rangedAllies) {
            if (s.isDead()) continue;
            LivingEntity tgt = nearest(s.getLocation(), enemies);
            if (tgt == null) continue;

            double dist = s.getLocation().distance(tgt.getLocation());
            if (dist > 18) {
                s.getPathfinder().moveTo(tgt.getLocation(), 1.05D);
            } else if (dist < 10) {
                var away = s.getLocation().toVector()
                        .subtract(tgt.getLocation().toVector())
                        .normalize().multiply(2.5);
                s.getPathfinder().moveTo(s.getLocation().add(away), 1.05D);
            } else {
                s.lookAt(tgt);
            }

            int cd = archerCd.getOrDefault(s.getUniqueId(), 0);
            if (cd == 0) {
                Location eye = s.getEyeLocation();
                Location aim = (tgt instanceof Mob m) ? m.getEyeLocation() : tgt.getLocation().add(0, 1.3, 0);

                org.bukkit.util.Vector vel = aimArrow(eye, aim);
                Arrow arrow = s.launchProjectile(Arrow.class, vel);
                arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);
                arrow.setShooter(s);


                archerCd.put(s.getUniqueId(), 30); // ~1.5s (10-tick loop)
            }
        }
    }

    private LivingEntity nearest(Location from, List<LivingEntity> list) {
        double best = Double.MAX_VALUE;
        LivingEntity pick = null;
        for (LivingEntity e : list) {
            double d = e.getLocation().distanceSquared(from);
            if (d < best) { best = d; pick = e; }
        }
        return pick;
    }

    // Builds a velocity that compensates mildly for gravity and scales speed by distance
    private org.bukkit.util.Vector aimArrow(Location fromEye, Location toEye) {
        org.bukkit.util.Vector diff = toEye.toVector().subtract(fromEye.toVector());
        double dist = fromEye.distance(toEye);
        double horiz = Math.hypot(diff.getX(), diff.getZ());

        // speed scales gently with distance (clamped)
        double speed = Math.max(1.4, Math.min(1.9, 1.15 + 0.04 * dist));

        // mild gravity compensation — proportional to horizontal distance
        double lift = 0.010 * horiz; // was 0.008; a touch more keeps shots from falling short

        org.bukkit.util.Vector dir = diff.normalize();
        dir.setY(dir.getY() + lift);

        return dir.normalize().multiply(speed);
    }

    private void checkWinLose() {
        boolean enemiesAlive = enemyInfantry.stream().anyMatch(e -> !e.isDead())
                || enemyArchers.stream().anyMatch(e -> !e.isDead());
        boolean alliesAlive = infantry.stream().anyMatch(e -> !e.isDead())
                || archers.stream().anyMatch(e -> !e.isDead());

        if (!enemiesAlive || !alliesAlive) {
            Bukkit.broadcastMessage(!enemiesAlive ? "§aYou won!" : "§cYou lost!");
            stopBattle();
        }
    }

    public List<Zombie> getInfantry() { return infantry; }
    public List<Skeleton> getArchers() { return archers; }
    public boolean isBattleRunning() { return battleRunning; }
}
