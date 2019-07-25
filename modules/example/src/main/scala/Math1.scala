// Copyright (c) 2018 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package example

import cats.effect.{Bracket, ExitCode, IO, IOApp, Resource}
import skunk.Session
import skunk.implicits._
import skunk.codec.numeric.{float8, int4}
import natchez.Trace.Implicits.noop

object Math1 extends IOApp {

  val session: Resource[IO, Session[IO]] =
    Session.single(
      host     = "localhost",
      port     = 5432,
      user     = "postgres",
      database = "world"
    )

  // An algebra for doing math.
  trait Math[F[_]] {
    def add(a:  Int, b: Int): F[Int]
    def sqrt(d: Double): F[Double]
  }

  object Math {

    object Statements {
      val add  = sql"select $int4 + $int4".query(int4)
      val sqrt = sql"select sqrt($float8)".query(float8)
    }

    // `Math` implementation that delegates its work to Postgres.
    def fromSession[F[_]: Bracket[?[_], Throwable]](sess: Session[F]): Math[F] =
      new Math[F] {
        def add(a:  Int, b: Int) = sess.prepare(Statements.add).use(_.unique(a ~ b))
        def sqrt(d: Double) = sess.prepare(Statements.sqrt).use(_.unique(d))
      }

  }

  def run(args: List[String]): IO[ExitCode] =
    session.map(Math.fromSession(_)).use { m =>
      for {
        n <- m.add(42, 71)
        d <- m.sqrt(2)
        d2 <- m.sqrt(42)
        _ <- IO(println(s"The answers were $n and $d and $d2"))
      } yield ExitCode.Success
    }

}
