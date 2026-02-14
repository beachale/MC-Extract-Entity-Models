import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

        ctx.sharedConstantsTryDetectVersionMethod.invoke(null);
        ctx.bootstrapMethod.invoke(null);

        Object entityModelSet = ctx.entityModelSetVanillaMethod.invoke(null);
        @SuppressWarnings("unchecked")
        Map<Object, Object> roots = (Map<Object, Object>) ctx.layerDefinitionsCreateRootsMethod.invoke(null);

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
                        Object rootPart = ctx.entityModelSetBakeLayerMethod.invoke(entityModelSet, location);
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

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                if (!"visit".equals(method.getName()) || arguments == null || arguments.length != 4) {
                    return null;
                }

                Object pose = arguments[0];
                String path = (String) arguments[1];
                Object cube = arguments[3];

                writer.beginPart(sanitizeObjName(normalizePartPath(path)));
                exportCube(ctx, pose, cube, writer, config);
                return null;
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
        Object[] polygons = (Object[]) ctx.cubePolygonsField.get(cube);
        float signX = config.applyRuntimeOrientation ? -1.0f : 1.0f;
        float signY = config.applyRuntimeOrientation ? -1.0f : 1.0f;
        float signZ = config.flipZ ? -1.0f : 1.0f;
        boolean reverseWinding = signX * signY * signZ < 0.0f;

        for (Object polygon : polygons) {
            Object normal = ctx.polygonNormalMethod.invoke(polygon);
            Object tempNormal = ctx.vector3fCtor.newInstance();
            Object transformedNormal = ctx.poseTransformNormalMethod.invoke(pose, normal, tempNormal);

            float normalX = invokeFloat(ctx.vector3fXMethod, transformedNormal) * signX;
            float normalY = invokeFloat(ctx.vector3fYMethod, transformedNormal) * signY;
            float normalZ = invokeFloat(ctx.vector3fZMethod, transformedNormal) * signZ;
            int normalIndex = writer.writeNormal(normalX, normalY, normalZ);

            Object[] vertices = (Object[]) ctx.polygonVerticesMethod.invoke(polygon);
            int[] vertexIndices = new int[vertices.length];
            int[] uvIndices = new int[vertices.length];

            for (int i = 0; i < vertices.length; i++) {
                Object vertex = vertices[i];

                float worldX = invokeFloat(ctx.vertexWorldXMethod, vertex);
                float worldY = invokeFloat(ctx.vertexWorldYMethod, vertex);
                float worldZ = invokeFloat(ctx.vertexWorldZMethod, vertex);

                Object tempPosition = ctx.vector3fCtor.newInstance();
                Object transformedPosition = ctx.matrixTransformPositionMethod.invoke(
                    matrix,
                    Float.valueOf(worldX),
                    Float.valueOf(worldY),
                    Float.valueOf(worldZ),
                    tempPosition
                );

                float x = invokeFloat(ctx.vector3fXMethod, transformedPosition);
                float y = invokeFloat(ctx.vector3fYMethod, transformedPosition);
                float z = invokeFloat(ctx.vector3fZMethod, transformedPosition);

                x *= signX;
                y *= signY;
                z *= signZ;

                vertexIndices[i] = writer.writeVertex(x * config.scale, y * config.scale, z * config.scale);

                float u = invokeFloat(ctx.vertexUMethod, vertex);
                float v = invokeFloat(ctx.vertexVMethod, vertex);
                if (config.flipV) {
                    v = 1.0f - v;
                }

                uvIndices[i] = writer.writeTexCoord(u, v);
            }

            writer.writeFace(vertexIndices, uvIndices, normalIndex, reverseWinding);
        }
    }

    private static float invokeFloat(Method method, Object target)
            throws IllegalAccessException, InvocationTargetException {
        return ((Float) method.invoke(target)).floatValue();
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
            throws IllegalAccessException, InvocationTargetException {
        Object modelId = ctx.modelLayerLocationModelMethod.invoke(location);
        String modelString = String.valueOf(modelId);
        String layer = String.valueOf(ctx.modelLayerLocationLayerMethod.invoke(location));

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
        final float scale;

        Config(Path outputDir, Path clientJarPath, boolean applyRuntimeOrientation, boolean liftToGrid, boolean flipV, boolean flipZ, float scale) {
            this.outputDir = outputDir;
            this.clientJarPath = clientJarPath;
            this.applyRuntimeOrientation = applyRuntimeOrientation;
            this.liftToGrid = liftToGrid;
            this.flipV = flipV;
            this.flipZ = flipZ;
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

            return new Config(outputDir, clientJarPath, applyRuntimeOrientation, liftToGrid, flipV, flipZ, scale);
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
            System.out.println("  java EntityLayerObjExporter --out <outputDir> [--client-jar <clientJar>] [--runtime-orientation true|false] [--lift-to-grid true|false] [--flip-v true|false] [--flip-z true|false] [--scale <float>]");
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
        final Class<?> modelPartVisitorClass;

        final Constructor<?> poseStackCtor;
        final Constructor<?> vector3fCtor;

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
        final Method polygonNormalMethod;

        final Method vertexWorldXMethod;
        final Method vertexWorldYMethod;
        final Method vertexWorldZMethod;
        final Method vertexUMethod;
        final Method vertexVMethod;

        final Method vector3fXMethod;
        final Method vector3fYMethod;
        final Method vector3fZMethod;

        final Method modelLayerLocationModelMethod;
        final Method modelLayerLocationLayerMethod;

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

            this.modelPartVisitorClass = Class.forName("net.minecraft.client.model.geom.ModelPart$Visitor");

            this.poseStackCtor = poseStackClass.getConstructor();
            this.vector3fCtor = vector3fClass.getConstructor();

            this.entityModelSetVanillaMethod = entityModelSetClass.getMethod("vanilla");
            this.entityModelSetBakeLayerMethod = entityModelSetClass.getMethod("bakeLayer", modelLayerLocationClass);
            this.layerDefinitionsCreateRootsMethod = layerDefinitionsClass.getMethod("createRoots");
            this.sharedConstantsTryDetectVersionMethod = sharedConstantsClass.getMethod("tryDetectVersion");
            this.bootstrapMethod = bootstrapClass.getMethod("bootStrap");

            this.modelPartVisitMethod = modelPartClass.getMethod("visit", poseStackClass, this.modelPartVisitorClass);
            this.posePoseMethod = poseClass.getMethod("pose");
            this.poseTransformNormalMethod = poseClass.getMethod("transformNormal", vector3fcClass, vector3fClass);
            this.matrixTransformPositionMethod = matrix4fClass.getMethod(
                "transformPosition",
                Float.TYPE,
                Float.TYPE,
                Float.TYPE,
                vector3fClass
            );

            this.polygonVerticesMethod = polygonClass.getMethod("vertices");
            this.polygonNormalMethod = polygonClass.getMethod("normal");

            this.vertexWorldXMethod = vertexClass.getMethod("worldX");
            this.vertexWorldYMethod = vertexClass.getMethod("worldY");
            this.vertexWorldZMethod = vertexClass.getMethod("worldZ");
            this.vertexUMethod = vertexClass.getMethod("u");
            this.vertexVMethod = vertexClass.getMethod("v");

            this.vector3fXMethod = vector3fClass.getMethod("x");
            this.vector3fYMethod = vector3fClass.getMethod("y");
            this.vector3fZMethod = vector3fClass.getMethod("z");

            this.modelLayerLocationModelMethod = modelLayerLocationClass.getMethod("model");
            this.modelLayerLocationLayerMethod = modelLayerLocationClass.getMethod("layer");

            this.cubePolygonsField = cubeClass.getField("polygons");
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
            this.objWriter.println("g " + partName);
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
