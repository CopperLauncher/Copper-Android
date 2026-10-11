//
// NativeFileDialog bridge: implements the native side of the launcher-provided
// org.lwjgl.util.nfd.NativeFileDialog replacement class, covering both the
// classic NFD API (LWJGL 3.2.x-3.3.1) and the extended NFD API (LWJGL 3.3.2+).
//
// Like the tinyfd bridge, game threads are attached to the Android runtime and
// forwarded to the shared FileDialogBridge open/save/folder methods. Result
// paths are individually allocated because, unlike tinyfd, NFD callers free
// them with NFD_FreePath. Only pointers handed out by this bridge are ever
// freed.
//
// Overloaded natives (same name in both NFD generations) use long JNI-mangled
// names, as plain C has no overloading.
//

#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "dialog_bridge.h"
#include "utils.h"

#define NFD_ERROR 0
#define NFD_OKAY 1
#define NFD_CANCEL 2

// Dialog types for run_dialog()
#define NFD_DIALOG_OPEN 0
#define NFD_DIALOG_SAVE 1
#define NFD_DIALOG_FOLDER 2

static pthread_mutex_t g_nfd_lock = PTHREAD_MUTEX_INITIALIZER;
static char s_nfd_error[256];

// A collected path set, keyed either by a classic struct address or by an
// extended-API handle (is_handle tells the two apart).
typedef struct {
    long key;
    int is_handle;
    char **paths;
    int count;
} nfd_pathset_t;

static nfd_pathset_t *s_pathsets = NULL;
static int s_pathset_count = 0;
static int s_pathset_capacity = 0;
static long s_next_handle = 1;

// Individually owned path pointers handed to the game.
static char **s_owned = NULL;
static int s_owned_count = 0;
static int s_owned_capacity = 0;

// Extended-API path set enumerators.
typedef struct {
    long enum_address;
    long set_key;
    int set_is_handle;
    int index;
} nfd_enum_t;

static nfd_enum_t *s_enums = NULL;
static int s_enum_count = 0;
static int s_enum_capacity = 0;

// The whole bridge is serialized, so these table helpers assume g_nfd_lock.

static void set_error(const char *message) {
    strncpy(s_nfd_error, message, sizeof(s_nfd_error) - 1);
    s_nfd_error[sizeof(s_nfd_error) - 1] = '\0';
}

static void owned_add(char *path) {
    if (s_owned_count == s_owned_capacity) {
        int new_capacity = s_owned_capacity == 0 ? 16 : s_owned_capacity * 2;
        char **grown = realloc(s_owned, (size_t) new_capacity * sizeof(char *));
        if (grown == NULL) return;
        s_owned = grown;
        s_owned_capacity = new_capacity;
    }
    s_owned[s_owned_count++] = path;
}

// Frees the pointer if this bridge handed it out. Returns whether it did.
static int owned_remove(char *path) {
    for (int i = 0; i < s_owned_count; i++) {
        if (s_owned[i] == path) {
            free(path);
            s_owned[i] = s_owned[--s_owned_count];
            return 1;
        }
    }
    return 0;
}

static int pathset_find(long key, int is_handle) {
    for (int i = 0; i < s_pathset_count; i++) {
        if (s_pathsets[i].key == key && s_pathsets[i].is_handle == is_handle) return i;
    }
    return -1;
}

static void free_path_array(char **paths, int count) {
    if (paths == NULL) return;
    for (int i = 0; i < count; i++) free(paths[i]);
    free(paths);
}

// Stores (adopting) a path array under the key, replacing any previous one.
static int pathset_adopt(long key, int is_handle, char **paths, int count) {
    int index = pathset_find(key, is_handle);
    if (index >= 0) {
        free_path_array(s_pathsets[index].paths, s_pathsets[index].count);
        s_pathsets[index].paths = paths;
        s_pathsets[index].count = count;
        return 1;
    }
    if (s_pathset_count == s_pathset_capacity) {
        int new_capacity = s_pathset_capacity == 0 ? 8 : s_pathset_capacity * 2;
        nfd_pathset_t *grown = realloc(s_pathsets, (size_t) new_capacity * sizeof(nfd_pathset_t));
        if (grown == NULL) return 0;
        s_pathsets = grown;
        s_pathset_capacity = new_capacity;
    }
    s_pathsets[s_pathset_count].key = key;
    s_pathsets[s_pathset_count].is_handle = is_handle;
    s_pathsets[s_pathset_count].paths = paths;
    s_pathsets[s_pathset_count].count = count;
    s_pathset_count++;
    return 1;
}

static void forget_enums_for_set(long key, int is_handle) {
    for (int i = 0; i < s_enum_count;) {
        if (s_enums[i].set_key == key && s_enums[i].set_is_handle == is_handle) {
            s_enums[i] = s_enums[--s_enum_count];
        } else {
            i++;
        }
    }
}

static void pathset_remove(long key, int is_handle) {
    int index = pathset_find(key, is_handle);
    if (index < 0) return;
    free_path_array(s_pathsets[index].paths, s_pathsets[index].count);
    s_pathsets[index] = s_pathsets[--s_pathset_count];
    forget_enums_for_set(key, is_handle);
}

static int enum_find(long enum_address) {
    for (int i = 0; i < s_enum_count; i++) {
        if (s_enums[i].enum_address == enum_address) return i;
    }
    return -1;
}

static int enum_register(long enum_address, long set_key, int set_is_handle) {
    int index = enum_find(enum_address);
    if (index >= 0) {
        s_enums[index].set_key = set_key;
        s_enums[index].set_is_handle = set_is_handle;
        s_enums[index].index = 0;
        return 1;
    }
    if (s_enum_count == s_enum_capacity) {
        int new_capacity = s_enum_capacity == 0 ? 8 : s_enum_capacity * 2;
        nfd_enum_t *grown = realloc(s_enums, (size_t) new_capacity * sizeof(nfd_enum_t));
        if (grown == NULL) return 0;
        s_enums = grown;
        s_enum_capacity = new_capacity;
    }
    s_enums[s_enum_count].enum_address = enum_address;
    s_enums[s_enum_count].set_key = set_key;
    s_enums[s_enum_count].set_is_handle = set_is_handle;
    s_enums[s_enum_count].index = 0;
    s_enum_count++;
    return 1;
}

static void enum_remove(long enum_address) {
    int index = enum_find(enum_address);
    if (index >= 0) s_enums[index] = s_enums[--s_enum_count];
}

// Splits a '|' joined result, or wraps a single path when split is false.
static int collect_result(const char *utf, int split, char ***out_paths, int *out_count) {
    if (!split) {
        char **paths = malloc(sizeof(char *));
        if (paths == NULL) return NFD_ERROR;
        paths[0] = strdup(utf);
        if (paths[0] == NULL) {
            free(paths);
            return NFD_ERROR;
        }
        *out_paths = paths;
        *out_count = 1;
        return NFD_OKAY;
    }
    int count = 1;
    for (const char *p = utf; *p != '\0'; p++) {
        if (*p == '|') count++;
    }
    char **paths = calloc((size_t) count, sizeof(char *));
    char *copy = strdup(utf);
    if (paths == NULL || copy == NULL) {
        free(paths);
        free(copy);
        return NFD_ERROR;
    }
    int index = 0;
    char *start = copy;
    for (char *p = copy;; p++) {
        if (*p == '|' || *p == '\0') {
            char saved = *p;
            *p = '\0';
            paths[index] = strdup(start);
            *p = saved;
            if (paths[index] == NULL) {
                free_path_array(paths, index);
                free(copy);
                return NFD_ERROR;
            }
            index++;
            if (saved == '\0') break;
            start = p + 1;
        }
    }
    free(copy);
    *out_paths = paths;
    *out_count = count;
    return NFD_OKAY;
}

// Runs one shared file dialog. Hands freshly allocated paths back on success.
static int run_dialog(char **specs, int spec_count, const char *default_path,
                      int dialog_type, int allow_multiple, char ***out_paths, int *out_count) {
    *out_paths = NULL;
    *out_count = 0;
    JNIEnv *android_env = g_dialog_vm != NULL ? get_attached_env(g_dialog_vm) : NULL;
    if (android_env == NULL) {
        set_error("File picker unavailable");
        return NFD_ERROR;
    }
    if ((*android_env)->PushLocalFrame(android_env, 16 + spec_count + 1) != 0) {
        set_error("File picker unavailable");
        return NFD_ERROR;
    }
    jclass string_class = (*android_env)->FindClass(android_env, "java/lang/String");
    jobjectArray specs_array = (*android_env)->NewObjectArray(android_env, (jsize) spec_count, string_class, NULL);
    for (int i = 0; i < spec_count && specs_array != NULL; i++) {
        jstring spec = (*android_env)->NewStringUTF(android_env, specs[i]);
        if (spec == NULL) break;
        (*android_env)->SetObjectArrayElement(android_env, specs_array, (jsize) i, spec);
        (*android_env)->DeleteLocalRef(android_env, spec);
    }
    jstring default_string = default_path != NULL ? (*android_env)->NewStringUTF(android_env, default_path) : NULL;
    int code = NFD_ERROR;
    jstring result = NULL;
    if (specs_array != NULL && (spec_count == 0 || (*android_env)->ExceptionCheck(android_env) == JNI_FALSE)) {
        if (dialog_type == NFD_DIALOG_OPEN) {
            result = (*android_env)->CallStaticObjectMethod(android_env, g_dialog_class, g_m_open_file_dialog,
                    NULL, default_string, specs_array, NULL,
                    allow_multiple ? JNI_TRUE : JNI_FALSE);
        } else if (dialog_type == NFD_DIALOG_SAVE) {
            result = (*android_env)->CallStaticObjectMethod(android_env, g_dialog_class, g_m_save_file_dialog,
                    NULL, default_string, specs_array, NULL);
        } else {
            result = (*android_env)->CallStaticObjectMethod(android_env, g_dialog_class, g_m_select_folder_dialog,
                    NULL, default_string);
        }
    }
    if ((*android_env)->ExceptionCheck(android_env)) {
        (*android_env)->ExceptionClear(android_env);
        (*android_env)->PopLocalFrame(android_env, NULL);
        set_error("File picker failed");
        return NFD_ERROR;
    }
    if (result == NULL) {
        (*android_env)->PopLocalFrame(android_env, NULL);
        return NFD_CANCEL;
    }
    const char *utf = (*android_env)->GetStringUTFChars(android_env, result, NULL);
    if (utf == NULL) {
        (*android_env)->PopLocalFrame(android_env, NULL);
        set_error("File picker failed");
        return NFD_ERROR;
    }
    code = collect_result(utf, dialog_type == NFD_DIALOG_OPEN && allow_multiple, out_paths, out_count);
    (*android_env)->ReleaseStringUTFChars(android_env, result, utf);
    (*android_env)->PopLocalFrame(android_env, NULL);
    if (code != NFD_OKAY) set_error("File picker failed");
    return code;
}

// Runs one dialog and hands a single owned path to *slot. The slot is only
// touched when the user confirmed.
static int deliver_single(char **specs, int spec_count, const char *default_path,
                          int dialog_type, char **slot) {
    char **paths = NULL;
    int count = 0;
    int code = run_dialog(specs, spec_count, default_path, dialog_type, JNI_FALSE, &paths, &count);
    if (code != NFD_OKAY || count < 1) {
        free_path_array(paths, count);
        return code;
    }
    for (int i = 1; i < count; i++) free(paths[i]);
    char *chosen = paths[0];
    free(paths);
    owned_add(chosen);
    *slot = chosen;
    return NFD_OKAY;
}

static const char *peek_cstr(jlong address) {
    return (const char *) (uintptr_t) address;
}

// Flattens a game-VM String[] into owned C strings for the dialog call.
static char **flatten_specs(JNIEnv *game_env, jobjectArray array, int *out_count) {
    jsize length = array != NULL ? (*game_env)->GetArrayLength(game_env, array) : 0;
    *out_count = 0;
    if (length <= 0) return NULL;
    char **specs = calloc((size_t) length, sizeof(char *));
    if (specs == NULL) return NULL;
    int count = 0;
    for (jsize i = 0; i < length; i++) {
        jstring entry = (*game_env)->GetObjectArrayElement(game_env, array, i);
        if (entry == NULL) continue;
        const char *utf = (*game_env)->GetStringUTFChars(game_env, entry, NULL);
        if (utf != NULL) {
            specs[count] = strdup(utf);
            (*game_env)->ReleaseStringUTFChars(game_env, entry, utf);
            if (specs[count] != NULL) count++;
        }
        (*game_env)->DeleteLocalRef(game_env, entry);
        if ((*game_env)->ExceptionCheck(game_env)) break;
    }
    if ((*game_env)->ExceptionCheck(game_env)) {
        (*game_env)->ExceptionClear(game_env);
        free_path_array(specs, count);
        return NULL;
    }
    *out_count = count;
    return specs;
}

static void release_specs(char **specs, int count) {
    free_path_array(specs, count);
}

static char *export_cstr(JNIEnv *game_env, jstring value) {
    if (value == NULL) return NULL;
    // The caller frees the copy. Kept out of any local frame on purpose.
    const char *utf = (*game_env)->GetStringUTFChars(game_env, value, NULL);
    if (utf == NULL) return NULL;
    char *copy = strdup(utf);
    (*game_env)->ReleaseStringUTFChars(game_env, value, utf);
    return copy;
}

static void write_slot(JNIEnv *game_env, jlongArray out, char *path) {
    if (out == NULL) {
        if (path != NULL) {
            owned_add(path);
            // Nowhere to hand it out; free it right away.
            owned_remove(path);
        }
        return;
    }
    jlong slot = (jlong) (uintptr_t) path;
    (*game_env)->SetLongArrayRegion(game_env, out, 0, 1, &slot);
    if (path != NULL) owned_add(path);
}

// Joins a directory and a file name for save dialogs. The caller frees it.
static char *join_path(const char *dir, const char *name) {
    if (dir == NULL) return name != NULL ? strdup(name) : NULL;
    if (name == NULL) return strdup(dir);
    size_t dir_len = strlen(dir);
    while (dir_len > 0 && dir[dir_len - 1] == '/') dir_len--;
    size_t total = dir_len + 1 + strlen(name) + 1;
    char *joined = malloc(total);
    if (joined == NULL) return NULL;
    memcpy(joined, dir, dir_len);
    joined[dir_len] = '/';
    strcpy(joined + dir_len + 1, name);
    return joined;
}

// --- Private bridge natives (called by the shadow class) ---

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDOpen(JNIEnv *env, jclass clazz,
        jobjectArray filter_specs, jstring default_path, jlongArray out_address) {
    pthread_mutex_lock(&g_nfd_lock);
    int spec_count = 0;
    char **specs = flatten_specs(env, filter_specs, &spec_count);
    char *default_copy = export_cstr(env, default_path);
    char *slot = NULL;
    int code = deliver_single(specs, spec_count, default_copy, NFD_DIALOG_OPEN, &slot);
    write_slot(env, out_address, slot);
    free(default_copy);
    release_specs(specs, spec_count);
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDSave(JNIEnv *env, jclass clazz,
        jobjectArray filter_specs, jstring default_path, jstring default_name,
        jlongArray out_address) {
    pthread_mutex_lock(&g_nfd_lock);
    int spec_count = 0;
    char **specs = flatten_specs(env, filter_specs, &spec_count);
    char *dir = export_cstr(env, default_path);
    char *name = export_cstr(env, default_name);
    char *joined = join_path(dir, name);
    char *slot = NULL;
    int code = deliver_single(specs, spec_count, joined, NFD_DIALOG_SAVE, &slot);
    write_slot(env, out_address, slot);
    free(dir);
    free(name);
    free(joined);
    release_specs(specs, spec_count);
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDPickFolder(JNIEnv *env, jclass clazz,
        jstring default_path, jlongArray out_address) {
    pthread_mutex_lock(&g_nfd_lock);
    char *default_copy = export_cstr(env, default_path);
    char *slot = NULL;
    int code = deliver_single(NULL, 0, default_copy, NFD_DIALOG_FOLDER, &slot);
    write_slot(env, out_address, slot);
    free(default_copy);
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDOpenMultiple(JNIEnv *env, jclass clazz,
        jobjectArray filter_specs, jstring default_path) {
    pthread_mutex_lock(&g_nfd_lock);
    int spec_count = 0;
    char **specs = flatten_specs(env, filter_specs, &spec_count);
    char *default_copy = export_cstr(env, default_path);
    char **paths = NULL;
    int count = 0;
    int code = run_dialog(specs, spec_count, default_copy, NFD_DIALOG_OPEN, JNI_TRUE, &paths, &count);
    long handle = 0;
    if (code == NFD_OKAY) {
        handle = s_next_handle++;
        if (!pathset_adopt(handle, 1, paths, count)) {
            free_path_array(paths, count);
            handle = code == NFD_OKAY ? -1 : 0;
        }
    } else if (code == NFD_ERROR) {
        handle = -1;
    }
    free(default_copy);
    release_specs(specs, spec_count);
    pthread_mutex_unlock(&g_nfd_lock);
    return handle;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDOpenMultipleToSet(JNIEnv *env, jclass clazz,
        jlong pathset_address, jobjectArray filter_specs, jstring default_path) {
    pthread_mutex_lock(&g_nfd_lock);
    int spec_count = 0;
    char **specs = flatten_specs(env, filter_specs, &spec_count);
    char *default_copy = export_cstr(env, default_path);
    char **paths = NULL;
    int count = 0;
    int code = run_dialog(specs, spec_count, default_copy, NFD_DIALOG_OPEN, JNI_TRUE, &paths, &count);
    if (code == NFD_OKAY && !pathset_adopt(pathset_address, 0, paths, count)) {
        free_path_array(paths, count);
        code = NFD_ERROR;
        set_error("File picker failed");
    }
    if (code != NFD_OKAY) free_path_array(paths, count);
    free(default_copy);
    release_specs(specs, spec_count);
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDPickFolderMultiple(JNIEnv *env, jclass clazz,
        jstring default_path) {
    pthread_mutex_lock(&g_nfd_lock);
    char *default_copy = export_cstr(env, default_path);
    // The shared folder picker selects one directory; report it as a set of one.
    char *slot = NULL;
    int code = deliver_single(NULL, 0, default_copy, NFD_DIALOG_FOLDER, &slot);
    long handle = 0;
    if (code == NFD_OKAY) {
        char **paths = malloc(sizeof(char *));
        if (paths == NULL) {
            owned_remove(slot);
            code = NFD_ERROR;
        } else {
            paths[0] = slot;
            handle = s_next_handle++;
            if (!pathset_adopt(handle, 1, paths, 1)) {
                free_path_array(paths, 1);
                handle = -1;
            }
        }
    } else if (code == NFD_ERROR) {
        handle = -1;
    }
    free(default_copy);
    pthread_mutex_unlock(&g_nfd_lock);
    return handle;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDPathSetGetCount(JNIEnv *env, jclass clazz,
        jlong pathset_handle) {
    pthread_mutex_lock(&g_nfd_lock);
    int index = pathset_find(pathset_handle, 1);
    int count = index >= 0 ? s_pathsets[index].count : -1;
    pthread_mutex_unlock(&g_nfd_lock);
    return count;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDPathSetGetPath(JNIEnv *env, jclass clazz,
        jlong pathset_handle, jint path_index) {
    pthread_mutex_lock(&g_nfd_lock);
    char *result = NULL;
    int index = pathset_find(pathset_handle, 1);
    if (index >= 0 && path_index >= 0 && path_index < s_pathsets[index].count) {
        // GetPath results are freed with FreePath, like upstream.
        result = strdup(s_pathsets[index].paths[path_index]);
        if (result != NULL) owned_add(result);
    }
    pthread_mutex_unlock(&g_nfd_lock);
    return (jlong) (uintptr_t) result;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDPathSetGetEnum(JNIEnv *env, jclass clazz,
        jlong pathset_handle, jlong enumerator_address) {
    pthread_mutex_lock(&g_nfd_lock);
    int code = NFD_ERROR;
    if (pathset_find(pathset_handle, 1) >= 0 && enum_register(enumerator_address, pathset_handle, 1)) {
        code = NFD_OKAY;
    } else {
        set_error("Invalid path set");
    }
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDPathSetFreeEnum(JNIEnv *env, jclass clazz,
        jlong enumerator_address) {
    pthread_mutex_lock(&g_nfd_lock);
    enum_remove(enumerator_address);
    pthread_mutex_unlock(&g_nfd_lock);
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDPathSetEnumNext(JNIEnv *env, jclass clazz,
        jlong enumerator_address) {
    pthread_mutex_lock(&g_nfd_lock);
    char *result = NULL;
    int enum_index = enum_find(enumerator_address);
    if (enum_index >= 0) {
        int set_index = pathset_find(s_enums[enum_index].set_key, s_enums[enum_index].set_is_handle);
        if (set_index >= 0 && s_enums[enum_index].index < s_pathsets[set_index].count) {
            result = strdup(s_pathsets[set_index].paths[s_enums[enum_index].index++]);
            if (result != NULL) owned_add(result);
        }
    }
    pthread_mutex_unlock(&g_nfd_lock);
    return (jlong) (uintptr_t) result;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDFree(JNIEnv *env, jclass clazz,
        jlong address) {
    if (address == 0) return;
    pthread_mutex_lock(&g_nfd_lock);
    owned_remove((char *) (uintptr_t) address);
    pthread_mutex_unlock(&g_nfd_lock);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDFreeBuffer(JNIEnv *env, jclass clazz,
        jobject buffer) {
    if (buffer == NULL) return;
    // The game is expected to pass the buffer wrapping the path start.
    void *address = (*env)->GetDirectBufferAddress(env, buffer);
    pthread_mutex_lock(&g_nfd_lock);
    if (address != NULL) owned_remove(address);
    pthread_mutex_unlock(&g_nfd_lock);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_bridgeNFDClearError(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_nfd_lock);
    s_nfd_error[0] = '\0';
    pthread_mutex_unlock(&g_nfd_lock);
}

// --- Classic natives ---

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1OpenDialog__JJJ(JNIEnv *env, jclass clazz,
        jlong out_path, jlong filter_list, jlong default_path) {
    pthread_mutex_lock(&g_nfd_lock);
    char *specs[1];
    specs[0] = (char *) peek_cstr(filter_list);
    int spec_count = specs[0] != NULL ? 1 : 0;
    char *slot = NULL;
    int code = deliver_single(specs, spec_count, peek_cstr(default_path), NFD_DIALOG_OPEN, &slot);
    if (code == NFD_OKAY) *(char **) (uintptr_t) out_path = slot;
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1OpenDialogMultiple__JJJ(JNIEnv *env, jclass clazz,
        jlong out_pathset, jlong filter_list, jlong default_path) {
    pthread_mutex_lock(&g_nfd_lock);
    char *specs[1];
    specs[0] = (char *) peek_cstr(filter_list);
    int spec_count = specs[0] != NULL ? 1 : 0;
    char **paths = NULL;
    int count = 0;
    int code = run_dialog(specs, spec_count, peek_cstr(default_path), NFD_DIALOG_OPEN, JNI_TRUE, &paths, &count);
    if (code == NFD_OKAY && !pathset_adopt(out_pathset, 0, paths, count)) {
        free_path_array(paths, count);
        code = NFD_ERROR;
        set_error("File picker failed");
    }
    if (code != NFD_OKAY) free_path_array(paths, count);
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1SaveDialog__JJJ(JNIEnv *env, jclass clazz,
        jlong out_path, jlong filter_list, jlong default_path) {
    pthread_mutex_lock(&g_nfd_lock);
    char *specs[1];
    specs[0] = (char *) peek_cstr(filter_list);
    int spec_count = specs[0] != NULL ? 1 : 0;
    char *slot = NULL;
    int code = deliver_single(specs, spec_count, peek_cstr(default_path), NFD_DIALOG_SAVE, &slot);
    if (code == NFD_OKAY) *(char **) (uintptr_t) out_path = slot;
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PickFolder(JNIEnv *env, jclass clazz,
        jlong out_path, jlong default_path) {
    pthread_mutex_lock(&g_nfd_lock);
    char *slot = NULL;
    int code = deliver_single(NULL, 0, peek_cstr(default_path), NFD_DIALOG_FOLDER, &slot);
    if (code == NFD_OKAY) *(char **) (uintptr_t) out_path = slot;
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1GetError(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_nfd_lock);
    const char *error = s_nfd_error[0] != '\0' ? s_nfd_error : NULL;
    pthread_mutex_unlock(&g_nfd_lock);
    return (jlong) (uintptr_t) error;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PathSet_1GetCount__J(JNIEnv *env, jclass clazz,
        jlong pathset) {
    pthread_mutex_lock(&g_nfd_lock);
    long count = 0;
    int index = pathset_find(pathset, 0);
    if (index >= 0) count = s_pathsets[index].count;
    pthread_mutex_unlock(&g_nfd_lock);
    return count;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PathSet_1GetPath__JJ(JNIEnv *env, jclass clazz,
        jlong pathset, jlong path_index) {
    pthread_mutex_lock(&g_nfd_lock);
    // Owned by the set, like upstream: freed with PathSet_Free, not FreePath.
    char *result = NULL;
    int index = pathset_find(pathset, 0);
    if (index >= 0 && path_index >= 0 && path_index < s_pathsets[index].count) {
        result = s_pathsets[index].paths[(int) path_index];
    }
    pthread_mutex_unlock(&g_nfd_lock);
    return (jlong) (uintptr_t) result;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PathSet_1Free(JNIEnv *env, jclass clazz,
        jlong pathset) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_nfd_lock);
    // Serves both generations; classic struct addresses and extended handles
    // live in disjoint flag spaces, so removing from both is exact.
    pathset_remove(pathset, 0);
    pathset_remove(pathset, 1);
    pthread_mutex_unlock(&g_nfd_lock);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1Free(JNIEnv *env, jclass clazz,
        jlong file_path) {
    if (file_path == 0) return;
    pthread_mutex_lock(&g_nfd_lock);
    owned_remove((char *) (uintptr_t) file_path);
    pthread_mutex_unlock(&g_nfd_lock);
}

// --- Extended natives ---

JNIEXPORT void JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1FreePath(JNIEnv *env, jclass clazz,
        jlong file_path) {
    if (file_path == 0) return;
    pthread_mutex_lock(&g_nfd_lock);
    owned_remove((char *) (uintptr_t) file_path);
    pthread_mutex_unlock(&g_nfd_lock);
}

// Reads extended filter structs (name/spec pointer pairs) into a spec array.
static char **read_filter_structs(jlong filter_list, int filter_count, int *out_count) {
    *out_count = 0;
    if (filter_list == 0 || filter_count <= 0) return NULL;
    char **specs = calloc((size_t) filter_count, sizeof(char *));
    if (specs == NULL) return NULL;
    char **items = (char **) (uintptr_t) filter_list;
    int count = 0;
    for (int i = 0; i < filter_count; i++) {
        if (items[i * 2 + 1] != NULL) specs[count++] = items[i * 2 + 1];
    }
    *out_count = count;
    return specs;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1OpenDialog__JJIJ(JNIEnv *env, jclass clazz,
        jlong out_path, jlong filter_list, jint filter_count, jlong default_path) {
    pthread_mutex_lock(&g_nfd_lock);
    int spec_count = 0;
    char **specs = read_filter_structs(filter_list, filter_count, &spec_count);
    char *slot = NULL;
    int code = deliver_single(specs, spec_count, peek_cstr(default_path), NFD_DIALOG_OPEN, &slot);
    if (code == NFD_OKAY) *(char **) (uintptr_t) out_path = slot;
    free(specs);
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1OpenDialogMultiple__JJIJ(JNIEnv *env, jclass clazz,
        jlong out_path, jlong filter_list, jint filter_count, jlong default_path) {
    pthread_mutex_lock(&g_nfd_lock);
    int spec_count = 0;
    char **specs = read_filter_structs(filter_list, filter_count, &spec_count);
    char **paths = NULL;
    int count = 0;
    int code = run_dialog(specs, spec_count, peek_cstr(default_path), NFD_DIALOG_OPEN, JNI_TRUE, &paths, &count);
    if (code == NFD_OKAY) {
        long handle = s_next_handle++;
        if (pathset_adopt(handle, 1, paths, count)) {
            *(long *) (uintptr_t) out_path = handle;
        } else {
            free_path_array(paths, count);
            code = NFD_ERROR;
            set_error("File picker failed");
        }
    }
    if (code != NFD_OKAY) free_path_array(paths, count);
    free(specs);
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1SaveDialog__JJIJJ(JNIEnv *env, jclass clazz,
        jlong out_path, jlong filter_list, jint filter_count, jlong default_path,
        jlong default_name) {
    pthread_mutex_lock(&g_nfd_lock);
    int spec_count = 0;
    char **specs = read_filter_structs(filter_list, filter_count, &spec_count);
    char *joined = join_path(peek_cstr(default_path), peek_cstr(default_name));
    char *slot = NULL;
    int code = deliver_single(specs, spec_count, joined, NFD_DIALOG_SAVE, &slot);
    if (code == NFD_OKAY) *(char **) (uintptr_t) out_path = slot;
    free(joined);
    free(specs);
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PickFolderMultiple(JNIEnv *env, jclass clazz,
        jlong out_path, jlong default_path) {
    pthread_mutex_lock(&g_nfd_lock);
    char *slot = NULL;
    int code = deliver_single(NULL, 0, peek_cstr(default_path), NFD_DIALOG_FOLDER, &slot);
    if (code == NFD_OKAY) {
        char **paths = malloc(sizeof(char *));
        if (paths == NULL) {
            owned_remove(slot);
            code = NFD_ERROR;
        } else {
            paths[0] = slot;
            long handle = s_next_handle++;
            if (pathset_adopt(handle, 1, paths, 1)) {
                *(long *) (uintptr_t) out_path = handle;
            } else {
                free_path_array(paths, 1);
                code = NFD_ERROR;
                set_error("File picker failed");
            }
        }
    }
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1ClearError(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_nfd_lock);
    s_nfd_error[0] = '\0';
    pthread_mutex_unlock(&g_nfd_lock);
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PathSet_1GetCount__JJ(JNIEnv *env, jclass clazz,
        jlong pathset, jlong count) {
    pthread_mutex_lock(&g_nfd_lock);
    int code = NFD_ERROR;
    int index = pathset_find(pathset, 1);
    if (index >= 0 && count != 0) {
        *(int *) (uintptr_t) count = s_pathsets[index].count;
        code = NFD_OKAY;
    } else {
        set_error("Invalid path set");
    }
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PathSet_1GetCount__J_3I(JNIEnv *env, jclass clazz,
        jlong pathset, jintArray count) {
    pthread_mutex_lock(&g_nfd_lock);
    int code = NFD_ERROR;
    int index = pathset_find(pathset, 1);
    if (index >= 0 && count != NULL) {
        jint value = (jint) s_pathsets[index].count;
        (*env)->SetIntArrayRegion(env, count, 0, 1, &value);
        code = NFD_OKAY;
    } else {
        set_error("Invalid path set");
    }
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PathSet_1GetPath__JIJ(JNIEnv *env, jclass clazz,
        jlong pathset, jint path_index, jlong out_path) {
    pthread_mutex_lock(&g_nfd_lock);
    int code = NFD_ERROR;
    int index = pathset_find(pathset, 1);
    if (index >= 0 && path_index >= 0 && path_index < s_pathsets[index].count && out_path != 0) {
        // GetPath results are freed with FreePath, like upstream.
        char *copy = strdup(s_pathsets[index].paths[path_index]);
        if (copy != NULL) {
            owned_add(copy);
            *(char **) (uintptr_t) out_path = copy;
            code = NFD_OKAY;
        } else {
            set_error("File picker failed");
        }
    } else {
        set_error("Invalid path set index");
    }
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PathSet_1FreePath(JNIEnv *env, jclass clazz,
        jlong file_path) {
    if (file_path == 0) return;
    pthread_mutex_lock(&g_nfd_lock);
    owned_remove((char *) (uintptr_t) file_path);
    pthread_mutex_unlock(&g_nfd_lock);
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PathSet_1GetEnum(JNIEnv *env, jclass clazz,
        jlong pathset, jlong out_enumerator) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_nfd_lock);
    int code = NFD_ERROR;
    // The game passes the enumerator struct address, like the bridge does.
    if (pathset_find(pathset, 1) >= 0 && enum_register(out_enumerator, pathset, 1)) {
        code = NFD_OKAY;
    } else {
        set_error("Invalid path set");
    }
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PathSet_1FreeEnum(JNIEnv *env, jclass clazz,
        jlong enumerator) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_nfd_lock);
    enum_remove(enumerator);
    pthread_mutex_unlock(&g_nfd_lock);
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PathSet_1EnumNext(JNIEnv *env, jclass clazz,
        jlong enumerator, jlong out_path) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_nfd_lock);
    int code = NFD_ERROR;
    int enum_index = enum_find(enumerator);
    if (enum_index >= 0 && out_path != 0) {
        int set_index = pathset_find(s_enums[enum_index].set_key, s_enums[enum_index].set_is_handle);
        if (set_index >= 0 && s_enums[enum_index].index < s_pathsets[set_index].count) {
            char *copy = strdup(s_pathsets[set_index].paths[s_enums[enum_index].index++]);
            if (copy != NULL) {
                owned_add(copy);
                *(char **) (uintptr_t) out_path = copy;
                code = NFD_OKAY;
            } else {
                set_error("File picker failed");
            }
        } else {
            set_error("Invalid enumerator");
        }
    } else {
        set_error("Invalid enumerator");
    }
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

// Argument-struct natives are intentionally unsupported: parent windows do not
// exist on Android, and the Java wrappers already serve those call shapes.
static jint unsupported_args(void) {
    set_error("Unsupported dialog");
    return NFD_ERROR;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1OpenDialog_1With(JNIEnv *env, jclass clazz,
        jlong out_path, jlong args) {
    (void) env;
    (void) clazz;
    (void) out_path;
    (void) args;
    pthread_mutex_lock(&g_nfd_lock);
    jint code = unsupported_args();
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1OpenDialogMultiple_1With(JNIEnv *env, jclass clazz,
        jlong out_path, jlong args) {
    (void) env;
    (void) clazz;
    (void) out_path;
    (void) args;
    pthread_mutex_lock(&g_nfd_lock);
    jint code = unsupported_args();
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1SaveDialog_1With(JNIEnv *env, jclass clazz,
        jlong out_path, jlong args) {
    (void) env;
    (void) clazz;
    (void) out_path;
    (void) args;
    pthread_mutex_lock(&g_nfd_lock);
    jint code = unsupported_args();
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PickFolder_1With(JNIEnv *env, jclass clazz,
        jlong out_path, jlong args) {
    (void) env;
    (void) clazz;
    (void) out_path;
    (void) args;
    pthread_mutex_lock(&g_nfd_lock);
    jint code = unsupported_args();
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_nfd_NativeFileDialog_nNFD_1PickFolderMultiple_1With(JNIEnv *env, jclass clazz,
        jlong out_path, jlong args) {
    (void) env;
    (void) clazz;
    (void) out_path;
    (void) args;
    pthread_mutex_lock(&g_nfd_lock);
    jint code = unsupported_args();
    pthread_mutex_unlock(&g_nfd_lock);
    return code;
}
