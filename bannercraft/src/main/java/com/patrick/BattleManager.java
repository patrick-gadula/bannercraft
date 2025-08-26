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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;

@SuppressWarnings("deprecation")
public class BattleManager implements Listener {
    private final BannerCraft plugin;
    private final ArenaManager arena = new ArenaManager();
    private final FormationManager formationManager;

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
    // admin-created arena setup flag
    private boolean adminSetup = false;
    // scheduled task for continuous selective follow
    private int selectiveFollowTaskId = -1;
        // admin simulation task id (runs when admin spawns mobs even if no battleRunning)
        private int adminSimTaskId = -1;

    // per-skeleton shot cooldown (ticks)
    private final Map<UUID, Integer> archerCd = new HashMap<>();

    // per-player troop budget (how many additional troops the player may spawn)
    private final Map<UUID, Integer> playerTroopBudget = new HashMap<>();

    // UI selection state (legacy) was previously here; now managed inline in listeners

    // persistent per-mob formation slot assignment (mob UUID -> slot index)
    private final Map<UUID, Integer> formationAssignments = new HashMap<>();

    // per-group reformation hold (ticks remaining) — during this, combat is paused for that group
    private int infReformTicks = 0;
    private int archReformTicks = 0;
    // guard to ensure end-of-battle sequence runs only once
    private boolean endScheduled = false;

    public BattleManager(BannerCraft plugin) { 
        this.plugin = plugin; 
        this.formationManager = new FormationManager(plugin, arena);
        // register formation command handler to move hotbar/interaction logic out of this class
        Bukkit.getPluginManager().registerEvents(new FormationCommandHandler(plugin, this, formationManager), plugin);
    }

    // Backwards-compatible overload: spawn on player side by default
    public void spawnAdminZombie(Location loc, InfantryType type) {
        spawnAdminZombie(loc, type, false);
    }

    // Simple enum to describe admin spawn variants
    public enum InfantryType { AXE, SWORD_SHIELD, SPEAR_SHIELD, MOUNTED_SPEAR }

    /**
     * Spawn an admin/test zombie variant at the given location. Tags the mob so selection
     * filters work and attempts to set a default scale via configured command template.
     */
    public void spawnAdminZombie(Location loc, InfantryType type, boolean enemy) {
        if (loc == null) return;
        Zombie z = loc.getWorld().spawn(loc, Zombie.class);
        z.setShouldBurnInDay(false);
        z.setRemoveWhenFarAway(false);
        prepareMob(z);

        switch (type) {
            case AXE -> {
                z.setCustomName("Infantry");
                try { z.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_AXE)); } catch (Throwable ignore) {}
                z.setMetadata("troop_subclass", new FixedMetadataValue(plugin, "axeman"));
                    if (enemy) enemyInfantry.add(z); else infantry.add(z);
                    // AI should follow frozen state (don't enable AI during setup)
                    try { z.setAI(!frozen); } catch (Throwable ignore) {}
                    // face the opposing team
                    try {
                        Location faceTo = enemy ? arena.getPlayerSideCenter() : arena.getEnemySideCenter();
                        z.teleport(z.getLocation().setDirection(faceTo.toVector().subtract(z.getLocation().toVector())));
                    } catch (Throwable ignore) {}
            }
            case SWORD_SHIELD -> {
                z.setCustomName("Infantry");
                try { z.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD)); } catch (Throwable ignore) {}
                try { z.getEquipment().setItemInOffHand(new ItemStack(Material.SHIELD)); } catch (Throwable ignore) {}
                z.setMetadata("troop_subclass", new FixedMetadataValue(plugin, "swordshield"));
                if (enemy) enemyInfantry.add(z); else infantry.add(z);
                    try { z.setAI(!frozen); } catch (Throwable ignore) {}
                    // face the opposing team
                    try {
                        Location faceTo = enemy ? arena.getPlayerSideCenter() : arena.getEnemySideCenter();
                        z.teleport(z.getLocation().setDirection(faceTo.toVector().subtract(z.getLocation().toVector())));
                    } catch (Throwable ignore) {}
            }
            case SPEAR_SHIELD -> {
                z.setCustomName("Infantry");
                try { z.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT)); } catch (Throwable ignore) {}
                try { z.getEquipment().setItemInOffHand(new ItemStack(Material.SHIELD)); } catch (Throwable ignore) {}
                z.setMetadata("troop_subclass", new FixedMetadataValue(plugin, "spearman"));
                if (enemy) enemyInfantry.add(z); else infantry.add(z);
                    try { z.setAI(!frozen); } catch (Throwable ignore) {}
                    // face the opposing team
                    try {
                        Location faceTo = enemy ? arena.getPlayerSideCenter() : arena.getEnemySideCenter();
                        z.teleport(z.getLocation().setDirection(faceTo.toVector().subtract(z.getLocation().toVector())));
                    } catch (Throwable ignore) {}
            }
            case MOUNTED_SPEAR -> {
                z.setCustomName("Infantry");
                try { z.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT)); } catch (Throwable ignore) {}
                z.setMetadata("troop_subclass", new FixedMetadataValue(plugin, "mounted"));
                if (enemy) enemyInfantry.add(z); else infantry.add(z);
                // spawn and mount a horse robustly
                try {
                    org.bukkit.entity.Horse horse = loc.getWorld().spawn(loc.clone().add(0.5, 0, 0.5), org.bukkit.entity.Horse.class);
                    horse.setAdult();
                    try { horse.setTamed(true); } catch (Throwable ignore) {}
                    try { horse.getInventory().setSaddle(new ItemStack(Material.SADDLE)); } catch (Throwable ignore) {}
                    try { horse.setAI(!frozen); } catch (Throwable ignore) {}
                    try { horse.setMetadata("troop_subclass", new FixedMetadataValue(plugin, "mounted")); } catch (Throwable ignore) {}
                    // mount rider onto the horse
                    try { horse.addPassenger(z); } catch (Throwable ignore) {}
                    // face the opposing team from the mount
                    try {
                        Location faceTo = enemy ? arena.getPlayerSideCenter() : arena.getEnemySideCenter();
                        horse.teleport(horse.getLocation().setDirection(faceTo.toVector().subtract(horse.getLocation().toVector())));
                    } catch (Throwable ignore) {}
                } catch (Throwable ignore) {}
            }
        }

        // Attempt to apply base scale of 0.5 via configured template or Minecraft:scale if available
        try {
            String template = plugin.getConfig().getString("scalingCommand");
            String scale = plugin.getConfig().getString("scalingValue", "0.5");
            if (template != null && !template.isBlank()) {
                String cmd = template.replace("{target}", z.getUniqueId().toString()).replace("{scale}", scale);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } else {
                // fallback command for servers supporting Minecraft:scale
                String cmd = String.format("minecraft:scale base set %s %s", z.getUniqueId().toString(), "0.5");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        } catch (Throwable ignore) {}

    // Admin spawn should not auto-start an admin simulation here; admin-created
    // instances reuse the normal battle setup and the troops respect the
    // current frozen state until the divider falls.
    }

    public void startBattle(Player p) {
        if (battleRunning) { 
            p.sendMessage("§cBattle already running!"); 
            return; 
        }
        battleRunning = true;

        arena.createArena();
        arena.buildDivider();
        arena.sendToArena(p);

        p.getInventory().remove(Material.STICK);
        giveCommandSticks(p);

        // Setup: keep player in adventure/spectator-like setup with flight allowed so they can arrange
        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);

        // spawn + anchors
        Location center = arena.getPlatformCenter();
        infAnchor  = center.clone().add(0, 0, -5);
        archAnchor = center.clone().add(0, 0, -9);

        // Give player their starting troop: 1 free infantry already present
        UUID pid = p.getUniqueId();
        if (!playerTroopBudget.containsKey(pid)) {
            // default budget = 0 additional troops; player still gets the free starter
            playerTroopBudget.put(pid, 0);
        }
    spawnPlayerTroop(infAnchor.clone(), true);
    formationManager.placeGroup(infantry, infAnchor, infFormation);

    // Freeze troops during setup so nothing moves across the divider
    setFrozen(true);

        // Provide an early-start item (Redstone) in hotbar slot 4
        ItemStack early = new ItemStack(Material.REDSTONE);
        ItemMeta earlyMeta = early.getItemMeta(); earlyMeta.setDisplayName("§cStart Battle Early"); early.setItemMeta(earlyMeta);
        p.getInventory().setItem(4, early);

        // Automatic 60s setup countdown: show titles at 60,30,10,5..1 and auto-begin
        final int[] secondsLeft = new int[] { 60 };
        final int[] taskHolder = new int[] { -1 };
        taskHolder[0] = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!battleRunning) { Bukkit.getScheduler().cancelTask(taskHolder[0]); return; }
            int s = secondsLeft[0];
            if (s == 60 || s == 30 || s == 10 || (s <= 5 && s >= 1)) {
                p.sendTitle("§eSetup: " + s + "s", "Arrange your troops", 5, 20, 5);
            }
            if (s <= 0) {
                Bukkit.getScheduler().cancelTask(taskHolder[0]);
                beginCombat();
            }
            secondsLeft[0] = Math.max(0, secondsLeft[0] - 1);
        }, 0L, 20L);

        // Reset any previous command state so a new battle starts in NORMAL mode
        this.commandMode = CommandMode.NORMAL;
        this.followTarget = null;
        this.formationAssignments.clear();

    // spawn enemy troops now but keep them frozen until the barrier drops
    spawnEnemyTroops();

    // just wait for /start to actually drop divider + order combat
    p.sendMessage("§eTroops are ready. Use /start when ready to begin!");
    }

    /**
     * Prepare an admin-created arena like startBattle but without spawning enemy troops
     * or setting battleRunning to true. This lets admins use the same setup tools
     * and formation commands while having full control over spawning.
     */
    public void enterAdminSetup(Player p) {
        arena.createArena();
        arena.buildDivider();
        arena.sendToArena(p);

        p.getInventory().remove(Material.STICK);
        giveCommandSticks(p);

        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);

        Location center = arena.getPlatformCenter();
        infAnchor  = center.clone().add(0, 0, -5);
        archAnchor = center.clone().add(0, 0, -9);

        // freeze troops during admin setup so they don't wander across divider
    setFrozen(true);
    // mark admin setup active so /start can begin combat manually
    this.adminSetup = true;
    // reset selection/command state similar to startBattle
    this.commandMode = CommandMode.NORMAL;
    this.followTarget = null;
    this.formationAssignments.clear();
    }

    /** Put the given player into setup mode (arena created already). Keeps troops frozen and gives command sticks. */
    public void enterSetupMode(Player p) {
        // ensure troops won't act until the wall falls
        setFrozen(true);
        // give normal tools so admin/player can arrange
        giveCommandSticks(p);
    }

    public void beginCombat() {
        // allow beginCombat when a normal battle is running or when an admin-created
        // instance has been prepared (adminSetup = true) and is manually started.
        if (!battleRunning && !adminSetup) return;
        if (!battleRunning && adminSetup) {
            // transition admin setup into a running battle
            battleRunning = true;
            adminSetup = false;
        }
        arena.removeDivider();
    // Unfreeze troops now that the barrier is down and start combat behavior
    setFrozen(false);

    // Ensure all allied mobs have AI enabled and issue an immediate move command
    infantry.forEach(m -> { if (!m.isDead()) { m.setAI(true); } });
    archers.forEach(m -> { if (!m.isDead()) { m.setAI(true); } });

    // Trigger a short reformation burst so troops walk into their combat slots
    infReformTicks = archReformTicks = 80; // ~4s of reforming
    formationManager.initializeFormationAssignments(infantry, infAnchor, infFormation);
    formationManager.moveGroupInFormation(infantry, infAnchor, infFormation);
    formationManager.scheduleFormationBurst(infantry, infAnchor, infFormation);
    formationManager.initializeFormationAssignments(archers, archAnchor, archFormation);
    formationManager.moveGroupInFormation(archers, archAnchor, archFormation);
    formationManager.scheduleFormationBurst(archers, archAnchor, archFormation);

    // Ensure enemy mobs have AI enabled so they can act when combat starts
    enemyInfantry.forEach(m -> { if (!m.isDead()) m.setAI(true); });
    enemyArchers.forEach(m -> { if (!m.isDead()) m.setAI(true); });

    // Clear players' inventories for combat and give a single redstone torch (charge command)
    for (Player pl : Bukkit.getOnlinePlayers()) {
        pl.getInventory().clear();
        try { pl.getInventory().setArmorContents(new ItemStack[0]); } catch (Throwable ignore) {}
        ItemStack charge = new ItemStack(Material.REDSTONE_TORCH);
        ItemMeta cm = charge.getItemMeta(); cm.setDisplayName("§cCharge"); charge.setItemMeta(cm);
        pl.getInventory().setItem(4, charge);
    }

        startCombatTicker();
        // Preserve follow state if a selective follow task is active; otherwise reset
        if (selectiveFollowTaskId != -1 && this.followTarget != null) {
            this.commandMode = CommandMode.FOLLOW;
        } else {
            this.commandMode = CommandMode.NORMAL;
            this.followTarget = null;
        }
        // Disable flight and ensure players are in SURVIVAL when combat actually begins
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.setGameMode(GameMode.SURVIVAL);
            pl.setAllowFlight(false);
            pl.setFlying(false);
        }
    String msg = "§cThe battle has begun!";
    Bukkit.getOnlinePlayers().forEach(pl -> pl.sendMessage(msg));

        // Attempt to scale players and mobs via configured attribute command if present
        tryApplyScaling();
    }

    public void stopBattle() {
        battleRunning = false;
    adminSetup = false;
        stopFormationTicker();
        stopCombatTicker();
    stopAdminSimulation();

        infantry.forEach(e -> { if (!e.isDead()) e.remove(); });
        archers.forEach(e -> { if (!e.isDead()) e.remove(); });
        enemyInfantry.forEach(e -> { if (!e.isDead()) e.remove(); });
        enemyArchers.forEach(e -> { if (!e.isDead()) e.remove(); });

        infantry.clear(); archers.clear();
        enemyInfantry.clear(); enemyArchers.clear();
        archerCd.clear();
        infReformTicks = archReformTicks = 0;

        // remove command tools and sweep only our named battle mobs across all worlds
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.getInventory().remove(Material.LIME_DYE);
            pl.getInventory().remove(Material.IRON_SWORD);
            pl.getInventory().remove(Material.BOW);
            pl.getInventory().remove(Material.SADDLE);
            pl.getInventory().remove(Material.IRON_AXE);
            pl.getInventory().remove(Material.ARROW);
            pl.getInventory().remove(Material.REDSTONE_TORCH);
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

    // Admin simulation: run a light combat loop even when battleRunning is false so admin can test.
    private void startAdminSimulation() {
        if (adminSimTaskId != -1) return;
        adminSimTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            // simple auto combat loop between any alive allies and enemies
            List<LivingEntity> enemyTargets = new ArrayList<>();
            enemyInfantry.stream().filter(e -> !e.isDead()).forEach(enemyTargets::add);
            List<LivingEntity> allyTargets = new ArrayList<>();
            infantry.stream().filter(e -> !e.isDead()).forEach(allyTargets::add);

            if (!enemyTargets.isEmpty()) autoCombat(infantry, archers, enemyTargets);
            if (!allyTargets.isEmpty()) autoCombat(enemyInfantry, new ArrayList<>(), allyTargets);
        }, 10L, 10L);
    }

    private void stopAdminSimulation() {
        if (adminSimTaskId != -1) {
            try { Bukkit.getScheduler().cancelTask(adminSimTaskId); } catch (Throwable ignore) {}
            adminSimTaskId = -1;
        }
    }

    public void giveCommandSticks(Player p) {
        // Primary formation selectors placed in hotbar slots 0..3
        p.getInventory().clear();

        ItemStack everyone = new ItemStack(Material.LIME_DYE);
        ItemMeta em = everyone.getItemMeta(); em.setDisplayName("§aEveryone!"); everyone.setItemMeta(em);

        ItemStack vanguard = new ItemStack(Material.IRON_SWORD);
        ItemMeta vm = vanguard.getItemMeta(); vm.setDisplayName("§7Vanguard"); vanguard.setItemMeta(vm);

        ItemStack arch = new ItemStack(Material.BOW);
        ItemMeta am = arch.getItemMeta(); am.setDisplayName("§bArchers"); arch.setItemMeta(am);

        ItemStack calv = new ItemStack(Material.SADDLE);
        ItemMeta cm = calv.getItemMeta(); cm.setDisplayName("§6Calvary"); calv.setItemMeta(cm);

    p.getInventory().setItem(0, everyone);
    p.getInventory().setItem(1, vanguard);
    p.getInventory().setItem(2, arch);
    p.getInventory().setItem(3, calv);
    }

    /**
     * Attempt to spawn a troop for the player; respects per-player budget.
     * If budget is zero, only allow the free starter that was already given in startBattle.
     */
    public boolean spawnPlayerTroopFor(Player p, Location loc, boolean infantryType) {
        UUID pid = p.getUniqueId();
        int budget = playerTroopBudget.getOrDefault(pid, 0);
        if (budget <= 0) {
            p.sendMessage("§cNo troop budget remaining. Buy troops to spawn more.");
            return false;
        }
        // decrement budget and spawn
        playerTroopBudget.put(pid, budget - 1);
        spawnPlayerTroop(loc, infantryType);
        return true;
    }

    // Only open secondary hotbar when player actually interacts (right-click) with a primary item.
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent ev) {
        if (!battleRunning) return;
        if (ev.getAction() != Action.RIGHT_CLICK_AIR && ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = ev.getPlayer();
        ItemStack inHand = p.getInventory().getItemInMainHand();
        if (inHand == null) return;
        Material m = inHand.getType();

        // Early-start hotbar item (redstone) during setup
        if (m == Material.REDSTONE) {
            ev.setCancelled(true);
            if (combatTaskId == -1) beginCombat();
            return;
        }

        // Charge torch during combat: right-click to order charge
        if (m == Material.REDSTONE_TORCH) {
            ev.setCancelled(true);
            // only meaningful during combat
            if (combatTaskId != -1 && battleRunning) {
                orderCharge();
                p.sendMessage("§cCharge order given!");
                // remove torch from slot 4 to avoid repeated use
                p.getInventory().setItem(4, null);
            }
            return;
        }

        // Primaries are handled by FormationCommandHandler; ignore here to avoid double-handling.
        if (m == Material.LIME_DYE || m == Material.IRON_SWORD || m == Material.BOW || m == Material.SADDLE) {
            ev.setCancelled(true);
            return;
        }

        // If player clicks Back, restore primaries
        if (m == Material.GRAY_DYE) {
            ev.setCancelled(true);
            giveCommandSticks(p);
        }
    }

    // Inventory and drop handling moved to FormationCommandHandler to centralize UI logic.

    // NOTE: scaling players and mobs to 0.5 is not supported directly via the Bukkit API.
    // Native entity/player scaling requires server-specific features or client-side resource packs.
    // Alternatives:
    //  - Spawn 'baby' variants where available (Zombie#setBaby(true)) to approximate smaller mobs.
    //  - Use a third-party plugin (e.g. LibsDisguises or an entity-scaling plugin) or Paper API if available.
    // If you want, I can implement baby-zombies for infantry and keep archers as standard, or add hooks
    // for an external scaling plugin.

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent ev) {
        // cleanup budget state
        playerTroopBudget.remove(ev.getPlayer().getUniqueId());
        // if the quitting player was the follow target, cancel selective follow
        try {
            if (followTarget != null && ev.getPlayer().getUniqueId().equals(followTarget.getUniqueId())) {
                followTarget = null;
                if (selectiveFollowTaskId != -1) {
                    Bukkit.getScheduler().cancelTask(selectiveFollowTaskId);
                    selectiveFollowTaskId = -1;
                }
                this.commandMode = CommandMode.NORMAL;
            }
            // If the player disconnected while in the arena, send them back to their saved overworld
            try { if (arena.isArenaWorld(ev.getPlayer().getWorld())) arena.sendBack(ev.getPlayer()); } catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
    }

    public void spawnPlayerTroop(Location loc, boolean infantryType) {
        if (infantryType) {
            Zombie z = loc.getWorld().spawn(loc, Zombie.class);
            z.setCustomName("Axeman");
            try { z.setCustomNameVisible(true); } catch (Throwable ignore) {}
            try { z.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_AXE)); } catch (Throwable ignore) {}
            // tag entity with subclass metadata for robust selection
            try { z.setMetadata("troop_subclass", new FixedMetadataValue(plugin, "axeman")); } catch (Throwable ignore) {}
            z.setShouldBurnInDay(false);
            // prepare mob (scaling attempted in prepareMob via Attribute.SCALE when available)
            prepareMob(z);
            infantry.add(z);
        } else {
            Skeleton s = loc.getWorld().spawn(loc, Skeleton.class);
            s.setCustomName("Archer");
            try { s.setMetadata("troop_subclass", new FixedMetadataValue(plugin, "archer")); } catch (Throwable ignore) {}
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
            try { bandit.setMetadata("troop_subclass", new FixedMetadataValue(plugin, "bandit")); } catch (Throwable ignore) {}
            prepareMob(bandit);

            // Equip sword
            bandit.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));

            // Buff health
            try { if (bandit.getAttribute(Attribute.MAX_HEALTH) != null) bandit.getAttribute(Attribute.MAX_HEALTH).setBaseValue(30.0); } catch (Exception ignore) {}
            try { bandit.setHealth(30.0); } catch (Exception ignore) {}

            // scaling is handled via Attribute.SCALE where available

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
        try {
            // Try to set a SCALE attribute when available (Paper/modern servers)
            try {
                Attribute a = Attribute.valueOf("SCALE");
                if (a != null && mob.getAttribute(a) != null) {
                    mob.getAttribute(a).setBaseValue(0.5);
                }
            } catch (IllegalArgumentException ignore) {
                // SCALE not present; ignore
            }
        } catch (Throwable ignore) {}
    }

    private void setFrozen(boolean frozen) {
        this.frozen = frozen;
        infantry.forEach(m -> m.setAI(!frozen));
        archers.forEach(m -> m.setAI(!frozen));
        enemyInfantry.forEach(m -> m.setAI(!frozen));
        enemyArchers.forEach(m -> m.setAI(!frozen));
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
                formationManager.moveGroupInFormation(infantry, infAnchor, infFormation);
                infReformTicks = Math.max(0, infReformTicks - 10);
            }
            if (archReforming) {
                formationManager.moveGroupInFormation(archers, archAnchor, archFormation);
                archReformTicks = Math.max(0, archReformTicks - 10);
            }

            // Allies act (skip groups that are reforming)
            if (!(infReforming || archReforming)) {
                if (commandMode == CommandMode.HOLD) {
                    // do nothing – troops stay in place
                } else if (commandMode == CommandMode.FOLLOW && followTarget != null) {
                    formationManager.moveGroupInFormation(infantry, followTarget.getLocation().add(0, 0, 2), infFormation);
                    formationManager.moveGroupInFormation(archers,  followTarget.getLocation().add(0, 0, -2), archFormation);
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

    // Formation logic moved into FormationManager to keep this class focused.
    public void setInfantryFormation(Formation f, Location ignored) {
        this.infFormation = f;
    // if caller provided a specific anchor location, use it; otherwise compute centroid
    if (ignored != null) this.infAnchor = ignored;
    else this.infAnchor = formationManager.centroid(infantry);
    if (frozen) {
            // during setup: snap instantly so the player sees the change
            formationManager.placeGroup(infantry, infAnchor, infFormation);
            infReformTicks = 0;
        } else {
            // mid-battle: walk into the new shape and pause their combat briefly
            infReformTicks = 80; // ~4s
            // preassign slots deterministically so the first move call is stable
            formationManager.initializeFormationAssignments(infantry, infAnchor, infFormation);
            // run a short burst of movement+snap checks so they settle on first command
            formationManager.scheduleFormationBurst(infantry, infAnchor, infFormation);
        }
    }

    /**
     * Set the infantry formation state without issuing movement commands.
     * Useful for Hold commands where troops should keep their current positions.
     */
    public void setInfantryFormationPassive(Formation f) {
        this.infFormation = f;
    }

    public void setArcherFormation(Formation f, Location ignored) {
        this.archFormation = f;
    if (ignored != null) this.archAnchor = ignored;
    else this.archAnchor = formationManager.centroid(archers);
        if (frozen) {
            formationManager.placeGroup(archers, archAnchor, archFormation);
            archReformTicks = 0;
        } else {
            archReformTicks = 80;
            // preassign slots deterministically so the first move call is stable
            formationManager.initializeFormationAssignments(archers, archAnchor, archFormation);
            formationManager.scheduleFormationBurst(archers, archAnchor, archFormation);
        }
    }

    /**
     * Set the archer formation state without issuing movement commands.
     */
    public void setArcherFormationPassive(Formation f) {
        this.archFormation = f;
    }

    /**
     * Apply a formation to a selected subset of troops. If the battle is in setup
     * (frozen) the formation will snap into place; otherwise troops will be
     * asked to walk into formation and a short movement burst will be scheduled.
     *
     * @param playerId player who issued the command (currently unused but kept for future)
     * @param subclassTag "axeman", "spearman" or null to select all
     * @param includeInfantry whether infantry should be targeted
     * @param includeArchers whether archers should be targeted
     * @param formation desired formation
     * @param anchor formation anchor location (if null, arena center is used)
     */
    public void applyFormationToSelection(java.util.UUID playerId, String subclassTag, boolean includeInfantry, boolean includeArchers, Formation formation, Location anchor) {
        if (anchor == null) anchor = arena.getPlatformCenter();

        if (includeInfantry) {
            List<Zombie> targets = new ArrayList<>();
            for (Zombie z : infantry) {
                if (z.isDead()) continue;
                if (subclassTag == null || subclassTag.isEmpty() || hasTag(z, subclassTag)) targets.add(z);
            }
            if (!targets.isEmpty()) {
                if (frozen) {
                    // snap into place during setup
                    formationManager.placeGroup(targets, anchor, formation);
                } else {
                    formationManager.initializeFormationAssignments(targets, anchor, formation);
                    formationManager.moveGroupInFormation(targets, anchor, formation);
                    formationManager.scheduleFormationBurst(targets, anchor, formation);
                }
            }
        }

        if (includeArchers) {
            List<Skeleton> targets = new ArrayList<>();
            for (Skeleton s : archers) {
                if (s.isDead()) continue;
                if (subclassTag == null || subclassTag.isEmpty() || hasTag(s, subclassTag)) targets.add(s);
            }
            if (!targets.isEmpty()) {
                if (frozen) {
                    formationManager.placeGroup(targets, anchor, formation);
                } else {
                    formationManager.initializeFormationAssignments(targets, anchor, formation);
                    formationManager.moveGroupInFormation(targets, anchor, formation);
                    formationManager.scheduleFormationBurst(targets, anchor, formation);
                }
            }
        }
    }

    // Side-aware APIs for admin mode: apply formation to either player or enemy side depending on `onEnemy`.
    public void applyFormationToSide(boolean onEnemy, String subclassTag, boolean includeInfantry, boolean includeArchers, Formation formation, Location anchor) {
        if (onEnemy) {
            // operate only on enemy lists
            List<Zombie> targets = new ArrayList<>();
            for (Zombie z : enemyInfantry) if (!z.isDead() && (subclassTag == null || subclassTag.isEmpty() || hasTag(z, subclassTag))) targets.add(z);
            if (!targets.isEmpty()) {
                if (frozen) formationManager.placeGroup(targets, anchor, formation);
                else { formationManager.initializeFormationAssignments(targets, anchor, formation); formationManager.moveGroupInFormation(targets, anchor, formation); formationManager.scheduleFormationBurst(targets, anchor, formation); }
            }
            // archers not supported for enemy in this admin flow for now
        } else {
            // operate on player-side lists
            if (includeInfantry) {
                List<Zombie> targets = new ArrayList<>();
                for (Zombie z : infantry) if (!z.isDead() && (subclassTag == null || subclassTag.isEmpty() || hasTag(z, subclassTag))) targets.add(z);
                if (!targets.isEmpty()) {
                    if (frozen) formationManager.placeGroup(targets, anchor, formation);
                    else { formationManager.initializeFormationAssignments(targets, anchor, formation); formationManager.moveGroupInFormation(targets, anchor, formation); formationManager.scheduleFormationBurst(targets, anchor, formation); }
                }
            }
            if (includeArchers) {
                List<Skeleton> targets = new ArrayList<>();
                for (Skeleton s : archers) if (!s.isDead() && (subclassTag == null || subclassTag.isEmpty() || hasTag(s, subclassTag))) targets.add(s);
                if (!targets.isEmpty()) {
                    if (frozen) formationManager.placeGroup(targets, anchor, formation);
                    else { formationManager.initializeFormationAssignments(targets, anchor, formation); formationManager.moveGroupInFormation(targets, anchor, formation); formationManager.scheduleFormationBurst(targets, anchor, formation); }
                }
            }
        }
    }

    // formation bursts delegated to FormationManager

    // Deterministically assign each alive troop to a centered slot so the first reform
    // tick will already have stable assignments and snap behavior.
    // initialization delegated to FormationManager

    // centroid delegated to FormationManager

    // Attempt to scale players and mobs using a configurable server command template
    // The plugin config may provide a `scalingCommand` such as:
    //   attribute scale set {target} {scale}
    // where {target} and {scale} are placeholders. This method dispatches that
    // command for each online player and for any named battle mobs (UUID is used
    // as the default mob placeholder). If no template is configured, this is a no-op.
    private void tryApplyScaling() {
        String template = plugin.getConfig().getString("scalingCommand");
        if (template == null || template.isBlank()) return;
        String scale = plugin.getConfig().getString("scalingValue", "0.5");

        // Scale players
        for (Player pl : Bukkit.getOnlinePlayers()) {
            String cmd = template.replace("{target}", pl.getName()).replace("{scale}", scale);
            Bukkit.getLogger().info(String.format("Dispatching scaling command: %s", cmd));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }

        // Scale named battle mobs (replace {target} with the entity UUID)
        for (World w : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity ent : w.getEntities()) {
                if (ent instanceof LivingEntity) {
                    String n = ent.getCustomName();
                    if ("Infantry".equals(n) || "Archer".equals(n)
                            || "Enemy Infantry".equals(n) || "Enemy Archer".equals(n)) {
                        String target = ent.getUniqueId().toString();
                        String cmd = template.replace("{target}", target).replace("{scale}", scale);
                        Bukkit.getLogger().info(String.format("Dispatching scaling command for mob: %s", cmd));
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    }
                }
            }
        }
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
        // If a selective follow is active, cancel it so troops stop following the player
        if (selectiveFollowTaskId != -1) {
            try { Bukkit.getScheduler().cancelTask(selectiveFollowTaskId); } catch (Throwable ignore) {}
            selectiveFollowTaskId = -1;
        }
        this.followTarget = null;

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
    // quick formation movement so troops visibly surge forward when charged
    formationManager.scheduleFormationBurst(infantry, infAnchor, infFormation);
    formationManager.scheduleFormationBurst(archers, archAnchor, archFormation);
    }


    public void orderFollow(Player p) {
        this.commandMode = CommandMode.FOLLOW;
        this.followTarget = p;

        // Unfreeze troops so they can move again
        infantry.forEach(m -> m.setAI(true));
        archers.forEach(m -> m.setAI(true));
        // immediately move groups to positions around the player so the reaction is visible
        formationManager.initializeFormationAssignments(infantry, p.getLocation().add(0, 0, 2), infFormation);
        formationManager.moveGroupInFormation(infantry, p.getLocation().add(0, 0, 2), infFormation);
        formationManager.scheduleFormationBurst(infantry, p.getLocation().add(0, 0, 2), infFormation);
        formationManager.initializeFormationAssignments(archers, p.getLocation().add(0, 0, -2), archFormation);
        formationManager.moveGroupInFormation(archers, p.getLocation().add(0, 0, -2), archFormation);
        formationManager.scheduleFormationBurst(archers, p.getLocation().add(0, 0, -2), archFormation);
        }

    public void orderHold() {
        this.commandMode = CommandMode.HOLD;
        infantry.forEach(m -> m.setAI(false));
        archers.forEach(m -> m.setAI(false));
    }

    // Selective orders that apply only to troops with matching metadata tag.
    public void followSelection(Player p, String subclassTag, boolean includeArchers) {
        this.commandMode = CommandMode.FOLLOW;
        this.followTarget = p;

        // cancel any previous selective follow task
        if (selectiveFollowTaskId != -1) {
            Bukkit.getScheduler().cancelTask(selectiveFollowTaskId);
            selectiveFollowTaskId = -1;
        }

        // unfreeze targeted mobs so they can move
        infantry.forEach(m -> {
            try { if (hasTag(m, subclassTag)) m.setAI(true); } catch (Throwable ignore) {}
        });
        if (includeArchers) {
            archers.forEach(s -> {
                try { if (hasTag(s, subclassTag)) s.setAI(true); } catch (Throwable ignore) {}
            });
        }

        // schedule a repeating follow task that updates selective groups frequently
        selectiveFollowTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            // keep the task running during admin-created setup (arena world) even when
            // battleRunning is false so admins can use selective follow while arranging.
            if (followTarget == null) {
                if (selectiveFollowTaskId != -1) {
                    Bukkit.getScheduler().cancelTask(selectiveFollowTaskId);
                    selectiveFollowTaskId = -1;
                }
                return;
            }
            if (!battleRunning) {
                // if not in a running battle, only allow when the target player is inside the arena
                try {
                    if (!arena.isArenaWorld(followTarget.getWorld())) {
                        if (selectiveFollowTaskId != -1) {
                            Bukkit.getScheduler().cancelTask(selectiveFollowTaskId);
                            selectiveFollowTaskId = -1;
                        }
                        return;
                    }
                } catch (Throwable ignore) {
                    if (selectiveFollowTaskId != -1) {
                        Bukkit.getScheduler().cancelTask(selectiveFollowTaskId);
                        selectiveFollowTaskId = -1;
                    }
                    return;
                }
            }

            Location anchor = followTarget.getLocation();
            formationManager.moveGroupInFormation(infantry.stream().filter(m -> !m.isDead() && hasTag(m, subclassTag)).toList(), anchor.clone().add(0, 0, 2), infFormation);
            if (includeArchers) formationManager.moveGroupInFormation(archers.stream().filter(s -> !s.isDead() && hasTag(s, subclassTag)).toList(), anchor.clone().add(0, 0, -2), archFormation);
        }, 0L, 10L);
    }

    public void holdSelection(String subclassTag, boolean includeArchers) {
        this.commandMode = CommandMode.HOLD;
        // disable AI only for matching troops
        infantry.forEach(m -> {
            try { if (hasTag(m, subclassTag)) m.setAI(false); } catch (Throwable ignore) {}
        });
        if (includeArchers) archers.forEach(s -> {
            try { if (hasTag(s, subclassTag)) s.setAI(false); } catch (Throwable ignore) {}
        });
    }

    public void chargeSelection(String subclassTag, boolean includeArchers) {
        this.commandMode = CommandMode.CHARGE;
        if (selectiveFollowTaskId != -1) {
            try { Bukkit.getScheduler().cancelTask(selectiveFollowTaskId); } catch (Throwable ignore) {}
            selectiveFollowTaskId = -1;
        }
        this.followTarget = null;
        // targeted charge: set targets only for matching troops
        for (Zombie z : infantry) {
            try {
                if (!hasTag(z, subclassTag)) continue;
                z.setAI(true);
                LivingEntity target = nearest(z.getLocation(), new ArrayList<>(enemyInfantry));
                if (target != null) z.setTarget(target);
            } catch (Throwable ignore) {}
        }
        if (includeArchers) for (Skeleton s : archers) {
            try {
                if (!hasTag(s, subclassTag)) continue;
                s.setAI(true);
                LivingEntity target = nearest(s.getLocation(), new ArrayList<>(enemyInfantry));
                if (target != null) s.setTarget(target);
            } catch (Throwable ignore) {}
        }
        formationManager.scheduleFormationBurst(infantry, infAnchor, infFormation);
        formationManager.scheduleFormationBurst(archers, archAnchor, archFormation);
    }

    private boolean hasTag(org.bukkit.entity.Entity e, String tag) {
    if (e == null) return false;
    // empty or null tag means match all
    if (tag == null || tag.isEmpty()) return true;
        try {
            if (e.hasMetadata("troop_subclass")) {
                return e.getMetadata("troop_subclass").stream().anyMatch(md -> tag.equalsIgnoreCase(md.asString()));
            }
        } catch (Throwable ignore) {}
        return false;
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
            if (!endScheduled) {
                endScheduled = true;
                String winnerMsg = !enemiesAlive ? "§aYou won!" : "§cYou lost!";
                Bukkit.getOnlinePlayers().forEach(pl -> pl.sendTitle(winnerMsg, "Returning in 10s...", 5, 40, 5));
                // delay actual stop to allow celebration / rewards
                Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> stopBattle(), 20L * 10);
            }
        }
    }

    @EventHandler
    public void onArenaMobDeath(org.bukkit.event.entity.EntityDeathEvent ev) {
        org.bukkit.entity.Entity ent = ev.getEntity();
        if (ent instanceof Zombie || ent instanceof Skeleton) {
            String n = ent.getCustomName();
            if ("Infantry".equals(n) || "Archer".equals(n)
                    || "Enemy Infantry".equals(n) || "Enemy Archer".equals(n) || "Bandit".equals(n)) {
                ev.getDrops().clear();
                ev.setDroppedExp(0);
                // roll for a drop and drop it into the world if present
                try {
                    org.bukkit.inventory.ItemStack drop = RewardService.rollDrop(ent);
                    if (drop != null) ent.getWorld().dropItemNaturally(ent.getLocation(), drop);
                } catch (Throwable ignore) {}
                // show a quick title to all players in arena announcing the kill
                try { Bukkit.getOnlinePlayers().forEach(pl -> TitleHelper.showTitle(pl, "§aTroop down", "A troop has fallen")); } catch (Throwable ignore) {}
            }
        }
    }

    public List<Zombie> getInfantry() { return infantry; }
    public List<Skeleton> getArchers() { return archers; }
    public boolean isBattleRunning() { return battleRunning; }
    /** Returns true when combat (the repeating combat ticker) is active */
    public boolean isCombatRunning() { return combatTaskId != -1; }

    public ArenaManager getArenaManager() { return this.arena; }

    public boolean isAdminSetup() { return this.adminSetup; }
}
