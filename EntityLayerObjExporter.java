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

        List<Object> locations = new ArrayList<Object>(roots.keySet());
        locations.sort(new Comparator<Object>() {
            @Override
            public int compare(Object a, Object b) {
                return a.toString().compareTo(b.toString());
            }
        });

        int exported = 0;
        int failed = 0;
        int index = 0;

        TextureResolver textureResolver = null;
        try {
            if (config.clientJarPath != null) {
                textureResolver = new TextureResolver(config.clientJarPath, config.outputDir);
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

                    try (ObjWriter writer = new ObjWriter(objPath, mtlPath, location.toString(), textureMapPath, textureSource)) {
                        Object rootPart = ctx.bakeLayer(entityModelSet, location);
                        exportModel(ctx, rootPart, writer, config);
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
            "Done. Exported: %d, Failed: %d, Output: %s%n",
            Integer.valueOf(exported),
            Integer.valueOf(failed),
            config.outputDir.toAbsolutePath().toString()
        );

        if (failed > 0) {
            System.exit(2);
        }
    }

    private static void exportModel(final ReflectionContext ctx, Object rootPart, final ObjWriter writer, final Config config)
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
                    int resolvedIndex;
                    if (cubeIndex != null && cubeIndex.intValue() >= 0) {
                        resolvedIndex = cubeIndex.intValue();
                    } else {
                        Integer next = cubeCountersByPart.get(normalizedPath);
                        resolvedIndex = next != null ? next.intValue() : 0;
                        cubeCountersByPart.put(normalizedPath, Integer.valueOf(resolvedIndex + 1));
                    }
                    partPath = String.format(Locale.ROOT, "%s.cube_%03d", normalizedPath, Integer.valueOf(resolvedIndex));
                }

                writer.beginPart(sanitizeObjName(partPath));
                exportCube(ctx, pose, cube, writer, config);
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

    private static void exportCube(ReflectionContext ctx, Object pose, Object cube, ObjWriter writer, Config config)
            throws Exception {
        Object matrix = ctx.posePoseMethod.invoke(pose);
        Object[] polygons = ctx.getCubePolygons(cube);
        float signX = config.applyRuntimeOrientation ? -1.0f : 1.0f;
        float signY = config.applyRuntimeOrientation ? -1.0f : 1.0f;
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

                float u = ctx.getVertexU(vertex);
                float v = ctx.getVertexV(vertex);
                if (config.flipV) {
                    v = 1.0f - v;
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
        final float scale;

        Config(
            Path outputDir,
            Path clientJarPath,
            boolean applyRuntimeOrientation,
            boolean liftToGrid,
            boolean flipV,
            boolean flipZ,
            boolean splitCubes,
            float scale
        ) {
            this.outputDir = outputDir;
            this.clientJarPath = clientJarPath;
            this.applyRuntimeOrientation = applyRuntimeOrientation;
            this.liftToGrid = liftToGrid;
            this.flipV = flipV;
            this.flipZ = flipZ;
            this.splitCubes = splitCubes;
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

            return new Config(outputDir, clientJarPath, applyRuntimeOrientation, liftToGrid, flipV, flipZ, splitCubes, scale);
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
            System.out.println("  java EntityLayerObjExporter --out <outputDir> [--client-jar <clientJar>] [--runtime-orientation true|false] [--lift-to-grid true|false] [--flip-v true|false] [--flip-z true|false] [--split-cubes true|false] [--scale <float>]");
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
            "_eyes",
            "eyes",
            "_armor",
            "armor",
            "_saddle",
            "saddle",
            "_pattern",
            "pattern",
            "_overlay",
            "overlay",
            "_glow",
            "glow",
            "_emissive",
            "emissive",
            "_wind",
            "wind",
            "_heart",
            "heart",
            "_tendrils",
            "tendrils",
            "_collar",
            "collar",
            "_harness",
            "harness",
            "_ropes",
            "ropes",
            "_spots",
            "spots",
            "_undercoat",
            "undercoat"
        };

        private final ZipFile zipFile;
        private final Path outputDir;
        private final List<String> textureEntries;
        private final Map<String, ResolvedTexture> cache;
        private final Set<String> extracted;

        TextureResolver(Path clientJar, Path outputDir) throws IOException {
            this.zipFile = new ZipFile(clientJar.toFile());
            this.outputDir = outputDir;
            this.textureEntries = new ArrayList<String>();
            this.cache = new HashMap<String, ResolvedTexture>();
            this.extracted = new HashSet<String>();

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
                if (!lower.contains("/textures/entity/") && !lower.contains("/textures/models/armor/")) {
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

        private ResolvedTexture resolve(LocationInfo info) {
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

            LinkedHashSet<String> names = new LinkedHashSet<String>();
            addName(names, flatModel);
            addName(names, lastSegment);
            addName(names, canonicalFlat);
            addName(names, canonicalLast);

            if (!"main".equals(layerLower)) {
                addName(names, flatModel + "_" + layerLower);
                addName(names, lastSegment + "_" + layerLower);
                addName(names, canonicalFlat + "_" + layerLower);
                addName(names, canonicalLast + "_" + layerLower);
            }

            Set<String> modelTokens = tokenize(flatModel);
            modelTokens.addAll(tokenize(lastSegment));
            if (!canonicalFlat.equals(flatModel)) {
                modelTokens.addAll(tokenize(canonicalFlat));
            }

            Set<String> layerTokens = tokenize(layerLower);

            int bestScore = Integer.MIN_VALUE;
            String bestEntry = null;

            for (String entry : this.textureEntries) {
                String lower = entry.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("assets/" + namespaceLower + "/")) {
                    continue;
                }

                int score = scoreEntry(
                    lower,
                    modelPathLower,
                    layerLower,
                    names,
                    modelTokens,
                    layerTokens,
                    flatModel,
                    canonicalFlat
                );

                if (score > bestScore) {
                    bestScore = score;
                    bestEntry = entry;
                }
            }

            if (bestEntry == null || bestScore < 120) {
                return null;
            }

            String lowerBest = bestEntry.toLowerCase(Locale.ROOT);
            int texturesIndex = lowerBest.indexOf("/textures/");
            if (texturesIndex < 0) {
                return null;
            }

            String subPath = bestEntry.substring(texturesIndex + "/textures/".length());
            Path extractedPath = this.outputDir.resolve("textures").resolve(subPath.replace('/', java.io.File.separatorChar));
            String mapKd = toForwardSlashes(this.outputDir.relativize(extractedPath).toString());

            return new ResolvedTexture(bestEntry, extractedPath, mapKd);
        }
        private static int scoreEntry(
            String entry,
            String modelPathLower,
            String layerLower,
            Set<String> names,
            Set<String> modelTokens,
            Set<String> layerTokens,
            String flatModel,
            String canonicalFlat
        ) {
            int score = 0;

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

            String fileName = extractFileNameWithoutExtension(entry);
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
                if (token.length() > 2 && entry.contains(token)) {
                    score += 7;
                    matchedModelTokens++;
                }
            }

            if (!modelTokens.isEmpty() && matchedModelTokens == modelTokens.size()) {
                score += 70;
            }

            if (layerLower.contains("baby")) {
                if (entry.contains("baby")) {
                    score += 35;
                }
            } else if (entry.contains("baby")) {
                score -= 20;
            }

            if ("main".equals(layerLower)) {
                for (String hint : MAIN_LAYER_PENALTY_HINTS) {
                    if (entry.contains(hint)) {
                        score -= 70;
                    }
                }
            } else {
                for (String token : layerTokens) {
                    if (token.length() > 2 && entry.contains(token)) {
                        score += 40;
                    }
                }

                if (layerLower.contains("armor") || layerLower.contains("boots") || layerLower.contains("leggings")
                        || layerLower.contains("chestplate") || layerLower.contains("helmet")) {
                    if (entry.contains("/textures/models/armor/")) {
                        score += 90;
                    }
                }
            }

            return score;
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

    private static final class ReflectionContext {
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

            Method createRoots = findNoArgMethod(layerDefinitionsClass, true, "createRoots");
            if (createRoots == null) {
                createRoots = findNoArgMapMethod(layerDefinitionsClass, true);
            }
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

        private static Method findNoArgMapMethod(Class<?> owner, boolean requireStatic) {
            Method[] all = allMethods(owner);
            for (Method method : all) {
                if (!isMethodCompatible(method, requireStatic, 0)) {
                    continue;
                }
                if (!Map.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                makeAccessible(method);
                return method;
            }
            return null;
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
            this.vertexCount++;
            this.objWriter.printf(Locale.ROOT, "v %.8f %.8f %.8f%n", Float.valueOf(x), Float.valueOf(y), Float.valueOf(z));
            return this.vertexCount;
        }

        int writeTexCoord(float u, float v) {
            this.uvCount++;
            this.objWriter.printf(Locale.ROOT, "vt %.8f %.8f%n", Float.valueOf(u), Float.valueOf(v));
            return this.uvCount;
        }

        int writeNormal(float x, float y, float z) {
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
