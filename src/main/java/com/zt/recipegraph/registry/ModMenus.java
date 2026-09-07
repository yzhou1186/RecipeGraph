package com.zt.recipegraph.registry;

import com.zt.recipegraph.RecipeGraphMod;
import com.zt.recipegraph.client.GraphTerminalScreen;
import com.zt.recipegraph.menus.GraphTerminalMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Menu type registration and screen wiring.
 */
public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, RecipeGraphMod.MOD_ID);

    public static final Supplier<MenuType<GraphTerminalMenu>> GRAPH_TERMINAL =
        MENUS.register("graph_terminal",
            () -> new MenuType<>(
                (IContainerFactory<GraphTerminalMenu>)
                    (containerId, inv, buf) -> new GraphTerminalMenu(containerId, inv, buf),
                FeatureFlagSet.of()));

    private ModMenus() {}

    /**
     * Registers the client-side screen that pairs with each menu. Called once from the main
     * mod class on the mod bus.
     */
    public static void registerScreens(IEventBus modBus) {
        modBus.addListener(ModMenus::onRegisterMenuScreens);
    }

    private static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.<GraphTerminalMenu, GraphTerminalScreen>register(GRAPH_TERMINAL.get(),
            (menu, inv, title) -> new GraphTerminalScreen(menu, inv, title));
    }
}
