# Runtime verification

This test-only mod uses Fabric Loader 0.19.5 and its real Mixin transformer to execute Minecraft 26.3's `EndPlatformFeature.createEndPlatform`. It intercepts the client or dedicated-server main method before Minecraft starts a window, opens a world, checks the EULA, or binds a port. It never belongs in the distributed mod JAR.

The test uses actual Minecraft classes and registries. A Java dynamic proxy implements the `ServerLevelAccessor` boundary so every world read, write, and destruction can be inspected without creating a game world. A test-only `ServerLevel` subclass supplies real `GameRules`; `Unsafe.allocateInstance` bypasses its constructor so no world infrastructure is opened. None of these test helpers are distributed.

Run from PowerShell 7, with the installed Minecraft 26.3 client/libraries and the ASM/Mixin JARs already present in `.tools/deps`:

```powershell
.\verification\Verify.ps1 -ProductionJar '.\build\libs\end-platform-preserver-1.1.0+mc26.3.jar'
```

`-JavaDirectory` and `-MinecraftDirectory` override the local installation paths. Compilation targets Java 25. The normal production JAR can be replaced by another built copy through `-ProductionJar`.

To verify the dedicated-server environment, put the official 26.3 server bundler at `.tools/minecraft-26.3-server.jar` (or override `-ServerBundlerJar`) and run:

```powershell
.\verification\Verify.ps1 -ProductionJar '.\build\libs\end-platform-preserver-1.1.0+mc26.3.jar' -Environment server
```

This extracts the server game JAR and its bundled libraries into `verification/build/official-server`. The resulting KnotServer classpath contains no client game JAR or client libraries.

Assertions cover:

- Real Loader discovery and application of the production mod's required mixin.
- Exactly 25 distinct floor reads, with no access to upper layers or surrounding blocks.
- Upper stone, glass, chest, redstone, and water states preserved at the usual origin and another arbitrary origin.
- Floor generation and repairs, including missing and different blocks.
- Both `dropResources` values, repeated arrivals, and exact floor write/destruction counts.
- Opaque container payload sentinels untouched. This is a world-access test; it does not exercise real chest NBT serialization.
- Actual `EndPortalBlock` bytecode calls the shared platform method before distinguishing a player from another entity.
- Mixin audit resolves all required targets and injections.
- Game rule registration during vanilla registry bootstrap and an enabled default.
- Real vanilla `/gamerule` command execution: off, on, and current-value queries, plus boolean suggestions, rejected invalid input, and rejected unprivileged sources.
- Disabled preservation restores all 75 upper clearing cells while retaining floor generation, both drop modes, and surrounding blocks.
- Re-enabling immediately protects a rebuilt chest and water during the next invocation.
- Both settings survive NBT serialization/deserialization through Minecraft's real game rule codec; new world settings remain independent.

There are four origin/drop-mode combinations and twenty actual feature invocations. These are controlled boundary tests, not an interactive player traversal or a dedicated-server world test. NBT codec round trips cover persistence format; they do not simulate a full server restart.

An optional negative control copies the production JAR and omits only its platform mixin, keeping game rule registration. The same assertions should then reject vanilla's first read in the protected upper volume:

```powershell
.\verification\Verify.ps1 -ProductionJar '.\build\libs\end-platform-preserver-1.1.0+mc26.3.jar' -NegativeControl
```

`-NegativeControl` can also be combined with `-Environment server`. The script requires both a nonzero Java exit and the expected `AssertionError: Feature touched protected space` message, so an unrelated classpath failure cannot pass the negative control.

Each run saves full stdout/stderr to `verification-console.log` and Fabric logging to `logs/latest.log`, under `verification/run`, `verification/run-server`, `verification/run-control`, or `verification/run-server-control` as appropriate.

## Recorded result

On 2026-10-23, the Gradle-built 1.1.0 production JAR passed the complete twenty-call suite under both KnotClient and KnotServer, using Fabric Loader 0.19.5, Sponge Mixin 0.8.7, ASM 9.10.1, and JDK 26.0.1 (sources compiled with `--release 25`). The dedicated-server negative control failed at `getBlockState` on `(98,49,-2)`, the first protected upper-layer cell, and the script recognized that exact expected failure.
