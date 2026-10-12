package git.artdeell.mojo.fragments;

import android.app.Dialog;
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

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.modding.modpacks.ContentSession;
import git.artdeell.mojo.modding.modpacks.api.ModpackApi;
import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.ContentInfo;
import git.artdeell.mojo.modding.modpacks.models.ContentType;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.utils.ContentTextRenderer;
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
 *   <li>Landscape: this is the left pane (logo, name, description, gallery, changelog and a back
 *   button) and {@link ContentVersionsFragment} takes the right pane, see
 *   {@link MainMenuFragment#openContentDetail()}.</li>
 *   <li>Portrait: it is a full screen with the same tabs plus a Versions tab.</li>
 * </ul>
 * The content itself lives in {@link ContentSession}.
 */
public class ContentDetailFragment extends Fragment {
    public static final String TAG = "ContentDetailFragment";

    private static final String STATE_TAB = "tab";
    private static final int TAB_DESCRIPTION = 0;
    private static final int TAB_GALLERY = 1;
    private static final int TAB_CHANGELOG = 2;
    private static final int TAB_VERSIONS = 3;

    private ContentSession mSession;
    private boolean mInPane;
    private int mTab = TAB_DESCRIPTION;

    private View mScroll;
    private View mVersionsContainer;
    private final TextView[] mTabs = new TextView[4];

    private ShapeableImageView mLogo;
    private View mOpenPageButton;

    private View mDescriptionSection, mGallerySection, mChangelogSection;
    private View mDescriptionProgress;
    private TextView mDescriptionNote, mDescriptionBody;
    private View mGalleryProgress;
    private TextView mGalleryEmpty;
    private ViewGroup mGalleryList;
    private ViewGroup mChangelogList;
    private View mChangelogProgress;
    private TextView mChangelogStatus;
    private TextView mChangelogMore;
    private int mChangelogShown;

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Fragment parent = getParentFragment();
        mInPane = parent instanceof MainMenuFragment && ((MainMenuFragment) parent).isTwoPane();
        return inflater.inflate(mInPane ? R.layout.fragment_content_detail_pane
                : R.layout.fragment_content_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.findViewById(R.id.content_detail_back).setOnClickListener(v -> goBack());

        mSession = new ViewModelProvider(requireActivity()).get(ContentSession.class);
        ModItem item = mSession.getItem();
        if (item == null) {
            // The process was recreated while this screen was open, there is nothing to show anymore
            Toast.makeText(requireContext(), R.string.content_session_lost, Toast.LENGTH_SHORT).show();
            view.post(this::goBack);
            return;
        }

        mScroll = view.findViewById(R.id.content_detail_scroll);
        mVersionsContainer = view.findViewById(R.id.content_detail_versions_container);
        mLogo = view.findViewById(R.id.content_detail_logo);
        mOpenPageButton = view.findViewById(R.id.content_detail_open_page);

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

        mDescriptionBody.setMovementMethod(LinkMovementMethod.getInstance());
        mChangelogMore.setOnClickListener(v -> mSession.loadMoreChangelogs());

        bindHero(view, item);
        setupTabs(view);
        if (savedInstanceState != null) mTab = savedInstanceState.getInt(STATE_TAB, TAB_DESCRIPTION);
        if (mTab == TAB_VERSIONS && mInPane) mTab = TAB_DESCRIPTION;
        selectTab(mTab);

        mSession.getInfo().observe(getViewLifecycleOwner(), state -> onInfo(item, state));
        mSession.getChangelog().observe(getViewLifecycleOwner(), this::onChangelog);
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
        // In landscape the versions have a pane of their own
        mTabs[TAB_VERSIONS].setVisibility(mInPane ? View.GONE : View.VISIBLE);
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

        if (tab == TAB_CHANGELOG) mSession.startChangelogs();
    }

    // ── Description and gallery ───────────────────────────────────────────────

    private void onInfo(ModItem item, @Nullable ContentSession.InfoState state) {
        if (mDescriptionBody == null || state == null) return;
        boolean loading = state.status == ContentSession.Status.LOADING;
        mDescriptionProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        mGalleryProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) return;

        ContentInfo info = state.info;
        if (state.status == ContentSession.Status.ERROR || info == null) {
            // Only the summary of the search result is known
            mDescriptionNote.setText(R.string.content_description_error);
            mDescriptionNote.setVisibility(View.VISIBLE);
            mDescriptionBody.setText(item.description != null ? item.description : "");
            mGalleryEmpty.setVisibility(View.VISIBLE);
            return;
        }

        mDescriptionNote.setVisibility(View.GONE);
        CharSequence body = ContentTextRenderer.render(info.body, info.bodyIsHtml);
        if (body.length() == 0) {
            mDescriptionBody.setText(R.string.content_description_empty);
        } else {
            mDescriptionBody.setText(body);
        }

        if (info.websiteUrl != null && !info.websiteUrl.isEmpty()) showOpenPage(info.websiteUrl);
        buildGallery(info);
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
            CharSequence rendered = ContentTextRenderer.render(entry.text, entry.textIsHtml);
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
