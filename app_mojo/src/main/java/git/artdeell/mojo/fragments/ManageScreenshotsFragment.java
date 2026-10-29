package git.artdeell.mojo.fragments;

import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.WorldUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Manage Content → Screenshots. A grid of the pictures in the screenshots folder, tap one to see
 * it at full quality, share it or delete it. Screenshots can't be browsed, there is nothing online.
 */
public class ManageScreenshotsFragment extends Fragment {
    public static final String TAG = "ManageScreenshotsFragment";
    private static final int THUMBNAIL_SIZE = 480;
    private static final int FULL_SIZE = 4096;

    private final List<File> mShots = new ArrayList<>();
    private ShotAdapter mAdapter;
    private RecyclerView mRecycler;
    private View mEmptyState;

    public ManageScreenshotsFragment() {
        super(R.layout.fragment_manage_screenshots);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ImageButton back = view.findViewById(R.id.manage_screenshots_back);
        mRecycler = view.findViewById(R.id.manage_screenshots_recycler);
        mEmptyState = view.findViewById(R.id.manage_screenshots_empty);

        if (getParentFragment() instanceof MainMenuFragment) {
            back.setVisibility(View.GONE);
        } else {
            back.setOnClickListener(v -> requireActivity().onBackPressed());
        }

        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        mAdapter = new ShotAdapter();
        mRecycler.setLayoutManager(new GridLayoutManager(requireContext(), landscape ? 3 : 2));
        mRecycler.setAdapter(mAdapter);
        reload();
    }

    private void reload() {
        File[] files = WorldUtils.getScreenshotsDirectory().listFiles(f -> {
            String name = f.getName().toLowerCase(Locale.ROOT);
            return f.isFile() && (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"));
        });
        if (files == null) files = new File[0];
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        mShots.clear();
        mShots.addAll(Arrays.asList(files));
        mAdapter.notifyDataSetChanged();
        boolean empty = mShots.isEmpty();
        mRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        mEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    @Nullable
    private static Bitmap decode(File file, int maxDimension) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (sample * 2) >= maxDimension) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private void confirmDelete(File file, @Nullable Dialog viewer) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.screenshot_delete_title)
                .setMessage(getString(R.string.screenshot_delete_message, file.getName()))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    file.delete();
                    if (viewer != null) viewer.dismiss();
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Full screen view of one screenshot, with share and delete */
    private void showScreenshot(File file) {
        FrameLayout root = new FrameLayout(requireContext());
        root.setBackgroundColor(0xF2000000);

        ImageView picture = new ImageView(requireContext());
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(picture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Dialog dialog = new Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        LinearLayout bar = new LinearLayout(requireContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(0x80000000);
        MaterialButton share = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        share.setText(R.string.screenshot_share);
        share.setOnClickListener(v -> Tools.openPath(requireContext(), file, true));
        MaterialButton delete = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        delete.setText(R.string.delete);
        delete.setOnClickListener(v -> confirmDelete(file, dialog));
        int margin = (int) (8 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.setMargins(margin, margin, margin, margin);
        bar.addView(share, buttonParams);
        bar.addView(delete, buttonParams);
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        dialog.setContentView(root);
        picture.setOnClickListener(v -> dialog.dismiss());

        MojoApplication.sExecutorService.execute(() -> {
            Bitmap bitmap = decode(file, FULL_SIZE);
            Tools.runOnUiThread(() -> {
                if (dialog.isShowing() && bitmap != null) picture.setImageBitmap(bitmap);
            });
        });
        dialog.show();
    }

    private class ShotAdapter extends RecyclerView.Adapter<ShotAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_screenshot, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            File file = mShots.get(position);
            holder.image.setTag(file);
            holder.image.setImageDrawable(null);
            holder.image.setOnClickListener(v -> showScreenshot(file));
            holder.image.setOnLongClickListener(v -> {
                confirmDelete(file, null);
                return true;
            });
            MojoApplication.sExecutorService.execute(() -> {
                Bitmap bitmap = decode(file, THUMBNAIL_SIZE);
                Tools.runOnUiThread(() -> {
                    // The row may have been recycled for another screenshot meanwhile
                    if (bitmap != null && file.equals(holder.image.getTag())) holder.image.setImageBitmap(bitmap);
                });
            });
        }

        @Override
        public int getItemCount() {
            return mShots.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView image;

            Holder(@NonNull View itemView) {
                super(itemView);
                image = (ImageView) itemView;
            }
        }
    }
}
