package com.patrick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;

public class FormationManager {
    private final Plugin plugin;
    private final ArenaManager arena;

    // persistent per-mob formation slot assignment (mob UUID -> slot index)
    private final Map<UUID, Integer> formationAssignments = new HashMap<>();

    public FormationManager(Plugin plugin, ArenaManager arena) {
        this.plugin = plugin;
        this.arena = arena;
    }

    // snap placement (teleport) — only for initial setup
    public <T extends LivingEntity> void placeGroup(List<T> troops, Location anchor, Formation f) {
        var offsets = f.getOffsets(troops.size());
        for (int i = 0; i < troops.size(); i++) {
            Location target = anchor.clone().add(offsets.get(i));

            // snap X/Z to block center so placement is aligned
            target.setX(Math.floor(target.getX()) + 0.5);
            target.setZ(Math.floor(target.getZ()) + 0.5);

            // Face the enemy side center instead of hardcoding north
            Location enemyCenter = arena.getEnemySideCenter();

            // Prefer pathfinding movement instead of teleporting so mobs "walk" into place.
            LivingEntity ent = troops.get(i);
            if (ent instanceof Mob) {
                Mob m = (Mob) ent;
                try {
                    m.getPathfinder().moveTo(target, 1.05D);
                    m.setTarget(null);
                    try { m.lookAt(enemyCenter); } catch (Throwable ignore) {}
                } catch (Throwable ex) {
                    // fallback: last-resort teleport if pathing API isn't available
                    try {
                        target.setDirection(enemyCenter.toVector().subtract(target.toVector()));
                        ent.teleport(target);
                    } catch (Throwable ignore) {}
                }
            } else {
                // non-mob entities: teleport as a fallback
                try { target.setDirection(enemyCenter.toVector().subtract(target.toVector())); ent.teleport(target); } catch (Throwable ignore) {}
            }
        }
    }

    // gentle walking to slots (call once when reforming)
    public <T extends Mob> void moveGroupInFormation(List<T> troops, Location anchor, Formation f) {
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
            // If we're pretty close to the centered slot, stop moving and orient; do NOT teleport.
            if (distSq <= 0.9) {
                mob.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                mob.setTarget(null);
                try { mob.lookAt(slot); } catch (Throwable ignore) {}
            } else {
                // Pathfind toward the slot and orient toward the enemy center; avoid teleporting.
                try {
                    mob.getPathfinder().moveTo(slot, 1.05D);
                    mob.setTarget(null);
                    try { mob.lookAt(enemyCenter); } catch (Throwable ignore) {}
                } catch (Throwable ex) {
                    // fallback: if pathing is unavailable, gently teleport as a last resort
                    try {
                        Location snap = slot.clone();
                        snap.setDirection(enemyCenter.toVector().subtract(snap.toVector()));
                        mob.teleport(snap);
                    } catch (Throwable ignore) {}
                }
            }
        }
    }

    // Call moveGroupInFormation several times over a short period so mobs finish
    // their pathing approach and snap without requiring the user to re-run the command.
    public <T extends Mob> void scheduleFormationBurst(List<T> troops, Location anchor, Formation f, Runnable onCancelIfStopped) {
        moveGroupInFormation(troops, anchor, f);

        final int[] ticks = new int[] { 0 };
        final int[] holder = new int[1];

        var offsets = f.getOffsets(troops.size());
        List<Location> centeredSlots = new ArrayList<>();
        for (int i = 0; i < offsets.size(); i++) {
            Location s = anchor.clone().add(offsets.get(i));
            s.setX(Math.floor(s.getX()) + 0.5);
            s.setZ(Math.floor(s.getZ()) + 0.5);
            centeredSlots.add(s);
        }

        holder[0] = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            ticks[0] += 10;

            int close = 0, total = 0;
            for (T m : troops) {
                if (m.isDead()) continue;
                total++;
                Integer idx = formationAssignments.get(m.getUniqueId());
                if (idx == null || idx < 0 || idx >= centeredSlots.size()) continue;
                Location slot = centeredSlots.get(idx);
                double d2 = m.getLocation().distanceSquared(slot);
                if (d2 <= 1.2) close++;
            }

            if (total > 0 && (close * 100 / total >= 70 || ticks[0] >= 120)) {
                moveGroupInFormation(troops, anchor, f);
                Bukkit.getScheduler().cancelTask(holder[0]);
            }
        }, 10L, 10L);
    }

    // Convenience overload matching previous signature in BattleManager
    public <T extends Mob> void scheduleFormationBurst(List<T> troops, Location anchor, Formation f) {
        scheduleFormationBurst(troops, anchor, f, () -> {});
    }

    public <T extends Mob> void initializeFormationAssignments(List<T> troops, Location anchor, Formation f) {
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

    public Location centroid(List<? extends LivingEntity> list) {
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
}
