package wallet.send;

import android.text.TextUtils;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.script.Script;
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
import wallet.main.R;

/** Owns send validation, transaction preparation, review state, and broadcast state. */
public final class SendPresenter {

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
    private static NetworkParameters pendingParameters;

    public interface View {
        String recipient();

        String amount();

        int feeSatVb();

        void showMessage(String message);

        void showPreparing(boolean preparing);

        void showReview(SendTransactionPreview preview, long remainingMs);

        void showSending(boolean sending);

        void showWalletBalance(Coin balance, Coin available, Coin pending);

        void showMaxAmount(Coin amount);

        void showSummaryPending(Coin balance);

        android.content.Context getActivityContext();

        String getStringResource(int resId, Object... formatArgs);

        void requestSendUnlock(Wallet wallet);

        void requestMaxUnlock(Wallet wallet);
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
        final Wallet wallet = MainActivityPresenter.getActiveWallet();

        if (wallet == null) {
            if (viewActive) {
                view.showSummaryPending(null);
            }
            return;
        }

        executor.execute(() -> {
            try {
                Script selectedWatchScript = WalletSelection.findSelectedScript(
                        view.getActivityContext(), wallet);
                Script selectedImportedScript = selectedWatchScript == null
                        ? WalletSelection.findSelectedImportedScript(
                        view.getActivityContext(), wallet) : null;

                Coin available;
                Coin pending;
                Coin balance;
                if (selectedWatchScript != null || selectedImportedScript != null) {
                    Script selectedScript = selectedWatchScript != null
                            ? selectedWatchScript : selectedImportedScript;
                    Iterable<TransactionOutput> outputs;
                    if (selectedWatchScript != null) {
                        outputs = wallet.getWatchedOutputs(false);
                    } else {
                        outputs = WalletSelection.selectedImportedUsesWatchedOutputs(
                                view.getActivityContext())
                                ? wallet.getWatchedOutputs(false) : wallet.getUnspents();
                    }
                    long confirmedSat = 0L;
                    long pendingSat = 0L;
                    for (TransactionOutput output : outputs) {
                        if (!output.isAvailableForSpending()
                                || !selectedScript.equals(output.getScriptPubKey())) {
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
                } else {
                    balance = WalletSelection.mainEstimatedBalance(wallet);
                    available = WalletSelection.mainAvailableBalance(wallet);
                    pending = balance.subtract(available);
                }

                if (requestId != summaryId.get() || !viewActive || state != State.IDLE) {
                    return;
                }
                view.showWalletBalance(balance, available, pending);
            } catch (Exception error) {
                if (requestId == summaryId.get() && viewActive && state == State.IDLE) {
                    view.showSummaryPending(null);
                }
            }
        });
    }

    public void fillMax() {
        final int requestId = maxRequestId.incrementAndGet();
        final Wallet wallet = MainActivityPresenter.getActiveWallet();
        final NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
        final String recipientText = view.recipient().trim();
        final int feeSatVb = view.feeSatVb();

        if (wallet == null || parameters == null) {
            view.showMessage(view.getStringResource(R.string.wallet_not_ready));
            return;
        }
        try {
            if (WalletSelection.isReadOnlySelection(
                    view.getActivityContext(), wallet)) {
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

        if (WalletSecurity.isEncrypted(wallet) && !WalletSecurity.isSessionValid(wallet)) {
            view.requestMaxUnlock(wallet);
            return;
        }

        executor.execute(() -> {
            Context.propagate(Context.getOrCreate(parameters));
            try {
                Address destination = Address.fromString(parameters, recipientText);
                Script selectedWatchScript = WalletSelection.findSelectedScript(
                        view.getActivityContext(), wallet);
                ECKey requestKey = WalletSelection.findRequestNestedKeyForScript(
                        view.getActivityContext(), wallet, selectedWatchScript);
                if (requestKey != null) {
                    Coin maxAmount = NestedSegwitSpend.calculateMaxSendAmount(
                            wallet, selectedWatchScript, requestKey, destination,
                            feeSatVb, WalletSecurity.getSessionKey());
                    if (!viewActive
                            || state != State.IDLE
                            || requestId != maxRequestId.get()) {
                        return;
                    }
                    view.showMaxAmount(maxAmount);
                    return;
                }
                SendRequest request = SendRequest.emptyWallet(destination);
                request.aesKey = WalletSecurity.getSessionKey();
                request.setFeePerVkb(Coin.valueOf(feeSatVb * 1000L));
                request.ensureMinRequiredFee = true;
                request.coinSelector = CoinControl.selector(WalletSelection.mainCoinSelector(view.getActivityContext(), wallet));
                wallet.completeTx(request);


                if (request.tx.getOutputs().isEmpty()) {
                    throw new IllegalStateException(
                            view.getStringResource(R.string.max_amount_unavailable));
                }

                Coin maxAmount = request.tx.getOutput(0).getValue();
                if (maxAmount == null || !maxAmount.isPositive()) {
                    throw new IllegalStateException(
                            view.getStringResource(R.string.max_amount_unavailable));
                }
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
                                    walletBalance(wallet)));
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

        final Wallet wallet = MainActivityPresenter.getActiveWallet();
        final NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
        if (wallet == null || parameters == null) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.wallet_not_ready));
            return;
        }
        try {
            if (WalletSelection.isReadOnlySelection(
                    view.getActivityContext(), wallet)) {
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
                parameters,
                recipient,
                amount,
                feeSatVb));
    }

    private void prepareTransaction(
            int operation,
            NetworkParameters parameters,
            String recipientText,
            Coin amount,
            int feeSatVb) {
        Context.propagate(Context.getOrCreate(parameters));

        try {
            Wallet wallet = MainActivityPresenter.getActiveWallet();
            if (wallet == null) {
                throw new IllegalStateException(
                        view.getStringResource(R.string.wallet_not_ready));
            }
            String selectedImportedAddress = WalletSelection.getSelectedImportedAddress(view.getActivityContext());
            if (selectedImportedAddress != null) {
                String importedType = ImportedWalletStore.getAddressType(
                        view.getActivityContext(), selectedImportedAddress);
                if (!WalletAddressType.isNativelySpendable(importedType)) {
                    throw new IllegalStateException(
                            view.getStringResource(R.string.imported_wallet_type_not_spendable));
                }
            }
            Script selectedWatchScript = WalletSelection.findSelectedScript(
                    view.getActivityContext(), wallet);
            ECKey requestNestedKey = WalletSelection.findRequestNestedKeyForScript(
                    view.getActivityContext(), wallet, selectedWatchScript);
            if (selectedWatchScript != null && requestNestedKey == null) {
                throw new IllegalStateException(
                        view.getStringResource(R.string.watch_send_read_only_message));
            }
            Script importedScript = WalletSelection.findSelectedImportedScript(view.getActivityContext(), wallet);
            Coin balance = requestNestedKey != null
                    ? NestedSegwitSpend.availableBalance(wallet, selectedWatchScript)
                    : (importedScript == null
                    ? WalletSelection.mainAvailableBalance(wallet)
                    : WalletSelection.selectedImportedAvailableBalance(view.getActivityContext(), wallet, importedScript));
            Address destination = Address.fromString(parameters, recipientText);

            if (WalletSecurity.isEncrypted(wallet) && !WalletSecurity.isSessionValid(wallet)) {
                throw new IllegalStateException(
                        view.getStringResource(R.string.wallet_locked));
            }
            final SendRequest request;
            if (requestNestedKey != null) {
                org.bitcoinj.core.Transaction transaction = NestedSegwitSpend.buildTransaction(
                        wallet, selectedWatchScript, requestNestedKey, destination, amount,
                        feeSatVb, WalletSecurity.getSessionKey());
                request = SendRequest.forTx(transaction);
                request.aesKey = WalletSecurity.getSessionKey();
            } else {
                request = SendRequest.to(destination, amount);
                request.aesKey = WalletSecurity.getSessionKey();
                request.setFeePerVkb(Coin.valueOf(feeSatVb * 1000L));
                request.ensureMinRequiredFee = true;
                if (importedScript != null) {
                    request.changeAddress = Address.fromString(
                            parameters, WalletSelection.getSelectedImportedAddress(view.getActivityContext()));
                }
                request.coinSelector = CoinControl.selector(
                        WalletSelection.mainCoinSelector(view.getActivityContext(), wallet));
                wallet.completeTx(request);
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
            failPreparation(operation, R.string.insufficient_balance, walletBalance(MainActivityPresenter.getActiveWallet()));
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

    public void confirmSend() {
        final SendRequest request;
        final NetworkParameters parameters;
        final Coin amount;

        synchronized (STATE_LOCK) {
            if (state != State.WAITING_CONFIRMATION) {
                return;
            }
            request = pendingRequest;
            amount = pendingAmount;
            parameters = pendingParameters;
            state = State.BROADCASTING;
        }

        if (request == null || amount == null || parameters == null) {
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

        executor.execute(() -> broadcast(request, amount, parameters));
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
            SendRequest request,
            Coin amount,
            NetworkParameters parameters) {
        Context.propagate(Context.getOrCreate(parameters));

        try {
            Wallet wallet = MainActivityPresenter.getActiveWallet();
            if (wallet == null) {
                throw new IllegalStateException(
                        view.getStringResource(R.string.wallet_not_ready));
            }
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
                Script selectedFundingScript = WalletSelection.findSelectedScript(
                        view.getActivityContext(), wallet);
                ECKey requestNestedKey = WalletSelection.findRequestNestedKeyForScript(
                        view.getActivityContext(), wallet, selectedFundingScript);
                Script importedScript = WalletSelection.findSelectedImportedScript(
                        view.getActivityContext(), wallet);
                Coin balance = requestNestedKey != null
                        ? NestedSegwitSpend.availableBalance(wallet, selectedFundingScript)
                        : (importedScript == null
                        ? WalletSelection.mainAvailableBalance(wallet)
                        : WalletSelection.selectedImportedAvailableBalance(
                        view.getActivityContext(), wallet, importedScript));
                ensureNoRbf(request.tx);

                Coin fee = request.tx.getFee();
                if (fee == null) {
                    throw new IllegalStateException(
                            view.getStringResource(R.string.transaction_fee_unavailable));
                }
                if (amount.add(fee).isGreaterThan(balance)) {
                    throw new InsufficientMoneyException(amount.add(fee).subtract(balance));
                }

                org.bitcoinj.core.TransactionBroadcast broadcast =
                        MainActivityPresenter.commitAndBroadcastTransaction(request.tx);

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
                    {
                        Script selectedWatchScript = WalletSelection.findSelectedScript(
                                view.getActivityContext(), wallet);
                        Script selectedImportedScript = selectedWatchScript == null
                                ? WalletSelection.findSelectedImportedScript(
                                view.getActivityContext(), wallet) : null;
                        Coin available;
                        Coin pending;
                        if (selectedWatchScript != null || selectedImportedScript != null) {
                            Script selectedScript = selectedWatchScript != null
                                    ? selectedWatchScript : selectedImportedScript;
                            Iterable<TransactionOutput> outputs = selectedWatchScript != null
                                    ? wallet.getWatchedOutputs(false)
                                    : (WalletSelection.selectedImportedUsesWatchedOutputs(
                                    view.getActivityContext())
                                    ? wallet.getWatchedOutputs(false) : wallet.getUnspents());
                            long confirmedSat = 0L;
                            long pendingSat = 0L;
                            for (TransactionOutput output : outputs) {
                                if (!output.isAvailableForSpending()
                                        || !selectedScript.equals(output.getScriptPubKey())) {
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
                        } else {
                            balance = WalletSelection.mainEstimatedBalance(wallet);
                            available = WalletSelection.mainAvailableBalance(wallet);
                            pending = balance.subtract(available);
                        }
                        view.showWalletBalance(balance, available, pending);
                    }
                }
            }
        } catch (InsufficientMoneyException error) {
            clearPendingState();
            if (viewActive) {
                view.showSending(false);
                view.showMessage(
                        view.getStringResource(
                                R.string.insufficient_balance,
                                walletBalance(MainActivityPresenter.getActiveWallet())));
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

    private static void ensureNoRbf(org.bitcoinj.core.Transaction transaction) {
        if (transaction == null) {
            throw new IllegalStateException("transaction == null");
        }
        for (TransactionInput input : transaction.getInputs()) {
            if (input.isOptInFullRBF()) {
                throw new IllegalStateException("RBF is disabled");
            }
        }
    }

    private void clearPendingState() {
        synchronized (STATE_LOCK) {
            clearPendingStateLocked();
        }
    }

    private static void clearPendingStateLocked() {
        pendingRequest = null;
        pendingAmount = null;
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

    private String walletBalance(Wallet wallet) {
        try {
            if (wallet == null) {
                return "--";
            }
            Script selectedWatchScript = WalletSelection.findSelectedScript(
                    view.getActivityContext(), wallet);
            Script selectedImportedScript = selectedWatchScript == null
                    ? WalletSelection.findSelectedImportedScript(
                    view.getActivityContext(), wallet) : null;
            if (selectedWatchScript != null || selectedImportedScript != null) {
                Script selectedScript = selectedWatchScript != null
                        ? selectedWatchScript : selectedImportedScript;
                Iterable<TransactionOutput> outputs = selectedWatchScript != null
                        ? wallet.getWatchedOutputs(false)
                        : (WalletSelection.selectedImportedUsesWatchedOutputs(
                        view.getActivityContext())
                        ? wallet.getWatchedOutputs(false) : wallet.getUnspents());
                Coin available = Coin.ZERO;
                for (TransactionOutput output : outputs) {
                    if (output.isAvailableForSpending()
                            && output.getParentTransactionDepthInBlocks() > 0
                            && selectedScript.equals(output.getScriptPubKey())) {
                        available = available.add(output.getValue());
                    }
                }
                return available.toFriendlyString();
            }
            return WalletSelection.mainAvailableBalance(wallet).toFriendlyString();
        } catch (Exception error) {
            return view.getStringResource(R.string.unknown_error);
        }
    }
}
