package io.github.tlm.converter.molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 把关键帧数据点改写成 BetterModel（moonflower molang-compiler）能解析的形式。
 *
 * <p>实机日志里观察到的四类失败，全部在这里处理：
 * <ul>
 *   <li>二元条件 {@code a ? b} 与结尾分号 → 补齐为 {@code a ? b : 0}、去掉语句分隔；</li>
 *   <li>超长小数（如 {@code -91.9271241135}）→ 截到 5 位小数；</li>
 *   <li>{@code -(...)} → {@code (-1 * ...)}；</li>
 *   <li>{@code ysm.*}、{@code tlm.*} 等 BetterModel 不认识的对象 → 替换为常量，
 *       未知函数取「目标值」参数近似（{@code ysm.second_order(name, target, ...)} 取 target）。</li>
 * </ul>
 * 被替换掉的引用会被记录，转换结束时汇总输出，供后续在运行时用程序化动画补回。
 */
public final class MolangNormalizer {

    /** BetterModel 运行时可求值的引用；其余一律视为不支持。 */
    private static final Set<String> SUPPORTED_REFS = Set.of(
            // 实机日志确认 query.anim_time 可求值；其余查询未验证，一律折算
            "query.anim_time", "math.pi");

    private final Set<String> replaced = new TreeSet<>();

    /** 改写一个数据点；无法解析时返回 "0" 并记录。 */
    public String normalize(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return "0";
        }
        try {
            return Molang.number(Double.parseDouble(trimmed));
        } catch (NumberFormatException ignored) {
            // 不是纯数字，按表达式处理
        }
        Molang.Node node;
        try {
            node = Molang.parse(trimmed);
        } catch (IllegalArgumentException e) {
            replaced.add("<无法解析> " + trimmed);
            return "0";
        }
        Molang.Node rewritten;
        String emitted;
        try {
            rewritten = fold(flatten(inline(node)));
            emitted = Molang.emit(rewritten);
        } catch (RuntimeException e) {
            // 单个数据点失败只影响这一帧，不拖垮整个模型
            replaced.add("<无法改写> " + trimmed);
            return "0";
        }
        if (compiles(emitted)) {
            return emitted;
        }
        // 编译器仍不接受：退化为把变量全部按 0 求出的常量（静止姿态）
        replaced.add("<编译失败> " + emitted);
        return Molang.number(Molang.eval(rewritten, path -> null));
    }

    /** 用 BetterModel 运行时同版本的编译器校验；与 BetterModel 一样在前面加 return 后编译。 */
    private static boolean compiles(String expression) {
        try {
            COMPILER.compile(expression);
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    private static final gg.moonflower.molangcompiler.api.MolangCompiler COMPILER =
            gg.moonflower.molangcompiler.api.MolangCompiler.create();

    /**
     * 处理嵌套在表达式内部的语句（如 {@code a ? {t.x = 1; return t.x;} : 0}）：
     * 代码块按 {@link #inline} 取值，残留的赋值取右值，{@code return} 取其值。
     */
    private Molang.Node flatten(Molang.Node node) {
        return map(node, n -> switch (n) {
            case Molang.Seq seq -> inline(seq);
            case Molang.Assign assign -> assign.value();
            case Molang.Return r -> r.value();
            default -> null;
        });
    }

    /** 被替换为常量的引用（去重、排序）。 */
    public Set<String> replaced() {
        return replaced;
    }

    /**
     * 语句序列：把 {@code temp.x = ...;} 一类局部赋值内联进最终表达式，
     * 取 {@code return} 或最后一句作为值。
     */
    private Molang.Node inline(Molang.Node node) {
        if (!(node instanceof Molang.Seq seq)) {
            return node instanceof Molang.Return r ? r.value() : node;
        }
        java.util.Map<String, Molang.Node> locals = new java.util.HashMap<>();
        Molang.Node result = new Molang.Num(0);
        for (Molang.Node statement : seq.statements()) {
            if (statement instanceof Molang.Assign assign) {
                locals.put(assign.path(), substitute(assign.value(), locals));
            } else if (statement instanceof Molang.Return r) {
                return substitute(r.value(), locals);
            } else {
                result = substitute(statement, locals);
            }
        }
        return result;
    }

    private Molang.Node substitute(Molang.Node node, java.util.Map<String, Molang.Node> locals) {
        return map(node, n -> n instanceof Molang.Ref ref && locals.containsKey(ref.path()) ? locals.get(ref.path()) : null);
    }

    /** 替换不支持的引用并做常量折叠。 */
    private Molang.Node fold(Molang.Node node) {
        Molang.Node replacedNode = map(node, n -> {
            if (n instanceof Molang.Ref ref && !supported(ref.path())) {
                replaced.add(ref.path());
                return new Molang.Num(0);
            }
            if (n instanceof Molang.Call call && !call.path().startsWith("math.")) {
                replaced.add(call.path() + "()");
                // 二阶/一阶物理平滑等函数：取目标值参数近似
                return call.args().size() >= 2 ? call.args().get(1) : new Molang.Num(0);
            }
            if (n instanceof Molang.Str) {
                return new Molang.Num(0);
            }
            return null;
        });
        return constantFold(replacedNode);
    }

    /**
     * BetterModel 运行时不会定义模型自己的 {@code variable.*}（GeckoLib/YSM 在 pre_animation 或代码里赋值），
     * 求值时遇到未定义变量直接抛异常而不是按 0 处理，所以变量也一律折算为 0。
     * 局部 {@code temp.*} 已由 {@link #inline} 内联掉。
     */
    private static boolean supported(String path) {
        return SUPPORTED_REFS.contains(path);
    }

    /** 若整个子树不含变量引用，直接求值为常量。 */
    private static Molang.Node constantFold(Molang.Node node) {
        Set<String> refs = new java.util.HashSet<>();
        Molang.references(node, refs);
        boolean constant = refs.stream().allMatch(r -> r.startsWith("math.") && r.endsWith("()") || r.equals("math.pi"));
        if (constant) {
            return new Molang.Num(Molang.eval(node, path -> null));
        }
        return node;
    }

    /** 自底向上映射：mapper 返回非 null 时替换该节点（替换结果会继续递归处理其子节点）。 */
    private static Molang.Node map(Molang.Node node, java.util.function.Function<Molang.Node, Molang.Node> mapper) {
        Molang.Node direct = mapper.apply(node);
        if (direct != null && direct != node) {
            return map(direct, mapper);
        }
        return switch (node) {
            case Molang.Num n -> n;
            case Molang.Str s -> s;
            case Molang.Ref r -> r;
            case Molang.Call c -> {
                List<Molang.Node> args = new ArrayList<>();
                c.args().forEach(a -> args.add(map(a, mapper)));
                yield new Molang.Call(c.path(), args);
            }
            case Molang.Unary u -> new Molang.Unary(u.op(), map(u.operand(), mapper));
            case Molang.Binary b -> new Molang.Binary(b.op(), map(b.left(), mapper), map(b.right(), mapper));
            case Molang.Cond c -> new Molang.Cond(map(c.condition(), mapper), map(c.then(), mapper),
                    c.otherwise() == null ? null : map(c.otherwise(), mapper));
            case Molang.Assign a -> new Molang.Assign(a.path(), map(a.value(), mapper));
            case Molang.Return r -> new Molang.Return(map(r.value(), mapper));
            case Molang.Seq s -> {
                List<Molang.Node> statements = new ArrayList<>();
                s.statements().forEach(st -> statements.add(map(st, mapper)));
                yield new Molang.Seq(statements);
            }
        };
    }
}
