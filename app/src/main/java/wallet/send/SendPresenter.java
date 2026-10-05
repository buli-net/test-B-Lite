package wallet.send;

import android.text.TextUtils;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import wallet.security.WalletSecurity;
import wallet.tools.CoinControl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import wallet.main.MainActivityPresenter;
import wallet.main.WalletSelection;
import wallet.main.ImportedWalletStore;
import wallet.main.WalletAddressType;
import wallet.transaction.RbfMetadata;
import wallet.main.R;

/** Owns send validation, transaction preparation, review state, and broadcast state. */
public final class SendPresenter {

    private static final long RBF_SEQUENCE = 0xfffffffdL;

    public static final int MIN_FEE_SAT_VB = 1;
    public static final int MAX_FEE_SAT_VB = 10;

    private static final AtomicBoolean SEND_IN_PROGRESS = new AtomicBoolean(false);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static final Object STATE_LOCK = new Object();

    private enum State {
        IDLE,
        PREPARING,
        WAITING_CONFIRMATION,
        BROADCASTING
    }

    private static volatile View view;
    private final ExecutorService executor = EXECUTOR;
    private static final AtomicInteger operationId = new AtomicInteger();
    private final AtomicInteger summaryId = new AtomicInteger();
    private final AtomicInteger maxRequestId = new AtomicInteger();

    private static volatile State state = State.IDLE;
    private static volatile boolean viewActive;

    private static SendRequest pendingRequest;
    private static Coin pendingAmount;
    private static SendTransactionPreview pendingPreview;
    private static long confirmationDeadlineMs;
    private static ScheduledFuture<?> autoConfirmFuture;
    private static WalletAppKit pendingWalletAppKit;
    private static NetworkParameters pendingParameters;

    public interface View {
        String recipient();

        String amount();

        int feeSatVb();

        boolean replaceByFee();

        void showMessage(String message);

        void showPreparing(boolean preparing);

        void showReview(SendTransactionPreview preview, long remainingMs);

        void showSending(boolean sending);

        void showWalletBalance(Coin balance);

        void showMaxAmount(Coin amount);

        void showSummaryPending(Coin balance);

        android.content.Context getActivityContext();

        String getStringResource(int resId, Object... formatArgs);

        void requestSendUnlock(Wallet wallet);
    }

    private SendPresenter() {
    }

    public SendPresenter(View view) {
        attachView(view);
    }

    private void attachView(View newView) {
        viewActive = true;
        view = newView;
        renderCurrentState();
    }

    private void renderCurrentState() {
        View current = view;
        if (current == null) {
            return;
        }

        State currentState = state;
        if (currentState == State.PREPARING) {
            current.showPreparing(true);
        } else if (currentState == State.WAITING_CONFIRMATION && pendingPreview != null) {
            long remaining = Math.max(0L, confirmationDeadlineMs - android.os.SystemClock.elapsedRealtime());
            if (remaining == 0L) {
                confirmSend();
            } else {
                current.showReview(pendingPreview, remaining);
            }
        } else if (currentState == State.BROADCASTING) {
            current.showSending(true);
        }
    }

    public void refreshWalletSummary() {
        maxRequestId.incrementAndGet();
        final int requestId = summaryId.incrementAndGet();
        final WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();

        if (walletAppKit == null) {
            if (viewActive) {
                view.showSummaryPending(null);
            }
            return;
        }

        executor.execute(() -> {
            try {
                Wallet wallet = walletAppKit.wallet();
                if (WalletSelection.findSelectedScript(
                        view.getActivityContext(), wallet) != null) {
                    return;
                }
                Coin balance = WalletSelection.mainAvailableBalance(wallet);
                if (requestId != summaryId.get() || !viewActive || state != State.IDLE) {
                    return;
                }
                view.showWalletBalance(balance);
            } catch (Exception error) {
                if (requestId == summaryId.get() && viewActive && state == State.IDLE) {
                    view.showSummaryPending(null);
                }
            }
        });
    }

    public void fillMax() {
        final int requestId = maxRequestId.incrementAndGet();
        final WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();
        final NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
        final String recipientText = view.recipient().trim();
        final int feeSatVb = view.feeSatVb();
        final boolean replaceByFee = view.replaceByFee();

        if (walletAppKit == null || parameters == null) {
            view.showMessage(view.getStringResource(R.string.wallet_not_ready));
            return;
        }
        try {
            if (WalletSelection.findSelectedScript(
                    view.getActivityContext(), walletAppKit.wallet()) != null) {
                view.showMessage(view.getStringResource(
                        R.string.watch_send_read_only_message));
                return;
            }
        } catch (Exception error) {
            view.showMessage(view.getStringResource(R.string.wallet_not_ready));
            return;
        }
        if (TextUtils.isEmpty(recipientText)) {
            view.showMessage(view.getStringResource(R.string.select_recipient));
            return;
        }
        if (feeSatVb < MIN_FEE_SAT_VB || feeSatVb > MAX_FEE_SAT_VB) {
            view.showMessage(view.getStringResource(R.string.select_valid_fee_rate));
            return;
        }

        executor.execute(() -> {
            Context.propagate(Context.getOrCreate(parameters));
            try {
                if (WalletSecurity.isEncrypted(walletAppKit.wallet())
                        && !WalletSecurity.isSessionValid(walletAppKit.wallet())) {
                    throw new IllegalStateException(
                            view.getStringResource(R.string.wallet_locked));
                }
                Address destination = Address.fromString(parameters, recipientText);
                Wallet wallet = walletAppKit.wallet();
                SendRequest request = SendRequest.emptyWallet(destination);
                request.aesKey = WalletSecurity.getSessionKey();
                request.setFeePerVkb(Coin.valueOf(feeSatVb * 1000L));
                request.ensureMinRequiredFee = true;
                request.coinSelector = CoinControl.selector(WalletSelection.mainCoinSelector(view.getActivityContext(), wallet));
                wallet.completeTx(request);

                if (replaceByFee) {
                    enableReplaceByFee(wallet, request);
                }

                if (request.tx.getOutputs().isEmpty()) {
                    throw new IllegalStateException(
                            view.getStringResource(R.string.max_amount_unavailable));
                }

                Coin maxAmount = request.tx.getOutput(0).getValue();
                if (!viewActive
                        || state != State.IDLE
                        || requestId != maxRequestId.get()) {
                    return;
                }
                view.showMaxAmount(maxAmount);
            } catch (InsufficientMoneyException error) {
                if (viewActive) {
                    view.showMessage(
                            view.getStringResource(
                                    R.string.insufficient_balance,
                                    walletBalance(walletAppKit)));
                }
            } catch (Exception error) {
                if (viewActive) {
                    view.showMessage(
                            view.getStringResource(
                                    R.string.max_amount_failed,
                                    error.getMessage() == null
                                            ? error.getClass().getSimpleName()
                                            : error.getMessage()));
                }
            }
        });
    }

    public void prepareSend() {
        if (!SEND_IN_PROGRESS.compareAndSet(false, true)) {
            view.showMessage(view.getStringResource(R.string.send_already_pending));
            return;
        }

        final WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();
        final NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
        if (walletAppKit == null || parameters == null) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.wallet_not_ready));
            return;
        }
        try {
            if (WalletSelection.findSelectedScript(
                    view.getActivityContext(), walletAppKit.wallet()) != null) {
                releaseSend();
                view.showMessage(view.getStringResource(
                        R.string.watch_send_read_only_message));
                return;
            }
        } catch (Exception error) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.wallet_not_ready));
            return;
        }

        final String recipient = view.recipient().trim();
        final String amountText = view.amount().trim();
        final int feeSatVb = view.feeSatVb();
        final boolean replaceByFee = view.replaceByFee();

        if (TextUtils.isEmpty(recipient)) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.select_recipient));
            return;
        }
        if (TextUtils.isEmpty(amountText)) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.select_valid_amount));
            return;
        }
        if (feeSatVb < MIN_FEE_SAT_VB || feeSatVb > MAX_FEE_SAT_VB) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.select_valid_fee_rate));
            return;
        }

        final Coin amount;
        try {
            amount = Coin.parseCoin(amountText);
            if (!amount.isPositive()) {
                releaseSend();
                view.showMessage(view.getStringResource(R.string.select_valid_amount));
                return;
            }
        } catch (Exception error) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.select_valid_amount));
            return;
        }

        Wallet wallet = walletAppKit.wallet();
        if (WalletSecurity.isEncrypted(wallet) && !WalletSecurity.isSessionValid(wallet)) {
            releaseSend();
            view.requestSendUnlock(wallet);
            return;
        }

        final int operation = operationId.incrementAndGet();
        state = State.PREPARING;
        view.showPreparing(true);

        executor.execute(() -> prepareTransaction(
                operation,
                walletAppKit,
                parameters,
                recipient,
                amount,
                feeSatVb,
                replaceByFee));
    }

    private void prepareTransaction(
            int operation,
            WalletAppKit walletAppKit,
            NetworkParameters parameters,
            String recipientText,
            Coin amount,
            int feeSatVb,
            boolean replaceByFee) {
        Context.propagate(Context.getOrCreate(parameters));

        try {
            Wallet wallet = walletAppKit.wallet();
            String selectedImportedAddress = WalletSelection.getSelectedImportedAddress(view.getActivityContext());
            if (selectedImportedAddress != null) {
                String importedType = ImportedWalletStore.getAddressType(
                        view.getActivityContext(), selectedImportedAddress);
                if (!WalletAddressType.isNativelySpendable(importedType)) {
                    throw new IllegalStateException(
                            view.getStringResource(R.string.imported_wallet_type_not_spendable));
                }
            }
            Script importedScript = WalletSelection.findSelectedImportedScript(view.getActivityContext(), wallet);
            Coin balance = importedScript == null
                    ? WalletSelection.mainAvailableBalance(wallet)
                    : WalletSelection.selectedImportedAvailableBalance(view.getActivityContext(), wallet, importedScript);
            Address destination = Address.fromString(parameters, recipientText);

            SendRequest request = SendRequest.to(destination, amount);
            request.aesKey = WalletSecurity.getSessionKey();
            if (WalletSecurity.isEncrypted(wallet) && !WalletSecurity.isSessionValid(wallet)) {
                throw new IllegalStateException(
                        view.getStringResource(R.string.wallet_locked));
            }
            request.setFeePerVkb(Coin.valueOf(feeSatVb * 1000L));
            request.ensureMinRequiredFee = true;
            if (importedScript != null) {
                request.changeAddress = Address.fromString(
                        parameters, WalletSelection.getSelectedImportedAddress(view.getActivityContext()));
            }
            request.coinSelector = CoinControl.selector(WalletSelection.mainCoinSelector(view.getActivityContext(), wallet));
            wallet.completeTx(request);

            if (replaceByFee) {
                enableReplaceByFee(wallet, request);
            }

            Coin actualFee = request.tx.getFee();
            if (actualFee == null) {
                throw new IllegalStateException(
                        view.getStringResource(R.string.transaction_fee_unavailable));
            }

            Coin totalDebit = amount.add(actualFee);
            if (totalDebit.isGreaterThan(balance)) {
                throw new InsufficientMoneyException(totalDebit.subtract(balance));
            }

            Coin remainingBalance = balance.subtract(totalDebit);

            SendTransactionPreview preview = new SendTransactionPreview(
                    balance,
                    amount,
                    actualFee,
                    totalDebit,
                    remainingBalance,
                    feeSatVb,
                    replaceByFee,
                    recipientText,
                    request.tx.bitcoinSerialize().length);

            synchronized (STATE_LOCK) {
                if (operation != operationId.get()
                        || state != State.PREPARING
                        || !SEND_IN_PROGRESS.get()) {
                    return;
                }
                pendingRequest = request;
                pendingAmount = amount;
                pendingWalletAppKit = walletAppKit;
                pendingParameters = parameters;
                pendingPreview = preview;
                confirmationDeadlineMs = android.os.SystemClock.elapsedRealtime() + 60_000L;
                state = State.WAITING_CONFIRMATION;
            }

            View current = view;
            if (current != null) {
                current.showPreparing(false);
                current.showReview(preview, 60_000L);
            }
            scheduleAutoConfirm(operation);
        } catch (InsufficientMoneyException error) {
            if (operation != operationId.get() || state != State.PREPARING) {
                return;
            }
            failPreparation(operation, R.string.insufficient_balance, walletBalance(walletAppKit));
        } catch (Wallet.DustySendRequested error) {
            failPreparation(operation, R.string.dust_amount);
        } catch (Exception error) {
            if (operation != operationId.get() || state != State.PREPARING) {
                return;
            }

            clearPendingState();
            if (viewActive) {
                view.showPreparing(false);
                view.showMessage(
                        view.getStringResource(
                                R.string.send_failed,
                                error.getMessage() == null
                                        ? error.getClass().getSimpleName()
                                        : error.getMessage()));
            }
        }
    }

    private void failPreparation(int operation, int messageId, Object... args) {
        if (operation != operationId.get() || state != State.PREPARING) {
            return;
        }
        clearPendingState();
        if (viewActive) {
            view.showPreparing(false);
            view.showMessage(view.getStringResource(messageId, args));
        }
    }

    private void enableReplaceByFee(Wallet wallet, SendRequest request) {
        request.tx.setVersion(2);

        for (int i = 0; i < request.tx.getInputs().size(); i++) {
            TransactionInput input = request.tx.getInput(i);
            TransactionInput replacement = new TransactionInput(
                    request.tx,
                    new byte[0],
                    input.getOutpoint(),
                    RBF_SEQUENCE,
                    input.getValue(),
                    null);
            request.tx.replaceInput(i, replacement);
        }

        wallet.signTransaction(request);
        if (!request.tx.isOptInFullRBF()) {
            throw new IllegalStateException(
                    view.getStringResource(R.string.rbf_enable_failed));
        }
    }

    public void confirmSend() {
        final SendRequest request;
        final WalletAppKit walletAppKit;
        final NetworkParameters parameters;
        final Coin amount;

        synchronized (STATE_LOCK) {
            if (state != State.WAITING_CONFIRMATION) {
                return;
            }
            request = pendingRequest;
            amount = pendingAmount;
            walletAppKit = pendingWalletAppKit;
            parameters = pendingParameters;
            state = State.BROADCASTING;
        }

        if (request == null || amount == null || walletAppKit == null || parameters == null) {
            clearPendingState();
            if (viewActive) {
                view.showMessage(view.getStringResource(R.string.send_not_ready));
            }
            return;
        }

        View current = view;
        if (current != null) {
            current.showSending(true);
        }

        executor.execute(() -> broadcast(walletAppKit, request, amount, parameters));
    }

    public void cancelPendingSend() {
        synchronized (STATE_LOCK) {
            if (state == State.BROADCASTING) {
                return;
            }
            operationId.incrementAndGet();
            clearPendingStateLocked();
        }
    }

    public void onViewDestroyed(View destroyedView, boolean configurationChange) {
        if (view == destroyedView) {
            viewActive = false;
            view = null;
        }
        if (!configurationChange) {
            synchronized (STATE_LOCK) {
                if (state != State.BROADCASTING) {
                    operationId.incrementAndGet();
                    clearPendingStateLocked();
                }
            }
        }
    }

    private void scheduleAutoConfirm(int operation) {
        synchronized (STATE_LOCK) {
            if (autoConfirmFuture != null) {
                autoConfirmFuture.cancel(false);
            }
            long remaining = Math.max(
                    0L,
                    confirmationDeadlineMs - android.os.SystemClock.elapsedRealtime());
            autoConfirmFuture = SCHEDULER.schedule(() -> {
                synchronized (STATE_LOCK) {
                    if (operation != operationId.get() || state != State.WAITING_CONFIRMATION) {
                        return;
                    }
                }
                confirmSend();
            }, remaining, TimeUnit.MILLISECONDS);
        }
    }

    private void broadcast(
            WalletAppKit walletAppKit,
            SendRequest request,
            Coin amount,
            NetworkParameters parameters) {
        Context.propagate(Context.getOrCreate(parameters));

        try {
            Wallet wallet = walletAppKit.wallet();
            synchronized (wallet) {
                String selectedImportedAddress = WalletSelection.getSelectedImportedAddress(view.getActivityContext());
                if (selectedImportedAddress != null) {
                    String importedType = ImportedWalletStore.getAddressType(
                            view.getActivityContext(), selectedImportedAddress);
                    if (!WalletAddressType.isNativelySpendable(importedType)) {
                        throw new IllegalStateException(
                                view.getStringResource(R.string.imported_wallet_type_not_spendable));
                    }
                }
                Script importedScript = WalletSelection.findSelectedImportedScript(
                        view.getActivityContext(), wallet);
                Coin balance = importedScript == null
                        ? WalletSelection.mainAvailableBalance(wallet)
                        : WalletSelection.selectedImportedAvailableBalance(view.getActivityContext(), wallet, importedScript);
                Coin fee = request.tx.getFee();
                if (fee == null) {
                    throw new IllegalStateException(
                            view.getStringResource(R.string.transaction_fee_unavailable));
                }
                if (amount.add(fee).isGreaterThan(balance)) {
                    throw new InsufficientMoneyException(amount.add(fee).subtract(balance));
                }

                wallet.commitTx(request.tx);
                if (request.tx.isOptInFullRBF()) {
                    int changeIndex = findChangeIndex(request.tx, request.changeAddress);
                    if (changeIndex >= 0) {
                        RbfMetadata.recordCreated(view.getActivityContext(), request.tx.getTxId().toString(), changeIndex);
                    }
                }
                org.bitcoinj.core.TransactionBroadcast broadcast =
                        walletAppKit.peerGroup()
                                .broadcastTransaction(request.tx);
                broadcast.broadcast();

                boolean relayed = false;
                try {
                    broadcast.awaitRelayed().get(15, TimeUnit.SECONDS);
                    relayed = true;
                } catch (TimeoutException ignored) {
                    // Relay confirmation is advisory; broadcast completion is already known.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }

                final int peers = request.tx.getConfidence() == null
                        ? 0
                        : request.tx.getConfidence().numBroadcastPeers();
                final boolean finalRelayed = relayed;

                CoinControl.clear();
                clearPendingState();
                if (viewActive) {
                    view.showSending(false);
                    String message = view.getStringResource(
                            R.string.transaction_broadcast,
                            request.tx.getFee().toFriendlyString());
                    message += " " + view.getStringResource(
                            R.string.broadcast_peers, peers);
                    if (finalRelayed) {
                        message += " " + view.getStringResource(
                                R.string.broadcast_relayed);
                    }
                    view.showMessage(message);
                    view.showWalletBalance(WalletSelection.mainAvailableBalance(walletAppKit.wallet()));
                }
            }
        } catch (InsufficientMoneyException error) {
            clearPendingState();
            if (viewActive) {
                view.showSending(false);
                view.showMessage(
                        view.getStringResource(
                                R.string.insufficient_balance,
                                walletBalance(walletAppKit)));
            }
        } catch (Exception error) {
            clearPendingState();
            if (viewActive) {
                view.showSending(false);
                view.showMessage(
                        view.getStringResource(
                                R.string.broadcast_failed,
                                error.getMessage() == null
                                        ? error.getClass().getSimpleName()
                                        : error.getMessage()));
            }
        }
    }

    private int findChangeIndex(Transaction tx, Address changeAddress) {
        if (tx == null || changeAddress == null) return -1;
        byte[] target = ScriptBuilder.createOutputScript(changeAddress).program();
        for (int i = 0; i < tx.getOutputs().size(); i++) {
            if (java.util.Arrays.equals(target, tx.getOutput(i).getScriptBytes())) return i;
        }
        return -1;
    }

    private void clearPendingState() {
        synchronized (STATE_LOCK) {
            clearPendingStateLocked();
        }
    }

    private static void clearPendingStateLocked() {
        pendingRequest = null;
        pendingAmount = null;
        pendingWalletAppKit = null;
        pendingParameters = null;
        pendingPreview = null;
        confirmationDeadlineMs = 0L;
        if (autoConfirmFuture != null) {
            autoConfirmFuture.cancel(false);
            autoConfirmFuture = null;
        }
        state = State.IDLE;
        SEND_IN_PROGRESS.set(false);
    }

    private void releaseSend() {
        SEND_IN_PROGRESS.set(false);
    }

    private String walletBalance(WalletAppKit walletAppKit) {
        try {
            return WalletSelection.mainAvailableBalance(walletAppKit.wallet()).toFriendlyString();
        } catch (Exception error) {
            return view.getStringResource(R.string.unknown_error);
        }
    }
}
