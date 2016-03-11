import sbt._
import sbt.Keys._
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import com.typesafe.sbt.web.SbtWeb
import com.typesafe.sbt.web.Import.WebKeys
import com.typesafe.sbteclipse.core.EclipsePlugin._
import spray.revolver.RevolverPlugin._

object Build extends sbt.Build {

  val genElectronMain = TaskKey[Unit]("gen-electron-main", "Generates Electron application's main file.")

  lazy val commonSettings = Seq(
    scalaVersion := "2.11.7",
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding", "UTF-8",
      "-feature",
      "-language:_",
      "-unchecked",
      "-Xlint",
      "-Xfuture",
      //"-Xfatal-warnings",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused-import",
      "-Ywarn-unused"
    ),

    EclipseKeys.withSource := true,

    incOptions := incOptions.value.withNameHashing(true),
    updateOptions := updateOptions.value.withCachedResolution(true),

    cancelable := true
  )

  lazy val protocol = crossProject crossType CrossType.Pure in file("protocol") settings (
    name := "protocol",
    // We need to explicitly set this to the default Eclipse output folder, otherwise another one is created
    EclipseKeys.eclipseOutput := Some("bin/"),

    libraryDependencies ++= deps.protocol.value
  ) settings (commonSettings: _*)

  lazy val protocolJvm = protocol.jvm

  lazy val protocolJs = protocol.js

  lazy val electron = project in file("electron") enablePlugins(ScalaJSPlugin) settings commonSettings ++ Seq(
    name := "electron",

    persistLauncher in Compile := true,
    persistLauncher in Test := false,

    /*
     * We need to generate the Electron's main files "package.json" and "main.js".
     */
    genElectronMain := {
      import java.nio.charset.Charset
      // TODO we rely on the files written on disk but it would be better to be able to get the actual content directly from the tasks
      val launchCode = IO.read((packageScalaJSLauncher in Compile).value.data, Charset.forName("UTF-8"))
      val jsCode = IO.read((fullOptJS in Compile).value.data, Charset.forName("UTF-8"))

      val pkgJson = """
      {
        "name": "electron-starter",
        "version": "0.1",
        "main": "main.js",
        "repository": {
          "type": "git",
          "url": "https://github.com/sschaef/tooling-research"
        },
        "license": "MIT"
      }
      """.stripMargin

      // hack to get require and __dirname to work in the main process
      // see https://gitter.im/scala-js/scala-js/archives/2015/04/25
      val mainJs = s"""
        var addGlobalProps = function(obj) {
          obj.require = require;
          obj.__dirname = __dirname;
        }

        if((typeof __ScalaJSEnv === "object") && typeof __ScalaJSEnv.global === "object") {
          addGlobalProps(__ScalaJSEnv.global);
        } else if(typeof  global === "object") {
          addGlobalProps(global);
        } else if(typeof __ScalaJSEnv === "object") {
          __ScalaJSEnv.global = {};
          addGlobalProps(__ScalaJSEnv.global);
        } else {
          var __ScalaJSEnv = { global: {} };
          addGlobalProps(__ScalaJSEnv.global)
        }
        $jsCode
        $launchCode
      """

      val dest = (classDirectory in Compile).value / ".."
      IO.write(dest / "package.json", pkgJson)
      IO.write(dest / "main.js", mainJs)
    }
  )

  lazy val ui = project in file("ui") enablePlugins(ScalaJSPlugin, SbtWeb) settings commonSettings ++ Seq(
    name := "ui",
    scalaJSStage in Global := FastOptStage,

    resolvers += sbt.Resolver.bintrayRepo("denigma", "denigma-releases"),

    libraryDependencies ++= deps.sjs.value,

    skip in packageJSDependencies := false,
    jsDependencies ++= deps.webjars.value,

    persistLauncher in Compile := true,
    persistLauncher in Test := false
  ) dependsOn (protocolJs)

  lazy val nvim = project in file("nvim") settings commonSettings ++ Seq(
    name := "nvim",

    libraryDependencies ++= deps.nvim.value
  )

  lazy val backend = project in file("backend") settings commonSettings ++ Revolver.settings ++ Seq(
    name := "backend",

    resolvers += Resolver.sonatypeRepo("snapshots"),
    libraryDependencies ++= deps.backend.value,
    javaOptions ++= Seq(
      "-agentlib:jdwp=transport=dt_socket,server=y,address=8000,suspend=n"
    ),

    // add *fastopt.js file to resources
    resourceGenerators in Compile <+= (fastOptJS in Compile in ui).map(r => Seq(r.data)),
    // add *fullopt.js file to resources
//    (resourceGenerators in Compile) <+= (fullOptJS in Compile in ui).map(r => Seq(r.data)),
    // add *launcher.js file to resources
    resourceGenerators in Compile <+= (packageScalaJSLauncher in Compile in ui).map(r => Seq(r.data)),
    // add *jsdeps.js file to resources
    resourceGenerators in Compile <+= (packageJSDependencies in Compile in ui).map(Seq(_)),
    // depend on the genElectronMain task but don't add its generated resources since we don't need to route them at runtime
    resourceGenerators in Compile <+= (genElectronMain in Compile in electron).map(_ => Seq()),
    // add folder of webjars to resources
    unmanagedResourceDirectories in Compile += (WebKeys.webTarget in Compile in ui).value / "web-modules" / "main" / "webjars" / "lib",

    // once the server is started, we also want to restart it on changes in the protocol project
    watchSources ++= (watchSources in protocolJvm).value
  ) dependsOn (protocolJvm, nvim)

  lazy val scalacPlugin = project in file("scalac-plugin") settings commonSettings ++ Seq(
    name := "scalac-plugin",

    libraryDependencies ++= deps.scalacPlugin.value,

    scalacOptions in console in Compile += s"-Xplugin:${(packageBin in Compile).value}",
    scalacOptions in Test += s"-Xplugin:${(packageBin in Compile).value}",

    // show stack traces up to first sbt stak frame
    traceLevel in Test := 0
  )

  object versions {
    // https://github.com/lihaoyi/scalatags
    val scalatags       = "0.5.2"
    // https://github.com/ChrisNeveu/macrame
    val macrame         = "1.0.1"
    val paradise        = "2.1.0-M5"
    val akkaStream      = "1.0"
    val akkaHttp        = "1.0"
    val scalameta       = "0.1.0-SNAPSHOT"
    // https://github.com/typesafehub/scala-logging
    val scalaLogging    = "3.1.0"
    val slf4jLog4j12    = "1.7.12"
    val junit           = "4.12"
    // https://github.com/ochrons/boopickle
    val boopickle       = "1.1.0"
    // https://github.com/msgpack4z/msgpack4z-core
    val msgpack4zCore   = "0.2.0"
    // https://github.com/msgpack4z/msgpack4z-java07
    val msgpack4zJava07 = "0.2.0"
    // https://github.com/antonkulaga/codemirror-facade
    val codemirror      = "5.5-0.5"
    val jquery          = "0.8.0"
  }

  object deps {
    lazy val protocol = Def.setting(Seq(
      "me.chrons"                      %%% "boopickle"                  % versions.boopickle
    ))

    lazy val backend = Def.setting(Seq(
      compilerPlugin("org.scalamacros" %   "paradise"                   % versions.paradise         cross CrossVersion.full),
      "com.typesafe.akka"              %%  "akka-http-experimental"     % versions.akkaHttp,
      "com.typesafe.akka"              %%  "akka-stream-experimental"   % versions.akkaStream,
      "org.scala-lang"                 %   "scala-compiler"             % scalaVersion.value,
      "org.scalameta"                  %   "scalameta"                  % versions.scalameta        cross CrossVersion.binary,
      "org.scalameta"                  %   "scalahost"                  % versions.scalameta        cross CrossVersion.full,
      "org.scalameta"                  %%  "interpreter"                % versions.scalameta,
      "com.chrisneveu"                 %%  "macrame"                    % versions.macrame,
      "com.lihaoyi"                    %%% "scalatags"                  % versions.scalatags,
      "org.slf4j"                      %   "slf4j-log4j12"              % versions.slf4jLog4j12,
      "junit"                          %   "junit"                      % versions.junit            % "test"
    ))

    lazy val nvim = Def.setting(Seq(
      compilerPlugin("org.scalamacros" %   "paradise"                   % versions.paradise         cross CrossVersion.full),
      "com.github.xuwei-k"             %%  "msgpack4z-core"             % versions.msgpack4zCore,
      "com.github.xuwei-k"             %   "msgpack4z-java07"           % versions.msgpack4zJava07,
      "com.chrisneveu"                 %%  "macrame"                    % versions.macrame,
      "org.scala-lang"                 %   "scala-compiler"             % scalaVersion.value,
      "com.typesafe.scala-logging"     %%  "scala-logging"              % versions.scalaLogging
    ))

    lazy val sjs = Def.setting(Seq(
      "be.doeraene"                    %%% "scalajs-jquery"             % versions.jquery,
      "org.denigma"                    %%% "codemirror-facade"          % versions.codemirror,
      "com.lihaoyi"                    %%% "scalatags"                  % versions.scalatags
    ))

    lazy val webjars = Def.setting(Seq(
      "org.webjars"                    %   "codemirror"                 % "5.5"                     / "codemirror.js",
      // https://github.com/chjj/marked
      "org.webjars.bower"              %   "marked"                     % "0.3.3"                   / "marked.js",
      "org.webjars"                    %   "d3js"                       % "3.5.5-1"                 / "d3.js",
      // https://github.com/fgnass/spin.js
      "org.webjars.bower"              %   "spin.js"                    % "2.3.1"                   / "spin.js"
    ))

    lazy val scalacPlugin = Def.setting(Seq(
      "org.scala-lang"                 %   "scala-compiler"             % scalaVersion.value,
      "org.apache.jena"                %   "apache-jena-libs"           % "3.0.1",
      "junit"                          %   "junit"                      % versions.junit            % "test"
    ))
  }
}
