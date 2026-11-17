//
// Created by maks on 20.09.2025.
//

#include <jni.h>
#include <stdbool.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <jvm_hooks/jvm_hooks.h>
#include "elf_hinter.h"
#include "load_stages.h"

#define TAG __FILE_NAME__
#include <log.h>

extern bool apiRequiresHints();

// Java 21 style hook
typedef jboolean (*NativeLibraries_load)(JNIEnv *env, jclass cls, jobject lib, jstring name, jboolean isBuiltin, jboolean throwExceptionIfFail);

// Java 17 style hook
typedef jboolean (*j17_NativeLibraries_load)(JNIEnv *env, jclass cls, jobject lib, jstring name, jboolean isBuiltin, jboolean isJNI, jboolean throwExceptionIfFail);

// Java 8 style hook
typedef void(*ClassLoader_00024NativeLibrary_load)(JNIEnv *env, jobject this, jstring name, jboolean isBuiltin);

union {
    NativeLibraries_load j21;
    j17_NativeLibraries_load j17;
    ClassLoader_00024NativeLibrary_load j8;
    void* generic;
} original_func;

static void library_preload_hook(JNIEnv *env, const char* name) {
    const char* lib_filename = strrchr(name, '/');
    if(lib_filename == NULL) lib_filename = name;
    else lib_filename++;
    if(strcmp(lib_filename, "liblwjgl.so") == 0) {
        LOGI("Running LWJGL preload hooks...");
        installLwjglDlopenHook(env);
        installEMUIIteratorMititgation(env);
    }
}

// The desktop lwjgl-tinyfd native library has no Android file picker, and the
// launcher bridge library already implements the same JNI entry points. Point
// its load (and the bridge's own System.loadLibrary call) at libmojort.so so
// JNI resolution happens without depending on java.library.path.
static jstring maybe_redirect_dialog_library(JNIEnv *env, jstring name) {
    const char *utf = (*env)->GetStringUTFChars(env, name, NULL);
    if (utf == NULL) return NULL;
    const char *base = strrchr(utf, '/');
    base = base != NULL ? base + 1 : utf;
    bool redirect = strcmp(base, "liblwjgl_tinyfd.so") == 0
            || strcmp(base, "liblwjgl_tinyfd") == 0
            || strcmp(base, "lwjgl_tinyfd") == 0
            || strcmp(base, "libmojort.so") == 0
            || strcmp(base, "mojort") == 0;
    jstring result = NULL;
    if (redirect) {
        Dl_info info;
        if (dladdr((void *) maybe_redirect_dialog_library, &info) != 0
                && info.dli_fname != NULL) {
            result = (*env)->NewStringUTF(env, info.dli_fname);
        }
    }
    (*env)->ReleaseStringUTFChars(env, name, utf);
    return result;
}

#define NATIVES_HOOK(CALLTHROUGH, RESULT, RESULT_RETURN) \
do { \
    jstring redirect = maybe_redirect_dialog_library(env, name); \
    jstring load_name = redirect != NULL ? redirect : name; \
    const char *name_n = (*env)->GetStringUTFChars(env, load_name, NULL); \
    library_preload_hook(env, name_n); \
    const bool hintsRequired = apiRequiresHints(); \
    hinter_t hinter; \
    if (hintsRequired) hinter_process(&hinter, name_n); \
    (*env)->ReleaseStringUTFChars(env, load_name, name_n); \
    RESULT CALLTHROUGH; \
    if (hintsRequired) hinter_free(&hinter); \
    if (redirect != NULL) (*env)->DeleteLocalRef(env, redirect); \
    RESULT_RETURN \
} while(0);


static jboolean hook_NativeLibraries_load(JNIEnv *env, jclass cls, jobject lib, jstring name, jboolean isBuiltin, jboolean throwExceptionIfFail) {
    NATIVES_HOOK(original_func.j21(env, cls, lib, load_name, isBuiltin, throwExceptionIfFail), jboolean result =, return result;)
}

static jboolean hook_j17_NativeLibraries_load(JNIEnv *env, jclass cls, jobject lib, jstring name, jboolean isBuiltin, jboolean isJNI, jboolean throwExceptionIfFail) {
    NATIVES_HOOK(original_func.j17(env, cls, lib, load_name, isBuiltin, isJNI, throwExceptionIfFail), jboolean result =, return result;)
}

static void hook_ClassLoader_00024NativeLibrary_load(JNIEnv *env, jobject this, jstring name, jboolean isBuiltin) {
    NATIVES_HOOK(original_func.j8(env, this, load_name, isBuiltin),,)
}

static bool testMethod(JNIEnv *vm_env, jclass hookClass, const char* sign, JNINativeMethod* hookMethod) {
    jmethodID hookMethod_j17 = (*vm_env)->GetStaticMethodID(vm_env, hookClass, hookMethod->name, sign);
    if(hookMethod_j17 != NULL) {
        hookMethod->signature = sign;
        return true;
    }else {
        (*vm_env)->ExceptionClear(vm_env);
        return false;
    }
}

bool installClassLoaderHooks(JNIEnv *env, JNIEnv* vm_env) {
    void* libjava = dlopen("libjava.so", RTLD_NOLOAD);
    if(libjava == NULL) {
        throwException(env, STAGE_FIND_HOOKS_NATIVE, JNI_ERR, "Failed to find libjava.so after VM startup");
        return false;
    }
    jclass hookClass;
    JNINativeMethod hookMethod;
    hookClass = (*vm_env)->FindClass(vm_env,"java/lang/ClassLoader$NativeLibrary");
    if(hookClass != NULL) {
        hookMethod.name = "load";
        hookMethod.signature = "(Ljava/lang/String;Z)V";
        hookMethod.fnPtr = hook_ClassLoader_00024NativeLibrary_load;
        original_func.j8 = dlsym(libjava, "Java_java_lang_ClassLoader_00024NativeLibrary_load");
    }else {
        (*vm_env)->ExceptionClear(vm_env);
        hookClass = (*vm_env)->FindClass(vm_env, "jdk/internal/loader/NativeLibraries");
        if(hookClass == NULL) goto err;
        hookMethod.name = "load";
        original_func.generic = dlsym(libjava, "Java_jdk_internal_loader_NativeLibraries_load");
        if(testMethod(vm_env, hookClass, "(Ljdk/internal/loader/NativeLibraries$NativeLibraryImpl;Ljava/lang/String;ZZZ)Z", &hookMethod)) {
            hookMethod.fnPtr = hook_j17_NativeLibraries_load;
        }else if(testMethod(vm_env, hookClass, "(Ljdk/internal/loader/NativeLibraries$NativeLibraryImpl;Ljava/lang/String;ZZ)Z", &hookMethod)) {
            hookMethod.fnPtr = hook_NativeLibraries_load;
        }else {
            throwException(env, STAGE_FIND_HOOKS, JNI_ERR, "Cannot find hook target.");
        }
    }
    jint result = (*vm_env)->RegisterNatives(vm_env, hookClass, &hookMethod, 1);
    if(result != JNI_OK) {
        if((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionDescribe(vm_env);
        throwException(env, STAGE_INSERT_HOOKS, result, "Cannot register hooks");
        return false;
    }
    return true;
    err:
    throwException(env, STAGE_FIND_HOOKS, JNI_ERR, "Cannot find hook target.");
    return false;
}