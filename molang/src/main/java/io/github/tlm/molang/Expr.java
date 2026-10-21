package io.github.tlm.molang;

/**
 * 编译后的 Molang 表达式节点，自身即可求值（树遍历，无反射、无字符串拼接）。
 *
 * <p>插件运行时与转换器校验共用这套节点：转换器只解析、收集引用，插件逐 tick 求值。
 */
public interface Expr {

    double eval(MolangContext ctx);

    record Num(double value) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            return this.value;
        }
    }

    /** 字符串只作函数参数（骨骼名、物理键、物品 id），单独求值为 0。 */
    record Str(String value) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            return 0;
        }
    }

    /** {@code variable.*}：随实体持久保存。 */
    record Var(String name) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            return ctx.getVariable(this.name);
        }
    }

    /** {@code temp.*}：单次求值内有效。 */
    record Temp(String name) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            return ctx.getTemp(this.name);
        }
    }

    /** {@code query.* / ysm.* / tlm.* / math.pi} 等外部引用。 */
    record Ref(String path) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            return ctx.resolve(this.path);
        }
    }

    /** 函数调用；{@code member} 非空表示取返回结构体的分量，如 {@code ysm.bone_rot('Head').x}。 */
    record Call(String path, Expr[] args, String member) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            return ctx.call(this.path, this.args, this.member);
        }
    }

    record Neg(Expr value) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            return -this.value.eval(ctx);
        }
    }

    record Not(Expr value) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            return this.value.eval(ctx) == 0 ? 1 : 0;
        }
    }

    /** 算术与比较；比较结果为 0/1。 */
    record Bin(char op, Expr left, Expr right) implements Expr {
        // 两字符运算符的单字符编码
        public static final char LE = 'l';
        public static final char GE = 'g';
        public static final char EQ = 'e';
        public static final char NE = 'n';

        @Override
        public double eval(MolangContext ctx) {
            double l = this.left.eval(ctx);
            double r = this.right.eval(ctx);
            return switch (this.op) {
                case '+' -> l + r;
                case '-' -> l - r;
                case '*' -> l * r;
                case '/' -> r == 0 ? 0 : l / r;
                case '<' -> l < r ? 1 : 0;
                case '>' -> l > r ? 1 : 0;
                case LE -> l <= r ? 1 : 0;
                case GE -> l >= r ? 1 : 0;
                case EQ -> l == r ? 1 : 0;
                case NE -> l != r ? 1 : 0;
                default -> throw new IllegalStateException("未知运算符 " + this.op);
            };
        }
    }

    record And(Expr left, Expr right) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            return this.left.eval(ctx) != 0 && this.right.eval(ctx) != 0 ? 1 : 0;
        }
    }

    record Or(Expr left, Expr right) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            return this.left.eval(ctx) != 0 || this.right.eval(ctx) != 0 ? 1 : 0;
        }
    }

    /** {@code a ?? b}：左侧是未定义的变量时取右侧。 */
    record Coalesce(Expr left, Expr right) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            if (this.left instanceof Var v && !ctx.hasVariable(v.name())) {
                return this.right.eval(ctx);
            }
            return this.left.eval(ctx);
        }
    }

    /** 三元条件；{@code otherwise} 为 null 表示二元条件 {@code a ? b}，不成立取 0。 */
    record Cond(Expr condition, Expr then, Expr otherwise) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            if (this.condition.eval(ctx) != 0) {
                return this.then.eval(ctx);
            }
            return this.otherwise == null ? 0 : this.otherwise.eval(ctx);
        }
    }

    record SetVar(String name, Expr value) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            double v = this.value.eval(ctx);
            ctx.setVariable(this.name, v);
            return v;
        }
    }

    record SetTemp(String name, Expr value) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            double v = this.value.eval(ctx);
            ctx.setTemp(this.name, v);
            return v;
        }
    }

    record Return(Expr value) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            return this.value.eval(ctx);
        }
    }

    /** 语句序列：遇到 return 立即取值，否则取最后一句的值。 */
    record Seq(Expr[] statements) implements Expr {
        @Override
        public double eval(MolangContext ctx) {
            double last = 0;
            for (Expr statement : this.statements) {
                last = statement.eval(ctx);
                if (statement instanceof Return) {
                    return last;
                }
            }
            return last;
        }
    }
}
