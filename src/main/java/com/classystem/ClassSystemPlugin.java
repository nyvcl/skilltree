package com.classystem;

import com.classystem.command.ReloadCommand;
import com.classystem.command.ResetClassCommand;
import com.classystem.command.SelectClassCommand;
import com.classystem.command.SkillTreeCommand;
import com.classystem.data.ClassRegistry;
import com.classystem.data.PlayerDataManager;
import com.classystem.gui.*;
import com.classystem.listener.MagicAbilityListener;
import com.classystem.listener.MeleeAbilityListener;
import com.classystem.listener.RangedAbilityListener;
import com.classystem.listener.SummonerAbilityListener;
import com.classystem.lp.LuckPermsHook;
import com.classystem.lp.VaultHook;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Objects;

public class ClassSystemPlugin extends JavaPlugin {

    private ClassRegistry classRegistry;
    private PlayerDataManager playerDataManager;
    private LuckPermsHook luckPerms;
    private VaultHook vault;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        luckPerms = new LuckPermsHook(this);
        vault = new VaultHook(this);

        if (!vault.isAvailable()) {
            getLogger().severe("Vault economy not found — disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        classRegistry = new ClassRegistry(this);
        playerDataManager = new PlayerDataManager(this);

        ClassSelectGui classSelectGui = new ClassSelectGui(
                classRegistry, playerDataManager, luckPerms, vault, this);

        SkillTreeGui skillTreeGui = new SkillTreeGui(classRegistry, playerDataManager);

        SkillTreeClickHandler clickHandler = new SkillTreeClickHandler(
                classRegistry, playerDataManager, luckPerms, vault, skillTreeGui);

        GuiListener guiListener = new GuiListener(
                classSelectGui, skillTreeGui, clickHandler, playerDataManager);
        getServer().getPluginManager().registerEvents(guiListener, this);

        MeleeAbilityListener meleeListener = new MeleeAbilityListener(playerDataManager, this);
        getServer().getPluginManager().registerEvents(meleeListener, this);

        RangedAbilityListener rangedListener = new RangedAbilityListener(playerDataManager, this);
        getServer().getPluginManager().registerEvents(rangedListener, this);

        MagicAbilityListener magicListener = new MagicAbilityListener(playerDataManager, this);
        getServer().getPluginManager().registerEvents(magicListener, this);

        SummonerAbilityListener summonerListener = new SummonerAbilityListener(playerDataManager, this);
        getServer().getPluginManager().registerEvents(summonerListener, this);

        Objects.requireNonNull(getCommand("selectclass"))
                .setExecutor(new SelectClassCommand(classSelectGui, playerDataManager, this));

        Objects.requireNonNull(getCommand("skilltree"))
                .setExecutor(new SkillTreeCommand(skillTreeGui, playerDataManager, this));

        Objects.requireNonNull(getCommand("resetclass"))
                .setExecutor(new ResetClassCommand(classRegistry, playerDataManager, luckPerms, this, summonerListener));

        Objects.requireNonNull(getCommand("classystemreload"))
                .setExecutor(new ReloadCommand(this));

        getLogger().info("ClassSystem enabled successfully.");
    }

    @Override
    public void onDisable() {
        getLogger().info("ClassSystem disabled.");
    }

    public ClassRegistry getClassRegistry() { return classRegistry; }
    public PlayerDataManager getPlayerDataManager() { return playerDataManager; }
    public LuckPermsHook getLuckPerms() { return luckPerms; }
    public VaultHook getVault() { return vault; }

    /**
     * Returns true if the player's current world is in the valid-worlds list.
     * If the list is empty, every world is considered valid.
     */
    public boolean isWorldAllowed(Player player) {
        List<String> validWorlds = getConfig().getStringList("valid-worlds");
        if (validWorlds.isEmpty()) return true;
        return validWorlds.stream().anyMatch(w -> w.equalsIgnoreCase(player.getWorld().getName()));
    }
}
