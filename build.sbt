import precogbuild.PlatformBuild._

def scalazVersion   = "7.2.4"
def specsVersion    = "3.7"
def pathyVersion    = "0.2.1"
def argonautVersion = "6.2-M3"

lazy val root = project.setup.root.noArtifacts aggregate (precog, blueeyes, yggdrasil) dependsOn (yggdrasil) also (
  initialCommands in console := "import blueeyes._, json._, quasar.precog._"
)
lazy val all = project.setup.root.noArtifacts aggregate (precog, blueeyes, yggdrasil, mimir)

/** mimir used to be the evaluator project.
 */
lazy val mimir     = project.setup.noArtifacts dependsOn (yggdrasil % BothScopes, blueeyes, precog)
lazy val yggdrasil = project.setup dependsOn blueeyes % BothScopes
lazy val blueeyes  = project.setup dependsOn precog % BothScopes

lazy val precog = (
  project.setup deps (

    "org.openjdk.jmh"    % "jmh-generator-annprocess" %     "1.12",
    "com.slamdata"      %% "pathy-core"               %  pathyVersion,
    "com.slamdata"      %% "pathy-argonaut"           %  pathyVersion,
    "io.argonaut"       %% "argonaut"                 % argonautVersion,
    "io.argonaut"       %% "argonaut-scalaz"          % argonautVersion,
    "com.chuusai"       %% "shapeless"                %     "2.3.1",
    "org.slf4s"         %% "slf4s-api"                %    "1.7.13",
    "org.spire-math"    %% "spire"                    %     "0.7.4",
    "org.scodec"        %% "scodec-bits"              %     "1.1.0",
    "org.scodec"        %% "scodec-scalaz"            %    "1.3.0a",
    // "io.github.jto"     %% "validation-playjson"      %      "2.0",
    // "com.typesafe.play" %% "play-json"                %     "2.5.3",
    "org.scalaz"        %% "scalaz-effect"            %  scalazVersion,
    "org.scalaz"        %% "scalaz-concurrent"        %  scalazVersion,
    "com.slamdata"      %% "pathy-scalacheck"         %   pathyVersion   % Test,
    "org.scalacheck"    %% "scalacheck"               %     "1.12.5"     % Test,
    "org.scalaz.stream" %% "scalaz-stream"            %     "0.8.3a"     % Test,
    "org.specs2"        %% "specs2-scalacheck"        %   specsVersion   % Test,
    "org.specs2"        %% "specs2-core"              %   specsVersion   % Test
  )
)

lazy val benchmark = project.setup dependsOn (blueeyes % BothScopes) enablePlugins JmhPlugin also (
                fork in Test :=  true,
      sourceDirectory in Jmh <<= sourceDirectory in Test,
       classDirectory in Jmh <<= classDirectory in Test,
  dependencyClasspath in Jmh <<= dependencyClasspath in Test,
              compile in Jmh <<= (compile in Jmh) dependsOn (compile in Test),
                  run in Jmh <<= (run in Jmh) dependsOn (Keys.compile in Jmh)
)

addCommandAlias("bench", "benchmark/jmh:run -f1 -t1")
addCommandAlias("cc", "all/test:compile")
addCommandAlias("tt", "; cc ; {.}/test")
addCommandAlias("ttq", "; cc ; all/testQuick")
