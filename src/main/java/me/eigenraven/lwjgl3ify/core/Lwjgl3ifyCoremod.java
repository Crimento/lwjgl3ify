package me.eigenraven.lwjgl3ify.core;

import java.awt.Toolkit;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.Platform;
import org.lwjglx.Sys;
import org.spongepowered.asm.launch.GlobalProperties;
import org.spongepowered.asm.service.mojang.MixinServiceLaunchWrapper;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;

import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(Integer.MAX_VALUE - 2)
public class Lwjgl3ifyCoremod implements IFMLLoadingPlugin, IEarlyMixinLoader {

    public static final Logger LOGGER = LogManager.getLogger("lwjgl3ify");

    public Lwjgl3ifyCoremod() {
        Config.loadConfig();
        try {
            LaunchClassLoader launchLoader = (LaunchClassLoader) getClass().getClassLoader();
            // Packages that used to be in rt.jar
            launchLoader.addClassLoaderExclusion("com.sun");
            launchLoader.addClassLoaderExclusion("com.oracle");
            launchLoader.addClassLoaderExclusion("javax");
            launchLoader.addClassLoaderExclusion("jdk");
            launchLoader.addClassLoaderExclusion("org.ietf.jgss");
            launchLoader.addClassLoaderExclusion("org.jcp.xml.dsig.internal");
            launchLoader.addClassLoaderExclusion("org.omg");
            launchLoader.addClassLoaderExclusion("org.w3c.dom");
            launchLoader.addClassLoaderExclusion("org.xml.sax");
            launchLoader.addClassLoaderExclusion("org.hotswap.agent");
            launchLoader.addClassLoaderExclusion("org.lwjglx.debug");
        } catch (ClassCastException e) {
            LOGGER.warn(
                "Unsupported launch class loader type " + getClass().getClassLoader()
                    .getClass(),
                e);
        }
        // Ensure javax.script.ScriptEngineManager gets loaded
        try {
            Class.forName("javax.script.ScriptEngineManager");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        if (FMLLaunchHandler.side()
            .isClient()) {
            clientMacOsFix();
            Sys.initialize();
        }
    }

    private void clientMacOsFix() {
        if (Platform.get() == Platform.MACOSX) {
            Configuration.GLFW_LIBRARY_NAME.set("glfw_async");
            Configuration.GLFW_CHECK_THREAD0.set(false);
            Toolkit.getDefaultToolkit(); // Initialize AWT before GLFW
        }
    }

    @Override
    public String[] getASMTransformerClass() {
        LOGGER.info("Registering lwjgl3ify redirect transformer");

        List<String> tweakClasses = GlobalProperties.get(MixinServiceLaunchWrapper.BLACKBOARD_KEY_TWEAKCLASSES);
        if (tweakClasses != null) {
            tweakClasses.add(PostMixinTransformInjector.class.getName());
        }

        return new String[] { LwjglRedirectTransformer.class.getName(),
            UnfinalizeObjectHoldersTransformer.class.getName() };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {}

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    @Override
    public String getMixinConfig() {
        return "mixins.lwjgl3ify.early.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedCoreMods) {

        final boolean hasFastcraft = loadedCoreMods.contains("fastcraft.Tweaker");
        final boolean hasOptifine = loadedCoreMods.contains("optifine.OptiFineForgeTweaker");
        List<String> mixins = new ArrayList<>(8);
        // FML Java 9+ compatibility patches
        mixins.add("fml.ItemStackHolderRef");
        mixins.add("fml.JarDiscoverer");
        mixins.add("fml.ModVisitorAsmVersion");
        mixins.add("fml.ObjectHolderRef");
        mixins.add("fml.ObjectHolderRegistry");
        if (FMLLaunchHandler.side()
            .isClient()) {
            // STB replacements for vanilla functions
            if (Config.MIXIN_STBI_TEXTURE_LOADING) {
                LOGGER.info("Enabling STB texture loading mixin");
                mixins.add("game.MixinTextureAtlasSprite");
                mixins.add("game.MixinTextureMap");
            } else {
                LOGGER.info("Disabling STB texture loading mixin");
            }

            final boolean fcBugFixedByOF = isFastcraftVersion1_25();
            final boolean fcBugTriggered = hasFastcraft && !(hasOptifine && fcBugFixedByOF);
            if (fcBugTriggered && !Config.MIXIN_STBI_IGNORE_FASTCRAFT) {
                LOGGER.error(
                    "Not using STB stiching mixins because FastCraft is installed to prevent rapidly flashing screen. Remove FastCraft or "
                        + (!fcBugFixedByOF ? "update to FastCraft 1.25 and " : "")
                        + "add OptiFine to enable these performance-improving patches.");
            } else {
                if (Config.MIXIN_STBI_TEXTURE_STICHING) {
                    LOGGER.info("Enabling STB texture stitching mixin");
                    mixins.add("game.MixinStitcher");
                } else {
                    LOGGER.info("Disabling STB texture stitching mixin");
                }
            }
        }
        return mixins;
    }

    private static boolean isFastcraftVersion1_25() {
        // FastCraft tweaker hasn't run yet so no easy way to grab version.
        // Let's compare the hash of fastcraft.a, which contains the version string in both 1.23 and 1.25.
        try {
            byte[] bytes = Launch.classLoader.getClassBytes("fastcraft.a");

            if (bytes == null) return false;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);

            final byte[] fc125AHash = new byte[] { -125, -16, 44, -79, -105, 108, -65, -19, 56, -98, -65, -94, 0, -49,
                66, -58, -60, -39, -23, 55, 87, 127, -77, 100, 73, -92, 37, 84, 115, -114, -76, -23 };

            return Arrays.equals(fc125AHash, hash);
        } catch (Exception e) {
            return false;
        }
    }
}
