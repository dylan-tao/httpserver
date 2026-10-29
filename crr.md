# Code Review Report (crr)

- 范围：近 1 周提交（`cf8eeee..HEAD`）
- 方法：逐提交 diff 审查 + 100 万次差分模糊（OpenAddressMap vs HashMap 预言机）+ 60 轮多场景压测战役 + 20 轮正交质量视角
- 结论：1 个真实 bug 已修复；6 条建议经复核处置完毕（3 条已落地、2 条确认无需行动、1 条明确不做并记录理由）

## 已修复

### F1 [高] LazyRequestHeaders 跨请求头泄漏（commit `73a055d`）

- 现象：handler 从不调 `getRequestHeaders()` 的交换，其异步任务在连接解析完**下一个请求之后**首次调用该方法时，读到请求 N+1 的活动解析表
- 根因：把"未暴露 ⇒ 无人可保留"当公理；exchange 对象本身就是保留通道
- 修复：`clear()` 对未暴露视图 `retire()`（锁内置空 source、detach），迟到首读物化为空表
- 回归测试：`testLateUnexposedReadNeverSeesNextRequestData`

## 建议处置结果（经逐条分析执行）

### 已落地（3 条）

1. **P1-1 已知差异文档化** — README「测试与合规」新增「已知差异」节：异步迟到首读返回空表 vs JDK 过期快照，注明安全性取舍与正常读取路径行为一致。
2. **P1-2 补测试覆盖** — 新增 `TestNormalizeUnicodeFallback`（slowFold 触发条件：ASCII 违规 + >0xFF 字符共存，如 `"x-ž"`；**非 ASCII 首字母不折叠**的既有语义固化；ASCII 快折叠）与 `TestNormalizeCRLFRejection`（键含 CR/LF 拒绝；**值中的 CR/LF 由写头路径校验**——add 不拦）。修正过程中发现初版断言对两个既有语义理解有误（值校验位置、非 ASCII 折叠范围），均按真实语义重写并固化。
3. **P2-4 注释更新** — `testUnreadUnexposedViewIsSkippedByClear` 注释改为 retire 语义，指向泄漏回归测试。

### 确认无需行动（2 条）

4. **P1-3 addNormalized 前置保证已确认成立**：h1 解析器逐字节构建规范形 key（首大写+lower，TCHAR 校验同时完成）；h2 侧 `Http2HeaderField.normalizedName` 在 HPACK 层已规范化。两个调用点的"正常请求必已规范"前提**由构造保证**，非正常请求在进入 `addNormalized` 前已被拒（400/协议错误）。无需额外守护。
5. **P2-5 未物化视图生命周期：有完整管理与清理**——`materialize()`/`retire()` 双路径均 `detach`（清 `pendingView` 强引用）；`retire()` 后 `source=null` 物化即空表；解析表本身是 ThreadLocal、随连接线程消亡整链 GC。无泄漏路径。

### 明确不做（1 条）

6. **P2-6 doFilter 后统一补 `wrapped.close()`：不建议实施**。分析：
   - **会破坏官方异步语义**——`AsyncHandlerTest`（JDK 派生用例）要求 doFilter 返回后响应流仍可异步写；"先检查未关闭再关闭"无法区分"忘了关"与"异步仍在写"（两者状态相同）。
   - **现状并非泄漏**——未归还的 chunked 缓冲持有者是孤儿流对象，连接关闭后整链不可达，GC 回收；唯一代价是下一次交换退回私有分配（既有兜底路径）。
   - 若未来要做，必须引入"异步宽限期"（idle 检测时关闭），复杂度与收益不成比例。

## 压测战役补充发现（第 11 轮）

h2 500 流场景失败三次，**根因全部为压测探针的协议违规，非服务器 bug**：裸文本头块（应 HPACK 编码）、HPACK 字符串长度前缀错、客户端流 ID 用了偶数（RFC 7540 5.1.1 要求奇数）。服务器对每个违规都按协议正确响应（COMPRESSION_ERROR / PROTOCOL_ERROR GOAWAY），h1 服务全程健康。探针修复后第 11 轮 PASS。经验教训已记录：自制压测工具本身需先通过协议合规验证，其失败不可直接归因于服务器。

## 20 轮视角清单与结果

| # | 视角 | 结果 |
|---|---|---|
| 1 | 线程安全 | 通过：DCL+volatile 发布序正确，materialize/retire 均在视图监视器内 |
| 2 | 资源生命周期 | 通过：借还配对全路径核实；弃单缓冲随连接 GC（见处置 6） |
| 3 | API 契约 | 通过；迟到读取差异已文档化（处置 1） |
| 4 | null 处理 | 通过：null key/list/值在合并路径显式 NPE/IOException |
| 5 | 边界条件 | 通过：OpenAddressMap 百万级差分模糊全过 |
| 6 | 异常安全 | 通过：violation 收集后统一抛出 |
| 7 | 重入 | 通过：materializing 标志防递归 |
| 8 | 内存可见性 | 通过 |
| 9 | 回归风险 | 通过：Date/头字节输出逐字节一致 |
| 10 | 测试覆盖 | 缺口已补（处置 2） |
| 11 | 错误消息 | 通过 |
| 12 | 文档准确性 | 已修（处置 3） |
| 13 | 死代码 | borrowChunkBuffer 已清理（`73a055d`） |
| 14 | 命名一致性 | 通过 |
| 15 | 模块边界 | 通过 |
| 16 | 性能回归 | 通过：60 轮压测（见会话记录压测汇总） |
| 17 | 并发变更 | 观察项：验证-写入窗口与改造前一致，非新风险 |
| 18 | equals/hashCode | 通过：身份比较已文档化 |
| 19 | 静态状态 | 通过：反射字段生产 JVM 为 null，JIT 消除 |
| 20 | 可运维性 | 通过 |
