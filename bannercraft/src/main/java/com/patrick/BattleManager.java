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

    private final List<Zombie> enemyInfantry = new ArrayList<>();
    private final List<Skeleton> enemyArchers = new ArrayList<>();

    private boolean battleRunning = false;
    private int formationTaskId = -1;
    private int combatTaskId = -1;

    private Formation infFormation = Formation.SHIELD_WALL;
    private Formation archFormation = Formation.LINE;
    private Location infAnchor, archAnchor;

    private enum CommandMode { NORMAL, CHARGE, FOLLOW, HOLD }
    private CommandMode commandMode = CommandMode.NORMAL;
    private Player followTarget = null;

    private boolean frozen = false;
    private boolean adminSetup = false;
    private int selectiveFollowTaskId = -1;
    private int adminSimTaskId = -1;

    private final Map<UUID, Integer> archerCd = new HashMap<>();
    private final Map<UUID, Integer> playerTroopBudget = new HashMap<>();
    private final Map<UUID, Integer> formationAssignments = new HashMap<>();

    private int infReformTicks = 0;
    private int archReformTicks = 0;
    private boolean endScheduled = false;

    /* BattleManager
    Inputs: BannerCraft plugin
    Description: Constructor, initializes formation manager and registers handlers
    Returns: none */
    public BattleManager(BannerCraft plugin) {
        this.plugin = plugin;
        this.formationManager = new FormationManager(plugin, arena);
        Bukkit.getPluginManager().registerEvents(new FormationCommandHandler(plugin, this, formationManager), plugin);
    }

    /* spawnAdminZombie (overload)
    Inputs: Location loc, InfantryType type
    Description: Overload that spawns on player side by default
    Returns: void */
    public void spawnAdminZombie(Location loc, InfantryType type) {
        spawnAdminZombie(loc, type, false);
    }

    public enum InfantryType { AXE, SWORD_SHIELD, SPEAR_SHIELD, MOUNTED_SPEAR }

    /* spawnAdminZombie
    Inputs: Location loc, InfantryType type, boolean enemy
    Description: Spawn an admin/test zombie variant and tag/configure it
    Returns: void */
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
                try { z.setAI(!frozen); } catch (Throwable ignore) {}
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
                try {
                    org.bukkit.entity.Horse horse = loc.getWorld().spawn(loc.clone().add(0.5, 0, 0.5), org.bukkit.entity.Horse.class);
                    horse.setAdult();
                    try { horse.setTamed(true); } catch (Throwable ignore) {}
                    try { horse.getInventory().setSaddle(new ItemStack(Material.SADDLE)); } catch (Throwable ignore) {}
                    try { horse.setAI(!frozen); } catch (Throwable ignore) {}
                    try { horse.setMetadata("troop_subclass", new FixedMetadataValue(plugin, "mounted")); } catch (Throwable ignore) {}
                    try { horse.addPassenger(z); } catch (Throwable ignore) {}
                    try {
                        Location faceTo = enemy ? arena.getPlayerSideCenter() : arena.getEnemySideCenter();
                        horse.teleport(horse.getLocation().setDirection(faceTo.toVector().subtract(horse.getLocation().toVector())));
                    } catch (Throwable ignore) {}
                } catch (Throwable ignore) {}
            }
        }

        try {
            String template = plugin.getConfig().getString("scalingCommand");
            String scale = plugin.getConfig().getString("scalingValue", "0.5");
            if (template != null && !template.isBlank()) {
                String cmd = template.replace("{target}", z.getUniqueId().toString()).replace("{scale}", scale);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } else {
                String cmd = String.format("minecraft:scale base set %s %s", z.getUniqueId().toString(), "0.5");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        } catch (Throwable ignore) {}
    }

    /* startBattle
    Inputs: Player p
    Description: Prepare and start a normal instanced battle with a 60s setup timer
    Returns: void */
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

        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);

        Location center = arena.getPlatformCenter();
        infAnchor = center.clone().add(0, 0, -5);
        archAnchor = center.clone().add(0, 0, -9);

        UUID pid = p.getUniqueId();
        if (!playerTroopBudget.containsKey(pid)) {
            playerTroopBudget.put(pid, 0);
        }
        spawnPlayerTroop(infAnchor.clone(), true);
        formationManager.placeGroup(infantry, infAnchor, infFormation);

        setFrozen(true);

        ItemStack early = new ItemStack(Material.REDSTONE);
        ItemMeta earlyMeta = early.getItemMeta();
        earlyMeta.setDisplayName("§cStart Battle Early");
        early.setItemMeta(earlyMeta);
        p.getInventory().setItem(4, early);

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

        this.commandMode = CommandMode.NORMAL;
        this.followTarget = null;
        this.formationAssignments.clear();

        spawnEnemyTroops();

        p.sendMessage("§eTroops are ready. Use /start when ready to begin!");
    }

    /* enterAdminSetup
    Inputs: Player p
    Description: Prepare an admin-created arena like startBattle but without spawning enemy troops
    or setting battleRunning to true. Allows admins to use setup tools and control spawning.
    Returns: void */
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

        setFrozen(true);
        this.adminSetup = true;
        this.commandMode = CommandMode.NORMAL;
        this.followTarget = null;
        this.formationAssignments.clear();
    }

    /* enterSetupMode
    Inputs: Player p
    Description: Put the given player into setup mode (arena created already). Keeps troops frozen and gives command sticks.
    Returns: void */
    public void enterSetupMode(Player p) {
        setFrozen(true);
        giveCommandSticks(p);
    }

    /* beginCombat
    Inputs: none
    Description: Transition an arena from setup/admin state into active combat. Removes divider, unfreezes troops,
    enables AI, initializes formation bursts, starts combat ticker, and applies scaling.
    Returns: void */
    public void beginCombat() {
        if (!battleRunning && !adminSetup) return;
        if (!battleRunning && adminSetup) {
            battleRunning = true;
            adminSetup = false;
        }
        arena.removeDivider();
        setFrozen(false);

        infantry.forEach(m -> { if (!m.isDead()) { m.setAI(true); } });
        archers.forEach(m -> { if (!m.isDead()) { m.setAI(true); } });

        infReformTicks = archReformTicks = 80;
        formationManager.initializeFormationAssignments(infantry, infAnchor, infFormation);
        formationManager.moveGroupInFormation(infantry, infAnchor, infFormation);
        formationManager.scheduleFormationBurst(infantry, infAnchor, infFormation);
        formationManager.initializeFormationAssignments(archers, archAnchor, archFormation);
        formationManager.moveGroupInFormation(archers, archAnchor, archFormation);
        formationManager.scheduleFormationBurst(archers, archAnchor, archFormation);

        enemyInfantry.forEach(m -> { if (!m.isDead()) m.setAI(true); });
        enemyArchers.forEach(m -> { if (!m.isDead()) m.setAI(true); });

        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.getInventory().clear();
            try { pl.getInventory().setArmorContents(new ItemStack[0]); } catch (Throwable ignore) {}
            ItemStack charge = new ItemStack(Material.REDSTONE_TORCH);
            ItemMeta cm = charge.getItemMeta(); cm.setDisplayName("§cCharge"); charge.setItemMeta(cm);
            pl.getInventory().setItem(4, charge);
        }

        startCombatTicker();
        if (selectiveFollowTaskId != -1 && this.followTarget != null) {
            this.commandMode = CommandMode.FOLLOW;
        } else {
            this.commandMode = CommandMode.NORMAL;
            this.followTarget = null;
        }
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.setGameMode(GameMode.SURVIVAL);
            pl.setAllowFlight(false);
            pl.setFlying(false);
        }
        String msg = "§cThe battle has begun!";
        Bukkit.getOnlinePlayers().forEach(pl -> pl.sendMessage(msg));

        tryApplyScaling();
    }

    /* stopBattle
    Inputs: none
    Description: Stop the current battle, cancel tickers and tasks, remove and clear all troops,
    clear inventories for players and teleport them back, and sweep named battle mobs across worlds.
    Returns: void */
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

    /* startAdminSimulation
    Inputs: none
    Description: Start a lightweight admin simulation task that runs auto combat loops while not in a battle.
    Returns: void */
    private void startAdminSimulation() {
        if (adminSimTaskId != -1) return;
        adminSimTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            List<LivingEntity> enemyTargets = new ArrayList<>();
            enemyInfantry.stream().filter(e -> !e.isDead()).forEach(enemyTargets::add);
            List<LivingEntity> allyTargets = new ArrayList<>();
            infantry.stream().filter(e -> !e.isDead()).forEach(allyTargets::add);

            if (!enemyTargets.isEmpty()) autoCombat(infantry, archers, enemyTargets);
            if (!allyTargets.isEmpty()) autoCombat(enemyInfantry, new ArrayList<>(), allyTargets);
        }, 10L, 10L);
    }

    /* stopAdminSimulation
    Inputs: none
    Description: Stop the admin simulation task if running.
    Returns: void */
    private void stopAdminSimulation() {
        if (adminSimTaskId != -1) {
            try { Bukkit.getScheduler().cancelTask(adminSimTaskId); } catch (Throwable ignore) {}
            adminSimTaskId = -1;
        }
    }

    /* giveCommandSticks
    Inputs: Player p
    Description: Populate the player's hotbar with primary formation selector items.
    Returns: void */
    public void giveCommandSticks(Player p) {
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

    /* spawnPlayerTroopFor
    Inputs: Player p, Location loc, boolean infantryType
    Description: Attempt to spawn a troop for the player; respects per-player budget. If budget is zero,
    disallow additional spawns and inform the player.
    Returns: boolean indicating success */
    public boolean spawnPlayerTroopFor(Player p, Location loc, boolean infantryType) {
        UUID pid = p.getUniqueId();
        int budget = playerTroopBudget.getOrDefault(pid, 0);
        if (budget <= 0) {
            p.sendMessage("§cNo troop budget remaining. Buy troops to spawn more.");
            return false;
        }
        playerTroopBudget.put(pid, budget - 1);
        spawnPlayerTroop(loc, infantryType);
        return true;
    }

    /* onPlayerInteract
    Inputs: PlayerInteractEvent ev
    Description: Handle right-click interactions for setup (early start) and combat (charge torch); primary selectors are handled elsewhere.
    Returns: void */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent ev) {
        if (!battleRunning) return;
        if (ev.getAction() != Action.RIGHT_CLICK_AIR && ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = ev.getPlayer();
        ItemStack inHand = p.getInventory().getItemInMainHand();
        if (inHand == null) return;
        Material m = inHand.getType();

        if (m == Material.REDSTONE) {
            ev.setCancelled(true);
            if (combatTaskId == -1) beginCombat();
            return;
        }

        if (m == Material.REDSTONE_TORCH) {
            ev.setCancelled(true);
            if (combatTaskId != -1 && battleRunning) {
                orderCharge();
                p.sendMessage("§cCharge order given!");
                p.getInventory().setItem(4, null);
            }
            return;
        }

        if (m == Material.LIME_DYE || m == Material.IRON_SWORD || m == Material.BOW || m == Material.SADDLE) {
            ev.setCancelled(true);
            return;
        }

        if (m == Material.GRAY_DYE) {
            ev.setCancelled(true);
            giveCommandSticks(p);
        }
    }

    /* onPlayerQuit
    Inputs: PlayerQuitEvent ev
    Description: Cleanup budget state, cancel selective follow when the follow target disconnects, and send player back to saved overworld location if they disconnected from the arena.
    Returns: void */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent ev) {
        playerTroopBudget.remove(ev.getPlayer().getUniqueId());
        try {
            if (followTarget != null && ev.getPlayer().getUniqueId().equals(followTarget.getUniqueId())) {
                followTarget = null;
                if (selectiveFollowTaskId != -1) {
                    Bukkit.getScheduler().cancelTask(selectiveFollowTaskId);
                    selectiveFollowTaskId = -1;
                }
                this.commandMode = CommandMode.NORMAL;
            }
            try { if (arena.isArenaWorld(ev.getPlayer().getWorld())) arena.sendBack(ev.getPlayer()); } catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
    }

    /* spawnPlayerTroop
    Inputs: Location loc, boolean infantryType
    Description: Spawn either an infantry or archer troop at the given location and prepare the mob.
    Returns: void */
    public void spawnPlayerTroop(Location loc, boolean infantryType) {
        if (infantryType) {
            Zombie z = loc.getWorld().spawn(loc, Zombie.class);
            z.setCustomName("Axeman");
            try { z.setCustomNameVisible(true); } catch (Throwable ignore) {}
            try { z.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_AXE)); } catch (Throwable ignore) {}
            try { z.setMetadata("troop_subclass", new FixedMetadataValue(plugin, "axeman")); } catch (Throwable ignore) {}
            z.setShouldBurnInDay(false);
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

    /* spawnEnemyTroops
    Inputs: none
    Description: Spawn a preset group of enemy infantry and configure their equipment and health.
    Returns: void */
    private void spawnEnemyTroops() {
        Location center = arena.getEnemySideCenter();
        for (int i = 0; i < 5; i++) {
            Zombie bandit = center.getWorld().spawn(center.clone().add(i, 0, 2), Zombie.class);
            bandit.setCustomName("Bandit");
            bandit.setShouldBurnInDay(false);
            try { bandit.setMetadata("troop_subclass", new FixedMetadataValue(plugin, "bandit")); } catch (Throwable ignore) {}
            prepareMob(bandit);

            bandit.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));

            try { if (bandit.getAttribute(Attribute.MAX_HEALTH) != null) bandit.getAttribute(Attribute.MAX_HEALTH).setBaseValue(30.0); } catch (Exception ignore) {}
            try { bandit.setHealth(30.0); } catch (Exception ignore) {}

            enemyInfantry.add(bandit);
        }
    }

    /* prepareMob
    Inputs: Mob mob
    Description: Remove default goals, lock pickup/targeting settings, and attempt to set SCALE attribute when present.
    Returns: void */
    private void prepareMob(Mob mob) {
        Bukkit.getMobGoals().removeAllGoals(mob);
        mob.setCollidable(true);
        mob.setCanPickupItems(false);
        mob.setTarget(null);
        mob.setInvulnerable(false);
        mob.setRemoveWhenFarAway(false);
        try {
            try {
                var attr = mob.getAttribute(Attribute.SCALE);
                if (attr != null) attr.setBaseValue(0.5);
            } catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
    }

    /* setFrozen
    Inputs: boolean frozen
    Description: Toggle frozen state and enable/disable AI for all troop groups.
    Returns: void */
    private void setFrozen(boolean frozen) {
        this.frozen = frozen;
        infantry.forEach(m -> m.setAI(!frozen));
        archers.forEach(m -> m.setAI(!frozen));
        enemyInfantry.forEach(m -> m.setAI(!frozen));
        enemyArchers.forEach(m -> m.setAI(!frozen));
    }

    /* stopFormationTicker
    Inputs: none
    Description: Cancel the scheduled formation task if present.
    Returns: void */
    private void stopFormationTicker() {
        if (formationTaskId != -1) {
            Bukkit.getScheduler().cancelTask(formationTaskId);
            formationTaskId = -1;
        }
    }

    /* startCombatTicker
    Inputs: none
    Description: Start the repeating combat ticker which handles reforming, AI actions, enemy actions, and cooldowns.
    Returns: void */
    private void startCombatTicker() {
        stopCombatTicker();
        combatTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!battleRunning) return;

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

            if (!(infReforming || archReforming)) {
                if (commandMode == CommandMode.HOLD) {
                } else if (commandMode == CommandMode.FOLLOW && followTarget != null) {
                    formationManager.moveGroupInFormation(infantry, followTarget.getLocation().add(0, 0, 2), infFormation);
                    formationManager.moveGroupInFormation(archers,  followTarget.getLocation().add(0, 0, -2), archFormation);
                } else if (commandMode == CommandMode.CHARGE) {
                    List<LivingEntity> enemyTargets = new ArrayList<>();
                    enemyInfantry.stream().filter(e -> !e.isDead()).forEach(enemyTargets::add);
                    autoCombat(infantry, archers, enemyTargets);
                }
            }

            List<LivingEntity> allyTargets = new ArrayList<>();
            infantry.stream().filter(e -> !e.isDead()).forEach(allyTargets::add);
            archers.stream().filter(e -> !e.isDead()).forEach(allyTargets::add);
            autoCombat(enemyInfantry, new ArrayList<>(), allyTargets);

            checkWinLose();

            archerCd.replaceAll((id, cd) -> Math.max(0, cd - 10));
        }, 10L, 10L);
    }

    /* stopCombatTicker
    Inputs: none
    Description: Cancel the combat ticker if running.
    Returns: void */
    private void stopCombatTicker() {
        if (combatTaskId != -1) {
            Bukkit.getScheduler().cancelTask(combatTaskId);
            combatTaskId = -1;
        }
    }

    /* setInfantryFormation
    Inputs: Formation f, Location ignored
    Description: Set infantry formation and anchor; snap instantly during setup or schedule reforming during combat.
    Returns: void */
    public void setInfantryFormation(Formation f, Location ignored) {
        this.infFormation = f;
        if (ignored != null) this.infAnchor = ignored;
        else this.infAnchor = formationManager.centroid(infantry);
        if (frozen) {
            formationManager.placeGroup(infantry, infAnchor, infFormation);
            infReformTicks = 0;
        } else {
            infReformTicks = 80;
            formationManager.initializeFormationAssignments(infantry, infAnchor, infFormation);
            formationManager.scheduleFormationBurst(infantry, infAnchor, infFormation);
        }
    }

    /* setInfantryFormationPassive
    Inputs: Formation f
    Description: Set the infantry formation state without issuing movement commands.
    Returns: void */
    public void setInfantryFormationPassive(Formation f) {
        this.infFormation = f;
    }

    /* setArcherFormation
    Inputs: Formation f, Location ignored
    Description: Set archer formation and anchor; snap during setup or schedule reforming during combat.
    Returns: void */
    public void setArcherFormation(Formation f, Location ignored) {
        this.archFormation = f;
        if (ignored != null) this.archAnchor = ignored;
        else this.archAnchor = formationManager.centroid(archers);
        if (frozen) {
            formationManager.placeGroup(archers, archAnchor, archFormation);
            archReformTicks = 0;
        } else {
            archReformTicks = 80;
            formationManager.initializeFormationAssignments(archers, archAnchor, archFormation);
            formationManager.scheduleFormationBurst(archers, archAnchor, archFormation);
        }
    }

    /* setArcherFormationPassive
    Inputs: Formation f
    Description: Set the archer formation state without issuing movement commands.
    Returns: void */
    public void setArcherFormationPassive(Formation f) {
        this.archFormation = f;
    }

    /* applyFormationToSelection
    Inputs: UUID playerId, String subclassTag, boolean includeInfantry, boolean includeArchers, Formation formation, Location anchor
    Description: Apply a formation to a selected subset of troops. Snap into place during setup; otherwise schedule movement.
    Returns: void */
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

    /* applyFormationToSide
    Inputs: boolean onEnemy, String subclassTag, boolean includeInfantry, boolean includeArchers, Formation formation, Location anchor
    Description: Side-aware API to apply formation to the player or enemy side depending on onEnemy.
    Returns: void */
    public void applyFormationToSide(boolean onEnemy, String subclassTag, boolean includeInfantry, boolean includeArchers, Formation formation, Location anchor) {
        if (onEnemy) {
            List<Zombie> targets = new ArrayList<>();
            for (Zombie z : enemyInfantry) if (!z.isDead() && (subclassTag == null || subclassTag.isEmpty() || hasTag(z, subclassTag))) targets.add(z);
            if (!targets.isEmpty()) {
                if (frozen) formationManager.placeGroup(targets, anchor, formation);
                else { formationManager.initializeFormationAssignments(targets, anchor, formation); formationManager.moveGroupInFormation(targets, anchor, formation); formationManager.scheduleFormationBurst(targets, anchor, formation); }
            }
        } else {
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

    /* tryApplyScaling
    Inputs: none
    Description: Dispatch configured scaling command template for online players and named battle mobs; no-op if no template configured.
    Returns: void */
    private void tryApplyScaling() {
        String template = plugin.getConfig().getString("scalingCommand");
        if (template == null || template.isBlank()) return;
        String scale = plugin.getConfig().getString("scalingValue", "0.5");

        for (Player pl : Bukkit.getOnlinePlayers()) {
            String cmd = template.replace("{target}", pl.getName()).replace("{scale}", scale);
            Bukkit.getLogger().info(String.format("Dispatching scaling command: %s", cmd));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }

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

    /* autoCombat
    Inputs: List<Zombie> meleeAllies, List<Skeleton> rangedAllies, List<LivingEntity> enemies
    Description: Simple auto-combat loop that handles melee movement/attacks and archer behavior.
    Returns: void */
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

    /* orderCharge
    Inputs: none
    Description: Switch troops to CHARGE mode and assign targets accordingly.
    Returns: void */
    public void orderCharge() {
        this.commandMode = CommandMode.CHARGE;
        if (selectiveFollowTaskId != -1) {
            try { Bukkit.getScheduler().cancelTask(selectiveFollowTaskId); } catch (Throwable ignore) {}
            selectiveFollowTaskId = -1;
        }
        this.followTarget = null;

        for (Zombie z : infantry) {
            z.setAI(true);
            LivingEntity target = nearest(z.getLocation(), new ArrayList<>(enemyInfantry));
            if (target != null) {
                z.setTarget(target);
            }
        }

        for (Skeleton s : archers) {
            s.setAI(true);
            LivingEntity target = nearest(s.getLocation(), new ArrayList<>(enemyInfantry));
            if (target != null) {
                s.setTarget(target);
            }
        }
        formationManager.scheduleFormationBurst(infantry, infAnchor, infFormation);
        formationManager.scheduleFormationBurst(archers, archAnchor, archFormation);
    }

    /* orderFollow
    Inputs: Player p
    Description: Set FOLLOW mode and move troops to positions around the player.
    Returns: void */
    public void orderFollow(Player p) {
        this.commandMode = CommandMode.FOLLOW;
        this.followTarget = p;

        infantry.forEach(m -> m.setAI(true));
        archers.forEach(m -> m.setAI(true));
        formationManager.initializeFormationAssignments(infantry, p.getLocation().add(0, 0, 2), infFormation);
        formationManager.moveGroupInFormation(infantry, p.getLocation().add(0, 0, 2), infFormation);
        formationManager.scheduleFormationBurst(infantry, p.getLocation().add(0, 0, 2), infFormation);
        formationManager.initializeFormationAssignments(archers, p.getLocation().add(0, 0, -2), archFormation);
        formationManager.moveGroupInFormation(archers, p.getLocation().add(0, 0, -2), archFormation);
        formationManager.scheduleFormationBurst(archers, p.getLocation().add(0, 0, -2), archFormation);
    }

    /* orderHold
    Inputs: none
    Description: Set HOLD mode and disable AI for all troops.
    Returns: void */
    public void orderHold() {
        this.commandMode = CommandMode.HOLD;
        infantry.forEach(m -> m.setAI(false));
        archers.forEach(m -> m.setAI(false));
    }

    /* followSelection
    Inputs: Player p, String subclassTag, boolean includeArchers
    Description: Begin a selective follow task for troops matching subclassTag that follows the player.
    Returns: void */
    public void followSelection(Player p, String subclassTag, boolean includeArchers) {
        this.commandMode = CommandMode.FOLLOW;
        this.followTarget = p;

        if (selectiveFollowTaskId != -1) {
            Bukkit.getScheduler().cancelTask(selectiveFollowTaskId);
            selectiveFollowTaskId = -1;
        }

        infantry.forEach(m -> {
            try { if (hasTag(m, subclassTag)) m.setAI(true); } catch (Throwable ignore) {}
        });
        if (includeArchers) {
            archers.forEach(s -> {
                try { if (hasTag(s, subclassTag)) s.setAI(true); } catch (Throwable ignore) {}
            });
        }

        selectiveFollowTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (followTarget == null) {
                if (selectiveFollowTaskId != -1) {
                    Bukkit.getScheduler().cancelTask(selectiveFollowTaskId);
                    selectiveFollowTaskId = -1;
                }
                return;
            }
            if (!battleRunning) {
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

    /* holdSelection
    Inputs: String subclassTag, boolean includeArchers
    Description: Hold mode applied only to troops matching subclassTag.
    Returns: void */
    public void holdSelection(String subclassTag, boolean includeArchers) {
        this.commandMode = CommandMode.HOLD;
        infantry.forEach(m -> {
            try { if (hasTag(m, subclassTag)) m.setAI(false); } catch (Throwable ignore) {}
        });
        if (includeArchers) archers.forEach(s -> {
            try { if (hasTag(s, subclassTag)) s.setAI(false); } catch (Throwable ignore) {}
        });
    }

    /* chargeSelection
    Inputs: String subclassTag, boolean includeArchers
    Description: Charge mode applied only to troops matching subclassTag and schedules a formation burst.
    Returns: void */
    public void chargeSelection(String subclassTag, boolean includeArchers) {
        this.commandMode = CommandMode.CHARGE;
        if (selectiveFollowTaskId != -1) {
            try { Bukkit.getScheduler().cancelTask(selectiveFollowTaskId); } catch (Throwable ignore) {}
            selectiveFollowTaskId = -1;
        }
        this.followTarget = null;

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

    /* hasTag
    Inputs: Entity e, String tag
    Description: Return true when the entity has a troop_subclass metadata matching tag; null/empty tag matches all.
    Returns: boolean */
    private boolean hasTag(org.bukkit.entity.Entity e, String tag) {
        if (e == null) return false;
        if (tag == null || tag.isEmpty()) return true;
        try {
            if (e.hasMetadata("troop_subclass")) {
                return e.getMetadata("troop_subclass").stream().anyMatch(md -> tag.equalsIgnoreCase(md.asString()));
            }
        } catch (Throwable ignore) {}
        return false;
    }

    /* nearest
    Inputs: Location from, List<LivingEntity> list
    Description: Find the closest living entity to the provided location from the list.
    Returns: LivingEntity or null */
    private LivingEntity nearest(Location from, List<LivingEntity> list) {
        double best = Double.MAX_VALUE;
        LivingEntity pick = null;
        for (LivingEntity e : list) {
            double d = e.getLocation().distanceSquared(from);
            if (d < best) { best = d; pick = e; }
        }
        return pick;
    }

    /* aimArrow
    Inputs: Location fromEye, Location toEye
    Description: Build a projectile velocity vector that compensates for gravity and scales with distance.
    Returns: Vector */
    private org.bukkit.util.Vector aimArrow(Location fromEye, Location toEye) {
        org.bukkit.util.Vector diff = toEye.toVector().subtract(fromEye.toVector());
        double dist = fromEye.distance(toEye);
        double horiz = Math.hypot(diff.getX(), diff.getZ());

        double speed = Math.max(1.4, Math.min(1.9, 1.15 + 0.04 * dist));
        double lift = 0.010 * horiz;

        org.bukkit.util.Vector dir = diff.normalize();
        dir.setY(dir.getY() + lift);

        return dir.normalize().multiply(speed);
    }

    /* checkWinLose
    Inputs: none
    Description: Check for win/lose conditions and schedule stopBattle with a delay when the battle ends.
    Returns: void */
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
                Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> stopBattle(), 20L * 10);
            }
        }
    }

    /* onArenaMobDeath
    Inputs: EntityDeathEvent ev
    Description: Handle troop death cleanup, clear drops, roll reward and show a short title.
    Returns: void */
    @EventHandler
    public void onArenaMobDeath(org.bukkit.event.entity.EntityDeathEvent ev) {
        org.bukkit.entity.Entity ent = ev.getEntity();
        if (ent instanceof Zombie || ent instanceof Skeleton) {
            String n = ent.getCustomName();
            if ("Infantry".equals(n) || "Archer".equals(n)
                    || "Enemy Infantry".equals(n) || "Enemy Archer".equals(n) || "Bandit".equals(n)) {
                ev.getDrops().clear();
                ev.setDroppedExp(0);
                try {
                    org.bukkit.inventory.ItemStack drop = RewardService.rollDrop(ent);
                    if (drop != null) ent.getWorld().dropItemNaturally(ent.getLocation(), drop);
                } catch (Throwable ignore) {}
                try { Bukkit.getOnlinePlayers().forEach(pl -> TitleHelper.showTitle(pl, "§aTroop down", "A troop has fallen")); } catch (Throwable ignore) {}
            }
        }
    }

    public List<Zombie> getInfantry() { return infantry; }
    public List<Skeleton> getArchers() { return archers; }
    public boolean isBattleRunning() { return battleRunning; }
    public boolean isCombatRunning() { return combatTaskId != -1; }

    public ArenaManager getArenaManager() { return this.arena; }

    public boolean isAdminSetup() { return this.adminSetup; }
}
