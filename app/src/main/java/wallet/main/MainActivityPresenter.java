package wallet.main;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import wallet.Constants;
import wallet.model.TransactionItem;
import wallet.transaction.TransactionMapper;
import wallet.security.WalletSecurity;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.VersionMessage;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.MnemonicCode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

/** Owns the persistent bitcoinj wallet and sync lifecycle. */
public class MainActivityPresenter
        implements MainActivityContract.MainActivityPresenter {

    private static volatile MainActivityPresenter activePresenter;

    private static final int MAX_CONNECTIONS = 12;

    private static final long STALL_TIMEOUT_MS = 90_000L;
    private static final long NO_PEER_RECONNECT_TIMEOUT_MS = 45_000L;

    private static final long REFRESH_DEBOUNCE_MS = 500L;
    // Never push historical-sync progress to the Android main thread for every block.
    // During fast catch-up bitcoinj can report thousands of blocks per second.
    private static final long PROGRESS_UI_UPDATE_MS = 500L;
    private static final long SYNC_NOTIFICATION_UPDATE_MS = 1000L;
    private static final long DOWNLOAD_KICK_COOLDOWN_MS = 30_000L;

    private static final int MAX_AUTO_RESTARTS = 1440;

    private MainActivityContract.MainActivityView view;

    private final android.content.Context applicationContext;


    private volatile NetworkParameters parameters;

    private volatile BitcoinNetwork activeNetwork;

    private volatile File walletDataDir;

    private volatile WalletAppKit walletAppKit;

    private volatile DownloadProgressTracker activeDownloadListener;
    private volatile long lastDownloadKickAt = 0L;

    private volatile File walletFile;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final Object kitLock = new Object();

    private final AtomicBoolean restartInProgress =
            new AtomicBoolean(false);

    private final Object refreshLock = new Object();

    private boolean refreshInProgress;

    private boolean refreshPending;

    private volatile boolean walletReady = false;

    private volatile boolean downloadFinished = false;

    private volatile boolean startupInProgress = false;

    private volatile boolean shuttingDown = false;

    private volatile int lastPercent = -1;

    /** Start and target heights for the current sync session. A reconnect starts a new session
     * from the current chain tip so progress reflects only the remaining work. */
    private volatile int syncProgressStartBlock = -1;
    private volatile int syncProgressTargetBlock = -1;

    private volatile int lastChainHeight = -1;

    private volatile long lastProgressAt = 0L;
    private volatile long lastProgressUiUpdateAt = 0L;
    private volatile long lastSyncNotificationUpdateAt = 0L;
    private volatile int lastSyncNotificationPercent = -1;
    private volatile long noPeerSince = 0L;

    private volatile int autoRestartCount = 0;

    private volatile boolean networkSwitchInProgress = false;
    private volatile boolean syncModeSwitchInProgress = false;

    private volatile double syncBlocksPerSecond = 0.0;
    private volatile long lastBlockEventAt = 0L;
    private volatile int lastRateHeight = -1;
    private volatile String bestChainHash = "";
    private volatile long bestChainTimeSeconds = 0L;

    private volatile DeterministicSeed pendingRestoreSeed;
    private volatile File pendingMnemonicBackupFile;
    private volatile List<ECKey> pendingRestoreImportedKeys = new ArrayList<>();
    private volatile List<org.bitcoinj.script.Script> pendingRestoreWatchedScripts = new ArrayList<>();
    private volatile AesKey pendingRestoreSourceSessionKey;
    private volatile boolean pendingRestoreSourceEncrypted;

    private ScheduledExecutorService watchdog;

    /** UI clients that need an immediate refresh when the wallet changes while they are visible. */
    private final CopyOnWriteArrayList<Runnable> walletUpdateListeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<Runnable> syncStateListeners =
            new CopyOnWriteArrayList<>();

    public MainActivityPresenter(
            MainActivityContract.MainActivityView view) {

        this.view = view;
        this.applicationContext = view.getActivityContext().getApplicationContext();
        this.activeNetwork = NetworkConfig.get(applicationContext);
        this.parameters = NetworkConfig.parameters(activeNetwork);
        this.walletDataDir = NetworkConfig.walletDirectory(applicationContext, activeNetwork);
        this.walletFile = new File(walletDataDir, Constants.WALLET_NAME + ".wallet");
        activePresenter = this;

        view.setPresenter(this);
    }

    public void attachView(MainActivityContract.MainActivityView newView) {
        if (newView == null) {
            detachView();
            return;
        }
        view = newView;
        view.setPresenter(this);
        renderCurrentState();
    }

    /** Detaches the Activity while keeping the wallet sync engine alive. */
    public void detachView() {
        view = new HeadlessView(applicationContext);
    }

    private void renderCurrentState() {
        runOnUi(() -> {
            if (walletReady && walletAppKit != null) {
                view.displayDownloadContent(!downloadFinished);

                if (!downloadFinished && lastPercent >= 0) {
                    view.displayPercentage(lastPercent);
                    view.displayProgress(lastPercent);
                }

                refresh();
                return;
            }

            view.displayDownloadContent(true);

            if (lastPercent >= 0) {
                view.displayPercentage(lastPercent);
                view.displayProgress(lastPercent);
            }
        });
    }

    @Override
    public void subscribe() {

        shuttingDown = false;

        activeNetwork = NetworkConfig.get(applicationContext);
        parameters = NetworkConfig.parameters(activeNetwork);
        walletDataDir = NetworkConfig.walletDirectory(applicationContext, activeNetwork);
        walletFile = new File(walletDataDir, Constants.WALLET_NAME + ".wallet");
        wallet.widget.BalanceWidgetProvider.requestRefresh(applicationContext);

        BriefLogFormatter.init();

        startWatchdog();

        if (walletAppKit != null
                || startupInProgress
                || restartInProgress.get()) {
            renderCurrentState();
            return;
        }

        renderCurrentState();
        startWalletKit();
    }

    /**
     * Calculates progress for the current sync session. The session starts at the chain height
     * already present when WalletAppKit is (re)started, so a reconnect measures only the blocks
     * that remain instead of falling back to a whole-chain percentage.
     */
    private int calculateSessionProgress(int currentBlock, int peerHeight) {
        if (currentBlock < 0) {
            return 0;
        }

        int start = syncProgressStartBlock;
        if (start < 0 || currentBlock < start) {
            start = currentBlock;
            syncProgressStartBlock = start;
        }

        int target = syncProgressTargetBlock;
        if (target <= start && peerHeight > start) {
            target = peerHeight;
            syncProgressTargetBlock = target;
        } else if (target < 0 && peerHeight > start) {
            target = peerHeight;
            syncProgressTargetBlock = target;
        }

        if (target <= start) {
            return currentBlock > start ? 100 : 0;
        }

        if (currentBlock <= start) {
            return 0;
        }

        if (currentBlock >= target) {
            return 100;
        }

        long completed = (long) currentBlock - start;
        long remaining = (long) target - start;
        return (int) Math.max(0, Math.min(100, Math.round(completed * 100.0 / remaining)));
    }

    private void startWalletKit() {

        synchronized (kitLock) {
            if (shuttingDown
                    || walletAppKit != null
                    || startupInProgress) {
                return;
            }

            startupInProgress = true;
            notifySyncStateChanged();
        }

        new Thread(() -> {

            final NetworkParameters startParameters = parameters;
            Context.propagate(Context.getOrCreate(startParameters));

            if (shuttingDown) {
                startupInProgress = false;
                return;
            }

            WalletAppKit kit = null;

            try {

                synchronized (kitLock) {

                    if (shuttingDown) {
                        startupInProgress = false;
                        return;
                    }

                    final BitcoinNetwork startNetwork = activeNetwork;
                    final File startWalletDir = walletDataDir;
                    final WalletAppKit newKit =
                            new WalletAppKit(
                                    startNetwork,
                                    ScriptType.P2WPKH,
                                    NetworkConfig.keyChainGroupStructure(startNetwork),
                                    startWalletDir,
                                    Constants.WALLET_NAME
                            ) {

                                @Override
                                protected void onSetupCompleted() {

                                    try {

                                        peerGroup().setMaxConnections(MAX_CONNECTIONS);

                                        applySyncMode(peerGroup(), startNetwork,
                                                NetworkConfig.getSyncMode(applicationContext, startNetwork));

                                        // Use mobile-friendly connection/discovery timeouts. Do not artificially cap
                                        // the discovered peer pool: PeerGroup will maintain the
                                        // requested number of live connections itself.
                                        peerGroup().setConnectTimeout(Duration.ofSeconds(15));
                                        peerGroup().setPeerDiscoveryTimeout(Duration.ofSeconds(5));
                                        peerGroup().setStallThreshold(20, Block.HEADER_SIZE * 10);

                                    } catch (Exception ignored) {
                                        // Peer tuning is best-effort; WalletAppKit defaults remain valid.
                                    }

                                    Wallet setupWallet;
                                    PeerGroup setupPeers;
                                    synchronized (kitLock) {
                                        if (shuttingDown || walletAppKit != this) {
                                            return;
                                        }
                                        setupWallet = wallet();
                                        setupPeers = peerGroup();
                                    }

                                    // Reconcile Request-created scripts for every network through
                                    // one wallet-driven path before SPV synchronization starts.
                                    // This also migrates obsolete P2PKH/P2WPKH Request watch entries
                                    // back to normal main-wallet ownership and restores only the
                                    // P2SH-P2WPKH scripts that require explicit script watching.
                                    int requestScriptChanges =
                                            WalletSelection.ensureRequestNestedScripts(setupWallet);
                                    if (requestScriptChanges > 0) {
                                        try {
                                            setupWallet.saveToFile(walletFile);
                                        } catch (IOException ignored) {
                                            // WalletKit autosave will retry; continue startup with
                                            // the corrected in-memory ownership state.
                                        }
                                    }
                                    setupWalletListeners(this, setupWallet);
                                    setupPeerListeners(this, setupPeers);

                                    File restoreBackup = pendingMnemonicBackupFile;
                                    synchronized (kitLock) {
                                        if (shuttingDown || walletAppKit != this) {
                                            return;
                                        }
                                        if (restoreBackup != null && restoreBackup.exists()) {
                                            setupWallet.isConsistentOrThrow();
                                            if (!setupWallet.getParams().equals(parameters)) {
                                                throw new IllegalStateException(
                                                        text(R.string.restore_network_mismatch));
                                            }
                                            try {
                                                mergeRestoredSecondaryWalletData(
                                                        setupWallet,
                                                        pendingRestoreImportedKeys,
                                                        pendingRestoreWatchedScripts,
                                                        pendingRestoreSourceEncrypted,
                                                        pendingRestoreSourceSessionKey);
                                                setupWallet.saveToFile(walletFile);
                                            } catch (IOException error) {
                                                throw new IllegalStateException(
                                                        text(R.string.restore_imported_wallets_preserve_failed),
                                                        error);
                                            }
                                            restoreBackup.delete();
                                            pendingMnemonicBackupFile = null;
                                            clearPendingRestoreSecondaryData();
                                        }

                                        walletReady = true;
                                        if (networkSwitchInProgress) {
                                            networkSwitchInProgress = false;
                                        }
                                    }
                                    notifySyncStateChanged();

                                    int height =
                                            safeChainHeight(this);

                                    lastChainHeight = height;

                                    touchProgress();

                                    runOnUi(() -> {

                                        view.displayDownloadContent(true);

                                        refresh();
                                    });

                                }
                            };

                    final DownloadProgressTracker downloadTracker =
                            new DownloadProgressTracker() {

                                @Override
                                protected void progress(
                                        double pct,
                                        int blocksSoFar,
                                        Instant date) {

                                    super.progress(
                                            pct,
                                            blocksSoFar,
                                            date
                                    );

                                    int chainHeight =
                                            safeChainHeight(newKit);
                                    int peerHeight =
                                            safePeerHeight(newKit);
                                    int percentage =
                                            calculateSessionProgress(chainHeight, peerHeight);

                                    synchronized (kitLock) {
                                        if (shuttingDown || walletAppKit != newKit) {
                                            return;
                                        }
                                    }

                                    lastPercent = percentage;
                                    notifySyncStateChanged();

                                    if (chainHeight >
                                            lastChainHeight) {

                                        lastChainHeight =
                                                chainHeight;
                                    }

                                    touchProgress();

                                    final int uiPercent =
                                            percentage;

                                    long now = System.currentTimeMillis();
                                    boolean percentChanged = uiPercent != lastSyncNotificationPercent;
                                    boolean updateNotification = percentChanged
                                            || now - lastSyncNotificationUpdateAt >= SYNC_NOTIFICATION_UPDATE_MS;

                                    if (updateNotification) {
                                        lastSyncNotificationUpdateAt = now;
                                        lastSyncNotificationPercent = uiPercent;
                                        BitcoinSyncService.updateSyncNotification(
                                                applicationContext,
                                                uiPercent,
                                                chainHeight,
                                                safePeerHeight(newKit),
                                                true);
                                    }

                                    // IMPORTANT: progress() may be called once per downloaded block.
                                    // Posting every callback to the main Looper can queue tens of
                                    // thousands of Runnables during fast catch-up and make the whole
                                    // phone appear frozen. The Sync screen already polls once/sec,
                                    // so a 500 ms UI update is more than sufficient.
                                    if (now - lastProgressUiUpdateAt >= PROGRESS_UI_UPDATE_MS) {
                                        lastProgressUiUpdateAt = now;
                                        runOnUi(() -> {
                                            view.displayDownloadContent(true);
                                            view.displayPercentage(uiPercent);
                                            view.displayProgress(uiPercent);
                                        });
                                    }
                                }

                                @Override
                                protected void doneDownload() {

                                    super.doneDownload();

                                    downloadFinished = true;
                    notifySyncStateChanged();

                                    lastPercent = 100;

                                    BitcoinSyncService.updateSyncNotification(
                                            applicationContext,
                                            100,
                                            safeChainHeight(newKit),
                                            safePeerHeight(newKit),
                                            false);

                                    lastProgressAt =
                                            System.currentTimeMillis();

                                    runOnUi(() -> {

                                        view.displayPercentage(
                                                100
                                        );

                                        view.displayProgress(
                                                100
                                        );

                                        view.displayDownloadContent(
                                                false
                                        );

                                        refresh();
                                    });
                                }
                            };

                    activeDownloadListener = downloadTracker;
                    newKit.setDownloadListener(downloadTracker);

                    newKit.setBlockingStartup(false);

                    newKit.setAutoSave(true);

                    DeterministicSeed restoreSeed = pendingRestoreSeed;
                    if (restoreSeed != null) {
                        newKit.restoreWalletFromSeed(restoreSeed);
                        pendingRestoreSeed = null;
                    }

                    walletAppKit = newKit;

                    kit = newKit;
                }

                lastPercent = -1;

                lastChainHeight =
                        safeChainHeight(kit);
                syncProgressStartBlock = lastChainHeight;
                syncProgressTargetBlock = safePeerHeight(kit);
                noPeerSince = 0L;
                lastRateHeight = lastChainHeight;
                lastBlockEventAt = 0L;
                syncBlocksPerSecond = 0.0;
                lastProgressUiUpdateAt = 0L;
                lastSyncNotificationUpdateAt = 0L;
                lastSyncNotificationPercent = -1;
                lastDownloadKickAt = 0L;
                bestChainHash = "";
                bestChainTimeSeconds = 0L;

                BitcoinSyncService.updateSyncNotification(
                        applicationContext,
                        0,
                        lastChainHeight,
                        safePeerHeight(kit),
                        true);

                downloadFinished = false;

                touchProgress();

                kit.startAsync();

                kit.awaitRunning();

                // WalletAppKit normally starts the chain download after the peer group
                // becomes ready. On Signet, especially with an empty .spvchain, a peer
                // can be connected before the download request is actually scheduled.
                // Kick the downloader once after startup; this is idempotent and also
                // works when there are currently no peers (the PeerGroup will retry).
                requestBlockchainDownload(kit, true);

                startupInProgress = false;

            } catch (Exception e) {

                if (networkSwitchInProgress) {
                    networkSwitchInProgress = false;
                }

                File restoreBackup = pendingMnemonicBackupFile;
                if (restoreBackup != null && restoreBackup.exists()) {
                    if (walletFile.exists()) {
                        walletFile.delete();
                    }
                    if (restoreBackup.renameTo(walletFile)) {
                        pendingMnemonicBackupFile = null;
                        pendingRestoreSeed = null;
                    }
                }

                startupInProgress = false;
                walletReady = false;

                WalletAppKit failedKit;

                synchronized (kitLock) {

                    failedKit = walletAppKit;
                }

                Throwable rootCause =
                        findRootCause(e);

                Throwable failureCause = null;

                if (failedKit != null) {

                    try {

                        failureCause =
                                failedKit.failureCause();

                    } catch (Exception ignored) {
                        // Failure details are optional during cleanup.
                    }
                }

                if (failureCause != null) {

                    rootCause =
                            findRootCause(
                                    failureCause
                            );
                }


                final String errorText =
                        buildFailureMessage(
                                e,
                                rootCause
                        );

                if (!shuttingDown) {

                    runOnUi(() ->
                            view.showToastMessage(
                                    text(R.string.sync_error, errorText)
                            )
                    );

                    scheduleRestartAfterFailure();
                }
            }

        }, "bitcoinj-start").start();
    }

    private void startWatchdog() {

        stopWatchdog();

        watchdog =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {

                            Thread t =
                                    new Thread(
                                            r,
                                            "bitcoinj-watchdog"
                                    );

                            t.setDaemon(true);

                            return t;
                        }
                );

        watchdog.scheduleWithFixedDelay(

                () -> {

                    if (shuttingDown
                            || restartInProgress.get()) {

                        return;
                    }

                    WalletAppKit kit =
                            walletAppKit;

                    if (kit == null) {
                        return;
                    }

                    if (!kit.isRunning()) {
                        // During normal startup awaitRunning() can briefly leave
                        // the kit in a non-running state. Do not restart it while
                        // that startup is already in progress.
                        if (!startupInProgress) {

                            restartWalletKit("WalletAppKit stopped");
                        }
                        return;
                    }

                    int chainHeight =
                            safeChainHeight(kit);

                    int peerHeight =
                            safePeerHeight(kit);

                    int peers =
                            safePeerCount(kit);

                    int pendingPeers =
                            safePendingPeerCount(kit);

                    long now = System.currentTimeMillis();

                    BitcoinSyncService.updateSyncNotification(
                            applicationContext,
                            downloadFinished ? 100 : Math.max(0, lastPercent),
                            chainHeight,
                            peerHeight,
                            !downloadFinished);

                    // A foreground service can stay alive while bitcoinj's P2P
                    // sockets have all gone away after the screen turns off.
                    // Treat zero connected peers as its own health condition;
                    // do not wait for blockchain height to advance before
                    // deciding that the network engine needs recovery.
                    if (peers <= 0) {
                        if (noPeerSince == 0L) {
                            noPeerSince = now;
                        }

                        long noPeerFor = now - noPeerSince;
                        if (noPeerFor >= NO_PEER_RECONNECT_TIMEOUT_MS
                                && !startupInProgress) {


                            if (autoRestartCount >= MAX_AUTO_RESTARTS) {

                                runOnUi(() ->
                                        view.showToastMessage(
                                                text(R.string.sync_waiting_network)
                                        )
                                );
                                noPeerSince = now;
                                touchProgress();
                                return;
                            }

                            restartWalletKit(
                                    downloadFinished
                                            ? "no connected peers after sync"
                                            : "no connected peers during sync"
                            );
                            return;
                        }
                    } else {
                        noPeerSince = 0L;
                        // A real peer connection proves the engine recovered.
                        // Keep the restart limit from permanently disabling
                        // future recovery after several unrelated network drops.
                        if (autoRestartCount > 0) {
                            autoRestartCount = 0;
                        }
                    }

                    if (chainHeight > lastChainHeight) {

                        lastChainHeight =
                                chainHeight;

                        touchProgress();

                        return;
                    }

                    long stalledFor =
                            now - lastProgressAt;

                    // If peers are healthy and clearly ahead but no block has reached
                    // the chain yet, explicitly re-arm blockchain download before doing
                    // a full WalletAppKit restart. This is particularly important for
                    // Signet where peer discovery can succeed while the initial getblocks
                    // request is not yet active.
                    if (!downloadFinished
                            && peerHeight > chainHeight
                            && stalledFor >= 10_000L) {
                        if (requestBlockchainDownload(kit, false)) {
                            return;
                        }
                    }

                    if (stalledFor < STALL_TIMEOUT_MS) {
                        return;
                    }

                    if (autoRestartCount >= MAX_AUTO_RESTARTS) {


                        runOnUi(() ->
                                view.showToastMessage(
                                        text(R.string.sync_waiting_network)
                                )
                        );

                        touchProgress();

                        return;
                    }

                    restartWalletKit("download stalled");

                },

                15,
                15,
                TimeUnit.SECONDS
        );
    }

    private void scheduleRestartAfterFailure() {

        if (shuttingDown ||
                watchdog == null) {

            return;
        }

        if (autoRestartCount >=
                MAX_AUTO_RESTARTS) {


            runOnUi(() ->
                    view.showToastMessage(
                            text(R.string.wallet_start_failed_detailed)
                    )
            );

            return;
        }

        watchdog.schedule(

                () -> {

                    if (!shuttingDown &&
                            !restartInProgress.get()) {

                        restartWalletKit(
                                "WalletAppKit startup failed"
                        );
                    }

                },

                10,
                TimeUnit.SECONDS
        );
    }

    private void restartWalletKit(
            String reason) {

        if (shuttingDown) {
            return;
        }

        if (!restartInProgress.compareAndSet(
                false,
                true)) {

            return;
        }

        autoRestartCount++;

        walletReady = false;

        downloadFinished = false;
        notifySyncStateChanged();


        // Automatic reconnect is an internal lifecycle transition. Do not surface
        // transient STARTING/RECONNECTING states as Toasts; the existing sync state
        // and progress UI already represent this state without interrupting the user.
        runOnUi(() -> view.displayDownloadContent(true));

        new Thread(() -> {

            try {

                WalletAppKit oldKit;

                synchronized (kitLock) {

                    oldKit =
                            walletAppKit;

                    walletAppKit = null;
                }

                if (oldKit != null) {

                    try {

                        oldKit
                                .stopAsync()
                                .awaitTerminated();

                    } catch (Exception ignored) {
                        // Stopping an already-failed WalletAppKit is best-effort cleanup.
                    }
                }

                if (!shuttingDown) {

                    lastPercent = -1;
                    syncProgressStartBlock = -1;
                    syncProgressTargetBlock = -1;

                    lastChainHeight = -1;
                    noPeerSince = 0L;
                    lastDownloadKickAt = 0L;

                    touchProgress();

                    startWalletKit();
                }

            } finally {

                restartInProgress.set(false);
            }

        }, "bitcoinj-reconnect").start();
    }

    /**
     * Explicitly re-arms the bitcoinj blockchain download when peers are already
     * connected but the chain height is not moving. WalletAppKit normally performs
     * this step itself; keeping the fallback here makes the sync lifecycle resilient
     * to a late peer handshake and prevents a connected-but-idle Signet engine.
     */
    private boolean requestBlockchainDownload(WalletAppKit kit, boolean force) {
        if (kit == null || shuttingDown) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (!force && now - lastDownloadKickAt < DOWNLOAD_KICK_COOLDOWN_MS) {
            return false;
        }

        try {
            PeerGroup group = kit.peerGroup();
            if (group == null) {
                return false;
            }

            int chainHeight = safeChainHeight(kit);
            int peerHeight = safePeerHeight(kit);
            if (peerHeight <= chainHeight) {
                return false;
            }

            Peer downloadPeer = group.getDownloadPeer();
            if (downloadPeer != null) {
                // Re-apply the selected mode to the active download peer.
                // Lite requests filtered blocks; Full Block requests full blocks.
                NetworkConfig.SyncMode syncMode =
                        NetworkConfig.getSyncMode(applicationContext, activeNetwork);
                downloadPeer.setDownloadData(true);
                downloadPeer.setDownloadParameters(syncMode == NetworkConfig.SyncMode.LITE);
            }

            if (downloadPeer != null) {
                // The peer is already connected, so bypass the normal WalletAppKit
                // startup path and explicitly send the chain-download request. The
                // tracker installed on the PeerGroup remains active for progress UI.
                downloadPeer.startBlockChainDownload();
            } else {
                DownloadProgressTracker tracker = activeDownloadListener;
                if (tracker == null) {
                    tracker = new DownloadProgressTracker();
                    activeDownloadListener = tracker;
                }
                // No download peer yet: ask PeerGroup to retry as soon as one connects.
                group.startBlockChainDownload(tracker);
            }

            lastDownloadKickAt = now;
            touchProgress();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void applySyncMode(PeerGroup peerGroup, BitcoinNetwork network,
                               NetworkConfig.SyncMode syncMode) {
        if (peerGroup == null) {
            return;
        }

        boolean lite = syncMode == NetworkConfig.SyncMode.LITE;
        peerGroup.setBloomFilteringEnabled(lite);
        peerGroup.setRequiredServices(
                lite
                        ? VersionMessage.NODE_BLOOM | VersionMessage.NODE_WITNESS
                        : VersionMessage.NODE_WITNESS);
        peerGroup.setDownloadTxDependencies(0);
    }

    private void touchProgress() {

        lastProgressAt =
                System.currentTimeMillis();
    }

    private int safeChainHeight(
            WalletAppKit kit) {

        try {

            return kit == null ||
                    kit.chain() == null

                    ? 0

                    : kit.chain()
                            .getBestChainHeight();

        } catch (Exception e) {

            return 0;
        }
    }

    private int safePeerHeight(
            WalletAppKit kit) {

        try {

            PeerGroup peers =
                    kit == null
                            ? null
                            : kit.peerGroup();

            return peers == null
                    ? 0
                    : peers.getMostCommonChainHeight();

        } catch (Exception e) {

            return 0;
        }
    }

    private int safePeerCount(
            WalletAppKit kit) {

        try {

            PeerGroup peers =
                    kit == null
                            ? null
                            : kit.peerGroup();

            return peers == null
                    ? 0
                    : peers.getConnectedPeers()
                            .size();

        } catch (Exception e) {

            return 0;
        }
    }

    private int safePendingPeerCount(
            WalletAppKit kit) {

        try {

            PeerGroup peers =
                    kit == null
                            ? null
                            : kit.peerGroup();

            return peers == null
                    ? 0
                    : peers.getPendingPeers()
                            .size();

        } catch (Exception e) {

            return 0;
        }
    }

    private Throwable findRootCause(
            Throwable throwable) {

        if (throwable == null) {
            return null;
        }

        Throwable current =
                throwable;

        int guard = 0;

        while (
                current.getCause() != null
                        && current.getCause() != current
                        && guard++ < 32
        ) {

            current =
                    current.getCause();
        }

        return current;
    }

    private String buildFailureMessage(
            Exception startException,
            Throwable rootCause) {

        Throwable cause =
                rootCause != null
                        ? rootCause
                        : startException;

        String message =
                cause.getMessage();

        if (TextUtils.isEmpty(message)) {

            message =
                    cause.getClass()
                            .getSimpleName();
        }

        return message;
    }

    private String text(int resId, Object... formatArgs) {
        android.content.Context context = view.getActivityContext();
        return formatArgs == null || formatArgs.length == 0
                ? context.getString(resId)
                : context.getString(resId, formatArgs);
    }

    private String safeMessage(
            Exception e) {

        if (e == null) {
            return text(R.string.unknown_error);
        }

        String message =
                e.getMessage();

        return TextUtils.isEmpty(message)

                ? e.getClass()
                        .getSimpleName()

                : message;
    }

    @Override
    public void unsubscribe() {

        if (activePresenter == this) {
            activePresenter = null;
        }

        shuttingDown = true;
        WalletSecurity.clearSessionKey();
        walletUpdateListeners.clear();
        syncStateListeners.clear();

        startupInProgress = false;
        walletReady = false;

        stopWatchdog();

        new Thread(() -> {

            WalletAppKit kit;

            synchronized (kitLock) {

                kit =
                        walletAppKit;

                walletAppKit = null;
            }

            try {

                if (kit != null) {

                    kit.stopAsync()
                            .awaitTerminated();
                }

            } catch (Exception ignored) {
                // Shutdown cleanup is best-effort.
            }

        }, "bitcoinj-stop").start();
    }

    private void stopWatchdog() {

        if (watchdog != null) {

            watchdog.shutdownNow();

            watchdog = null;
        }
    }

    @Override
    public void refresh() {

        WalletAppKit kit = walletAppKit;

        if (!walletReady || kit == null || restartInProgress.get()) {
            return;
        }

        boolean startWorker = false;
        synchronized (refreshLock) {
            refreshPending = true;
            if (!refreshInProgress) {
                refreshInProgress = true;
                startWorker = true;
            }
        }

        if (startWorker) {
            startRefreshWorker();
        }
    }

    private void startRefreshWorker() {
        new Thread(() -> {
            long lastRefreshAt = 0L;
            boolean restartWorker = false;
            try {
                while (true) {
                    synchronized (refreshLock) {
                        if (!refreshPending) {
                            // Keep refreshInProgress true until finally has
                            // completed the hand-off. Any refresh arriving
                            // after this check will either be observed there
                            // or start its own worker after the flag is cleared.
                            break;
                        }
                        refreshPending = false;
                    }

                    long now = System.currentTimeMillis();
                    long waitMs = REFRESH_DEBOUNCE_MS - (now - lastRefreshAt);
                    if (lastRefreshAt != 0L && waitMs > 0L) {
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    if (!walletReady || restartInProgress.get()) {
                        continue;
                    }

                    try {
                        Wallet currentWallet = getActiveWallet();
                        if (currentWallet == null) {
                            continue;
                        }
                        Context.propagate(Context.getOrCreate(parameters));
                        renderSelectedWallet(currentWallet);

                        // Keep the home-screen widget on the exact same refresh path
                        // as the wallet screen. The widget reads the same live
                        // bitcoinj Wallet state, so confirmed/unconfirmed balance
                        // changes are reflected without polling or an online API.
                        wallet.widget.BalanceWidgetProvider.requestRefresh(applicationContext);

                        lastRefreshAt = System.currentTimeMillis();
                    } catch (Exception ignored) {
                        // A failed refresh will be retried by the next wallet update.
                    }
                }
            } finally {
                synchronized (refreshLock) {
                    if (refreshPending) {
                        // A new update arrived while the worker was ending.
                        // Keep ownership of the worker and process the newest
                        // pending state instead of losing that final update.
                        restartWorker = true;
                    } else {
                        refreshInProgress = false;
                    }
                }

                if (restartWorker) {
                    startRefreshWorker();
                }
            }
        }, "bitcoinj-refresh").start();
    }

    private void renderSelectedWallet(Wallet wallet) {
        android.content.Context context = view.getActivityContext();
        Script selectedScript = WalletSelection.findSelectedScript(context, wallet);
        Script importedScript = selectedScript == null
                ? WalletSelection.findSelectedImportedScript(context, wallet) : null;

        Coin balance;
        Coin available;
        Coin pending;
        String address;
        String walletType;
        List<TransactionItem> transactions;

        if (selectedScript != null || importedScript != null) {
            Script displayScript = selectedScript != null ? selectedScript : importedScript;
            long confirmedSat = 0L;
            long pendingSat = 0L;
            Iterable<TransactionOutput> selectedOutputs;
            if (selectedScript != null) {
                selectedOutputs = wallet.getWatchedOutputs(false);
            } else {
                selectedOutputs = WalletSelection.selectedImportedUsesWatchedOutputs(context)
                        ? wallet.getWatchedOutputs(false) : wallet.getUnspents();
            }
            for (TransactionOutput output : selectedOutputs) {
                if (!output.isAvailableForSpending()
                        || !displayScript.equals(output.getScriptPubKey())) {
                    continue;
                }
                if (output.getParentTransactionDepthInBlocks() > 0) {
                    confirmedSat += output.getValue().value;
                } else {
                    pendingSat += output.getValue().value;
                }
            }

            available = Coin.valueOf(confirmedSat);
            pending = Coin.valueOf(pendingSat);
            balance = available.add(pending);
            address = WalletSelection.addressForScript(displayScript, parameters);
            if (selectedScript != null && WalletSelection.isRequestNestedScript(
                    context, wallet, selectedScript)) {
                walletType = text(R.string.wallet_type_main) + " • "
                        + WalletAddressType.label(context, WalletAddressType.P2SH_P2WPKH);
            } else if (selectedScript != null) {
                walletType = text(R.string.wallet_type_watch);
            } else {
                String importedType = ImportedWalletStore.getAddressType(context,
                        WalletSelection.getSelectedImportedAddress(context));
                walletType = text(R.string.wallet_type_imported) + " • "
                        + WalletAddressType.label(context, importedType);
            }
            transactions = TransactionMapper.mapForWatchedScript(
                    context, wallet, displayScript);
        } else {
            // Keep request-owned Nested SegWit outputs in the main-wallet balance.
            balance = WalletSelection.mainEstimatedBalance(context, wallet);
            available = WalletSelection.mainAvailableBalance(context, wallet);
            pending = balance.subtract(available);
            address = wallet.currentReceiveAddress().toString();
            walletType = text(R.string.wallet_type_main);
            transactions = TransactionMapper.mapForMainWallet(context, wallet);
        }

        final String balanceText = balance.toFriendlyString();
        final String availableText = text(R.string.available_balance,
                available.toFriendlyString());
        final String pendingText = text(R.string.pending_balance,
                pending.toFriendlyString());
        final String selectedAddress = address;
        final String selectedWalletType = walletType;
        final List<TransactionItem> selectedTransactions = transactions;

        runOnUi(() -> {
            view.displayWalletType(selectedWalletType);
            view.displayMyBalance(balanceText);
            view.displayBalanceState(availableText, pendingText);
            if (!TextUtils.isEmpty(selectedAddress)) {
                view.displayMyAddress(selectedAddress);
            }
            view.displayTransactions(selectedTransactions);
        });
    }

    @Override
    public void restoreWallet(final android.net.Uri backupUri) {
        restoreWallet(backupUri, null);
    }

    /**
     * Restores a wallet backup after the current wallet password has been
     * verified. The authorization key is kept local to this restore operation
     * and is never installed as the persistent WalletSecurity session key.
     */
    public void restoreWallet(
            final android.net.Uri backupUri,
            final AesKey currentWalletAuthorizationKey) {

        if (backupUri == null) {
            runOnUi(() ->
                    view.showToastMessage(text(R.string.file_restore_invalid))
            );
            return;
        }

        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

            File tempFile =
                    new File(
                            walletDataDir,
                            Constants.WALLET_NAME + ".restore.tmp"
                    );
            File backupOfCurrent =
                    new File(
                            walletDataDir,
                            Constants.WALLET_NAME + ".before-restore.wallet"
                    );

            WalletAppKit oldKit;
            boolean restoreNeedsWalletRestart = false;

            try {

                if (shuttingDown) {
                    throw new IOException(text(R.string.wallet_closing));
                }

                Wallet currentWallet = null;
                synchronized (kitLock) {
                    if (walletAppKit != null && walletReady) {
                        currentWallet = walletAppKit.wallet();
                    }
                }
                if (currentWallet == null && walletFile.exists()) {
                    currentWallet = Wallet.loadFromFile(walletFile);
                }
                captureRestoreSecondaryWalletData(
                        currentWallet,
                        currentWalletAuthorizationKey);
                WalletSecurity.clearSessionKey();

                try (InputStream input =
                             ((android.content.Context) view)
                                     .getContentResolver()
                                     .openInputStream(backupUri);
                     FileOutputStream output =
                             new FileOutputStream(tempFile)) {

                    if (input == null) {
                        throw new IOException(text(R.string.restore_read_failed));
                    }

                    byte[] buffer = new byte[8192];
                    int count;
                    long total = 0L;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        total += count;

                        if (total > 64L * 1024L * 1024L) {
                            throw new IOException(text(R.string.restore_too_large));
                        }
                    }

                    output.flush();

                    if (total == 0L) {
                        throw new IOException(text(R.string.restore_empty));
                    }
                }

                Wallet restoredWallet = Wallet.loadFromFile(tempFile);

                if (!restoredWallet.getParams().equals(parameters)) {
                    throw new IOException(
                            text(R.string.restore_network_mismatch)
                    );
                }

                restoredWallet.isConsistentOrThrow();

                stopWatchdog();

                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    walletReady = false;
                }

                if (oldKit != null) {
                    restoreNeedsWalletRestart = true;
                    try {
                        oldKit.stopAsync().awaitTerminated();
                    } catch (Exception stopError) {
                        throw new IOException(
                                text(R.string.restore_stop_failed),
                                stopError
                        );
                    }
                }

                if (walletFile.exists()) {
                    if (backupOfCurrent.exists() && !backupOfCurrent.delete()) {
                        throw new IOException(text(R.string.restore_before_delete_failed));
                    }

                    if (!walletFile.renameTo(backupOfCurrent)) {
                        throw new IOException(text(R.string.restore_current_wallet_preserve_failed));
                    }
                }

                if (!tempFile.renameTo(walletFile)) {
                    if (!walletFile.exists() && backupOfCurrent.exists()) {
                        backupOfCurrent.renameTo(walletFile);
                    }
                    throw new IOException(text(R.string.restore_install_failed));
                }

                // Verify the exact wallet file that will be used by WalletAppKit before
                // deleting the safety copy of the previous wallet.
                Wallet installedWallet = Wallet.loadFromFile(walletFile);
                if (!installedWallet.getParams().equals(parameters)) {
                    throw new IOException(text(R.string.restore_network_mismatch));
                }
                installedWallet.isConsistentOrThrow();

                mergeRestoredSecondaryWalletData(
                        installedWallet,
                        pendingRestoreImportedKeys,
                        pendingRestoreWatchedScripts,
                        pendingRestoreSourceEncrypted,
                        pendingRestoreSourceSessionKey);
                installedWallet.saveToFile(walletFile);

                if (backupOfCurrent.exists()) {
                    backupOfCurrent.delete();
                }
                clearPendingRestoreSecondaryData();

                autoRestartCount = 0;
                lastPercent = -1;
                syncProgressStartBlock = -1;
                syncProgressTargetBlock = -1;
                lastChainHeight = -1;
                downloadFinished = false;
                shuttingDown = false;

                runOnUi(() ->
                        view.showToastMessage(
                                text(R.string.restore_success)
                        )
                );

                startWalletKit();
                startWatchdog();

            } catch (Exception e) {

                if (tempFile.exists()) {
                    tempFile.delete();
                }

                // If installation happened but verification/startup failed, restore the
                // previous wallet instead of leaving a questionable wallet in place.
                if (backupOfCurrent.exists()) {
                    if (walletFile.exists()) {
                        walletFile.delete();
                    }
                    backupOfCurrent.renameTo(walletFile);
                }
                clearPendingRestoreSecondaryData();

                if (restoreNeedsWalletRestart && !shuttingDown) {
                    autoRestartCount = 0;
                    lastPercent = -1;
                    syncProgressStartBlock = -1;
                    syncProgressTargetBlock = -1;
                    lastChainHeight = -1;
                    downloadFinished = false;
                    startWalletKit();
                    startWatchdog();
                }

                runOnUi(() ->
                        view.showToastMessage(
                                text(R.string.restore_failed, safeMessage(e))
                        )
                );
            }

        }, "bitcoinj-wallet-restore").start();
    }

    @Override
    public void restoreWalletFromMnemonic(
            final String mnemonic,
            final String birthday) {

        new Thread(() -> {
            Context.propagate(Context.getOrCreate(parameters));
            File backupOfCurrent =
                    new File(
                            walletDataDir,
                            Constants.WALLET_NAME + ".before-mnemonic-restore.wallet");
            File chainFile =
                    new File(
                            walletDataDir,
                            Constants.WALLET_NAME + ".spvchain");

            try {
                if (shuttingDown) {
                    throw new IOException(text(R.string.wallet_closing));
                }

                List<String> words = new ArrayList<>();
                for (String word : mnemonic.trim().split("\\s+")) {
                    if (!word.isEmpty()) {
                        words.add(word.toLowerCase(java.util.Locale.US));
                    }
                }

                new MnemonicCode().check(words);

                DeterministicSeed seed;
                if (birthday == null || birthday.trim().isEmpty()) {
                    seed = DeterministicSeed.ofMnemonic(words, "");
                } else {
                    Instant creationTime = parseBirthday(birthday);
                    seed = DeterministicSeed.ofMnemonic(words, "", creationTime);
                }

                Wallet currentWallet = null;
                synchronized (kitLock) {
                    if (walletAppKit != null && walletReady) {
                        currentWallet = walletAppKit.wallet();
                    }
                }
                if (currentWallet == null && walletFile.exists()) {
                    currentWallet = Wallet.loadFromFile(walletFile);
                }
                captureRestoreSecondaryWalletData(currentWallet);

                stopWatchdog();
                WalletSecurity.clearSessionKey();

                WalletAppKit oldKit;
                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    walletReady = false;
                }

                if (oldKit != null) {
                    oldKit.stopAsync().awaitTerminated();
                }

                if (walletFile.exists()) {
                    if (backupOfCurrent.exists() && !backupOfCurrent.delete()) {
                        throw new IOException(
                                text(R.string.restore_before_delete_failed));
                    }
                    if (!walletFile.renameTo(backupOfCurrent)) {
                        throw new IOException(
                                text(R.string.restore_current_wallet_preserve_failed));
                    }
                }

                if (chainFile.exists() && !chainFile.delete()) {
                    if (backupOfCurrent.exists()) {
                        backupOfCurrent.renameTo(walletFile);
                    }
                    throw new IOException(text(R.string.restore_chain_delete_failed));
                }

                pendingRestoreSeed = seed;
                pendingMnemonicBackupFile = backupOfCurrent;
                autoRestartCount = 0;
                lastPercent = -1;
                syncProgressStartBlock = -1;
                syncProgressTargetBlock = -1;
                lastChainHeight = -1;
                downloadFinished = false;
                shuttingDown = false;

                runOnUi(() ->
                        view.showToastMessage(text(R.string.mnemonic_restore_in_progress)));

                startWalletKit();
                startWatchdog();
            } catch (Exception error) {
                pendingRestoreSeed = null;
                pendingMnemonicBackupFile = null;
                if (backupOfCurrent.exists() && !walletFile.exists()) {
                    backupOfCurrent.renameTo(walletFile);
                }
                clearPendingRestoreSecondaryData();
                runOnUi(() ->
                        view.showToastMessage(
                                text(R.string.mnemonic_restore_failed, safeMessage(error))));
            }
        }, "bitcoinj-mnemonic-restore").start();
    }

    private void captureRestoreSecondaryWalletData(Wallet source) throws IOException {
        captureRestoreSecondaryWalletData(source, null);
    }

    private void captureRestoreSecondaryWalletData(
            Wallet source,
            AesKey authorizationKey) throws IOException {
        pendingRestoreImportedKeys = new ArrayList<>();
        pendingRestoreWatchedScripts = new ArrayList<>();
        pendingRestoreSourceSessionKey = null;
        pendingRestoreSourceEncrypted = false;
        if (source == null) return;

        pendingRestoreSourceEncrypted = WalletSecurity.isEncrypted(source);
        if (pendingRestoreSourceEncrypted) {
            if (authorizationKey != null && source.checkAESKey(authorizationKey)) {
                pendingRestoreSourceSessionKey = authorizationKey;
            } else if (WalletSecurity.isSessionValid(source)) {
                pendingRestoreSourceSessionKey = WalletSecurity.getSessionKey();
            }
        }
        pendingRestoreImportedKeys.addAll(source.getImportedKeys());

        // Preserve explicitly watched external addresses, but do not copy Request
        // scripts whose output scripts are derived from this wallet's own HD receive
        // keys. Older builds stored those scripts in watchedScripts, so blindly merging
        // the list during restore turned every Request address into a Watch-only entry.
        java.util.Set<org.bitcoinj.script.Script> mainWalletReceiveScripts =
                WalletSelection.getMainWalletReceiveScripts(source);
        for (org.bitcoinj.script.Script script : source.getWatchedScripts()) {
            if (!mainWalletReceiveScripts.contains(script)) {
                pendingRestoreWatchedScripts.add(script);
            }
        }
    }

    private void mergeRestoredSecondaryWalletData(
            Wallet target,
            List<ECKey> importedKeys,
            List<org.bitcoinj.script.Script> watchedScripts,
            boolean sourceEncrypted,
            AesKey sourceSessionKey) throws IOException {
        if (target == null) throw new IOException(text(R.string.restored_wallet_unavailable));

        if (watchedScripts != null && !watchedScripts.isEmpty()) {
            List<org.bitcoinj.script.Script> existing = target.getWatchedScripts();
            List<org.bitcoinj.script.Script> missing = new ArrayList<>();
            for (org.bitcoinj.script.Script script : watchedScripts) {
                if (!existing.contains(script)) missing.add(script);
            }
            if (!missing.isEmpty()) target.addWatchedScripts(missing);
        }

        if (importedKeys == null || importedKeys.isEmpty()) return;

        List<ECKey> keysToImport = new ArrayList<>();
        if (sourceEncrypted && !target.isEncrypted()) {
            if (sourceSessionKey == null) {
                throw new IOException(text(R.string.restore_imported_wallet_unlock_current));
            }
            for (ECKey key : importedKeys) {
                if (key.isEncrypted()) {
                    try {
                        keysToImport.add(key.decrypt(key.getKeyCrypter(), sourceSessionKey));
                    } catch (Exception error) {
                        throw new IOException(text(R.string.restore_imported_wallet_preserve_failed), error);
                    }
                } else {
                    keysToImport.add(key);
                }
            }
        } else {
            keysToImport.addAll(importedKeys);
        }

        List<ECKey> missingKeys = new ArrayList<>();
        for (ECKey key : keysToImport) {
            if (target.findKeyFromPubKey(key.getPubKey()) == null) missingKeys.add(key);
        }
        if (missingKeys.isEmpty()) return;

        try {
            if (target.isEncrypted()) {
                if (sourceEncrypted && missingKeys.get(0).isEncrypted()) {
                    for (ECKey key : missingKeys) target.importKey(key);
                } else {
                    AesKey targetSessionKey = WalletSecurity.getSessionKey();
                    if (targetSessionKey == null) {
                        throw new IOException(text(R.string.restore_imported_wallet_unlock_restored));
                    }
                    target.importKeysAndEncrypt(missingKeys, targetSessionKey);
                }
            } else {
                target.importKeys(missingKeys);
            }
        } catch (Exception error) {
            throw new IOException(text(R.string.restore_imported_wallets_preserve_failed), error);
        }
    }

    private void clearPendingRestoreSecondaryData() {
        pendingRestoreImportedKeys = new ArrayList<>();
        pendingRestoreWatchedScripts = new ArrayList<>();
        pendingRestoreSourceSessionKey = null;
        pendingRestoreSourceEncrypted = false;
    }

    private Instant parseBirthday(String birthday) {
        try {
            return java.time.LocalDate.parse(birthday.trim())
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(
                    text(R.string.invalid_wallet_birthday),
                    e
            );
        }
    }

    public void rescanWatchedAddresses(
            final Instant scanFrom,
            final java.util.function.Consumer<Exception> finished) {

        new Thread(() -> {
            WalletAppKit oldKit = null;
            Exception failure = null;
            File safetyCopy = null;

            // A watch-only rescan replaces the running WalletAppKit. Serialize it with
            // the normal watchdog/reconnect path so the kit cannot be restarted twice
            // while the wallet file and SPV chain are being replaced.
            if (!restartInProgress.compareAndSet(false, true)) {
                failure = new IOException(text(R.string.watch_rescan_already_restarting));
                if (finished != null) {
                    final Exception result = failure;
                    runOnUi(() -> finished.accept(result));
                }
                return;
            }

            try {
                Context.propagate(Context.getOrCreate(parameters));

                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    walletReady = false;
                    downloadFinished = false;
                    lastPercent = -1;
                    syncProgressStartBlock = -1;
                    syncProgressTargetBlock = -1;
                    lastChainHeight = -1;
                }

                if (oldKit == null) {
                    throw new IOException(text(R.string.watch_rescan_wallet_not_running));
                }

                // WalletAppKit.wallet() is only accessible while the kit is STARTING/RUNNING.
                // Capture the wallet and its watched scripts BEFORE stopping the kit. Calling
                // oldKit.wallet() after awaitTerminated() throws:
                // "cannot call until startup is complete".
                Wallet wallet = oldKit.wallet();
                List<org.bitcoinj.script.Script> oldScripts = wallet.getWatchedScripts();
                safetyCopy = createWalletSafetyCopy("watch-rescan");

                // Stop the running kit BEFORE rewriting the wallet. This prevents its autosave/shutdown
                // path from writing the pre-rescan state back over our reset wallet.
                oldKit.stopAsync().awaitTerminated();
                List<org.bitcoinj.script.Script> rescannedScripts = new ArrayList<>();
                for (org.bitcoinj.script.Script script : oldScripts) {
                    // Preserve the exact output script bytes. Only replace the creation timestamp used by
                    // bitcoinj for fast-catchup/scanning. This avoids converting a script through Address and
                    // accidentally changing an uncommon script form.
                    rescannedScripts.add(
                            org.bitcoinj.script.Script.parse(script.program(), scanFrom));
                }

                wallet.removeWatchedScripts(oldScripts);
                wallet.addWatchedScripts(rescannedScripts);
                wallet.reset();
                wallet.saveToFile(walletFile);

                File chainFile =
                        new File(
                                walletDataDir,
                                Constants.WALLET_NAME + ".spvchain");
                if (chainFile.exists() && !chainFile.delete()) {
                    throw new IOException(text(R.string.watch_rescan_chain_reset_failed));
                }

                shuttingDown = false;
                autoRestartCount = 0;
                startWalletKit();
            } catch (Exception error) {
                failure = error;
                if (safetyCopy != null && safetyCopy.exists()) {
                    try {
                        copyFile(safetyCopy, walletFile);
                    } catch (Exception ignored) {
                        // The original wallet file remains untouched if recovery fails.
                    }
                }

                synchronized (kitLock) {
                    walletAppKit = null;
                    walletReady = false;
                }
                if (!shuttingDown) {
                    try {
                        startWalletKit();
                    } catch (Exception ignored) {
                        // The completion callback reports the restart failure to the caller.
                    }
                }
            } finally {
                restartInProgress.set(false);
                final Exception result = failure;
                if (finished != null) {
                    runOnUi(() -> finished.accept(result));
                }
            }
        }, "bitcoinj-watch-rescan").start();
    }

    private File createWalletSafetyCopy(String reason) throws IOException {
        if (!walletFile.exists()) {
            throw new IOException(text(R.string.wallet_file_missing));
        }
        File dir = new File(walletDataDir, "backup-safety");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException(text(R.string.wallet_safety_directory_failed));
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        File target = new File(dir, Constants.WALLET_NAME + "-" + reason + "-" + stamp + ".wallet");
        copyFile(walletFile, target);
        return target;
    }

    private void copyFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        }
    }

    public static void saveActiveWalletToFile(File target) throws IOException {
        if (target == null) {
            throw new IOException("wallet target is null");
        }
        MainActivityPresenter presenter = activePresenter;
        if (presenter == null) {
            throw new IOException("wallet_not_ready");
        }
        synchronized (presenter.kitLock) {
            WalletAppKit kit = presenter.walletAppKit;
            if (presenter.shuttingDown || !presenter.walletReady
                    || kit == null || !kit.isRunning()) {
                throw new IOException("wallet_not_ready");
            }
            try {
                kit.wallet().saveToFile(target);
            } catch (IllegalStateException error) {
                throw new IOException("wallet_not_ready", error);
            }
        }
    }

    public void saveWalletNow() throws IOException {
        saveActiveWalletToFile(walletFile);
    }

    private void setupWalletListeners(
            WalletAppKit ownerKit,
            Wallet wallet) {

        wallet.addCoinsReceivedEventListener(
                (wallet1, tx, prevBalance, newBalance) -> {
                    if (!isCurrentKit(ownerKit)) {
                        return;
                    }
                    WalletSelection.observeRequestNestedTransaction(wallet1, tx);
                    refresh();
                    notifyWalletUpdated();
                    if (tx.getPurpose() == Transaction.Purpose.UNKNOWN) {
                        try {
                            Coin received = newBalance.minus(prevBalance);
                            BitcoinSyncService.notifyReceived(
                                    applicationContext, received, tx);
                            runOnUi(() -> view.showToastMessage(
                                    text(R.string.receive_message,
                                            received.toFriendlyString())));
                        } catch (Exception ignored) {
                            // UI refresh remains the important part of the event.
                        }
                    }
                }
        );

        wallet.addCoinsSentEventListener(
                (wallet1, tx, prevBalance, newBalance) -> {
                    if (!isCurrentKit(ownerKit)) {
                        return;
                    }
                    refresh();
                    notifyWalletUpdated();
                    try {
                        Coin sent = prevBalance.minus(newBalance);
                        if (sent.isPositive()) {
                            BitcoinSyncService.notifySent(
                                    applicationContext, sent, tx);
                        }
                    } catch (Exception ignored) {
                        // The wallet refresh remains the important part of the event.
                    }
                }
        );

        wallet.addTransactionConfidenceEventListener(
                (wallet1, tx) -> {
                    if (!isCurrentKit(ownerKit)) {
                        return;
                    }
                    refresh();
                    notifyWalletUpdated();
                }
        );

        wallet.addChangeEventListener(
                wallet1 -> {
                    if (!isCurrentKit(ownerKit)) {
                        return;
                    }
                    refresh();
                    notifyWalletUpdated();
                }
        );
    }

    /**
     * Registers a lightweight UI callback. The callback is responsible for
     * switching back to its own UI thread if necessary.
     */
    public void addWalletUpdateListener(Runnable listener) {
        if (listener != null) {
            walletUpdateListeners.addIfAbsent(listener);
        }
    }

    public void removeWalletUpdateListener(Runnable listener) {
        if (listener != null) {
            walletUpdateListeners.remove(listener);
        }
    }

    public void addSyncStateListener(Runnable listener) {
        if (listener != null) {
            syncStateListeners.addIfAbsent(listener);
        }
    }

    public void removeSyncStateListener(Runnable listener) {
        if (listener != null) {
            syncStateListeners.remove(listener);
        }
    }

    private void notifySyncStateChanged() {
        for (Runnable listener : syncStateListeners) {
            try {
                listener.run();
            } catch (Exception e) {

            }
        }
    }

    public boolean isSyncing() {
        return startupInProgress || restartInProgress.get() || !downloadFinished;
    }

    public boolean isSyncStalled() {
        return !downloadFinished
                && walletAppKit != null
                && walletAppKit.isRunning()
                && System.currentTimeMillis() - lastProgressAt >= STALL_TIMEOUT_MS;
    }

    public int getSyncStatusResId() {
        if (restartInProgress.get()) {
            return R.string.sync_status_reconnecting;
        }
        if (!walletReady || walletAppKit == null) {
            return R.string.sync_status_starting;
        }
        if (getConnectedPeerCount() == 0) {
            return R.string.sync_status_no_peers;
        }
        if (isSyncStalled()) {
            return R.string.sync_status_stalled;
        }
        return isSyncing()
                ? R.string.sync_status_syncing
                : R.string.sync_status_live;
    }

    public boolean isWalletReady() {
        synchronized (kitLock) {
            try {
                return walletReady && walletAppKit != null && walletAppKit.isRunning();
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    public int getSyncPercent() {
        return Math.max(0, Math.min(100, lastPercent));
    }

    public int getCurrentBlock() {
        return safeChainHeight(walletAppKit);
    }

    /** Best chain height known by bitcoinj. */
    public int getBestChainHeight() {
        return safeChainHeight(walletAppKit);
    }

    public int getNetworkBlock() {
        return safePeerHeight(walletAppKit);
    }

    public int getBlocksBehind() {
        int best = getBestChainHeight();
        int network = getNetworkBlock();
        return best > 0 && network > 0 ? Math.max(0, network - best) : 0;
    }

    public int getConnectedPeerCount() {
        return safePeerCount(walletAppKit);
    }

    public int getPendingPeerCount() {
        try {
            PeerGroup peers = walletAppKit == null ? null : walletAppKit.peerGroup();
            return peers == null ? 0 : peers.getPendingPeers().size();
        } catch (Exception e) {
            return 0;
        }
    }

    public double getSyncBlocksPerSecond() {
        return syncBlocksPerSecond;
    }

    public String getBestChainHash() {
        updateBestChainSnapshot();
        return bestChainHash;
    }

    public long getBestChainTimeSeconds() {
        updateBestChainSnapshot();
        return bestChainTimeSeconds;
    }

    private void updateBestChainSnapshot() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) {
            return;
        }
        try {
            org.bitcoinj.core.StoredBlock head = kit.chain().getChainHead();
            if (head != null && head.getHeader() != null) {
                bestChainHash = head.getHeader().getHashAsString();
                bestChainTimeSeconds = head.getHeader().getTimeSeconds();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Called by the foreground sync service when Android reports that a network
     * is available again. A transient network loss does not stop WalletAppKit;
     * this method only repairs the peer lifecycle when the kit is running but
     * has no connected peers.
     */
    public void onNetworkAvailable() {
        if (shuttingDown || restartInProgress.get()) {
            return;
        }

        WalletAppKit kit = walletAppKit;
        if (kit == null) {
            startWalletKit();
            return;
        }

        try {
            if (!kit.isRunning()) {
                startWalletKit();
                return;
            }
            PeerGroup group = kit.peerGroup();
            if (group != null && !group.getConnectedPeers().isEmpty()) {
                noPeerSince = 0L;
                return;
            }
        } catch (Exception ignored) {
            // Treat an inaccessible/stale kit as a lifecycle recovery case.
        }

        noPeerSince = 0L;
        restartWalletKit("network became available");
    }

    public boolean isWalletKitRunning() {
        WalletAppKit kit = walletAppKit;
        try {
            return kit != null && kit.isRunning();
        } catch (Exception ignored) {
            return false;
        }
    }

    public String getPeerGroupDetails() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) {
            return "—";
        }
        try {
            PeerGroup group = kit.peerGroup();
            if (group == null) {
                return "—";
            }
            List<Peer> connected = group.getConnectedPeers();
            if (connected.isEmpty()) {
                return applicationContext.getString(R.string.sync_peers_none);
            }
            StringBuilder out = new StringBuilder();
            Peer downloadPeer = group.getDownloadPeer();
            for (int i = 0; i < connected.size(); i++) {
                Peer peer = connected.get(i);
                if (i > 0) out.append("\n\n");
                out.append(i + 1).append("  ")
                        .append(peer.getAddress() == null
                                ? applicationContext.getString(R.string.sync_peer_unknown)
                                : peer.getAddress().toString());
                out.append("\n").append(applicationContext.getString(
                        R.string.sync_peer_height, peer.getBestHeight()));
                out.append("  ").append(applicationContext.getString(
                        R.string.sync_peer_behind_ahead, peer.getPeerBlockHeightDifference()));
                out.append("\n").append(applicationContext.getString(
                        R.string.sync_peer_download,
                        peer.isDownloadData()
                                ? applicationContext.getString(R.string.sync_yes)
                                : applicationContext.getString(R.string.sync_no)));
                try {
                    if (peer.pingInterval().isPresent()) {
                        out.append("  ").append(applicationContext.getString(
                                R.string.sync_peer_ping, peer.pingInterval().get().toMillis()));
                    } else {
                        out.append("  ").append(applicationContext.getString(R.string.sync_peer_ping_unavailable));
                    }
                } catch (Exception ignored) {
                    out.append("  ").append(applicationContext.getString(R.string.sync_peer_ping_unavailable));
                }
                try {
                    if (peer.getFeeFilter() != null) {
                        out.append("\n").append(applicationContext.getString(
                                R.string.sync_peer_fee_filter, peer.getFeeFilter().toFriendlyString()));
                    }
                } catch (Exception ignored) {
                }
                if (peer == downloadPeer) {
                    out.append("\n").append(applicationContext.getString(R.string.sync_peer_role_download));
                }
            }
            return out.toString();
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public String getNetworkCapabilities() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) return "—";
        try {
            PeerGroup group = kit.peerGroup();
            if (group == null) return "—";
            return applicationContext.getString(
                    R.string.sync_capabilities_details,
                    group.getMaxConnections(),
                    group.getMinBroadcastConnections(),
                    group.getMinRequiredProtocolVersion(),
                    group.getPingIntervalMsec(),
                    group.isBloomFilteringEnabled()
                            ? applicationContext.getString(R.string.sync_enabled)
                            : applicationContext.getString(R.string.sync_disabled),
                    group.getFastCatchupTime());
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public String getChainTechnicalDetails() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) return "—";
        try {
            org.bitcoinj.core.StoredBlock head = kit.chain().getChainHead();
            if (head == null || head.getHeader() == null) return "—";
            org.bitcoinj.core.Block header = head.getHeader();
            return applicationContext.getString(
                    R.string.sync_chain_technical_details,
                    head.getChainWork(),
                    header.getVersion(),
                    header.getNonce(),
                    Long.toUnsignedString(header.getDifficultyTarget()));
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public String getChainMerkleRoot() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) return "—";
        try {
            org.bitcoinj.core.StoredBlock head = kit.chain().getChainHead();
            if (head == null || head.getHeader() == null || head.getHeader().getMerkleRoot() == null) return "—";
            return head.getHeader().getMerkleRoot().toString();
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public String getWalletChainState() {
        try {
            Wallet wallet = getActiveWallet();
            if (wallet == null) return "—";
            return applicationContext.getString(
                    R.string.sync_wallet_chain_state,
                    wallet.getLastBlockSeenHeight(),
                    wallet.getPendingTransactions().size());
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public int getWalletLastSeenHeight() {
        try {
            Wallet wallet = getActiveWallet();
            if (wallet == null) {
                return 0;
            }
            return wallet.getLastBlockSeenHeight();
        } catch (Exception ignored) {
            return 0;
        }
    }

    public String getDownloadPeerDetails() {
        WalletAppKit kit = walletAppKit;
        if (kit == null) {
            return "—";
        }
        try {
            PeerGroup group = kit.peerGroup();
            if (group == null) {
                return "—";
            }
            Peer peer = group.getDownloadPeer();
            if (peer == null) {
                return applicationContext.getString(R.string.sync_none);
            }
            String address = peer.getAddress() == null
                    ? applicationContext.getString(R.string.sync_peer_unknown)
                    : peer.getAddress().toString();
            return applicationContext.getString(
                    R.string.sync_download_peer_details,
                    address,
                    peer.getBestHeight());
        } catch (Exception e) {
            return applicationContext.getString(R.string.sync_unavailable);
        }
    }

    public int getAutoRestartCount() {
        return autoRestartCount;
    }

    public String getNetworkName() {
        return NetworkConfig.displayName(applicationContext, activeNetwork);
    }

    public BitcoinNetwork getActiveNetwork() {
        return activeNetwork;
    }

    public boolean isNetworkSwitching() {
        return networkSwitchInProgress;
    }

    public boolean isSyncModeSwitching() {
        return syncModeSwitchInProgress;
    }

    public NetworkConfig.SyncMode getSyncMode() {
        return NetworkConfig.getSyncMode(applicationContext, activeNetwork);
    }

    public void switchSyncMode(final NetworkConfig.SyncMode newMode) {
        if (newMode == null || newMode == getSyncMode()) {
            return;
        }
        if (shuttingDown || startupInProgress || networkSwitchInProgress
                || syncModeSwitchInProgress
                || !restartInProgress.compareAndSet(false, true)) {
            runOnUi(() -> view.showToastMessage(text(R.string.sync_mode_switch_busy)));
            return;
        }

        syncModeSwitchInProgress = true;
        walletReady = false;
        downloadFinished = false;
        notifySyncStateChanged();

        new Thread(() -> {
            try {
                WalletAppKit oldKit;
                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    activeDownloadListener = null;
                }

                if (oldKit != null) {
                    try {
                        oldKit.stopAsync().awaitTerminated();
                    } catch (Exception ignored) {
                    }
                }

                NetworkConfig.setSyncMode(applicationContext, activeNetwork, newMode);
                autoRestartCount = 0;
                lastPercent = -1;
                syncProgressStartBlock = -1;
                syncProgressTargetBlock = -1;
                lastChainHeight = -1;
                lastProgressAt = System.currentTimeMillis();
                lastProgressUiUpdateAt = 0L;
                lastSyncNotificationUpdateAt = 0L;
                lastSyncNotificationPercent = -1;
                noPeerSince = 0L;
                lastDownloadKickAt = 0L;
                downloadFinished = false;

                runOnUi(() -> view.showToastMessage(
                        text(R.string.sync_mode_switching,
                                modeDisplayName(newMode))));

                startWalletKit();
                startWatchdog();
            } finally {
                syncModeSwitchInProgress = false;
                restartInProgress.set(false);
                notifySyncStateChanged();
            }
        }, "bitcoinj-sync-mode-switch").start();
    }

    private String modeDisplayName(NetworkConfig.SyncMode mode) {
        return applicationContext.getString(
                mode == NetworkConfig.SyncMode.FULL
                        ? R.string.sync_mode_full
                        : R.string.sync_mode_lite);
    }

    public void switchNetwork(final BitcoinNetwork newNetwork) {
        if (newNetwork == null
                || (newNetwork != BitcoinNetwork.MAINNET
                && newNetwork != BitcoinNetwork.SIGNET)
                || newNetwork == activeNetwork) {
            return;
        }
        if (shuttingDown || startupInProgress || networkSwitchInProgress
                || syncModeSwitchInProgress
                || !restartInProgress.compareAndSet(false, true)) {
            runOnUi(() -> view.showToastMessage(text(R.string.sync_network_switch_busy)));
            return;
        }

        networkSwitchInProgress = true;
        walletReady = false;
        downloadFinished = false;
        notifySyncStateChanged();

        new Thread(() -> {
            try {
                WalletAppKit oldKit;
                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    activeDownloadListener = null;
                }
                if (oldKit != null) {
                    try {
                        oldKit.stopAsync().awaitTerminated();
                    } catch (Exception ignored) {
                    }
                }
                WalletSecurity.clearSessionKey();

                activeNetwork = newNetwork;
                NetworkConfig.set(applicationContext, newNetwork);
                parameters = NetworkConfig.parameters(newNetwork);
                walletDataDir = NetworkConfig.walletDirectory(applicationContext, newNetwork);
                walletFile = new File(walletDataDir, Constants.WALLET_NAME + ".wallet");

                autoRestartCount = 0;
                lastPercent = -1;
                syncProgressStartBlock = -1;
                syncProgressTargetBlock = -1;
                lastChainHeight = -1;
                lastProgressAt = System.currentTimeMillis();
                noPeerSince = 0L;
                downloadFinished = false;
                shuttingDown = false;

                runOnUi(() -> view.showToastMessage(
                        text(R.string.sync_network_switching,
                                NetworkConfig.displayName(applicationContext, newNetwork))));

                startWalletKit();
                startWatchdog();
            } finally {
                restartInProgress.set(false);
                notifySyncStateChanged();
            }
        }, "bitcoinj-network-switch").start();
    }

    public void reconnectNow() {
        restartWalletKit("manual reconnect");
    }

    private void notifyWalletUpdated() {
        wallet.widget.BalanceWidgetProvider.requestRefresh(applicationContext);
        for (Runnable listener : walletUpdateListeners) {
            try {
                listener.run();
            } catch (Exception e) {

            }
        }
    }

    private boolean isCurrentKit(WalletAppKit candidate) {
        if (candidate == null) {
            return false;
        }
        synchronized (kitLock) {
            return !shuttingDown && walletAppKit == candidate;
        }
    }

    /**
     * Listens to blocks arriving after the initial download as well as during
     * catch-up. This is the important realtime path: WalletAppKit continues
     * receiving blocks even when MainActivity is not visible.
     */
    private void setupPeerListeners(WalletAppKit ownerKit, PeerGroup peerGroup) {
        if (peerGroup == null) {
            return;
        }

        peerGroup.addBlocksDownloadedEventListener(
                (peer, block, filteredBlock, blocksLeft) -> {
                    if (shuttingDown || !isCurrentKit(ownerKit)) {
                        return;
                    }

                    int chainHeight = safeChainHeight(ownerKit);
                    int peerHeight = safePeerHeight(ownerKit);

                    if (chainHeight > lastChainHeight) {
                        lastChainHeight = chainHeight;
                    }

                    long now = System.currentTimeMillis();
                    if (chainHeight > 0 && chainHeight > lastRateHeight && lastBlockEventAt > 0L) {
                        long elapsed = now - lastBlockEventAt;
                        if (elapsed > 0L) {
                            double instantRate = (chainHeight - lastRateHeight) * 1000.0 / elapsed;
                            syncBlocksPerSecond = syncBlocksPerSecond == 0.0
                                    ? instantRate
                                    : (syncBlocksPerSecond * 0.7) + (instantRate * 0.3);
                        }
                    }
                    if (chainHeight > lastRateHeight) {
                        lastRateHeight = chainHeight;
                        lastBlockEventAt = now;
                    }
                    try {
                        org.bitcoinj.core.StoredBlock head = ownerKit.chain().getChainHead();
                        if (head != null && head.getHeader() != null) {
                            bestChainHash = head.getHeader().getHashAsString();
                            bestChainTimeSeconds = head.getHeader().getTimeSeconds();
                        }
                    } catch (Exception ignored) {
                    }
                    touchProgress();

                    final boolean liveSynced = downloadFinished;
                    int percent = liveSynced
                            ? 100
                            : calculateSessionProgress(chainHeight, peerHeight);
                    if (!liveSynced && percent != lastPercent) {
                        lastPercent = percent;
                        notifySyncStateChanged();
                    }

                    long notificationNow = System.currentTimeMillis();
                    boolean notificationPercentChanged = percent != lastSyncNotificationPercent;
                    if (notificationPercentChanged
                            || notificationNow - lastSyncNotificationUpdateAt >= SYNC_NOTIFICATION_UPDATE_MS) {
                        lastSyncNotificationUpdateAt = notificationNow;
                        lastSyncNotificationPercent = percent;
                        BitcoinSyncService.updateSyncNotification(
                                applicationContext,
                                percent,
                                chainHeight,
                                peerHeight,
                                !liveSynced);
                    }

                    // Once caught up, every newly accepted block is a wallet UI
                    // refresh point. During historical sync, DownloadProgressTracker
                    // already drives the progress/UI updates and we avoid a refresh
                    // for every historical block.
                    if (liveSynced || blocksLeft == 0) {
                        refresh();
                        notifyWalletUpdated();
                    }
                });
    }

    /** Minimal non-UI view used while the foreground sync service owns the presenter. */
    private static final class HeadlessView implements MainActivityContract.MainActivityView {
        private final android.content.Context context;

        HeadlessView(android.content.Context context) {
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
        @Override public void displayTransactions(List<TransactionItem> transactions) { }
        @Override public void showToastMessage(String message) { }
        @Override public android.content.Context getActivityContext() { return context; }
    }

    /** Snapshot used by the home-screen balance widget. */
    public static final class WidgetSnapshot {
        public final boolean available;
        public final String balance;
        public final String availableBalance;
        public final String pendingBalance;
        public final String networkLabel;
        public final String receiveAddress;

        public WidgetSnapshot(
                boolean available,
                String balance,
                String availableBalance,
                String pendingBalance,
                String networkLabel,
                String receiveAddress) {
            this.available = available;
            this.balance = balance;
            this.availableBalance = availableBalance;
            this.pendingBalance = pendingBalance;
            this.networkLabel = networkLabel;
            this.receiveAddress = receiveAddress;
        }

        public static WidgetSnapshot unavailable(
                String balance,
                String networkLabel) {
            return new WidgetSnapshot(
                    false,
                    balance,
                    balance,
                    balance,
                    networkLabel,
                    null);
        }
    }

    /**
     * Returns the same wallet-scoped balance used by MainActivity, without any
     * online API. A running presenter is preferred so the widget sees live
     * bitcoinj state; otherwise a local wallet-file snapshot is used.
     */
    public static WidgetSnapshot getWidgetSnapshot(android.content.Context context) {
        if (context == null) {
            return WidgetSnapshot.unavailable("--", "");
        }

        android.content.Context app = context.getApplicationContext();
        MainActivityPresenter presenter = activePresenter;
        if (presenter != null) {
            synchronized (presenter.kitLock) {
                WalletAppKit kit = presenter.walletAppKit;
                if (presenter.walletReady && kit != null && kit.isRunning()) {
                    try {
                        Context.propagate(Context.getOrCreate(presenter.parameters));
                        Wallet liveWallet = kit.wallet();
                        return presenter.buildWidgetSnapshot(app, liveWallet, presenter.parameters);
                    } catch (Exception ignored) {
                        // Fall through to the local wallet-file snapshot only when the
                        // live wallet cannot be read.
                    }
                }
            }
        }

        BitcoinNetwork network = NetworkConfig.get(app);
        NetworkParameters params = NetworkConfig.parameters(network);
        File walletFile = new File(
                NetworkConfig.walletDirectory(app, network),
                Constants.WALLET_NAME + ".wallet");
        if (!walletFile.isFile()) {
            return WidgetSnapshot.unavailable(
                    "--",
                    NetworkConfig.displayName(app, network));
        }

        try {
            Context.propagate(Context.getOrCreate(params));
            Wallet wallet = Wallet.loadFromFile(walletFile);
            return presenter != null
                    ? presenter.buildWidgetSnapshot(app, wallet, params)
                    : buildWidgetSnapshotStatic(app, wallet, params);
        } catch (Exception ignored) {
            return WidgetSnapshot.unavailable(
                    "--",
                    NetworkConfig.displayName(app, network));
        }
    }

    private WidgetSnapshot buildWidgetSnapshot(
            android.content.Context context,
            Wallet wallet,
            NetworkParameters walletParameters) {
        return buildWidgetSnapshotStatic(context, wallet, walletParameters);
    }

    private static WidgetSnapshot buildWidgetSnapshotStatic(
            android.content.Context context,
            Wallet wallet,
            NetworkParameters walletParameters) {
        BitcoinNetwork network = NetworkConfig.get(context);

        Script selectedWatchScript = WalletSelection.findSelectedScript(context, wallet);
        Script selectedImportedScript = selectedWatchScript == null
                ? WalletSelection.findSelectedImportedScript(context, wallet)
                : null;

        Coin balance;
        Coin available;
        Coin pending;
        String address;

        if (selectedWatchScript != null || selectedImportedScript != null) {
            Script displayScript = selectedWatchScript != null
                    ? selectedWatchScript : selectedImportedScript;
            long confirmedSat = 0L;
            long pendingSat = 0L;
            Iterable<TransactionOutput> selectedOutputs;
            if (selectedWatchScript != null) {
                selectedOutputs = wallet.getWatchedOutputs(false);
            } else {
                selectedOutputs = WalletSelection.selectedImportedUsesWatchedOutputs(context)
                        ? wallet.getWatchedOutputs(false) : wallet.getUnspents();
            }

            for (TransactionOutput output : selectedOutputs) {
                if (!displayScript.equals(output.getScriptPubKey())
                        || !output.isAvailableForSpending()) {
                    continue;
                }
                if (output.getParentTransactionDepthInBlocks() > 0) {
                    confirmedSat += output.getValue().value;
                } else {
                    pendingSat += output.getValue().value;
                }
            }
            available = Coin.valueOf(confirmedSat);
            pending = Coin.valueOf(pendingSat);
            balance = available.add(pending);
            address = WalletSelection.addressForScript(displayScript, walletParameters);
        } else {
            // Keep the widget aligned with MainActivity's wallet-scoped balance.
            balance = WalletSelection.mainEstimatedBalance(context, wallet);
            available = WalletSelection.mainAvailableBalance(context, wallet);
            pending = balance.subtract(available);
            address = wallet.currentReceiveAddress().toString();
        }

        String networkLabel = context.getString(
                network == BitcoinNetwork.SIGNET
                        ? R.string.network_badge_signet
                        : R.string.network_badge_mainnet);

        return new WidgetSnapshot(
                true,
                balance.toFriendlyString(),
                available.toFriendlyString(),
                pending.toFriendlyString(),
                networkLabel,
                address);
    }

    public static MainActivityPresenter getActivePresenter() {
        return activePresenter;
    }

    /**
     * Returns a wallet snapshot handle only while WalletAppKit is fully running.
     * The critical wallet() call is made while kitLock is held, preventing the
     * common stop/restart race that can throw
     * "cannot call until startup is complete".
     */
    public static Wallet getActiveWallet() {
        MainActivityPresenter presenter = activePresenter;
        if (presenter == null || !presenter.walletReady) {
            return null;
        }

        synchronized (presenter.kitLock) {
            WalletAppKit kit = presenter.walletAppKit;
            if (kit == null || !kit.isRunning()) {
                return null;
            }
            try {
                return kit.wallet();
            } catch (IllegalStateException ignored) {
                return null;
            }
        }
    }

    /**
     * Commits and broadcasts a transaction while the active WalletAppKit lifecycle
     * lock is held. This prevents a stop/restart from invalidating the kit between
     * wallet.commitTx() and peerGroup().broadcastTransaction().
     */
    public static org.bitcoinj.core.TransactionBroadcast commitAndBroadcastTransaction(
            org.bitcoinj.core.Transaction transaction) throws Exception {
        if (transaction == null) {
            throw new IllegalArgumentException("transaction == null");
        }

        MainActivityPresenter presenter = activePresenter;
        if (presenter == null || !presenter.walletReady) {
            throw new IllegalStateException("wallet_not_ready");
        }

        synchronized (presenter.kitLock) {
            WalletAppKit kit = presenter.walletAppKit;
            if (kit == null || !kit.isRunning()) {
                throw new IllegalStateException("wallet_not_ready");
            }

            Wallet wallet;
            try {
                wallet = kit.wallet();
            } catch (IllegalStateException error) {
                throw new IllegalStateException("wallet_not_ready", error);
            }

            wallet.commitTx(transaction);
            org.bitcoinj.core.TransactionBroadcast broadcast =
                    kit.peerGroup().broadcastTransaction(transaction);
            broadcast.broadcast();
            return broadcast;
        }
    }

    public static File getActiveWalletFile() {
        MainActivityPresenter presenter = activePresenter;
        return presenter == null ? null : presenter.walletFile;
    }

    public static NetworkParameters getActiveParameters() {
        MainActivityPresenter presenter = activePresenter;
        return presenter == null ? null : presenter.parameters;
    }

    private void runOnUi(
            Runnable r) {

        if (Looper.myLooper()
                == Looper.getMainLooper()) {

            r.run();

        } else {

            mainHandler.post(r);
        }
    }

}
