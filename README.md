# More Attributes

为 Minecraft 1.20.1 Forge 添加丰富的属性系统，包含命中/闪避、伤害减免、暴击、生命窃取、真实伤害等27种属性。

## 项目概述

- **模组ID**: `more_attributes`
- **Minecraft版本**: 1.20.1
- **Forge版本**: 47.4.21
- **许可证**: [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
- **作者**: Fu_Meng

## 核心属性

### 对所有生物生效

| 属性名称    | 标识符                              | 默认值                 |
|---------|----------------------------------|---------------------|
| 受到伤害上限  | `incoming_damage_cap`            | 0.0 (范围: 0.0 ~ ∞)   |
| 物理伤害减免  | `physical_damage_reduction`      | 0.0 (范围: -∞ ~ 1.0)  |
| 魔法伤害减免  | `magic_damage_reduction`         | 0.0 (范围: -∞ ~ 1.0)  |
| 环境伤害减免  | `environmental_damage_reduction` | 0.0 (范围: -∞ ~ 1.0)  |
| 伤害减免    | `damage_reduction`               | 0.0 (范围: -∞ ~ 1.0)  |
| 魔法护甲值   | `magic_armor`                    | 0                   |
| 魔法护甲韧性  | `magic_armor_toughness`          | 0                   |
| 闪避率     | `evasion`                        | 0.0 (范围: 0.0 ~ 1.0) |
| 命中率     | `hit_rate`                       | 1.0                 |
| 最终命中率   | `final_hit_rate`                 | 0.0 (范围: -∞ ~ ∞)    |
| 最终闪避率   | `final_evasion`                  | 0.0 (范围: -∞ ~ ∞)    |
| 削减无敌时间  | `ignore_invincible_time`         | 0.0 (范围: -∞ ~ 1.0)  |
| 无敌时间    | `invincible_time`                | 1.0                 |
| 箭矢伤害    | `arrow_damage`                   | 1.0                 |
| 箭矢速度    | `arrow_velocity`                 | 1.0                 |
| 暴击率     | `critical_chance`                | 0.0                 |
| 暴击伤害    | `critical_damage`                | 1.5                 |
| 真实伤害转化率 | `true_damage_ratio`              | 0.0                 |
| 额外真实伤害  | `extra_true_damage`              | 0.0                 |
| 魔法伤害转化率 | `magic_damage_ratio`             | 0.0                 |
| 额外魔法伤害  | `extra_magic_damage`             | 0.0                 |
| 生命窃取    | `life_steal`                     | 0.0                 |
| 再生      | `health_regen`                   | 0.0                 |

### 仅对玩家生效

| 属性名称 | 标识符            | 默认值 |
|------|----------------|-----|
| 进食速度 | `eating_speed` | 1.0 |
| 拉弓速度 | `draw_speed`   | 1.0 |
| 饥饿速率 | `hunger_rate`  | 1.0 |
| 挖掘速度 | `mining_speed` | 1.0 |

## 伤害类型定义

### 物理伤害

指伤害类型**不**带有 `BYPASSES_ARMOR` tag 的伤害。这类伤害会先经过物理伤害减免计算，再应用基础护甲减伤。

### 魔法伤害

指伤害类型包含在 `more_attributes:is_magic_damage` tag 的伤害。默认包含：`minecraft:magic`、`minecraft:indirect_magic`、`irons_spellbooks:` 系列、`botania:magic`。

魔法伤害会同时受到魔法伤害减免、魔法护甲和魔法护甲韧性的减伤。

### 环境伤害

指来源于环境且**无伤害来源实体**（`source.getEntity() == null`）的伤害。

**伤害标签**：`IS_FIRE`、`IS_FALL`、`IS_EXPLOSION`、`IS_DROWNING`、`IS_FREEZING`、`IS_LIGHTNING`

**伤害类型**：`CACTUS`、`SWEET_BERRY_BUSH`、`IN_WALL`、`CRAMMING`、`FALLING_BLOCK`、`FALLING_ANVIL`、`FALLING_STALACTITE`、`FLY_INTO_WALL`、`FELL_OUT_OF_WORLD`、`OUTSIDE_BORDER`

**关键特征**：环境伤害的 `DamageSource.getEntity()` 返回 `null`，无法触发生命窃取等依赖攻击者的属性。

### 真实伤害

真实伤害无视所有护甲和伤害减免。通过两种方式产生：
- **真实伤害转化率**：将部分普通伤害转化为真实伤害
- **额外真实伤害**：直接附加的真实伤害值

**机制说明**：
- 未开启 `strongerTrueDamage` 配置时，真实伤害通过对伤害进行处理，能突破部分限伤且不改变原伤害的来源与类型。
- 开启 `strongerTrueDamage` 配置后，真实伤害会在原伤害无效时尝试使用setHealth()方法，且在伤害致死时调用die()方法。
- 无论是否开启配置，真实伤害都不会影响生物掉落物品


## 属性机制

### 强度模型

本模组采用**强度模型**处理概率类属性，实现概率的乘法叠加。

**核心公式**：
- 概率转强度：`s = -ln(1 - p)`
- 强度转概率：`p = 1 - e^(-s)`

**叠加原则**：最终强度 `S = Σ(概率转强度(p_i)) × 乘法系数`，最终概率 `P = 1 - e^(-S)`

**优势**：概率永远不会超过 100%（除非单个修饰符就达到了100%），支持负值（降低概率）。

**应用属性**：闪避率、物理/魔法/环境/全伤害减免、削减无敌时间。

### 受到伤害上限

该属性限制单次受到的最大伤害值，取以下两者的较小值：
- 所有 ADDITION 类型 modifier 的最小值（负值表示无上限）
- 所有 MULTIPLY 类型 modifier 的最小值 × 最大生命值

### 无敌时间

指原版受击后无敌时间。本模组通过以下属性调整：
- **无敌时间**（`invincible_time`）：按比例缩放无敌时间长度
- **削减无敌时间**（`ignore_invincible_time`）：攻击会削减目标的无敌时间

## 伤害处理流程

1. **LivingAttackEvent**：计算实际命中概率（命中率 × (1 - 闪避率) + 最终命中率 - 最终闪避率），判定攻击是否被闪避
2. **LivingHurtEvent**：处理无敌时间、判定暴击、应用各类伤害减免、魔法护甲计算
3. **LivingDamageEvent**：伤害上限检查、伤害转化（真实伤害/魔法伤害）、附加额外伤害、生命窃取

## Mixin 功能

- **AttributeMapMixin**：注入 `getValue` 方法，当调用 `getAttributeValue()` 时自动处理以下属性的最终值：
  - 概率类属性（物理伤害减免、魔法伤害减免、伤害减免、闪避率、削减无敌时间）：返回强度模型计算后的概率值
  - 受到伤害上限：返回实际的伤害上限值

## Tooltip 处理

以下属性在物品 tooltip 中显示为百分比形式：
- 闪避率、命中率、最终命中率、最终闪避率
- 削减无敌时间、无敌时间
- 各类伤害减免
- 真实/魔法伤害转化率
- 进食速度、拉弓速度、箭矢伤害、箭矢速度、暴击率、暴击伤害、挖掘速度

**受到伤害上限**：
- ADDITION 类型：显示具体数值
- MULTIPLY 类型：显示百分比

非 ADDITION 操作的 modifier 会追加"效力"后缀以示区分。

## 配置说明

配置文件位置：`config/more_attributes_config.toml`

| 配置项                            | 类型      | 默认值   | 说明                 |
|--------------------------------|---------|-------|--------------------|
| `hungerRateAffectRegeneration` | Boolean | false | 饥饿速率是否影响回血时的消耗     |
| `strongerTrueDamage`           | Boolean | false | 开启后会增强真实伤害的效果      |
| `unlimitedLiftSteal`           | Boolean | false | 生命窃取能否吸取比造成伤害更多的血量 |