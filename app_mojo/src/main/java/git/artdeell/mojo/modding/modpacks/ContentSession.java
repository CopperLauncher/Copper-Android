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
import git.artdeell.mojo.modding.modpacks.models.ContentInfo;
import git.artdeell.mojo.modding.modpacks.models.ContentType;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.prefs.LauncherPreferences;

import java.util.ArrayList;
import java.util.Collections;
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

    public static final class VersionsState {
        public final Status status;
        @Nullable public final ModDetail detail;

        VersionsState(Status status, @Nullable ModDetail detail) {
            this.status = status;
            this.detail = detail;
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

    private ModItem mItem;
    private ModpackApi mApi;
    private ContentType mContentType;
    private String mCurseforgeKey;

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

        mVersions.setValue(new VersionsState(Status.LOADING, null));
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
            if (token == mToken) {
                mVersions.postValue(new VersionsState(detail != null ? Status.READY : Status.ERROR, detail));
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

    /** Re-checks which version is installed, call it after an installation finished */
    public void refreshInstalled() {
        VersionsState current = mVersions.getValue();
        ModpackApi api = mApi;
        if (current == null || current.detail == null || current.detail.isModpack
                || !(api instanceof InstalledStateResolver)) return;
        final ModDetail detail = current.detail;
        final int token = mToken;
        MojoApplication.sExecutorService.execute(() -> {
            try {
                ((InstalledStateResolver) api).resolveInstalledState(detail);
            } catch (Exception e) {
                Log.w(TAG, "Failed to refresh the installed version", e);
            }
            if (token == mToken) mVersions.postValue(new VersionsState(Status.READY, detail));
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
}
