package com.classystem.lp;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class LuckPermsHook {

    private LuckPerms api;

    public LuckPermsHook(JavaPlugin plugin) {
        try {
            api = LuckPermsProvider.get();
            plugin.getLogger().info("LuckPerms hooked successfully.");
        } catch (IllegalStateException e) {
            plugin.getLogger().warning("LuckPerms not available: " + e.getMessage());
        }
    }

    public void addGroup(Player player, String group) {
        if (api == null || group == null || group.isEmpty()) return;
        api.getUserManager().modifyUser(player.getUniqueId(), user ->
                user.data().add(InheritanceNode.builder(group).build()));
    }

    public void removeGroup(Player player, String group) {
        if (api == null || group == null || group.isEmpty()) return;
        api.getUserManager().modifyUser(player.getUniqueId(), user ->
                user.data().remove(InheritanceNode.builder(group).build()));
    }

    public boolean isInGroup(Player player, String group) {
        if (api == null) return false;
        User user = api.getUserManager().getUser(player.getUniqueId());
        if (user == null) return false;
        return user.getInheritedGroups(user.getQueryOptions()).stream()
                .anyMatch(g -> g.getName().equalsIgnoreCase(group));
    }
}
