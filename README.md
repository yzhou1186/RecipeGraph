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

Current mod version: **0.4**

## Configuration

The mod exposes one common-side config value, written to `<config>/recipegraph.toml` and editable in-game via NeoForge's Mod List → Config button:

| Key | Type | Range | Default | Description |
|---|---|---|---|---|
| `maxModuleSize` | int | 3–100 | 16 | Maximum **recipes per module box** (the "(N 项)" count in a box title). Louvain communities larger than the cap are split into consecutive chunks; small rings within a community are never cut across chunks. Larger = fewer, bigger boxes; smaller = more, smaller boxes. |

The value is clamped to its declared range on read, so manually editing the TOML out-of-bounds is safe. Changes take effect the next time a graph is laid out (Rebuild button or opening the terminal). Other layout parameters (e.g. collision-separation passes) are fully automatic and adapt to graph size.

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
|  product-anchored layering        |
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

**Step 2 — Module identity & splitting (recipe-count cap):**
- Module boxes are EXACTLY the Louvain communities (plus singletons). Modules mutually reachable in a directed SCC are NEVER glued together: in dense modpacks even a small ring SCC ties together modules from completely different product lines via byproduct loops, so SCC merging produced boxes full of unrelated items (storage cells + QIO + 充能棒 + 天枢 in one box). Louvain already decides which recipes belong together by edge density — that identity is preserved
- Rings are handled without merging: product-anchored layering flags loop-return edges and routes them via bottom rails
- Oversize boxes (large communities) are split into consecutive chunks of at most the cap (`maxModuleSize`), following internal (column, row) flow; node-level SCCs (rings WITHIN one community) smaller than the cap are emitted atomically so a small internal ring is never cut in half
- All boxes renumbered to 1..N after splitting

**Step 3 — Product-anchored layering:**
- Dense modpacks tie almost every module into ONE giant SCC through byproduct loops, so module out-degree is never 0 — seeding on DFS-stripped sinks lands on arbitrary ring-tail points
- Layer 0 anchors on the genuine PRODUCT side: (a) modules containing a final-product recipe (a recipe whose output no network recipe consumes — 出度为0 的配方), plus (c) genuine out-degree-0 sinks outside rings
- Ring EXIT nodes (a ring member feeding something outside its SCC) are NOT anchored: the anchor-rooted reverse DFS reaches them at layer ≥ 1, exactly one column right of the products they feed. Anchoring every exit was wrong — oversize splitting turns formerly intra-box edges into inter-box edges and multiplies artificial exits (45 false anchors in one tested pack)
- An anchor-rooted DFS over REVERSE edges (consumer → producer) flags loop-return feedback edges; longest-path relaxation over the remaining edges assigns every box a layer: finished products on the FAR LEFT, raw materials on the right
- Layer count is entirely data-driven; unreachable byproduct recyclers get penalty layers beyond all product chains

**Step 4 — Sugiyama meta-layout:**
- Feedback/loop-return pairs (`railPairs`) are excluded from layering: no dummies, no barycentre influence — they route as bottom rails
- Dummy nodes for forward edges spanning >1 layer
- 4 barycenter sweeps (alternating down/up) for Y ordering
- 2 median relaxation passes
- Per-layer overlap resolution (sequential push-down with ROW_GAP spacing)
- X positions: accumulate widths per column DIRECTLY — layer 0 (products) at the far left, no mirroring

**Step 5 — Module internals:**
- Each box gets an independent longest-path column ranking (does NOT inherit external meta layer); inputs enter on the right edge, outputs leave on the left edge
- Internal cycle removal via DFS gray-marking; back edges route along box top/bottom border
- Barycenter ordering within columns (2 forward + 1 backward sweep)
- Card height scales with port count; cards stack cumulatively per column

**Step 6 — Edge routing:**
- **Intra-module forward:** straight or L-shaped orthogonal segments (output LEFT port -> input RIGHT port)
- **Intra-module cycle:** routes along box's top/bottom internal rail, never leaves the box
- **Cross-module forward (producer RIGHT → consumer LEFT):** through the vertical channel between the boxes (right of the consumer), one track per edge (alternating +/-offset)
- **Cross-module loop returns (railPairs):** bottom rail around the canvas below all boxes, one track per edge

**Step 7 — Normalisation:** Shift everything into positive coordinate space with MARGIN padding.

### 4. Rendering (GraphRenderer.java)

- **Module boxes:** colored rectangles with title strip, colored outline by cluster id
- **Recipe cards:** dark cards with product icon (center) + product name (top); left output port + right input ports as icon+label chips extending from card edges
- **Item icons:** `AEItemKey.toStack()` rebuilds full ItemStack with components; rendered via vanilla `GuiGraphics.renderItem`
- **Fluid/gas icons:** `AEKeyRendering.drawInGui()` — AE2's registered render handlers (each addon provides its own)
- **Names:** `key.getDisplayName()` for localized material names
- **Edges:** orthogonal polylines, bright cyan (intra-module) or dark grey (cross-module), arrowheads at segment ends
- **Z-order:** cross-module edges are drawn **first** (bottom layer), intra-module edges drawn **on top** so they are never obscured by lines crossing between boxes
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
| Functions button (top-right) | Opens popup menu: Rebuild Layout / Toggle Modules / Layout Mode / Export SVG / Export JSON |
| Layout Mode item | Cycles through available layout strategies (currently "Recipe-Material" only) |

## Status Display

The screen shows distinct messages based on `GraphDataPacket.status`:
- **STATUS_OK (0):** Graph rendered normally; pattern count shown in header
- **STATUS_NO_NETWORK (1):** "Not attached to an ME network" — terminal has no in-world connections
- **STATUS_NO_PATTERNS (2):** "Attached but no readable patterns" — network exists but no patterns found
- **STATUS_ERROR (3):** "Could not collect the pattern graph" — server-side collection failed; see the game log

While a freshly received non-empty graph is still being laid out in the background the screen
shows a "laying out…" message instead of the stale empty-state text.

Server logs a `[GraphTerminal]` diagnostic line with node/grid/booted/active/channels/providers/craftables/patterns for troubleshooting.

## Screen Architecture

`GraphTerminalScreen` extends `Screen` (not `AbstractContainerScreen`) to prevent JEI and FTB Library sidebar buttons from overlaying the fullscreen graph. The paired `GraphTerminalMenu` is managed manually with vanilla lifecycle mirroring (`onClose` -> `player.closeContainer()`, `removed` -> `menu.removed()`).

## Performance

- Pattern collection + Louvain clustering run on a server-side daemon worker thread (`RecipeGraph-Collector`): AE2 grid nodes are snapshotted on the main thread, and the finished packet is handed back via `server.executeIfPossible()` for sending
- Layout runs on a client-side daemon worker thread (`RecipeGraph-Layout`); the (graph, layout) pair is published atomically through a monotonic sequence number so a stale slow layout can never overwrite a newer graph
- Post-layout node collision separation uses a spatial-hash grid over the actual card rectangles (120px wide; height driven by port count) for linear-time neighbour checks; pass count adapts to graph size (`clamp(40 + n/10, 40, 200)` where n = recipe count) — no user tuning
- Orthogonal edge segments draw as single `fill()` calls (main frame-time optimization)
- Item stack / AEKey / name resolution is cached per material keyId; SVG atlas pixels are cached per texture instance (auto-refreshed on resource reload)
- Module geometry constants: inner padding 26px X / 14px Y, inter-layer gap 150px, inter-row gap 42px

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

当前模组版本：**0.4**

## 配置

模组提供一个通用端配置值，写入 `<config>/recipegraph.toml`，可通过 NeoForge 模组列表 → 配置按钮在游戏内修改：

| 键名 | 类型 | 范围 | 默认值 | 说明 |
|---|---|---|---|---|
| `maxModuleSize` | int | 3–100 | 16 | 单个模块框允许的**最大配方数**（即模块标题"(N 项)"的 N）。超过该配方数的 Louvain 大社区会被拆分为多个连续小块；同社区内的小环不会被切到两个块中。值越大模块框越大越少，值越小模块越小越多。 |

读取时自动钳制到声明范围，手动编辑 TOML 越界是安全的。修改在下次布局时生效（重建按钮或重新打开终端）。其余布局参数（如碰撞分离迭代次数）完全自动化，按图的大小自适应。

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
|  产品锚定分层（终产物最左）        |
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

基于模块度的贪心聚类算法（Blondel et al., 2008）。将图视为无向加权图，**只运行局部移动阶段**：固定随机种子，反复按随机顺序扫描，把每个节点移入模块度增益最大且严格为正的邻居社区，直到整轮扫描没有任何移动。

经典的**聚合（收缩）阶段被刻意省略**。正确的收缩必须把社区内部边权作为超节点自环保留在 `sigmaTot`/`m` 中；否则模块度惩罚项逐层衰减，全图坍缩——在 605 配方整合包图上实测：145 → 28 → 7 → 2 → **1 个社区**，随后超大框拆分把这一个"社区"机械切成上限大小的碎块，约 98% 的社区内部边被切断，几乎每个碎块都只剩一列互不相关的配方。phase-1 的划分（约 150 个紧凑的小社区）正是框大小上限与元图 Sugiyama 布局所针对的粒度，且本身就是合法的 Louvain 最优解。

结果：每个 `GraphNode` 被分配 cluster id，定义业务模块。

### 3. 层级布局 (HierarchicalLayout.java)

两层布局：元图（模块）+ 模块内部（配方卡片）。

**步骤 1 — 模块分组：** 按 Louvain cluster id 分组；未聚类的节点成为单例。

**步骤 2 — 模块身份与拆分（按配方数上限）：**
- 模块框**严格等于** Louvain 社区（加单例）。在有向图中互相可达（SCC）的模块**绝不合并**：密集整合包中即使小环 SCC 也会通过副产品回环把完全不同产品线的模块绑在一起，SCC 合并会产生塞满无关物品的框（存储元件 + QIO + 充能棒 + 天枢混在一个框内）。哪些配方属于同一社区已由 Louvain 按边密度判定——该身份必须保留
- 环不依赖合并处理：产品锚定分层会标记回路边并统一走底部轨道
- 超过上限的方框（大型社区）按内部（列、行）流向拆分为不超过上限配方数（`maxModuleSize`）的连续块；低于上限的节点级 SCC（同一社区内部的环）原子整块发射，小环绝不被切成两半
- 拆分后所有方框重新编号为 1..N

**步骤 3 — 产品锚定分层：**
- 密集整合包中副产品循环会把几乎所有模块绑成**一个巨型 SCC**，模块出度恒不为 0——在 DFS 剥边后的汇点上播种只会命中任意的环尾模块
- layer 0 改为锚定真正的**产品侧**：(a) 含终产物配方的模块（产物不被网络中任何配方消耗的配方——出度为0的配方），以及 (c) 环外真实出度为 0 的汇点
- 环的**出口节点**（向环外供料的环成员）**不锚定**：产品锚点的反向 DFS 会在 layer ≥ 1 处到达它们——正好排在其所供产品右侧一列。锚定所有出口是错误的：大模块**拆分**会把原框内边变成跨框边，人工"出口"成倍出现（某整合包实测产生 45 个错误锚点，最左列堆了 48 个框）
- 以这些锚点为根沿**反向边**（消费者 → 生产者）DFS，标记回环反馈边；在剩余边上做最长路松弛，为每个方框分配层级：终产物在**最左侧**，原料在右侧
- 层数完全由数据驱动；无法到达产品链的副产品回收模块放置在所有产品链之后的惩罚列

**步骤 4 — Sugiyama 元布局：**
- 反馈/回路边对（`railPairs`）不参与分层：不插虚拟节点、不影响 barycenter——统一走底部轨道
- 跨越多层的顺向边插入虚拟节点
- 4 次 barycenter 扫描（下行/上行交替）用于 Y 排序
- 2 次中值松弛遍历
- 逐层重叠消除（顺序下推，ROW_GAP 间距）
- X 坐标：按列直接累加宽度——layer 0（终产物）直接位于最左侧，不再镜像翻转

**步骤 5 — 模块内部布局：**
- 每个方框拥有独立的最长路径列排布（不继承外部元图层级）；输入从右边进入，输出从左边离开
- 内部环消除通过 DFS 灰色标记；回边沿方框顶/底边走线
- 列内 barycenter 排序（2 次正向 + 1 次反向扫描）
- 卡片高度随端口数增长；卡片按列累积堆叠

**步骤 6 — 边路由：**
- **模块内正向：** 直线或 L 形正交线段（左输出端口 -> 右输入端口）
- **模块内环：** 沿方框顶/底内轨走线，不出框
- **跨模块正向（生产者在右 → 消费者在左）：** 经两框之间的垂直通道（消费者右侧），每边独立轨道（交替 ±偏移）
- **跨模块回路返回（railPairs）：** 画布底部、所有方框之下的轨道绕行，每边一条轨

**步骤 7 — 归一化：** 将所有坐标平移到正空间，保留 MARGIN 边距。

### 4. 渲染 (GraphRenderer.java)

- **模块框：** 带标题条的彩色矩形，按 cluster id 着色边框
- **配方卡片：** 深色卡片，产品图标居中 + 产品名在顶部；左输出端口和右输入端口以图标+标签芯片从卡片边缘伸出
- **物品图标：** `AEItemKey.toStack()` 重建带完整组件的 ItemStack；通过原版 `GuiGraphics.renderItem` 渲染
- **流体/气体图标：** `AEKeyRendering.drawInGui()`——AE2 注册的渲染处理器（各附属模组提供自己的）
- **名称：** `key.getDisplayName()` 获取本地化物料名
- **连线：** 正交折线，亮青色（模块内）或深灰色（跨模块），线段末端有箭头
- **Z-order：** 跨模块连线**先绘制**（底层），模块内连线**后绘制**在顶层，确保模块内连线永远不被模块外连线遮挡
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
| 功能按钮（右上角） | 弹出菜单：重建布局 / 切换模块显示 / 布局模式 / 导出SVG / 导出数据 |
| 布局模式项 | 切换可用布局策略（当前仅"配方-材料"一种） |

## 状态显示

屏幕根据 `GraphDataPacket.status` 显示不同提示：
- **STATUS_OK (0)：** 正常渲染图谱，标题栏显示样板数
- **STATUS_NO_NETWORK (1)：** "未接入ME网络"——终端无世界连接
- **STATUS_NO_PATTERNS (2)：** "已接入但无样板"——网络存在但无可读样板
- **STATUS_ERROR (3)：** "样板图谱采集失败"——服务端采集异常，请查看游戏日志

收到非空图谱但仍在后台布局期间，屏幕会显示"正在布局…"，而不是误导性的空状态提示。

服务端日志输出 `[GraphTerminal]` 诊断行，含 node/grid/booted/active/channels/providers/craftables/patterns 用于排查。

## 屏幕架构

`GraphTerminalScreen` 继承 `Screen`（非 `AbstractContainerScreen`），防止 JEI 和 FTB 库侧边栏按钮叠加在全屏图谱上。配对的 `GraphTerminalMenu` 手动管理，镜像原版生命周期（`onClose` -> `player.closeContainer()`，`removed` -> `menu.removed()`）。

## 性能

- 样板采集 + Louvain 聚类在服务端守护线程（`RecipeGraph-Collector`）执行：所有 AE2 访问（网格节点查询、`ICraftingProvider.getAvailablePatterns()`、合成服务 `getCraftables`/`getCraftingFor`）均在主线程完成并生成不可变的 `IPatternDetails` 快照——worker 线程不再触碰任何 AE2 服务——完成的数据包通过 `server.executeIfPossible()` 回到主线程发送
- 布局在客户端守护线程（`RecipeGraph-Layout`）执行；(图, 布局) 对通过单调递增序号原子发布，较慢的旧布局永远不会覆盖更新的图
- 布局后节点碰撞分离使用空间哈希网格，基于卡片真实矩形（宽 120px，高度由端口数决定），邻居检测为线性复杂度；迭代次数按图大小自适应（配方数 n 时 `clamp(40 + n/10, 40, 200)` 次），无需用户调节
- 正交线段以单次 `fill()` 调用绘制（主要帧时间优化）
- 物品堆栈/AEKey/名称解析按物料 keyId 缓存；SVG 图集像素按纹理实例缓存（资源重载后自动刷新）
- 模块几何常量：内边距 26px（X）/ 14px（Y），层间距 150px，行间距 42px
