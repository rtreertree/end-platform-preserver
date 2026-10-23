package net.rtree.endplatformpreserver.verification;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.commands.GameRuleCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.rtree.endplatformpreserver.EndPlatformPreserverRules;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.MixinEnvironment;
import sun.misc.Unsafe;

/** Runs the real, Loader-transformed Minecraft method against a controlled world boundary. */
public final class EndPlatformVerification {
    private static final String FEATURE = "net/minecraft/world/level/levelgen/feature/EndPlatformFeature";
    private static final String FEATURE_DESCRIPTOR =
            "(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Z)V";

    private EndPlatformVerification() {}

    public static void run() throws Exception {
        check(FabricLoader.getInstance().isModLoaded("end-platform-preserver"), "Production mod was not loaded");
        SharedConstants.tryDetectVersion();
        check("26.3".equals(SharedConstants.getCurrentVersion().id()), "Expected Minecraft 26.3");
        Bootstrap.bootStrap();
        check(BuiltInRegistries.GAME_RULE.getValue(EndPlatformPreserverRules.ENABLED_ID)
                == EndPlatformPreserverRules.ENABLED, "Game rule was not registered during bootstrap");

        int cases = 0;
        for (BlockPos origin : List.of(new BlockPos(100, 49, 0), new BlockPos(731, 22, -913))) {
            for (boolean dropResources : List.of(false, true)) {
                verifyCase(origin, dropResources);
                verifyToggleCase(origin, dropResources);
                cases++;
            }
        }
        verifySharedPortalCall();
        // Required production and test injectors must resolve, including when audit loads targets.
        MixinEnvironment.getCurrentEnvironment().audit();
        System.out.println("PASS: actual Fabric/Mixin Minecraft 26.3 platform method; " + cases
                + " cases, 20 calls; default preservation, command on/off/query, permissions, NBT round trips,"
                + " per-world isolation, vanilla clearing when disabled, floor/drop behavior,"
                + " and shared player/entity portal call verified.");
    }

    private static void verifyCase(BlockPos origin, boolean dropResources) {
        TrackedLevel tracked = new TrackedLevel(origin);
        ServerLevelAccessor level = tracked.createAccessor();
        Map<BlockPos, BlockState> protectedStates = new HashMap<>(tracked.blocks);
        protectedStates.keySet().removeIf(tracked::isFloor);
        Map<BlockPos, String> protectedPayloads = new HashMap<>(tracked.containerPayloads);

        int firstReplacements = tracked.missingFloorCount();
        EndPlatformFeature.createEndPlatform(level, origin, dropResources);
        tracked.assertPass(firstReplacements, dropResources, protectedStates, protectedPayloads);

        // A repeat arrival should inspect the floor but perform no writes or destruction.
        tracked.clearOperations();
        EndPlatformFeature.createEndPlatform(level, origin, dropResources);
        tracked.assertPass(0, dropResources, protectedStates, protectedPayloads);

        // Platform regeneration must still restore changed and missing floor cells.
        tracked.blocks.put(origin.offset(0, -1, 0), Blocks.DIRT.defaultBlockState());
        tracked.blocks.put(origin.offset(2, -1, -2), Blocks.AIR.defaultBlockState());
        tracked.clearOperations();
        EndPlatformFeature.createEndPlatform(level, origin, dropResources);
        tracked.assertPass(2, dropResources, protectedStates, protectedPayloads);
    }

    private static void verifyToggleCase(BlockPos origin, boolean dropResources) throws Exception {
        TrackedLevel tracked = new TrackedLevel(origin);
        ServerLevelAccessor level = tracked.createAccessor();
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        CommandBuildContext buildContext = (CommandBuildContext) Proxy.newProxyInstance(
                CommandBuildContext.class.getClassLoader(), new Class<?>[]{CommandBuildContext.class},
                (proxy, method, arguments) -> {
                    check(method.getName().equals("enabledFeatures"), "Unexpected command registry access");
                    return FeatureFlags.VANILLA_SET;
                });
        GameRuleCommand.register(dispatcher, buildContext);
        CommandSourceStack source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO,
                tracked.serverLevel, PermissionSet.ALL_PERMISSIONS, Component.literal("Verification"), null);
        String command = "gamerule " + EndPlatformPreserverRules.ENABLED_ID;
        check(dispatcher.execute(command, source) == 1, "Preservation must default to enabled");
        check(dispatcher.getCompletionSuggestions(dispatcher.parse(command + " ", source)).join().getList()
                .stream().map(suggestion -> suggestion.getText()).collect(java.util.stream.Collectors.toSet())
                .equals(java.util.Set.of("true", "false")), "Missing boolean command suggestions");
        expectCommandFailure(dispatcher, command + " false", source.withPermission(PermissionSet.NO_PERMISSIONS));
        expectCommandFailure(dispatcher, command + " invalid", source);
        check(tracked.rules.get(EndPlatformPreserverRules.ENABLED), "Rejected commands changed the rule");

        check(dispatcher.execute(command + " false", source) == 0, "Disable command result incorrect");
        check(dispatcher.execute(command, source) == 0, "Disabled query result incorrect");
        verifySavedRule(tracked.rules, false);
        check(new GameRules(FeatureFlags.VANILLA_SET).get(EndPlatformPreserverRules.ENABLED),
                "Changing one world's rule affected another world");

        Map<BlockPos, BlockState> outsideStates = new HashMap<>(tracked.blocks);
        outsideStates.keySet().removeIf(position -> tracked.isFloor(position) || tracked.isUpper(position));
        int replacements = tracked.missingFloorCount() + 75;
        EndPlatformFeature.createEndPlatform(level, origin, dropResources);
        tracked.assertCounts(replacements, dropResources, 100);
        for (Map.Entry<BlockPos, BlockState> entry : tracked.blocks.entrySet()) {
            if (tracked.isUpper(entry.getKey())) {
                check(entry.getValue().isAir(), "Disabled rule did not restore vanilla upper clearing");
                check(!tracked.containerPayloads.containsKey(entry.getKey()), "Vanilla clearing kept a container");
            }
        }
        outsideStates.forEach((position, state) ->
                check(tracked.blocks.get(position) == state, "Vanilla clearing affected surrounding blocks"));

        check(dispatcher.execute(command + " true", source) == 1, "Enable command result incorrect");
        check(dispatcher.execute(command, source) == 1, "Enabled query result incorrect");
        verifySavedRule(tracked.rules, true);
        BlockPos chest = origin.offset(0, 1, 0);
        tracked.blocks.put(chest, Blocks.CHEST.defaultBlockState());
        tracked.containerPayloads.put(chest, "rebuilt-chest");
        tracked.blocks.put(origin, Blocks.WATER.defaultBlockState());
        Map<BlockPos, BlockState> protectedStates = new HashMap<>(tracked.blocks);
        protectedStates.keySet().removeIf(tracked::isFloor);
        Map<BlockPos, String> protectedPayloads = new HashMap<>(tracked.containerPayloads);
        tracked.clearOperations();
        EndPlatformFeature.createEndPlatform(level, origin, dropResources);
        tracked.assertPass(0, dropResources, protectedStates, protectedPayloads);
    }

    private static void verifySavedRule(GameRules rules, boolean expected) {
        Codec<GameRules> codec = GameRules.codec(FeatureFlags.VANILLA_SET);
        Tag saved = codec.encodeStart(NbtOps.INSTANCE, rules).getOrThrow();
        GameRules restored = codec.parse(NbtOps.INSTANCE, saved).getOrThrow();
        check(restored.get(EndPlatformPreserverRules.ENABLED) == expected, "Saved rule value was lost");
    }

    private static void expectCommandFailure(CommandDispatcher<CommandSourceStack> dispatcher,
            String command, CommandSourceStack source) throws Exception {
        try {
            dispatcher.execute(command, source);
            throw new AssertionError("Command should have been rejected: " + command);
        } catch (CommandSyntaxException expected) {
            // The normal vanilla permission and boolean argument checks must apply.
        }
    }

    /** Only getGameRules is usable; no world, server, storage, or sockets are created. */
    private static final class RuleOnlyServerLevel extends ServerLevel {
        private GameRules rules;

        private RuleOnlyServerLevel() {
            super(null, null, null, null, null, null, false, 0L, List.of(), false);
            throw new AssertionError("Test level constructor must not run");
        }

        private static RuleOnlyServerLevel create(GameRules rules) {
            try {
                var field = Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                Unsafe unsafe = (Unsafe) field.get(null);
                RuleOnlyServerLevel level = (RuleOnlyServerLevel) unsafe.allocateInstance(RuleOnlyServerLevel.class);
                level.rules = rules;
                return level;
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Could not create test level boundary", failure);
            }
        }

        @Override
        public GameRules getGameRules() {
            return rules;
        }
    }

    /** The actual portal bytecode calls the feature before its first player-specific branch. */
    private static void verifySharedPortalCall() throws Exception {
        String resource = "net/minecraft/world/level/block/EndPortalBlock.class";
        try (InputStream stream = EndPlatformFeature.class.getClassLoader().getResourceAsStream(resource)) {
            check(stream != null, "Missing actual EndPortalBlock bytecode");
            int[] methodCount = {0};
            int[] calls = {0};
            int[] playerBranches = {0};
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (!name.equals("getPortalDestination") || !descriptor.equals(
                            "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;"
                                    + "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/portal/TeleportTransition;")) {
                        return null;
                    }
                    methodCount[0]++;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String invokedName,
                                String invokedDescriptor, boolean isInterface) {
                            if (owner.equals(FEATURE) && invokedName.equals("createEndPlatform")) {
                                check(opcode == Opcodes.INVOKESTATIC, "Platform call must be static");
                                check(invokedDescriptor.equals(FEATURE_DESCRIPTOR), "Platform signature changed");
                                check(playerBranches[0] == 0, "Platform generation is player-specific");
                                calls[0]++;
                            }
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (opcode == Opcodes.INSTANCEOF && type.equals("net/minecraft/server/level/ServerPlayer")) {
                                check(calls[0] == 1, "Player distinction occurs before the shared platform call");
                                playerBranches[0]++;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            check(methodCount[0] == 1 && calls[0] == 1 && playerBranches[0] > 0,
                    "Expected one shared portal-to-platform call and later player-specific behavior");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class TrackedLevel implements InvocationHandler {
        private final BlockPos origin;
        private final GameRules rules = new GameRules(FeatureFlags.VANILLA_SET);
        private final ServerLevel serverLevel = RuleOnlyServerLevel.create(rules);
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        // Opaque payloads detect container destruction; this does not emulate real NBT persistence.
        private final Map<BlockPos, String> containerPayloads = new HashMap<>();
        private final List<BlockPos> reads = new ArrayList<>();
        private final List<BlockPos> writes = new ArrayList<>();
        private final List<BlockPos> destructions = new ArrayList<>();

        private TrackedLevel(BlockPos origin) {
            this.origin = origin;
            BlockState[] upperStates = {Blocks.STONE.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                    Blocks.CHEST.defaultBlockState(), Blocks.REDSTONE_BLOCK.defaultBlockState(),
                    Blocks.WATER.defaultBlockState()};
            int index = 0;
            for (int z = -3; z <= 3; z++) {
                for (int x = -3; x <= 3; x++) {
                    for (int y = -2; y <= 3; y++) {
                        BlockPos position = origin.offset(x, y, z);
                        BlockState state = upperStates[index++ % upperStates.length];
                        if (isFloor(position)) {
                            state = switch (index % 4) {
                                case 0 -> Blocks.OBSIDIAN.defaultBlockState();
                                case 1 -> Blocks.AIR.defaultBlockState();
                                default -> Blocks.DIRT.defaultBlockState();
                            };
                        }
                        blocks.put(position, state);
                        if (!isFloor(position) && state.is(Blocks.CHEST)) {
                            containerPayloads.put(position, "unique-container-payload:" + position);
                        }
                    }
                }
            }
        }

        private ServerLevelAccessor createAccessor() {
            return (ServerLevelAccessor) Proxy.newProxyInstance(ServerLevelAccessor.class.getClassLoader(),
                    new Class<?>[]{ServerLevelAccessor.class}, this);
        }

        private boolean isFloor(BlockPos position) {
            return position.getY() == origin.getY() - 1
                    && Math.abs(position.getX() - origin.getX()) <= 2
                    && Math.abs(position.getZ() - origin.getZ()) <= 2;
        }

        private boolean isUpper(BlockPos position) {
            return position.getY() >= origin.getY() && position.getY() <= origin.getY() + 2
                    && Math.abs(position.getX() - origin.getX()) <= 2
                    && Math.abs(position.getZ() - origin.getZ()) <= 2;
        }

        private int missingFloorCount() {
            return (int) blocks.entrySet().stream()
                    .filter(entry -> isFloor(entry.getKey()) && !entry.getValue().is(Blocks.OBSIDIAN)).count();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "TrackedLevel(" + origin + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError("Unexpected Object method " + method);
                };
            }
            if (method.getName().equals("getLevel")) {
                return serverLevel;
            }
            check(arguments != null && arguments.length > 0 && arguments[0] instanceof BlockPos,
                    "Unexpected accessor interaction: " + method);
            // Minecraft reuses MutableBlockPos; retain a stable coordinate for every observation.
            BlockPos position = ((BlockPos) arguments[0]).immutable();
            check(isFloor(position) || (!rules.get(EndPlatformPreserverRules.ENABLED) && isUpper(position)),
                    "Feature touched protected space at " + position + " via " + method.getName());
            return switch (method.getName()) {
                case "getBlockState" -> {
                    reads.add(position);
                    yield blocks.getOrDefault(position, Blocks.AIR.defaultBlockState());
                }
                case "destroyBlock" -> {
                    check(arguments.length == 3 && Boolean.TRUE.equals(arguments[1]) && arguments[2] == null,
                            "Unexpected vanilla floor destruction arguments");
                    destructions.add(position);
                    BlockState previous = blocks.put(position, Blocks.AIR.defaultBlockState());
                    containerPayloads.remove(position);
                    yield previous != null && !previous.isAir();
                }
                case "setBlockAndUpdate" -> {
                    check(arguments.length == 2 && arguments[1] instanceof BlockState,
                            "Unexpected vanilla floor write arguments");
                    BlockState state = (BlockState) arguments[1];
                    check(isFloor(position) ? state.is(Blocks.OBSIDIAN) : state.isAir(),
                            "Unexpected platform block write");
                    writes.add(position);
                    blocks.put(position, state);
                    containerPayloads.remove(position);
                    yield true;
                }
                default -> throw new AssertionError("Unexpected accessor interaction: " + method);
            };
        }

        private void assertPass(int replacements, boolean dropResources,
                Map<BlockPos, BlockState> protectedStates, Map<BlockPos, String> protectedPayloads) {
            assertCounts(replacements, dropResources, 25);
            for (Map.Entry<BlockPos, BlockState> entry : protectedStates.entrySet()) {
                check(blocks.get(entry.getKey()) == entry.getValue(), "Protected state changed at " + entry.getKey());
            }
            check(containerPayloads.equals(protectedPayloads), "Protected container payloads changed");
        }

        private void assertCounts(int replacements, boolean dropResources, int expectedReads) {
            check(reads.size() == expectedReads && new HashSet<>(reads).size() == expectedReads,
                    "Expected exactly " + expectedReads + " distinct platform reads");
            check(writes.size() == replacements && new HashSet<>(writes).size() == replacements,
                    "Unexpected floor write count: " + writes.size() + ", expected " + replacements);
            check(destructions.size() == (dropResources ? replacements : 0), "dropResources behavior changed");
            if (dropResources) {
                check(new HashSet<>(destructions).equals(new HashSet<>(writes)), "Destruction/write positions differ");
            }
            check(missingFloorCount() == 0, "Platform floor was not completely restored");
        }

        private void clearOperations() {
            reads.clear();
            writes.clear();
            destructions.clear();
        }
    }
}
