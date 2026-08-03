package com.fu_meng.more_attributes;

import com.fu_meng.more_attributes.tooltip.AttributeTooltipHandler;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.util.thread.SidedThreadGroups;
import org.slf4j.Logger;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.fu_meng.more_attributes.AttributesRegistry.ATTRIBUTES;

@Mod(MoreAttributesMod.MODID)
public class MoreAttributesMod {

    public static final String MODID = "more_attributes";
    public static final Logger LOGGER = LogUtils.getLogger();

    @SuppressWarnings("removal")
    public MoreAttributesMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        MinecraftForge.EVENT_BUS.register(this);

        MAConfig.register();
        ATTRIBUTES.register(modEventBus);

        // attributeslib 存在时，条件注册 attributeslib 相关的 tooltip 监听器
        // 用 @Mod.EventBusSubscriber 会在类加载时触发 NoClassDefFoundError
        if (ModList.get().isLoaded("attributeslib")) {
            MinecraftForge.EVENT_BUS.register(AttributeTooltipHandler.ApophicTooltipCompatibility.class);
        }
    }

    private static final Collection<AbstractMap.SimpleEntry<Runnable, Integer>> workQueue = new ConcurrentLinkedQueue<>();

    public static void queueServerWork(int tick, Runnable action) {
        if (Thread.currentThread().getThreadGroup() == SidedThreadGroups.SERVER)
            workQueue.add(new AbstractMap.SimpleEntry<>(action, tick));
    }

    public static void queueServerWork(Runnable action) {
        queueServerWork(0, action);
    }


    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            List<AbstractMap.SimpleEntry<Runnable, Integer>> actions = new ArrayList<>();
            workQueue.forEach(work -> {
                work.setValue(work.getValue() - 1);
                if (work.getValue() <= 0)
                    actions.add(work);
            });
            actions.forEach(e -> e.getKey().run());
            workQueue.removeAll(actions);
        }
    }

}
