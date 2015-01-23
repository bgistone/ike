import Dependencies._
import com.typesafe.sbt.SbtNativePackager.NativePackagerHelper._

val dictionaryBuilder = project.in(file(".")).enablePlugins(DeployPlugin).enablePlugins(WebServicePlugin)

name := "dictionary-builder"

description := "buildin' them electric dictionaries"

libraryDependencies ++= Seq(
    allenAiCommon exclude("com.typesafe", "config"),
    allenAiTestkit,
    akkaActor,
    sprayCan,
    sprayRouting,
    lucene("core"),
    lucene("analyzers-common"),
    lucene("highlighter"),
    lucene("queries"),
    lucene("queryparser")
)

fork in run := true

javaOptions in run ++= Seq("-Xms2G", "-Xmx8G")

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

mappings in Universal ++= directory(baseDirectory.value / "public")
