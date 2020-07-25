/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.openfine;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

class EmptyMap<K, V> implements Map<K, V> {
	@Override
	public int size() {
		return 0;
	}

	@Override
	public boolean isEmpty() {
		return true;
	}

	@Override
	public boolean containsKey(Object key) {
		return false;
	}

	@Override
	public boolean containsValue(Object value) {
		return false;
	}

	@Override
	public V get(Object key) {
		return null;
	}

	@Override
	public V put(K key, V value) {
		return null;
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> map) {
	}

	@Override
	public V remove(Object key) {
		return null;
	}

	@Override
	public void clear() {
	}

	@Override
	public Set<K> keySet() {
		return Collections.emptySet();
	}

	@Override
	public Collection<V> values() {
		return Collections.emptySet();
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		return Collections.emptySet();
	}
}