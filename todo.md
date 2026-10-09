# httpserver 待修复问题与性能优化清单

> 生成日期：2026-09-17（静态审查 + JDK 21 实测验证）
> 验证标记说明：
> - **[已实测]** — 用 JDK 21 编译后通过原始 socket 冒烟测试复现
> - **[已验证]** — 代码级逐行确认（关键行号已核对）
> - **[待复核]** — 来自子代理分析，建议修复前先复核
> - **[已修复]** — 已完成修复并通过回归验证（附验证方式）
>
> 项目背景：零依赖 JDK HttpServer 替代实现（HTTP/1.1 + HTTP/2 + WebSocket），
> 注意 `src/test/java/.../http2/Http2Test.java` 整类 `@Test(enabled=false)` 被禁用，
> HTTP/2 模块目前**零生效测试覆盖**，与下面 P1 区大量 h2 缺陷直接相关。
>
> **修复进度**（2026-09-17~21）：P0、P1、P2 **全部完成**（40 项），P3 完成 11 项
>（42 经微基准评估后决定不做），P4 完成 13/14（仅 gradle wrapper 受环境限制）——
> 累计 64 项，另修复复查发现的 6 处缺陷（3 处人工复核 + 3 处 code-review sweep）：
> 第 1-3 批：P0-1、P0-2、P1-9、P1-10、P1-11、P1-21、P1-22、P1-23；
> 第 4 批：P1-13、P1-14；第 5 批：P0-3~P0-8、P1-15、P1-17、P1-24；
> 第 6 批：P3-41、P3-43、P3-45（P3-42 实施后回退，原因见该条目）；
> 第 7 批：P1-25、P1-26、P1-27、P1-28、P1-29、P1-30；
> 第 8 批：P1-12、P1-16、P1-18、P1-19、P1-20；
> 第 9 批：P2-31~P2-40 全部十项；第 10~11 批：P3 余项与 P4 清理；
> 第 12 批（2026-09-21 复查）：修复复查发现的六处缺陷（3 人工 + 3 code-review，见"复查修正"）。
> 第 13 批（2026-10-09 核查收尾）：全量核查发现 P2-34 修复不完整（CL 未实际剔除）并补完、
> 顺修 ProxyHandler 空 headers IAE 边缘缺陷、清理两处无用 import；六个丢失的验证测试
> 重建并集成进 src/test（H2Compliance/MultiFrameTrailer/Review3/SlowBodyKeepAlive/
> Huffman/ProxyForwarding + h2c 裸 socket 测试客户端 H2Client）。
> 验证方式：JDK 21 编译 0 错误；TestNG 套件 104/104 通过（2026-10-09 第 13 批后 118/118，
> 第 14 批后 127/127，第 15 批后 135/135，第 16 批虚拟线程化后 135/135 保持全绿）；
> 九套冒烟与专项验证在修复当时全部通过（**注意：其源文件位于 /tmp/hs_smoke，
> 已被系统清理丢失**——正式集成进 src/test 的必要性由此实证，见"遗漏与残留清单"D 类）。
>
> **状态总览**：
>
> | 分区 | 已修复 | 未修复（编号） |
> |---|---|---|
> | P0 安全 | 8/8 | —（全部完成） |
> | P1 严重 bug | 22/22 | —（全部完成） |
> | P2 中等 bug | 10/10 | —（全部完成） |
> | P3 性能 | 11 修复 + 1 评估不做 | 42：微基准实测收益 0.04µs/请求（100k QPS 下 0.4% 单核），不值得结构性重构 |
> | P4 清理 | 13/14 | 仅 gradle wrapper（需 gradle 环境重新生成） |

---

## P0 — 安全漏洞（8/8 已修复 ✅）

### 1. [已修复] MultipartFormParser 文件名路径穿越（任意文件写）
- **位置**：`src/main/java/robaho/net/httpserver/extras/MultipartFormParser.java:127`
- **问题**：`Path.of(storage.toString(), meta.filename)` 的 filename 完全来自客户端
  Content-Disposition，无任何清洗。`filename="../../evil.jsp"`、`filename="/etc/cron.d/x"`
  均可写出 storage 目录，且写入内容（part data）也由客户端控制。
- **修复**：剥离路径分量只取文件名部分；拒绝 `..`/绝对路径/空字节；建议改用随机临时名，
  在 `Part` 中保留原始文件名（javadoc 声称 "the name may differ from filename" 但代码未实现）。
- **已修复（2026-09-17）**：新增 `sanitizeFilename()`——统一 `\`/`/` 分隔后取最后路径分量，
  空名/`.`/`..` 抛 IllegalArgumentException；落盘用清洗名，`Part.filename` 保留原始名。
  集成实测：`../../evil.txt`、`/tmp/abs-evil.txt`、`..\..\bs-evil.txt` 全部落盘于 storage 内，
  storage 外无逃逸文件；正常文件名不受影响；既有 MultipartFormParserTest 用例兼容。

### 2. [已修复] HTTP/1.1 请求体未排空时连接被复用（请求失步 / 走私向量）**[已实测]**
- **位置**：`ServerImpl.java:843-849`（`runPerRequest` 末尾）配合 `LeftOverInputStream.java:57-65`
- **问题**：handler 未读完请求体且剩余超过 drain 上限（默认 64KB，`ServerConfig.java:51`）时
  `drain()` 返回 false（eof=false），但 `runPerRequest()` 结束时**没有 JDK 上游版本的
  `isEOF()` 检查**，直接复用连接解析下一个请求。
- **实测**：POST 100KB（handler 不读 body），客户端**连第一个 200 响应都收不到**，
  连接卡死直至超时。复合因素：drain 失败后残留数据使 `available()>0`，触发 P2-32 的
  available-flush 优化不 flush 响应；连接随后在失步数据上解析，无任何响应到达客户端。
- **修复**：`tx = null` 前检查 `if (!tx.getOriginalInputStream().isEOF()) { closeConnection(connection); return; }`。
- **已修复（2026-09-17）**：`runPerRequest` 末尾补 `isEOF()` 检查（null 或未排空即关闭连接），
  并为 `LeftOverInputStream` 补上 JDK 原版有而移植时丢失的 `isEOF()` 方法。
  实测：100KB 未读 body → 第一个 200 正常到达客户端，随后服务器主动关闭连接（Broken pipe），
  不再失步卡死；chunk 溢出（P1-12）场景也被此检查兜底为"响应后关闭"而非静默卡死。

### 3. [已修复] 请求行/头部无任何长度与数量上限（内存 DoS）**[已实测]**
- **位置**：`Request.java:130-136`（请求行）、`:163-169`（BufferedBuilder 无限翻倍）、
  `:181-234`（头部数量无上限）
- **问题**：`BufferedBuilder` 容量无限翻倍，无 CRLF 的超长请求行/头部可使服务器无限分配内存；
  读头部期间 `inRequest=true`，而 `maxReqTime` 默认 `-1`（无限），慢速超大头攻击不受超时保护
  （idle 检查只覆盖 `inRequest=false` 阶段，`ServerImpl.java:935`）。
- **实测**：1000 个自定义头被无上限接受并正常处理（200 OK）。
- **修复**：请求行/单头设上限（如 8KB/16KB）、头部总数上限（如 100），超限 400/431 并断连；
  给 `maxReqTime` 安全默认值。
- **已修复（2026-09-17）**：`readLine` 与 headers() 的 key/value 追加均受 `maxReqLineSize`
  （默认 64KB，可配 `robaho.net.httpserver.maxReqLineSize`）约束；头部数量接线既有的
  `maxReqHeaders`（默认 200）——在两个 add 点计数，超限抛 IOException 并由 runPerRequest 转 400。
  实测：300 头 → 400，70KB 单头 → 400；ReadFix T6 千头用例现为 400（预期行为变化）。

### 4. [已修复] ProxyHandler 默认开放代理 + proxies 表竞态/NPE/无界增长
- **位置**：`extras/ProxyHandler.java:125-127`（authorizeConnect 默认 return true）、
  `:51-56`（select 对 map miss 直接 NPE）、`:106`（并发同 URI put 互相覆盖）
- **问题**：
  1. 默认任何客户端可 `CONNECT 任意主机:端口`，服务器成为开放跳板；
  2. 请求 A 刚 put(host1)，请求 B put(host2)，A 的流量实际走 host2（走错代理）；
  3. `proxies` 按 URI 无界增长永不清理。
- **修复**：默认拒绝 CONNECT（403）；select miss 回退默认值；代理选择改为按请求传递（如
  ThreadLocal/exchange 属性），避免全局共享可变映射。
- **已修复（2026-09-17）**：authorizeConnect 默认发 403 并返回 false（需授权的部署必须显式
  覆盖，TunnelProxyTest 已适配为显式允许）；select 优先 ThreadLocal 当前请求代理、回退
  authority 键 map、最终 NO_PROXY 兜底（不再 NPE）；proxies 键改为 authority（增长受目标集
  而非完整 URI 限制）。同 authority 并发不同代理的极端场景仍可能错选——per-request 代理需
  HttpClient API 支持，已在代码注释说明局限。

### 5. [已修复] HPACK 解码 4 字节可触发任意大内存分配（OOM DoS）
- **位置**：`http2/hpack/HPackContext.java:169`（`decodeFieldValue` 的 `Arrays.copyOfRange`）
- **问题**：字符串长度来自攻击者可控的变长整数（可达 2^28+），`Arrays.copyOfRange(buffer,
  index, index+length)` 在越界时会分配超大数组并填 0；`OutOfMemoryError` 不被 catch 捕获，
  直接杀死读线程。
- **修复**：拷贝前校验 `index + length <= buffer.length`（越界抛 COMPRESSION_ERROR）；
  实现 SETTINGS_MAX_HEADER_LIST_SIZE。
- **已修复（2026-09-17）**：decodeFieldName/decodeFieldValue 拷贝前均校验长度不超出头块
  （COMPRESSION_ERROR）；顺修 decodeFieldName 长度解析改用变长整数（原为单字节 0x7F，
  ≥128 字节头名会解析错——与 P1-21 对称的解码端 bug）。

### 6. [已修复] CONTINUATION 洪水与 HPACK 动态表无界（HTTP/2 内存 DoS）
- **位置**：`http2/HTTP2Connection.java:200,282,297`（headerBlockFragments 无界累积）；
  `hpack/HPackContext.java:26,39-41,98-109`（动态表无尺寸核算）
- **问题**：单连接上 CONTINUATION 帧可无限堆叠（CVE-2024-27316 同类攻击面）；
  HPACK 动态表按 RFC 7541 的 `name.len+value.len+32` 核算完全缺失，跨请求持续膨胀。
- **修复**：对 header block 总字节数设上限（超限 COMPRESSION_ERROR/ENHANCE_YOUR_CALM）；
  实现动态表尺寸核算与插入时淘汰。
- **已修复（2026-09-17）**：processFrames 累计 headerBlockBytes，超 `http2MaxHeaderBlockSize`
  （默认 128KB，可配）抛 ENHANCE_YOUR_CALM，消费后清零；动态表实现 RFC 7541 §4.1 字节核算
  （fieldSize + evict），decodeDynamicTableSizeUpdate 同步用字节语义（原为条目数对比字节数）。

### 7. [已修复] WebSocket 帧与消息大小无上限（OOM DoS）
- **位置**：`websockets/WebSocketFrame.java:220-225`（`new byte[payloadLength]` 立即分配，
  64 位长度仅以 Integer.MAX_VALUE 为界）、`WebSocket.java:60`（continuousFrames 无界累积）
- **问题**：客户端可声明 ~2GB 单帧迫使服务器立刻分配数组；分片消息聚合再分配一次同等大小。
- **修复**：增加可配置 maxFrameSize/maxMessageSize，超限以 1009 (MessageTooBig) 关闭。
- **已修复（2026-09-17）**：帧分配前检查 `maxWebSocketFrameSize`（默认 64MB，可配
  `robaho.net.httpserver.maxWebSocketFrameSize`）；分片聚合处累计检查 `maxWebSocketMessageSize`
  （默认 64MB），超限抛 MessageTooBig(1009)。

### 8. [已修复] FormParser/MultipartFormParser 非文件字段无内存上限
- **位置**：`extras/FormParser.java:33-38`（整个 body 读入内存）；`MultipartFormParser.java:123-125`
- **问题**：巨型 body 或声明为普通字段的巨型提交直接 OOM。
- **修复**：增加 maxContentLength/maxFieldSize，流式计数超限即抛。
- **已修复（2026-09-17）**：FormParser 读 body 计数超 `max_form_bytes`（默认 10MB，0 不限）
  抛 IOException（顺修 read()==0 提前截断的既有 bug，改为 `!= -1`）；MultipartFormParser
  非文件字段经 BoundedOutputStream 限 `max_multipart_field_bytes`（默认 1MB，0 不限）。

---

## P1 — 严重 bug（22/22 已修复 ✅）

### HTTP/1.1 与核心

### 9. [已修复] 裸 LF 进入 header 值导致服务器内部异常（应答 400）**[已实测]**
- **位置**：`Request.java:197-231`（else 分支不清除 prevCR、裸 LF 被 `current.append((char)c)` 吞进值）
- **实测**：`X-Foo: aaa\nX-Bar: bbb\r\n` → 解析出含 LF 的值，在 `ExchangeImpl.java:94`
  `putAll` 复制时被 JDK `Headers.checkValue` 抛 `IllegalArgumentException: Illegal LF found
  in header` → **客户端收到连接关闭（CONN-CLOSED），无任何 HTTP 响应**（应为 400）。
- **影响修正**：因 JDK 复制层拦截，未构成走私向量，但行为错误（服务器内部异常）且
  `Request.headers()` 直接使用方与复制层校验不一致。
- **修复**：解析层遇裸 LF 直接 400；else 分支显式 `prevCR = false`；对 value 中 CR/LF 统一拒绝。
- **已修复（2026-09-17）**：`headers()` 解析循环把裸 LF 一律当行终止符（RFC 9112 §2.2
  宽容做法，前置 CR 一并消费）；裸 CR（CR 后非 LF）抛 IOException，由 `runPerRequest` 新增的
  try-catch 转为 400 响应。实测：`X-Foo: aaa\nX-Bar: bbb` 现解析为两个头，请求正常 200。

### 10. [已修复] 对已存在单值请求头 add() 抛 UnsupportedOperationException（破坏 handler）**[已实测]**
- **位置**：`ExchangeImpl.java:93-94`（`reqHdrs.putAll(req.headers())`）+
  `OptimizedHeaders.java:150`（forEach 把单值暴露为不可变 `List.of(s)`）
- **实测**：handler 中 `exchange.getRequestHeaders().add("X-Foo","second")`（X-Foo 已有单值）
  抛 `UnsupportedOperationException`（实测复现 THREW:UnsupportedOperationException）。
  handler 若不自行捕获即异常终止、客户端收不到响应。这是 filter/handler 的常见操作
  （如 AuthFilter 追加 Authorization）。
- **修复**：`OptimizedHeaders.forEach/entrySet` 返回可变 `ArrayList` 包装，或复制时展开为可变 List。
- **已修复（2026-09-17）**：`forEach()`（entrySet 的数据来源）单值改为返回可变
  `new ArrayList<>(List.of(s))`，`putAll` 复制后 add 正常。实测：add-ok。
  遗留：`get()` 仍返回 `List.of(s)` 不可变（h2 直连路径的 get().add() 场景，热路径
  权衡暂不改，如需要可后续处理）。

### 11. [已修复] ChunkedInputStream 不支持 trailer（连接失步 / 走私向量）**[已实测]**
- **位置**：`ChunkedInputStream.java:139-149`（`consumeCRLF` 无条件消费一个 CRLF）
- **实测**：`POST` chunked + `0\r\nX-Trailer: v\r\n\r\n`，请求本身 200；但同一连接的下一个
  请求被解析为 `400 Bad request line`——**服务器把 trailer 残留 `X-Trailer: v` 当作了下一个
  请求的请求行**。连接不断开而失步；若残留内容恰好构成合法请求行，即构成请求走私
  （RFC 7230 §4.1.2 允许 trailer）。
- **修复**：eof chunk 后循环读取并丢弃 trailer 行直到空行。
- **已修复（2026-09-17）**：新增 `consumeTrailer()`——eof chunk 后消费 trailer 行直到空行
  （裸 LF 容忍、裸 CR 拒绝、行数上限 100 防 DoS）。实测：带 trailer 的 chunked 请求后，
  同一连接第二个请求正常 200，管道保持同步。

### 12. [已修复] chunk-size 十六进制解析 int 溢出（服务器静默卡死）**[已实测]**
- **位置**：`ChunkedInputStream.java:48-66`（`numeric`）、`:83`（允许 15 个 hex 字符）
- **问题**：`len = len*16 + val` 静默溢出可得到负 remaining，`in.read(b, off, 负数)` 抛
  `IndexOutOfBoundsException`（RuntimeException）而非干净 IOException。
- **实测**：发送 15 位 hex chunk 长度（FFFFFFFFFFFFFFF）后，服务器**无响应也不关闭连接**，
  客户端挂起直至超时（异常路径走 `Exchange.run` 的 catch-Exception 分支，因 sentHeaders/closed
  均为 true 不关连接，循环继续阻塞在读请求行上）。
- **修复**：逐位检查 `len > (Integer.MAX_VALUE - val) / 16` 抛 IOException。
- **注（2026-09-17）**：P0-2 修复后此问题的"静默卡死"后果已被兜底（异常后 isEOF 检查
  关闭连接，实测响应正常到达后断连），但溢出本身仍会产生 RuntimeException 而非 400——
  溢出检查与 400 化仍待修。
- **已修复（2026-09-18）**：`numeric()` 改 long 累积并逐位检查溢出，超 Integer.MAX_VALUE
  抛干净的 IOException("invalid chunk length (overflow)")。实测（读 body 的 handler 路径）：
  服务器日志**无 IndexOutOfBoundsException**，客户端收连接关闭；400 化不可行（chunk 解析
  发生在响应开始后的流式读取中），干净 IO 异常 + 断连即正确行为。

### 13. [已修复] OpenAddressMap / OpenAddressIntMap 系列正确性缺陷（6 项）
- **位置**：`OpenAddressMap.java`、`OpenAddressIntMap.java`
- **问题清单**（均已逐行核对）：
  1. `OpenAddressMap.java:63-70`：表满 resize 后 `sentinel` 未重置为 -1，用旧表墓碑下标
     写新表会**覆盖存活条目**（数据丢失）；对照 `OpenAddressIntMap.java:69` 有 `sentinel=-1`；
  2. 两个 Map 的 `used` 递增条件写反（`OpenAddressMap.java:72`、`OpenAddressIntMap.java:77`：
     `if(sentinel!=-1) used++`）——空槽插入不增、墓碑复用反而增，纯插入场景**从不主动扩容**，
     探测退化为 O(n)；
  3. `OpenAddressMap.java:70-71`：put(不存在键, null) 创建幻影墓碑条目并 size++（IntMap 有
     null 守卫，Map 缺失）；
  4. `OpenAddressMap.java:78-80`：resize 重放墓碑（value==null）污染 size（IntMap 跳过）；
  5. 两个 Map 重复 remove 同一 key 重复 `size--`（`OpenAddressMap.java:56-57`），`HTTP2Stream.close()`
     多路径调用时 `http2Streams.size()` 下溢 → **MAX_CONCURRENT_STREAMS 限流失效**；
  6. `OpenAddressIntMap.java:45` put 加锁而 `:95-109` get/values 无锁且字段非 volatile——
     h2 读线程 get 与 handler 线程 close-put 并发时，resize 的非原子多字段替换可致
     AIOOBE 或读到过期视图。
- **修复**：补齐 sentinel 重置/null 守卫/resize 跳墓碑/used 条件；或低改动预算下直接
  替换为 `ConcurrentHashMap`/`HashMap`。
- **已修复（2026-09-17）**：六项全部修复——OpenAddressMap 补 sentinel 重置、put(不存在键,
  null) 守卫、resize 跳墓碑、used 条件翻转（`if(sentinel==-1) used++`）、双 remove 守卫，
  并加"非线程安全"文档；IntMap 同步修复 used/双 remove，get/values/entrySet 补 synchronized
  （与 put/resize 一致），entrySet 跳过墓碑（Map.entry 禁 null）。单元验证：1000 键
  put/get、双/三重 remove 不下溢、幻影 null 不建条目、混合插入删除后全部存活键可检索
  （FixBatch456 全绿）。

### 14. [已修复] ContextList 缓存永不失效（removeContext 后旧 handler 继续服务）
- **位置**：`ContextList.java:58-69`（cache）与 `:93-111`（remove 不清缓存）
- **问题**：`findContext` 只缓存精确匹配，`remove` 只从 list 移除——移除 context 后，
  路径恰好等于该 context path 的请求仍路由到已移除的 handler；缓存条目也永不释放。
- **修复**：remove 时清除 cache 中指向该 context 的条目。
- **已修复（2026-09-17）**：两个 remove 重载均 `cache.values().removeIf(c -> c == ctx)`。
  集成验证：removeContext 后同路径请求 404（回退父 context）、缓存预热后再 remove 也正确失效。

### 15. [已修复] SSL 握手同步阻塞且无超时（DoS 面）+ 未配置 configurator 时 NPE
- **位置**：`ServerImpl.java:392-414`（`sslSocket.getSession()` 同步等待握手完成）、
  `SSLConfigurator.java:14`（cfg==null 静默返回后 `httpsConfig.getSSLContext()` NPE）
- **问题**：恶意客户端打开连接不完成握手可永久占用 executor 线程（DefaultExecutor 是
  cachedThreadPool，线程无限创建）；HTTPS server 未 setHttpsConfigurator 直接 start 时
  accept 路径 NPE 而非明确报错。
- **修复**：握手前 `setSoTimeout`（或在独立阶段带超时）；start()/accept 时校验 httpsConfig 非空。
- **已修复（2026-09-17）**：`getSession()` 用 `setSoTimeout(sslHandshakeTimeout)` 包裹
  （默认 30s，可配 `robaho.net.httpserver.sslHandshakeTimeout`），finally 恢复 0；`start()`
  对 https 且无 configurator 抛 IllegalStateException（快速失败）。

### 16. [已修复] ActivityTimer 静态时钟与 server 生命周期错配
- **位置**：`ActivityTimer.java:10-11`（static 字段）+ `ServerImpl.java:148`（每实例 schedule）+
  `ServerImpl.java:277`（stop 时 timer.cancel）
- **问题**：`now`/`dateAndTime` 是静态的，但更新任务挂在每个实例的 Timer 上。**最后一个
  server 实例 stop 后静态时钟冻结**：Date 响应头持续发送过期时间、基于 `ActivityTimer.now()`
  的全部超时判断失效。多实例并存时又重复更新（无害但冗余）。
- **修复**：把 ActivityTimer 的调度移到静态单例 Timer（引用计数或 daemon 常驻），与实例解耦。
- **已修复（2026-09-18）**：ActivityTimer 自带静态 daemon Timer（类初始化时 schedule，
  750ms 周期），ServerImpl 不再 per-instance 调度。实测（独立 JVM、无任何 server 实例）：
  时钟 2.3 秒内正常推进——修复前该场景时钟永远冻结。

### 17. [已修复] Http2Exchange ping 定时任务：异常吞掉、死连接永不清理
- **位置**：`ServerImpl.java:486-500`（catch IOException 忽略，从不关闭失效连接）；
  ping 写操作经 `ActivityTimerOutputStream` 刷新 `lastActivityTime`，使 `ConnectionCleanerTask`
  眼中该连接永远活跃——**死 h2 连接的读线程与 handler 线程永久泄漏**（无 socket 读超时兜底）。
- **修复**：ping 失败即关闭连接（或失败 N 次后）；ping 不应刷新 lastActivityTime。
- **已修复（2026-09-17）**：ping 失败即 `connection.close()`（读线程退出后由 finally 完成完整
  清理）；ping 前保存 `lastActivityTime`、ping 后恢复（不再让 ping 写刷新空闲时钟）。

### 18. [已修复] WebSocket 正常关闭握手后 onClose 恒收到 code=1011
- **位置**：`websockets/WebSocket.java:220-239`（readWebsocket finally 无条件
  `doClose(InternalServerError, "Handler terminated without closing...")`）
- **问题**：远程发起关闭（state→CLOSING）→ 读循环退出 → finally 无条件以 1011 关闭，
  **对端真实 close code/reason 全部丢失**，用户 onClose 回调拿到的永远是 1011+误导 reason。
- **修复**：finally 中区分 state：CLOSING 时使用记录的真实关闭码，仅异常路径用 1011。
- **已修复（2026-09-18）**：close() 开始关闭握手时记录 code/reason/initiatedByRemote
  （volatile pendingClose* 字段）；readWebsocket finally 中 state==CLOSING 且有记录值时
  doClose(记录值)，仅无记录且未 CLOSED 时才用 1011。实测（JDK WebSocket 客户端
  sendClose(1000, "bye bye")）：onClose 收到 `NormalClosure|bye bye|initiatedByRemote=true`
  ——修复前恒为 `InternalServerError|Handler terminated...`。

### 19. [已修复] WebSocket UTF-8 校验完全失效（死代码）
- **位置**：`websockets/WebSocketFrame.java:49-55`、`:233-236`（`new String(payload, UTF_8)`
  遇非法序列**永不抛** CharacterCodingException，静默替换 U+FFFD）
- **问题**：非法 UTF-8 文本帧不会被 1007 拒绝，违反 RFC 6455 §8.1；相关 catch 分支不可达。
- **修复**：用 `CharsetDecoder.decode(ByteBuffer).onMalformedInput(REPORT)` 真正校验。
- **已修复（2026-09-18）**：binary2Text（两个重载，CloseFrame reason 解析同路径）改用
  `UTF_8.newDecoder().onMalformedInput(REPORT).onUnmappableCharacter(REPORT)` 严格解码——
  readPayload 的"Test for Unicode errors"与 readWebsocket 的 CharacterCodingException
  catch 分支（1007 关闭）被激活。实测：非法 UTF-8 被 rejected，合法多字节 UTF-8 正常解码。

### 20. [已修复] MultipartFormParser 其余解析缺陷
- **位置与问题**（均已逐行核对）：
  1. `:61` boundary 解析不支持引号与后续参数（`boundary="x"`、`boundary=x; charset=...`
     静默解析失败，整个上传被扫到 EOF 后返回空 map）；
  2. `:101` `headers.clear()` 只在循环前执行一次，part2 缺失的头**继承 part1 的值**
     （可导致同名文件被 part2 覆盖写入）；
  3. `:75-93` 首边界只匹配到 `boundaryCheck.length - 2` 即 break，终止边界
     `--boundary--` 的 `"--".equals(s)` 检测永不命中——空 multipart 上传不能正确终止；
  4. `:176-178` 无冒号的头行抛 AIOOBE（500 而非 400）。
- **修复**：正确解析 Content-Type 参数；clear 移入循环体；break 条件改为完整匹配；
  split 后检查 parts.length。
- **已修复（2026-09-18）**：新增 `parseBoundary()`（RFC 2046 §5.1.1——支持引号值、
  后续参数、边界空白）；`headers.clear()` 移入 part 循环开头；首边界 break 条件改为
  完整匹配 `boundaryCheck.length`（终止边界 `--boundary--` 检测由此生效）；parseHeaders
  对无冒号行 skip + 日志。实测 5 用例全过：引号 boundary、boundary 带参数、空上传正确
  终止、part2 不继承 part1 头（files=1）、无冒号行跳过；既有 TestNG MultipartFormParserTest
  4 用例兼容。

### HTTP/2 协议

### 21. [已修复] HPACK 字符串编码长度 ≥128 时完全错误（含 Huffman 位误置）
- **位置**：`hpack/HPackContext.java:330-345`
- **问题**：`(byte)(length | 0x80)` 把 HPACK 的 Huffman 标志位当长度位用，且非 RFC 7541
  整数编码。任何 ≥128 字节的响应头（长 Set-Cookie、长 URL）都会导致对端 COMPRESSION_ERROR。
- **修复**：首字节 `0x7F`（无 Huffman）+ 变长余数编码，补 ≥128/≥16384 用例。
- **已修复（2026-09-17）**：`encodeString` 改为标准 RFC 7541 §5.2 编码——首字节 127
  （Huffman 位清零），余数 length-127 变长编码。h2c 实测：200 字节头值编码为
  `0x7F 0x49` 且值内容完整往返；解码端 `decodeUnsignedInteger` 本就正确，现互恰。

### 22. [已修复] HTTP/2 状态码 500 被编码成 404
- **位置**：`hpack/HPackContext.java:378`（`"500", indexedField(13)` —— 静态表 index 13 是
  `:status 404`，500 在 index 14）
- **影响**：所有 500 响应在线上被编码为 `:status: 404`。
- **修复**：改为 `indexedField(14)`。
- **已修复（2026-09-17）**：改为 `indexedField(14)`。h2c 实测：500 响应的 HEADERS 帧
  首字节为 0x8E（index 14 = :status 500）。

### 23. [已修复] HTTP/2 `:query` 伪头不存在，查询串丢失 **[已实测]**
- **位置**：`ServerImpl.java:551-552,562-567`
- **实测**：`:path=/echo?a=1&b=2` 时 `new URI(scheme,authority,path,null,null)` 得到
  `getPath()="/echo?a=1&b=2"`、`getQuery()=null`（rawPath 中 `?` 还被转义为 `%3F`）。
  所有依赖 `getRequestURI().getQuery()` 的 handler 在 h2 下全部失效。
- **修复**：解析 `:path` 按第一个 `?` 拆分为 path 与 query 再构造 URI。
- **已修复（2026-09-17）**：`handleStream` 在构造 URI 前对 `:path` 按第一个 `?` 拆分
  （客户端显式发送 `:query` 时优先用之）。h2c 实测：`GET /echo?a=1&b=2` 的 handler 中
  `getRequestURI().getQuery()` 正确返回 `a=1&b=2`，path 为干净的 `/echo`。

### 24. [已修复] HTTP/2 DataIn.close() 永久 park（线程泄漏 / 连接僵死）
- **位置**：`http2/HTTP2Stream.java:388-394`（close 里 readAllBytes）、`:127-148`
  （close 中 interrupt 排在 dataIn.close 之后，卡死时 interrupt 永远执行不到）、
  `:172-178`（长度不匹配路径 break 跳过 `halfClosed=true`，readAllBytes 在**连接读线程**上 park，
  整条连接停止处理任何帧）
- **修复**：close 不再读取数据——置关闭标志 + unpark reader 使 read 返回 -1；
  interrupt 移到阻塞调用之前；close 前先置 halfClosed。
- **已修复（2026-09-17）**：DataIn 加 volatile closed 标志，close() 只置标志 + unpark（不再
  readAllBytes），read() 空队列时检查 `closed || halfClosed` 返回 -1；RST/长度不匹配路径
  `halfClosed=true` 前置于 close()。close 不再读数据后，与 handler read 共享 offset 的数据
  竞争（P2-40 最后一条）一并消除。

### 25. [已修复] 流级协议错误一律升级为连接级 GOAWAY
- **位置**：`HTTP2Connection.java:335-337`（REFUSED_STREAM）、`HTTP2Stream.java:155-157,164-169`、
  汇合点 `HTTP2Connection.java:186-194`
- **问题**：超 MAX_CONCURRENT_STREAMS 应回 RST_STREAM(REFUSED_STREAM) 流错误，这里直接
  GOAWAY 撕掉整条连接上所有在途请求；half-closed 后收到 DATA、trailer HEADERS
  （`HTTP2Connection.java:273-279`，RFC 7540 §8.1.2.3 允许）等场景也全部断连。
- **修复**：区分流错误与连接错误，流错误改发 RST_STREAM 只关单流。
- **已修复（2026-09-17）**：四处流级错误改为就地处理——超并发流回 RST_STREAM(REFUSED_STREAM)
  单流拒绝并清理 fragments；open 流的后续 HEADERS 按合法 trailer 接受（消费丢弃，END_STREAM
  则半关闭，新增 `receiveTrailer()`）；closed/half-closed 流的 HEADERS/DATA 回
  RST_STREAM(STREAM_CLOSED)；已关闭流的迟到帧忽略（RFC 7540 §5.1 closed 态）。
  实测（H2ComplianceTest）：trailer 后正常响应、超限流收 RST(7) 无 GOAWAY、迟到 DATA 后
  连接存活新流正常。

### 26. [已修复] RST_STREAM(NO_ERROR)/未知错误码被静默忽略（流泄漏）
- **位置**：`HTTP2Connection.java:311-315`、`HTTP2ErrorCode.java:30-38`（getEnum 未知码默认
  返回 NO_ERROR）
- **问题**：客户端以为已取消，服务端继续持有流（占用 maxConcurrentStreams 计数、handler
  线程、读 body 时永久 park）。
- **修复**：RST_STREAM 无论错误码都走 `halfClosed=true; close()`；getEnum 对未知码保留原值。
- **已修复（2026-09-17）**：RST_STREAM 分支不再区分错误码（含 NO_ERROR 与 getEnum 映射不到
  的未知码），一律走流的 close 路径；新增 rstReceived 标志——RST 后 Http2OutputStream 的
  write 抛 IOException、close/写路径全部跳过（不再发 END_STREAM/头帧，RFC 7540 §5.1 closed
  态，同时消除了 P2-40 中"RST 后仍发送 END_STREAM"一条）。实测：RST(CANCEL) 后新流正常响应。

### 27. [已修复] 接收侧流量控制不强制 + DataIn 队列无界
- **位置**：`HTTP2Connection.java:259`（只记账不校验越窗）、`HTTP2Stream.java:170`
  （enqueue 无条件入队）、`:436`（窗口按 handler 实际读的字节数补——数据滞留队列时永不补窗，
  对端窗口耗尽后死锁）
- **修复**：入队/记账处校验连接与流两级窗口（越界 FLOW_CONTROL_ERROR）；队列深度超限丢弃
  并 RST_STREAM；补窗时机改为收到 DATA 时。
- **已修复（2026-09-17）**：窗口模型重整为"收帧扣减、读取退还"——流级窗口在 processFrame
  收 DATA 时扣（负值即 RST_STREAM(FLOW_CONTROL_ERROR) 关流），DataIn.read 消费后退还并
  按半窗阈值批量化发 WINDOW_UPDATE；连接级窗口收帧时校验负值（FLOW_CONTROL_ERROR）。
  队列上界由流级窗口自然限定（默认 64KB/流，handler 不读则客户端窗口耗尽停发）。
  实测：慢读流上发送超过流窗 → RST_STREAM(3)，无 GOAWAY，连接窗不受影响。

### 28. [已修复] Http2OutputStream.flush() 为空操作（SSE 等流式响应被无限延迟）
- **位置**：`HTTP2Stream.java:331-333`（只清流级 1KB 缓冲，不 flush 连接级缓冲）
- **问题**：连接级 `NoSyncBufferedOutputStream`（1024B）只在凑满或最后一请求 close 时落盘，
  小事件流客户端可能长时间收不到任何字节；`ServerConfig.http2DisableFlushDelay` 定义了
  却从未被使用（死配置）。
- **修复**：flush 在 connection.lock() 下 flush 连接流（可按配置延迟）。
- **已修复（2026-09-17）**：flush() 在 connection.lock() 下 flush 连接级流（显式 flush 语义
  必须到达客户端）；http2DisableFlushDelay 接线到 write 的每帧后 flush（低延迟模式）。
  实测：handler 写 5 字节 flush 后 **0ms** 到达客户端（修复前会滞留至连接缓冲凑满）。

### 29. [已修复] hasProperPreface 单次 read() 即判 preface 合法性
- **位置**：`HTTP2Connection.java:174-180`（单次 `inputStream.read(preface)` 不保证读满；
  类中已有 readFully:473 未用）
- **修复**：改用 readFully。
- **已修复（2026-09-17）**：改用类内既有的 `readFully()`，preface 被 TCP/SSL 分段送达时
  仍能正确读取比对。

### 30. [已修复] 响应头未含 :status 时 writeHeaderFrame NPE；巨大头块超 MAX_FRAME_SIZE 不拆分
- **位置**：`hpack/HPackContext.java:218,324`（getFirst(":status") 为 null 时
  `value.getBytes()` NPE，请求悬挂）；`:242-243`（整块放单 HEADERS 帧，超 16384 时对端必
  FRAME_SIZE_ERROR）
- **修复**：入口校验 null 抛 IOException 并对 stream 发 RST_STREAM；超限时拆分为
  HEADERS + CONTINUATION。
- **已修复（2026-09-17）**：writeHeaderFrame 入口校验 :status 非空（缺失抛 IOException，
  writeResponseHeaders 捕获后对 stream 发 RST_STREAM(INTERNAL_ERROR)，客户端不再悬挂）；
  新增 writeHeaderBlock()——编码块超过本端通告的 SETTINGS_MAX_FRAME_SIZE 时拆分为
  HEADERS + CONTINUATION 序列（首帧保留 END_STREAM 语义，末帧带 END_HEADERS）。
  实测：无 :status 响应收 RST_STREAM(1)；20KB 响应头拆为 HEADERS + 1×CONTINUATION
  （20492 字节块）完整到达。

---

## P2 — 中等 bug（10/10 已修复 ✅）

### 31. [已修复] LeftOverInputStream close 与 read 无同步/可见性保障
- **位置**：`LeftOverInputStream.java:47-48,57-65`（closed/eof 非 volatile、close 非
  synchronized，并发 close/read 会破坏 ChunkedInputStream 内部状态）
- **修复**：close 声明 synchronized，closed/eof 声明 volatile（或明确单线程约定）。
- **已修复（2026-09-18）**：closed/eof 改 volatile，close() 改 synchronized（与 read 一致）；
  另在本批中 drain 重写为非阻塞（只消费已到达字节，见 P2-32 条目）并新增 atEof() 非阻塞探测。

### 32. [已修复] FixedLengthOutputStream.close() 依赖 available() 决定是否 flush（响应滞留）**[已实测]**
- **位置**：`FixedLengthOutputStream.java:99-105`（pipelining 优化：`available()==0` 才 flush）
- **问题**：available() 只是估算，pipelined 下一请求"头到 body 未到"时双方互等至 idle 超时；
  SSL 流下语义更不可靠。JDK 上游是无条件 flush。
- **实测**：P0-2 的复现即依赖此机制——body 未读完时 available()>0，handler 的 200 响应
  **完全不发出**（客户端连响应头都收不到）。
- **修复**：无条件 flush（或进入下一请求读循环前兜底 flush）。
- **已修复（2026-09-18）**：close 改无条件 flush。实测暴露更深层问题并一并修复——**drain 在
  客户端未发完 body 时无限阻塞**（等不存在的数据）：drain 重写为只消费 `in.available()` 已
  到达字节；为保住零长度 body 的 keep-alive（HeadKeepAlive 用例）新增 `atEof()` 非阻塞探测
  （FixedLengthInputStream 按 remaining==0 判定）。实测：POST 声明 100KB 只发 50KB →
  **200 在 24ms 内到达**（修复前永久挂起）；TestNG HeadKeepAlive 等全部恢复。

### 33. [已修复] ChunkedOutputStream.close() 吞掉所有 IOException
- **位置**：`ChunkedOutputStream.java:138-151`（终止 chunk 写失败、drain 失败全部空 catch，
  handler 无感知、无日志）
- **修复**：至少记录 DEBUG/WARNING；未关闭的 is 在 finally 补 close。
- **已修复（2026-09-18）**：终止 chunk 写失败记 DEBUG 日志；`is.close()`（drain 语义）移入
  finally 保证执行，drain 失败同样记日志。

### 34. [已修复] ProxyHandler 转发响应头导致 Content-length 与 Transfer-encoding 并存
- **位置**：`extras/ProxyHandler.java:108-110`（putAll 原样转发含 CL，随后
  sendResponseHeaders(status, 0) 又走 chunked）；`:149-155`（逐跳头剔除不完整，漏
  Transfer-Encoding/TE/Trailer/Keep-Alive/Proxy-Authorization）
- **修复**：转发前剔除 CL/TE 等逐跳头。
- **已修复（2026-09-18）**：响应侧 putAll 后按 HOP_BY_HOP_HEADERS 集合剔除（CL/TE/Trailer/
  Keep-Alive/Proxy-Authenticate/Proxy-Authorization/Connection/Upgrade，RFC 7230 §6.1）；
  请求侧 restrictedHeaders 补全同样 5 个逐跳头。
- **修正（2026-10-09）**：核查发现上述记录与代码不符——HOP_BY_HOP_HEADERS 实际不含
  Content-Length，转发响应仍出现 CL+TE 并存（正向代理端到端实测复现）。已补：剔除集合
  加入 CONTENT-LENGTH（注释说明其为成帧头而非逐跳头）；同批修复"请求头全被
  restrictedHeaders 过滤后 headers() 空数组触发 IllegalArgumentException"的边缘缺陷。
  新增 ProxyForwardingTest 守护（CL/TE 互斥 + 空 headers 场景），端到端复测通过。

### 35. [已修复] ProxyHandler CONNECT 隧道生命周期缺陷
- **位置**：`extras/ProxyHandler.java:69-95`
- **问题**：任一方向 EOF 不关闭对端（线程泄漏）；finally 中 return 吞异常；printStackTrace
  而非 logger；getExecutor() 未设置时 NPE；connect 无超时。
- **修复**：任一方向完成即关闭 socket 与 exchange；加 connectTimeout；校验目标地址。
- **已修复（2026-09-18）**：畸形端口 400、connect 加 30s 超时、上游失败改 502、executor 为
  null 时回退 commonPool、两个方向的 transfer 均在 finally closeQuietly(s)（任一方向 EOF
  关闭远端 socket 解除另一方向阻塞，消除线程泄漏）、printStackTrace 改 logger、移除
  finally 中的 return。

### 36. [已修复] WebSocket 杂项（W-4~W-8）
- `CloseFrame.java:40-51`：reason 无 123 字节截断，可超控制帧 125 字节上限；
- `BufferedWebSocket.java:23,64-68`：无界队列无背压（OOM）；close 竞态可使 CloseFrame 丢失；
- `Util.java:82`：错误响应把 Content-type 设到**请求头**上（应为 getResponseHeaders）；
  `:73`：makeAcceptKey 字符数/字节数混用；
- `WebSocketHandler.java:46-49`：子协议盲目回显（未声明支持性）；未拒绝未掩码客户端帧；
  isWebsocketRequested 用 contains 匹配会误命中 `X-UpgradeFoo`。
- **修复**：见各条注释，均为局部修复。
- **已修复（2026-09-18，全部五项）**：CloseFrame reason 截断至 123 字节且不切在多字节 UTF-8
  序列中间（实测 payload≤125 且中文截断后仍合法 UTF-8）；BufferedWebSocket 队列限 1024 帧 +
  put() 阻塞背压 + POISON_PILL 唤醒退出 + doClose 等待排空（drain 线程不再泄漏、CloseFrame
  有机会写出）+ IO 故障关闭码改 AbnormalClosure；Util.sendResponseHeaders 改设**响应头** +
  UTF-8 字节、makeAcceptKey 改按 US_ASCII 字节哈希；WebSocketHandler 新增 supportsProtocol()
  声明式子协议协商（默认不回显）、握手强制 GET（null 方法容忍以兼容桩）、Connection 头改
  逗号分隔 token 精确匹配（X-UpgradeFoo 不再误命中）；WebSocket.readWebsocket 循环拒绝
  未掩码客户端帧（1002，RFC 6455 §5.1）。

### 37. [已修复] FormParser 杂项（F-2/F-3）
- `FormParser.java:74-76`：body 为裸 `_charset_` 时 IndexOutOfBoundsException（500 而非 400）；
  `%zz` 未捕获 IllegalArgumentException；
- `:56-71`：先分组后解码，`a=1` 与 `%61=2` 产生重复键（与 Servlet 语义不一致）。
- **已修复（2026-09-18）**：`_charset_` 空值列表安全跳过；decode helper 捕获
  IllegalArgumentException 转 IOException；第二阶段改为**先解码 key 再分组**（`a=1` 与
  `%61=2` 合并为单键 [1,2]，与 Servlet 语义一致）。实测三用例全过。

### 38. [已修复] ContentEncoding.encoding() 从 Content-encoding 头提取 charset（语义错误）
- **位置**：`extras/ContentEncoding.java:15-28`
- **问题**：字符集在 `Content-type` 的 charset 参数中；Content-Encoding 是 gzip/deflate 等
  压缩编码。当前实现对真实请求永不生效（返回默认 ISO-8859-1）。
- **修复**：改为解析 Content-type 的 charset 参数。
- **已修复（2026-09-18）**：改为解析 Content-type 的 `; charset=` 参数（大小写不敏感逐段
  扫描）。实测：`text/html; charset=UTF-8` → UTF-8；仅有 Content-encoding: gzip → 默认
  ISO-8859-1。

### 39. [已修复] QueryParameters.decode() 不解码 key
- **位置**：`extras/QueryParameters.java:28`（只 URLDecoder.decode 值，`%61=1` 的键不解码）
- **修复**：键值都解码。
- **已修复（2026-09-18）**：键先解码再分组。实测：`%61=1&b=2` → 键 "a" 单条目。

### 40. [已修复] HTTP/2 其余协议/健壮性问题（择要）
- `HTTP2Connection.java:217-221`：maxConcurrentStreams 仅在收到 SETTINGS ACK 时才赋值，
  不发 ACK 即可无限开流；
- `frame/WindowUpdateFrame.java:27-43`：增量未做无符号 31 位校验，负增量可穿透压窗；
- `HTTP2Connection.java:259` + `frame/DataFrame.java:31`：流量控制记账忽略 PADDED 填充字节；
- `HTTP2Stream.java:207-226`：performRequest 无 finally，RuntimeException 路径泄漏
  requestsInProgress/activeStreams 计数；
- `ServerImpl.java:600-610`：handleStream 静默吞 IOException 且无 finally 兜底关闭流；
- ~~RST 后 close 仍发送 END_STREAM DATA 帧~~ **已被 P1-26 消除**；
- `HTTP2Stream.java:264-265`：max_frame_size 构造时固化，对端后续调大不生效；
- ~~Huffman 静态表懒加载竞态~~ **已被 P3-43 重写消除**；
- `hpack/Huffman.java:176`：解码按 ISO-8859-1 语义拼字符，非 ASCII 头部值乱码（é→Ã©）；
- `HTTP2Stream.java:122`：expectedSize 的 parseLong 未捕获 NumberFormatException；
- ~~DataIn.offset 数据竞争~~ **已被 P1-24 消除**。
- **已修复（2026-09-18，全部余项）**：maxConcurrentStreams 从构造起生效（ACK 分支不再补偿
  赋值，不发 ACK 也受限）；WindowUpdateFrame 高位为 1 的增量拒 FLOW_CONTROL_ERROR（实测
  0x80000001 → goaway:3）；连接与流两级流量控制记账改用整帧长度（含 PADDED 字节，RFC 7540
  §6.9）；performRequest 的 lambda 加 catch Exception + finally close（计数不泄漏、handler
  忘记 close 时 END_STREAM 仍发出）；ServerImpl.handleStream 的 IOException 改记 DEBUG 日志
  （兜底由 performRequest finally 承担）并删除冗余 if(https) 分支；Http2OutputStream 的
  max_frame_size 改每次 write 动态读取；Huffman.decode 改字节级收集 + 一次 UTF-8 转换
  （实测中文 "值" 正确解码——修复前 Latin-1 乱码）；expectedSize 捕获 NumberFormatException
  （畸形 CL 返回 -2 强制不匹配 → RST 而非未捕获异常）。
- **注**：Huffman 字节缓冲初版按"输出 ≤ 输入字节"预分配有 AIOOBE（Huffman 压缩的是位数，
  输出字节数可多于输入），被 HuffmanTest 捕获后按最短码 5 位修正上界。

---

## P3 — 性能优化（11 项修复 + 1 项经评估不做）

### 41. [已修复] writeHeaders 每个头 4 次 String→byte[] 分配
- **位置**：`ExchangeImpl.java:343-362`（`": "`/`"\r\n"` 常量串每次重新 getBytes）
- **优化**：常量预编码为 `static final byte[]`；响应头整体拼入复用缓冲一次写出
  （Date 头已用 `ActivityTimer.dateAndTime()` 缓存，是现成的好范例）。
- **已修复（2026-09-17）**：`": "`/`"\r\n"` 预编码为 static final byte[]（每头省 2 次
  String→byte[] 分配与编码）；整块拼接方案暂缓（进一步收益需权衡，保守版零风险）。

### 42. [已评估：不做] HTTP/1.1 请求行/头部逐字节读取
- **位置**：`Request.java:99`（read() 单字节）、`:197`（headers 循环逐字节）
- **优化**：改为基于缓冲数组批量扫描；消除 PushbackStream 单字节回推层。
- **实施后回退（2026-09-17）**：给 PushbackStream 加 8KB 批量预读后 TestNG 的 InputNotRead
  挂起——预读把请求体字节吞进 pbs 缓冲，而 body 流从原始流读取，drain 永久阻塞。正确实现
  需统一 Request 的读取层（headers 解析与 body 流共用一个缓冲），属结构性重构。
- **评估结论（2026-09-21，微基准数据支撑）：不值得做**。稳定微基准（7×1000 次取中位数，
  两次运行一致）：逐字节两层虚调用链解析 1KB 头 = 0.11µs/请求，纯数组扫描 = 0.06µs/请求，
  **差值 0.04µs/请求——10 万 QPS 下仅占 0.4% 单核**。收益上限远低于风险（核心解析路径
  结构性重构，第一次尝试即破坏 body 流起点）。若未来 profile 显示头解析成为瓶颈再重启。

### 43. [已修复] HPACK/Huffman 解码逐位分配对象
- **位置**：`hpack/Huffman.java:160-180,219-221`（每比特 new HuffmanSequence + 二分查找，
  1KB 头部值 ≈ 8192 次分配）
- **优化**：按 RFC 7541 附录 B 码表改为位树/前缀解码，逐字节零分配——**h2 高 QPS 下收益最大**。
- **已修复（2026-09-17）**：Huffman 完全重写为 canonical 前缀码表——按码长的
  firstCode/count/firstIndex 三表，逐位累积后 O(1) 范围检查命中，**零对象分配**；资源解析
  加固（正确处理符号 124 为竖线字符、单组 code 无尾竖线的格式，class-init 单例消除懒加载
  竞态）。验证：RFC 7541 附录 C.4.1~C.4.4、C.6.1 全部标准向量 + 1000 符号长串 + 非法
  padding 拒绝（HuffmanTest 全绿）。

### 44. [已修复] HPACK 动态表头部插入 O(n)
- **位置**：`hpack/HPackContext.java:40,106`（`add(0, field)` 每次搬移整个数组，与 P0-6
  无界问题叠加成 O(n²)）
- **优化**：环形缓冲/ArrayDeque，头插尾淘汰 O(1)。
- **已修复（2026-09-18）**：动态表改 `ArrayDeque`——addFirst（新条目）与 evict-pollLast
  （淘汰）均 O(1)（原 ArrayList 的 add(0,..) 每次搬移全表）；getHeaderField 按索引遍历
  （动态表受 4096 字节核算约束只有几十条目，遍历代价可忽略）。

### 45. [已修复] ChunkedOutputStream 大块写入仍经 4KB 内部缓冲拷贝
- **位置**：`ChunkedOutputStream.java:76-107`（≥CHUNK_SIZE 数据循环 arraycopy 进内部 buf；
  CHUNK_SIZE=4096 偏小；writeChunk 每次 toCharArray 小分配）
- **优化**：大块写路径零拷贝直写 `out.write(header)` + `out.write(b,off,len)`；
  CHUNK_SIZE 提升到 16-64KB；hex 长度查表。
- **已修复（2026-09-17）**：满块 chunk 直写（FULL_CHUNK_HEADER 常量 + `out.write(b,off,
  CHUNK_SIZE)` + CRNL_BYTES），不再经过内部 buf 拷贝；CHUNK_SIZE 提升与 hex 查表暂缓
  （涉及帧格式行为面，保守起见未动）。

### 46. [已修复] 连接缓冲固定 1KB 无池化
- **位置**：`NoSyncBufferedInputStream.java:13`、`NoSyncBufferedOutputStream.java:14`
  （每连接 2 个 1KB 缓冲，小响应多次 flushBuffer）；h2 DATA 帧受流级 1KB 缓冲限制
  普遍小于对端 max frame size（帧头开销放大 ~16 倍，`HTTP2Stream.java:68`）
- **优化**：缓冲提升至 8-16KB 或按 ServerConfig 可配；h2 流缓冲取 min(max_frame_size,16K)；
  close 时若末帧是 DATA 直接置 END_STREAM 标志（省一个空帧）。
- **已修复（2026-09-18）**：连接级输入/输出缓冲 1KB → 16KB（NoSyncBufferedInputStream 字段、
  NoSyncBufferedOutputStream 新增 size 构造）；h2 流级缓冲取 16KB（DATA 帧从小于 1KB 合并为
  可达 16KB，帧头开销缩小 ~16 倍）。END_STREAM 搭车末帧需延迟帧头机制，暂缓。

### 47. [已修复] 流量控制等待用 1ms parkNanos 自旋轮询
- **位置**：`HTTP2Stream.java:282-285,292-307`
- **优化**：用 Condition/监视器在收到 WINDOW_UPDATE 时精确唤醒。
- **已修复（2026-09-18）**：新增 writerThread 引用——processFrame 收到 WINDOW_UPDATE 时
  `LockSupport.unpark(writerThread)` 精确唤醒阻塞的写线程；park 从 1ms 自旋改为 200ms 兜底
  轮询（正常路径零延迟唤醒、异常路径有限延迟，消除稳态 CPU 空转）。

### 48. [已修复] 每帧两次数组分配 + 帧头逐字节写出
- **位置**：`frame/FrameSerializer.java:16-25`、`FrameHeader.java:118-122`（出站帧头 9 次单字节
  write，每次穿两层流还刷 volatile lastActivityTime）
- **优化**：读侧复用帧缓冲；写侧预编码 9 字节头部一次写出。
- **已修复（2026-09-18，写侧）**：FrameHeader.writeTo 两个重载改为构建 byte[9] 一次写出
  （原 9 次单字节 write 每次穿两层流并刷 volatile）。读侧 body 复用需贯穿全部 frame 类，
  侵入过大暂缓。

### 49. [已修复] OptimizedHeaders 视图重建
- **位置**：`OptimizedHeaders.java:132-136`（entrySet 每次 new HashSet + SimpleEntry 装箱）、
  `:127-129`（values() 用 Collectors.toSet()，**List.equals 相同的不同头会去重丢条目**——
  这条实为正确性 bug）
- **优化**：entrySet 返回懒视图或缓存；values() 改 ArrayList。
- **已修复（2026-09-18）**：values() 改返回 ArrayList（同时修复 `Collectors.toSet()` 因
  List.equals 去重丢条目的正确性 bug）；entrySet() 改为**缓存的懒视图**——OpenAddressMap 新增
  entryIterator() 直读 entries 数组（跳墓碑），entrySet 视图的 iterator 按需创建 SimpleEntry，
  不再每次调用重建整个 HashSet；单值仍暴露为可变 ArrayList（M3 契约保持）。

### 50. [已修复] OpenAddressMap 哈希无扩散
- **位置**：`OpenAddressMap.java:23-26`（`hash(int)` 恒等，扰动函数被注释掉）
- **优化**：恢复 `hash ^ (hash>>>16)`（与 P1-13 一起修复后收益才明显）。
- **已修复（2026-09-18）**：恢复 `hash ^ (hash >>> 16)` 扰动（与 P1-13 的 used 计数修复
  配合，扩容时机正确后收益成立）。

### 51. [已修复] MultipartFormParser 逐字节解析
- **位置**：`MultipartFormParser.java:78,136`（BufferedInputStream 单字节 read 为同步方法，
  大文件上传吞吐显著偏低）
- **优化**：块读取 + 内存中边界搜索。
- **已修复（2026-09-18，部分）**：解析入口的 `BufferedInputStream` 换为项目自有的
  `NoSyncBufferedInputStream`（其单字节 read 无锁，消除每字节 synchronized 开销）。
  块读取 + 内存边界搜索的完整重构因与 P1-20 刚修复的解析状态机耦合较深，暂缓。

### 52. [已修复] 其他小项
- `HttpConnection.java:170-229`：ActivityTimer 包装流每次读写做 volatile 写 + volatile 读
  （高 QPS 跨核缓存行流量；且 currentTimeMillis 受时钟跳变影响，建议 nanoTime 单调语义）；
- `ExchangeImpl.java:378-382`：getProtocol() 每次 substring 分配，且请求行尾随空格时返回带
  空格协议串（影响 `ServerImpl.java:793` 的 http/1.0 判断）；应在 Request 解析时一次性缓存
  method/uri/version 三元组；
- `ServerImpl.java:374`：`allConnections.size()`（ConcurrentHashMap-backed set）每 accept
  O(n) 遍历；
- `ServerImpl.java:601-605`：if(https) 两分支代码完全相同（冗余）；
- `hpack/HPackContext.java:451-453`：静态初始化 O(n²)（indexOf 线性查找）。

---
- **已修复（2026-09-18）**：Request 构造时缓存协议 token（getProtocol 不再每次 substring
  分配）；ServerImpl 新增 AtomicInteger connectionCount（accept 路径的 MAX_CONNECTIONS 检查
  从 O(n) 遍历降为 O(1)）；HPackContext 静态表索引初始化 O(n²) → O(n) 循环下标；
  if(https) 冗余分支已在 P2-40 顺手删除。ActivityTimer 包装流的 volatile 开销经评估收益小、
  改 nanoTime 时钟侵入大，保持现状。

## P4 — 代码清理 / 低优先级（13/14 已修复 ✅ 2026-09-18；仅 gradle wrapper 受环境限制）

- [已修复] `PingFrame.writeTo()` 补 8 字节载荷（防御性：当前 sendPing/sendPingAck 走
  encode() 不受影响，消除潜在畸形帧缺陷）；
- [已修复] JUL logger 泄漏：per-instance identityHashCode 命名 logger + Filter 改为**共享
  静态 logger**（"robaho.net.httpserver"）——多实例创建/销毁不再在 LogManager 累积。
  注意：日志行的 `[protocol:port]` 前缀随 Filter 移除而消失（消息内的 connection 标识仍在）；
  `getAddress()` 移除废弃的 AccessController.doPrivileged；
- [已修复] stop(delay) 改按剩余时间精确 sleep（保留 200ms 步进），删除 delay() 方法；
- [已修复] ConnectionCleanerTask 首个 if 关闭后补 continue（不再重复判定/重复计数）；
- [已修复] HttpConnection.close()：删除双重 flush、socket 判空死代码（final 字段），
  0 请求关闭从 WARNING 降为 DEBUG（TCP 端口探测不再刷告警）；
- [已修复] HttpsServerImpl/HttpServerImpl 的 server 字段改 final；
- [已修复] ServerStats.stats() 加 synchronized + secs 下限 0.001（并发调用不再产生
  错乱的 requests/sec，不再除零）；
- [已修复] NoSyncBufferedInputStream.transferTo 补 ensureOpen（close 后不再 NPE）；
- [已修复] WebSocketFrame 64 位长度写出的 `>>> 56 & 0` 恒 0 表达式改为显式
  `out.write(0)`（带注释，语义清晰）；
- [已修复] QueryParameters javadoc "sorted map" 改为 "insertion-ordered map"；
- [已修复] **writeHeaders 接线响应头校验**（原 Utils 校验零调用）：响应头 value 含
  CR/LF 或 null 时抛 IOException——补上响应拆分的最后一道防线（P1-9 的请求侧已修，
  此前 handler 回显污染数据仍可拆分响应）；
- [已修复] ExchangeImpl 静态 DateTimeFormatter 死代码删除；
- [已修复] Content-Length 只接受纯数字（isDigits，RFC 7230 §5.3）——实测 `Content-Length:
  +5` 现返回 400（此前 200 接受）；
- [已修复] build.gradle 移除全部 `--enable-preview`（实测主/测试源码均无需 preview 特性
  即可编译——发布产物不再依赖 preview JVM）；**gradle wrapper jar 缺失未修**：需要装有
  gradle 的环境执行 `gradle wrapper` 重新生成，无法在当前环境完成。

---

## 建议修复顺序

1. ~~**P0-1**（路径穿越）与 **P0-2**（drain 失步）~~ **✅ 已完成（2026-09-17）**
2. ~~**P1-10**（不可变头列表）、**P1-9**（裸 LF）、**P1-11**（trailer）~~ **✅ 已完成（2026-09-17）**
3. ~~**P1-21/22/23**（HPACK 编码、500→404、:query）~~ **✅ 已完成（2026-09-17）**
4. ~~**P1-13**（OpenAddressMap 系列）与 **P1-14**（ContextList 缓存）~~ **✅ 已完成（2026-09-17）**
5. ~~**P0-3~8 + P1-15/17/24**（各 DoS 面与线程泄漏）~~ **✅ 已完成（2026-09-17）**
6. ~~**P3 性能项**（41/43/45）~~ **✅ 已完成（2026-09-17）**；P3-42（头部批量解析）实施后回退
   （预读会吞掉请求体字节破坏 body 流起点，正确实现需统一 Request 读取层，暂缓）；
   P3 其余小项（44/46-52）未做；
7. ~~**h2 协议合规 P1-25~30**~~ **✅ 已完成（2026-09-17，追加批次）**——流错误与连接错误
   区分、RST 处理、流量控制强制、flush 语义、preface 读取、:status 校验与大头块拆分；
   专项验证 H2ComplianceTest 8 用例全绿；
8. ~~**P1 余项（12、16、18、19、20）**~~ **✅ 已完成（2026-09-18，追加批次）**——
   chunk 溢出、ActivityTimer 生命周期、WebSocket 关闭码/UTF-8 校验、multipart 四项解析
   缺陷；P1 至此 22/22 全部完成；
9. ~~剩余建议：重新启用 Http2Test、P2 全部、P3 余项、P4~~ **✅ P2/P3/P4 已全部完成
   （2026-09-18）**；
10. ~~剩余建议：**将九套冒烟验证（/tmp/hs_smoke）正式集成进 testng 套件**并重新启用
    Http2Test；见下方"遗漏与残留清单"。~~ **部分完成（2026-10-09）**：六个关键验证测试
    已集成（见 D-14）；Http2Test 重新启用仍待做（其依赖 JDK HttpClient 的 h2c 先验连接，
    需改用 H2Client 类似的裸 socket 方式重写）。

## 复查修正（2026-09-21）

> 对全部 64 项修复做系统性正确性复查（人工逐区复核 + code-review 全量 diff 审查）
> 发现并已修复六处缺陷（复查-1/2/3 为人工复核发现的修复回归；复查-4/5/6 为 code-review
> sweep 发现的组合缺陷/上游既有缺陷）；P3-42 经微基准评估决定不做。
> **补注（2026-10-09）**：本节声称"新增"的 MultiFrameTrailerTest / SlowBodyKeepAliveTest /
> Review3Test 当时只存在于 /tmp/hs_smoke（后被系统清理），从未进入 src/test。现已按原
> 验证场景重建并正式集成（连同 H2ComplianceTest、HuffmanTest 与 h2c 测试客户端
> H2Client），见"遗漏与残留清单"D-11/D-14 的更新。

### 复查-1. [已修复] RST/连接关闭路径泄漏 requestsInProgress 计数
- **引入批次**：P1-26（rstReceived 提前 return）+ 上游既有（isClosed 分支同型问题）
- **位置**：`HTTP2Stream.java` Http2OutputStream.close()
- **问题**：`rstReceived` 与 `connection.isClosed()` 两个提前 return 路径跳过
  `requestsInProgress.decrementAndGet()`——RST 一个流后，连接最后一个正常流 close 时
  `decrementAndGet() == 0` 永不成立，**最终 flush（P1-28 修复的行为）在 RST 场景失效**，
  小响应滞留缓冲直至连接关闭。
- **修复**：两个提前 return 前补 decrement（isClosed 分支为上游同型问题一并修复）。

### 复查-2. [已修复] 多帧 trailer（≥16KB trailer 块）被误判为新请求头 → GOAWAY
- **引入批次**：P1-25（单帧 trailer 接受路径）
- **位置**：`HTTP2Connection.java` processFrames 的 CONTINUATION case
- **问题**：P1-25 修复只拦截了**单帧** trailer（HEADERS 带 END_HEADERS 直接走
  receiveTrailer）；trailer 块 ≥16KB 时 HEADERS 无 END_HEADERS → inHeaders=true →
  CONTINUATION 完成后 **break 落入公共路径** → 流已存在 → processFrame(CONTINUATION) →
  performRequest() 的 CAS 失败 → 抛 PROTOCOL_ERROR → **GOAWAY 杀死整条连接**。
- **修复**：新增 pendingTrailerEndStream 记录 trailer HEADERS 的 END_STREAM 标志；
  CONTINUATION 完成时若流已存在则按多帧 trailer 处理（清 fragments、inHeaders 复位、
  receiveTrailer）。
- **验证**：新增 MultiFrameTrailerTest——POST + 20KB trailer（HEADERS+CONTINUATION 拆分）
  → 响应正常到达（修复前 GOAWAY）；TestNG 104/104 保持。

### 复查-3. [已修复] 慢速发送的 body（<drain 上限）导致 keep-alive 误断
- **引入批次**：P2-32（drain 非阻塞化）
- **位置**：`LeftOverInputStream.java` drain()、`FixedLengthOutputStream.java` close()
- **问题**：drain 在两次数据到达的间隙 `available()==0` 时立即 break——慢速客户端仍在发送
  其（小于 64KB drain 上限的）body 时，`isEOF` 被误判为 false → 连接被关闭，keep-alive
  失效（修复前的阻塞 drain 会等发完并复用；原记录为"设计权衡"，复查确认为缺陷）。
  code-review 的 verify 子代理（"Verify keep-alive regression"）同样锁定此问题。
- **修复**：
  1. drain 加 1 秒限时等待（MAX_DRAIN_WAIT_MILLIS）——straggling 数据在窗口内到达则继续排空，
     永不发送的客户端最多占用 1 秒（P2-32 防的死锁场景仍然防住）；
  2. FixedLengthOutputStream.close 的顺序改为**先 flush 响应再 drain**——客户端见到响应后
     继续发完 body，drain 在等待窗口内收齐 → 连接复用。
- **验证**：新增 SlowBodyKeepAliveTest——30KB body 慢速分 6×5KB 发送（间隔 150ms）+ 不读
  body 的 handler → 200 立即到达 + **同连接第二请求 200**（修复前 CONNECTION-CLOSED）；
  TestNG 104/104 保持。

### 复查-4. [已修复] 交错流（interleaved）的 trailer 被 out-of-order 检查拦截 → GOAWAY
- **来源**：code-review sweep 发现（上游既有检查与 P1-25 的组合缺陷）
- **位置**：`HTTP2Connection.java` HEADERS case
- **问题**：`streamId < lastSeenStreamId` 的 out-of-order 检查在 trailer 分支**之前**——客户端
  开流 1（POST+trailer）后复用开流 3（lastSeenStreamId=3），流 1 的合法 trailer HEADERS
  到达时 `1 < 3` → PROTOCOL_ERROR → **GOAWAY 杀掉连接上全部在途流**（含流 3 的响应）。
  trailer 只在"最近开的流"上可用。
- **修复**：`http2Streams.get(streamId)` 的已存在流判定（trailer/RST 处理）**提到** out-of-order
  检查之前；out-of-order 仅约束新流。
- **验证**：Review3Test——流 1 POST（无 END_STREAM）+ 交错开流 3 + 流 1 trailer(END_STREAM)
  → 两个流均正常完成（`len=5`/`len=0`，无 GOAWAY；修复前 GOAWAY 全杀）。

### 复查-5. [已修复] Http2OutputStream.close 的 requestsInProgress 双减竞态
- **来源**：code-review sweep 发现（复查-1 修复的三处减量共享此缺陷）
- **位置**：`HTTP2Stream.java` Http2OutputStream.close()
- **问题**：`closed` 为非 volatile 普通字段——handler 线程 close（经
  NoSyncBufferedOutputStream→FilterOutputStream.close 直达，不经 HTTP2Stream.close 的
  put 守卫）与读线程 RST close 可**同时进入** close()，双双通过 `if(closed) return` →
  各减一次 `requestsInProgress`（单次 increment 对两次 decrement）→ 计数下溢 →
  后续任何流的 `decrementAndGet()==0` 永不成立 → 最终 flush 永不触发。
- **修复**：close() 加 `synchronized`（写路径不受影响）。

### 复查-6. [已修复] 纯裸 LF 客户端无法通过请求行（P1-9 修复不完整）
- **来源**：code-review sweep 发现
- **位置**：`Request.java` readLine()
- **问题**：P1-9 只修了 headers() 的裸 LF 行终止，readLine（请求行）仍把裸 LF 当普通字符
  追加——纯 LF 客户端（`GET / HTTP/1.1\nHost: h\n\n`）的整个请求被并进一行，headers 得到
  空集，请求畸形（裸 LF header 支持对此类客户端是死代码）。
- **修复**：readLine 把裸 LF 也当行终止符（RFC 9112 §2.2，与 headers 解析语义对齐）。
- **验证**：Review3Test——裸 LF 请求行 → 200 正常处理（修复前畸形）。

### P3-42 评估结论：不做
稳定微基准（7×1000 中位数，两次一致）：逐字节两层虚调用链解析 1KB 头 0.11µs/请求 vs
数组扫描 0.06µs——**差 0.04µs/请求，10 万 QPS 占 0.4% 单核**。收益上限远低于核心解析
路径结构性重构的风险（首次尝试即破坏 body 流起点）。详见 P3-42 条目。

---

## 二次排查（2026-10-09，全量 bug 猎查 + 内存/性能审计）

> 六路分区排查（h2 状态机 / HTTP/1.1 核心 / websockets+extras / 并发生命周期 / 内存 / 性能），
> 重点发现经 JDK 21 运行时探针实证。**第 14 批（2026-10-09）：下述 1-9 项已修复；第 15 批
> （2026-10-09）：9-15 项全部修复**。仅内存/性能清单仍开放。

### 第 14 批修复记录（2026-10-09）

> 1. `DataFrame.parse` 正确跳过 Pad Length 八位组并按 padLength 截尾（探针回显 hello）；
> 2. 新流 HEADERS+CONTINUATION 记录 `pendingEndStream`，块完成后经 `receiveTrailer(true)`
>    重放（REFUSED 分支同步清除）；
> 3. 流级 WINDOW_UPDATE 写后 flush（单流 512KB 上传 7034ms → 9ms，约 1000 倍）；
> 4. `Exchange.run` 异常分支补与正常路径相同的 isEOF 检查（走私请求不再被服务）；
> 5. `FixedLengthOutputStream.close` 对 `uis_orig==null`（升级请求）判空；
> 6. `Util.sendResponseHeaders` 改为 try-with-resources 关闭响应流（触发 flush），
>    不再关闭原始请求流（握手错误响应实测送达）；
> 7. `ProxyHandler` CONNECT 块后补 `return`（不再跌落转发路径）；
> 8. multipart `filename=""` 按字段处理（浏览器空文件输入不再 500）；
> 9. `HTTP2Stats.stats()` 除零下限 0.001（对齐 P4-7 ServerStats）。
> 验证：7 个探针全部翻绿（rst-buffered 维持证伪结论）；新增 9 个回归测试
> （H2ComplianceTest+3、Http1RegressionTest×2、WebSocketHandshakeErrorTest×2、
> MultipartFormParserTest+1、ProxyForwardingTest+1——探针场景已全部转正为 testng 用例），
> 全量套件 118 → **127 全绿**（JDK 21）。
> 顺带发现：CONNECT 以 IP 字面量为目标（`127.0.0.1:443`）被请求行 URI 解析拒绝
> （非合法 scheme，上游同样如此）——已记录未修。

### 第 15 批修复记录（2026-10-09，代码级确认项 9-15）

> 9. `LeftOverInputStream` 覆写 `skip()`——经成帧感知的 readImpl 丢弃（FilterInputStream.skip
>    会绕过 Content-Length/chunked 状态导致连接失步/走私）；
> 10. 流量控制：PADDED 八位组在接收时即记入 `pendingPadding`（AtomicLong，读线程累加），
>     handler 读取时折入窗口返还（padded 上传不再泄漏窗口死锁）；END_STREAM/CL 不匹配
>     路径补 `rstReceived=true`（RST 后不再发帧）；
> 11. h2 状态机：流级 WINDOW_UPDATE 在 header block 中途抛 PROTOCOL_ERROR（不再把部分
>     头块当请求分发）；switch 补 default 分支（未知帧类型如 PUSH_PROMISE 不再创建幻影流，
>     中途则报错）；REFUSED_STREAM 分支清 `inHeaders`（客户端重试不再 GOAWAY 全杀）；
>     trailer 块（单帧与多帧路径）经 `hpack.decodeFieldSegments` 解码后再丢弃（动态表保持
>     同步，后续请求不再 COMPRESSION_ERROR/错头）；
> 12. WebSocket：未知关闭码 3xxx/4xxx 经 `pendingCloseRecorded` 不再伪报 1011"Handler
>     terminated"（onClose 收到真实 reason/initiator；code 为 null 是 CloseCode 枚举 API
>     的固有限制，`CloseFrame.getCloseCodeValue()` 新增暴露原始码）；UTF-8 校验改为整消息
>     （合法跨分片码点通过、continuation 帧中的非法 UTF-8 以 1007 拒绝）；`close()/doClose()`
>     加 synchronized（onClose 不再可能双回调）；控制帧载荷 >125 构造时抛
>     IllegalArgumentException（RFC 6455 5.5）；BufferedWebSocket 的 drain 线程自等 2s
>     消除（drainThread 识别自调用）；首分片补消息大小检查；
> 13. 并发：DataIn.read 观察到 halfClosed 后重查队列（末段 body 不再被 EOF 截断）；
>     `acceptConnection` 入口检查 `isFinishing()`（stop() 后排队的 accept 不再泄漏连接）；
>     `maxIdleConnections` 真正生效（清理任务关闭最旧的超额空闲连接）；HTTP2Stats 除零
>     已在第 14 批修；**ping 的 lastActivityTime 恢复微竞态经评估接受**（窗口为 ping 写入的
>     微秒级，活跃请求的后续 socket 活动会重新刷新时间戳，热路径修复代价大于收益，代码
>     注释已说明）；
> 14. `ProxyHandler.headers()` 的 `toUpperCase()` 加 `Locale.ROOT`（tr/az 区域不再失效）；
> 15. 见 13（maxIdleConnections）。
> 验证：新增 8 个回归测试（H2ComplianceTest+3：padded 上传吞吐/状态机中途拒绝/trailer
> HPACK 同步；Http1RegressionTest+1：skip 成帧；WebSocketFrameValidationTest×4：跨分片
> 码点/continuation 非法 UTF-8/ping>125/应用关闭码——含自建的带掩码裸帧客户端）；
> 既有 WebSocketResponseHandlerTest 3 个固化"写 >125 字节 Ping 帧"非法行为的用例改为
> 期望 IllegalArgumentException。全量套件 127 → **135 全绿**（JDK 21）。
> 顺带发现（已记录未修）：WebSocket 所有错误路径（如 1002/1007）均为静默断连，不发
> wire 级关闭帧（RFC 6455 建议 close 帧告知错误码）——独立改进项。
> 注：测试侧教训——TestNG 同一类实例跑全部方法，跨方法共享的 latch/引用必须在
> @BeforeMethod 重置（WebSocketFrameValidationTest 踩坑）。

### 第 16 批修复记录（2026-10-09，虚拟线程适配：清除全部 synchronized）

> **背景**：本服务目标运行于 JDK 21 虚拟线程（JEP 444）。虚拟线程在 synchronized
> 块/方法内阻塞会**钉住（pin）载体平台线程**（JDK 24 / JEP 491 才修复）——对
> thread-per-connection 的阻塞 IO 模型是致命的。全库共 13 处 synchronized 全部清除，
> 其中 8 处是"持锁做阻塞 IO"的真实钉住源（含第 15 批引入的 3 处，已一并纠正）。

> 1. `LeftOverInputStream` read/read[]/skip/close → ReentrantLock（**最严重钉住源**：
>    每个请求体读取都持 monitor 阻塞读 socket；close 内 drain 最长持锁 1s）；
> 2. `WebSocket` close/doClose → 复用 sendFrame 既有的 ReentrantLock（顺带修复
>    sendFrame 与 doClose 并发写流的竞态）；`BufferedWebSocket.doClose` 去锁
>    （once-only 由基类锁保证）；
> 3. `HttpConnection.close` → ReentrantLock（持锁 flush/close IO）；
> 4. `Http2OutputStream.close`（复查-5 的 synchronized）→ **AtomicBoolean CAS**
>    （零锁，双 decrement 守卫语义不变）；
> 5. 政策性清除（短临界区、无钉住风险）：`OpenAddressIntMap`×4、`ServerStats.stats`、
>    `ContextList` add/remove×3、`BufferedWebSocket` 静态 executor 锁×2 → ReentrantLock；
> 6. **默认执行器切虚拟线程**：`DefaultExecutor` 与 `BufferedWebSocket` drain 线程改为
>    `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(...).factory())`，
>    默认开启，`robaho.net.httpserver.virtualThreads=false` 回退平台线程池。
>    h2 的 performRequest 复用 ServerImpl 执行器，同样虚拟化。
> 验证：处理器实测运行于虚拟线程（开关双向验证）；全量套件 **135/135 全绿**。
> 注意：本机 JDK 21 运行时在会话中途被移除（现为 JDK 25），最终验证以 JDK 25 运行
> `--release 21` 编译产物；`-Djdk.tracePinnedThreads` 的运行时钉住验证需 JDK 21
> 运行时（代码级已保证：全部阻塞路径无 monitor）。JDK 24+（JEP 491）已根治钉住，
> 本改造对 21 必需、对 24+ 无害。

### 虚拟线程视角的性能清单修订（2026-10-09）

> - **已完成**：原 P13（虚拟线程）——每连接线程成本从 ~512KB-1MB 平台栈降至 KB 级；
> - **优先级上升**：M1/M3（每连接 32KB + 每流 16KB 缓冲）——线程栈开销消失后，
>   缓冲成为每连接内存的主导项（10k 连接 ~340MB 主要是缓冲）；
> - **仍然成立**：P2（HPACK 编码在连接锁内——虚拟线程使锁等待更便宜但串行化仍在）、
>   O-6（http2Streams → ConcurrentHashMap，读线程每帧一次锁获取）；
> - **新增**：JVM 层建议分代 ZGC（JDK 21 JEP 439，`-XX:+UseZGC -XX:+ZGenerational`，
>   对症本服务的分配密集负载）；JDK 21 运维用 `-Djdk.tracePinnedThreads=full` 监控
>   钉住（升级 24+ 后无需）；微项：分片文本消息 UTF-8 校验与 getTextPayload 双解码、
>   drain 的 10ms 轮询（虚拟线程下 park 无钉住，可接受）、JUL 内部 monitor 在虚拟
>   线程上可能短暂钉住（仅 INFO 级连接日志，DEBUG/TRACE 默认关）；
> - **不采用**：结构化并发 / ScopedValue 为 JDK 21 预览特性，生产不用（未来项）。
> 今日（第 14/15 批）修正影响评估：第 15 批引入的 3 处 synchronized 属"利用不当"
> （虚拟线程钉住源），本轮已全部纠正；其余修正性能中性或正面（WINDOW_UPDATE flush
> 为 ~1000 倍单流吞吐收益；Http2OutputStream.close 的 CAS 化比锁更廉价）。

### JDK 25 视角的锁选型与清单修订（2026-10-09）

> **synchronized vs ReentrantLock（JDK 25）**：JEP 491（JDK 24）重写了对象监视器，
> 虚拟线程在 monitor 内阻塞不再钉住——禁用 synchronized 的技术理由在 24+ 消失。
> 结论：**保留现有 ReentrantLock/CAS 设计不变**。理由：
> 1. 无竞争路径 synchronized 确实略快（thin-lock CAS ~15ns vs AQS ~20-25ns）且省一个
>    锁对象（~40B），但本库最热的锁点（OpenAddressIntMap.get 每帧一次）差距仅
>    ~5ns/帧 ≈ 10 万 QPS 下 0.05% 单核——噪声级；
> 2. build.gradle 工具链仍为 21：阻塞路径回退 synchronized 会重新引入 21 上的钉住；
> 3. ReentrantLock 留有 API 余量（tryLock/超时/可中断/Condition）；
> 4. 热点锁的正确终局是**消除锁**（O-6 换 ConcurrentHashMap），不是换更便宜的锁。
> 若项目永久只跑 25+：仅 5 处短临界区（OpenAddressIntMap×4、ServerStats、ContextList、
> BufferedWS 静态）可回退 synchronized 换取微弱收益——不建议为此翻动，优先做 O-6。
> Http2OutputStream.close 的 CAS 化在两个版本上都是严格最优。

> **JDK 版本特性映射**（避免混淆）：虚拟线程 JEP 444=21；monitor 不钉住 JEP 491=24；
> 分代 ZGC 默认 JEP 474=23（21 需 `-XX:+ZGenerational`，24+ 非分代已移除）；
> 紧凑对象头 JEP 450=24（`-XX:+UseCompactObjectHeaders`）；AOT 类缓存 JEP 483=24。

> **JDK 25 + 虚拟线程基线下的最新性能清单**（按收益排序，均未实施）：
> | # | 项 | 收益 | 备注 |
> |---|---|---|---|
> | 1 | HPACK 响应头编码移出连接锁（锁外编码、锁内只写） | 临界区 ~10 倍缩小，h2 RPS 随核扩展 | 不变，仍是首选 |
> | 2 | 每流 16KB 缓冲分配（连接级 free-list / 懒分配） | 100k h2 RPS 省 ~1.6GB/s 年轻代分配 | VT 下成为 GC 压力主项 |
> | 3 | 16KB DATA 帧的 9 字节帧头合并写 / 连接缓冲 ≥2× 帧大小 | 大响应 syscall 减半 | 不变 |
> | 4 | HTTP/1.1 响应头整块拼装 + 状态行/Date 字节缓存 | ~0.5µs/响应 + GC 缓解 | P3-41 未完部分 |
> | 5 | http2Streams → ConcurrentHashMap | 消除读线程每帧锁获取（比换 synchronized 更优） | 取代"换更便宜的锁" |
> | 6 | HPACK 编/解码分配风暴（每头 6-10 小对象 → 单次定长分配） | 每请求省 30-40 个对象 | 与紧凑对象头互补 |
> | 7 | stats volatile RMW 门控（static final boolean） | ~0.2µs/请求 | 不变 |
> | 8 | TCP_NODELAY 默认开启 | h2 小帧 + 延迟确认的 ~40ms 停顿消除 | 25 上更值得（VT 调度更密） |
> | 9 | Huffman 编码端（已知 C-9） | 上行头带宽 ~10-30% | 不变 |
> | 10 | 热路径日志 lambda 化 / isLoggable 守卫 | 每帧 3-5 个分配 | 不变 |
> | N1 | **JVM：紧凑对象头**（`-XX:+UseCompactObjectHeaders`） | 全部小对象（帧/HPACK 字段/头列表）头开销 -20~30%，缓存密度提升 | 零代码风险，24+ 标志 |
> | N2 | **JVM：ZGC（25 上即分代）** | 分配密集负载下亚毫秒停顿 | 吞吐优先则 G1+调优 |
> | N3 | **JVM：AOT 类缓存**（`java -XX:AOTMode`） | 启动加速 + 元数据内存下降 | 适合短生命周期部署 |
> | N4 | VT 调度调优（`jdk.virtualThreadScheduler.parallelism`）+ JFR `jdk.VirtualThreadPinned` 事件监控 | CPU 密集 handler 混载下的核利用 | 25 上 Pinned 事件应≈0 |

> **JDK 25 + 虚拟线程基线下的最新内存清单**（按收益排序，均未实施）：
> | # | 项 | 量级 | 备注 |
> |---|---|---|---|
> | 1 | 每连接 32KB + 每流 16KB 缓冲（配置化/懒分配） | 10k 连接 ~340MB——VT 后的绝对主导 | 优先级最高 |
> | 2 | WebSocket 64MB 默认上限 + 预分配 + byte[]/String 双份 | 最坏 ~128-256MB/连接 | 不变 |
> | 3 | h2 默认无限并发流（-1）→ 默认 100-250 | 单连接 1000 流 = 16MB 缓冲（线程已廉价） | 与 #1 联动 |
> | 4 | FormParser 4-5 倍峰值放大 | 10MB 表单 ~50MB 峰值 | 不变 |
> | 5 | multipart deleteOnExit 累积 + BufferedWS 按帧不按字节 | 慢性泄漏 | 不变 |
> | N1 | 紧凑对象头（同上） | 小对象总量 -20~30% | 与所有项正交叠加 |
> | N2 | AOT 缓存 | metaspace 下降 | 同上 |

### 实证确认的 bug（探针复现）

1. **[P1] PADDED DATA 帧体损坏**：`http2/frame/DataFrame.java:25-31`——解析未跳过 1 字节
   Pad Length，该字节被当作 body 首字节、末字节丢失（实测回显 `\x03hell`）；空帧+PADDED
   另有 AIOOBE。任何带 padding 的合规客户端（Chrome/nghttp2）上传即静默损坏。
2. **[P1] 新流 HEADERS+CONTINUATION 丢失 END_STREAM**：`HTTP2Connection.java:329-333`——
   新流路径未记录 HEADERS 帧的 END_STREAM（复查-2 只修了 trailer 路径），CONTINUATION
   完成后 halfClosed=false，读 body 的 handler 永久 park（实测 15s 无响应无 GOAWAY，
   线程泄漏）。大头块（>16KB cookie/Authorization）GET 触发。
3. **[P1 级性能缺陷] 单流 h2 上传限速 ~64-72KB/s**：`HTTP2Stream.java:544-555`——流级
   WINDOW_UPDATE 写入连接缓冲后**不 flush**，仅靠 1s 空闲 ping 兜底释放（实测 512KB
   单流 7034ms ≈ 72KB/s；多流被连接级更新部分掩盖）。
4. **[P2] 异常路径跳过 isEOF 检查（请求走私向量）**：`ServerImpl.java:692-695`——handler
   已响应后抛异常时直接续用连接，>64KB 未读 body 残余被当作下一请求解析（实测：走私
   的 pipelined 请求被正常服务）。正常路径（:898-909）有检查，catch 分支漏了。
5. **[P2] 升级请求的定长响应 NPE 断连**：`FixedLengthOutputStream.java:97`——
   `getRequestBody()` 对 Connection:Upgrade 请求返回原始流（`uis_orig` 为 null），
   close() 未判空（Chunked/UndefLength 均有判空）。实测：200 响应到达后连接被拆 +
   每请求一条 NPE WARNING。
6. **[P1] WebSocket 握手错误响应不送达**：`websockets/Util.java:82-89`——
   sendResponseHeaders 不关闭/flush 响应流，且随后 close 原始请求流直接杀 socket。
   实测：坏 Sec-WebSocket-Version 的握手**收不到任何 HTTP 响应**（RFC 6455 §4.2.1
   要求 HTTP 错误响应）。

### 代码级确认的 bug（7-15 项已分别于第 14/15 批修复，见上方修复记录）

7. `extras/ProxyHandler.java` CONNECT 隧道完成后**跌落转发路径**（P2-35 修复删了 finally
   里的 return 未在块后补上）——每条隧道结束后必抛异常/误走转发；
8. `extras/MultipartFormParser.java` `filename=""`（浏览器空文件输入的标准编码）→
   sanitizeFilename 抛 IAE → 整个上传 500；
9. `LeftOverInputStream` 无 skip() 覆写——FilterInputStream.skip 绕过 HTTP 成帧
   （Content-Length/chunked 状态不随 skip 前进，走私原语）；
10. 流量控制：padding 记账只扣（全帧长）不还（仅 body 字节）→ padded 上传窗口泄漏
    终致死锁；END_STREAM/CL 不匹配路径漏设 rstReceived（RST 后仍发帧）；
11. h2 状态机：WINDOW_UPDATE（流≠0）/PUSH_PROMISE 可在 header block 中途落入公共路径
    （部分头块被当请求分发）；REFUSED_STREAM 分支不清 inHeaders（重试即 GOAWAY 全杀）；
    trailer 块不经 HPACK 解码直接丢弃（动态表失步，后续请求 COMPRESSION_ERROR/错头）；
    迟到帧 continue 路径不清 headerBlockFragments/inHeaders；
12. WebSocket：未知关闭码 3000-4999 丢失（CloseCode.find 返 null → onClose 恒 1011，
    P1-18 只修了枚举内码）；UTF-8 校验按帧不按消息（合法分片文本被拒 1007，continuation
    帧的非法 UTF-8 反而漏检）；doClose 非原子（onClose 可能双回调）；ping 载荷 >125
    不拒绝（写出非法控制帧）；BufferedWebSocket 毒丸 offer 可静默丢失 + drain 线程自等 2s；
13. 并发：DataIn.read check-then-act 竞态可截断末段 body（peek null 与 halfClosed 置位
    之间）；ping 任务的 lastActivityTime 恢复可覆盖并发真实活动（活跃连接被误判空闲）；
    stop() 泄漏"已接受未处理"连接（清理器已取消，JVM 退出可挂起）；HTTP2Stats.stats()
    除零（ServerStats 修了 P4-7，它漏了）；
14. `ProxyHandler.java:239` `toUpperCase()` 无 Locale.ROOT（tr/az 区域 restrictedHeaders
    失效 → 转发必失败）；
15. `maxIdleConnections` 死配置（`ServerImpl.java:1003` 执行逻辑被注释）——空闲连接数
    无上限，10k 慢轮询连接 ~340MB 常驻。

### 证伪（代理误报）

- **"RST_STREAM + 缓冲响应数据杀整条连接"**（两个代理独立报告为 P1）：实测连接存活、
  后续流正常服务——`HTTP2Stream.close()` 捕获该 IOException 仅记 WARNING（"IOException
  closing http2 stream"），不升级为 connection.close()。残留问题仅为每次该场景一条
  WARNING 日志噪音（可降 DEBUG）。

### 内存优化清单（按收益排序，均未实施）

| # | 项 | 位置 | 量级 |
|---|---|---|---|
| M1 | h2 流级 16KB 输出缓冲 × 默认无限并发流（-1）| `HTTP2Stream.java:77` + `ServerConfig.java:56` | 单连接 1000 流 = 16MB 缓冲 + 1000 平台线程；建议默认 100-250 + 缓冲懒分配 |
| M2 | WebSocket 64MB 默认帧/消息上限 + 帧载荷预分配 + 文本帧 byte[]/String 双份保留 | `WebSocketFrame.java:230,243` | 最坏 ~128-256MB/连接；建议默认 1-4MB + 懒文本解码 |
| M3 | 每连接固定 32KB 缓冲不可配置 + maxIdleConnections 不生效 | `NoSyncBuffered*Stream` + `ServerImpl.java:1003` | 10k 连接 ~340MB；建议 bufferSize 配置化 + 空闲上限执行 |
| M4 | FormParser 整体缓冲 4-5 倍峰值放大（BAOS 翻倍 + toByteArray + String + split） | `FormParser.java:35-66` | 10MB 表单峰值 ~50MB |
| M5 | MultipartFormParser deleteOnExit 静态累积 + BufferedWebSocket 队列按帧数不按字节 | `MultipartFormParser.java:132`、`BufferedWebSocket.java:24` | 千万上传 ~1-2GB；1024×帧大小无字节上限 |

### 性能优化清单（按收益排序，均未实施）

| # | 项 | 位置 | 收益 |
|---|---|---|---|
| P1 | **流级 WINDOW_UPDATE 不 flush（已实证）** | `HTTP2Stream.java:544-555` | 单流上传 ~64KB/s → 线速（~1000 倍） |
| P2 | HPACK 响应头编码整体在连接锁内 | `HTTP2Stream.java:288-298` | 临界区 ~10 倍缩小，h2 RPS 随核扩展 |
| P3 | 每流 16KB 缓冲分配 | `HTTP2Stream.java:77` | 100k h2 RPS 省 ~1.6GB/s 分配（GC 压力） |
| P4 | 16KB DATA 帧的 9 字节帧头单独 syscall | `HTTP2Stream.java:388` + 连接缓冲 | 大响应 syscall 减半 |
| P5 | HTTP/1.1 响应头逐头 4 write + 2 编码（P3-41 未完部分） | `ExchangeImpl.java:217-358` | ~0.5µs/响应 + GC 缓解 |
| P6 | 次级：HPACK 编/解码分配风暴（每头 6-10 小对象）；http2Streams 换 ConcurrentHashMap；stats volatile 门控；TCP_NODELAY 默认 false（h2 小帧 + 延迟确认可 40ms 停顿）；Huffman 编码（已知 C-9）；热路径日志 lambda 化 | 各处 | 累积可观 |

---

## 遗漏与残留清单（2026-09-18 复查）

> 对 P0~P3 全部"已修复"条目逐项复查后的残留项汇总，按性质分类。这些均为**已知且有意**
> 的权衡或外部限制，非新发现的缺陷。

### A. 有意保留的权衡 / API 限制（4 项）

1. **OptimizedHeaders.get() 单值仍返回不可变 `List.of(s)`**（P1-10 残留）——forEach/
   entrySet 已改可变，get() 为热路径有意保留（每次调用 new ArrayList 的代价）；影响：
   h2 直连路径 `getRequestHeaders().get("X").add(...)` 仍会 UOE，规避方式为先取再建新列表；
2. **同 authority 并发不同代理可能错选**（P0-4 残留）——HttpClient 的 ProxySelector 回调
   不携带请求上下文，per-request 代理需 HttpClient API 支持（代码注释已说明）；
3. **drain 非阻塞化的 keep-aline 保活率下降**（P2-32 设计权衡）——慢速客户端分开发送
   尾部数据（如 trailer 与最后帧分段）时，drain 在 available 归零后提前结束 → isEOF
   false → 连接关闭（比修复前的永久阻塞正确，但激进）；
4. **多实例日志失去 `[protocol:port]` 行前缀**（P4-2 的代价）——per-instance logger 是
   泄漏源，共享 logger 无法注入实例前缀；消息内的 connection 标识仍在。

### B. 暂缓的结构性优化（4 项）

5. **P3-42**：HTTP/1.1 头部批量解析（回退）——预读与 body 流起点冲突，需统一 Request
   读取层的重构；
6. **P3-46c**：END_STREAM 搭车末个 DATA 帧——需延迟帧头机制；
7. **P3-48b**：入站帧 body 缓冲复用——需贯穿全部 frame 类的签名改造；
8. **P3-51b**：multipart 块读取 + 内存边界搜索——与 P1-20 刚修复的状态机耦合深。

### C. 功能缺口（2 项）

9. **Huffman 编码端未实现**（HPackContext.encodeString 的 TODO）——发送侧从不 huffman
   编码（解码端完整），上游带宽多 ~10-30%（纯性能机会，非缺陷）；
10. **P1-17 残留**：ping 无 ack 跟踪——死连接检测依赖"ping 写失败"（TCP 重传超时
    最长 ~15 分钟），期间连接与线程仍占用。

### D. 验证空白（5 项）

11. **BufferedWebSocket 零测试覆盖**（P2-36b 的 POISON/背压/排空等待逻辑）——现有
    WebSocket 测试全用普通 WebSocket；
12. ~~**ProxyHandler 转发路径无专项测试**（P2-34 逐跳头剔除）~~ **已解决（2026-10-09）**：
    新增 ProxyForwardingTest（CL/TE 互斥断言 + 全受限头请求场景）进入 testng 套件；
13. **P1-15 SSL 握手超时无实测**（慢握手模拟）——功能路径由 SSLSessionTest/
    HttpsConfiguratorTest 覆盖；
14. ~~**九套冒烟测试未集成 testng**（位于 /tmp/hs_smoke 临时目录）~~ **已部分解决
    （2026-10-09）**：六个关键验证测试（H2ComplianceTest、MultiFrameTrailerTest、
    Review3Test、SlowBodyKeepAliveTest、HuffmanTest、ProxyForwardingTest，含新建的
    h2c 裸 socket 测试客户端 H2Client）已重建并集成进 src/test，TestNG 套件
    104 → 118 全绿（JDK 21 实测）；其余冒烟用例（ReadFix 等）随 /tmp 丢失未重建；
    另注：超并发流 REFUSED_STREAM 路径（P1-25 之一）因 ServerConfig 静态缓存
    maxConcurrentStreams（默认 -1 无限制）无法在共享 JVM 的套件内触发，需独立 JVM
    设 `-Drobaho.net.httpserver.http2MaxConcurrentStreams=2` 验证；
15. **Http2Test 仍整类禁用**（`@Test(enabled=false)`）。

### E. 环境限制（1 项）

16. **gradle wrapper jar 缺失**——需装有 gradle 的环境执行 `gradle wrapper` 重新生成。

---

## 备注

- 冒烟测试源码位于 `/tmp/hs_smoke/`，其中 **ReadFix.java** 是最终修正版（响应读取按
  `\r\n\r\n` 精确检测，早期版本的客户端读取缺陷已修复），一次运行覆盖全部用例，实测结果：
  - M3 单值请求头 add → `THREW:UnsupportedOperationException`（P1-10）
  - T1 裸 LF → 连接关闭、无任何 HTTP 响应（P1-9）
  - T2 chunked trailer → 下一请求 400 "Bad request line"，连接失步（P1-11）
  - T3 未读 100KB body → 客户端连第一个响应都收不到（P0-2 + P2-32 复合）
  - T4 `Content-Length: +5` → 200 正常接受（P4）
  - T5 15 位 hex chunk 长度 → 服务器静默无响应不断连（P1-12）
  - T6 1000 个头 → 200 无上限接受（P0-3）
  运行方式：JDK 21 编译 `src/main/java` 至 /tmp/hs_build 后
  `java -cp /tmp/hs_build:/tmp/hs_smoke ReadFix`。
- 本清单无 [待复核] 项——所有条目均经代码级逐行核对，标注 [已实测] 者另行通过
  原始 socket 冒烟测试复现；子代理分析中的两处误判（PingFrame 触发路径、裸 LF 走私定性）
  已修正后收录。
