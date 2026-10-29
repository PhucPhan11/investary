# Toolchain traps: Maven mirror, JDK 17, blocked npx

## Summary

On the development machine, three environment defaults broke the first build attempts in Phase 1:

- `mvn` failed with "Non-resolvable parent POM … nexus.mobicorp.ch: No such host is known". The
  Maven installation's global `settings.xml` mirrors `*` to an unreachable corporate Nexus.
- `JAVA_HOME` points to JDK 17, while `java` on `PATH` is 21. Maven, and anything launched with
  `JAVA_HOME`, ran on 17.
- `npx @angular/cli …` failed with NVM event `NVM4306`: "delegated script identity changed since it
  was trusted". Plain `npm` still works.

## Root Cause

These are machine-level settings outside the repository. Agents that use the conventional commands
(`mvn`, `npx`) or trust `JAVA_HOME` hit them immediately.

## Prevention

- The repository ships the Maven wrapper (`backend/mvnw`, Maven 3.9.11 from Maven Central). Always use
  `./mvnw`; it does not read the installation's global settings.
- `maven-enforcer-plugin` in `backend/pom.xml` requires Java 21+ and fails fast with a clear message.
- `AGENTS.md` gives the JDK 21 path, and the IntelliJ run configurations use a JDK named `temurin-21`.
- Never bypass the NVM check by calling `npx-cli.js` directly; it is a security control. Use
  `npm run …` or `node node_modules/.bin/…`. For one-off CLIs, install them into a scratch folder with
  `npm install` and run them with `node`.

## Detection

- Enforcer error: "Investary requires Java 21+. Point JAVA_HOME at a JDK 21 installation."
- Any build log mentioning `nexus.mobicorp.ch` means plain `mvn` was used instead of `./mvnw`.
- `NVM4306` in output means `npx` was used.

## Evidence

Phase 1 session, 2026-10-29: the first `mvn dependency:resolve` and `npx @angular/cli new` both
failed with the messages above. They were resolved with the wrapper, an explicit `JAVA_HOME` and a
locally installed CLI.
