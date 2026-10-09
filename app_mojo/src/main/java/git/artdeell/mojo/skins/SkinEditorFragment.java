package git.artdeell.mojo.skins;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.authenticator.accounts.SkinHeadRenderer;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import git.artdeell.mojo.R;

/**
 * Lets the user pick the skin and the cape of a Microsoft account without leaving the launcher.
 * <ul>
 *     <li>Skins are stored locally with no limit, can be added with "+" and deleted with a long press.</li>
 *     <li>Capes are read from the Minecraft profile, so there is nothing to add or delete.</li>
 *     <li>Tapping a tile previews it on the 3D player and applies it to the account.</li>
 * </ul>
 */
public class SkinEditorFragment extends Fragment {
    public static final String TAG = "SKIN_EDITOR_FRAGMENT";
    /** File name (inside the accounts folder) of the account to edit */
    public static final String ARG_ACCOUNT_FILE = "account_file";

    private static final int TAB_SKINS = 0;
    private static final int TAB_CAPES = 1;
    private static final int GRID_TILE_DP = 96;
    private static final long SLIM_APPLY_DELAY_MS = 1500;
    private static final int MAX_SKIN_FILE_BYTES = 1024 * 1024;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> mPickSkin =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onSkinPicked);

    private PlayerPreviewView mPreview;
    private SwitchCompat mSlimSwitch;
    private GridLayoutManager mLayoutManager;
    private LinearProgressIndicator mProgress;
    private TextView mHint;
    private TileAdapter mAdapter;

    private Account mAccount;
    private boolean mLoaded;
    private int mTab = TAB_SKINS;
    private int mBusy;
    private boolean mIgnoreSlimCallback;

    private final List<SkinLibrary.Entry> mSkins = new ArrayList<>();
    private final List<MinecraftSkinApi.Cape> mCapes = new ArrayList<>();
    private final Map<String, Bitmap> mCapeTextures = new HashMap<>();
    private final Set<String> mThumbsLoading = new HashSet<>();
    private final LruCache<String, Bitmap> mThumbs = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    @Nullable private String mSelectedSkinId;
    @Nullable private String mSelectedCapeId;

    private final Runnable mApplySlimRunnable = this::applySlimChange;

    public SkinEditorFragment() {
        super(R.layout.fragment_skin_editor);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mPreview = view.findViewById(R.id.skin_preview);
        mSlimSwitch = view.findViewById(R.id.skin_slim_switch);
        mProgress = view.findViewById(R.id.skin_progress);
        mHint = view.findViewById(R.id.skin_hint);
        MaterialButtonToggleGroup tabs = view.findViewById(R.id.skin_tab_group);
        RecyclerView grid = view.findViewById(R.id.skin_grid);

        mAdapter = new TileAdapter();
        mLayoutManager = new GridLayoutManager(requireContext(), 3);
        grid.setLayoutManager(mLayoutManager);
        grid.setAdapter(mAdapter);
        // As many columns as fit, so the grid works in portrait and landscape alike
        grid.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            int width = r - l;
            if (width == oldR - oldL) return;
            int span = Math.max(2, (int) (width / (GRID_TILE_DP * getResources().getDisplayMetrics().density)));
            if (span != mLayoutManager.getSpanCount()) mLayoutManager.setSpanCount(span);
        });

        tabs.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            setTab(checkedId == R.id.skin_tab_capes ? TAB_CAPES : TAB_SKINS);
        });
        tabs.check(R.id.skin_tab_skins);

        mSlimSwitch.setEnabled(false);
        mSlimSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!mIgnoreSlimCallback) onSlimToggled(checked);
        });

        loadData();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHandler.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------ loading

    private static final class LoadResult {
        Account account;
        List<SkinLibrary.Entry> skins = new ArrayList<>();
        String selectedSkinId;
        MinecraftSkinApi.Profile profile;
        final Map<String, Bitmap> capeTextures = new HashMap<>();
        Throwable error;
        boolean notMicrosoft;
    }

    private void loadData() {
        final String accountFile = requireArguments().getString(ARG_ACCOUNT_FILE);
        final String seedName = getString(R.string.skin_editor_current_skin);
        beginBusy();
        MojoApplication.sExecutorService.execute(() -> {
            LoadResult result = new LoadResult();
            try {
                result.account = findAccount(accountFile);
                if (result.account == null || result.account.authType != AuthType.MICROSOFT) {
                    result.notMicrosoft = true;
                } else {
                    loadInBackground(result, seedName);
                }
            } catch (Exception e) {
                result.error = e;
            }
            runIfAttached(() -> onLoaded(result));
        });
    }

    private static void loadInBackground(LoadResult result, String seedName) throws IOException {
        result.skins = SkinLibrary.list();
        result.selectedSkinId = SkinLibrary.getSelected(result.account.profileId);
        try {
            result.account = MinecraftSkinApi.ensureFreshSession(result.account);
            result.profile = MinecraftSkinApi.fetchProfile(result.account.accessToken);
        } catch (IOException e) {
            // Offline: the local skins can still be browsed
            result.error = e;
            return;
        }

        // First launch of the editor: put the skin the account is wearing into the library
        if (result.skins.isEmpty() && result.profile.skinUrl != null) {
            try {
                byte[] png = MinecraftSkinApi.download(result.profile.skinUrl);
                SkinLibrary.Entry seed = SkinLibrary.add(png, seedName, result.profile.skinSlim);
                SkinLibrary.setSelected(result.account.profileId, seed.id);
                result.skins = SkinLibrary.list();
                result.selectedSkinId = seed.id;
            } catch (IOException ignored) {
                // Not fatal, the user can still add skins by hand
            }
        }

        for (MinecraftSkinApi.Cape cape : result.profile.capes) {
            try {
                Bitmap texture = loadCapeTexture(cape);
                if (texture != null) result.capeTextures.put(cape.id, texture);
            } catch (IOException ignored) {
                // A missing cape image only means a blank tile
            }
        }
    }

    @Nullable
    private static Bitmap loadCapeTexture(MinecraftSkinApi.Cape cape) throws IOException {
        File cacheDir = new File(Tools.DIR_CACHE, "capes");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        File cached = new File(cacheDir, cape.id + ".png");
        if (!cached.exists() || cached.length() == 0) {
            byte[] data = MinecraftSkinApi.download(cape.url);
            try (FileOutputStream out = new FileOutputStream(cached)) {
                out.write(data);
            }
        }
        Bitmap raw = BitmapFactory.decodeFile(cached.getAbsolutePath());
        if (raw == null) return null;
        Bitmap normalized = SkinTextures.normalizeCape(raw);
        raw.recycle();
        return normalized;
    }

    @Nullable
    private static Account findAccount(@Nullable String fileName) throws IOException {
        if (fileName == null) return null;
        for (Account account : Accounts.load().accounts) {
            if (account.mSaveLocation != null && fileName.equals(account.mSaveLocation.getName())) {
                return account;
            }
        }
        return null;
    }

    private void onLoaded(LoadResult result) {
        endBusy();
        if (result.notMicrosoft) {
            Toast.makeText(requireContext(), R.string.skin_editor_not_microsoft, Toast.LENGTH_LONG).show();
            Tools.removeCurrentFragment(requireActivity());
            return;
        }
        if (result.account != null) mAccount = result.account;
        mLoaded = true;

        mSkins.clear();
        mSkins.addAll(result.skins);
        mSelectedSkinId = SkinLibrary.find(result.selectedSkinId) != null ? result.selectedSkinId : null;

        mCapes.clear();
        mCapeTextures.clear();
        mSelectedCapeId = null;
        if (result.profile != null) {
            mCapes.addAll(result.profile.capes);
            mCapeTextures.putAll(result.capeTextures);
            mSelectedCapeId = result.profile.activeCapeId();
        } else if (result.error != null) {
            Toast.makeText(requireContext(), R.string.skin_editor_load_failed, Toast.LENGTH_LONG).show();
        }

        showSkinInPreview(selectedSkin());
        mPreview.setCape(mCapeTextures.get(mSelectedCapeId));
        mAdapter.notifyDataSetChanged();
        updateHint();
    }

    // ------------------------------------------------------------------ tabs

    private void setTab(int tab) {
        mTab = tab;
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
        updateHint();
    }

    private void updateHint() {
        mHint.setVisibility(mLoaded && mTab == TAB_CAPES && mCapes.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ skins

    @Nullable
    private SkinLibrary.Entry selectedSkin() {
        if (mSelectedSkinId == null) return null;
        for (SkinLibrary.Entry entry : mSkins) if (mSelectedSkinId.equals(entry.id)) return entry;
        return null;
    }

    private void showSkinInPreview(@Nullable SkinLibrary.Entry entry) {
        Bitmap bitmap = entry == null ? null
                : BitmapFactory.decodeFile(SkinLibrary.fileOf(entry).getAbsolutePath());
        mPreview.setSkin(bitmap, entry != null && entry.slim);
        if (bitmap != null) bitmap.recycle(); // the preview keeps its own normalized copy
        mIgnoreSlimCallback = true;
        mSlimSwitch.setChecked(entry != null && entry.slim);
        mSlimSwitch.setEnabled(entry != null);
        mIgnoreSlimCallback = false;
    }

    private void onSkinTileClicked(@NonNull SkinLibrary.Entry entry) {
        if (mBusy > 0 || mAccount == null) return;
        if (entry.id.equals(mSelectedSkinId)) return;
        String previous = mSelectedSkinId;
        mSelectedSkinId = entry.id;
        showSkinInPreview(entry);
        mAdapter.notifyDataSetChanged();
        applySkin(entry, previous);
    }

    /** Uploads the skin to the account; puts the previous selection back if that fails */
    private void applySkin(@NonNull SkinLibrary.Entry entry, @Nullable String previousSkinId) {
        final Account account = mAccount;
        final boolean slim = entry.slim;
        final String skinId = entry.id;
        beginBusy();
        MojoApplication.sExecutorService.execute(() -> {
            Account fresh = account;
            Throwable error = null;
            try {
                fresh = MinecraftSkinApi.ensureFreshSession(account);
                MinecraftSkinApi.uploadSkin(fresh.accessToken, SkinLibrary.readBytes(entry), slim);
                SkinLibrary.setSelected(fresh.profileId, skinId);
            } catch (Exception e) {
                error = e;
            }
            final Account finalAccount = fresh;
            final Throwable finalError = error;
            runIfAttached(() -> {
                endBusy();
                mAccount = finalAccount;
                if (finalError == null) {
                    Toast.makeText(requireContext(), R.string.skin_editor_skin_applied, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (skinId.equals(mSelectedSkinId)) {
                    mSelectedSkinId = previousSkinId;
                    showSkinInPreview(selectedSkin());
                    mAdapter.notifyDataSetChanged();
                }
                showError(finalError);
            });
        });
    }

    private void onSlimToggled(boolean slim) {
        SkinLibrary.Entry entry = selectedSkin();
        if (entry == null) return;
        entry.slim = slim;
        mPreview.setSlim(slim);
        final String id = entry.id;
        MojoApplication.sExecutorService.execute(() -> {
            try {
                SkinLibrary.setSlim(id, slim);
            } catch (IOException ignored) {}
        });
        // Wait until the user stops flipping the switch before talking to Mojang
        mHandler.removeCallbacks(mApplySlimRunnable);
        mHandler.postDelayed(mApplySlimRunnable, SLIM_APPLY_DELAY_MS);
    }

    private void applySlimChange() {
        if (mBusy > 0) {
            mHandler.postDelayed(mApplySlimRunnable, 500);
            return;
        }
        SkinLibrary.Entry entry = selectedSkin();
        if (entry != null && mAccount != null) applySkin(entry, mSelectedSkinId);
    }

    private void confirmDelete(@NonNull SkinLibrary.Entry entry) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.skin_editor_delete_title)
                .setMessage(R.string.skin_editor_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.global_delete, (dialog, which) -> deleteSkin(entry))
                .show();
    }

    private void deleteSkin(@NonNull SkinLibrary.Entry entry) {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                SkinLibrary.delete(entry.id);
            } catch (IOException e) {
                runIfAttached(() -> showError(e));
                return;
            }
            runIfAttached(() -> {
                mSkins.remove(entry);
                mThumbs.remove("skin:" + entry.id);
                if (entry.id.equals(mSelectedSkinId)) {
                    mSelectedSkinId = null;
                    mIgnoreSlimCallback = true;
                    mSlimSwitch.setEnabled(false);
                    mIgnoreSlimCallback = false;
                }
                mAdapter.notifyDataSetChanged();
            });
        });
    }

    // ------------------------------------------------------------------ adding skins

    private void onSkinPicked(@Nullable Uri uri) {
        if (uri == null) return;
        final Context appContext = requireContext().getApplicationContext();
        beginBusy();
        MojoApplication.sExecutorService.execute(() -> {
            SkinLibrary.Entry added = null;
            Throwable error = null;
            boolean invalid = false;
            try {
                byte[] data = readLimited(appContext, uri);
                Bitmap bitmap = isPng(data) ? BitmapFactory.decodeByteArray(data, 0, data.length) : null;
                if (bitmap == null || !SkinTextures.isValidSkinSize(bitmap.getWidth(), bitmap.getHeight())) {
                    invalid = true;
                } else {
                    added = SkinLibrary.add(data, displayName(appContext, uri), SkinTextures.looksSlim(bitmap));
                    bitmap.recycle();
                }
            } catch (Exception e) {
                error = e;
            }
            final SkinLibrary.Entry finalAdded = added;
            final Throwable finalError = error;
            final boolean finalInvalid = invalid;
            runIfAttached(() -> {
                endBusy();
                if (finalInvalid) {
                    Toast.makeText(requireContext(), R.string.skin_editor_invalid_skin, Toast.LENGTH_LONG).show();
                } else if (finalError != null) {
                    showError(finalError);
                } else if (finalAdded != null) {
                    mSkins.add(finalAdded);
                    mAdapter.notifyDataSetChanged();
                    // A new skin is applied right away, like any other tile that gets tapped
                    onSkinTileClicked(finalAdded);
                }
            });
        });
    }

    private static byte[] readLimited(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open the selected file");
            byte[] data = IOUtils.toByteArray(in);
            if (data.length > MAX_SKIN_FILE_BYTES) throw new IOException("File is too big for a skin");
            return data;
        }
    }

    private static boolean isPng(byte[] data) {
        if (data.length < PNG_MAGIC.length) return false;
        for (int i = 0; i < PNG_MAGIC.length; i++) if (data[i] != PNG_MAGIC[i]) return false;
        return true;
    }

    @NonNull
    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name.toLowerCase().endsWith(".png") ? name.substring(0, name.length() - 4) : name;
                }
            }
        } catch (Exception ignored) {}
        return "Skin";
    }

    // ------------------------------------------------------------------ capes

    private void onCapeTileClicked(@Nullable MinecraftSkinApi.Cape cape) {
        if (mBusy > 0 || mAccount == null) return;
        final String newId = cape == null ? null : cape.id;
        if (Objects.equals(newId, mSelectedCapeId)) return;
        final String previous = mSelectedCapeId;
        mSelectedCapeId = newId;
        mPreview.setCape(mCapeTextures.get(newId));
        mAdapter.notifyDataSetChanged();

        final Account account = mAccount;
        beginBusy();
        MojoApplication.sExecutorService.execute(() -> {
            Account fresh = account;
            Throwable error = null;
            try {
                fresh = MinecraftSkinApi.ensureFreshSession(account);
                MinecraftSkinApi.setActiveCape(fresh.accessToken, newId);
            } catch (Exception e) {
                error = e;
            }
            final Account finalAccount = fresh;
            final Throwable finalError = error;
            runIfAttached(() -> {
                endBusy();
                mAccount = finalAccount;
                if (finalError == null) {
                    Toast.makeText(requireContext(),
                            newId == null ? R.string.skin_editor_cape_removed : R.string.skin_editor_cape_applied,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                mSelectedCapeId = previous;
                mPreview.setCape(mCapeTextures.get(previous));
                mAdapter.notifyDataSetChanged();
                showError(finalError);
            });
        });
    }

    // ------------------------------------------------------------------ helpers

    private void beginBusy() {
        mBusy++;
        if (mProgress != null) mProgress.setVisibility(View.VISIBLE);
    }

    private void endBusy() {
        if (mBusy > 0) mBusy--;
        if (mProgress != null && mBusy == 0) mProgress.setVisibility(View.INVISIBLE);
    }

    private void runIfAttached(@NonNull Runnable runnable) {
        mHandler.post(() -> {
            if (isAdded() && getView() != null) runnable.run();
        });
    }

    private void showError(@NonNull Throwable error) {
        String message = null;
        for (Throwable t = error; t != null && message == null; t = t.getCause()) {
            if (!(t instanceof MinecraftSkinApi.ApiException)) continue;
            MinecraftSkinApi.ApiException api = (MinecraftSkinApi.ApiException) t;
            if (api.code == 429) message = getString(R.string.skin_editor_rate_limited);
            else if (api.code == 401 || api.code == 403) message = getString(R.string.skin_editor_session_expired);
            else message = getString(R.string.skin_editor_error, api.reason.isEmpty() ? "HTTP " + api.code : api.reason);
        }
        if (message == null) {
            String text = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
            message = getString(R.string.skin_editor_error, text);
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ thumbnails

    /** Isometric head of a skin; loaded in the background the first time it is needed */
    @Nullable
    private Bitmap skinThumb(@NonNull SkinLibrary.Entry entry) {
        final String key = "skin:" + entry.id;
        Bitmap cached = mThumbs.get(key);
        if (cached != null) return cached;
        if (!mThumbsLoading.add(key)) return null;
        MojoApplication.sExecutorService.execute(() -> {
            Bitmap head = null;
            Bitmap raw = BitmapFactory.decodeFile(SkinLibrary.fileOf(entry).getAbsolutePath());
            if (raw != null) {
                Bitmap normalized = SkinTextures.normalizeSkin(raw);
                head = new SkinHeadRenderer().render(160, normalized);
                normalized.recycle();
                raw.recycle();
            }
            final Bitmap result = head;
            runIfAttached(() -> {
                mThumbsLoading.remove(key);
                if (result == null) return;
                mThumbs.put(key, result);
                int index = mSkins.indexOf(entry);
                if (index >= 0 && mTab == TAB_SKINS) mAdapter.notifyItemChanged(index);
            });
        });
        return null;
    }

    /** Flat view of the outside of a cape, scaled up without smoothing */
    @Nullable
    private Bitmap capeThumb(@NonNull MinecraftSkinApi.Cape cape) {
        final String key = "cape:" + cape.id;
        Bitmap cached = mThumbs.get(key);
        if (cached != null) return cached;
        Bitmap texture = mCapeTextures.get(cape.id);
        if (texture == null) return null;
        Bitmap front = SkinTextures.capeFront(texture);
        Bitmap scaled = Bitmap.createScaledBitmap(front, front.getWidth() * 8, front.getHeight() * 8, false);
        mThumbs.put(key, scaled);
        return scaled;
    }

    // ------------------------------------------------------------------ grid

    private static final class TileHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView label;

        TileHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.tile_image);
            label = itemView.findViewById(R.id.tile_label);
        }
    }

    private final class TileAdapter extends RecyclerView.Adapter<TileHolder> {
        @NonNull
        @Override
        public TileHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new TileHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_skin_tile, parent, false));
        }

        @Override
        public int getItemCount() {
            // skins: every skin + the "add" tile; capes: "none" + every cape (no add tile)
            return (mTab == TAB_SKINS ? mSkins.size() : mCapes.size()) + 1;
        }

        @Override
        public void onBindViewHolder(@NonNull TileHolder holder, int position) {
            View tile = holder.itemView;
            tile.setOnClickListener(null);
            tile.setOnLongClickListener(null);
            tile.setLongClickable(false);
            tile.setSelected(false);
            holder.label.setVisibility(View.GONE);
            holder.image.setImageDrawable(null);

            if (mTab == TAB_SKINS) bindSkinTile(holder, position);
            else bindCapeTile(holder, position);
        }

        private void bindSkinTile(TileHolder holder, int position) {
            View tile = holder.itemView;
            if (position == mSkins.size()) {
                holder.image.setImageResource(R.drawable.ic_add);
                tile.setContentDescription(getString(R.string.skin_editor_add_skin));
                tile.setOnClickListener(v -> {
                    if (mBusy == 0) mPickSkin.launch("image/*");
                });
                return;
            }
            SkinLibrary.Entry entry = mSkins.get(position);
            Bitmap thumb = skinThumb(entry);
            if (thumb != null) holder.image.setImageDrawable(new BitmapDrawable(getResources(), thumb));
            tile.setContentDescription(entry.name);
            tile.setSelected(entry.id.equals(mSelectedSkinId));
            tile.setOnClickListener(v -> onSkinTileClicked(entry));
            // Only skins can be deleted, capes belong to the Minecraft account
            tile.setOnLongClickListener(v -> {
                confirmDelete(entry);
                return true;
            });
        }

        private void bindCapeTile(TileHolder holder, int position) {
            View tile = holder.itemView;
            if (position == 0) {
                holder.label.setText(R.string.skin_editor_no_cape);
                holder.label.setVisibility(View.VISIBLE);
                tile.setSelected(mSelectedCapeId == null);
                tile.setOnClickListener(v -> onCapeTileClicked(null));
                return;
            }
            MinecraftSkinApi.Cape cape = mCapes.get(position - 1);
            Bitmap thumb = capeThumb(cape);
            if (thumb != null) holder.image.setImageDrawable(new BitmapDrawable(getResources(), thumb));
            tile.setContentDescription(cape.alias);
            tile.setSelected(cape.id.equals(mSelectedCapeId));
            tile.setOnClickListener(v -> onCapeTileClicked(cape));
        }
    }
}
