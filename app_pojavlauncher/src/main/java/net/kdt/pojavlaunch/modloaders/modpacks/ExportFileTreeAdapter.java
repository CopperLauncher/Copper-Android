package net.kdt.pojavlaunch.modloaders.modpacks;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Renders an expandable, checkable file/folder tree for the .mrpack export dialog, the same
 * way the official Modrinth app lets you drill into a folder and deselect individual files
 * instead of just toggling the whole folder.
 * <p/>
 * Selection state lives in a single {@code Map<String, Boolean>} (path relative to the
 * instance root -> explicit checked state) that is shared with {@code MrpackExporter}: a path
 * with no explicit entry simply inherits the state of its nearest ancestor that has one. This
 * means the export step doesn't need this adapter at all — it can recompute the same effective
 * state by walking the same map.
 */
public class ExportFileTreeAdapter extends RecyclerView.Adapter<ExportFileTreeAdapter.RowHolder> {

    private static class Node {
        final File file;
        final String relPath;
        final boolean isDirectory;
        final int depth;
        boolean expanded = false;
        List<Node> children;

        Node(File file, String relPath, int depth) {
            this.file = file;
            this.relPath = relPath;
            this.isDirectory = file.isDirectory();
            this.depth = depth;
        }
    }

    private final List<Node> mVisibleNodes = new ArrayList<>();
    private final Map<String, Boolean> mOverrides;
    private final int mIndentPx;

    public ExportFileTreeAdapter(File instanceDir, Map<String, Boolean> overrides, int indentPx) {
        mOverrides = overrides;
        mIndentPx = indentPx;
        mVisibleNodes.addAll(buildNodeList(instanceDir, "", 0));
    }

    private static List<Node> buildNodeList(File parent, String parentRelPath, int depth) {
        File[] files = parent.listFiles();
        List<Node> nodes = new ArrayList<>();
        if (files == null) return nodes;

        List<File> directories = new ArrayList<>();
        List<File> plainFiles = new ArrayList<>();
        for (File f : files) {
            if (f.isDirectory()) directories.add(f);
            else plainFiles.add(f);
        }
        Comparator<File> byNameIgnoreCase = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        Collections.sort(directories, byNameIgnoreCase);
        Collections.sort(plainFiles, byNameIgnoreCase);

        List<File> ordered = new ArrayList<>(directories.size() + plainFiles.size());
        ordered.addAll(directories);
        ordered.addAll(plainFiles);

        for (File f : ordered) {
            String relPath = parentRelPath.isEmpty() ? f.getName() : parentRelPath + "/" + f.getName();
            nodes.add(new Node(f, relPath, depth));
        }
        return nodes;
    }

    private boolean effectiveChecked(String relPath) {
        String path = relPath;
        while (true) {
            Boolean explicit = mOverrides.get(path);
            if (explicit != null) return explicit;
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash < 0) return false;
            path = path.substring(0, lastSlash);
        }
    }

    private void setCheckedCascading(Node node, boolean checked) {
        mOverrides.put(node.relPath, checked);
        // Drop any explicit overrides belonging to already-known descendants so they go back
        // to inheriting from this node, instead of conflicting with the new state.
        String prefix = node.relPath + "/";
        Iterator<String> keys = mOverrides.keySet().iterator();
        while (keys.hasNext()) {
            if (keys.next().startsWith(prefix)) keys.remove();
        }
        if (node.children != null) {
            for (Node child : node.children) setCheckedCascading(child, checked);
        }
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_export_tree_entry, parent, false);
        return new RowHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
        holder.bind(mVisibleNodes.get(position));
    }

    @Override
    public int getItemCount() {
        return mVisibleNodes.size();
    }

    private void toggleExpand(Node node) {
        int index = mVisibleNodes.indexOf(node);
        if (index < 0) return;

        if (node.expanded) {
            int removeCount = countVisibleDescendants(index, node.depth);
            for (int i = 0; i < removeCount; i++) mVisibleNodes.remove(index + 1);
            node.expanded = false;
            notifyItemChanged(index);
            if (removeCount > 0) notifyItemRangeRemoved(index + 1, removeCount);
        } else {
            if (node.children == null) node.children = buildNodeList(node.file, node.relPath, node.depth + 1);
            mVisibleNodes.addAll(index + 1, node.children);
            node.expanded = true;
            notifyItemChanged(index);
            if (!node.children.isEmpty()) notifyItemRangeInserted(index + 1, node.children.size());
        }
    }

    private int countVisibleDescendants(int index, int depth) {
        int count = 0;
        for (int i = index + 1; i < mVisibleNodes.size(); i++) {
            if (mVisibleNodes.get(i).depth <= depth) break;
            count++;
        }
        return count;
    }

    private void refreshVisibleDescendants(Node node) {
        int index = mVisibleNodes.indexOf(node);
        if (index < 0) return;
        int count = countVisibleDescendants(index, node.depth);
        if (count > 0) notifyItemRangeChanged(index + 1, count);
    }

    class RowHolder extends RecyclerView.ViewHolder {
        final View indentSpacer;
        final CheckBox checkBox;
        final TextView nameView;
        final ImageView chevron;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            indentSpacer = itemView.findViewById(R.id.export_entry_indent);
            checkBox = itemView.findViewById(R.id.export_entry_checkbox);
            nameView = itemView.findViewById(R.id.export_entry_name);
            chevron = itemView.findViewById(R.id.export_entry_chevron);
        }

        void bind(Node node) {
            ViewGroup.LayoutParams lp = indentSpacer.getLayoutParams();
            lp.width = node.depth * mIndentPx;
            indentSpacer.setLayoutParams(lp);

            nameView.setText(node.isDirectory ? node.file.getName() + "/" : node.file.getName());

            checkBox.setOnCheckedChangeListener(null);
            checkBox.setChecked(effectiveChecked(node.relPath));
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                setCheckedCascading(node, isChecked);
                refreshVisibleDescendants(node);
            });

            if (node.isDirectory) {
                chevron.setVisibility(View.VISIBLE);
                chevron.setRotation(node.expanded ? 0f : 180f);
                View.OnClickListener expandListener = v -> toggleExpand(node);
                chevron.setOnClickListener(expandListener);
                itemView.setOnClickListener(expandListener);
            } else {
                chevron.setVisibility(View.INVISIBLE);
                chevron.setOnClickListener(null);
                itemView.setOnClickListener(null);
            }
        }
    }
}