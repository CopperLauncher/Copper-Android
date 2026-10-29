package git.artdeell.mojo.modding.modpacks;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.modding.modpacks.api.ContentInfoLoader;
import git.artdeell.mojo.modding.modpacks.api.ContentInfoLoader.ChangelogSource;
import git.artdeell.mojo.modding.modpacks.api.InstalledStateResolver;
import git.artdeell.mojo.modding.modpacks.api.ModpackApi;
import git.artdeell.mojo.modding.modpacks.api.UnfilteredVersionsProvider;
import git.artdeell.mojo.modding.modpacks.models.ContentInfo;
import git.artdeell.mojo.modding.modpacks.models.ContentType;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.prefs.LauncherPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

/**
 * The piece of content that is currently opened in the detail screens. It is shared by the
 * description side ({@code ContentDetailFragment}) and the version side
 * ({@code ContentVersionsFragment}), which are two separate fragments in landscape, and it
 * survives rotations. Everything is loaded once here and observed by both.
 */
public class ContentSession extends ViewModel {
    private static final String TAG = "ContentSession";
    private static final int CHANGELOG_PAGE_SIZE = 10;

    public enum Status { LOADING, READY, ERROR }

    public static final int TAB_DESCRIPTION = 0;
    public static final int TAB_GALLERY = 1;
    public static final int TAB_CHANGELOG = 2;
    public static final int TAB_VERSIONS = 3;

    public static final class VersionsState {
        public final Status status;
        @Nullable public final ModDetail detail;
        /** The "Show incompatible" button is on, the list has the versions that don't match the filters too */
        public final boolean showingAll;
        /** The API can give the versions that don't match the filters, so the button makes sense */
        public final boolean canShowAll;
        /** Ids of the versions that match the filters, used to mark the other ones as incompatible */
        public final Set<String> compatibleIds;

        VersionsState(Status status, @Nullable ModDetail detail, boolean showingAll, boolean canShowAll,
                      Set<String> compatibleIds) {
            this.status = status;
            this.detail = detail;
            this.showingAll = showingAll;
            this.canShowAll = canShowAll;
            this.compatibleIds = compatibleIds;
        }
    }

    public static final class InfoState {
        public final Status status;
        @Nullable public final ContentInfo info;

        InfoState(Status status, @Nullable ContentInfo info) {
            this.status = status;
            this.info = info;
        }
    }

    public static final class ChangelogState {
        public final List<ContentInfo.ChangelogEntry> entries;
        public final boolean loading;
        public final boolean hasMore;
        public final boolean error;

        ChangelogState(List<ContentInfo.ChangelogEntry> entries, boolean loading, boolean hasMore, boolean error) {
            this.entries = entries;
            this.loading = loading;
            this.hasMore = hasMore;
            this.error = error;
        }
    }

    private final MutableLiveData<VersionsState> mVersions = new MutableLiveData<>();
    private final MutableLiveData<InfoState> mInfo = new MutableLiveData<>();
    private final MutableLiveData<ChangelogState> mChangelog = new MutableLiveData<>();
    /* The section shown in the right pane of the landscape layout, picked with the left pane buttons */
    private final MutableLiveData<Integer> mSelectedTab = new MutableLiveData<>(TAB_VERSIONS);

    private ModItem mItem;
    private ModpackApi mApi;
    private ContentType mContentType;
    private String mCurseforgeKey;

    /* The versions that match the filters (what the list shows by default) and every version
     * (loaded the first time "Show incompatible" is switched on) */
    private ModDetail mFilteredDetail;
    private ModDetail mAllDetail;
    private boolean mShowAll;
    private boolean mCanShowAll;

    private ChangelogSource mChangelogSource;
    private final List<ContentInfo.ChangelogEntry> mChangelogEntries = new ArrayList<>();
    private boolean mChangelogStarted;
    private boolean mChangelogLoading;

    /* Bumped on every open(), results of an older one are dropped */
    private volatile int mToken;

    /**
     * Starts showing a piece of content and loads everything about it. UI thread only.
     * @param contentType null for modpacks
     */
    public void open(@NonNull Context context, @NonNull ModItem item, @NonNull ModpackApi api,
                     @Nullable ContentType contentType) {
        final int token = ++mToken;
        mItem = item;
        mApi = api;
        mContentType = contentType;
        mCurseforgeKey = LauncherPreferences.PREF_DISABLE_CURSEFORGE_API
                ? null : LauncherPreferences.resolveCurseforgeApiKey(context.getApplicationContext());

        mChangelogSource = null;
        mChangelogEntries.clear();
        mChangelogStarted = false;
        mChangelogLoading = false;

        mFilteredDetail = null;
        mAllDetail = null;
        mShowAll = false;
        mCanShowAll = api instanceof UnfilteredVersionsProvider;

        mSelectedTab.setValue(TAB_VERSIONS);
        mVersions.setValue(versionsState(Status.LOADING));
        mInfo.setValue(new InfoState(Status.LOADING, null));
        mChangelog.setValue(new ChangelogState(Collections.emptyList(), false, true, false));

        final String curseforgeKey = mCurseforgeKey;
        MojoApplication.sExecutorService.execute(() -> {
            ModDetail detail = null;
            try {
                detail = api.getModDetails(item);
            } catch (Exception e) {
                Log.w(TAG, "Failed to load the versions of " + item.id, e);
            }
            final ModDetail loaded = detail;
            if (token == mToken) {
                Tools.runOnUiThread(() -> {
                    if (token != mToken) return;
                    mFilteredDetail = loaded;
                    mVersions.setValue(versionsState(loaded != null ? Status.READY : Status.ERROR));
                });
            }
        });
        MojoApplication.sExecutorService.execute(() -> {
            ContentInfo info = null;
            try {
                info = ContentInfoLoader.load(item, curseforgeKey);
            } catch (Exception e) {
                Log.w(TAG, "Failed to load the description of " + item.id, e);
            }
            if (token == mToken) {
                mInfo.postValue(new InfoState(info != null ? Status.READY : Status.ERROR, info));
            }
        });
    }

    /** The state the version list should show right now */
    private VersionsState versionsState(Status status) {
        ModDetail shown = mShowAll ? mAllDetail : mFilteredDetail;
        Set<String> compatible = new HashSet<>();
        if (mFilteredDetail != null && mFilteredDetail.versionIds != null) {
            Collections.addAll(compatible, mFilteredDetail.versionIds);
        }
        if (status == Status.READY && shown == null) status = Status.ERROR;
        return new VersionsState(status, shown, mShowAll, mCanShowAll, compatible);
    }

    /**
     * Switches the "Show incompatible" button of the version list. When it is on the list also has
     * the versions that don't match the Minecraft version / loader filters. UI thread only.
     */
    public void toggleShowIncompatible() {
        VersionsState current = mVersions.getValue();
        if (!mCanShowAll || mItem == null || current == null || current.status == Status.LOADING) return;

        mShowAll = !mShowAll;
        if (!mShowAll || mAllDetail != null) {
            mVersions.setValue(versionsState(Status.READY));
            return;
        }

        mVersions.setValue(versionsState(Status.LOADING));
        final int token = mToken;
        final ModItem item = mItem;
        final ModpackApi api = mApi;
        MojoApplication.sExecutorService.execute(() -> {
            ModDetail all = null;
            try {
                all = ((UnfilteredVersionsProvider) api).getAllVersions(item);
            } catch (Exception e) {
                Log.w(TAG, "Failed to load all versions of " + item.id, e);
            }
            final ModDetail loaded = all;
            Tools.runOnUiThread(() -> {
                if (token != mToken) return;
                mAllDetail = loaded;
                mVersions.setValue(versionsState(loaded != null ? Status.READY : Status.ERROR));
            });
        });
    }

    /** Re-checks which version is installed, call it after an installation finished */
    public void refreshInstalled() {
        ModpackApi api = mApi;
        if (!(api instanceof InstalledStateResolver)) return;
        final List<ModDetail> details = new ArrayList<>();
        if (mFilteredDetail != null && !mFilteredDetail.isModpack) details.add(mFilteredDetail);
        if (mAllDetail != null && !mAllDetail.isModpack) details.add(mAllDetail);
        if (details.isEmpty()) return;
        final int token = mToken;
        MojoApplication.sExecutorService.execute(() -> {
            for (ModDetail detail : details) {
                try {
                    ((InstalledStateResolver) api).resolveInstalledState(detail);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to refresh the installed version", e);
                }
            }
            Tools.runOnUiThread(() -> {
                if (token == mToken) mVersions.setValue(versionsState(Status.READY));
            });
        });
    }

    /** Loads the first page of changelogs the first time it is called. UI thread only. */
    public void startChangelogs() {
        if (mChangelogStarted) return;
        mChangelogStarted = true;
        loadMoreChangelogs();
    }

    /** Loads the next page of changelogs, also used to retry after an error. UI thread only. */
    public void loadMoreChangelogs() {
        if (mChangelogLoading || mItem == null) return;
        if (mChangelogSource == null) {
            mChangelogSource = ContentInfoLoader.createChangelogSource(mItem, mCurseforgeKey);
        }
        final ChangelogSource source = mChangelogSource;
        if (source == null) {
            mChangelog.setValue(new ChangelogState(copyEntries(), false, false, false));
            return;
        }
        if (!source.hasMore()) return;

        mChangelogLoading = true;
        mChangelog.setValue(new ChangelogState(copyEntries(), true, true, false));
        final int token = mToken;
        MojoApplication.sExecutorService.execute(() -> {
            List<ContentInfo.ChangelogEntry> page = null;
            try {
                page = source.loadMore(CHANGELOG_PAGE_SIZE);
            } catch (Exception e) {
                Log.w(TAG, "Failed to load changelogs", e);
            }
            final List<ContentInfo.ChangelogEntry> result = page;
            Tools.runOnUiThread(() -> {
                if (token != mToken) return;
                mChangelogLoading = false;
                if (result == null) {
                    mChangelog.setValue(new ChangelogState(copyEntries(), false, true, true));
                } else {
                    mChangelogEntries.addAll(result);
                    mChangelog.setValue(new ChangelogState(copyEntries(), false, source.hasMore(), false));
                }
            });
        });
    }

    private List<ContentInfo.ChangelogEntry> copyEntries() {
        return new ArrayList<>(mChangelogEntries);
    }

    @Nullable public ModItem getItem() { return mItem; }
    @Nullable public ModpackApi getApi() { return mApi; }
    /** @return the kind of content, null for modpacks */
    @Nullable public ContentType getContentType() { return mContentType; }

    public LiveData<VersionsState> getVersions() { return mVersions; }
    public LiveData<InfoState> getInfo() { return mInfo; }
    public LiveData<ChangelogState> getChangelog() { return mChangelog; }
    public LiveData<Integer> getSelectedTab() { return mSelectedTab; }

    public void setSelectedTab(int tab) {
        Integer current = mSelectedTab.getValue();
        if (current == null || current != tab) mSelectedTab.setValue(tab);
    }
}
