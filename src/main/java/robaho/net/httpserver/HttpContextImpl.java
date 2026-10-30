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

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.lang.System.Logger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HttpContext represents a mapping between a protocol (http or https) together
 * with a root URI path
 * to a {@link HttpHandler} which is invoked to handle requests destined
 * for the protocol/path on the associated HttpServer.
 * <p>
 * HttpContext instances are created by
 * {@link HttpServer#createContext(String,String,HttpHandler,Object)}
 * <p>
 */
class HttpContextImpl extends HttpContext {

  private final String path;

  private final String protocol;

  private final ServerImpl server;

  private final AuthFilter authfilter;

  private final Map<String, Object> attributes = new ConcurrentHashMap<>();

  /* system filters, not visible to applications */
  private final List<Filter> sfilters = new CopyOnWriteArrayList<>();

  /* user filters, set by applications */
  private final List<Filter> ufilters = new CopyOnWriteArrayList<>();

  private Authenticator authenticator;

  private HttpHandler handler;

  /**
   * cached tail of the filter chain (see {@link #getChainTail()}):
   * runs the system filters and then this context's handler
   */
  private volatile HttpHandler chainTail;

  private volatile HttpHandler chainTailHandler;

  /**
   * cached complete chain for the (common) no-user-filters case: a Chain
   * over an IMMUTABLE empty list never advances its iterator, so it is
   * stateless and safely shared across concurrent exchanges. keyed on the
   * cached tail (which is rebuilt when the handler changes)
   */
  private volatile Filter.Chain cachedEmptyUserChain;

  private volatile HttpHandler cachedEmptyUserChainTail;

  /**
   * the cached per-request chain tail: invokes the (structurally fixed)
   * system filters and then the context handler. the system filter list
   * only ever gains the constructor's AuthFilter, so with a single system
   * filter no iteration state is needed - and the empty-list
   * {@code Filter$Chain} handed to it as its continuation NEVER advances
   * its iterator, making it stateless and safely shared across concurrent
   * exchanges. invalidated when the handler changes (setHandler's
   * one-shot null-to-non-null transition is the only mutation).
   */
  HttpHandler getChainTail() {
    HttpHandler h = handler;
    HttpHandler tail = chainTail;
    if (tail != null && chainTailHandler == h) {
      return tail;
    }
    return buildChainTail(h);
  }

  private synchronized HttpHandler buildChainTail(HttpHandler h) {
    if (chainTail != null && chainTailHandler == h) {
      return chainTail;
    }
    List<Filter> sf = sfilters;
    HttpHandler tail;
    if (sf.size() == 1) {
      // the end-of-system chain: empty list, so its iterator is never
      // advanced - doFilter always falls straight through to the handler
      Filter.Chain end = new Filter.Chain(List.of(), h);
      tail = new SystemFilterTail(sf.get(0), end);
    } else if (sf.isEmpty()) {
      tail = h;
    } else {
      // more than one system filter would need per-exchange iteration
      // state: no cache (unreachable today - only the constructor adds)
      return null;
    }
    chainTail = tail;
    chainTailHandler = h;
    return tail;
  }

  /**
   * runs one fixed system filter and continues into the (stateless,
   * cached) end chain - the cached form of the per-request system chain
   */
  private record SystemFilterTail(Filter filter, Filter.Chain end) implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      filter.doFilter(exchange, end);
    }

  }

  /**
   * the full filter chain for an exchange. the common case - no user
   * filters - returns a CACHED chain: a {@code Filter$Chain} over an
   * immutable empty list never advances its iterator, so a single
   * instance serves every concurrent exchange (the old path allocated a
   * Chain, a LinkHandler, a system Chain and two COW iterators per
   * request). when user filters exist, only a thin per-exchange user
   * Chain is built over the cached system tail - its iterator is
   * per-request state by necessity. rebuilt whenever the user filter
   * count changes (filters may be added at runtime via getFilters()),
   * which invalidates the cached empty-chain case, and when the handler
   * changes.
   */
  Filter.Chain getFilterChain() {
    HttpHandler tail = getChainTail();
    if (tail == null) {
      return null; // nonstandard system list: caller uses the fallback
    }
    List<Filter> uf = ufilters;
    if (uf.isEmpty()) {
      Filter.Chain cached = cachedEmptyUserChain;
      if (cached != null && cachedEmptyUserChainTail == tail) {
        return cached;
      }
      return buildEmptyUserChain(tail);
    }
    // user filters present: per-exchange chain over the live list (so
    // runtime additions are picked up), with the cached system tail
    return new Filter.Chain(uf, tail);
  }

  private synchronized Filter.Chain buildEmptyUserChain(HttpHandler tail) {
    if (cachedEmptyUserChain != null && cachedEmptyUserChainTail == tail && ufilters.isEmpty()) {
      return cachedEmptyUserChain;
    }
    if (!ufilters.isEmpty()) {
      return new Filter.Chain(ufilters, tail);
    }
    Filter.Chain chain = new Filter.Chain(List.of(), tail);
    cachedEmptyUserChain = chain;
    cachedEmptyUserChainTail = tail;
    return chain;
  }

  /**
   * constructor is package private.
   */
  HttpContextImpl(String protocol, String path, HttpHandler cb, ServerImpl server) {
    if (path == null || protocol == null || path.isEmpty() || path.charAt(0) != '/') {
      throw new IllegalArgumentException("Illegal value for path or protocol");
    }
    this.protocol = protocol.toLowerCase(Locale.ROOT);
    this.path = path;
    if (!this.protocol.equals("http") && !this.protocol.equals("https")) {
      throw new IllegalArgumentException("Illegal value for protocol");
    }
    this.handler = cb;
    this.server = server;
    authfilter = new AuthFilter(null);
    sfilters.add(authfilter);
  }

  /**
   * returns the handler for this context
   * @return the HttpHandler for this context
   */
  @Override
  public HttpHandler getHandler() { return handler; }

  @Override
  public void setHandler(HttpHandler h) {
    if (h == null) {
      throw new NullPointerException("Null handler parameter");
    }
    if (handler != null) {
      throw new IllegalArgumentException("handler already set");
    }
    handler = h;
  }

  /**
   * returns the path this context was created with
   * @return this context's path
   */
  @Override
  public String getPath() { return path; }

  /**
   * returns the server this context was created with
   * @return this context's server
   */
  @Override
  public HttpServer getServer() { return server.getWrapper(); }

  ServerImpl getServerImpl() { return server; }

  /**
   * returns the protocol this context was created with
   * @return this context's path
   */
  public String getProtocol() { return protocol; }

  /**
   * returns a mutable Map, which can be used to pass
   * configuration and other data to Filter modules
   * and to the context's exchange handler.
   * <p>
   * Every attribute stored in this Map will be visible to
   * every HttpExchange processed by this context
   */
  @Override
  public Map<String, Object> getAttributes() { return attributes; }

  @Override
  public List<Filter> getFilters() { return ufilters; }

  List<Filter> getSystemFilters() { return sfilters; }

  @Override
  public Authenticator setAuthenticator(Authenticator auth) {
    Authenticator old = authenticator;
    authenticator = auth;
    authfilter.setAuthenticator(auth);
    return old;
  }

  @Override
  public Authenticator getAuthenticator() { return authenticator; }

  Logger getLogger() { return server.getLogger(); }

}
