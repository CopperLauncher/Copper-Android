package net.kdt.pojavlaunch.contracts;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.PojavApplication;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

// Android's OpenDocument contract is the basicmost crap that doesn't allow
// you to specify practically anything. So i made this instead.
public class OpenDocumentWithExtension extends ActivityResultContract<Object, Uri> {
    private final Future<String> extensionMimeTypeFuture;
    /** Non-null when the contract was created with several extensions, see {@link #OpenDocumentWithExtension(String[])} */
    private final String[] mMimeTypes;

    /**
     * Create a new OpenDocumentWithExtension contract.
     * If the extension provided to the constructor is not available in the device's MIME
     * type database, the filter will default to "all types"
     * @param extension the extension to filter by
     */
    public OpenDocumentWithExtension(String extension) {
        mMimeTypes = null;
        // Who would have thought that loading the MIME map takes a significant amount of time?
        extensionMimeTypeFuture = PojavApplication.sExecutorService.submit(()->{
            String extensionMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if(extensionMimeType == null) extensionMimeType = "*/*";
            return extensionMimeType;
        });
    }

    /**
     * Create a new OpenDocumentWithExtension contract that accepts several extensions at once.
     * Extensions that are not present in the device's MIME type database are skipped.
     * @param extensions the extensions to filter by
     */
    public OpenDocumentWithExtension(String[] extensions) {
        ArrayList<String> mimeTypes = new ArrayList<>();
        for(String extension : extensions) {
            if("mrpack".equals(extension)) {
                // Depending on whether the ROM knows x-modrinth-modpack+zip, mrpack files are
                // reported either as that or as octet-stream, so both have to be allowed.
                mimeTypes.add("application/octet-stream");
                mimeTypes.add("application/x-modrinth-modpack+zip");
                continue;
            }
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if(mimeType != null) mimeTypes.add(mimeType);
        }
        mMimeTypes = mimeTypes.toArray(new String[0]);
        extensionMimeTypeFuture = PojavApplication.sExecutorService.submit(()-> "*/*");
    }

    @NonNull
    @Override
    public Intent createIntent(@NonNull Context context, @NonNull Object input) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            intent.setType(extensionMimeTypeFuture.get());
        }catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        if(mMimeTypes != null && mMimeTypes.length > 0) intent.putExtra(Intent.EXTRA_MIME_TYPES, mMimeTypes);
        return intent;
    }

    @Nullable
    @Override
    public final SynchronousResult<Uri> getSynchronousResult(@NonNull Context context,
                                                             @NonNull Object input) {
        return null;
    }

    @Nullable
    @Override
    public final Uri parseResult(int resultCode, @Nullable Intent intent) {
        if (intent == null || resultCode != Activity.RESULT_OK) return null;
        return intent.getData();
    }
}
