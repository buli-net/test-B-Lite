package wallet.tools;

import wallet.main.BaseActivity;

import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.Wallet;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.main.WalletSelection;
import wallet.ui.TextViewUtils;

/** Selects specific wallet UTXOs for the next send. */
public final class CoinControlActivity extends BaseActivity {

    private LinearLayout outputsContainer;
    private TextView totalText;
    private final Set<String> selected = new HashSet<>();
    private final Map<String, View> coinRows = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_coin_control);

        Toolbar toolbar = findViewById(R.id.toolbar_coin_control);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.coin_control_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        outputsContainer = findViewById(R.id.coinOutputsContainer);
        totalText = findViewById(R.id.coinControlTotal);
        Button clear = findViewById(R.id.clearCoinSelection);
        Button apply = findViewById(R.id.applyCoinSelection);
        clear.setOnClickListener(v -> {
            selected.clear();
            render();
        });
        apply.setOnClickListener(v -> {
            CoinControl.setSelected(selected);
            Toast.makeText(this, R.string.coin_selection_saved, Toast.LENGTH_SHORT).show();
            finish();
        });

        selected.addAll(CoinControl.getSelected());
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (outputsContainer != null) {
            render();
        }
    }

    private void render() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null || kit.wallet() == null) {
            outputsContainer.removeAllViews();
            coinRows.clear();
            totalText.setText(R.string.wallet_not_ready);
            return;
        }

        Wallet wallet = kit.wallet();
        List<TransactionOutput> outputs = wallet.getUnspents();
        java.util.ArrayList<TransactionOutput> mainOutputs = new java.util.ArrayList<>();
        for (TransactionOutput output : outputs) {
            if (output.isAvailableForSpending()
                    && !WalletSelection.isWatchedOutput(wallet, output)) {
                mainOutputs.add(output);
            }
        }

        // Remove stale selections that belong to outputs that are no longer
        // spendable by the main wallet.
        selected.removeIf(key -> {
            for (TransactionOutput output : mainOutputs) {
                if (key.equals(CoinControl.key(output))) return false;
            }
            return true;
        });

        long total = 0L;
        Map<String, View> activeRows = new HashMap<>();
        for (TransactionOutput output : mainOutputs) {
            String key = CoinControl.key(output);
            View row = coinRows.get(key);
            if (row == null) {
                row = getLayoutInflater().inflate(
                        R.layout.item_coin_control, outputsContainer, false);
                TextView txId = row.findViewById(R.id.tvCoinTxId);
                TextViewUtils.configureSelectableMiddleEllipsis(txId);
                coinRows.put(key, row);
            }

            CheckBox box = row.findViewById(R.id.coinCheckBox);
            TextView txId = row.findViewById(R.id.tvCoinTxId);
            TextView outputIndex = row.findViewById(R.id.tvCoinOutput);
            TextView confirmations = row.findViewById(R.id.tvCoinConf);
            TextView value = row.findViewById(R.id.tvCoinAmount);

            TextViewUtils.setTextIfChanged(txId, output.getParentTransactionHash().toString());
            TextViewUtils.setTextIfChanged(outputIndex, getString(
                    R.string.watch_send_coin_output, output.getIndex()));
            TextViewUtils.setTextIfChanged(confirmations, getString(
                    R.string.watch_send_coin_confirmations,
                    Math.max(0, output.getParentTransactionDepthInBlocks())));
            TextViewUtils.setTextIfChanged(value, output.getValue().toFriendlyString());

            box.setOnCheckedChangeListener(null);
            box.setChecked(selected.contains(key));
            box.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    selected.add(key);
                } else {
                    selected.remove(key);
                }
                updateTotal(mainOutputs);
            });
            row.setOnClickListener(v -> box.setChecked(!box.isChecked()));

            activeRows.put(key, row);
            if (selected.contains(key)) {
                total += output.getValue().value;
            }
        }

        java.util.Iterator<Map.Entry<String, View>> iterator = coinRows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, View> entry = iterator.next();
            if (!activeRows.containsKey(entry.getKey())) {
                outputsContainer.removeView(entry.getValue());
                iterator.remove();
            }
        }

        if (mainOutputs.isEmpty()) {
            outputsContainer.removeAllViews();
            coinRows.clear();
            TextView empty = (TextView) getLayoutInflater().inflate(
                    R.layout.item_empty_message, outputsContainer, false);
            empty.setText(R.string.coin_control_empty);
            outputsContainer.addView(empty);
        } else {
            boolean orderChanged = outputsContainer.getChildCount() != mainOutputs.size();
            if (!orderChanged) {
                for (int i = 0; i < mainOutputs.size(); i++) {
                    View expected = activeRows.get(CoinControl.key(mainOutputs.get(i)));
                    if (outputsContainer.getChildAt(i) != expected) {
                        orderChanged = true;
                        break;
                    }
                }
            }
            if (orderChanged) {
                outputsContainer.removeAllViews();
                for (TransactionOutput output : mainOutputs) {
                    View row = activeRows.get(CoinControl.key(output));
                    if (row != null) outputsContainer.addView(row);
                }
            }
        }

        totalText.setText(getString(
                R.string.coin_control_selected_total,
                org.bitcoinj.base.Coin.valueOf(total).toFriendlyString()));
    }

    private void updateTotal(List<TransactionOutput> outputs) {
        long total = 0L;
        for (TransactionOutput output : outputs) {
            if (selected.contains(CoinControl.key(output))) {
                total += output.getValue().value;
            }
        }
        totalText.setText(getString(
                R.string.coin_control_selected_total,
                org.bitcoinj.base.Coin.valueOf(total).toFriendlyString()));
    }
}
