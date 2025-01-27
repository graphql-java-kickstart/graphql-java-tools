package graphql.kickstart.tools.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.Collections.unmodifiableMap;

@SuppressWarnings("SuspiciousMethodCalls")
public class BiMap<K, V> implements Map<K, V> {

    private final Map<K, V> delegate;
    private final Map<V, K> inverse;

    public static <K, V> BiMap<K, V> create() {
        return new BiMap<>(new HashMap<>(), new HashMap<>());
    }

    public static <K, V> BiMap<K, V> unmodifiableBiMap(BiMap<K, V> biMap) {
        return new BiMap<>(unmodifiableMap(biMap.delegate), unmodifiableMap(biMap.inverse));
    }

    private BiMap(Map<K, V> delegate, Map<V, K> inverse) {
        this.delegate = delegate;
        this.inverse = inverse;
    }

    public BiMap<V, K> inverse() {
        return new BiMap<>(inverse, delegate);
    }

    @Override
    public void clear() {
        delegate.clear();
        inverse.clear();
    }

    @Override
    public boolean containsKey(Object key) {
        return delegate.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return inverse.containsKey(value);
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return delegate.entrySet();
    }

    @Override
    public V get(Object key) {
        return delegate.get(key);
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public Set<K> keySet() {
        return delegate.keySet();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the given value is already bound to a different key in this bimap.
     */
    @Override
    public V put(K key, V value) {
        // if the key already exists, and the value is the same as the old, then nothing to do
        boolean containedKey = containsKey(key);
        if (containedKey && Objects.equals(value, get(key))) {
            return value;
        }

        // if the value already exists, then it's not ok
        if (containsValue(value)) {
            throw new IllegalArgumentException("value already present: " + value);
        }

        // put the value; if the key already exists, it replaces an existing value, and we have to remove it from the inverse as well
        V oldValue = delegate.put(key, value);
        if (containedKey) {
            inverse.remove(oldValue);
        }
        inverse.put(value, key);

        return oldValue;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if an attempt to {@code put} any entry fails.
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        for (Entry<? extends K, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public V remove(Object key) {
        if (!containsKey(key)) {
            return null;
        }

        inverse.remove(delegate.get(key));
        return delegate.remove(key);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public Set<V> values() {
        return inverse.keySet();
    }
}
