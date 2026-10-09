package wallet.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.RemoteViews;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import wallet.main.MainActivity;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.qr.QrCodeGenerator;
import wallet.send.SendActivity;

/** Native Android home-screen widget matching the Bitcoin Wallet balance card. */
public final class BalanceWidgetProvider extends AppWidgetProvider {

    private static final ExecutorService UPDATE_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "balance-widget");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean UPDATE_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean UPDATE_REQUESTED = new AtomicBoolean(false);
    private static final AtomicReference<String> LAST_RENDER_SIGNATURE = new AtomicReference<>();
    private static final String SNAPSHOT_PREFS = "balance_widget_snapshot";
    private static final String SNAPSHOT_AVAILABLE = "available";
    private static final String SNAPSHOT_BALANCE = "balance";
    private static final String SNAPSHOT_AVAILABLE_BALANCE = "available_balance";
    private static final String SNAPSHOT_PENDING_BALANCE = "pending_balance";
    private static final String SNAPSHOT_NETWORK = "network";
    private static final String SNAPSHOT_ADDRESS = "address";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        if (appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }

        // Apply a complete RemoteViews immediately. The asynchronous wallet read below
        // must not leave the launcher showing only the XML placeholder without click actions.
        MainActivityPresenter.WidgetSnapshot snapshot = loadCachedSnapshot(context);
        if (snapshot == null) {
            snapshot = MainActivityPresenter.WidgetSnapshot.unavailable("--", "");
        }
        RemoteViews views = createViews(context, snapshot);
        manager.updateAppWidget(appWidgetIds, views);
        LAST_RENDER_SIGNATURE.set(null);
        requestRefresh(context);
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
        if (snapshot == null || !snapshot.available) {
            MainActivityPresenter.WidgetSnapshot cached = loadCachedSnapshot(context);
            if (cached != null) {
                snapshot = cached;
            } else {
                snapshot = MainActivityPresenter.WidgetSnapshot.unavailable("--", "");
            }
        } else {
            saveCachedSnapshot(context, snapshot);
        }

        // Always re-apply RemoteViews and PendingIntents. The launcher can restore its
        // initial XML layout after process recreation even when the data signature is unchanged.
        RemoteViews views = createViews(context, snapshot);
        manager.updateAppWidget(component, views);
        LAST_RENDER_SIGNATURE.set(renderSignature(context, ids, snapshot));
    }

    private static void saveCachedSnapshot(
            Context context, MainActivityPresenter.WidgetSnapshot snapshot) {
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
        if (!prefs.getBoolean(SNAPSHOT_AVAILABLE, false)) {
            return null;
        }
        String address = prefs.getString(SNAPSHOT_ADDRESS, null);
        if (address == null || address.isEmpty()) {
            return null;
        }
        return new MainActivityPresenter.WidgetSnapshot(
                true,
                prefs.getString(SNAPSHOT_BALANCE, "--"),
                prefs.getString(SNAPSHOT_AVAILABLE_BALANCE, "--"),
                prefs.getString(SNAPSHOT_PENDING_BALANCE, "--"),
                prefs.getString(SNAPSHOT_NETWORK, ""),
                address);
    }

    private static String renderSignature(
            Context context,
            int[] appWidgetIds,
            MainActivityPresenter.WidgetSnapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        builder.append(context.getResources().getConfiguration().uiMode)
                .append('|');
        if (appWidgetIds != null) {
            for (int id : appWidgetIds) {
                builder.append(id).append(',');
            }
        }
        if (snapshot != null) {
            builder.append('|').append(snapshot.available)
                    .append('|').append(snapshot.balance)
                    .append('|').append(snapshot.availableBalance)
                    .append('|').append(snapshot.pendingBalance)
                    .append('|').append(snapshot.networkLabel)
                    .append('|').append(snapshot.receiveAddress);
        }
        return builder.toString();
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

        // All widget colors and shapes are resolved by the widget XML using framework theme attrs.
        // No widget-specific palette or hard-coded colors are used here.
        views.setImageViewResource(R.id.widgetRequestIcon, R.drawable.ic_widget_request_24dp);
        views.setImageViewResource(R.id.widgetSendIcon, R.drawable.ic_widget_send_24dp);

        Intent qrIntent = new Intent(context, MainActivity.class)
                .setAction(MainActivity.ACTION_SHOW_WIDGET_QR)
                .setData(android.net.Uri.parse("blite-widget://qr"))
                .putExtra(MainActivity.EXTRA_WIDGET_QR_ADDRESS, address)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int qrFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            qrFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent qrPendingIntent = PendingIntent.getActivity(
                context, 7102, qrIntent, qrFlags);
        views.setOnClickPendingIntent(R.id.widgetQr, qrPendingIntent);
        views.setOnClickPendingIntent(R.id.widgetQrContainer, qrPendingIntent);

        PendingIntent requestPendingIntent = activityPendingIntent(
                context,
                wallet.request.RequestCoinsActivity.class,
                7104);
        views.setOnClickPendingIntent(R.id.widgetRequest, requestPendingIntent);
        views.setOnClickPendingIntent(R.id.widgetRequestIcon, requestPendingIntent);

        PendingIntent sendPendingIntent = activityPendingIntent(
                context,
                SendActivity.class,
                7103);
        views.setOnClickPendingIntent(R.id.widgetSend, sendPendingIntent);
        views.setOnClickPendingIntent(R.id.widgetSendIcon, sendPendingIntent);

        return views;
    }

    private static PendingIntent activityPendingIntent(
            Context context,
            Class<?> activityClass,
            int requestCode) {
        Intent intent = new Intent(context, activityClass)
                .setAction(context.getPackageName() + ".WIDGET_OPEN." + requestCode)
                .setData(android.net.Uri.parse("blite-widget://action/" + requestCode))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }

}