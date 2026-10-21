package io.github.tlm.converter.convert;

import java.util.List;
import java.util.Set;

/**
 * 骨骼的初始可见性。
 *
 * <p>原模组很多骨骼由客户端 .js 程序化动画按状态显隐（眨眼、受伤、睡觉、盔甲、背包、任务道具……），
 * 骨骼本身在几何体里是可见的。BetterModel 没有这些脚本，若原样输出，所有「状态贴片」会一直显示，
 * 与底下的贴图几乎共面而产生 z-fighting 闪烁（典型的就是眼睛上的 blink）。
 *
 * <p>这里按女仆的默认状态（清醒、站立、满血、无盔甲、无背包、空闲任务、主世界白天）复刻各脚本的结果；
 * 只有模型声明了对应脚本时才生效，与原模组一致。运行时状态变化由插件再切换。
 */
final class DefaultVisibility {

    private static final String ANIM = "touhou_little_maid:animation/";

    private static final List<String> ARMOR_PARTS = List.of(
            "helmet", "chestPlate", "chestPlateLeft", "chestPlateMiddle", "chestPlateRight",
            "leggings", "leggingsLeft", "leggingsMiddle", "leggingsRight", "bootsLeft", "bootsRight");

    private static final List<String> TASKS = List.of(
            "attack", "danmakuAttack", "farm", "feedAnimal", "milk", "shears", "sugarCane", "cocoa",
            "extinguishing", "feed", "grass", "melon", "rangedAttack", "snow", "torch");

    private DefaultVisibility() {
    }

    /**
     * @param bone    原始骨骼名（未加去重后缀）
     * @param scripts 模型声明的程序化动画资源 id
     */
    static boolean visible(String bone, Set<String> scripts) {
        if (scripts.isEmpty()) {
            return true;
        }
        return !hidden(bone, scripts);
    }

    private static boolean hidden(String bone, Set<String> s) {
        // 头部
        if (has(s, "maid/default/head/blink.js") && (bone.equals("blink") || bone.equals("blink2"))) {
            return true;
        }
        if (has(s, "maid/default/head/hurt.js") && bone.equals("hurtBlink")) {
            return true;
        }
        if (has(s, "maid/default/head/beg.js") && bone.equals("begShow")) {
            return true;
        }
        // 姿态与状态
        if (has(s, "maid/default/sleep/default.js") && bone.equals("sleepShow")) {
            return true;
        }
        if (has(s, "maid/default/sit/skirt_hidden.js") && bone.equals("_sittingHiddenSkirt")) {
            return true;
        }
        if (has(s, "maid/default/status/backpack.js") && bone.equals("backpackShow")) {
            return true;
        }
        if (has(s, "maid/default/health/less_show.js") && bone.startsWith("healthLess") && bone.endsWith("Show")) {
            return true;
        }
        // 盔甲：默认无盔甲、护甲值 0、晴天
        if (has(s, "maid/default/armor/default.js") && ARMOR_PARTS.contains(bone)) {
            return true;
        }
        for (String suffix : new String[]{"ValueFull", "ValueHigh", "ValueNormal", "ValueLow",
                "WeatherRaining", "WeatherThundering"}) {
            String script = suffix.startsWith("Value")
                    ? "maid/default/armor/value/value_" + suffix.substring(5).toLowerCase() + ".js"
                    : "maid/default/armor/weather/" + suffix.substring(7).toLowerCase() + ".js";
            if (has(s, script) && bone.endsWith(suffix) && ARMOR_PARTS.contains(bone.substring(0, bone.length() - suffix.length()))) {
                return true;
            }
        }
        // 任务：默认空闲
        if (has(s, "maid/default/task/idle.js") && bone.equals("idleHidden")) {
            return true;
        }
        for (String task : TASKS) {
            String file = task.replaceAll("([A-Z])", "_$1").toLowerCase();
            if (has(s, "maid/default/task/" + file + ".js") && bone.equals(task + "Show")) {
                return true;
            }
        }
        // 环境：主世界、白天、非节日
        if (has(s, "base/dimension/default.js")
                && (bone.equals("overWorldHidden") || bone.equals("netherWorldShow") || bone.equals("endWorldShow"))) {
            return true;
        }
        if (has(s, "base/time/day_night_hidden.js") && bone.equals("nightShow")) {
            return true;
        }
        for (String festival : new String[]{"newYear", "christmas", "springFestival", "duanwu", "midAutumn"}) {
            String file = festival.replaceAll("([A-Z])", "_$1").toLowerCase();
            if (has(s, "base/festival/" + file + ".js") && bone.equals(festival + "Show")) {
                return true;
            }
        }
        if (has(s, "base/random/select.js") && bone.matches("randomSelect[2-5]")) {
            return true;
        }
        // 椅子：默认无乘客
        return has(s, "chair/passenger/hidden.js") && bone.equals("passengerShow");
    }

    private static boolean has(Set<String> scripts, String path) {
        return scripts.contains(ANIM + path);
    }
}
