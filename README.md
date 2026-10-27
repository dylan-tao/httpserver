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

- 比 JDK 内置实现快 **10 倍以上**（TechEmpower 基准，同硬件同 JDK）
- 比 Jetty http2 快 **3 倍**、http1 快 **5 倍**（h2load 基准）
- 单机回环实测：h1 ~31k RPS、h2 多路复用 ~28k RPS、单流上传 ~110MB/s（8 核 macOS，小响应）
- h2 路径垃圾分配比优化前减少 **73%**（GC 停顿 4 倍减少）

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

### 简单文件服务器

用于基本测试（下载、echo、hello）：

```
mvn -q compile test-compile exec:java -Dexec.mainClass=SimpleFileServer -Dexec.classpathScope=test
```

## WebSocket

继承 `WebSocketHandler` 并注册端点：

```java
HttpHandler h = new EchoWebSocketHandler();
HttpContext c = server.createContext("/ws", h);
```

更多示例见 [websocket 测试目录](https://github.com/robaho/httpserver/tree/main/src/test/java/robaho/net/httpserver/websockets)。底层 API 来自 [nanohttpd](https://github.com/NanoHttpd/nanohttpd)。

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

### 客户端流控窗口指引（大响应/多路复用下载）

HTTP/2 连接级发送窗口（服务端→客户端方向）初始为 65535 字节（协议默认），**只能由客户端通过 WINDOW_UPDATE 增长**，服务端无法代为扩大。大响应或多路复用下载场景下，客户端收满 64KB 后服务端写线程会等待窗口信用回流，吞吐受窗口往返次数限制（实测：默认窗口下 640KB 多路复用响应需约 10 次窗口往返）。

自研 h2 客户端若追求高吞吐，建议：

- **连接建立早期主动发送一次大的连接级 WINDOW_UPDATE**（如 1~16MB），浏览器均如此
- 收到 DATA 后**及时归还信用**（收即还，而非攒到消息结束），避免服务端写线程等待
- 流级窗口同理，可通过 SETTINGS 帧通告更大的 `INITIAL_WINDOW_SIZE`

服务端侧对应的调优项为 `http2InitialWindowSize`（流级接收窗口，影响上传吞吐）与 `http2ConnectionWindowSize`（连接级接收窗口）。

## 测试与合规

- 几乎所有 JDK 原有测试均已包含（全部 TestNG 套件通过（当前 214 项，见 CI））
- 额外的代理、WebSocket、HTTP/2 合规测试
- HTTP/2 通过 [h2spec](https://github.com/summerwind/h2spec) 全部规范测试
