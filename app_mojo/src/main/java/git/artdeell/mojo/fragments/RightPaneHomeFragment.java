package git.artdeell.mojo.fragments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.CustomControlsActivity;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.home.HomeTile;
import git.artdeell.mojo.home.HomeTileBoard;
import git.artdeell.mojo.home.HomeTileStore;
import git.artdeell.mojo.home.HomeTileType;
import git.artdeell.mojo.instances.DisplayInstance;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceIconProvider;
import git.artdeell.mojo.instances.Instances;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Home screen of the right pane of the two-pane landscape main menu: a board of tiles that can be
 * moved, resized, added and removed. The wallpaper is drawn behind the whole launcher by the activity.
 */
public class RightPaneHomeFragment extends Fragment implements HomeTileBoard.Listener {
    public static final String TAG = "RightPaneHomeFragment";

    private HomeTileBoard mBoard;
    private View mEditBar;
    private OnBackPressedCallback mExitEditCallback;

    public RightPaneHomeFragment() {
        super(R.layout.fragment_right_pane_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mBoard = view.findViewById(R.id.home_tile_board);
        mEditBar = view.findViewById(R.id.home_edit_bar);
        mBoard.setListener(this);
        mBoard.setTiles(HomeTileStore.load(requireContext()));

        view.findViewById(R.id.home_edit_add).setOnClickListener(v -> showAddTileDialog());
        view.findViewById(R.id.home_edit_done).setOnClickListener(v -> mBoard.setEditMode(false));
        view.findViewById(R.id.home_edit_reset).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.home_reset_confirm)
                        .setPositiveButton(R.string.home_reset_tiles, (d, w) -> {
                            HomeTileStore.reset(requireContext());
                            mBoard.setTiles(HomeTileStore.load(requireContext()));
                            refreshInstances();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());

        // Back leaves the edit mode before it does anything else
        mExitEditCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (mBoard != null) mBoard.setEditMode(false);
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), mExitEditCallback);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshInstances();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mBoard != null) {
            mBoard.setListener(null);
            mBoard.setEditMode(false);
        }
        mBoard = null;
        mEditBar = null;
        mExitEditCallback = null;
    }

    // ─── Instances ───────────────────────────────────────────────────────────

    /** Reads the instances the tiles show, the instance tiles are filled when it is done */
    private void refreshInstances() {
        if (mBoard == null) return;
        List<String> keys = new ArrayList<>();
        final String selectedKey = Instances.getSelectedInstanceKey();
        for (HomeTile tile : mBoard.getTiles()) {
            if (!HomeTileType.INSTANCE.name().equals(tile.type)) continue;
            String key = tile.instanceKey == null || tile.instanceKey.isEmpty() ? selectedKey : tile.instanceKey;
            if (key != null && !key.isEmpty() && !keys.contains(key)) keys.add(key);
        }
        MojoApplication.sExecutorService.execute(() -> {
            Map<String, Instance> loaded = new HashMap<>();
            for (String key : keys) {
                Instance instance = Instances.loadInstance(key);
                if (instance != null) loaded.put(key, instance);
            }
            View root = getView();
            if (root == null) return;
            root.post(() -> {
                if (mBoard != null) mBoard.setInstanceData(loaded, selectedKey);
            });
        });
    }

    private String resolveKey(@NonNull HomeTile tile) {
        return tile.instanceKey == null || tile.instanceKey.isEmpty()
                ? Instances.getSelectedInstanceKey() : tile.instanceKey;
    }

    /** Makes the instance of the tile the selected one, the version bar follows */
    private boolean selectInstance(@NonNull HomeTile tile) {
        Instance instance = Instances.loadInstance(resolveKey(tile));
        if (instance == null) {
            Toast.makeText(requireContext(), R.string.home_instance_missing, Toast.LENGTH_SHORT).show();
            return false;
        }
        Instances.setSelectedInstance(instance);
        ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, true);
        return true;
    }

    // ─── HomeTileBoard.Listener ──────────────────────────────────────────────

    @Override
    public void onTileClick(@NonNull HomeTile tile) {
        HomeTileType type = tile.getType();
        if (type == null) return;
        Context context = requireContext();
        MainMenuFragment mainMenu = getParentFragment() instanceof MainMenuFragment
                ? (MainMenuFragment) getParentFragment() : null;
        switch (type) {
            case WIKI:
                Tools.openURL(requireActivity(), Tools.URL_HOME);
                break;
            case DISCORD:
                Tools.openURL(requireActivity(), getString(R.string.social_media_invite));
                break;
            case CONTROLLER_SETTINGS:
                Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
                break;
            case CUSTOM_CONTROLS:
                startActivity(new Intent(context, CustomControlsActivity.class));
                break;
            case BROWSE_CONTENT:
                if (mainMenu != null) mainMenu.showContentPicker(false);
                break;
            case MANAGE_CONTENT:
                if (mainMenu != null) mainMenu.showContentPicker(true);
                break;
            case OPEN_FOLDER:
                if (mainMenu != null) mainMenu.openGameDirectory(context);
                break;
            case INSTALL_JAR:
                if (mainMenu != null) mainMenu.runInstallerWithConfirmation();
                break;
            case SHARE_LOGS:
                Tools.shareLog(context);
                break;
            case INSTANCE:
                selectInstance(tile);
                break;
            default:
                break;
        }
    }

    @Override
    public void onTilePlay(@NonNull HomeTile tile) {
        if (!selectInstance(tile)) return;
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    @Override
    public void onTileEdit(@NonNull HomeTile tile) {
        Instance instance = Instances.loadInstance(resolveKey(tile));
        if (instance == null) {
            Toast.makeText(requireContext(), R.string.home_instance_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = Tools.validOrNullString(instance.name);
        String title = name != null ? name : instance.getKey();
        CharSequence[] options = {
                getString(R.string.home_instance_edit),
                getString(R.string.home_instance_manage),
                getString(R.string.home_instance_browse)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setItems(options, (dialog, which) -> {
                    if (!selectInstance(tile)) return;
                    MainMenuFragment mainMenu = getParentFragment() instanceof MainMenuFragment
                            ? (MainMenuFragment) getParentFragment() : null;
                    if (which == 0) {
                        Tools.swapFragment(requireActivity(), InstanceEditorFragment.class,
                                InstanceEditorFragment.TAG, null);
                    } else if (mainMenu != null) {
                        mainMenu.showContentPicker(which == 1);
                    }
                })
                .show();
    }

    @Override
    public void onEmptyLongPress() {
        mBoard.setEditMode(true);
        showAddTileDialog();
    }

    @Override
    public void onEditModeChanged(boolean editMode) {
        if (mEditBar != null) mEditBar.setVisibility(editMode ? View.VISIBLE : View.GONE);
        if (mExitEditCallback != null) mExitEditCallback.setEnabled(editMode);
    }

    @Override
    public void onTilesChanged(@NonNull List<HomeTile> tiles) {
        HomeTileStore.save(requireContext().getApplicationContext(), tiles);
    }

    // ─── Dialogs ─────────────────────────────────────────────────────────────

    private void showAddTileDialog() {
        List<HomeTileType> available = new ArrayList<>();
        for (HomeTileType type : HomeTileType.values()) {
            if (type.unique && boardHas(type)) continue;
            available.add(type);
        }
        CharSequence[] labels = new CharSequence[available.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(available.get(i).label);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.home_add_tile_title)
                .setItems(labels, (dialog, which) -> {
                    HomeTileType type = available.get(which);
                    if (type == HomeTileType.INSTANCE) showInstancePicker();
                    else addTile(HomeTile.create(type));
                })
                .show();
    }

    private boolean boardHas(HomeTileType type) {
        for (HomeTile tile : mBoard.getTiles()) {
            if (type.name().equals(tile.type)) return true;
        }
        return false;
    }

    private void addTile(HomeTile tile) {
        if (mBoard == null) return;
        if (!mBoard.addTile(tile)) {
            Toast.makeText(requireContext(), R.string.home_no_space, Toast.LENGTH_LONG).show();
            return;
        }
        if (HomeTileType.INSTANCE.name().equals(tile.type)) refreshInstances();
    }

    /** Lists the instances, a tap adds the tile and the play button starts the instance right away */
    private void showInstancePicker() {
        MojoApplication.sExecutorService.execute(() -> {
            Instances loaded = null;
            try {
                loaded = Instances.loadDisplay();
            } catch (IOException ignored) {
            }
            final Instances instances = loaded;
            View root = getView();
            if (root == null) return;
            root.post(() -> {
                if (mBoard == null) return;
                if (instances == null || instances.list.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.home_pick_instance_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                presentInstancePicker(instances.list);
            });
        });
    }

    private void presentInstancePicker(List<DisplayInstance> list) {
        final androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
        ArrayAdapter<DisplayInstance> adapter = new ArrayAdapter<DisplayInstance>(requireContext(), 0, list) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View row = convertView != null ? convertView
                        : LayoutInflater.from(parent.getContext()).inflate(R.layout.item_home_instance_pick, parent, false);
                DisplayInstance instance = getItem(position);
                ((ImageView) row.findViewById(R.id.pick_icon))
                        .setImageDrawable(InstanceIconProvider.fetchIcon(row.getResources(), instance));
                String name = Tools.validOrNullString(instance.name);
                ((TextView) row.findViewById(R.id.pick_name)).setText(name != null ? name : instance.getKey());
                String version = Tools.validOrNullString(instance.versionId);
                if (Instance.VERSION_LATEST_RELEASE.equalsIgnoreCase(version)) version = getString(R.string.profiles_latest_release);
                else if (Instance.VERSION_LATEST_SNAPSHOT.equalsIgnoreCase(version)) version = getString(R.string.profiles_latest_snapshot);
                ((TextView) row.findViewById(R.id.pick_version)).setText(version == null ? "" : version);
                ImageButton play = row.findViewById(R.id.pick_play);
                play.setOnClickListener(v -> {
                    if (holder[0] != null) holder[0].dismiss();
                    Instances.setSelectedInstance(instance);
                    ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, true);
                    ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                });
                return row;
            }
        };
        holder[0] = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.home_pick_instance_title)
                .setAdapter(adapter, (dialog, which) -> {
                    HomeTile tile = HomeTile.create(HomeTileType.INSTANCE);
                    tile.instanceKey = adapter.getItem(which).getKey();
                    addTile(tile);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
