package git.artdeell.mojo.multirt;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;

import androidx.activity.result.ActivityResultLauncher;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;
import git.artdeell.mojo.dialogs.PackageSelector;
import git.artdeell.mojo.dialogs.PackageAdapter;

public class MultiRTConfigDialog extends PackageSelector {
    public void show(){
        refresh();
        mDialog.show();
    }
    @SuppressLint("NotifyDataSetChanged") //only used to completely refresh the list, it is necessary
    public void refresh() {
        RecyclerView.Adapter<?> adapter = mDialogView.getAdapter();
        if(adapter != null) adapter.notifyDataSetChanged();
    }
    protected int getTitleRes() { return R.string.multirt_config_title; }
    protected int getPositiveButtonText() { return R.string.multirt_config_add; }
    protected int getNeutralButtonText() { return R.string.multirt_delete_runtime; }
    protected int getEditingButtonLabel() { return R.string.multirt_config_setdefault; }
    protected PackageAdapter<?> createAdapter(Context context, PackageSelector dialog) {
        RTRecyclerViewAdapter adapter = new RTRecyclerViewAdapter(dialog);
        // Needed to install the downloadable runtimes from the bundled assets
        if(context instanceof Activity) adapter.setActivity((Activity) context);
        return adapter;
    }
    protected void onPositive(Context context, ActivityResultLauncher<Object> launcher) { launcher.launch(null); }
}
