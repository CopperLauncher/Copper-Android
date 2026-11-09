package git.artdeell.mojo.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.BackRow;

/** The loaders an instance can be created with, opened by "Create a loader instance" */
public class LoaderSelectFragment extends Fragment {
    public static final String TAG = "LoaderSelectFragment";

    public LoaderSelectFragment() {
        super(R.layout.fragment_loader_select);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        BackRow.bind(view);
        // NOTE: Special care needed! These are not added to the back stack, see the comment in
        // FabricInstallFragment.onDownloadFinished(), which returns with
        // ProfileTypeSelectFragment.returnToStart()
        view.findViewById(R.id.modded_profile_fabric).setOnClickListener((v)->
                Tools.swapFragment(requireActivity(), FabricInstallFragment.class, FabricInstallFragment.TAG, null));
        view.findViewById(R.id.modded_profile_forge).setOnClickListener((v)->
                Tools.swapFragment(requireActivity(), ForgeInstallFragment.class, ForgeInstallFragment.TAG, null));
        view.findViewById(R.id.modded_profile_quilt).setOnClickListener((v)->
                Tools.swapFragment(requireActivity(), QuiltInstallFragment.class, QuiltInstallFragment.TAG, null));
        view.findViewById(R.id.modded_profile_bta).setOnClickListener((v)->
                Tools.swapFragment(requireActivity(), BTAInstallFragment.class, BTAInstallFragment.TAG, null));
        view.findViewById(R.id.modded_profile_neoforge).setOnClickListener((v)->
                Tools.swapFragment(requireActivity(), NeoforgeInstallFragment.class, NeoforgeInstallFragment.TAG, null));
        view.findViewById(R.id.modded_profile_legacy_fabric).setOnClickListener((v) ->
                Tools.swapFragment(requireActivity(), LegacyFabricInstallFragment.class, LegacyFabricInstallFragment.TAG, null));
    }
}
