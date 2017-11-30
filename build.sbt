import sbt.io.Using

lazy val common = (project in file("."))
  .settings(commonSettings: _*)
  .settings(
    name := "blockchain"
  )

lazy val client = (project in file("client"))
  .settings(commonSettings: _*)
  .settings(
    name := "blockchain-client",
    libraryDependencies ++= Seq(
    )
  )
  .dependsOn(common)

lazy val node = (project in file("node"))
  .settings(commonSettings: _*)
  .settings(
    name := "blockchain-node"
  )
  .dependsOn(common)


lazy val commonSettings = Seq(
  version := "0.1",
  scalaVersion := "2.12.3",
  PB.protocOptions in Compile ++= Seq(
    s"--plugin=protoc-gen-java_rpc=${grpcExePath.value.get}",
    s"--java_rpc_out=${(sourceManaged in Compile).value.getAbsolutePath}"
  ),

  PB.targets in Compile := Seq(
    PB.gens.java -> (sourceManaged in Compile).value
  ),

  libraryDependencies ++= Seq(
    "io.grpc" % "grpc-all" % grpcJavaVersion,
    "org.mortbay.jetty.alpn" % "alpn-boot" % "8.1.9.v20160720" % "runtime"
  ),

  grpcExePath := xsbti.api.SafeLazyProxy {
    val exe: File = (baseDirectory in ThisBuild).value / ".bin" / grpcExeFileName
    if (!exe.exists) {
      println("grpc protoc plugin (for Java) does not exist. Downloading.")
      Using.urlInputStream(grpcExeUrl) { stream => IO.transfer(stream, exe) }
      exe.setExecutable(true)
    } else {
      println("grpc protoc plugin (for Java) exists.")
    }
    exe
  },

  fork := true,
  javaOptions ++= (managedClasspath in Runtime).map { attList =>
    attList
      .map { _.data.getAbsolutePath }
      .collect { case x if x contains "jetty.alpn" => s"-Xbootclasspath/p:$x"}
  }.value
)

def grpcExeFileName = {
  val os = if (scala.util.Properties.isMac){
    "osx-x86_64"
  } else if (scala.util.Properties.isWin){
    "windows-x86_64"
  } else {
    "linux-x86_64"
  }
  s"$grpcArtifactId-$grpcJavaVersion-$os.exe"
}

val grpcArtifactId = "protoc-gen-grpc-java"

val grpcJavaVersion = "1.7.0"

val grpcExeUrl =
  url(s"http://repo1.maven.org/maven2/io/grpc/$grpcArtifactId/$grpcJavaVersion/$grpcExeFileName")

val grpcExePath = SettingKey[xsbti.api.Lazy[File]]("grpcExePath")
