package com.example.bish;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {
    
    private SharedPreferences sharedPreferences;
    private BottomNavigationView bottomNavigationView;
    private FragmentManager fragmentManager;
    
    private RecordFragment recordFragment;
    private ChartFragment chartFragment;
    private PredictionFragment predictionFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 检查登录状态
        sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE);
        boolean isLoggedIn = sharedPreferences.getBoolean("is_logged_in", false);
        if (!isLoggedIn) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        
        setContentView(R.layout.activity_main);
        
        // 初始化 Fragment
        recordFragment = new RecordFragment();
        chartFragment = new ChartFragment();
        predictionFragment = new PredictionFragment();
        
        fragmentManager = getSupportFragmentManager();
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        
        // 设置默认选中第一个 Fragment
        if (savedInstanceState == null) {
            loadFragment(recordFragment);
            bottomNavigationView.setSelectedItemId(R.id.nav_record);
        }
        
        // 底部导航监听
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_record) {
                loadFragment(recordFragment);
                return true;
            } else if (itemId == R.id.nav_chart) {
                loadFragment(chartFragment);
                return true;
            } else if (itemId == R.id.nav_prediction) {
                loadFragment(predictionFragment);
                return true;
            }
            return false;
        });
    }
    
    private void loadFragment(Fragment fragment) {
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.commit();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 再次检查登录状态
        boolean isLoggedIn = sharedPreferences.getBoolean("is_logged_in", false);
        if (!isLoggedIn) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        }
    }
}
