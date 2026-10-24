package git.artdeell.mojo.skins;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.JSONUtils;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Local skin storage. Every skin is a PNG file in {@code <data>/skins}, the metadata
 * (name, slim flag, which skin each account is using) is kept in {@code index.json}.
 * There is no limit on how many skins can be stored.
 */
public final class SkinLibrary {
    private static final String INDEX_NAME = "index.json";

    private SkinLibrary() {}

    @Keep
    public static final class Entry {
        public String id;
        public String name;
        public boolean slim;
        public long added;
    }

    @Keep
    private static final class Index {
        List<Entry> skins = new ArrayList<>();
        /** profile UUID -> id of the skin that is applied to that profile */
        Map<String, String> selected = new HashMap<>();
    }

    private static File dir() {
        return new File(Tools.DIR_DATA, "skins");
    }

    private static File indexFile() {
        return new File(dir(), INDEX_NAME);
    }

    @NonNull
    private static Index readIndex() {
        File file = indexFile();
        if (!file.exists()) return new Index();
        try {
            Index index = JSONUtils.readFromFile(file, Index.class);
            if (index == null) return new Index();
            if (index.skins == null) index.skins = new ArrayList<>();
            if (index.selected == null) index.selected = new HashMap<>();
            return index;
        } catch (Exception e) {
            return new Index();
        }
    }

    private static void writeIndex(Index index) throws IOException {
        FileUtils.ensureDirectory(dir());
        JSONUtils.writeToFile(indexFile(), index);
    }

    public static File fileOf(@NonNull Entry entry) {
        return new File(dir(), entry.id + ".png");
    }

    @NonNull
    public static synchronized List<Entry> list() {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : readIndex().skins) {
            if (entry != null && entry.id != null && fileOf(entry).exists()) result.add(entry);
        }
        return result;
    }

    @Nullable
    public static synchronized Entry find(@Nullable String id) {
        if (id == null) return null;
        for (Entry entry : list()) if (id.equals(entry.id)) return entry;
        return null;
    }

    @NonNull
    public static synchronized Entry add(@NonNull byte[] png, @NonNull String name, boolean slim) throws IOException {
        FileUtils.ensureDirectory(dir());
        Entry entry = new Entry();
        entry.id = UUID.randomUUID().toString();
        entry.name = name;
        entry.slim = slim;
        entry.added = System.currentTimeMillis();
        try (FileOutputStream out = new FileOutputStream(fileOf(entry))) {
            out.write(png);
        }
        Index index = readIndex();
        index.skins.add(entry);
        writeIndex(index);
        return entry;
    }

    public static synchronized void delete(@NonNull String id) throws IOException {
        Index index = readIndex();
        Entry target = null;
        for (Entry entry : index.skins) if (id.equals(entry.id)) target = entry;
        if (target != null) {
            index.skins.remove(target);
            //noinspection ResultOfMethodCallIgnored
            fileOf(target).delete();
        }
        Iterator<String> selected = index.selected.values().iterator();
        while (selected.hasNext()) {
            if (id.equals(selected.next())) selected.remove();
        }
        writeIndex(index);
    }

    public static synchronized void setSlim(@NonNull String id, boolean slim) throws IOException {
        Index index = readIndex();
        for (Entry entry : index.skins) if (id.equals(entry.id)) entry.slim = slim;
        writeIndex(index);
    }

    @Nullable
    public static synchronized String getSelected(@NonNull String profileId) {
        return readIndex().selected.get(profileId);
    }

    public static synchronized void setSelected(@NonNull String profileId, @Nullable String id) throws IOException {
        Index index = readIndex();
        if (id == null) index.selected.remove(profileId);
        else index.selected.put(profileId, id);
        writeIndex(index);
    }

    @NonNull
    public static byte[] readBytes(@NonNull Entry entry) throws IOException {
        try (InputStream in = new FileInputStream(fileOf(entry))) {
            return IOUtils.toByteArray(in);
        }
    }
}
