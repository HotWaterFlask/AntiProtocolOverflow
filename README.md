# AntiProtocolOverflow 插件

一个用于防止Minecraft服务器中协议溢出攻击的插件，通过控制区块和物品加载过程来保护玩家免受禁人书盒、禁人塔等恶意结构的影响。

## 功能特点

- **玩家加入时的区块加载控制**：玩家加入后只加载初始区块，周围区块逐步加载
- **背包物品缓慢加载**：玩家背包物品一个接一个地显示，而不是一次性全部显示
- **容器和告示牌逐个加载**：区块内的容器和告示牌等特殊方块缓慢显示，防止协议溢出
- **容器打开时的内容缓慢加载**：玩家打开容器时，物品内容逐一显示
- **完整的配置选项**：可调整加载延迟、初始区块范围等参数
- **权限系统**：支持管理员权限和绕过保护权限
- **状态查看命令**：实时查看插件运行状态

## 工作原理

该插件通过以下方式防止协议溢出攻击：

1. **渐进式区块加载**：玩家加入时只加载其所在区块，然后逐步加载周围区块，防止一次加载过多数据
2. **背包加载控制**：玩家背包物品一个接一个地显示，而不是一次性全部显示，减轻客户端处理压力
3. **特殊方块延迟加载**：容器（箱子、潜影盒等）和告示牌等特殊方块在区块加载后延迟逐个显示
4. **容器内容延迟加载**：打开容器时，物品内容逐步显示，避免一次发送过多物品数据

这种方法不同于传统的禁止建造方式，它允许玩家建造各种结构，同时通过控制加载过程来防止客户端被大量数据淹没，从而有效防止协议溢出攻击。

## 命令

- `/antiprotocol reload` - 重载插件配置
- `/antiprotocol version` - 查看插件版本
- `/antiprotocol status` - 查看插件运行状态
- `/ap` - 命令别名，与/antiprotocol功能相同
- `/antioverflow` - 命令别名，与/antiprotocol功能相同

## 权限

- `antiprotocoloverflow.admin` - 允许使用管理员命令
- `antiprotocoloverflow.bypass` - 允许绕过所有保护机制，获得此权限的玩家不会受到加载控制影响

## 配置说明

配置文件位于 `plugins/AntiProtocolOverflow/config.yml`，包含以下选项：

```yaml
# 全局保护开关
enable-protection: true

# 缓慢加载设置
slow-inventory-load: true
slow-block-load: true

# 加载延迟设置（毫秒）
item-load-delay: 50        # 物品加载延迟，值越小加载越快，值越大加载越慢
block-load-delay: 20       # 方块加载延迟，值越小加载越快，值越大加载越慢

# 初始区块加载半径
initial-chunk-radius: 1    # 玩家加入时初始加载的区块半径

# 日志记录
log-load-events: false     # 是否记录加载事件到控制台
```

## 安装方法

1. 编译或下载AntiProtocolOverflow.jar文件
2. 将jar文件放入服务器的plugins文件夹
3. 重启服务器，插件将自动生成默认配置
4. 根据需要修改plugins/AntiProtocolOverflow/config.yml配置文件
5. 使用 `/antiprotocol reload` 命令重载配置

## 构建指南

确保安装了Java JDK和Maven，然后运行：

```bash
mvn clean package
```

或者使用提供的build.bat脚本（Windows）：

```bash
build.bat
```

## 性能优化

- 对于高性能服务器，可以尝试减小加载延迟值
- 对于低性能服务器，建议增大加载延迟值
- 可以根据服务器玩家数量调整初始区块加载半径
- 可以选择性地启用或禁用缓慢加载功能

## 常见问题

### 如何调整加载速度？
调整 `item-load-delay` 和 `block-load-delay` 参数，值越小加载越快，值越大加载越慢。

### 如何允许管理员不受限制？
给管理员玩家分配 `antiprotocoloverflow.bypass` 权限。

### 如何暂时禁用插件功能？
在配置文件中将 `enable-protection` 设置为 `false`，然后使用重载命令。

## 版本支持

支持Minecraft 1.13及以上版本的Spigot/Paper服务器。