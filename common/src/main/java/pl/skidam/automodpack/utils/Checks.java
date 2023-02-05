package pl.skidam.automodpack.utils;

import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.Platform;

import static pl.skidam.automodpack.AutoModpack.preload;

public class Checks {
    public static boolean properlyLoaded() {
        try {
            if (preload) return false;
            if (Platform.getEnvironmentType().equals("SERVER")) return false;
            if (MinecraftClient.getInstance() == null) return false;
            if (MinecraftClient.getInstance().currentScreen == null) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
