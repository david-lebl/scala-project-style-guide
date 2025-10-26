import mill._
import mill.scalalib._

// ZIO version
val zioVersion = "2.1.22"
val zioHttpVersion = "3.0.1"
val zioJsonVersion = "0.7.4"

// Scala version
val scalaVersion = "3.7.3"

/**
 * Worker module - demonstrates Use Case Pattern with scheduled background jobs.
 *
 * Module structure:
 * - worker-01-core: Domain logic, use cases, queries, scheduler
 * - worker-02-db: PostgreSQL implementations
 * - worker-02-http-server: REST API with ZIO HTTP
 * - worker-03-impl: Pre-wired layer bundles
 * - worker-04-app: Main application
 */
object worker extends Module {

  // 01-core: Domain logic (no infrastructure dependencies)
  object `01-core` extends ScalaModule {
    def scalaVersion = scalaVersion

    def ivyDeps = Agg(
      ivy"dev.zio::zio:$zioVersion"
    )

    def moduleDeps = Seq()
  }

  // 02-db: Database implementations
  object `02-db` extends ScalaModule {
    def scalaVersion = scalaVersion

    def ivyDeps = Agg(
      ivy"dev.zio::zio:$zioVersion",
      ivy"org.postgresql:postgresql:42.7.4"
    )

    def moduleDeps = Seq(`01-core`)
  }

  // 02-http-server: REST API
  object `02-http-server` extends ScalaModule {
    def scalaVersion = scalaVersion

    def ivyDeps = Agg(
      ivy"dev.zio::zio:$zioVersion",
      ivy"dev.zio::zio-http:$zioHttpVersion",
      ivy"dev.zio::zio-json:$zioJsonVersion"
    )

    def moduleDeps = Seq(`01-core`)
  }

  // 03-impl: Pre-wired bundles
  object `03-impl` extends ScalaModule {
    def scalaVersion = scalaVersion

    def ivyDeps = Agg(
      ivy"dev.zio::zio:$zioVersion",
      ivy"dev.zio::zio-http:$zioHttpVersion"
    )

    def moduleDeps = Seq(`01-core`, `02-db`, `02-http-server`)
  }

  // 04-app: Main application
  object `04-app` extends ScalaModule {
    def scalaVersion = scalaVersion

    def ivyDeps = Agg(
      ivy"dev.zio::zio:$zioVersion",
      ivy"dev.zio::zio-http:$zioHttpVersion",
      ivy"com.zaxxer:HikariCP:6.2.1" // Connection pool
    )

    def moduleDeps = Seq(`03-impl`)
  }
}