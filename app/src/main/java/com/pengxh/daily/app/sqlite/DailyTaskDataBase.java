package com.pengxh.daily.app.sqlite;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean;
import com.pengxh.daily.app.sqlite.bean.LeaveRecordBean;
import com.pengxh.daily.app.sqlite.bean.ExecutionRecordBean;
import com.pengxh.daily.app.sqlite.bean.NotificationBean;
import com.pengxh.daily.app.sqlite.dao.DailyTaskBeanDao;
import com.pengxh.daily.app.sqlite.dao.LeaveRecordBeanDao;
import com.pengxh.daily.app.sqlite.dao.ExecutionRecordBeanDao;
import com.pengxh.daily.app.sqlite.dao.NotificationBeanDao;

@Database(
        entities = {DailyTaskBean.class, NotificationBean.class, LeaveRecordBean.class, ExecutionRecordBean.class},
        version = 3,
        exportSchema = false
)
public abstract class DailyTaskDataBase extends RoomDatabase {
    public abstract DailyTaskBeanDao dailyTaskDao();

    public abstract NotificationBeanDao noticeDao();

    public abstract LeaveRecordBeanDao leaveRecordDao();

    public abstract ExecutionRecordBeanDao executionRecordDao();
}
