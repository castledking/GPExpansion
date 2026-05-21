package codes.castled.gpexpansion;

import codes.castled.gpexpansion.command.CancelSetupCommand;
import codes.castled.gpexpansion.command.ClaimCommand;
import codes.castled.gpexpansion.command.ClaimFlyCommand;
import codes.castled.gpexpansion.command.ClaimInfoCommand;
import codes.castled.gpexpansion.command.GPXCommand;
import codes.castled.gpexpansion.command.MailboxCommand;
import codes.castled.gpexpansion.command.RentClaimCommand;
import codes.castled.gpexpansion.command.SellClaimCommand;
import codes.castled.gpexpansion.config.VersionManager;
import codes.castled.gpexpansion.claimfly.ClaimFlyManager;
import codes.castled.gpexpansion.setup.SetupWizardManager;
import codes.castled.gpexpansion.setup.SetupChatListener;
import codes.castled.gpexpansion.setup.SignAutoPasteListener;
import codes.castled.gpexpansion.gp.GPBridge;
import codes.castled.gpexpansion.listener.SignListener;
import codes.castled.gpexpansion.listener.MailboxListener;
import codes.castled.gpexpansion.listener.BanEnforcementListener;
import codes.castled.gpexpansion.command.PaperCommandWrapper;
import codes.castled.gpexpansion.permission.SignLimitManager;
import codes.castled.gpexpansion.scheduler.SchedulerAdapter;
import codes.castled.gpexpansion.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

public final class GPExpansionPlugin extends JavaPlugin {

    private codes.castled.gpexpansion.economy.EconomyManager economyManager;
    private codes.castled.gpexpansion.economy.TaxManager taxManager;
    private codes.castled.gpexpansion.sign.RentalSignManager rentalSignManager;
    private codes.castled.gpexpansion.scheduler.SchedulerFacade schedulerFacade;
    private codes.castled.gpexpansion.permission.PermissionService permissionService;
    private codes.castled.gpexpansion.reminder.RentalReminderService reminderService;
    private codes.castled.gpexpansion.confirm.ConfirmationService confirmationService;
    private codes.castled.gpexpansion.storage.ClaimDataStore claimDataStore;
    private codes.castled.gpexpansion.storage.ClaimSnapshotStore snapshotStore;
    private codes.castled.gpexpansion.gui.GUIManager guiManager;
    private SignLimitManager signLimitManager;
    private MailboxListener mailboxListener;
    private SetupWizardManager setupWizardManager;
    private codes.castled.gpexpansion.gui.DescriptionInputManager descriptionInputManager;
    private Messages messages;
    private codes.castled.gpexpansion.util.Config configManager;
    private VersionManager versionManager;
    private codes.castled.gpexpansion.permission.PermissionManager permissionManager;
    private ClaimCommand claimCommand;
    private boolean gp3dClaimMode;
    private codes.castled.gpexpansion.listener.SignDisplayListener signDisplayListener;
    private codes.castled.gpexpansion.scheduler.TaskHandle evictionDisplayTickTask;
    private ClaimFlyManager claimFlyManager;

    @Override
    public void onEnable() {
        // Initialize config manager (handles defaults automatically)
        configManager = new codes.castled.gpexpansion.util.Config(this);
        configManager.load();
        
        // Initialize messages/lang system
        messages = new Messages(this);
        
        // Enable debug mode if configured
        if (configManager.isDebugEnabled()) {
            GPBridge.setDebug(true);
            getLogger().info("Debug mode enabled for GPBridge");
        }
        
        // Initialize managers
        economyManager = new codes.castled.gpexpansion.economy.EconomyManager(this);
        taxManager = new codes.castled.gpexpansion.economy.TaxManager(this);
        rentalSignManager = new codes.castled.gpexpansion.sign.RentalSignManager(this);
        schedulerFacade = new codes.castled.gpexpansion.scheduler.SchedulerFacade(this);
        permissionService = new codes.castled.gpexpansion.permission.PermissionService();

        // Load tax settings
        taxManager.loadTaxSettings();

        // Setup economy if Vault present
        economyManager.setupEconomy();
        permissionService.setupVaultPermission();

        // Initialize version manager and check for migrations
        versionManager = new VersionManager(this);
        versionManager.checkAndMigrateConfiguration();

        // Load stores
        claimDataStore = new codes.castled.gpexpansion.storage.ClaimDataStore(this);
        claimDataStore.load();
        snapshotStore = new codes.castled.gpexpansion.storage.ClaimSnapshotStore(this);
        
        // Initialize GUI manager
        guiManager = new codes.castled.gpexpansion.gui.GUIManager(this);
        
        // Initialize sign limit manager
        signLimitManager = new codes.castled.gpexpansion.permission.SignLimitManager(this);
        claimFlyManager = new ClaimFlyManager(this);
        
        // Initialize permission manager (handles dynamic gpx.player permissions)
        try {
            permissionManager = new codes.castled.gpexpansion.permission.PermissionManager(this);
        } catch (NoClassDefFoundError e) {
            permissionManager = null;
            getLogger().info("Vault classes not available - permission management features disabled");
        } catch (Exception e) {
            permissionManager = null;
            getLogger().warning("Failed to initialize PermissionManager: " + e.getMessage());
        }

        // Start reminder service
        reminderService = new codes.castled.gpexpansion.reminder.RentalReminderService(this);
        reminderService.start();

        // Start confirmation service
        confirmationService = new codes.castled.gpexpansion.confirm.ConfirmationService(this);

        registerPluginCommands();

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new codes.castled.gpexpansion.claimfly.ClaimFlyPlaceholderExpansion(this).register();
            getLogger().info("- Registered PlaceholderAPI claim flight placeholders");
        }

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
        
        codes.castled.gpexpansion.listener.CommandInterceptListener commandListener = 
            new codes.castled.gpexpansion.listener.CommandInterceptListener(this);
        Bukkit.getPluginManager().registerEvents(commandListener, this);
        getLogger().info("- Registered CommandInterceptListener: " + commandListener.getClass().getName());
        // Protect rent/buy signs and their supports from griefing and enforce deletion flow
        Bukkit.getPluginManager().registerEvents(new codes.castled.gpexpansion.listener.SignProtectionListener(this), this);
        // Prevent claim abandonment during active rentals
        Bukkit.getPluginManager().registerEvents(new codes.castled.gpexpansion.listener.ClaimAbandonListener(this), this);
        // Join reminders
        Bukkit.getPluginManager().registerEvents(new codes.castled.gpexpansion.listener.ReminderJoinListener(this), this);
        // Dynamic sign display ([Renew], [Evicted] countdown, item scroll)
        signDisplayListener = new codes.castled.gpexpansion.listener.SignDisplayListener(this);
        Bukkit.getPluginManager().registerEvents(signDisplayListener, this);
        startEvictionDisplayTick();
        // Economy late-hook listener
        Bukkit.getPluginManager().registerEvents(new codes.castled.gpexpansion.listener.EconomyHookListener(this), this);
        // Claim flight listener
        Bukkit.getPluginManager().registerEvents(new codes.castled.gpexpansion.listener.ClaimFlyListener(this), this);
        getLogger().info("- Registered ClaimFlyListener for claim flight feature");
        registerAccrualListener();
        
        getLogger().info(() -> "GPExpansion enabled");
    }

    private void registerAccrualListener() {
        if (!Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) {
            getLogger().info("- Skipped AccrualListener because GriefPrevention is not enabled");
            return;
        }

        try {
            Bukkit.getPluginManager().registerEvents(new codes.castled.gpexpansion.listener.AccrualListener(this), this);
            getLogger().info("- Registered AccrualListener for claim block accrual profiles");
        } catch (NoClassDefFoundError error) {
            getLogger().warning("- Skipped AccrualListener because this GriefPrevention build does not expose the required accrual events: " + error.getMessage());
        }
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

    @SuppressWarnings("unchecked")
    private void unregisterExistingCommands(String... labels) throws ReflectiveOperationException {
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
        if (f == null) {
            getLogger().warning("Could not reflectively access knownCommands; cannot clean up duplicate command labels");
            return;
        }

        f.setAccessible(true);
        Object raw = f.get(map);
        if (!(raw instanceof java.util.Map)) return;
        java.util.Map<String, Command> known = (java.util.Map<String, Command>) raw;
        String pluginNamespace = getName().toLowerCase(Locale.ROOT);

        for (String label : Arrays.asList(labels)) {
            for (String key : Arrays.asList(label, pluginNamespace + ":" + label)) {
                Command existing = known.get(key);
                if (existing == null) continue;
                try {
                    existing.unregister(map);
                } catch (Throwable ignored) { }
                known.remove(key);
                getLogger().info("Unregistered existing command label: " + key);
            }
        }
    }

    private void registerPluginCommands() {
        ClaimCommand claimCommand = new ClaimCommand(this);
        boolean gp3dPresent = new codes.castled.gpexpansion.gp.GPBridge().isGP3D();
        if (gp3dPresent) {
            try {
                unregisterClaimlistCommands();
            } catch (Exception e) {
                getLogger().warning("Failed to unregister claimlist for takeover: " + e.getMessage());
            }
            codes.castled.gpexpansion.gp.ClaimCommandAddonImpl.register(this);
            getLogger().info("GP3D detected: GPExpansion takes claimslist/claimlist for enhanced display, intercepts /claim list");
        } else {
            try {
                unregisterExistingClaimCommands();
            } catch (Exception e) {
                getLogger().warning("Failed to proactively unregister existing /claim: " + e.getMessage());
            }
        }

        this.claimCommand = claimCommand;
        this.gp3dClaimMode = gp3dPresent;

        Command wrapper = new PaperCommandWrapper(
                this,
                "claim",
                "Unified GriefPrevention claim command",
                "/claim <sub>",
                java.util.Arrays.asList("claims"),
                claimCommand,
                claimCommand
        );
        Command adminClaimListWrapper = new PaperCommandWrapper(
                this,
                "adminclaimlist",
                "Show expanded admin claims list (with IDs and subclaims)",
                "/adminclaimlist",
                java.util.Arrays.asList("adminclaimslist"),
                claimCommand,
                claimCommand
        );
        Command claimsListWrapper = new PaperCommandWrapper(
                this,
                "claimslist",
                "Show your claims list (with IDs and names)",
                "/claimslist",
                java.util.Arrays.asList("claimlist"),
                claimCommand,
                claimCommand
        );
        Command globalClaimWrapper = new PaperCommandWrapper(
                this,
                "globalclaim",
                "Toggle or set global listing for a claim",
                "/globalclaim [true|false] [claimId]",
                java.util.Arrays.asList("toggleglobal"),
                claimCommand,
                claimCommand
        );

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

        try {
            unregisterExistingCommands(
                    "gpx",
                    "claiminfo",
                    "globalclaim",
                    "claimfly",
                    "claimtp",
                    "setclaimspawn",
                    "resizeclaim",
                    "claimmap"
            );
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Failed to clean up duplicate command labels: " + e.getMessage());
        }

        try {
            java.lang.reflect.Method reg = JavaPlugin.class.getMethod("registerCommand", Command.class);
            if (!gp3dPresent) reg.invoke(this, wrapper);
            reg.invoke(this, adminClaimListWrapper);
            reg.invoke(this, claimsListWrapper);
            reg.invoke(this, globalClaimWrapper);
        } catch (NoSuchMethodException missing) {
            if (!gp3dPresent) map.register("gpexpansion", wrapper);
            map.register("gpexpansion", adminClaimListWrapper);
            map.register("gpexpansion", claimsListWrapper);
            map.register("gpexpansion", globalClaimWrapper);
        } catch (ReflectiveOperationException e) {
            getLogger().severe("Failed to register core commands: " + e.getMessage());
        }

        codes.castled.gpexpansion.command.ConfirmCommand confirm = new codes.castled.gpexpansion.command.ConfirmCommand(this);
        Command confirmWrapper = new PaperCommandWrapper(
                this,
                "gpxconfirm",
                "Confirm GPExpansion actions",
                "/gpxconfirm [accept|cancel]",
                Collections.emptyList(),
                confirm,
                confirm
        );
        registerRuntimeCommand(map, confirmWrapper);

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
        registerRuntimeCommand(map, gpxWrapper);

        ClaimFlyCommand claimFlyCommand = new ClaimFlyCommand(this);
        Command claimFlyWrapper = new PaperCommandWrapper(
                this,
                "claimfly",
                "Toggle or manage claim flight time",
                "/claimfly [add|check|reset|take|set] [players] [time]",
                java.util.Collections.emptyList(),
                claimFlyCommand,
                claimFlyCommand
        );
        registerRuntimeCommand(map, claimFlyWrapper);

        setupWizardManager = new SetupWizardManager(this);

        Bukkit.getPluginManager().registerEvents(new SetupChatListener(this, setupWizardManager), this);
        getLogger().info("- Registered SetupChatListener for wizard commands");

        descriptionInputManager = new codes.castled.gpexpansion.gui.DescriptionInputManager(this);
        Bukkit.getPluginManager().registerEvents(new codes.castled.gpexpansion.gui.DescriptionChatListener(this, descriptionInputManager), this);
        getLogger().info("- Registered DescriptionChatListener for claim descriptions");

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
        registerRuntimeCommand(map, mailboxWrapper);

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
        registerRuntimeCommand(map, rentClaimWrapper);

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
        registerRuntimeCommand(map, sellClaimWrapper);

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
        registerRuntimeCommand(map, claimInfoWrapper);

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
        registerRuntimeCommand(map, cancelSetupWrapper);

        Command claimTpWrapper = new PaperCommandWrapper(
                this,
                "claimtp",
                "Teleport to a claim",
                "/claimtp <claimId> [player]",
                Collections.emptyList(),
                claimCommand,
                claimCommand
        );
        registerRuntimeCommand(map, claimTpWrapper);

        Command setClaimSpawnWrapper = new PaperCommandWrapper(
                this,
                "setclaimspawn",
                "Set the teleport spawn point for a claim",
                "/setclaimspawn",
                Collections.emptyList(),
                claimCommand,
                claimCommand
        );
        registerRuntimeCommand(map, setClaimSpawnWrapper);

        Command expandClaimWrapper = new PaperCommandWrapper(
                this,
                "resizeclaim",
                "Open the claim resize menu or forward a resize request",
                "/resizeclaim [blocks]",
                java.util.Collections.emptyList(),
                claimCommand,
                claimCommand
        );
        registerRuntimeCommand(map, expandClaimWrapper);

        Command claimMapWrapper = new PaperCommandWrapper(
                this,
                "claimmap",
                "Open the claim map editor for the claim you are standing in",
                "/claimmap",
                java.util.Collections.emptyList(),
                claimCommand,
                claimCommand
        );
        registerRuntimeCommand(map, claimMapWrapper);

        // /buyclaimblocks - opens the hopper confirmation GUI (migrated from GP3D).
        // Unregister any prior owner (GP3D itself or other plugins) first so our
        // handler wins the command dispatch.
        try {
            unregisterExistingCommands("buyclaimblocks");
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Failed to clean up existing /buyclaimblocks registration: " + e.getMessage());
        }
        codes.castled.gpexpansion.command.BuyClaimBlocksCommand buyCommand =
                new codes.castled.gpexpansion.command.BuyClaimBlocksCommand(this);
        Command buyWrapper = new PaperCommandWrapper(
                this,
                "buyclaimblocks",
                "Purchase additional claim blocks (opens confirmation GUI)",
                "/buyclaimblocks <amount>",
                java.util.Collections.emptyList(),
                buyCommand,
                buyCommand
        );
        registerRuntimeCommand(map, buyWrapper);

        SignAutoPasteListener autoPasteListener = new SignAutoPasteListener(this, setupWizardManager);
        Bukkit.getPluginManager().registerEvents(autoPasteListener, this);
        getLogger().info("- Registered SignAutoPasteListener for auto-paste mode");
        syncCommandTree();
        getLogger().info("Registered /claim, /claimmap, /adminclaimlist, /gpx, /rentclaim, /sellclaim, /cancelsetup commands under GPExpansion");
    }

    private void syncCommandTree() {
        try {
            java.lang.reflect.Method sync = getServer().getClass().getMethod("syncCommands");
            sync.invoke(getServer());
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Failed to sync command tree after dynamic registration: " + e.getMessage());
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                if (player != null) {
                    player.updateCommands();
                }
            } catch (Throwable ignored) { }
        }
    }

    private void registerRuntimeCommand(CommandMap map, Command command) {
        try {
            java.lang.reflect.Method reg = JavaPlugin.class.getMethod("registerCommand", Command.class);
            reg.invoke(this, command);
        } catch (NoSuchMethodException missing) {
            map.register("gpexpansion", command);
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Falling back to CommandMap registration for /" + command.getName() + ": " + e.getMessage());
            map.register("gpexpansion", command);
        }
    }

    /** Starts a periodic task (every 1 second) that updates eviction countdowns for all online players, even when standing still. */
    @SuppressWarnings("all")
    private void startEvictionDisplayTick() {
        if (signDisplayListener == null) return;
        if (evictionDisplayTickTask != null) {
            evictionDisplayTickTask.cancel();
        }
        // Run every 20 ticks (1 second); for each player run sign update on their entity region (Folia-safe)
        evictionDisplayTickTask = SchedulerAdapter.runRepeatingGlobal(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isValid() && p.getWorld() != null) {
                    schedulerFacade.runAtEntity(p, () -> signDisplayListener.tickEvictionDisplays(p));
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
            codes.castled.gpexpansion.gp.ClaimCommandAddonImpl.unregister();
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
        if (claimFlyManager != null) {
            claimFlyManager.save();
        }
        getLogger().info(() -> "GPExpansion disabled");
    }

    public codes.castled.gpexpansion.storage.ClaimSnapshotStore getSnapshotStore() {
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
        taxManager.loadTaxSettings();
        
        // Reload permission manager (updates gpx.player children)
        if (permissionManager != null) {
            permissionManager.reload();
        }
    }

    public codes.castled.gpexpansion.permission.PermissionManager getPermissionManager() {
        return permissionManager;
    }

    /**
     * Get the consolidated claim data store
     */
    public codes.castled.gpexpansion.storage.ClaimDataStore getClaimDataStore() {
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
    
    public codes.castled.gpexpansion.gui.GUIManager getGUIManager() {
        return guiManager;
    }

    public codes.castled.gpexpansion.gui.DescriptionInputManager getDescriptionInputManager() {
        return descriptionInputManager;
    }
    
    public SignLimitManager getSignLimitManager() {
        return signLimitManager;
    }

    public ClaimFlyManager getClaimFlyManager() {
        return claimFlyManager;
    }

    public codes.castled.gpexpansion.reminder.RentalReminderService getReminderService() {
        return reminderService;
    }

    public codes.castled.gpexpansion.confirm.ConfirmationService getConfirmationService() {
        return confirmationService;
    }

    public MailboxListener getMailboxListener() {
        return mailboxListener;
    }

    public codes.castled.gpexpansion.util.Config getConfigManager() {
        return configManager;
    }
    
    // New manager getters
    
    public codes.castled.gpexpansion.economy.EconomyManager getEconomyManager() {
        return economyManager;
    }
    
    public codes.castled.gpexpansion.economy.TaxManager getTaxManager() {
        return taxManager;
    }
    
    public codes.castled.gpexpansion.sign.RentalSignManager getRentalSignManager() {
        return rentalSignManager;
    }
    
    public codes.castled.gpexpansion.scheduler.SchedulerFacade getSchedulerFacade() {
        return schedulerFacade;
    }
    
    public codes.castled.gpexpansion.permission.PermissionService getPermissionService() {
        return permissionService;
    }
}
