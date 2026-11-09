package git.artdeell.mojo.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.utils.BackRow;

import java.io.IOException;

public class ProfileTypeSelectFragment extends Fragment {
    public static final String TAG = "ProfileTypeSelectFragment";

    /** Back stack depth of the screen the instance creation was started from, -1 when unknown */
    private static int sReturnDepth = -1;
    public ProfileTypeSelectFragment() {
        super(R.layout.fragment_profile_type);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        BackRow.bind(view);
        // The entry that shows this screen is on the stack already, the screen below it is where
        // an install has to return to, however many loader screens are opened on top of this one
        sReturnDepth = getParentFragmentManager().getBackStackEntryCount() - 1;
        view.findViewById(R.id.vanilla_profile).setOnClickListener(v -> {
            try {
                Instance instance = Instances.createDefaultInstance();
                Instances.setSelectedInstance(instance);
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, true);
                Tools.swapFragment(requireActivity(), InstanceEditorFragment.class,
                        InstanceEditorFragment.TAG, new Bundle(1));
            }catch (IOException e) {
                Tools.showError(view.getContext(), e);
            }
        });

        // NOTE: Special care needed! If you wll decide to add these to the back stack, please read
        // the comment in FabricInstallFragment.onDownloadFinished() and amend the code
        // in FabricInstallFragment.onDownloadFinished() and ModVersionListFragment.onDownloadFinished()
        view.findViewById(R.id.optifine_profile).setOnClickListener(v -> Tools.swapFragment(requireActivity(), OptiFineInstallFragment.class,
                OptiFineInstallFragment.TAG, null));
        view.findViewById(R.id.modded_profile_loader).setOnClickListener((v)->
                Tools.swapFragment(requireActivity(), LoaderSelectFragment.class, LoaderSelectFragment.TAG, null));
        view.findViewById(R.id.modded_profile_modpack).setOnClickListener((v)->
                Tools.swapFragment(requireActivity(), SearchModFragment.class, SearchModFragment.TAG, null));
    }

    /**
     * Leaves the instance creation screens after an instance was installed and goes back to the
     * screen the creation was started from. Pops a single screen when that screen is unknown.
     */
    public static void returnToStart(@NonNull FragmentManager manager) {
        int depth = sReturnDepth;
        if (depth < 0 || manager.getBackStackEntryCount() <= depth) {
            manager.popBackStackImmediate();
            return;
        }
        while (manager.getBackStackEntryCount() > depth) {
            if (!manager.popBackStackImmediate()) break;
        }
    }
}
