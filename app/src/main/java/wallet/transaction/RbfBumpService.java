package wallet.transaction;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Electrum-style fee bumping for an already broadcast, opt-in-RBF transaction.
 *
 * <p>The strategy is intentionally preserve-payment-first:
 * <ol>
 *     <li>Keep every non-wallet output unchanged.</li>
 *     <li>First reduce wallet-owned outputs, smallest first.</li>
 *     <li>If that is not enough, keep the payment outputs and add confirmed wallet inputs,
 *         creating a fresh wallet change output.</li>
 *     <li>Every candidate is rebuilt and signed from scratch before its fee/vsize is evaluated.</li>
 * </ol>
 */
public final class RbfBumpService {

    private static final long RBF_SEQUENCE = 0xfffffffdL;
    private static final int MAX_ADJUSTMENT_ROUNDS = 32;

    private RbfBumpService() {
    }

    public enum Error {
        UNAVAILABLE,
        WATCH_ONLY,
        MISSING_INPUT,
        NO_PAYMENT_OUTPUTS,
        NO_CHANGE_OR_FUNDS,
        FEE_NOT_HIGHER,
        FEE_TARGET_NOT_MET,
        FAILED
    }

    public static final class RbfException extends Exception {
        private final Error error;

        RbfException(Error error, String detail) {
            super(detail);
            this.error = error;
        }

        public Error error() {
            return error;
        }
    }

    public static boolean canBump(Transaction tx, Wallet wallet) {
        if (tx == null || wallet == null || wallet.isWatching()) {
            return false;
        }
        if (!tx.isPending() || !tx.isOptInFullRBF()) {
            return false;
        }
        if (tx.getFee() == null || !tx.getValueSentFromMe(wallet).isPositive()) {
            return false;
        }
        return hasWalletInput(tx, wallet);
    }

    public static Transaction bump(
            Wallet wallet,
            Transaction original,
            double targetFeeRateSatVb,
            AesKey aesKey) throws RbfException {
        if (!canBump(original, wallet)) {
            if (wallet.isWatching()) {
                throw new RbfException(Error.WATCH_ONLY, "watch-only wallet");
            }
            throw new RbfException(Error.UNAVAILABLE, "transaction is not RBF-replaceable");
        }
        if (!Double.isFinite(targetFeeRateSatVb) || targetFeeRateSatVb <= 0.0) {
            throw new RbfException(Error.FEE_TARGET_NOT_MET, "invalid fee rate");
        }

        final Coin oldFee = original.getFee();
        if (oldFee == null) {
            throw new RbfException(Error.FAILED, "old fee unavailable");
        }
        final double oldRate = oldFee.value / (double) Math.max(1, original.getVsize());
        if (targetFeeRateSatVb <= oldRate) {
            throw new RbfException(Error.FEE_NOT_HIGHER, "new fee rate is not higher");
        }

        List<TransactionOutput> paymentOutputs = new ArrayList<>();
        List<MutableOutput> walletOutputs = new ArrayList<>();
        for (TransactionOutput output : original.getOutputs()) {
            if (output.isMine(wallet)) {
                walletOutputs.add(new MutableOutput(output));
            } else {
                paymentOutputs.add(output);
            }
        }
        if (paymentOutputs.isEmpty()) {
            throw new RbfException(Error.NO_PAYMENT_OUTPUTS, "no non-wallet payment output");
        }

        walletOutputs.sort(Comparator.comparing(o -> o.value.value));
        List<MutableOutput> remainingChange = new ArrayList<>(walletOutputs);

        for (int round = 0; round < MAX_ADJUSTMENT_ROUNDS; round++) {
            Transaction probe = buildOriginalInputsTransaction(wallet, original, paymentOutputs, remainingChange);
            sign(wallet, probe, aesKey);
            Coin probeFee = probe.getFee();
            if (probeFee == null) {
                throw new RbfException(Error.FAILED, "probe fee unavailable");
            }

            long targetFee = requiredFee(probe.getVsize(), targetFeeRateSatVb);
            long delta = targetFee - probeFee.value;
            if (delta <= 0) {
                validateReplacement(wallet, original, probe, targetFeeRateSatVb);
                probe.setPurpose(Transaction.Purpose.RAISE_FEE);
                return probe;
            }

            if (remainingChange.isEmpty()) {
                break;
            }

            MutableOutput candidate = remainingChange.get(0);
            long dust = candidate.template.getMinNonDustValue().value;
            long newValue = candidate.value.value - delta;
            if (newValue >= dust) {
                candidate.value = Coin.valueOf(newValue);
            } else {
                remainingChange.remove(0);
            }
        }

        Transaction addedInputs = bumpThroughConfirmedInputs(
                wallet, original, paymentOutputs, targetFeeRateSatVb, aesKey);
        if (addedInputs == null) {
            throw new RbfException(Error.NO_CHANGE_OR_FUNDS, "could not raise fee without touching payment outputs");
        }
        addedInputs.setPurpose(Transaction.Purpose.RAISE_FEE);
        validateReplacement(wallet, original, addedInputs, targetFeeRateSatVb);
        return addedInputs;
    }

    private static Transaction bumpThroughConfirmedInputs(
            Wallet wallet,
            Transaction original,
            List<TransactionOutput> paymentOutputs,
            double targetFeeRateSatVb,
            AesKey aesKey) throws RbfException {
        AddressScript change = new AddressScript(wallet);
        List<TransactionOutput> candidates = new ArrayList<>();
        Set<String> originalOutpoints = new HashSet<>();
        for (TransactionInput input : original.getInputs()) {
            originalOutpoints.add(input.getOutpoint().toString());
        }

        for (TransactionOutput output : wallet.getUnspents()) {
            if (output == null || !output.isAvailableForSpending() || !output.isMine(wallet)) {
                continue;
            }
            if (output.getParentTransactionHash() == null || output.getParentTransactionDepthInBlocks() <= 0) {
                continue;
            }
            if (originalOutpoints.contains(output.getOutPointFor().toString())) {
                continue;
            }
            candidates.add(output);
        }
        candidates.sort(Comparator.comparing(o -> o.getValue().value));

        List<TransactionOutput> selected = new ArrayList<>();
        for (TransactionOutput candidate : candidates) {
            selected.add(candidate);

            Transaction probe = buildWithAddedInputs(
                    wallet, original, paymentOutputs, selected, Coin.SATOSHI, change.script);
            sign(wallet, probe, aesKey);
            Coin probeFee = probe.getFee();
            if (probeFee == null) {
                throw new RbfException(Error.FAILED, "probe fee unavailable");
            }

            long targetFee = requiredFee(probe.getVsize(), targetFeeRateSatVb);
            long totalInputs = totalInputValue(original, selected);
            long paymentValue = sumValues(paymentOutputs);
            long newChange = totalInputs - paymentValue - targetFee;
            if (newChange < change.minNonDust) {
                continue;
            }

            Transaction finalTx = buildWithAddedInputs(
                    wallet, original, paymentOutputs, selected, Coin.valueOf(newChange), change.script);
            sign(wallet, finalTx, aesKey);
            if (meetsTarget(finalTx, targetFeeRateSatVb, original.getFee())) {
                return finalTx;
            }

            // A signed transaction can gain a byte at a varint boundary. Re-price once more from scratch.
            long actualFee = finalTx.getFee() == null ? -1 : finalTx.getFee().value;
            long correctedTarget = requiredFee(finalTx.getVsize(), targetFeeRateSatVb);
            long correctedChange = totalInputValue(original, selected) - paymentValue - correctedTarget;
            if (actualFee >= 0 && correctedChange >= change.minNonDust) {
                Transaction corrected = buildWithAddedInputs(
                        wallet, original, paymentOutputs, selected,
                        Coin.valueOf(correctedChange), change.script);
                sign(wallet, corrected, aesKey);
                if (meetsTarget(corrected, targetFeeRateSatVb, original.getFee())) {
                    return corrected;
                }
            }
        }
        return null;
    }

    private static Transaction buildOriginalInputsTransaction(
            Wallet wallet,
            Transaction original,
            List<TransactionOutput> paymentOutputs,
            List<MutableOutput> changeOutputs) throws RbfException {
        Transaction tx = new Transaction(wallet.getNetworkParameters());
        tx.setVersion((int) Math.max(2L, original.getVersion()));
        tx.setLockTime(original.getLockTime());
        addOriginalInputs(wallet, tx, original);
        for (TransactionOutput output : paymentOutputs) {
            tx.addOutput(output.duplicateDetached());
        }
        for (MutableOutput output : changeOutputs) {
            if (output.value.isPositive()) {
                tx.addOutput(new TransactionOutput(tx, output.value, output.template.getScriptBytes()));
            }
        }
        return tx;
    }

    private static Transaction buildWithAddedInputs(
            Wallet wallet,
            Transaction original,
            List<TransactionOutput> paymentOutputs,
            List<TransactionOutput> added,
            Coin changeValue,
            byte[] changeScript) throws RbfException {
        Transaction tx = new Transaction(wallet.getNetworkParameters());
        tx.setVersion((int) Math.max(2L, original.getVersion()));
        tx.setLockTime(original.getLockTime());
        addOriginalInputs(wallet, tx, original);
        for (TransactionOutput output : added) {
            TransactionInput input = new TransactionInput(
                    tx, new byte[0], output.getOutPointFor(), RBF_SEQUENCE, output.getValue(), null);
            input.connect(output.duplicateDetached());
            tx.addInput(input);
        }
        for (TransactionOutput output : paymentOutputs) {
            tx.addOutput(output.duplicateDetached());
        }
        tx.addOutput(new TransactionOutput(tx, changeValue, changeScript));
        return tx;
    }

    private static void addOriginalInputs(Wallet wallet, Transaction tx, Transaction original) throws RbfException {
        for (TransactionInput oldInput : original.getInputs()) {
            TransactionOutput connected = oldInput.getConnectedOutput();
            if (connected == null) {
                Transaction previous = wallet.getTransaction(oldInput.getOutpoint().hash());
                if (previous != null && oldInput.getOutpoint().index() < previous.getOutputs().size()) {
                    connected = previous.getOutput(oldInput.getOutpoint().index());
                }
            }
            if (connected == null) {
                throw new RbfException(Error.MISSING_INPUT, "original input is missing its previous output");
            }
            TransactionInput input = new TransactionInput(
                    tx,
                    new byte[0],
                    oldInput.getOutpoint(),
                    RBF_SEQUENCE,
                    connected.getValue(),
                    null);
            input.connect(connected.duplicateDetached());
            tx.addInput(input);
        }
    }

    private static void sign(Wallet wallet, Transaction tx, AesKey aesKey) throws RbfException {
        try {
            SendRequest request = SendRequest.forTx(tx);
            request.aesKey = aesKey;
            request.signInputs = true;
            wallet.signTransaction(request);
        } catch (Exception error) {
            throw new RbfException(Error.FAILED,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private static void validateReplacement(
            Wallet wallet,
            Transaction original,
            Transaction replacement,
            double targetFeeRateSatVb) throws RbfException {
        if (!replacement.isOptInFullRBF() || replacement.getInputs().size() < original.getInputs().size()) {
            throw new RbfException(Error.FAILED, "replacement is not RBF-enabled");
        }
        for (int i = 0; i < original.getInputs().size(); i++) {
            if (!original.getInput(i).getOutpoint().equals(replacement.getInput(i).getOutpoint())) {
                throw new RbfException(Error.FAILED, "original inputs were changed");
            }
        }
        Coin oldFee = original.getFee();
        Coin newFee = replacement.getFee();
        if (oldFee == null || newFee == null || newFee.compareTo(oldFee) <= 0) {
            throw new RbfException(Error.FEE_NOT_HIGHER, "replacement fee is not higher");
        }
        if (!meetsTarget(replacement, targetFeeRateSatVb, oldFee)) {
            throw new RbfException(Error.FEE_TARGET_NOT_MET, "replacement fee rate target was not met");
        }
        try {
            Transaction.verify(wallet.getNetworkParameters().network(), replacement);
        } catch (Exception error) {
            throw new RbfException(Error.FAILED, error.getMessage() == null ? "verification failed" : error.getMessage());
        }
    }

    private static boolean meetsTarget(Transaction tx, double targetFeeRateSatVb, Coin oldFee) {
        Coin fee = tx.getFee();
        return fee != null
                && oldFee != null
                && fee.compareTo(oldFee) > 0
                && fee.value >= requiredFee(tx.getVsize(), targetFeeRateSatVb);
    }

    private static boolean hasWalletInput(Transaction tx, Wallet wallet) {
        try {
            return tx.getValueSentFromMe(wallet).isPositive();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long totalInputValue(Transaction original, List<TransactionOutput> added) {
        long total = original.getInputSum().value;
        for (TransactionOutput output : added) {
            total += output.getValue().value;
        }
        return total;
    }

    private static long sumValues(List<TransactionOutput> outputs) {
        long total = 0L;
        for (TransactionOutput output : outputs) {
            total += output.getValue().value;
        }
        return total;
    }

    private static long requiredFee(long vbytes, double feeRateSatVb) {
        double value = Math.ceil(Math.max(1L, vbytes) * feeRateSatVb);
        if (!Double.isFinite(value) || value > Long.MAX_VALUE) {
            throw new IllegalArgumentException("fee target is out of range");
        }
        return (long) value;
    }

    private static final class MutableOutput {
        final TransactionOutput template;
        Coin value;

        MutableOutput(TransactionOutput template) {
            this.template = template;
            this.value = template.getValue();
        }
    }

    private static final class AddressScript {
        final byte[] script;
        final long minNonDust;

        AddressScript(Wallet wallet) throws RbfException {
            try {
                script = org.bitcoinj.script.ScriptBuilder
                        .createOutputScript(wallet.currentChangeAddress())
                        .program();
                minNonDust = new TransactionOutput(null, Coin.SATOSHI, script)
                        .getMinNonDustValue().value;
            } catch (Exception error) {
                throw new RbfException(Error.FAILED,
                        error.getMessage() == null ? "change address unavailable" : error.getMessage());
            }
        }
    }
}
