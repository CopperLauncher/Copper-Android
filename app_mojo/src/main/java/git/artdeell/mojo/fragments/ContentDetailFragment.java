package git.artdeell.mojo.fragments;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.imageview.ShapeableImageView;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.modding.modpacks.ContentSession;
import git.artdeell.mojo.modding.modpacks.api.ModpackApi;
import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.ContentInfo;
import git.artdeell.mojo.modding.modpacks.models.ContentType;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.utils.ContentBody;
import git.artdeell.mojo.utils.ContentTextRenderer;
import git.artdeell.mojo.utils.CountFormatter;
import git.artdeell.mojo.utils.RemoteImageLoader;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;

/**
 * What opens when a mod, resource pack, shader pack or modpack is tapped in the content browser.
 * <ul>
 *   <li>Landscape: two instances of this fragment, see {@link MainMenuFragment#openContentDetail()}.
 *   {@link #MODE_LEFT} is the left pane (back button, logo, name and the Description / Gallery /
 *   Changelog / Versions navigation items), {@link #MODE_RIGHT} is the right pane that shows what the
 *   buttons picked, the versions by default. The picked section lives in {@link ContentSession}.</li>
 *   <li>Portrait ({@link #MODE_FULL}): a full screen with the same sections as tabs.</li>
 * </ul>
 * The content itself lives in {@link ContentSession}.
 */
public class ContentDetailFragment extends Fragment {
    public static final String TAG = "ContentDetailFragment";
    public static final String TAG_RIGHT = "ContentDetailRightFragment";

    public static final int MODE_FULL = 0;
    public static final int MODE_LEFT = 1;
    public static final int MODE_RIGHT = 2;
    private static final String ARG_MODE = "mode";

    private static final String STATE_TAB = "tab";
    private static final int TAB_DESCRIPTION = ContentSession.TAB_DESCRIPTION;
    private static final int TAB_GALLERY = ContentSession.TAB_GALLERY;
    private static final int TAB_CHANGELOG = ContentSession.TAB_CHANGELOG;
    private static final int TAB_VERSIONS = ContentSession.TAB_VERSIONS;

    private ContentSession mSession;
    private int mMode = MODE_FULL;
    private int mTab = TAB_VERSIONS;

    private View mScroll;
    private View mVersionsContainer;
    private final TextView[] mTabs = new TextView[4];

    private ShapeableImageView mLogo;
    private View mOpenPageButton;

    private View mDescriptionSection, mGallerySection, mChangelogSection;
    private View mDescriptionProgress;
    private TextView mDescriptionNote;
    private ViewGroup mDescriptionBody;
    private ContentBody mBody;
    private int mBodyToken;
    private View mGalleryProgress;
    private TextView mGalleryEmpty;
    private ViewGroup mGalleryList;
    private ViewGroup mChangelogList;
    private View mChangelogProgress;
    private TextView mChangelogStatus;
    private TextView mChangelogMore;
    private int mChangelogShown;
    private ContentInfo mLoadedInfo;
    private boolean mGalleryBuilt;

    private final List<Future<?>> mImageLoads = new ArrayList<>();

    /**
     * Opens the detail screens for a piece of content, in the two panes of the landscape main menu
     * or as a full screen in portrait.
     * @param contentType null for modpacks
     */
    public static void open(@NonNull FragmentActivity activity, @NonNull ModItem item,
                            @NonNull ModpackApi api, @Nullable ContentType contentType) {
        ContentSession session = new ViewModelProvider(activity).get(ContentSession.class);
        session.open(activity, item, api, contentType);

        MainMenuFragment mainMenu = Tools.getTwoPaneMainMenu(activity);
        if (mainMenu != null) {
            mainMenu.openContentDetail();
        } else {
            Tools.swapFragmentFullScreen(activity, ContentDetailFragment.class, TAG, null);
        }
    }

    public static Bundle args(int mode) {
        Bundle args = new Bundle();
        args.putInt(ARG_MODE, mode);
        return args;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Fragment parent = getParentFragment();
        boolean twoPane = parent instanceof MainMenuFragment && ((MainMenuFragment) parent).isTwoPane();
        Bundle arguments = getArguments();
        mMode = twoPane && arguments != null ? arguments.getInt(ARG_MODE, MODE_FULL) : MODE_FULL;
        int layout;
        switch (mMode) {
            case MODE_LEFT: layout = R.layout.fragment_content_detail_pane; break;
            case MODE_RIGHT: layout = R.layout.fragment_content_detail_right; break;
            default: layout = R.layout.fragment_content_detail; break;
        }
        return inflater.inflate(layout, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View back = view.findViewById(R.id.content_detail_back);
        if (back != null) back.setOnClickListener(v -> goBack());

        mSession = new ViewModelProvider(requireActivity()).get(ContentSession.class);
        ModItem item = mSession.getItem();
        if (item == null) {
            // The process was recreated while this screen was open, there is nothing to show anymore.
            // The left pane (or the full screen) is the one that leaves, the right pane goes with it.
            if (mMode != MODE_RIGHT) {
                Toast.makeText(requireContext(), R.string.content_session_lost, Toast.LENGTH_SHORT).show();
                view.post(this::goBack);
            }
            return;
        }

        if (mMode != MODE_RIGHT) {
            mLogo = view.findViewById(R.id.content_detail_logo);
            mOpenPageButton = view.findViewById(R.id.content_detail_open_page);
            bindHero(view, item);
        }

        if (mMode == MODE_LEFT) {
            setupNav(view);
            mSession.getInfo().observe(getViewLifecycleOwner(), state -> onInfo(item, state));
            return;
        }

        mScroll = view.findViewById(R.id.content_detail_scroll);
        mVersionsContainer = view.findViewById(R.id.content_detail_versions_container);

        mDescriptionSection = view.findViewById(R.id.content_section_description);
        mGallerySection = view.findViewById(R.id.content_section_gallery);
        mChangelogSection = view.findViewById(R.id.content_section_changelog);
        mDescriptionProgress = view.findViewById(R.id.content_description_progress);
        mDescriptionNote = view.findViewById(R.id.content_description_note);
        mDescriptionBody = view.findViewById(R.id.content_description_body);
        mGalleryProgress = view.findViewById(R.id.content_gallery_progress);
        mGalleryEmpty = view.findViewById(R.id.content_gallery_empty);
        mGalleryList = view.findViewById(R.id.content_gallery_list);
        mChangelogList = view.findViewById(R.id.content_changelog_list);
        mChangelogProgress = view.findViewById(R.id.content_changelog_progress);
        mChangelogStatus = view.findViewById(R.id.content_changelog_status);
        mChangelogMore = view.findViewById(R.id.content_changelog_more);
        mChangelogShown = 0;
        mLoadedInfo = null;
        mGalleryBuilt = false;

        mChangelogMore.setOnClickListener(v -> mSession.loadMoreChangelogs());

        if (mMode == MODE_FULL) {
            setupTabs(view);
            // The versions are what the screen is mostly opened for
            if (savedInstanceState != null) mTab = savedInstanceState.getInt(STATE_TAB, TAB_VERSIONS);
            else mTab = TAB_VERSIONS;
            selectTab(mTab);
        } else {
            // The left pane buttons pick the section, it starts on the versions
            mSession.getSelectedTab().observe(getViewLifecycleOwner(), tab -> {
                if (tab != null) showSection(tab);
            });
        }

        mSession.getInfo().observe(getViewLifecycleOwner(), state -> onInfo(item, state));
        mSession.getChangelog().observe(getViewLifecycleOwner(), this::onChangelog);
    }

    /** The navigation items of the left pane, they decide what the right pane shows */
    private void setupNav(View view) {
        final int[] ids = {R.id.content_nav_description, R.id.content_nav_gallery,
                R.id.content_nav_changelog, R.id.content_nav_versions};
        final ViewGroup[] items = new ViewGroup[ids.length];
        for (int tab = 0; tab < ids.length; tab++) {
            final int picked = tab;
            items[tab] = view.findViewById(ids[tab]);
            items[tab].setOnClickListener(v -> mSession.setSelectedTab(picked));
        }
        mSession.getSelectedTab().observe(getViewLifecycleOwner(), tab -> {
            if (tab != null && tab >= 0 && tab < items.length) markNav(items, tab);
        });
    }

    /** Fills the picked item with the secondary container, same as a Material 3 navigation drawer */
    private static void markNav(ViewGroup[] items, int tab) {
        int selectedColor = MaterialColors.getColor(items[0],
                com.google.android.material.R.attr.colorOnSecondaryContainer);
        int normalColor = MaterialColors.getColor(items[0],
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        for (int i = 0; i < items.length; i++) {
            boolean picked = i == tab;
            int color = picked ? selectedColor : normalColor;
            items[i].setSelected(picked);
            ((ImageView) items[i].getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
            ((TextView) items[i].getChildAt(1)).setTextColor(color);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, mTab);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        for (Future<?> load : mImageLoads) load.cancel(true);
        mImageLoads.clear();
        mBodyToken++;
        if (mBody != null) mBody.cancel();
        mBody = null;
        mDescriptionBody = null;
        mLogo = null;
        mGalleryList = null;
        mChangelogList = null;
    }

    private void goBack() {
        Tools.removeCurrentFragment(requireActivity());
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private void bindHero(View view, ModItem item) {
        ((TextView) view.findViewById(R.id.content_detail_title)).setText(item.title);
        TextView summary = view.findViewById(R.id.content_detail_summary);
        summary.setText(item.description);
        summary.setVisibility(item.description == null || item.description.isEmpty() ? View.GONE : View.VISIBLE);

        ImageView sourceIcon = view.findViewById(R.id.content_detail_source_icon);
        TextView sourceLabel = view.findViewById(R.id.content_detail_source_label);
        if (item.apiSource == Constants.SOURCE_CURSEFORGE) {
            sourceIcon.setImageResource(R.drawable.ic_curseforge);
            sourceLabel.setText(R.string.search_mod_engine_curseforge);
        } else {
            sourceIcon.setImageResource(R.drawable.ic_modrinth);
            sourceLabel.setText(R.string.search_mod_engine_modrinth);
        }

        ((TextView) view.findViewById(R.id.content_detail_kind_badge)).setText(kindLabel(item));
        bindCount(view, R.id.content_detail_downloads_group, R.id.content_detail_downloads_text, item.downloads);
        bindCount(view, R.id.content_detail_favorites_group, R.id.content_detail_favorites_text, item.favorites);

        float radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14,
                getResources().getDisplayMetrics());
        mLogo.setShapeAppearanceModel(mLogo.getShapeAppearanceModel().toBuilder()
                .setAllCornerSizes(radius).build());
        if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            track(RemoteImageLoader.load(item.imageUrl, 256, bitmap -> {
                if (bitmap != null && mLogo != null) mLogo.setImageBitmap(bitmap);
            }));
        }

        if (item.websiteUrl != null && !item.websiteUrl.isEmpty()) showOpenPage(item.websiteUrl);
    }

    private static void bindCount(View root, int groupId, int textId, long count) {
        View group = root.findViewById(groupId);
        group.setVisibility(count < 0 ? View.GONE : View.VISIBLE);
        if (count >= 0) ((TextView) root.findViewById(textId)).setText(CountFormatter.compact(count));
    }

    private int kindLabel(ModItem item) {
        if (item.isModpack) return R.string.content_kind_modpack;
        ContentType type = mSession.getContentType();
        if (type == ContentType.RESOURCE_PACK) return R.string.content_kind_resourcepack;
        if (type == ContentType.SHADER_PACK) return R.string.content_kind_shaderpack;
        return R.string.content_kind_mod;
    }

    private void showOpenPage(String url) {
        mOpenPageButton.setVisibility(View.VISIBLE);
        mOpenPageButton.setOnClickListener(v -> Tools.openURL(requireActivity(), url));
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────

    private void setupTabs(View view) {
        mTabs[TAB_DESCRIPTION] = view.findViewById(R.id.content_tab_description);
        mTabs[TAB_GALLERY] = view.findViewById(R.id.content_tab_gallery);
        mTabs[TAB_CHANGELOG] = view.findViewById(R.id.content_tab_changelog);
        mTabs[TAB_VERSIONS] = view.findViewById(R.id.content_tab_versions);
        for (int i = 0; i < mTabs.length; i++) {
            final int tab = i;
            mTabs[i].setOnClickListener(v -> selectTab(tab));
        }
        mTabs[TAB_VERSIONS].setVisibility(View.VISIBLE);
    }

    private void selectTab(int tab) {
        mTab = tab;
        int selectedText = MaterialColors.getColor(mTabs[0],
                com.google.android.material.R.attr.colorOnSecondaryContainer);
        int normalText = MaterialColors.getColor(mTabs[0],
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        for (int i = 0; i < mTabs.length; i++) {
            mTabs[i].setSelected(i == tab);
            mTabs[i].setTextColor(i == tab ? selectedText : normalText);
        }
        showSection(tab);
    }

    /** Shows one of the sections (or the versions) in the area below the tabs / in the right pane */
    private void showSection(int tab) {
        mTab = tab;
        mDescriptionSection.setVisibility(tab == TAB_DESCRIPTION ? View.VISIBLE : View.GONE);
        mGallerySection.setVisibility(tab == TAB_GALLERY ? View.VISIBLE : View.GONE);
        mChangelogSection.setVisibility(tab == TAB_CHANGELOG ? View.VISIBLE : View.GONE);

        if (mVersionsContainer != null) {
            boolean versions = tab == TAB_VERSIONS;
            mVersionsContainer.setVisibility(versions ? View.VISIBLE : View.GONE);
            mScroll.setVisibility(versions ? View.GONE : View.VISIBLE);
            if (versions && getChildFragmentManager().findFragmentById(R.id.content_detail_versions_container) == null) {
                getChildFragmentManager().beginTransaction()
                        .replace(R.id.content_detail_versions_container, ContentVersionsFragment.class, null,
                                ContentVersionsFragment.TAG)
                        .commit();
            }
        }

        if (tab == TAB_GALLERY) ensureGallery();
        if (tab == TAB_CHANGELOG) mSession.startChangelogs();
    }

    // ── Description and gallery ───────────────────────────────────────────────

    private void onInfo(ModItem item, @Nullable ContentSession.InfoState state) {
        if (state == null) return;
        if (mMode != MODE_RIGHT) updateHero(item, state);
        if (mMode != MODE_LEFT) updateSections(item, state);
    }

    /** The project page knows the counts and the address even when the search result didn't have them */
    private void updateHero(ModItem item, ContentSession.InfoState state) {
        ContentInfo info = state.info;
        View root = getView();
        if (root == null || state.status != ContentSession.Status.READY || info == null) return;
        if (item.downloads < 0 && info.downloads >= 0) {
            item.downloads = info.downloads;
            bindCount(root, R.id.content_detail_downloads_group, R.id.content_detail_downloads_text, item.downloads);
        }
        if (item.favorites < 0 && info.favorites >= 0) {
            item.favorites = info.favorites;
            bindCount(root, R.id.content_detail_favorites_group, R.id.content_detail_favorites_text, item.favorites);
        }
        if (info.websiteUrl != null && !info.websiteUrl.isEmpty()) showOpenPage(info.websiteUrl);
    }

    private void updateSections(ModItem item, ContentSession.InfoState state) {
        if (mDescriptionBody == null) return;
        boolean loading = state.status == ContentSession.Status.LOADING;
        mDescriptionProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        mGalleryProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) return;

        ContentInfo info = state.info;
        if (state.status == ContentSession.Status.ERROR || info == null) {
            // Only the summary of the search result is known
            mBodyToken++;
            mDescriptionNote.setText(R.string.content_description_error);
            mDescriptionNote.setVisibility(View.VISIBLE);
            showPlainDescription(item.description != null ? item.description : "");
            mGalleryEmpty.setVisibility(View.VISIBLE);
            return;
        }

        mDescriptionNote.setVisibility(View.GONE);
        mLoadedInfo = info;
        // The gallery is only built once its section is opened, it downloads pictures
        if (mTab == TAB_GALLERY) ensureGallery();
        else mGalleryEmpty.setVisibility(View.GONE);

        // Long descriptions take a while to turn into views, keep that off the UI thread
        mDescriptionProgress.setVisibility(View.VISIBLE);
        final ViewGroup target = mDescriptionBody;
        final int token = ++mBodyToken;
        final float density = getResources().getDisplayMetrics().density;
        final int width = bodyWidth(target);
        MojoApplication.sExecutorService.execute(() -> {
            ContentBody body = ContentBody.parse(info.body, info.bodyIsHtml, width, density);
            Tools.runOnUiThread(() -> {
                if (!isAdded() || mDescriptionBody != target || token != mBodyToken) {
                    body.cancel();
                    return;
                }
                mDescriptionProgress.setVisibility(View.GONE);
                if (mBody != null) mBody.cancel();
                mBody = body;
                if (body.isEmpty()) showPlainDescription(getString(R.string.content_description_empty));
                else body.bind(requireActivity(), target);
            });
        });
    }

    private int bodyWidth(View target) {
        if (target.getWidth() > 0) return target.getWidth();
        int screen = getResources().getDisplayMetrics().widthPixels;
        int padding = getResources().getDimensionPixelSize(
                mMode == MODE_RIGHT ? R.dimen.padding_large : R.dimen.padding_heavy) * 2;
        return Math.max(1, (mMode == MODE_RIGHT ? screen / 2 : screen) - padding);
    }

    private void showPlainDescription(CharSequence text) {
        if (mBody != null) mBody.cancel();
        mBody = null;
        mDescriptionBody.removeAllViews();
        TextView view = (TextView) LayoutInflater.from(requireContext())
                .inflate(R.layout.item_content_text, mDescriptionBody, false);
        view.setText(text);
        mDescriptionBody.addView(view);
    }

    private void ensureGallery() {
        if (mLoadedInfo == null || mGalleryBuilt || mGalleryList == null) return;
        mGalleryBuilt = true;
        buildGallery(mLoadedInfo);
    }

    private void buildGallery(ContentInfo info) {
        mGalleryList.removeAllViews();
        mGalleryEmpty.setVisibility(info.gallery.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        float radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12,
                getResources().getDisplayMetrics());
        for (ContentInfo.GalleryImage image : info.gallery) {
            View row = inflater.inflate(R.layout.item_content_gallery, mGalleryList, false);
            ShapeableImageView picture = row.findViewById(R.id.gallery_image);
            TextView caption = row.findViewById(R.id.gallery_caption);
            picture.setShapeAppearanceModel(picture.getShapeAppearanceModel().toBuilder()
                    .setAllCornerSizes(radius).build());
            if (image.title != null && !image.title.isEmpty()) {
                caption.setText(image.title);
                caption.setVisibility(View.VISIBLE);
            }
            picture.setOnClickListener(v -> showFullImage(image));
            mGalleryList.addView(row);
            track(RemoteImageLoader.load(image.url, 720, bitmap -> {
                if (bitmap != null) picture.setImageBitmap(bitmap);
            }));
        }
    }

    private void showFullImage(ContentInfo.GalleryImage image) {
        FrameLayout root = new FrameLayout(requireContext());
        root.setBackgroundColor(0xF2000000);

        ImageView picture = new ImageView(requireContext());
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(picture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ProgressBar progress = new ProgressBar(requireContext());
        root.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        if (image.title != null && !image.title.isEmpty()) {
            TextView caption = new TextView(requireContext());
            caption.setText(image.title);
            caption.setTextColor(0xFFFFFFFF);
            caption.setGravity(Gravity.CENTER);
            int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12,
                    getResources().getDisplayMetrics());
            caption.setPadding(pad, pad, pad, pad);
            caption.setBackgroundColor(0x80000000);
            root.addView(caption, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        }

        Dialog dialog = new Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(root);
        root.setOnClickListener(v -> dialog.dismiss());

        Future<?> load = RemoteImageLoader.load(image.url, 2048, (Bitmap bitmap) -> {
            progress.setVisibility(View.GONE);
            if (bitmap != null) picture.setImageBitmap(bitmap);
        });
        dialog.setOnDismissListener(d -> load.cancel(true));
        dialog.show();
    }

    // ── Changelog ─────────────────────────────────────────────────────────────

    private void onChangelog(@Nullable ContentSession.ChangelogState state) {
        if (mChangelogList == null || state == null) return;

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = mChangelogShown; i < state.entries.size(); i++) {
            ContentInfo.ChangelogEntry entry = state.entries.get(i);
            View card = inflater.inflate(R.layout.item_content_changelog, mChangelogList, false);
            ((TextView) card.findViewById(R.id.changelog_version)).setText(entry.versionName);

            String date = formatDate(entry.date);
            TextView dateView = card.findViewById(R.id.changelog_date);
            if (date != null) {
                dateView.setText(date);
                dateView.setVisibility(View.VISIBLE);
            }

            TextView text = card.findViewById(R.id.changelog_text);
            text.setMovementMethod(LinkMovementMethod.getInstance());
            CharSequence rendered = ContentTextRenderer.render(entry.text, entry.textIsHtml, text);
            if (rendered.length() == 0) text.setText(R.string.content_changelog_none);
            else text.setText(rendered);
            mChangelogList.addView(card);
        }
        mChangelogShown = state.entries.size();

        mChangelogProgress.setVisibility(state.loading ? View.VISIBLE : View.GONE);

        boolean nothingToShow = state.entries.isEmpty() && !state.loading && !state.hasMore && !state.error;
        if (state.error) {
            mChangelogStatus.setText(R.string.content_changelog_error);
            mChangelogStatus.setVisibility(View.VISIBLE);
            mChangelogMore.setText(R.string.content_changelog_retry);
            mChangelogMore.setVisibility(View.VISIBLE);
        } else if (nothingToShow) {
            mChangelogStatus.setText(R.string.content_changelog_empty);
            mChangelogStatus.setVisibility(View.VISIBLE);
            mChangelogMore.setVisibility(View.GONE);
        } else {
            mChangelogStatus.setVisibility(View.GONE);
            mChangelogMore.setText(R.string.content_changelog_more);
            mChangelogMore.setVisibility(state.hasMore && !state.loading && !state.entries.isEmpty()
                    ? View.VISIBLE : View.GONE);
        }
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

    private void track(Future<?> load) {
        mImageLoads.add(load);
    }
}
