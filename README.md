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

| 属性名称    | 标识符                              | 默认值 |
|---------|----------------------------------|-----|
| 受到伤害上限  | `incoming_damage_cap`            | ∞   |
| 物理伤害减免  | `physical_damage_reduction`      | 0.0 |
| 魔法伤害减免  | `magic_damage_reduction`         | 0.0 |
| 环境伤害减免  | `environmental_damage_reduction` | 0.0 |
| 伤害减免    | `damage_reduction`               | 0.0 |
| 护甲穿透    | `armor_penetration`              | 0.0 |
| 保护穿透    | `protection_penetration`         | 0.0 |
| 固定减伤    | `flat_damage_reduction`          | 0.0 |
| 魔法护甲值   | `magic_armor`                    | 0   |
| 魔法护甲韧性  | `magic_armor_toughness`          | 0   |
| 闪避率     | `evasion`                        | 0.0 |
| 命中率     | `hit_rate`                       | 1.0 |
| 最终命中率   | `final_hit_rate`                 | 0.0 |
| 最终闪避率   | `final_evasion`                  | 0.0 |
| 削减无敌时间  | `ignore_invincible_time`         | 0.0 |
| 无敌时间    | `invincible_time`                | 1.0 |
| 箭矢伤害    | `arrow_damage`                   | 1.0 |
| 箭矢速度    | `arrow_velocity`                 | 1.0 |
| 暴击率     | `critical_chance`                | 0.0 |
| 暴击伤害    | `critical_damage`                | 1.5 |
| 真实伤害转化率 | `true_damage_ratio`              | 0.0 |
| 额外真实伤害  | `extra_true_damage`              | 0.0 |
| 魔法伤害转化率 | `magic_damage_ratio`             | 0.0 |
| 额外魔法伤害  | `extra_magic_damage`             | 0.0 |
| 生命窃取    | `life_steal`                     | 0.0 |
| 再生      | `health_regen`                   | 0.0 |

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

指伤害类型在配置项 `consideredMagicDamage` 列表中的伤害。默认包含：`minecraft:magic`、`minecraft:indirect_magic`、`irons_spellbooks:` 系列、`botania:magic`。

魔法伤害会同时受到魔法伤害减免、魔法护甲和魔法护甲韧性的减伤。

### 环境伤害

符合以下伤害标签/类型列表且**无伤害来源实体**的伤害。

**伤害标签**：`IS_FIRE`、`IS_FALL`、`IS_EXPLOSION`、`IS_DROWNING`、`IS_FREEZING`、`IS_LIGHTNING`

**伤害类型**：`CACTUS`、`SWEET_BERRY_BUSH`、`IN_WALL`、`CRAMMING`、`FALLING_BLOCK`、`FALLING_ANVIL`、`FALLING_STALACTITE`、`FLY_INTO_WALL`、`FELL_OUT_OF_WORLD`、`OUTSIDE_BORDER`

**关键特征**：环境伤害的 `DamageSource.getEntity()` 返回 `null`，无法触发生命窃取等依赖攻击者的属性。若上述伤害类型存在来源实体（如玩家点燃的爆炸），则不会应用环境伤害减免。

### 真实伤害

真实伤害无视所有护甲和伤害减免。通过两种方式产生：
- **真实伤害转化率**：将部分普通伤害转化为真实伤害
- **额外真实伤害**：直接附加的真实伤害值

**机制说明**：真实伤害**不被护甲/抗性/吸收等减伤削减**，但强制写入血量的深度由 `trueDamageMode` 配置分档控制。每一档在上一档基础上增加一层兜底，档位越高覆盖的自定义生物越多、探测开销越大：

| `trueDamageMode` | 名称 | 结算方式 | 强制写入层 | 无敌帧 | 图腾 / 死亡保护 |
| --- | --- | --- | --- | --- | --- |
| `0` | 管线内（原版兼容） | 并入事件伤害 | 无 | 原版处理 | 由原版处理 |
| `1` | 原版同步数据（默认） | 独立结算 | `setHealth` + 原版同步血量 | 全额绕过 | 尊重 |
| `2` | 探针与遍历 | 独立结算 | 档 1 + 类字段探针 + 深度对象图遍历 | 全额绕过 | 尊重 |
| `3` | 绝对真伤 | 独立结算 | 档 2 + 引用类静态表（字节码 + `Unsafe`） | 全额绕过 | 绕过 |

- 档 `0`：真伤分量并入事件伤害并照常结算。虽然不被护甲等削减，但目标处于无敌帧内、或他模组在 `hurt()` 中直接取消伤害时，真伤仍会丢失。
- 档 `1`（默认）：真伤脱离伤害管线独立结算，覆盖覆写了 `setHealth` 做单次扣血上限、但底层真源头仍是原版同步血量的生物。不再考虑无敌帧，每次命中都足额结算。
- 档 `2`：在档 `1` 基础上增加纯反射兜底：扫描实体自定义字段（含「累计伤害」约定）与同步数据，并从实体出发做有界对象图遍历，覆盖把血量存在 capability 里的生物。不加载引用类、不使用 `Unsafe`。
- 档 `3`：在档 `2` 基础上解析实体字节码常量池，在可达模组的静态表里查找血量存储并直写（含镜像回滚表），覆盖血量存在「与实体毫无引用关系」的静态表里的反作弊 Boss；同时连死亡保护也一并绕过（图腾不生效，且由本模组真伤/斩杀造成的死亡不会被其他模组取消）。
- 无论处于哪一档，真实伤害都不会影响生物掉落物品。

> 旧配置 `strongerTrueDamage` 已移除：`false` 对应 `trueDamageMode = 0`，`true` 对应 `trueDamageMode = 3`。


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

### 护甲穿透 / 保护穿透

两个属性作用于攻击者，分别降低目标结算减伤时的有效护甲值与保护附魔点数，负值视作不穿透。
- **ADDITION**：固定穿透值（穿透几点护甲 / 几点保护魔咒点数）
- **MULTIPLY_BASE / MULTIPLY_TOTAL**：百分比穿透（0.4 = 穿透 40%）
- 结算顺序：先按百分比穿透，再扣除固定穿透值，结果不低于 0

护甲穿透在护甲减免结算时缩小目标的有效护甲值，保护穿透在保护附魔减免结算时缩小有效保护点数；
两者都只改写结算的「输入」，不介入原版/模组的减免公式本身，故未使用这些属性时行为与原版完全一致。
神化（attributeslib）存在时，其公式会原样消费被缩小后的护甲/保护值，因此二者的穿透会自动叠加。

### 固定减伤

作用于受害者，按固定值减少**实际受到的伤害**（如固定减伤为 1 则减少 1 点伤害），只减不增。
配置项：
- `flatDamageReductionMinDamage`：减伤后至少保留的伤害值（默认 0，即可以完全挡下）
- `flatDamageReductionAffectsEnvironmental`：是否同样减少环境伤害（默认否）

## 伤害处理流程

1. **LivingAttackEvent**：计算实际命中概率（命中率 × (1 - 闪避率) + 最终命中率 - 最终闪避率），判定攻击是否被闪避
2. **LivingHurtEvent**：处理无敌时间、判定暴击、应用各类伤害减免、魔法护甲计算
3. **LivingDamageEvent**：伤害上限检查、固定减伤、伤害转化（真实伤害/魔法伤害）、附加额外伤害、生命窃取

## Mixin 功能

- **AttributeMapMixin**：注入 `getValue` 方法，当调用 `getAttributeValue()` 时自动处理以下属性的最终值：
  - 概率类属性（物理伤害减免、魔法伤害减免、伤害减免、闪避率、削减无敌时间）：返回强度模型计算后的概率值
  - 受到伤害上限：返回实际的伤害上限值
- **LivingEntityMixin**：重定向 `getDamageAfterArmorAbsorb` 中读取的 `getArmorValue()` 与
  `getDamageAfterMagicAbsorb` 中读取的 `EnchantmentHelper.getDamageProtection(...)`，
  按攻击者的护甲穿透 / 保护穿透缩小减免结算的输入（仅当穿透值非 0 时介入）；
  只改输入、不碰 `CombatRules` 调用，因此与神化对该调用的 `@Redirect` 互不冲突，二者穿透自动叠加

## Tooltip 处理

以下属性在物品 tooltip 中显示为百分比形式：
- 闪避率、命中率、最终命中率、最终闪避率
- 削减无敌时间、无敌时间
- 各类伤害减免
- 真实/魔法伤害转化率
- 进食速度、拉弓速度、饥饿速率、箭矢伤害、箭矢速度、暴击率、暴击伤害、挖掘速度

**受到伤害上限**：
- ADDITION 类型：显示具体数值
- MULTIPLY 类型：显示百分比

非 ADDITION 操作的 modifier 会追加"效力"后缀以示区分。

## 配置说明

配置文件位置：`config/more_attributes_config.toml`

| 配置项                            | 类型           | 默认值   | 说明                 |
|--------------------------------|--------------|-------|--------------------|
| `hungerRateAffectRegeneration` | Boolean      | false | 饥饿速率是否影响回血时的消耗     |
| `trueDamageMode`               | Integer      | 1     | 真伤行为分档（0~3），详见「真实伤害」章节 |
| `unlimitedLifeSteal`           | Boolean      | false | 生命窃取能否吸取比造成伤害更多的血量 |
| `consideredMagicDamage`        | String List  | 见下    | 被视为魔法伤害的伤害类型列表     |

**consideredMagicDamage 默认值**：
```
["minecraft:magic", "minecraft:indirect_magic",
 "irons_spellbooks:fire_magic", "irons_spellbooks:ice_magic",
 "irons_spellbooks:lightning_magic", "irons_spellbooks:holy_magic",
 "irons_spellbooks:ender_magic", "irons_spellbooks:blood_magic",
 "irons_spellbooks:evocation_magic", "irons_spellbooks:poison_magic",
 "botania:magic"]
```