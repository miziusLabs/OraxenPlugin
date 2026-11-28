## Oraxen Plugin
Oraxen is a Paper/Folia plugin for versions 1.20.1 through 26.3 that allows administrators to add custom content to their servers through YAML configuration and simple file setup.

### Rules
- All changes must be compatible with Folia.
- Prefer the modern Paper API over older Bukkit APIs.
- Variables should be lowercase or camelCase.
- When logging an exception, log the descriptive message on its own line with the normal prefix. Log the exception on the next line without a prefix.
- NMS handler code lives once in `nms/shared` and each NMS module copies it into its own package at build time. Put calls that differ between the 1.21.11 and 26.x dev bundles in the `NmsAccessors` class of both `nms/java21` and `nms/java25`.

### Building and testing
- Run focused tests with `./gradlew :test --tests '<package>.*' --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g`.
- Build the plugin jar with `./gradlew shadowJar --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g`.
- The first build decompiles both NMS dev bundles and needs about 4.5 GB for Vineflower. On machines with 8 GB of memory, add swap and run `./gradlew :nms:java21:paperweightUserdevSetup` and `./gradlew :nms:java25:paperweightUserdevSetup` one at a time with the same flags before building.
- Start local test servers with `./scripts/server.sh --version <Paper|Folia>/<version>`. Use `--no-reset` to keep files copied into `plugins/Oraxen/` between runs.
