package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.pm.PackageManager;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

public class AboutDialog extends BaseDialog {

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_about);
        
        try {
            TextView tvVersion = findViewById(R.id.tvVersion);
            if (tvVersion != null) {
                String versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
                tvVersion.setText("版本: " + versionName);
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }
}