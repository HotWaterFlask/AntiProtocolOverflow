package com.minecraft.antiprotocoloverflow;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PacketHandler {
    private final JavaPlugin plugin;
    private final ProtocolManager protocolManager;
    private final Set<Material> protectedBlockTypes = new HashSet<>();
    private final Map<UUID, Map<BlockPosition, Material>> hiddenBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Location>> processedChunks = new ConcurrentHashMap<>();
    
    // 配置参数
    private final int maxDistance = 16; // 显示保护方块的最大距离
    private final int maxBlocksPerUpdate = 50; // 每批次更新的最大方块数量
    private final long updateInterval = 50; // 更新间隔(毫秒)
    
    public PacketHandler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        initProtectedBlockTypes();
        registerPacketListeners();
        registerPlayerMoveListener();
    }
    
    // 初始化玩家数据
    public void initializePlayer(Player player) {
        // 初始化玩家隐藏方块集合
        hiddenBlocks.putIfAbsent(player.getUniqueId(), new HashMap<>());
        processedChunks.putIfAbsent(player.getUniqueId(), new HashSet<>());
    }
    
    private void initProtectedBlockTypes() {
        // 添加所有需要保护的方块类型
        protectedBlockTypes.add(Material.CHEST);
        protectedBlockTypes.add(Material.TRAPPED_CHEST);
        protectedBlockTypes.add(Material.ENDER_CHEST);
        
        // 添加所有潜影盒类型
        for (Material material : Material.values()) {
            if (material.toString().contains("SHULKER_BOX")) {
                protectedBlockTypes.add(material);
            }
        }
        
        // 添加所有告示牌类型
        protectedBlockTypes.add(Material.OAK_SIGN);
        protectedBlockTypes.add(Material.OAK_WALL_SIGN);
        protectedBlockTypes.add(Material.SPRUCE_SIGN);
        protectedBlockTypes.add(Material.SPRUCE_WALL_SIGN);
        protectedBlockTypes.add(Material.BIRCH_SIGN);
        protectedBlockTypes.add(Material.BIRCH_WALL_SIGN);
        protectedBlockTypes.add(Material.JUNGLE_SIGN);
        protectedBlockTypes.add(Material.JUNGLE_WALL_SIGN);
        protectedBlockTypes.add(Material.ACACIA_SIGN);
        protectedBlockTypes.add(Material.ACACIA_WALL_SIGN);
    }
    
    // 从BlockData获取Material（兼容不同版本）
    private Material getMaterialFromBlockData(WrappedBlockData blockData) {
        try {
            // 直接使用字符串解析方式，避免调用可能不存在的方法
            String dataString = blockData.toString();
            // 简单解析dataString获取方块类型
            if (dataString.contains("{")) {
                dataString = dataString.substring(0, dataString.indexOf('{')).trim();
            }
            try {
                return Material.valueOf(dataString.toUpperCase());
            } catch (Exception ex) {
                return null;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("获取方块材质时出错: " + e.getMessage());
            return null;
        }
    }
    
    // 检查方块是否为容器类型
    private boolean isContainerType(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST ||
               material == Material.BARREL || material == Material.SHULKER_BOX ||
               material == Material.WHITE_SHULKER_BOX || material == Material.ORANGE_SHULKER_BOX ||
               material == Material.MAGENTA_SHULKER_BOX || material == Material.LIGHT_BLUE_SHULKER_BOX ||
               material == Material.YELLOW_SHULKER_BOX || material == Material.LIME_SHULKER_BOX ||
               material == Material.PINK_SHULKER_BOX || material == Material.GRAY_SHULKER_BOX ||
               material == Material.LIGHT_GRAY_SHULKER_BOX || material == Material.CYAN_SHULKER_BOX ||
               material == Material.PURPLE_SHULKER_BOX || material == Material.BLUE_SHULKER_BOX ||
               material == Material.BROWN_SHULKER_BOX || material == Material.GREEN_SHULKER_BOX ||
               material == Material.RED_SHULKER_BOX || material == Material.BLACK_SHULKER_BOX ||
               material == Material.ENDER_CHEST || material == Material.HOPPER ||
               material == Material.DROPPER || material == Material.DISPENSER ||
               material == Material.FURNACE || material == Material.BLAST_FURNACE ||
               material == Material.SMOKER || material == Material.BREWING_STAND ||
               material == Material.LECTERN || material == Material.CARTOGRAPHY_TABLE ||
               material == Material.LOOM || material == Material.STONECUTTER ||
               material == Material.GRINDSTONE || material == Material.ANVIL ||
               material == Material.CHIPPED_ANVIL || material == Material.DAMAGED_ANVIL ||
               material == Material.BEACON || material == Material.ENCHANTING_TABLE ||
               material == Material.BOOKSHELF || material == Material.COMPOSTER ||
               material == Material.CRAFTING_TABLE || material == Material.SMITHING_TABLE ||
               material == Material.FLETCHING_TABLE;
    }
    
    private void registerPacketListeners() {
        // 监听区块数据发送包
        protocolManager.addPacketListener(new PacketAdapter(plugin, PacketType.Play.Server.MAP_CHUNK) {
            @Override
            public void onPacketSending(PacketEvent event) {
                handleChunkDataPacket(event);
            }
        });
        
        // 监听方块变化包
        protocolManager.addPacketListener(new PacketAdapter(plugin, PacketType.Play.Server.BLOCK_CHANGE) {
            @Override
            public void onPacketSending(PacketEvent event) {
                handleBlockChangePacket(event);
            }
        });
    }
    
    // 注册玩家移动监听器，用于检测距离变化并显示接近的方块
    private void registerPlayerMoveListener() {
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerMove(PlayerMoveEvent event) {
                Location from = event.getFrom();
                Location to = event.getTo();
                if (to != null && from.distanceSquared(to) > 2.0) { // 当移动超过约1.4格时
                    checkAndShowNearbyBlocks(event.getPlayer());
                }
            }
            
            @EventHandler
            public void onPlayerTeleport(PlayerTeleportEvent event) {
                // 传送后立即检查并显示周围的方块
                Player player = event.getPlayer();
                // 使用延迟任务确保区块已加载
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    checkAndShowNearbyBlocks(player);
                    // 额外显示玩家周围的方块，确保所有近距离方块都正确显示
                    showBlocksForPlayer(player);
                }, 1L); // 1 tick后执行
            }
        }, plugin);
    }
    
    // 检查并显示玩家附近的隐藏方块
    private void checkAndShowNearbyBlocks(Player player) {
        UUID playerId = player.getUniqueId();
        Map<BlockPosition, Material> playerHiddenBlocks = hiddenBlocks.get(playerId);
        
        if (playerHiddenBlocks != null && !playerHiddenBlocks.isEmpty()) {
            List<BlockPosition> toShow = new ArrayList<>();
            Location playerLoc = player.getLocation();
            double maxDistSq = maxDistance * maxDistance;
            
            for (Map.Entry<BlockPosition, Material> entry : playerHiddenBlocks.entrySet()) {
                BlockPosition pos = entry.getKey();
                Location blockLoc = new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ());
                
                // 如果方块现在在显示范围内
                if (blockLoc.distanceSquared(playerLoc) <= maxDistSq) {
                    toShow.add(pos);
                }
            }
            
            // 显示应该可见的方块
            for (BlockPosition pos : toShow) {
                showBlock(player, pos);
            }
        }
        
        // 额外检查玩家周围8个区块内的所有方块
        refreshVisibleBlocksInRange(player);
    }
    
    // 显示单个隐藏的方块
    private void showBlock(Player player, BlockPosition pos) {
        UUID playerId = player.getUniqueId();
        Map<BlockPosition, Material> playerHiddenBlocks = hiddenBlocks.get(playerId);
        
        if (playerHiddenBlocks != null && playerHiddenBlocks.containsKey(pos)) {
            playerHiddenBlocks.remove(pos);
            
            try {
                // 获取世界中的实际方块，使用真实的方块状态
                World world = player.getWorld();
                Block realBlock = world.getBlockAt(pos.getX(), pos.getY(), pos.getZ());
                
                // 发送恢复方块的数据包，使用实际方块的完整数据（包括方向、状态等）
                PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
                packet.getBlockPositionModifier().write(0, pos);
                // 直接使用方块的BlockData创建WrappedBlockData
                packet.getBlockData().write(0, WrappedBlockData.createData(realBlock.getBlockData()));
                protocolManager.sendServerPacket(player, packet);
                
                // 对于大箱子等复合方块，还需要发送相邻方块的更新
                if (realBlock.getType() == Material.CHEST || realBlock.getType() == Material.TRAPPED_CHEST) {
                    updateAdjacentChestBlocks(player, world, pos.getX(), pos.getY(), pos.getZ());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("显示方块时出错: " + e.getMessage());
            }
        }
    }
    
    // 更新相邻的箱子方块，确保大箱子正确显示
    private void updateAdjacentChestBlocks(Player player, World world, int x, int y, int z) {
        // 检查四个方向的相邻方块
        int[][] directions = {{1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}}; // 东、西、南、北
        
        for (int[] dir : directions) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            int nz = z + dir[2];
            
            Block adjacentBlock = world.getBlockAt(nx, ny, nz);
            Material type = adjacentBlock.getType();
            
            // 如果相邻方块也是箱子，发送更新
            if (type == Material.CHEST || type == Material.TRAPPED_CHEST) {
                try {
                    BlockPosition adjPos = new BlockPosition(nx, ny, nz);
                    PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
                    packet.getBlockPositionModifier().write(0, adjPos);
                    // 直接使用方块的BlockData创建WrappedBlockData
                    packet.getBlockData().write(0, WrappedBlockData.createData(adjacentBlock.getBlockData()));
                    protocolManager.sendServerPacket(player, packet);
                } catch (Exception e) {
                    plugin.getLogger().warning("更新相邻箱子时出错: " + e.getMessage());
                }
            }
        }
    }
    
    private void handleChunkDataPacket(PacketEvent event) {
        Player player = event.getPlayer();
        PacketContainer packet = event.getPacket();
        
        try {
            // 获取区块坐标
            int chunkX = packet.getIntegers().read(0);
            int chunkZ = packet.getIntegers().read(1);
            
            Location chunkLocation = new Location(player.getWorld(), chunkX * 16, 0, chunkZ * 16);
            
            // 记录已处理的区块
            processedChunks.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(chunkLocation);
            
            // 异步扫描区块中的保护方块
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                scanChunkForProtectedBlocks(player, chunkX, chunkZ);
            });
            
            // 区块加载后，立即检查并显示玩家附近应该可见的方块
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                checkAndShowNearbyBlocks(player);
            }, 2L); // 2 ticks后执行，确保区块完全加载
            
        } catch (Exception e) {
            plugin.getLogger().warning("处理区块数据包时出错: " + e.getMessage());
        }
    }
    
    // 刷新玩家可见范围内的所有方块
    private void refreshVisibleBlocksInRange(Player player) {
        // 获取玩家当前位置
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();
        if (world == null) return;
        
        // 获取玩家周围应该可见的方块
        Map<BlockPosition, Material> playerHiddenBlocks = hiddenBlocks.get(player.getUniqueId());
        if (playerHiddenBlocks == null) return;
        
        // 检查是否有距离玩家很近但仍然被隐藏的方块
        double immediateDistSq = (maxDistance / 2) * (maxDistance / 2); // 更近的距离阈值
        
        for (Map.Entry<BlockPosition, Material> entry : new HashMap<>(playerHiddenBlocks).entrySet()) {
            BlockPosition pos = entry.getKey();
            Location blockLoc = new Location(world, pos.getX(), pos.getY(), pos.getZ());
            
            // 如果方块距离玩家很近，强制显示
            if (blockLoc.distanceSquared(playerLoc) <= immediateDistSq) {
                showBlock(player, pos);
            }
        }
    }
    
    private void handleBlockChangePacket(PacketEvent event) {
        Player player = event.getPlayer();
        PacketContainer packet = event.getPacket();
        
        try {
            // 获取方块位置
            BlockPosition blockPos = packet.getBlockPositionModifier().read(0);
            WrappedBlockData blockData = packet.getBlockData().read(0);
            
            // 检查是否为保护方块类型
            try {
                Material blockType = getMaterialFromBlockData(blockData);
                if (blockType != null && protectedBlockTypes.contains(blockType)) {
                    Location blockLocation = new Location(player.getWorld(), blockPos.getX(), blockPos.getY(), blockPos.getZ());
                
                    // 计算与玩家的距离
                    double distance = blockLocation.distanceSquared(player.getLocation());
                
                    // 如果距离超过最大距离，隐藏方块
                    if (distance > maxDistance * maxDistance) {
                        hideBlock(player, blockPos, blockType);
                        event.setCancelled(true);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("处理方块检查时出错: " + e.getMessage());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("处理方块变化数据包时出错: " + e.getMessage());
        }
    }
    
    private void scanChunkForProtectedBlocks(Player player, int chunkX, int chunkZ) {
        try {
            World world = player.getWorld();
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            
            List<BlockData> protectedBlocks = new ArrayList<>();
            
            // 扫描区块中的所有保护方块（限制Y范围以提高性能）
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        // 只扫描可能有方块的Y范围
                        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                            Block block = chunk.getBlock(x, y, z);
                            if (protectedBlockTypes.contains(block.getType())) {
                                Location loc = block.getLocation();
                                double distSq = loc.distanceSquared(player.getLocation());
                                
                                if (distSq > maxDistance * maxDistance) {
                                    protectedBlocks.add(new BlockData(
                                        new BlockPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                                        block.getType(),
                                        distSq,
                                        loc.getBlockY()
                                    ));
                                }
                            }
                        }
                    }
                }
            
            // 按距离和Y坐标排序
            protectedBlocks.sort((a, b) -> {
                if (a.distanceSquared != b.distanceSquared) {
                    return Double.compare(a.distanceSquared, b.distanceSquared);
                }
                return Integer.compare(b.y, a.y); // Y坐标降序
            });
            
            // 批量处理需要隐藏的方块
            if (!protectedBlocks.isEmpty()) {
                sendBlockUpdates(player, protectedBlocks);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("扫描区块保护方块时出错: " + e.getMessage());
        }
    }
    
    private void sendBlockUpdates(Player player, List<BlockData> blocks) {
        final int[] index = {0};
        
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            int count = 0;
            
            // 每次处理一定数量的方块
            while (index[0] < blocks.size() && count < maxBlocksPerUpdate) {
                BlockData blockData = blocks.get(index[0]);
                hideBlock(player, blockData.position, blockData.material);
                
                index[0]++;
                count++;
            }
            
            // 完成所有方块处理后取消任务
            if (index[0] >= blocks.size()) {
                task.cancel();
            }
        }, 0, updateInterval / 50); // Minecraft tick为50ms
    }
    
    private void hideBlock(Player player, BlockPosition pos, Material originalType) {
        try {
            // 创建空气方块更新包
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
            packet.getBlockPositionModifier().write(0, pos);
            packet.getBlockData().write(0, WrappedBlockData.createData(Material.AIR));
            
            // 发送伪装包给玩家
            protocolManager.sendServerPacket(player, packet);
            
            // 记录隐藏的方块
            hiddenBlocks.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(pos, originalType);
        } catch (Exception e) {
            plugin.getLogger().warning("隐藏方块时出错: " + e.getMessage());
        }
    }
    
    public void showBlocksForPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        Map<BlockPosition, Material> playerHiddenBlocks = hiddenBlocks.get(playerId);
        
        if (playerHiddenBlocks != null) {
            List<Map.Entry<BlockPosition, Material>> blocksList = new ArrayList<>(playerHiddenBlocks.entrySet());
            final int[] index = {0};
            
            // 批量显示方块
            plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
                int count = 0;
                
                while (index[0] < blocksList.size() && count < maxBlocksPerUpdate) {
                    Map.Entry<BlockPosition, Material> entry = blocksList.get(index[0]);
                    BlockPosition pos = entry.getKey();
                    
                    // 使用showBlock方法统一处理
                    showBlock(player, pos);
                    
                    index[0]++;
                    count++;
                }
                
                if (index[0] >= blocksList.size()) {
                    task.cancel();
                    // 清除记录
                    playerHiddenBlocks.clear();
                }
            }, 0, updateInterval / 50);
        }
    }
    
    public void clearPlayerData(Player player) {
        UUID playerId = player.getUniqueId();
        hiddenBlocks.remove(playerId);
        if (processedChunks.containsKey(playerId)) {
            processedChunks.get(playerId).clear();
        }
    }
    
    public void unregister() {
        protocolManager.removePacketListeners(plugin);
        hiddenBlocks.clear();
        processedChunks.clear();
    }
    
    // 内部类用于存储方块数据
    private static class BlockData {
        final BlockPosition position;
        final Material material;
        final double distanceSquared;
        final int y;
        
        BlockData(BlockPosition position, Material material, double distanceSquared, int y) {
            this.position = position;
            this.material = material;
            this.distanceSquared = distanceSquared;
            this.y = y;
        }
    }
}