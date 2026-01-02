package dev.towki.gpexpansion;

import dev.towki.gpexpansion.command.CancelSetupCommand;
import dev.towki.gpexpansion.command.ClaimCommand;
import dev.towki.gpexpansion.command.ClaimInfoCommand;
import dev.towki.gpexpansion.command.GPXCommand;
import dev.towki.gpexpansion.command.MailboxCommand;
import dev.towki.gpexpansion.command.RentClaimCommand;
import dev.towki.gpexpansion.command.SellClaimCommand;
import dev.towki.gpexpansion.setup.SetupWizardManager;
import dev.towki.gpexpansion.setup.SetupChatListener;
import dev.towki.gpexpansion.setup.SignAutoPasteListener;
import dev.towki.gpexpansion.setup.SignPacketListener;
import dev.towki.gpexpansion.gp.GPBridge;
import dev.towki.gpexpansion.listener.SignListener;
import dev.towki.gpexpansion.listener.MailboxListener;
import dev.towki.gpexpansion.listener.BanEnforcementListener;
import dev.towki.gpexpansion.storage.BanStore;
import dev.towki.gpexpansion.storage.NameStore;
import dev.towki.gpexpansion.storage.EvictionStore;
import dev.towki.gpexpansion.storage.PendingRentStore;
import dev.towki.gpexpansion.storage.RentalStore;
import dev.towki.gpexpansion.storage.MailboxStore;
import dev.towki.gpexpansion.command.PaperCommandWrapper;
import dev.towki.gpexpansion.permission.SignLimitManager;
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
import java.util.Locale;

public final class GPExpansionPlugin extends JavaPlugin {

    private static GPExpansionPlugin instance;

    private Economy economy; // optional
    // Optional: modern Vault 2 provider (stored as raw Object to avoid compile dep)
    private Object economyV2; // net.milkbowl.vault2.economy.Economy
    private Class<?> economyV2Class; // cached class for reflection
    private PendingRentStore pendingRentStore;
    private dev.towki.gpexpansion.reminder.RentalReminderService reminderService;
    private dev.towki.gpexpansion.confirm.ConfirmationService confirmationService;
    private NameStore nameStore;
    private BanStore banStore;
    private RentalStore rentalStore;
    private EvictionStore evictionStore;
    private MailboxStore mailboxStore;
    private SignLimitManager signLimitManager;
    private MailboxListener mailboxListener;
    private SetupWizardManager setupWizardManager;
    private Messages messages;

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();
        
        // Initialize messages/lang system
        messages = new Messages(this);
        
        // Enable debug mode if configured
        if (getConfig().getBoolean("debug.enabled", false)) {
            GPBridge.setDebug(true);
            getLogger().info("Debug mode enabled for GPBridge");
        }

        // Setup economy if Vault present
        setupEconomy();

        // Load stores
        nameStore = new NameStore(this);
        nameStore.load();
        banStore = new BanStore(this);
        banStore.load();
        rentalStore = new RentalStore(this);
        rentalStore.load();
        evictionStore = new EvictionStore(this);
        evictionStore.load();
        mailboxStore = new MailboxStore(this);
        
        // Initialize sign limit manager
        signLimitManager = new dev.towki.gpexpansion.permission.SignLimitManager(this);

        // Start reminder service
        reminderService = new dev.towki.gpexpansion.reminder.RentalReminderService(this);
        reminderService.start();

        // Start confirmation service
        confirmationService = new dev.towki.gpexpansion.confirm.ConfirmationService(this);

        // Delay until server tick using Folia global scheduler when available
        runGlobal(() -> {
            try {
                unregisterExistingClaimCommands();
            } catch (Exception e) {
                getLogger().warning("Failed to proactively unregister existing /claim: " + e.getMessage());
            }

            // Register command programmatically (Paper requires this)
            ClaimCommand claimCommand = new ClaimCommand(this);
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
            // Prefer Paper's JavaPlugin#registerCommand when available; otherwise fallback to CommandMap
            try {
                java.lang.reflect.Method reg = JavaPlugin.class.getMethod("registerCommand", Command.class);
                reg.invoke(this, wrapper);
                reg.invoke(this, trustlistWrapper);
                reg.invoke(this, adminClaimListWrapper);
                reg.invoke(this, claimsListWrapper);
            } catch (NoSuchMethodException missing) {
                try {
                    Object mapObj = getServer().getClass().getMethod("getCommandMap").invoke(getServer());
                    if (mapObj instanceof CommandMap) {
                        CommandMap map = (CommandMap) mapObj;
                        map.register("gpexpansion", wrapper);
                        map.register("gpexpansion", trustlistWrapper);
                        map.register("gpexpansion", adminClaimListWrapper);
                        map.register("gpexpansion", claimsListWrapper);
                        // Register /gpxconfirm command as well
                        dev.towki.gpexpansion.command.ConfirmCommand confirm = new dev.towki.gpexpansion.command.ConfirmCommand(this);
                        Command wrapper2 = new PaperCommandWrapper(
                                this, // Pass plugin instance
                                "gpxconfirm",
                                "Confirm GPExpansion actions",
                                "/gpxconfirm <token> <accept|cancel>",
                                Collections.emptyList(),
                                confirm,
                                confirm
                        );
                        map.register("gpexpansion", wrapper2);
                        
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
                        
                        // Register /mailbox command
                        MailboxCommand mailboxCommand = new MailboxCommand(this);
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
                        
                        // Initialize setup wizard manager
                        setupWizardManager = new SetupWizardManager(this);
                        
                        // Register chat listener for wizard (must be done here after wizard manager is created)
                        Bukkit.getPluginManager().registerEvents(new SetupChatListener(GPExpansionPlugin.this, setupWizardManager), GPExpansionPlugin.this);
                        getLogger().info("- Registered SetupChatListener for wizard commands");
                        
                        // Wire wizard manager to mailbox command
                        mailboxCommand.setWizardManager(setupWizardManager);
                        
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
                        
                        // Register sign auto-paste listener (fallback for SignChangeEvent injection)
                        SignAutoPasteListener autoPasteListener = new SignAutoPasteListener(GPExpansionPlugin.this, setupWizardManager);
                        Bukkit.getPluginManager().registerEvents(autoPasteListener, GPExpansionPlugin.this);
                        getLogger().info("- Registered SignAutoPasteListener for auto-paste mode");
                        
                        // Try to register PacketEvents listener for better sign GUI experience
                        if (isPacketEventsAvailable()) {
                            try {
                                SignPacketListener packetListener = new SignPacketListener(GPExpansionPlugin.this, setupWizardManager);
                                packetListener.register();
                                // Tell autoPasteListener that PacketEvents is handling sign pre-fill
                                autoPasteListener.setPacketEventsAvailable(true);
                            } catch (Exception e) {
                                getLogger().warning("Failed to register PacketEvents listener: " + e.getMessage());
                                getLogger().info("Falling back to GUI bypass mode");
                            }
                        } else {
                            getLogger().info("PacketEvents not found - sign wizard will use fallback mode (GUI bypass)");
                        }
                    } else {
                        getLogger().severe("Could not obtain CommandMap to register /claim");
                    }
                } catch (ReflectiveOperationException e) {
                    getLogger().severe("Failed to register /claim command via CommandMap: " + e.getMessage());
                }
            } catch (ReflectiveOperationException e) {
                getLogger().severe("Failed to register /claim command: " + e.getMessage());
            }
            getLogger().info("Registered /claim, /trustlist, /adminclaimlist, /gpx, /rentclaim, /sellclaim, /cancelsetup commands under GPExpansion");
        });

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
        // Join reminders
        Bukkit.getPluginManager().registerEvents(new dev.towki.gpexpansion.listener.ReminderJoinListener(this), this);
        // Dynamic sign display ([Renew] and item scroll)
        Bukkit.getPluginManager().registerEvents(new dev.towki.gpexpansion.listener.SignDisplayListener(this), this);
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

    @Override
    public void onDisable() {
        if (reminderService != null) {
            reminderService.stop();
        }
        if (nameStore != null) {
            nameStore.save();
        }
        if (banStore != null) {
            banStore.save();
        }
        if (rentalStore != null) {
            rentalStore.save();
        }
        if (evictionStore != null) {
            evictionStore.save();
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
        
        // Re-apply debug setting
        GPBridge.setDebug(getConfig().getBoolean("debug.enabled", false));
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

    public NameStore getNameStore() {
        return nameStore;
    }

    public BanStore getBanStore() {
        return banStore;
    }

    public RentalStore getRentalStore() {
        return rentalStore;
    }
    
    public MailboxStore getMailboxStore() {
        return mailboxStore;
    }
    
    public MailboxListener getMailboxListener() {
        return mailboxListener;
    }

    public EvictionStore getEvictionStore() {
        return evictionStore;
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
        try {
            // Folia: use GlobalRegionScheduler
            Object grs = getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(getServer());
            // run(Plugin, Consumer<ScheduledTask>) signature
            java.lang.reflect.Method runMethod = grs.getClass().getMethod("run", org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class);
            runMethod.invoke(grs, this, (java.util.function.Consumer<Object>) scheduledTask -> task.run());
        } catch (Throwable t) {
            // Fallback to Bukkit scheduler on Paper/Spigot
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    public void runAtEntity(Player player, Runnable task) {
        try {
            // Check if this is Folia
            try {
                // Folia: Player has getScheduler()
                Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
                java.lang.reflect.Method execute = scheduler.getClass().getMethod(
                        "execute",
                        org.bukkit.plugin.Plugin.class,
                        java.lang.Runnable.class,
                        java.util.function.Consumer.class,
                        long.class
                );
                // execute(plugin, runnable, null, 0L)
                execute.invoke(scheduler, this, (Runnable) task, null, 0L);
                return;
            } catch (NoSuchMethodException ignored) {
                // Not Folia, fall through to Bukkit scheduler
            }
            // Fallback to Bukkit scheduler on non-Folia
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isValid()) {
                    task.run();
                }
            });
        } catch (Throwable t) {
            // If Folia is present, do NOT fall back to Bukkit main-thread scheduler (it will throw UOE).
            try {
                Object grs = getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(getServer());
                // run(Plugin, Consumer<ScheduledTask>)
                java.lang.reflect.Method runMethod = grs.getClass().getMethod(
                        "run",
                        org.bukkit.plugin.Plugin.class,
                        java.util.function.Consumer.class
                );
                runMethod.invoke(grs, this, (java.util.function.Consumer<Object>) scheduledTask -> {
                    try {
                        Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
                        java.lang.reflect.Method execute = scheduler.getClass().getMethod(
                                "execute",
                                org.bukkit.plugin.Plugin.class,
                                java.lang.Runnable.class,
                                java.util.function.Consumer.class,
                                long.class
                        );
                        execute.invoke(scheduler, this, (Runnable) task, null, 0L);
                    } catch (Throwable ignored) {
                        // As a last resort (non-Folia), attempt Bukkit scheduler
                        try { org.bukkit.Bukkit.getScheduler().runTask(this, task); } catch (Throwable ignored2) {}
                    }
                });
                return;
            } catch (Throwable ignoredFolia) {
                // Non-Folia/Paper fallback
                org.bukkit.Bukkit.getScheduler().runTask(this, task);
            }
        }
    }

    public void teleportEntity(Player player, Location to) {
        runAtEntity(player, () -> {
            try { player.teleport(to); } catch (Throwable ignored) {}
        });
    }

    /**
     * Schedule a task on the region thread that owns the given location (Folia),
     * or on the main thread if Folia region scheduler is unavailable.
     */
    public void runAtLocation(org.bukkit.Location loc, Runnable task) {
        try {
            Object rs = getServer().getClass().getMethod("getRegionScheduler").invoke(getServer());
            try {
                // Try signature: run(Plugin, World, int chunkX, int chunkZ, Consumer<ScheduledTask>)
                java.lang.reflect.Method run = rs.getClass().getMethod(
                        "run",
                        org.bukkit.plugin.Plugin.class,
                        org.bukkit.World.class,
                        int.class,
                        int.class,
                        java.util.function.Consumer.class
                );
                int cx = loc.getBlockX() >> 4;
                int cz = loc.getBlockZ() >> 4;
                run.invoke(rs, this, loc.getWorld(), cx, cz, (java.util.function.Consumer<Object>) st -> task.run());
                return;
            } catch (NoSuchMethodException ignored) {
                // Fallback signature: run(Plugin, Location, Consumer<ScheduledTask>)
                java.lang.reflect.Method run2 = rs.getClass().getMethod(
                        "run",
                        org.bukkit.plugin.Plugin.class,
                        org.bukkit.Location.class,
                        java.util.function.Consumer.class
                );
                run2.invoke(rs, this, loc, (java.util.function.Consumer<Object>) st -> task.run());
                return;
            }
        } catch (Throwable t) {
            // Fallback to Bukkit main thread
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    public PendingRentStore getPendingRentStore() {
        return pendingRentStore;
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
}
