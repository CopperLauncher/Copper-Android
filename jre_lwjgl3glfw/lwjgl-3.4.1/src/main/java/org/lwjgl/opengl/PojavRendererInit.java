package org.lwjgl.opengl;

import org.jspecify.annotations.*;

import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.SharedLibrary;

/**
 * Class for initializing renderer-specific callbacks. Allows to reliably initialize
 * any callbacks needed for renderers by using the same FunctionProvider as used for loading
 * GL symbols.
 * */
public class PojavRendererInit {

    public static void onCreateCapabilities(FunctionProvider functionProvider) {
        String rendererName = null;
        if(functionProvider instanceof SharedLibrary) {
            SharedLibrary rendererLibrary = (SharedLibrary) functionProvider;
            rendererName = rendererLibrary.getName();
        }
        if(!isValidString(rendererName)) {
            rendererName = System.getProperty("org.lwjgl.opengl.libname");
        }
        if(!isValidString(rendererName)) {
            System.out.println("PojavRendererInit: Failed to find Pojav renderer name! " +
                                "Renderer-specific initialization may not work properly");
        }
        // NOTE: hardcoded gl4es libnames - libgl4es_115.so is legacy GL4ES,
        // libng_gl4es.so is Krypton Wrapper. Both are gl4es-based and need this init.
        if(rendererName.endsWith("libgl4es_115.so") || rendererName.endsWith("libng_gl4es.so")) {
            nativeInitGl4esInternals(functionProvider);
        }
    }

    private static boolean isValidString(@Nullable String s) {
        return s != null && !s.isEmpty();
    }

    public static native void nativeInitGl4esInternals(FunctionProvider functionProvider);
}
