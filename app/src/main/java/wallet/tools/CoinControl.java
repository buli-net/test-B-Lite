package wallet.tools;

import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.wallet.CoinSelector;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Stores the UTXOs selected for the next send. */
public final class CoinControl {

    private static final Object LOCK = new Object();
    private static final Set<String> selected = new HashSet<>();

    private CoinControl() {
    }

    public static void setSelected(Set<String> outpoints) {
        synchronized (LOCK) {
            selected.clear();
            selected.addAll(outpoints);
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            selected.clear();
        }
    }

    public static boolean isSelected(TransactionOutput output) {
        return isSelected(key(output));
    }

    public static boolean isSelected(String outpoint) {
        synchronized (LOCK) {
            return selected.contains(outpoint);
        }
    }

    public static Set<String> getSelected() {
        synchronized (LOCK) {
            return Collections.unmodifiableSet(new HashSet<>(selected));
        }
    }

    public static CoinSelector selector(CoinSelector delegate) {
        final Set<String> selectedNow = getSelected();
        if (selectedNow.isEmpty()) {
            return delegate;
        }

        return (target, candidates) -> {
            java.util.ArrayList<TransactionOutput> filtered = new java.util.ArrayList<>();
            for (TransactionOutput output : candidates) {
                if (selectedNow.contains(key(output))) {
                    filtered.add(output);
                }
            }
            return delegate.select(target, filtered);
        };
    }

    public static String key(TransactionOutput output) {
        TransactionOutPoint outPoint = output.getOutPointFor();
        Sha256Hash hash = outPoint.hash();
        return hash + ":" + outPoint.index();
    }
}
