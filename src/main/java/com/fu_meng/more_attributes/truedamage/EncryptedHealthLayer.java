package com.fu_meng.more_attributes.truedamage;

import com.fu_meng.more_attributes.MAConfig;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.fu_meng.more_attributes.truedamage.FieldHealthProbe.*;
import static com.fu_meng.more_attributes.truedamage.ReflectionToolkit.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageWarmup.*;

/**
 * 字符串型血量层（档 3）：血量被加密或被编解码成字符串保存时使用。
 * 加密路径：找出能让 getHealth() 变化的密文字段或同步项，现场确认加解密方向后整串回写。
 * 编解码路径：确认数值与字符串的编解码方法对，改写主字段并注销外部权威缓存，避免被回灌。
 */
final class EncryptedHealthLayer {

    private static final Map<Class<?>, List<Method>> HEALTH_SIGNAL_CACHE = new ConcurrentHashMap<>();

    private static final int SIGNAL_MAX_METHODS = 96;

    private static final Map<Class<?>, CryptoPair> CRYPTO_PAIR_CACHE = new ConcurrentHashMap<>();

    /** 加密血量改写方案；只缓存跑完的发现（按类缓存） */
    private static final Map<Class<?>, List<EncryptedPlan>> ENCRYPTED_PLAN_CACHE = new ConcurrentHashMap<>();

    /** 加/解密方法对「没找到」的退避缓存：现场串可能仍是空串/退化，不能固化成永久结论 */
    private static final NegativeCache<Class<?>> CRYPTO_PAIR_NEGATIVE =
            new NegativeCache<>("加解密方法对探测");

    /** 加密血量方案「前置条件不满足」时的短退避缓存 */
    private static final NegativeCache<Class<?>> ENCRYPTED_PLAN_NEGATIVE =
            new NegativeCache<>("加密血量方案前置条件");

    /** 一次发现是否满足全部前置条件（不满足 = 「这一刻探不了」，不是「该类没有」） */
    static boolean encryptedPlanPreconditionsOk(LivingEntity victim) {
        return !cryptoPair(victim).unusable()
                && !healthSignalMethods(victim.getClass()).isEmpty()
                && victim.getMaxHealth() > 0.0F;
    }

    private static final String[] CRYPTO_PROBES = {"0", "7", "42", "1234", "9.75", "100.5"};

    private static final int CRYPTO_MAX_CANDIDATES = 128;

    private static final CryptoPair NO_CRYPTO = new CryptoPair(null, null);

    /** 加密血量方案：+1 = 已受伤量，-1 = 剩余血量 */
    record EncryptedPlan(Field field, EntityDataAccessor<?> accessor, int sign) {
    }

    /** 一对互逆的字符串方法：{@code decrypt(encrypt(明文)) == 明文} */
    record CryptoPair(Method encrypt, Method decrypt) {
        boolean unusable() {
            return encrypt == null || decrypt == null;
        }

        String encryptValue(String plain) {
            if (encrypt == null) return null;
            try {
                return encrypt.invoke(null, plain) instanceof String text ? text : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        Double decryptValue(String raw) {
            if (decrypt == null || raw == null || raw.isEmpty()) return null;
            try {
                Object plain = decrypt.invoke(null, raw);
                return plain instanceof String text ? Double.parseDouble(text) : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    static void writeEncryptedHealth(LivingEntity victim, float damage) {
        if (!(damage > 0.0F)) return;
        Class<?> type = victim.getClass();
        List<EncryptedPlan> plans = ENCRYPTED_PLAN_CACHE.get(type);
        if (plans == null) {
            // 前置条件不满足只做短退避、绝不入正缓存：首次观察可能恰逢槽位为空串的退化状态，
            // 固化后该实体类会永久失去这一层。
            if (!encryptedPlanPreconditionsOk(victim)) {
                if (!ENCRYPTED_PLAN_NEGATIVE.blocked(type)) ENCRYPTED_PLAN_NEGATIVE.markShort(type);
                return;
            }
            plans = discoverEncryptedPlans(victim);
            ENCRYPTED_PLAN_NEGATIVE.clear(type);
            // 跑到这里才算「跑完的发现」：空结果 = 结构上确实没有可用的加密血量串，按类缓存
            ENCRYPTED_PLAN_CACHE.put(type, plans);
        }
        if (plans.isEmpty()) return;

        CryptoPair pair = cryptoPair(victim);
        if (pair.unusable()) return;

        for (EncryptedPlan plan : plans) {
            StringSlot slot = resolveSlot(victim, plan);
            if (slot == null) continue;
            Double stored = pair.decryptValue(slot.read());
            if (stored == null || !Double.isFinite(stored)) continue;
            double target = Math.max(0.0, stored + plan.sign() * (double) damage);
            String encrypted = pair.encryptValue(plainText(target));
            if (encrypted == null) continue;
            slot.write(encrypted);
            return;
        }
    }

    /** 首次遇到该类实体时识别“哪个字符串是血量、其明文是什么语义” */
    static List<EncryptedPlan> discoverEncryptedPlans(LivingEntity victim) {
        CryptoPair pair = cryptoPair(victim);
        if (pair.unusable()) return List.of();
        List<Method> signals = healthSignalMethods(victim.getClass());
        if (signals.isEmpty()) return List.of();

        float maxHealth = victim.getMaxHealth();
        if (!(maxHealth > 0.0F)) return List.of();
        double limit = maxHealth * 1.5 + 1.0;

        for (StringCandidate candidate : encryptedStringCandidates(victim)) {
            String raw = candidate.slot().read();
            Double stored = pair.decryptValue(raw);
            if (stored == null || !Double.isFinite(stored)) continue;
            if (stored < -limit || stored > limit) continue;
            Integer sign = confirmEncryptedSign(victim, candidate.slot(), pair, raw, stored, signals, limit);
            if (sign == null) continue;
            return List.of(new EncryptedPlan(candidate.field(), candidate.accessor(), sign));
        }
        return List.of();
    }

    /** 行为验证：把密文改写为「明文 + delta」，按 getter 变化符号定方向，结束前还原原文 */
    static Integer confirmEncryptedSign(LivingEntity victim, StringSlot slot, CryptoPair pair,
                                                String raw, double stored, List<Method> signals, double limit) {
        double delta = Math.max(1.0, Math.abs(stored) * 0.01);
        String perturbed = pair.encryptValue(plainText(stored + delta));
        if (perturbed == null) return null;
        float maxBefore = victim.getMaxHealth();
        for (Method signal : signals) {
            Double before = invokeForNumber(signal, victim, NO_ARGS);
            if (before == null || before.isNaN() || before < -limit || before > limit) continue;
            try {
                slot.write(perturbed);
                if (!matches(victim.getMaxHealth(), maxBefore)) return null;
                Double after = invokeForNumber(signal, victim, NO_ARGS);
                if (after != null) {
                    double observed = after - before;
                    if (Math.abs(observed - delta) <= PROBE_EPSILON) return -1;
                    if (Math.abs(observed + delta) <= PROBE_EPSILON) return 1;
                }
            } finally {
                slot.write(raw);
            }
        }
        return null;
    }

    /** 实体继承链上的无参 float/double getter（按类缓存） */
    static List<Method> healthSignalMethods(Class<?> type) {
        return computeOutsideLock(HEALTH_SIGNAL_CACHE, type, () -> {
            List<Method> methods = new ArrayList<>();
            for (Class<?> current : hierarchy(type)) {
                for (Method method : declaredMethodsOf(current)) {
                    if (method.isSynthetic() || method.getParameterCount() != 0 || isNativeMember(method)) continue;
                    Class<?> returns = method.getReturnType();
                    if (returns != float.class && returns != double.class) continue;
                    try {
                        method.setAccessible(true);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    methods.add(method);
                    if (methods.size() >= SIGNAL_MAX_METHODS) return methods;
                }
            }
            return methods;
        });
    }

    /** 候选字符串位点：实体类的 String 实例字段 + 同步数据里当前值为 String 的 accessor */
    static List<StringCandidate> encryptedStringCandidates(LivingEntity victim) {
        List<StringCandidate> candidates = new ArrayList<>();
        for (Class<?> current : hierarchy(victim.getClass())) {
            for (Field field : declaredFieldsOf(current)) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                if (field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                candidates.add(new StringCandidate(field, null, new StringFieldSlot(victim, field)));
            }
        }
        SynchedEntityData entityData = victim.getEntityData();
        for (EntityDataAccessor<?> accessor : collectDataAccessors(victim.getClass())) {
            try {
                if (!(entityData.get(accessor) instanceof String)) continue;
            } catch (Throwable ignored) {
                continue;
            }
            candidates.add(new StringCandidate(null, accessor,
                    new SyncedStringSlot(entityData, rawAccessor(accessor))));
        }
        return candidates;
    }

    static StringSlot resolveSlot(LivingEntity victim, EncryptedPlan plan) {
        if (plan.field() != null) return new StringFieldSlot(victim, plan.field());
        if (plan.accessor() != null) return new SyncedStringSlot(victim.getEntityData(), rawAccessor(plan.accessor()));
        return null;
    }

    private static CryptoPair cryptoPair(LivingEntity victim) {
        Class<?> type = victim.getClass();
        CryptoPair cached = CRYPTO_PAIR_CACHE.get(type);
        if (cached != null) return cached;
        if (CRYPTO_PAIR_NEGATIVE.blocked(type)) return NO_CRYPTO;
        CryptoPair found = findCryptoPair(victim);
        if (found.unusable()) {
            // 「找不到」依赖现场串（首次观察槽位可能仍是空串），故不入正缓存，只做退避重探。
            CRYPTO_PAIR_NEGATIVE.mark(type);
            return NO_CRYPTO;
        }
        CRYPTO_PAIR_NEGATIVE.clear(type);
        CRYPTO_PAIR_CACHE.put(type, found);
        return found;
    }

    /** 在实体字节码可达的模组类里找一对互逆的静态 {@code (String) -> String} 方法 */
    static CryptoPair findCryptoPair(LivingEntity victim) {
        List<Method> candidates = stringStringMethods(victim.getClass());
        if (candidates.size() < 2) return NO_CRYPTO;
        String[] probes = cryptoProbes(victim);
        for (StringCandidate candidate : encryptedStringCandidates(victim)) {
            String raw = candidate.slot().read();
            if (raw == null || raw.isEmpty()) continue;
            for (Method decrypt : candidates) {
                if (implausibleHealth(victim, decryptAsNumber(decrypt, raw))) continue;
                for (Method encrypt : candidates) {
                    if (encrypt != decrypt && roundTripsOn(decrypt, encrypt, probes)) {
                        return new CryptoPair(encrypt, decrypt);
                    }
                }
            }
        }
        for (Method encrypt : candidates) {
            for (Method decrypt : candidates) {
                if (encrypt == decrypt) continue;
                // 两者可互相还原（对称算法）时无法区分方向，放弃
                if (roundTripsOn(decrypt, encrypt, probes) && !roundTripsOn(encrypt, decrypt, probes)) {
                    return new CryptoPair(encrypt, decrypt);
                }
            }
        }
        return NO_CRYPTO;
    }

    /** 收集实体可达模组类里的静态 {@code (String) -> String} 方法 */
    static List<Method> stringStringMethods(Class<?> type) {
        List<Method> candidates = new ArrayList<>();
        try {
            for (Class<?> clazz : referencedModClasses(type)) {
                for (Method method : declaredMethodsOf(clazz)) {
                    if (candidates.size() >= CRYPTO_MAX_CANDIDATES) return candidates;
                    if (!Modifier.isStatic(method.getModifiers()) || method.isSynthetic()
                            || isNativeMember(method)) {
                        continue;
                    }
                    if (method.getParameterCount() != 1) continue;
                    if (method.getParameterTypes()[0] != String.class || method.getReturnType() != String.class) {
                        continue;
                    }
                    if (cannotAccess(method)) continue;
                    candidates.add(method);
                }
            }
        } catch (Throwable ignored) {
            // 枚举失败时按已收集到的候选继续
        }
        return candidates;
    }

    private static Double decryptAsNumber(Method decrypt, String raw) {
        try {
            if (decrypt.invoke(null, raw) instanceof String text) return Double.parseDouble(text);
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean implausibleHealth(LivingEntity victim, Double value) {
        if (value == null || !Double.isFinite(value)) return true;
        float max = victim.getMaxHealth();
        double limit = max > 0.0F ? max * 1.5 + 1.0 : Math.abs(healthOf(victim)) * 1.5 + 1.0;
        return value < -limit || value > limit;
    }

    static String[] cryptoProbes(LivingEntity victim) {
        List<String> probes = new ArrayList<>(Arrays.asList(CRYPTO_PROBES));
        addCryptoProbe(probes, healthOf(victim));
        addCryptoProbe(probes, victim.getMaxHealth());
        addCryptoProbe(probes, victim.getMaxHealth() * 0.5);
        addCryptoProbe(probes, 0.0);
        return probes.toArray(new String[0]);
    }

    static void addCryptoProbe(List<String> probes, double value) {
        if (!Double.isFinite(value)) return;
        String text = plainText(value);
        if (!probes.contains(text)) probes.add(text);
    }

    /** 校验 {@code outer(inner(x)) == x} 对全部样本成立（inner 为加密、outer 为解密） */
    private static boolean roundTripsOn(Method outer, Method inner, String[] probes) {
        for (String probe : probes) {
            try {
                if (!(inner.invoke(null, probe) instanceof String middle)) return false;
                if (!(outer.invoke(null, middle) instanceof String back)) return false;
                if (!probe.equals(back)) return false;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    /** 数值编解码器：{@code decode(encode(x)) == x}（encode 可带随机化，故只验证单向还原） */
    record ValueCodec(Method encode, Method decode) {
    }

    record EncodedHealthPlan(ValueCodec codec, Field health, Field shadow, Method wrap) {
    }

    /** 编码血量方案按实体类缓存；空结果不缓存 */
    private static final Map<Class<?>, EncodedHealthPlan> ENCODED_HEALTH_CACHE = new ConcurrentHashMap<>();

    static final NegativeCache<Class<?>> ENCODED_HEALTH_NEGATIVE =
            new NegativeCache<>("编码血量字段");

    /** 外部权威缓存「注销口」按实体类缓存；空结果不缓存 */
    private static final Map<Class<?>, List<Method>> AUTHORITY_PURGE_CACHE = new ConcurrentHashMap<>();

    static final NegativeCache<Class<?>> AUTHORITY_PURGE_NEGATIVE =
            new NegativeCache<>("权威缓存注销口");

    private static final float[] ENCODE_PROBES = {0.0F, 1.0F, 7.0F, 42.0F, 100.5F, 1000.0F};

    /** 用实体自己的编码器改写血量主字段与镜像字段 */
    static void writeViaEncodedHealth(LivingEntity victim, float healthAfter) {
        EncodedHealthPlan plan = encodedHealthPlan(victim);
        if (plan == null) return;
        String encoded = encodeValue(plan.codec(), healthAfter);
        if (encoded == null) return;
        // 先注销外部权威缓存里该实体的登记
        purgeAuthority(victim);
        String previousHealth = readStringField(plan.health(), victim);
        String previousShadow = plan.shadow() == null ? null : readStringField(plan.shadow(), victim);
        writeStringField(plan.health(), victim, encoded);
        if (plan.shadow() != null && plan.wrap() != null) {
            String shadow = invokeWrap(plan.wrap(), encoded);
            if (shadow != null) writeStringField(plan.shadow(), victim, shadow);
        }
        float now = healthOf(victim);
        if (matches(now, healthAfter)) {
            probeLog("编码数值字段层写入生效：目标={} getHealth={}", healthAfter, now);
            return;
        }
        probeLog("编码数值字段层未生效，已还原：目标={} getHealth={}", healthAfter, now);
        writeStringField(plan.health(), victim, previousHealth);
        if (plan.shadow() != null) writeStringField(plan.shadow(), victim, previousShadow);
    }

    private static EncodedHealthPlan encodedHealthPlan(LivingEntity victim) {
        Class<?> type = victim.getClass();
        EncodedHealthPlan cached = ENCODED_HEALTH_CACHE.get(type);
        if (cached != null) return cached;
        if (ENCODED_HEALTH_NEGATIVE.blocked(type)) return null;
        EncodedHealthPlan found;
        try {
            found = discoverEncodedHealth(victim);
        } catch (Throwable ignored) {
            ENCODED_HEALTH_NEGATIVE.mark(type);
            return null;
        }
        if (found != null) {
            ENCODED_HEALTH_CACHE.put(type, found);
            ENCODED_HEALTH_NEGATIVE.clear(type);
        } else {
            // 负结果退避：发现过程要枚举继承链方法并真调用模组编解码器
            ENCODED_HEALTH_NEGATIVE.mark(type);
        }
        return found;
    }

    /** 识别「主字段 + 镜像字段 + 编解码器」三件套，全部靠行为验证 */
    static EncodedHealthPlan discoverEncodedHealth(LivingEntity victim) {
        float health = healthOf(victim);
        if (Float.isNaN(health) || health <= 0.0F) return null;
        ValueCodec codec = valueCodec(victim);
        if (codec == null) return null;

        Field main = null;
        outer:
        for (Class<?> current : hierarchy(victim.getClass())) {
            for (Field field : declaredFieldsOf(current)) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                if (confirmsValueField(victim, field, codec, health)) {
                    main = field;
                    break outer;
                }
            }
        }
        if (main == null) return null;

        Field shadow = null;
        Method wrap = null;
        String mainRaw = readStringField(main, victim);
        if (mainRaw != null) {
            List<Method> wrappers = stringWrappers(victim.getClass());
            outer2:
            for (Class<?> current : hierarchy(victim.getClass())) {
                for (Field field : declaredFieldsOf(current)) {
                    if (field == main || field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType() != String.class) continue;
                    try {
                        field.setAccessible(true);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    String otherRaw = readStringField(field, victim);
                    if (otherRaw == null) continue;
                    for (Method candidate : wrappers) {
                        if (otherRaw.equals(invokeWrap(candidate, mainRaw))) {
                            shadow = field;
                            wrap = candidate;
                            break outer2;
                        }
                    }
                }
            }
        }
        return new EncodedHealthPlan(codec, main, shadow, wrap);
    }

    static boolean confirmsValueField(LivingEntity victim, Field field, ValueCodec codec, float health) {
        String raw = readStringField(field, victim);
        if (raw == null || raw.isEmpty()) return false;
        Double stored = decodeValue(codec, raw);
        if (stored == null || !Double.isFinite(stored)) return false;
        double delta = Math.max(1.0, Math.abs(health) * 0.01);
        if (Math.abs(stored - health) > delta) return false;
        String perturbed = encodeValue(codec, health + delta);
        if (perturbed == null) return false;
        try {
            writeStringField(field, victim, perturbed);
            return matches(healthOf(victim), (float) (health + delta));
        } finally {
            writeStringField(field, victim, raw);
        }
    }

    static ValueCodec valueCodec(LivingEntity victim) {
        List<Method> encoders = new ArrayList<>();
        List<Method> decoders = new ArrayList<>();
        for (Class<?> current : hierarchy(victim.getClass())) {
            for (Method method : declaredMethodsOf(current)) {
                if (method.isSynthetic() || !Modifier.isStatic(method.getModifiers()) || isNativeMember(method)) {
                    continue;
                }
                if (method.getParameterCount() != 1) continue;
                Class<?> param = method.getParameterTypes()[0];
                Class<?> returns = method.getReturnType();
                if (cannotAccess(method)) continue;
                if (returns == String.class && (param == float.class || param == double.class)) {
                    encoders.add(method);
                } else if (param == String.class && (returns == float.class || returns == double.class)) {
                    decoders.add(method);
                }
            }
        }
        if (encoders.isEmpty() || decoders.isEmpty()) return null;
        float[] probes = encodeProbes(victim);
        for (StringCandidate candidate : encryptedStringCandidates(victim)) {
            String raw = candidate.slot().read();
            if (raw == null || raw.isEmpty()) continue;
            for (Method decoder : decoders) {
                if (implausibleHealth(victim, decodeAsNumber(decoder, raw))) continue;
                for (Method encoder : encoders) {
                    if (codecRoundTripsOn(encoder, decoder, probes)) return new ValueCodec(encoder, decoder);
                }
            }
        }
        for (Method encoder : encoders) {
            for (Method decoder : decoders) {
                if (codecRoundTripsOn(encoder, decoder, probes)) return new ValueCodec(encoder, decoder);
            }
        }
        return null;
    }

    private static Double decodeAsNumber(Method decoder, String raw) {
        try {
            if (decoder.invoke(null, raw) instanceof Number number) return number.doubleValue();
        } catch (Throwable ignored) {
            // 解不出来即视为不是解码口
        }
        return null;
    }

    static float[] encodeProbes(LivingEntity victim) {
        double health = healthOf(victim);
        double max = maxHealthOf(victim);
        double[] candidates = {
                health, max, max * 0.5,
                health - 1.0, health + 1.0,
                ENCODE_PROBES[0], ENCODE_PROBES[1], ENCODE_PROBES[2], ENCODE_PROBES[3],
                ENCODE_PROBES[4], ENCODE_PROBES[5]
        };
        float[] probes = new float[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            probes[i] = Double.isFinite(candidates[i]) ? (float) candidates[i] : 0.0F;
        }
        return probes;
    }

    static boolean codecRoundTripsOn(Method encoder, Method decoder, float[] probes) {
        for (float probe : probes) {
            try {
                Object middle = encoder.invoke(null, boxNumber(encoder.getParameterTypes()[0], probe));
                if (!(middle instanceof String text)) return false;
                Object back = decoder.invoke(null, text);
                if (!(back instanceof Number number)) return false;
                if (Math.abs(number.doubleValue() - probe) > PROBE_EPSILON) return false;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    static String encodeValue(ValueCodec codec, double value) {
        if (codec == null) return null;
        try {
            Class<?> param = codec.encode().getParameterTypes()[0];
            return codec.encode().invoke(null, boxNumber(param, value)) instanceof String text ? text : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Double decodeValue(ValueCodec codec, String raw) {
        if (codec == null || raw == null || raw.isEmpty()) return null;
        try {
            return codec.decode().invoke(null, raw) instanceof Number number ? number.doubleValue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 实体继承链上的静态 {@code (String) -> String} 方法（镜像字段的包装器） */
    static List<Method> stringWrappers(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> current : hierarchy(type)) {
            for (Method method : declaredMethodsOf(current)) {
                if (method.isSynthetic() || !Modifier.isStatic(method.getModifiers()) || isNativeMember(method)) {
                    continue;
                }
                if (method.getParameterCount() != 1) continue;
                if (method.getParameterTypes()[0] != String.class || method.getReturnType() != String.class) continue;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                methods.add(method);
            }
        }
        return methods;
    }

    static String invokeWrap(Method wrap, String value) {
        if (wrap == null) return null;
        return invokeObject(wrap, null, new Object[]{value}) instanceof String text ? text : null;
    }

    static String readStringField(Field field, Object owner) {
        try {
            return field.get(owner) instanceof String text ? text : null;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    @SuppressWarnings("CatchMayIgnoreException")
    static void writeStringField(Field field, Object owner, String value) {
        try {
            field.set(owner, value);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            // 写入失败即放弃
        }
    }

    // 外部权威缓存注销

    static void purgeAuthority(LivingEntity victim) {
        for (Method purge : authorityPurges(victim)) {
            invokeQuietly(purge, new Object[]{victim});
        }
    }

    static List<Method> authorityPurges(LivingEntity victim) {
        Class<?> type = victim.getClass();
        List<Method> cached = AUTHORITY_PURGE_CACHE.get(type);
        if (cached != null) return cached;
        if (AUTHORITY_PURGE_NEGATIVE.blocked(type)) return List.of();
        // 收割未就绪不能跳过本层，只影响空结论是否进入退避
        boolean complete = seedHarvestReady(victim);
        List<Method> found = new ArrayList<>();
        float anchor = currentHealth(victim);
        if (!Float.isNaN(anchor)) {
            for (Class<?> clazz : healthCoreCandidates(victim).classes()) {
                if (clazz == null || isVanillaClass(clazz) || skipDeepClass(clazz)) continue;
                Method reader = authorityReader(clazz, victim, anchor);
                if (reader == null) continue;
                found.addAll(authorityRemovers(clazz, victim, reader));
            }
        }
        if (!found.isEmpty()) {
            AUTHORITY_PURGE_CACHE.put(type, found);
            AUTHORITY_PURGE_NEGATIVE.clear(type);
        } else {
            // 负结果退避：注销口探测要在上千候选类上找「读口 + 注销口」并真调用，不缓存会每次命中重扫
            markDiscoveryMiss(AUTHORITY_PURGE_NEGATIVE, type, complete);
        }
        return found;
    }

    /** 静态读口：接收单个实体、返回其血量（与锚点一致） */
    static Method authorityReader(Class<?> clazz, LivingEntity victim, float anchor) {
        for (Method method : declaredStaticMethods(clazz)) {
            if (method.getParameterCount() != 1) continue;
            Class<?> returns = method.getReturnType();
            if (returns != float.class && returns != double.class) continue;
            if (!method.getParameterTypes()[0].isInstance(victim)) continue;
            Double value = invokeForNumber(method, null, new Object[]{victim});
            if (value != null && !value.isNaN()
                    && Math.abs(value - anchor) <= Math.max(1.0, Math.abs(anchor) * 0.01)) {
                return method;
            }
        }
        return null;
    }

    /** 静态注销口：调用后上面那个读口对本实体返回 NaN */
    static List<Method> authorityRemovers(Class<?> clazz, LivingEntity victim, Method reader) {
        List<Method> removers = new ArrayList<>();
        for (Method method : declaredStaticMethods(clazz)) {
            if (method == reader || method.getParameterCount() != 1) continue;
            Class<?> returns = method.getReturnType();
            if (returns != void.class && returns != boolean.class) continue;
            if (!method.getParameterTypes()[0].isInstance(victim)) continue;
            Object[] args = {victim};
            if (!invokeQuietly(method, args)) continue;
            Double after = invokeForNumber(reader, null, args);
            if (after != null && after.isNaN()) {
                removers.add(method);
            }
        }
        return removers;
    }

    // 数值编解码血量（档 3）：血量被编成 long/int 之类的数值载体

    /** 数值位点来源：同步项或字段（按实体重建位点用） */
    private record NumericSource(EntityDataAccessor<?> accessor, Field field) {
    }

    private record NumericCodec(Method encode, Method decode) {
    }

    /** 数值编解码血量方案：直接位点 / 派生位点（总量 − 已消耗） */
    private record NumericPlan(NumericCodec codec, List<NumericSource> direct,
                               NumericSource total, NumericSource consumed) {
    }

    private static final Map<Class<?>, NumericPlan> NUMERIC_HEALTH_CACHE = new ConcurrentHashMap<>();

    static final NegativeCache<Class<?>> NUMERIC_HEALTH_NEGATIVE =
            new NegativeCache<>("数值编解码血量");

    private static final double[] NUMERIC_PROBES = {0.0D, 1.0D, 7.0D, 42.5D, 100.5D, 1000.0D};

    /**
     * 数值编解码血量层：血量不是明文数字，而是模组自己 encode/decode 过的 long/int。
     * 典型形状：{@code getHealth() = valueDeEnc(IS_NAN) - valueDeEnc(GET_TOKEN_DAMAGE)}，
     * 两个 synced Long 分别存「总量」和「已消耗」的密文；{@link #writeViaEncodedHealth} 只认字符串载体，
     * 浮点探针又探不到编码后的值，故单列此层。
     * 步骤：1) 用实体自己的方法对做往返验证拿到 (encode, decode)；2) decode(位点值) 等于权威血量 → 直接位点；
     * 3) decode(A) - decode(B) == 血量 → 派生位点（总量 A / 已消耗 B）；
     * 4) 写入 encode(目标) 或 encode(总量 - 目标)，回读验证，失败整体还原。
     */
    static void writeViaNumericCodec(LivingEntity victim, float healthAfter) {
        float health = authoritativeHealth(victim, healthOf(victim), readVanillaHealthData(victim));
        if (Float.isNaN(health) || health <= 0.0F) return;
        NumericPlan plan = numericPlan(victim, health);
        if (plan == null) return;

        List<HealthSlot> directSlots = new ArrayList<>();
        for (NumericSource source : plan.direct()) {
            HealthSlot slot = numericSlot(victim, source);
            if (slot != null) directSlots.add(slot);
        }
        HealthSlot totalSlot = numericSlot(victim, plan.total());
        HealthSlot consumedSlot = numericSlot(victim, plan.consumed());
        boolean derived = totalSlot != null && consumedSlot != null;
        if (directSlots.isEmpty() && !derived) return;

        // 写入前快照，验证不通过时整体还原（编码位点写错会污染模组的权威存储）
        double[] backup = new double[directSlots.size() + 2];
        int n = 0;
        for (HealthSlot slot : directSlots) backup[n++] = numericRead(slot);
        backup[n++] = derived ? numericRead(totalSlot) : Double.NaN;
        backup[n] = derived ? numericRead(consumedSlot) : Double.NaN;

        boolean wrote = false;
        try {
            if (!directSlots.isEmpty()) {
                if (invokeCodec(victim, plan.codec().encode(), healthAfter) instanceof Number wire) {
                    for (HealthSlot slot : directSlots) {
                        slot.write(wire.doubleValue());
                    }
                    wrote = true;
                }
            }
            if (derived) {
                double totalDecoded = decodeWire(plan.codec(), victim, numericRead(totalSlot));
                if (!Double.isNaN(totalDecoded)) {
                    double consumedTarget = Math.min(Math.max(totalDecoded - healthAfter, 0.0D), totalDecoded);
                    if (invokeCodec(victim, plan.codec().encode(), consumedTarget) instanceof Number wire) {
                        consumedSlot.write(wire.doubleValue());
                        wrote = true;
                    }
                }
            }
        } catch (Throwable t) {
            rethrowIfFatal(t);
        }
        float now = authoritativeHealth(victim, healthOf(victim), readVanillaHealthData(victim));
        if (wrote && !Float.isNaN(now) && matches(now, healthAfter)) {
            probeLog("数值编解码血量层写入生效：目标={} getHealth={}", healthAfter, now);
            return;
        }
        int k = 0;
        for (HealthSlot slot : directSlots) numericRestore(slot, backup[k++]);
        if (derived) {
            numericRestore(totalSlot, backup[k++]);
            numericRestore(consumedSlot, backup[k]);
        }
        probeLog("数值编解码血量层未生效，已还原（{}）目标={} 当前={}",
                victim.getClass().getName(), healthAfter, now);
    }

    /** 按类缓存数值编解码方案；探不到时退避（发现过程要扫方法表并反复试调用） */
    private static NumericPlan numericPlan(LivingEntity victim, float health) {
        Class<?> type = victim.getClass();
        NumericPlan cached = NUMERIC_HEALTH_CACHE.get(type);
        if (cached != null) return cached;
        if (NUMERIC_HEALTH_NEGATIVE.blocked(type)) return null;
        NumericPlan found = null;
        try {
            found = discoverNumericPlan(victim, health);
        } catch (Throwable t) {
            rethrowIfFatal(t);
        }
        if (found != null) {
            NUMERIC_HEALTH_CACHE.put(type, found);
            NUMERIC_HEALTH_NEGATIVE.clear(type);
            if (MAConfig.debugProbes) {
                probeLog("数值编解码血量层：{} 方案命中（直接位点 {} 派生位点 {}）", type.getName(),
                        found.direct().size(), found.total() != null);
            }
        } else {
            NUMERIC_HEALTH_NEGATIVE.mark(type);
        }
        return found;
    }

    private static NumericPlan discoverNumericPlan(LivingEntity victim, float health) {
        NumericCodec codec = discoverNumericCodec(victim, health);
        if (codec == null) return null;
        List<NumericSource> sources = numericSources(victim);
        if (sources.isEmpty() || sources.size() > 32) return null;
        List<NumericSource> direct = new ArrayList<>();
        List<NumericSource> withDecoded = new ArrayList<>();
        List<Double> decoded = new ArrayList<>();
        for (NumericSource source : sources) {
            HealthSlot slot = numericSlot(victim, source);
            if (slot == null) continue;
            double raw = numericRead(slot);
            if (Double.isNaN(raw)) continue;
            double value = decodeWire(codec, victim, raw);
            if (Double.isNaN(value) || !Double.isFinite(value)) continue;
            withDecoded.add(source);
            decoded.add(value);
            if (matches((float) value, health)) direct.add(source);
        }
        // 派生形状：decode(总量) − decode(已消耗) == 当前血量
        for (int i = 0; i < decoded.size(); i++) {
            for (int j = 0; j < decoded.size(); j++) {
                if (i == j) continue;
                double total = decoded.get(i);
                double consumed = decoded.get(j);
                if (total < consumed) continue;
                if (matches((float) (total - consumed), health)) {
                    return new NumericPlan(codec, direct, withDecoded.get(i), withDecoded.get(j));
                }
            }
        }
        if (!direct.isEmpty()) return new NumericPlan(codec, direct, null, null);
        return null;
    }

    /** 收集数值位点来源：模组自定义的同步项 + 类字段（浮点与名字像血量的整型/包装字段） */
    private static List<NumericSource> numericSources(LivingEntity victim) {
        List<NumericSource> sources = new ArrayList<>();
        SynchedEntityData entityData = victim.getEntityData();
        for (EntityDataAccessor<?> accessor : collectDataAccessors(victim.getClass())) {
            if (dataAccessorSlot(entityData, accessor) != null) {
                sources.add(new NumericSource(accessor, null));
            }
        }
        for (Field field : collectCandidateFields(victim.getClass())) {
            sources.add(new NumericSource(null, field));
        }
        return sources;
    }

    private static HealthSlot numericSlot(LivingEntity victim, NumericSource source) {
        if (source == null) return null;
        try {
            if (source.accessor() != null) {
                return dataAccessorSlot(victim.getEntityData(), source.accessor());
            }
            if (source.field() != null) {
                return fieldSlot(victim, source.field());
            }
        } catch (Throwable t) {
            rethrowIfFatal(t);
        }
        return null;
    }

    private static double numericRead(HealthSlot slot) {
        try {
            return slot.read();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return Double.NaN;
        }
    }

    private static void numericRestore(HealthSlot slot, double value) {
        if (slot == null || Double.isNaN(value)) return;
        try {
            slot.write(value);
        } catch (Throwable t) {
            rethrowIfFatal(t);
        }
    }

    /**
     * 找实体的数值编解码方法对：encode(数值) → 载体，decode(载体) → 数值，且 {@code decode(encode(x)) == x}。
     * 方法可以是实例方法（如 ashes 的 {@code valueEnc/valueDeEnc}）也可以是静态工具方法。
     */
    private static NumericCodec discoverNumericCodec(LivingEntity victim, float health) {
        List<Method> encodes = new ArrayList<>();
        List<Method> decodes = new ArrayList<>();
        for (Class<?> clazz : hierarchy(victim.getClass())) {
            if (isVanillaClass(clazz)) continue;
            Method[] methods;
            try {
                methods = declaredMethodsOf(clazz);
            } catch (Throwable ignored) {
                continue;
            }
            for (Method method : methods) {
                if (method.isSynthetic() || method.getParameterCount() != 1) continue;
                Class<?> param = method.getParameterTypes()[0];
                Class<?> returns = method.getReturnType();
                // isHealthCapableType（TrueDamageRuntime）覆盖 long/int/short/byte/float/double 及其 6 个包装类
                if ((param == float.class || param == double.class) && isHealthCapableType(returns)) {
                    encodes.add(method);
                } else if (isHealthCapableType(param) && (returns == float.class || returns == double.class)) {
                    decodes.add(method);
                }
            }
        }
        if (encodes.isEmpty() || decodes.isEmpty()) return null;
        int limit = 0;
        for (Method encode : encodes) {
            if (++limit > 24) break;
            if (cannotAccess(encode)) continue;
            for (Method decode : decodes) {
                if (decode == encode || cannotAccess(decode)) continue;
                if (numericRoundTrips(victim, encode, decode, health)) {
                    return new NumericCodec(encode, decode);
                }
            }
        }
        return null;
    }

    /** 往返验证：对一组样本都与原值一致（容差按相对 1%）才算可用 */
    private static boolean numericRoundTrips(LivingEntity victim, Method encode, Method decode, float health) {
        for (double probe : NUMERIC_PROBES) {
            if (!roundTripsOn(victim, encode, decode, probe)) return false;
        }
        return roundTripsOn(victim, encode, decode, Math.max(1.0D, health));
    }

    private static boolean roundTripsOn(LivingEntity victim, Method encode, Method decode, double value) {
        Object wire = invokeCodec(victim, encode, value);
        if (!(wire instanceof Number number)) return false;
        double back = decodeWire(new NumericCodec(encode, decode), victim, number.doubleValue());
        if (Double.isNaN(back)) return false;
        double tolerance = Math.max(0.05D, Math.abs(value) * 0.01D);
        return Math.abs(back - value) <= tolerance;
    }

    /** 调用 encode（实例方法绑在实体上） */
    private static Object invokeCodec(LivingEntity victim, Method method, double value) {
        Object arg = boxNumericArg(method.getParameterTypes()[0], value);
        if (arg == null) return null;
        try {
            return Modifier.isStatic(method.getModifiers()) ? method.invoke(null, arg) : method.invoke(victim, arg);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    private static double decodeWire(NumericCodec codec, LivingEntity victim, double wire) {
        Object result = invokeCodec(victim, codec.decode(), wire);
        return result instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    /** 按形参类型装箱数值（decode 的形参可能是 Long，必须给包装类型） */
    private static Object boxNumericArg(Class<?> type, double value) {
        if (type == long.class) return Math.round(value);
        if (type == int.class) return (int) Math.round(value);
        if (type == short.class) return (short) Math.round(value);
        if (type == byte.class) return (byte) Math.round(value);
        if (type == float.class) return (float) value;
        if (type == double.class) return value;
        if (type == Long.class) return Math.round(value);
        if (type == Integer.class) return (int) Math.round(value);
        if (type == Short.class) return (short) Math.round(value);
        if (type == Byte.class) return (byte) Math.round(value);
        if (type == Float.class) return (float) value;
        if (type == Double.class) return value;
        return null;
    }
}
