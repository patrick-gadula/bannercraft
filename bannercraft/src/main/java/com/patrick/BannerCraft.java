package com.patrick;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BannerCraft extends JavaPlugin {
    private BattleManager battle;
    private AdminTools adminTools;

    @Override
    public void onEnable() {
        battle = new BattleManager(this);

        // Event listeners
    getServer().getPluginManager().registerEvents(battle, this);
    // register optional scale listener to set Attribute.SCALE when available
    getServer().getPluginManager().registerEvents(new ScaleListener(), this);
    // Admin tools
    this.adminTools = new AdminTools(this, battle.getArenaManager(), battle);
    getServer().getPluginManager().registerEvents(this.adminTools, this);
        getServer().getPluginManager().registerEvents(new CommandListener(battle), this);

        // /battle
        getCommand("battle").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            if (args.length == 0) {
                p.sendMessage("/battle <start|stop>");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "start" -> battle.startBattle(p);
                case "stop"  -> battle.stopBattle();
                default      -> p.sendMessage("/battle <start|stop>");
            }
            return true;
        });

        // /start (also used to manually start admin-created instances)
        getCommand("start").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            // allow start if a normal battle is running or if admin setup mode is active
            if (!battle.isBattleRunning() && !battle.isAdminSetup()) {
                p.sendMessage("§cNo battle is running.");
                return true;
            }

            battle.beginCombat();
            p.sendMessage("§aBattle started!");
            return true;
        });

        // /formation <infantry|archers> <line|shield|loose>  (inline executor, no extra class)
        getCommand("formation").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            if (!battle.isBattleRunning() && !battle.isAdminSetup()) { p.sendMessage("§cNo battle running."); return true; }
            if (args.length < 2) { p.sendMessage("Usage: /formation <infantry|archers> <line|shield|loose>"); return true; }

            final String group = args[0].toLowerCase();
            final String form  = args[1].toLowerCase();

            Formation f = switch (form) {
                case "line"   -> Formation.LINE;
                case "shield" -> Formation.SHIELD_WALL;
                case "loose"  -> Formation.LOOSE;
                default -> { p.sendMessage("§cBad formation."); yield null; }
            };
            if (f == null) return true;

            switch (group) {
                case "infantry" -> { battle.setInfantryFormation(f, p.getLocation()); p.sendMessage("§aInfantry -> " + f); }
                case "archers"  -> { battle.setArcherFormation(f,  p.getLocation()); p.sendMessage("§bArchers  -> " + f); }
                default         -> p.sendMessage("§cBad group. Use infantry|archers");
            }
            return true;
        });

        // /create battleinstance
        getCommand("create").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            if (args.length == 0 || !"battleinstance".equalsIgnoreCase(args[0])) {
                p.sendMessage("Usage: /create battleinstance");
                return true;
            }
            // create/reset arena, teleport admin there and give admin tools (no mobs spawned)
            battle.getArenaManager().createArena();
            battle.getArenaManager().buildDivider();
            // teleport admin into the arena similar to startBattle but without spawning mobs
            battle.getArenaManager().sendToArena(p);
            // ensure setup mode (troops frozen) and give the normal setup tools
            battle.enterAdminSetup(p);
            // keep admin in setup mode so they can fly and arrange
            p.setGameMode(org.bukkit.GameMode.ADVENTURE);
            p.setAllowFlight(true);
            p.setFlying(true);
            adminTools.giveAdminHotbar(p);
            p.sendMessage("§aBattle instance created and you were teleported to the arena. Use admin tools to spawn mobs.");
            return true;
        });

        // /adminhotbar - re-give admin spawn hotbar when needed
        getCommand("adminhotbar").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            adminTools.giveAdminHotbar(p);
            p.sendMessage("§aAdmin hotbar given.");
            return true;
        });
    }

    @Override
    public void onDisable() {
        if (battle != null) battle.stopBattle();
    }

    public BattleManager getBattle() {
        return battle;
    }

    public AdminTools getAdminTools() { return adminTools; }
}