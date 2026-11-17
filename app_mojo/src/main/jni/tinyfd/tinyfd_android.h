//
// Android backend for the vendored tinyfiledialogs implementation.
//
// The desktop backends shell out to zenity/kdialog/osascript and friends.
// Those do not exist on Android, so every public tinyfd entry point is
// redirected to these functions, which forward the request to the launcher's
// built-in file manager through FileDialogBridge.
//

#ifndef TINYFD_ANDROID_H
#define TINYFD_ANDROID_H

#ifdef __cplusplus
extern "C" {
#endif

void mojo_tinyfd_beep(void);
int mojo_tinyfd_messageBox(char const * aTitle, char const * aMessage,
        char const * aDialogType, char const * aIconType, int aDefaultButton);
int mojo_tinyfd_notifyPopup(char const * aTitle, char const * aMessage,
        char const * aIconType);
char * mojo_tinyfd_inputBox(char const * aTitle, char const * aMessage,
        char const * aDefaultInput);
char * mojo_tinyfd_saveFileDialog(char const * aTitle,
        char const * aDefaultPathAndOrFile, int aNumOfFilterPatterns,
        char const * const * aFilterPatterns, char const * aSingleFilterDescription);
char * mojo_tinyfd_openFileDialog(char const * aTitle,
        char const * aDefaultPathAndOrFile, int aNumOfFilterPatterns,
        char const * const * aFilterPatterns, char const * aSingleFilterDescription,
        int aAllowMultipleSelects);
char * mojo_tinyfd_selectFolderDialog(char const * aTitle,
        char const * aDefaultPath);
char * mojo_tinyfd_colorChooser(char const * aTitle,
        char const * aDefaultHexRGB, unsigned char const aDefaultRGB[3],
        unsigned char aoResultRGB[3]);

#ifdef __cplusplus
}
#endif

#endif // TINYFD_ANDROID_H
