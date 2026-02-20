package dev.towki.gpexpansion;

import dev.towki.gpexpansion.command.CancelSetupCommand;
import dev.towki.gpexpansion.command.ClaimCommand;
import dev.towki.gpexpansion.command.ClaimInfoCommand;
import dev.towki.gpexpansion.command.GPXCommand;
import dev.towki.gpexpansion.command.MailboxCommand;
import dev.towki.gpexpansion.command.RentClaimCommand;
import dev.towki.gpexpansion.command.SellClaimCommand;
import dev.towki.gpexpansion.config.VersionManager;
import dev.towki.gpexpansion.setup.SetupWizardManager;
import dev.towki.gpexpansion.setup.SetupChatListener;
import dev.towki.gpexpansion.setup.SignAutoPasteListener;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.listener.SignListener;
import dev.towki.gpexpansion.listener.MailboxListener;
import dev.towki.gpexpansion.listener.BanEnforcementListener;
import dev.towki.gpexpansion.command.PaperCommandWrapper;
import dev.towki.gpexpansion.permission.SignLimitManager;
import dev.towki.gpexpansion.scheduler.SchedulerAdapter;
import dev.towki.gpexpansion.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;

import java.util.Collections;

public final class GPExpansionPlugin extends JavaPlugin {

    private static GPExpansionPlugin instance;

    private Economy economy; // optional
    // Optional: modern Vault 2 provider (stored as raw Object to avoid compile dep)
    private Object economyV2; // net.milkbowl.vault2.economy.Economy
    private Class<?> economyV2Class; // cached class for reflection
    private dev.towki.gpexpansion.reminder.RentalReminderService reminderService;
    private dev.towki.gpexpansion.confirm.ConfirmationService confirmationService;
    private dev.towki.gpexpansion.storage.ClaimDataStore claimDataStore;
    private dev.towki.gpexpansion.storage.ClaimSnapshotStore snapshotStore;
    private dev.towki.gpexpansion.gui.GUIManager guiManager;
    private SignLimitManager signLimitManager;
    private MailboxListener mailboxListener;
    private SetupWizardManager setupWizardManager;
    private dev.towki.gpexpansion.gui.DescriptionInputManager descriptionInputManager;
    private Messages messages;
    private dev.towki.gpexpansion.util.Config configManager;
    private VersionManager versionManager;
    private dev.towki.gpexpansion.permission.PermissionManager permissionManager;
    private ClaimCommand claimCommand;
    private boolean gp3dClaimMode;
    private dev.towki.gpexpansion.listener.SignDisplayListener signDisplayListener;
    private dev.towki.gpexpansion.scheduler.TaskHandle evictionDisplayTickTask;

    // Tax settings
    private double taxPercent = 5.0;
    private String taxAccountName = "Tax";

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        // Initialize config manager (handles defaults automatically)
        configManager = new dev.towki.gpexpansion.util.Config(this);
        configManager.load();
        
        // Initialize messages/lang system
        messages = new Messages(this);
        
        // Enable debug mode if configured
        if (configManager.isDebugEnabled()) {
            GPBridge.setDebug(true);
            getLogger().info("Debug mode enabled for GPBridge");
        }
        
        // Load tax settings
        loadTaxSettings();

        // Setup economy if Vault present
        setupEconomy();
        setupVaultPermission();

        // Initialize version manager and check for migrations
        versionManager = new VersionManager(this);
        versionManager.checkAndMigrateConfiguration();

        // Load stores
        claimDataStore = new dev.towki.gpexpansion.storage.ClaimDataStore(this);
        claimDataStore.load();
        snapshotStore = new dev.towki.gpexpansion.storage.ClaimSnapshotStore(this);
        
        // Initialize GUI manager
        guiManager = new dev.towki.gpexpansion.gui.GUIManager(this);
        
        // Initialize sign limit manager
        signLimitManager = new dev.towki.gpexpansion.permission.SignLimitManager(this);
        
        // Initialize permission manager (handles dynamic gpx.player permissions)
        try {
            permissionManager = new dev.towki.gpexpansion.permission.PermissionManager(this);
        } catch (NoClassDefFoundError e) {
            permissionManager = null;
            getLogger().info("Vault classes not available - permission management features disabled");
        } catch (Exception e) {
            permissionManager = null;
            getLogger().warning("Failed to initialize PermissionManager: " + e.getMessage());
        }

        // Start reminder service
        reminderService = new dev.towki.gpexpansion.reminder.RentalReminderService(this);
        reminderService.start();

        // Start confirmation service
        confirmationService = new dev.towki.gpexpansion.confirm.ConfirmationService(this);

        // Delay until server tick using Folia global scheduler when available
        // Use runLaterGlobal with 1 tick delay to ensure server is fully ready (fixes Purpur startup issues)
        SchedulerAdapter.runLaterGlobal(this, () -> {
            ClaimCommand claimCommand = new ClaimCommand(this);
            boolean gp3dPresent = new dev.towki.gpexpansion.gp.GPBridge().isGP3D();
            if (gp3dPresent) {
                // GP3D present: keep GP3D's /claim (tab completion etc). Only take over claimlist/claimslist for enhanced ID+name display.
                try {
                    unregisterClaimlistCommands();
                } catch (Exception e) {
                    getLogger().warning("Failed to unregister claimlist for takeover: " + e.getMessage());
                }
                dev.towki.gpexpansion.gp.ClaimCommandAddonImpl.register(this);
                getLogger().info("GP3D detected: GPExpansion takes claimslist/claimlist for enhanced display, intercepts /claim list");
            } else {
                try {
                    unregisterExistingClaimCommands();
                } catch (Exception e) {
                    getLogger().warning("Failed to proactively unregister existing /claim: " + e.getMessage());
                }
            }

            // Store claim command for CommandInterceptListener (used when GP3D present)
            this.claimCommand = claimCommand;
            this.gp3dClaimMode = gp3dPresent;

            // Register command programmatically (Paper requires this) - only when NOT sharing with GP3D
            Command wrapper = new PaperCommandWrapper(
                    this, // Pass plugin instance
                    "claim",
                    "Unified GriefPrevention claim command",
                    "/claim <sub>",
                    java.util.Arrays.asList("claims"),
                    claimCommand,
                    claimCommand
            );
            // Standalone trustlist command that supports an <id> argument via our ClaimCommand
            Command trustlistWrapper = new PaperCommandWrapper(
                    this, // Pass plugin instance
                    "trustlist",
                    "Show GP trust list (supports optional claim ID)",
                    "/trustlist [claimId]",
                    Collections.emptyList(),
                    claimCommand,
                    claimCommand
            );
            // Standalone adminclaimlist command to take over GP's default and route to our expanded list
            Command adminClaimListWrapper = new PaperCommandWrapper(
                    this, // Pass plugin instance
                    "adminclaimlist",
                    "Show expanded admin claims list (with IDs and subclaims)",
                    "/adminclaimlist",
                    java.util.Arrays.asList("adminclaimslist"),
                    claimCommand,
                    claimCommand
            );
            // Standalone claimslist command to take over GP's default and route to our expanded list with IDs
            Command claimsListWrapper = new PaperCommandWrapper(
                    this, // Pass plugin instance
                    "claimslist",
                    "Show your claims list (with IDs and names)",
                    "/claimslist",
                    java.util.Arrays.asList("claimlist"),
                    claimCommand,
                    claimCommand
            );
            // Standalone globalclaim command - /globalclaim [true|false] [claimId]
            Command globalClaimWrapper = new PaperCommandWrapper(
                    this,
                    "globalclaim",
                    "Toggle or set global listing for a claim",
                    "/globalclaim [true|false] [claimId]",
                    java.util.Arrays.asList("toggleglobal"),
                    claimCommand,
                    claimCommand
            );
            // Get CommandMap for registering commands
            CommandMap map = null;
            try {
                Object mapObj = getServer().getClass().getMethod("getCommandMap").invoke(getServer());
                if (mapObj instanceof CommandMap) {
                    map = (CommandMap) mapObj;
                }
            } catch (ReflectiveOperationException e) {
                getLogger().severe("Failed to get CommandMap: " + e.getMessage());
            }
            
            if (map == null) {
                getLogger().severe("Could not obtain CommandMap to register commands");
                return;
            }
            
            // Register core commands - try Paper's registerCommand first, fallback to CommandMap
            // When GP3D present, don't register our claim - GP3D keeps /claim (tab completion for abandon all/toplevel etc)
            try {
                java.lang.reflect.Method reg = JavaPlugin.class.getMethod("registerCommand", Command.class);
                if (!gp3dPresent) reg.invoke(this, wrapper);
                reg.invoke(this, trustlistWrapper);
                reg.invoke(this, adminClaimListWrapper);
                reg.invoke(this, claimsListWrapper);
                reg.invoke(this, globalClaimWrapper);
            } catch (NoSuchMethodException missing) {
                // Paper registerCommand not available, use CommandMap
                if (!gp3dPresent) map.register("gpexpansion", wrapper);
                map.register("gpexpansion", trustlistWrapper);
                map.register("gpexpansion", adminClaimListWrapper);
                map.register("gpexpansion", claimsListWrapper);
                map.register("gpexpansion", globalClaimWrapper);
            } catch (ReflectiveOperationException e) {
                getLogger().severe("Failed to register core commands: " + e.getMessage());
            }
            
            // Register all additional commands via CommandMap (always needed)
            // Register /gpxconfirm command
            dev.towki.gpexpansion.command.ConfirmCommand confirm = new dev.towki.gpexpansion.command.ConfirmCommand(this);
            Command confirmWrapper = new PaperCommandWrapper(
                    this,
                    "gpxconfirm",
                    "Confirm GPExpansion actions",
                    "/gpxconfirm [accept|cancel]",
                    Collections.emptyList(),
                    confirm,
                    confirm
            );
            map.register("gpexpansion", confirmWrapper);
            
            // Register /gpx command
            GPXCommand gpxCommand = new GPXCommand(this);
            Command gpxWrapper = new PaperCommandWrapper(
                    this,
                    "gpx",
                    "GPExpansion admin commands",
                    "/gpx <subcommand>",
                    Collections.emptyList(),
                    gpxCommand,
                    gpxCommand
            );
            map.register("gpexpansion", gpxWrapper);
            
            // Initialize setup wizard manager
            setupWizardManager = new SetupWizardManager(this);
            
            // Register chat listener for wizard
            Bukkit.getPluginManager().registerEvents(new SetupChatListener(GPExpansionPlugin.this, setupWizardManager), GPExpansionPlugin.this);
            getLogger().info("- Registered SetupChatListener for wizard commands");
            
            // Initialize description input manager
            descriptionInputManager = new dev.towki.gpexpansion.gui.DescriptionInputManager(this);
            Bukkit.getPluginManager().registerEvents(new dev.towki.gpexpansion.gui.DescriptionChatListener(GPExpansionPlugin.this, descriptionInputManager), GPExpansionPlugin.this);
            getLogger().info("- Registered DescriptionChatListener for claim descriptions");
            
            // Register /mailbox command
            MailboxCommand mailboxCommand = new MailboxCommand(this);
            mailboxCommand.setWizardManager(setupWizardManager);
            Command mailboxWrapper = new PaperCommandWrapper(
                    this,
                    "mailbox",
                    "Mailbox management command",
                    "/mailbox <subcommand>",
                    Collections.emptyList(),
                    mailboxCommand,
                    mailboxCommand
            );
            map.register("gpexpansion", mailboxWrapper);
            
            // Register /rentclaim command
            RentClaimCommand rentClaimCommand = new RentClaimCommand(this, setupWizardManager);
            Command rentClaimWrapper = new PaperCommandWrapper(
                    this,
                    "rentclaim",
                    "Start rental sign setup wizard",
                    "/rentclaim [claimId]",
                    Collections.emptyList(),
                    rentClaimCommand,
                    rentClaimCommand
            );
            map.register("gpexpansion", rentClaimWrapper);
            
            // Register /sellclaim command
            SellClaimCommand sellClaimCommand = new SellClaimCommand(this, setupWizardManager);
            Command sellClaimWrapper = new PaperCommandWrapper(
                    this,
                    "sellclaim",
                    "Start sell sign setup wizard",
                    "/sellclaim [claimId]",
                    Collections.emptyList(),
                    sellClaimCommand,
                    sellClaimCommand
            );
            map.register("gpexpansion", sellClaimWrapper);
            
            // Register /claiminfo command
            ClaimInfoCommand claimInfoCommand = new ClaimInfoCommand(this);
            Command claimInfoWrapper = new PaperCommandWrapper(
                    this,
                    "claiminfo",
                    "Show detailed claim information",
                    "/claiminfo [claimId]",
                    Collections.emptyList(),
                    claimInfoCommand,
                    claimInfoCommand
            );
            map.register("gpexpansion", claimInfoWrapper);
            
            // Register /cancelsetup command
            CancelSetupCommand cancelSetupCommand = new CancelSetupCommand(setupWizardManager);
            Command cancelSetupWrapper = new PaperCommandWrapper(
                    this,
                    "cancelsetup",
                    "Cancel setup wizard or auto-paste mode",
                    "/cancelsetup",
                    Collections.emptyList(),
                    cancelSetupCommand,
                    cancelSetupCommand
            );
            map.register("gpexpansion", cancelSetupWrapper);
            
            // Register /claimtp command
            Command claimTpWrapper = new PaperCommandWrapper(
                    this,
                    "claimtp",
                    "Teleport to a claim",
                    "/claimtp <claimId> [player]",
                    Collections.emptyList(),
                    claimCommand,
                    claimCommand
            );
            map.register("gpexpansion", claimTpWrapper);
            
            // Register /setclaimspawn command
            Command setClaimSpawnWrapper = new PaperCommandWrapper(
                    this,
                    "setclaimspawn",
                    "Set the teleport spawn point for a claim",
                    "/setclaimspawn",
                    Collections.emptyList(),
                    claimCommand,
                    claimCommand
            );
            map.register("gpexpansion", setClaimSpawnWrapper);
            
            // Register sign auto-paste listener (fallback for SignChangeEvent injection)
            SignAutoPasteListener autoPasteListener = new SignAutoPasteListener(GPExpansionPlugin.this, setupWizardManager);
            Bukkit.getPluginManager().registerEvents(autoPasteListener, GPExpansionPlugin.this);
            getLogger().info("- Registered SignAutoPasteListener for auto-paste mode");
            getLogger().info("Registered /claim, /trustlist, /adminclaimlist, /gpx, /rentclaim, /sellclaim, /cancelsetup commands under GPExpansion");
        }, 1L);

        // Register listeners
        getLogger().info("Registering event listeners...");
        
        SignListener signListener = new SignListener(this);
        Bukkit.getPluginManager().registerEvents(signListener, this);
        getLogger().info("- Registered SignListener: " + signListener.getClass().getName());
        
        mailboxListener = new MailboxListener(this);
        Bukkit.getPluginManager().registerEvents(mailboxListener, this);
        getLogger().info("- Registered MailboxListener: " + mailboxListener.getClass().getName());
        
        BanEnforcementListener banListener = new BanEnforcementListener(this);
        Bukkit.getPluginManager().registerEvents(banListener, this);
        getLogger().info("- Registered BanEnforcementListener: " + banListener.getClass().getName());
        
        dev.towki.gpexpansion.listener.CommandInterceptListener commandListener = 
            new dev.towki.gpexpansion.listener.CommandInterceptListener(this);
        Bukkit.getPluginManager().registerEvents(commandListener, this);
        getLogger().info("- Registered CommandInterceptListener: " + commandListener.getClass().getName());
        // Protect rent/buy signs and their supports from griefing and enforce deletion flow
        Bukkit.getPluginManager().registerEvents(new dev.towki.gpexpansion.listener.SignProtectionListener(this), this);
        // Prevent claim abandonment during active rentals
        Bukkit.getPluginManager().registerEvents(new dev.towki.gpexpansion.listener.ClaimAbandonListener(this), this);
        // Join reminders
        Bukkit.getPluginManager().registerEvents(new dev.towki.gpexpansion.listener.ReminderJoinListener(this), this);
        // Dynamic sign display ([Renew], [Evicted] countdown, item scroll)
        signDisplayListener = new dev.towki.gpexpansion.listener.SignDisplayListener(this);
        Bukkit.getPluginManager().registerEvents(signDisplayListener, this);
        startEvictionDisplayTick();
        // Economy late-hook listener
        Bukkit.getPluginManager().registerEvents(new dev.towki.gpexpansion.listener.EconomyHookListener(this), this);
        
        getLogger().info(() -> "GPExpansion enabled");
    }

    @SuppressWarnings("unchecked")
    private void unregisterExistingClaimCommands() throws ReflectiveOperationException {
        Object mapObj = getServer().getClass().getMethod("getCommandMap").invoke(getServer());
        if (!(mapObj instanceof CommandMap)) return;
        CommandMap map = (CommandMap) mapObj;

        // Access knownCommands map reflectively (walk class hierarchy)
        Class<?> cls = map.getClass();
        java.lang.reflect.Field f = null;
        while (cls != null && f == null) {
            try {
                f = cls.getDeclaredField("knownCommands");
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        if (f == null) {
            getLogger().warning("Could not reflectively access knownCommands; cannot override /claim");
            return;
        }
        f.setAccessible(true);
        Object raw = f.get(map);
        if (!(raw instanceof java.util.Map)) return;
        java.util.Map<String, Command> known = (java.util.Map<String, Command>) raw;

        // Attempt to unregister both bare and namespaced entries
        String[] labels = new String[]{
                "claim",
                "griefprevention:claim",
                ("gpexpansion:claim"),
                // Also ensure we take over adminclaimlist
                "adminclaimlist",
                "adminclaimslist",
                "griefprevention:adminclaimlist",
                "griefprevention:adminclaimslist",
                ("gpexpansion:adminclaimlist"),
                ("gpexpansion:adminclaimslist")
        };
        for (String label : labels) {
            Command existing = known.get(label);
            if (existing != null) {
                try {
                    existing.unregister(map);
                } catch (Throwable ignored) { }
                known.remove(label);
                getLogger().info("Unregistered existing command label: " + label);
            }
        }
    }

    /** Starts a periodic task (every 1 second) that updates eviction countdowns for all online players, even when standing still. */
    private void startEvictionDisplayTick() {
        if (signDisplayListener == null) return;
        if (evictionDisplayTickTask != null) {
            evictionDisplayTickTask.cancel();
        }
        // Run every 20 ticks (1 second); for each player run sign update on their entity region (Folia-safe)
        evictionDisplayTickTask = SchedulerAdapter.runRepeatingGlobal(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isValid() && p.getWorld() != null) {
                    runAtEntity(p, () -> signDisplayListener.tickEvictionDisplays(p));
                }
            }
        }, 20L, 20L);
    }

    /** Unregister claimlist and adminclaimlist so our enhanced display takes over (used when sharing /claim with GP3D) */
    @SuppressWarnings("unchecked")
    private void unregisterClaimlistCommands() throws ReflectiveOperationException {
        Object mapObj = getServer().getClass().getMethod("getCommandMap").invoke(getServer());
        if (!(mapObj instanceof CommandMap)) return;
        CommandMap map = (CommandMap) mapObj;
        Class<?> cls = map.getClass();
        java.lang.reflect.Field f = null;
        while (cls != null && f == null) {
            try {
                f = cls.getDeclaredField("knownCommands");
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        if (f == null) return;
        f.setAccessible(true);
        Object raw = f.get(map);
        if (!(raw instanceof java.util.Map)) return;
        java.util.Map<String, Command> known = (java.util.Map<String, Command>) raw;
        for (String label : new String[]{
            "claimlist", "claimslist", "griefprevention:claimlist", "griefprevention:claimslist", "gpexpansion:claimlist", "gpexpansion:claimslist",
            "adminclaimlist", "adminclaimslist", "griefprevention:adminclaimlist", "griefprevention:adminclaimslist", "gpexpansion:adminclaimlist", "gpexpansion:adminclaimslist"
        }) {
            Command existing = known.get(label);
            if (existing != null) {
                try { existing.unregister(map); } catch (Throwable ignored) {}
                known.remove(label);
            }
        }
    }

    @Override
    public void onDisable() {
        if (gp3dClaimMode) {
            dev.towki.gpexpansion.gp.ClaimCommandAddonImpl.unregister();
        }
        if (reminderService != null) {
            reminderService.stop();
        }
        if (evictionDisplayTickTask != null) {
            evictionDisplayTickTask.cancel();
            evictionDisplayTickTask = null;
        }
        if (claimDataStore != null) {
            claimDataStore.save();
        }
        getLogger().info(() -> "GPExpansion disabled");
    }

    public void setupEconomy() {
        // Try legacy Vault if present
        try {
            if (getServer().getPluginManager().getPlugin("Vault") != null) {
                RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
                if (rsp != null) {
                    economy = rsp.getProvider();
                    getLogger().info("Hooked into Vault economy (legacy): " + economy.getName());
                }
                // If typed lookup failed, scan all legacy Vault registrations
                if (economy == null) {
                    java.util.Collection<RegisteredServiceProvider<Economy>> regs = getServer().getServicesManager().getRegistrations(Economy.class);
                    if (regs != null && !regs.isEmpty()) {
                        // Choose first for simplicity (ServicesManager already orders by priority)
                        RegisteredServiceProvider<Economy> pick = regs.iterator().next();
                        if (pick != null && pick.getProvider() != null) {
                            economy = pick.getProvider();
                            getLogger().info("Hooked into Vault economy (legacy via scan): " + economy.getName());
                        }
                    }
                }
                // Reflective fallback in case typed lookup failed due to classloader or API shim
                if (economy == null) {
                    try {
                        Class<?> legacyEcoClass = Class.forName("net.milkbowl.vault.economy.Economy");
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        RegisteredServiceProvider<?> rsp2 = (RegisteredServiceProvider) getServer().getServicesManager().getRegistration((Class) legacyEcoClass);
                        if (rsp2 == null) {
                            @SuppressWarnings({"rawtypes", "unchecked"})
                            java.util.Collection<RegisteredServiceProvider<?>> regs2 = (java.util.Collection) getServer().getServicesManager().getRegistrations((Class) legacyEcoClass);
                            if (regs2 != null && !regs2.isEmpty()) {
                                rsp2 = regs2.iterator().next();
                            }
                        }
                        if (rsp2 != null && rsp2.getProvider() != null) {
                            // Store under reflective path so format/has/withdraw methods still work
                            this.economyV2Class = legacyEcoClass;
                            this.economyV2 = rsp2.getProvider();
                            getLogger().info("Hooked into Vault economy via reflective legacy bridge: " + economyV2.getClass().getName());
                        }
                    } catch (Throwable ignored2) { }
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Vault legacy API not on classpath; continue to try Vault 2
        } catch (Throwable t) {
            getLogger().warning("Error while hooking legacy Vault: " + t.getMessage());
        }

        // Always try Vault 2 (VaultUnlocked) modern API under package net.milkbowl.vault2
        if (economy == null) {
            try {
                economyV2Class = Class.forName("net.milkbowl.vault2.economy.Economy");
                @SuppressWarnings({"rawtypes", "unchecked"})
                RegisteredServiceProvider<?> rsp2 = (RegisteredServiceProvider) getServer().getServicesManager().getRegistration((Class) economyV2Class);
                if (rsp2 != null) {
                    economyV2 = rsp2.getProvider();
                    getLogger().info("Hooked into Vault economy (modern v2): " + economyV2.getClass().getName());
                }
            } catch (ClassNotFoundException ignored) {
                // Vault 2 API not present
            } catch (Throwable t) {
                getLogger().warning("Failed to hook Vault v2 economy: " + t.getMessage());
            }
        }

        // FINAL FALLBACK: classloader-agnostic scan of known services, then bind via the actual service class objects
        if (this.economy == null && this.economyV2 == null) {
            try {
                org.bukkit.plugin.ServicesManager sm = getServer().getServicesManager();
                java.util.Collection<Class<?>> known = sm.getKnownServices();
                if (known != null) {
                    for (Class<?> svc : known) {
                        String name = svc.getName();
                        if ("net.milkbowl.vault.economy.Economy".equals(name) || "net.milkbowl.vault2.economy.Economy".equals(name)) {
                            @SuppressWarnings({"rawtypes", "unchecked"})
                            RegisteredServiceProvider<?> reg = (RegisteredServiceProvider) sm.getRegistration((Class) svc);
                            if (reg != null && reg.getProvider() != null) {
                                this.economyV2Class = svc; // bind to the exact class object that ServicesManager knows
                                this.economyV2 = reg.getProvider();
                                getLogger().info("Hooked into economy via known-services scan: " + svc.getName() + " -> " + economyV2.getClass().getName());
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                getLogger().fine("Known-services scan failed: " + t.getMessage());
            }
        }
    }

    public void refreshEconomy() {
        setupEconomy();
    }

    private net.milkbowl.vault.permission.Permission vaultPermission;

    private void setupVaultPermission() {
        try {
            if (getServer().getPluginManager().getPlugin("Vault") != null) {
                RegisteredServiceProvider<net.milkbowl.vault.permission.Permission> rsp =
                    getServer().getServicesManager().getRegistration(net.milkbowl.vault.permission.Permission.class);
                if (rsp != null && rsp.getProvider() != null) {
                    vaultPermission = rsp.getProvider();
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Check if player has griefprevention.restoresnapshot.
     * Uses Bukkit API for online players; Vault Permission API for offline (required since owner may be offline).
     */
    public boolean hasRestoreSnapshotPermission(OfflinePlayer player) {
        if (player.isOnline() && player.getPlayer() != null) {
            if (player.getPlayer().hasPermission(dev.towki.gpexpansion.storage.ClaimSnapshotStore.getPermission())) {
                return true;
            }
        }
        if (vaultPermission != null) {
            try {
                String worldName = null;
                if (player.getPlayer() != null && player.getPlayer().getWorld() != null) {
                    worldName = player.getPlayer().getWorld().getName();
                } else if (!getServer().getWorlds().isEmpty()) {
                    worldName = getServer().getWorlds().get(0).getName();
                }
                String playerName = player.getName();
                if (playerName != null && !playerName.isEmpty()) {
                    return vaultPermission.has(worldName, playerName, dev.towki.gpexpansion.storage.ClaimSnapshotStore.getPermission());
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public dev.towki.gpexpansion.storage.ClaimSnapshotStore getSnapshotStore() {
        return snapshotStore;
    }
    
    /**
     * Get the messages/lang manager.
     */
    public Messages getMessages() {
        return messages;
    }
    
    /**
     * Reload config and language files.
     */
    public void reloadAll() {
        reloadConfig();
        messages.loadLanguageFile();
        
        // Reload config (read-only; missing keys are only injected on startup/migration)
        configManager.reload();
        
        // Reload consolidated claim data store
        claimDataStore.reload();
        
        // Reload GUI configurations
        guiManager.reload();
        
        // Re-apply debug setting
        GPBridge.setDebug(configManager.isDebugEnabled());
        
        // Reload tax settings
        loadTaxSettings();
        
        // Reload permission manager (updates gpx.player children)
        if (permissionManager != null) {
            permissionManager.reload();
        }
    }

    // Economy bridge methods to support both legacy Vault and Vault 2 without a hard compile dependency on v2
    public boolean isEconomyAvailable() {
        boolean ok = economy != null || economyV2 != null;
        if (!ok) {
            // Light debug - use FINE to avoid spam
            getLogger().fine("Economy queried but no provider hooked yet.");
        } else {
            getLogger().fine("Economy available via: " + (economy != null ? ("Vault legacy - " + economy.getName()) : ("reflective/modern - " + economyV2.getClass().getName())));
        }
        return ok;
    }

    public String formatMoney(double amount) {
        try {
            if (economy != null) return economy.format(amount);
            if (economyV2 != null && economyV2Class != null) {
                java.lang.reflect.Method m = economyV2Class.getMethod("format", double.class);
                Object out = m.invoke(economyV2, amount);
                return out != null ? out.toString() : String.format(java.util.Locale.US, "%,.2f", amount);
            }
        } catch (Throwable ignored) { }
        return String.format(java.util.Locale.US, "%,.2f", amount);
    }

    private static final String CURRENCY_SYMBOLS = "$€£¥₩₽₹₺₫₴₦₱₪₡₲₵₸₭₮₨";

    /** Currency symbols used for formatting and for parsing sign input (e.g. $100 or 100$). */
    public static String getCurrencySymbolsForParsing() {
        return CURRENCY_SYMBOLS;
    }

    /**
     * Format money for sign display using Vault's economy.format().
     * Respects the provider's currency symbol and position (prefix or suffix).
     * Trims trailing .00 for cleaner sign display when appropriate.
     * Falls back to "$" prefix when the economy returns a plain number with no symbol.
     */
    public String formatMoneyForSign(double amount) {
        String formatted = formatMoney(amount);
        if (formatted == null || formatted.isEmpty()) return "$" + compactAmount(amount);
        // Trim trailing .00 for whole numbers (e.g. "100.00" -> "100", "$1,000.00" -> "$1,000")
        if (amount == Math.floor(amount) && formatted.contains(".00")) {
            formatted = formatted.replace(".00", "");
        }
        // If economy format has no currency symbol, use $ prefix as fallback
        boolean hasSymbol = false;
        for (int i = 0; i < CURRENCY_SYMBOLS.length(); i++) {
            if (formatted.indexOf(CURRENCY_SYMBOLS.charAt(i)) >= 0) {
                hasSymbol = true;
                break;
            }
        }
        if (!hasSymbol) return "$" + compactAmount(amount);
        return formatted;
    }

    private String compactAmount(double amount) {
        return amount == Math.floor(amount)
            ? String.format(java.util.Locale.US, "%,.0f", amount)
            : String.format(java.util.Locale.US, "%,.2f", amount);
    }

    /**
     * Reset a rental sign to available-for-rent state: clear rental/eviction data, untrust renter,
     * remove renter from sign PDC, and update display to [Rent].
     */
    public void resetRentalSign(org.bukkit.block.Block signBlock) {
        if (!(signBlock.getState() instanceof org.bukkit.block.Sign sign)) return;
        org.bukkit.persistence.PersistentDataContainer pdc = sign.getPersistentDataContainer();
        org.bukkit.NamespacedKey keyKind = new org.bukkit.NamespacedKey(this, "sign.kind");
        org.bukkit.NamespacedKey keyClaim = new org.bukkit.NamespacedKey(this, "sign.claimId");
        org.bukkit.NamespacedKey keyRenter = new org.bukkit.NamespacedKey(this, "rent.renter");
        org.bukkit.NamespacedKey keyExpiry = new org.bukkit.NamespacedKey(this, "rent.expiry");
        org.bukkit.NamespacedKey keyEcoAmt = new org.bukkit.NamespacedKey(this, "sign.ecoAmt");
        org.bukkit.NamespacedKey keyEcoKind = new org.bukkit.NamespacedKey(this, "sign.ecoKind");
        org.bukkit.NamespacedKey keyPerClick = new org.bukkit.NamespacedKey(this, "sign.perClick");
        org.bukkit.NamespacedKey keyMaxCap = new org.bukkit.NamespacedKey(this, "sign.maxCap");
        if (!"RENT".equals(pdc.get(keyKind, org.bukkit.persistence.PersistentDataType.STRING))) return;
        String claimId = pdc.get(keyClaim, org.bukkit.persistence.PersistentDataType.STRING);
        String renterStr = pdc.get(keyRenter, org.bukkit.persistence.PersistentDataType.STRING);
        dev.towki.gpexpansion.storage.ClaimDataStore dataStore = getClaimDataStore();

        // Only restore snapshot when we're actually clearing an active rental/eviction from the data store.
        // Skip restore if rental/eviction are already cleared (e.g. eviction was already processed, or after
        // server restart when owner sneak+breaks the sign) so we don't restore the same snapshot twice.
        boolean hasActiveRentalOrEviction = claimId != null && (
            dataStore.getRental(claimId).isPresent() || dataStore.getEviction(claimId).isPresent());

        // Restore claim from snapshot if owner has permission (owner may be offline; use Vault)
        // Use sign block location for runAtLocation to ensure Folia runs in loaded region
        if (claimId != null && hasActiveRentalOrEviction) {
            java.util.Optional<Object> claimOpt = new dev.towki.gpexpansion.gp.GPBridge().findClaimById(claimId);
            if (claimOpt.isPresent()) {
                try {
                    Object ownerId = claimOpt.get().getClass().getMethod("getOwnerID").invoke(claimOpt.get());
                    if (ownerId instanceof java.util.UUID ownerUuid) {
                        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUuid);
                        if (hasRestoreSnapshotPermission(owner)) {
                            java.util.Optional<dev.towki.gpexpansion.storage.ClaimSnapshotStore.SnapshotEntry> latest =
                                snapshotStore.getLatestSnapshot(claimId);
                            if (latest.isPresent()) {
                                // Use the sign's world so we restore in the same dimension the player sees (avoids world-name mismatch)
                                org.bukkit.World world = signBlock.getWorld();
                                String snapId = latest.get().id;
                                final String fid = claimId;
                                final String fsid = snapId;
                                getLogger().info("Restoring claim " + claimId + " from snapshot " + snapId + " in world " + world.getName() + " (Folia=" + dev.towki.gpexpansion.scheduler.SchedulerAdapter.isFolia() + ")");
                                if (dev.towki.gpexpansion.scheduler.SchedulerAdapter.isFolia()) {
                                    java.util.Optional<org.bukkit.Location> originOpt = snapshotStore.getSnapshotOrigin(claimId, snapId, world);
                                    org.bukkit.Location runAt = originOpt.orElse(signBlock.getLocation());
                                    dev.towki.gpexpansion.scheduler.SchedulerAdapter.runAtLocationLater(this, runAt, () -> {
                                        boolean ok = snapshotStore.restoreSnapshot(claimId, snapId, world, null);
                                        if (ok) getLogger().info("Claim " + claimId + " restored from snapshot on eviction.");
                                        else getLogger().warning("Failed to restore snapshot for claim " + claimId + " (snapshot " + snapId + ")");
                                    }, 1L);
                                } else {
                                        // Non-Folia: run restore on main thread. If we're already on primary thread, run now so chunks are loaded.
                                        Runnable doRestore = () -> {
                                            boolean ok = snapshotStore.restoreSnapshot(fid, fsid, world);
                                            if (ok) getLogger().info("Claim " + fid + " restored from snapshot on eviction.");
                                            else getLogger().warning("Failed to restore snapshot for claim " + fid + " (snapshot " + fsid + ")");
                                        };
                                        if (Bukkit.isPrimaryThread()) {
                                            doRestore.run();
                                        } else {
                                            dev.towki.gpexpansion.scheduler.SchedulerAdapter.runLaterGlobal(this, doRestore, 1L);
                                        }
                                }
                            }
                        }
                    }
                } catch (ReflectiveOperationException ignored) {}
            }
        }

        if (claimId != null) {
            dataStore.clearRental(claimId);
            dataStore.clearEviction(claimId);
            dataStore.save();
        }
        if (claimId != null && renterStr != null) {
            dev.towki.gpexpansion.gp.GPBridge gpBridge = new dev.towki.gpexpansion.gp.GPBridge();
            java.util.Optional<Object> claimOpt = gpBridge.findClaimById(claimId);
            if (claimOpt.isPresent()) {
                try {
                    java.util.UUID renter = java.util.UUID.fromString(renterStr);
                    String renterName = Bukkit.getOfflinePlayer(renter).getName();
                    if (renterName != null) {
                        boolean untrusted = gpBridge.untrust(renterName, claimOpt.get());
                        if (untrusted) {
                            gpBridge.saveClaim(claimOpt.get()); // Persist so untrust survives server restarts
                            getLogger().info("Removed trust for " + renterName + " from claim " + claimId);
                        }
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
        pdc.remove(keyRenter);
        pdc.remove(keyExpiry);
        String ecoAmt = pdc.get(keyEcoAmt, org.bukkit.persistence.PersistentDataType.STRING);
        String ecoKindStr = pdc.get(keyEcoKind, org.bukkit.persistence.PersistentDataType.STRING);
        String perClick = pdc.get(keyPerClick, org.bukkit.persistence.PersistentDataType.STRING);
        String maxCap = pdc.get(keyMaxCap, org.bukkit.persistence.PersistentDataType.STRING);
        if (ecoAmt == null) ecoAmt = "";
        if (perClick == null) perClick = "";
        if (maxCap == null) maxCap = "";
        String ecoFormatted = formatEcoForSign(ecoKindStr, ecoAmt);
        boolean hanging = signBlock.getType().name().contains("HANGING_SIGN");
        String displayLine0 = org.bukkit.ChatColor.translateAlternateColorCodes('&',
            messages.getRaw(hanging ? "sign-interaction.sign-display-rent-hanging" : "sign-interaction.sign-display-rent-full"));
        org.bukkit.block.sign.SignSide front = sign.getSide(org.bukkit.block.sign.Side.FRONT);
        front.setLine(0, displayLine0);
        front.setLine(1, org.bukkit.ChatColor.BLACK + "ID: " + org.bukkit.ChatColor.GOLD + (claimId != null ? claimId : ""));
        front.setLine(2, org.bukkit.ChatColor.BLACK + ecoFormatted + "/" + perClick);
        front.setLine(3, org.bukkit.ChatColor.BLACK + "Max: " + maxCap);
        sign.update(true);
    }

    private String formatEcoForSign(String ecoKindStr, String ecoAmtRaw) {
        if (ecoAmtRaw == null || ecoAmtRaw.isEmpty()) return "";
        if (ecoKindStr == null) ecoKindStr = "MONEY";
        try {
            dev.towki.gpexpansion.util.EcoKind kind = dev.towki.gpexpansion.util.EcoKind.valueOf(ecoKindStr.toUpperCase());
            switch (kind) {
                case MONEY:
                    try {
                        return formatMoneyForSign(Double.parseDouble(ecoAmtRaw));
                    } catch (NumberFormatException ignored) {
                        return "$" + ecoAmtRaw;
                    }
                case EXPERIENCE:
                    return ecoAmtRaw.toUpperCase().endsWith("L")
                        ? ecoAmtRaw.substring(0, ecoAmtRaw.length() - 1) + " Levels"
                        : ecoAmtRaw + " XP";
                case CLAIMBLOCKS:
                    return ecoAmtRaw + " blocks";
                case ITEM:
                    return ecoAmtRaw + " items";
                default:
                    return ecoAmtRaw;
            }
        } catch (IllegalArgumentException ignored) {
            return ecoAmtRaw;
        }
    }

    /**
     * Parse eviction notice period from config.
     * Supports: "14d", "1h", "10m", "10s", "1w", or plain number (treated as days).
     */
    public long getEvictionNoticePeriodMs() {
        Object val = getConfig().get("eviction.notice-period");
        if (val != null) {
            if (val instanceof Number n) {
                return n.longValue() * 24L * 60L * 60L * 1000L;
            }
            String s = String.valueOf(val).trim();
            if (!s.isEmpty()) {
                long ms = parseDurationToMillis(s);
                if (ms > 0) return ms;
            }
        }
        return 14L * 24L * 60L * 60L * 1000L; // default 14 days
    }

    /** Human-readable eviction notice duration for messages (e.g. "14 days", "1 hour"). */
    public String getEvictionNoticePeriodDisplay() {
        long ms = getEvictionNoticePeriodMs();
        long sec = ms / 1000L;
        long min = sec / 60L;
        long hr = min / 60L;
        long d = hr / 24L;
        if (d > 0) return d + " day" + (d == 1 ? "" : "s");
        if (hr > 0) return hr + " hour" + (hr == 1 ? "" : "s");
        if (min > 0) return min + " minute" + (min == 1 ? "" : "s");
        return sec + " second" + (sec == 1 ? "" : "s");
    }

    private long parseDurationToMillis(String s) {
        if (s == null || s.isEmpty()) return 0L;
        String numStr = s.replaceAll("[^0-9]", "");
        if (numStr.isEmpty()) return 0L;
        long n = Long.parseLong(numStr);
        if (s.length() == numStr.length()) return n * 24L * 60L * 60L * 1000L; // plain number = days
        char unit = Character.toLowerCase(s.charAt(s.length() - 1));
        return switch (unit) {
            case 's' -> n * 1000L;
            case 'm' -> n * 60L * 1000L;
            case 'h' -> n * 60L * 60L * 1000L;
            case 'd' -> n * 24L * 60L * 60L * 1000L;
            case 'w' -> n * 7L * 24L * 60L * 60L * 1000L;
            default -> 0L;
        };
    }

    public boolean hasMoney(OfflinePlayer player, double amount) {
        try {
            if (economy != null) return economy.has(player, amount);
            if (economyV2 != null && economyV2Class != null) {
                // Try common method names in Vault 2
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("has", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    return out instanceof Boolean ? (Boolean) out : false;
                } catch (NoSuchMethodException e1) {
                    java.lang.reflect.Method m = economyV2Class.getMethod("hasBalance", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    return out instanceof Boolean ? (Boolean) out : false;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    public boolean withdrawMoney(OfflinePlayer player, double amount) {
        try {
            if (economy != null) {
                net.milkbowl.vault.economy.EconomyResponse resp = economy.withdrawPlayer(player, amount);
                return resp != null && resp.transactionSuccess();
            }
            if (economyV2 != null && economyV2Class != null) {
                // Try common method names in Vault 2
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("withdraw", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    // Accept boolean or response-like objects with success() method
                    if (out instanceof Boolean) return (Boolean) out;
                    try {
                        // Recognize common response shapes: transactionSuccess/isSuccessful/success/wasSuccessful
                        for (String method : new String[]{"transactionSuccess", "isSuccessful", "success", "wasSuccessful"}) {
                            try {
                                java.lang.reflect.Method ok = out.getClass().getMethod(method);
                                Object b = ok.invoke(out);
                                if (b instanceof Boolean) return (Boolean) b;
                            } catch (NoSuchMethodException ignored3) { }
                        }
                    } catch (Throwable ignored2) {}
                    return true; // assume ok if no info available
                } catch (NoSuchMethodException e1) {
                    java.lang.reflect.Method m = economyV2Class.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    if (out instanceof Boolean) return (Boolean) out;
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * Get the consolidated claim data store
     */
    public dev.towki.gpexpansion.storage.ClaimDataStore getClaimDataStore() {
        return claimDataStore;
    }

    /** Claim command handler (for CommandInterceptListener when sharing with GP3D) */
    public ClaimCommand getClaimCommand() {
        return claimCommand;
    }

    /** True when GP3D is present and we share /claim - only intercept our subcommands */
    public boolean isGp3dClaimMode() {
        return gp3dClaimMode;
    }
    
    public dev.towki.gpexpansion.gui.GUIManager getGUIManager() {
        return guiManager;
    }

    public dev.towki.gpexpansion.gui.DescriptionInputManager getDescriptionInputManager() {
        return descriptionInputManager;
    }
    
    public SignLimitManager getSignLimitManager() {
        return signLimitManager;
    }

    public dev.towki.gpexpansion.reminder.RentalReminderService getReminderService() {
        return reminderService;
    }

    public dev.towki.gpexpansion.confirm.ConfirmationService getConfirmationService() {
        return confirmationService;
    }

    // Folia/Paper compatible helpers
    public void runGlobal(Runnable task) {
        SchedulerAdapter.runGlobal(this, task);
    }

    public void runAtEntity(Player player, Runnable task) {
        SchedulerAdapter.runEntity(this, player, () -> {
            if (player.isValid()) {
                task.run();
            }
        }, () -> {});
    }

    public void teleportEntity(Player player, Location to) {
        if (SchedulerAdapter.isFolia()) {
            // On Folia, use teleportAsync for cross-region teleportation
            try {
                player.teleportAsync(to);
            } catch (Throwable ignored) {}
        } else {
            // On Bukkit/Paper, use standard teleport
            try {
                player.teleport(to);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Schedule a task on the region thread that owns the given location (Folia),
     * or on the main thread if Folia region scheduler is unavailable.
     */
    public void runAtLocation(org.bukkit.Location loc, Runnable task) {
        SchedulerAdapter.runAtLocation(this, loc, task);
    }

    public MailboxListener getMailboxListener() {
        return mailboxListener;
    }

    public boolean depositMoney(Player player, double amount) {
        try {
            if (economy != null) {
                net.milkbowl.vault.economy.EconomyResponse resp = economy.depositPlayer(player, amount);
                return resp != null && resp.transactionSuccess();
            }
            if (economyV2 != null && economyV2Class != null) {
                // Try common method names in Vault 2
                try {
                    java.lang.reflect.Method m = economyV2Class.getMethod("deposit", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    // Accept boolean or response-like objects with success() method
                    if (out instanceof Boolean) return (Boolean) out;
                    try {
                        // Recognize common response shapes: transactionSuccess/isSuccessful/success/wasSuccessful
                        for (String method : new String[]{"transactionSuccess", "isSuccessful", "success", "wasSuccessful"}) {
                            try {
                                java.lang.reflect.Method ok = out.getClass().getMethod(method);
                                Object b = ok.invoke(out);
                                if (b instanceof Boolean) return (Boolean) b;
                            } catch (NoSuchMethodException ignored3) { }
                        }
                    } catch (Throwable ignored2) {}
                    return true; // assume ok if no info available
                } catch (NoSuchMethodException e1) {
                    java.lang.reflect.Method m = economyV2Class.getMethod("depositPlayer", OfflinePlayer.class, double.class);
                    Object out = m.invoke(economyV2, player, amount);
                    if (out instanceof Boolean) return (Boolean) out;
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }
    
    /**
     * Check if PacketEvents plugin is available.
     */
    private boolean isPacketEventsAvailable() {
        // Check by plugin instance - more reliable than Class.forName
        org.bukkit.plugin.Plugin pe = Bukkit.getPluginManager().getPlugin("packetevents");
        if (pe == null) {
            pe = Bukkit.getPluginManager().getPlugin("PacketEvents");
        }
        if (pe != null && pe.isEnabled()) {
            getLogger().info("PacketEvents detected: " + pe.getName() + " v" + pe.getDescription().getVersion());
            return true;
        }
        return false;
    }
    
    /**
     * Load tax settings from config.
     */
    private void loadTaxSettings() {
        taxPercent = configManager.getTaxPercent();
        taxAccountName = configManager.getTaxAccountName();
        if (taxPercent > 0) {
            getLogger().info("Tax enabled: " + taxPercent + "% to account '" + taxAccountName + "'");
        }
    }
    
    /**
     * Get the config manager.
     */
    public dev.towki.gpexpansion.util.Config getConfigManager() {
        return configManager;
    }
    
    /**
     * Get the configured tax percentage (0 = disabled).
     */
    public double getTaxPercent() {
        return taxPercent;
    }
    
    /**
     * Get the configured tax account name.
     */
    public String getTaxAccountName() {
        return taxAccountName;
    }
    
    /**
     * Check if tax is enabled (percent > 0 and economy available).
     */
    public boolean isTaxEnabled() {
        return taxPercent > 0 && isEconomyAvailable();
    }
    
    /**
     * Deposit money to an NPC/fake account (for tax collection).
     * Uses Vault's depositPlayer with an OfflinePlayer created from the account name.
     */
    public boolean depositToAccount(String accountName, double amount) {
        if (!isEconomyAvailable() || amount <= 0) return false;
        try {
            if (economy != null) {
                // Create a fake OfflinePlayer for the NPC account
                // Most economy plugins support this for NPC/server accounts
                @SuppressWarnings("deprecation")
                OfflinePlayer fakePlayer = Bukkit.getOfflinePlayer(accountName);
                
                // Some economy plugins have createPlayerAccount - try to ensure account exists
                if (!economy.hasAccount(fakePlayer)) {
                    economy.createPlayerAccount(fakePlayer);
                }
                
                net.milkbowl.vault.economy.EconomyResponse resp = economy.depositPlayer(fakePlayer, amount);
                return resp != null && resp.transactionSuccess();
            }
        } catch (Throwable e) {
            getLogger().warning("Failed to deposit " + amount + " to tax account '" + accountName + "': " + e.getMessage());
        }
        return false;
    }
    
    /**
     * Calculate tax amount from a payment.
     * @param amount The full payment amount
     * @return The tax amount to deduct
     */
    public double calculateTax(double amount) {
        if (taxPercent <= 0) return 0;
        return amount * (taxPercent / 100.0);
    }
}
