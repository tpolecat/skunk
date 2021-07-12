// Copyright (c) 2018-2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package skunk.data

import cats.Monad
import cats.data.StateT
import cats.syntax.all._
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop._

class SemispaceCacheTest extends ScalaCheckSuite {

  implicit val MonadGen: Monad[Gen] =
    new Monad[Gen] {
      def pure[A](a: A) = Gen.const(a)
      def flatMap[A, B](fa: Gen[A])(f: A => Gen[B]) = fa.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Gen[Either[A,B]]): Gen[B] = ???
    }

  // An empty cache of size in [0..10]
  val genEmpty: Gen[SemispaceCache[Int, String]] =
    Gen.choose(-1, 10).map(SemispaceCache.empty)

  // A short list of ints in [1..10]
  val genInts: Gen[List[Int]] =
    for {
      len  <- Gen.choose(1, 25)
      list <- Gen.listOfN(len, Gen.choose(1, 10))
    } yield list

  // Insert or read from `cache`
  def update(cache: SemispaceCache[Int, String], key: Int): Gen[SemispaceCache[Int, String]] =
    Gen.oneOf(
      Gen.const(cache.insert(key, key.toString)),
      Gen.const(cache.lookup(key).map(_._1).getOrElse(cache))
    )

  // Insert and read many times.
  def updateMany(cache: SemispaceCache[Int, String], keys: List[Int]): Gen[SemispaceCache[Int, String]] =
    keys.traverse(n => StateT.modifyF[Gen, SemispaceCache[Int, String]](update(_, n))).runS(cache)

  // A random cache.
  def genCache: Gen[SemispaceCache[Int, String]] =
    for {
      c  <- genEmpty
      ns <- genInts
      cʹ <- updateMany(c, ns)
    } yield cʹ

  test("max is never negative") {
    forAll(genCache) { c =>
      assert(c.max >= 0)
    }
  }

  test("gen0.size <= max") {
    forAll(genCache) { c =>
      assert(c.gen1.size <= c.max)
    }
  }

  test("gen1.size <= max") {
    forAll(genCache) { c =>
      assert(c.gen1.size <= c.max)
    }
  }

  test("insert should allow subsequent lookup, unless max == 0") {
    forAll(genCache) { c =>
      val cʹ = c.insert(1, "x")
      assertEquals(cʹ.lookup(1), if (c.max == 0) None else Some((cʹ, "x")))
    }
  }

  test("all keys in keyset can be looked up") {
    forAll(genCache) { c =>
      c.keySet.forall { k =>
        c.lookup(k).isDefined
      }
    }
  }

  test("keys that exist in both generations map to the same value") {
    forAll(genCache) { c =>
      val intersection = c.gen0.keySet intersect c.gen1.keySet
      intersection.forall { k =>
        c.gen0(k) == c.gen1(k)
      }
    }
  }

  def checkEvictions(c: SemispaceCache[Int, String], cʹ: SemispaceCache[Int, String], es: Map[Int, String]): Unit = {
    assert((cʹ.keySet intersect es.keySet).isEmpty, "Keyset and eviction keyset must be disjoint")
    c.keySet.foreach { k =>
      assert(cʹ.keySet.contains(k) || es.contains(k), "All keys in c must also be in cʹ or es.")
    }
  }

  test("eviction consistency on insert") {
    forAll(genCache) { c =>
      assert(c.lookup(100).isEmpty)
      val (cʹ, es) = c.insertWithEvictions(100, "x")
      checkEvictions(c, cʹ, es)
    }
  }

  test("eviction consistency on lookup") {
    forAll(genCache, Gen.choose(1, 10)) { (c, k) =>
      c.lookupWithEvictions(k).foreach { case (cʹ, _, es) =>
        checkEvictions(c, cʹ, es)
      }
    }
  }

  test("overflow") {
    forAll(genEmpty) { c =>
      val max = c.max

      // Load up the cache such that one more insert will cause it to overflow
      val cʹ = (0 until max).foldLeft(c) { case (c, n) => c.insert(n, "x") }
      assertEquals(cʹ.gen0.size, max)
      assertEquals(cʹ.gen1.size, 0)

      // Overflow the cache
      val cʹʹ = cʹ.insert(max, "x")
      assertEquals(cʹʹ.gen0.size, 1 min max)
      assertEquals(cʹʹ.gen1.size, max)

    }
  }

  test("promotion") {
    forAll(genEmpty) { c =>
      val max = c.max

      // Load up the cache such that it overflows by 1
      val cʹ = (0 to max).foldLeft(c) { case (c, n) => c.insert(n, "x") }
      assertEquals(cʹ.gen0.size, 1 min max)
      assertEquals(cʹ.gen1.size, max)

      // Look up something that was demoted.
      cʹ.lookup(0) match {
        case None => assertEquals(max, 0)
        case Some((cʹʹ, _)) =>
          assertEquals(cʹʹ.gen0.size, 2 min max)
          assertEquals(cʹʹ.gen1.size, max)
      }

    }
  }

}
