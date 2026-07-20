package com.pengxh.daily.app.sqlite.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.pengxh.daily.app.sqlite.bean.NotificationBean;

import java.util.List;

@Dao
public interface NotificationBeanDao {
    @Query("SELECT * FROM notice_record_table WHERE postTime LIKE :date || '%'")
    List<NotificationBean> loadCurrentDayNotice(String date);

    @Query("SELECT * FROM notice_record_table WHERE postTime >= :from AND postTime < :to")
    List<NotificationBean> loadBetween(String from, String to);

    @Insert
    void insert(NotificationBean bean);
}
