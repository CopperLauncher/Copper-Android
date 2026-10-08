package net.kdt.pojavlaunch.game.renderer.impl;

import android.content.Context;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.game.renderer.RenderSpec;
import net.kdt.pojavlaunch.game.renderer.def.Renderers;
import net.kdt.pojavlaunch.utils.JREUtils;

import java.io.File;
import java.util.Map;

import git.artdeell.mojo.R;
import git.artdeell.mojoexec.MojoExec;

/**
 * RustGL RenderSpec. RustGL (https://github.com/whaltermc/rust-renderer) is a desktop OpenGL to
 * OpenGL ES 3 translation layer written in Rust, shipped as {@code librust_gl.so} inside the
 * rust-renderer AAR (see app_pojavlauncher/libs, downloaded by the CI workflow).
 * <p>
 * The library is a MobileGL-style single .so: it exports the GL entry points and also re-exports
 * EGL (forwarding to the system libEGL), so it is handed to MojoExec as the EGL library, while
 * the GLFW bridge is forced to create a GLES 3 context (the library forwards eglBindAPI to the
 * system EGL, which only knows OpenGL ES).
 */
public class RustRenderSpec implements RenderSpec {
    private static final String JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS";
    // Unresolved GL functions must not turn into a native SIGSEGV (e.g. the null fog buffer on 1.16)
    private static final String LWJGL_NO_CHECKS = "-Dorg.lwjgl.util.NoChecks=true";

    public String library() {
        return "librust_gl.so";
    }
    public boolean compatibleDevice(Context context) {
        return JREUtils.getDetectedVersion() >= 3 && new File(Tools.NATIVE_LIB_DIR, this.library()).exists();
    }
    public String name() {
        return "RustGL";
    }
    public int displayName() {
        return R.string.mcl_setting_renderer_rust;
    }
    public String tag() {
        return Renderers.RUST_RENDERER;
    }
    public void setupEnvironment(Context context, Map<String, String> envMap) {
        envMap.put("LIBGL_ES", "3");
        // The GLES backend is the only one that actually draws Minecraft frames
        envMap.put("RENDERER_BACKEND", "gles");
        // Advertise OpenGL 4.4 core, required for Minecraft/Iris version checks to pass
        envMap.put("RENDERER_SPOOF_GL", "1");

        String javaToolOptions = System.getenv(JAVA_TOOL_OPTIONS);
        if (javaToolOptions == null || javaToolOptions.isEmpty()) {
            envMap.put(JAVA_TOOL_OPTIONS, LWJGL_NO_CHECKS);
        } else if (!javaToolOptions.contains("org.lwjgl.util.NoChecks")) {
            envMap.put(JAVA_TOOL_OPTIONS, javaToolOptions + " " + LWJGL_NO_CHECKS);
        }
    }
    public boolean setupRenderer() {
        // librust_gl.so only depends on public system libraries (libEGL/libGLESv3) and sits in the
        // app's native library directory, so no linker namespace bypass is needed (same as native LTW).
        // GLES 3 context is forced as the library forwards EGL calls to the system EGL.
        return MojoExec.prepareEgl(library(), false, true, 3);
    }
}
