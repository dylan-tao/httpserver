module robaho.httpserver {
    exports robaho.net.httpserver;
    exports robaho.net.httpserver.extras;
    exports robaho.net.httpserver.websockets;
    exports robaho.net.httpserver.http2;

    requires transitive java.logging;
    requires transitive java.net.http;
    requires transitive jdk.httpserver;
    // for the startup JVM feature detection (GC / launch flags) in ServerConfig.
    // static: purely diagnostic - ServerConfig catches Throwable and reports
    // "unknown" at runtime, so minimal jlink images may omit java.management
    requires static java.management;

    provides com.sun.net.httpserver.spi.HttpServerProvider with robaho.net.httpserver.DefaultHttpServerProvider;
}
