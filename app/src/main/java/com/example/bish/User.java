package com.example.bish;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "users")
public class User {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public String username;      // 用户名
    public String password;      // 密码（实际项目中应该加密存储）
    public long createTime;      // 创建时间戳
}
