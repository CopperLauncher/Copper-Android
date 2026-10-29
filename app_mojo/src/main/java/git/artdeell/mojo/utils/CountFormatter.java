package git.artdeell.mojo.utils;

import java.util.Locale;

/** Short counts for download and favorite numbers: 950, 1.2K, 159M */
public final class CountFormatter {
    private CountFormatter() {}

    public static String compact(long count) {
        if (count < 1_000) return String.valueOf(count);
        double value;
        String suffix;
        if (count < 1_000_000) { value = count / 1_000d; suffix = "K"; }
        else if (count < 1_000_000_000L) { value = count / 1_000_000d; suffix = "M"; }
        else { value = count / 1_000_000_000d; suffix = "B"; }
        // 1.2K but 275M, a decimal on three digits is just noise
        if (value >= 100) return String.format(Locale.US, "%.0f%s", value, suffix);
        String text = String.format(Locale.US, "%.1f", value);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return text + suffix;
    }
}
