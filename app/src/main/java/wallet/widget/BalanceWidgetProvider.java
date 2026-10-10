package wallet.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import wallet.main.MainActivity;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.qr.QrCodeGenerator;
import wallet.send.SendActivity;

/** Home-screen wallet widget. */
public final class BalanceWidgetProvider extends AppWidgetProvider {
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "balance-widget"));

    private static final String SNAPSHOT_PREFS = "balance_widget_snapshot";
    private static final String SNAPSHOT_AVAILABLE = "available";
    private static final String SNAPSHOT_BALANCE = "balance";
    private static final String SNAPSHOT_AVAILABLE_BALANCE = "available_balance";
    private static final String SNAPSHOT_PENDING_BALANCE = "pending_balance";
    private static final String SNAPSHOT_NETWORK = "network";
    private static final String SNAPSHOT_ADDRESS = "address";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        if (appWidgetIds == null || appWidgetIds.length == 0) return;

        MainActivityPresenter.WidgetSnapshot cached = loadCachedSnapshot(context);
        if (cached == null)
            cached = MainActivityPresenter.WidgetSnapshot.unavailable("--", "");
        manager.updateAppWidget(appWidgetIds, createViews(context, cached));

        final Context app = context.getApplicationContext();
        final int[] ids = appWidgetIds.clone();
        final PendingResult result = goAsync();
        EXECUTOR.execute(() -> {
            try {
                updateWidgets(app, AppWidgetManager.getInstance(app), ids);
            } finally {
                result.finish();
            }
        });
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        final Context app = context.getApplicationContext();
        final PendingResult result = goAsync();
        EXECUTOR.execute(() -> {
            try {
                updateWidgets(app, AppWidgetManager.getInstance(app), new int[]{appWidgetId});
            } finally {
                result.finish();
            }
        });
    }

    /** Refreshes all installed instances after wallet state changes. */
    public static void requestRefresh(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            AppWidgetManager manager = AppWidgetManager.getInstance(app);
            int[] ids = manager.getAppWidgetIds(new ComponentName(app, BalanceWidgetProvider.class));
            if (ids.length > 0) updateWidgets(app, manager, ids);
        });
    }

    private static void updateWidgets(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0) return;

        MainActivityPresenter.WidgetSnapshot snapshot = MainActivityPresenter.getWidgetSnapshot(context);
        if (snapshot == null || !snapshot.available) {
            MainActivityPresenter.WidgetSnapshot cached = loadCachedSnapshot(context);
            snapshot = cached != null ? cached
                    : MainActivityPresenter.WidgetSnapshot.unavailable("--", "");
        } else {
            saveCachedSnapshot(context, snapshot);
        }

        RemoteViews views = createViews(context, snapshot);
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    private static void saveCachedSnapshot(Context context,
                                          MainActivityPresenter.WidgetSnapshot snapshot) {
        context.getSharedPreferences(SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(SNAPSHOT_AVAILABLE, true)
                .putString(SNAPSHOT_BALANCE, snapshot.balance)
                .putString(SNAPSHOT_AVAILABLE_BALANCE, snapshot.availableBalance)
                .putString(SNAPSHOT_PENDING_BALANCE, snapshot.pendingBalance)
                .putString(SNAPSHOT_NETWORK, snapshot.networkLabel)
                .putString(SNAPSHOT_ADDRESS, snapshot.receiveAddress)
                .apply();
    }

    private static MainActivityPresenter.WidgetSnapshot loadCachedSnapshot(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SNAPSHOT_PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(SNAPSHOT_AVAILABLE, false)) return null;
        String address = prefs.getString(SNAPSHOT_ADDRESS, null);
        if (address == null || address.isEmpty()) return null;
        return new MainActivityPresenter.WidgetSnapshot(
                true,
                prefs.getString(SNAPSHOT_BALANCE, "--"),
                prefs.getString(SNAPSHOT_AVAILABLE_BALANCE, "--"),
                prefs.getString(SNAPSHOT_PENDING_BALANCE, "--"),
                prefs.getString(SNAPSHOT_NETWORK, ""),
                address);
    }

    private static RemoteViews createViews(Context context,
                                           MainActivityPresenter.WidgetSnapshot snapshot) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_balance);
        boolean available = snapshot != null && snapshot.available;
        String balance = available ? snapshot.balance : "--";
        String availableBalance = available ? snapshot.availableBalance : "--";
        String pendingBalance = available ? snapshot.pendingBalance : "--";
        String network = snapshot == null || snapshot.networkLabel == null ? "" : snapshot.networkLabel;
        String address = snapshot == null ? null : snapshot.receiveAddress;

        views.setTextViewText(R.id.widgetBalance, balance);
        views.setTextViewText(R.id.widgetNetwork, network);
        views.setTextViewText(R.id.widgetAvailable,
                context.getString(R.string.available_balance, availableBalance));
        views.setTextViewText(R.id.widgetPending,
                context.getString(R.string.pending_balance, pendingBalance));

        if (address != null && !address.isEmpty()) {
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

        views.setImageViewResource(R.id.widgetRequestIcon, R.drawable.ic_widget_request_24dp);
        views.setImageViewResource(R.id.widgetSendIcon, R.drawable.ic_widget_send_24dp);

        Intent qrIntent = new Intent(context, MainActivity.class)
                .setAction(MainActivity.ACTION_SHOW_WIDGET_QR)
                .setData(android.net.Uri.parse("blite-widget://qr"))
                .putExtra(MainActivity.EXTRA_WIDGET_QR_ADDRESS, address)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.widgetQr, pendingIntent(context, qrIntent, 7102));
        views.setOnClickPendingIntent(R.id.widgetQrContainer, pendingIntent(context, qrIntent, 7102));

        PendingIntent request = activityPendingIntent(context,
                wallet.request.RequestCoinsActivity.class, 7104);
        views.setOnClickPendingIntent(R.id.widgetRequest, request);
        views.setOnClickPendingIntent(R.id.widgetRequestIcon, request);

        PendingIntent send = activityPendingIntent(context, SendActivity.class, 7103);
        views.setOnClickPendingIntent(R.id.widgetSend, send);
        views.setOnClickPendingIntent(R.id.widgetSendIcon, send);
        return views;
    }

    private static PendingIntent activityPendingIntent(Context context, Class<?> target, int requestCode) {
        Intent intent = new Intent(context, target)
                .setAction(context.getPackageName() + ".WIDGET_OPEN." + requestCode)
                .setData(android.net.Uri.parse("blite-widget://action/" + requestCode))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return pendingIntent(context, intent, requestCode);
    }

    private static PendingIntent pendingIntent(Context context, Intent intent, int requestCode) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
            flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }
}
