package net.kdt.pojavlaunch.game.renderer.impl;

import android.content.Context;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.game.renderer.GameRenderer;
import net.kdt.pojavlaunch.game.renderer.RenderSpec;
import net.kdt.pojavlaunch.game.renderer.def.Renderers;
import net.kdt.pojavlaunch.game.renderer.extra.GLESProvider;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.jre.GameRunner;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import git.artdeell.mojo.R;
import git.artdeell.mojoexec.MojoExec;

/**
 * Base GLES RenderSpec. Represents a desktop OpenGL wrapper running on-top of {@link GLESProvider}
 */
public abstract class GLESRenderSpec implements RenderSpec {
    private boolean nsBypass = false;
    protected abstract int glesVersion();
    public void setupEnvironment(Context context, Map<String, String> envMap) {
        GLESProvider provider = GLESProvider.getGlesProvider(context, LauncherPreferences.PREF_USE_ANGLE, LauncherPreferences.PREF_USE_SYSTEM_ANGLE);
        Log.i("GLESRenderSpec", "Using GLESProvider: " + provider.type());
        provider.setEnvironment(envMap);
        this.nsBypass = provider.requiresNamespace();
        if (LauncherPreferences.PREF_DUMP_SHADERS)
            envMap.put("LIBGL_VGPU_DUMP", "1");
        envMap.put("force_glsl_extensions_warn", "true");
        envMap.put("allow_higher_compat_version", "true");
        envMap.put("allow_glsl_extension_directive_midshader", "true");
        // Prevent OptiFine (and other error-reporting stuff in Minecraft) from balooning the log
        envMap.put("LIBGL_NOERROR", "1");
    }
    public boolean setupRenderer() {
        return MojoExec.prepareEgl(library(), nsBypass, true, glesVersion());
    }

    public static class LTWRenderSpec extends GLESRenderSpec {
        public boolean compatibleDevice(Context context) {
            return JREUtils.getDetectedVersion() >= 3 && new File(Tools.NATIVE_LIB_DIR, this.library()).exists();
        }
        public String name() {
            return "OpenLTW";
        }
        public int displayName() {
            return R.string.mcl_setting_renderer_ltw;
        }
        public String tag() {
            return Renderers.LTW_RENDERER;
        }
        public String library() {
            return "libltw.so";
        }
        protected int glesVersion() {
            return 3;
        }
    }

    public static class GL4ESRenderSpec extends GLESRenderSpec {
        public boolean compatibleDevice(Context context) {
            return true;
        }
        public String name() {
            return "GL4ES";
        }
        public int displayName() {
            return R.string.mcl_setting_renderer_gles2_4;
        }
        public String tag() {
            return Renderers.GL4ES_RENDERER;
        }
        public String library() {
            return "libgl4es_114.so";
        }
        protected int glesVersion() {
            return 2;
        }
    }

    /**
     * SFPEW (Simple FPE Wrapper). Emulates the fixed function pipeline on top of the GLES backend
     * selected through SFPEW_EGL. Like upstream PojavLauncher, the backend is MobileGlues.
     */
    public static class SFPEWRenderSpec extends GLESRenderSpec {
        /** Backend library SFPEW wraps */
        public static final String BACKEND_LIBRARY = "libmobileglues.so";

        public boolean compatibleDevice(Context context) {
            return JREUtils.getDetectedVersion() >= 3
                    && new File(Tools.NATIVE_LIB_DIR, this.library()).exists()
                    && new File(Tools.NATIVE_LIB_DIR, BACKEND_LIBRARY).exists();
        }
        public String name() {
            return "SFPEW";
        }
        public int displayName() {
            return R.string.mcl_setting_renderer_sfpew;
        }
        public void setupEnvironment(Context context, Map<String, String> envMap) {
            // Same MG-ES config as the standalone MobileGlues renderer, since MG is the backend here
            try {
                LauncherPreferences.writeMGRendererSettings();
            } catch (IOException e) {
                Log.e("SFPEWRenderSpec", "Failed to write MG-ES renderer settings", e);
            }
            envMap.put("MG_DIR_PATH", Tools.DIR_DATA + "/MobileGlues");

            Instance instance = Instances.loadSelectedInstance();
            boolean hasAngelica = instance != null && GameRunner.hasAngelica(instance.getGameDirectory());
            // If Angelica is present, don't set SFPEW_EGL (Angelica provides its own FPE)
            if (!hasAngelica) {
                envMap.put("SFPEW_EGL", BACKEND_LIBRARY);
            }
        }
        public String tag() {
            return Renderers.SFPEW_RENDERER;
        }
        public String library() {
            return "libSimpleFPEWrapper.so";
        }
        protected int glesVersion() {
            return 3;
        }
    }
}
