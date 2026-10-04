package wallet.main;

import android.os.Bundle;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.widget.Button;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import wallet.ui.TextViewUtils;
/** Live blockchain and BitcoinJ synchronization monitor. */
public class SyncActivity extends BaseActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MainActivityPresenter presenter;
    private Toolbar toolbar;
    private ProgressBar progress;
    private TextView status;
    private TextView percent;
    private TextView walletBlock;
    private TextView networkBlock;
    private TextView bestChain;
    private TextView blocksBehind;
    private TextView chainHash;
    private TextView lastBlockTime;
    private TextView syncRate;
    private TextView syncTargetInline;
    private TextView syncBlockAge;
    private TextView pendingPeers;
    private TextView peers;
    private TextView network;
    private TextView engine;
    private TextView restarts;
    private TextView liveNote;
    private TextView peerList;
    private TextView networkCapabilities;
    private TextView chainTechnical;
    private TextView merkleRoot;
    private TextView walletChainState;
    private Button refresh;
    private Button reconnect;
    private Button rescan;
    private Button allowBackgroundSync;
    private TextView backgroundSyncStatus;

    private final Runnable updater = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_sync);
        bindViews();
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.sync_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        BitcoinSyncService.start(this);
        presenter = MainActivityPresenter.getActivePresenter();
        refresh.setOnClickListener(v -> render());
        reconnect.setOnClickListener(v -> {
            MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
            if (active != null) {
                active.reconnectNow();
                render();
            }
        });
        rescan.setOnClickListener(v -> showRescanDialog());
        allowBackgroundSync.setOnClickListener(v -> {
            boolean unrestricted = BitcoinSyncService.isBatteryOptimizationIgnored(this);
            if (unrestricted) {
                showCancelBackgroundSyncDialog();
                return;
            }

            boolean opened = BitcoinSyncService.requestBatteryOptimizationExemption(this);
            if (!opened) {
                Toast.makeText(this, R.string.sync_background_settings_failed, Toast.LENGTH_SHORT).show();
            }
        });
        handler.post(updater);
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar_sync);
        progress = findViewById(R.id.syncPageProgress);
        status = findViewById(R.id.syncPageStatus);
        percent = findViewById(R.id.syncPagePercent);
        walletBlock = findViewById(R.id.syncWalletBlock);
        networkBlock = findViewById(R.id.syncNetworkBlock);
        bestChain = findViewById(R.id.syncBestChain);
        blocksBehind = findViewById(R.id.syncBlocksBehind);
        chainHash = findViewById(R.id.syncChainHash);
        lastBlockTime = findViewById(R.id.syncLastBlockTime);
        syncRate = findViewById(R.id.syncRate);
        syncTargetInline = findViewById(R.id.syncTargetInline);
        syncBlockAge = findViewById(R.id.syncBlockAge);
        pendingPeers = findViewById(R.id.syncPendingPeers);
        peers = findViewById(R.id.syncPeers);
        network = findViewById(R.id.syncNetwork);
        engine = findViewById(R.id.syncEngine);
        restarts = findViewById(R.id.syncRestarts);
        liveNote = findViewById(R.id.syncLiveNote);
        peerList = findViewById(R.id.syncPeerList);
        networkCapabilities = findViewById(R.id.syncNetworkCapabilities);
        chainTechnical = findViewById(R.id.syncChainTechnical);
        merkleRoot = findViewById(R.id.syncMerkleRoot);
        walletChainState = findViewById(R.id.syncWalletChainState);
        TextViewUtils.configureSelectableMiddleEllipsis(chainHash);
        TextViewUtils.configureSelectableMiddleEllipsis(merkleRoot);
        refresh = findViewById(R.id.syncRefreshButton);
        reconnect = findViewById(R.id.syncReconnectButton);
        rescan = findViewById(R.id.syncRescanButton);
        allowBackgroundSync = findViewById(R.id.syncAllowBackgroundButton);
        backgroundSyncStatus = findViewById(R.id.syncBackgroundStatus);
    }

    private void render() {
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active == null) {
            status.setText(R.string.sync_status_starting);
            progress.setIndeterminate(true);
            percent.setText(R.string.sync_percent_initial);
            return;
        }
        presenter = active;
        int pct = active.getSyncPercent();
        int current = active.getCurrentBlock();
        int target = active.getNetworkBlock();

        progress.setIndeterminate(false);
        progress.setProgress(pct);
        if (hasActiveSelection()) {
            reconnect.setEnabled(active.isWalletReady());
            renderBackgroundSyncState();
            return;
        }
        setTextIfChanged(percent, getString(R.string.percentage_display, pct));
        setTextIfChanged(walletBlock, String.valueOf(current));
        setTextIfChanged(networkBlock, String.valueOf(target));
        setTextIfChanged(syncTargetInline, String.valueOf(target));
        setTextIfChanged(bestChain, String.valueOf(active.getBestChainHeight()));
        setTextIfChanged(blocksBehind, String.valueOf(active.getBlocksBehind()));
        setTextIfChanged(peers, String.valueOf(active.getConnectedPeerCount()));
        setTextIfChanged(pendingPeers, String.valueOf(active.getPendingPeerCount()));
        String hash = active.getBestChainHash();
        setTextIfChanged(chainHash, hash.isEmpty() ? getString(R.string.sync_placeholder) : hash);

        long blockTime = active.getBestChainTimeSeconds();
        if (blockTime <= 0L) {
            setTextIfChanged(lastBlockTime, getString(R.string.sync_placeholder));
        } else {
            setTextIfChanged(lastBlockTime, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(blockTime * 1000L)));
        }
        if (blockTime <= 0L) {
            setTextIfChanged(syncBlockAge, getString(R.string.sync_placeholder));
        } else {
            long ageSeconds = Math.max(0L, (System.currentTimeMillis() / 1000L) - blockTime);
            setTextIfChanged(syncBlockAge, formatAge(ageSeconds));
        }
        setTextIfChanged(syncRate, getString(R.string.sync_rate_value, active.getSyncBlocksPerSecond()));
        setTextIfChanged(network, active.getNetworkName());
        setTextIfChanged(engine, getString(active.isWalletKitRunning()
                ? R.string.sync_engine_running : R.string.sync_engine_stopped));
        setTextIfChanged(restarts, String.valueOf(active.getAutoRestartCount()));
        String peerDetails = getString(R.string.sync_download_peer_value, active.getDownloadPeerDetails())
                + "\n\n" + active.getPeerGroupDetails();
        setTextIfChanged(peerList, peerDetails);
        setTextIfChanged(networkCapabilities, active.getNetworkCapabilities());
        setTextIfChanged(chainTechnical, active.getChainTechnicalDetails());
        setTextIfChanged(merkleRoot, active.getChainMerkleRoot());
        setTextIfChanged(walletChainState, active.getWalletChainState());
        setTextIfChanged(status, getString(active.getSyncStatusResId()));
        setTextIfChanged(liveNote, getString(R.string.sync_live_note));
        reconnect.setEnabled(active.isWalletReady());
        renderBackgroundSyncState();
    }


    private boolean hasActiveSelection() {
        return hasSelection(peerList)
                || hasSelection(networkCapabilities)
                || hasSelection(chainTechnical)
                || hasSelection(merkleRoot)
                || hasSelection(chainHash)
                || hasSelection(walletChainState);
    }

    private boolean hasSelection(TextView view) {
        return view != null && view.hasSelection();
    }

    private void setTextIfChanged(TextView view, CharSequence value) {
        if (view == null || view.hasSelection()) return;
        CharSequence current = view.getText();
        if (!TextUtils.equals(current, value)) {
            view.setText(value);
        }
    }

    private void showRescanDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sync_rescan_title)
                .setMessage(R.string.sync_rescan_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.sync_rescan_button, (dialog, which) -> startRescan())
                .show();
    }

    private void startRescan() {
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active == null || !active.isWalletReady()) {
            Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_LONG).show();
            return;
        }
        rescan.setEnabled(false);
        active.rescanWatchedAddresses(
                java.time.Instant.ofEpochSecond(1231006505L),
                error -> runOnUiThread(() -> {
                    rescan.setEnabled(true);
                    if (error != null) {
                        Toast.makeText(this, getString(
                                R.string.sync_rescan_failed,
                                error.getMessage() == null
                                        ? error.getClass().getSimpleName()
                                        : error.getMessage()),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.sync_rescan_started, Toast.LENGTH_LONG).show();
                        render();
                    }
                }));
    }

    private void showCancelBackgroundSyncDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sync_background_cancel_title)
                .setMessage(R.string.sync_background_cancel_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.sync_background_cancel_confirm, (dialog, which) ->
                        openBatteryOptimizationRevokeScreen())
                .show();
    }

    private void openBatteryOptimizationRevokeScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            renderBackgroundSyncState();
            return;
        }

        boolean opened = BitcoinSyncService.openBatteryOptimizationSettings(this);
        if (!opened) {
            Toast.makeText(this, R.string.sync_background_settings_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void renderBackgroundSyncState() {
        boolean unrestricted = BitcoinSyncService.isBatteryOptimizationIgnored(this);
        setTextIfChanged(backgroundSyncStatus, getString(unrestricted
                ? R.string.sync_background_allowed
                : R.string.sync_background_restricted));
        setTextIfChanged(allowBackgroundSync, getString(unrestricted
                ? R.string.sync_background_cancel_button
                : R.string.sync_background_allow_button));
        allowBackgroundSync.setEnabled(true);
    }

    private String formatAge(long seconds) {
        if (seconds < 60L) return getString(R.string.sync_block_age_seconds, seconds);
        if (seconds < 3600L) return getString(R.string.sync_block_age_minutes, seconds / 60L);
        if (seconds < 86400L) return getString(R.string.sync_block_age_hours, seconds / 3600L);
        return getString(R.string.sync_block_age_days, seconds / 86400L);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(updater);
        handler.post(updater);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(updater);
        super.onPause();
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
