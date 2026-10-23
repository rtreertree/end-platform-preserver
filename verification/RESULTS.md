# Verification results

Verified on 2026-10-23 against Minecraft Java Edition 26.3.

- Gradle 9.6.0 / Fabric Loom 1.17.3: `build` succeeded.
- JDK 26.0.1 compiler targeting Java 25 (class version 69).
- Fabric Loader 0.19.5 with its official ASM/Mixin dependencies.
- Final production JAR: `build/libs/end-platform-preserver-1.1.0+mc26.3.jar`.
- SHA256: `31c365b1971712fde2d44e5cfef45e5adc751ff79e2e2d243db63511ff6ade08`.

The final JAR passed the real Fabric client and dedicated-server transformation
environments. Each environment ran four origin/drop-mode combinations and twenty calls to Minecraft's
actual `EndPlatformFeature.createEndPlatform` method with a tracked world-access
boundary. Checks covered upper blocks and fluids, repeated generation, floor
creation and repair, both drop modes, and container payload sentinels. The real
vanilla gamerule command was exercised for on/off/query, boolean suggestions,
permission rejection, and invalid input. Disabled mode cleared all 75 upper cells;
re-enabling protected a rebuilt chest and water on the next call. Both settings
passed NBT codec round trips, and new world defaults remained independent. Mixin
audit passed. The actual portal bytecode was checked to confirm the shared call
precedes player-specific handling.

A negative control without the platform mixin (retaining game rule registration) failed as expected at the
first upper-layer access: `(98,49,-2)`. The verification script checked the
specific assertion text and the nonzero Java exit status.

These tests exercise the real transformed game method with controlled world
access. They do not perform interactive portal traversal, a live server-world
test, full save/restart, or real chest NBT serialization. No user world was opened or modified.

The production JAR was inspected and contains only its manifest, production
rule class, two mixin classes, configuration, English game rule text, expanded
Fabric metadata, and license. The test
mod and test source are separate.

See `README.md` in this directory for reproduction commands and test scope.
