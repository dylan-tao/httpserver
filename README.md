# robaho httpserver

> 本文档为中文版。英文使用文档见 [README.en.md](README.en.md)。
> This README is in Chinese; see [README.en.md](README.en.md) for English.


零依赖的 JDK `com.sun.net.httpserver.HttpServer` [规范](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html)替代实现，专为 **JDK 25 + 虚拟线程** 设计。

## 特性

- **HTTP/1.1**：完整实现，支持 keep-alive、pipelining、chunked、trailer
- **HTTP/2**：[RFC 9113](https://www.rfc-editor.org/rfc/rfc9113.html) 合规（h2c + ALPN），含 HPACK Huffman 编解码、流控、多路复用
- **WebSocket**：基于 nanohttpd 修改的 RFC 6455 实现
- **代理**：[ProxyHandler](https://github.com/robaho/httpserver/blob/main/src/main/java/robaho/net/httpserver/extras/ProxyHandler.java) 支持 HTTP 转发与 CONNECT 隧道
- **虚拟线程原生**：默认执行器即虚拟线程，全链路无 monitor 锁（ReentrantLock/CAS），ScopedValue（JDK 25）
- **零依赖**：仅 200KB jar，无任何第三方库

## 性能

- 与 JDK 25 内建 `HttpServer` 对照（8 核 macOS、JDK 25、每臂 2 分钟预热、wrk t4/c64、240 项测试门禁的可复现剧本 `src/test/bench/`）：
  - h1 吞吐持平（回环已达平台 syscall 地板：裸 socket 无解析基线的 94–97%）
  - h1 bulk64k **+15%**（writev 合并路径）
  - **GC 暂停次数 -64%**（同负载 189 vs 530 次，256MB 堆）
  - 头块解析 2–3×、管道化 4.5×（历史战役数据，见 CHANGELOG）
- 对上游 main 分支：h1 bulk64k +15%、h2 顺序（容器）+15%、h2 单流上传在容器环境 **3×**（流控修复）
- 老基准（TechEmpower 10×、h2load vs Jetty 3×/5×）见上游项目页；本仓库的可复现数据以上表为准

## Maven

[![Maven Central](https://img.shields.io/maven-central/v/io.github.robaho/httpserver.svg?label=Maven%20Central)](https://mvnrepository.com/artifact/io.github.robaho/httpserver)

```xml
<dependency>
  <groupId>io.github.robaho</groupId>
  <artifactId>httpserver</artifactId>
  <version>使用徽章中的版本号（去掉前缀 v）</version>
</dependency>
```

**要求 JDK 25+**。

## 快速开始

JDK 会自动使用 `robaho.net.httpserver.DefaultHttpServerProvider` 替代内置实现（jar 在 classpath/module path 上即可）。如有多个 provider，用系统属性指定：

```
-Dcom.sun.net.httpserver.HttpServerProvider=robaho.net.httpserver.DefaultHttpServerProvider
```

也可以直接实例化：[DefaultHttpServerProvider.java](https://github.com/robaho/httpserver/blob/main/src/main/java/robaho/net/httpserver/DefaultHttpServerProvider.java#L33)

### 示例

```java
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class Test {
    static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/", new MyHandler());
        // 虚拟线程执行器（默认已是虚拟线程，可省略）
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    static class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] bytes = "This is the response".getBytes();
            var contentLength = bytes.length == 0 ? -1 : bytes.length;
            try (OutputStream os = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(200, contentLength);
                os.write(bytes);
            }
        }
    }
}
```

## API 总览

模块名 `robaho.httpserver`（JPMS）。导出 4 个包；依赖 `java.logging`、`java.net.http`、`jdk.httpserver`（均传递），`java.management` 为静态可选（最小 jlink 镜像可省，仅影响启动时 JVM 特性探测）。完整契约见 javadoc jar。

| 包 | 内容 |
|---|---|
| `robaho.net.httpserver` | 主 API：`AuthFilter`、`DefaultHttpServerProvider`（SPI，自动发现）、`HttpStatus`（状态码常量与文案） |
| `robaho.net.httpserver.extras` | 工具：`Sendfile`（零拷贝）、`FormParser`、`MultipartFormParser`、`QueryParameters`、`ContentTypeCharset`、`ProxyHandler` |
| `robaho.net.httpserver.websockets` | RFC 6455：`WebSocketHandler`、`WebSocket`、`BufferedWebSocket`、`WebSocketFrame`、`CloseFrame`、`CloseCode`、`OpCode`、`WebSocketException` |
| `robaho.net.httpserver.http2` | h2 诊断：`Http2Stats`（全部计数器）、`Http2ErrorCode`（错误码枚举）、`Http2Exception` |

实现类（`HttpConnection`、`Http2ExchangeImpl`、`HttpServerImpl`、`HttpsServerImpl` 等）与数据结构内部均在非导出包——2.0 起 API 面已收窄，`package-info` 声明各包契约。

### 认证（AuthFilter）

与 JDK `Authenticator` 集成，作为 `Filter` 挂到 context 的系统过滤器链：

```java
Authenticator auth = new MyBasicAuthenticator("realm");
server.createContext("/api", handler).getFilters().add(new AuthFilter(auth));
// 或替换：((AuthFilter) filter).setAuthenticator(newAuth);
```

`AuthFilter` 实现了 `PrincipalExchange` 扩展接口：通过认证的 `HttpExchange` 可取出 `Principal`（避免再解析 Authorization 头）。认证失败时按 JDK 语义返回 `Authenticator` 的重试响应（如 401 + WWW-Authenticate）。

### 表单解析（extras）

```java
// application/x-www-form-urlencoded
Map<String, List<String>> params = FormParser.parse(charset, exchange.getRequestBody());

// multipart/form-data（文件落盘到 storage 目录，data 为内存字段）
Map<String, List<MultipartFormParser.Part>> parts =
    MultipartFormParser.parse(charset, contentType, exchange.getRequestBody(), storagePath);
for (var part : parts.get("upload")) {
  // part.contentType() / part.filename() / part.data()（内存）/ part.file()（落盘）
}

// 查询串
QueryParameters qp = QueryParameters.decode(charset, exchange.getRequestURI().getQuery());
String first = qp.getFirst("q");

// 请求的 charset（Content-Type 头 + RFC 2046 默认规则）
String cs = ContentTypeCharset.charset(exchange.getRequestHeaders());
```

属性上限（防 DoS）：`max_form_bytes`（10MB）、`max_form_params`（1000）、`max_multipart_field_bytes`（1MB）见参数表。`SimpleFileServer`（测试源码树 `src/test/extras/`，非发布 API）演示了 FormParser/QueryParameters/ContentTypeCharset 的组合用法。

### 简单文件服务器

用于基本测试（下载、echo、hello）：

```
mvn -q compile test-compile exec:java -Dexec.mainClass=SimpleFileServer -Dexec.classpathScope=test
```

### 零拷贝文件响应（sendfile）

静态文件响应走 `extras.Sendfile`，普通 h1 连接上由内核 `FileChannel.transferTo` 直通 socket——文件字节不经过用户态拷贝，handler 线程零参与：

```java
// handler 内：
Sendfile.serve(exchange, path, "application/octet-stream");
// 或等价地：exchange.setAttribute(Sendfile.ATTRIBUTE, path) 后
// sendResponseHeaders(200, Files.size(path))，再 close 响应流
```

约束：声明的 Content-Length 必须等于文件大小（`serve` 自动计算）；声明后 handler 不得再写响应体。h2 与 TLS 连接自动回退为常规流式发送（h2 的 DATA 帧封装无法直通 socket），语义不变。

## WebSocket

继承 `WebSocketHandler` 并注册端点：

```java
HttpHandler h = new EchoWebSocketHandler();
HttpContext c = server.createContext("/ws", h);
```

更多示例见 [websocket 测试目录](https://github.com/robaho/httpserver/tree/main/src/test/java/robaho/net/httpserver/websockets)。底层 API 来自 [nanohttpd](https://github.com/NanoHttpd/nanohttpd)。

### 回调契约（WebSocket）

`WebSocketHandler.openWebSocket(exchange)` 返回你的 `WebSocket` 子类实例，四个回调：

| 回调 | 必须覆写 | 线程 | 说明 |
|---|---|---|---|
| `onOpen()` | 否 | 连接 VT | 连接建立后调用一次 |
| `onClose(code, reason, initiatedByRemote)` | **是** | 单线程 | 恰好一次；`initiatedByRemote` 区分本地/远端发起 |
| `onMessage(WebSocketFrame)` | **是** | 连接 VT | 完整消息（分片已聚合；控制帧走 `onPong`） |
| `onPong(pong)` | 否 | 连接 VT | 心跳响应 |

发送 API：`send(String)` / `send(byte[])` / `sendFrame(frame)` / `ping(payload)` / `isOpen()`。**单一写者纪律**：回调之外的线程发送需自行串行化（或用 `BufferedWebSocket`，其内部队列 + drain 线程代管并发发送，背压上限 `maxBufferedWebSocketBytes`）。

工具类型：`WebSocketFrame.binary2Text/text2Binary`（UTF-8 编解码）、`CloseFrame`（关闭帧构造/解析）、`CloseCode`（RFC 6455 关闭码枚举，`find(int)` 反查）、`OpCode`（帧类型，`isControlFrame()`）。静态谓词 `WebSocketHandler.isWebsocketRequested(headers)` 供自定义升级路由复用握手检测。

### h2 诊断类型

`Http2Stats`（`/__stats` h2 段的数据源）暴露全部 `AtomicLong` 计数器：`activeStreams`、`totalStreams`、`bytesSent`、`framesSent`、`flushes`、`sslConnections`/`nonsslConnections` 等——按实例获取（`Http2Stats` 由服务器内部持有；`/__stats` 端点或 `EnableStats` 即为对外视图）。`Http2ErrorCode` 是全部 RFC 9113 错误码枚举（`getValue()`/`getEnum(int)`），`Http2Exception` 携带错误码抛给 handler 捕获。

## 日志

使用 [Java System Logger](https://docs.oracle.com/en/java/javase/19/docs/api/java.base/java/lang/System.Logger.html)。

## 服务器统计

设置 `robaho.net.httpserver.EnableStats=true` 后，`/__stats` 端点返回实时统计：

```shell
$ curl http://localhost:8080/__stats
Active Connections: 2049
Requests Since: 2669256
Requests/sec: 73719
Total Handler Exceptions: 0
...
```

"Since" 统计在每次拉取时重置，"Total" 统计累计。

---

## 全部可配置参数

以下参数均通过 **JVM 系统属性**（`-D`）设置，在服务器启动时一次性读取。

### 核心服务器

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `robaho.net.httpserver.virtualThreads` | boolean | `true` | 连接处理是否使用虚拟线程。`false` 回退到平台线程池（cachedThreadPool）。同时控制 BufferedWebSocket 的 drain 线程 |
| `robaho.net.httpserver.nodelay` | boolean | `true` | **h1 连接**的 TCP_NODELAY。开启可防止 SSE/小响应的 Nagle+延迟确认 ~40ms 停顿；h1 无实测吞吐损失 |
| `robaho.net.httpserver.http2Nodelay` | boolean | `false` | **h2 连接**的 TCP_NODELAY。默认关闭（Nagle 合并多路复用小帧写入，实测 NODELAY 开启会使 h2 多路复用吞吐 -8%）。h2 SSE 场景可设 `true` |
| `robaho.net.httpserver.bufferSize` | int | `16384` | 连接级输入/输出缓冲大小（字节），同时用于 h2 流缓冲池和 multipart 预读。下限 512。影响每连接内存 ~2×bufferSize |
| `jdk.httpserver.maxConnections` | int | `-1`（无限） | 最大并发连接数。超过时新连接直接关闭并计数。使用 JDK 兼容属性名（与 com.sun.net.httpserver 一致） |
| `robaho.net.httpserver.maxIdleConnections` | int | `200` | 最大空闲连接数。超过时清理器关闭最旧的空闲连接 |
| `robaho.net.httpserver.idleInterval` | long | `30`（秒） | 连接空闲判定间隔。超过此时间无活动且不在请求中的连接被清理器关闭 |
| `robaho.net.httpserver.clockTick` | long | `10000`（毫秒，10 秒） | 清理器运行周期 |
| `robaho.net.httpserver.drainAmount` | long | `65536`（字节） | handler 未读完请求体时，连接复用前最多排空的字节数。超过则关闭连接（防走私）。排空等待上限 1 秒 |
| `robaho.net.httpserver.maxReqTime` | long | `-1`（无限） | 请求处理最长允许时间（秒）。超时的请求中连接被清理器关闭 |
| `robaho.net.httpserver.EnableStats` | boolean | `false` | 开启 `/__stats` 统计端点 |
| `robaho.net.httpserver.EnableDebug` | boolean | `false` | 开启 `/__debug` 调试端点（连接/流明细） |

### 请求限制（防 DoS）

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `robaho.net.httpserver.maxReqLineSize` | int | `65536`（64KB） | 请求行或单个头行的最大字节数。超过返回 400 |
| `robaho.net.httpserver.maxReqHeaders` | int | `200` | 请求头最大数量。超过返回 400 |
| `robaho.net.httpserver.maxReqHeaderSize` | int | `389120`（380KB） | 请求头总字节数上限（请求行 + 全部头 + 空行累计）。超过返回 431。`0` 为不限 |
| `robaho.net.httpserver.maxRequestBodySize` | long | `-1`（无限） | 请求体最大字节数。超过时连接被关闭（防超大 body 占用内存）。`0` 或负数为不限 |

### SSL

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `robaho.net.httpserver.sslHandshakeTimeout` | long | `30`（秒） | SSL 握手超时。超时的连接被关闭，防止恶意客户端永久占用线程 |

### HTTP/2

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `robaho.net.httpserver.http2OverSSL` | boolean | `false` | 通过 SSL/ALPN 协商启用 HTTP/2 |
| `robaho.net.httpserver.http2OverNonSSL` | boolean | `false` | 在非 SSL 连接上启用 HTTP/2（需客户端 prior-knowledge，即直接发送 h2 preface）。RFC 9113 已弃用 upgrade 机制故不支持 |
| `robaho.net.httpserver.http2MaxFrameSize` | int | `16384` | 本端通告的 SETTINGS_MAX_FRAME_SIZE（字节）。出站帧按对端通告值拆分 |
| `robaho.net.httpserver.http2InitialWindowSize` | int | `65535` | 流级流控初始窗口大小（字节）。影响单流上传吞吐 |
| `robaho.net.httpserver.http2ConnectionWindowSize` | int | `65535` | 连接级流控窗口大小（字节） |
| `robaho.net.httpserver.http2AutoTuneMaxWindow` | int | `524288`（512KB） | 接收窗口自动调优上限（字节）。持续发送的连接/流按活跃度倍增其通告窗口直至该上限（空闲对端维持配置窗口）；流级增长信用随 handler 消费释放，慢 handler 不放大窗口。`0` 禁用（窗口固定为配置值） |
| `robaho.net.httpserver.http2MaxConcurrentStreams` | int | `100` | 每连接最大并发流数。超过时新流收 REFUSED_STREAM（客户端可重试）。`-1` 为无限。100 对齐浏览器行为 |
| `robaho.net.httpserver.http2MaxHeaderBlockSize` | int | `131072`（128KB） | 单个头块（HEADERS+CONTINUATION 累计）最大字节数。超过返回 ENHANCE_YOUR_CALM（防 CONTINUATION 洪水） |
| `robaho.net.httpserver.http2DisableFlushDelay` | boolean | `false` | 每帧写后立即 flush（低延迟模式）。默认 false（批量冲刷，吞吐更高）。SSE 场景建议 `true` |
| `robaho.net.httpserver.http2PingIntervalMillis` | long | `1000`（毫秒） | 空闲 h2 连接的 keepalive PING 间隔（同时是死连接探测：PING 失败即关闭连接）。`<= 0` 禁用（死连接由空闲清理器兜底，行为同 h1） |

### WebSocket

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `robaho.net.httpserver.maxWebSocketFrameSize` | int | `4194304`（4MB） | 单帧最大字节数。超过时以 1009（MessageTooBig）关闭 |
| `robaho.net.httpserver.maxWebSocketMessageSize` | int | `4194304`（4MB） | 分片聚合后的消息最大字节数。超过时以 1009 关闭 |
| `robaho.net.httpserver.maxBufferedWebSocketBytes` | int | `16777216`（16MB） | BufferedWebSocket 待发送队列的字节上限。消费端停滞时超过此值抛异常。`0` 为不限 |

### 表单解析（extras）

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `robaho.net.httpserver.max_form_bytes` | int | `10485760`（10MB） | url-encoded 表单请求体最大字节数。超过抛 IOException。`0` 为不限 |
| `robaho.net.httpserver.max_form_params` | int | `1000` | url-encoded 表单最大参数个数。超过抛异常（防哈希碰撞 DoS）。`0` 为不限 |
| `robaho.net.httpserver.max_multipart_field_bytes` | int | `1048576`（1MB） | multipart 非文件字段最大字节数。超过抛异常。`0` 为不限 |

---

## 性能调优建议

| 场景 | 推荐配置 |
|---|---|
| 高吞吐 h2 多路复用 | 默认即可（http2Nodelay=false） |
| h2 SSE / 低延迟 | `-Drobaho.net.httpserver.http2Nodelay=true -Drobaho.net.httpserver.http2DisableFlushDelay=true` |
| h2 通用吞吐（推荐起点） | `-Drobaho.net.httpserver.http2InitialWindowSize=131072 -Drobaho.net.httpserver.http2ConnectionWindowSize=131072`（128KB：默认 64KB 的一半窗口往返，流级缓冲增量约 +64KB/流，内存与吞吐的平衡点；更大上传场景再上调） |
| 内存受限（容器/多实例） | `-XX:+UseCompactObjectHeaders` + G1（默认） |
| 延迟敏感（P99 SLA） | `-XX:+UseZGC -XX:+ZGenerational` |
| 吞吐优先、内存充裕 | `-XX:+UseZGC -XX:+ZGenerational`（不设 Xmx，自适应堆） |
| 大文件上传 | 增大 `http2InitialWindowSize`（如 1024000） |
| CPU 密集 handler | `-Djdk.virtualThreadScheduler.parallelism=<核数>` |

### 部署配方（实测数据）

以下配方均在 8 核 macOS / JDK 25 / 本库 35k rps 负载下实测验证：

**1. 紧凑对象头**（分配密集场景，零代码）：

```bash
java -XX:+UseCompactObjectHeaders ...
```

本库每请求分配已压到 ~2KB，对象头 12B→8B 对小对象（String、ArrayList 节点）再省约 10% 脚印，等效减少 young GC 频率。测试套件默认在该模式下运行。

**2. 闲时内存主动归还**（长连接服务空闲时把内存还给系统）：

```bash
java -Xms64m -Xmx256m -XX:G1PeriodicGCInterval=30000 ...
```

默认配置下 JVM 闲置后不主动归还（`Xms=Xmx` 时 G1 永不 uncommit），物理内存回落依赖操作系统内核对冷页的异步压缩，实测需要约 5 分钟。开启周期 GC 后，实测闲置 ~30 秒即触发 `G1 Periodic Collection`（37M→8M，committed 收缩回 Xms 下限，RSS 从 123MB 降到 96MB）。适合跑在计费按 RSS、或与其他进程共址的容器里；吞吐负载下周期 GC 会被高分配率自然抑制，无副作用。

**3. 分代 ZGC**（停顿敏感部署的可选项）：

```bash
java -XX:+UseZGC -XX:+ZGenerational ...
```

实测与 G1 对比：吞吐 33.1k vs 33.4k rps（-0.6%）、p99 8.26ms 持平、服务端 CPU 持平、负载期零可测 GC 停顿（G1 为 0.7-5ms/次）。G1 默认停顿已足够小；仅在 P99 有硬性 SLA 且对亚毫秒停顿敏感时选 ZGC。

**4. AOT 缓存**（JDK 24+ Leyden，缩短冷启动，面向 CLI/短生命周期进程）：

```bash
# 1) 训练运行（classpath 必须全部是 JAR，非空目录会被 CDS 拒绝）：
java -XX:AOTMode=record -XX:AOTCacheOutput=app.aot -cp app.jar:... Main
# 2) 生成缓存：
java -XX:AOTMode=create -XX:AOTConfiguration=app.aot.config -XX:AOTCacheOutput=app.aot
# 3) 正式启动：
java -XX:AOTCache=app.aot -cp app.jar:... Main
```

训练进程需正常退出（`System.exit`）才会落盘 config；训练时带一轮真实流量可预热方法编译。实测本库示例服务冷启动 ~150ms → ~111ms（-25%）；应用类越多收益越大。

**5. h1 密集 + 低连接数场景的平台线程模式**（省 CPU 而非提吞吐）：

```java
server.setExecutor(Executors.newCachedThreadPool(
    Thread.ofPlatform().name("conn-", 0).factory()));
```

实测 64/256/1024 连接下吞吐与虚拟线程持平（瓶颈在链路时延），但服务端 CPU 从 1.55 核降到 1.05 核（-32%——省去了 VT 调度器 + Read-Poller 唤醒机制，两者合计约 27% 执行采样 + 11% poller CPU）。注意：该执行器同时服务于 h2 流分发，h2 多路复用场景勿用（每流一个平台线程会毁掉多路复用）。连接数上万时回退虚拟线程（平台线程栈内存 ~16KB+ 栈预留 1MB/线程）。


### 客户端流控窗口指引（大响应/多路复用下载）

HTTP/2 连接级发送窗口（服务端→客户端方向）初始为 65535 字节（协议默认），**只能由客户端通过 WINDOW_UPDATE 增长**，服务端无法代为扩大。大响应或多路复用下载场景下，客户端收满 64KB 后服务端写线程会等待窗口信用回流，吞吐受窗口往返次数限制（实测：默认窗口下 640KB 多路复用响应需约 10 次窗口往返）。

自研 h2 客户端若追求高吞吐，建议：

- **连接建立早期主动发送一次大的连接级 WINDOW_UPDATE**（如 1~16MB），浏览器均如此
- 收到 DATA 后**及时归还信用**（收即还，而非攒到消息结束），避免服务端写线程等待
- 流级窗口同理，可通过 SETTINGS 帧通告更大的 `INITIAL_WINDOW_SIZE`

服务端侧对应的调优项为 `http2InitialWindowSize`（流级接收窗口，影响上传吞吐）与 `http2ConnectionWindowSize`（连接级接收窗口）。

## 测试与合规

- 几乎所有 JDK 原有测试均已包含（全部 TestNG 套件通过（当前 240 项，见 CI））
- 额外的代理、WebSocket、HTTP/2 合规测试
- HTTP/2 通过 [h2spec](https://github.com/summerwind/h2spec) 全部规范测试

### 已知差异（vs JDK HttpServer）

- **异步迟到读取请求头返回空表**：handler 从未调用 `getRequestHeaders()` 的交换，其请求头视图按需惰性物化；若应用代码在**响应完成且连接已服务下一请求之后**才首次读取该视图，得到的是空 `Headers`（而非 JDK 实现的过期快照）。任何时点在 handler 内正常读取（含异步 handler 在 doFilter 返回前获取引用）行为与 JDK 完全一致。此差异杜绝了跨请求头泄漏，是安全性取舍。
