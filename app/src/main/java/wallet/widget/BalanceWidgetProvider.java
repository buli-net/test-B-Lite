package wallet.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import wallet.main.MainActivity;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.qr.QrCodeGenerator;
import wallet.send.SendActivity;

/** Native Android home-screen widget matching the B-Lite balance card. */
public final class BalanceWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "wallet.widget.action.BALANCE_REFRESH";

    private static final ExecutorService UPDATE_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "balance-widget");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean UPDATE_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean UPDATE_REQUESTED = new AtomicBoolean(false);

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        requestRefresh(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            requestRefresh(context);
        }
    }

    /** Requests a coalesced asynchronous widget refresh. Safe to call from bitcoinj threads. */
    public static void requestRefresh(Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        UPDATE_REQUESTED.set(true);
        if (!UPDATE_RUNNING.compareAndSet(false, true)) {
            return;
        }
        UPDATE_EXECUTOR.execute(() -> {
            try {
                while (UPDATE_REQUESTED.getAndSet(false)) {
                    updateAllWidgets(app);
                }
            } finally {
                UPDATE_RUNNING.set(false);
                if (UPDATE_REQUESTED.get()) {
                    requestRefresh(app);
                }
            }
        });
    }

    private static void updateAllWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, BalanceWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids == null || ids.length == 0) {
            return;
        }

        MainActivityPresenter.WidgetSnapshot snapshot =
                MainActivityPresenter.getWidgetSnapshot(context);
        RemoteViews views = createViews(context, snapshot);
        manager.updateAppWidget(component, views);
    }

    private static RemoteViews createViews(
            Context context,
            MainActivityPresenter.WidgetSnapshot snapshot) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_balance);

        String balance = snapshot == null || !snapshot.available
                ? "--"
                : snapshot.balance;
        String available = snapshot == null || !snapshot.available
                ? "--"
                : snapshot.availableBalance;
        String pending = snapshot == null || !snapshot.available
                ? "--"
                : snapshot.pendingBalance;
        String network = snapshot == null || snapshot.networkLabel == null
                ? ""
                : snapshot.networkLabel;
        String address = snapshot == null ? null : snapshot.receiveAddress;

        views.setTextViewText(R.id.widgetBalance, balance);
        views.setTextViewText(R.id.widgetNetwork, network);
        views.setTextViewText(
                R.id.widgetAvailable,
                context.getString(R.string.available_balance, available));
        views.setTextViewText(
                R.id.widgetPending,
                context.getString(R.string.pending_balance, pending));

        boolean hasAddress = address != null && !address.isEmpty();
        if (hasAddress) {
            try {
                Bitmap qr = QrCodeGenerator.generate(address, 256);
                views.setImageViewBitmap(R.id.widgetQr, qr);
                views.setViewVisibility(R.id.widgetQr, View.VISIBLE);
            } catch (Exception ignored) {
                views.setViewVisibility(R.id.widgetQr, View.INVISIBLE);
            }
        } else {
            views.setViewVisibility(R.id.widgetQr, View.INVISIBLE);
        }

        int cardColor = resolveThemeColor(
                context, android.R.attr.colorAccent,
                resolveThemeColor(context, android.R.attr.colorBackground, 0));
        int themePrimary = resolveThemeColor(context, android.R.attr.textColorPrimary, 0);
        int themeSecondary = resolveThemeColor(context, android.R.attr.textColorSecondary, themePrimary);
        int themeInverse = resolveThemeColor(context, android.R.attr.textColorPrimaryInverse, themeSecondary);
        int primaryText = chooseBestContrastColor(cardColor, themePrimary, themeInverse);
        int secondaryText = chooseBestContrastColor(cardColor, themeSecondary, primaryText);
        int actionColor = resolveThemeColor(context, android.R.attr.colorBackground, cardColor);
        int actionBorder = resolveThemeColor(context, android.R.attr.colorControlNormal, secondaryText);

        views.setTextColor(R.id.widgetTotalTitle, secondaryText);
        views.setTextColor(R.id.widgetNetwork, primaryText);
        views.setTextColor(R.id.widgetBalance, primaryText);
        views.setTextColor(R.id.widgetAvailable, secondaryText);
        views.setTextColor(R.id.widgetPending, secondaryText);
        views.setTextColor(R.id.widgetSendText, primaryText);
        views.setImageViewBitmap(R.id.widgetSendIcon, createSendIconBitmap(context, primaryText, 48));
        views.setImageViewBitmap(R.id.widgetBackground, createRoundedBitmap(
                cardColor, 10f, 720, 160));
        views.setImageViewBitmap(R.id.widgetQrBackground, createRoundedBitmap(
                actionColor, 10f, 160, 160, actionBorder));
        views.setImageViewBitmap(R.id.widgetSendBackground, createRoundedBitmap(
                actionColor, 10f, 160, 160, actionBorder));

        PendingIntent openAppPendingIntent = activityPendingIntent(
                context,
                MainActivity.class,
                7101);
        views.setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent);
        views.setOnClickPendingIntent(R.id.widgetQr, openAppPendingIntent);

        PendingIntent sendPendingIntent = activityPendingIntent(
                context,
                SendActivity.class,
                7103);
        views.setOnClickPendingIntent(R.id.widgetSend, sendPendingIntent);

        return views;
    }

    private static Bitmap createSendIconBitmap(Context context, int tintColor, int sizePx) {
        Drawable drawable = context.getDrawable(R.drawable.ic_toolbar_send_24dp);
        if (drawable == null) {
            return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        }
        drawable = drawable.mutate();
        drawable.setTint(tintColor);
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, sizePx, sizePx);
        drawable.draw(canvas);
        return bitmap;
    }

    private static int chooseBestContrastColor(int background, int first, int second) {
        double firstContrast = contrastRatio(background, first);
        double secondContrast = contrastRatio(background, second);
        return firstContrast >= secondContrast ? first : second;
    }

    private static double contrastRatio(int background, int foreground) {
        double bg = relativeLuminance(background);
        double fg = relativeLuminance(foreground);
        double light = Math.max(bg, fg);
        double dark = Math.min(bg, fg);
        return (light + 0.05d) / (dark + 0.05d);
    }

    private static double relativeLuminance(int color) {
        double r = linearize(Color.red(color) / 255d);
        double g = linearize(Color.green(color) / 255d);
        double b = linearize(Color.blue(color) / 255d);
        return 0.2126d * r + 0.7152d * g + 0.0722d * b;
    }

    private static double linearize(double value) {
        return value <= 0.03928d
                ? value / 12.92d
                : Math.pow((value + 0.055d) / 1.055d, 2.4d);
    }

    private static PendingIntent activityPendingIntent(
            Context context,
            Class<?> activityClass,
            int requestCode) {
        Intent intent = new Intent(context, activityClass)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }

    private static int resolveThemeColor(Context context, int attrId, int fallback) {
        try {
            TypedValue value = new TypedValue();
            if (!context.getTheme().resolveAttribute(attrId, value, true)) {
                return fallback;
            }
            if (value.resourceId != 0) {
                ColorStateList list = context.getResources().getColorStateList(value.resourceId, context.getTheme());
                if (list != null) {
                    return list.getDefaultColor();
                }
                return context.getResources().getColor(value.resourceId, context.getTheme());
            }
            return value.data;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Bitmap createRoundedBitmap(int fillColor, float radiusDp, int widthPx, int heightPx) {
        return createRoundedBitmap(fillColor, radiusDp, widthPx, heightPx, null);
    }

    private static Bitmap createRoundedBitmap(
            int fillColor, float radiusDp, int widthPx, int heightPx, Integer strokeColor) {
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float radius = radiusDp * 2.0f;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(fillColor);
        canvas.drawRoundRect(new RectF(0, 0, widthPx, heightPx), radius, radius, fill);
        if (strokeColor != null) {
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(3f);
            stroke.setColor(strokeColor);
            canvas.drawRoundRect(new RectF(1.5f, 1.5f, widthPx - 1.5f, heightPx - 1.5f),
                    radius, radius, stroke);
        }
        return bitmap;
    }
}