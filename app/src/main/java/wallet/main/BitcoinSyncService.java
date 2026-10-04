package wallet.main;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.net.Uri;
import androidx.core.app.NotificationCompat;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Transaction;

/**
 * Keeps the bitcoinj WalletAppKit alive independently of the Activity.
 * The Activity is only a UI client; this foreground service owns the sync lifecycle.
 */
public class BitcoinSyncService extends Service {

    private static final String CHANNEL_ID = "bitcoin_sync";
    private static final String TRANSACTION_CHANNEL_ID = "bitcoin_transactions";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS_NOTIFICATIONS = "transaction_notifications";
    private static final String KEY_RECEIVED_PREFIX = "received_";
    private static final String KEY_SENT_PREFIX = "sent_";

    // Keep the service CPU-awake while the wallet engine is running. This is
    // important for long blockchain syncs when the device screen is off. The
    // app also requests the Android battery-optimization exemption from the
    // Sync screen; the wake lock prevents the foreground service thread from
    // being put to sleep between peer/network recovery checks.
    private PowerManager.WakeLock syncWakeLock;

    public static void start(Context context) {
        Intent intent = new Intent(context.getApplicationContext(), BitcoinSyncService.class);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    /** Returns whether Android Doze is allowed to restrict this app. */
    public static boolean isBatteryOptimizationIgnored(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager powerManager =
                (PowerManager) context.getApplicationContext().getSystemService(Context.POWER_SERVICE);
        return powerManager == null
                || powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * Opens the standard Android settings page for this app.
     *
     * This deliberately uses only public Android APIs and contains no OEM-specific
     * package names, activities, or manufacturer checks. Every supported Android
     * device therefore follows the same code path. The OEM may expose its own
     * battery/background controls from this app-specific settings page.
     */
    /**
     * Requests the standard Android battery-optimization exemption for this app.
     * This uses only the public Android API and works on all Android devices
     * that support Doze/battery optimization.
     */
    public static boolean requestBatteryOptimizationExemption(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || isBatteryOptimizationIgnored(context)) {
            return false;
        }

        Context app = context.getApplicationContext();
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + app.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            // Some OEMs do not expose the request screen. Fall back to the
            // public battery-optimization settings page rather than failing.
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(fallback);
                return true;
            } catch (Exception ignoredFallback) {
                return false;
            }
        }
    }

    public static boolean openBatteryOptimizationSettings(Context context) {
        if (context == null) {
            return false;
        }

        Context app = context.getApplicationContext();

        // Universal: open this application's own App info page.
        // This never opens a list of all installed applications and does not
        // depend on the manufacturer, brand, ROM, or a private OEM activity.
        try {
            Intent appInfo = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            appInfo.setData(Uri.parse("package:" + app.getPackageName()));
            appInfo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(appInfo);
            return true;
        } catch (Exception ignored) {
        }

        // Last-resort public Android screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent battery = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                battery.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(battery);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        startForeground(NOTIFICATION_ID, buildSyncNotification(this, 0, 0, 0, true));
        acquireSyncWakeLock();
        ensureWalletPresenter();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Keep the service foreground whenever Android recreates it. The service
        // owns WalletAppKit; Activities are only UI clients.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, buildSyncNotification(this, 0, 0, 0, true));
        }
        acquireSyncWakeLock();
        ensureWalletPresenter();
        return START_STICKY;
    }

    private void acquireSyncWakeLock() {
        if (syncWakeLock != null && syncWakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager =
                (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        try {
            syncWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":bitcoin-sync");
            syncWakeLock.setReferenceCounted(false);
            syncWakeLock.acquire();
        } catch (Exception ignored) {
            syncWakeLock = null;
        }
    }

    private void releaseSyncWakeLock() {
        PowerManager.WakeLock wakeLock = syncWakeLock;
        syncWakeLock = null;
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void ensureWalletPresenter() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            presenter = new MainActivityPresenter(
                    new ServiceView(getApplicationContext()),
                    getFilesDir());
        }
        presenter.subscribe();
    }

    public static void updateSyncNotification(
            Context context,
            int percent,
            int currentBlock,
            int targetBlock,
            boolean syncing) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        NotificationManager manager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        manager.notify(
                NOTIFICATION_ID,
                buildSyncNotification(app, percent, currentBlock, targetBlock, syncing));
    }

    public static void notifyReceived(
            Context context, Coin amount, Transaction transaction) {
        postTransactionNotification(
                context,
                appText(context, wallet.main.R.string.notification_bitcoin_received),
                appText(context, wallet.main.R.string.notification_received_amount,
                        amount == null ? "Bitcoin" : amount.toFriendlyString()),
                transaction);
    }

    public static void notifySent(
            Context context, Coin amount, Transaction transaction) {
        postTransactionNotification(
                context,
                appText(context, wallet.main.R.string.notification_bitcoin_sent),
                appText(context, wallet.main.R.string.notification_sent_amount,
                        amount == null ? "Bitcoin" : amount.toFriendlyString()),
                transaction);
    }

    private static String appText(Context context, int resId, Object... args) {
        return context.getString(resId, args);
    }

    private static void postTransactionNotification(
            Context context, String title, String text, Transaction transaction) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        NotificationManager manager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        String hash = transaction == null ? "" : transaction.getTxId().toString();
        if (!hash.isEmpty() && !markTransactionNotificationSeen(app, title, hash)) {
            return;
        }

        String expanded = text;
        if (!hash.isEmpty()) {
            expanded = text + "\nTX: " + hash;
        }

        Notification notification = new NotificationCompat.Builder(app, TRANSACTION_CHANNEL_ID)
                .setSmallIcon(wallet.main.R.drawable.ic_bitcoin_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(expanded))
                .setContentIntent(mainActivityPendingIntent(app))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setDefaults(NotificationCompat.DEFAULT_SOUND)
                .build();

        int notificationId = hash.isEmpty()
                ? (int) (System.currentTimeMillis() & 0x7fffffff)
                : ((title + ":" + hash).hashCode() & 0x7fffffff);
        if (notificationId == 0) {
            notificationId = 1;
        }
        manager.notify(notificationId, notification);
    }

    private static boolean markTransactionNotificationSeen(
            Context context, String title, String txid) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NOTIFICATIONS, Context.MODE_PRIVATE);
        String prefix = context.getString(wallet.main.R.string.notification_bitcoin_sent).equals(title)
                ? KEY_SENT_PREFIX
                : KEY_RECEIVED_PREFIX;
        String key = prefix + txid;
        synchronized (BitcoinSyncService.class) {
            if (prefs.getBoolean(key, false)) {
                return false;
            }
            prefs.edit().putBoolean(key, true).apply();
            return true;
        }
    }

    private static PendingIntent mainActivityPendingIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, 2001, intent, flags);
    }

    private static Notification buildSyncNotification(
            Context context,
            int percent,
            int currentBlock,
            int targetBlock,
            boolean syncing) {
        int safePercent = Math.max(0, Math.min(100, percent));
        String title = context.getString(wallet.main.R.string.app_name);
        String content;

        if (syncing) {
            if (targetBlock > 0) {
                content = context.getString(
                        wallet.main.R.string.notification_syncing,
                        safePercent, currentBlock, targetBlock);
            } else if (currentBlock > 0) {
                content = context.getString(
                        wallet.main.R.string.notification_waiting_peers,
                        currentBlock);
            } else {
                content = context.getString(
                        wallet.main.R.string.notification_syncing_no_target,
                        safePercent, currentBlock);
            }
        } else {
            content = context.getString(
                    wallet.main.R.string.notification_sync_complete, currentBlock);
        }

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(wallet.main.R.drawable.ic_bitcoin_notification)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setCategory(NotificationCompat.CATEGORY_SERVICE)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setProgress(100, safePercent, false);

        builder.setContentIntent(mainActivityPendingIntent(context));
        return builder.build();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel syncChannel = new NotificationChannel(
                CHANNEL_ID,
                getString(wallet.main.R.string.notification_channel_sync),
                NotificationManager.IMPORTANCE_LOW);
        syncChannel.setDescription(
                getString(wallet.main.R.string.notification_channel_sync_description));
        manager.createNotificationChannel(syncChannel);

        NotificationChannel transactionChannel = new NotificationChannel(
                TRANSACTION_CHANNEL_ID,
                getString(wallet.main.R.string.notification_channel_transactions),
                NotificationManager.IMPORTANCE_DEFAULT);
        transactionChannel.setDescription(
                getString(wallet.main.R.string.notification_channel_transactions_description));
        manager.createNotificationChannel(transactionChannel);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Do not stop the service when the wallet task is removed from Recents.
        // START_STICKY keeps the sync engine alive/restartable independently of the UI task.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        releaseSyncWakeLock();
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter != null) {
            presenter.unsubscribe();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class ServiceView implements MainActivityContract.MainActivityView {
        private final Context context;

        ServiceView(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override public void setPresenter(MainActivityContract.MainActivityPresenter presenter) { }
        @Override public void displayDownloadContent(boolean shown) { }
        @Override public void displayProgress(int percent) { }
        @Override public void displayPercentage(int percent) { }
        @Override public void displayMyBalance(String balance) { }
        @Override public void displayBalanceState(String available, String pending) { }
        @Override public void displayMyAddress(String address) { }
        @Override public void displayWalletType(String type) { }
        @Override public void displayTransactions(java.util.List<wallet.model.TransactionItem> transactions) { }
        @Override public void showToastMessage(String message) { }
        @Override public Context getActivityContext() { return context; }
    }
}
