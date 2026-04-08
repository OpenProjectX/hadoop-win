# AGENTS.md

## Build / Gradle

- In this repository, prefer running `./gradlew` from the project root.
- Use `GRADLE_USER_HOME=/data/.gradle ./gradlew ...` by default in this repository.
- Do not switch Gradle home to `/tmp` unless there is a concrete reason.
- For normal project-local Gradle work in this repository, use the local wrapper directly.

