//
// TinyFileDialogs bridge: implements the native side of the launcher-provided
// org.lwjgl.util.tinyfd.TinyFileDialogs replacement class.
//
// The game calls into these functions on JVM threads. They attach the calling
// thread to the Android runtime (captured during nativeInit on the app side)
// and forward the request to FileDialogBridge, which shows the Android file
// manager. Blocking calls wait here until the user picks a file or cancels.
//

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "utils.h"

#include "dialog_bridge.h"

#define TINYFD_RESULT_BUFFER_SIZE (32 * 1024)

JavaVM *g_dialog_vm;
jclass g_dialog_class;
jmethodID g_m_open_file_dialog;
jmethodID g_m_save_file_dialog;
jmethodID g_m_select_folder_dialog;
static jmethodID m_message_box;
static jmethodID m_input_box;
static jmethodID m_notify_popup;
static jmethodID m_beep;

// Dialogs are modal: serialize bridge calls and guard the result buffer.
static pthread_mutex_t g_bridge_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_result_buffer[TINYFD_RESULT_BUFFER_SIZE];

JNIEXPORT void JNICALL
Java_git_artdeell_mojo_game_platform_dialog_FileDialogBridge_nativeInit(JNIEnv *env, jclass clazz) {
    (*env)->GetJavaVM(env, &g_dialog_vm);
    g_dialog_class = (*env)->NewGlobalRef(env, clazz);
    g_m_open_file_dialog = (*env)->GetStaticMethodID(env, clazz, "openFileDialog",
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;");
    g_m_save_file_dialog = (*env)->GetStaticMethodID(env, clazz, "saveFileDialog",
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    g_m_select_folder_dialog = (*env)->GetStaticMethodID(env, clazz, "selectFolderDialog",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    m_message_box = (*env)->GetStaticMethodID(env, clazz, "messageBox",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)I");
    m_input_box = (*env)->GetStaticMethodID(env, clazz, "inputBox",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;");
    m_notify_popup = (*env)->GetStaticMethodID(env, clazz, "notifyPopup",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    m_beep = (*env)->GetStaticMethodID(env, clazz, "beep", "()V");
}

// Copy a game-VM string into the Android runtime. Returns NULL on OOM.
// The result lives in the current local frame.
static jstring export_string(JNIEnv *game_env, JNIEnv *android_env, jstring value) {
    if (value == NULL) return NULL;
    const char *utf = (*game_env)->GetStringUTFChars(game_env, value, NULL);
    if (utf == NULL) return NULL;
    jstring result = (*android_env)->NewStringUTF(android_env, utf);
    (*game_env)->ReleaseStringUTFChars(game_env, value, utf);
    return result;
}

// Read a direct PointerBuffer of C strings (game-VM addresses) into a Java
// string array on the Android runtime. LWJGL always passes direct buffers.
// The result lives in the current local frame.
static jobjectArray export_patterns(JNIEnv *game_env, JNIEnv *android_env, jobject patterns) {
    jclass string_class = (*android_env)->FindClass(android_env, "java/lang/String");
    if (patterns == NULL) {
        return (*android_env)->NewObjectArray(android_env, 0, string_class, NULL);
    }
    const char *const *addresses = (const char *const *) (*game_env)->GetDirectBufferAddress(game_env, patterns);
    jlong capacity = (*game_env)->GetDirectBufferCapacity(game_env, patterns);
    if (addresses == NULL || capacity <= 0) {
        return (*android_env)->NewObjectArray(android_env, 0, string_class, NULL);
    }
    jobjectArray result = (*android_env)->NewObjectArray(android_env, (jsize) capacity, string_class, NULL);
    if (result == NULL) return NULL;
    for (jlong i = 0; i < capacity; i++) {
        if (addresses[i] == NULL) continue;
        jstring entry = (*android_env)->NewStringUTF(android_env, addresses[i]);
        if (entry == NULL) return NULL;
        (*android_env)->SetObjectArrayElement(android_env, result, (jsize) i, entry);
        (*android_env)->DeleteLocalRef(android_env, entry);
    }
    return result;
}

// Open a local frame, run the dialog call, and return its kept result (or NULL).
// Clears any exception the Android side threw and treats it as a cancellation.
static jstring end_object_call(JNIEnv *android_env, jstring call_result) {
    if ((*android_env)->ExceptionCheck(android_env)) {
        (*android_env)->ExceptionClear(android_env);
        call_result = NULL;
    }
    return (*android_env)->PopLocalFrame(android_env, call_result);
}

static void end_void_call(JNIEnv *android_env) {
    if ((*android_env)->ExceptionCheck(android_env)) {
        (*android_env)->ExceptionDescribe(android_env);
        (*android_env)->ExceptionClear(android_env);
    }
    (*android_env)->PopLocalFrame(android_env, NULL);
}

// Copy an Android-runtime result string into the shared buffer. The caller
// must hold g_bridge_lock. Returns NULL when the dialog was cancelled. The
// passed reference is deleted.
static const char *import_result(JNIEnv *android_env, jstring result) {
    if (result == NULL) return NULL;
    const char *utf = (*android_env)->GetStringUTFChars(android_env, result, NULL);
    if (utf == NULL) {
        (*android_env)->DeleteLocalRef(android_env, result);
        return NULL;
    }
    strncpy(g_result_buffer, utf, sizeof(g_result_buffer) - 1);
    g_result_buffer[sizeof(g_result_buffer) - 1] = '\0';
    (*android_env)->ReleaseStringUTFChars(android_env, result, utf);
    (*android_env)->DeleteLocalRef(android_env, result);
    return g_result_buffer;
}

JNIEXPORT jstring JNICALL
Java_org_lwjgl_util_tinyfd_TinyFileDialogs_bridgeOpenFileDialog(JNIEnv *env, jclass clazz,
        jstring title, jstring default_path, jobject filter_patterns,
        jstring single_filter_description, jboolean allow_multiple) {
    pthread_mutex_lock(&g_bridge_lock);
    const char *result_utf = NULL;
    JNIEnv *android_env = g_dialog_vm != NULL ? get_attached_env(g_dialog_vm) : NULL;
    if (android_env != NULL && (*android_env)->PushLocalFrame(android_env, 16) == 0) {
        jstring call_result = (*android_env)->CallStaticObjectMethod(android_env, g_dialog_class,
                g_m_open_file_dialog,
                export_string(env, android_env, title),
                export_string(env, android_env, default_path),
                export_patterns(env, android_env, filter_patterns),
                export_string(env, android_env, single_filter_description),
                allow_multiple);
        result_utf = import_result(android_env, end_object_call(android_env, call_result));
    }
    jstring game_result = result_utf != NULL ? (*env)->NewStringUTF(env, result_utf) : NULL;
    pthread_mutex_unlock(&g_bridge_lock);
    return game_result;
}

JNIEXPORT jstring JNICALL
Java_org_lwjgl_util_tinyfd_TinyFileDialogs_bridgeSaveFileDialog(JNIEnv *env, jclass clazz,
        jstring title, jstring default_path, jobject filter_patterns,
        jstring single_filter_description) {
    pthread_mutex_lock(&g_bridge_lock);
    const char *result_utf = NULL;
    JNIEnv *android_env = g_dialog_vm != NULL ? get_attached_env(g_dialog_vm) : NULL;
    if (android_env != NULL && (*android_env)->PushLocalFrame(android_env, 16) == 0) {
        jstring call_result = (*android_env)->CallStaticObjectMethod(android_env, g_dialog_class,
                g_m_save_file_dialog,
                export_string(env, android_env, title),
                export_string(env, android_env, default_path),
                export_patterns(env, android_env, filter_patterns),
                export_string(env, android_env, single_filter_description));
        result_utf = import_result(android_env, end_object_call(android_env, call_result));
    }
    jstring game_result = result_utf != NULL ? (*env)->NewStringUTF(env, result_utf) : NULL;
    pthread_mutex_unlock(&g_bridge_lock);
    return game_result;
}

JNIEXPORT jstring JNICALL
Java_org_lwjgl_util_tinyfd_TinyFileDialogs_bridgeSelectFolderDialog(JNIEnv *env, jclass clazz,
        jstring title, jstring default_path) {
    pthread_mutex_lock(&g_bridge_lock);
    const char *result_utf = NULL;
    JNIEnv *android_env = g_dialog_vm != NULL ? get_attached_env(g_dialog_vm) : NULL;
    if (android_env != NULL && (*android_env)->PushLocalFrame(android_env, 16) == 0) {
        jstring call_result = (*android_env)->CallStaticObjectMethod(android_env, g_dialog_class,
                g_m_select_folder_dialog,
                export_string(env, android_env, title),
                export_string(env, android_env, default_path));
        result_utf = import_result(android_env, end_object_call(android_env, call_result));
    }
    jstring game_result = result_utf != NULL ? (*env)->NewStringUTF(env, result_utf) : NULL;
    pthread_mutex_unlock(&g_bridge_lock);
    return game_result;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_util_tinyfd_TinyFileDialogs_bridgeMessageBox(JNIEnv *env, jclass clazz,
        jstring title, jstring message, jstring dialog_type, jstring icon_type,
        jint default_button) {
    pthread_mutex_lock(&g_bridge_lock);
    jint result = 0;
    JNIEnv *android_env = g_dialog_vm != NULL ? get_attached_env(g_dialog_vm) : NULL;
    if (android_env != NULL && (*android_env)->PushLocalFrame(android_env, 16) == 0) {
        result = (*android_env)->CallStaticIntMethod(android_env, g_dialog_class, m_message_box,
                export_string(env, android_env, title),
                export_string(env, android_env, message),
                export_string(env, android_env, dialog_type),
                export_string(env, android_env, icon_type),
                default_button);
        if ((*android_env)->ExceptionCheck(android_env)) {
            (*android_env)->ExceptionClear(android_env);
            result = 0;
        }
        (*android_env)->PopLocalFrame(android_env, NULL);
    }
    pthread_mutex_unlock(&g_bridge_lock);
    return result;
}

JNIEXPORT jstring JNICALL
Java_org_lwjgl_util_tinyfd_TinyFileDialogs_bridgeInputBox(JNIEnv *env, jclass clazz,
        jstring title, jstring message, jstring default_input, jboolean password) {
    pthread_mutex_lock(&g_bridge_lock);
    const char *result_utf = NULL;
    JNIEnv *android_env = g_dialog_vm != NULL ? get_attached_env(g_dialog_vm) : NULL;
    if (android_env != NULL && (*android_env)->PushLocalFrame(android_env, 16) == 0) {
        jstring call_result = (*android_env)->CallStaticObjectMethod(android_env, g_dialog_class,
                m_input_box,
                export_string(env, android_env, title),
                export_string(env, android_env, message),
                export_string(env, android_env, default_input),
                password);
        result_utf = import_result(android_env, end_object_call(android_env, call_result));
    }
    jstring game_result = result_utf != NULL ? (*env)->NewStringUTF(env, result_utf) : NULL;
    pthread_mutex_unlock(&g_bridge_lock);
    return game_result;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_util_tinyfd_TinyFileDialogs_bridgeNotifyPopup(JNIEnv *env, jclass clazz,
        jstring title, jstring message, jstring icon_type) {
    pthread_mutex_lock(&g_bridge_lock);
    JNIEnv *android_env = g_dialog_vm != NULL ? get_attached_env(g_dialog_vm) : NULL;
    if (android_env != NULL && (*android_env)->PushLocalFrame(android_env, 16) == 0) {
        (*android_env)->CallStaticVoidMethod(android_env, g_dialog_class, m_notify_popup,
                export_string(env, android_env, title),
                export_string(env, android_env, message),
                export_string(env, android_env, icon_type));
        end_void_call(android_env);
    }
    pthread_mutex_unlock(&g_bridge_lock);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_util_tinyfd_TinyFileDialogs_bridgeBeep(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_bridge_lock);
    JNIEnv *android_env = g_dialog_vm != NULL ? get_attached_env(g_dialog_vm) : NULL;
    if (android_env != NULL && (*android_env)->PushLocalFrame(android_env, 8) == 0) {
        (*android_env)->CallStaticVoidMethod(android_env, g_dialog_class, m_beep);
        end_void_call(android_env);
    }
    pthread_mutex_unlock(&g_bridge_lock);
}
