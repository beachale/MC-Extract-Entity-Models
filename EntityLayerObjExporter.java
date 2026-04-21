import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class EntityLayerObjExporter {
    private static final String MATERIAL_NAME = "material0";

    private EntityLayerObjExporter() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        Files.createDirectories(config.outputDir);

        ReflectionContext ctx = new ReflectionContext();

        ctx.initializeGameData();
        Map<Object, Object> roots = ctx.createRoots();
        Object entityModelSet = ctx.createEntityModelSet(roots);
        RuntimeOrientationResolver orientationResolver = RuntimeOrientationResolver.create(ctx, config.clientJarPath);

        List<Object> locations = new ArrayList<Object>(roots.keySet());
        locations.sort(Comparator.comparing(Object::toString));

        int exported = 0;
        int failed = 0;
        int index = 0;
        int extractedTextures = 0;

        TextureResolver textureResolver = null;
        try {
            if (config.clientJarPath != null) {
                textureResolver = new TextureResolver(ctx, config.clientJarPath, config.outputDir);
                extractedTextures = textureResolver.extractAllTrackedTextures();
            }

            for (Object location : locations) {
                index++;
                try {
                    LocationInfo info = describeLocation(ctx, location);
                    String stem = fileStemForLocation(info);

                    Path objPath = config.outputDir.resolve(stem + ".obj");
                    Path mtlPath = config.outputDir.resolve(stem + ".mtl");

                    ResolvedTexture texture = null;
                    if (textureResolver != null) {
                        texture = textureResolver.resolveAndExtract(info);
                    }

                    String textureMapPath = texture != null ? texture.mapKdPath : null;
                    String textureSource = texture != null ? texture.sourceEntry : null;
                    boolean applyRuntimeOrientation = config.applyRuntimeOrientation && orientationResolver.shouldApply(location);

                    try (ObjWriter writer = new ObjWriter(objPath, mtlPath, location.toString(), textureMapPath, textureSource)) {
                        Object rootPart = ctx.bakeLayer(entityModelSet, location);
                        exportModel(ctx, rootPart, writer, config, applyRuntimeOrientation);
                    }
                    if (config.liftToGrid) {
                        liftModelToGrid(objPath);
                    }
                    exported++;
                    System.out.printf(Locale.ROOT, "[%4d/%4d] exported %s%n", index, locations.size(), objPath.getFileName());
                } catch (Throwable t) {
                    failed++;
                    Throwable cause = rootCause(t);
                    System.err.printf(Locale.ROOT, "[%4d/%4d] failed %s (%s)%n", index, locations.size(), location, cause.toString());
                }
            }
        }
        finally {
            if (textureResolver != null) {
                textureResolver.close();
            }
        }

        System.out.printf(
            Locale.ROOT,
            "Done. Exported: %d, Failed: %d, Extracted textures: %d, Output: %s%n",
            exported,
            failed,
            extractedTextures,
            config.outputDir.toAbsolutePath().toString()
        );

        if (failed > 0) {
            System.exit(2);
        }
    }

    private static void exportModel(
            final ReflectionContext ctx,
            Object rootPart,
            final ObjWriter writer,
            final Config config,
            final boolean applyRuntimeOrientation)
            throws Exception {
        final Object poseStack = ctx.poseStackCtor.newInstance();
        final Map<String, Integer> cubeCountersByPart = new HashMap<String, Integer>();

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                if (arguments == null || arguments.length == 0) {
                    return defaultReturnValue(method.getReturnType());
                }

                Object pose = null;
                String path = null;
                Object cube = null;
                Integer cubeIndex = null;
                for (Object argument : arguments) {
                    if (argument == null) {
                        continue;
                    }
                    if (pose == null && ctx.poseClass.isInstance(argument)) {
                        pose = argument;
                    }
                    if (path == null && argument instanceof String) {
                        path = (String) argument;
                    }
                    if (cube == null && ctx.cubeClass.isInstance(argument)) {
                        cube = argument;
                    }
                    if (cubeIndex == null && isIntegralNumber(argument)) {
                        cubeIndex = Integer.valueOf(((Number) argument).intValue());
                    }
                }

                if (cube == null) {
                    return defaultReturnValue(method.getReturnType());
                }
                if (pose == null) {
                    return defaultReturnValue(method.getReturnType());
                }

                String normalizedPath = normalizePartPath(path);
                String partPath = normalizedPath;
                if (config.splitCubes) {
                    int resolvedIndex = resolveCubeIndex(normalizedPath, cubeIndex, cubeCountersByPart);
                    partPath = formatSplitPartPath(normalizedPath, resolvedIndex);
                }

                writer.beginPart(sanitizeObjName(partPath));
                exportCube(ctx, pose, cube, writer, config, applyRuntimeOrientation);
                return defaultReturnValue(method.getReturnType());
            }
        };

        Object visitor = Proxy.newProxyInstance(
            ctx.modelPartVisitorClass.getClassLoader(),
            new Class<?>[] { ctx.modelPartVisitorClass },
            handler
        );

        ctx.modelPartVisitMethod.invoke(rootPart, poseStack, visitor);
    }

    private static void exportCube(
            ReflectionContext ctx,
            Object pose,
            Object cube,
            ObjWriter writer,
            Config config,
            boolean applyRuntimeOrientation)
            throws Exception {
        Object matrix = ctx.posePoseMethod.invoke(pose);
        Object[] polygons = ctx.getCubePolygons(cube);
        float signX = applyRuntimeOrientation ? -1.0f : 1.0f;
        float signY = applyRuntimeOrientation ? -1.0f : 1.0f;
        float signZ = config.flipZ ? -1.0f : 1.0f;
        boolean reverseWinding = signX * signY * signZ < 0.0f;

        for (Object polygon : polygons) {
            Object normal = ctx.getPolygonNormal(polygon);
            Object transformedNormal = ctx.transformNormal(pose, normal);

            float normalX = ctx.getVectorX(transformedNormal) * signX;
            float normalY = ctx.getVectorY(transformedNormal) * signY;
            float normalZ = ctx.getVectorZ(transformedNormal) * signZ;
            int normalIndex = writer.writeNormal(normalX, normalY, normalZ);

            Object[] vertices = ctx.getPolygonVertices(polygon);
            int[] vertexIndices = new int[vertices.length];
            int[] uvIndices = new int[vertices.length];

            for (int i = 0; i < vertices.length; i++) {
                Object vertex = vertices[i];

                float worldX = ctx.getVertexWorldX(vertex);
                float worldY = ctx.getVertexWorldY(vertex);
                float worldZ = ctx.getVertexWorldZ(vertex);

                Object transformedPosition = ctx.transformPosition(matrix, worldX, worldY, worldZ);

                float x = ctx.getVectorX(transformedPosition);
                float y = ctx.getVectorY(transformedPosition);
                float z = ctx.getVectorZ(transformedPosition);

                x *= signX;
                y *= signY;
                z *= signZ;

                vertexIndices[i] = writer.writeVertex(x * config.scale, y * config.scale, z * config.scale);

                float u = finiteOrDefault(ctx.getVertexU(vertex), 0.0f);
                float v = finiteOrDefault(ctx.getVertexV(vertex), 0.0f);
                if (config.flipV) {
                    v = 1.0f - v;
                }
                if (config.clampUv) {
                    u = clamp01(u);
                    v = clamp01(v);
                }

                uvIndices[i] = writer.writeTexCoord(u, v);
            }

            writer.writeFace(vertexIndices, uvIndices, normalIndex, reverseWinding);
        }
    }

    private static Object defaultReturnValue(Class<?> type) {
        if (type == null || type == Void.TYPE) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0.0f);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0.0d);
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        return null;
    }

    private static boolean isIntegralNumber(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
    }

    private static float clamp01(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        return value;
    }

    private static float finiteOrDefault(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    private static String normalizePartPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "root";
        }

        String value = rawPath;
        while (value.startsWith("/")) {
            value = value.substring(1);
        }

        if (value.isEmpty()) {
            return "root";
        }

        return value.replace('/', '.');
    }

    private static String formatSplitPartPath(String normalizedPath, int cubeIndex) {
        if (cubeIndex <= 0) {
            return normalizedPath;
        }
        return String.format(Locale.ROOT, "%s.cube_%03d", normalizedPath, Integer.valueOf(cubeIndex));
    }

    private static int resolveCubeIndex(String normalizedPath, Integer cubeIndex, Map<String, Integer> cubeCountersByPart) {
        if (cubeIndex != null && cubeIndex.intValue() >= 0) {
            return cubeIndex.intValue();
        }

        Integer next = cubeCountersByPart.get(normalizedPath);
        int resolvedIndex = next != null ? next.intValue() : 0;
        cubeCountersByPart.put(normalizedPath, Integer.valueOf(resolvedIndex + 1));
        return resolvedIndex;
    }

    private static String sanitizeObjName(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static LocationInfo describeLocation(ReflectionContext ctx, Object location)
            throws Exception {
        Object modelId = ctx.getLocationModel(location);
        String modelString = String.valueOf(modelId);
        String layer = String.valueOf(ctx.getLocationLayer(location));

        String namespace = "minecraft";
        String modelPath = modelString;

        int separator = modelString.indexOf(':');
        if (separator >= 0) {
            namespace = modelString.substring(0, separator);
            modelPath = modelString.substring(separator + 1);
        }

        return new LocationInfo(namespace, modelPath, layer);
    }

    private static String fileStemForLocation(LocationInfo info) {
        String stem = info.namespace + "_" + info.modelPath.replace('/', '_') + "__" + info.layer;
        return stem.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException && ((InvocationTargetException) current).getCause() != null) {
            current = ((InvocationTargetException) current).getCause();
        }
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static void liftModelToGrid(Path objPath) throws IOException {
        List<String> lines = Files.readAllLines(objPath, StandardCharsets.UTF_8);
        float minY = Float.POSITIVE_INFINITY;

        for (String line : lines) {
            if (!line.startsWith("v ")) {
                continue;
            }

            String[] parts = line.trim().split("\\s+");
            if (parts.length < 4) {
                continue;
            }

            float y = Float.parseFloat(parts[2]);
            if (y < minY) {
                minY = y;
            }
        }

        if (!Float.isFinite(minY) || minY >= -0.000001f) {
            return;
        }

        float offsetY = -minY;
        List<String> updated = new ArrayList<String>(lines.size());

        for (String line : lines) {
            if (!line.startsWith("v ")) {
                updated.add(line);
                continue;
            }

            String[] parts = line.trim().split("\\s+");
            if (parts.length < 4) {
                updated.add(line);
                continue;
            }

            float x = Float.parseFloat(parts[1]);
            float y = Float.parseFloat(parts[2]) + offsetY;
            float z = Float.parseFloat(parts[3]);
            updated.add(String.format(Locale.ROOT, "v %.8f %.8f %.8f", Float.valueOf(x), Float.valueOf(y), Float.valueOf(z)));
        }

        Files.write(
            objPath,
            updated,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
    }

    private static final class Config {
        final Path outputDir;
        final Path clientJarPath;
        final boolean applyRuntimeOrientation;
        final boolean liftToGrid;
        final boolean flipV;
        final boolean flipZ;
        final boolean splitCubes;
        final boolean clampUv;
        final float scale;

        Config(
            Path outputDir,
            Path clientJarPath,
            boolean applyRuntimeOrientation,
            boolean liftToGrid,
            boolean flipV,
            boolean flipZ,
            boolean splitCubes,
            boolean clampUv,
            float scale
        ) {
            this.outputDir = outputDir;
            this.clientJarPath = clientJarPath;
            this.applyRuntimeOrientation = applyRuntimeOrientation;
            this.liftToGrid = liftToGrid;
            this.flipV = flipV;
            this.flipZ = flipZ;
            this.splitCubes = splitCubes;
            this.clampUv = clampUv;
            this.scale = scale;
        }

        static Config parse(String[] args) {
            if (args.length == 0) {
                printUsageAndExit(0);
            }

            Path outputDir = null;
            Path clientJarPath = null;
            boolean applyRuntimeOrientation = true;
            boolean liftToGrid = true;
            boolean flipV = true;
            boolean flipZ = false;
            boolean splitCubes = true;
            boolean clampUv = true;
            float scale = 1.0f;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    printUsageAndExit(0);
                } else if ("--out".equals(arg)) {
                    outputDir = Paths.get(requireValue(args, ++i, "--out"));
                } else if ("--client-jar".equals(arg)) {
                    clientJarPath = Paths.get(requireValue(args, ++i, "--client-jar"));
                } else if ("--runtime-orientation".equals(arg)) {
                    applyRuntimeOrientation = parseBoolean(requireValue(args, ++i, "--runtime-orientation"));
                } else if ("--lift-to-grid".equals(arg)) {
                    liftToGrid = parseBoolean(requireValue(args, ++i, "--lift-to-grid"));
                } else if ("--flip-v".equals(arg)) {
                    flipV = parseBoolean(requireValue(args, ++i, "--flip-v"));
                } else if ("--flip-z".equals(arg)) {
                    flipZ = parseBoolean(requireValue(args, ++i, "--flip-z"));
                } else if ("--split-cubes".equals(arg)) {
                    splitCubes = parseBoolean(requireValue(args, ++i, "--split-cubes"));
                } else if ("--clamp-uv".equals(arg)) {
                    clampUv = parseBoolean(requireValue(args, ++i, "--clamp-uv"));
                } else if ("--scale".equals(arg)) {
                    scale = Float.parseFloat(requireValue(args, ++i, "--scale"));
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (outputDir == null) {
                throw new IllegalArgumentException("Missing required --out argument.");
            }

            if (clientJarPath != null && !Files.exists(clientJarPath)) {
                throw new IllegalArgumentException("Client jar not found: " + clientJarPath.toAbsolutePath());
            }

            return new Config(outputDir, clientJarPath, applyRuntimeOrientation, liftToGrid, flipV, flipZ, splitCubes, clampUv, scale);
        }

        private static String requireValue(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args[index];
        }

        private static boolean parseBoolean(String value) {
            if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
                return false;
            }
            throw new IllegalArgumentException("Invalid boolean: " + value);
        }

        private static void printUsageAndExit(int code) {
            System.out.println("Usage:");
            System.out.println("  java EntityLayerObjExporter --out <outputDir> [--client-jar <clientJar>] [--runtime-orientation true|false] [--lift-to-grid true|false] [--flip-v true|false] [--flip-z true|false] [--split-cubes true|false] [--clamp-uv true|false] [--scale <float>]");
            System.exit(code);
        }
    }

    private static final class LocationInfo {
        final String namespace;
        final String modelPath;
        final String layer;

        LocationInfo(String namespace, String modelPath, String layer) {
            this.namespace = namespace;
            this.modelPath = modelPath;
            this.layer = layer;
        }
    }

    private static final class ResolvedTexture {
        final String sourceEntry;
        final Path extractedPath;
        final String mapKdPath;

        ResolvedTexture(String sourceEntry, Path extractedPath, String mapKdPath) {
            this.sourceEntry = sourceEntry;
            this.extractedPath = extractedPath;
            this.mapKdPath = mapKdPath;
        }
    }

    private static final class RuntimeTextureIndex {
        private final Map<String, Map<String, Integer>> candidateScoresByLocationKey;

        private RuntimeTextureIndex(Map<String, Map<String, Integer>> candidateScoresByLocationKey) {
            this.candidateScoresByLocationKey = candidateScoresByLocationKey;
        }

        static RuntimeTextureIndex create(ReflectionContext ctx, Path clientJarPath) throws Exception {
            Map<String, Map<String, Integer>> candidateScoresByLocationKey = new HashMap<String, Map<String, Integer>>();
            if (clientJarPath == null || !Files.exists(clientJarPath)) {
                return new RuntimeTextureIndex(candidateScoresByLocationKey);
            }

            Map<String, String> fieldNameByLocationKey = RuntimeOrientationResolver.discoverStaticLocations(ctx);
            Map<String, String> factoryNameByLocationKey = RuntimeOrientationResolver.discoverFactoryLocations(ctx);
            Map<String, Set<String>> locationKeysByField = invertLocationMembers(fieldNameByLocationKey);
            Map<String, Set<String>> locationKeysByFactory = invertLocationMembers(factoryNameByLocationKey);

            try (ZipFile zipFile = new ZipFile(clientJarPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String entryName = entry.getName();
                    if (!isRendererTextureCandidate(entryName)) {
                        continue;
                    }

                    try (InputStream in = zipFile.getInputStream(entry)) {
                        ClassTextureUsage usage = scanClassTextureUsage(in, entryName);
                        if (usage != null) {
                            usage.recordCandidates(candidateScoresByLocationKey, locationKeysByField, locationKeysByFactory);
                        }
                    }
                }
            }

            return new RuntimeTextureIndex(candidateScoresByLocationKey);
        }

        Map<String, Integer> findCandidates(LocationInfo info) {
            Map<String, Integer> candidates = this.candidateScoresByLocationKey.get(locationKey(info));
            return candidates != null ? candidates : new HashMap<String, Integer>();
        }

        private static String locationKey(LocationInfo info) {
            return info.namespace + ":" + info.modelPath + "#" + info.layer;
        }

        private static Map<String, Set<String>> invertLocationMembers(Map<String, String> memberByLocationKey) {
            Map<String, Set<String>> out = new HashMap<String, Set<String>>();
            for (Map.Entry<String, String> entry : memberByLocationKey.entrySet()) {
                String locationKey = entry.getKey();
                String memberName = entry.getValue();
                if (locationKey == null || memberName == null || memberName.isEmpty()) {
                    continue;
                }

                Set<String> locations = out.get(memberName);
                if (locations == null) {
                    locations = new LinkedHashSet<String>();
                    out.put(memberName, locations);
                }
                locations.add(locationKey);
            }
            return out;
        }

        private static boolean isRendererTextureCandidate(String entryName) {
            if (!entryName.endsWith(".class")) {
                return false;
            }
            if (!entryName.startsWith("net/minecraft/client/renderer/")) {
                return false;
            }
            return entryName.startsWith("net/minecraft/client/renderer/entity/")
                || entryName.startsWith("net/minecraft/client/renderer/blockentity/")
                || entryName.startsWith("net/minecraft/client/renderer/special/");
        }

        private static ClassTextureUsage scanClassTextureUsage(InputStream input, String entryName) throws IOException {
            DataInputStream in = new DataInputStream(input);
            if (in.readInt() != 0xCAFEBABE) {
                return null;
            }

            in.readUnsignedShort();
            in.readUnsignedShort();

            ConstantPoolData constantPool = ConstantPoolData.read(in);

            in.readUnsignedShort();
            int thisClassIndex = in.readUnsignedShort();
            in.readUnsignedShort();
            String className = constantPool.resolveClassName(thisClassIndex);
            if (className == null || className.isEmpty()) {
                className = entryName.substring(0, entryName.length() - ".class".length());
            }

            int interfaceCount = in.readUnsignedShort();
            skipFully(in, interfaceCount * 2);

            int fieldCount = in.readUnsignedShort();
            for (int i = 0; i < fieldCount; i++) {
                skipFieldInfo(in);
            }

            ClassTextureUsage usage = new ClassTextureUsage(className);
            int methodCount = in.readUnsignedShort();
            for (int i = 0; i < methodCount; i++) {
                scanMethod(in, constantPool, usage);
            }

            skipAttributes(in);
            return usage;
        }

        private static void scanMethod(DataInputStream in, ConstantPoolData constantPool, ClassTextureUsage usage) throws IOException {
            in.readUnsignedShort();
            String methodName = constantPool.resolveUtf8(in.readUnsignedShort());
            in.readUnsignedShort();

            byte[] code = null;
            int attributeCount = in.readUnsignedShort();
            for (int i = 0; i < attributeCount; i++) {
                String attributeName = constantPool.resolveUtf8(in.readUnsignedShort());
                int attributeLength = in.readInt();
                if ("Code".equals(attributeName)) {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                    int codeLength = in.readInt();
                    code = new byte[codeLength];
                    in.readFully(code);

                    int exceptionTableLength = in.readUnsignedShort();
                    skipFully(in, exceptionTableLength * 8);

                    int codeAttributeCount = in.readUnsignedShort();
                    for (int j = 0; j < codeAttributeCount; j++) {
                        in.readUnsignedShort();
                        int nestedAttributeLength = in.readInt();
                        skipFully(in, nestedAttributeLength);
                    }
                } else {
                    skipFully(in, attributeLength);
                }
            }

            if (code != null) {
                scanBytecode(code, methodName, constantPool, usage);
            }
        }

        private static void scanBytecode(byte[] code, String methodName, ConstantPoolData constantPool, ClassTextureUsage usage) throws IOException {
            boolean constructor = "<init>".equals(methodName);
            boolean clinit = "<clinit>".equals(methodName);
            boolean textureMethod = isTextureMethod(methodName);
            boolean renderMethod = isRenderMethod(methodName);
            String pendingTexture = null;

            for (int pc = 0; pc < code.length; ) {
                int opcode = code[pc] & 0xFF;
                switch (opcode) {
                    case 18: {
                        String textureEntry = normalizeTextureEntry(constantPool.resolveString(code[pc + 1] & 0xFF));
                        if (textureEntry != null) {
                            pendingTexture = textureEntry;
                            usage.recordDirectTexture(textureEntry, textureMethod, renderMethod);
                        }
                        pc += 2;
                        continue;
                    }
                    case 19:
                    case 20: {
                        String textureEntry = normalizeTextureEntry(constantPool.resolveString(readUnsignedShort(code, pc + 1)));
                        if (textureEntry != null) {
                            pendingTexture = textureEntry;
                            usage.recordDirectTexture(textureEntry, textureMethod, renderMethod);
                        }
                        pc += 3;
                        continue;
                    }
                    case 178:
                    case 179:
                    case 180:
                    case 181: {
                        int memberIndex = readUnsignedShort(code, pc + 1);
                        String owner = constantPool.resolveMemberOwner(memberIndex);
                        String memberName = constantPool.resolveMemberName(memberIndex);
                        if ("net/minecraft/client/model/geom/ModelLayers".equals(owner)) {
                            usage.recordLayerField(memberName, constructor);
                        } else if (usage.className.equals(owner) && memberName != null) {
                            if (opcode == 179 && clinit && pendingTexture != null) {
                                usage.texturePathByField.put(memberName, pendingTexture);
                                pendingTexture = null;
                            } else if (opcode == 178) {
                                usage.recordTextureFieldUse(memberName, textureMethod, renderMethod);
                            }
                        }
                        pc += 3;
                        continue;
                    }
                    case 182:
                    case 183:
                    case 184: {
                        int memberIndex = readUnsignedShort(code, pc + 1);
                        String owner = constantPool.resolveMemberOwner(memberIndex);
                        String memberName = constantPool.resolveMemberName(memberIndex);
                        if ("net/minecraft/client/model/geom/ModelLayers".equals(owner)) {
                            usage.recordLayerFactory(memberName, constructor);
                        }
                        pc += 3;
                        continue;
                    }
                    case 185:
                    case 186:
                        pc += 5;
                        continue;
                    default:
                        pc += instructionLength(code, pc, opcode);
                        continue;
                }
            }
        }

        private static boolean isTextureMethod(String methodName) {
            if (methodName == null) {
                return false;
            }
            String lower = methodName.toLowerCase(Locale.ROOT);
            return lower.contains("texture") || lower.contains("skin");
        }

        private static boolean isRenderMethod(String methodName) {
            if (methodName == null) {
                return false;
            }
            String lower = methodName.toLowerCase(Locale.ROOT);
            return lower.contains("render") || lower.contains("submit");
        }

        private static String normalizeTextureEntry(String value) {
            if (value == null) {
                return null;
            }

            String normalized = value.trim().replace('\\', '/');
            if (!normalized.endsWith(".png")) {
                return null;
            }
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            if (normalized.startsWith("assets/")) {
                return normalized;
            }

            int colon = normalized.indexOf(':');
            if (colon > 0) {
                String namespace = normalized.substring(0, colon);
                String path = normalized.substring(colon + 1);
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }
                return "assets/" + namespace + "/" + path;
            }

            if (normalized.startsWith("textures/")) {
                return "assets/minecraft/" + normalized;
            }

            return null;
        }

        private static int instructionLength(byte[] code, int pc, int opcode) throws IOException {
            switch (opcode) {
                case 16:
                case 18:
                case 21:
                case 22:
                case 23:
                case 24:
                case 25:
                case 54:
                case 55:
                case 56:
                case 57:
                case 58:
                case 169:
                case 188:
                    return 2;
                case 17:
                case 19:
                case 20:
                case 132:
                case 153:
                case 154:
                case 155:
                case 156:
                case 157:
                case 158:
                case 159:
                case 160:
                case 161:
                case 162:
                case 163:
                case 164:
                case 165:
                case 166:
                case 167:
                case 168:
                case 178:
                case 179:
                case 180:
                case 181:
                case 182:
                case 183:
                case 184:
                case 187:
                case 189:
                case 192:
                case 193:
                case 198:
                case 199:
                    return 3;
                case 185:
                case 186:
                case 197:
                case 200:
                case 201:
                    return opcode == 197 ? 4 : 5;
                case 170: {
                    int aligned = (pc + 4) & ~3;
                    int low = readInt(code, aligned + 4);
                    int high = readInt(code, aligned + 8);
                    return (aligned - pc) + 12 + ((high - low + 1) * 4);
                }
                case 171: {
                    int aligned = (pc + 4) & ~3;
                    int pairs = readInt(code, aligned + 4);
                    return (aligned - pc) + 8 + (pairs * 8);
                }
                case 196: {
                    int widenedOpcode = code[pc + 1] & 0xFF;
                    return widenedOpcode == 132 ? 6 : 4;
                }
                default:
                    return 1;
            }
        }

        private static int readUnsignedShort(byte[] code, int offset) {
            return ((code[offset] & 0xFF) << 8) | (code[offset + 1] & 0xFF);
        }

        private static int readInt(byte[] code, int offset) {
            return ((code[offset] & 0xFF) << 24)
                | ((code[offset + 1] & 0xFF) << 16)
                | ((code[offset + 2] & 0xFF) << 8)
                | (code[offset + 3] & 0xFF);
        }

        private static void skipFieldInfo(DataInputStream in) throws IOException {
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            skipAttributes(in);
        }

        private static void skipAttributes(DataInputStream in) throws IOException {
            int attributeCount = in.readUnsignedShort();
            for (int i = 0; i < attributeCount; i++) {
                in.readUnsignedShort();
                int attributeLength = in.readInt();
                skipFully(in, attributeLength);
            }
        }

        private static void skipFully(DataInputStream in, int length) throws IOException {
            int remaining = length;
            while (remaining > 0) {
                int skipped = in.skipBytes(remaining);
                if (skipped <= 0) {
                    in.readByte();
                    skipped = 1;
                }
                remaining -= skipped;
            }
        }

        private static final class ConstantPoolData {
            final int[] tags;
            final String[] utf8;
            final int[] classNameIndex;
            final int[] stringIndex;
            final int[] memberClassIndex;
            final int[] memberNameAndTypeIndex;
            final int[] nameAndTypeNameIndex;

            ConstantPoolData(
                int[] tags,
                String[] utf8,
                int[] classNameIndex,
                int[] stringIndex,
                int[] memberClassIndex,
                int[] memberNameAndTypeIndex,
                int[] nameAndTypeNameIndex
            ) {
                this.tags = tags;
                this.utf8 = utf8;
                this.classNameIndex = classNameIndex;
                this.stringIndex = stringIndex;
                this.memberClassIndex = memberClassIndex;
                this.memberNameAndTypeIndex = memberNameAndTypeIndex;
                this.nameAndTypeNameIndex = nameAndTypeNameIndex;
            }

            static ConstantPoolData read(DataInputStream in) throws IOException {
                int cpCount = in.readUnsignedShort();
                int[] tags = new int[cpCount];
                String[] utf8 = new String[cpCount];
                int[] classNameIndex = new int[cpCount];
                int[] stringIndex = new int[cpCount];
                int[] memberClassIndex = new int[cpCount];
                int[] memberNameAndTypeIndex = new int[cpCount];
                int[] nameAndTypeNameIndex = new int[cpCount];

                for (int i = 1; i < cpCount; i++) {
                    int tag = in.readUnsignedByte();
                    tags[i] = tag;
                    switch (tag) {
                        case 1:
                            utf8[i] = in.readUTF();
                            break;
                        case 3:
                        case 4:
                            in.readInt();
                            break;
                        case 5:
                        case 6:
                            in.readLong();
                            i++;
                            break;
                        case 7:
                            classNameIndex[i] = in.readUnsignedShort();
                            break;
                        case 8:
                            stringIndex[i] = in.readUnsignedShort();
                            break;
                        case 9:
                        case 10:
                        case 11:
                            memberClassIndex[i] = in.readUnsignedShort();
                            memberNameAndTypeIndex[i] = in.readUnsignedShort();
                            break;
                        case 12:
                            nameAndTypeNameIndex[i] = in.readUnsignedShort();
                            in.readUnsignedShort();
                            break;
                        case 15:
                            in.readUnsignedByte();
                            in.readUnsignedShort();
                            break;
                        case 16:
                        case 19:
                        case 20:
                            in.readUnsignedShort();
                            break;
                        case 17:
                        case 18:
                            in.readUnsignedShort();
                            in.readUnsignedShort();
                            break;
                        default:
                            throw new IOException("Unsupported class file constant tag: " + tag);
                    }
                }

                return new ConstantPoolData(tags, utf8, classNameIndex, stringIndex, memberClassIndex, memberNameAndTypeIndex, nameAndTypeNameIndex);
            }

            String resolveUtf8(int index) {
                if (index <= 0 || index >= this.utf8.length) {
                    return null;
                }
                return this.utf8[index];
            }

            String resolveClassName(int index) {
                if (index <= 0 || index >= this.classNameIndex.length) {
                    return null;
                }
                return resolveUtf8(this.classNameIndex[index]);
            }

            String resolveMemberOwner(int index) {
                if (index <= 0 || index >= this.memberClassIndex.length) {
                    return null;
                }
                return resolveClassName(this.memberClassIndex[index]);
            }

            String resolveMemberName(int index) {
                if (index <= 0 || index >= this.memberNameAndTypeIndex.length) {
                    return null;
                }
                int nameAndTypeIndex = this.memberNameAndTypeIndex[index];
                if (nameAndTypeIndex <= 0 || nameAndTypeIndex >= this.nameAndTypeNameIndex.length) {
                    return null;
                }
                return resolveUtf8(this.nameAndTypeNameIndex[nameAndTypeIndex]);
            }

            String resolveString(int index) {
                if (index <= 0 || index >= this.tags.length) {
                    return null;
                }
                if (this.tags[index] == 8) {
                    return resolveUtf8(this.stringIndex[index]);
                }
                if (this.tags[index] == 1) {
                    return this.utf8[index];
                }
                return null;
            }
        }

        private static final class ClassTextureUsage {
            final String className;
            final Set<String> constructorLayerFields;
            final Set<String> constructorLayerFactories;
            final Set<String> methodLayerFields;
            final Set<String> methodLayerFactories;
            final Map<String, String> texturePathByField;
            final Set<String> textureFieldsInTextureMethods;
            final Set<String> textureFieldsInRenderMethods;
            final Set<String> directTexturesInTextureMethods;
            final Set<String> directTexturesInRenderMethods;

            ClassTextureUsage(String className) {
                this.className = className;
                this.constructorLayerFields = new LinkedHashSet<String>();
                this.constructorLayerFactories = new LinkedHashSet<String>();
                this.methodLayerFields = new LinkedHashSet<String>();
                this.methodLayerFactories = new LinkedHashSet<String>();
                this.texturePathByField = new HashMap<String, String>();
                this.textureFieldsInTextureMethods = new LinkedHashSet<String>();
                this.textureFieldsInRenderMethods = new LinkedHashSet<String>();
                this.directTexturesInTextureMethods = new LinkedHashSet<String>();
                this.directTexturesInRenderMethods = new LinkedHashSet<String>();
            }

            void recordLayerField(String memberName, boolean constructor) {
                if (memberName == null || memberName.isEmpty()) {
                    return;
                }
                if (constructor) {
                    this.constructorLayerFields.add(memberName);
                } else {
                    this.methodLayerFields.add(memberName);
                }
            }

            void recordLayerFactory(String memberName, boolean constructor) {
                if (memberName == null || memberName.isEmpty()) {
                    return;
                }
                if (constructor) {
                    this.constructorLayerFactories.add(memberName);
                } else {
                    this.methodLayerFactories.add(memberName);
                }
            }

            void recordTextureFieldUse(String fieldName, boolean textureMethod, boolean renderMethod) {
                if (fieldName == null || fieldName.isEmpty()) {
                    return;
                }
                if (textureMethod) {
                    this.textureFieldsInTextureMethods.add(fieldName);
                }
                if (renderMethod) {
                    this.textureFieldsInRenderMethods.add(fieldName);
                }
            }

            void recordDirectTexture(String textureEntry, boolean textureMethod, boolean renderMethod) {
                if (textureEntry == null || textureEntry.isEmpty()) {
                    return;
                }
                if (textureMethod) {
                    this.directTexturesInTextureMethods.add(textureEntry);
                }
                if (renderMethod) {
                    this.directTexturesInRenderMethods.add(textureEntry);
                }
            }

            void recordCandidates(
                Map<String, Map<String, Integer>> candidateScoresByLocationKey,
                Map<String, Set<String>> locationKeysByField,
                Map<String, Set<String>> locationKeysByFactory
            ) {
                Map<String, Integer> locationScores = collectLocationScores(locationKeysByField, locationKeysByFactory);
                if (locationScores.isEmpty()) {
                    return;
                }

                Map<String, Integer> textureScores = collectTextureScores();
                if (textureScores.isEmpty()) {
                    return;
                }

                for (Map.Entry<String, Integer> locationEntry : locationScores.entrySet()) {
                    String locationKey = locationEntry.getKey();
                    int locationScore = locationEntry.getValue().intValue();

                    Map<String, Integer> textureScoresForLocation = candidateScoresByLocationKey.get(locationKey);
                    if (textureScoresForLocation == null) {
                        textureScoresForLocation = new HashMap<String, Integer>();
                        candidateScoresByLocationKey.put(locationKey, textureScoresForLocation);
                    }

                    for (Map.Entry<String, Integer> textureEntry : textureScores.entrySet()) {
                        int combinedScore = locationScore + textureEntry.getValue().intValue();
                        Integer previous = textureScoresForLocation.get(textureEntry.getKey());
                        if (previous == null || combinedScore > previous.intValue()) {
                            textureScoresForLocation.put(textureEntry.getKey(), Integer.valueOf(combinedScore));
                        }
                    }
                }
            }

            private Map<String, Integer> collectLocationScores(
                Map<String, Set<String>> locationKeysByField,
                Map<String, Set<String>> locationKeysByFactory
            ) {
                Map<String, Integer> out = new HashMap<String, Integer>();
                addLocationScores(out, this.constructorLayerFields, locationKeysByField, 240, false);
                addLocationScores(out, this.constructorLayerFactories, locationKeysByFactory, 200, true);
                addLocationScores(out, this.methodLayerFields, locationKeysByField, 90, false);
                addLocationScores(out, this.methodLayerFactories, locationKeysByFactory, 60, true);
                return out;
            }

            private Map<String, Integer> collectTextureScores() {
                Map<String, Integer> out = new HashMap<String, Integer>();
                addResolvedTextureScores(out, this.textureFieldsInTextureMethods, 420);
                addDirectTextureScores(out, this.directTexturesInTextureMethods, 400);
                addResolvedTextureScores(out, this.textureFieldsInRenderMethods, 320);
                addDirectTextureScores(out, this.directTexturesInRenderMethods, 300);

                if (out.isEmpty() && this.texturePathByField.size() == 1) {
                    for (String texture : this.texturePathByField.values()) {
                        out.put(texture, Integer.valueOf(220));
                    }
                }

                return out;
            }

            private void addResolvedTextureScores(Map<String, Integer> out, Set<String> fieldNames, int score) {
                for (String fieldName : fieldNames) {
                    String textureEntry = this.texturePathByField.get(fieldName);
                    if (textureEntry != null) {
                        mergeScore(out, textureEntry, score);
                    }
                }
            }

            private void addDirectTextureScores(Map<String, Integer> out, Set<String> textureEntries, int score) {
                for (String textureEntry : textureEntries) {
                    mergeScore(out, textureEntry, score);
                }
            }

            private static void addLocationScores(
                Map<String, Integer> out,
                Set<String> memberNames,
                Map<String, Set<String>> locationKeysByMember,
                int score,
                boolean requireUniqueLocation
            ) {
                for (String memberName : memberNames) {
                    Set<String> locationKeys = locationKeysByMember.get(memberName);
                    if (locationKeys == null || locationKeys.isEmpty()) {
                        continue;
                    }
                    if (requireUniqueLocation && locationKeys.size() != 1) {
                        continue;
                    }
                    for (String locationKey : locationKeys) {
                        mergeScore(out, locationKey, score);
                    }
                }
            }

            private static void mergeScore(Map<String, Integer> out, String key, int score) {
                Integer previous = out.get(key);
                if (previous == null || score > previous.intValue()) {
                    out.put(key, Integer.valueOf(score));
                }
            }
        }
    }

    private static final class TextureResolver implements AutoCloseable {
        private static final String[] STRIP_SUFFIXES = new String[] {
            "_baby",
            "_no_hat",
            "_slim",
            "_small",
            "_outer",
            "_inner",
            "_boots",
            "_chestplate",
            "_helmet",
            "_leggings",
            "_armor",
            "_water_patch",
            "_flag",
            "_main",
            "_left",
            "_right",
            "_decor",
            "_pattern",
            "_head",
            "_saddle"
        };

        private static final String[] MAIN_LAYER_PENALTY_HINTS = new String[] {
            "eyes",
            "armor",
            "saddle",
            "pattern",
            "overlay",
            "glow",
            "emissive",
            "wind",
            "heart",
            "tendrils",
            "collar",
            "harness",
            "ropes",
            "spots",
            "undercoat",
            "outer",
            "inner"
        };

        private final ZipFile zipFile;
        private final Path outputDir;
        private final List<String> textureEntries;
        private final Map<String, ResolvedTexture> cache;
        private final Set<String> extracted;
        private final RuntimeTextureIndex runtimeTextureIndex;

        TextureResolver(ReflectionContext ctx, Path clientJar, Path outputDir) throws Exception {
            this.zipFile = new ZipFile(clientJar.toFile());
            this.outputDir = outputDir;
            this.textureEntries = new ArrayList<String>();
            this.cache = new HashMap<String, ResolvedTexture>();
            this.extracted = new HashSet<String>();
            this.runtimeTextureIndex = RuntimeTextureIndex.create(ctx, clientJar);

            Enumeration<? extends ZipEntry> enumeration = this.zipFile.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".png")) {
                    continue;
                }
                if (!lower.contains("/textures/")) {
                    continue;
                }
                if (!lower.contains("/textures/entity/")
                        && !lower.contains("/textures/models/armor/")
                        && !lower.endsWith("/textures/block/water_still.png")) {
                    continue;
                }

                this.textureEntries.add(name);
            }
        }

        ResolvedTexture resolveAndExtract(LocationInfo info) throws IOException {
            String cacheKey = info.namespace + ":" + info.modelPath + "#" + info.layer;
            ResolvedTexture cached = this.cache.get(cacheKey);
            if (cached != null || this.cache.containsKey(cacheKey)) {
                return cached;
            }

            ResolvedTexture resolved = resolve(info);
            this.cache.put(cacheKey, resolved);

            if (resolved != null) {
                extractIfNeeded(resolved);
            }

            return resolved;
        }

        int extractAllTrackedTextures() throws IOException {
            int extractedCount = 0;
            for (String entry : this.textureEntries) {
                ResolvedTexture resolved = resolvedFromEntry(entry);
                if (resolved == null || this.extracted.contains(resolved.sourceEntry)) {
                    continue;
                }
                extractIfNeeded(resolved);
                if (this.extracted.contains(resolved.sourceEntry)) {
                    extractedCount++;
                }
            }
            return extractedCount;
        }

        private ResolvedTexture resolve(LocationInfo info) {
            TextureSearchContext search = TextureSearchContext.create(this, info);

            String runtimeEntry = findRuntimeTextureEntry(info, search);
            if (runtimeEntry != null) {
                return resolvedFromEntry(runtimeEntry);
            }

            String knownEntry = findKnownTextureEntry(search.namespaceLower, search.modelPathLower, search.layerLower);
            if (knownEntry != null) {
                return resolvedFromEntry(knownEntry);
            }

            int bestScore = Integer.MIN_VALUE;
            String bestEntry = null;

            for (String entry : this.textureEntries) {
                String lower = entry.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("assets/" + search.namespaceLower + "/")) {
                    continue;
                }

                int score = search.score(lower);

                if (score > bestScore) {
                    bestScore = score;
                    bestEntry = entry;
                }
            }

            if (bestEntry == null || bestScore < 90) {
                return null;
            }

            return resolvedFromEntry(bestEntry);
        }

        private String findRuntimeTextureEntry(LocationInfo info, TextureSearchContext search) {
            Map<String, Integer> candidates = this.runtimeTextureIndex.findCandidates(info);
            if (candidates.isEmpty()) {
                return null;
            }

            int bestScore = Integer.MIN_VALUE;
            String bestEntry = null;

            for (Map.Entry<String, Integer> candidate : candidates.entrySet()) {
                String entry = candidate.getKey();
                if (!hasTextureEntry(entry)) {
                    continue;
                }

                int score = candidate.getValue().intValue() + search.score(entry.toLowerCase(Locale.ROOT));
                if (score > bestScore) {
                    bestScore = score;
                    bestEntry = entry;
                }
            }

            return bestEntry;
        }

        private ResolvedTexture resolvedFromEntry(String entry) {
            String lower = entry.toLowerCase(Locale.ROOT);
            int texturesIndex = lower.indexOf("/textures/");
            if (texturesIndex < 0) {
                return null;
            }

            String subPath = entry.substring(texturesIndex + "/textures/".length());
            Path extractedPath = this.outputDir.resolve("textures").resolve(subPath.replace('/', java.io.File.separatorChar));
            String mapKd = toForwardSlashes(this.outputDir.relativize(extractedPath).toString());
            return new ResolvedTexture(entry, extractedPath, mapKd);
        }

        private String findKnownTextureEntry(String namespaceLower, String modelPathLower, String layerLower) {
            if (!"minecraft".equals(namespaceLower)) {
                return null;
            }
            String modelKey = modelPathLower.replace('/', '_');

            if (modelKey.endsWith("_minecart")) {
                String candidate = "assets/minecraft/textures/entity/minecart/minecart.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("boat".equals(modelKey) && "water_patch".equals(layerLower)) {
                String candidate = "assets/minecraft/textures/block/water_still.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("sniffer_baby".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/sniffer/snifflet.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("armor_stand".equals(modelKey) || "armor_stand_small".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/armorstand/armorstand.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("giant".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/zombie/zombie.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("player".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/player/wide/steve.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }
            if ("player_slim".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/player/slim/steve.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("leash_knot".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/lead_knot/lead_knot.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("dragon_skull".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/enderdragon/dragon.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("double_chest_left".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/chest/normal_left.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }
            if ("double_chest_right".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/chest/normal_right.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }
            if ("chest".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/chest/normal.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("bed_foot".equals(modelKey) || "bed_head".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/bed/red.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("decorated_pot_sides".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/decorated_pot/decorated_pot_side.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("conduit".equals(modelKey) && "shell".equals(layerLower)) {
                String candidate = "assets/minecraft/textures/entity/conduit/base.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if (modelKey.startsWith("hanging_sign_")) {
                String wood = detectWoodType(modelKey);
                if (wood != null) {
                    String candidate = "assets/minecraft/textures/entity/signs/hanging/" + wood + ".png";
                    if (hasTextureEntry(candidate)) {
                        return candidate;
                    }
                }
            }

            if ("standing_banner".equals(modelKey) || "wall_banner".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/banner/base.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("pufferfish_big".equals(modelKey) || "pufferfish_medium".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/fish/pufferfish.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }
            if ("salmon_large".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/fish/salmon.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }
            if ("tropical_fish_small".equals(modelKey)) {
                String candidate = "main".equals(layerLower)
                    ? "assets/minecraft/textures/entity/fish/tropical_a.png"
                    : "assets/minecraft/textures/entity/fish/tropical_a_pattern_1.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }
            if ("tropical_fish_large".equals(modelKey)) {
                String candidate = "main".equals(layerLower)
                    ? "assets/minecraft/textures/entity/fish/tropical_b.png"
                    : "assets/minecraft/textures/entity/fish/tropical_b_pattern_1.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("shulker_box".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/shulker/shulker.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }
            if ("shulker_bullet".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/shulker/spark.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("skeleton_skull".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/skeleton/skeleton.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }
            if ("wither_skeleton_skull".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/skeleton/wither_skeleton.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }
            if ("wither_skull".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/wither/wither.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("undead_horse_armor".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/horse/horse_zombie.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("spin_attack".equals(modelKey)) {
                String candidate = "assets/minecraft/textures/entity/trident/trident_riptide.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("llama".equals(modelKey) && "decor".equals(layerLower)) {
                String candidate = "assets/minecraft/textures/entity/llama/llama_white.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }
            if ("llama_baby".equals(modelKey) && "decor".equals(layerLower)) {
                String candidate = "assets/minecraft/textures/entity/llama/llama_white_baby.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if (modelKey.startsWith("copper_golem_") && "main".equals(layerLower)) {
                String candidate = "assets/minecraft/textures/entity/copper_golem/copper_golem.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if ("drowned".equals(modelKey) || "drowned_baby".equals(modelKey)) {
                boolean baby = modelKey.contains("baby");
                if (layerLower.contains("outer")) {
                    String candidate = baby
                        ? "assets/minecraft/textures/entity/zombie/drowned_outer_layer_baby.png"
                        : "assets/minecraft/textures/entity/zombie/drowned_outer_layer.png";
                    if (hasTextureEntry(candidate)) {
                        return candidate;
                    }
                }

                String candidate = baby
                    ? "assets/minecraft/textures/entity/zombie/drowned_baby.png"
                    : "assets/minecraft/textures/entity/zombie/drowned.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if (modelKey.startsWith("villager")) {
                boolean baby = modelKey.contains("_baby");
                String candidate = baby
                    ? "assets/minecraft/textures/entity/villager/villager_baby.png"
                    : "assets/minecraft/textures/entity/villager/villager.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if (modelKey.startsWith("zombie_villager")) {
                boolean baby = modelKey.contains("_baby");
                String candidate = baby
                    ? "assets/minecraft/textures/entity/zombie_villager/zombie_villager_baby.png"
                    : "assets/minecraft/textures/entity/zombie_villager/zombie_villager.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            if (modelPathLower.contains("happy_ghast") && modelPathLower.contains("harness")) {
                String candidate = "assets/minecraft/textures/entity/equipment/happy_ghast_body/white_harness.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }
            if (modelPathLower.contains("happy_ghast") && modelPathLower.contains("ropes")) {
                String candidate = "assets/minecraft/textures/entity/ghast/happy_ghast_ropes.png";
                if (hasTextureEntry(candidate)) {
                    return candidate;
                }
            }

            return null;
        }

        private static String detectWoodType(String modelPathLower) {
            String[] woods = new String[] {
                "acacia",
                "bamboo",
                "birch",
                "cherry",
                "crimson",
                "dark_oak",
                "jungle",
                "mangrove",
                "oak",
                "pale_oak",
                "spruce",
                "warped"
            };
            for (String wood : woods) {
                if (modelPathLower.contains("_" + wood + "_")) {
                    return wood;
                }
            }
            return null;
        }

        private boolean hasTextureEntry(String entryName) {
            for (String entry : this.textureEntries) {
                if (entryName.equalsIgnoreCase(entry)) {
                    return true;
                }
            }
            return false;
        }

        private static final class TextureSearchContext {
            final String namespaceLower;
            final String modelPathLower;
            final String layerLower;
            final String flatModel;
            final String canonicalFlat;
            final String lastSegment;
            final String canonicalLast;
            final boolean hasCanonicalFileNameCandidates;
            final Set<String> names;
            final Set<String> modelTokens;
            final Set<String> layerTokens;

            private TextureSearchContext(
                String namespaceLower,
                String modelPathLower,
                String layerLower,
                String flatModel,
                String canonicalFlat,
                String lastSegment,
                String canonicalLast,
                boolean hasCanonicalFileNameCandidates,
                Set<String> names,
                Set<String> modelTokens,
                Set<String> layerTokens
            ) {
                this.namespaceLower = namespaceLower;
                this.modelPathLower = modelPathLower;
                this.layerLower = layerLower;
                this.flatModel = flatModel;
                this.canonicalFlat = canonicalFlat;
                this.lastSegment = lastSegment;
                this.canonicalLast = canonicalLast;
                this.hasCanonicalFileNameCandidates = hasCanonicalFileNameCandidates;
                this.names = names;
                this.modelTokens = modelTokens;
                this.layerTokens = layerTokens;
            }

            static TextureSearchContext create(TextureResolver resolver, LocationInfo info) {
                String namespaceLower = info.namespace.toLowerCase(Locale.ROOT);
                String modelPathLower = info.modelPath.toLowerCase(Locale.ROOT);
                String layerLower = info.layer.toLowerCase(Locale.ROOT);

                String flatModel = modelPathLower.replace('/', '_');
                String lastSegment = modelPathLower;
                int slash = lastSegment.lastIndexOf('/');
                if (slash >= 0) {
                    lastSegment = lastSegment.substring(slash + 1);
                }

                String canonicalFlat = stripSuffixes(flatModel);
                String canonicalLast = stripSuffixes(lastSegment);
                String compactFlat = flatModel.replace("_", "");
                String compactLast = lastSegment.replace("_", "");
                String compactCanonicalFlat = canonicalFlat.replace("_", "");
                String compactCanonicalLast = canonicalLast.replace("_", "");
                boolean hasCanonicalFileNameCandidates = resolver.hasNamespaceEntryWithFileNameToken(namespaceLower, canonicalLast);

                LinkedHashSet<String> names = new LinkedHashSet<String>();
                addName(names, flatModel);
                addName(names, lastSegment);
                addName(names, canonicalFlat);
                addName(names, canonicalLast);
                addName(names, compactFlat);
                addName(names, compactLast);
                addName(names, compactCanonicalFlat);
                addName(names, compactCanonicalLast);

                if (!"main".equals(layerLower)) {
                    addName(names, flatModel + "_" + layerLower);
                    addName(names, lastSegment + "_" + layerLower);
                    addName(names, canonicalFlat + "_" + layerLower);
                    addName(names, canonicalLast + "_" + layerLower);
                    addName(names, compactFlat + "_" + layerLower);
                    addName(names, compactLast + "_" + layerLower);
                    addName(names, compactCanonicalFlat + "_" + layerLower);
                    addName(names, compactCanonicalLast + "_" + layerLower);
                }

                Set<String> modelTokens = tokenize(flatModel);
                modelTokens.addAll(tokenize(lastSegment));
                if (!canonicalFlat.equals(flatModel)) {
                    modelTokens.addAll(tokenize(canonicalFlat));
                }

                Set<String> layerTokens = tokenize(layerLower);
                return new TextureSearchContext(
                    namespaceLower,
                    modelPathLower,
                    layerLower,
                    flatModel,
                    canonicalFlat,
                    lastSegment,
                    canonicalLast,
                    hasCanonicalFileNameCandidates,
                    names,
                    modelTokens,
                    layerTokens
                );
            }

            int score(String lowerEntry) {
                return scoreEntry(
                    lowerEntry,
                    this.modelPathLower,
                    this.layerLower,
                    this.names,
                    this.modelTokens,
                    this.layerTokens,
                    this.flatModel,
                    this.canonicalFlat,
                    this.lastSegment,
                    this.canonicalLast,
                    this.hasCanonicalFileNameCandidates
                );
            }
        }

        private static int scoreEntry(
            String entry,
            String modelPathLower,
            String layerLower,
            Set<String> names,
            Set<String> modelTokens,
            Set<String> layerTokens,
            String flatModel,
            String canonicalFlat,
            String lastSegment,
            String canonicalLast,
            boolean hasCanonicalFileNameCandidates
        ) {
            int score = 0;
            Set<String> entryTokens = tokenize(entry);
            String fileName = extractFileNameWithoutExtension(entry);
            Set<String> fileNameTokens = tokenize(fileName);
            boolean modelHasBaby = modelTokens.contains("baby") || modelPathLower.contains("_baby");
            boolean fileNameHasCanonicalToken = hasTokenInFileName(fileName, fileNameTokens, canonicalLast);

            if (entry.contains("/textures/entity/")) {
                score += 50;
            }
            if (entry.contains("/textures/models/armor/")) {
                score += 30;
            }

            if (entry.endsWith("/" + modelPathLower + ".png")) {
                score += 260;
            }
            if (entry.endsWith("/" + flatModel + ".png")) {
                score += 220;
            }
            if (!canonicalFlat.equals(flatModel) && entry.endsWith("/" + canonicalFlat + ".png")) {
                score += 200;
            }

            for (String name : names) {
                if (fileName.equals(name)) {
                    score += 180;
                }
                if (entry.endsWith("/" + name + ".png")) {
                    score += 160;
                }
                if (entry.contains("/" + name + "/")) {
                    score += 60;
                }
                if (entry.contains(name)) {
                    score += 20;
                }
            }

            int matchedModelTokens = 0;
            for (String token : modelTokens) {
                if (token.length() > 2 && (entryTokens.contains(token) || fileNameTokens.contains(token))) {
                    score += 14;
                    matchedModelTokens++;
                }
            }

            if (!modelTokens.isEmpty() && matchedModelTokens == modelTokens.size()) {
                score += 70;
            }
            if (hasCanonicalFileNameCandidates) {
                if (fileNameHasCanonicalToken) {
                    score += 110;
                } else {
                    score -= 110;
                }
            }

            if (modelHasBaby) {
                if (entryTokens.contains("baby") || fileNameTokens.contains("baby")) {
                    score += 50;
                }
            } else if (entryTokens.contains("baby") || fileNameTokens.contains("baby")) {
                score -= 30;
            }

            if ("main".equals(layerLower)) {
                for (String hint : MAIN_LAYER_PENALTY_HINTS) {
                    if ((entryTokens.contains(hint) || fileNameTokens.contains(hint)) && !modelTokens.contains(hint)) {
                        score -= 55;
                    }
                }
            } else {
                int matchedLayerTokens = 0;
                for (String token : layerTokens) {
                    if (token.length() > 2 && (entryTokens.contains(token) || fileNameTokens.contains(token))) {
                        score += 40;
                        matchedLayerTokens++;
                    }
                }
                if (!layerTokens.isEmpty() && matchedLayerTokens == 0) {
                    score -= 200;
                }

                if (layerLower.contains("armor") || layerLower.contains("boots") || layerLower.contains("leggings")
                        || layerLower.contains("chestplate") || layerLower.contains("helmet")) {
                    if (entry.contains("/textures/models/armor/")) {
                        score += 90;
                    }
                }
            }

            if (layerLower.contains("outer") && (entryTokens.contains("outer") || entry.contains("outer_layer"))) {
                score += 110;
            }
            if (layerLower.contains("inner") && entryTokens.contains("inner")) {
                score += 80;
            }

            if (modelTokens.contains("armor") && modelTokens.contains("stand") && "armorstand".equals(fileName)) {
                score += 260;
            }
            if (modelHasBaby && (modelTokens.contains("sniffer") || "sniffer_baby".equals(lastSegment)) && "snifflet".equals(fileName)) {
                score += 300;
            }
            if (modelTokens.contains("harness") && fileName.endsWith("_harness")) {
                score += 170;
                if (fileName.startsWith("white_")) {
                    score += 120;
                }
            }

            return score;
        }

        private boolean hasNamespaceEntryWithFileNameToken(String namespaceLower, String token) {
            if (token == null || token.length() <= 2) {
                return false;
            }
            for (String entry : this.textureEntries) {
                String lower = entry.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("assets/" + namespaceLower + "/")) {
                    continue;
                }
                String fileName = extractFileNameWithoutExtension(lower);
                Set<String> fileNameTokens = tokenize(fileName);
                if (hasTokenInFileName(fileName, fileNameTokens, token)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasTokenInFileName(String fileName, Set<String> fileNameTokens, String token) {
            if (token == null || token.length() <= 2) {
                return false;
            }
            if (token.equals(fileName)) {
                return true;
            }
            if (fileNameTokens.contains(token)) {
                return true;
            }
            return fileName.startsWith(token + "_")
                || fileName.endsWith("_" + token)
                || fileName.contains("_" + token + "_");
        }

        private static String extractFileNameWithoutExtension(String entry) {
            int slash = entry.lastIndexOf('/');
            int dot = entry.lastIndexOf('.');
            if (dot <= slash) {
                return entry.substring(slash + 1);
            }
            return entry.substring(slash + 1, dot);
        }

        private static void addName(Set<String> names, String name) {
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }

        private static Set<String> tokenize(String value) {
            Set<String> out = new HashSet<String>();
            if (value == null || value.isEmpty()) {
                return out;
            }

            String[] split = value.split("[^a-z0-9]+");
            for (String token : split) {
                if (!token.isEmpty()) {
                    out.add(token);
                }
            }
            return out;
        }

        private static String stripSuffixes(String value) {
            String current = value;
            boolean changed;
            do {
                changed = false;
                for (String suffix : STRIP_SUFFIXES) {
                    if (current.endsWith(suffix) && current.length() > suffix.length() + 2) {
                        current = current.substring(0, current.length() - suffix.length());
                        changed = true;
                        break;
                    }
                }
            } while (changed);

            return current;
        }

        private static String toForwardSlashes(String path) {
            return path.replace('\\', '/');
        }

        private void extractIfNeeded(ResolvedTexture texture) throws IOException {
            if (this.extracted.contains(texture.sourceEntry)) {
                return;
            }

            ZipEntry entry = this.zipFile.getEntry(texture.sourceEntry);
            if (entry == null) {
                return;
            }

            Path parent = texture.extractedPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (InputStream in = this.zipFile.getInputStream(entry);
                 OutputStream out = Files.newOutputStream(texture.extractedPath)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            }

            this.extracted.add(texture.sourceEntry);
        }

        @Override
        public void close() throws IOException {
            this.zipFile.close();
        }
    }

    private static final class RuntimeOrientationResolver {
        private final Map<String, Boolean> applyByLocationKey;

        private RuntimeOrientationResolver(Map<String, Boolean> applyByLocationKey) {
            this.applyByLocationKey = applyByLocationKey;
        }

        static RuntimeOrientationResolver create(ReflectionContext ctx, Path clientJarPath) throws Exception {
            Map<String, Boolean> applyByLocationKey = new HashMap<String, Boolean>();
            if (clientJarPath == null || !Files.exists(clientJarPath)) {
                return new RuntimeOrientationResolver(applyByLocationKey);
            }

            Map<String, String> fieldNameByLocationKey = discoverStaticLocations(ctx);
            Map<String, String> factoryNameByLocationKey = discoverFactoryLocations(ctx);
            UsageIndex usageIndex = scanJarUsage(clientJarPath);

            Set<String> keys = new LinkedHashSet<String>();
            keys.addAll(fieldNameByLocationKey.keySet());
            keys.addAll(factoryNameByLocationKey.keySet());

            for (String locationKey : keys) {
                Usage usage = null;

                String fieldName = fieldNameByLocationKey.get(locationKey);
                if (fieldName != null) {
                    usage = usageIndex.fieldUsage.get(fieldName);
                }

                if (usage == null) {
                    String factoryName = factoryNameByLocationKey.get(locationKey);
                    if (factoryName != null) {
                        usage = usageIndex.factoryUsage.get(factoryName);
                    }
                }

                if (usage != null) {
                    applyByLocationKey.put(locationKey, Boolean.valueOf(usage.shouldApply()));
                }
            }

            return new RuntimeOrientationResolver(applyByLocationKey);
        }

        boolean shouldApply(Object location) {
            Boolean value = this.applyByLocationKey.get(String.valueOf(location));
            return value == null ? true : value.booleanValue();
        }

        private static Map<String, String> discoverStaticLocations(ReflectionContext ctx) throws Exception {
            Map<String, String> out = new HashMap<String, String>();
            for (Field field : ctx.modelLayersClass.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!ctx.modelLayerLocationClass.equals(field.getType())) {
                    continue;
                }

                ReflectionContext.makeAccessible(field);
                Object value = field.get(null);
                if (value != null) {
                    out.put(String.valueOf(value), field.getName());
                }
            }
            return out;
        }

        private static Map<String, String> discoverFactoryLocations(ReflectionContext ctx) throws Exception {
            Map<String, String> out = new HashMap<String, String>();
            for (Method method : ctx.modelLayersClass.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (!ctx.modelLayerLocationClass.equals(method.getReturnType())) {
                    continue;
                }

                List<Object[]> argumentSets = enumerateArguments(method.getParameterTypes());
                if (argumentSets == null) {
                    continue;
                }

                ReflectionContext.makeAccessible(method);
                for (Object[] arguments : argumentSets) {
                    Object value;
                    try {
                        value = method.invoke(null, arguments);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (value != null) {
                        out.put(String.valueOf(value), method.getName());
                    }
                }
            }
            return out;
        }

        private static List<Object[]> enumerateArguments(Class<?>[] parameterTypes) {
            List<Object[]> out = new ArrayList<Object[]>();
            out.add(new Object[parameterTypes.length]);

            for (int i = 0; i < parameterTypes.length; i++) {
                Object[] values = enumerateParameterValues(parameterTypes[i]);
                if (values == null) {
                    return null;
                }

                List<Object[]> next = new ArrayList<Object[]>(out.size() * Math.max(values.length, 1));
                for (Object[] existing : out) {
                    for (Object value : values) {
                        Object[] copy = new Object[parameterTypes.length];
                        System.arraycopy(existing, 0, copy, 0, existing.length);
                        copy[i] = value;
                        next.add(copy);
                    }
                }
                out = next;
            }

            return out;
        }

        private static Object[] enumerateParameterValues(Class<?> type) {
            if (type.isEnum()) {
                Object[] constants = type.getEnumConstants();
                return constants != null && constants.length > 0 ? constants : null;
            }
            if (type == Boolean.TYPE || type == Boolean.class) {
                return new Object[] { Boolean.FALSE, Boolean.TRUE };
            }
            try {
                Method valuesMethod = type.getMethod("values");
                if (Modifier.isStatic(valuesMethod.getModifiers()) && valuesMethod.getParameterCount() == 0) {
                    Object values = valuesMethod.invoke(null);
                    if (values instanceof Object[] && ((Object[]) values).length > 0) {
                        return (Object[]) values;
                    }
                    if (values instanceof Stream) {
                        try (Stream<?> stream = (Stream<?>) values) {
                            List<?> collected = stream.collect(Collectors.toList());
                            if (!collected.isEmpty()) {
                                return collected.toArray();
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private static UsageIndex scanJarUsage(Path clientJarPath) throws IOException {
            UsageIndex index = new UsageIndex();
            try (ZipFile zipFile = new ZipFile(clientJarPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String entryName = entry.getName();
                    RendererCategory category = RendererCategory.forClassEntry(entryName);
                    if (category == RendererCategory.IGNORE || !entryName.endsWith(".class")) {
                        continue;
                    }

                    InputStream in = zipFile.getInputStream(entry);
                    try {
                        scanClassReferences(in, category, index);
                    } finally {
                        in.close();
                    }
                }
            }
            return index;
        }

        private static void scanClassReferences(InputStream input, RendererCategory category, UsageIndex index) throws IOException {
            DataInputStream in = new DataInputStream(input);
            if (in.readInt() != 0xCAFEBABE) {
                return;
            }

            in.readUnsignedShort();
            in.readUnsignedShort();

            int cpCount = in.readUnsignedShort();
            int[] tags = new int[cpCount];
            String[] utf8 = new String[cpCount];
            int[] classNameIndex = new int[cpCount];
            int[] memberClassIndex = new int[cpCount];
            int[] memberNameAndTypeIndex = new int[cpCount];
            int[] nameAndTypeNameIndex = new int[cpCount];

            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = tag;
                switch (tag) {
                    case 1:
                        utf8[i] = in.readUTF();
                        break;
                    case 3:
                    case 4:
                        in.readInt();
                        break;
                    case 5:
                    case 6:
                        in.readLong();
                        i++;
                        break;
                    case 7:
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        classNameIndex[i] = in.readUnsignedShort();
                        break;
                    case 9:
                    case 10:
                    case 11:
                        memberClassIndex[i] = in.readUnsignedShort();
                        memberNameAndTypeIndex[i] = in.readUnsignedShort();
                        break;
                    case 12:
                        nameAndTypeNameIndex[i] = in.readUnsignedShort();
                        in.readUnsignedShort();
                        break;
                    case 15:
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    case 17:
                    case 18:
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                        break;
                    default:
                        throw new IOException("Unsupported class file constant tag: " + tag);
                }
            }

            for (int i = 1; i < cpCount; i++) {
                int tag = tags[i];
                if (tag != 9 && tag != 10) {
                    continue;
                }

                String owner = resolveClassName(utf8, classNameIndex, memberClassIndex[i]);
                String memberName = resolveUtf8(utf8, nameAndTypeNameIndex[memberNameAndTypeIndex[i]]);
                if (owner == null || memberName == null) {
                    continue;
                }
                if (!"net/minecraft/client/model/geom/ModelLayers".equals(owner)) {
                    continue;
                }

                if (tag == 9) {
                    index.recordField(memberName, category);
                } else {
                    index.recordFactory(memberName, category);
                }
            }
        }

        private static String resolveClassName(String[] utf8, int[] classNameIndex, int entryIndex) {
            if (entryIndex <= 0 || entryIndex >= classNameIndex.length) {
                return null;
            }
            int nameIndex = classNameIndex[entryIndex];
            return resolveUtf8(utf8, nameIndex);
        }

        private static String resolveUtf8(String[] utf8, int index) {
            if (index <= 0 || index >= utf8.length) {
                return null;
            }
            return utf8[index];
        }

        private static final class UsageIndex {
            final Map<String, Usage> fieldUsage = new HashMap<String, Usage>();
            final Map<String, Usage> factoryUsage = new HashMap<String, Usage>();

            void recordField(String name, RendererCategory category) {
                record(this.fieldUsage, name, category);
            }

            void recordFactory(String name, RendererCategory category) {
                record(this.factoryUsage, name, category);
            }

            private static void record(Map<String, Usage> map, String name, RendererCategory category) {
                Usage usage = map.get(name);
                if (usage == null) {
                    usage = new Usage();
                    map.put(name, usage);
                }
                usage.record(category);
            }
        }

        private static final class Usage {
            boolean entity;
            boolean nonEntity;

            void record(RendererCategory category) {
                if (category == RendererCategory.ENTITY) {
                    this.entity = true;
                } else if (category == RendererCategory.NON_ENTITY) {
                    this.nonEntity = true;
                }
            }

            boolean shouldApply() {
                return this.entity || !this.nonEntity;
            }
        }

        private static enum RendererCategory {
            ENTITY,
            NON_ENTITY,
            IGNORE;

            static RendererCategory forClassEntry(String entryName) {
                if (!entryName.startsWith("net/minecraft/client/renderer/")) {
                    return IGNORE;
                }
                if (entryName.startsWith("net/minecraft/client/renderer/entity/")) {
                    return ENTITY;
                }
                if (entryName.startsWith("net/minecraft/client/renderer/blockentity/")
                        || entryName.startsWith("net/minecraft/client/renderer/special/")) {
                    return NON_ENTITY;
                }
                return IGNORE;
            }
        }
    }

    private static final class ReflectionContext {
        final Class<?> modelLayerLocationClass;
        final Class<?> modelLayersClass;
        final Class<?> poseClass;
        final Class<?> cubeClass;
        final Class<?> modelPartVisitorClass;

        final Constructor<?> poseStackCtor;
        final Constructor<?> vector3fCtor;
        final Constructor<?> entityModelSetMapCtor;

        final Method entityModelSetVanillaMethod;
        final Method entityModelSetBakeLayerMethod;
        final Method layerDefinitionsCreateRootsMethod;
        final Method sharedConstantsTryDetectVersionMethod;
        final Method bootstrapMethod;

        final Method modelPartVisitMethod;
        final Method posePoseMethod;
        final Method poseTransformNormalMethod;
        final Method matrixTransformPositionMethod;

        final Method polygonVerticesMethod;
        final Field polygonVerticesField;
        final Method polygonNormalMethod;
        final Field polygonNormalField;

        final Method vertexWorldXMethod;
        final Field vertexWorldXField;
        final Method vertexWorldYMethod;
        final Field vertexWorldYField;
        final Method vertexWorldZMethod;
        final Field vertexWorldZField;
        final Method vertexUMethod;
        final Field vertexUField;
        final Method vertexVMethod;
        final Field vertexVField;

        final Method vector3fXMethod;
        final Field vector3fXField;
        final Method vector3fYMethod;
        final Field vector3fYField;
        final Method vector3fZMethod;
        final Field vector3fZField;

        final Method modelLayerLocationModelMethod;
        final Field modelLayerLocationModelField;
        final Method modelLayerLocationLayerMethod;
        final Field modelLayerLocationLayerField;

        final Method cubePolygonsMethod;
        final Field cubePolygonsField;

        ReflectionContext() throws Exception {
            Class<?> modelLayerLocationClass = Class.forName("net.minecraft.client.model.geom.ModelLayerLocation");
            Class<?> modelLayersClass = Class.forName("net.minecraft.client.model.geom.ModelLayers");
            Class<?> entityModelSetClass = Class.forName("net.minecraft.client.model.geom.EntityModelSet");
            Class<?> layerDefinitionsClass = Class.forName("net.minecraft.client.model.geom.LayerDefinitions");
            Class<?> sharedConstantsClass = Class.forName("net.minecraft.SharedConstants");
            Class<?> bootstrapClass = Class.forName("net.minecraft.server.Bootstrap");
            Class<?> modelPartClass = Class.forName("net.minecraft.client.model.geom.ModelPart");
            Class<?> poseStackClass = Class.forName("com.mojang.blaze3d.vertex.PoseStack");
            Class<?> poseClass = Class.forName("com.mojang.blaze3d.vertex.PoseStack$Pose");
            Class<?> vector3fcClass = Class.forName("org.joml.Vector3fc");
            Class<?> vector3fClass = Class.forName("org.joml.Vector3f");
            Class<?> matrix4fClass = Class.forName("org.joml.Matrix4f");
            Class<?> cubeClass = Class.forName("net.minecraft.client.model.geom.ModelPart$Cube");
            Class<?> polygonClass = Class.forName("net.minecraft.client.model.geom.ModelPart$Polygon");
            Class<?> vertexClass = Class.forName("net.minecraft.client.model.geom.ModelPart$Vertex");

            this.modelLayerLocationClass = modelLayerLocationClass;
            this.modelLayersClass = modelLayersClass;
            this.poseClass = poseClass;
            this.cubeClass = cubeClass;
            this.modelPartVisitorClass = Class.forName("net.minecraft.client.model.geom.ModelPart$Visitor");

            this.poseStackCtor = requireNoArgConstructor(poseStackClass);
            this.vector3fCtor = requireNoArgConstructor(vector3fClass);
            this.entityModelSetMapCtor = findSingleArgConstructor(entityModelSetClass, Map.class);

            Method vanilla = findNoArgMethod(entityModelSetClass, true, "vanilla", "createVanilla");
            if (vanilla == null) {
                vanilla = findStaticFactoryMethod(entityModelSetClass);
            }
            this.entityModelSetVanillaMethod = vanilla;

            this.entityModelSetBakeLayerMethod = requireMethodWithSingleParam(
                entityModelSetClass,
                false,
                modelLayerLocationClass,
                modelPartClass,
                "bakeLayer"
            );

            Method createRoots = findMapFactoryMethod(
                layerDefinitionsClass,
                true,
                "createRoots",
                "roots",
                "buildRoots",
                "create"
            );
            if (createRoots == null) {
                throw new NoSuchMethodException("LayerDefinitions#createRoots compatible method was not found.");
            }
            this.layerDefinitionsCreateRootsMethod = createRoots;

            this.sharedConstantsTryDetectVersionMethod = findNoArgMethod(sharedConstantsClass, true, "tryDetectVersion", "detectVersion");
            Method bootstrap = findNoArgMethod(bootstrapClass, true, "bootStrap", "bootstrap");
            if (bootstrap == null) {
                bootstrap = findMethodWithArity(bootstrapClass, true, 1, "bootStrap", "bootstrap");
            }
            this.bootstrapMethod = bootstrap;

            Method visit = findExactMethod(modelPartClass, "visit", poseStackClass, this.modelPartVisitorClass);
            if (visit == null) {
                visit = findMethodWithArity(modelPartClass, false, 2, "visit");
            }
            if (visit == null) {
                throw new NoSuchMethodException("ModelPart#visit compatible method was not found.");
            }
            this.modelPartVisitMethod = visit;

            this.posePoseMethod = requireNoArgMethod(poseClass, false, "pose", "positionMatrix");

            Method transformNormal = findExactMethod(poseClass, "transformNormal", vector3fcClass, vector3fClass);
            if (transformNormal == null) {
                transformNormal = findExactMethod(poseClass, "transformNormal", vector3fcClass);
            }
            if (transformNormal == null) {
                transformNormal = findMethodWithArity(poseClass, false, 2, "transformNormal");
            }
            if (transformNormal == null) {
                transformNormal = findMethodWithArity(poseClass, false, 1, "transformNormal");
            }
            this.poseTransformNormalMethod = transformNormal;

            Method transformPosition = findExactMethod(
                matrix4fClass,
                "transformPosition",
                Float.TYPE,
                Float.TYPE,
                Float.TYPE,
                vector3fClass
            );
            if (transformPosition == null) {
                transformPosition = findExactMethod(
                    matrix4fClass,
                    "transformPosition",
                    Float.TYPE,
                    Float.TYPE,
                    Float.TYPE
                );
            }
            if (transformPosition == null) {
                transformPosition = findMethodWithArity(matrix4fClass, false, 4, "transformPosition");
            }
            if (transformPosition == null) {
                transformPosition = findMethodWithArity(matrix4fClass, false, 3, "transformPosition");
            }
            if (transformPosition == null) {
                throw new NoSuchMethodException("Matrix4f#transformPosition compatible method was not found.");
            }
            this.matrixTransformPositionMethod = transformPosition;

            this.polygonVerticesMethod = findNoArgMethod(polygonClass, false, "vertices", "getVertices");
            this.polygonVerticesField = findField(polygonClass, "vertices", "vertexes");

            this.polygonNormalMethod = findNoArgMethod(polygonClass, false, "normal", "getNormal");
            this.polygonNormalField = findField(polygonClass, "normal");

            this.vertexWorldXMethod = findFloatGetter(vertexClass, "worldX", "x", "getX");
            this.vertexWorldXField = findFloatField(vertexClass, "worldX", "x");
            this.vertexWorldYMethod = findFloatGetter(vertexClass, "worldY", "y", "getY");
            this.vertexWorldYField = findFloatField(vertexClass, "worldY", "y");
            this.vertexWorldZMethod = findFloatGetter(vertexClass, "worldZ", "z", "getZ");
            this.vertexWorldZField = findFloatField(vertexClass, "worldZ", "z");
            this.vertexUMethod = findFloatGetter(vertexClass, "u", "getU");
            this.vertexUField = findFloatField(vertexClass, "u");
            this.vertexVMethod = findFloatGetter(vertexClass, "v", "getV");
            this.vertexVField = findFloatField(vertexClass, "v");

            this.vector3fXMethod = findFloatGetter(vector3fClass, "x", "getX");
            this.vector3fXField = findFloatField(vector3fClass, "x");
            this.vector3fYMethod = findFloatGetter(vector3fClass, "y", "getY");
            this.vector3fYField = findFloatField(vector3fClass, "y");
            this.vector3fZMethod = findFloatGetter(vector3fClass, "z", "getZ");
            this.vector3fZField = findFloatField(vector3fClass, "z");

            this.modelLayerLocationModelMethod = findNoArgMethod(modelLayerLocationClass, false, "model", "id", "getModel");
            this.modelLayerLocationModelField = findField(modelLayerLocationClass, "model", "id");
            this.modelLayerLocationLayerMethod = findNoArgMethod(modelLayerLocationClass, false, "layer", "getLayer");
            this.modelLayerLocationLayerField = findField(modelLayerLocationClass, "layer");

            this.cubePolygonsMethod = findNoArgMethod(cubeClass, false, "polygons", "getPolygons");
            this.cubePolygonsField = findField(cubeClass, "polygons");

            if (this.entityModelSetVanillaMethod == null && this.entityModelSetMapCtor == null) {
                throw new NoSuchMethodException("No compatible EntityModelSet initializer found.");
            }
            if (this.polygonVerticesMethod == null && this.polygonVerticesField == null) {
                throw new NoSuchMethodException("ModelPart$Polygon vertices accessor was not found.");
            }
            if (this.polygonNormalMethod == null && this.polygonNormalField == null) {
                throw new NoSuchMethodException("ModelPart$Polygon normal accessor was not found.");
            }
            if (this.cubePolygonsMethod == null && this.cubePolygonsField == null) {
                throw new NoSuchMethodException("ModelPart$Cube polygons accessor was not found.");
            }
        }

        void initializeGameData() throws Exception {
            invokeStaticOptional(this.sharedConstantsTryDetectVersionMethod);
            invokeStaticOptional(this.bootstrapMethod);
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> createRoots() throws Exception {
            Object value = invokeWithDefaults(this.layerDefinitionsCreateRootsMethod, null);
            if (!(value instanceof Map)) {
                throw new IllegalStateException("LayerDefinitions#createRoots returned non-map value: " + value);
            }
            return (Map<Object, Object>) value;
        }

        Object createEntityModelSet(Map<Object, Object> roots) throws Exception {
            if (this.entityModelSetVanillaMethod != null) {
                try {
                    Object value = invokeWithDefaults(this.entityModelSetVanillaMethod, null);
                    if (value != null) {
                        return value;
                    }
                } catch (InvocationTargetException ignored) {
                    if (this.entityModelSetMapCtor == null) {
                        throw ignored;
                    }
                }
            }

            if (this.entityModelSetMapCtor != null) {
                return this.entityModelSetMapCtor.newInstance(roots);
            }

            throw new IllegalStateException("No EntityModelSet creation strategy is available.");
        }

        Object bakeLayer(Object entityModelSet, Object location) throws Exception {
            return this.entityModelSetBakeLayerMethod.invoke(entityModelSet, location);
        }

        Object[] getCubePolygons(Object cube) throws Exception {
            Object raw = this.cubePolygonsMethod != null ? this.cubePolygonsMethod.invoke(cube) : this.cubePolygonsField.get(cube);
            return asObjectArray(raw, "cube polygons");
        }

        Object[] getPolygonVertices(Object polygon) throws Exception {
            Object raw = this.polygonVerticesMethod != null ? this.polygonVerticesMethod.invoke(polygon) : this.polygonVerticesField.get(polygon);
            return asObjectArray(raw, "polygon vertices");
        }

        Object getPolygonNormal(Object polygon) throws Exception {
            if (this.polygonNormalMethod != null) {
                return this.polygonNormalMethod.invoke(polygon);
            }
            return this.polygonNormalField.get(polygon);
        }

        Object transformNormal(Object pose, Object normal) throws Exception {
            if (this.poseTransformNormalMethod == null) {
                return normal;
            }

            int params = this.poseTransformNormalMethod.getParameterCount();
            if (params == 2) {
                Object temp = this.vector3fCtor.newInstance();
                Object out = this.poseTransformNormalMethod.invoke(pose, normal, temp);
                return out != null ? out : temp;
            }
            if (params == 1) {
                Object out = this.poseTransformNormalMethod.invoke(pose, normal);
                return out != null ? out : normal;
            }

            Object out = invokeWithDefaults(this.poseTransformNormalMethod, pose);
            return out != null ? out : normal;
        }

        Object transformPosition(Object matrix, float x, float y, float z) throws Exception {
            int params = this.matrixTransformPositionMethod.getParameterCount();
            if (params == 4) {
                Object temp = this.vector3fCtor.newInstance();
                Object out = this.matrixTransformPositionMethod.invoke(
                    matrix,
                    Float.valueOf(x),
                    Float.valueOf(y),
                    Float.valueOf(z),
                    temp
                );
                return out != null ? out : temp;
            }
            if (params == 3) {
                return this.matrixTransformPositionMethod.invoke(
                    matrix,
                    Float.valueOf(x),
                    Float.valueOf(y),
                    Float.valueOf(z)
                );
            }
            return invokeWithDefaults(this.matrixTransformPositionMethod, matrix);
        }

        float getVertexWorldX(Object vertex) throws Exception {
            return readFloat(vertex, this.vertexWorldXMethod, this.vertexWorldXField, "vertex worldX", "worldX", "x", "getX");
        }

        float getVertexWorldY(Object vertex) throws Exception {
            return readFloat(vertex, this.vertexWorldYMethod, this.vertexWorldYField, "vertex worldY", "worldY", "y", "getY");
        }

        float getVertexWorldZ(Object vertex) throws Exception {
            return readFloat(vertex, this.vertexWorldZMethod, this.vertexWorldZField, "vertex worldZ", "worldZ", "z", "getZ");
        }

        float getVertexU(Object vertex) throws Exception {
            return readFloat(vertex, this.vertexUMethod, this.vertexUField, "vertex U", "u", "getU");
        }

        float getVertexV(Object vertex) throws Exception {
            return readFloat(vertex, this.vertexVMethod, this.vertexVField, "vertex V", "v", "getV");
        }

        float getVectorX(Object vector) throws Exception {
            return readFloat(vector, this.vector3fXMethod, this.vector3fXField, "vector X", "x", "getX");
        }

        float getVectorY(Object vector) throws Exception {
            return readFloat(vector, this.vector3fYMethod, this.vector3fYField, "vector Y", "y", "getY");
        }

        float getVectorZ(Object vector) throws Exception {
            return readFloat(vector, this.vector3fZMethod, this.vector3fZField, "vector Z", "z", "getZ");
        }

        Object getLocationModel(Object location) throws Exception {
            return readObject(location, this.modelLayerLocationModelMethod, this.modelLayerLocationModelField, "location model", "model", "id", "getModel");
        }

        Object getLocationLayer(Object location) throws Exception {
            return readObject(location, this.modelLayerLocationLayerMethod, this.modelLayerLocationLayerField, "location layer", "layer", "getLayer");
        }

        private static Object[] asObjectArray(Object value, String label) {
            if (value == null) {
                return new Object[0];
            }
            if (value instanceof Object[]) {
                return (Object[]) value;
            }
            if (value instanceof List) {
                return ((List<?>) value).toArray();
            }
            throw new IllegalStateException("Unsupported " + label + " container type: " + value.getClass().getName());
        }

        private static void invokeStaticOptional(Method method) throws Exception {
            if (method != null) {
                invokeWithDefaults(method, null);
            }
        }

        private static Object invokeWithDefaults(Method method, Object target) throws Exception {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                arguments[i] = defaultReturnValue(parameterTypes[i]);
            }
            return method.invoke(target, arguments);
        }

        private static float readFloat(Object target, Method method, Field field, String label, String... dynamicNames) throws Exception {
            Object value;
            if (method != null) {
                value = method.invoke(target);
                return asFloat(value, label);
            }
            if (field != null) {
                value = field.get(target);
                return asFloat(value, label);
            }

            Method dynamicMethod = findFloatGetter(target.getClass(), dynamicNames);
            if (dynamicMethod != null) {
                return asFloat(dynamicMethod.invoke(target), label);
            }
            Field dynamicField = findFloatField(target.getClass(), dynamicNames);
            if (dynamicField != null) {
                return asFloat(dynamicField.get(target), label);
            }

            throw new NoSuchMethodException("No numeric accessor found for " + label + ".");
        }

        private static Object readObject(Object target, Method method, Field field, String label, String... dynamicNames) throws Exception {
            if (method != null) {
                return method.invoke(target);
            }
            if (field != null) {
                return field.get(target);
            }

            Method dynamicMethod = findNoArgMethod(target.getClass(), false, dynamicNames);
            if (dynamicMethod != null) {
                return dynamicMethod.invoke(target);
            }
            Field dynamicField = findField(target.getClass(), dynamicNames);
            if (dynamicField != null) {
                return dynamicField.get(target);
            }

            throw new NoSuchMethodException("No accessor found for " + label + ".");
        }

        private static float asFloat(Object value, String label) {
            if (value instanceof Number) {
                return ((Number) value).floatValue();
            }
            throw new IllegalStateException("Expected numeric value for " + label + " but got: " + value);
        }

        private static Constructor<?> requireNoArgConstructor(Class<?> owner) throws NoSuchMethodException {
            try {
                Constructor<?> constructor = owner.getConstructor();
                makeAccessible(constructor);
                return constructor;
            } catch (NoSuchMethodException ignored) {
            }
            Constructor<?> constructor = owner.getDeclaredConstructor();
            makeAccessible(constructor);
            return constructor;
        }

        private static Constructor<?> findSingleArgConstructor(Class<?> owner, Class<?> parameterSuperType) {
            for (Constructor<?> constructor : owner.getConstructors()) {
                if (constructor.getParameterCount() != 1) {
                    continue;
                }
                Class<?> argType = constructor.getParameterTypes()[0];
                if (argType.isAssignableFrom(parameterSuperType) || parameterSuperType.isAssignableFrom(argType)) {
                    makeAccessible(constructor);
                    return constructor;
                }
            }
            for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
                if (constructor.getParameterCount() != 1) {
                    continue;
                }
                Class<?> argType = constructor.getParameterTypes()[0];
                if (argType.isAssignableFrom(parameterSuperType) || parameterSuperType.isAssignableFrom(argType)) {
                    makeAccessible(constructor);
                    return constructor;
                }
            }
            return null;
        }

        private static Method requireNoArgMethod(Class<?> owner, boolean requireStatic, String... names) throws NoSuchMethodException {
            Method method = findNoArgMethod(owner, requireStatic, names);
            if (method == null) {
                throw new NoSuchMethodException("No compatible no-arg method found on " + owner.getName());
            }
            return method;
        }

        private static Method requireMethodWithSingleParam(
            Class<?> owner,
            boolean requireStatic,
            Class<?> requiredParamType,
            Class<?> requiredReturnType,
            String... names
        ) throws NoSuchMethodException {
            Method method = findMethodWithSingleParam(owner, requireStatic, requiredParamType, requiredReturnType, names);
            if (method == null) {
                throw new NoSuchMethodException("No compatible single-arg method found on " + owner.getName());
            }
            return method;
        }

        private static Method findExactMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
            try {
                Method method = owner.getMethod(name, parameterTypes);
                makeAccessible(method);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
            try {
                Method method = owner.getDeclaredMethod(name, parameterTypes);
                makeAccessible(method);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
            return null;
        }

        private static Method findNoArgMethod(Class<?> owner, boolean requireStatic, String... names) {
            Method method = findMethodWithArity(owner, requireStatic, 0, names);
            return method;
        }

        private static Method findMethodWithSingleParam(
            Class<?> owner,
            boolean requireStatic,
            Class<?> requiredParamType,
            Class<?> requiredReturnType,
            String... names
        ) {
            Method[] all = allMethods(owner);
            for (String name : names) {
                for (Method method : all) {
                    if (!name.equals(method.getName())) {
                        continue;
                    }
                    if (!isMethodCompatible(method, requireStatic, 1)) {
                        continue;
                    }
                    Class<?> parameterType = method.getParameterTypes()[0];
                    if (!parameterType.isAssignableFrom(requiredParamType) && !requiredParamType.isAssignableFrom(parameterType)) {
                        continue;
                    }
                    if (requiredReturnType != null && !requiredReturnType.isAssignableFrom(method.getReturnType())) {
                        continue;
                    }
                    makeAccessible(method);
                    return method;
                }
            }
            return null;
        }

        private static Method findMethodWithArity(Class<?> owner, boolean requireStatic, int arity, String... names) {
            Method[] all = allMethods(owner);
            for (String name : names) {
                for (Method method : all) {
                    if (!name.equals(method.getName())) {
                        continue;
                    }
                    if (!isMethodCompatible(method, requireStatic, arity)) {
                        continue;
                    }
                    makeAccessible(method);
                    return method;
                }
            }
            return null;
        }

        private static Method findStaticFactoryMethod(Class<?> owner) {
            Method[] all = allMethods(owner);
            for (Method method : all) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (method.getParameterCount() != 0) {
                    continue;
                }
                if (!owner.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                makeAccessible(method);
                return method;
            }
            return null;
        }

        private static Method findMapFactoryMethod(Class<?> owner, boolean requireStatic, String... preferredNames) {
            Method[] all = allMethods(owner);
            for (String preferredName : preferredNames) {
                Method bestNamed = null;
                for (Method method : all) {
                    if (!preferredName.equals(method.getName())) {
                        continue;
                    }
                    if (!isMapFactoryCandidate(method, requireStatic)) {
                        continue;
                    }
                    if (bestNamed == null || method.getParameterCount() < bestNamed.getParameterCount()) {
                        bestNamed = method;
                    }
                }
                if (bestNamed != null) {
                    makeAccessible(bestNamed);
                    return bestNamed;
                }
            }

            Method best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Method method : all) {
                if (!isMapFactoryCandidate(method, requireStatic)) {
                    continue;
                }
                int score = scoreMapFactoryCandidate(method);
                if (best == null || score > bestScore) {
                    best = method;
                    bestScore = score;
                }
            }
            if (best != null) {
                makeAccessible(best);
            }
            return best;
        }

        private static boolean isMapFactoryCandidate(Method method, boolean requireStatic) {
            if (requireStatic && !Modifier.isStatic(method.getModifiers())) {
                return false;
            }
            return Map.class.isAssignableFrom(method.getReturnType());
        }

        private static int scoreMapFactoryCandidate(Method method) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            int score = 0;
            score -= method.getParameterCount() * 10;
            if (name.contains("root")) {
                score += 70;
            }
            if (name.contains("layer")) {
                score += 20;
            }
            if (name.startsWith("create")) {
                score += 15;
            }
            return score;
        }

        private static Method findFloatGetter(Class<?> owner, String... names) {
            Method[] all = allMethods(owner);
            for (String name : names) {
                for (Method method : all) {
                    if (!name.equals(method.getName())) {
                        continue;
                    }
                    if (method.getParameterCount() != 0) {
                        continue;
                    }
                    if (!isNumericType(method.getReturnType())) {
                        continue;
                    }
                    makeAccessible(method);
                    return method;
                }
            }
            return null;
        }

        private static Field findFloatField(Class<?> owner, String... names) {
            Field field = findField(owner, names);
            if (field == null) {
                return null;
            }
            if (!isNumericType(field.getType())) {
                return null;
            }
            return field;
        }

        private static Field findField(Class<?> owner, String... names) {
            Field[] publicFields = owner.getFields();
            for (String name : names) {
                for (Field field : publicFields) {
                    if (name.equals(field.getName())) {
                        makeAccessible(field);
                        return field;
                    }
                }
            }

            Field[] declaredFields = owner.getDeclaredFields();
            for (String name : names) {
                for (Field field : declaredFields) {
                    if (name.equals(field.getName())) {
                        makeAccessible(field);
                        return field;
                    }
                }
            }

            return null;
        }

        private static boolean isMethodCompatible(Method method, boolean requireStatic, int arity) {
            if (method.getParameterCount() != arity) {
                return false;
            }
            if (requireStatic && !Modifier.isStatic(method.getModifiers())) {
                return false;
            }
            return true;
        }

        private static boolean isNumericType(Class<?> type) {
            if (type.isPrimitive()) {
                return type == Float.TYPE || type == Double.TYPE || type == Integer.TYPE || type == Long.TYPE
                    || type == Short.TYPE || type == Byte.TYPE;
            }
            return Number.class.isAssignableFrom(type);
        }

        private static Method[] allMethods(Class<?> owner) {
            Method[] publicMethods = owner.getMethods();
            Method[] declaredMethods = owner.getDeclaredMethods();
            Method[] out = new Method[publicMethods.length + declaredMethods.length];
            System.arraycopy(publicMethods, 0, out, 0, publicMethods.length);
            System.arraycopy(declaredMethods, 0, out, publicMethods.length, declaredMethods.length);
            return out;
        }

        private static void makeAccessible(java.lang.reflect.AccessibleObject object) {
            try {
                object.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class ObjWriter implements AutoCloseable {
        private final PrintWriter objWriter;
        private final PrintWriter mtlWriter;

        private int vertexCount = 0;
        private int uvCount = 0;
        private int normalCount = 0;

        private String currentPart = null;

        ObjWriter(Path objPath, Path mtlPath, String sourceLocation, String textureMapPath, String textureSource) throws IOException {
            this.objWriter = new PrintWriter(Files.newBufferedWriter(objPath, StandardCharsets.UTF_8));
            this.mtlWriter = new PrintWriter(Files.newBufferedWriter(mtlPath, StandardCharsets.UTF_8));

            this.objWriter.println("# Exported by EntityLayerObjExporter");
            this.objWriter.println("# Generated: " + Instant.now().toString());
            this.objWriter.println("# Source layer: " + sourceLocation);
            this.objWriter.println("mtllib " + mtlPath.getFileName().toString());
            this.objWriter.println();

            this.mtlWriter.println("# Exported by EntityLayerObjExporter");
            this.mtlWriter.println("newmtl " + MATERIAL_NAME);
            this.mtlWriter.println("Ka 1.000000 1.000000 1.000000");
            this.mtlWriter.println("Kd 1.000000 1.000000 1.000000");
            this.mtlWriter.println("Ks 0.000000 0.000000 0.000000");
            this.mtlWriter.println("d 1.0");
            this.mtlWriter.println("illum 2");
            if (textureMapPath != null && !textureMapPath.isEmpty()) {
                if (textureSource != null && !textureSource.isEmpty()) {
                    this.mtlWriter.println("# Source texture: " + textureSource);
                }
                this.mtlWriter.println("map_Kd " + textureMapPath);
            } else {
                this.mtlWriter.println("# No texture match found for this layer.");
            }
        }

        void beginPart(String partName) {
            if (partName.equals(this.currentPart)) {
                return;
            }

            this.currentPart = partName;
            this.objWriter.println();
            this.objWriter.println("o " + partName);
            this.objWriter.println("usemtl " + MATERIAL_NAME);
        }

        int writeVertex(float x, float y, float z) {
            x = finiteOrDefault(x, 0.0f);
            y = finiteOrDefault(y, 0.0f);
            z = finiteOrDefault(z, 0.0f);
            this.vertexCount++;
            this.objWriter.printf(Locale.ROOT, "v %.8f %.8f %.8f%n", Float.valueOf(x), Float.valueOf(y), Float.valueOf(z));
            return this.vertexCount;
        }

        int writeTexCoord(float u, float v) {
            u = finiteOrDefault(u, 0.0f);
            v = finiteOrDefault(v, 0.0f);
            this.uvCount++;
            this.objWriter.printf(Locale.ROOT, "vt %.8f %.8f%n", Float.valueOf(u), Float.valueOf(v));
            return this.uvCount;
        }

        int writeNormal(float x, float y, float z) {
            x = finiteOrDefault(x, 0.0f);
            y = finiteOrDefault(y, 0.0f);
            z = finiteOrDefault(z, 0.0f);
            this.normalCount++;
            this.objWriter.printf(Locale.ROOT, "vn %.8f %.8f %.8f%n", Float.valueOf(x), Float.valueOf(y), Float.valueOf(z));
            return this.normalCount;
        }

        void writeFace(int[] vertices, int[] uvs, int normalIndex, boolean reverseWinding) {
            if (vertices.length != uvs.length) {
                throw new IllegalArgumentException("Vertex and UV index count mismatch.");
            }

            StringBuilder builder = new StringBuilder("f");
            for (int i = 0; i < vertices.length; i++) {
                int index = reverseWinding ? (vertices.length - 1 - i) : i;
                builder.append(' ')
                    .append(vertices[index])
                    .append('/')
                    .append(uvs[index])
                    .append('/')
                    .append(normalIndex);
            }
            this.objWriter.println(builder.toString());
        }

        @Override
        public void close() {
            this.objWriter.close();
            this.mtlWriter.close();
        }
    }
}
