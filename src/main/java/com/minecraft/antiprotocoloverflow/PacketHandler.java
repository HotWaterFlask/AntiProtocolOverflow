package com.minecraft.antiprotocoloverflow;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.PrintWriter;
import java.io.StringWriter;
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
                try {
                    handleChunkDataPacket(event);
                } catch (Exception e) {
                    // 捕获异常，防止服务器崩溃或玩家被踢出
                    plugin.getLogger().warning("处理区块数据包时发生异常: " + e.getMessage());
                    event.setCancelled(true);
                }
            }
        });
        
        // 监听方块变化包
        protocolManager.addPacketListener(new PacketAdapter(plugin, PacketType.Play.Server.BLOCK_CHANGE) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    handleBlockChangePacket(event);
                } catch (Exception e) {
                    // 捕获异常，防止服务器崩溃或玩家被踢出
                    plugin.getLogger().warning("处理方块变化数据包时发生异常: " + e.getMessage());
                    event.setCancelled(true);
                }
            }
        });
        
        // 监听方块实体数据数据包（TILE_ENTITY_DATA - ProtocolLib 5.4.0中的正确常量）
        try {
            // 使用ProtocolLib 5.4.0中正确的常量名称TILE_ENTITY_DATA
            protocolManager.addPacketListener(new PacketAdapter(plugin, PacketType.Play.Server.TILE_ENTITY_DATA) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    try {
                        // 更安全的处理，确保异常不会导致玩家断开连接
                        handleBlockEntityDataPacket(event);
                    } catch (Exception e) {
                        // 记录异常但不影响玩家
                        plugin.getLogger().fine("处理方块实体数据时发生异常(安全处理): " + e.getMessage());
                        // 确保取消数据包发送，避免EncoderException
                        event.setCancelled(true);
                    }
                }
            });
            
            // 同时监听UPDATE_SIGN数据包，因为它也可能包含方块实体数据
            if (PacketType.Play.Server.UPDATE_SIGN != null) {
                protocolManager.addPacketListener(new PacketAdapter(plugin, PacketType.Play.Server.UPDATE_SIGN) {
                    @Override
                    public void onPacketSending(PacketEvent event) {
                        try {
                            handleBlockEntityDataPacket(event);
                        } catch (Exception e) {
                            // 记录异常但不影响玩家
                            plugin.getLogger().fine("处理告示牌数据时发生异常(安全处理): " + e.getMessage());
                            // 确保取消数据包发送
                            event.setCancelled(true);
                        }
                    }
                });
            }
        } catch (Exception e) {
            plugin.getLogger().warning("注册方块实体数据监听器时发生错误: " + e.getMessage());
        }
    }
    
    // 处理方块实体数据数据包 - 增强版，专门解决双箱问题
    private void handleBlockEntityDataPacket(PacketEvent event) {
        Player player = event.getPlayer();
        PacketContainer packet = event.getPacket();
        String debugInfo = "";
        
        try {
            // 直接检查数据包中的block_entity_type是否为null
            // 这是解决"Can't find id for 'null'"错误的关键
            try {
                // 尝试获取和检查block_entity_type
                // 根据ProtocolLib的API，我们需要使用适当的修饰符来访问这个字段
                // 这里使用ObjectModifier作为通用方式尝试访问
                StructureModifier<Object> objects = packet.getModifier();
                
                // 获取数据包的详细信息用于调试
                debugInfo += "玩家: " + player.getName() + " | ";
                
                // 尝试获取block_entity_type字段（具体索引可能需要根据Minecraft版本调整）
                // 通常在BlockPosition之后
                try {
                    Object blockEntityType = objects.read(1); // 假设在索引1位置
                        if (blockEntityType == null) {
                            // 获取方块位置信息
                            BlockPosition position = null;
                            try {
                                position = packet.getBlockPositionModifier().read(0);
                                debugInfo += "位置: " + position + " | ";
                            } catch (Exception posEx) {
                                debugInfo += "无法获取位置: " + posEx.getMessage() + " | ";
                            }
                            
                            // 获取玩家位置信息
                            Location playerLoc = player.getLocation();
                            debugInfo += "玩家位置: " + playerLoc.getBlockX() + "," + playerLoc.getBlockY() + "," + playerLoc.getBlockZ() + " | ";
                            
                            // 获取世界信息
                            debugInfo += "世界: " + player.getWorld().getName() + " | ";
                            
                            // 记录数据包的其他信息
                            try {
                                debugInfo += "数据包类型: " + packet.getType().name() + " | ";
                                debugInfo += "修饰符数量: " + objects.getValues().size() + " | ";
                            } catch (Exception ex) {
                                debugInfo += "无法获取数据包信息: " + ex.getMessage() + " | ";
                            }
                            
                            // 特殊处理：如果位置不是null，检查该位置是否是告示牌
                            if (position != null) {
                                try {
                                    World world = player.getWorld();
                                    if (world != null) {
                                        Block block = world.getBlockAt(position.getX(), position.getY(), position.getZ());
                                        if (block != null && isSignType(block.getType())) {
                                            // 如果是告示牌，允许通过，只记录日志而不取消数据包
                                            plugin.getLogger().fine("检测到告示牌位置的block_entity_type为null，允许通过 | " + debugInfo);
                                            // 不取消事件，继续处理
                                        } else {
                                            // 非告示牌位置，取消发送数据包
                                            plugin.getLogger().warning("检测到block_entity_type为null，立即取消发送数据包 | " + debugInfo);
                                            event.setCancelled(true);
                                            return;
                                        }
                                    } else {
                                        // 世界为null，取消发送数据包
                                        plugin.getLogger().warning("检测到block_entity_type为null且世界为null，取消发送数据包 | " + debugInfo);
                                        event.setCancelled(true);
                                        return;
                                    }
                                } catch (Exception ex) {
                                    // 检查方块类型时出错，记录日志但不取消事件，让后续检查继续处理
                                    plugin.getLogger().fine("检查方块类型时出错: " + ex.getMessage() + " | " + debugInfo);
                                }
                            } else {
                                // 位置为null，取消发送数据包
                                plugin.getLogger().warning("检测到block_entity_type为null且位置为null，取消发送数据包 | " + debugInfo);
                                event.setCancelled(true);
                                return;
                            }
                        } else {
                            debugInfo += "block_entity_type: " + blockEntityType.getClass().getName() + " | ";
                        }
                } catch (Exception typeAccessEx) {
                    // 如果无法直接访问block_entity_type字段，继续进行其他检查
                    plugin.getLogger().fine("无法直接访问block_entity_type字段: " + typeAccessEx.getMessage() + " | " + debugInfo);
                }
            } catch (Exception e) {
                plugin.getLogger().fine("检查block_entity_type时出错: " + e.getMessage() + " | " + debugInfo);
            }
            
            // 获取方块位置
            BlockPosition position = packet.getBlockPositionModifier().read(0);
            debugInfo += "处理方块位置: " + position + " | ";
            
            // 首先检查该位置是否是我们隐藏的方块
            UUID playerId = player.getUniqueId();
            Map<BlockPosition, Material> playerHiddenBlocks = hiddenBlocks.get(playerId);
            
            if (playerHiddenBlocks != null && playerHiddenBlocks.containsKey(position)) {
                // 如果是隐藏的方块，取消发送方块实体数据
                plugin.getLogger().fine("取消发送隐藏方块的实体数据: " + position + " | " + debugInfo);
                event.setCancelled(true);
                return;
            }
            
            // 增强的安全检查：验证方块是否真实存在且不为空气
            World world = player.getWorld();
            if (world == null) {
                // 世界为空的情况下取消发送数据包
                plugin.getLogger().fine("玩家世界为空，已取消发送方块实体数据: " + position + " | " + debugInfo);
                event.setCancelled(true);
                return;
            }
            debugInfo += "世界有效 | ";
            
            // 获取实际方块
            Block realBlock = world.getBlockAt(position.getX(), position.getY(), position.getZ());
            if (realBlock == null) {
                plugin.getLogger().fine("方块对象为null，已取消发送方块实体数据: " + position + " | " + debugInfo);
                event.setCancelled(true);
                return;
            }
            debugInfo += "方块对象有效 | ";
            
            Material blockType = realBlock.getType();
            debugInfo += "方块类型: " + blockType + " | ";
            
            if (blockType == Material.AIR) {
                // 如果方块不存在或为空，取消发送数据包
                plugin.getLogger().fine("检测到发送到空气方块的实体数据，已取消发送: " + position + " | " + debugInfo);
                event.setCancelled(true);
                return;
            }
            
            // 针对箱子（特别是双箱）的特殊处理
            if (blockType == Material.CHEST || blockType == Material.TRAPPED_CHEST) {
                debugInfo += "是箱子类型 | ";
                // 检查方块是否真的有方块实体数据
                BlockState state = realBlock.getState();
                if (state == null) {
                    // 如果方块状态为null，取消发送数据包
                    plugin.getLogger().fine("箱子方块状态为null，已取消发送方块实体数据: " + position + " | " + debugInfo);
                    event.setCancelled(true);
                    return;
                }
                debugInfo += "方块状态有效 | ";
                
                // 检查是否为双箱的一部分
                boolean isDoubleChestPart = false;
                int adjacentChestCount = 0;
                BlockPosition adjacentChestPos = null;
                
                int[][] directions = {{1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}}; // 东、西、南、北
                for (int[] dir : directions) {
                    Block adjacentBlock = world.getBlockAt(
                        position.getX() + dir[0], 
                        position.getY() + dir[1], 
                        position.getZ() + dir[2]);
                    if (adjacentBlock.getType() == blockType) {
                        isDoubleChestPart = true;
                        adjacentChestCount++;
                        adjacentChestPos = new BlockPosition(
                            position.getX() + dir[0], 
                            position.getY() + dir[1], 
                            position.getZ() + dir[2]);
                    }
                }
                
                debugInfo += "是否双箱: " + isDoubleChestPart + " | 相邻箱子数: " + adjacentChestCount + " | ";
                if (adjacentChestPos != null) {
                    debugInfo += "相邻箱子位置: " + adjacentChestPos + " | ";
                }
                
                // 对于双箱，需要更严格的检查
                if (isDoubleChestPart) {
                    debugInfo += "进行双箱特殊处理 | ";
                    // 双箱特殊处理：检查两个箱子是否都存在有效状态
                    try {
                        // 验证方块实体数据是否完整
                        // 这里我们不使用isPlaced()方法，因为在某些版本可能不可用
                        // 我们改为检查方块是否确实是容器类型
                        if (!(state instanceof org.bukkit.block.Container)) {
                            plugin.getLogger().fine("双箱部分方块不是有效的容器类型，已取消发送方块实体数据: " + position + " | " + debugInfo);
                            event.setCancelled(true);
                            return;
                        }
                        debugInfo += "容器类型验证通过 | ";
                    } catch (Exception ex) {
                        // 任何异常都取消发送
                        plugin.getLogger().fine("验证双箱状态时出错，已取消发送方块实体数据: " + position + " | " + debugInfo + "错误: " + ex.getMessage());
                        event.setCancelled(true);
                        return;
                    }
                }
            } else if (!isContainerType(blockType) && !isSignType(blockType) && !protectedBlockTypes.contains(blockType)) {
                // 对于非容器、非告示牌、非受保护类型的方块，取消发送方块实体数据
                plugin.getLogger().fine("检测到非预期类型的方块实体数据，已取消发送: " + position + " - " + blockType + " | " + debugInfo);
                event.setCancelled(true);
                return;
            } else {
                debugInfo += "方块类型通过初步验证 | ";
                
                // 特殊处理告示牌：即使block_entity_type为null，也允许通过验证
                // 这是为了解决告示牌位置出现block_entity_type为null的问题
                if (isSignType(blockType)) {
                    debugInfo += "特殊处理：告示牌类型 | ";
                    plugin.getLogger().fine("告示牌类型通过特殊验证: " + position + " | " + debugInfo);
                    // 不取消事件，允许发送数据包
                    return;
                }
            }
            
            // 最后的安全检查：尝试获取方块状态，确保它确实是一个方块实体
            try {
                BlockState state = realBlock.getState();
                debugInfo += "进行最终方块实体类型验证 | ";
                
                // 简化的方块实体类型检查，减少可能的异常
                boolean isBlockEntity = false;
                
                try {
                    isBlockEntity = state instanceof org.bukkit.block.Container || 
                                   state instanceof org.bukkit.block.Sign;
                    debugInfo += "容器/告示牌检查: " + isBlockEntity + " | ";
                    
                    // 只在确认是block entity后才进行额外检查
                    if (!isBlockEntity) {
                        String blockTypeName = blockType.name();
                        isBlockEntity = blockTypeName.contains("BEACON") || 
                                        blockTypeName.contains("JUKEBOX") ||
                                        blockTypeName.contains("BREWING") ||
                                        blockTypeName.contains("ENCHANTING") ||
                                        blockTypeName.contains("FURNACE") ||
                                        blockTypeName.contains("HOPPER") ||
                                        blockTypeName.contains("DISPENSER") ||
                                        blockTypeName.contains("DROPPER") ||
                                        blockTypeName.contains("LECTERN");
                        debugInfo += "特殊方块类型检查: " + isBlockEntity + " | 方块名: " + blockTypeName + " | ";
                    }
                } catch (Exception innerEx) {
                    // 如果类型检查出错，视为非方块实体
                    debugInfo += "类型检查异常: " + innerEx.getMessage() + " | ";
                    plugin.getLogger().fine("方块实体类型检查出错: " + innerEx.getMessage() + " | " + debugInfo);
                }
                
                if (!isBlockEntity) {
                    plugin.getLogger().fine("方块状态不是有效的方块实体类型，已取消发送: " + position + " | " + debugInfo);
                    event.setCancelled(true);
                } else {
                    plugin.getLogger().fine("方块实体数据通过所有安全检查: " + position + " | " + debugInfo);
                }
            } catch (Exception e) {
                // 获取状态失败时取消发送
                plugin.getLogger().fine("获取方块状态失败，已取消发送方块实体数据: " + position + " | " + debugInfo + "错误: " + e.getMessage());
                event.setCancelled(true);
            }
            
        } catch (Exception e) {
            // 捕获所有异常，确保不会因为单个数据包处理失败而导致玩家断开连接
            plugin.getLogger().warning("处理方块实体数据时发生异常(完全捕获): " + e.getMessage() + " | " + debugInfo + " | 异常堆栈: " + getStackTraceAsString(e));
            // 异常情况下取消发送数据包，避免EncoderException
            event.setCancelled(true);
        }
    }
    
    // 辅助方法：获取异常堆栈的字符串表示
    private String getStackTraceAsString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString().substring(0, Math.min(300, sw.toString().length())); // 限制长度避免日志过大
    }
    
    // 安全显示方块的方法，避免发送有问题的方块实体数据
    private void safeShowBlock(Player player, BlockPosition pos, Material originalType) {
        try {
            // 检查方块位置是否有效
            World world = player.getWorld();
            if (world == null) {
                throw new IllegalArgumentException("玩家世界为空");
            }
            
            // 获取世界中的实际方块
            Block realBlock = world.getBlockAt(pos.getX(), pos.getY(), pos.getZ());
            
            // 验证方块是否存在且类型与记录一致
            if (realBlock.getType() != originalType && originalType != null) {
                plugin.getLogger().warning("方块类型不匹配，跳过显示: " + pos);
                return;
            }
            
            // 发送恢复方块的数据包
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
            packet.getBlockPositionModifier().write(0, pos);
            packet.getBlockData().write(0, WrappedBlockData.createData(realBlock.getBlockData()));
            protocolManager.sendServerPacket(player, packet);
            
            // 对于大箱子等复合方块，安全地更新相邻方块
            if (originalType == Material.CHEST || originalType == Material.TRAPPED_CHEST) {
                try {
                    updateAdjacentChestBlocks(player, world, pos.getX(), pos.getY(), pos.getZ());
                } catch (Exception e) {
                    plugin.getLogger().warning("更新相邻箱子时出错，但不影响主方块显示: " + e.getMessage());
                }
            }
            
            // 对于告示牌，安全地更新文本内容
            if (isSignType(originalType)) {
                try {
                    updateSignText(player, realBlock, pos);
                } catch (Exception e) {
                    plugin.getLogger().warning("更新告示牌文本时出错，但不影响主方块显示: " + e.getMessage());
                }
            }
            
            // 从隐藏列表中移除
            Map<BlockPosition, Material> playerHiddenBlocks = hiddenBlocks.get(player.getUniqueId());
            if (playerHiddenBlocks != null) {
                playerHiddenBlocks.remove(pos);
            }
        } catch (Exception e) {
            // 捕获所有异常，确保不会因为单个方块显示失败而导致更严重的问题
            throw new RuntimeException("显示方块时发生错误: " + pos, e);
        }
    }
    
    // 增强隐藏方块管理，确保安全处理方块数据
    public void safeShowBlocks(Player player) {
        UUID playerId = player.getUniqueId();
        Map<BlockPosition, Material> playerHiddenBlocks = hiddenBlocks.get(playerId);
        
        if (playerHiddenBlocks != null) {
            // 直接调用现有的显示方法，但添加额外的错误处理
            try {
                showBlocksForPlayer(player);
            } catch (Exception e) {
                plugin.getLogger().warning("安全显示方块过程中发生异常: " + e.getMessage());
                // 即使出现异常，也尝试清理隐藏方块记录
                try {
                    playerHiddenBlocks.clear();
                } catch (Exception cleanupEx) {
                    plugin.getLogger().severe("清理隐藏方块记录失败: " + cleanupEx.getMessage());
                }
            }
        }
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
                Material type = realBlock.getType();
                
                // 发送恢复方块的数据包，使用实际方块的完整数据（包括方向、状态等）
                PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
                packet.getBlockPositionModifier().write(0, pos);
                // 直接使用方块的BlockData创建WrappedBlockData
                packet.getBlockData().write(0, WrappedBlockData.createData(realBlock.getBlockData()));
                protocolManager.sendServerPacket(player, packet);
                
                // 对于大箱子等复合方块，还需要发送相邻方块的更新
                if (type == Material.CHEST || type == Material.TRAPPED_CHEST) {
                    updateAdjacentChestBlocks(player, world, pos.getX(), pos.getY(), pos.getZ());
                }
                
                // 对于告示牌，额外发送UPDATE_SIGN数据包来更新文本内容
                if (isSignType(type)) {
                    updateSignText(player, realBlock, pos);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("显示方块时出错: " + e.getMessage());
            }
        }
    }
    
    // 判断方块是否为告示牌类型
    private boolean isSignType(Material type) {
        if (type == null) return false;
        
        String typeName = type.toString();
        return typeName.contains("SIGN") && !typeName.contains("ITEM_FRAME");
    }
    
    // 更新告示牌文本内容
    private void updateSignText(Player player, Block realBlock, BlockPosition pos) {
        try {
            // 安全检查
            if (player == null || realBlock == null || pos == null) {
                plugin.getLogger().fine("更新告示牌文本时参数为空: player=" + (player != null) + ", block=" + (realBlock != null) + ", pos=" + (pos != null));
                return;
            }
            
            // 获取告示牌状态
            BlockState state = realBlock.getState();
            if (state instanceof Sign) {
                Sign sign = (Sign) state;
                
                // 记录告示牌更新信息
                plugin.getLogger().fine("更新告示牌文本: " + pos + " | 世界: " + player.getWorld().getName());
                
                // 检查告示牌是否为空（所有行都为空）
                boolean isEmptySign = true;
                for (int i = 0; i < 4; i++) {
                    String line = sign.getLine(i);
                    if (line != null && !line.isEmpty()) {
                        isEmptySign = false;
                        break;
                    }
                }
                
                // 如果告示牌为空，不发送UPDATE_SIGN数据包，避免产生block_entity_type为null的问题
                if (isEmptySign) {
                    plugin.getLogger().fine("空告示牌，跳过发送UPDATE_SIGN数据包: " + pos);
                    return;
                }
                
                // 创建UPDATE_SIGN数据包
                PacketContainer updatePacket = protocolManager.createPacket(PacketType.Play.Server.UPDATE_SIGN);
                updatePacket.getBlockPositionModifier().write(0, pos);
                
                // 简化的文本写入方式，避免索引越界
                StructureModifier<String> stringModifier = updatePacket.getStrings();
                
                // 尝试安全地写入文本行，捕获每一行可能的索引错误
                // 使用getValues()检查是否有足够的字段可用
                List<String> values = stringModifier.getValues();
                for (int i = 0; i < 4 && i < values.size(); i++) {
                    try {
                        String line = sign.getLine(i) != null ? sign.getLine(i) : "";
                        stringModifier.write(i, line);
                    } catch (Exception ex) {
                        // 忽略单行错误，继续处理其他行
                        plugin.getLogger().fine("写入告示牌第" + i + "行时出错: " + ex.getMessage());
                    }
                }
                
                // 发送数据包给玩家
                protocolManager.sendServerPacket(player, updatePacket);
                plugin.getLogger().fine("成功发送告示牌更新数据包: " + pos);
            } else {
                plugin.getLogger().fine("方块状态不是告示牌类型: " + pos + " | 类型: " + (state != null ? state.getType() : "null"));
            }
        } catch (Exception e) {
            // 使用更通用的错误信息，避免显示索引越界细节
            plugin.getLogger().warning("更新告示牌文本时发生异常" + " | 位置: " + pos + " | " + getStackTraceAsString(e));
        }
    }
    
    // 更新相邻的箱子方块，确保大箱子正确显示 - 增强版
    private void updateAdjacentChestBlocks(Player player, World world, int x, int y, int z) {
        if (world == null || player == null) {
            plugin.getLogger().warning("更新相邻箱子时世界或玩家为空");
            return;
        }
        
        // 检查四个方向的相邻方块
        int[][] directions = {{1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}}; // 东、西、南、北
        
        for (int[] dir : directions) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            int nz = z + dir[2];
            
            // 安全检查坐标是否在世界范围内
            if (nx < world.getMinHeight() || nx >= world.getMaxHeight() ||
                ny < world.getMinHeight() || ny >= world.getMaxHeight() ||
                nz < world.getMinHeight() || nz >= world.getMaxHeight()) {
                continue;
            }
            
            try {
                Block adjacentBlock = world.getBlockAt(nx, ny, nz);
                if (adjacentBlock == null) continue;
                
                Material type = adjacentBlock.getType();
                
                // 如果相邻方块也是箱子，发送更新
                if (type == Material.CHEST || type == Material.TRAPPED_CHEST) {
                    try {
                        // 确保方块状态有效
                        BlockState state = adjacentBlock.getState();
                        if (state == null || !state.isPlaced()) {
                            plugin.getLogger().fine("相邻箱子方块状态无效，跳过更新: " + nx + "," + ny + "," + nz);
                            continue;
                        }
                        
                        BlockPosition adjPos = new BlockPosition(nx, ny, nz);
                        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
                        packet.getBlockPositionModifier().write(0, adjPos);
                        // 直接使用方块的BlockData创建WrappedBlockData
                        packet.getBlockData().write(0, WrappedBlockData.createData(adjacentBlock.getBlockData()));
                        protocolManager.sendServerPacket(player, packet);
                        
                        // 延迟发送方块实体数据，避免时序问题
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            try {
                                // 单独处理方块实体数据的更新，确保安全
                                updateBlockEntityData(player, adjacentBlock, adjPos);
                            } catch (Exception ex) {
                                plugin.getLogger().fine("延迟更新相邻箱子方块实体数据时出错: " + ex.getMessage());
                            }
                        }, 1L);
                    } catch (Exception e) {
                        plugin.getLogger().fine("更新相邻箱子时出错: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                // 捕获每个方向的异常，不影响其他方向的处理
                plugin.getLogger().fine("处理相邻方向(" + dir[0] + "," + dir[1] + "," + dir[2] + ")时出错: " + e.getMessage());
            }
        }
    }
    
    // 安全更新方块实体数据的辅助方法
    private void updateBlockEntityData(Player player, Block block, BlockPosition pos) {
        try {
            // 获取方块状态
            BlockState state = block.getState();
            if (state == null) return;
            
            // 根据方块类型分别处理
            if (state instanceof Sign) {
                // 对于告示牌，使用专门的更新方法
                updateSignText(player, block, pos);
            } else if (state instanceof org.bukkit.block.Container) {
                // 对于容器类方块，我们不直接发送方块实体数据
                // 因为这可能导致block_entity_type为null的问题
                // 相反，我们依赖服务器正常发送这些数据，并在handleBlockEntityDataPacket中进行安全过滤
                plugin.getLogger().fine("容器方块实体数据将由服务器正常发送并经过安全过滤: " + pos);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("安全更新方块实体数据时出错: " + e.getMessage());
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
    
    // 删除重复的方法，保留原有的hideBlock实现
    
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
            // 创建一个副本进行迭代，避免并发修改异常
            Map<BlockPosition, Material> copyOfHiddenBlocks = new HashMap<>(playerHiddenBlocks);
            List<BlockPosition> blocksToShow = new ArrayList<>(copyOfHiddenBlocks.keySet());
            final int[] index = {0};
            
            // 批量显示方块，使用安全的显示方法
            plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
                try {
                    int count = 0;
                    
                    while (index[0] < blocksToShow.size() && count < maxBlocksPerUpdate) {
                        BlockPosition pos = blocksToShow.get(index[0]);
                        Material originalType = copyOfHiddenBlocks.get(pos);
                        
                        // 使用安全的方法显示方块，增强异常处理
                        try {
                            safeShowBlock(player, pos, originalType);
                        } catch (Exception e) {
                            plugin.getLogger().warning("安全显示方块失败: " + e.getMessage());
                            // 即使显示失败，也从隐藏列表中移除，避免后续再次尝试导致错误
                            if (playerHiddenBlocks != null) {
                                playerHiddenBlocks.remove(pos);
                            }
                        }
                        
                        index[0]++;
                        count++;
                    }
                    
                    if (index[0] >= blocksToShow.size()) {
                        task.cancel();
                        // 清除记录
                        if (playerHiddenBlocks != null) {
                            playerHiddenBlocks.clear();
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("批量显示方块任务异常: " + e.getMessage());
                    task.cancel();
                    // 出错时也尝试清理，避免状态不一致
                    if (playerHiddenBlocks != null) {
                        playerHiddenBlocks.clear();
                    }
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