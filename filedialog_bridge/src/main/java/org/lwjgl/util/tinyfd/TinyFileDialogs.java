package org.lwjgl.util.tinyfd;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.PointerBuffer;

/**
 * Launcher-provided replacement for LWJGL's TinyFileDialogs binding.
 *
 * This class exposes the same API as {@code org.lwjgl.util.tinyfd.TinyFileDialogs}
 * from LWJGL 3.2.x through 3.4.x, but routes every dialog through the Android
 * file manager and platform UI instead of the desktop helpers (zenity, kdialog
 * and friends) that do not exist on Android.
 *
 * It is placed ahead of lwjgl-tinyfd.jar on the game classpath, so the game
 * links against this implementation and the bundled liblwjgl_tinyfd.so is
 * never loaded. File dialogs return real filesystem paths: picked documents
 * are copied into the app cache, save dialogs hand out a scratch file that is
 * written back to the picked location once closed.
 */
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

    static {
        try {
            System.loadLibrary("pojavexec");
        } catch (Throwable t) {
            // The bridge calls below will throw UnsatisfiedLinkError individually.
            System.err.println("[TinyFileDialogs] Failed to load the launcher bridge library: " + t);
        }
    }

    private static final Map<String, String> globalChars = new HashMap<>();
    private static final Map<String, Integer> globalInts = new HashMap<>();

    protected TinyFileDialogs() {
        throw new UnsupportedOperationException();
    }

    // --- [ tinyfd_getGlobalChar ] ---

    public static String tinyfd_getGlobalChar(ByteBuffer aCharVariableName) {
        return globalChars.get(bufferToString(aCharVariableName));
    }

    public static String tinyfd_getGlobalChar(CharSequence aCharVariableName) {
        if (aCharVariableName == null) return null;
        return globalChars.get(aCharVariableName.toString());
    }

    // --- [ tinyfd_getGlobalInt ] ---

    public static int tinyfd_getGlobalInt(ByteBuffer aIntVariableName) {
        Integer value = globalInts.get(bufferToString(aIntVariableName));
        return value != null ? value : 0;
    }

    public static int tinyfd_getGlobalInt(CharSequence aIntVariableName) {
        if (aIntVariableName == null) return 0;
        Integer value = globalInts.get(aIntVariableName.toString());
        return value != null ? value : 0;
    }

    // --- [ tinyfd_setGlobalInt ] ---

    public static int tinyfd_setGlobalInt(ByteBuffer aIntVariableName, int aValue) {
        globalInts.put(bufferToString(aIntVariableName), aValue);
        return aValue;
    }

    public static int tinyfd_setGlobalInt(CharSequence aIntVariableName, int aValue) {
        if (aIntVariableName == null) return 0;
        globalInts.put(aIntVariableName.toString(), aValue);
        return aValue;
    }

    // --- [ tinyfd_beep ] ---

    public static void tinyfd_beep() {
        bridgeBeep();
    }

    // --- [ tinyfd_notifyPopup ] ---

    public static int tinyfd_notifyPopup(ByteBuffer aTitle, ByteBuffer aMessage, ByteBuffer aIconType) {
        bridgeNotifyPopup(bufferToString(aTitle), bufferToString(aMessage), bufferToString(aIconType));
        return 1;
    }

    public static int tinyfd_notifyPopup(CharSequence aTitle, CharSequence aMessage, CharSequence aIconType) {
        bridgeNotifyPopup(toString(aTitle), toString(aMessage), toString(aIconType));
        return 1;
    }

    // --- [ tinyfd_messageBox ] ---

    /** LWJGL 3.2.x through 3.3.1 flavor (boolean result). */
    public static boolean tinyfd_messageBox(ByteBuffer aTitle, ByteBuffer aMessage,
                                            ByteBuffer aDialogType, ByteBuffer aIconType,
                                            boolean aDefaultButton) {
        return messageBox(bufferToString(aTitle), bufferToString(aMessage),
                bufferToString(aDialogType), bufferToString(aIconType),
                aDefaultButton ? 1 : 0) == 1;
    }

    /** LWJGL 3.2.x through 3.3.1 flavor (boolean result). */
    public static boolean tinyfd_messageBox(CharSequence aTitle, CharSequence aMessage,
                                            CharSequence aDialogType, CharSequence aIconType,
                                            boolean aDefaultButton) {
        return messageBox(toString(aTitle), toString(aMessage), toString(aDialogType),
                toString(aIconType), aDefaultButton ? 1 : 0) == 1;
    }

    /** LWJGL 3.3.2+ flavor (1 for ok/yes/retry, 2 for no, 0 for cancel). */
    public static int tinyfd_messageBox(ByteBuffer aTitle, ByteBuffer aMessage,
                                        ByteBuffer aDialogType, ByteBuffer aIconType,
                                        int aDefaultButton) {
        return messageBox(bufferToString(aTitle), bufferToString(aMessage),
                bufferToString(aDialogType), bufferToString(aIconType), aDefaultButton);
    }

    /** LWJGL 3.3.2+ flavor (1 for ok/yes/retry, 2 for no, 0 for cancel). */
    public static int tinyfd_messageBox(CharSequence aTitle, CharSequence aMessage,
                                        CharSequence aDialogType, CharSequence aIconType,
                                        int aDefaultButton) {
        return messageBox(toString(aTitle), toString(aMessage), toString(aDialogType),
                toString(aIconType), aDefaultButton);
    }

    private static int messageBox(String title, String message, String dialogType,
                                  String iconType, int defaultButton) {
        int result = bridgeMessageBox(title, message, dialogType, iconType, defaultButton);
        globalChars.put(tinyfd_response, String.valueOf(result));
        return result;
    }

    // --- [ tinyfd_inputBox ] ---

    public static String tinyfd_inputBox(ByteBuffer aTitle, ByteBuffer aMessage, ByteBuffer aDefaultInput) {
        return inputBox(bufferToString(aTitle), bufferToString(aMessage),
                bufferToString(aDefaultInput));
    }

    public static String tinyfd_inputBox(CharSequence aTitle, CharSequence aMessage, CharSequence aDefaultInput) {
        return inputBox(toString(aTitle), toString(aMessage), toString(aDefaultInput));
    }

    private static String inputBox(String title, String message, String defaultInput) {
        String result = bridgeInputBox(title, message, defaultInput, false);
        globalChars.put(tinyfd_response, result != null ? result : "");
        return result;
    }

    // --- [ tinyfd_saveFileDialog ] ---

    public static String tinyfd_saveFileDialog(ByteBuffer aTitle, ByteBuffer aDefaultPathAndOrFile,
                                               PointerBuffer aFilterPatterns,
                                               ByteBuffer aSingleFilterDescription) {
        return saveFileDialog(bufferToString(aTitle), bufferToString(aDefaultPathAndOrFile),
                aFilterPatterns, bufferToString(aSingleFilterDescription));
    }

    public static String tinyfd_saveFileDialog(CharSequence aTitle, CharSequence aDefaultPathAndOrFile,
                                               PointerBuffer aFilterPatterns,
                                               CharSequence aSingleFilterDescription) {
        return saveFileDialog(toString(aTitle), toString(aDefaultPathAndOrFile),
                aFilterPatterns, toString(aSingleFilterDescription));
    }

    private static String saveFileDialog(String title, String defaultPath, PointerBuffer filterPatterns,
                                         String singleFilterDescription) {
        String result = bridgeSaveFileDialog(title, defaultPath, filterPatterns, singleFilterDescription);
        globalChars.put(tinyfd_response, result != null ? result : "");
        return result;
    }

    // --- [ tinyfd_openFileDialog ] ---

    public static String tinyfd_openFileDialog(ByteBuffer aTitle, ByteBuffer aDefaultPathAndOrFile,
                                               PointerBuffer aFilterPatterns,
                                               ByteBuffer aSingleFilterDescription,
                                               boolean aAllowMultipleSelects) {
        return openFileDialog(bufferToString(aTitle), bufferToString(aDefaultPathAndOrFile),
                aFilterPatterns, bufferToString(aSingleFilterDescription), aAllowMultipleSelects);
    }

    public static String tinyfd_openFileDialog(CharSequence aTitle, CharSequence aDefaultPathAndOrFile,
                                               PointerBuffer aFilterPatterns,
                                               CharSequence aSingleFilterDescription,
                                               boolean aAllowMultipleSelects) {
        return openFileDialog(toString(aTitle), toString(aDefaultPathAndOrFile),
                aFilterPatterns, toString(aSingleFilterDescription), aAllowMultipleSelects);
    }

    private static String openFileDialog(String title, String defaultPath, PointerBuffer filterPatterns,
                                         String singleFilterDescription, boolean allowMultipleSelects) {
        String result = bridgeOpenFileDialog(title, defaultPath, filterPatterns,
                singleFilterDescription, allowMultipleSelects);
        globalChars.put(tinyfd_response, result != null ? result : "");
        return result;
    }

    // --- [ tinyfd_selectFolderDialog ] ---

    public static String tinyfd_selectFolderDialog(ByteBuffer aTitle, ByteBuffer aDefaultPath) {
        return selectFolderDialog(bufferToString(aTitle), bufferToString(aDefaultPath));
    }

    public static String tinyfd_selectFolderDialog(CharSequence aTitle, CharSequence aDefaultPath) {
        return selectFolderDialog(toString(aTitle), toString(aDefaultPath));
    }

    private static String selectFolderDialog(String title, String defaultPath) {
        String result = bridgeSelectFolderDialog(title, defaultPath);
        globalChars.put(tinyfd_response, result != null ? result : "");
        return result;
    }

    // --- [ tinyfd_colorChooser ] ---
    // The platform has no blocking color picker wired up, so the default color
    // passes through untouched.

    public static String tinyfd_colorChooser(ByteBuffer aTitle, ByteBuffer aDefaultHexRGB,
                                             ByteBuffer aDefaultRGB, ByteBuffer aoResultRGB) {
        return colorChooser(bufferToString(aTitle), bufferToString(aDefaultHexRGB),
                aDefaultRGB, aoResultRGB);
    }

    public static String tinyfd_colorChooser(CharSequence aTitle, CharSequence aDefaultHexRGB,
                                             ByteBuffer aDefaultRGB, ByteBuffer aoResultRGB) {
        return colorChooser(toString(aTitle), toString(aDefaultHexRGB), aDefaultRGB, aoResultRGB);
    }

    private static String colorChooser(String title, String defaultHexRGB,
                                       ByteBuffer defaultRGB, ByteBuffer resultRGB) {
        int r = 0, g = 0, b = 0;
        boolean parsed = false;
        if (defaultHexRGB != null && defaultHexRGB.length() == 7 && defaultHexRGB.charAt(0) == '#') {
            try {
                r = Integer.parseInt(defaultHexRGB.substring(1, 3), 16);
                g = Integer.parseInt(defaultHexRGB.substring(3, 5), 16);
                b = Integer.parseInt(defaultHexRGB.substring(5, 7), 16);
                parsed = true;
            } catch (NumberFormatException ignored) {
            }
        }
        if (!parsed && defaultRGB != null && defaultRGB.remaining() >= 3) {
            r = defaultRGB.get(defaultRGB.position()) & 0xFF;
            g = defaultRGB.get(defaultRGB.position() + 1) & 0xFF;
            b = defaultRGB.get(defaultRGB.position() + 2) & 0xFF;
        }
        if (resultRGB != null && resultRGB.remaining() >= 3) {
            int position = resultRGB.position();
            resultRGB.put(position, (byte) r);
            resultRGB.put(position + 1, (byte) g);
            resultRGB.put(position + 2, (byte) b);
        }
        String hex = String.format("#%02X%02X%02X", r, g, b);
        globalChars.put(tinyfd_response, hex);
        return hex;
    }

    // --- Bridge into the launcher (implemented in libpojavexec.so) ---

    private static native String bridgeOpenFileDialog(String title, String defaultPath,
                                                      PointerBuffer filterPatterns,
                                                      String singleFilterDescription,
                                                      boolean allowMultipleSelects);

    private static native String bridgeSaveFileDialog(String title, String defaultPath,
                                                      PointerBuffer filterPatterns,
                                                      String singleFilterDescription);

    private static native String bridgeSelectFolderDialog(String title, String defaultPath);

    private static native int bridgeMessageBox(String title, String message, String dialogType,
                                               String iconType, int defaultButton);

    private static native String bridgeInputBox(String title, String message,
                                                String defaultInput, boolean password);

    private static native void bridgeNotifyPopup(String title, String message, String iconType);

    private static native void bridgeBeep();

    // --- Argument conversion (no LWJGL runtime helpers, this jar ships standalone) ---

    private static String toString(CharSequence value) {
        return value != null ? value.toString() : null;
    }

    // Reads a NUL-terminated UTF-8 string, mirroring LWJGL's stack encoding.
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
