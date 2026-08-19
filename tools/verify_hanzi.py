# -*- coding: utf-8 -*-
"""用跟 ChineseVariants.kt 相同的演算法驗證字典與轉換鏈是否正確。

跑法：python verify_hanzi.py
結果寫到同目錄的 verify_hanzi_out.txt（避免 PowerShell 以 CP950 誤讀輸出）。
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "app", "src", "main", "assets", "opencc")

TO_TRADITIONAL = [
    ["STPhrases.txt", "STCharacters.txt"],
    ["TWVariantsPhrases.txt", "TWVariants.txt"],
    ["TWPhrases.txt"],
]
TO_SIMPLIFIED = [
    ["TWPhrasesRev.txt"],
    ["TSPhrases.txt", "TSCharacters.txt"],
]


def load_stage(files):
    table = {}
    max_key = 1
    for name in files:
        with open(os.path.join(ASSETS, name), encoding="utf-8") as fh:
            for raw in fh:
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                if "\t" not in line:
                    continue
                key, rest = line.split("\t", 1)
                value = rest.lstrip().split(" ")[0]
                if not key or not value:
                    continue
                if key not in table:
                    table[key] = value
                    max_key = max(max_key, len(key))
    return table, max_key


def apply_stage(text, stage):
    table, max_key = stage
    out = []
    i = 0
    while i < len(text):
        n = min(max_key, len(text) - i)
        while n >= 1:
            hit = table.get(text[i:i + n])
            if hit is not None:
                out.append(hit)
                i += n
                break
            n -= 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def build(chain):
    return [load_stage(files) for files in chain]


def main():
    trad = build(TO_TRADITIONAL)
    simp = build(TO_SIMPLIFIED)

    def s2t(t):
        for stage in trad:
            t = apply_stage(t, stage)
        return t

    def t2s(t):
        for stage in simp:
            t = apply_stage(t, stage)
        return t

    # 簡→繁：前四句是一字多繁的歧義字，後三句是台灣用詞
    cases_s2t = [
        "头发很长",
        "干燥的干部干活",
        "我发现了",
        "着急地看着",
        "这个软件的质量不错",
        "打印机的信息",
        "请问洗手间在哪里",
        "我不吃辣的东西",
        "多少钱",
    ]
    # 繁→簡：語音辨識吐給 ML Kit 之前的那一刀
    cases_t2s = [
        "請問洗手間在哪裡",
        "這個軟體的品質不錯",
        "頭髮很長",
        "我不吃辣的東西",
        "印表機沒有墨水了",
        "我想搭計程車去機場",
        "這支手機的螢幕很亮",
    ]

    lines = ["字典檔：" + ", ".join(sorted(os.listdir(ASSETS))), ""]
    lines.append("各段條目數（簡→繁）：" + ", ".join(
        str(len(t)) + " maxKey=" + str(m) for t, m in trad))
    lines.append("各段條目數（繁→簡）：" + ", ".join(
        str(len(t)) + " maxKey=" + str(m) for t, m in simp))
    lines.append("")
    lines.append("== 簡→繁（台灣） ==")
    for c in cases_s2t:
        lines.append(c + "  ->  " + s2t(c))
    lines.append("")
    lines.append("== 繁→簡 ==")
    for c in cases_t2s:
        lines.append(c + "  ->  " + t2s(c))
    lines.append("")
    lines.append("== 來回一趟是否還原 ==")
    for c in cases_t2s:
        back = s2t(t2s(c))
        lines.append(("OK  " if back == c else "差異 ") + c + "  ->  " + back)

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "verify_hanzi_out.txt")
    with open(out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print("wrote", out)


if __name__ == "__main__":
    main()
