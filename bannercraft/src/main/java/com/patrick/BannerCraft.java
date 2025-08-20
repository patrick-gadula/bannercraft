package com.patrick;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BannerCraft extends JavaPlugin {
    private BattleManager battle;

    @Override
    public void onEnable() {
        battle = new BattleManager(this);

        // Event listeners
        getServer().getPluginManager().registerEvents(battle, this);
        getServer().getPluginManager().registerEvents(new CommandListener(battle), this);

        // Register /battle command
        getCommand("battle").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            if (args.length == 0) {
                p.sendMessage("/battle <start|stop>");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "start" -> battle.startBattle(p);
                case "stop" -> battle.stopBattle();
                default -> p.sendMessage("/battle <start|stop>");
            }
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
