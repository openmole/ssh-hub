
def commonSettings = Seq(
  scalaVersion := "3.8.3",
  version := "0.1.0-SNAPSHOT",
  organization := "org.openmole.miniclust",
  resolvers += "jitpack" at "https://jitpack.io"
)

lazy val pilot = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    commonSettings,
    name := "ssh-hub",
    libraryDependencies ++= Seq(
      //"xyz.matthieucourt" %% "layoutz" % "0.7.0",
      "com.github.romainreuillon" % "layoutz" % "424ad06f6d",
      "io.circe" %% "circe-generic" % "0.14.15",
      "io.circe" %% "circe-yaml-scalayaml" % "0.16.1",
      "com.github.pathikrit" %% "better-files" % "3.9.2",
      "dev.optics" %% "monocle-core"  % "3.1.0",
      "dev.optics" %% "monocle-macro" % "3.1.0"
    )
  )
