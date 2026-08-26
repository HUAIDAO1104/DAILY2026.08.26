package com.pengxh.daily.app.sqlite.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import com.pengxh.daily.app.sqlite.bean.LeaveRecordBean;

import java.util.List;

@Dao
public interface LeaveRecordBeanDao {
    @Query("SELECT * FROM leave_record_table ORDER BY startDate ASC, createdAt ASC")
    List<LeaveRecordBean> loadAll();

    @Query("SELECT * FROM leave_record_table WHERE startDate <= :date AND endDate >= :date ORDER BY createdAt DESC")
    List<LeaveRecordBean> loadForDate(String date);

    @Query("SELECT * FROM leave_record_table WHERE id = :id LIMIT 1")
    LeaveRecordBean findById(int id);

    @Insert
    long insert(LeaveRecordBean bean);

    @Delete
    void delete(LeaveRecordBean bean);

    @Query("DELETE FROM leave_record_table WHERE id = :id")
    void deleteById(int id);

    @Query("DELETE FROM leave_record_table WHERE startDate <= :date AND endDate >= :date")
    int deleteForDate(String date);

    @Query("DELETE FROM leave_record_table")
    void deleteAll();
}
