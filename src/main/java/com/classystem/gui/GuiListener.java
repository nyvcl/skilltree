package com.classystem.gui;

import com.classystem.data.PlayerDataManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Listens for inventory interactions and routes them to the appropriate GUI handler.
 * Cancels all clicks inside plugin GUIs to prevent item movement.
 */
public class GuiListener implements Listener {

    private final ClassSelectGui classSelectGui;
    private final SkillTreeGui skillTreeGui;
    private final SkillTreeClickHandler clickHandler;
    private final PlayerDataManager pdm;

    public GuiListener(ClassSelectGui classSelectGui, SkillTreeGui skillTreeGui,
                       SkillTreeClickHandler clickHandler, PlayerDataManager pdm) {
        this.classSelectGui = classSelectGui;
        this.skillTreeGui = skillTreeGui;
        this.clickHandler = clickHandler;
        this.pdm = pdm;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;

        String title = event.getView().getTitle();

        // ---- Class select GUI ----
        if (title.equals(ClassSelectGui.TITLE)) {
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getView().getTopInventory()) return;

            if (pdm.hasClass(player)) {
                player.sendMessage("§cYou already have a class.");
                player.closeInventory();
                return;
            }

            String classId = classSelectGui.handleClick(event.getRawSlot());
            if (classId != null) {
                classSelectGui.confirmSelection(player, classId);
            }
            return;
        }

        // ---- Skill tree GUI ----
        if (title.equals(SkillTreeGui.TITLE)) {
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getView().getTopInventory()) return;
            clickHandler.handle(player, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();
        if (title.equals(ClassSelectGui.TITLE) || title.equals(SkillTreeGui.TITLE)) {
            event.setCancelled(true);
        }
    }
}
