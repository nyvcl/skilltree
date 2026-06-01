package com.classystem;

import com.classystem.gui.ClassSelectGui;
import com.classystem.gui.GuiListener;
import com.classystem.gui.SkillTreeClickHandler;
import com.classystem.gui.SkillTreeGui;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassSystemMockBukkitTest {

    private ServerMock server;
    private ClassSystemPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        installDependencyPlugins();
        plugin = MockBukkit.load(ClassSystemPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginBootsWithMockedServerDependencies() {
        assertTrue(plugin.isEnabled());
        assertEquals(4, plugin.getClassRegistry().getAllClasses().size());
        assertEquals(7, plugin.getClassRegistry().getGeneralUpgrades().size());
        assertTrue(plugin.getVault().isAvailable());
    }

    @Test
    void selectClassCommandOpensGuiAndClickChoosesMagic() {
        PlayerMock player = server.addPlayer("Mage");

        player.performCommand("selectclass");
        player.assertInventoryView(ClassSelectGui.TITLE, org.bukkit.event.inventory.InventoryType.CHEST);

        ClassSelectGui classSelectGui = new ClassSelectGui(
                plugin.getClassRegistry(),
                plugin.getPlayerDataManager(),
                plugin.getLuckPerms(),
                plugin.getVault(),
                plugin);
        SkillTreeGui skillTreeGui = new SkillTreeGui(plugin.getClassRegistry(), plugin.getPlayerDataManager());
        SkillTreeClickHandler clickHandler = new SkillTreeClickHandler(
                plugin.getClassRegistry(),
                plugin.getPlayerDataManager(),
                plugin.getLuckPerms(),
                plugin.getVault(),
                skillTreeGui);
        GuiListener listener = new GuiListener(classSelectGui, skillTreeGui, clickHandler, plugin.getPlayerDataManager());
        InventoryView view = new TitledInventoryView(player.getOpenInventory(), ClassSelectGui.TITLE);
        var click = new TopInventoryClickEvent(view, player, 5);
        assertTrue(click.getWhoClicked() instanceof Player);
        assertEquals(ClassSelectGui.TITLE, click.getView().getTitle());
        assertSame(click.getView().getTopInventory(), click.getClickedInventory());
        listener.onInventoryClick(click);

        assertTrue(click.isCancelled());
        assertEquals("magic", plugin.getPlayerDataManager().getClass(player));
        assertTrue(hasMaterial(player, Material.BLAZE_ROD));
    }

    @Test
    void summonerShiftRightDismissesMinionsWhenNoActiveAbilityApplies() {
        PlayerMock player = server.addPlayer("Necro");
        plugin.getPlayerDataManager().setClass(player, "summoner");
        ItemStack staff = com.classystem.listener.SummonerAbilityListener.createSummonStaff(plugin);
        player.getInventory().setItemInMainHand(staff);

        callStaffInteract(player, Action.RIGHT_CLICK_AIR);
        assertEquals(1, countZombies(player.getWorld()));

        player.simulateSneak(true);
        callStaffInteract(player, Action.RIGHT_CLICK_AIR);

        assertEquals(0, countZombies(player.getWorld()));
    }

    @Test
    void summonerMinionsDoNotSpawnInBlockedWorlds() {
        plugin.getConfig().set("valid-worlds", List.of("world"));
        World blocked = server.addSimpleWorld("blocked");
        PlayerMock player = server.addPlayer("BlockedNecro");
        player.teleport(new Location(blocked, 0, 64, 0));
        plugin.getPlayerDataManager().setClass(player, "summoner");
        plugin.getPlayerDataManager().addTreePane(player, 50);
        ItemStack staff = com.classystem.listener.SummonerAbilityListener.createSummonStaff(plugin);
        player.getInventory().setItemInMainHand(staff);

        callStaffInteract(player, Action.RIGHT_CLICK_AIR);
        server.getScheduler().performTicks(65);

        assertEquals(0, countZombies(blocked));
    }

    @Test
    void skillTreeCommandRequiresAClassThenOpensTreeAfterClassIsSet() {
        PlayerMock player = server.addPlayer("Warrior");

        player.performCommand("skilltree");
        assertTrue(player.nextMessage().contains("haven't chosen a class"));

        plugin.getPlayerDataManager().setClass(player, "melee");
        player.performCommand("skilltree");

        player.assertInventoryView(SkillTreeGui.TITLE, org.bukkit.event.inventory.InventoryType.CHEST);
    }

    @Test
    void resetClassConfirmClearsClassAndSkillProgress() {
        PlayerMock player = server.addPlayer("ResetMe");
        plugin.getPlayerDataManager().setClass(player, "melee");
        plugin.getPlayerDataManager().addTreePane(player, 50);
        plugin.getPlayerDataManager().addTreeItem(player, 32);
        plugin.getPlayerDataManager().addGenUpgrade(player, "silk_spawners");

        player.performCommand("resetclass");
        player.performCommand("resetclass confirm");

        assertFalse(plugin.getPlayerDataManager().hasClass(player));
        assertTrue(plugin.getPlayerDataManager().getTreePanes(player).isEmpty());
        assertTrue(plugin.getPlayerDataManager().getTreeItems(player).isEmpty());
        assertTrue(plugin.getPlayerDataManager().getGenUpgrades(player).isEmpty());
    }

    @Test
    void worldRestrictionsAreEnforcedByCommands() {
        PlayerMock player = server.addPlayer("Traveler");
        World blocked = server.addSimpleWorld("blocked");
        player.teleport(new Location(blocked, 0, 64, 0));

        player.performCommand("selectclass");

        assertTrue(player.nextMessage().contains("not active in this world"));
        assertFalse(plugin.getPlayerDataManager().hasClass(player));
    }

    @Test
    void reloadCommandReloadsRegistry() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);

        admin.performCommand("classystemreload");

        assertTrue(admin.nextMessage().contains("reloaded successfully"));
        assertEquals(4, plugin.getClassRegistry().getAllClasses().size());
    }

    private void installDependencyPlugins() {
        MockBukkit.createMockPlugin("LuckPerms");
        PluginMock vaultPlugin = MockBukkit.createMockPlugin("Vault");
        server.getServicesManager().register(Economy.class, new TestEconomy(), vaultPlugin, ServicePriority.Normal);
    }

    private boolean hasMaterial(Player player, Material material) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) return true;
        }
        return false;
    }

    private void callStaffInteract(PlayerMock player, Action action) {
        server.getPluginManager().callEvent(new PlayerInteractEvent(
                player,
                action,
                player.getInventory().getItemInMainHand(),
                null,
                BlockFace.SELF,
                EquipmentSlot.HAND));
    }

    private long countZombies(World world) {
        return world.getEntities().stream()
                .filter(Entity::isValid)
                .filter(entity -> entity instanceof Zombie)
                .count();
    }

    private static final class TopInventoryClickEvent extends InventoryClickEvent {
        private final InventoryView view;
        private final Inventory clickedInventory;
        private final HumanEntity whoClicked;
        private boolean cancelled;

        private TopInventoryClickEvent(InventoryView view, HumanEntity whoClicked, int rawSlot) {
            super(view, InventoryType.SlotType.CONTAINER, rawSlot, ClickType.LEFT, InventoryAction.UNKNOWN);
            this.view = view;
            this.clickedInventory = view.getTopInventory();
            this.whoClicked = whoClicked;
        }

        @Override
        public @NotNull InventoryView getView() {
            return view;
        }

        @Override
        public HumanEntity getWhoClicked() {
            return whoClicked;
        }

        @Override
        public Inventory getClickedInventory() {
            return clickedInventory;
        }

        @Override
        public boolean isCancelled() {
            return cancelled || super.isCancelled();
        }

        @Override
        public void setCancelled(boolean cancel) {
            this.cancelled = cancel;
            super.setCancelled(cancel);
        }
    }

    private static final class TitledInventoryView implements InventoryView {
        private final InventoryView delegate;
        private final String title;

        private TitledInventoryView(InventoryView delegate, String title) {
            this.delegate = delegate;
            this.title = title;
        }

        @Override public Inventory getTopInventory() { return delegate.getTopInventory(); }
        @Override public Inventory getBottomInventory() { return delegate.getBottomInventory(); }
        @Override public HumanEntity getPlayer() { return delegate.getPlayer(); }
        @Override public InventoryType getType() { return delegate.getType(); }
        @Override public void setItem(int slot, ItemStack item) { delegate.setItem(slot, item); }
        @Override public ItemStack getItem(int slot) { return delegate.getItem(slot); }
        @Override public void setCursor(ItemStack item) { delegate.setCursor(item); }
        @Override public ItemStack getCursor() { return delegate.getCursor(); }
        @Override public Inventory getInventory(int rawSlot) { return delegate.getInventory(rawSlot); }
        @Override public int convertSlot(int rawSlot) { return delegate.convertSlot(rawSlot); }
        @Override public InventoryType.SlotType getSlotType(int slot) { return delegate.getSlotType(slot); }
        @Override public void close() { delegate.close(); }
        @Override public int countSlots() { return delegate.countSlots(); }
        @Override public boolean setProperty(Property prop, int value) { return delegate.setProperty(prop, value); }
        @Override public String getTitle() { return title; }
        @Override public String getOriginalTitle() { return title; }
        @Override public void setTitle(String title) { delegate.setTitle(title); }
    }

    private static final class TestEconomy implements Economy {
        @Override public boolean isEnabled() { return true; }
        @Override public String getName() { return "MockEconomy"; }
        @Override public boolean hasBankSupport() { return false; }
        @Override public int fractionalDigits() { return 2; }
        @Override public String format(double amount) { return String.format("$%.2f", amount); }
        @Override public String currencyNamePlural() { return "dollars"; }
        @Override public String currencyNameSingular() { return "dollar"; }
        @Override public boolean hasAccount(String playerName) { return true; }
        @Override public boolean hasAccount(org.bukkit.OfflinePlayer player) { return true; }
        @Override public boolean hasAccount(String playerName, String worldName) { return true; }
        @Override public boolean hasAccount(org.bukkit.OfflinePlayer player, String worldName) { return true; }
        @Override public double getBalance(String playerName) { return 1_000_000.0; }
        @Override public double getBalance(org.bukkit.OfflinePlayer player) { return 1_000_000.0; }
        @Override public double getBalance(String playerName, String world) { return 1_000_000.0; }
        @Override public double getBalance(org.bukkit.OfflinePlayer player, String world) { return 1_000_000.0; }
        @Override public boolean has(String playerName, double amount) { return true; }
        @Override public boolean has(org.bukkit.OfflinePlayer player, double amount) { return true; }
        @Override public boolean has(String playerName, String worldName, double amount) { return true; }
        @Override public boolean has(org.bukkit.OfflinePlayer player, String worldName, double amount) { return true; }
        @Override public EconomyResponse withdrawPlayer(String playerName, double amount) { return ok(amount); }
        @Override public EconomyResponse withdrawPlayer(org.bukkit.OfflinePlayer player, double amount) { return ok(amount); }
        @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return ok(amount); }
        @Override public EconomyResponse withdrawPlayer(org.bukkit.OfflinePlayer player, String worldName, double amount) { return ok(amount); }
        @Override public EconomyResponse depositPlayer(String playerName, double amount) { return ok(amount); }
        @Override public EconomyResponse depositPlayer(org.bukkit.OfflinePlayer player, double amount) { return ok(amount); }
        @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return ok(amount); }
        @Override public EconomyResponse depositPlayer(org.bukkit.OfflinePlayer player, String worldName, double amount) { return ok(amount); }
        @Override public EconomyResponse createBank(String name, String player) { return ok(0); }
        @Override public EconomyResponse createBank(String name, org.bukkit.OfflinePlayer player) { return ok(0); }
        @Override public EconomyResponse deleteBank(String name) { return ok(0); }
        @Override public EconomyResponse bankBalance(String name) { return ok(0); }
        @Override public EconomyResponse bankHas(String name, double amount) { return ok(amount); }
        @Override public EconomyResponse bankWithdraw(String name, double amount) { return ok(amount); }
        @Override public EconomyResponse bankDeposit(String name, double amount) { return ok(amount); }
        @Override public EconomyResponse isBankOwner(String name, String playerName) { return ok(0); }
        @Override public EconomyResponse isBankOwner(String name, org.bukkit.OfflinePlayer player) { return ok(0); }
        @Override public EconomyResponse isBankMember(String name, String playerName) { return ok(0); }
        @Override public EconomyResponse isBankMember(String name, org.bukkit.OfflinePlayer player) { return ok(0); }
        @Override public List<String> getBanks() { return Collections.emptyList(); }
        @Override public boolean createPlayerAccount(String playerName) { return true; }
        @Override public boolean createPlayerAccount(org.bukkit.OfflinePlayer player) { return true; }
        @Override public boolean createPlayerAccount(String playerName, String worldName) { return true; }
        @Override public boolean createPlayerAccount(org.bukkit.OfflinePlayer player, String worldName) { return true; }

        private EconomyResponse ok(double amount) {
            return new EconomyResponse(amount, 1_000_000.0, EconomyResponse.ResponseType.SUCCESS, null);
        }
    }
}
