package com.patrick;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BannerCraft extends JavaPlugin {
    private BattleManager battle;

    @Override
    public void onEnable() {
        battle = new BattleManager(this);

        // Event listeners
        getServer().getPluginManager().registerEvents(battle, this);
    // Admin tools
    AdminTools admin = new AdminTools(this, battle.getArenaManager(), battle);
    getServer().getPluginManager().registerEvents(admin, this);
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

        // /start
        getCommand("start").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            if (!battle.isBattleRunning()) {
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
            if (!battle.isBattleRunning()) { p.sendMessage("§cNo battle running."); return true; }
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
            // create/reset arena and give admin tools
            battle.getArenaManager().createArena();
            battle.getArenaManager().buildDivider();
            admin.giveAdminHotbar(p);
            p.sendMessage("§aBattle instance created and admin hotbar given.");
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
}