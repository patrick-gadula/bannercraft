package com.patrick;

import org.bukkit.entity.Player;

public class TitleHelper {
    public static void showTitle(Player p, String title, String subtitle) {
        try { p.sendTitle(title, subtitle, 5, 40, 5); } catch (Throwable ignore) {}
    }
}
