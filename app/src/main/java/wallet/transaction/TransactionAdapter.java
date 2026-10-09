package wallet.transaction;

import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.text.TextUtils;

import java.util.List;

import wallet.contacts.AddressBookStore;
import wallet.main.R;
import wallet.model.TransactionItem;

/** Binds transaction rows to the wallet transaction list. */
public final class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.Holder> {

    private final List<TransactionItem> items;

    public TransactionAdapter(List<TransactionItem> items) {
        this.items = items;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transaction, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        TransactionItem item = items.get(position);
        holder.type.setText(item.type);
        holder.amount.setText(item.amount);
        holder.time.setText(item.time);
        holder.confirmations.setText(item.confirmations);
        String label = item.counterparty == null ? "" : item.counterparty.trim();
        if (!TextUtils.isEmpty(label)) {
            for (AddressBookStore.Entry entry : AddressBookStore.load(vContext(holder))) {
                if (entry.address.equals(label)) {
                    label = entry.name + " · " + label;
                    break;
                }
            }
        }
        holder.counterparty.setText(label);
        holder.counterparty.setVisibility(TextUtils.isEmpty(label) ? View.GONE : View.VISIBLE);
        holder.txid.setText(item.txid == null ? "—" : item.txid);
        holder.itemView.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(v.getContext(), TransactionDetailActivity.class);
            intent.putExtra(TransactionDetailActivity.EXTRA_TXID, item.txid);
            v.getContext().startActivity(intent);
        });
        holder.peers.setText(item.peers);
        holder.state.setText(item.state);
    }

    private android.content.Context vContext(Holder holder) { return holder.itemView.getContext(); }


    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView type;
        final TextView amount;
        final TextView time;
        final TextView confirmations;
        final TextView txid;
        final TextView counterparty;
        final TextView peers;
        final TextView state;

        Holder(View view) {
            super(view);
            type = view.findViewById(R.id.tvTxType);
            amount = view.findViewById(R.id.tvTxAmount);
            time = view.findViewById(R.id.tvTxTime);
            confirmations = view.findViewById(R.id.tvTxConf);
            txid = view.findViewById(R.id.tvTxId);
            counterparty = view.findViewById(R.id.tvTxCounterparty);
            peers = view.findViewById(R.id.tvTxPeers);
            state = view.findViewById(R.id.tvTxState);
        }
    }
}
