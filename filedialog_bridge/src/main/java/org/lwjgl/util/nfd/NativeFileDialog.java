package org.lwjgl.util.nfd;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Launcher-provided replacement for LWJGL's NativeFileDialog binding.
 *
 * This class exposes the same API as {@code org.lwjgl.util.nfd.NativeFileDialog}
 * from LWJGL 3.2.x through 3.4.x (both the classic NFD API and the newer
 * extended API with filter structs and path sets), but routes every dialog
 * through the Android file manager instead of the desktop helpers that do not
 * exist on Android.
 *
 * It is placed ahead of lwjgl-nfd.jar on the game classpath, so the game links
 * against this implementation and the bundled liblwjgl_nfd.so is never loaded.
 * Dialogs return real filesystem paths: picked documents are copied into the
 * app cache, save dialogs hand out a scratch file that is written back to the
 * picked location once closed.
 */
public class NativeFileDialog {

    public static final int
        NFD_ERROR = 0,
        NFD_OKAY = 1,
        NFD_CANCEL = 2;

    public static final int
        NFD_WINDOW_HANDLE_TYPE_UNSET = 0,
        NFD_WINDOW_HANDLE_TYPE_WINDOWS = 1,
        NFD_WINDOW_HANDLE_TYPE_COCOA = 2,
        NFD_WINDOW_HANDLE_TYPE_X11 = 3;

    static {
        try {
            System.loadLibrary("pojavexec");
        } catch (Throwable t) {
            // The bridge calls below will throw UnsatisfiedLinkError individually.
            System.err.println("[NativeFileDialog] Failed to load the launcher bridge library: " + t);
        }
    }

    private static String lastError;

    protected NativeFileDialog() {
        throw new UnsupportedOperationException();
    }

    // --- [ NFD_Init ] ---

    public static int NFD_Init() {
        return NFD_OKAY;
    }

    // --- [ NFD_Quit ] ---

    public static void NFD_Quit() {
    }

    // --- [ NFD_FreePath ] ---

    public static void NFD_FreePath(ByteBuffer filePath) {
        if (filePath != null) {
            bridgeNFDFreeBuffer(filePath);
        }
    }

    public static void NFD_FreePath(long filePath) {
        if (filePath != 0) {
            bridgeNFDFree(filePath);
        }
    }

    // --- [ NFD_Free ] --- (classic API)

    public static void NFD_Free(ByteBuffer filePath) {
        NFD_FreePath(filePath);
    }

    // --- [ NFD_OpenDialog ] ---

    /** Classic API: filter list is a single string (for example "png,jpg"). */
    public static int NFD_OpenDialog(ByteBuffer filterList, ByteBuffer defaultPath, PointerBuffer outPath) {
        return openSingle(splitFilter(bufferToString(filterList)), bufferToString(defaultPath), outPath);
    }

    /** Classic API: filter list is a single string (for example "png,jpg"). */
    public static int NFD_OpenDialog(CharSequence filterList, CharSequence defaultPath, PointerBuffer outPath) {
        return openSingle(splitFilter(toString(filterList)), toString(defaultPath), outPath);
    }

    /** Extended API: filters are name/spec struct pairs, only the specs matter here. */
    public static int NFD_OpenDialog(PointerBuffer outPath, NFDFilterItem.Buffer filterList, ByteBuffer defaultPath) {
        return openSingle(filterSpecs(filterList), bufferToString(defaultPath), outPath);
    }

    /** Extended API: filters are name/spec struct pairs, only the specs matter here. */
    public static int NFD_OpenDialog(PointerBuffer outPath, NFDFilterItem.Buffer filterList, CharSequence defaultPath) {
        return openSingle(filterSpecs(filterList), toString(defaultPath), outPath);
    }

    /** Parent windows do not exist on Android, the rest behaves like NFD_OpenDialog. */
    public static int NFD_OpenDialog_With(PointerBuffer outPath, NFDOpenDialogArgs args) {
        if (args == null) {
            return openSingle(new String[0], null, outPath);
        }
        return openSingle(filterSpecs(args.filterList(), args.filterCount()), args.defaultPathString(), outPath);
    }

    private static int openSingle(String[] specs, String defaultPath, PointerBuffer outPath) {
        if (outPath == null) {
            return fail("No output path buffer");
        }
        long[] outAddress = new long[1];
        int result = bridgeNFDOpen(specs, defaultPath, outAddress);
        if (result == NFD_OKAY) {
            outPath.put(0, outAddress[0]);
        } else if (result == NFD_ERROR) {
            fail("File picker unavailable");
        }
        return result;
    }

    // --- [ NFD_OpenDialogMultiple ] ---

    /** Classic API: paths are collected into a path set struct. */
    public static int NFD_OpenDialogMultiple(ByteBuffer filterList, ByteBuffer defaultPath, NFDPathSet pathSet) {
        if (pathSet == null) {
            return fail("No path set");
        }
        return bridgeNFDOpenMultipleToSet(pathSet.address(),
                splitFilter(bufferToString(filterList)), bufferToString(defaultPath));
    }

    /** Classic API: paths are collected into a path set struct. */
    public static int NFD_OpenDialogMultiple(CharSequence filterList, CharSequence defaultPath, NFDPathSet pathSet) {
        if (pathSet == null) {
            return fail("No path set");
        }
        return bridgeNFDOpenMultipleToSet(pathSet.address(),
                splitFilter(toString(filterList)), toString(defaultPath));
    }

    /** Extended API: paths are collected into a path set handle. */
    public static int NFD_OpenDialogMultiple(PointerBuffer outPath, NFDFilterItem.Buffer filterList, ByteBuffer defaultPath) {
        return openMultiple(filterSpecs(filterList), bufferToString(defaultPath), outPath);
    }

    /** Extended API: paths are collected into a path set handle. */
    public static int NFD_OpenDialogMultiple(PointerBuffer outPath, NFDFilterItem.Buffer filterList, CharSequence defaultPath) {
        return openMultiple(filterSpecs(filterList), toString(defaultPath), outPath);
    }

    /** Parent windows do not exist on Android, the rest behaves like NFD_OpenDialogMultiple. */
    public static int NFD_OpenDialogMultiple_With(PointerBuffer outPath, NFDOpenDialogArgs args) {
        if (args == null || outPath == null) {
            return fail("Invalid dialog arguments");
        }
        return openMultiple(filterSpecs(args.filterList(), args.filterCount()), args.defaultPathString(), outPath);
    }

    private static int openMultiple(String[] specs, String defaultPath, PointerBuffer outPath) {
        if (outPath == null) {
            return fail("No output path buffer");
        }
        long handle = bridgeNFDOpenMultiple(specs, defaultPath);
        if (handle == 0) {
            return NFD_CANCEL;
        }
        outPath.put(0, handle);
        return NFD_OKAY;
    }

    // --- [ NFD_SaveDialog ] ---

    /** Classic API. */
    public static int NFD_SaveDialog(ByteBuffer filterList, ByteBuffer defaultPath, PointerBuffer outPath) {
        return saveSingle(splitFilter(bufferToString(filterList)),
                bufferToString(defaultPath), null, outPath);
    }

    /** Classic API. */
    public static int NFD_SaveDialog(CharSequence filterList, CharSequence defaultPath, PointerBuffer outPath) {
        return saveSingle(splitFilter(toString(filterList)), toString(defaultPath), null, outPath);
    }

    /** Extended API. */
    public static int NFD_SaveDialog(PointerBuffer outPath, NFDFilterItem.Buffer filterList,
                                     ByteBuffer defaultPath, ByteBuffer defaultName) {
        return saveSingle(filterSpecs(filterList),
                bufferToString(defaultPath), bufferToString(defaultName), outPath);
    }

    /** Extended API. */
    public static int NFD_SaveDialog(PointerBuffer outPath, NFDFilterItem.Buffer filterList,
                                     CharSequence defaultPath, CharSequence defaultName) {
        return saveSingle(filterSpecs(filterList),
                toString(defaultPath), toString(defaultName), outPath);
    }

    /** Parent windows do not exist on Android, the rest behaves like NFD_SaveDialog. */
    public static int NFD_SaveDialog_With(PointerBuffer outPath, NFDSaveDialogArgs args) {
        if (args == null || outPath == null) {
            return fail("Invalid dialog arguments");
        }
        return saveSingle(filterSpecs(args.filterList(), args.filterCount()),
                args.defaultPathString(), args.defaultNameString(), outPath);
    }

    private static int saveSingle(String[] specs, String defaultPath, String defaultName, PointerBuffer outPath) {
        if (outPath == null) {
            return fail("No output path buffer");
        }
        long[] outAddress = new long[1];
        int result = bridgeNFDSave(specs, defaultPath, defaultName, outAddress);
        if (result == NFD_OKAY) {
            outPath.put(0, outAddress[0]);
        } else if (result == NFD_ERROR) {
            fail("File picker unavailable");
        }
        return result;
    }

    // --- [ NFD_PickFolder ] ---

    /** Classic API: the default path comes first. */
    public static int NFD_PickFolder(ByteBuffer defaultPath, PointerBuffer outPath) {
        return pickFolder(bufferToString(defaultPath), outPath);
    }

    /** Classic API: the default path comes first. */
    public static int NFD_PickFolder(CharSequence defaultPath, PointerBuffer outPath) {
        return pickFolder(toString(defaultPath), outPath);
    }

    /** Extended API: the output buffer comes first. */
    public static int NFD_PickFolder(PointerBuffer outPath, ByteBuffer defaultPath) {
        return pickFolder(bufferToString(defaultPath), outPath);
    }

    /** Extended API: the output buffer comes first. */
    public static int NFD_PickFolder(PointerBuffer outPath, CharSequence defaultPath) {
        return pickFolder(toString(defaultPath), outPath);
    }

    /** Parent windows do not exist on Android, the rest behaves like NFD_PickFolder. */
    public static int NFD_PickFolder_With(PointerBuffer outPath, NFDPickFolderArgs args) {
        if (args == null || outPath == null) {
            return fail("Invalid dialog arguments");
        }
        return pickFolder(args.defaultPathString(), outPath);
    }

    private static int pickFolder(String defaultPath, PointerBuffer outPath) {
        if (outPath == null) {
            return fail("No output path buffer");
        }
        long[] outAddress = new long[1];
        int result = bridgeNFDPickFolder(defaultPath, outAddress);
        if (result == NFD_OKAY) {
            outPath.put(0, outAddress[0]);
        } else if (result == NFD_ERROR) {
            fail("File picker unavailable");
        }
        return result;
    }

    // --- [ NFD_PickFolderMultiple ] --- (extended API only)

    public static int NFD_PickFolderMultiple(PointerBuffer outPath, ByteBuffer defaultPath) {
        return pickFolderMultiple(bufferToString(defaultPath), outPath);
    }

    public static int NFD_PickFolderMultiple(PointerBuffer outPath, CharSequence defaultPath) {
        return pickFolderMultiple(toString(defaultPath), outPath);
    }

    /** Parent windows do not exist on Android, the rest behaves like NFD_PickFolderMultiple. */
    public static int NFD_PickFolderMultiple_With(PointerBuffer outPath, NFDPickFolderArgs args) {
        if (args == null || outPath == null) {
            return fail("Invalid dialog arguments");
        }
        return pickFolderMultiple(args.defaultPathString(), outPath);
    }

    private static int pickFolderMultiple(String defaultPath, PointerBuffer outPath) {
        if (outPath == null) {
            return fail("No output path buffer");
        }
        long handle = bridgeNFDPickFolderMultiple(defaultPath);
        if (handle == 0) {
            return NFD_CANCEL;
        }
        outPath.put(0, handle);
        return NFD_OKAY;
    }

    // --- [ NFD_GetError / NFD_ClearError ] ---

    public static String NFD_GetError() {
        return lastError;
    }

    public static void NFD_ClearError() {
        lastError = null;
        bridgeNFDClearError();
    }

    private static int fail(String message) {
        lastError = message;
        return NFD_ERROR;
    }

    // --- [ NFD_PathSet ] --- (classic API, struct-backed)

    public static long NFD_PathSet_GetCount(NFDPathSet pathSet) {
        if (pathSet == null) {
            return 0;
        }
        return nNFD_PathSet_GetCount(pathSet.address());
    }

    public static String NFD_PathSet_GetPath(NFDPathSet pathSet, long index) {
        if (pathSet == null) {
            return null;
        }
        long address = nNFD_PathSet_GetPath(pathSet.address(), index);
        return address == 0 ? null : MemoryUtil.memUTF8(address);
    }

    public static void NFD_PathSet_Free(NFDPathSet pathSet) {
        if (pathSet != null) {
            nNFD_PathSet_Free(pathSet.address());
        }
    }

    // --- [ NFD_PathSet ] --- (extended API, handle-backed)

    public static int NFD_PathSet_GetCount(long pathSet, IntBuffer count) {
        if (count == null || count.remaining() < 1) {
            return fail("No count output");
        }
        int result = bridgeNFDPathSetGetCount(pathSet);
        if (result >= 0) {
            count.put(count.position(), result);
            return NFD_OKAY;
        }
        return fail("Invalid path set");
    }

    public static int NFD_PathSet_GetCount(long pathSet, int[] count) {
        if (count == null || count.length < 1) {
            return fail("No count output");
        }
        int result = bridgeNFDPathSetGetCount(pathSet);
        if (result >= 0) {
            count[0] = result;
            return NFD_OKAY;
        }
        return fail("Invalid path set");
    }

    public static int NFD_PathSet_GetPath(long pathSet, int index, PointerBuffer outPath) {
        if (outPath == null) {
            return fail("No output path buffer");
        }
        long address = bridgeNFDPathSetGetPath(pathSet, index);
        if (address == 0) {
            return fail("Invalid path set index");
        }
        outPath.put(0, address);
        return NFD_OKAY;
    }

    public static void NFD_PathSet_FreePath(ByteBuffer filePath) {
        NFD_FreePath(filePath);
    }

    public static void NFD_PathSet_FreePath(long filePath) {
        NFD_FreePath(filePath);
    }

    public static void NFD_PathSet_FreePath(CharSequence filePath) {
        // Upstream encodes to the stack and frees that address, which no game
        // can rely on. Do nothing instead of corrupting the heap.
    }

    public static int NFD_PathSet_GetEnum(long pathSet, NFDPathSetEnum enumerator) {
        if (enumerator == null) {
            return fail("No enumerator");
        }
        return bridgeNFDPathSetGetEnum(pathSet, enumerator.address());
    }

    public static void NFD_PathSet_FreeEnum(NFDPathSetEnum enumerator) {
        if (enumerator != null) {
            bridgeNFDPathSetFreeEnum(enumerator.address());
        }
    }

    public static int NFD_PathSet_EnumNext(NFDPathSetEnum enumerator, PointerBuffer outPath) {
        if (enumerator == null || outPath == null) {
            return fail("Invalid enumerator");
        }
        long address = bridgeNFDPathSetEnumNext(enumerator.address());
        if (address == 0) {
            // Exhausted. Cancel terminates index-based loops as well as
            // null-checking ones.
            return NFD_CANCEL;
        }
        outPath.put(0, address);
        return NFD_OKAY;
    }

    public static void NFD_PathSet_Free(long pathSet) {
        nNFD_PathSet_Free(pathSet);
    }

    // --- Classic natives (also used through JNI by older games) ---

    public static native int nNFD_OpenDialog(long outPath, long filterList, long defaultPath);

    public static native int nNFD_OpenDialogMultiple(long outPathSet, long filterList, long defaultPath);

    public static native int nNFD_SaveDialog(long outPath, long filterList, long defaultPath);

    public static native int nNFD_PickFolder(long outPath, long defaultPath);

    public static native long nNFD_GetError();

    public static native long nNFD_PathSet_GetCount(long pathSet);

    public static native long nNFD_PathSet_GetPath(long pathSet, long index);

    public static native void nNFD_PathSet_Free(long pathSet);

    public static native void nNFD_Free(long filePath);

    // --- Extended natives ---

    public static native void nNFD_FreePath(long filePath);

    public static native int nNFD_OpenDialog(long outPath, long filterList, int filterCount, long defaultPath);

    public static native int nNFD_OpenDialog_With(long outPath, long args);

    public static native int nNFD_OpenDialogMultiple(long outPath, long filterList, int filterCount, long defaultPath);

    public static native int nNFD_OpenDialogMultiple_With(long outPath, long args);

    public static native int nNFD_SaveDialog(long outPath, long filterList, int filterCount, long defaultPath, long defaultName);

    public static native int nNFD_SaveDialog_With(long outPath, long args);

    public static native int nNFD_PickFolder_With(long outPath, long args);

    public static native int nNFD_PickFolderMultiple(long outPath, long defaultPath);

    public static native int nNFD_PickFolderMultiple_With(long outPath, long args);

    public static native void nNFD_ClearError();

    public static native int nNFD_PathSet_GetCount(long pathSet, long count);

    public static native int nNFD_PathSet_GetCount(long pathSet, int[] count);

    public static native int nNFD_PathSet_GetPath(long pathSet, int index, long outPath);

    public static native void nNFD_PathSet_FreePath(long filePath);

    public static native int nNFD_PathSet_GetEnum(long pathSet, long outEnumerator);

    public static native void nNFD_PathSet_FreeEnum(long enumerator);

    public static native int nNFD_PathSet_EnumNext(long enumerator, long outPath);

    // --- Bridge into the launcher (implemented in libpojavexec.so) ---

    private static native int bridgeNFDOpen(String[] filterSpecs, String defaultPath, long[] outAddress);

    private static native int bridgeNFDSave(String[] filterSpecs, String defaultPath,
                                            String defaultName, long[] outAddress);

    private static native int bridgeNFDPickFolder(String defaultPath, long[] outAddress);

    private static native long bridgeNFDOpenMultiple(String[] filterSpecs, String defaultPath);

    private static native int bridgeNFDOpenMultipleToSet(long pathSetAddress, String[] filterSpecs,
                                                         String defaultPath);

    private static native long bridgeNFDPickFolderMultiple(String defaultPath);

    private static native int bridgeNFDPathSetGetCount(long pathSetHandle);

    private static native long bridgeNFDPathSetGetPath(long pathSetHandle, int index);

    private static native int bridgeNFDPathSetGetEnum(long pathSetHandle, long enumeratorAddress);

    private static native void bridgeNFDPathSetFreeEnum(long enumeratorAddress);

    private static native long bridgeNFDPathSetEnumNext(long enumeratorAddress);

    private static native void bridgeNFDFree(long address);

    private static native void bridgeNFDFreeBuffer(ByteBuffer buffer);

    private static native void bridgeNFDClearError();

    // --- Argument conversion (no LWJGL runtime helpers besides memUTF8, this jar ships standalone) ---

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

    // Classic filter string ("png,jpg") into specs.
    private static String[] splitFilter(String filterList) {
        if (filterList == null || filterList.isEmpty()) return new String[0];
        String[] parts = filterList.split("[;,]");
        int count = 0;
        for (String part : parts) {
            if (!part.trim().isEmpty()) count++;
        }
        String[] specs = new String[count];
        int index = 0;
        for (String part : parts) {
            String spec = part.trim();
            if (!spec.isEmpty()) specs[index++] = spec;
        }
        return specs;
    }

    // Extended filter structs into specs. Only the spec matters for matching.
    private static String[] filterSpecs(NFDFilterItem.Buffer filterList) {
        return filterSpecs(filterList, filterList != null ? filterList.remaining() : 0);
    }

    private static String[] filterSpecs(NFDFilterItem.Buffer filterList, int filterCount) {
        if (filterList == null || filterCount <= 0) return new String[0];
        int count = Math.min(filterCount, filterList.remaining());
        String[] specs = new String[count];
        for (int i = 0; i < count; i++) {
            specs[i] = filterList.get(i).specString();
        }
        return specs;
    }
}
