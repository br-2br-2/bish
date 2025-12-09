package com.example.bish;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "expenses")
public class Expense {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public double amount;      // 金额
    public String category;    // 类别，如 "餐饮"
    public long date;          // 时间戳（毫秒）
    public String note;        // 备注
}