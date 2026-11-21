package org.lwjgl.util.tinyfd;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/** LWJGL TinyFD API routed to the launcher's Android file manager. */
public class TinyFileDialogs {
    public static final String
            tinyfd_version = "tinyfd_version",
            tinyfd_needs = "tinyfd_needs",
            tinyfd_response = "tinyfd_response",
            tinyfd_verbose = "tinyfd_verbose",
            tinyfd_silent = "tinyfd_silent",
            tinyfd_allowCursesDialogs = "tinyfd_allowCursesDialogs",
            tinyfd_forceConsole = "tinyfd_forceConsole",
            tinyfd_assumeGraphicDisplay = "tinyfd_assumeGraphicDisplay",
            tinyfd_winUtf8 = "tinyfd_winUtf8";

    private static final Map<String, String> globalChars =
            Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Integer> globalInts =
            Collections.synchronizedMap(new HashMap<>());

    static {
        globalChars.put(tinyfd_version, "3.22.1");
        globalChars.put(tinyfd_needs, "Android built-in dialogs");
        globalChars.put(tinyfd_response, "");
        globalInts.put(tinyfd_verbose, 0);
        globalInts.put(tinyfd_silent, 1);
        globalInts.put(tinyfd_allowCursesDialogs, 0);
        globalInts.put(tinyfd_forceConsole, 0);
        globalInts.put(tinyfd_assumeGraphicDisplay, 1);
        globalInts.put(tinyfd_winUtf8, 1);
        try {
            System.loadLibrary("mojort");
        } catch (Throwable t) {
            System.err.println("[TinyFileDialogs] Launcher JNI bridge is already loaded: " + t);
        }
    }

    protected TinyFileDialogs() {
        throw new UnsupportedOperationException();
    }

    public static String tinyfd_getGlobalChar(ByteBuffer name) {
        return globalChars.get(bufferToString(name));
    }

    public static String tinyfd_getGlobalChar(CharSequence name) {
        return name != null ? globalChars.get(name.toString()) : null;
    }

    public static int tinyfd_getGlobalInt(ByteBuffer name) {
        Integer value = globalInts.get(bufferToString(name));
        return value != null ? value : -1;
    }

    public static int tinyfd_getGlobalInt(CharSequence name) {
        Integer value = name != null ? globalInts.get(name.toString()) : null;
        return value != null ? value : -1;
    }

    public static int tinyfd_setGlobalInt(ByteBuffer name, int value) {
        return tinyfd_setGlobalInt(bufferToString(name), value);
    }

    public static int tinyfd_setGlobalInt(CharSequence name, int value) {
        return tinyfd_setGlobalInt(name != null ? name.toString() : null, value);
    }

    public static void tinyfd_beep() {
        bridgeBeep();
    }

    public static int tinyfd_notifyPopup(ByteBuffer title, ByteBuffer message, ByteBuffer iconType) {
        return notifyPopup(bufferToString(title), bufferToString(message), bufferToString(iconType));
    }

    public static int tinyfd_notifyPopup(CharSequence title, CharSequence message, CharSequence iconType) {
        return notifyPopup(toString(title), toString(message), toString(iconType));
    }

    public static boolean tinyfd_messageBox(ByteBuffer title, ByteBuffer message, ByteBuffer dialogType,
                                            ByteBuffer iconType, boolean defaultButton) {
        return messageBox(bufferToString(title), bufferToString(message), bufferToString(dialogType),
                bufferToString(iconType), defaultButton ? 1 : 0) == 1;
    }

    public static boolean tinyfd_messageBox(CharSequence title, CharSequence message, CharSequence dialogType,
                                            CharSequence iconType, boolean defaultButton) {
        return messageBox(toString(title), toString(message), toString(dialogType),
                toString(iconType), defaultButton ? 1 : 0) == 1;
    }

    public static int tinyfd_messageBox(ByteBuffer title, ByteBuffer message, ByteBuffer dialogType,
                                        ByteBuffer iconType, int defaultButton) {
        return messageBox(bufferToString(title), bufferToString(message), bufferToString(dialogType),
                bufferToString(iconType), defaultButton);
    }

    public static int tinyfd_messageBox(CharSequence title, CharSequence message, CharSequence dialogType,
                                        CharSequence iconType, int defaultButton) {
        return messageBox(toString(title), toString(message), toString(dialogType),
                toString(iconType), defaultButton);
    }

    private static int messageBox(String title, String message, String dialogType,
                                  String iconType, int defaultButton) {
        if (isQuery(title)) {
            replyToQuery();
            return 1;
        }
        int result = bridgeMessageBox(title, message, dialogType, iconType, defaultButton);
        return result;
    }

    public static String tinyfd_inputBox(ByteBuffer title, ByteBuffer message, ByteBuffer defaultInput) {
        return inputBox(bufferToString(title), bufferToString(message), bufferToString(defaultInput));
    }

    public static String tinyfd_inputBox(CharSequence title, CharSequence message, CharSequence defaultInput) {
        return inputBox(toString(title), toString(message), toString(defaultInput));
    }

    private static String inputBox(String title, String message, String defaultInput) {
        if (isQuery(title)) {
            replyToQuery();
            return "android";
        }
        String result = bridgeInputBox(title, message, defaultInput, defaultInput == null);
        return result;
    }

    public static String tinyfd_saveFileDialog(ByteBuffer title, ByteBuffer defaultPath,
                                               PointerBuffer filterPatterns, ByteBuffer filterDescription) {
        return saveFileDialog(bufferToString(title), bufferToString(defaultPath), filterPatterns,
                bufferToString(filterDescription));
    }

    public static String tinyfd_saveFileDialog(CharSequence title, CharSequence defaultPath,
                                               PointerBuffer filterPatterns, CharSequence filterDescription) {
        return saveFileDialog(toString(title), toString(defaultPath), filterPatterns, toString(filterDescription));
    }

    private static String saveFileDialog(String title, String defaultPath, PointerBuffer filterPatterns,
                                         String filterDescription) {
        if (isQuery(title)) {
            replyToQuery();
            return "android";
        }
        String result = bridgeSaveFileDialog(title, defaultPath, patternsToStrings(filterPatterns), filterDescription);
        return result;
    }

    public static String tinyfd_openFileDialog(ByteBuffer title, ByteBuffer defaultPath,
                                               PointerBuffer filterPatterns, ByteBuffer filterDescription,
                                               boolean allowMultiple) {
        return openFileDialog(bufferToString(title), bufferToString(defaultPath), filterPatterns,
                bufferToString(filterDescription), allowMultiple);
    }

    public static String tinyfd_openFileDialog(CharSequence title, CharSequence defaultPath,
                                               PointerBuffer filterPatterns, CharSequence filterDescription,
                                               boolean allowMultiple) {
        return openFileDialog(toString(title), toString(defaultPath), filterPatterns,
                toString(filterDescription), allowMultiple);
    }

    private static String openFileDialog(String title, String defaultPath, PointerBuffer filterPatterns,
                                         String filterDescription, boolean allowMultiple) {
        if (isQuery(title)) {
            replyToQuery();
            return "android";
        }
        String result = bridgeOpenFileDialog(title, defaultPath, patternsToStrings(filterPatterns),
                filterDescription, allowMultiple);
        return result;
    }

    public static String tinyfd_selectFolderDialog(ByteBuffer title, ByteBuffer defaultPath) {
        return selectFolderDialog(bufferToString(title), bufferToString(defaultPath));
    }

    public static String tinyfd_selectFolderDialog(CharSequence title, CharSequence defaultPath) {
        return selectFolderDialog(toString(title), toString(defaultPath));
    }

    private static String selectFolderDialog(String title, String defaultPath) {
        if (isQuery(title)) {
            replyToQuery();
            return "android";
        }
        String result = bridgeSelectFolderDialog(title, defaultPath);
        return result;
    }

    public static String tinyfd_colorChooser(ByteBuffer title, ByteBuffer defaultHex,
                                             ByteBuffer defaultRGB, ByteBuffer resultRGB) {
        return colorChooser(bufferToString(title), bufferToString(defaultHex), defaultRGB, resultRGB);
    }

    public static String tinyfd_colorChooser(CharSequence title, CharSequence defaultHex,
                                             ByteBuffer defaultRGB, ByteBuffer resultRGB) {
        return colorChooser(toString(title), toString(defaultHex), defaultRGB, resultRGB);
    }

    private static String colorChooser(String title, String defaultHex,
                                       ByteBuffer defaultRGB, ByteBuffer resultRGB) {
        if (isQuery(title)) {
            replyToQuery();
            return "android";
        }
        int r = 0, g = 0, b = 0;
        boolean parsed = false;
        if (defaultHex != null && defaultHex.length() == 7 && defaultHex.charAt(0) == '#') {
            try {
                r = Integer.parseInt(defaultHex.substring(1, 3), 16);
                g = Integer.parseInt(defaultHex.substring(3, 5), 16);
                b = Integer.parseInt(defaultHex.substring(5, 7), 16);
                parsed = true;
            } catch (NumberFormatException ignored) {
            }
        }
        if (!parsed && defaultRGB != null && defaultRGB.remaining() >= 3) {
            int position = defaultRGB.position();
            r = defaultRGB.get(position) & 0xFF;
            g = defaultRGB.get(position + 1) & 0xFF;
            b = defaultRGB.get(position + 2) & 0xFF;
        }
        String initialHex = parsed
                ? defaultHex : String.format("#%02X%02X%02X", r, g, b);
        String result = bridgeColorChooser(title, initialHex);
        if (result == null) return null;
        if (result.length() == 7 && result.charAt(0) == '#') {
            try {
                r = Integer.parseInt(result.substring(1, 3), 16);
                g = Integer.parseInt(result.substring(3, 5), 16);
                b = Integer.parseInt(result.substring(5, 7), 16);
            } catch (NumberFormatException ignored) {
                return null;
            }
        } else {
            return null;
        }
        if (resultRGB != null && resultRGB.remaining() >= 3) {
            int position = resultRGB.position();
            resultRGB.put(position, (byte) r);
            resultRGB.put(position + 1, (byte) g);
            resultRGB.put(position + 2, (byte) b);
        }
        return result;
    }

    private static int tinyfd_setGlobalInt(String name, int value) {
        if (name == null || !globalInts.containsKey(name)) return -1;
        globalInts.put(name, value);
        return value;
    }

    private static int notifyPopup(String title, String message, String iconType) {
        if (isQuery(title)) {
            replyToQuery();
            return 1;
        }
        bridgeNotifyPopup(title, message, iconType);
        return 1;
    }

    private static boolean isQuery(String title) {
        return "tinyfd_query".equals(title);
    }

    private static void replyToQuery() {
        globalChars.put(tinyfd_response, "android");
    }

    private static String[] patternsToStrings(PointerBuffer patterns) {
        if (patterns == null || !patterns.hasRemaining()) return new String[0];
        String[] result = new String[patterns.remaining()];
        int position = patterns.position();
        for (int i = 0; i < result.length; i++) {
            long address = patterns.get(position + i);
            result[i] = address == 0 ? null : MemoryUtil.memUTF8(address);
        }
        return result;
    }

    private static native String bridgeOpenFileDialog(String title, String defaultPath, String[] patterns,
                                                      String filterDescription, boolean allowMultiple);
    private static native String bridgeSaveFileDialog(String title, String defaultPath, String[] patterns,
                                                      String filterDescription);
    private static native String bridgeSelectFolderDialog(String title, String defaultPath);
    private static native String bridgeColorChooser(String title, String defaultHex);
    private static native int bridgeMessageBox(String title, String message, String dialogType,
                                               String iconType, int defaultButton);
    private static native String bridgeInputBox(String title, String message, String defaultInput,
                                                boolean password);
    private static native void bridgeNotifyPopup(String title, String message, String iconType);
    private static native void bridgeBeep();

    private static String toString(CharSequence value) {
        return value != null ? value.toString() : null;
    }

    private static String bufferToString(ByteBuffer buffer) {
        if (buffer == null) return null;
        int start = buffer.position();
        int end = start;
        while (end < buffer.limit() && buffer.get(end) != 0) end++;
        byte[] bytes = new byte[end - start];
        for (int i = 0; i < bytes.length; i++) bytes[i] = buffer.get(start + i);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
