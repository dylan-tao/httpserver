package robaho.net.httpserver.http2;

import java.io.IOException;
import java.util.Arrays;

/** h2 load generator for the main-vs-op comparison: sequential, multiplexed,
 *  upload (flow-control aware) and download scenarios over one connection */
public class H2Bench {
    static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String scenario = args[1];
        int count = Integer.parseInt(args[2]);
        long t0 = 0;
        int ok = 0;
        long t1 = 0;
        switch (scenario) {
            case "seq" -> {
                try (H2Client c = new H2Client(port)) {
                    warmup(c, 500);
                    t0 = System.nanoTime();
                    for (int i = 0; i < count; i++) {
                        int s = c.sendRequest("GET", "/hello", true);
                        var r = c.awaitResponse(s);
                        check(r);
                        ok++;
                    }
                    t1 = System.nanoTime();
                }
            }
            case "mux8" -> {
                try (H2Client c = new H2Client(port)) {
                    int lanes = 8;
                    for (int i = 0; i < lanes; i++) {
                        int s = c.sendRequest("GET", "/hello", true);
                        check(c.awaitResponse(s));
                    }
                    int per = count / lanes;
                    t0 = System.nanoTime();
                    for (int i = 0; i < per; i++) {
                        int[] ids = new int[lanes];
                        for (int l = 0; l < lanes; l++) ids[l] = c.sendRequest("GET", "/hello", true);
                        for (int l = 0; l < lanes; l++) check(c.awaitResponse(ids[l]));
                        ok += lanes;
                    }
                    t1 = System.nanoTime();
                }
            }
            case "upload256k" -> {
                try (H2Client c = new H2Client(port)) {
                    upload(c, 256 * 1024); // warmup
                    t0 = System.nanoTime();
                    for (int i = 0; i < count; i++) {
                        upload(c, 256 * 1024);
                        ok++;
                    }
                    t1 = System.nanoTime();
                }
            }
            case "download64k", "download1m" -> {
                String path = scenario.equals("download64k") ? "/large64k" : "/large1m";
                int expect = scenario.equals("download64k") ? 64 * 1024 : 1024 * 1024;
                try (H2Client c = new H2Client(port)) {
                    for (int i = 0; i < 10; i++) {
                        int s = c.sendRequest("GET", path, true);
                        var r = c.awaitResponse(s);
                        if (r.data().length != expect) throw new IOException("warmup len " + r.data().length);
                    }
                    t0 = System.nanoTime();
                    for (int i = 0; i < count; i++) {
                        int s = c.sendRequest("GET", path, true);
                        var r = c.awaitResponse(s);
                        if (r.status() != 200 || r.data().length != expect) {
                            throw new IOException("bad response: status=" + r.status() + " len=" + r.data().length);
                        }
                        ok++;
                    }
                    t1 = System.nanoTime();
                }
            }
            default -> throw new IllegalArgumentException(scenario);
        }
        double sec = (t1 - t0) / 1e9;
        System.out.printf("RESULT scenario=%s ok=%d wall_s=%.3f rps=%.0f%n", scenario, ok, sec, ok / sec);
        System.exit(0);
    }

    static void warmup(H2Client c, int n) throws IOException {
        for (int i = 0; i < n; i++) {
            int s = c.sendRequest("GET", "/hello", true);
            check(c.awaitResponse(s));
        }
    }

    static void check(H2Client.Response r) throws IOException {
        if (r.status() != 200 || r.data().length != 128) {
            throw new IOException("bad response: status=" + r.status() + " len=" + r.data().length);
        }
    }

    /** single-stream upload with both-window flow control: send 16KB DATA
     *  frames while the windows allow, blocking on WINDOW_UPDATE otherwise */
    static void upload(H2Client c, int total) throws IOException {
        int s = c.sendRequest("POST", "/echo", false);
        long connWindow = 65535, streamWindow = 65535;
        byte[] chunk = new byte[16384];
        Arrays.fill(chunk, (byte) 'u');
        int sent = 0;
        while (sent < total) {
            int len = Math.min(chunk.length, total - sent);
            boolean end = sent + len >= total;
            if (connWindow >= len && streamWindow >= len) {
                c.sendData(s, Arrays.copyOf(chunk, len), end);
                connWindow -= len;
                streamWindow -= len;
                sent += len;
            } else {
                int[] wu = c.readWindowUpdate();
                if (wu[0] == 0) connWindow += wu[1];
                else if (wu[0] == s) streamWindow += wu[1];
                // else: another stream's update (single stream here) - ignore
            }
        }
        var r = c.awaitResponse(s);
        if (r.status() != 200 || !new String(r.data()).startsWith("ok " + total)) {
            throw new IOException("upload response: " + r.status() + " " + new String(r.data()));
        }
    }
}
