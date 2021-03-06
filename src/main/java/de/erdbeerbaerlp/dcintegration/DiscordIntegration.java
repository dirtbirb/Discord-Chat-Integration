package de.erdbeerbaerlp.dcintegration;


import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import com.google.gson.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.erdbeerbaerlp.dcintegration.commands.*;
import de.erdbeerbaerlp.dcintegration.storage.Configuration;
import net.dv8tion.jda.api.entities.Message;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.event.server.*;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;


@SuppressWarnings("ConstantConditions")
@Mod(DiscordIntegration.MODID)
public class DiscordIntegration {
    /**
     * Mod name
     */
    public static final String NAME = "Discord Integration";
    /**
     * Mod version
     */
    public static final String VERSION = "1.2.1";
    /**
     * Modid
     */
    public static final String MODID = "dcintegration";
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Pattern PATTERN_CONTROL_CODE = Pattern.compile("(?i)\\u00A7[0-9A-FK-OR]");
    /**
     * The only instance of {@link Discord}
     */
    public static Discord discord_instance;
    /**
     * Time when the server was started
     */
    public static long started;
    public static ModConfig cfg = null;
    public static PlayerEntity lastTimeout;
    public static final ArrayList<UUID> timeouts = new ArrayList<>();
    static String defaultCommandJson;

    static {
        final JsonObject a = new JsonObject();
        final JsonObject kick = new JsonObject();
        kick.addProperty("adminOnly", true);
        kick.addProperty("mcCommand", "kick");
        kick.addProperty("description", "Kicks a player from the server");
        kick.addProperty("useArgs", true);
        kick.addProperty("argText", "<player> [reason]");
        a.add("kick", kick);
        final JsonObject stop = new JsonObject();
        stop.addProperty("adminOnly", true);
        stop.addProperty("mcCommand", "stop");
        stop.addProperty("description", "Stops the server");
        final JsonArray stopAliases = new JsonArray();
        stopAliases.add("shutdown");
        stop.add("aliases", stopAliases);
        stop.addProperty("useArgs", false);
        a.add("stop", stop);
        final JsonObject kill = new JsonObject();
        kill.addProperty("adminOnly", true);
        kill.addProperty("mcCommand", "kill");
        kill.addProperty("description", "Kills a player");
        kill.addProperty("useArgs", true);
        kill.addProperty("argText", "<player>");
        a.add("kill", kill);
        final JsonObject tps = new JsonObject();
        tps.addProperty("adminOnly", false);
        tps.addProperty("mcCommand", "forge tps");
        tps.addProperty("description", "Displays TPS");
        tps.addProperty("useArgs", false);
        a.add("tps", tps);
        final Gson gson = new GsonBuilder().create();
        defaultCommandJson = gson.toJson(a);
    }

    /**
     * Message sent when the server is starting (in non-webhook mode!), stored for editing
     */
    private CompletableFuture<Message> startingMsg;
    /**
     * If the server was stopped or has crashed
     */
    private boolean stopped = false;

    public DiscordIntegration() throws SecurityException, IllegalArgumentException {

        //Register Config
        final ModLoadingContext modLoadingContext = ModLoadingContext.get();
        modLoadingContext.registerConfig(ModConfig.Type.COMMON, Configuration.cfgSpec);

        // Register the setup method for modloading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::preInit);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static String formatPlayerName(Entity p) {
        return formatPlayerName(p, true);
    }

    public static void registerConfigCommands() {
        final JsonObject commandJson = new JsonParser().parse(Configuration.INSTANCE.jsonCommands.get()).getAsJsonObject();
        System.out.println("Detected to load " + commandJson.size() + " commands to load from config");
        for (Map.Entry<String, JsonElement> cmd : commandJson.entrySet()) {
            final JsonObject cmdVal = cmd.getValue().getAsJsonObject();
            if (!cmdVal.has("mcCommand")) {
                System.err.println("Skipping command " + cmd.getKey() + " because it is invalid! Check your config!");
                continue;
            }
            final String mcCommand = cmdVal.get("mcCommand").getAsString();
            final String desc = cmdVal.has("description") ? cmdVal.get("description").getAsString() : "No Description";
            final boolean admin = !cmdVal.has("adminOnly") || cmdVal.get("adminOnly").getAsBoolean();
            final boolean useArgs = !cmdVal.has("useArgs") || cmdVal.get("useArgs").getAsBoolean();
            String argText = "<args>";
            if (cmdVal.has("argText")) argText = cmdVal.get("argText").getAsString();
            String[] aliases = new String[0];
            if (cmdVal.has("aliases") && cmdVal.get("aliases").isJsonArray()) {
                aliases = new String[cmdVal.getAsJsonArray("aliases").size()];
                for (int i = 0; i < aliases.length; i++)
                    aliases[i] = cmdVal.getAsJsonArray("aliases").get(i).getAsString();
            }
            String[] channelID = (cmdVal.has("channelID") && cmdVal.get("channelID") instanceof JsonArray) ? makeStringArray(cmdVal.get("channelID").getAsJsonArray()) : new String[]{"0"};
            final DiscordCommand regCmd = new CommandFromCFG(cmd.getKey(), desc, mcCommand, admin, aliases, useArgs, argText, channelID);
            if (!discord_instance.registerCommand(regCmd))
                System.err.println("Failed Registering command \"" + cmd.getKey() + "\" because it would override an existing command!");

        }
    }

    private static String[] makeStringArray(final JsonArray channelID) {
        final String[] out = new String[channelID.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = channelID.get(i).getAsString();
        }
        return out;
    }

    public static String getFullUptime() {
        if (started == 0) {
            return "?????";
        }
        final Duration duration = Duration.between(Instant.ofEpochMilli(started), Instant.now());
        return DurationFormatUtils.formatDuration(duration.toMillis(), Configuration.INSTANCE.uptimeFormat.get());
    }

    public static int getUptimeSeconds() {
        long diff = new Date().getTime() - started;
        return (int) Math.floorDiv(diff, 1000);
    }

    public static int getUptimeMinutes() {
        return Math.floorDiv(getUptimeSeconds(), 60);
    }

    public static int getUptimeHours() {
        return Math.floorDiv(getUptimeMinutes(), 60);
    }

    public static int getUptimeDays() {
        return Math.floorDiv(getUptimeHours(), 24);
    }

    public static String formatPlayerName(Entity p, boolean chatFormat) {
        /*if (Loader.isModLoaded("ftbutilities") && p instanceof EntityPlayer) {
            final FTBUtilitiesPlayerData d = FTBUtilitiesPlayerData.get(Universe.get().getPlayer(p));
            final String nick = (Configuration.FTB_UTILITIES.CHAT_FORMATTING && chatFormat) ? d.getNameForChat((EntityPlayerMP) p).getUnformattedText().replace("<", "").replace(">", "").trim() : d.getNickname().trim();
            if (!nick.isEmpty()) return nick;
        }*/
        if (p.getDisplayName().getUnformattedComponentText().isEmpty())
            return p.getName().getUnformattedComponentText();
        else
            return p.getDisplayName().getUnformattedComponentText();
    }

    public static String stripControlCodes(String text) {
        return PATTERN_CONTROL_CODE.matcher(text).replaceAll("");
    }

    @SubscribeEvent
    public void playerJoin(final PlayerEvent.PlayerLoggedInEvent ev) {
        if (discord_instance != null) {
            discord_instance.sendMessage(Configuration.INSTANCE.msgPlayerJoin.get().replace("%player%", formatPlayerName(ev.getPlayer())));
        }
    }

    @SubscribeEvent
    public void onModConfigEvent(final ModConfig.ModConfigEvent event) {
        final ModConfig config = event.getConfig();
        // Rebake the configs when they change
        if (config.getSpec() == Configuration.cfgSpec) {
            cfg = config;
        }
    }

    @SubscribeEvent
    public void advancement(AdvancementEvent ev) {
        if (discord_instance != null && ev.getAdvancement().getDisplay() != null && ev.getAdvancement().getDisplay().shouldAnnounceToChat())
            discord_instance.sendMessage(Configuration.INSTANCE.msgAdvancement.get().replace("%player%",
                    ev.getPlayer()
                            .getName()
                            .getUnformattedComponentText())
                    .replace("%name%",
                            ev.getAdvancement()
                                    .getDisplay()
                                    .getTitle()
                                    .getUnformattedComponentText())
                    .replace("%desc%",
                            ev.getAdvancement()
                                    .getDisplay()
                                    .getDescription()
                                    .getUnformattedComponentText())
                    .replace("\\n", "\n"));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void preInit(final FMLDedicatedServerSetupEvent ev) {
        System.out.println("Loading mod");
        try {
            discord_instance = new Discord();
            if (Configuration.INSTANCE.cmdHelpEnabled.get()) discord_instance.registerCommand(new CommandHelp());
            if (Configuration.INSTANCE.cmdListEnabled.get()) discord_instance.registerCommand(new CommandList());
            if (Configuration.INSTANCE.cmdUptimeEnabled.get()) discord_instance.registerCommand(new CommandUptime());
            final JsonObject commandJson = new JsonParser().parse(Configuration.INSTANCE.jsonCommands.get()).getAsJsonObject();
            System.out.println("Detected to load " + commandJson.size() + " commands to load from config");
            for (Map.Entry<String, JsonElement> cmd : commandJson.entrySet()) {
                final JsonObject cmdVal = cmd.getValue().getAsJsonObject();
                if (!cmdVal.has("mcCommand")) {
                    System.err.println("Skipping command " + cmd.getKey() + " because it is invalid! Check your config!");
                    continue;
                }

                final String mcCommand = cmdVal.get("mcCommand").getAsString();
                final String desc = cmdVal.has("description") ? cmdVal.get("description").getAsString() : "No Description";
                final boolean admin = !cmdVal.has("adminOnly") || cmdVal.get("adminOnly").getAsBoolean();
                final boolean useArgs = !cmdVal.has("useArgs") || cmdVal.get("useArgs").getAsBoolean();
                String argText = "<args>";
                if (cmdVal.has("argText")) argText = cmdVal.get("argText").getAsString();
                String[] aliases = new String[0];
                if (cmdVal.has("aliases") && cmdVal.get("aliases").isJsonArray()) {
                    aliases = new String[cmdVal.getAsJsonArray("aliases").size()];
                    for (int i = 0; i < aliases.length; i++)
                        aliases[i] = cmdVal.getAsJsonArray("aliases").get(i).getAsString();
                }
                String[] channelID = (cmdVal.has("channelIDs") && cmdVal.get("channelIDs") instanceof JsonArray) ? makeStringArray(cmdVal.get("channelIDs").getAsJsonArray()) : new String[]{"0"};
                final DiscordCommand regCmd = new CommandFromCFG(cmd.getKey(), desc, mcCommand, admin, aliases, useArgs, argText, channelID);
                if (!discord_instance.registerCommand(regCmd))
                    System.err.println("Failed Registering command \"" + cmd.getKey() + "\" because it would override an existing command!");
            }
            System.out.println("Finished registering! Registered " + discord_instance.getCommandList().size() + " commands");
            if (ModList.get().isLoaded("serverutilities")) {
                final File pdata = new File("./" + ev.getServerSupplier().get().getFolderName() + "/playerdata/" + Configuration.INSTANCE.senderUUID.get() + ".pdat");
                if (pdata.exists()) pdata.delete();
                else System.out.println("Generating playerdata file for comaptibility with ServerUtilities");
                pdata.createNewFile();
                final FileWriter w = new FileWriter(pdata);
                w.write("{\"uuid\":\"" + Configuration.INSTANCE.senderUUID.get() + "\",\"username\":\"DiscordFakeUser\",\"nickname\":\"Discord\",\"homes\":{},\"kitCooldowns\":{},\"perms\":[\"*\"],\"ranks\":[],\"maxHomes\":1,\"hasBeenRtpWarned\":false,\"enableFly\":false,\"isFlying\":false,\"godmode\":false,\"disableMsg\":false,\"firstKit\":false}");
                w.close();
            }
        } catch (Exception e) {
            System.err.println("Failed to login: " + e.getMessage());
            discord_instance = null;
        }
        if (discord_instance != null && !Configuration.INSTANCE.enableWebhook.get())
            if (!Configuration.INSTANCE.msgServerStarting.get().isEmpty())
                this.startingMsg = discord_instance.sendMessageReturns(Configuration.INSTANCE.msgServerStarting.get());
        if (discord_instance != null && Configuration.INSTANCE.botModifyDescription.get())
            (Configuration.INSTANCE.channelDescriptionID.get().isEmpty() ? discord_instance.getChannelManager() : discord_instance.getChannelManager(Configuration.INSTANCE.channelDescriptionID.get())).setTopic(Configuration.INSTANCE.descriptionStarting.get()).complete();
    }

    @SubscribeEvent
    public void serverAboutToStart(final FMLServerAboutToStartEvent ev) {

    }

    @SubscribeEvent
    public void serverStarting(final FMLServerStartingEvent ev) {
        new McCommandDiscord(ev.getCommandDispatcher());
    }

    @SubscribeEvent
    public void serverStarted(final FMLServerStartedEvent ev) {
        LOGGER.info("Started");
        //Detect mixin
        try {
            Class.forName("org.spongepowered.asm.mixin.Mixins");
        } catch (ClassNotFoundException e) {
            LOGGER.error("It looks like mixin is missing. Install MixinBootstrap from https://www.curseforge.com/minecraft/mc-mods/mixinbootstrap/files to get access to all features (whitelist and timeout detection)");
        }
        started = new Date().getTime();
        if (discord_instance != null) if (startingMsg != null) try {
            this.startingMsg.get().editMessage(Configuration.INSTANCE.msgServerStarted.get()).queue();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        else discord_instance.sendMessage(Configuration.INSTANCE.msgServerStarted.get());
        if (discord_instance != null) {
            discord_instance.startThreads();
        }
        /*Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (discord_instance != null) {
                if (!stopped) {
                    if (!discord_instance.isKilled) {
                        discord_instance.stopThreads();
                        if (Configuration.INSTANCE.botModifyDescription.get()) discord_instance.getChannelManager().setTopic(Configuration.INSTANCE.msgServerCrash.get()).complete();
                        discord_instance.sendMessage(Configuration.INSTANCE.msgServerCrash.get());
                    }
                }
                discord_instance.kill();
            }
        }));*/
    }

    @SubscribeEvent
    public void command(CommandEvent ev) {
        String command = ev.getParseResults().getReader().getString();
        if (discord_instance != null) {
            if (((command.startsWith("/say") || command.startsWith("say")) && Configuration.INSTANCE.sayOutput.get()) || ((command.startsWith("/me") || command.startsWith("me")) && Configuration.INSTANCE.meOutput.get())) {
                String msg = command.replace("/say ", "").replace("/me ", "");
                if (command.startsWith("say") || command.startsWith("me"))
                    msg = msg.replaceFirst("say ", "").replaceFirst("me ", "");
                if (command.startsWith("/me") || command.startsWith("me")) msg = "*" + msg.trim() + "*";
                try {
                    discord_instance.sendMessage(ev.getParseResults().getContext().getSource().getName(), ev.getParseResults().getContext().getSource().assertIsEntity().getUniqueID().toString(), msg, Configuration.INSTANCE.chatOutputChannel.get().isEmpty() ? discord_instance.getChannel() : discord_instance.getChannel(Configuration.INSTANCE.chatOutputChannel.get()));
                } catch (CommandSyntaxException e) {
                    discord_instance.sendMessage(msg);
                }
            }
        }
    }

    private String getModNameFromID(String modid) {
        for (ModInfo c : ModList.get().getMods()) {
            if (c.getModId().equals(modid)) return c.getDisplayName();
        }
        return modid;
    }

    @SubscribeEvent
    public void serverStopping(final FMLServerStoppingEvent ev) {
        if (discord_instance != null) {
            discord_instance.stopThreads();
            if (Configuration.INSTANCE.enableWebhook.get()) {
                final WebhookMessageBuilder b = new WebhookMessageBuilder();
                b.setContent(Configuration.INSTANCE.msgServerStopped.get());
                b.setUsername(Configuration.INSTANCE.serverName.get());
                b.setAvatarUrl(Configuration.INSTANCE.serverAvatar.get());
                final WebhookClient cli = WebhookClient.withUrl(discord_instance.getWebhook(Configuration.INSTANCE.serverChannelID.get().isEmpty() ? discord_instance.getChannel() : discord_instance.getChannel(Configuration.INSTANCE.serverChannelID.get())).getUrl());
                cli.send(b.build());
                cli.close();
            } else discord_instance.getChannel().sendMessage(Configuration.INSTANCE.msgServerStopped.get()).queue();
            if (Configuration.INSTANCE.botModifyDescription.get())
                (Configuration.INSTANCE.channelDescriptionID.get().isEmpty() ? discord_instance.getChannelManager() : discord_instance.getChannelManager(Configuration.INSTANCE.channelDescriptionID.get())).setTopic(Configuration.INSTANCE.descriptionOffline.get()).complete();
        }
        stopped = true;
    }

    @SubscribeEvent
    public void serverStopped(final FMLServerStoppedEvent ev) {
        ev.getServer().runImmediately(() -> {
            if (discord_instance != null) {
                if (!stopped) {
                    if (!discord_instance.isKilled) {
                        discord_instance.stopThreads();
                        if (Configuration.INSTANCE.botModifyDescription.get())
                            (Configuration.INSTANCE.channelDescriptionID.get().isEmpty() ? discord_instance.getChannelManager() : discord_instance.getChannelManager(Configuration.INSTANCE.channelDescriptionID.get())).setTopic(Configuration.INSTANCE.msgServerCrash.get()).complete();
                        discord_instance.sendMessage(Configuration.INSTANCE.msgServerCrash.get());
                    }
                }
                discord_instance.kill();
            }
        });

    }

    /* TODO Find out more
    @SubscribeEvent
    public void imc(InterModEnqueueEvent ev) {
        for (InterModComms.IMCMessage e : ev.getMessages()) {
            System.out.println("[IMC-Message] Sender: " + e.getSender() + "Key: " + e.key);
            if (isModIDBlacklisted(e.getSender())) continue;
            if (e.isStringMessage() && (e.key.equals("Discord-Message") || e.key.equals("sendMessage"))) {
                discord_instance.sendMessage(e.getStringValue());
            }
            //Compat with imc from another discord integration mod
            if (e.isNBTMessage() && e.key.equals("sendMessage")) {
                final CompoundNBT msg = e.getNBTValue();
                discord_instance.sendMessage(msg.getString("message"));
            }
        }
    } */
    private boolean isModIDBlacklisted(String sender) {
        return ArrayUtils.contains(Configuration.getArray(Configuration.INSTANCE.imcModIdBlacklist.get()), sender);
    }

    @SubscribeEvent
    public void chat(ServerChatEvent ev) {
        if (discord_instance != null) {
            discord_instance.sendMessage(ev.getPlayer(), ev.getMessage().replace("@everyone", "[at]everyone").replace("@here", "[at]here"));
        }
    }

    /* XX Not Working :/
    @SubscribeEvent
    public void renderName(PlayerEvent.NameFormat ev){
        if (PlayerLinkController.isPlayerLinked(ev.getPlayer().getUniqueID()) && PlayerLinkController.getSettings(null, ev.getPlayer().getUniqueID()).useDiscordNameIngame) {
            ev.setDisplayname(PlayerLinkController.getDiscordFromPlayer(ev.getPlayer().getUniqueID()));
        }else{
            ev.setDisplayname(ev.getUsername());
        }
    }*/
    @SubscribeEvent
    public void death(LivingDeathEvent ev) {
        if (ev.getEntity() instanceof PlayerEntity || (ev.getEntity() instanceof TameableEntity && ((TameableEntity) ev.getEntity()).getOwner() instanceof PlayerEntity && Configuration.INSTANCE.tamedDeathEnabled.get())) {
            if (discord_instance != null)
                discord_instance.sendMessage(Configuration.INSTANCE.msgPlayerDeath.get().replace("%player%", formatPlayerName(ev.getEntity())).replace("%msg%", ev.getSource().getDeathMessage(ev.getEntityLiving())
                        .getUnformattedComponentText().replace(
                                ev.getEntity().getName().getUnformattedComponentText() + " ", "")), Configuration.INSTANCE.deathChannelID.get().isEmpty() ? discord_instance.getChannel() : discord_instance.getChannel(Configuration.INSTANCE.deathChannelID.get()));
        }
    }

    @SubscribeEvent
    public void playerLeave(PlayerEvent.PlayerLoggedOutEvent ev) {
        if (discord_instance != null && !timeouts.contains(ev.getPlayer().getUniqueID()))
            discord_instance.sendMessage(Configuration.INSTANCE.msgPlayerLeave.get().replace("%player%", formatPlayerName(ev.getPlayer())));
        else if (discord_instance != null && timeouts.contains(ev.getPlayer().getUniqueID())) {
            discord_instance.sendMessage(Configuration.INSTANCE.msgPlayerTimeout.get().replace("%player%", formatPlayerName(ev.getPlayer())));
            timeouts.remove(ev.getPlayer().getUniqueID());
        }
        /*if (Loader.isModLoaded("votifier")) {
            MinecraftForge.EVENT_BUS.register(new VotifierEventHandler());
        }*/
    }
}
