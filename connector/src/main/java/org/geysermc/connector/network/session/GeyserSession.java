/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.network.session;

import com.github.steveice10.mc.auth.data.GameProfile;
import com.github.steveice10.mc.auth.exception.request.AuthPendingException;
import com.github.steveice10.mc.auth.exception.request.InvalidCredentialsException;
import com.github.steveice10.mc.auth.exception.request.RequestException;
import com.github.steveice10.mc.auth.service.AuthenticationService;
import com.github.steveice10.mc.auth.service.MojangAuthenticationService;
import com.github.steveice10.mc.auth.service.MsaAuthenticationService;
import com.github.steveice10.mc.protocol.MinecraftConstants;
import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.data.SubProtocol;
import com.github.steveice10.mc.protocol.data.game.entity.metadata.Pose;
import com.github.steveice10.mc.protocol.data.game.entity.player.GameMode;
import com.github.steveice10.mc.protocol.data.game.statistic.Statistic;
import com.github.steveice10.mc.protocol.data.game.window.VillagerTrade;
import com.github.steveice10.mc.protocol.packet.handshake.client.HandshakePacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.player.ClientPlayerPositionPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.player.ClientPlayerPositionRotationPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientPluginMessagePacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.world.ClientTeleportConfirmPacket;
import com.github.steveice10.mc.protocol.packet.login.server.LoginSuccessPacket;
import com.github.steveice10.packetlib.BuiltinFlags;
import com.github.steveice10.packetlib.Client;
import com.github.steveice10.packetlib.event.session.*;
import com.github.steveice10.packetlib.packet.Packet;
import com.github.steveice10.packetlib.tcp.TcpSessionFactory;
import com.nukkitx.math.GenericMath;
import com.nukkitx.math.vector.*;
import com.nukkitx.protocol.bedrock.BedrockPacket;
import com.nukkitx.protocol.bedrock.BedrockServerSession;
import com.nukkitx.protocol.bedrock.data.*;
import com.nukkitx.protocol.bedrock.data.command.CommandPermission;
import com.nukkitx.protocol.bedrock.data.entity.EntityFlag;
import com.nukkitx.protocol.bedrock.packet.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.geysermc.common.window.CustomFormWindow;
import org.geysermc.common.window.FormWindow;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.entity.Tickable;
import org.geysermc.connector.command.CommandSender;
import org.geysermc.connector.common.AuthType;
import org.geysermc.connector.entity.Entity;
import org.geysermc.connector.entity.attribute.Attribute;
import org.geysermc.connector.entity.attribute.AttributeType;
import org.geysermc.connector.entity.player.SessionPlayerEntity;
import org.geysermc.connector.entity.player.SkullPlayerEntity;
import org.geysermc.connector.event.EventManager;
import org.geysermc.connector.event.EventResult;
import org.geysermc.connector.event.events.geyser.GeyserLoginEvent;
import org.geysermc.connector.event.events.network.SessionConnectEvent;
import org.geysermc.connector.event.events.network.SessionDisconnectEvent;
import org.geysermc.connector.event.events.packet.DownstreamPacketReceiveEvent;
import org.geysermc.connector.event.events.packet.DownstreamPacketSendEvent;
import org.geysermc.connector.event.events.packet.UpstreamPacketSendEvent;
import org.geysermc.connector.inventory.PlayerInventory;
import org.geysermc.connector.network.remote.RemoteServer;
import org.geysermc.connector.network.session.auth.AuthData;
import org.geysermc.connector.network.session.auth.BedrockClientData;
import org.geysermc.connector.network.session.cache.*;
import org.geysermc.connector.network.translators.BiomeTranslator;
import org.geysermc.connector.network.translators.EntityIdentifierRegistry;
import org.geysermc.connector.network.translators.PacketTranslatorRegistry;
import org.geysermc.connector.network.translators.chat.MessageTranslator;
import org.geysermc.connector.network.translators.collision.CollisionManager;
import org.geysermc.connector.network.translators.inventory.EnchantmentInventoryTranslator;
import org.geysermc.connector.network.translators.item.ItemRegistry;
import org.geysermc.connector.skin.SkinManager;
import org.geysermc.connector.utils.*;
import org.geysermc.floodgate.util.BedrockData;
import org.geysermc.floodgate.util.EncryptionUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;
import java.util.Map;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
public class GeyserSession implements CommandSender {

    private final GeyserConnector connector;
    private final UpstreamSession upstream;
    private RemoteServer remoteServer;
    private Client downstream;
    @Setter
    private AuthData authData;
    @Setter
    private BedrockClientData clientData;

    @Deprecated
    @Setter
    private boolean microsoftAccount;

    private final SessionPlayerEntity playerEntity;
    private PlayerInventory inventory;

    private AdvancementsCache advancementsCache;
    private BookEditCache bookEditCache;
    private ChunkCache chunkCache;
    private EntityCache entityCache;
    private EntityEffectCache effectCache;
    private InventoryCache inventoryCache;
    private final PistonCache pistonCache;
    private WorldCache worldCache;
    private WindowCache windowCache;
    private final Int2ObjectMap<TeleportCache> teleportMap = new Int2ObjectOpenHashMap<>();

    /**
     * Stores session collision
     */
    private final CollisionManager collisionManager;

    private final Map<Vector3i, SkullPlayerEntity> skullCache = new ConcurrentHashMap<>();
    private final Long2ObjectMap<ClientboundMapItemDataPacket> storedMaps = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    /**
     * A map of Vector3i positions to Java entity IDs.
     * Used for translating Bedrock block actions to Java entity actions.
     */
    private final Object2LongMap<Vector3i> itemFrameCache = new Object2LongOpenHashMap<>();


    @Setter
    private Vector2i lastChunkPosition = null;
    private int renderDistance;

    private boolean loggedIn;
    private boolean loggingIn;

    @Setter
    private boolean spawned;
    private boolean closed;

    @Setter
    private GameMode gameMode = GameMode.SURVIVAL;

    /**
     * Keeps track of the world name for respawning.
     */
    @Setter
    private String worldName = null;

    private boolean sneaking;

    /**
     * Stores the pose that the server believes the player currently has.
     */
    @Setter
    private Pose pose = Pose.STANDING;

    @Setter
    private boolean sprinting;

    @Setter
    private boolean jumping;

    /**
     * Whether the player is swimming in water.
     * Used to update speed when crawling.
     */
    @Setter
    private boolean swimmingInWater;

    /**
     * Tracks the original speed attribute.
     *
     * We need to do this in order to emulate speeds when sneaking under 1.5-blocks-tall areas if the player isn't sneaking,
     * and when crawling.
     */
    @Setter
    private float originalSpeedAttribute;

    /**
     * The dimension of the player.
     * As all entities are in the same world, this can be safely applied to all other entities.
     */
    @Setter
    private String dimension = DimensionUtils.OVERWORLD;

    @Setter
    private int breakingBlock;

    @Setter
    private Vector3i lastBlockPlacePosition;

    @Setter
    private String lastBlockPlacedId;

    @Setter
    private boolean interacting;

    /**
     * Stores the last position of the block the player interacted with. This can either be a block that the client
     * placed or an existing block the player interacted with (for example, a chest). <br>
     * Initialized as (0, 0, 0) so it is always not-null.
     */
    @Setter
    private Vector3i lastInteractionPosition = Vector3i.ZERO;

    @Setter
    private Entity ridingVehicleEntity;

    @Setter
    private int craftSlot = 0;

    @Setter
    private long lastWindowCloseTime = 0;

    @Setter
    private VillagerTrade[] villagerTrades;
    @Setter
    private long lastInteractedVillagerEid;

    /**
     * Stores the enchantment information the client has received if they are in an enchantment table GUI
     */
    private final EnchantmentInventoryTranslator.EnchantmentSlotData[] enchantmentSlotData = new EnchantmentInventoryTranslator.EnchantmentSlotData[3];

    /**
     * The current attack speed of the player. Used for sending proper cooldown timings.
     * Setting a default fixes cooldowns not showing up on a fresh world.
     */
    @Setter
    private double attackSpeed = 4.0d;
    /**
     * The time of the last hit. Used to gauge how long the cooldown is taking.
     * This is a session variable in order to prevent more scheduled threads than necessary.
     */
    @Setter
    private long lastHitTime;

    /**
     * Saves if the client is steering left on a boat.
     */
    @Setter
    private boolean steeringLeft;
    /**
     * Saves if the client is steering right on a boat.
     */
    @Setter
    private boolean steeringRight;

    /**
     * Store the last time the player interacted. Used to fix a right-click spam bug.
     * See https://github.com/GeyserMC/Geyser/issues/503 for context.
     */
    @Setter
    private long lastInteractionTime;

    /**
     * Stores a future interaction to place a bucket. Will be cancelled if the client instead intended to
     * interact with a block.
     */
    @Setter
    private ScheduledFuture<?> bucketScheduledFuture;

    /**
     * Used to send a movement packet every three seconds if the player hasn't moved. Prevents timeouts when AFK in certain instances.
     */
    @Setter
    private long lastMovementTimestamp = System.currentTimeMillis();

    /**
     * Controls whether the daylight cycle gamerule has been sent to the client, so the sun/moon remain motionless.
     */
    private boolean daylightCycle = true;

    private boolean reducedDebugInfo = false;

    @Setter
    private CustomFormWindow settingsForm;

    /**
     * The op permission level set by the server
     */
    @Setter
    private int opPermissionLevel = 0;

    /**
     * If the current player can fly
     */
    @Setter
    private boolean canFly = false;

    /**
     * If the current player is flying
     */
    @Setter
    private boolean flying = false;

    /**
     * If the current player is in noclip
     */
    @Setter
    private boolean noClip = false;

    /**
     * If the current player can not interact with the world
     */
    @Setter
    private boolean worldImmutable = false;

    /**
     * Caches current rain status.
     */
    @Setter
    private boolean raining = false;

    /**
     * Caches current thunder status.
     */
    @Setter
    private boolean thunder = false;

    /**
     * Stores the last text inputted into a sign.
     *
     * Bedrock sends packets every time you update the sign, Java only wants the final packet.
     * Until we determine that the user has finished editing, we save the sign's current status.
     */
    @Setter
    private String lastSignMessage;

    /**
     * Stores a map of all statistics sent from the server.
     * The server only sends new statistics back to us, so in order to show all statistics we need to cache existing ones.
     */
    private final Map<Statistic, Integer> statistics = new HashMap<>();

    /**
     * Whether we're expecting statistics to be sent back to us.
     */
    @Setter
    private boolean waitingForStatistics = false;

    @Setter
    private List<UUID> selectedEmotes = new ArrayList<>();
    private final Set<UUID> emotes = new HashSet<>();

    /**
     * The thread that will run every 50 milliseconds - one Minecraft tick.
     */
    private ScheduledFuture<?> tickThread = null;

    private MinecraftProtocol protocol;

    public GeyserSession(GeyserConnector connector, BedrockServerSession bedrockServerSession) {
        this.connector = connector;
        this.upstream = new UpstreamSession(bedrockServerSession);

        this.advancementsCache = new AdvancementsCache(this);
        this.bookEditCache = new BookEditCache(this);
        this.chunkCache = new ChunkCache(this);
        this.entityCache = new EntityCache(this);
        this.effectCache = new EntityEffectCache();
        this.inventoryCache = new InventoryCache(this);
        this.pistonCache = new PistonCache(this);
        this.worldCache = new WorldCache(this);
        this.windowCache = new WindowCache(this);

        this.collisionManager = new CollisionManager(this);

        this.playerEntity = new SessionPlayerEntity(this);
        collisionManager.updatePlayerBoundingBox(this.playerEntity.getPosition());
        this.inventory = new PlayerInventory();

        this.spawned = false;
        this.loggedIn = false;

        this.inventoryCache.getInventories().put(0, inventory);

        EventManager.getInstance().triggerEvent(new SessionConnectEvent(this, "Disconnected by Server")) // TODO: @translate
                .onCancelled(result -> disconnect(result.getEvent().getMessage()));

        connector.getPlayers().forEach(player -> this.emotes.addAll(player.getEmotes()));

        bedrockServerSession.addDisconnectHandler(disconnectReason -> {
            EventManager.getInstance().triggerEvent(new SessionDisconnectEvent(this, disconnectReason));

            connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.network.disconnect", bedrockServerSession.getAddress().getAddress(), disconnectReason));

            disconnect(disconnectReason.name());
            connector.removePlayer(this);
        });
    }

    public void connect(RemoteServer remoteServer) {
        this.remoteServer = remoteServer;

        PlayerListPacket playerListPacket = new PlayerListPacket();
        playerListPacket.setAction(PlayerListPacket.Action.ADD);
        playerListPacket.getEntries().add(SkinManager.buildCachedEntry(this, playerEntity));
        sendUpstreamPacket(playerListPacket);

        startGame();

        // Set the hardcoded shield ID to the ID we just defined in StartGamePacket
        upstream.getSession().getHardcodedBlockingId().set(ItemRegistry.SHIELD.getBedrockId());

        ChunkUtils.sendEmptyChunks(this, playerEntity.getPosition().toInt(), 0, false);

        BiomeDefinitionListPacket biomeDefinitionListPacket = new BiomeDefinitionListPacket();
        biomeDefinitionListPacket.setDefinitions(BiomeTranslator.BIOMES);
        sendUpstreamPacket(biomeDefinitionListPacket);

        AvailableEntityIdentifiersPacket entityPacket = new AvailableEntityIdentifiersPacket();
        entityPacket.setIdentifiers(EntityIdentifierRegistry.ENTITY_IDENTIFIERS);
        sendUpstreamPacket(entityPacket);

        CreativeContentPacket creativePacket = new CreativeContentPacket();
        creativePacket.setContents(ItemRegistry.CREATIVE_ITEMS);
        sendUpstreamPacket(creativePacket);

        UpdateAttributesPacket attributesPacket = new UpdateAttributesPacket();
        attributesPacket.setRuntimeEntityId(getPlayerEntity().getGeyserId());
        List<AttributeData> attributes = new ArrayList<>();
        // Default move speed
        // Bedrock clients move very fast by default until they get an attribute packet correcting the speed
        attributes.add(new AttributeData("minecraft:movement", 0.0f, 1024f, 0.1f, 0.1f));
        attributesPacket.setAttributes(attributes);
        sendUpstreamPacket(attributesPacket);

        GameRulesChangedPacket gamerulePacket = new GameRulesChangedPacket();
        // Only allow the server to send health information
        // Setting this to false allows natural regeneration to work false but doesn't break it being true
        gamerulePacket.getGameRules().add(new GameRuleData<>("naturalregeneration", false));
        // Don't let the client modify the inventory on death
        // Setting this to true allows keep inventory to work if enabled but doesn't break functionality being false
        gamerulePacket.getGameRules().add(new GameRuleData<>("keepinventory", true));
        // Ensure client doesn't try and do anything funky; the server handles this for us
        gamerulePacket.getGameRules().add(new GameRuleData<>("spawnradius", 0));
        sendUpstreamPacket(gamerulePacket);

        // Spawn the player
        PlayStatusPacket playStatusPacket = new PlayStatusPacket();
        playStatusPacket.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
        sendUpstreamPacket(playStatusPacket);
    }

    public void login() {
        if (EventManager.getInstance().triggerEvent(new GeyserLoginEvent(this)).isCancelled()) {
            return;
        }

        if (connector.getAuthType() != AuthType.ONLINE) {
            if (connector.getAuthType() == AuthType.OFFLINE) {
                connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.auth.login.offline"));
            } else {
                connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.auth.login.floodgate"));
            }
            authenticate(authData.getName());
        }
    }

    public void authenticate(String username) {
        authenticate(username, "");
    }

    public void authenticate(String username, String password) {
        if (loggedIn) {
            connector.getLogger().severe(LanguageUtils.getLocaleStringLog("geyser.auth.already_loggedin", username));
            return;
        }

        loggingIn = true;
        // new thread so clients don't timeout
        new Thread(() -> {
            try {
                if (password != null && !password.isEmpty()) {
                    AuthenticationService authenticationService;
                    if (microsoftAccount) {
                        authenticationService = new MsaAuthenticationService(GeyserConnector.OAUTH_CLIENT_ID);
                    } else {
                        authenticationService = new MojangAuthenticationService();
                    }
                    authenticationService.setUsername(username);
                    authenticationService.setPassword(password);
                    authenticationService.login();

                    protocol = new MinecraftProtocol(authenticationService);
                } else {
                    protocol = new MinecraftProtocol(username.replace(" ","_"));
                }

                connectDownstream();
            } catch (InvalidCredentialsException | IllegalArgumentException e) {
                connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.auth.login.invalid", username));
                disconnect(LanguageUtils.getPlayerLocaleString("geyser.auth.login.invalid.kick", getClientData().getLanguageCode()));
            } catch (RequestException ex) {
                ex.printStackTrace();
            }
        }).start();
    }

    /**
     * Present a form window to the user asking to log in with another web browser
     */
    public void authenticateWithMicrosoftCode() {
        if (loggedIn) {
            connector.getLogger().severe(LanguageUtils.getLocaleStringLog("geyser.auth.already_loggedin", getAuthData().getName()));
            return;
        }

        loggingIn = true;
        // new thread so clients don't timeout
        new Thread(() -> {
            try {
                MsaAuthenticationService msaAuthenticationService = new MsaAuthenticationService(GeyserConnector.OAUTH_CLIENT_ID);

                MsaAuthenticationService.MsCodeResponse response = msaAuthenticationService.getAuthCode();
                LoginEncryptionUtils.showMicrosoftCodeWindow(this, response);

                // This just looks cool
                SetTimePacket packet = new SetTimePacket();
                packet.setTime(16000);
                sendUpstreamPacket(packet);

                // Wait for the code to validate
                attemptCodeAuthentication(msaAuthenticationService);
            } catch (InvalidCredentialsException | IllegalArgumentException e) {
                connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.auth.login.invalid", getAuthData().getName()));
                disconnect(LanguageUtils.getPlayerLocaleString("geyser.auth.login.invalid.kick", getClientData().getLanguageCode()));
            } catch (RequestException ex) {
                ex.printStackTrace();
            }
        }).start();
    }

    /**
     * Poll every second to see if the user has successfully signed in
     */
    private void attemptCodeAuthentication(MsaAuthenticationService msaAuthenticationService) {
        if (loggedIn || closed) {
            return;
        }
        try {
            msaAuthenticationService.login();
            protocol = new MinecraftProtocol(msaAuthenticationService);

            connectDownstream();
        } catch (RequestException e) {
            if (!(e instanceof AuthPendingException)) {
                e.printStackTrace();
            } else {
                // Wait one second before trying again
                connector.getGeneralThreadPool().schedule(() -> attemptCodeAuthentication(msaAuthenticationService), 1, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * After getting whatever credentials needed, we attempt to join the Java server.
     */
    private void connectDownstream() {
        boolean floodgate = connector.getAuthType() == AuthType.FLOODGATE;
        final PublicKey publicKey;

        if (floodgate) {
            PublicKey key = null;
            try {
                key = EncryptionUtil.getKeyFromFile(
                        connector.getConfig().getFloodgateKeyPath(),
                        PublicKey.class
                );
            } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException e) {
                connector.getLogger().error(LanguageUtils.getLocaleStringLog("geyser.auth.floodgate.bad_key"), e);
            }
            publicKey = key;
        } else publicKey = null;

        if (publicKey != null) {
            connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.auth.floodgate.loaded_key"));
        }

        // Start ticking
        tickThread = connector.getGeneralThreadPool().scheduleAtFixedRate(this::tick, 50, 50, TimeUnit.MILLISECONDS);

        downstream = new Client(remoteServer.getAddress(), remoteServer.getPort(), protocol, new TcpSessionFactory());
        if (connector.getConfig().getRemote().isUseProxyProtocol()) {
            downstream.getSession().setFlag(BuiltinFlags.ENABLE_CLIENT_PROXY_PROTOCOL, true);
            downstream.getSession().setFlag(BuiltinFlags.CLIENT_PROXIED_ADDRESS, upstream.getAddress());
        }
        // Let Geyser handle sending the keep alive
        downstream.getSession().setFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, false);
        downstream.getSession().addListener(new SessionAdapter() {
            @Override
            public void packetSending(PacketSendingEvent event) {
                //todo move this somewhere else
                if (event.getPacket() instanceof HandshakePacket && floodgate) {
                    String encrypted = "";
                    try {
                        encrypted = EncryptionUtil.encryptBedrockData(publicKey, new BedrockData(
                                clientData.getGameVersion(),
                                authData.getName(),
                                authData.getXboxUUID(),
                                clientData.getDeviceOS().ordinal(),
                                clientData.getLanguageCode(),
                                clientData.getCurrentInputMode().ordinal(),
                                upstream.getSession().getAddress().getAddress().getHostAddress()
                        ));
                    } catch (Exception e) {
                        connector.getLogger().error(LanguageUtils.getLocaleStringLog("geyser.auth.floodgate.encrypt_fail"), e);
                    }

                    HandshakePacket handshakePacket = event.getPacket();
                    event.setPacket(new HandshakePacket(
                            handshakePacket.getProtocolVersion(),
                            handshakePacket.getHostname() + '\0' + BedrockData.FLOODGATE_IDENTIFIER + '\0' + encrypted,
                            handshakePacket.getPort(),
                            handshakePacket.getIntent()
                    ));
                }
            }

            @Override
            public void connected(ConnectedEvent event) {
                loggingIn = false;
                loggedIn = true;
                if (protocol.getProfile() == null) {
                    // Java account is offline
                    disconnect(LanguageUtils.getPlayerLocaleString("geyser.network.remote.invalid_account", clientData.getLanguageCode()));
                    return;
                }
                connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.network.remote.connect", authData.getName(), protocol.getProfile().getName(), remoteServer.getAddress()));
                playerEntity.setUuid(protocol.getProfile().getId());
                playerEntity.setUsername(protocol.getProfile().getName());

                String locale = clientData.getLanguageCode();

                // Let the user know there locale may take some time to download
                // as it has to be extracted from a JAR
                if (locale.toLowerCase().equals("en_us") && !LocaleUtils.LOCALE_MAPPINGS.containsKey("en_us")) {
                    // This should probably be left hardcoded as it will only show for en_us clients
                    sendMessage("Loading your locale (en_us); if this isn't already downloaded, this may take some time");
                }

                // Download and load the language for the player
                LocaleUtils.downloadAndLoadLocale(locale);

                // Register plugin channels
                    connector.getGeneralThreadPool().schedule(() -> {
                        for (String channel : getConnector().getRegisteredPluginChannels()) {
                        registerPluginChannel(channel);
                        }
                    }, 1, TimeUnit.SECONDS);
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                loggingIn = false;
                loggedIn = false;
                connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.network.remote.disconnect", authData.getName(), remoteServer.getAddress(), event.getReason()));
                if (event.getCause() != null) {
                    event.getCause().printStackTrace();
                }

                upstream.disconnect(MessageTranslator.convertMessageLenient(event.getReason()));
            }

            @Override
            public void packetReceived(PacketReceivedEvent event) {
                if (!closed) {
                handleDownstreamPacket(event.getPacket());
                }
            }
                PacketTranslatorRegistry.JAVA_TRANSLATOR.translate(event.getPacket().getClass(), event.getPacket(), GeyserSession.this);
                }
            }

            @Override
            public void packetError(PacketErrorEvent event) {
                connector.getLogger().warning(LanguageUtils.getLocaleStringLog("geyser.network.downstream_error", event.getCause().getMessage()));
                if (connector.getConfig().isDebugMode())
                    event.getCause().printStackTrace();
                event.setSuppress(true);
            }
        });

        if (!daylightCycle) {
            setDaylightCycle(true);
        }
        downstream.getSession().connect();
        connector.addPlayer(this);
    }

    public void handleDownstreamPacket(Packet packet) {
	// Required, or else Floodgate players break with Bukkit chunk caching
        if (packet instanceof LoginSuccessPacket) {
            GameProfile profile = ((LoginSuccessPacket) packet).getProfile();
            playerEntity.setUsername(profile.getName());
            playerEntity.setUuid(profile.getId());

            // Check if they are not using a linked account
            if (connector.getAuthType() == AuthType.OFFLINE || playerEntity.getUuid().getMostSignificantBits() == 0) {
                SkinManager.handleBedrockSkin(playerEntity, clientData);
            }
        }

        EventResult<DownstreamPacketReceiveEvent<?>> result = EventManager.getInstance().triggerEvent(DownstreamPacketReceiveEvent.of(this, packet));
        if (!result.isCancelled()) {
            PacketTranslatorRegistry.JAVA_TRANSLATOR.translate(result.getEvent().getPacket().getClass(), result.getEvent().getPacket(), this);
        }	
    }

    public void disconnect(String reason) {
        if (!closed) {
            loggedIn = false;
            if (downstream != null && downstream.getSession() != null) {
                downstream.getSession().disconnect(reason);
            }
            if (upstream != null && !upstream.isClosed()) {
                connector.getPlayers().remove(this);
                upstream.disconnect(reason);
            }
        }

        if (tickThread != null) {
            tickThread.cancel(true);
        }

        this.advancementsCache = null;
        this.bookEditCache = null;
        this.chunkCache = null;
        this.entityCache = null;
        this.effectCache = null;
        this.worldCache = null;
        this.inventoryCache = null;
        this.windowCache = null;

        closed = true;
    }

    public void close() {
        disconnect(LanguageUtils.getPlayerLocaleString("geyser.network.close", getClientData().getLanguageCode()));
    }

    /**
     * Called every 50 milliseconds - one Minecraft tick.
     */
    public void tick() {
        pistonCache.tick(this);
        // Check to see if the player's position needs updating - a position update should be sent once every 3 seconds
        if (spawned && (System.currentTimeMillis() - lastMovementTimestamp) > 3000) {
            // Recalculate in case something else changed position
            Vector3d position = collisionManager.adjustBedrockPosition(playerEntity.getPosition(), playerEntity.isOnGround());
            // A null return value cancels the packet
            if (position != null) {
                ClientPlayerPositionPacket packet = new ClientPlayerPositionPacket(playerEntity.isOnGround(),
                        position.getX(), position.getY(), position.getZ());
                sendDownstreamPacket(packet);
            }
            lastMovementTimestamp = System.currentTimeMillis();
        }

        for (Tickable entity : entityCache.getTickableEntities()) {
            entity.tick(this);
        }
    }

    public void setAuthenticationData(AuthData authData) {
        this.authData = authData;
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
        collisionManager.updatePlayerBoundingBox();
        collisionManager.updateScaffoldingFlags();
    }

    /**
     * Adjusts speed if the player should be sneaking, or if the player is crawling.
     *
     * @return true if attributes should be updated.
     */
    public boolean adjustSpeed() {
        Attribute currentPlayerSpeed = playerEntity.getAttributes().get(AttributeType.MOVEMENT_SPEED);
        if (currentPlayerSpeed != null) {
            if ((pose.equals(Pose.SNEAKING) && !sneaking && collisionManager.isUnderSlab()) ||
                    (!swimmingInWater && playerEntity.getMetadata().getFlags().getFlag(EntityFlag.SWIMMING) && !collisionManager.isPlayerInWater())) {
                // Either of those conditions means that Bedrock goes zoom when they shouldn't be
                currentPlayerSpeed.setValue(originalSpeedAttribute / 3.32f);
                return true;
            } else if (originalSpeedAttribute != currentPlayerSpeed.getValue()) {
                // Speed has reset to normal
                currentPlayerSpeed.setValue(originalSpeedAttribute);
                return true;
            }
        }
        return false;
    }

    @Override
    public String getName() {
        return authData.getName();
    }

    @Override
    public void sendMessage(String message) {
        TextPacket textPacket = new TextPacket();
        textPacket.setPlatformChatId("");
        textPacket.setSourceName("");
        textPacket.setXuid("");
        textPacket.setType(TextPacket.Type.CHAT);
        textPacket.setNeedsTranslation(false);
        textPacket.setMessage(message);

        sendUpstreamPacket(textPacket);
    }

    @Override
    public boolean isConsole() {
        return false;
    }

     @Override
     public String getLocale() {
        return clientData.getLanguageCode();
     }

    public void sendForm(FormWindow window, int id) {
        windowCache.showWindow(window, id);
    }

    public void setRenderDistance(int renderDistance) {
        renderDistance = GenericMath.ceil(++renderDistance * MathUtils.SQRT_OF_TWO); //square to circle
        this.renderDistance = renderDistance;

        ChunkRadiusUpdatedPacket chunkRadiusUpdatedPacket = new ChunkRadiusUpdatedPacket();
        chunkRadiusUpdatedPacket.setRadius(renderDistance);
        sendUpstreamPacket(chunkRadiusUpdatedPacket);
    }

    public InetSocketAddress getSocketAddress() {
        return this.upstream.getAddress();
    }

    public void sendForm(FormWindow window) {
        windowCache.showWindow(window);
    }

    private void startGame() {
        StartGamePacket startGamePacket = new StartGamePacket();
        startGamePacket.setUniqueEntityId(playerEntity.getGeyserId());
        startGamePacket.setRuntimeEntityId(playerEntity.getGeyserId());
        startGamePacket.setPlayerGameType(GameType.SURVIVAL);
        startGamePacket.setPlayerPosition(Vector3f.from(0, 69, 0));
        startGamePacket.setRotation(Vector2f.from(1, 1));

        startGamePacket.setSeed(-1);
        startGamePacket.setDimensionId(DimensionUtils.javaToBedrock(dimension));
        startGamePacket.setGeneratorId(1);
        startGamePacket.setLevelGameType(GameType.SURVIVAL);
        startGamePacket.setDifficulty(1);
        startGamePacket.setDefaultSpawn(Vector3i.ZERO);
        startGamePacket.setAchievementsDisabled(!connector.getConfig().isXboxAchievementsEnabled());
        startGamePacket.setCurrentTick(-1);
        startGamePacket.setEduEditionOffers(0);
        startGamePacket.setEduFeaturesEnabled(false);
        startGamePacket.setRainLevel(0);
        startGamePacket.setLightningLevel(0);
        startGamePacket.setMultiplayerGame(true);
        startGamePacket.setBroadcastingToLan(true);
        startGamePacket.getGamerules().add(new GameRuleData<>("showcoordinates", connector.getConfig().isShowCoordinates()));
        startGamePacket.setPlatformBroadcastMode(GamePublishSetting.PUBLIC);
        startGamePacket.setXblBroadcastMode(GamePublishSetting.PUBLIC);
        startGamePacket.setCommandsEnabled(!connector.getConfig().isXboxAchievementsEnabled());
        startGamePacket.setTexturePacksRequired(false);
        startGamePacket.setBonusChestEnabled(false);
        startGamePacket.setStartingWithMap(false);
        startGamePacket.setTrustingPlayers(true);
        startGamePacket.setDefaultPlayerPermission(PlayerPermission.MEMBER);
        startGamePacket.setServerChunkTickRange(4);
        startGamePacket.setBehaviorPackLocked(false);
        startGamePacket.setResourcePackLocked(false);
        startGamePacket.setFromLockedWorldTemplate(false);
        startGamePacket.setUsingMsaGamertagsOnly(false);
        startGamePacket.setFromWorldTemplate(false);
        startGamePacket.setWorldTemplateOptionLocked(false);

        String serverName = connector.getConfig().getBedrock().getServerName();
        startGamePacket.setLevelId(serverName);
        startGamePacket.setLevelName(serverName);

        startGamePacket.setPremiumWorldTemplateId("00000000-0000-0000-0000-000000000000");
        // startGamePacket.setCurrentTick(0);
        startGamePacket.setEnchantmentSeed(0);
        startGamePacket.setMultiplayerCorrelationId("");
        startGamePacket.setItemEntries(ItemRegistry.ITEMS);
        startGamePacket.setVanillaVersion("*");
        startGamePacket.setAuthoritativeMovementMode(AuthoritativeMovementMode.CLIENT);
        sendUpstreamPacket(startGamePacket);
    }

    public void addTeleport(TeleportCache teleportCache) {
        teleportMap.put(teleportCache.getTeleportConfirmId(), teleportCache);

        ObjectIterator<Int2ObjectMap.Entry<TeleportCache>> it = teleportMap.int2ObjectEntrySet().iterator();

        // Remove any teleports with a higher number - maybe this is a world change that reset the ID to 0?
        while (it.hasNext()) {
            Int2ObjectMap.Entry<TeleportCache> entry = it.next();
            int nextID = entry.getValue().getTeleportConfirmId();
            if (nextID > teleportCache.getTeleportConfirmId()) {
                it.remove();
            }
        }
    }

    public boolean confirmTeleport(Vector3d position) {
        if (teleportMap.size() == 0) {
            return true;
        }
        int teleportID = -1;

        for (Int2ObjectMap.Entry<TeleportCache> entry : teleportMap.int2ObjectEntrySet()) {
            if (entry.getValue().canConfirm(position)) {
                if (entry.getValue().getTeleportConfirmId() > teleportID) {
                    teleportID = entry.getValue().getTeleportConfirmId();
                }
            }
        }

        ObjectIterator<Int2ObjectMap.Entry<TeleportCache>> it = teleportMap.int2ObjectEntrySet().iterator();

        if (teleportID != -1) {
            // Confirm the current teleport and any earlier ones
            while (it.hasNext()) {
                TeleportCache entry = it.next().getValue();
                int nextID = entry.getTeleportConfirmId();
                if (nextID <= teleportID) {
                    ClientTeleportConfirmPacket teleportConfirmPacket = new ClientTeleportConfirmPacket(nextID);
                    sendDownstreamPacket(teleportConfirmPacket);
                    // Servers (especially ones like Hypixel) expect exact coordinates given back to them.
                    ClientPlayerPositionRotationPacket positionPacket = new ClientPlayerPositionRotationPacket(playerEntity.isOnGround(),
                            entry.getX(), entry.getY(), entry.getZ(), entry.getYaw(), entry.getPitch());
                    sendDownstreamPacket(positionPacket);
                    it.remove();
                    connector.getLogger().debug("Confirmed teleport " + nextID);
                }
            }
        }

        if (teleportMap.size() > 0) {
            int resendID = -1;
            for (Int2ObjectMap.Entry<TeleportCache> entry : teleportMap.int2ObjectEntrySet()) {
                TeleportCache teleport = entry.getValue();
                teleport.incrementUnconfirmedFor();
                if (teleport.shouldResend()) {
                    if (teleport.getTeleportConfirmId() >= resendID) {
                        resendID = teleport.getTeleportConfirmId();
                    }
                }
            }

            if (resendID != -1) {
                connector.getLogger().debug("Resending teleport " + resendID);
                TeleportCache teleport = teleportMap.get(resendID);
                getPlayerEntity().moveAbsolute(this, Vector3f.from(teleport.getX(), teleport.getY(), teleport.getZ()),
                        teleport.getYaw(), teleport.getPitch(), playerEntity.isOnGround(), true);
            }
        }

        return true;
    }

    /**
     * Queue a packet to be sent to player.
     *
     * @param packet the bedrock packet from the NukkitX protocol lib
     */
    public void sendUpstreamPacket(BedrockPacket packet) {
        EventManager.getInstance().triggerEvent(UpstreamPacketSendEvent.of(this, packet))
                .onNotCancelled(result -> {
                    if (upstream != null) {
                        upstream.sendPacket(result.getEvent().getPacket());
                    } else {
                        connector.getLogger().debug("Tried to send upstream packet " + result.getEvent().getPacket().getClass().getSimpleName() + " but the session was null");
                    }
                });
    }

    /**
     * Inject a packet as if it was received by the upstream client
     * @param packet the bedrock packet to be injected
     * @return true if handled
     */
    @SuppressWarnings("unused")
    public boolean receiveUpstreamPacket(BedrockPacket packet) {
        return packet.handle(getUpstream().getSession().getPacketHandler());
    }

    /**
     * Inject a packet as if it was received by the downstream server
     * @param packet the java packet to be injected
     */
    @SuppressWarnings("unused")
    public void receiveDownstreamPacket(Packet packet) {
        handleDownstreamPacket(packet);
    }

    /**
     * Send a packet immediately to the player.
     *
     * @param packet the bedrock packet from the NukkitX protocol lib
     */
    public void sendUpstreamPacketImmediately(BedrockPacket packet) {
        EventManager.getInstance().triggerEvent(UpstreamPacketSendEvent.of(this, packet))
                .onNotCancelled(result -> {
                    if (upstream != null) {
                        upstream.sendPacketImmediately(result.getEvent().getPacket());
                    } else {
                        connector.getLogger().debug("Tried to send upstream packet " + result.getEvent().getPacket().getClass().getSimpleName() + " immediately but the session was null");
                    }
                });
    }

    /**
     * Send a packet to the remote server.
     *
     * @param packet the java edition packet from MCProtocolLib
     */
    public void sendDownstreamPacket(Packet packet) {
        EventManager.getInstance().triggerEvent(DownstreamPacketSendEvent.of(this, packet))
                .onNotCancelled(result -> {
                    if (downstream != null && downstream.getSession() != null && protocol.getSubProtocol().equals(SubProtocol.GAME)) {
                        downstream.getSession().send(result.getEvent().getPacket());
                    } else {
                        connector.getLogger().debug("Tried to send downstream packet " + result.getEvent().getPacket().getClass().getSimpleName() + " before connected to the server");
                    }
                });
    }

    /**
     * Update the cached value for the reduced debug info gamerule.
     * This also toggles the coordinates display
     *
     * @param value The new value for reducedDebugInfo
     */
    public void setReducedDebugInfo(boolean value) {
        worldCache.setShowCoordinates(!value);
        reducedDebugInfo = value;
    }

    /**
     * Changes the daylight cycle gamerule on the client
     * This is used in the login screen along-side normal usage
     *
     * @param doCycle If the cycle should continue
     */
    public void setDaylightCycle(boolean doCycle) {
        sendGameRule("dodaylightcycle", doCycle);
        // Save the value so we don't have to constantly send a daylight cycle gamerule update
        this.daylightCycle = doCycle;
    }

    /**
     * Send a gamerule value to the client
     *
     * @param gameRule The gamerule to send
     * @param value The value of the gamerule
     */
    public void sendGameRule(String gameRule, Object value) {
        GameRulesChangedPacket gameRulesChangedPacket = new GameRulesChangedPacket();
        gameRulesChangedPacket.getGameRules().add(new GameRuleData<>(gameRule, value));
        upstream.sendPacket(gameRulesChangedPacket);
    }

    /**
     * Checks if the given session's player has a permission
     *
     * @param permission The permission node to check
     * @return true if the player has the requested permission, false if not
     */
    public Boolean hasPermission(String permission) {
        return connector.getWorldManager().hasPermission(this, permission);
    }

    /**
     * Send an AdventureSettingsPacket to the client with the latest flags
     */
    public void sendAdventureSettings() {
        AdventureSettingsPacket adventureSettingsPacket = new AdventureSettingsPacket();
        adventureSettingsPacket.setUniqueEntityId(playerEntity.getGeyserId());
        // Set command permission if OP permission level is high enough
        // This allows mobile players access to a GUI for doing commands. The commands there do not change above OPERATOR
        // and all commands there are accessible with OP permission level 2
        adventureSettingsPacket.setCommandPermission(opPermissionLevel >= 2 ? CommandPermission.OPERATOR : CommandPermission.NORMAL);
        // Required to make command blocks destroyable
        adventureSettingsPacket.setPlayerPermission(opPermissionLevel >= 2 ? PlayerPermission.OPERATOR : PlayerPermission.MEMBER);

        // Update the noClip and worldImmutable values based on the current gamemode
        noClip = gameMode == GameMode.SPECTATOR;
        worldImmutable = gameMode == GameMode.ADVENTURE || gameMode == GameMode.SPECTATOR;

        Set<AdventureSetting> flags = new HashSet<>();
        if (canFly) {
            flags.add(AdventureSetting.MAY_FLY);
        }

        if (flying) {
            flags.add(AdventureSetting.FLYING);
        }

        if (worldImmutable) {
            flags.add(AdventureSetting.WORLD_IMMUTABLE);
        }

        if (noClip) {
            flags.add(AdventureSetting.NO_CLIP);
        }

        flags.add(AdventureSetting.AUTO_JUMP);

        adventureSettingsPacket.getSettings().addAll(flags);
        sendUpstreamPacket(adventureSettingsPacket);
    }

    /**
     * Used for updating statistic values since we only get changes from the server
     *
     * @param statistics Updated statistics values
     */
    public void updateStatistics(@NonNull Map<Statistic, Integer> statistics) {
        this.statistics.putAll(statistics);
    }

    public void refreshEmotes(List<UUID> emotes) {
        this.selectedEmotes = emotes;
        this.emotes.addAll(emotes);
        for (GeyserSession player : connector.getPlayers()) {
            List<UUID> pieces = new ArrayList<>();
            for (UUID piece : emotes) {
                if (!player.getEmotes().contains(piece)) {
                    pieces.add(piece);
                }
                player.getEmotes().add(piece);
            }
            EmoteListPacket emoteList = new EmoteListPacket();
            emoteList.setRuntimeEntityId(player.getPlayerEntity().getGeyserId());
            emoteList.getPieceIds().addAll(pieces);
            player.sendUpstreamPacket(emoteList);
        }
    }

    /**
     * Send message on a Plugin Channel - https://www.spigotmc.org/wiki/bukkit-bungee-plugin-messaging-channel
     *
     * @param channel channel to send on
     * @param data payload
     */
    public void sendPluginMessage(String channel, byte[] data) {
        sendDownstreamPacket(new ClientPluginMessagePacket(channel, data));
    }

    /**
     * Register a Plugin Channel
     *
     * @param channel Channel to register
     */
    public void registerPluginChannel(String channel) {
        sendDownstreamPacket(new ClientPluginMessagePacket("minecraft:register", channel.getBytes()));
    }

    /**
     * Unregister a Plugin Channel
     *
     * @param channel Channel to unregister
     */
    public void unregisterPluginChannel(String channel) {
        sendDownstreamPacket(new ClientPluginMessagePacket("minecraft:unregister", channel.getBytes()));
    }
}
