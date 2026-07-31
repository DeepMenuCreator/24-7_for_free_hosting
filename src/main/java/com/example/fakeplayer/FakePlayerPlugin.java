package com.example.fakeplayer;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class FakePlayerPlugin extends JavaPlugin implements Listener {

    private static final String FAKE_NAME = "Server24_7";
    private static final UUID FAKE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private ServerPlayer fakePlayer;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        World serverWorld = Bukkit.getWorld("Server");
        if (serverWorld == null) {
            getLogger().info("Creating world 'Server'...");
            serverWorld = new WorldCreator("Server")
                    .environment(World.Environment.NORMAL)
                    .generateStructures(false)
                    .createWorld();
        }

        final World finalWorld = serverWorld;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                createFakePlayer(finalWorld);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to create fake player", e);
            }
        }, 40L);

        new BukkitRunnable() {
            @Override
            public void run() {
                ensureFakePlayer();
            }
        }.runTaskTimer(this, 200L, 1200L);
    }

    private void createFakePlayer(World world) throws Exception {
        CraftServer craftServer = (CraftServer) Bukkit.getServer();
        MinecraftServer minecraftServer = craftServer.getServer();
        ServerLevel serverLevel = ((CraftWorld) world).getHandle();

        GameProfile profile = new GameProfile(FAKE_UUID, FAKE_NAME);
        ClientInformation clientInfo = ClientInformation.createDefault();

        fakePlayer = new ServerPlayer(minecraftServer, serverLevel, profile, clientInfo);
        fakePlayer.setPos(0.5, 128, 0.5);

        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        ServerGamePacketListenerImpl packetListener = new ServerGamePacketListenerImpl(
                minecraftServer,
                connection,
                fakePlayer,
                CommonListenerCookie.createInitial(profile, true)
        );
        fakePlayer.connection = packetListener;

        fakePlayer.setInvulnerable(true);
        fakePlayer.getAbilities().flying = true;
        fakePlayer.getAbilities().invulnerable = true;
        fakePlayer.onUpdateAbilities();
        fakePlayer.getFoodData().setFoodLevel(20);
        fakePlayer.getFoodData().setSaturation(20.0F);

        // setGameModeForPlayer — protected, через рефлексию
        Method setGameMode = fakePlayer.gameMode.getClass().getDeclaredMethod(
                "setGameModeForPlayer", GameType.class, GameType.class);
        setGameMode.setAccessible(true);
        setGameMode.invoke(fakePlayer.gameMode, GameType.CREATIVE, null);

        var playerList = minecraftServer.getPlayerList();

        Field playersField = playerList.getClass().getDeclaredField("players");
        playersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ServerPlayer> players = (List<ServerPlayer>) playersField.get(playerList);
        players.add(fakePlayer);

        Field byNameField = playerList.getClass().getDeclaredField("playersByName");
        byNameField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ServerPlayer> byName = (Map<String, ServerPlayer>) byNameField.get(playerList);
        byName.put(profile.getName(), fakePlayer);

        Field byUUIDField = playerList.getClass().getDeclaredField("playersByUUID");
        byUUIDField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, ServerPlayer> byUUID = (Map<UUID, ServerPlayer>) byUUIDField.get(playerList);
        byUUID.put(profile.getId(), fakePlayer);

        serverLevel.addNewPlayer(fakePlayer);

        CraftPlayer craftFake = (CraftPlayer) fakePlayer.getBukkitEntity();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(FAKE_UUID)) {
                p.hidePlayer(this, craftFake);
            }
        }

        getLogger().info("Fake player '" + FAKE_NAME + "' is now online in world 'Server'!");
    }

    private void ensureFakePlayer() {
        try {
            Player fake = Bukkit.getPlayer(FAKE_UUID);
            if (fakePlayer == null || fakePlayer.isRemoved() || fake == null || !fake.isOnline()) {
                getLogger().warning("Fake player lost! Recreating...");
                World world = Bukkit.getWorld("Server");
                if (world != null) {
                    createFakePlayer(world);
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to ensure fake player", e);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (fakePlayer != null) {
            Player fake = Bukkit.getPlayer(FAKE_UUID);
            if (fake != null && fake.isOnline()) {
                event.getPlayer().hidePlayer(this, fake);
            }
        }
    }

    @Override
    public void onDisable() {
        if (fakePlayer != null && fakePlayer.connection != null) {
            try {
                fakePlayer.connection.disconnect(Component.literal("Server shutdown"));
            } catch (Exception ignored) {
            }
        }
    }
}
