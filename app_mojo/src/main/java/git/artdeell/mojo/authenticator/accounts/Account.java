package git.artdeell.mojo.authenticator.accounts;


import android.graphics.BitmapFactory;
import android.util.Log;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.JSONUtils;

import java.io.*;
import java.net.URL;

import android.graphics.Bitmap;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import com.google.gson.JsonParseException;

import org.apache.commons.io.IOUtils;

@Keep
public class Account {
    protected transient File mSaveLocation;
    public String accessToken = "0"; // access token
    public String profileId = "00000000-0000-0000-0000-000000000000"; // profile UUID, for obtaining skin
    public String username = "Steve";
    public AuthType authType = AuthType.LOCAL;
    public boolean isMicrosoft = false;
    public String refreshToken = "0";
    public String xuid;
    public long expiresAt;
    private transient Bitmap mFaceCache;

    protected Account() {}

    /** @return the file this account is stored in, or null for ephemeral accounts */
    @Nullable
    public File getSaveLocation() {
        return mSaveLocation;
    }

    public void updateSkinFace() {
        String skinFaceUrlTemplate = authType.skinUrl;
        if(skinFaceUrlTemplate == null) return;
        String skinFaceUrl = String.format(skinFaceUrlTemplate, username);
        try {
            Log.i("SkinLoader", "Updating skin face...");
            File skinFile = getSkinFaceFile();
            // Streaming it directly breaks on some devices
            byte[] skinBytes = IOUtils.toByteArray(new URL(skinFaceUrl));
            Bitmap skinBitmap = BitmapFactory.decodeByteArray(skinBytes, 0, skinBytes.length);
            if(skinBitmap == null) return;
            Bitmap skinFace = new SkinHeadRenderer().render(100, skinBitmap);
            skinBitmap.recycle();
            if(skinFace == null) return;
            try(FileOutputStream fileOutputStream = new FileOutputStream(skinFile)) {
                skinFace.compress(Bitmap.CompressFormat.WEBP, 90, fileOutputStream);
            }
            Log.i("SkinLoader", "Update skin face success");
        } catch (IOException e) {
            // Skin refresh limit, no internet connection, etc...
            // Simply ignore updating skin face
            Log.w("SkinLoader", "Could not update skin face", e);
        }
    }

    /**
     * Re-renders the face from a skin PNG the user has just uploaded. The skin face services cache
     * for a while, so downloading it again right after an upload would still give the old head.
     */
    public void updateSkinFace(byte[] skinPng) {
        if(authType.skinUrl == null && authType != AuthType.LOCAL) return;
        Bitmap skinBitmap = BitmapFactory.decodeByteArray(skinPng, 0, skinPng.length);
        if(skinBitmap == null) return;
        Bitmap skinFace = new SkinHeadRenderer().render(100, skinBitmap);
        skinBitmap.recycle();
        if(skinFace == null) return;
        try(FileOutputStream fileOutputStream = new FileOutputStream(getSkinFaceFile())) {
            skinFace.compress(Bitmap.CompressFormat.WEBP, 90, fileOutputStream);
            mFaceCache = null;
        } catch (IOException e) {
            Log.w("SkinLoader", "Could not save the new skin face", e);
        }
    }

    public boolean isLocal(){
        return accessToken.equals("0");
    }

    public Account toLocal() {
        Account localAccount = new Account();
        localAccount.username = this.username;
        localAccount.authType = AuthType.LOCAL;
        return localAccount;
    }

    public void save() throws IOException {
        if(mSaveLocation == null) throw new RuntimeException("Not supposed to save ephemeral account");
        FileUtils.ensureParentDirectory(mSaveLocation);
        JSONUtils.writeToFile(mSaveLocation, this);
    }

    public Account reload() {
        try {
            Account account = JSONUtils.readFromFile(mSaveLocation, Account.class);
            if(account == null) return null;
            account.mSaveLocation = mSaveLocation;
            return account;
        }catch (IOException | JsonParseException e) {
            return null;
        }
     }

    public Bitmap getSkinFace(){
        if(isLocal() && authType != AuthType.LOCAL) return null;
        File skinFaceFile = getSkinFaceFile();
        if(!skinFaceFile.exists()) return null;
        if(mFaceCache == null) {
            mFaceCache = BitmapFactory.decodeFile(skinFaceFile.getAbsolutePath());
        }
        return mFaceCache;
    }

    /** Drops the saved avatar, used when a local account has no skin selected anymore */
    public void clearSkinFace() {
        //noinspection ResultOfMethodCallIgnored
        getSkinFaceFile().delete();
        mFaceCache = null;
    }

    private File getSkinFaceFile() {
        // Local accounts all share the same empty profile id, so the name tells them apart
        String id = authType == AuthType.LOCAL ? username : profileId;
        return new File(Tools.DIR_CACHE,  "skin-face-" + id +"-"+authType.name() + ".webp");
    }
}
