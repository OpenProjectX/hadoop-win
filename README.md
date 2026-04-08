# hadoop-win

`hadoop-win` builds a shaded replacement for `org.apache.hadoop:hadoop-common` that is intended to work better on Windows by removing Hadoop's normal Windows JNI dependency paths where practical.

The current implementation focuses on replacing a small set of Hadoop classes with Java-based fallbacks:

- `org.apache.hadoop.util.NativeCodeLoader`
- `org.apache.hadoop.io.nativeio.NativeIO`
- `org.apache.hadoop.fs.FileUtil`

The output artifact is published as:

- group: `org.openprojectx.hadoop.win`
- module: `common`

## Use Case

The target usage is:

```gradle
implementation("org.apache.hive:hive-standalone-metastore:3.1.3000.7.1.9.14-2") {
    exclude(group = "org.apache.hadoop", module = "hadoop-common")
}
implementation("org.openprojectx.hadoop.win:common:0.1.0-3.1.1.7.1.9.14-2")
```

The `common` jar shades `hadoop-common` and includes override classes with the original Hadoop package names so they replace the upstream implementations in the final jar.

## Build

This repository uses the local Gradle wrapper and keeps Gradle state under `/data/.gradle`.

Build the shaded jar:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew :common:shadowJar
```

The built artifact is written to:

```text
common/build/libs/common-<version>.jar
```

## Versioning

Project versions use this format:

```text
<major>.<minor>.<patch>-<clouderaHadoopVersion>
```

Example:

```text
0.1.0-3.1.1.7.1.9.14-2
```

Important properties in [gradle.properties](/data/Git/hadoop-win/gradle.properties):

- `version`: the published project version
- `clouderaHadoopVersion`: the Cloudera Hadoop dependency version used by the build
- `releaseBranch`: the Git branch required by the release plugin

Current defaults:

```properties
version=0.1.0-3.1.1.7.1.9.14-2
clouderaHadoopVersion=3.1.1.7.1.9.14-2
releaseBranch=cloudera
```

The release config preserves the Cloudera suffix already present in `version` and increments only the leading project patch number.

Examples:

- `0.1.0-3.1.1.7.1.9.14-2` -> `0.1.1-3.1.1.7.1.9.14-2`
- `0.1.9-3.1.1.7.1.9.14-2` -> `0.1.10-3.1.1.7.1.9.14-2`

## Release

The repository uses:

- `net.researchgate.release`
- `io.github.gradle-nexus.publish-plugin`

The release plugin currently expects the `cloudera` branch unless overridden.

Typical release flow:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew release
```

If you need a different branch temporarily:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew release -PreleaseBranch=your-branch
```

## Notes

- The replacement classes are currently implemented in Java for binary compatibility with the original Hadoop APIs.
- Some native-only Hadoop behaviors do not have a true Java equivalent; those paths are implemented as best-effort fallbacks or explicit non-native behavior.
- The Gradle configuration cache still reports a known warning from the `net.researchgate.release` plugin.
