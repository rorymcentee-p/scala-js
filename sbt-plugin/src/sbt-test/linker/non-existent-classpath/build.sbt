version := scalaJSVersion
scalaVersion := "2.12.15"

enablePlugins(ScalaJSPlugin)

// Test that non-existent classpath entries are allowed - #2198
fullClasspath in Compile += baseDirectory.value /
  "non-existent-directory-please-dont-ever-create-this"

scalaJSUseMainModuleInitializer := true
