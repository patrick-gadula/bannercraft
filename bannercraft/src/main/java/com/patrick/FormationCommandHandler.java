package com.patrick;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class FormationCommandHandler implements Listener {
    private final BattleManager battle;
    private final BannerCraft plugin;

    private enum Primary { EVERYONE, INFANTRY, ARCHERS, CALVARY }
    private enum Subclass { NONE, AXEMAN, SPEARMAN }

    private final Map<UUID, Primary> selectedPrimary = new HashMap<>();
    private final Map<UUID, Subclass> selectedSubclass = new HashMap<>();

    public FormationCommandHandler(BannerCraft plugin, BattleManager battle, FormationManager fm) {
        this.battle = battle;
        this.plugin = plugin;
    }

    private void restorePrimaries(Player p) {
        try {
            battle.giveCommandSticks(p);
            return;
        } catch (Throwable ignored) {}

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

    private void giveSecondaries(Player p) {
        p.getInventory().clear();
        UUID id = p.getUniqueId();
        Primary pri = selectedPrimary.getOrDefault(id, Primary.EVERYONE);

    // If the selected primary is ARCHERS, CALVARY, or EVERYONE we don't offer infantry subclasses.
    // Jump straight to actions in that case.
    if (pri == Primary.ARCHERS || pri == Primary.CALVARY || pri == Primary.EVERYONE) {
            selectedSubclass.put(id, Subclass.NONE);
            giveActions(p);
            return;
        }

        // show only subclass choices (Axeman / Spearman) + Back
        ItemStack back = new ItemStack(Material.GRAY_DYE);
        ItemMeta bm = back.getItemMeta(); bm.setDisplayName("§7Back"); back.setItemMeta(bm);
        // subclass choices left-to-right, Back on far right
        ItemStack ax = new ItemStack(Material.IRON_AXE);
        ItemMeta axm = ax.getItemMeta(); axm.setDisplayName("§cAxeman"); ax.setItemMeta(axm);
        ItemStack spear = new ItemStack(Material.ARROW);
        ItemMeta sm = spear.getItemMeta(); sm.setDisplayName("§aSpearman"); spear.setItemMeta(sm);
        p.getInventory().setItem(0, ax);
        p.getInventory().setItem(1, spear);
        p.getInventory().setItem(8, back);
    }

    private void giveActions(Player p) {
        p.getInventory().clear();
        // Actions left-to-right: Hold, Follow, Charge, formations (Line, Tight, Loose)
        ItemStack hold = new ItemStack(Material.SHIELD);
        ItemMeta hm = hold.getItemMeta(); hm.setDisplayName("§eHold Position"); hold.setItemMeta(hm);
        ItemStack follow = new ItemStack(Material.NETHERITE_INGOT);
        ItemMeta fm = follow.getItemMeta(); fm.setDisplayName("§bFollow Me"); follow.setItemMeta(fm);
        ItemStack charge = new ItemStack(Material.REDSTONE_TORCH);
        ItemMeta cm = charge.getItemMeta(); cm.setDisplayName("§cCharge"); charge.setItemMeta(cm);

        ItemStack line = new ItemStack(Material.WHITE_BANNER);
        ItemMeta lm = line.getItemMeta(); lm.setDisplayName("§9Line"); line.setItemMeta(lm);
        ItemStack tight = new ItemStack(Material.WHITE_BANNER);
        ItemMeta tm = tight.getItemMeta(); tm.setDisplayName("§9Tight"); tight.setItemMeta(tm);
        ItemStack loose = new ItemStack(Material.WHITE_BANNER);
        ItemMeta olm = loose.getItemMeta(); olm.setDisplayName("§9Loose"); loose.setItemMeta(olm);

    // place actions left-to-right starting at the leftmost hotbar slot
    p.getInventory().setItem(0, hold);
    p.getInventory().setItem(1, follow);
    p.getInventory().setItem(2, charge);
    p.getInventory().setItem(3, line);
    p.getInventory().setItem(4, tight);
    p.getInventory().setItem(5, loose);
        // Back on far right for convenience
        ItemStack back = new ItemStack(Material.GRAY_DYE);
        ItemMeta bm = back.getItemMeta(); bm.setDisplayName("§7Back"); back.setItemMeta(bm);
        p.getInventory().setItem(8, back);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent ev) {
        // allow formation interactions either during a running battle or when inside an admin-created arena
        Player p = ev.getPlayer();
        if (!battle.isBattleRunning() && !battle.getArenaManager().isArenaWorld(p.getWorld())) return;
        if (ev.getAction() != Action.RIGHT_CLICK_AIR && ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack inHand = p.getInventory().getItemInMainHand();
        if (inHand == null) return;
        Material m = inHand.getType();
        ev.setCancelled(true);

        if (m == Material.REDSTONE) {
            if (!battle.isCombatRunning()) battle.beginCombat();
            return;
        }

        if (m == Material.REDSTONE_TORCH) {
            if (battle.isCombatRunning()) {
                battle.orderCharge();
                p.sendMessage("§cCharge order given!");
                p.getInventory().setItem(4, null);
            }
            return;
        }

        if (m == Material.LIME_DYE || m == Material.IRON_SWORD || m == Material.BOW || m == Material.SADDLE) {
            UUID id = p.getUniqueId();
            if (m == Material.LIME_DYE) selectedPrimary.put(id, Primary.EVERYONE);
            else if (m == Material.IRON_SWORD) selectedPrimary.put(id, Primary.INFANTRY);
            else if (m == Material.BOW) selectedPrimary.put(id, Primary.ARCHERS);
            else selectedPrimary.put(id, Primary.CALVARY);
            selectedSubclass.put(id, Subclass.NONE);
            giveSecondaries(p);
            p.sendTitle("§ePrimary: " + selectedPrimary.get(id).name(), "Select subclass or action", 5, 40, 5);
            return;
        }

        if (m == Material.GRAY_DYE) {
            selectedPrimary.remove(p.getUniqueId());
            selectedSubclass.remove(p.getUniqueId());
            restorePrimaries(p);
            return;
        }

        if (m == Material.IRON_AXE) {
            selectedSubclass.put(p.getUniqueId(), Subclass.AXEMAN);
            // now show actions for the chosen subclass
            giveActions(p);
            p.sendTitle("§eAxeman selected", "Choose action", 5, 40, 5);
            return;
        }
        if (m == Material.ARROW) {
            selectedSubclass.put(p.getUniqueId(), Subclass.SPEARMAN);
            giveActions(p);
            p.sendTitle("§eSpearman selected", "Choose action", 5, 40, 5);
            return;
        }

    UUID id = p.getUniqueId();
    Primary pri = selectedPrimary.getOrDefault(id, Primary.EVERYONE);
    Subclass sub = selectedSubclass.getOrDefault(id, Subclass.NONE);
    boolean includeArchers = (pri == Primary.EVERYONE || pri == Primary.ARCHERS);
    boolean includeInfantry = (pri == Primary.EVERYONE || pri == Primary.INFANTRY);
    String tag = null;
    if (sub == Subclass.AXEMAN) tag = "axeman";
    else if (sub == Subclass.SPEARMAN) tag = "spearman";

        if (m == Material.SHIELD) {
            Location loc = p.getLocation();
            if (includeInfantry) {
                // set infantry to shield wall and place them around the player
                // require the player selected infantry primary and a subclass first
                        Primary priSel = selectedPrimary.getOrDefault(id, Primary.EVERYONE);
                        Subclass subSel = selectedSubclass.getOrDefault(id, Subclass.NONE);
                        // Everyone includes infantry and archers; if the player selected Everyone and no subclass,
                        // treat the subclass as 'match all' and allow the action.
                        if (priSel != Primary.INFANTRY && priSel != Primary.EVERYONE) {
                            p.sendMessage("§cSelect Infantry primary before issuing this action.");
                            return;
                        }
                        // set formation state but don't move troops to the player's location
                        battle.setInfantryFormationPassive(com.patrick.Formation.SHIELD_WALL);
                        // hold selection: if a subclass was chosen restrict to it, otherwise apply to all
                        boolean restrict = (subSel != Subclass.NONE);
                        battle.holdSelection(tag, includeArchers && restrict);
            }
            if (includeArchers) {
                    battle.setArcherFormationPassive(com.patrick.Formation.LINE);
                    battle.holdSelection(tag, includeArchers && sub != Subclass.NONE);
            }
            p.sendTitle("§eHold Order", "Troops holding position", 5, 40, 5);
            restorePrimaries(p);
            selectedPrimary.remove(id); selectedSubclass.remove(id);
            return;
        }

        if (m == Material.NETHERITE_INGOT) {
            Primary priSel = selectedPrimary.getOrDefault(id, Primary.EVERYONE);
            Subclass subSel = selectedSubclass.getOrDefault(id, Subclass.NONE);
            if (priSel != Primary.INFANTRY && priSel != Primary.EVERYONE) {
                p.sendMessage("§cSelect Infantry primary before issuing this action.");
                return;
            }
            // followSelection accepts a tag==null to mean "all"; if no subclass selected, pass null tag
            String selectTag = (subSel == Subclass.NONE) ? null : tag;
            battle.followSelection(p, selectTag, includeArchers && subSel != Subclass.NONE);
            p.sendTitle("§eFollow Order", "Troops will follow you", 5, 40, 5);
            restorePrimaries(p);
            selectedPrimary.remove(id); selectedSubclass.remove(id);
            return;
        }

        if (m == Material.REDSTONE_TORCH) {
            Primary priSel = selectedPrimary.getOrDefault(id, Primary.EVERYONE);
            Subclass subSel = selectedSubclass.getOrDefault(id, Subclass.NONE);
            if (priSel != Primary.INFANTRY && priSel != Primary.EVERYONE) {
                p.sendMessage("§cSelect Infantry primary before issuing this action.");
                return;
            }
            String chargeTag = (subSel == Subclass.NONE) ? null : tag;
            battle.chargeSelection(chargeTag, includeArchers && subSel != Subclass.NONE);
            p.sendTitle("§cCharge Order", "Troops charging!", 5, 40, 5);
            restorePrimaries(p);
            selectedPrimary.remove(id); selectedSubclass.remove(id);
            return;
        }

        // formation buttons recognized by display name rather than material constant
        ItemMeta im = inHand.getItemMeta();
        if (im != null && im.hasDisplayName()) {
            String dn = im.getDisplayName();
            if (dn.contains("Line")) {
                // move selected troops into LINE formation anchored at player's location
                battle.applyFormationToSelection(p.getUniqueId(), tag, includeInfantry, includeArchers, com.patrick.Formation.LINE, p.getLocation());
                p.sendTitle("§eFormation: Line", "Applied", 5, 40, 5);
                restorePrimaries(p);
                selectedPrimary.remove(id); selectedSubclass.remove(id);
                return;
            }
            if (dn.contains("Tight")) {
                battle.applyFormationToSelection(p.getUniqueId(), tag, includeInfantry, false, com.patrick.Formation.SHIELD_WALL, p.getLocation());
                p.sendTitle("§eFormation: Tight", "Applied", 5, 40, 5);
                restorePrimaries(p);
                selectedPrimary.remove(id); selectedSubclass.remove(id);
                return;
            }
            if (dn.contains("Loose")) {
                battle.applyFormationToSelection(p.getUniqueId(), tag, includeInfantry, false, com.patrick.Formation.LOOSE, p.getLocation());
                p.sendTitle("§eFormation: Loose", "Applied", 5, 40, 5);
                restorePrimaries(p);
                selectedPrimary.remove(id); selectedSubclass.remove(id);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent ev) {
        if (!(ev.getWhoClicked() instanceof Player)) return;
        Player p = (Player) ev.getWhoClicked();
    if ((battle.isBattleRunning() || battle.getArenaManager().isArenaWorld(p.getWorld())) && !battle.isCombatRunning()) {
            ItemStack cur = ev.getCurrentItem();
            if (cur == null) return;
            ItemMeta cm = cur.getItemMeta();
            if (cm != null && cm.hasDisplayName()) {
                String dn = cm.getDisplayName();
                if (dn.contains("Everyone") || dn.contains("Vanguard") || dn.contains("Archers") || dn.contains("Calvary")
                        || dn.contains("Start Battle Early") || dn.contains("Back") || dn.contains("Axeman") || dn.contains("Spearman")
                        || dn.contains("Hold") || dn.contains("Follow") || dn.contains("Charge") || dn.contains("Line") || dn.contains("Tight") || dn.contains("Loose")) {
                    ev.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent ev) {
    if (!(battle.isBattleRunning() || battle.getArenaManager().isArenaWorld(ev.getPlayer().getWorld()))) return;
    if (battle.isCombatRunning()) return;
        ItemStack drop = ev.getItemDrop().getItemStack();
        if (drop == null) return;
        ItemMeta dm = drop.getItemMeta();
        if (dm != null && dm.hasDisplayName()) {
            String dn = dm.getDisplayName();
            if (dn.contains("Everyone") || dn.contains("Vanguard") || dn.contains("Archers") || dn.contains("Calvary")
                    || dn.contains("Start Battle Early") || dn.contains("Back") || dn.contains("Axeman") || dn.contains("Spearman")
                    || dn.contains("Hold") || dn.contains("Follow") || dn.contains("Charge") || dn.contains("Line") || dn.contains("Tight") || dn.contains("Loose")) {
                ev.setCancelled(true);
            }
        }
    }
}
