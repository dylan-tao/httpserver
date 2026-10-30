package robaho.net.httpserver.internal;

import com.sun.net.httpserver.Headers;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A request-headers view that materializes lazily from the connection's
 * parse table. The eager per-request copy into a JDK Headers (HashMap
 * table + Node + ArrayList per header) was ~28% of the benchmark's
 * allocation profile, yet most handlers never read request headers at
 * all; this view stays empty until the first Map access, then copies
 * once via {@link OptimizedHeaders#copyInto}.
 * The parse table is REUSED by the next request on the same connection
 * (h1 is one thread per connection: the handler completes before the
 * next parse starts), so an un-materialized view must be copied before
 * the table is cleared. Two materialization triggers, both serialized
 * on this view's monitor:
 * <ul>
 * <li>any Map access from any thread - a handler, a filter, or
 * application code holding the reference past response completion</li>
 * <li>{@link OptimizedHeaders#clear()} for table reuse force-copies
 * the pending view first, so a retained view never observes a
 * cleared or next-request table</li>
 * </ul>
 * Memory-visibility: the source field is volatile and nulled only AFTER
 * the copy completes, so the lock-free reader fast path (source == null)
 * is ordered after every copy write and never sees a half-populated map.
 */
public final class LazyRequestHeaders extends Headers {

  /**
   * the parse table this view copies from, or null once materialized.
   * volatile: the null store is the release that publishes the copied
   * entries to lock-free readers
   */
  private volatile OptimizedHeaders source;

  /**
   * set when this view was handed to application code
   * (getRequestHeaders). only an exposed view can be RETAINED past
   * response completion, so clear() only force-materializes exposed
   * views - an unexposed one dies with its exchange and is skipped,
   * eliminating the copy for handlers that never read request headers.
   * volatile: exposure can happen on any thread that holds the
   * exchange while clear() runs on the connection thread
   */
  private volatile boolean exposed;

  /**
   * reentrancy guard: copyInto(target) calls target.put(...) virtually,
   * which lands back in this class's put() - a materialize() triggered
   * from inside materialize()'s own copy must be a no-op or it recurses
   * forever. guarded by this view's monitor
   */
  private boolean materializing;

  public LazyRequestHeaders(OptimizedHeaders source) {
    this.source = source;
    source.attach(this);
  }

  /**
   * called when the view is handed to application code
   * (ExchangeImpl.getRequestHeaders): from this point the view can be
   * retained past response completion, so the table's clear() must
   * force-materialize it
   */
  @Override
  public boolean equals(Object o) {
    materialize();
    return super.equals(o);
  }

  @Override
  public int hashCode() {
    materialize();
    return super.hashCode();
  }

  public void markExposed() {
    exposed = true;
  }

  /**
   * true once handed out: clear() force-materializes only exposed
   * views, skipping the copy for the never-read case
   */
  boolean isExposed() { return exposed; }

  /**
   * called by OptimizedHeaders.clear() for an UNEXPOSED view: the
   * entries are about to be destroyed, and this view was never handed
   * to the handler. the exchange object itself can still reach the
   * view later (an async task calling getRequestHeaders for the first
   * time after the connection moved on) - such a late read must see
   * EMPTY, never the table's refilled next-request contents
   * (cross-request leakage)
   */
  void retire() {
    if (source == null) {
      return;
    }
    synchronized (this) {
      OptimizedHeaders s = source;
      if (s == null) {
        return;
      }
      source = null;
      s.detach(this);
    }
  }

  /**
   * copy the source table into the standard HashMap backing exactly
   * once; idempotent and safe to call from any thread. package-private:
   * OptimizedHeaders.clear() force-calls this so a never-read view
   * snapshots before the table is reused
   */
  void materialize() {
    if (source == null) {
      return;
    }
    synchronized (this) {
      if (source == null || materializing) {
        return;
      }
      materializing = true;
      try {
        OptimizedHeaders s = source;
        s.detach(this);
        s.copyInto(this);
      } finally {
        materializing = false;
        // publish AFTER the copy: a reader that sees null is
        // guaranteed the fully populated map
        source = null;
      }
    }
  }

  @Override
  public List<String> get(Object key) {
    materialize();
    return super.get(key);
  }

  @Override
  public List<String> getOrDefault(Object key, List<String> defaultValue) {
    materialize();
    return super.getOrDefault(key, defaultValue);
  }

  @Override
  public boolean containsKey(Object key) {
    materialize();
    return super.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    materialize();
    return super.containsValue(value);
  }

  @Override
  public int size() {
    materialize();
    return super.size();
  }

  @Override
  public boolean isEmpty() {
    materialize();
    return super.isEmpty();
  }

  @Override
  public Set<String> keySet() {
    materialize();
    return super.keySet();
  }

  @Override
  public Collection<List<String>> values() {
    materialize();
    return super.values();
  }

  @Override
  public Set<Map.Entry<String, List<String>>> entrySet() {
    materialize();
    return super.entrySet();
  }

  @Override
  public void forEach(BiConsumer<? super String, ? super List<String>> action) {
    materialize();
    super.forEach(action);
  }

  @Override
  public List<String> put(String key, List<String> value) {
    materialize();
    return super.put(key, value);
  }

  @Override
  public void putAll(Map<? extends String, ? extends List<String>> m) {
    materialize();
    super.putAll(m);
  }

  @Override
  public List<String> remove(Object key) {
    materialize();
    return super.remove(key);
  }

  @Override
  public boolean remove(Object key, Object value) {
    materialize();
    return super.remove(key, value);
  }

  @Override
  public void clear() {
    materialize();
    super.clear();
  }

  @Override
  public List<String> putIfAbsent(String key, List<String> value) {
    materialize();
    return super.putIfAbsent(key, value);
  }

  @Override
  public List<String> replace(String key, List<String> value) {
    materialize();
    return super.replace(key, value);
  }

  @Override
  public boolean replace(String key, List<String> oldValue, List<String> newValue) {
    materialize();
    return super.replace(key, oldValue, newValue);
  }

  @Override
  public void replaceAll(BiFunction<? super String, ? super List<String>, ? extends List<String>> function) {
    materialize();
    super.replaceAll(function);
  }

  @Override
  public List<String> computeIfAbsent(String key, Function<? super String, ? extends List<String>> mappingFunction) {
    materialize();
    return super.computeIfAbsent(key, mappingFunction);
  }

  @Override
  public List<String> computeIfPresent(String key,
      BiFunction<? super String, ? super List<String>, ? extends List<String>> remappingFunction) {
    materialize();
    return super.computeIfPresent(key, remappingFunction);
  }

  @Override
  public List<String> compute(String key,
      BiFunction<? super String, ? super List<String>, ? extends List<String>> remappingFunction) {
    materialize();
    return super.compute(key, remappingFunction);
  }

  @Override
  public List<String> merge(String key, List<String> value,
      BiFunction<? super List<String>, ? super List<String>, ? extends List<String>> remappingFunction) {
    materialize();
    return super.merge(key, value, remappingFunction);
  }

  @Override
  public String getFirst(String key) {
    materialize();
    return super.getFirst(key);
  }

  @Override
  public void add(String key, String value) {
    materialize();
    super.add(key, value);
  }

  @Override
  public void set(String key, String value) {
    materialize();
    super.set(key, value);
  }

}
