package git.artdeell.mojo.fragments;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
import git.artdeell.mojo.home.HomeTileStyle;
import git.artdeell.mojo.home.HomeTileType;
import git.artdeell.mojo.instances.DisplayInstance;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.InstanceIconProvider;
import git.artdeell.mojo.instances.Instances;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the tile boards of the main menu: the sidebar on the left and the home screen on the
 * right. It loads and saves their tiles, does what a tapped tile is for and owns every dialog.
 * The right board only exists while the right pane shows its home screen, the left one always.
 */
public final class HomeTilesController {
    public enum Side { LEFT, RIGHT }

    private static final String TAG = "HomeTilesController";
    private static final int MAX_IMPORT_BYTES = 1024 * 1024;

    private final MainMenuFragment mHost;
    private HomeTileBoard mLeft, mRight;

    private final HomeTileBoard.Listener mLeftListener = new SideListener(Side.LEFT);
    private final HomeTileBoard.Listener mRightListener = new SideListener(Side.RIGHT);

    HomeTilesController(@NonNull MainMenuFragment host) {
        mHost = host;
    }

    // ─── Boards ──────────────────────────────────────────────────────────────

    void attachLeft(@NonNull HomeTileBoard board) {
        mLeft = board;
        board.setColumns(HomeTileStore.SIDEBAR_COLUMNS);
        // The sidebar always scrolls, "Infinite tiles" decides whether it can also keep growing
        board.setScrollable(true);
        board.setInfinite(HomeTileStyle.isInfinite(mHost.requireContext()));
        board.setListener(mLeftListener);
        board.setTiles(HomeTileStore.loadSidebar(mHost.requireContext()));
        refreshInstances();
        updateEditUi();
    }

    public void attachRight(@NonNull HomeTileBoard board) {
        mRight = board;
        board.setColumns(HomeTileStore.COLUMNS);
        boolean infinite = HomeTileStyle.isInfinite(mHost.requireContext());
        board.setScrollable(infinite);
        board.setInfinite(infinite);
        board.setListener(mRightListener);
        board.setTiles(HomeTileStore.load(mHost.requireContext()));
        refreshInstances();
        updateEditUi();
    }

    public void detachRight(@NonNull HomeTileBoard board) {
        if (mRight != board) return;
        board.setListener(null);
        mRight = null;
        updateEditUi();
    }

    void detachAll() {
        if (mLeft != null) mLeft.setListener(null);
        if (mRight != null) mRight.setListener(null);
        mLeft = null;
        mRight = null;
    }

    @Nullable
    private HomeTileBoard board(Side side) {
        return side == Side.LEFT ? mLeft : mRight;
    }

    @Nullable
    private Side sideOf(HomeTile tile) {
        if (mLeft != null && mLeft.getTiles().contains(tile)) return Side.LEFT;
        if (mRight != null && mRight.getTiles().contains(tile)) return Side.RIGHT;
        return null;
    }

    /** The tiles of a side, from its board or, when it isn't on screen, from what was saved */
    @NonNull
    private List<HomeTile> tilesOf(Side side) {
        HomeTileBoard board = board(side);
        if (board != null) return board.getTiles();
        return side == Side.LEFT ? HomeTileStore.loadSidebar(mHost.requireContext())
                : HomeTileStore.load(mHost.requireContext());
    }

    void onResume() {
        // The tile look is set in Launcher appearance
        if (mLeft != null) mLeft.refreshStyle();
        if (mRight != null) mRight.refreshStyle();
        refreshInstances();
    }

    // ─── Edit mode ───────────────────────────────────────────────────────────

    /** Leaves the edit mode on every board. @return whether it was on */
    boolean exitEditMode() {
        if (!HomeTileBoard.anyEditing()) return false;
        if (mLeft != null) mLeft.setEditMode(false);
        else if (mRight != null) mRight.setEditMode(false);
        return true;
    }

    private void updateEditUi() {
        mHost.setHomeEditing(HomeTileBoard.anyEditing(), mRight != null);
    }

    void onAddClicked(Side side) {
        showAddTileDialog(side);
    }

    // ─── Listener of one board ───────────────────────────────────────────────

    private final class SideListener implements HomeTileBoard.Listener {
        private final Side mSide;

        SideListener(Side side) {
            mSide = side;
        }

        @Override
        public void onTileClick(@NonNull HomeTile tile) {
            perform(tile);
        }

        @Override
        public void onTilePlay(@NonNull HomeTile tile) {
            play(tile);
        }

        @Override
        public void onTileEdit(@NonNull HomeTile tile) {
            showInstanceActions(tile);
        }

        @Override
        public void onTileTap(@NonNull HomeTile tile) {
            HomeTileType type = tile.getType();
            if (type == HomeTileType.SEPARATOR || type == HomeTileType.TEXT) showTextDialog(tile, type);
        }

        @Override
        public void onEmptyLongPress() {
            HomeTileBoard board = board(mSide);
            if (board == null) return;
            board.setEditMode(true);
            showAddTileDialog(mSide);
        }

        @Override
        public void onEditModeChanged(boolean editMode) {
            updateEditUi();
        }

        @Override
        public void onTilesChanged(@NonNull List<HomeTile> tiles) {
            Context context = mHost.getContext();
            if (context == null) return;
            if (mSide == Side.LEFT) HomeTileStore.saveSidebar(context.getApplicationContext(), tiles);
            else HomeTileStore.save(context.getApplicationContext(), tiles);
            // A tile may have come over from the other board with an instance this one doesn't know
            refreshInstances();
        }
    }

    // ─── What a tile does ────────────────────────────────────────────────────

    private void perform(@NonNull HomeTile tile) {
        HomeTileType type = tile.getType();
        if (type == null || mHost.getContext() == null) return;
        Context context = mHost.requireContext();
        switch (type) {
            case WIKI:
                Tools.openURL(mHost.requireActivity(), Tools.URL_HOME);
                break;
            case DISCORD:
                Tools.openURL(mHost.requireActivity(), context.getString(R.string.social_media_invite));
                break;
            case CONTROLLER_SETTINGS:
                Tools.swapFragment(mHost.requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
                break;
            case CUSTOM_CONTROLS:
                mHost.startActivity(new Intent(context, CustomControlsActivity.class));
                break;
            case BROWSE_CONTENT:
                mHost.showContentPicker(false);
                break;
            case MANAGE_CONTENT:
                mHost.showContentPicker(true);
                break;
            case OPEN_FOLDER:
                mHost.openGameDirectory(context);
                break;
            case INSTALL_JAR:
                mHost.runInstallerWithConfirmation();
                break;
            case SHARE_LOGS:
                Tools.shareLog(context);
                break;
            case INSTANCE:
                if (selectInstance(tile)) refreshInstances();
                break;
            default:
                break;
        }
    }

    private void play(@NonNull HomeTile tile) {
        if (!selectInstance(tile)) return;
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    private String resolveKey(@NonNull HomeTile tile) {
        return tile.instanceKey == null || tile.instanceKey.isEmpty()
                ? Instances.getSelectedInstanceKey() : tile.instanceKey;
    }

    /** Makes the instance of the tile the selected one, the version bar follows */
    private boolean selectInstance(@NonNull HomeTile tile) {
        Instance instance = Instances.loadInstance(resolveKey(tile));
        if (instance == null) {
            Toast.makeText(mHost.requireContext(), R.string.home_instance_missing, Toast.LENGTH_SHORT).show();
            return false;
        }
        Instances.setSelectedInstance(instance);
        ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, true);
        return true;
    }

    /** The pencil of an instance tile: edit it, manage its content or browse content for it */
    private void showInstanceActions(@NonNull HomeTile tile) {
        Instance instance = Instances.loadInstance(resolveKey(tile));
        if (instance == null) {
            Toast.makeText(mHost.requireContext(), R.string.home_instance_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = Tools.validOrNullString(instance.name);
        CharSequence[] options = {
                mHost.getString(R.string.home_instance_edit),
                mHost.getString(R.string.home_instance_manage),
                mHost.getString(R.string.home_instance_browse)
        };
        new MaterialAlertDialogBuilder(mHost.requireContext())
                .setTitle(name != null ? name : instance.getKey())
                .setItems(options, (dialog, which) -> {
                    if (!selectInstance(tile)) return;
                    if (which == 0) {
                        Tools.swapFragment(mHost.requireActivity(), InstanceEditorFragment.class,
                                InstanceEditorFragment.TAG, null);
                    } else {
                        mHost.showContentPicker(which == 1);
                    }
                })
                .show();
    }

    // ─── Instances ───────────────────────────────────────────────────────────

    /** Reads the instances the tiles show, the instance tiles are filled when it is done */
    void refreshInstances() {
        if (mHost.getContext() == null) return;
        final String selectedKey = Instances.getSelectedInstanceKey();
        List<String> keys = new ArrayList<>();
        collectKeys(mLeft, selectedKey, keys);
        collectKeys(mRight, selectedKey, keys);
        if (keys.isEmpty()) return;
        MojoApplication.sExecutorService.execute(() -> {
            Map<String, Instance> loaded = new HashMap<>();
            for (String key : keys) {
                Instance instance = Instances.loadInstance(key);
                if (instance != null) loaded.put(key, instance);
            }
            View root = mHost.getView();
            if (root == null) return;
            root.post(() -> {
                if (mLeft != null) mLeft.setInstanceData(loaded, selectedKey);
                if (mRight != null) mRight.setInstanceData(loaded, selectedKey);
            });
        });
    }

    private void collectKeys(@Nullable HomeTileBoard board, String selectedKey, List<String> keys) {
        if (board == null) return;
        for (HomeTile tile : board.getTiles()) {
            if (!HomeTileType.INSTANCE.name().equals(tile.type)) continue;
            String key = tile.instanceKey == null || tile.instanceKey.isEmpty() ? selectedKey : tile.instanceKey;
            if (key != null && !key.isEmpty() && !keys.contains(key)) keys.add(key);
        }
    }

    // ─── Adding tiles ────────────────────────────────────────────────────────

    private void showAddTileDialog(Side side) {
        if (board(side) == null || mHost.getContext() == null) return;
        List<HomeTileType> available = new ArrayList<>();
        for (HomeTileType type : HomeTileType.values()) {
            if (type.unique && boardsHave(type)) continue;
            available.add(type);
        }
        CharSequence[] labels = new CharSequence[available.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = mHost.getString(available.get(i).label);
        new MaterialAlertDialogBuilder(mHost.requireContext())
                .setTitle(R.string.home_add_tile_title)
                .setItems(labels, (dialog, which) -> {
                    HomeTileType type = available.get(which);
                    if (type == HomeTileType.INSTANCE) {
                        showInstancePicker(side);
                    } else {
                        HomeTile tile = HomeTile.create(type);
                        if (type == HomeTileType.TEXT) tile.label = mHost.getString(R.string.home_tile_text);
                        addTile(side, tile);
                    }
                })
                .show();
    }

    private boolean boardsHave(HomeTileType type) {
        for (HomeTileBoard board : new HomeTileBoard[]{mLeft, mRight}) {
            if (board == null) continue;
            for (HomeTile tile : board.getTiles()) {
                if (type.name().equals(tile.type)) return true;
            }
        }
        return false;
    }

    private void addTile(Side side, HomeTile tile) {
        HomeTileBoard board = board(side);
        if (board == null) return;
        if (!board.addTile(tile)) {
            Toast.makeText(mHost.requireContext(), R.string.home_no_space, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Lists the instances. Each has a button that adds it as a tile; tapping the row adds it too.
     */
    private void showInstancePicker(Side side) {
        MojoApplication.sExecutorService.execute(() -> {
            Instances loaded = null;
            try {
                loaded = Instances.loadDisplay();
            } catch (Exception e) {
                android.util.Log.w(TAG, "Couldn't list the instances", e);
            }
            final Instances instances = loaded;
            View root = mHost.getView();
            if (root == null) return;
            root.post(() -> {
                if (mHost.getContext() == null || !mHost.isAdded()) return;
                if (instances == null || instances.list.isEmpty()) {
                    Toast.makeText(mHost.requireContext(),
                            instances == null ? R.string.home_instance_load_failed : R.string.home_pick_instance_empty,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                presentInstancePicker(side, instances.list);
            });
        });
    }

    private void presentInstancePicker(Side side, List<DisplayInstance> list) {
        final androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
        ArrayAdapter<DisplayInstance> adapter = new ArrayAdapter<DisplayInstance>(mHost.requireContext(), 0, list) {
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
                if (Instance.VERSION_LATEST_RELEASE.equalsIgnoreCase(version)) version = mHost.getString(R.string.profiles_latest_release);
                else if (Instance.VERSION_LATEST_SNAPSHOT.equalsIgnoreCase(version)) version = mHost.getString(R.string.profiles_latest_snapshot);
                ((TextView) row.findViewById(R.id.pick_version)).setText(version == null ? "" : version);
                ImageButton add = row.findViewById(R.id.pick_add);
                add.setOnClickListener(v -> {
                    if (holder[0] != null) holder[0].dismiss();
                    addInstanceTile(side, instance);
                });
                return row;
            }
        };
        holder[0] = new MaterialAlertDialogBuilder(mHost.requireContext())
                .setTitle(R.string.home_pick_instance_title)
                .setAdapter(adapter, (dialog, which) -> addInstanceTile(side, adapter.getItem(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addInstanceTile(Side side, DisplayInstance instance) {
        HomeTile tile = HomeTile.create(HomeTileType.INSTANCE);
        tile.instanceKey = instance.getKey();
        addTile(side, tile);
        refreshInstances();
    }

    // ─── Text of a separator or a text tile ──────────────────────────────────

    private void showTextDialog(@NonNull HomeTile tile, HomeTileType type) {
        Context context = mHost.requireContext();
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(type == HomeTileType.TEXT ? R.string.home_text_hint : R.string.home_separator_hint);
        input.setText(tile.label == null ? "" : tile.label);
        input.setSelection(input.getText().length());
        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(context);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(context)
                .setTitle(type == HomeTileType.TEXT ? R.string.home_text_title : R.string.home_separator_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    tile.label = text.isEmpty() ? null : text;
                    Side side = sideOf(tile);
                    HomeTileBoard board = side == null ? null : board(side);
                    if (board == null) return;
                    board.refreshTile(tile);
                    saveSide(side);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveSide(Side side) {
        HomeTileBoard board = board(side);
        if (board == null || mHost.getContext() == null) return;
        Context context = mHost.requireContext().getApplicationContext();
        if (side == Side.LEFT) HomeTileStore.saveSidebar(context, board.getTiles());
        else HomeTileStore.save(context, board.getTiles());
    }

    // ─── More: reset, share, import ──────────────────────────────────────────

    void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(mHost.requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.home_reset_layout);
        menu.getMenu().add(0, 2, 1, R.string.home_share_layout);
        menu.getMenu().add(0, 3, 2, R.string.home_import_layout);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    confirmReset();
                    return true;
                case 2:
                    shareLayout();
                    return true;
                case 3:
                    mHost.launchLayoutImport();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void confirmReset() {
        new MaterialAlertDialogBuilder(mHost.requireContext())
                .setMessage(R.string.home_reset_confirm)
                .setPositiveButton(R.string.home_reset_layout, (d, w) -> {
                    HomeTileStore.reset(mHost.requireContext().getApplicationContext());
                    if (mLeft != null) mLeft.setTiles(HomeTileStore.loadSidebar(mHost.requireContext()));
                    if (mRight != null) mRight.setTiles(HomeTileStore.load(mHost.requireContext()));
                    refreshInstances();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Writes the layout of both sides to a .json file and opens the share sheet for it */
    private void shareLayout() {
        final String json = HomeTileStore.toJson(tilesOf(Side.RIGHT), tilesOf(Side.LEFT));
        final Context context = mHost.requireContext();
        MojoApplication.sExecutorService.execute(() -> {
            File file = new File(Tools.DIR_GAME_HOME, "home_layout.json");
            try {
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                Tools.runOnUiThread(() -> Tools.openPath(context, file, true));
            } catch (IOException e) {
                Tools.runOnUiThread(() -> Toast.makeText(context, R.string.home_export_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Reads the picked file and replaces the layout with the one in it */
    void importLayout(@Nullable Uri uri) {
        if (uri == null || mHost.getContext() == null) return;
        final Context context = mHost.requireContext().getApplicationContext();
        MojoApplication.sExecutorService.execute(() -> {
            String text = null;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        if (out.size() > MAX_IMPORT_BYTES) throw new IOException("Too big for a layout");
                    }
                    text = out.toString("UTF-8");
                }
            } catch (IOException | SecurityException ignored) {
            }
            final HomeTileStore.Layout layout = HomeTileStore.parse(text);
            Tools.runOnUiThread(() -> {
                if (layout == null) {
                    Toast.makeText(context, R.string.home_import_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                if (!mHost.isAdded()) return;
                HomeTileStore.save(context, layout.tiles);
                if (mRight != null) mRight.setTiles(layout.tiles);
                if (layout.sidebarTiles != null) {
                    HomeTileStore.saveSidebar(context, layout.sidebarTiles);
                    if (mLeft != null) mLeft.setTiles(layout.sidebarTiles);
                }
                refreshInstances();
                Toast.makeText(context, R.string.home_import_done, Toast.LENGTH_SHORT).show();
            });
        });
    }
}
