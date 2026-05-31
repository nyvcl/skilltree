package com.classystem.lp;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class VaultHook {

    private Economy economy;

    public VaultHook(JavaPlugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault plugin not found.");
            return;
        }
        RegisteredServiceProvider<Economy> rsp =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
            plugin.getLogger().info("Vault economy hooked: " + economy.getName());
        } else {
            plugin.getLogger().warning("No economy provider found for Vault.");
        }
    }

    public boolean isAvailable() {
        return economy != null;
    }

    public boolean has(Player player, double amount) {
        return economy != null && economy.has(player, amount);
    }

    public void withdraw(Player player, double amount) {
        if (economy != null) economy.withdrawPlayer(player, amount);
    }

    public void deposit(Player player, double amount) {
        if (economy != null) economy.depositPlayer(player, amount);
    }

    public double getBalance(Player player) {
        return economy != null ? economy.getBalance(player) : 0;
    }
}
