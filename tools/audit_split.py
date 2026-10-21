#!/usr/bin/env python3
"""双包拆分审计：检查 A（付费核心）与 B（开源义务包）的 jar 是否守住了许可边界。

用法：
    python tools/audit_split.py --a <A.jar> --b <B.jar> [--a-src <A 的 src/main/java>]

检查项：
  A 的 jar
    1. 不得含任何资产产物：content/、models/、runtime/、assets/touhou_little_maid/book/、altar/
    2. 不得含 *.bbmodel / gecko/ / tlm_models.json（jar 根）
    3. 不得含 GPL 引擎类：io/github/tlm/folia/game/xqwlight|chess/
    4. 不得含 api 类（单点携带在 B）：io/github/tlm/folia/api/
  B 的 jar
    5. 必须含 LICENSE / LICENSE-ASSETS / THIRD-PARTY.md（META-INF 下）
    6. 必须含 content/、models/、runtime/、BOOK.DAT、api 类、两个引擎类
  A 的源码（可选）
    7. 不得出现 xqwlight / folia.game.chess / CChessGame.INSTANCE 的引用
退出码：0 = 全部通过；1 = 有违规。
"""
from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

FORBIDDEN_A_PREFIXES = (
    "content/",
    "models/",
    "runtime/",
    "assets/touhou_little_maid/book/",
    "altar/",
    "gecko/",
)
FORBIDDEN_A_CLASS_PREFIXES = (
    "io/github/tlm/folia/game/xqwlight/",
    "io/github/tlm/folia/game/chess/",
    "io/github/tlm/folia/api/",
)
REQUIRED_B_ENTRIES = (
    "paper-plugin.yml",
    "META-INF/LICENSE",
    "META-INF/LICENSE-ASSETS",
    "META-INF/THIRD-PARTY.md",
    "content/",
    "models/",
    "runtime/",
    # protocol.txt 由 B 在 onEnable 时写入 runtime 目录，不打进 jar
    "assets/touhou_little_maid/book/cchess/BOOK.DAT",
    "assets/touhou_little_maid/book/wchess/BOOK.DAT",
    "io/github/tlm/folia/api/BoardGame.class",
    "io/github/tlm/folia/game/xqwlight/Position.class",
    "io/github/tlm/folia/game/chess/Position.class",
)
A_SRC_FORBIDDEN_MARKERS = (
    "xqwlight",
    "io.github.tlm.folia.game.chess",
    "CChessGame.INSTANCE",
    "WChessGame.INSTANCE",
)


def check_a_jar(path: Path) -> list[str]:
    problems: list[str] = []
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
    for name in names:
        if any(name.startswith(prefix) for prefix in FORBIDDEN_A_PREFIXES) or name == "tlm_models.json":
            problems.append(f"A 含资产产物：{name}")
        if name.endswith(".bbmodel"):
            problems.append(f"A 含 bbmodel：{name}")
        if any(name.startswith(prefix) for prefix in FORBIDDEN_A_CLASS_PREFIXES):
            problems.append(f"A 含 GPL/api 类：{name}")
    return problems


def check_b_jar(path: Path) -> list[str]:
    problems: list[str] = []
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
    for required in REQUIRED_B_ENTRIES:
        if not any(name == required or name.startswith(required) for name in names):
            problems.append(f"B 缺少必需项：{required}")
    return problems


def check_a_src(src: Path) -> list[str]:
    problems: list[str] = []
    for java in src.rglob("*.java"):
        text = java.read_text(encoding="utf-8", errors="ignore")
        for marker in A_SRC_FORBIDDEN_MARKERS:
            if marker in text:
                problems.append(f"A 源码仍引用 {marker}：{java}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--a", required=True, type=Path, help="A（付费核心）的 jar")
    parser.add_argument("--b", required=True, type=Path, help="B（开源义务包）的 jar")
    parser.add_argument("--a-src", type=Path, default=None, help="A 的 src/main/java（可选）")
    args = parser.parse_args()

    problems: list[str] = []
    problems += check_a_jar(args.a)
    problems += check_b_jar(args.b)
    if args.a_src is not None:
        problems += check_a_src(args.a_src)

    if problems:
        print(f"审计失败：{len(problems)} 项违规")
        for problem in problems:
            print("  -", problem)
        return 1
    print("审计通过：A 零资产/零 GPL/零 api 类；B 资产与声明齐全。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
