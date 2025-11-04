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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
    // 注意：容器加载状态现在由PacketHandler管理，不再需要这里的跟踪
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
                // 更新数据包处理器中的配置
        packetHandler.updateConfig(itemLoadDelay, itemsPerLoad, blockLoadDelay, 50, 
                                  logLoadEvents, enableProtection, slowInventoryLoad, slowBlockLoad);
        logger.info("成功初始化ProtocolLib支持，使用数据包层处理方块和物品");
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
        logger.info("物品处理已迁移到数据包层面，避免物品丢失风险");
        logger.info("容器和背包物品现在通过WINDOW_ITEMS和SET_SLOT数据包进行延迟加载");
    }

    @Override
    public void onDisable() {
        // 清理数据包处理器
        if (packetHandler != null) {
            packetHandler.clearAllData();
            packetHandler.unregister();
            packetHandler = null;
        }
        
        // 清理本类中的数据结构
        isLoadingInventory.clear();
        inventoryLoadProgress.clear();
        loadedChunks.clear();
        
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
        
        // 重新初始化保护方块类型
        protectedBlockTypes.clear();
        initProtectedBlockTypes();
        
        // 更新数据包处理器中的配置
        if (packetHandler != null) {
            packetHandler.updateConfig(itemLoadDelay, itemsPerLoad, blockLoadDelay, 50, 
                                      logLoadEvents, enableProtection, slowInventoryLoad, slowBlockLoad);
            logger.info("已更新PacketHandler中的物品加载配置");
        }
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
        
        // 初始化玩家数据并启用数据包层面的物品处理
        if (packetHandler != null) {
            packetHandler.initializePlayer(player);
            
            // 通过数据包处理器启用缓慢加载背包
            if (slowInventoryLoad) {
                packetHandler.markInventoryLoading(player, true);
                logEvent("为玩家 " + player.getName() + " 启用数据包层面的背包缓慢加载");
            }
        }
        
        // 注意：不再直接调用startSlowInventoryLoad，因为现在由PacketHandler通过数据包层面处理
    }
    
    // 注意：区块加载控制已迁移到PacketHandler中，不再使用此方法
    
    /**
     * 缓慢加载玩家背包物品 - 现在通过数据包层面处理
     */
    private void startSlowInventoryLoad(final Player player) {
        final UUID playerId = player.getUniqueId();
        
        // 标记正在加载
        isLoadingInventory.put(playerId, true);
        inventoryLoadProgress.put(playerId, 0);
        
        logEvent("通过数据包层面为玩家 " + player.getName() + " 缓慢加载背包物品");
        
        // 注意：不再直接操作Inventory，而是通过PacketHandler在数据包层面处理
        // 无需清空背包或手动加载物品，PacketHandler会在WINDOW_ITEMS数据包中处理
    }
    
    /**
     * 检查物品是否为潜影盒
     */
    private boolean isShulkerBox(ItemStack item) {
        if (item == null) return false;
        return item.getType().toString().contains("SHULKER_BOX");
    }
    
    /**
     * 检查潜影盒是否为空
     */
    private boolean isEmptyShulkerBox(ItemStack shulkerBox) {
        if (shulkerBox == null || !isShulkerBox(shulkerBox)) return true;
        ItemMeta meta = shulkerBox.getItemMeta();
        if (meta instanceof BlockStateMeta) {
            BlockStateMeta blockStateMeta = (BlockStateMeta) meta;
            if (blockStateMeta.hasBlockState()) {
                BlockState blockState = blockStateMeta.getBlockState();
                if (blockState instanceof ShulkerBox) {
                    ShulkerBox shulkerBoxState = (ShulkerBox) blockState;
                    return shulkerBoxState.getInventory().isEmpty();
                }
            }
        }
        return true;
    }
    
    /**
     * 获取空的潜影盒（保留原始类型）
     */
    private ItemStack getEmptyShulkerBox(ItemStack originalShulkerBox) {
        if (originalShulkerBox == null || !isShulkerBox(originalShulkerBox)) return null;
        
        ItemStack emptyShulkerBox = new ItemStack(originalShulkerBox.getType());
        // 复制名称和自定义数据，但不复制内容
        ItemMeta meta = originalShulkerBox.getItemMeta();
        if (meta != null) {
            ItemMeta emptyMeta = emptyShulkerBox.getItemMeta();
            if (meta.hasDisplayName()) {
                emptyMeta.setDisplayName(meta.getDisplayName());
            }
            if (meta.hasLore()) {
                emptyMeta.setLore(meta.getLore());
            }
            emptyShulkerBox.setItemMeta(emptyMeta);
        }
        return emptyShulkerBox;
    }
    
    /**
     * 加载背包物品的方法 - 一次性加载普通物品，对潜影盒进行特殊处理
     */
    private void startInventoryItemsLoad(final Player player, final ItemStack[] contents) {
        final UUID playerId = player.getUniqueId();
        final Inventory inventory = player.getInventory();
        final int HOTBAR_SIZE = 9; // 快捷栏大小
        
        // 用于跟踪需要加载内容的潜影盒
        final Set<Integer> shulkerBoxSlots = new HashSet<>();
        
        // 第一阶段：先加载快捷栏
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (contents[i] != null && contents[i].getType() != Material.AIR) {
                if (isShulkerBox(contents[i])) {
                    // 对于潜影盒，如果不为空，则先发送空的潜影盒
                    if (!isEmptyShulkerBox(contents[i])) {
                        shulkerBoxSlots.add(i);
                        ItemStack emptyShulkerBox = getEmptyShulkerBox(contents[i]);
                        if (emptyShulkerBox != null) {
                            inventory.setItem(i, emptyShulkerBox);
                            if (logLoadEvents) {
                                logEvent("为玩家 " + player.getName() + " 放置背包快捷栏空潜影盒槽: " + i);
                            }
                        }
                    } else {
                        // 空潜影盒直接发送
                        inventory.setItem(i, contents[i]);
                        if (logLoadEvents) {
                            logEvent("为玩家 " + player.getName() + " 放置背包快捷栏空潜影盒槽: " + i);
                        }
                    }
                } else {
                    // 普通物品立即加载
                    inventory.setItem(i, contents[i]);
                    if (logLoadEvents) {
                        logEvent("为玩家 " + player.getName() + " 放置背包快捷栏普通物品槽: " + i);
                    }
                }
            }
        }
        
        logEvent("玩家 " + player.getName() + " 快捷栏物品加载完成");
        
        // 第二阶段：加载背包其他槽位
        for (int i = HOTBAR_SIZE; i < contents.length; i++) {
            if (contents[i] != null && contents[i].getType() != Material.AIR) {
                if (isShulkerBox(contents[i])) {
                    // 对于潜影盒，如果不为空，则先发送空的潜影盒
                    if (!isEmptyShulkerBox(contents[i])) {
                        shulkerBoxSlots.add(i);
                        ItemStack emptyShulkerBox = getEmptyShulkerBox(contents[i]);
                        if (emptyShulkerBox != null) {
                            inventory.setItem(i, emptyShulkerBox);
                            if (logLoadEvents) {
                                logEvent("为玩家 " + player.getName() + " 放置背包空潜影盒槽: " + i);
                            }
                        }
                    } else {
                        // 空潜影盒直接发送
                        inventory.setItem(i, contents[i]);
                        if (logLoadEvents) {
                            logEvent("为玩家 " + player.getName() + " 放置背包空潜影盒槽: " + i);
                        }
                    }
                } else {
                    // 普通物品立即加载
                    inventory.setItem(i, contents[i]);
                    if (logLoadEvents) {
                        logEvent("为玩家 " + player.getName() + " 放置背包普通物品槽: " + i);
                    }
                }
            }
        }
        
        logEvent("玩家 " + player.getName() + " 背包普通物品已加载完成");
        
        // 如果有潜影盒需要加载内容，则使用定时任务逐个加载
        if (!shulkerBoxSlots.isEmpty()) {
            logEvent("玩家 " + player.getName() + " 准备加载背包潜影盒内容");
            
            final Iterator<Integer> shulkerIterator = shulkerBoxSlots.iterator();
            
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!player.isOnline()) {
                        isLoadingInventory.put(playerId, false);
                        logEvent("玩家 " + player.getName() + " 离线，停止加载背包潜影盒内容");
                        this.cancel();
                        return;
                    }
                    
                    // 每次处理一定数量的潜影盒
                    int processedThisTick = 0;
                    
                    while (shulkerIterator.hasNext() && processedThisTick < itemsPerLoad) {
                        int slot = shulkerIterator.next();
                        inventory.setItem(slot, contents[slot]);
                        processedThisTick++;
                        shulkerIterator.remove();
                        if (logLoadEvents) {
                            logEvent("为玩家 " + player.getName() + " 加载背包潜影盒内容槽: " + slot);
                        }
                    }
                    
                    // 检查是否所有潜影盒内容加载完成
                    if (!shulkerIterator.hasNext()) {
                        // 标记背包加载完成
                        isLoadingInventory.put(playerId, false);
                        logEvent("玩家 " + player.getName() + " 的背包物品加载完成，包括所有潜影盒内容");
                        this.cancel();
                    }
                }
            }.runTaskTimer(this, Math.max(3L, itemLoadDelay / 50L), Math.max(3L, itemLoadDelay / 50L)); // 增加延迟至3tick
        } else {
            // 如果没有潜影盒，直接标记加载完成
            isLoadingInventory.put(playerId, false);
            logEvent("玩家 " + player.getName() + " 的背包物品加载完成，没有潜影盒需要特殊处理");
        }
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
            
            // 注意：不再直接操作服务器端Inventory对象
            // 所有物品加载逻辑已移至PacketHandler类中通过数据包层面处理
            
            logEvent("玩家 " + player.getName() + " 打开了容器，数据包层面开始处理物品加载");
            
            // 无需清空容器或设置物品，这些都由PacketHandler在数据包层面处理
            // PacketHandler会监听OPEN_WINDOW和WINDOW_ITEMS数据包来实现延迟加载
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
        
        // 检查玩家背包是否正在加载 - 使用本地变量和PacketHandler结合判断
            boolean isLoading = Boolean.TRUE.equals(isLoadingInventory.getOrDefault(playerId, false));
        
        if (isLoading) {
            event.setCancelled(true);
            sendMessage(player, messageInventoryLoading);
            logEvent("阻止玩家 " + player.getName() + " 在背包加载时拾取物品");
        }
    }
    
    /**
     * 阻止玩家在背包未加载完成时操作物品栏
     * 注意：容器加载状态现在由PacketHandler在数据包层面管理
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enableProtection || !slowInventoryLoad) return;
        
        if (event.getWhoClicked() instanceof Player) {
            final Player player = (Player) event.getWhoClicked();
            final UUID playerId = player.getUniqueId();
            
            // 检查是否有绕过权限
            if (player.hasPermission("antiprotocoloverflow.bypass")) {
                return;
            }
            
            final Inventory inventory = event.getInventory();
            final ItemStack cursorItem = event.getCursor();
            int rawSlot = event.getRawSlot();
            
            // 检查玩家背包是否正在加载
            boolean isLoading = false;
            if (packetHandler != null) {
                // 优先使用PacketHandler中的状态
                isLoading = Boolean.TRUE.equals(this.isLoadingInventory.getOrDefault(playerId, false));
            }
            
            if (isLoading) {
                // 检查是否点击的是玩家自己的物品栏（包括主手、副手、防具栏）
                if (inventory.equals(player.getInventory())) {
                    event.setCancelled(true);
                    sendMessage(player, messageInventoryLoading);
                    logEvent("阻止玩家 " + player.getName() + " 在背包加载时操作物品栏，槽位: " + rawSlot);
                    
                    // 确保物品返还
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
                    return; // 直接返回，避免后续检查
                }
            }
            
            // 注意：容器加载状态现在由PacketHandler在数据包层面管理
            // 不再需要直接检查isLoadingContainer
            // 但保留基本的操作限制以确保游戏体验一致
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