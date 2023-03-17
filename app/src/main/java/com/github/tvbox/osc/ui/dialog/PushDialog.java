package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class PushDialog extends BaseDialog {

    private EditText etAddr;
    private EditText etPort;

    public PushDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_push);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        etAddr = findViewById(R.id.etAddr);
        etPort = findViewById(R.id.etPort);
        String cfgAddr = Hawk.get(HawkConfig.PUSH_TO_ADDR, "");
        String cfgPort = Hawk.get(HawkConfig.PUSH_TO_PORT, "");
		if (cfgAddr.isEmpty()) {
            String ipAddress = RemoteServer.getLocalIPAddress(PushDialog.this.getContext());
            int lp = ipAddress.lastIndexOf('.');
            if (lp > 0)
		        etAddr.setText(ipAddress.substring(0, lp + 1));
		} else {
			etAddr.setText(cfgAddr);
		}
		if (cfgPort.isEmpty()) {
			etPort.setText("" + RemoteServer.serverPort);
		} else {
			etPort.setText(cfgPort);
		}
        findViewById(R.id.btnConfirm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String addr = etAddr.getText().toString();
                String port = etPort.getText().toString();
                if(addr == null || addr.length() == 0)
                {
                    Toast.makeText(PushDialog.this.getContext(), "请输入远端tvbox地址", Toast.LENGTH_SHORT).show();
                    return;
                }
                if(port == null || port.length() == 0)
                {
                    Toast.makeText(PushDialog.this.getContext(), "请输入远端tvbox端口", Toast.LENGTH_SHORT).show();
                    return;
                }
				Hawk.put(HawkConfig.PUSH_TO_ADDR, addr);
				Hawk.put(HawkConfig.PUSH_TO_PORT, port);
				List<String> list = new ArrayList<>();
				list.add(addr);
				list.add(port);
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PUSH_VOD, list));
                PushDialog.this.dismiss();
            }
        });
        findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Context context = getContext().getApplicationContext();
                WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
                Toast.makeText(PushDialog.this.getContext(), "当前IP " + ip, Toast.LENGTH_SHORT).show();
            }
        });
    }

}