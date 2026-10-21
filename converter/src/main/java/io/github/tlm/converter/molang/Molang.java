package io.github.tlm.converter.molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 精简的 Bedrock Molang 解析器：解析成 AST，支持求值与规范化重写。
 *
 * <p>用途有两个：
 * <ul>
 *   <li>转换期：把原模组 / YSM 动画里 BetterModel 解析不了的写法（二元条件 {@code a ? b}、
 *       结尾分号、{@code -(...)}、超长小数、{@code ysm.*} 等未知对象）重写或折算成常量；</li>
 *   <li>运行期：以后由插件驱动的动态骨骼（头部朝向等）可以复用同一套求值。</li>
 * </ul>
 *
 * <p>语法覆盖：数字、字符串、标识符路径与函数调用、一元 {@code - !}、算术、比较、逻辑、
 * {@code ??}、三元与二元条件、语句序列（{@code ;}）、赋值与 {@code return}。
 */
public final class Molang {

    private Molang() {
    }

    // ------------------------------------------------------------------ AST

    public sealed interface Node permits Num, Str, Ref, Call, Unary, Binary, Cond, Assign, Return, Seq {
    }

    public record Num(double value) implements Node {
    }

    public record Str(String value) implements Node {
    }

    /** 标识符路径，已规范化：{@code q./v./t./c.} 展开为 {@code query./variable./temp./context.}，全小写。 */
    public record Ref(String path) implements Node {
    }

    public record Call(String path, List<Node> args) implements Node {
    }

    public record Unary(String op, Node operand) implements Node {
    }

    public record Binary(String op, Node left, Node right) implements Node {
    }

    /** {@code otherwise} 为 null 表示二元条件 {@code cond ? then}（条件不成立时取 0）。 */
    public record Cond(Node condition, Node then, Node otherwise) implements Node {
    }

    public record Assign(String path, Node value) implements Node {
    }

    public record Return(Node value) implements Node {
    }

    public record Seq(List<Node> statements) implements Node {
    }

    // ------------------------------------------------------------------ 解析

    public static Node parse(String source) {
        Parser parser = new Parser(source);
        Node node = parser.program();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("多余的内容 @" + parser.pos + ": " + source);
        }
        return node;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            skip();
            return pos >= src.length();
        }

        Node program() {
            List<Node> statements = new ArrayList<>();
            while (!atEnd()) {
                statements.add(statement());
                skip();
                while (eat(";")) {
                    skip();
                }
            }
            if (statements.isEmpty()) {
                return new Num(0);
            }
            return statements.size() == 1 ? statements.getFirst() : new Seq(statements);
        }

        Node statement() {
            int start = pos;
            if (keyword("return")) {
                return new Return(expression());
            }
            // 赋值：标识符路径后面紧跟单个 '='
            String path = tryPath();
            if (path != null) {
                skip();
                if (peek("=") && !peek("==")) {
                    pos++;
                    return new Assign(normalize(path), expression());
                }
            }
            pos = start;
            return expression();
        }

        Node expression() {
            Node condition = coalesce();
            skip();
            if (eat("?")) {
                Node then = expression();
                skip();
                Node otherwise = eat(":") ? expression() : null;
                return new Cond(condition, then, otherwise);
            }
            return condition;
        }

        Node coalesce() {
            Node left = or();
            while (true) {
                skip();
                if (eat("??")) {
                    left = new Binary("??", left, or());
                } else {
                    return left;
                }
            }
        }

        Node or() {
            Node left = and();
            while (true) {
                skip();
                if (eat("||")) {
                    left = new Binary("||", left, and());
                } else {
                    return left;
                }
            }
        }

        Node and() {
            Node left = equality();
            while (true) {
                skip();
                if (eat("&&")) {
                    left = new Binary("&&", left, equality());
                } else {
                    return left;
                }
            }
        }

        Node equality() {
            Node left = relational();
            while (true) {
                skip();
                if (eat("==")) {
                    left = new Binary("==", left, relational());
                } else if (eat("!=")) {
                    left = new Binary("!=", left, relational());
                } else {
                    return left;
                }
            }
        }

        Node relational() {
            Node left = additive();
            while (true) {
                skip();
                if (eat("<=")) {
                    left = new Binary("<=", left, additive());
                } else if (eat(">=")) {
                    left = new Binary(">=", left, additive());
                } else if (eat("<")) {
                    left = new Binary("<", left, additive());
                } else if (eat(">")) {
                    left = new Binary(">", left, additive());
                } else {
                    return left;
                }
            }
        }

        Node additive() {
            Node left = multiplicative();
            while (true) {
                skip();
                if (eat("+")) {
                    left = new Binary("+", left, multiplicative());
                } else if (peek("-")) {
                    pos++;
                    left = new Binary("-", left, multiplicative());
                } else {
                    return left;
                }
            }
        }

        Node multiplicative() {
            Node left = unary();
            while (true) {
                skip();
                if (eat("*")) {
                    left = new Binary("*", left, unary());
                } else if (eat("/")) {
                    left = new Binary("/", left, unary());
                } else {
                    return left;
                }
            }
        }

        Node unary() {
            skip();
            if (eat("-")) {
                return new Unary("-", unary());
            }
            if (eat("+")) {
                return unary();
            }
            if (peek("!") && !peek("!=")) {
                pos++;
                return new Unary("!", unary());
            }
            return primary();
        }

        Node primary() {
            skip();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("表达式意外结束: " + src);
            }
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                Node inner = expression();
                skip();
                expect(")");
                return inner;
            }
            if (c == '{') {
                // 代码块：{ a; b; }，取最后一句的值
                pos++;
                List<Node> statements = new ArrayList<>();
                while (true) {
                    skip();
                    if (eat("}")) {
                        break;
                    }
                    statements.add(statement());
                    skip();
                    eat(";");
                }
                return statements.isEmpty() ? new Num(0) : new Seq(statements);
            }
            if (c == '\'' || c == '"') {
                int end = src.indexOf(c, pos + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("字符串未闭合: " + src);
                }
                String value = src.substring(pos + 1, end);
                pos = end + 1;
                return new Str(value);
            }
            if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                    pos++;
                }
                // 允许 1.5f 这类后缀
                if (pos < src.length() && (src.charAt(pos) == 'f' || src.charAt(pos) == 'F')) {
                    pos++;
                    return new Num(Double.parseDouble(src.substring(start, pos - 1)));
                }
                return new Num(Double.parseDouble(src.substring(start, pos)));
            }
            String path = tryPath();
            if (path == null) {
                throw new IllegalArgumentException("无法识别的字符 '" + c + "' @" + pos + ": " + src);
            }
            String normalized = normalize(path);
            if (normalized.equals("true")) {
                return new Num(1);
            }
            if (normalized.equals("false")) {
                return new Num(0);
            }
            skip();
            if (eat("(")) {
                List<Node> args = new ArrayList<>();
                skip();
                if (!eat(")")) {
                    do {
                        args.add(expression());
                        skip();
                    } while (eat(","));
                    expect(")");
                }
                return new Call(normalized, args);
            }
            return new Ref(normalized);
        }

        /** 读取 {@code a.b.c} 形式的标识符路径；当前位置不是标识符时返回 null 且不移动。 */
        String tryPath() {
            skip();
            int start = pos;
            if (pos >= src.length() || !isIdentStart(src.charAt(pos))) {
                return null;
            }
            while (pos < src.length() && (isIdentPart(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            return src.substring(start, pos);
        }

        boolean keyword(String word) {
            skip();
            if (src.regionMatches(true, pos, word, 0, word.length())
                    && (pos + word.length() >= src.length() || !isIdentPart(src.charAt(pos + word.length())))) {
                pos += word.length();
                return true;
            }
            return false;
        }

        boolean peek(String token) {
            return src.startsWith(token, pos);
        }

        boolean eat(String token) {
            if (src.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        void expect(String token) {
            skip();
            if (!eat(token)) {
                throw new IllegalArgumentException("缺少 '" + token + "' @" + pos + ": " + src);
            }
        }

        void skip() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        static boolean isIdentStart(char c) {
            return Character.isLetter(c) || c == '_';
        }

        static boolean isIdentPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }
    }

    /** 统一前缀缩写与大小写，便于后续按路径判断。 */
    static String normalize(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.indexOf('.');
        if (dot < 0) {
            return lower;
        }
        String head = lower.substring(0, dot);
        String tail = lower.substring(dot);
        return switch (head) {
            case "q" -> "query" + tail;
            case "v" -> "variable" + tail;
            case "t" -> "temp" + tail;
            case "c" -> "context" + tail;
            default -> lower;
        };
    }

    // ------------------------------------------------------------------ 求值

    /** 变量/查询解析器；返回 null 表示未知，按 0 处理（与 Bedrock 对未定义变量的行为一致）。 */
    @FunctionalInterface
    public interface Resolver {
        Double resolve(String path);
    }

    public static double eval(Node node, Resolver resolver) {
        return new Evaluator(resolver).eval(node);
    }

    private static final class Evaluator {
        private final Resolver resolver;
        private final Map<String, Double> locals = new java.util.HashMap<>();
        private Double returned;

        Evaluator(Resolver resolver) {
            this.resolver = resolver;
        }

        double eval(Node node) {
            return switch (node) {
                case Num n -> n.value();
                case Str s -> 0;
                case Ref r -> lookup(r.path());
                case Call c -> call(c);
                case Unary u -> u.op().equals("-") ? -eval(u.operand()) : truth(eval(u.operand())) ? 0 : 1;
                case Binary b -> binary(b);
                case Cond c -> truth(eval(c.condition())) ? eval(c.then())
                        : c.otherwise() == null ? 0 : eval(c.otherwise());
                case Assign a -> {
                    double value = eval(a.value());
                    locals.put(a.path(), value);
                    yield value;
                }
                case Return r -> {
                    returned = eval(r.value());
                    yield returned;
                }
                case Seq s -> {
                    double last = 0;
                    for (Node statement : s.statements()) {
                        last = eval(statement);
                        if (returned != null) {
                            yield returned;
                        }
                    }
                    yield last;
                }
            };
        }

        double lookup(String path) {
            Double local = locals.get(path);
            if (local != null) {
                return local;
            }
            if (path.equals("math.pi")) {
                return Math.PI;
            }
            Double value = resolver.resolve(path);
            return value == null ? 0 : value;
        }

        double binary(Binary b) {
            if (b.op().equals("&&")) {
                return truth(eval(b.left())) && truth(eval(b.right())) ? 1 : 0;
            }
            if (b.op().equals("||")) {
                return truth(eval(b.left())) || truth(eval(b.right())) ? 1 : 0;
            }
            if (b.op().equals("??")) {
                // 未定义变量会被解析为 0，这里退化为「左值非 0 取左值」
                double left = eval(b.left());
                return left != 0 ? left : eval(b.right());
            }
            double l = eval(b.left());
            double r = eval(b.right());
            return switch (b.op()) {
                case "+" -> l + r;
                case "-" -> l - r;
                case "*" -> l * r;
                case "/" -> r == 0 ? 0 : l / r;
                case "==" -> l == r ? 1 : 0;
                case "!=" -> l != r ? 1 : 0;
                case "<" -> l < r ? 1 : 0;
                case "<=" -> l <= r ? 1 : 0;
                case ">" -> l > r ? 1 : 0;
                case ">=" -> l >= r ? 1 : 0;
                default -> throw new IllegalStateException(b.op());
            };
        }

        double call(Call c) {
            List<Node> args = c.args();
            double a = args.isEmpty() ? 0 : eval(args.get(0));
            double b = args.size() < 2 ? 0 : eval(args.get(1));
            double d = args.size() < 3 ? 0 : eval(args.get(2));
            return switch (c.path()) {
                case "math.abs" -> Math.abs(a);
                case "math.sin" -> Math.sin(Math.toRadians(a));
                case "math.cos" -> Math.cos(Math.toRadians(a));
                case "math.asin" -> Math.toDegrees(Math.asin(a));
                case "math.acos" -> Math.toDegrees(Math.acos(a));
                case "math.atan" -> Math.toDegrees(Math.atan(a));
                case "math.atan2" -> Math.toDegrees(Math.atan2(a, b));
                case "math.clamp" -> Math.max(b, Math.min(d, a));
                case "math.min" -> Math.min(a, b);
                case "math.max" -> Math.max(a, b);
                case "math.floor" -> Math.floor(a);
                case "math.ceil" -> Math.ceil(a);
                case "math.round" -> Math.round(a);
                case "math.trunc" -> (double) (long) a;
                case "math.sqrt" -> Math.sqrt(a);
                case "math.pow" -> Math.pow(a, b);
                case "math.exp" -> Math.exp(a);
                case "math.ln" -> Math.log(a);
                case "math.mod" -> b == 0 ? 0 : a % b;
                case "math.lerp" -> a + (b - a) * d;
                case "math.hermite_blend" -> 3 * a * a - 2 * a * a * a;
                // 随机数折算为区间中点，保证同一输入转换结果稳定
                case "math.random", "math.random_integer" -> (a + b) / 2;
                case "math.die_roll", "math.die_roll_integer" -> a * (b + d) / 2;
                default -> {
                    // 未知函数（ysm.second_order 等）：取第二个参数作为「目标值」的近似，没有则 0
                    Double value = resolver.resolve(c.path() + "()");
                    yield value != null ? value : args.size() >= 2 ? b : 0;
                }
            };
        }

        static boolean truth(double value) {
            return value != 0;
        }
    }

    // ------------------------------------------------------------------ 分析与输出

    /** 收集表达式里出现的全部变量/查询/函数路径。 */
    public static void references(Node node, java.util.Set<String> out) {
        switch (node) {
            case Num n -> {
            }
            case Str s -> {
            }
            case Ref r -> out.add(r.path());
            case Call c -> {
                out.add(c.path() + "()");
                c.args().forEach(arg -> references(arg, out));
            }
            case Unary u -> references(u.operand(), out);
            case Binary b -> {
                references(b.left(), out);
                references(b.right(), out);
            }
            case Cond c -> {
                references(c.condition(), out);
                references(c.then(), out);
                if (c.otherwise() != null) {
                    references(c.otherwise(), out);
                }
            }
            case Assign a -> {
                out.add(a.path());
                references(a.value(), out);
            }
            case Return r -> references(r.value(), out);
            case Seq s -> s.statements().forEach(st -> references(st, out));
        }
    }

    /**
     * 输出为 BetterModel（moonflower molang-compiler）稳定可解析的单表达式：
     * 完整括号、三元补齐 {@code : 0}、取反写成 {@code -1 * x}、数字限定小数位。
     * 仅对不含赋值/语句序列的表达式调用。
     */
    public static String emit(Node node) {
        return switch (node) {
            case Num n -> number(n.value());
            case Str s -> "'" + s.value() + "'";
            case Ref r -> r.path();
            case Call c -> {
                StringBuilder sb = new StringBuilder(c.path()).append('(');
                for (int i = 0; i < c.args().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(emit(c.args().get(i)));
                }
                yield sb.append(')').toString();
            }
            // BetterModel（molang-compiler）对 '!'、'&&'、'||'、嵌套三元生成字节码会失败，
            // 这里全部改写为只含算术与比较的等价式：布尔值统一为 0/1。
            case Unary u -> u.op().equals("-") ? "(-1 * " + emit(u.operand()) + ")" : "(1 - " + bool(u.operand()) + ")";
            case Binary b -> switch (b.op()) {
                case "&&" -> "(" + bool(b.left()) + " * " + bool(b.right()) + ")";
                case "||" -> "math.min(1, " + bool(b.left()) + " + " + bool(b.right()) + ")";
                // 未定义变量按 0 处理，?? 退化为取左值
                case "??" -> emit(b.left());
                default -> "(" + emit(b.left()) + " " + b.op() + " " + emit(b.right()) + ")";
            };
            case Cond c -> {
                String condition = bool(c.condition());
                String otherwise = c.otherwise() == null ? "0" : emit(c.otherwise());
                yield "((" + condition + " * " + emit(c.then()) + ") + ((1 - " + condition + ") * " + otherwise + "))";
            }
            case Return r -> emit(r.value());
            case Assign a -> throw new IllegalArgumentException("赋值不能内联输出");
            case Seq s -> throw new IllegalArgumentException("语句序列不能内联输出");
        };
    }

    /** 输出为 0/1 布尔值；比较与逻辑运算本身已是 0/1，其余用 {@code != 0} 归一。 */
    private static String bool(Node node) {
        if (node instanceof Binary b && switch (b.op()) {
            case "==", "!=", "<", "<=", ">", ">=", "&&", "||" -> true;
            default -> false;
        }) {
            return emit(node);
        }
        if (node instanceof Unary u && u.op().equals("!")) {
            return emit(node);
        }
        if (node instanceof Num n) {
            return n.value() != 0 ? "1" : "0";
        }
        return "(" + emit(node) + " != 0)";
    }

    /** 数字格式：最多 5 位小数，去掉尾随 0；BetterModel 的数字词法读不了过长的小数。 */
    public static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        double rounded = Math.round(value * 100000d) / 100000d;
        if (rounded == 0) {
            return "0";
        }
        String text = java.math.BigDecimal.valueOf(rounded).stripTrailingZeros().toPlainString();
        return text.equals("-0") ? "0" : text;
    }
}
