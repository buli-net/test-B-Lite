package wallet.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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

        // All widget colors and shapes are resolved by the widget XML using framework theme attrs.
        // No widget-specific palette or hard-coded colors are used here.
        views.setImageViewResource(R.id.widgetSendIcon, R.drawable.ic_widget_send_24dp);

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


}