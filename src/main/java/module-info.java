module robaho.httpserver {
    exports robaho.net.httpserver;
    exports robaho.net.httpserver.extras;
    exports robaho.net.httpserver.websockets;
    exports robaho.net.httpserver.http2;

	requires transitive java.logging;
	requires transitive java.net.http;
	requires transitive jdk.httpserver;
	// for the startup JVM feature detection (GC / launch flags) in ServerConfig
	requires java.management;

    provides com.sun.net.httpserver.spi.HttpServerProvider with robaho.net.httpserver.DefaultHttpServerProvider;
}
