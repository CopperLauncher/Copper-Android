package net.kdt.pojavlaunch.modloaders.bta;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BuiltinLWJGLVersion {
    private static final String LWJGL_VERSION_CLASS = "org/lwjgl/Version.class";

    public final int lwjglMajor, lwjglMinor, lwjglPatch;

    private BuiltinLWJGLVersion(int lwjglMajor, int lwjglMinor, int lwjglPatch) {
        this.lwjglMajor = lwjglMajor;
        this.lwjglMinor = lwjglMinor;
        this.lwjglPatch = lwjglPatch;
    }

    public boolean isValid() {
        return lwjglMajor != -1 && lwjglMinor != -1 && lwjglPatch != -1;
    }

    @NonNull
    @Override
    public String toString() {
        if(!isValid()) return "<invalid>";
        return lwjglMajor+"."+lwjglMinor+"."+lwjglPatch;
    }

    private static int objectToInt(Object obj) {
        if(obj instanceof Integer) return (int) obj;
        if(obj instanceof Float) return (int) ((float)obj);
        if(obj instanceof Long) return (int) ((long)obj);
        if(obj instanceof Double) return (int) ((double)obj);
        if(obj instanceof String) return Integer.parseInt((String)obj);
        return -1;
    }

    private static BuiltinLWJGLVersion findVersion(InputStream inputStream) throws IOException, ConstantFieldReader.ClassFormatException {
        ConstantFieldReader fieldReader = new ConstantFieldReader().read(inputStream);
        int major = objectToInt(fieldReader.getFieldValue("VERSION_MAJOR"));
        int minor = objectToInt(fieldReader.getFieldValue("VERSION_MINOR"));
        int patch = objectToInt(fieldReader.getFieldValue("VERSION_REVISION"));
        Log.i("BuiltinLWJGLVersion", "Detected version: "+major+"."+minor+"."+patch);
        return new BuiltinLWJGLVersion(major, minor, patch);
    }

    public static BuiltinLWJGLVersion detect(File jarFile) throws IOException, ConstantFieldReader.ClassFormatException {
        try(ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(jarFile))) {
            ZipEntry zipEntry;
            while((zipEntry = zipInputStream.getNextEntry()) != null) {
                String name = zipEntry.getName();
                if(!name.equals(LWJGL_VERSION_CLASS)) continue;
                return findVersion(zipInputStream);
            }
        }
        return null;
    }
}
