/*
 * Copyright (c) 2005, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package robaho.net.httpserver;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

class ContextList {

  private final List<HttpContextImpl> list = new CopyOnWriteArrayList<>();

  private final Map<CacheKey, HttpContextImpl> cache = new ConcurrentHashMap<>();

  private record CacheKey(String protocol, String path) {}

  /**
   * serializes add/remove against each other (rare admin operations).
   * kept as a ReentrantLock for no urgent reason: the original
   * justification (monitor-based locking pinning virtual threads, JEP 444)
   * was removed in JDK 24 (JEP 491) - either primitive is safe now
   */
  private final ReentrantLock lock = new ReentrantLock();

  public void add(HttpContextImpl ctx) {
    lock.lock();
    try {
      assert ctx.getPath() != null;
      if (contains(ctx)) {
        throw new IllegalArgumentException("cannot add context to list");
      }
      list.add(ctx);
      // no cache invalidation needed: the cache only stores exact
      // matches (see findContext), and a context registered at a
      // different path can never outrank an exact match for its own
      // path. if the caching policy is ever relaxed to prefix
      // matches, add() must invalidate entries under the new path
      // (guarded by ContextListCacheTest)
    } finally {
      lock.unlock();
    }
  }

  boolean contains(HttpContextImpl ctx) {
    return findContext(ctx.getProtocol(), ctx.getPath(), true) != null;
  }

  public int size() {
    return list.size();
  }

  /*
   * initially contexts are located only by protocol:path.
   * Context with longest prefix matches (currently case-sensitive)
   */
  HttpContextImpl findContext(String protocol, String path) {
    CacheKey key = new CacheKey(protocol, path);
    var ctx = cache.get(key);
    if (ctx != null)
      return ctx;
    ctx = findContext(protocol, path, false);
    // only cache exact matches, otherwise path parameters will
    // explode memory
    if (ctx != null && ctx.getPath().equals(path)) {
      // put under the remove lock: a removeContext landing between the
      // miss-scan and this put would otherwise permanently cache the
      // removed handler (the identity-based invalidation can never
      // match a later re-registered context, so the stale entry is
      // unfixable). under the lock, a concurrent remove either waits
      // and invalidates our entry, or has already removed the context
      // and the contains() check suppresses the put
      lock.lock();
      try {
        if (list.contains(ctx)) {
          cache.put(key, ctx);
        }
      } finally {
        lock.unlock();
      }
    }
    return ctx;
  }

  HttpContextImpl findContext(String protocol, String path, boolean exact) {
    String _protocol = protocol.toLowerCase(Locale.ROOT);
    String longest = "";
    HttpContextImpl lc = null;
    for (HttpContextImpl ctx : list) {
      if (!ctx.getProtocol().equals(_protocol)) {
        continue;
      }
      String cpath = ctx.getPath();
      if (exact && !cpath.equals(path)) {
        continue;
      } else if (!exact && !path.startsWith(cpath)) {
        continue;
      }
      if (cpath.length() > longest.length()) {
        longest = cpath;
        lc = ctx;
      }
    }
    return lc;
  }

  public void remove(String protocol, String path) throws IllegalArgumentException {
    lock.lock();
    try {
      HttpContextImpl ctx = findContext(protocol, path, true);
      if (ctx == null) {
        throw new IllegalArgumentException("cannot remove element from list");
      }
      list.remove(ctx);
      // invalidate cached lookups pointing at the removed context
      cache.values().removeIf(c -> c == ctx);
    } finally {
      lock.unlock();
    }
  }

  public void remove(HttpContextImpl context) throws IllegalArgumentException {
    lock.lock();
    try {
      for (HttpContextImpl ctx : list) {
        if (ctx.equals(context)) {
          list.remove(ctx);
          // invalidate cached lookups pointing at the removed context
          cache.values().removeIf(c -> c == context);
          return;
        }
      }
      throw new IllegalArgumentException("no such context in list");
    } finally {
      lock.unlock();
    }
  }

}
