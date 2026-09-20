package com.personal.accounting.android.ui;

import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.personal.accounting.android.R;
import com.personal.accounting.android.data.RecurringRepo;
import com.personal.accounting.android.sync.SyncConfig;

/** 主界面：底部 Tab = 记账 / 报表 / 设置，默认进入记账。 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 恢复用户在“设置”里填写的后端地址，保证 release 包可改服务器
        SharedPreferences sp = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE);
        SyncConfig.BASE_URL = sp.getString("base_url", SyncConfig.BASE_URL);

        // 周期记账：每次打开应用把到期的规则生成「待确认项」（幂等，不会重复生成）
        try {
            new RecurringRepo(this).materialize(java.time.LocalDate.now());
        } catch (Exception ignore) {
            // 生成失败不影响记账主流程
        }

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_record) {
                show(new RecordFragment());
                return true;
            } else if (id == R.id.nav_report) {
                show(new ReportFragment());
                return true;
            } else if (id == R.id.nav_budget) {
                show(new BudgetFragment());
                return true;
            } else if (id == R.id.nav_history) {
                show(new HistoryFragment());
                return true;
            } else if (id == R.id.nav_settings) {
                show(new SettingsFragment());
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            show(new RecordFragment());
        }
    }

    private void show(Fragment f) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, f)
                .commit();
    }
}
