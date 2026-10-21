package io.github.tlm.molang;

import java.util.HashMap;
import java.util.Map;

/**
 * 求值上下文：变量存储 + 外部引用与函数的解析。
 *
 * <p>{@code variable.*} 由子类持久保存（随实体），{@code temp.*} 每次顶层求值前清空。
 * 未定义的变量与未知引用一律按 0 处理，与 GeckoLib 行为一致。
 */
public abstract class MolangContext {

    private final Map<String, Double> temps = new HashMap<>();

    public abstract double getVariable(String name);

    public abstract boolean hasVariable(String name);

    public abstract void setVariable(String name, double value);

    /** 解析 {@code query.* / ysm.* / tlm.* / math.pi} 等无参引用。 */
    public abstract double resolve(String path);

    /** 解析函数调用；{@code member} 为结构体分量（如 {@code .x}），没有时为 null。 */
    public abstract double call(String path, Expr[] args, String member);

    public final double getTemp(String name) {
        Double value = this.temps.get(name);
        return value == null ? 0 : value;
    }

    public final void setTemp(String name, double value) {
        this.temps.put(name, value);
    }

    /** 顶层求值：清空临时变量，结果为 NaN/无穷时按 0。 */
    public final double evaluate(Expr expr) {
        if (!this.temps.isEmpty()) {
            this.temps.clear();
        }
        double value = expr.eval(this);
        return Double.isFinite(value) ? value : 0;
    }

    public final float evaluateFloat(Expr expr) {
        return (float) evaluate(expr);
    }

    /** 参数为字符串字面量时返回其内容，否则 null。 */
    public static String string(Expr expr) {
        return expr instanceof Expr.Str s ? s.value() : null;
    }
}
