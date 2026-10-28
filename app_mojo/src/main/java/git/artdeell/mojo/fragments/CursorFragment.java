package git.artdeell.mojo.fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import git.artdeell.mojo.R;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.cursor.AnimatedCursorDrawable;
import git.artdeell.mojo.utils.cursor.CursorParser;
import git.artdeell.mojo.utils.cursor.CursorStyle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Stack;

public class CursorFragment extends Fragment {
    public static final String TAG = "CursorFragment";

    private View mCardStandard;
    private ImageView mPreviewStandard;

    // Custom Card
    private View mCardCustom;
    private ImageView mPreviewCustom;
    private com.kdt.mcgui.MineButton mBtnCustomUpload, mBtnCustomEditCurrent, mBtnCustomReset;

    // Test zone elements
    private SeekBar mSeekBarSize;
    private TextView mTxtSizeVal;
    private FrameLayout mCursorTestPad;
    private ImageView mTestPointer;

    // Editor elements
    private View mPanelEditor;
    private ImageView mEditorCheckboard;
    private FrameLayout mEditorCheckboardContainer;

    private com.kdt.mcgui.MineButton mBtnFlood, mBtnManualEraser, mBtnAutoCut;
    private TextView mTxtSliderTitle, mTxtSliderValue;
    private SeekBar mSeekBrushOrThreshold;
    private com.kdt.mcgui.MineButton mBtnUndo;

    private EditorState mState;
    private static final int MODE_NONE = 0;
    private static final int MODE_MANUAL_ERASER = 1;
    private static final int MODE_FLOOD_ERASE = 2;
    private int mCurrentEditorMode = MODE_FLOOD_ERASE;

    private int mEraserRadius = 15;
    private int mThreshold = 45;

    /** Keeps the bitmap being edited and its undo history alive across activity recreation */
    public static class EditorState extends ViewModel {
        Bitmap bitmap;
        final Stack<Bitmap> undo = new Stack<>();

        @Override
        protected void onCleared() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            while (!undo.isEmpty()) {
                Bitmap b = undo.pop();
                if (b != null && !b.isRecycled()) b.recycle();
            }
            bitmap = null;
        }
    }

    private final ActivityResultLauncher<String> mPickCustomCursor = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            this::onCustomCursorPicked
    );

    public CursorFragment() {
        super(R.layout.fragment_cursor);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mState = new ViewModelProvider(this).get(EditorState.class);

        view.findViewById(R.id.cursor_back_row).setOnClickListener(v ->
                ExtraCore.setValue(ExtraConstants.BACK_PREFERENCE, "true"));

        // Grid cards
        mCardStandard = view.findViewById(R.id.card_cursor_standard);
        mCardCustom = view.findViewById(R.id.card_cursor_custom);

        // Previews
        mPreviewStandard = view.findViewById(R.id.preview_standard);
        mPreviewCustom = view.findViewById(R.id.preview_custom);

        // Buttons
        mBtnCustomUpload = view.findViewById(R.id.btn_custom_upload);
        mBtnCustomEditCurrent = view.findViewById(R.id.btn_custom_edit_current);
        mBtnCustomReset = view.findViewById(R.id.btn_custom_reset);

        // Test zone
        mSeekBarSize = view.findViewById(R.id.seek_cursor_size);
        mTxtSizeVal = view.findViewById(R.id.txt_cursor_size_val);
        mCursorTestPad = view.findViewById(R.id.cursor_test_pad);
        mTestPointer = view.findViewById(R.id.test_pointer);

        // Editor Panel
        mPanelEditor = view.findViewById(R.id.panel_cursor_editor);
        mEditorCheckboard = view.findViewById(R.id.editor_checkboard);
        mEditorCheckboardContainer = view.findViewById(R.id.editor_checkboard_container);

        mBtnFlood = view.findViewById(R.id.btn_editor_flood);
        mBtnManualEraser = view.findViewById(R.id.btn_editor_manual_eraser);
        mBtnAutoCut = view.findViewById(R.id.btn_editor_autocut);

        mTxtSliderTitle = view.findViewById(R.id.txt_slider_title);
        mTxtSliderValue = view.findViewById(R.id.txt_slider_value);
        mSeekBrushOrThreshold = view.findViewById(R.id.seek_brush_or_threshold);
        mBtnUndo = view.findViewById(R.id.btn_editor_undo);

        // Setup Photoshop Checkered Background
        setCheckboardBackground(mEditorCheckboardContainer);

        mPreviewStandard.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.ic_mouse_pointer, requireContext().getTheme()));
        updateCustomPreview();

        mCardStandard.setOnClickListener(v -> selectStandard());

        // Custom Clicks
        mCardCustom.setOnClickListener(v -> selectCustomCursor());
        mBtnCustomReset.setOnClickListener(v -> resetCustomCursor());
        mBtnCustomUpload.setOnClickListener(v -> mPickCustomCursor.launch("*/*"));
        mBtnCustomEditCurrent.setOnClickListener(v -> openEditorForCurrentCustom());

        // Size slider logic
        int currentSizePercent = (int) (LauncherPreferences.PREF_MOUSESCALE * 100);
        mSeekBarSize.setProgress(Math.max(0, currentSizePercent - 20));
        mTxtSizeVal.setText(currentSizePercent + "%");

        mSeekBarSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int scalePercent = progress + 20;
                mTxtSizeVal.setText(scalePercent + "%");
                float scaleVal = scalePercent / 100f;
                LauncherPreferences.PREF_MOUSESCALE = scaleVal;
                if (LauncherPreferences.DEFAULT_PREF != null) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putInt("mousescale", scalePercent)
                            .apply();
                }
                updateTestPointer();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Test pad touchscreen drags
        mCursorTestPad.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            updateParentIntercept(v, action);
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                mTestPointer.setVisibility(View.VISIBLE);
                float x = event.getX() - (mTestPointer.getWidth() / 2f);
                float y = event.getY() - (mTestPointer.getHeight() / 2f);
                x = Math.max(0, Math.min(mCursorTestPad.getWidth() - mTestPointer.getWidth(), x));
                y = Math.max(0, Math.min(mCursorTestPad.getHeight() - mTestPointer.getHeight(), y));
                mTestPointer.setX(x);
                mTestPointer.setY(y);
                return true;
            }
            return false;
        });

        // Editor Painting / Flood-Fill Erasing Touch Interception
        mEditorCheckboard.setOnTouchListener((v, event) -> {
            updateParentIntercept(v, event.getAction());
            if (mState.bitmap == null || mCurrentEditorMode == MODE_NONE) return false;
            int action = event.getAction();

            float touchX = event.getX();
            float touchY = event.getY();

            float intrinsicWidth = mEditorCheckboard.getDrawable().getIntrinsicWidth();
            float intrinsicHeight = mEditorCheckboard.getDrawable().getIntrinsicHeight();
            float viewWidth = mEditorCheckboard.getWidth();
            float viewHeight = mEditorCheckboard.getHeight();

            float scale = Math.min(viewWidth / intrinsicWidth, viewHeight / intrinsicHeight);
            float dx = (viewWidth - intrinsicWidth * scale) / 2f;
            float dy = (viewHeight - intrinsicHeight * scale) / 2f;

            float bitmapX = (touchX - dx) / scale;
            float bitmapY = (touchY - dy) / scale;

            if (mCurrentEditorMode == MODE_MANUAL_ERASER) {
                if (action == MotionEvent.ACTION_DOWN) {
                    saveToHistory();
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    boolean changed = false;
                    for (int x = (int) (bitmapX - mEraserRadius); x <= (int) (bitmapX + mEraserRadius); x++) {
                        for (int y = (int) (bitmapY - mEraserRadius); y <= (int) (bitmapY + mEraserRadius); y++) {
                            if (x >= 0 && x < mState.bitmap.getWidth() && y >= 0 && y < mState.bitmap.getHeight()) {
                                double dist = Math.sqrt((x - bitmapX) * (x - bitmapX) + (y - bitmapY) * (y - bitmapY));
                                if (dist <= mEraserRadius) {
                                    if (mState.bitmap.getPixel(x, y) != Color.TRANSPARENT) {
                                        mState.bitmap.setPixel(x, y, Color.TRANSPARENT);
                                        changed = true;
                                    }
                                }
                            }
                        }
                    }
                    if (changed) {
                        mEditorCheckboard.setImageBitmap(mState.bitmap);
                        mEditorCheckboard.invalidate();
                    }
                    return true;
                }
            } else if (mCurrentEditorMode == MODE_FLOOD_ERASE) {
                if (action == MotionEvent.ACTION_DOWN) {
                    int bx = (int) bitmapX;
                    int by = (int) bitmapY;
                    if (bx >= 0 && bx < mState.bitmap.getWidth() && by >= 0 && by < mState.bitmap.getHeight()) {
                        saveToHistory();
                        performFloodErase(bx, by, mThreshold);
                    }
                    return true;
                }
            }
            return false;
        });

        // Tool Mode Selection Clickers
        mBtnFlood.setOnClickListener(v -> selectToolMode(MODE_FLOOD_ERASE));
        mBtnManualEraser.setOnClickListener(v -> selectToolMode(MODE_MANUAL_ERASER));
        mBtnAutoCut.setOnClickListener(v -> {
            saveToHistory();
            performAutoCutBackground();
        });

        // Adaptive slider listener
        mSeekBrushOrThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mCurrentEditorMode == MODE_FLOOD_ERASE) {
                    mThreshold = Math.max(1, progress);
                    mTxtSliderValue.setText(String.valueOf(mThreshold));
                } else if (mCurrentEditorMode == MODE_MANUAL_ERASER) {
                    mEraserRadius = Math.max(1, progress);
                    mTxtSliderValue.setText(mEraserRadius + " px");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // History Actions
        mBtnUndo.setOnClickListener(v -> performUndo());

        // Cancel / Save
        view.findViewById(R.id.btn_editor_cancel).setOnClickListener(v -> closeEditorPanel(false));
        view.findViewById(R.id.btn_editor_save).setOnClickListener(v -> closeEditorPanel(true));

        highlightActiveCard();
        updateTestPointer();

        if (mState.bitmap != null) {
            mEditorCheckboard.setImageBitmap(mState.bitmap);
            mPanelEditor.setVisibility(View.VISIBLE);
            selectToolMode(mCurrentEditorMode == MODE_NONE ? MODE_FLOOD_ERASE : mCurrentEditorMode);
            updateUndoButtonState();
        }
    }

    private static void updateParentIntercept(View v, int action) {
        if (v.getParent() == null) return;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            v.getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            v.getParent().requestDisallowInterceptTouchEvent(false);
        }
    }

    private boolean hasCustomCursor() {
        String path = LauncherPreferences.DEFAULT_PREF.getString(CursorStyle.PREF_PATH, null);
        return path != null && new File(path).isFile();
    }

    private Drawable loadCustomDrawable(Context context) {
        String path = LauncherPreferences.DEFAULT_PREF.getString(CursorStyle.PREF_PATH, null);
        if (path == null || !new File(path).isFile()) return null;
        if (path.endsWith(".ani")) return new AnimatedCursorDrawable(context, path, false);
        return Drawable.createFromPath(path);
    }

    private void updateCustomPreview() {
        Context context = getContext();
        if (context == null) return;
        Drawable d = loadCustomDrawable(context);
        if (d == null) d = ResourcesCompat.getDrawable(getResources(), R.drawable.ic_mouse_pointer, context.getTheme());
        mPreviewCustom.setImageDrawable(d);
    }

    private void updateTestPointer() {
        Context context = getContext();
        if (context == null || mTestPointer == null) return;

        String key = LauncherPreferences.DEFAULT_PREF.getString(CursorStyle.PREF_STYLE_KEY, CursorStyle.KEY_STANDARD);
        Drawable d = null;
        if (!CursorStyle.KEY_STANDARD.equals(key)) d = loadCustomDrawable(context);
        if (d == null) d = ResourcesCompat.getDrawable(getResources(), R.drawable.ic_mouse_pointer, context.getTheme());
        mTestPointer.setImageDrawable(d);

        float scale = LauncherPreferences.PREF_MOUSESCALE;
        int intrinsicWidth = d.getIntrinsicWidth();
        int intrinsicHeight = d.getIntrinsicHeight();
        int width = (int) (36 * scale);
        int height = (intrinsicWidth <= 0 || intrinsicHeight <= 0)
                ? (int) (54 * scale)
                : (int) (36 * ((float) intrinsicHeight / intrinsicWidth) * scale);

        android.view.ViewGroup.LayoutParams params = mTestPointer.getLayoutParams();
        params.width = width;
        params.height = height;
        mTestPointer.setLayoutParams(params);
    }

    private void selectStandard() {
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(CursorStyle.PREF_STYLE_KEY, CursorStyle.KEY_STANDARD)
                .apply();
        Context context = getContext();
        if (context != null) Toast.makeText(context, getString(R.string.cursor_store_selected_standard), Toast.LENGTH_SHORT).show();
        highlightActiveCard();
        updateTestPointer();
    }

    private void selectCustomCursor() {
        Context context = getContext();
        if (context == null) return;
        if (!hasCustomCursor()) {
            Toast.makeText(context, getString(R.string.cursor_no_custom), Toast.LENGTH_SHORT).show();
            return;
        }
        String path = LauncherPreferences.DEFAULT_PREF.getString(CursorStyle.PREF_PATH, null);
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(CursorStyle.PREF_STYLE_KEY, path.endsWith(".ani") ? CursorStyle.KEY_CUSTOM_ANI : CursorStyle.KEY_CUSTOM)
                .apply();
        Toast.makeText(context, getString(R.string.cursor_store_success), Toast.LENGTH_SHORT).show();
        highlightActiveCard();
        updateTestPointer();
    }

    /** Goes back to the default pointer and deletes the uploaded cursor files */
    private void resetCustomCursor() {
        Context context = getContext();
        if (context == null) return;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(CursorStyle.PREF_STYLE_KEY, CursorStyle.KEY_STANDARD)
                .remove(CursorStyle.PREF_PATH)
                .apply();
        new File(context.getFilesDir(), "custom_cursor_user.png").delete();
        new File(context.getFilesDir(), "custom_cursor_user.ani").delete();
        Toast.makeText(context, getString(R.string.cursor_reset_done), Toast.LENGTH_SHORT).show();
        updateCustomPreview();
        highlightActiveCard();
        updateTestPointer();
    }

    private void highlightActiveCard() {
        String key = LauncherPreferences.DEFAULT_PREF.getString(CursorStyle.PREF_STYLE_KEY, CursorStyle.KEY_STANDARD);
        boolean standard = CursorStyle.KEY_STANDARD.equals(key);
        mCardStandard.setAlpha(standard ? 1.0f : 0.6f);
        mCardCustom.setAlpha(standard ? 0.6f : 1.0f);
    }








    private void onCustomCursorPicked(Uri uri) {
        if (uri == null) return;
        Context context = getContext();
        if (context == null) return;

        try {
            byte[] fileBytes;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("Failed to open stream");
                fileBytes = CursorParser.readAllBytes(in);
            }

            if (fileBytes == null || fileBytes.length < 4) {
                throw new Exception(getString(R.string.cursor_store_file_empty));
            }

            boolean isAnimated = false;
            Bitmap decodedBitmap = null;

            if (fileBytes.length >= 12 &&
                fileBytes[0] == 'R' && fileBytes[1] == 'I' && fileBytes[2] == 'F' && fileBytes[3] == 'F' &&
                fileBytes[8] == 'A' && fileBytes[9] == 'C' && fileBytes[10] == 'O' && fileBytes[11] == 'N') {
                isAnimated = true;
                List<CursorParser.AniFrame> frames = CursorParser.decodeAniAllFrames(fileBytes);
                if (frames != null && !frames.isEmpty()) {
                    decodedBitmap = frames.get(0).bitmap;
                }
            } else {
                try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(fileBytes)) {
                    decodedBitmap = CursorParser.decodeCursor(bais);
                }
            }

            if (decodedBitmap == null) {
                Toast.makeText(context, getString(R.string.cursor_store_invalid_format), Toast.LENGTH_LONG).show();
                return;
            }

            if (isAnimated) {
                File destFile = new File(context.getFilesDir(), "custom_cursor_user.ani");
                try (FileOutputStream fos = new FileOutputStream(destFile)) {
                    fos.write(fileBytes);
                }
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString("custom_cursor_path", destFile.getAbsolutePath())
                        .putString("custom_cursor_style_key", "custom_ani")
                        .apply();
            } else {
                File destFile = new File(context.getFilesDir(), "custom_cursor_user.png");
                try (FileOutputStream fos = new FileOutputStream(destFile)) {
                    decodedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString("custom_cursor_path", destFile.getAbsolutePath())
                        .putString("custom_cursor_style_key", "custom")
                        .apply();
            }

            mState.bitmap = decodedBitmap.copy(Bitmap.Config.ARGB_8888, true);
            mEditorCheckboard.setImageBitmap(mState.bitmap);
            mPanelEditor.setVisibility(View.VISIBLE);


            mState.undo.clear();
            updateUndoButtonState();
            selectToolMode(MODE_FLOOD_ERASE);

            Toast.makeText(context, getString(R.string.cursor_store_file_loaded), Toast.LENGTH_SHORT).show();
            highlightActiveCard();
            updateCustomPreview();
            updateTestPointer();
        } catch (Exception e) {
            Toast.makeText(context, getString(R.string.cursor_store_load_error_prefix) + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openEditorForCurrentCustom() {
        Context context = getContext();
        if (context == null) return;

        Bitmap activeBmp = null;
        String path = LauncherPreferences.DEFAULT_PREF.getString("custom_cursor_path", null);
        String selectedKey = LauncherPreferences.DEFAULT_PREF.getString("custom_cursor_style_key", "standard");

        if ("standard".equals(selectedKey)) {
            Drawable d = ResourcesCompat.getDrawable(getResources(), R.drawable.ic_mouse_pointer, context.getTheme());
            activeBmp = drawableToBitmap(d);
        } else if ("custom_ani".equals(selectedKey) && path != null && new File(path).exists()) {
            try {
                byte[] bytes = CursorParser.readAllBytes(new java.io.FileInputStream(new File(path)));
                List<CursorParser.AniFrame> frames = CursorParser.decodeAniAllFrames(bytes);
                if (frames != null && !frames.isEmpty()) {
                    activeBmp = frames.get(0).bitmap;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (path != null && new File(path).exists()) {
            activeBmp = BitmapFactory.decodeFile(path);
        }

        if (activeBmp == null) {
            Drawable d = ResourcesCompat.getDrawable(getResources(), R.drawable.ic_mouse_pointer, context.getTheme());
            activeBmp = drawableToBitmap(d);
        }

        try {
            mState.bitmap = activeBmp.copy(Bitmap.Config.ARGB_8888, true);
            mEditorCheckboard.setImageBitmap(mState.bitmap);
            mPanelEditor.setVisibility(View.VISIBLE);


            mState.undo.clear();
            updateUndoButtonState();
            selectToolMode(MODE_FLOOD_ERASE);

            Toast.makeText(context, getString(R.string.cursor_store_loaded_into_editor), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, getString(R.string.cursor_store_open_error_prefix) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap drawableToBitmap(Drawable d) {
        if (d instanceof BitmapDrawable) {
            return ((BitmapDrawable) d).getBitmap();
        }
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        d.setBounds(0, 0, 64, 64);
        d.draw(canvas);
        return bitmap;
    }

    private void selectToolMode(int mode) {
        mCurrentEditorMode = mode;
        highlightActiveToolButton();

        if (mode == MODE_FLOOD_ERASE) {
            mTxtSliderTitle.setText(getString(R.string.cursor_editor_flood_threshold_title));
            mTxtSliderValue.setText(String.valueOf(mThreshold));
            mSeekBrushOrThreshold.setMax(150);
            mSeekBrushOrThreshold.setProgress(mThreshold);
        } else if (mode == MODE_MANUAL_ERASER) {
            mTxtSliderTitle.setText(getString(R.string.cursor_editor_eraser_size_title));
            mTxtSliderValue.setText(mEraserRadius + " px");
            mSeekBrushOrThreshold.setMax(100);
            mSeekBrushOrThreshold.setProgress(mEraserRadius);
        }
    }

    private void highlightActiveToolButton() {
        mBtnFlood.setAlpha(0.6f);
        mBtnManualEraser.setAlpha(0.6f);

        if (mCurrentEditorMode == MODE_FLOOD_ERASE) {
            mBtnFlood.setAlpha(1.0f);
        } else if (mCurrentEditorMode == MODE_MANUAL_ERASER) {
            mBtnManualEraser.setAlpha(1.0f);
        }
    }

    private void performAutoCutBackground() {
        if (mState.bitmap == null) return;

        int width = mState.bitmap.getWidth();
        int height = mState.bitmap.getHeight();
        if (width <= 0 || height <= 0) return;

        int keyColor = mState.bitmap.getPixel(0, 0);
        int kr = Color.red(keyColor);
        int kg = Color.green(keyColor);
        int kb = Color.blue(keyColor);
        int ka = Color.alpha(keyColor);

        // If corners are already transparent, we should find first solid pixel or default to white
        if (ka == 0) {
            kr = 255;
            kg = 255;
            kb = 255;
        }

        int cutCount = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int px = mState.bitmap.getPixel(x, y);
                int pr = Color.red(px);
                int pg = Color.green(px);
                int pb = Color.blue(px);
                int pa = Color.alpha(px);

                if (pa > 0) {
                    double dist = Math.sqrt((pr - kr) * (pr - kr) + (pg - kg) * (pg - kg) + (pb - kb) * (pb - kb));
                    if (dist <= mThreshold) {
                        mState.bitmap.setPixel(x, y, Color.TRANSPARENT);
                        cutCount++;
                    }
                }
            }
        }

        mEditorCheckboard.setImageBitmap(mState.bitmap);
        mEditorCheckboard.invalidate();
        Toast.makeText(getContext(), getString(R.string.cursor_editor_pixels_cut, cutCount), Toast.LENGTH_SHORT).show();
    }

    private void performFloodErase(int startX, int startY, double threshold) {
        if (mState.bitmap == null) return;
        int width = mState.bitmap.getWidth();
        int height = mState.bitmap.getHeight();
        if (startX < 0 || startX >= width || startY < 0 || startY >= height) return;

        int targetColor = mState.bitmap.getPixel(startX, startY);
        if (Color.alpha(targetColor) == 0) return;

        int tr = Color.red(targetColor);
        int tg = Color.green(targetColor);
        int tb = Color.blue(targetColor);

        boolean[][] visited = new boolean[width][height];
        java.util.Queue<android.graphics.Point> queue = new java.util.LinkedList<>();
        queue.add(new android.graphics.Point(startX, startY));
        visited[startX][startY] = true;

        int filledCount = 0;
        while (!queue.isEmpty()) {
            android.graphics.Point p = queue.poll();
            int x = p.x;
            int y = p.y;

            mState.bitmap.setPixel(x, y, Color.TRANSPARENT);
            filledCount++;

            int[] dx = {0, 0, 1, -1};
            int[] dy = {1, -1, 0, 0};
            for (int i = 0; i < 4; i++) {
                int nx = x + dx[i];
                int ny = y + dy[i];

                if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                    if (!visited[nx][ny]) {
                        int px = mState.bitmap.getPixel(nx, ny);
                        int pr = Color.red(px);
                        int pg = Color.green(px);
                        int pb = Color.blue(px);
                        int pa = Color.alpha(px);

                        if (pa > 0) {
                            double dist = Math.sqrt((pr - tr)*(pr - tr) + (pg - tg)*(pg - tg) + (pb - tb)*(pb - tb));
                            if (dist <= threshold) {
                                visited[nx][ny] = true;
                                queue.add(new android.graphics.Point(nx, ny));
                            }
                        }
                    }
                }
            }
        }

        mEditorCheckboard.setImageBitmap(mState.bitmap);
        mEditorCheckboard.invalidate();
        Toast.makeText(getContext(), getString(R.string.cursor_store_flood_erased, filledCount), Toast.LENGTH_SHORT).show();
    }

    private void saveToHistory() {
        if (mState.bitmap != null) {
            if (mState.undo.size() >= 10) {
                Bitmap oldest = mState.undo.remove(0);
                if (oldest != null && !oldest.isRecycled()) {
                    oldest.recycle();
                }
            }
            mState.undo.push(mState.bitmap.copy(mState.bitmap.getConfig(), true));
            updateUndoButtonState();
        }
    }

    private void performUndo() {
        if (!mState.undo.isEmpty()) {
            if (mState.bitmap != null && !mState.bitmap.isRecycled()) {
                mState.bitmap.recycle();
            }
            mState.bitmap = mState.undo.pop();
            mEditorCheckboard.setImageBitmap(mState.bitmap);
            mEditorCheckboard.invalidate();
            updateUndoButtonState();
            Toast.makeText(getContext(), getString(R.string.cursor_store_action_undone), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUndoButtonState() {
        mBtnUndo.setEnabled(!mState.undo.isEmpty());
        mBtnUndo.setAlpha(mState.undo.isEmpty() ? 0.5f : 1.0f);
    }

    private void closeEditorPanel(boolean saveChanges) {
        Context context = getContext();
        if (context == null) return;

        if (saveChanges && mState.bitmap != null) {
            File cursorFile = new File(context.getFilesDir(), "custom_cursor_user.png");
            try {
                try (OutputStream out = new FileOutputStream(cursorFile)) {
                    mState.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }

                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString("custom_cursor_path", cursorFile.getAbsolutePath())
                        .putString("custom_cursor_style_key", "custom")
                        .apply();

                updateCustomPreview();
                highlightActiveCard();
                updateTestPointer();

                Toast.makeText(context, getString(R.string.cursor_store_save_success), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(context, getString(R.string.cursor_store_save_fail) + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        mEditorCheckboard.setImageDrawable(null);
        if (mState.bitmap != null && !mState.bitmap.isRecycled()) {
            mState.bitmap.recycle();
        }
        mState.bitmap = null;

        // Clear history stack
        while (!mState.undo.isEmpty()) {
            Bitmap b = mState.undo.pop();
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }

        mPanelEditor.setVisibility(View.GONE);
    }


    private void setCheckboardBackground(View view) {
        int size = 16;
        Bitmap bmp = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint();

        paint.setColor(0xFFE5E5E5);
        canvas.drawRect(0, 0, size * 2, size * 2, paint);

        paint.setColor(0xFFCCCCCC);
        canvas.drawRect(0, 0, size, size, paint);
        canvas.drawRect(size, size, size * 2, size * 2, paint);

        BitmapDrawable tiledDrawable = new BitmapDrawable(getResources(), bmp);
        tiledDrawable.setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        view.setBackground(tiledDrawable);
    }

    @Override

}
