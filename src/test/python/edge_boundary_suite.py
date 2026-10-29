"""Edge-case suite: each round exercises a different combination of boundary
cases against the live server. 60 rounds total, round number selects the
combination deterministically."""
import http.client, socket, struct, sys, time, random

HOST, PORT = "127.0.0.1", 18080
FAILURES = []

def h1(method, path, headers=None, body=None, expect=200, desc=""):
    try:
        c = http.client.HTTPConnection(HOST, PORT, timeout=10)
        c.request(method, path, body=body, headers=headers or {})
        r = c.getresponse()
        data = r.read()
        ok = r.status == expect
        c.close()
        if not ok:
            FAILURES.append(f"{desc}: expected {expect} got {r.status}")
        return r.status, dict(r.getheaders()), data
    except Exception as e:
        FAILURES.append(f"{desc}: exception {type(e).__name__}: {e}")
        return None, {}, b""

def raw(data, desc, expect_400=False):
    """send raw bytes, read whatever comes back"""
    try:
        s = socket.create_connection((HOST, PORT), timeout=5)
        s.sendall(data)
        s.settimeout(2)
        resp = b""
        try:
            while True:
                b = s.recv(4096)
                if not b: break
                resp += b
        except socket.timeout: pass
        s.close()
        if expect_400 and resp and not resp.startswith(b"HTTP/1.1 4"):
            FAILURES.append(f"{desc}: expected 4xx, got {resp[:20]}")
        return resp
    except Exception as e:
        FAILURES.append(f"{desc}: exception {type(e).__name__}")

def h2_stream(n, path="/hello", desc=""):
    """h2 prior-knowledge: n streams on one conn"""
    try:
        s = socket.create_connection((HOST, PORT), timeout=10)
        s.sendall(b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" + bytes([0,0,0,4,0,0,0,0,0]))
        def frame(t, f, sid, p=b""): return struct.pack(">I", len(p))[1:]+bytes([t,f])+struct.pack(">I", sid)+p
        for i in range(1, n*2, 2):
            hdr = b"\x82\x87\x04" + bytes([len(path)]) + path.encode() + b"\x01\x01x"
            s.sendall(frame(1, 0x4, i, hdr))
        total, s_ = 0, 2
        s.settimeout(s_)
        try:
            while True:
                b = s.recv(65536)
                if not b: break
                total += len(b)
        except socket.timeout: pass
        s.close()
        if total < 100: FAILURES.append(f"{desc}: drained only {total} bytes")
        return total
    except Exception as e:
        FAILURES.append(f"{desc}: exception {type(e).__name__}: {e}")

# ============ boundary case definitions ============
def case_empty_headers(rnd):
    h1("GET", "/hello", {}, desc=f"R{rnd} no headers")
def case_single_header(rnd):
    h1("GET", "/hello", {"X-One": "1"}, desc=f"R{rnd} single header")
def case_many_headers(rnd):
    h1("GET", "/hello", {f"X-H{i}": f"v{i}" for i in range(50)}, desc=f"R{rnd} 50 headers")
def case_max_headers(rnd):
    import socket as sk
    s = sk.create_connection((HOST, PORT), timeout=5)
    req = b"GET /hello HTTP/1.1\r\nHost: x\r\n" + b"".join(f"X-M{i}: v\r\n".encode() for i in range(199)) + b"\r\n"
    s.sendall(req)
    resp = s.recv(200); s.close()
    if not resp.startswith(b"HTTP/1.1 200"):
        FAILURES.append(f"R{rnd} at-limit raw 200: {resp[:20]}")
def case_over_max_headers(rnd):
    import socket as sk
    s = sk.create_connection((HOST, PORT), timeout=5)
    req = b"GET /hello HTTP/1.1\r\nHost: x\r\n" + b"".join(f"X-O{i}: v\r\n".encode() for i in range(200)) + b"\r\n"
    s.sendall(req)
    resp = s.recv(200); s.close()
    if not resp.startswith(b"HTTP/1.1 400"):
        FAILURES.append(f"R{rnd} 201-headers-over: {resp[:20]}")
def case_header_reuse(rnd):
    c = http.client.HTTPConnection(HOST, PORT, timeout=10)
    for i in range(5):
        c.request("GET", "/hello", headers={"X-Reuse": f"r{i}"})
        r = c.getresponse(); r.read()
        if r.status != 200: FAILURES.append(f"R{rnd} reuse {i}: {r.status}")
    c.close()
def case_case_mixed(rnd):
    c = http.client.HTTPConnection(HOST, PORT, timeout=10)
    for name in ["content-type", "Content-Type", "CONTENT-TYPE", "CoNtEnT-tYpE"]:
        c.request("GET", "/hello", headers={name: "text/plain"})
        r = c.getresponse(); r.read()
        if r.status != 200: FAILURES.append(f"R{rnd} case {name}: {r.status}")
    c.close()
def case_long_header_name(rnd):
    h1("GET", "/hello", {"X-" + "a"*1000: "v"}, desc=f"R{rnd} 1KB header name")
def case_long_header_val(rnd):
    h1("GET", "/hello", {"X-Long": "v"*10000}, desc=f"R{rnd} 10KB header value")
def case_response_null_hack(rnd):
    # try to make server write a null header value (should 4xx or error, not hang)
    raw(b"GET /hello HTTP/1.1\r\nHost: x\r\nX-Null:\r\n\r\n", f"R{rnd} empty header value", expect_400=False)
def case_crlf_inject(rnd):
    raw(b"GET /hello HTTP/1.1\r\nHost: x\r\nX-Bad: val\rue\r\n\r\n", f"R{rnd} CR in header value", expect_400=True)
def case_empty_request(rnd):
    raw(b"\r\n\r\n", f"R{rnd} empty request")
def case_garbage_request(rnd):
    raw(b"\x00\x01\x02\x03\r\n\r\n", f"R{rnd} garbage bytes", expect_400=True)
def case_no_colon(rnd):
    raw(b"GET /hello HTTP/1.1\r\nHost: x\r\nBadHeaderNoColon\r\n\r\n", f"R{rnd} header without colon", expect_400=True)
def case_chunked_large(rnd):
    h1("GET", "/large1m", desc=f"R{rnd} 1MB chunked-ish")
def case_fixed_64k(rnd):
    s, h, d = h1("GET", "/large64k", desc=f"R{rnd} 64KB")
    if s == 200 and len(d) != 65536: FAILURES.append(f"R{rnd} 64KB body len={len(d)}")
def case_head(rnd):
    s, h, d = h1("HEAD", "/hello", desc=f"R{rnd} HEAD")
    if d: FAILURES.append(f"R{rnd} HEAD returned body {len(d)}")
def case_304(rnd):
    h1("GET", "/hello", {"If-None-Match": "*"}, expect=200, desc=f"R{rnd} conditional (server always 200)")
def case_post_empty(rnd):
    s, h, d = h1("POST", "/echo", body=b"", desc=f"R{rnd} POST empty")
def case_post_1b(rnd):
    s, h, d = h1("POST", "/echo", body=b"x", desc=f"R{rnd} POST 1 byte")
def case_post_100k(rnd):
    s, h, d = h1("POST", "/echo", body=b"z"*100000, desc=f"R{rnd} POST 100KB")
def case_upload_truncated(rnd):
    # declare 1MB, send 10 bytes, close
    try:
        s = socket.create_connection((HOST, PORT), timeout=5)
        s.sendall(b"POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 1048576\r\n\r\n" + b"t"*10)
        s.close()  # truncate
    except Exception as e:
        FAILURES.append(f"R{rnd} truncated upload: {e}")
def case_h2_basic(rnd):
    h2_stream(10, desc=f"R{rnd} h2 10 streams")
def case_h2_multi(rnd):
    h2_stream(100, desc=f"R{rnd} h2 100 streams")
def case_h2_flood(rnd):
    h2_stream(500, desc=f"R{rnd} h2 500 streams")
def case_conn_close(rnd):
    h1("GET", "/hello", {"Connection": "close"}, desc=f"R{rnd} Connection: close")
def case_keepalive_pipeline(rnd):
    # 3 pipelined requests in one write
    try:
        s = socket.create_connection((HOST, PORT), timeout=10)
        reqs = b"GET /hello HTTP/1.1\r\nHost: x\r\n\r\n" * 3
        s.sendall(reqs)
        s.settimeout(5)
        resp = b""
        try:
            while resp.count(b"HTTP/1.1 200") < 3:
                b = s.recv(65536)
                if not b: break
                resp += b
        except socket.timeout: pass
        s.close()
        n = resp.count(b"HTTP/1.1 200")
        if n != 3: FAILURES.append(f"R{rnd} pipeline: got {n}/3 responses")
    except Exception as e:
        FAILURES.append(f"R{rnd} pipeline: {e}")
def case_mixed_then_close(rnd):
    # interleave fixed/chunked then abrupt close
    try:
        s = socket.create_connection((HOST, PORT), timeout=5)
        s.sendall(b"GET /hello HTTP/1.1\r\nHost: x\r\n\r\nGET /multi HTTP/1.1\r\nHost: x\r\n\r\n")
        time.sleep(0.3)
        s.close()
    except Exception as e:
        FAILURES.append(f"R{rnd} mixed+abrupt: {e}")
def case_weird_method(rnd):
    h1("PROPFIND", "/hello", expect=200, desc=f"R{rnd} weird method (server allows)")
def case_uri_edge(rnd):
    h1("GET", "/hello?a=&b=1&c=%00", desc=f"R{rnd} edge query string")
def case_double_slash(rnd):
    h1("GET", "//hello", expect=400, desc=f"R{rnd} double slash (JDK parity: 400)")
def case_deep_path(rnd):
    h1("GET", "/" + "a/"*100 + "hello", expect=404, desc=f"R{rnd} deep path")
def case_http10(rnd):
    raw(b"GET /hello HTTP/1.0\r\n\r\n", f"R{rnd} HTTP/1.0 no host")
def case_http10_keepalive(rnd):
    raw(b"GET /hello HTTP/1.0\r\nConnection: keep-alive\r\n\r\n", f"R{rnd} HTTP/1.0 keep-alive")
def case_empty_path(rnd):
    raw(b"GET  HTTP/1.1\r\nHost: x\r\n\r\n", f"R{rnd} empty path", expect_400=True)
def case_expect_100(rnd):
    h1("POST", "/echo", {"Expect": "100-continue"}, body=b"data", desc=f"R{rnd} Expect 100-continue")
def case_upload_cap(rnd):
    # 1MB+ body: the server 413s once the cap is crossed and closes; a client
    # still blasting body bytes can get its socket RST before reading the 413
    # (kernel discard on reset, documented behavior). Probe tolerantly.
    import socket as sk
    for attempt in range(3):
        try:
            s = sk.create_connection((HOST, PORT), timeout=10)
            s.sendall(b"POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 1100000\r\n\r\n")
            try:
                s.sendall(b"z"*300000)
                s.settimeout(3)
                resp = s.recv(4096)
                s.close()
                if resp.startswith(b"HTTP/1.1 413"):
                    return
            except (ConnectionResetError, BrokenPipeError):
                s.close()
                time.sleep(0.3)
                continue
        except Exception as e:
            FAILURES.append(f"R{rnd} 1MB cap: {type(e).__name__}")
            return
    FAILURES.append(f"R{rnd} 1MB cap: no readable 413 in 3 attempts (RST-race)")
def case_h2_goaway_garbage(rnd):
    # send h2 preface then garbage
    try:
        s = socket.create_connection((HOST, PORT), timeout=5)
        s.sendall(b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" + bytes([0,0,0,4,0,0,0,0,0]))
        s.sendall(b"\xff\xff\xff\x01\x00\x00\x00\x00\x01")  # garbage frame
        s.settimeout(2)
        try: s.recv(4096)
        except socket.timeout: pass
        s.close()
    except Exception as e:
        FAILURES.append(f"R{rnd} h2 garbage: {e}")

ALL_CASES = [v for k, v in sorted(globals().items()) if k.startswith("case_")]

# ============ runner: 60 rounds, each picks a deterministic subset ============
rnd_gen = random.Random(20261030)
ROUND_SIZE = 6  # cases per round
total = 0
for round_no in range(1, 61):
    cases = rnd_gen.sample(ALL_CASES, min(ROUND_SIZE, len(ALL_CASES)))
    for c in cases:
        c(round_no)
        total += 1
    # health gate after each round
    try:
        s = http.client.HTTPConnection(HOST, PORT, timeout=5)
        s.request("GET", "/hello")
        r = s.getresponse(); r.read(); s.close()
        if r.status != 200:
            FAILURES.append(f"R{round_no} HEALTH: {r.status}")
            break
    except Exception as e:
        FAILURES.append(f"R{round_no} HEALTH FAIL: {e}")
        break

print(f"ROUNDS=60 CASES_TOTAL={total} FAILURES={len(FAILURES)}")
for f in FAILURES[:20]:
    print(f"  FAIL: {f}")
sys.exit(1 if FAILURES else 0)
