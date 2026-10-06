package wallet.backup;

import wallet.main.BaseActivity;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;

import wallet.main.R;

/** Groups wallet backup and recovery actions in one place. */
public final class BackupRecoveryActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_backup_recovery);

        Toolbar toolbar = findViewById(R.id.toolbar_backup_recovery);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.backup_recovery_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        findViewById(R.id.openBackupButton).setOnClickListener(v ->
                startActivity(new Intent(this, BackupActivity.class)));
        findViewById(R.id.openRestoreButton).setOnClickListener(v ->
                startActivity(new Intent(this, RestoreActivity.class)));
    }
}
