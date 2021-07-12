// Copyright (c) 2018-2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package skunk

import cats._
import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._
import fs2.concurrent.Signal
import fs2.io.net.{ Network, SocketGroup }
import fs2.Pipe
import fs2.Stream
import natchez.Trace
import skunk.codec.all.bool
import skunk.data._
import skunk.net.Protocol
import skunk.util._
import skunk.util.Typer.Strategy.{ BuiltinsOnly, SearchPath }
import skunk.net.SSLNegotiation
import skunk.data.TransactionIsolationLevel
import skunk.data.TransactionAccessMode
import skunk.net.protocol.Describe

/**
 * Represents a live connection to a Postgres database. Operations provided here are safe to use
 * concurrently. Note that this is a lifetime-managed resource and as such is invalid outside the
 * scope of its owning `Resource`, as are any streams constructed here. If you `start` an operation
 * be sure to `join` its `Fiber` before releasing the resource.
 *
 * See the [[skunk.Session companion object]] for information on obtaining a pooled or single-use
 * instance.
 *
 * @groupprio Queries 10
 * @groupdesc Queries A query is any SQL statement that returns rows; i.e., any `SELECT` or
 * `VALUES` query, or an `INSERT`, `UPDATE`, or `DELETE` command that returns rows via `RETURNING`.
 *  Parameterized queries must first be prepared, then can be executed many times with different
 *  arguments. Non-parameterized queries can be executed directly.

 * @groupprio Commands 20
 * @groupdesc Commands A command is any SQL statement that cannot return rows. Parameterized
 *   commands must first be prepared, then can be executed many times with different arguments.
 *   Commands without parameters can be executed directly.
 *
 * @groupprio Transactions 25
 * @groupdesc Transactions Users can manipulate transactions directly via commands like `BEGIN` and
 *   `COMMIT`, but dealing with cancellation and error conditions can be complicated and repetitive.
 *   Skunk provides managed transaction blocks to make this easier.
 *
 * @groupprio Channels 30
 *
 * @groupprio Environment 30
 * @groupname Environment Session Environment
 * @groupdesc Environment The Postgres session has a dynamic environment that includes a
 *   configuration map with keys like `TimeZone` and `server_version`, as well as a current
 *   `TransactionStatus`. These can change asynchronously and are exposed as `Signal`s. Note that
 *   any `Stream` based on these signals is only valid for the lifetime of the `Session`.
 *
 * @group Session
 */
trait Session[F[_]] {

  /**
   * Signal representing the current state of all Postgres configuration variables announced to this
   * session. These are sent after authentication and are updated asynchronously if the runtime
   * environment changes. The current keys are as follows (with example values), but these may
   * change with future releases so you should be prepared to handle unexpected ones.
   *
   * {{{
   * Map(
   *   "application_name"            -> "",
   *   "client_encoding"             -> "UTF8",
   *   "DateStyle"                   -> "ISO, MDY",
   *   "integer_datetimes"           -> "on",       // cannot change after startup
   *   "IntervalStyle"               -> "postgres",
   *   "is_superuser"                -> "on",
   *   "server_encoding"             -> "UTF8",     // cannot change after startup
   *   "server_version"              -> "9.5.3",    // cannot change after startup
   *   "session_authorization"       -> "postgres",
   *   "standard_conforming_strings" -> "on",
   *   "TimeZone"                    -> "US/Pacific",
   * )
   * }}}
   * @group Environment
   */
  def parameters: Signal[F, Map[String, String]]

  /**
   * Stream (possibly empty) of discrete values for the specified key, via `parameters`.
   * @group Environment
   */
  def parameter(key: String): Stream[F, String]

  /**
   * Signal representing the current transaction status.
   * @group Environment
   */
  def transactionStatus: Signal[F, TransactionStatus]

  /**
   * Execute a non-parameterized query and yield all results. If you have parameters or wish to limit
   * returned rows use `prepare` instead.
   * @group Queries
   */
  def execute[A](query: Query[Void, A]): F[List[A]]

  /**
   * Execute a non-parameterized query and yield exactly one row, raising an exception if there are
   * more or fewer. If you have parameters use `prepare` instead.
   * @group Queries
   */
  def unique[A](query: Query[Void, A]): F[A]

  /**
   * Execute a non-parameterized query and yield at most one row, raising an exception if there are
   * more. If you have parameters use `prepare` instead.
   * @group Queries
   */
  def option[A](query: Query[Void, A]): F[Option[A]]

  /**
   * Execute a non-parameterized command and yield a `Completion`. If you have parameters use
   * `prepare` instead.
   * @group Commands
   */
  def execute(command: Command[Void]): F[Completion]

  /**
   * Resource that prepares a query, yielding a `PreparedQuery` which can be executed multiple
   * times with different arguments.
   * @group Queries
   */
  def prepare[A, B](query: Query[A, B]): Resource[F, PreparedQuery[F, A, B]]

  /**
   * Prepare an `INSERT`, `UPDATE`, or `DELETE` command that returns no rows. The resulting
   * `PreparedCommand` can be executed multiple times with different arguments.
   * @group Commands
   */
  def prepare[A](command: Command[A]): Resource[F, PreparedCommand[F, A]]

  /**
   * Transform a `Command` into a `Pipe` from inputs to `Completion`s.
   * @group Commands
   */
  def pipe[A](command: Command[A]): Pipe[F, A, Completion]

  /**
   * A named asynchronous channel that can be used for inter-process communication.
   * @group Channels
   */
  def channel(name: Identifier): Channel[F, String, String]

  /**
   * Resource that wraps a transaction block. A transaction is begun before entering the `use`
   * block, on success the block is executed, and on exit the following behavior holds.
   *
   *   - If the block exits normally, and the session transaction status is
   *     - `Active`, then the transaction will be committed.
   *     - `Idle`, then this means the user terminated the
   *       transaction explicitly inside the block and there is nothing to be done.
   *     - `Error` then this means the user encountered and
   *       handled an error but left the transaction in a failed state, and the transaction will
   *       be rolled back.
   *   - If the block exits due to cancellation or an error and the session transaction status is
   *     not `Idle` then the transaction will be rolled back and any error will be re-raised.
   * @group Transactions
   */
  def transaction[A]: Resource[F, Transaction[F]]


  /**
    * Resource that wraps a transaction block.
    * It has the ability to specify a non-default isolation level and access mode.
    * @see Session#transaction for more information
    * @group Transactions
    */
  def transaction[A](isolationLevel: TransactionIsolationLevel, accessMode: TransactionAccessMode): Resource[F, Transaction[F]]

  def typer: Typer

  /**
   * Each session has access to the pool-wide cache of all statements that have been checked via the
   * `Describe` protocol, which allows us to skip subsequent checks. Users can inspect and clear
   * the cache through this accessor.
   */
  def describeCache: Describe.Cache[F]

  /**
   * Stream for all notifications on all subscribed channels.
   */
  def notifications(maxQueued: Int): Stream[F, Notification[String]]
}



/** @group Companions */
object Session {
  val DefaultConnectionParameters: Map[String, String] =
    Map(
      "client_min_messages" -> "WARNING",
      "DateStyle"           -> "ISO, MDY",
      "IntervalStyle"       -> "iso_8601",
      "client_encoding"     -> "UTF8",
    )

  object Recyclers {

    /**
     * Ensure the session is idle, then remove all channel listeners and reset all variables to
     * system defaults. Note that this is usually more work than you need to do. If your application
     * isn't running arbitrary statements then `minimal` might be more efficient.
     */
    def full[F[_]: Monad]: Recycler[F, Session[F]] =
      ensureIdle[F] <+> unlistenAll <+> resetAll

    /**
     * Ensure the session is idle, then run a trivial query to ensure the connection is in working
     * order. In most cases this check is sufficient.
     */
    def minimal[F[_]: Monad]: Recycler[F, Session[F]] =
      ensureIdle[F] <+> Recycler(_.unique(Query("VALUES (true)", Origin.unknown, Void.codec, bool)))

    /**
     * Yield `true` the session is idle (i.e., that there is no ongoing transaction), otherwise
     * yield false. This check does not require network IO.
     */
    def ensureIdle[F[_]: Monad]: Recycler[F, Session[F]] =
      Recycler(_.transactionStatus.get.map(_ == TransactionStatus.Idle))

    /** Remove all channel listeners and yield `true`. */
    def unlistenAll[F[_]: Functor]: Recycler[F, Session[F]] =
      Recycler(_.execute(Command("UNLISTEN *", Origin.unknown, Void.codec)).as(true))

    /** Reset all variables to system defaults and yield `true`. */
    def resetAll[F[_]: Functor]: Recycler[F, Session[F]] =
      Recycler(_.execute(Command("RESET ALL", Origin.unknown, Void.codec)).as(true))

  }

  /**
   * Resource yielding a `SessionPool` managing up to `max` concurrent `Session`s. Typically you
   * will `use` this resource once on application startup and pass the resulting
   * `Resource[F, Session[F]]` to the rest of your program.
   *
   * The pool maintains a cache of queries and commands that have been checked against the schema,
   * eliminating the need to check them more than once. If your program is changing the schema on
   * the fly than you probably don't want this behavior; you can disable it by setting the
   * `commandCache` and `queryCache` parameters to zero.
   *
   * Note that calling `.flatten` on the nested `Resource` returned by this method may seem
   * reasonable, but it will result in a resource that allocates a new pool for each session, which
   * is probably not what you want.
   * @param host          Postgres server host
   * @param port          Postgres port, default 5432
   * @param user          Postgres user
   * @param database      Postgres database
   * @param max           Maximum concurrent sessions
   * @param debug
   * @param strategy
   * @param commandCache  Size of the cache for command checking
   * @param queryCache    Size of the cache for query checking
   * @group Constructors
   */
  def pooled[F[_]: Concurrent: Trace: Network: Console](
    host:         String,
    port:         Int            = 5432,
    user:         String,
    database:     String,
    password:     Option[String] = none,
    max:          Int,
    debug:        Boolean        = false,
    strategy:     Typer.Strategy = Typer.Strategy.BuiltinsOnly,
    ssl:          SSL            = SSL.None,
    parameters:   Map[String, String] = Session.DefaultConnectionParameters,
    commandCache: Int = 1024,
    queryCache:   Int = 1024,
  ): Resource[F, Resource[F, Session[F]]] = {

    def session(socketGroup:  SocketGroup[F], sslOp: Option[SSLNegotiation.Options[F]], cache: Describe.Cache[F]): Resource[F, Session[F]] =
      fromSocketGroup[F](socketGroup, host, port, user, database, password, debug, strategy, sslOp, parameters, cache)

    val logger: String => F[Unit] = s => Console[F].println(s"TLS: $s")

    for {
      dc      <- Resource.eval(Describe.Cache.empty[F](commandCache, queryCache))
      sslOp   <- Resource.eval(ssl.toSSLNegotiationOptions(if (debug) logger.some else none))
      pool    <- Pool.of(session(Network[F], sslOp, dc), max)(Recyclers.full)
    } yield pool

  }

  /**
   * Resource yielding logically unpooled sessions. This can be convenient for demonstrations and
   * programs that only need a single session. In reality each session is managed by its own
   * single-session pool. This method is shorthand for `Session.pooled(..., max = 1, ...).flatten`.
   * @see pooled
   */
  def single[F[_]: Concurrent: Trace: Network: Console](
    host:         String,
    port:         Int            = 5432,
    user:         String,
    database:     String,
    password:     Option[String] = none,
    debug:        Boolean        = false,
    strategy:     Typer.Strategy = Typer.Strategy.BuiltinsOnly,
    ssl:          SSL            = SSL.None,
    parameters:   Map[String, String] = Session.DefaultConnectionParameters,
    commandCache: Int = 1024,
    queryCache:   Int = 1024,
  ): Resource[F, Session[F]] =
    pooled(
      host         = host,
      port         = port,
      user         = user,
      database     = database,
      password     = password,
      max          = 1,
      debug        = debug,
      strategy     = strategy,
      ssl          = ssl,
      parameters   = parameters,
      commandCache = commandCache,
      queryCache   = queryCache,
    ).flatten

  def fromSocketGroup[F[_]: Concurrent: Trace: Console](
    socketGroup:  SocketGroup[F],
    host:         String,
    port:         Int            = 5432,
    user:         String,
    database:     String,
    password:     Option[String] = none,
    debug:        Boolean        = false,
    strategy:     Typer.Strategy = Typer.Strategy.BuiltinsOnly,
    sslOptions:   Option[SSLNegotiation.Options[F]],
    parameters:   Map[String, String],
    describeCache: Describe.Cache[F],
  ): Resource[F, Session[F]] =
    for {
      namer <- Resource.eval(Namer[F])
      proto <- Protocol[F](host, port, debug, namer, socketGroup, sslOptions, describeCache)
      _     <- Resource.eval(proto.startup(user, database, password, parameters))
      sess  <- Resource.eval(fromProtocol(proto, namer, strategy))
    } yield sess

  /**
   * Construct a `Session` by wrapping an existing `Protocol`, which we assume has already been
   * started up.
   * @group Constructors
   */
  def fromProtocol[F[_]](
    proto:    Protocol[F],
    namer:    Namer[F],
    strategy: Typer.Strategy
  )(implicit ev: MonadCancel[F, Throwable]): F[Session[F]] = {

    val ft: F[Typer] =
      strategy match {
        case BuiltinsOnly => Typer.Static.pure[F]
        case SearchPath   => Typer.fromProtocol(proto)
      }

    ft.map { typ =>
      new Session[F] {

        override val typer: Typer = typ

        override def execute(command: Command[Void]): F[Completion] =
          proto.execute(command)

        override def channel(name: Identifier): Channel[F, String, String] =
          Channel.fromNameAndProtocol(name, proto)

        override def parameters: Signal[F, Map[String, String]] =
          proto.parameters

        override def parameter(key: String): Stream[F, String] =
          parameters.discrete.map(_.get(key)).unNone.changes

        override def transactionStatus: Signal[F, TransactionStatus] =
          proto.transactionStatus

        override def execute[A](query: Query[Void, A]): F[List[A]] =
          proto.execute(query, typer)

        override def pipe[A](command: Command[A]): Pipe[F, A, Completion] = fa =>
          Stream.resource(prepare(command)).flatMap(pc => fa.evalMap(pc.execute)).scope

        override def unique[A](query: Query[Void, A]): F[A] =
          execute(query).flatMap {
            case a :: Nil => a.pure[F]
            case Nil      => ev.raiseError(new RuntimeException("Expected exactly one row, none returned."))
            case _        => ev.raiseError(new RuntimeException("Expected exactly one row, more returned."))
          }

        override def option[A](query: Query[Void, A]): F[Option[A]] =
          execute(query).flatMap {
            case a :: Nil => a.some.pure[F]
            case Nil      => none[A].pure[F]
            case _        => ev.raiseError(new RuntimeException("Expected at most one row, more returned."))
          }

        override def prepare[A, B](query: Query[A, B]): Resource[F, PreparedQuery[F, A, B]] =
          proto.prepare(query, typer).map(PreparedQuery.fromProto(_))

        override def prepare[A](command: Command[A]): Resource[F, PreparedCommand[F, A]] =
          proto.prepare(command, typer).map(PreparedCommand.fromProto(_))

        override def transaction[A]: Resource[F, Transaction[F]] =
          Transaction.fromSession(this, namer, none, none)

        override def transaction[A](isolationLevel: TransactionIsolationLevel, accessMode: TransactionAccessMode): Resource[F, Transaction[F]] =
          Transaction.fromSession(this, namer, isolationLevel.some, accessMode.some)

        override def describeCache: Describe.Cache[F] =
          proto.describeCache

        override def notifications(maxQueued: Int): Stream[F, Notification[String]] =
          proto.notifications(maxQueued)

      }
    }
  }

  // TODO: upstream
  implicit class SignalOps[F[_], A](outer: Signal[F, A]) {
    def mapK[G[_]](fk: F ~> G): Signal[G, A] =
      new Signal[G, A] {
        def continuous: Stream[G,A] = outer.continuous.translate(fk)
        def discrete: Stream[G,A] = outer.continuous.translate(fk)
        def get: G[A] = fk(outer.get)
      }
  }

  implicit class SessionSyntax[F[_]](outer: Session[F]) {

    /**
     * Transform this `Session` by a given `FunctionK`.
     * @group Transformations
     */
    def mapK[G[_]: MonadCancelThrow](fk: F ~> G)(
      implicit mcf: MonadCancel[F, _]
    ): Session[G] =
      new Session[G] {

        override val typer: Typer = outer.typer

        override def channel(name: Identifier): Channel[G,String,String] = outer.channel(name).mapK(fk)

        override def execute(command: Command[Void]): G[Completion] = fk(outer.execute(command))

        override def execute[A](query: Query[Void,A]): G[List[A]] = fk(outer.execute(query))

        override def pipe[A](command: Command[A]): Pipe[G, A, Completion] = fa =>
          Stream.resource(prepare(command)).flatMap(pc => fa.evalMap(pc.execute)).scope

        override def option[A](query: Query[Void,A]): G[Option[A]] = fk(outer.option(query))

        override def parameter(key: String): Stream[G,String] = outer.parameter(key).translate(fk)

        override def parameters: Signal[G,Map[String,String]] = outer.parameters.mapK(fk)

        override def prepare[A, B](query: Query[A,B]): Resource[G,PreparedQuery[G,A,B]] = outer.prepare(query).mapK(fk).map(_.mapK(fk))

        override def prepare[A](command: Command[A]): Resource[G,PreparedCommand[G,A]] = outer.prepare(command).mapK(fk).map(_.mapK(fk))

        override def transaction[A]: Resource[G,Transaction[G]] = outer.transaction[A].mapK(fk).map(_.mapK(fk))

        override def transaction[A](isolationLevel: TransactionIsolationLevel, accessMode: TransactionAccessMode): Resource[G,Transaction[G]] =
          outer.transaction[A](isolationLevel, accessMode).mapK(fk).map(_.mapK(fk))

        override def transactionStatus: Signal[G,TransactionStatus] = outer.transactionStatus.mapK(fk)

        override def unique[A](query: Query[Void,A]): G[A] = fk(outer.unique(query))

        override def describeCache: Describe.Cache[G] = outer.describeCache.mapK(fk)

        override def notifications(maxQueued: Int): Stream[G, Notification[String]] =
          outer.notifications(maxQueued).translate(fk)
      }
  }

}
