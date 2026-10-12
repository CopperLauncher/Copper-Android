package git.artdeell.mojo.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kdt.mcgui.MineButton;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.modding.modpacks.ContentSession;
import git.artdeell.mojo.modding.modpacks.InstallButtonStyle;
import git.artdeell.mojo.modding.modpacks.api.ModpackApi;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;
import git.artdeell.mojo.utils.CountFormatter;
import git.artdeell.mojo.progresskeeper.TaskCountListener;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lists the versions of the content opened in {@link ContentDetailFragment}, and nothing else:
 * every version has its own button, Install for a new one, Update / Downgrade when another
 * version of the same content is already installed and Installed for the installed one.
 * In landscape it is the right pane, in portrait it is the Versions tab of the detail screen.
 */
public class ContentVersionsFragment extends Fragment {
    public static final String TAG = "ContentVersionsFragment";

    private ContentSession mSession;
    private VersionAdapter mAdapter;
    private View mProgress;
    private TextView mCount;
    private TextView mStatus;
    private RecyclerView mList;
    private TaskCountListener mTaskCountListener;
    private boolean mTasksRunning;

    public ContentVersionsFragment() {
        super(R.layout.fragment_content_versions);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mSession = new ViewModelProvider(requireActivity()).get(ContentSession.class);

        mProgress = view.findViewById(R.id.content_versions_progress);
        mCount = view.findViewById(R.id.content_versions_count);
        mStatus = view.findViewById(R.id.content_versions_status);
        mList = view.findViewById(R.id.content_versions_list);

        mAdapter = new VersionAdapter();
        mList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mList.setAdapter(mAdapter);

        mTasksRunning = ProgressKeeper.getTaskCount() != 0;
        mAdapter.setActionsEnabled(!mTasksRunning);
        mTaskCountListener = taskCount -> {
            Tools.runOnUiThread(() -> onTaskCountChanged(taskCount));
            return false;
        };
        ProgressKeeper.addTaskCountListener(mTaskCountListener);

        mSession.getVersions().observe(getViewLifecycleOwner(), this::onVersions);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mTaskCountListener != null) ProgressKeeper.removeTaskCountListener(mTaskCountListener);
        mTaskCountListener = null;
        mList.setAdapter(null);
    }

    private void onTaskCountChanged(int taskCount) {
        if (mAdapter == null) return;
        boolean running = taskCount != 0;
        boolean justFinished = mTasksRunning && !running;
        mTasksRunning = running;
        mAdapter.setActionsEnabled(!running);
        // Whatever just got installed changes what the buttons should say
        if (justFinished) mSession.refreshInstalled();
    }

    private void onVersions(@Nullable ContentSession.VersionsState state) {
        if (state == null || state.status == ContentSession.Status.LOADING) {
            mProgress.setVisibility(View.VISIBLE);
            mStatus.setVisibility(View.GONE);
            mCount.setVisibility(View.GONE);
            mAdapter.setDetail(null);
            return;
        }
        mProgress.setVisibility(View.GONE);
        ModDetail detail = state.detail;
        boolean empty = state.status == ContentSession.Status.ERROR
                || detail == null || detail.versionNames == null || detail.versionNames.length == 0;
        mAdapter.setDetail(empty ? null : detail);
        if (empty) {
            mCount.setVisibility(View.GONE);
            mStatus.setText(R.string.content_versions_empty);
            mStatus.setVisibility(View.VISIBLE);
        } else {
            mStatus.setVisibility(View.GONE);
            mCount.setText(getString(R.string.content_versions_count, detail.versionNames.length));
            mCount.setVisibility(View.VISIBLE);
        }
    }

    private void startInstall(@NonNull Context context, @NonNull ModDetail detail, int position, boolean confirm) {
        ModpackApi api = mSession.getApi();
        if (api == null) return;
        Runnable install = () -> api.handleInstallation(context.getApplicationContext(), detail, position);
        if (!confirm) {
            install.run();
            return;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.mod_reinstall_confirm_title)
                .setMessage(context.getString(R.string.mod_reinstall_confirm_message, detail.title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> install.run())
                .show();
    }

    @Nullable
    private static String formatDate(@Nullable String iso) {
        if (iso == null || iso.length() < 10) return null;
        String day = iso.substring(0, 10);
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day);
            return date == null ? day : DateFormat.getDateInstance(DateFormat.MEDIUM).format(date);
        } catch (ParseException e) {
            return day;
        }
    }

    private String describe(ModDetail detail, int position) {
        List<String> parts = new ArrayList<>();
        String type = at(detail.versionTypes, position);
        if (type != null) {
            switch (type) {
                case "release": parts.add(getString(R.string.content_version_release)); break;
                case "beta": parts.add(getString(R.string.content_version_beta)); break;
                case "alpha": parts.add(getString(R.string.content_version_alpha)); break;
                default: break;
            }
        }
        String loaders = at(detail.versionLoaders, position);
        if (loaders != null) parts.add(loaders);
        String mcVersion = at(detail.mcVersionNames, position);
        if (mcVersion != null) parts.add(mcVersion);
        String date = formatDate(at(detail.versionDates, position));
        if (date != null) parts.add(date);
        if (detail.versionDownloads != null && position < detail.versionDownloads.length
                && detail.versionDownloads[position] >= 0) {
            parts.add(getString(R.string.content_version_downloads,
                    CountFormatter.compact(detail.versionDownloads[position])));
        }
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (joined.length() > 0) joined.append(" · ");
            joined.append(part);
        }
        return joined.toString();
    }

    @Nullable
    private static String at(@Nullable String[] array, int position) {
        if (array == null || position >= array.length) return null;
        String value = array[position];
        return value == null || value.isEmpty() ? null : value;
    }

    private class VersionAdapter extends RecyclerView.Adapter<VersionAdapter.Holder> {
        private ModDetail mDetail;
        private boolean mActionsEnabled = true;

        void setDetail(@Nullable ModDetail detail) {
            mDetail = detail;
            notifyDataSetChanged();
        }

        void setActionsEnabled(boolean enabled) {
            mActionsEnabled = enabled;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_content_version, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(mDetail, position, mActionsEnabled);
        }

        @Override
        public int getItemCount() {
            return mDetail == null || mDetail.versionNames == null ? 0 : mDetail.versionNames.length;
        }

        class Holder extends RecyclerView.ViewHolder {
            private final TextView mName;
            private final TextView mDetails;
            private final ImageView mInstalledIcon;
            private final MineButton mAction;

            Holder(@NonNull View itemView) {
                super(itemView);
                mName = itemView.findViewById(R.id.version_item_name);
                mDetails = itemView.findViewById(R.id.version_item_details);
                mInstalledIcon = itemView.findViewById(R.id.version_item_installed_icon);
                mAction = itemView.findViewById(R.id.version_item_action);
            }

            void bind(@Nullable ModDetail detail, int position, boolean actionsEnabled) {
                if (detail == null) return;
                String[] names = detail.baseVersionNames != null ? detail.baseVersionNames : detail.versionNames;
                mName.setText(names[position]);

                String details = describe(detail, position);
                mDetails.setText(details);
                mDetails.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);

                InstallButtonStyle.State state = InstallButtonStyle.stateOf(detail, position);
                mInstalledIcon.setVisibility(state == InstallButtonStyle.State.INSTALLED ? View.VISIBLE : View.GONE);
                InstallButtonStyle.apply(mAction, state);
                mAction.setEnabled(actionsEnabled);
                mAction.setAlpha(actionsEnabled ? 1f : 0.5f);
                mAction.setOnClickListener(v -> {
                    int adapterPosition = getAdapterPosition();
                    if (adapterPosition == RecyclerView.NO_POSITION) return;
                    startInstall(v.getContext(), detail, adapterPosition, state == InstallButtonStyle.State.INSTALLED);
                });
            }
        }
    }
}
