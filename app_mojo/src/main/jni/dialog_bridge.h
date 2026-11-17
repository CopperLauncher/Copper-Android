//
// Shared Android-runtime references for the file dialog bridges.
//
// Owned by tinyfd_bridge.c (cached in FileDialogBridge.nativeInit). The NFD
// bridge invokes the same FileDialogBridge open/save/folder methods through
// these, so no new ART entry points are needed for NFD.
//

#ifndef DIALOG_BRIDGE_H
#define DIALOG_BRIDGE_H

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static inline jstring dialog_new_java_string(JNIEnv *env, const char *utf8) {
    if (utf8 == NULL) return NULL;
    size_t input_length = strlen(utf8);
    if (input_length >= SIZE_MAX / sizeof(jchar)) return NULL;
    jchar *utf16 = malloc((input_length + 1) * sizeof(jchar));
    if (utf16 == NULL) return NULL;

    size_t input = 0;
    jsize output = 0;
    while (input < input_length) {
        unsigned char first = (unsigned char) utf8[input];
        uint32_t codepoint;
        size_t bytes;
        if (first < 0x80) {
            codepoint = first;
            bytes = 1;
        } else if ((first & 0xE0) == 0xC0) {
            codepoint = first & 0x1F;
            bytes = 2;
        } else if ((first & 0xF0) == 0xE0) {
            codepoint = first & 0x0F;
            bytes = 3;
        } else if ((first & 0xF8) == 0xF0) {
            codepoint = first & 0x07;
            bytes = 4;
        } else {
            codepoint = 0xFFFD;
            bytes = 1;
        }

        int valid = bytes > 1 && input + bytes <= input_length;
        for (size_t i = 1; valid && i < bytes; i++) {
            unsigned char continuation = (unsigned char) utf8[input + i];
            if ((continuation & 0xC0) != 0x80) {
                valid = 0;
            } else {
                codepoint = (codepoint << 6) | (continuation & 0x3F);
            }
        }
        if (bytes == 1) valid = 1;
        if (!valid || (bytes == 2 && codepoint < 0x80)
                || (bytes == 3 && codepoint < 0x800)
                || (bytes == 4 && codepoint < 0x10000)
                || codepoint > 0x10FFFF
                || (codepoint >= 0xD800 && codepoint <= 0xDFFF)) {
            codepoint = 0xFFFD;
            bytes = 1;
        }
        input += bytes;
        if (codepoint <= 0xFFFF) {
            utf16[output++] = (jchar) codepoint;
        } else {
            codepoint -= 0x10000;
            utf16[output++] = (jchar) (0xD800 | (codepoint >> 10));
            utf16[output++] = (jchar) (0xDC00 | (codepoint & 0x3FF));
        }
    }

    jstring result = (*env)->NewString(env, utf16, output);
    free(utf16);
    return result;
}

static inline char *dialog_java_string_to_utf8(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    jsize length = (*env)->GetStringLength(env, value);
    if ((size_t) length > (SIZE_MAX - 1) / 3) return NULL;
    jchar const *utf16 = (*env)->GetStringChars(env, value, NULL);
    if (utf16 == NULL) return NULL;
    char *utf8 = malloc((size_t) length * 3 + 1);
    if (utf8 == NULL) {
        (*env)->ReleaseStringChars(env, value, utf16);
        return NULL;
    }

    size_t output = 0;
    for (jsize i = 0; i < length; i++) {
        uint32_t codepoint = utf16[i];
        if (codepoint >= 0xD800 && codepoint <= 0xDBFF
                && i + 1 < length
                && utf16[i + 1] >= 0xDC00 && utf16[i + 1] <= 0xDFFF) {
            codepoint = 0x10000 + ((codepoint - 0xD800) << 10)
                    + (utf16[++i] - 0xDC00);
        } else if (codepoint >= 0xD800 && codepoint <= 0xDFFF) {
            codepoint = 0xFFFD;
        }
        if (codepoint < 0x80) {
            utf8[output++] = (char) codepoint;
        } else if (codepoint < 0x800) {
            utf8[output++] = (char) (0xC0 | (codepoint >> 6));
            utf8[output++] = (char) (0x80 | (codepoint & 0x3F));
        } else if (codepoint < 0x10000) {
            utf8[output++] = (char) (0xE0 | (codepoint >> 12));
            utf8[output++] = (char) (0x80 | ((codepoint >> 6) & 0x3F));
            utf8[output++] = (char) (0x80 | (codepoint & 0x3F));
        } else {
            utf8[output++] = (char) (0xF0 | (codepoint >> 18));
            utf8[output++] = (char) (0x80 | ((codepoint >> 12) & 0x3F));
            utf8[output++] = (char) (0x80 | ((codepoint >> 6) & 0x3F));
            utf8[output++] = (char) (0x80 | (codepoint & 0x3F));
        }
    }
    utf8[output] = '\0';
    (*env)->ReleaseStringChars(env, value, utf16);
    return utf8;
}

extern JavaVM *g_dialog_vm;
extern jclass g_dialog_class;
extern jmethodID g_m_open_file_dialog;
extern jmethodID g_m_open_file_dialog_paths;
extern jmethodID g_m_save_file_dialog;
extern jmethodID g_m_select_folder_dialog;

#endif // DIALOG_BRIDGE_H
