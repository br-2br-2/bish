package com.example.bish;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {
    
    private EditText etUsername, etPassword;
    private Button btnLogin, btnRegister;
    private TextView tvMessage;
    private AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    // SharedPreferences 用于记住登录状态
    private SharedPreferences sharedPreferences;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 检查是否已经登录
        sharedPreferences = getSharedPreferences("user_prefs", MODE_PRIVATE);
        boolean isLoggedIn = sharedPreferences.getBoolean("is_logged_in", false);
        if (isLoggedIn) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        
        setContentView(R.layout.activity_login);
        
        db = AppDatabase.getDatabase(this);
        
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnRegister = findViewById(R.id.btnRegister);
        tvMessage = findViewById(R.id.tvMessage);
        
        // 登录按钮点击事件
        btnLogin.setOnClickListener(v -> {
            String username = etUsername.getText().toString().trim();
            String password = etPassword.getText().toString().trim();
            
            if (username.isEmpty() || password.isEmpty()) {
                tvMessage.setText("用户名和密码不能为空");
                return;
            }
            
            executor.execute(() -> {
                int count = db.userDao().checkLogin(username, password);
                runOnUiThread(() -> {
                    if (count > 0) {
                        // 登录成功，保存登录状态
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putBoolean("is_logged_in", true);
                        editor.putString("current_username", username);
                        editor.apply();
                        
                        Toast.makeText(LoginActivity.this, "登录成功", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(LoginActivity.this, MainActivity.class));
                        finish();
                    } else {
                        tvMessage.setText("用户名或密码错误");
                    }
                });
            });
        });
        
        // 注册按钮点击事件
        btnRegister.setOnClickListener(v -> {
            String username = etUsername.getText().toString().trim();
            String password = etPassword.getText().toString().trim();
            
            if (username.isEmpty() || password.isEmpty()) {
                tvMessage.setText("用户名和密码不能为空");
                return;
            }
            
            if (username.length() < 3) {
                tvMessage.setText("用户名至少 3 个字符");
                return;
            }
            
            if (password.length() < 6) {
                tvMessage.setText("密码至少 6 个字符");
                return;
            }
            
            executor.execute(() -> {
                User existingUser = db.userDao().getUserByUsername(username);
                runOnUiThread(() -> {
                    if (existingUser != null) {
                        tvMessage.setText("用户名已存在");
                    } else {
                        // 创建新用户
                        User newUser = new User();
                        newUser.username = username;
                        newUser.password = password;
                        newUser.createTime = System.currentTimeMillis();
                        
                        executor.execute(() -> {
                            db.userDao().insert(newUser);
                            runOnUiThread(() -> {
                                Toast.makeText(LoginActivity.this, "注册成功，请登录", Toast.LENGTH_SHORT).show();
                                tvMessage.setText("");
                                etPassword.setText("");
                            });
                        });
                    }
                });
            });
        });
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
