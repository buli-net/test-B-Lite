package wallet.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.TypedValue;
import android.widget.RemoteViews;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import wallet.main.MainActivity;
import wallet.main.MainActivityPresenter;
import wallet.main.R;

/** Native Android home-screen widget showing the currently selected B-Lite wallet balance. */
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
        String wallet = snapshot == null || snapshot.walletLabel == null
                ? context.getString(R.string.widget_balance_wallet_loading)
                : snapshot.walletLabel;
        String network = snapshot == null || snapshot.networkLabel == null
                ? ""
                : snapshot.networkLabel;

        views.setTextViewText(R.id.widgetBalance, balance);
        views.setTextViewText(R.id.widgetWallet, wallet);
        views.setTextViewText(R.id.widgetNetwork, network);

        int primary = resolveThemeColor(
                context, android.R.attr.textColorPrimary,
                systemColor(context, isNight(context)
                        ? android.R.color.primary_text_light
                        : android.R.color.primary_text_dark));
        int secondary = resolveThemeColor(
                context, android.R.attr.textColorSecondary,
                systemColor(context, isNight(context)
                        ? android.R.color.secondary_text_light
                        : android.R.color.secondary_text_dark));

        views.setTextColor(R.id.widgetBalance, primary);
        views.setTextColor(R.id.widgetWallet, primary);
        views.setTextColor(R.id.widgetNetwork, secondary);
        views.setInt(R.id.widgetBitcoinIcon, "setColorFilter", primary);
        views.setInt(R.id.widgetRefresh, "setColorFilter", secondary);

        Intent openApp = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int openFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            openFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context, 7101, openApp, openFlags);
        views.setOnClickPendingIntent(R.id.widgetRoot, openPendingIntent);

        Intent refreshIntent = new Intent(context, BalanceWidgetProvider.class)
                .setAction(ACTION_REFRESH);
        int refreshFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            refreshFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent refreshPendingIntent = PendingIntent.getBroadcast(
                context, 7102, refreshIntent, refreshFlags);
        views.setOnClickPendingIntent(R.id.widgetRefresh, refreshPendingIntent);

        return views;
    }

    private static boolean isNight(Context context) {
        int mask = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }


    private static int systemColor(Context context, int colorResId) {
        try {
            return context.getResources().getColor(colorResId, context.getTheme());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int resolveThemeColor(Context context, int attribute, int fallback) {
        TypedValue value = new TypedValue();
        Resources.Theme theme = context.getTheme();
        if (theme != null && theme.resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                try {
                    return context.getResources().getColor(value.resourceId, theme);
                } catch (Exception ignored) {
                }
            }
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
        }
        return fallback;
    }
}
