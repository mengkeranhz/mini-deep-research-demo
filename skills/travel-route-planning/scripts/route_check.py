#!/usr/bin/env python3
"""行程约束校验：绕路 / 时间缓冲 / 疲劳驾驶。

用法：python3 route_check.py < itinerary.json
输入 JSON 结构（km 为公里、*_min 为分钟、active_min 为当日可用活动时间，默认 720 即 12 小时）：
{
  "skeleton_km": 300,
  "days": [
    {
      "day": 1,
      "active_min": 720,
      "visit_min": 240,
      "meal_rest_min": 90,
      "segments": [
        {"from": "杭州", "to": "建德服务区", "km": 105, "drive_min": 85},
        {"from": "建德服务区", "to": "千岛湖中心湖区", "km": 70, "drive_min": 60}
      ]
    }
  ]
}
输出：总里程与绕路结论、逐日驾驶/缓冲结论，任一不通过以 FAIL 汇总并给出调整提示。
"""
import json
import sys

TOTAL_DETOUR_LIMIT = 0.20   # 绕路：总增幅上限
DAY_DRIVE_MAX = 240         # 疲劳：单日驾驶上限（分钟）
SEG_DRIVE_MAX = 120         # 疲劳：单段连续驾驶上限（分钟）
BUFFER_MIN = 60             # 缓冲：每日最少（分钟）
DEFAULT_ACTIVE = 720        # 每日可用活动时间（分钟，08:00-20:00）


def main():
    data = json.load(sys.stdin)
    days = data.get("days", [])
    skeleton = data.get("skeleton_km")
    fails = []

    actual = sum(s.get("km", 0) for d in days for s in d.get("segments", []))
    print(f"实际总里程: {actual:.0f} 公里（{len(days)} 天）")
    if skeleton:
        ratio = actual / skeleton - 1
        if ratio > TOTAL_DETOUR_LIMIT:
            fails.append(f"绕路校验：总增幅 {ratio:.0%} 超过 {TOTAL_DETOUR_LIMIT:.0%}，"
                         f"删除绕路补充点或调整访问顺序")
        print(f"[{'FAIL' if ratio > TOTAL_DETOUR_LIMIT else 'PASS'}] 绕路校验: "
              f"骨架 {skeleton:.0f} 公里，增幅 {ratio:.0%}（上限 {TOTAL_DETOUR_LIMIT:.0%}）")

    for d in days:
        segs = d.get("segments", [])
        drive = sum(s.get("drive_min", 0) for s in segs)
        km = sum(s.get("km", 0) for s in segs)
        longest = max((s.get("drive_min", 0) for s in segs), default=0)
        active = d.get("active_min", DEFAULT_ACTIVE)
        buffer = active - drive - d.get("visit_min", 0) - d.get("meal_rest_min", 0)
        tag = f"Day {d.get('day', '?')}"
        if drive > DAY_DRIVE_MAX:
            fails.append(f"{tag} 疲劳驾驶：单日驾驶 {drive} 分钟超上限 {DAY_DRIVE_MAX}，调整住宿点拆分行程")
        if longest > SEG_DRIVE_MAX:
            fails.append(f"{tag} 疲劳驾驶：最长单段 {longest} 分钟超上限 {SEG_DRIVE_MAX}，段间安排服务区休息")
        if buffer < BUFFER_MIN:
            fails.append(f"{tag} 缓冲不足：仅 {buffer} 分钟低于 {BUFFER_MIN}，删点或增加一天")
        verdict = "FAIL" if any(f.startswith(tag) for f in fails) else "PASS"
        print(f"[{verdict}] {tag}: 驾驶 {km:.0f} 公里 / {drive} 分钟，最长单段 {longest} 分钟，"
              f"游览 {d.get('visit_min', 0)} 分钟，缓冲 {buffer} 分钟")

    print()
    if fails:
        print("结论: FAIL — 需调整后重新校验")
        for f in fails:
            print(f"  - {f}")
    else:
        print("结论: PASS — 全部通过")


if __name__ == "__main__":
    main()
