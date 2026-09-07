# Recipe Graph

[English](#english) | [中文](#中文)

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

## How It Works

Right-click the Graph Terminal block to open a fullscreen graph of all patterns on the connected ME network. The graph flows **right-to-left**: raw materials on the right, finished products on the left.

### Data Pipeline

```
Patterns + Game Recipes
    |
    v  Server-side
+-----------------------------------+
|  PatternCollector                 |
|  Enumerate all ICraftingProvider  |
|  + craftingService fallback       |
|  -> each pattern = recipe node    |
|  -> primary output -> LEFT port   |
|  -> consumed materials -> RIGHT   |
|  -> link edges by shared keyId   |
+---------------+-------------------+
                v
+-----------------------------------+
|  Louvain Community Detection      |
|  Modularity-based greedy optim.   |
|  -> cluster id written to node    |
+---------------+-------------------+
                v  Serialize
+-----------------------------------+
|  GraphDataPacket (S->C)           |
|  status + patterns + nodes       |
|  + edges + ports(keyId/label)     |
+---------------+-------------------+
                v  Client-side
+-----------------------------------+
|  HierarchicalLayout (async)       |
|  SCC condense -> DAG longest path |
|  -> Sugiyama + orthogonal routing |
+---------------+-------------------+
                v
+-----------------------------------+
|  GraphRenderer                    |
|  Draw modules / cards / ports     |
|  + interaction (hover/click/find) |
+-----------------------------------+
```

## Algorithms

### 1. Pattern Collection (PatternCollector.java)

Enumerates all `IPatternDetails` from every `ICraftingProvider` node in the grid (vanilla Pattern Providers, ME Interfaces, addon devices), unioned with the crafting-service fallback path.

**Graph model:**
- Each pattern -> one recipe node (`recipe:N`)
- Primary output (`getPrimaryOutput()`) -> one LEFT output port
- Each distinct consumed material -> one RIGHT input port
- Materials are **not** global nodes — each port is a local copy on its recipe card
- Edge `A->B` when recipe A outputs material X and recipe B consumes X, carrying X's keyId

**Material identity:** All AEKeys (items, fluids, gases, etc.) are serialized via `AEKey.toTagGeneric()` as SNBT. For items this includes the full data-component map, so NBT variants (potions, enchanted books, renamed items) get distinct nodes. `dropSecondary()` is NOT used — it strips all components from AEItemKey.

**Dedup:** Recipes with identical product + input material set are deduplicated by signature.

### 2. Louvain Community Detection (LouvainClustering.java)

Greedy modularity-based clustering (Blondel et al., 2008). Treats the graph as undirected/weighted. Alternates:
1. **Local phase:** for each node, move to the neighbor community giving the best modularity gain
2. **Aggregation phase:** contract communities into single nodes, repeat

Result: cluster id assigned to each `GraphNode`, defining business modules.

### 3. Hierarchical Layout (HierarchicalLayout.java)

Two-layer layout: meta-graph (modules) + module internals (recipe cards).

**Step 1 — Module boxes:** Group nodes by Louvain cluster id; unclustered nodes become singletons.

**Step 2 — SCC condensation (adaptive cap):**
- Tarjan SCC on the directed module graph
- Small graphs (<=8 modules / <=60 nodes): every SCC merges fully -> meta-graph is a guaranteed DAG
- Large graphs: merge cap scales with sqrt(graph size); small/medium SCCs merge into super modules, but a mega-SCC that would produce an unreadably huge box stays apart (its back edges route via bottom rails)

**Step 3 — Dynamic longest-path layering:**
- Kahn topological propagation on the DAG
- layer 0 = raw materials (in-degree 0, rightmost), layer(v) = max(layer(u)) + 1
- Layer count is entirely data-driven — no hard-coded limits
- Flow direction: RIGHT_TO_LEFT (layer 0 on right, higher layers on left)

**Step 4 — Sugiyama meta-layout:**
- Dummy nodes for edges spanning >1 layer
- 4 barycenter sweeps (alternating down/up) for Y ordering
- 2 median relaxation passes
- Per-layer overlap resolution (sequential push-down with ROW_GAP spacing)
- X positions: accumulate widths in flow order, mirror to put layer 0 on the right

**Step 5 — Module internals:**
- Each box gets an independent longest-path column ranking (does NOT inherit external meta layer)
- Internal cycle removal via DFS gray-marking; back edges route along box top/bottom border
- Barycenter ordering within columns (2 forward + 1 backward sweep)
- Card height scales with port count; cards stack cumulatively per column

**Step 6 — Edge routing:**
- **Intra-module forward:** straight or L-shaped orthogonal segments (output LEFT port -> input RIGHT port)
- **Intra-module cycle (backward in RTL):** routes along box's top/bottom internal rail, never leaves the box
- **Cross-module normal:** through vertical channel right of target module, one track per edge (alternating +/-offset) to avoid overlap
- **Cross-module back edge (defensive):** bottom rail around canvas (only fires for un-merged mega SCCs)

**Step 7 — Normalisation:** Shift everything into positive coordinate space with MARGIN padding.

### 4. Rendering (GraphRenderer.java)

- **Module boxes:** colored rectangles with title strip, colored outline by cluster id
- **Recipe cards:** dark cards with product icon (center) + product name (top); left output port + right input ports as icon+label chips extending from card edges
- **Item icons:** `AEItemKey.toStack()` rebuilds full ItemStack with components; rendered via vanilla `GuiGraphics.renderItem`
- **Fluid/gas icons:** `AEKeyRendering.drawInGui()` — AE2's registered render handlers (each addon provides its own)
- **Names:** `key.getDisplayName()` for localized material names
- **Edges:** orthogonal polylines, bright cyan (intra-module) or dark grey (cross-module), arrowheads at segment ends
- **Hover:** hovering a port highlights all same-keyId ports + linked edges in bright amber; everything else dims
- **Click:** left-click an edge -> camera jumps to downstream node; left-click a right-side input port -> jumps to that material's producer recipe
- **Search:** Ctrl+F opens a search bar; Enter matches by product/material name and cycles through results

### 5. Export (GraphExporter.java)

- **SVG:** self-contained vector file with module rectangles + orthogonal edge routes + item icons embedded as base64 PNG (sampled from GPU texture atlases; fluids tinted with their own color, never rendered as buckets)
- **JSON:** dumps nodes (with resolved localized names), edges, module boxes, and edge routes

## AE2 Network Integration

### GraphTerminalBlockEntity

Implements `IGridConnectedBlockEntity`:
- **Grid node:** created via `GridHelper.createManagedNode()` with flags `REQUIRE_CHANNEL` + `DENSE_CAPACITY`
- **In-world node:** `setInWorldNode(true)` is critical — without it, `ManagedGridNode` creates a plain `GridNode` whose `findInWorldConnections()` is empty; the node can never connect to adjacent AE2 devices
- **Cable type:** `DENSE_SMART` on all six faces
- **Capability registration:** `AECapabilities.IN_WORLD_GRID_NODE_HOST` registered via `RegisterCapabilitiesEvent` on the MOD bus — without this, AE2 cables cannot discover the terminal
- **Attachment check:** `getGrid()` returns null when `connectedSides` is empty (isolated node forms its own single-node island grid that also reports as booted, so a non-null booted grid does NOT mean the terminal is wired to anything)

### PatternCollector

Enumerates `ICraftingProvider` directly from `grid.getNodes()` (node service or owner) to cover vanilla Pattern Providers, ME Interfaces, and addon-mod provider devices. The `getCraftables` -> `getCraftingFor` crafting-service path is kept as a unioned fallback.

## Interaction

| Input | Action |
|---|---|
| Right-click block | Open terminal |
| Right-drag | Pan canvas |
| Scroll | Zoom (cursor-centered) |
| Left-click edge | Jump to downstream recipe |
| Left-click right-side input port | Jump to that material's producer recipe |
| Hover port | Highlight all same-material ports + linked edges |
| Ctrl+F | Open search bar (Enter to cycle matches, Esc to close) |
| Buttons (top-right, vertical) | Rebuild layout / Toggle module display / Export SVG / Export JSON |

## Status Display

The screen shows distinct messages based on `GraphDataPacket.status`:
- **STATUS_OK (0):** Graph rendered normally; pattern count shown in header
- **STATUS_NO_NETWORK (1):** "Not attached to an ME network" — terminal has no in-world connections
- **STATUS_NO_PATTERNS (2):** "Attached but no readable patterns" — network exists but no patterns found

Server logs a `[GraphTerminal]` diagnostic line with node/grid/booted/active/channels/providers/craftables/patterns for troubleshooting.

## Screen Architecture

`GraphTerminalScreen` extends `Screen` (not `AbstractContainerScreen`) to prevent JEI and FTB Library sidebar buttons from overlaying the fullscreen graph. The paired `GraphTerminalMenu` is managed manually with vanilla lifecycle mirroring (`onClose` -> `player.closeContainer()`, `removed` -> `menu.removed()`).

## Performance

- Layout runs asynchronously on the client thread (server only collects patterns + clusters)
- Synchronous layout with 200 iterations caused server tick blocking; reduced to 50 iterations with async execution
- Orthogonal edge segments draw as single `fill()` calls (main frame-time optimization)
- Stack/key/name resolution is cached per material keyId
- Node collision detection uses 52px half-width / 20px half-height AABB boxes (16px icon + Chinese labels)
- Cluster bounding boxes use 68px padX / 48px padY spacing to prevent overlap

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

## 工作原理

右键点击图形终端方块，打开全屏图谱，展示所连接的 ME 网络中的所有样板。图谱流向为**从右到左**：原料在右侧，成品在左侧。

### 数据流水线

```
原始样板 + 游戏配方
    |
    v  服务端
+-----------------------------------+
|  PatternCollector                 |
|  枚举网格中所有 ICraftingProvider  |
|  + craftingService 兜底           |
|  -> 每个样板 = 一个配方节点       |
|  -> 主产物 -> 左输出端口          |
|  -> 消耗物料 -> 右输入端口        |
|  -> 同名物料连边 producer->consumer|
+---------------+-------------------+
                v
+-----------------------------------+
|  Louvain 社区检测                  |
|  模块度贪心优化，将节点分入社区     |
|  -> cluster id 写入 GraphNode     |
+---------------+-------------------+
                v  序列化
+-----------------------------------+
|  GraphDataPacket (S->C)           |
|  status + patterns + nodes       |
|  + edges + ports(keyId/label)     |
+---------------+-------------------+
                v  客户端
+-----------------------------------+
|  HierarchicalLayout (异步)        |
|  SCC缩点 -> DAG最长路径分层        |
|  -> Sugiyama + 正交路由           |
+---------------+-------------------+
                v
+-----------------------------------+
|  GraphRenderer                    |
|  绘制模块框/配方卡片/端口/连线     |
|  + 交互(悬停高亮/点击跳转/搜索)    |
+-----------------------------------+
```

## 算法

### 1. 样板采集 (PatternCollector.java)

枚举网格中每个 `ICraftingProvider` 节点的所有 `IPatternDetails`（原版样板供应器、ME接口、附属模组设备），与合成服务兜底路径取并集。

**图模型：**
- 每个样板 -> 一个配方节点（`recipe:N`）
- 主产物（`getPrimaryOutput()`）-> 一个左侧输出端口
- 每种不同消耗物料 -> 一个右侧输入端口
- 物料**不是**全局节点——每个端口是配方卡片上的局部副本
- 当配方 A 产出物料 X 且配方 B 消耗 X 时，创建边 `A->B`，携带 X 的 keyId

**物料身份：** 所有 AEKey（物品、流体、气体等）通过 `AEKey.toTagGeneric()` 序列化为 SNBT。对于物品，此标签包含完整数据组件 map，因此 NBT 变体（药水、附魔书、改名物品）会生成独立节点。不使用 `dropSecondary()`——它会剥离 AEItemKey 的所有组件。

**去重：** 产物和输入物料集合完全相同的配方按签名去重。

### 2. Louvain 社区检测 (LouvainClustering.java)

基于模块度的贪心聚类算法（Blondel et al., 2008）。将图视为无向加权图，交替执行：
1. **局部阶段：** 对每个节点，将其移到模块度增益最大的邻居社区
2. **聚合阶段：** 将社区收缩为单个节点，重复

结果：每个 `GraphNode` 被分配 cluster id，定义业务模块。

### 3. 层级布局 (HierarchicalLayout.java)

两层布局：元图（模块）+ 模块内部（配方卡片）。

**步骤 1 — 模块分组：** 按 Louvain cluster id 分组；未聚类的节点成为单例。

**步骤 2 — SCC 缩点（自适应上限）：**
- 对有向模块图执行 Tarjan SCC
- 小图（≤8模块 / ≤60节点）：所有 SCC 全量合并 -> 元图保证为 DAG
- 大图：合并上限随 √(图规模) 增长；中小 SCC 合并为超级组件，但会产生不可读巨型方框的 SCC 保持独立（其回边走底部轨道绕行）

**步骤 3 — 动态最长路径分层：**
- 在 DAG 上执行 Kahn 拓扑传播
- layer 0 = 原料（入度为0，最右侧），layer(v) = max(layer(u)) + 1
- 层数完全由数据驱动——无硬编码限制
- 流向：RIGHT_TO_LEFT（layer 0 在右，更高层在左）

**步骤 4 — Sugiyama 元布局：**
- 跨越多层的边插入虚拟节点
- 4 次 barycenter 扫描（下行/上行交替）用于 Y 排序
- 2 次中值松弛遍历
- 逐层重叠消除（顺序下推，ROW_GAP 间距）
- X 坐标：按流向累加宽度，镜像使 layer 0 位于右侧

**步骤 5 — 模块内部布局：**
- 每个方框拥有独立的最长路径列排布（不继承外部元图层级）
- 内部环消除通过 DFS 灰色标记；回边沿方框顶/底边走线
- 列内 barycenter 排序（2 次正向 + 1 次反向扫描）
- 卡片高度随端口数增长；卡片按列累积堆叠

**步骤 6 — 边路由：**
- **模块内正向：** 直线或 L 形正交线段（左输出端口 -> 右输入端口）
- **模块内环（RTL 反向）：** 沿方框顶/底内轨走线，不出框
- **跨模块正向：** 经目标模块右侧垂直通道，每边独立轨道（交替 ±偏移）避免重叠
- **跨模块回边（防御性）：** 画布底部轨道绕行（仅对未合并的巨型 SCC 触发）

**步骤 7 — 归一化：** 将所有坐标平移到正空间，保留 MARGIN 边距。

### 4. 渲染 (GraphRenderer.java)

- **模块框：** 带标题条的彩色矩形，按 cluster id 着色边框
- **配方卡片：** 深色卡片，产品图标居中 + 产品名在顶部；左输出端口和右输入端口以图标+标签芯片从卡片边缘伸出
- **物品图标：** `AEItemKey.toStack()` 重建带完整组件的 ItemStack；通过原版 `GuiGraphics.renderItem` 渲染
- **流体/气体图标：** `AEKeyRendering.drawInGui()`——AE2 注册的渲染处理器（各附属模组提供自己的）
- **名称：** `key.getDisplayName()` 获取本地化物料名
- **连线：** 正交折线，亮青色（模块内）或深灰色（跨模块），线段末端有箭头
- **悬停：** 悬停端口时高亮所有同名端口 + 关联边为亮琥珀色，其余变暗
- **点击：** 左键点击边 -> 相机跳转到下游节点；左键点击右侧输入端口 -> 跳转到该物料的产出配方
- **搜索：** Ctrl+F 打开搜索栏；回车按产品/物料名匹配并循环跳转

### 5. 导出 (GraphExporter.java)

- **SVG：** 自包含矢量文件，包含模块矩形 + 正交边路由 + 物品图标内嵌为 base64 PNG（从 GPU 纹理图集采样；流体用自身颜色染色，不渲染为桶）
- **JSON：** 导出节点（含解析后的本地化名称）、边、模块框和边路由

## AE2 网络集成

### GraphTerminalBlockEntity

实现 `IGridConnectedBlockEntity`：
- **网格节点：** 通过 `GridHelper.createManagedNode()` 创建，标志为 `REQUIRE_CHANNEL` + `DENSE_CAPACITY`
- **世界节点：** `setInWorldNode(true)` 至关重要——没有它，`ManagedGridNode` 会创建普通 `GridNode`，其 `findInWorldConnections()` 为空方法，节点永远无法连接相邻 AE2 设备
- **线缆类型：** 六面均为 `DENSE_SMART`
- **能力注册：** 通过 MOD 总线的 `RegisterCapabilitiesEvent` 注册 `AECapabilities.IN_WORLD_GRID_NODE_HOST`——不注册则 AE2 线缆无法发现终端
- **接入判定：** `getGrid()` 在 `connectedSides` 为空时返回 null（孤立节点会自建单节点孤岛网格，该孤岛也报告为已启动，因此非 null 的已启动网格并不代表终端已接线）

### PatternCollector

直接从 `grid.getNodes()` 枚举 `ICraftingProvider`（节点服务或 owner），覆盖原版样板供应器、ME接口和附属模组设备。`getCraftables` -> `getCraftingFor` 合成服务路径作为并集兜底保留。

## 交互

| 输入 | 操作 |
|---|---|
| 右键方块 | 打开终端 |
| 右键拖动 | 平移画布 |
| 滚轮 | 缩放（以光标为中心） |
| 左键点击连线 | 跳转至下游配方 |
| 左键点击右侧输入端口 | 跳转至该物料的产出配方 |
| 悬停端口 | 高亮所有同物料端口 + 关联边 |
| Ctrl+F | 打开搜索栏（回车循环匹配，Esc 关闭） |
| 按钮（右上角纵向） | 重建布局 / 切换模块显示 / 导出SVG / 导出数据 |

## 状态显示

屏幕根据 `GraphDataPacket.status` 显示不同提示：
- **STATUS_OK (0)：** 正常渲染图谱，标题栏显示样板数
- **STATUS_NO_NETWORK (1)：** "未接入ME网络"——终端无世界连接
- **STATUS_NO_PATTERNS (2)：** "已接入但无样板"——网络存在但无可读样板

服务端日志输出 `[GraphTerminal]` 诊断行，含 node/grid/booted/active/channels/providers/craftables/patterns 用于排查。

## 屏幕架构

`GraphTerminalScreen` 继承 `Screen`（非 `AbstractContainerScreen`），防止 JEI 和 FTB 库侧边栏按钮叠加在全屏图谱上。配对的 `GraphTerminalMenu` 手动管理，镜像原版生命周期（`onClose` -> `player.closeContainer()`，`removed` -> `menu.removed()`）。

## 性能

- 布局在客户端线程异步执行（服务端仅采集样板 + 聚类）
- 同步执行 200 次迭代导致服务器 tick 阻塞；降至 50 次并异步执行解决
- 正交线段以单次 `fill()` 调用绘制（主要帧时间优化）
- 物品堆栈/Key/名称解析按 keyId 缓存
- 节点碰撞检测使用 52px 半宽 / 20px 半高 AABB 盒（16px 图标 + 中文标签）
- 聚类边界框使用 68px padX / 48px padY 间距防止重叠
