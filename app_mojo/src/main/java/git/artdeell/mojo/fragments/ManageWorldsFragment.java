package git.artdeell.mojo.fragments;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.utils.WorldUtils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Manage Content → Worlds. Lists the worlds of the selected instance and lets the user open the
 * datapacks of a world, export a world as a zip, delete it, or import a world zip.
 */
public class ManageWorldsFragment extends Fragment {
    public static final String TAG = "ManageWorldsFragment";

    private static class WorldEntry {
        final File dir;
        final long size;
        final long lastPlayed;
        @Nullable final Bitmap icon;

        WorldEntry(File dir, long size, long lastPlayed, @Nullable Bitmap icon) {
            this.dir = dir;
            this.size = size;
            this.lastPlayed = lastPlayed;
            this.icon = icon;
        }
    }

    private final List<WorldEntry> mWorlds = new ArrayList<>();
    private WorldAdapter mAdapter;
    private RecyclerView mRecycler;
    private View mEmptyState;

    private final ActivityResultLauncher<Object> mImportLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("zip"), this::importWorld);

    public ManageWorldsFragment() {
        super(R.layout.fragment_manage_worlds);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ImageButton back = view.findViewById(R.id.manage_worlds_back);
        ImageButton importButton = view.findViewById(R.id.manage_worlds_import);
        mRecycler = view.findViewById(R.id.manage_worlds_recycler);
        mEmptyState = view.findViewById(R.id.manage_worlds_empty);

        // In landscape the content picker next to this pane already has a back button
        if (getParentFragment() instanceof MainMenuFragment) {
            back.setVisibility(View.GONE);
        } else {
            back.setOnClickListener(v -> requireActivity().onBackPressed());
        }
        importButton.setOnClickListener(v -> mImportLauncher.launch(null));

        mAdapter = new WorldAdapter();
        mRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecycler.setAdapter(mAdapter);
        reload();
    }

    private void reload() {
        MojoApplication.sExecutorService.execute(() -> {
            List<WorldEntry> loaded = new ArrayList<>();
            for (File dir : WorldUtils.listWorlds()) {
                File levelDat = new File(dir, "level.dat");
                Bitmap icon = null;
                File iconFile = new File(dir, "icon.png");
                if (iconFile.isFile()) icon = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
                loaded.add(new WorldEntry(dir, WorldUtils.folderSize(dir), levelDat.lastModified(), icon));
            }
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                mWorlds.clear();
                mWorlds.addAll(loaded);
                mAdapter.notifyDataSetChanged();
                boolean empty = mWorlds.isEmpty();
                mRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
                mEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void importWorld(@Nullable Uri uri) {
        if (uri == null) return;
        Toast.makeText(requireContext(), R.string.global_waiting, Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            File temp = new File(Tools.DIR_CACHE, "world_import_" + System.nanoTime() + ".zip");
            try {
                try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(temp)) {
                    if (in == null) throw new java.io.IOException("Could not open the file");
                    IOUtils.copy(in, out);
                }
                File world = WorldUtils.extractWorldZip(temp, WorldUtils.getSavesDirectory(), "World");
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(),
                            getString(R.string.world_import_done, world.getName()), Toast.LENGTH_LONG).show();
                    reload();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), R.string.world_import_failed, Toast.LENGTH_LONG).show();
                });
            } finally {
                temp.delete();
            }
        });
    }

    private void exportWorld(WorldEntry entry) {
        Toast.makeText(requireContext(), R.string.global_waiting, Toast.LENGTH_SHORT).show();
        MojoApplication.sExecutorService.execute(() -> {
            File zip = new File(Tools.DIR_CACHE, entry.dir.getName() + ".zip");
            try {
                WorldUtils.zipWorld(entry.dir, zip);
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Tools.openPath(requireContext(), zip, true);
                });
            } catch (Exception e) {
                zip.delete();
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Toast.makeText(requireContext(), R.string.world_export_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void confirmDelete(WorldEntry entry) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.world_delete_title)
                .setMessage(getString(R.string.world_delete_message, entry.dir.getName()))
                .setPositiveButton(R.string.delete, (d, w) -> MojoApplication.sExecutorService.execute(() -> {
                    FileUtils.deleteQuietly(entry.dir);
                    Tools.runOnUiThread(() -> {
                        if (isAdded()) reload();
                    });
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openDatapacks(WorldEntry entry) {
        String world = entry.dir.getName();
        Tools.swapFragment(requireActivity(), ManageModsFragment.class,
                ManageModsFragment.datapackTag(world), ManageModsFragment.datapackArgs(world));
    }

    private class WorldAdapter extends RecyclerView.Adapter<WorldAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_world, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            WorldEntry entry = mWorlds.get(position);
            holder.name.setText(entry.dir.getName());
            String played = DateFormat.getMediumDateFormat(holder.itemView.getContext())
                    .format(new Date(entry.lastPlayed));
            String size = Formatter.formatShortFileSize(holder.itemView.getContext(), entry.size);
            holder.info.setText(getString(R.string.world_info, size, played));
            if (entry.icon != null) holder.icon.setImageBitmap(entry.icon);
            else holder.icon.setImageResource(R.drawable.ic_px_world);
            holder.datapacks.setOnClickListener(v -> openDatapacks(entry));
            holder.export.setOnClickListener(v -> exportWorld(entry));
            holder.delete.setOnClickListener(v -> confirmDelete(entry));
        }

        @Override
        public int getItemCount() {
            return mWorlds.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, info;
            final ImageButton datapacks, export, delete;

            Holder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.world_icon);
                name = itemView.findViewById(R.id.world_name);
                info = itemView.findViewById(R.id.world_info);
                datapacks = itemView.findViewById(R.id.world_datapacks);
                export = itemView.findViewById(R.id.world_export);
                delete = itemView.findViewById(R.id.world_delete);
            }
        }
    }
}
