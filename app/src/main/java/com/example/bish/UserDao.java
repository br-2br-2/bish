package com.example.bish;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface UserDao {
    @Insert
    void insert(User user);
    
    @Delete
    void delete(User user);
    
    @Query("SELECT * FROM users WHERE username = :username")
    User getUserByUsername(String username);
    
    @Query("SELECT * FROM users ORDER BY createTime DESC")
    List<User> getAllUsers();
    
    @Query("SELECT COUNT(*) FROM users WHERE username = :username AND password = :password")
    int checkLogin(String username, String password);
}
