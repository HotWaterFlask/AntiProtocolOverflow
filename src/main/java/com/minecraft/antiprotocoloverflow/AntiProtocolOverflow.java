package com.minecraft.antiprotocoloverflow;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.Chunk;

public class AntiProtocolOverflow extends JavaPlugin implements Listener {
    private final Logger logger = Bukkit.getLogger();
    
    // 数据包处理器
    private PacketHandler packetHandler;
    
    // 用于跟踪玩家的加载状态
    private final Map<UUID, Boolean> isLoadingInventory = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> inventoryLoadProgress = new ConcurrentHashMap<>();
    // 用于跟踪容器的加载状态
    private final Map<Inventory, Boolean> isLoadingContainer = new ConcurrentHashMap<>();
    // 用于跟踪已加载的区块
    private final Map<UUID, Set<Chunk>> loadedChunks = new ConcurrentHashMap<>();
    
    // 配置参数
    private int itemLoadDelay;          // 物品加载延迟(毫秒)
    private int blockLoadDelay;         // 方块加载延迟(毫秒)
    private int initialChunkRadius;     // 初始加载区块半径
    private int itemsPerLoad;           // 每次加载的物品数量
    private boolean slowInventoryLoad;  // 是否启用缓慢加载背包
    private boolean slowBlockLoad;      // 是否启用缓慢加载方块
    private boolean logLoadEvents;      // 是否记录加载事件
    private boolean enableProtection;   // 是否启用保护
    private boolean enableMessages;     // 是否启用消息提示
    private String messageInventoryLoading; // 背包加载中的提示消息
    private String messageContainerLoading; // 容器加载中的提示消息
    
    // 受保护的方块类型
    private final Set<Material> protectedBlockTypes = new HashSet<>();

    @Override
    public void onEnable() {
        // 保存默认配置
        saveDefaultConfig();
        // 初始化受保护方块类型
        initProtectedBlockTypes();
        // 加载配置
        loadConfigValues();
        
        // 注册事件监听器
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // 初始化数据包处理器
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
                packetHandler = new PacketHandler(this);
                logger.info("成功初始化ProtocolLib支持，使用数据包层处理方块");
            } else {
                logger.warning("未检测到ProtocolLib，插件将无法正常工作！");
                logger.warning("请安装ProtocolLib 5.4.0或更高版本以使用此插件");
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
        } catch (Exception e) {
            logger.severe("初始化ProtocolLib处理器时出错: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        
        logger.info("AntiProtocolOverflow 插件已启用，使用ProtocolLib数据包层处理");
    }

    @Override
    public void onDisable() {
        // 清理数据包处理器
        if (packetHandler != null) {
            packetHandler.unregister();
            packetHandler = null;
        }
        
        logger.info("AntiProtocolOverflow 插件已禁用!");
    }
    
    /**
     * 初始化受保护的方块类型
     */
    private void initProtectedBlockTypes() {
        // 容器类型
        protectedBlockTypes.add(Material.CHEST);
        protectedBlockTypes.add(Material.TRAPPED_CHEST);
        protectedBlockTypes.add(Material.BARREL);
        protectedBlockTypes.add(Material.ENDER_CHEST);
        
        // 其他容器方块类型
        protectedBlockTypes.add(Material.DISPENSER); // 发射器
        protectedBlockTypes.add(Material.DROPPER);   // 投掷器
        protectedBlockTypes.add(Material.HOPPER);    // 漏斗
        protectedBlockTypes.add(Material.BREWING_STAND); // 酿造台
        protectedBlockTypes.add(Material.FURNACE);  // 熔炉
        protectedBlockTypes.add(Material.BLAST_FURNACE); // 高炉
        protectedBlockTypes.add(Material.SMOKER);   // 烟熏炉
        protectedBlockTypes.add(Material.CRAFTING_TABLE); // 工作台
        protectedBlockTypes.add(Material.LECTERN);  // 讲台
        protectedBlockTypes.add(Material.BEEHIVE);  // 蜂箱
        protectedBlockTypes.add(Material.BEE_NEST); // 蜂巢
        protectedBlockTypes.add(Material.LOOM);     // 织布机
        protectedBlockTypes.add(Material.STONECUTTER); // 切石机
        protectedBlockTypes.add(Material.CARTOGRAPHY_TABLE); // 制图台
        protectedBlockTypes.add(Material.GRINDSTONE); // 砂轮
        protectedBlockTypes.add(Material.FLETCHING_TABLE); // 制箭台
        
        // 潜影盒类型
        for (Material material : Material.values()) {
            if (material.toString().contains("SHULKER_BOX")) {
                protectedBlockTypes.add(material);
            }
        }
        
        // 告示牌类型
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
        protectedBlockTypes.add(Material.DARK_OAK_SIGN);
        protectedBlockTypes.add(Material.DARK_OAK_WALL_SIGN);
        protectedBlockTypes.add(Material.CRIMSON_SIGN);
        protectedBlockTypes.add(Material.CRIMSON_WALL_SIGN);
        protectedBlockTypes.add(Material.WARPED_SIGN);
        protectedBlockTypes.add(Material.WARPED_WALL_SIGN);
        
        // 悬挂式告示牌类型
        try {
            // 尝试添加悬挂式告示牌（兼容较新版本）
            protectedBlockTypes.add(Material.OAK_HANGING_SIGN);
            protectedBlockTypes.add(Material.OAK_WALL_HANGING_SIGN);
            protectedBlockTypes.add(Material.SPRUCE_HANGING_SIGN);
            protectedBlockTypes.add(Material.SPRUCE_WALL_HANGING_SIGN);
            protectedBlockTypes.add(Material.BIRCH_HANGING_SIGN);
            protectedBlockTypes.add(Material.BIRCH_WALL_HANGING_SIGN);
            protectedBlockTypes.add(Material.JUNGLE_HANGING_SIGN);
            protectedBlockTypes.add(Material.JUNGLE_WALL_HANGING_SIGN);
            protectedBlockTypes.add(Material.ACACIA_HANGING_SIGN);
            protectedBlockTypes.add(Material.ACACIA_WALL_HANGING_SIGN);
            protectedBlockTypes.add(Material.DARK_OAK_HANGING_SIGN);
            protectedBlockTypes.add(Material.DARK_OAK_WALL_HANGING_SIGN);
            protectedBlockTypes.add(Material.CRIMSON_HANGING_SIGN);
            protectedBlockTypes.add(Material.CRIMSON_WALL_HANGING_SIGN);
            protectedBlockTypes.add(Material.WARPED_HANGING_SIGN);
            protectedBlockTypes.add(Material.WARPED_WALL_HANGING_SIGN);
        } catch (NoSuchFieldError e) {
            // 如果服务器版本不支持悬挂式告示牌，则忽略错误
            logEvent("当前服务器版本不支持悬挂式告示牌，已跳过添加该类型方块");
        }
        
        // 其他特殊方块
        protectedBlockTypes.add(Material.ENCHANTING_TABLE);
        protectedBlockTypes.add(Material.ANVIL);
        protectedBlockTypes.add(Material.CHIPPED_ANVIL);
        protectedBlockTypes.add(Material.DAMAGED_ANVIL);
        
        try {
            // 尝试添加可能不支持的方块类型
            protectedBlockTypes.add(Material.ARMOR_STAND); // 盔甲架
            // BED类型在某些版本可能不存在，尝试使用床的具体类型
            for (Material material : Material.values()) {
                if (material.toString().contains("BED")) {
                    protectedBlockTypes.add(material);
                }
            }
            protectedBlockTypes.add(Material.SPAWNER);    // 刷怪笼
        } catch (Exception e) {
            logEvent("添加某些特殊方块类型时出错: " + e.getMessage());
        }
    }
    
    /**
     * 加载配置文件中的所有配置项
     */
    private void loadConfigValues() {
        enableProtection = getConfig().getBoolean("enable-protection", true);
        slowInventoryLoad = getConfig().getBoolean("slow-inventory-load", true);
        slowBlockLoad = getConfig().getBoolean("slow-block-load", true);
        itemLoadDelay = getConfig().getInt("item-load-delay", 50);       // 默认50毫秒延迟
        blockLoadDelay = getConfig().getInt("block-load-delay", 20);      // 默认20毫秒延迟
        initialChunkRadius = getConfig().getInt("initial-chunk-radius", 1); // 初始加载1x1区块
        itemsPerLoad = getConfig().getInt("items-per-load", 3);           // 每次加载3个物品
        logLoadEvents = getConfig().getBoolean("log-load-events", false);
        
        // 消息配置
        enableMessages = getConfig().getBoolean("enable-messages", true);
        messageInventoryLoading = ChatColor.translateAlternateColorCodes('&', 
                getConfig().getString("messages.inventory-loading", "&c背包正在加载中，请稍后再拾取物品！"));
        messageContainerLoading = ChatColor.translateAlternateColorCodes('&', 
                getConfig().getString("messages.container-loading", "&c容器正在加载中，请稍后再放入物品！"));
    }
    
    /**
     * 重新加载配置文件并更新配置项
     */
    @Override
    public void reloadConfig() {
        super.reloadConfig();
        loadConfigValues();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();
        
        // 检查是否有绕过权限
        if (player.hasPermission("antiprotocoloverflow.bypass") || !enableProtection) {
            return;
        }
        
        // 初始化玩家状态
        isLoadingInventory.put(playerId, false);
        inventoryLoadProgress.put(playerId, 0);
        loadedChunks.put(playerId, new HashSet<>());
        
        logEvent("玩家 " + player.getName() + " 加入游戏，开始控制区块加载");
        
        // 立即开始缓慢加载背包（不延迟），确保玩家首先看到空背包
        if (slowInventoryLoad) {
            startSlowInventoryLoad(player);
        }
        
        // 注意：区块加载控制已迁移到PacketHandler中，不再需要这里的延迟执行
        // 初始化玩家数据
        if (packetHandler != null) {
            packetHandler.initializePlayer(player);
        }
    }
    
    // 注意：区块加载控制已迁移到PacketHandler中，不再使用此方法
    
    /**
     * 缓慢加载玩家背包物品
     */
    private void startSlowInventoryLoad(final Player player) {
        final UUID playerId = player.getUniqueId();
        final Inventory inventory = player.getInventory();
        final ItemStack[] contents = inventory.getContents().clone();
        
        // 立即标记正在加载
        isLoadingInventory.put(playerId, true);
        inventoryLoadProgress.put(playerId, 0);
        
        logEvent("开始为玩家 " + player.getName() + " 缓慢加载背包物品");
        
        // 在独立任务中清空背包并开始加载，确保清空操作立即执行
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    // 清空玩家背包
                    inventory.clear();
                    logEvent("已清空玩家 " + player.getName() + " 的背包，准备开始逐个加载物品");
                    
                    // 开始逐个加载物品
                    startInventoryItemsLoad(player, contents);
                }
            }
        }.runTask(this); // 立即执行
    }
    
    /**
     * 逐个加载背包物品的方法 - 先加载快捷栏(0-8)，再加载背包其他槽位
     */
    private void startInventoryItemsLoad(final Player player, final ItemStack[] contents) {
        final UUID playerId = player.getUniqueId();
        final Inventory inventory = player.getInventory();
        
        // 创建任务逐个加载物品
        new BukkitRunnable() {
            int phase = 1; // 1: 快捷栏阶段, 2: 背包阶段
            int slot = 0;
            final int HOTBAR_SIZE = 9; // 快捷栏大小
            
            @Override
            public void run() {
                if (!player.isOnline()) {
                    isLoadingInventory.put(playerId, false);
                    logEvent("玩家 " + player.getName() + " 离线，停止加载背包物品");
                    this.cancel();
                    return;
                }
                
                // 根据当前阶段加载不同槽位
                if (phase == 1) {
                    // 加载快捷栏 (0-8)
                    if (slot < HOTBAR_SIZE) {
                        if (slot < contents.length && contents[slot] != null) {
                            inventory.setItem(slot, contents[slot]);
                            logEvent("为玩家 " + player.getName() + " 加载快捷栏物品槽: " + slot);
                        }
                        slot++;
                    } else {
                        // 快捷栏加载完成，进入背包阶段
                        phase = 2;
                        slot = HOTBAR_SIZE; // 从快捷栏之后的槽位开始
                        logEvent("玩家 " + player.getName() + " 快捷栏加载完成，开始加载背包物品");
                    }
                } else if (phase == 2) {
                    // 加载背包其他槽位
                    if (slot < contents.length) {
                        if (contents[slot] != null) {
                            inventory.setItem(slot, contents[slot]);
                            logEvent("为玩家 " + player.getName() + " 加载背包物品槽: " + slot);
                        }
                        slot++;
                    } else {
                        // 所有物品加载完成
                        isLoadingInventory.put(playerId, false);
                        logEvent("玩家 " + player.getName() + " 的背包物品加载完成");
                        this.cancel();
                        return;
                    }
                }
                
                // 更新进度
                inventoryLoadProgress.put(playerId, slot);
            }
        }.runTaskTimer(this, 0L, Math.max(3L, itemLoadDelay / 50L)); // 增加延迟至3tick，使加载过程更明显
    }
    
    // 注意：区块加载事件现在由PacketHandler处理，不再需要这里的处理方法
    // 原onChunkLoad方法已移除
    
    /**
     * 清理玩家数据
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // 清理玩家数据
        isLoadingInventory.remove(playerId);
        inventoryLoadProgress.remove(playerId);
        
        // 清理数据包处理器中的玩家数据
        if (packetHandler != null) {
            packetHandler.clearPlayerData(player);
        }
        
        logEvent("已清理玩家 " + player.getName() + " 的数据");
    }
    
    /**
     * 注意：以下方法已迁移到PacketHandler中，不再在此类中实现
     * - hideProtectedBlocksInChunk
     * - loadProtectedBlocksByDistance
     * - checkAndUpdateAdjacentChests
     * - processProtectedBlocksInChunk
     */
    
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!enableProtection || !slowInventoryLoad) return;
        
        if (event.getPlayer() instanceof Player) {
            final Player player = (Player) event.getPlayer();
            
            // 检查是否有绕过权限
            if (player.hasPermission("antiprotocoloverflow.bypass")) {
                return;
            }
            
            final Inventory inventory = event.getInventory();
            final ItemStack[] contents = inventory.getContents().clone();
            
            // 标记容器正在加载
            isLoadingContainer.put(inventory, true);
            
            // 清空容器
            inventory.clear();
            
            logEvent("玩家 " + player.getName() + " 打开了容器，开始缓慢加载内容");
            
            // 优化的容器物品加载 - 跳过空气格子，批量加载物品
            new BukkitRunnable() {
                int slot = 0;
                int itemsLoadedThisTick = 0;
                
                @Override
                public void run() {
                    // 重置本tick已加载物品数
                    itemsLoadedThisTick = 0;
                    
                    // 循环加载物品，直到达到本次批量加载数量或所有物品加载完成
                    while (slot < contents.length && itemsLoadedThisTick < itemsPerLoad) {
                        // 跳过空气格子，只加载有物品的槽位
                        if (contents[slot] != null) {
                            inventory.setItem(slot, contents[slot]);
                            itemsLoadedThisTick++;
                            logEvent("为玩家 " + player.getName() + " 加载容器物品槽: " + slot);
                        }
                        slot++;
                    }
                    
                    // 检查是否所有物品加载完成
                    if (slot >= contents.length) {
                        // 标记容器加载完成
                        isLoadingContainer.put(inventory, false);
                        logEvent("玩家 " + player.getName() + " 的容器内容加载完成");
                        this.cancel();
                        return;
                    }
                }
            }.runTaskTimer(this, 0L, itemLoadDelay / 50L); // 转换为tick延迟
        }
    }
    
    /**
     * 阻止玩家在背包未加载完成时拾取物品
     */
    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        if (!enableProtection || !slowInventoryLoad) return;
        
        final Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();
        
        // 检查是否有绕过权限
        if (player.hasPermission("antiprotocoloverflow.bypass")) {
            return;
        }
        
        // 检查玩家背包是否正在加载
        if (isLoadingInventory.containsKey(playerId) && isLoadingInventory.get(playerId)) {
            event.setCancelled(true);
            sendMessage(player, messageInventoryLoading);
            logEvent("阻止玩家 " + player.getName() + " 在背包加载时拾取物品");
        }
    }
    
    /**
     * 阻止玩家在容器未加载完成时放入物品，并将物品返还
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enableProtection || !slowInventoryLoad) return;
        
        if (event.getWhoClicked() instanceof Player) {
            final Player player = (Player) event.getWhoClicked();
            
            // 检查是否有绕过权限
            if (player.hasPermission("antiprotocoloverflow.bypass")) {
                return;
            }
            
            final Inventory inventory = event.getInventory();
            final ItemStack cursorItem = event.getCursor();
            
            // 检查容器是否正在加载
            if (isLoadingContainer.containsKey(inventory) && isLoadingContainer.get(inventory)) {
                // 获取点击的槽位和操作类型
                int rawSlot = event.getRawSlot();
                int slot = event.getSlot();
                InventoryAction action = event.getAction();
                
                // 检查是否是尝试放入容器的操作（包括shift点击）
                boolean isPlaceOperation = false;
                
                // 处理普通放置操作
                if (action == InventoryAction.PLACE_ALL || 
                    action == InventoryAction.PLACE_ONE || 
                    action == InventoryAction.PLACE_SOME || 
                    action == InventoryAction.SWAP_WITH_CURSOR) {
                    // 检查是否点击的是容器槽位
                    if (rawSlot < inventory.getSize()) {
                        isPlaceOperation = true;
                    }
                }
                // 特别处理Shift+点击移动（MOVE_TO_OTHER_INVENTORY）
                else if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                    // 对于Shift+点击，从玩家背包移动到容器
                    // 检查点击的是否是玩家背包槽位（会导致物品移动到容器）
                    if (rawSlot >= inventory.getSize()) {
                        isPlaceOperation = true;
                    }
                }
                
                if (isPlaceOperation) {
                    event.setCancelled(true);
                    sendMessage(player, messageContainerLoading);
                    
                    // 确保物品返还到玩家背包
                    if (cursorItem != null && cursorItem.getType() != Material.AIR) {
                        // 尝试将物品放入玩家背包
                        Map<Integer, ItemStack> remaining = player.getInventory().addItem(cursorItem);
                        // 如果背包满了，将物品放在玩家脚下
                        for (ItemStack item : remaining.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), item);
                        }
                        // 清空光标物品
                        event.setCursor(null);
                    }
                    
                    logEvent("阻止玩家 " + player.getName() + " 在容器加载时放入物品");
                }
            }
        }
    }

    /**
     * 记录加载事件到日志
     */
    private void logEvent(String message) {
        if (logLoadEvents) {
            logger.info("[AntiProtocolOverflow] " + message);
        }
    }
    
    /**
     * 发送提示消息给玩家
     */
    private void sendMessage(Player player, String message) {
        if (enableMessages) {
            player.sendMessage(message);
        }
    }
    
    /**
     * 命令处理方法
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("antiprotocol") || cmd.getName().equalsIgnoreCase("ap") || cmd.getName().equalsIgnoreCase("antioverflow")) {
            if (!sender.hasPermission("antiprotocoloverflow.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有权限使用此命令!");
                return true;
            }
            
            if (args.length == 0) {
                sender.sendMessage(ChatColor.GREEN + "=== AntiProtocolOverflow 插件帮助 ===");
                sender.sendMessage(ChatColor.YELLOW + "/antiprotocol reload - 重载插件配置");
                sender.sendMessage(ChatColor.YELLOW + "/antiprotocol version - 查看插件版本");
                sender.sendMessage(ChatColor.YELLOW + "/antiprotocol status - 查看插件状态");
                return true;
            }
            
            if (args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                
                sender.sendMessage(ChatColor.GREEN + "AntiProtocolOverflow 配置已重载!");
                logger.info("AntiProtocolOverflow 配置已由 " + sender.getName() + " 重载");
                return true;
            }
            
            if (args[0].equalsIgnoreCase("version")) {
                sender.sendMessage(ChatColor.GREEN + "AntiProtocolOverflow 版本: " + getDescription().getVersion());
                return true;
            }
            
            if (args[0].equalsIgnoreCase("status")) {
                sender.sendMessage(ChatColor.GREEN + "=== AntiProtocolOverflow 状态 ===");
                sender.sendMessage(ChatColor.YELLOW + "保护模式: " + (enableProtection ? "§a启用" : "§c禁用"));
                sender.sendMessage(ChatColor.YELLOW + "缓慢背包加载: " + (slowInventoryLoad ? "§a启用" : "§c禁用"));
                sender.sendMessage(ChatColor.YELLOW + "缓慢方块加载: " + (slowBlockLoad ? "§a启用" : "§c禁用"));
                sender.sendMessage(ChatColor.YELLOW + "消息提示: " + (enableMessages ? "§a启用" : "§c禁用"));
                sender.sendMessage(ChatColor.YELLOW + "物品加载延迟: " + itemLoadDelay + "ms");
                sender.sendMessage(ChatColor.YELLOW + "方块加载延迟: " + blockLoadDelay + "ms");
                sender.sendMessage(ChatColor.YELLOW + "初始区块半径: " + initialChunkRadius);
                sender.sendMessage(ChatColor.YELLOW + "受保护方块类型数: " + protectedBlockTypes.size());
                return true;
            }
        }
        return false;
    }
    

}