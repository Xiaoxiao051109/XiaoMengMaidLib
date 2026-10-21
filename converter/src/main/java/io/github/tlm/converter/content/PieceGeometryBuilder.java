package io.github.tlm.converter.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.tlm.converter.bedrock.BedrockGeometry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 棋子的立体造型：把原模型里的一棵骨骼子树烘焙成 Java 物品模型。
 *
 * <p>象棋棋子不是一块立方体，而是「一块文字贴图面 + 一圈圈小立方体」拼出来的：
 * 那些小立方体靠骨骼旋转按 45 度步进，围成棋子的圆柱轮廓。Java 物品模型的元素
 * 只能带单轴、正负 45 度以内的旋转，表达不了任意角度，所以这里把变换拆开：
 *
 * <ul>
 *   <li>先用骨骼链把立方体的八个角算到模型空间的真实位置；</li>
 *   <li>再把累积的 Y 轴旋转拆成「90 度的整数倍」与「正负 45 度的余量」——
 *       90 度的整数倍作用在轴对齐盒子上仍然是轴对齐盒子，直接烘进包围盒；
 *       余量交给元素的 {@code rotation} 字段。</li>
 * </ul>
 *
 * <p>非 90 倍数的倾斜（原模型里那些 10 度）会被吸收进包围盒，元素会略胖一点，
 * 在棋子这个尺度上（一格 0.3 格宽）看不出来。
 */
final class PieceGeometryBuilder {

    /** 平面立方体（某个轴尺寸为 0）的厚度：给一点厚度才看得见。 */
    private static final float FLAT_THICKNESS = 0.2f;

    /** 贴图边长（像素）：由调用方按模型声明值设置。 */
    static int textureSize = 512;

    /** 打开后会把每个立方体的烘焙结果打到控制台，用于核对变换。 */
    static boolean DEBUG = false;

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private PieceGeometryBuilder() {
    }

    /** 一个立方体连同它所在的骨骼链（根在前、所在骨骼在后）。 */
    private record Entry(List<BedrockGeometry.Bone> path, BedrockGeometry.Cube cube) {
    }

    /** 烘焙结果：轴对齐包围盒 + 还需要补回来的 Y 旋转。 */
    private record Baked(float[] lo, float[] hi, float[] center, float angle) {
    }

    /**
     * @param geometry   原模型
     * @param rootBone   棋子对应的根骨骼名（如 {@code ShuaiRed}）
     * @param textureRef 贴图引用（如 {@code touhou_little_maid:bedrock/block/cchess_pieces}）
     * @param render     棋子整体在 XZ 平面的目标跨度（格）；传 0 表示保持原模型的自然尺寸
     * @return Java 物品模型；找不到骨骼或子树为空时返回 null
     */
    static JsonObject build(BedrockGeometry geometry, String rootBone, String textureRef, double render) {
        BedrockGeometry.Bone root = find(geometry, rootBone);
        if (root == null) {
            return null;
        }
        List<Entry> entries = new ArrayList<>();
        collect(root, new ArrayList<>(), childrenOf(geometry), entries);
        if (entries.isEmpty()) {
            return null;
        }

        List<Baked> baked = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            baked.add(bake(entry));
        }
        if (DEBUG && "ShuaiRed".equals(rootBone)) {
            for (int i = 0; i < baked.size(); i++) {
                Baked b = baked.get(i);
                System.out.println("  [烘焙] 元素" + i
                        + " x " + fmt(b.lo()[0]) + ".." + fmt(b.hi()[0])
                        + "  y " + fmt(b.lo()[1]) + ".." + fmt(b.hi()[1])
                        + "  z " + fmt(b.lo()[2]) + ".." + fmt(b.hi()[2])
                        + "  rot=" + fmt(b.angle()));
            }
        }

        // 整体包围盒 → 缩放与落位：XZ 居中、底面贴地（y=0），这样 display 的原点就在棋子脚下
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Baked box : baked) {
            merge(lo, hi, box.lo(), box.hi());
        }
        double extent = Math.max(hi[0] - lo[0], hi[2] - lo[2]);
        // 16 模型单位 = 1 格（ItemDisplay 的 NONE 变换下）。
        // render 为 0 时不缩放：原模型自己就是这个尺寸，缩了反而和外圈轮廓对不上
        // （文字面只是棋子顶上的那一块，整个棋子的外圈比它宽）。
        double scale = render <= 0 || extent <= 1e-6 ? 1 : render * 16 / extent;
        double centerX = (lo[0] + hi[0]) / 2.0;
        double centerZ = (lo[2] + hi[2]) / 2.0;
        double baseY = lo[1];

        JsonArray elements = new JsonArray();
        for (int i = 0; i < baked.size(); i++) {
            elements.add(element(baked.get(i), entries.get(i).cube(), scale, centerX, baseY, centerZ));
        }

        JsonObject textures = new JsonObject();
        textures.addProperty("0", textureRef);
        textures.addProperty("particle", textureRef);
        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        return model;
    }

    // ------------------------------------------------------------------ 树

    private static BedrockGeometry.Bone find(BedrockGeometry geometry, String name) {
        for (BedrockGeometry.Bone bone : geometry.bones()) {
            if (bone.name().equals(name)) {
                return bone;
            }
        }
        return null;
    }

    /** 父骨骼名 → 子骨骼列表（根骨骼的父为 null）。 */
    private static Map<String, List<BedrockGeometry.Bone>> childrenOf(BedrockGeometry geometry) {
        Map<String, List<BedrockGeometry.Bone>> map = new LinkedHashMap<>();
        for (BedrockGeometry.Bone bone : geometry.bones()) {
            if (bone.parent() != null) {
                map.computeIfAbsent(bone.parent(), key -> new ArrayList<>()).add(bone);
            }
        }
        return map;
    }

    private static void collect(BedrockGeometry.Bone bone, List<BedrockGeometry.Bone> path,
                                Map<String, List<BedrockGeometry.Bone>> children, List<Entry> out) {
        List<BedrockGeometry.Bone> next = new ArrayList<>(path);
        next.add(bone);
        for (BedrockGeometry.Cube cube : bone.cubes()) {
            out.add(new Entry(next, cube));
        }
        for (BedrockGeometry.Bone child : children.getOrDefault(bone.name(), List.of())) {
            collect(child, next, children, out);
        }
    }

    // ------------------------------------------------------------------ 烘焙

    private static Baked bake(Entry entry) {
        BedrockGeometry.Cube cube = entry.cube();

        // 该立方体累积的 Y 轴旋转：骨骼链上各骨骼的 Y 分量 + 立方体自身的
        float totalY = 0;
        for (BedrockGeometry.Bone bone : entry.path()) {
            if (bone.rotation() != null) {
                totalY += bone.rotation()[1];
            }
        }
        if (cube.rotation() != null) {
            totalY += cube.rotation()[1];
        }
        float angle = remainder(totalY);

        // 八个角变换到模型空间
        float[][] corners = corners(cube);
        for (int i = 0; i < corners.length; i++) {
            corners[i] = applyChain(corners[i], entry.path(), cube);
        }
        float[] center = centroid(corners);

        // 把「余量」反着转掉，剩下含 90 度整数倍的朝向再取轴对齐包围盒
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] corner : corners) {
            float[] point = rotateYAbout(corner, center, -angle);
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(lo[i], point[i]);
                hi[i] = Math.max(hi[i], point[i]);
            }
        }
        return new Baked(lo, hi, center, angle);
    }

    /**
     * 取出还要交给元素 {@code rotation} 的那部分 Y 旋转（落在 -45~45）。
     *
     * <p>先归一到 -180~180，再减掉最近的 90 度整数倍 —— 减掉的那部分已经烘进包围盒了。
     */
    static float remainder(float degrees) {
        float d = degrees % 360f;
        if (d > 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d - Math.round(d / 90f) * 90f;
    }

    /** 立方体（含 inflate）的八个角；某个轴尺寸为 0 时补一点厚度。 */
    private static float[][] corners(BedrockGeometry.Cube cube) {
        float[] origin = cube.origin();
        float[] size = cube.size();
        float inflate = cube.inflate();
        float[] lo = new float[3];
        float[] hi = new float[3];
        for (int i = 0; i < 3; i++) {
            float a = origin[i] - inflate;
            float b = origin[i] + size[i] + inflate;
            lo[i] = Math.min(a, b);
            hi[i] = Math.max(a, b);
            if (Math.abs(size[i]) < 1e-4f) {
                hi[i] = lo[i] + FLAT_THICKNESS;
            }
        }
        float[][] out = new float[8][3];
        for (int i = 0; i < 8; i++) {
            out[i][0] = (i & 1) == 0 ? lo[0] : hi[0];
            out[i][1] = (i & 2) == 0 ? lo[1] : hi[1];
            out[i][2] = (i & 4) == 0 ? lo[2] : hi[2];
        }
        return out;
    }

    /**
     * 沿骨骼链把点变换到模型空间。
     *
     * <p>顺序是**从叶子往根**：立方体的顶点处在最深那根骨骼的局部空间里，
     * 它自己的旋转最先作用，然后逐级被祖先的旋转带动
     * （{@code v_model = M_root ∘ … ∘ M_leaf ∘ v}，最内层先算）。
     * 反过来写会把子骨骼的旋转绕到父骨骼外面去，棋子会被压扁。
     */
    private static float[] applyChain(float[] point, List<BedrockGeometry.Bone> path,
                                     BedrockGeometry.Cube cube) {
        float[] result = {point[0], point[1], point[2]};
        for (int i = path.size() - 1; i >= 0; i--) {
            BedrockGeometry.Bone bone = path.get(i);
            if (bone.rotation() == null) {
                continue;
            }
            float[] pivot = bone.pivot() != null ? bone.pivot() : new float[3];
            result = rotate(result, pivot, bone.rotation());
        }
        // 立方体自带的旋转最后施加（绕它自己的枢轴，缺省用所在骨骼的枢轴）
        if (cube.rotation() != null) {
            float[] pivot = cube.pivot() != null ? cube.pivot()
                    : (path.get(path.size() - 1).pivot() != null
                            ? path.get(path.size() - 1).pivot() : new float[3]);
            result = rotate(result, pivot, cube.rotation());
        }
        return result;
    }

    // ------------------------------------------------------------------ 向量

    /** 绕枢轴按 X→Y→Z 旋转一个点（Bedrock 骨骼的旋转顺序）。 */
    private static float[] rotate(float[] point, float[] pivot, float[] degrees) {
        float[] v = {point[0] - pivot[0], point[1] - pivot[1], point[2] - pivot[2]};
        v = rotateX(v, degrees[0]);
        v = rotateY(v, degrees[1]);
        v = rotateZ(v, degrees[2]);
        return new float[]{v[0] + pivot[0], v[1] + pivot[1], v[2] + pivot[2]};
    }

    private static float[] rotateX(float[] v, float degrees) {
        if (degrees == 0) {
            return v;
        }
        double r = Math.toRadians(degrees);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        return new float[]{v[0], (float) (v[1] * cos - v[2] * sin), (float) (v[1] * sin + v[2] * cos)};
    }

    private static float[] rotateY(float[] v, float degrees) {
        if (degrees == 0) {
            return v;
        }
        double r = Math.toRadians(degrees);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        return new float[]{(float) (v[0] * cos + v[2] * sin), v[1], (float) (-v[0] * sin + v[2] * cos)};
    }

    private static float[] rotateZ(float[] v, float degrees) {
        if (degrees == 0) {
            return v;
        }
        double r = Math.toRadians(degrees);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        return new float[]{(float) (v[0] * cos - v[1] * sin), (float) (v[0] * sin + v[1] * cos), v[2]};
    }

    /** 绕经过 {@code pivot} 的 Y 轴旋转。 */
    private static float[] rotateYAbout(float[] point, float[] pivot, float degrees) {
        float[] local = {point[0] - pivot[0], point[1] - pivot[1], point[2] - pivot[2]};
        float[] out = rotateY(local, degrees);
        return new float[]{out[0] + pivot[0], out[1] + pivot[1], out[2] + pivot[2]};
    }

    private static float[] centroid(float[][] corners) {
        float[] sum = new float[3];
        for (float[] corner : corners) {
            for (int i = 0; i < 3; i++) {
                sum[i] += corner[i];
            }
        }
        for (int i = 0; i < 3; i++) {
            sum[i] /= corners.length;
        }
        return sum;
    }

    private static void merge(float[] lo, float[] hi, float[] otherLo, float[] otherHi) {
        for (int i = 0; i < 3; i++) {
            lo[i] = Math.min(lo[i], otherLo[i]);
            hi[i] = Math.max(hi[i], otherHi[i]);
        }
    }

    // ------------------------------------------------------------------ 输出

    private static JsonObject element(Baked box, BedrockGeometry.Cube cube, double scale,
                                      double centerX, double baseY, double centerZ) {
        JsonObject element = new JsonObject();
        element.add("from", vector(
                8 + (box.lo()[0] - centerX) * scale,
                (box.lo()[1] - baseY) * scale,
                8 + (box.lo()[2] - centerZ) * scale));
        element.add("to", vector(
                8 + (box.hi()[0] - centerX) * scale,
                (box.hi()[1] - baseY) * scale,
                8 + (box.hi()[2] - centerZ) * scale));
        element.add("faces", faces(cube));
        if (Math.abs(box.angle()) > 1e-3) {
            JsonObject rotation = new JsonObject();
            rotation.add("origin", vector(
                    8 + (box.center()[0] - centerX) * scale,
                    (box.center()[1] - baseY) * scale,
                    8 + (box.center()[2] - centerZ) * scale));
            rotation.addProperty("axis", "y");
            rotation.addProperty("angle", round(box.angle()));
            rotation.addProperty("rescaled", false);
            element.add("rotation", rotation);
        }
        return element;
    }

    /**
     * Box UV → 逐面 UV（与 {@code BbModelWriter#boxUv} 同一套展开），换算到 Java 模型的 0~16 空间。
     *
     * <p>UV 按**原始立方体尺寸**展开：inflate 只改几何、不改贴图布局。
     */
    private static JsonObject faces(BedrockGeometry.Cube cube) {
        JsonObject out = new JsonObject();
        // faceUv 在「模型用的是 Box UV」时为 null（见 BedrockGeometry#parse）
        Map<String, float[]> perFace = cube.faceUv();
        if (perFace != null && !perFace.isEmpty()) {
            for (Map.Entry<String, float[]> entry : perFace.entrySet()) {
                JsonObject face = new JsonObject();
                JsonArray uv = new JsonArray();
                for (float value : entry.getValue()) {
                    uv.add(round(value));
                }
                face.add("uv", uv);
                face.addProperty("texture", "#0");
                out.add(entry.getKey(), face);
            }
            return out;
        }
        double u = cube.uv() == null ? 0 : cube.uv()[0];
        double v = cube.uv() == null ? 0 : cube.uv()[1];
        // 展开按原始尺寸取整（贴图是按毛坯尺寸切的）
        double w = Math.abs(Math.round(cube.size()[0]));
        double h = Math.abs(Math.round(cube.size()[1]));
        double d = Math.abs(Math.round(cube.size()[2]));

        Map<String, double[]> rects = new LinkedHashMap<>();
        rects.put("north", new double[]{u + d, v + d, u + d + w, v + d + h});
        rects.put("east", new double[]{u, v + d, u + d, v + d + h});
        rects.put("south", new double[]{u + d + w + d, v + d, u + d + w + d + w, v + d + h});
        rects.put("west", new double[]{u + d + w, v + d, u + d + w + d, v + d + h});
        rects.put("up", new double[]{u + d + w, v + d, u + d, v});
        rects.put("down", new double[]{u + d + w + w, v, u + d + w, v + d});

        // 贴图像素 → Java 模型 UV（整张贴图是 0~16）
        double k = 16.0 / Math.max(1, textureSize);
        for (Map.Entry<String, double[]> entry : rects.entrySet()) {
            JsonObject face = new JsonObject();
            JsonArray uv = new JsonArray();
            for (double value : entry.getValue()) {
                uv.add(round(Math.max(0, Math.min(16, value * k))));
            }
            face.add("uv", uv);
            face.addProperty("texture", "#0");
            out.add(entry.getKey(), face);
        }
        return out;
    }

    private static JsonArray vector(double x, double y, double z) {
        JsonArray out = new JsonArray();
        out.add(round(x));
        out.add(round(y));
        out.add(round(z));
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
