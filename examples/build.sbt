val scala3Version = "3.5.2"
val zioVersion    = "2.1.14"

ThisBuild / scalaVersion := scala3Version
ThisBuild / organization := "com.myco"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-Xfatal-warnings",
    "-Wunused:all"
  ),
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio"      % zioVersion,
    "dev.zio" %% "zio-test" % zioVersion % Test
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

// ── Ordering bounded context ─────────────────────────────────────

lazy val orderingCore = project
  .in(file("ordering-core"))
  .settings(commonSettings)
  .settings(name := "ordering-core")

lazy val orderingInfra = project
  .in(file("ordering-infra"))
  .settings(commonSettings)
  .settings(name := "ordering-infra")
  .dependsOn(orderingCore)

// ── Application ──────────────────────────────────────────────────

lazy val app = project
  .in(file("app"))
  .settings(commonSettings)
  .settings(name := "app")
  .dependsOn(orderingInfra)

// ── Root ─────────────────────────────────────────────────────────

lazy val root = project
  .in(file("."))
  .aggregate(orderingCore, orderingInfra, app)
  .settings(
    name    := "scala-style-guide-examples",
    publish := {},
    publishLocal := {}
  )
