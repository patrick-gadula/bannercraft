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
import org.bukkit.attribute.Attribute;
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

    private enum CommandMode { NORMAL, CHARGE, FOLLOW, HOLD }
    private CommandMode commandMode = CommandMode.NORMAL;
    private Player followTarget = null;

    private boolean frozen = false;

    // per-skeleton shot cooldown (ticks)
    private final Map<UUID, Integer> archerCd = new HashMap<>();

    // persistent per-mob formation slot assignment (mob UUID -> slot index)
    private final Map<UUID, Integer> formationAssignments = new HashMap<>();


    // per-group reformation hold (ticks remaining) — during this, combat is paused for that group
    private int infReformTicks = 0;
    private int archReformTicks = 0;

    public BattleManager(BannerCraft plugin) { this.plugin = plugin; }

    public void startBattle(Player p) {
        if (battleRunning) { 
            p.sendMessage("§cBattle already running!"); 
            return; 
        }
        battleRunning = true;

        arena.createArena();
        arena.buildDivider();
        arena.sendToArena(p);
        giveCommandSticks(p);

        // Player in survival right away
        p.setGameMode(GameMode.SURVIVAL);

        // spawn + anchors
        Location center = arena.getPlatformCenter();
        infAnchor  = center.clone().add(0, 0, -5);
        archAnchor = center.clone().add(0, 0, -9);

        // no freeze window
        setFrozen(false);

    // Reset any previous command state so a new battle starts in NORMAL mode
    this.commandMode = CommandMode.NORMAL;
    this.followTarget = null;
    this.formationAssignments.clear();

        // just wait for /start to actually drop divider + order combat
        p.sendMessage("§eTroops are ready. Use /start when ready to begin!");
    }

    public void beginCombat() {
        if (!battleRunning) return;
        arena.removeDivider();
        spawnEnemyTroops();
        startCombatTicker();
        this.commandMode = CommandMode.NORMAL;
        this.followTarget = null;
        Bukkit.broadcastMessage("§cThe battle has begun!");
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
            pl.getInventory().remove(Material.REDSTONE_TORCH);
            pl.getInventory().remove(Material.LIME_DYE);
            pl.getInventory().remove(Material.GRAY_DYE);
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
        ItemStack spawnTool = new ItemStack(Material.STICK);
        ItemMeta st = spawnTool.getItemMeta(); st.setDisplayName("§eTroop Spawner"); spawnTool.setItemMeta(st);

        ItemStack torch = new ItemStack(Material.REDSTONE_TORCH);
        ItemMeta tm = torch.getItemMeta(); tm.setDisplayName("§cCharge!"); torch.setItemMeta(tm);

        ItemStack lime = new ItemStack(Material.LIME_DYE);
        ItemMeta lm = lime.getItemMeta(); lm.setDisplayName("§aFollow Me!"); lime.setItemMeta(lm);

        ItemStack hold = new ItemStack(Material.GRAY_DYE);
        ItemMeta hm = hold.getItemMeta(); hm.setDisplayName("§7Hold Position!"); hold.setItemMeta(hm);

        p.getInventory().addItem(spawnTool, torch, lime, hold);
    }

    public void spawnPlayerTroop(Location loc, boolean infantryType) {
        if (infantryType) {
            Zombie z = loc.getWorld().spawn(loc, Zombie.class);
            z.setCustomName("Infantry");
            z.setShouldBurnInDay(false);
            prepareMob(z);
            infantry.add(z);
        } else {
            Skeleton s = loc.getWorld().spawn(loc, Skeleton.class);
            s.setCustomName("Archer");
            s.setShouldBurnInDay(false);
            prepareMob(s);
            archers.add(s);
        }
    }


    private void spawnEnemyTroops() {
        Location center = arena.getEnemySideCenter();
        for (int i = 0; i < 5; i++) {
            Zombie bandit = center.getWorld().spawn(center.clone().add(i, 0, 2), Zombie.class);
            bandit.setCustomName("Bandit");
            bandit.setShouldBurnInDay(false);
            prepareMob(bandit);

            // Equip sword
            bandit.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));

            // Buff health
            bandit.getAttribute(Attribute.MAX_HEALTH).setBaseValue(30.0);
            bandit.setHealth(30.0);

            enemyInfantry.add(bandit);
        }
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
            if (!(infReforming || archReforming)) {
                if (commandMode == CommandMode.HOLD) {
                    // do nothing – troops stay in place
                } else if (commandMode == CommandMode.FOLLOW && followTarget != null) {
                    moveGroupInFormation(infantry, followTarget.getLocation().add(0, 0, 2), infFormation);
                    moveGroupInFormation(archers,  followTarget.getLocation().add(0, 0, -2), archFormation);
                } else if (commandMode == CommandMode.CHARGE) {
                    // charge = actively target enemies
                    List<LivingEntity> enemyTargets = new ArrayList<>();
                    enemyInfantry.stream().filter(e -> !e.isDead()).forEach(enemyTargets::add);
                    autoCombat(infantry, archers, enemyTargets);
                }
            }

            // Enemies act (bandits only)
            List<LivingEntity> allyTargets = new ArrayList<>();
            infantry.stream().filter(e -> !e.isDead()).forEach(allyTargets::add);
            archers.stream().filter(e -> !e.isDead()).forEach(allyTargets::add);
            autoCombat(enemyInfantry, new ArrayList<>(), allyTargets);

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
            Location target = anchor.clone().add(offsets.get(i));

            // snap X/Z to block center so placement is exact
            target.setX(Math.floor(target.getX()) + 0.5);
            target.setZ(Math.floor(target.getZ()) + 0.5);

            // Face the enemy side center instead of hardcoding north
            Location enemyCenter = arena.getEnemySideCenter();
            target.setDirection(enemyCenter.toVector().subtract(target.toVector()));

            troops.get(i).teleport(target);
        }
    }


    // gentle walking to slots (call once when reforming)
    private <T extends Mob> void moveGroupInFormation(List<T> troops, Location anchor, Formation f) {
        var offsets = f.getOffsets(troops.size());

        // Build concrete slot locations from offsets
        List<Location> slots = new ArrayList<>();
        for (int i = 0; i < offsets.size(); i++) {
            slots.add(anchor.clone().add(offsets.get(i)));
        }

        // Also build centered-slot locations (snap X/Z to block center) so mobs line up exactly
        List<Location> centeredSlots = new ArrayList<>();
        for (Location s : slots) {
            Location c = s.clone();
            c.setX(Math.floor(c.getX()) + 0.5);
            c.setZ(Math.floor(c.getZ()) + 0.5);
            centeredSlots.add(c);
        }

        // Collect available (alive) mobs
        List<T> available = new ArrayList<>();
        for (T m : troops) if (!m.isDead()) available.add(m);

    // Greedy assignment with stability: prefer previously assigned slot for each mob
        Map<T, Location> assignment = new HashMap<>();
        // Track which slots are taken
    boolean[] slotTaken = new boolean[centeredSlots.size()];

        // First, try to reapply previous assignments (stable slot index to same mob UUID)
        for (T m : troops) {
            if (m.isDead()) continue;
            Integer idx = formationAssignments.get(m.getUniqueId());
            if (idx != null && idx >= 0 && idx < centeredSlots.size() && !slotTaken[idx]) {
                assignment.put(m, centeredSlots.get(idx));
                slotTaken[idx] = true;
                available.remove(m);
            }
        }

        // Then greedily fill remaining slots with nearest remaining mobs
        for (int i = 0; i < centeredSlots.size(); i++) {
            if (slotTaken[i]) continue;
            Location slot = centeredSlots.get(i);
            T best = null;
            double bestDist = Double.MAX_VALUE;
            for (T m : available) {
                double d = m.getLocation().distanceSquared(slot);
                if (d < bestDist) { bestDist = d; best = m; }
            }
            if (best != null) {
                assignment.put(best, slot);
                formationAssignments.put(best.getUniqueId(), i);
                available.remove(best);
                slotTaken[i] = true;
            }
        }

        // Move assigned mobs toward their unique slots and orient them
        Location enemyCenter = arena.getEnemySideCenter();
        for (Map.Entry<T, Location> e : assignment.entrySet()) {
            T mob = e.getKey();
            Location slot = e.getValue();

            double distSq = mob.getLocation().distanceSquared(slot);
            // If we're pretty close to the centered slot, snap exactly to it and set facing
            if (distSq <= 0.9) {
                // make a copy to set direction toward enemy center
                Location snap = slot.clone();
                snap.setDirection(enemyCenter.toVector().subtract(snap.toVector()));
                mob.teleport(snap);
                // clear velocity so they don't slide after teleport
                mob.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                mob.setTarget(null);
            } else {
                mob.getPathfinder().moveTo(slot, 1.05D);
                mob.setTarget(null);

                // While moving, keep them roughly oriented toward the enemy
                mob.teleport(mob.getLocation().setDirection(
                    enemyCenter.toVector().subtract(mob.getLocation().toVector())
                ));
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
            // preassign slots deterministically so the first move call is stable
            initializeFormationAssignments(infantry, infAnchor, infFormation);
            // run a short burst of movement+snap checks so they settle on first command
            scheduleFormationBurst(infantry, infAnchor, infFormation);
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
            // preassign slots deterministically so the first move call is stable
            initializeFormationAssignments(archers, archAnchor, archFormation);
            scheduleFormationBurst(archers, archAnchor, archFormation);
        }
    }

    // Call moveGroupInFormation several times over a short period so mobs finish
    // their pathing approach and snap without requiring the user to re-run the command.
    private <T extends Mob> void scheduleFormationBurst(List<T> troops, Location anchor, Formation f) {
        // First call immediately so they start moving toward slots.
        moveGroupInFormation(troops, anchor, f);

        // Then run a periodic check: if a sufficient fraction of troops are within
        // the snap radius of their assigned slot, perform the final snap. Timeout
        // after ~6 seconds (120 ticks) to avoid never completing.
        final int[] ticks = new int[] { 0 };
        final int[] holder = new int[1];

        // precompute centered slots from formation offsets
        var offsets = f.getOffsets(troops.size());
        List<Location> centeredSlots = new ArrayList<>();
        for (int i = 0; i < offsets.size(); i++) {
            Location s = anchor.clone().add(offsets.get(i));
            s.setX(Math.floor(s.getX()) + 0.5);
            s.setZ(Math.floor(s.getZ()) + 0.5);
            centeredSlots.add(s);
        }

        holder[0] = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!battleRunning) {
                Bukkit.getScheduler().cancelTask(holder[0]);
                return;
            }
            ticks[0] += 10;

            // Count troops close to their assigned centered slot
            int close = 0, total = 0;
            for (T m : troops) {
                if (m.isDead()) continue;
                total++;
                Integer idx = formationAssignments.get(m.getUniqueId());
                if (idx == null || idx < 0 || idx >= centeredSlots.size()) continue;
                Location slot = centeredSlots.get(idx);
                double d2 = m.getLocation().distanceSquared(slot);
                if (d2 <= 1.2) close++; // ~1.1 blocks squared threshold
            }

            // condition: at least 70% are close, or timeout reached
            if (total > 0 && (close * 100 / total >= 70 || ticks[0] >= 120)) {
                moveGroupInFormation(troops, anchor, f);
                Bukkit.getScheduler().cancelTask(holder[0]);
            }
        }, 10L, 10L);
    }

    // Deterministically assign each alive troop to a centered slot so the first reform
    // tick will already have stable assignments and snap behavior.
    private <T extends Mob> void initializeFormationAssignments(List<T> troops, Location anchor, Formation f) {
        var offsets = f.getOffsets(troops.size());
        List<Location> centeredSlots = new ArrayList<>();
        for (int i = 0; i < offsets.size(); i++) {
            Location s = anchor.clone().add(offsets.get(i));
            s.setX(Math.floor(s.getX()) + 0.5);
            s.setZ(Math.floor(s.getZ()) + 0.5);
            centeredSlots.add(s);
        }

        List<T> available = new ArrayList<>();
        for (T m : troops) if (!m.isDead()) available.add(m);

        // Greedy: for each slot, pick the nearest available mob and record its slot index
        for (int i = 0; i < centeredSlots.size() && !available.isEmpty(); i++) {
            Location slot = centeredSlots.get(i);
            T best = null; double bestDist = Double.MAX_VALUE;
            for (T m : available) {
                double d = m.getLocation().distanceSquared(slot);
                if (d < bestDist) { bestDist = d; best = m; }
            }
            if (best != null) {
                formationAssignments.put(best.getUniqueId(), i);
                available.remove(best);
            }
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
                        List<LivingEntity> enemies) {

        List<LivingEntity> targets = new ArrayList<>();
        enemies.stream().filter(e -> !e.isDead()).forEach(targets::add);
        if (targets.isEmpty()) return;

        // melee: close gap and attack
        for (Zombie z : meleeAllies) {
            if (z.isDead()) continue;
            LivingEntity tgt = nearest(z.getLocation(), targets);
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
            LivingEntity tgt = nearest(s.getLocation(), targets);
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


    // ===== Player-issued orders =====
    public void orderCharge() {
        this.commandMode = CommandMode.CHARGE;

        // Player infantry charge nearest bandit
        for (Zombie z : infantry) {
            z.setAI(true);
            LivingEntity target = nearest(z.getLocation(), new ArrayList<>(enemyInfantry));
            if (target != null) {
                z.setTarget(target);
            }
        }

        // Player archers charge nearest bandit
        for (Skeleton s : archers) {
            s.setAI(true);
            LivingEntity target = nearest(s.getLocation(), new ArrayList<>(enemyInfantry));
            if (target != null) {
                s.setTarget(target);
            }
        }
    }


    public void orderFollow(Player p) {
        this.commandMode = CommandMode.FOLLOW;
        this.followTarget = p;

        // Unfreeze troops so they can move again
        infantry.forEach(m -> m.setAI(true));
        archers.forEach(m -> m.setAI(true));
    }

    public void orderHold() {
        this.commandMode = CommandMode.HOLD;
        infantry.forEach(m -> m.setAI(false));
        archers.forEach(m -> m.setAI(false));
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
