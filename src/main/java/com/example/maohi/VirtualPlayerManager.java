package com.example.maohi;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 虚拟玩家管理器
 * 负责管理虚拟玩家的生成、存活检测、自动复活等功能
 *
 * 功能特性：
 * - 服务器启动后自动召唤虚拟玩家
 * - 最多维持 3 个虚拟玩家同时在线
 * - 虚拟玩家死亡后自动重新召唤
 * - 玩家名称随机生成，贴近真实玩家风格
 */
public class VirtualPlayerManager {

    private static final int MAX_VIRTUAL_PLAYERS = 3;
    private static final int RESPAWN_DELAY_TICKS = 100; // 5秒 (20 ticks/秒)

    // 虚拟玩家名称前缀和后缀词库，用于生成自然的玩家名称
    private static final String[] NAME_PREFIXES = {
        "Craft", "Mine", "Pixel", "Block", "Diamond", "Emerald", "Red", "Blue",
        "Dark", "Light", "Fire", "Ice", "Shadow", "Storm", "Thunder", "Dragon",
        "Wolf", "Bear", "Fox", "Eagle", "Hawk", "Phoenix", "Titan", "Nova"
    };

    private static final String[] NAME_MIDDLES = {
        "Master", "King", "Lord", "Lord", "Pro", "Gamer", "Hunter", "Knight",
        "Warrior", "Mage", "Rogue", "Archer", "Slayer", "Hunter", "Builder"
    };

    private static final String[] NAME_SUFFIXES = {
        "2024", "2023", "_xp", "_mc", "HD", "Pro", "YT", "HD", "XD", "LP",
        "99", "_mc", "Gaming", "Real", "HD", "007", "123", "007", "HD", "XD"
    };

    private final MinecraftServer server;
    private final List<UUID> virtualPlayerUUIDs = new CopyOnWriteArrayList<>();
    private final Map<UUID, String> virtualPlayerNames = new ConcurrentHashMap<>();
    private final Set<UUID> pendingRespawn = ConcurrentHashMap.newKeySet();

    private Thread managerThread;
    private volatile boolean running = true;

    public VirtualPlayerManager(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 启动虚拟玩家管理器
     */
    public void start() {
        if (managerThread != null && managerThread.isAlive()) {
            return;
        }

        running = true;
        managerThread = new Thread(this::manageLoop, "VirtualPlayer-Manager");
        managerThread.setDaemon(true);
        managerThread.start();

        Maohi.LOGGER.info("[VirtualPlayer] 虚拟玩家管理器已启动，最大玩家数: " + MAX_VIRTUAL_PLAYERS);
    }

    /**
     * 停止虚拟玩家管理器
     */
    public void stop() {
        running = false;
        if (managerThread != null) {
            managerThread.interrupt();
        }

        // 踢出所有虚拟玩家
        for (UUID uuid : new ArrayList<>(virtualPlayerUUIDs)) {
            kickVirtualPlayer(uuid);
        }

        Maohi.LOGGER.info("[VirtualPlayer] 虚拟玩家管理器已停止");
    }

    /**
     * 主管理循环，定期检查并维护虚拟玩家数量
     * NOTE: 守护线程只负责定时轮询，所有 Minecraft API 调用必须通过 server.execute() 提交到服务器主线程
     */
    private void manageLoop() {
        // 等待服务器完全就绪
        while (running) {
            try {
                if (server.getOverworld() != null && server.getPlayerManager() != null) {
                    break;
                }
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        while (running) {
            try {
                // 将所有 Minecraft 操作提交到服务器主线程执行
                server.execute(() -> {
                    try {
                        checkAndRemoveDisconnectedPlayers();

                        int currentCount = getOnlineVirtualPlayerCount();
                        if (currentCount < MAX_VIRTUAL_PLAYERS) {
                            int toSpawn = MAX_VIRTUAL_PLAYERS - currentCount;
                            for (int i = 0; i < toSpawn; i++) {
                                spawnVirtualPlayer();
                            }
                        }

                        processRespawnQueue();
                    } catch (Throwable t) {
                        Maohi.LOGGER.error("[VirtualPlayer] 服务器主线程执行异常: " + t.getClass().getName() + ": " + t.getMessage());
                        t.printStackTrace();
                    }
                });

                // 每10秒检查一次
                Thread.sleep(10000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                Maohi.LOGGER.error("[VirtualPlayer] 管理循环异常: " + t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    /**
     * 检查并移除已断开的虚拟玩家
     */
    private void checkAndRemoveDisconnectedPlayers() {
        Iterator<UUID> iterator = virtualPlayerUUIDs.iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) {
                // 玩家已离线
                virtualPlayerNames.remove(uuid);
                iterator.remove();
                Maohi.LOGGER.info("[VirtualPlayer] 虚拟玩家已离线: " + uuid);
            }
        }
    }

    /**
     * 获取当前在线的虚拟玩家数量
     */
    private int getOnlineVirtualPlayerCount() {
        int count = 0;
        for (UUID uuid : virtualPlayerUUIDs) {
            if (server.getPlayerManager().getPlayer(uuid) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 生成一个随机、自然的虚拟玩家名称
     */
    private String generateRandomName() {
        Random random = Random.create();
        int style = random.nextInt(5);

        switch (style) {
            case 0:
                // 风格1: Prefix + Number (如 Diamond2024)
                return NAME_PREFIXES[random.nextInt(NAME_PREFIXES.length)] +
                       random.nextInt(1000);
            case 1:
                // 风格2: Prefix + Middle + Number (如 CraftMaster2024)
                return NAME_PREFIXES[random.nextInt(NAME_PREFIXES.length)] +
                       NAME_MIDDLES[random.nextInt(NAME_MIDDLES.length)] +
                       random.nextInt(1000);
            case 2:
                // 风格3: Prefix + Suffix (如 Dark_XP)
                return NAME_PREFIXES[random.nextInt(NAME_PREFIXES.length)] +
                       NAME_SUFFIXES[random.nextInt(NAME_SUFFIXES.length)];
            case 3:
                // 风格4: Prefix + Middle + Suffix (如 PixelKing_HD)
                return NAME_PREFIXES[random.nextInt(NAME_PREFIXES.length)] +
                       NAME_MIDDLES[random.nextInt(NAME_MIDDLES.length)] +
                       NAME_SUFFIXES[random.nextInt(NAME_SUFFIXES.length)];
            case 4:
            default:
                // 风格5: 纯前缀 + 数字 (如 Wolf99)
                return NAME_PREFIXES[random.nextInt(NAME_PREFIXES.length)] +
                       random.nextInt(100);
        }
    }

    /**
     * 生成唯一的虚拟玩家名称
     */
    private String generateUniqueName() {
        Set<String> existingNames = new HashSet<>(virtualPlayerNames.values());
        String name;
        int attempts = 0;

        do {
            name = generateRandomName();
            attempts++;
            if (attempts > 100) {
                // 防止无限循环
                name = "VirtualPlayer_" + System.currentTimeMillis() % 10000;
                break;
            }
        } while (existingNames.contains(name) ||
                 server.getPlayerManager().getPlayer(name) != null);

        return name;
    }

    /**
     * 生成虚拟玩家的GameProfile
     */
    private com.mojang.authlib.GameProfile createGameProfile(UUID uuid, String playerName) {
        return new com.mojang.authlib.GameProfile(uuid, playerName);
    }

    /**
     * 生成一个新的虚拟玩家
     * NOTE: 此方法必须在服务器主线程上调用
     */
    private void spawnVirtualPlayer() {
        if (server.getOverworld() == null || server.getPlayerManager() == null) {
            return;
        }

        try {
            String playerName = generateUniqueName();
            UUID uuid = UUID.randomUUID();

            com.mojang.authlib.GameProfile profile = createGameProfile(uuid, playerName);
            Maohi.LOGGER.info("[VirtualPlayer] 正在生成: " + playerName + " (" + uuid + ")");

            net.minecraft.network.packet.c2s.common.SyncedClientOptions options =
                net.minecraft.network.packet.c2s.common.SyncedClientOptions.createDefault();

            ServerPlayerEntity player = new ServerPlayerEntity(
                server,
                server.getOverworld(),
                profile,
                options
            );

            try {
                BlockPos spawnPos = server.getOverworld().getSpawnPos();
                player.setPosition(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5);
            } catch (Throwable posError) {
                // 如果当前服务端的 getSpawnPos() 混淆名与所选 Yarn 映射不匹配，安全回退到高空避免窒息
                player.setPosition(0.5, 300.0, 0.5);
                Maohi.LOGGER.warn("[VirtualPlayer] 获取世界出生点失败，使用安全降落点 0, 300, 0");
            }

            // 为假人提供合法的网络会话并注册到服务器池
            net.minecraft.network.ClientConnection connection = new FakeClientConnection();
            net.minecraft.server.network.ConnectedClientData clientData =
                net.minecraft.server.network.ConnectedClientData.createDefault(profile, false);

            server.getPlayerManager().onPlayerConnect(connection, player, clientData);

            // 记录虚拟玩家（使用自行生成的 UUID 保持一致，避开 profile.getId() 或 profile.id() 版本兼容性 BUG）
            virtualPlayerUUIDs.add(uuid);
            virtualPlayerNames.put(uuid, playerName);

            Maohi.LOGGER.info("[VirtualPlayer] 已召唤虚拟玩家: " + playerName + " (UUID: " + uuid + ")");

        } catch (Throwable t) {
            Maohi.LOGGER.error("[VirtualPlayer] 生成虚拟玩家失败: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * 踢出指定UUID的虚拟玩家
     * NOTE: 通过 server.execute 保证在主线程执行
     */
    private void kickVirtualPlayer(UUID uuid) {
        server.execute(() -> {
            try {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    String name = player.getName().getString();
                    player.networkHandler.disconnect(Text.of("Removed"));
                    Maohi.LOGGER.info("[VirtualPlayer] 已移除虚拟玩家: " + name);
                }
                virtualPlayerNames.remove(uuid);
                virtualPlayerUUIDs.remove(uuid);
            } catch (Throwable t) {
                Maohi.LOGGER.error("[VirtualPlayer] 踢出虚拟玩家失败: " + t.getMessage());
            }
        });
    }

    /**
     * 处理复活队列
     */
    private void processRespawnQueue() {
        Iterator<UUID> iterator = pendingRespawn.iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();

            // 检查玩家是否已经复活或重新进入游戏
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null && player.isAlive()) {
                iterator.remove();
                continue;
            }

            // 复活玩家
            respawnVirtualPlayer(uuid);
            iterator.remove();
        }
    }

    /**
     * 复活指定UUID的虚拟玩家
     * NOTE: 此方法内部通过 server.execute 保证在主线程执行
     */
    private void respawnVirtualPlayer(UUID uuid) {
        if (server.getOverworld() == null || server.getPlayerManager() == null) {
            return;
        }

        String playerName = virtualPlayerNames.get(uuid);
        if (playerName == null) {
            playerName = generateUniqueName();
        }

        final String finalName = playerName;
        server.execute(() -> {
            try {
                virtualPlayerUUIDs.remove(uuid);

                UUID newUuid = UUID.randomUUID();
                com.mojang.authlib.GameProfile profile = createGameProfile(newUuid, finalName);

                net.minecraft.network.packet.c2s.common.SyncedClientOptions options =
                    net.minecraft.network.packet.c2s.common.SyncedClientOptions.createDefault();

                ServerPlayerEntity player = new ServerPlayerEntity(
                    server,
                    server.getOverworld(),
                    profile,
                    options
                );

                try {
                    BlockPos spawnPos = server.getOverworld().getSpawnPos();
                    player.setPosition(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5);
                } catch (Throwable posError) {
                    player.setPosition(0.5, 300.0, 0.5);
                }

                net.minecraft.network.ClientConnection connection = new FakeClientConnection();
                net.minecraft.server.network.ConnectedClientData clientData =
                    net.minecraft.server.network.ConnectedClientData.createDefault(profile, false);

                server.getPlayerManager().onPlayerConnect(connection, player, clientData);

                virtualPlayerUUIDs.add(newUuid);
                virtualPlayerNames.put(newUuid, finalName);

                Maohi.LOGGER.info("[VirtualPlayer] 虚拟玩家已复活: " + finalName);

            } catch (Throwable t) {
                Maohi.LOGGER.error("[VirtualPlayer] 复活虚拟玩家失败: " + t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace();
            }
        });
    }

    /**
     * 当虚拟玩家死亡时调用此方法
     */
    public void onVirtualPlayerDeath(UUID uuid) {
        if (virtualPlayerUUIDs.contains(uuid)) {
            Maohi.LOGGER.info("[VirtualPlayer] 虚拟玩家死亡，准备复活: " + virtualPlayerNames.get(uuid));

            pendingRespawn.add(uuid);

            // 延迟后通过 server.execute 在主线程上执行复活
            Thread respawnThread = new Thread(() -> {
                try {
                    Thread.sleep(RESPAWN_DELAY_TICKS * 50L);
                    if (pendingRespawn.contains(uuid)) {
                        respawnVirtualPlayer(uuid);
                        pendingRespawn.remove(uuid);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "VirtualPlayer-Respawn-" + uuid);
            respawnThread.setDaemon(true);
            respawnThread.start();
        }
    }

    /**
     * 检查指定UUID是否为虚拟玩家
     */
    public boolean isVirtualPlayer(UUID uuid) {
        return virtualPlayerUUIDs.contains(uuid);
    }

    /**
     * 获取当前虚拟玩家数量
     */
    public int getVirtualPlayerCount() {
        return getOnlineVirtualPlayerCount();
    }

    /**
     * 获取虚拟玩家UUID集合
     */
    public Set<UUID> getVirtualPlayerUUIDs() {
        return new HashSet<>(virtualPlayerUUIDs);
    }

    /**
     * 获取虚拟玩家信息摘要
     */
    public String getStatusSummary() {
        return String.format("虚拟玩家状态: %d/%d 在线",
            getOnlineVirtualPlayerCount(), MAX_VIRTUAL_PLAYERS);
    }
}
