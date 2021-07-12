// Copyright (c) 2018-2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package skunk.data

import cats.syntax.all._

/**
 * Cache based on a two-generation GC.
 * Taken from https://twitter.com/pchiusano/status/1260255494519865346
 */
sealed abstract case class SemispaceCache[K, V](gen0: Map[K, V], gen1: Map[K, V], max: Int) {

  /** All keys in the cache. */
  def keySet: Set[K] =
    gen0.keySet ++ gen1.keySet

  /** Insert the given pair, yielding a new SemispaceCache. */
  def insert(k: K, v: V): SemispaceCache[K, V] =
    insertWithEvictions(k, v)._1

  /**
   * Insert the given pair, yielding a new SemispaceCache and a map containing any entries that
   * were evicted as a result.
   */
  def insertWithEvictions(k: K, v: V): (SemispaceCache[K, V], Map[K, V]) =
    if (max == 0) {
      // Special case, can't insert. No evictions.
      (this, Map.empty)
    } else if (gen0.size < max || gen0.contains(k)) {
      // There is room in gen0. Add/replace the mapping. No evictions.
      (SemispaceCache(gen0 + (k -> v), gen1, max), Map.empty)
    } else {
      // There is no room in gen0. Make a new gen0 with a single entry. Our new gen1 is our old
      // gen0. Evictions are anything in the old gen1 that isn't in our new
      val evicted   = gen1 -- (gen0.keySet + k)
      (SemispaceCache(Map(k -> v), gen0, max), evicted)
    }

  /** Look up the given key, yielding a new SemispaceCache and a value on success. */
  def lookup(k: K): Option[(SemispaceCache[K, V], V)] =
    lookupWithEvictions(k).map { case (c, v, _) => (c, v) }

  /**
   * Look up the given key, yielding a new SemispaceCache, a value on success, and a map containing
   * any entries that were evicted as a result.
   */
  def lookupWithEvictions(k: K): Option[(SemispaceCache[K, V], V, Map[K, V])] =
    gen0.get(k).map(v => (this, v, Map.empty[K, V])) orElse       // key is in gen0, done!
    gen1.get(k).map { v =>
      // key is in gen1, copy to gen0
      val (c, e) = insertWithEvictions(k, v)
      (c, v, e)
    }

  /** True if the given key exists in the cache. */
  def containsKey(k: K): Boolean =
    gen0.contains(k) || gen1.contains(k)

}

object SemispaceCache {

  private def apply[K, V](gen0: Map[K, V], gen1: Map[K, V], max: Int): SemispaceCache[K, V] =
    new SemispaceCache[K, V](gen0, gen1, max) {}

  def empty[K, V](max: Int): SemispaceCache[K, V] =
    SemispaceCache[K, V](Map.empty, Map.empty, max max 0)

}