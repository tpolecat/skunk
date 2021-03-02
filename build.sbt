

// Our Scala versions.
lazy val `scala-2.12`     = "2.12.13"
lazy val `scala-2.13`     = "2.13.5"
lazy val `scala-3.0-prev` = "3.0.0-M3"
lazy val `scala-3.0-curr` = "3.0.0-RC1"

// This is used in a couple places
lazy val fs2Version = "3.0.0-M9"
lazy val natchezVersion = "0.1.0-M4"


// We do `evictionCheck` in CI
inThisBuild(Seq(
  evictionRules ++= Seq(
    "org.typelevel" % "cats-*" % "semver-spec",
  )
))

// Global Settings
lazy val commonSettings = Seq(

  // Resolvers
  resolvers += Resolver.sonatypeRepo("public"),
  resolvers += Resolver.sonatypeRepo("snapshots"),

  // Publishing
  organization := "org.tpolecat",
  licenses    ++= Seq(("MIT", url("http://opensource.org/licenses/MIT"))),
  homepage     := Some(url("https://github.com/tpolecat/skunk")),
  developers   := List(
    Developer("tpolecat", "Rob Norris", "rob_norris@mac.com", url("http://www.tpolecat.org"))
  ),

  // Headers
  headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
  headerLicense  := Some(HeaderLicense.Custom(
    """|Copyright (c) 2018-2020 by Rob Norris
       |This software is licensed under the MIT License (MIT).
       |For more information see LICENSE or https://opensource.org/licenses/MIT
       |""".stripMargin
    )
  ),

  // Compilation
  scalaVersion       := `scala-2.13`,
  crossScalaVersions := Seq(`scala-2.12`, `scala-2.13`, `scala-3.0-prev`, `scala-3.0-curr`),
  scalacOptions -= "-language:experimental.macros", // doesn't work cross-version
  Compile / doc     / scalacOptions --= Seq("-Xfatal-warnings"),
  Compile / doc     / scalacOptions ++= Seq(
    "-groups",
    "-sourcepath", (baseDirectory in LocalRootProject).value.getAbsolutePath,
    "-doc-source-url", "https://github.com/tpolecat/skunk/blob/v" + version.value + "€{FILE_PATH}.scala",
  ),
  libraryDependencies ++= Seq(
    compilerPlugin("org.typelevel" %% "kind-projector" % "0.11.3" cross CrossVersion.full),
  ).filterNot(_ => isDotty.value),

  // Coverage Exclusions
  coverageExcludedPackages := "ffstest.*;tests.*;example.*;natchez.http4s.*",

  // uncomment in case of emergency
  // scalacOptions ++= { if (isDotty.value) Seq("-source:3.0-migration") else Nil },

  // Add some more source directories
  unmanagedSourceDirectories in Compile ++= {
    val sourceDir = (sourceDirectory in Compile).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _))  => Seq(sourceDir / "scala-3", sourceDir / "scala-2.13+")
      case Some((2, 12)) => Seq(sourceDir / "scala-2")
      case Some((2, _))  => Seq(sourceDir / "scala-2", sourceDir / "scala-2.13+")
      case _             => Seq()
    }
  },

  // Also for test
  unmanagedSourceDirectories in Test ++= {
    val sourceDir = (sourceDirectory in Test).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _))  => Seq(sourceDir / "scala-3")
      case Some((2, _))  => Seq(sourceDir / "scala-2")
      case _             => Seq()
    }
  },

  // dottydoc really doesn't work at all right now
  Compile / doc / sources := {
    val old = (Compile / doc / sources).value
    if (isDotty.value)
      Seq()
    else
      old
  },

)

lazy val skunk = project
  .in(file("."))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .settings(publish / skip := true)
  .dependsOn(core, tests, circe, refined, example)
  .aggregate(core, tests, circe, refined, example)

lazy val core = project
  .in(file("modules/core"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .settings(
    name := "skunk-core",
    description := "Tagless, non-blocking data access library for Postgres.",
    resolvers   +=  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    libraryDependencies ++= Seq(
      "org.typelevel"    %% "cats-core"    % "2.4.2",
      "org.typelevel"    %% "cats-effect"  % "3.0.0-RC2",
      "co.fs2"           %% "fs2-core"     % fs2Version,
      "co.fs2"           %% "fs2-io"       % fs2Version,
      "org.scodec"       %% "scodec-core"  % (if (isDotty.value) "2.0.0-RC1" else "1.11.7"),
      "org.scodec"       %% "scodec-cats"  % "1.1.0-RC1",
      "org.tpolecat"     %% "natchez-core" % natchezVersion,
      "com.ongres.scram"  % "client"       % "2.1",
      "org.tpolecat"     %% "sourcepos"    % "0.1.1",
    ) ++ Seq(
      "com.beachape"  %% "enumeratum"   % "1.6.1",
    ).map(_.withDottyCompat(scalaVersion.value)) ++ Seq(
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.2",
    )
  )

lazy val refined = project
  .in(file("modules/refined"))
  .dependsOn(core)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "eu.timepit" %% "refined" % "0.9.21",
    ).map(_.withDottyCompat(scalaVersion.value))
  )

lazy val circe = project
  .in(file("modules/circe"))
  .dependsOn(core)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .settings(
    name := "skunk-circe",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core"   % "0.13.0",
      "io.circe" %% "circe-parser" % "0.13.0"
    ).filterNot(_ => isDotty.value)
  )

lazy val tests = project
  .in(file("modules/tests"))
  .dependsOn(core, circe)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    scalacOptions  -= "-Xfatal-warnings",
    libraryDependencies ++= Seq(
      "org.typelevel"     %% "scalacheck-effect-munit" % "0.7.1",
      "org.typelevel"     %% "munit-cats-effect-3"     % "0.13.1",
      "org.typelevel"     %% "cats-free"               % "2.4.2",
      "org.typelevel"     %% "cats-laws"               % "2.4.2",
      "org.typelevel"     %% "discipline-munit"        % "1.0.6",
    ) ++ Seq(
      "io.chrisdavenport" %% "cats-time"               % "0.3.4",
    ).filterNot(_ => isDotty.value),
    testFrameworks += new TestFramework("munit.Framework"),
  )

lazy val example = project
  .in(file("modules/example"))
  .dependsOn(core)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.tpolecat"  %% "natchez-honeycomb"   % natchezVersion,
      "org.tpolecat"  %% "natchez-jaeger"      % natchezVersion,
    ) ++ Seq(
      "org.http4s"    %% "http4s-dsl"          % "1.0.0-M18",
      "org.http4s"    %% "http4s-blaze-server" % "1.0.0-M18",
      "org.http4s"    %% "http4s-circe"        % "1.0.0-M18",
      "io.circe"      %% "circe-generic"       % "0.14.0-M4",
    ).filterNot(_ => isDotty.value)
  )

lazy val docs = project
  .in(file("modules/docs"))
  .dependsOn(core)
  .enablePlugins(AutomateHeaderPlugin)
  .enablePlugins(ParadoxPlugin)
  .enablePlugins(ParadoxSitePlugin)
  .enablePlugins(GhpagesPlugin)
  .enablePlugins(MdocPlugin)
  .settings(commonSettings)
  .settings(
    scalacOptions      := Nil,
    git.remoteRepo     := "git@github.com:tpolecat/skunk.git",
    ghpagesNoJekyll    := true,
    publish / skip     := true,
    paradoxTheme       := Some(builtinParadoxTheme("generic")),
    version            := version.value.takeWhile(_ != '+'), // strip off the +3-f22dca22+20191110-1520-SNAPSHOT business
    paradoxProperties ++= Map(
      "scala-versions"          -> (crossScalaVersions in core).value.map(CrossVersion.partialVersion).flatten.map(_._2).mkString("2.", "/", ""),
      "org"                     -> organization.value,
      "scala.binary.version"    -> s"2.${CrossVersion.partialVersion(scalaVersion.value).get._2}",
      "core-dep"                -> s"${(core / name).value}_2.${CrossVersion.partialVersion(scalaVersion.value).get._2}",
      "circe-dep"               -> s"${(circe / name).value}_2.${CrossVersion.partialVersion(scalaVersion.value).get._2}",
      "version"                 -> version.value,
      "scaladoc.skunk.base_url" -> s"https://static.javadoc.io/org.tpolecat/skunk-core_2.12/${version.value}",
      "scaladoc.fs2.io.base_url"-> s"https://static.javadoc.io/co.fs2/fs2-io_2.12/${fs2Version}",
    ),
    mdocIn := (baseDirectory.value) / "src" / "main" / "paradox",
    Compile / paradox / sourceDirectory := mdocOut.value,
    makeSite := makeSite.dependsOn(mdoc.toTask("")).value,
    mdocExtraArguments := Seq("--no-link-hygiene"), // paradox handles this
    libraryDependencies ++= Seq(
      "org.tpolecat"  %% "natchez-jaeger" % natchezVersion,
    )
)
