package io.github.tlm.molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Bedrock Molang 解析器，覆盖原模组 / YSM 动画实际用到的语法：
 * 数字、字符串、路径与函数调用（含 {@code f(...).x} 取分量）、一元 {@code - ! +}、算术、比较、
 * {@code && || ??}、三元与二元条件、赋值、{@code return}、{@code ;} 语句序列与 {@code {}} 代码块。
 *
 * <p>标识符大小写不敏感，统一小写；{@code q./v./t./c.} 展开为全称。字符串保留原样（骨骼名区分大小写）。
 * {@code math.*} 在解析期绑定为内置函数，其余调用交给 {@link MolangContext#call}。
 */
public final class MolangParser {

    private static final Random RANDOM = new Random();

    private final String src;
    private int pos;

    private MolangParser(String src) {
        this.src = src;
    }

    /** 解析失败抛 {@link IllegalArgumentException}。 */
    public static Expr parse(String source) {
        MolangParser parser = new MolangParser(source);
        Expr expr = parser.program();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("多余的内容 @" + parser.pos + ": " + source);
        }
        return expr;
    }

    private Expr program() {
        List<Expr> statements = new ArrayList<>();
        while (!atEnd()) {
            if (eat(";")) {
                continue;
            }
            statements.add(statement());
            skip();
            if (!atEnd() && !eat(";")) {
                throw new IllegalArgumentException("缺少 ';' @" + this.pos + ": " + this.src);
            }
        }
        return seq(statements);
    }

    private static Expr seq(List<Expr> statements) {
        if (statements.isEmpty()) {
            return new Expr.Num(0);
        }
        if (statements.size() == 1) {
            Expr only = statements.getFirst();
            return only instanceof Expr.Return r ? r.value() : only;
        }
        return new Expr.Seq(statements.toArray(Expr[]::new));
    }

    private Expr statement() {
        skip();
        if (keyword("return")) {
            return new Expr.Return(expression());
        }
        int start = this.pos;
        String path = tryPath();
        if (path != null) {
            skip();
            if (peek("=") && !peek("==")) {
                this.pos++;
                Expr value = expression();
                String name = normalize(path);
                if (name.startsWith("variable.")) {
                    return new Expr.SetVar(name.substring(9), value);
                }
                if (name.startsWith("temp.")) {
                    return new Expr.SetTemp(name.substring(5), value);
                }
                // 其他对象（如 context.*）不可写，只保留右值的副作用
                return value;
            }
        }
        this.pos = start;
        return expression();
    }

    private Expr expression() {
        Expr condition = coalesce();
        skip();
        if (peek("?") && !peek("??")) {
            this.pos++;
            Expr then = expression();
            skip();
            Expr otherwise = eat(":") ? expression() : null;
            return new Expr.Cond(condition, then, otherwise);
        }
        return condition;
    }

    private Expr coalesce() {
        Expr left = or();
        while (true) {
            skip();
            if (eat("??")) {
                left = new Expr.Coalesce(left, or());
            } else {
                return left;
            }
        }
    }

    private Expr or() {
        Expr left = and();
        while (true) {
            skip();
            if (eat("||")) {
                left = new Expr.Or(left, and());
            } else {
                return left;
            }
        }
    }

    private Expr and() {
        Expr left = equality();
        while (true) {
            skip();
            if (eat("&&")) {
                left = new Expr.And(left, equality());
            } else {
                return left;
            }
        }
    }

    private Expr equality() {
        Expr left = relational();
        while (true) {
            skip();
            if (eat("==")) {
                left = new Expr.Bin(Expr.Bin.EQ, left, relational());
            } else if (eat("!=")) {
                left = new Expr.Bin(Expr.Bin.NE, left, relational());
            } else {
                return left;
            }
        }
    }

    private Expr relational() {
        Expr left = additive();
        while (true) {
            skip();
            if (eat("<=")) {
                left = new Expr.Bin(Expr.Bin.LE, left, additive());
            } else if (eat(">=")) {
                left = new Expr.Bin(Expr.Bin.GE, left, additive());
            } else if (eat("<")) {
                left = new Expr.Bin('<', left, additive());
            } else if (eat(">")) {
                left = new Expr.Bin('>', left, additive());
            } else {
                return left;
            }
        }
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (true) {
            skip();
            if (eat("+")) {
                left = new Expr.Bin('+', left, multiplicative());
            } else if (eat("-")) {
                left = new Expr.Bin('-', left, multiplicative());
            } else {
                return left;
            }
        }
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (true) {
            skip();
            if (eat("*")) {
                left = new Expr.Bin('*', left, unary());
            } else if (eat("/")) {
                left = new Expr.Bin('/', left, unary());
            } else {
                return left;
            }
        }
    }

    private Expr unary() {
        skip();
        if (eat("-")) {
            Expr operand = unary();
            return operand instanceof Expr.Num n ? new Expr.Num(-n.value()) : new Expr.Neg(operand);
        }
        if (eat("+")) {
            return unary();
        }
        if (peek("!") && !peek("!=")) {
            this.pos++;
            return new Expr.Not(unary());
        }
        return primary();
    }

    private Expr primary() {
        skip();
        if (this.pos >= this.src.length()) {
            throw new IllegalArgumentException("表达式意外结束: " + this.src);
        }
        char c = this.src.charAt(this.pos);
        if (c == '(') {
            this.pos++;
            Expr inner = expression();
            expect(")");
            return inner;
        }
        if (c == '{') {
            this.pos++;
            List<Expr> statements = new ArrayList<>();
            while (!eat("}")) {
                if (atEnd()) {
                    throw new IllegalArgumentException("代码块未闭合: " + this.src);
                }
                if (eat(";")) {
                    continue;
                }
                statements.add(statement());
            }
            return seq(statements);
        }
        if (c == '\'' || c == '"') {
            int end = this.src.indexOf(c, this.pos + 1);
            if (end < 0) {
                throw new IllegalArgumentException("字符串未闭合: " + this.src);
            }
            String value = this.src.substring(this.pos + 1, end);
            this.pos = end + 1;
            return new Expr.Str(value);
        }
        if (Character.isDigit(c) || c == '.') {
            return number();
        }
        return reference();
    }

    private Expr number() {
        int start = this.pos;
        while (this.pos < this.src.length()
                && (Character.isDigit(this.src.charAt(this.pos)) || this.src.charAt(this.pos) == '.')) {
            this.pos++;
        }
        double value = Double.parseDouble(this.src.substring(start, this.pos));
        if (this.pos < this.src.length() && (this.src.charAt(this.pos) == 'f' || this.src.charAt(this.pos) == 'F')) {
            this.pos++;
        }
        return new Expr.Num(value);
    }

    private Expr reference() {
        String raw = tryPath();
        if (raw == null) {
            throw new IllegalArgumentException("无法识别的字符 '" + this.src.charAt(this.pos) + "' @" + this.pos + ": " + this.src);
        }
        String path = normalize(raw);
        switch (path) {
            case "true":
                return new Expr.Num(1);
            case "false":
                return new Expr.Num(0);
            case "math.pi":
                return new Expr.Num(Math.PI);
            default:
                break;
        }
        if (eat("(")) {
            List<Expr> args = new ArrayList<>();
            if (!eat(")")) {
                do {
                    args.add(expression());
                } while (eat(","));
                expect(")");
            }
            String member = null;
            if (peek(".") && this.pos + 1 < this.src.length() && isIdentStart(this.src.charAt(this.pos + 1))) {
                this.pos++;
                member = tryPath().toLowerCase(Locale.ROOT);
            }
            Expr[] array = args.toArray(Expr[]::new);
            if (path.startsWith("math.")) {
                return new MathCall(path.substring(5), array);
            }
            return new Expr.Call(path, array, member);
        }
        if (path.startsWith("variable.")) {
            return new Expr.Var(path.substring(9));
        }
        if (path.startsWith("temp.")) {
            return new Expr.Temp(path.substring(5));
        }
        return new Expr.Ref(path);
    }

    /** {@code math.*} 内置函数；三角函数参数为角度，与 Bedrock 一致。 */
    public record MathCall(String name, Expr[] args) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            double a = this.args.length > 0 ? this.args[0].eval(ctx) : 0;
            double b = this.args.length > 1 ? this.args[1].eval(ctx) : 0;
            double c = this.args.length > 2 ? this.args[2].eval(ctx) : 0;
            return switch (this.name) {
                case "abs" -> Math.abs(a);
                case "sin" -> Math.sin(Math.toRadians(a));
                case "cos" -> Math.cos(Math.toRadians(a));
                case "asin" -> Math.toDegrees(Math.asin(a));
                case "acos" -> Math.toDegrees(Math.acos(a));
                case "atan" -> Math.toDegrees(Math.atan(a));
                case "atan2" -> Math.toDegrees(Math.atan2(a, b));
                case "clamp" -> Math.max(b, Math.min(c, a));
                case "min" -> Math.min(a, b);
                case "max" -> Math.max(a, b);
                case "floor" -> Math.floor(a);
                case "ceil" -> Math.ceil(a);
                case "round" -> Math.round(a);
                case "trunc" -> (double) (long) a;
                case "sqrt" -> Math.sqrt(a);
                case "pow" -> Math.pow(a, b);
                case "exp" -> Math.exp(a);
                case "ln" -> Math.log(a);
                case "mod" -> b == 0 ? 0 : a % b;
                case "sign" -> Math.signum(a);
                case "lerp" -> a + (b - a) * c;
                case "lerprotate" -> a + wrapDegrees(b - a) * c;
                case "inverse_lerp" -> b == a ? 0 : (c - a) / (b - a);
                case "hermite_blend" -> 3 * a * a - 2 * a * a * a;
                case "min_angle" -> wrapDegrees(a);
                case "random" -> a + RANDOM.nextDouble() * (b - a);
                case "random_integer" -> Math.floor(a + RANDOM.nextDouble() * (Math.floor(b) - Math.floor(a) + 1));
                case "die_roll" -> dieRoll(a, b, c, false);
                case "die_roll_integer" -> dieRoll(a, b, c, true);
                default -> 0;
            };
        }

        private static double dieRoll(double count, double low, double high, boolean integer) {
            double sum = 0;
            for (int i = 0; i < (int) count; i++) {
                double roll = low + RANDOM.nextDouble() * (high - low + (integer ? 1 : 0));
                sum += integer ? Math.floor(roll) : roll;
            }
            return sum;
        }

        private static double wrapDegrees(double value) {
            double wrapped = value % 360;
            if (wrapped >= 180) {
                wrapped -= 360;
            }
            if (wrapped < -180) {
                wrapped += 360;
            }
            return wrapped;
        }
    }

    // ------------------------------------------------------------------ 词法

    /** 读取 {@code a.b.c} 路径；不吞掉路径后面的 {@code .}，留给 {@code f(...).x} 取分量。 */
    private String tryPath() {
        skip();
        int start = this.pos;
        if (this.pos >= this.src.length() || !isIdentStart(this.src.charAt(this.pos))) {
            return null;
        }
        while (this.pos < this.src.length()) {
            char ch = this.src.charAt(this.pos);
            if (isIdentPart(ch)) {
                this.pos++;
            } else if (ch == '.' && this.pos + 1 < this.src.length() && isIdentStart(this.src.charAt(this.pos + 1))) {
                this.pos++;
            } else {
                break;
            }
        }
        return this.src.substring(start, this.pos);
    }

    private boolean keyword(String word) {
        skip();
        if (this.src.regionMatches(true, this.pos, word, 0, word.length())
                && (this.pos + word.length() >= this.src.length() || !isIdentPart(this.src.charAt(this.pos + word.length())))) {
            this.pos += word.length();
            return true;
        }
        return false;
    }

    private boolean atEnd() {
        skip();
        return this.pos >= this.src.length();
    }

    private boolean peek(String token) {
        return this.src.startsWith(token, this.pos);
    }

    private boolean eat(String token) {
        skip();
        if (this.src.startsWith(token, this.pos)) {
            this.pos += token.length();
            return true;
        }
        return false;
    }

    private void expect(String token) {
        if (!eat(token)) {
            throw new IllegalArgumentException("缺少 '" + token + "' @" + this.pos + ": " + this.src);
        }
    }

    private void skip() {
        while (this.pos < this.src.length() && Character.isWhitespace(this.src.charAt(this.pos))) {
            this.pos++;
        }
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 统一前缀缩写与大小写。 */
    public static String normalize(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.indexOf('.');
        if (dot < 0) {
            return lower;
        }
        String tail = lower.substring(dot);
        return switch (lower.substring(0, dot)) {
            case "q" -> "query" + tail;
            case "v" -> "variable" + tail;
            case "t" -> "temp" + tail;
            case "c" -> "context" + tail;
            default -> lower;
        };
    }
}
