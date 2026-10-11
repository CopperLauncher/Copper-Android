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

extern JavaVM *g_dialog_vm;
extern jclass g_dialog_class;
extern jmethodID g_m_open_file_dialog;
extern jmethodID g_m_save_file_dialog;
extern jmethodID g_m_select_folder_dialog;

#endif // DIALOG_BRIDGE_H
