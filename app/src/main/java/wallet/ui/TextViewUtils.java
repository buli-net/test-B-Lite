package wallet.ui;

import android.graphics.Paint;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Common TextView behavior for long values that must remain visually compact. */
public final class TextViewUtils {
    private static final int MIN_LONG_VALUE_LENGTH = 26;
    private static final Map<TextView, State> STATES = new WeakHashMap<>();

    private TextViewUtils() {
    }

    /**
     * Updates a TextView only when its source value actually changed.
     *
     * The TextView may currently contain a Spannable used only for display
     * ellipsis; comparing toString() keeps that display wrapper from causing
     * unnecessary setText() calls and layout churn.
     */
    public static void setTextIfChanged(TextView view, CharSequence value) {
        if (view == null) return;

        String next = value == null ? "" : value.toString();
        CharSequence current = view.getText();
        String currentText = current == null ? "" : current.toString();
        if (currentText.equals(next)) {
            return;
        }

        view.setText(value == null ? "" : value);
    }

    public static void configureSelectableMiddleEllipsis(TextView view) {
        if (view == null) return;

        view.setMaxLines(1);
        view.setHorizontallyScrolling(false);
        view.setTextIsSelectable(true);
        view.setEllipsize(null);
        view.setTransformationMethod(null);

        synchronized (STATES) {
            if (STATES.containsKey(view)) {
                apply(view, STATES.get(view));
                return;
            }

            State state = new State();
            STATES.put(view, state);
            view.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (!state.applying) {
                        state.lastText = null;
                        state.lastWidth = -1;
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (!state.applying) {
                        // Apply the existing middle-ellipsis span synchronously.
                        // Posting this to the main queue briefly exposes the full
                        // source string between Sync refresh and the ellipsis pass,
                        // which causes visible flicker/jitter on live-updating views.
                        apply(view, state);
                    }
                }
            });
        }

        view.addOnLayoutChangeListener((v, left, top, right, bottom,
                                         oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                State state;
                synchronized (STATES) {
                    state = STATES.get(view);
                }
                if (state != null) {
                    state.lastWidth = -1;
                    apply(view, state);
                }
            }
        });

        State state;
        synchronized (STATES) {
            state = STATES.get(view);
        }
        apply(view, state);
    }

    /**
     * Covers long identifier-like values that are created dynamically, so a
     * newly added address/hash/path field gets the same display behavior.
     */
    public static void configureLongValueViews(View root) {
        if (root == null) return;
        configureLongValueViewTree(root);
    }

    private static void configureLongValueViewTree(View view) {
        if (view instanceof EditText) {
            return;
        }

        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            if (isLongValue(textView.getText())) {
                configureSelectableMiddleEllipsis(textView);
            }
            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                configureLongValueViewTree(group.getChildAt(i));
            }
        }
    }

    private static boolean isLongValue(CharSequence value) {
        if (value == null || value.length() < MIN_LONG_VALUE_LENGTH) {
            return false;
        }
        if (value.toString().indexOf('\n') >= 0) {
            return false;
        }

        String text = value.toString();
        String lower = text.toLowerCase(Locale.US);

        if (lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("bcrt1")
                || lower.startsWith("xpub") || lower.startsWith("tpub")
                || lower.startsWith("ypub") || lower.startsWith("zpub")
                || lower.startsWith("upub") || lower.startsWith("vpub")) {
            return true;
        }

        if (text.startsWith("/") || text.contains("/wallet")) {
            return true;
        }

        boolean hex = true;
        for (int i = 0; i < text.length(); i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                hex = false;
                break;
            }
        }
        if (hex && (text.length() == 64 || text.length() == 66)) {
            return true;
        }

        // Legacy Bitcoin Base58 addresses are typically 26-35 characters.
        // They are shorter than TXIDs but are still long identifiers that must
        // use the same compact display/copy behavior.
        if (text.length() >= 26 && text.length() <= 35
                && text.matches("[1-9A-HJ-NP-Za-km-z]+")) {
            return true;
        }

        return !text.matches(".*\\s+.*") && text.matches("[1-9A-HJ-NP-Za-km-z]{26,}");
    }

    private static void apply(TextView view, State state) {
        if (view == null || state == null || state.applying) return;

        int width = view.getWidth()
                - view.getCompoundPaddingLeft()
                - view.getCompoundPaddingRight();
        String raw = view.getText() == null ? "" : view.getText().toString();

        if (width <= 0 || raw.length() < MIN_LONG_VALUE_LENGTH) {
            return;
        }

        if (raw.equals(state.lastText) && width == state.lastWidth) {
            return;
        }

        state.applying = true;
        try {
            state.lastText = raw;
            state.lastWidth = width;

            if (view.getPaint().measureText(raw) <= width) {
                if (!(view.getText() instanceof Spanned)
                        || view.getText().toString().equals(raw)) {
                    // The text is already complete and fits; do not replace it.
                    return;
                }
                view.setText(raw, TextView.BufferType.SPANNABLE);
                return;
            }

            float ellipsisWidth = view.getPaint().measureText("…");
            if (ellipsisWidth >= width) {
                return;
            }

            int[] range = findVisibleRange(view.getPaint(), raw, width - ellipsisWidth);
            if (range[0] == 0 && range[1] == raw.length()) {
                return;
            }

            SpannableString display = new SpannableString(raw);
            display.setSpan(new EllipsisSpan(ellipsisWidth), range[0], range[1],
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(display, TextView.BufferType.SPANNABLE);
        } finally {
            state.applying = false;
        }
    }

    private static int[] findVisibleRange(Paint paint, String text, float available) {
        float prefixWidth = 0f;
        float suffixWidth = 0f;
        int prefix = 0;
        int suffix = text.length();

        while (prefix < suffix) {
            float nextPrefix = paint.measureText(text, prefix, prefix + 1);
            float nextSuffix = paint.measureText(text, suffix - 1, suffix);

            if (prefixWidth <= suffixWidth) {
                if (prefixWidth + nextPrefix + suffixWidth <= available) {
                    prefixWidth += nextPrefix;
                    prefix++;
                } else if (prefixWidth + suffixWidth + nextSuffix <= available) {
                    suffixWidth += nextSuffix;
                    suffix--;
                } else {
                    break;
                }
            } else {
                if (prefixWidth + suffixWidth + nextSuffix <= available) {
                    suffixWidth += nextSuffix;
                    suffix--;
                } else if (prefixWidth + nextPrefix + suffixWidth <= available) {
                    prefixWidth += nextPrefix;
                    prefix++;
                } else {
                    break;
                }
            }
        }

        return new int[]{prefix, suffix};
    }

    private static final class State {
        String lastText;
        int lastWidth = -1;
        boolean applying;
    }

    private static final class EllipsisSpan extends ReplacementSpan {
        private final float width;

        EllipsisSpan(float width) {
            this.width = width;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            return Math.round(width);
        }

        @Override
        public void draw(android.graphics.Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            canvas.drawText("…", x, y, paint);
        }
    }
}
