# Recipe Graph

[中文](#中文) | [English](#english)

---

# 中文

将 Applied Energistics 2 的合成样板可视化为聚类依赖图，在游戏内终端屏幕中展示。

## 运行环境

| 依赖 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Applied Energistics 2 | 19.x |
| Java | 21 |

模组版本：**0.4**

## 配置

配置文件位于 `<config>/recipegraph.toml`，也可通过 NeoForge 模组列表 → 配置按钮在游戏内修改：

| 键名 | 类型 | 范围 | 默认值 | 说明 |
|---|---|---|---|---|
| `maxModuleSize` | int | 3–100 | 42 | 单个模块框的最大配方数（标题"(N 项)"的 N）。超出上限的社区被拆分为连续小块；值越大框越少越大，越小越多。 |

读取时自动钳制到声明范围。修改在下次布局时生效（重建或重开终端）。

## 使用方法

右键点击图形终端方块，打开全屏图谱，展示 ME 网络中的所有样板。图谱**从右到左**流动：原料在右，成品在左。

### 交互

| 输入 | 操作 |
|---|---|
| 右键方块 | 打开终端 |
| 右键拖动 | 平移画布 |
| 滚轮 | 缩放（光标为中心） |
| 左键点击连线 | 跳转至下游配方 |
| 左键点击右侧输入端口 | 跳转至该物料的产出配方 |
| 悬停端口 | 高亮所有同物料端口 + 关联边 |
| Ctrl+F | 搜索栏（回车循环匹配，Esc 关闭） |
| 功能按钮（右上角） | 弹出菜单：重建布局 / 切换模块 / 布局模式 / 导出SVG / 导出JSON |

### 状态提示

| 状态码 | 含义 |
|---|---|
| 0 | 正常渲染，标题栏显示样板数 |
| 1 | 未接入 ME 网络——终端无世界连接 |
| 2 | 已接入但无样板——网络存在但无可读样板 |
| 3 | 采集失败——服务端异常，请查看日志 |

后台布局期间显示"正在布局…"。服务端输出 `[GraphTerminal]` 诊断行用于排查。

## 工作原理

```
样板 + 配方
  │  服务端
  ▼
PatternCollector ── 枚举 ICraftingProvider + 合成服务兜底
  │  每个样板 → 配方节点（主产物→左端口，消耗物料→右端口，同名物料连边）
  ▼
Louvain 社区检测 ── 模块度贪心聚类
  │  cluster id 写入节点
  ▼
GraphDataPacket (S→C) ── 序列化（状态 + 节点 + 边 + 端口）
  ▼
HierarchicalLayout (客户端异步) ── 产品锚定分层 + Sugiyama + 正交路由
  ▼
GraphRenderer ── 绘制模块框 / 配方卡片 / 端口 / 连线 + 交互
```

### 样板采集 (PatternCollector)

枚举网格中所有 `ICraftingProvider`（样板供应器、ME 接口、附属设备），与合成服务兜底取并集。每个样板生成一个配方节点：主产物为左侧输出端口，每种消耗物料为右侧输入端口。物料不是全局节点，而是卡片上的局部端口副本。

所有 AEKey（物品、流体、气体等）通过 `AEKey.toTagGeneric()` 序列化为 SNBT，物品保留完整数据组件，因此药水、附魔书、改名物品等 NBT 变体生成独立节点。产物与输入集合相同的配方按签名去重。

### Louvain 社区检测 (LouvainClustering)

基于模块度的贪心聚类（Blondel et al., 2008），**只运行局部移动阶段**：固定随机种子，反复扫描将每个节点移入增益最大的邻居社区，直到收敛。

聚合阶段被刻意省略。正确收缩须将社区内部边权作为超节点自环保留；否则惩罚项逐层衰减导致全图坍缩（实测 605 配方图：145 → 28 → 7 → 2 → **1 社区**），拆分后几乎每块只剩一列互不相关的配方。phase-1 产出约 150 个紧凑社区，正是布局所适合的粒度。

### 层级布局 (HierarchicalLayout)

两层布局：元图（模块框）+ 模块内部（配方卡片）。

1. **模块分组：** 按 Louvain cluster id 分组，未聚类者为单例。模块框严格等于社区，SCC 永不合并（副产品回环会将无关产品线绑在一起）
2. **拆分：** 超过 `maxModuleSize` 的社区按内部流向拆分为连续块；社区内小环原子发射不被切断
3. **产品锚定分层：** layer 0 锚定终产物侧（出度为 0 的配方所在模块），反向 DFS 标记回环边，最长路松弛分配层级——终产物最左、原料最右
4. **Sugiyama 元布局：** 4 次 barycenter 扫描 + 2 次中值松弛 + 逐层重叠消除；回环边走底部轨道不参与分层
5. **模块内部：** 独立列排布，DFS 环消除，barycenter 排序，卡片高度随端口数增长
6. **边路由：** 模块内正向直线/L 形，模块内环走框顶/底，跨模块走框间通道，回路返回走画布底部
7. **归一化：** 平移至正坐标空间

### 渲染 (GraphRenderer)

模块框按 cluster 着色；配方卡片为深色矩形，产品图标居中、名称在顶部，端口从边缘伸出。跨模块连线先绘制（底层），模块内连线后绘制（顶层）。悬停端口高亮同名端口与关联边为琥珀色。

### 导出 (GraphExporter)

- **SVG：** 自包含矢量文件，物品图标内嵌为 base64 PNG，流体用自身颜色染色（非桶）。每张卡片用 `<g><title>` 包裹，悬停显示完整产品名
- **JSON：** 导出节点、边、模块框和边路由

## AE2 网络集成

`GraphTerminalBlockEntity` 实现 `IGridConnectedBlockEntity`：
- 通过 `GridHelper.createManagedNode()` 创建，标志 `REQUIRE_CHANNEL` + `DENSE_CAPACITY`，六面 `DENSE_SMART`
- `setInWorldNode(true)` 必须——否则节点无法连接相邻 AE2 设备
- 通过 MOD 总线 `RegisterCapabilitiesEvent` 注册 `IN_WORLD_GRID_NODE_HOST`——不注册则线缆无法发现终端
- `getGrid()` 在 `connectedSides` 为空时返回 null（孤立节点自建孤岛网格，也报告为已启动）

## 性能

- 样板采集 + 聚类在服务端线程 `RecipeGraph-Collector` 执行：所有 AE2 访问在主线程完成并生成不可变快照，worker 不触碰 AE2 服务
- 布局在客户端线程 `RecipeGraph-Layout` 执行；(图, 布局) 对通过单调序号原子发布
- 碰撞分离用空间哈希网格，迭代次数自适应图大小（`clamp(40 + n/10, 40, 200)`）
- 物品/AEKey/名称按 keyId 缓存；SVG 图集像素按纹理实例缓存（资源重载自动刷新）

---

# English

Visualizes Applied Energistics 2 crafting patterns as a clustered dependency graph in an in-game terminal screen.

## Environment

| Dependency | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Applied Energistics 2 | 19.x |
| Java | 21 |

Mod version: **0.4**

## Configuration

Config file: `<config>/recipegraph.toml`, also editable in-game via NeoForge Mod List → Config:

| Key | Type | Range | Default | Description |
|---|---|---|---|---|
| `maxModuleSize` | int | 3–100 | 42 | Max recipes per module box (the "(N 项)" count). Communities exceeding the cap are split into consecutive chunks; larger = fewer/bigger boxes, smaller = more/smaller. |

Value is clamped on read. Changes take effect on next layout (Rebuild or reopen terminal).

## Usage

Right-click the Graph Terminal block to open a fullscreen graph of all patterns on the connected ME network. The graph flows **right-to-left**: raw materials on the right, finished products on the left.

### Interaction

| Input | Action |
|---|---|
| Right-click block | Open terminal |
| Right-drag | Pan canvas |
| Scroll | Zoom (cursor-centered) |
| Left-click edge | Jump to downstream recipe |
| Left-click right-side input port | Jump to that material's producer recipe |
| Hover port | Highlight all same-material ports + linked edges |
| Ctrl+F | Search bar (Enter to cycle, Esc to close) |
| Functions button (top-right) | Popup menu: Rebuild / Toggle Modules / Layout Mode / Export SVG / Export JSON |

### Status Display

| Status | Meaning |
|---|---|
| 0 | Normal render, pattern count in header |
| 1 | Not attached to ME network — no in-world connections |
| 2 | Attached but no patterns — network exists, nothing readable |
| 3 | Collection failed — server error, check logs |

Shows "laying out…" while background layout runs. Server logs a `[GraphTerminal]` diagnostic line.

## How It Works

```
Patterns + Recipes
  │  Server
  ▼
PatternCollector ── enumerate ICraftingProvider + crafting-service fallback
  │  each pattern → recipe node (primary output→LEFT port, consumed materials→RIGHT ports, shared keyId links edges)
  ▼
Louvain Community Detection ── modularity greedy clustering
  │  cluster id written to node
  ▼
GraphDataPacket (S→C) ── serialize (status + nodes + edges + ports)
  ▼
HierarchicalLayout (client async) ── product-anchored layering + Sugiyama + orthogonal routing
  ▼
GraphRenderer ── draw modules / cards / ports / edges + interaction
```

### Pattern Collection (PatternCollector)

Enumerates all `ICraftingProvider` nodes (Pattern Providers, ME Interfaces, addon devices), unioned with crafting-service fallback. Each pattern becomes a recipe node: primary output → LEFT output port, each consumed material → RIGHT input port. Materials are local port copies, not global nodes.

All AEKeys are serialized via `AEKey.toTagGeneric()` as SNBT with full data components — potions, enchanted books, renamed items get distinct nodes. `dropSecondary()` is not used. Duplicate recipes (same product + input set) are deduplicated by signature.

### Louvain Clustering (LouvainClustering)

Modularity-based greedy clustering (Blondel et al., 2008). **Local-moving phase only**: fixed-seed random-order sweeps move each node into the best-gain neighbor community until convergence.

The aggregation phase is intentionally skipped. Correct contraction requires intra-community edge weights as super-node self-loops; without them the penalty decays and the graph collapses (measured: 145 → 28 → 7 → 2 → **1 community** on a 605-recipe graph), producing single-column chunks of unrelated recipes. Phase-1 yields ~150 small coherent communities — the right granularity for the layout.

### Hierarchical Layout (HierarchicalLayout)

Two layers: meta-graph (module boxes) + module internals (recipe cards).

1. **Module grouping:** by Louvain cluster id; unclustered nodes are singletons. Boxes are exactly communities — SCC merging is never done (byproduct loops tie unrelated product lines together)
2. **Splitting:** communities exceeding `maxModuleSize` split into consecutive chunks; small internal rings emit atomically
3. **Product-anchored layering:** layer 0 anchors on final products (out-degree 0 recipes), reverse DFS flags loop edges, longest-path relaxation assigns layers — products far left, raw materials right
4. **Sugiyama meta-layout:** 4 barycenter sweeps + 2 median passes + per-layer overlap resolution; loop edges route as bottom rails, excluded from layering
5. **Module internals:** independent column ranking, DFS cycle removal, barycenter ordering, card height scales with port count
6. **Edge routing:** intra-module forward (straight/L-shaped), intra-module cycles (box top/bottom rail), cross-module (inter-box channel), loop returns (canvas bottom rail)
7. **Normalisation:** shift to positive coordinate space

### Rendering (GraphRenderer)

Module boxes colored by cluster; recipe cards with product icon centered, name on top, ports extending from edges. Cross-module edges drawn first (bottom), intra-module on top. Hovering a port highlights same-keyId ports and linked edges in amber.

### Export (GraphExporter)

- **SVG:** self-contained vector file, item icons embedded as base64 PNG, fluids tinted with own color (not buckets). Each card wrapped in `<g><title>` for hover tooltips
- **JSON:** nodes (with localized names), edges, module boxes, edge routes

## AE2 Network Integration

`GraphTerminalBlockEntity` implements `IGridConnectedBlockEntity`:
- Created via `GridHelper.createManagedNode()` with `REQUIRE_CHANNEL` + `DENSE_CAPACITY`, `DENSE_SMART` on all faces
- `setInWorldNode(true)` is mandatory — otherwise the node cannot connect to adjacent devices
- `IN_WORLD_GRID_NODE_HOST` registered via `RegisterCapabilitiesEvent` on MOD bus — without it cables cannot discover the terminal
- `getGrid()` returns null when `connectedSides` is empty (isolated node forms its own island grid that also reports as booted)

## Performance

- Collection + clustering on server thread `RecipeGraph-Collector`: all AE2 access on main thread, worker receives immutable snapshot only
- Layout on client thread `RecipeGraph-Layout`: (graph, layout) pair published atomically via monotonic sequence number
- Collision separation via spatial-hash grid, pass count adaptive (`clamp(40 + n/10, 40, 200)`)
- Item/AEKey/name resolution cached per keyId; SVG atlas pixels cached per texture (auto-refreshed on reload)
