package com.pengxh.daily.app.sqlite.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.pengxh.daily.app.sqlite.bean.ExecutionRecordBean;

import java.util.List;

@Dao
public interface ExecutionRecordBeanDao {
    @Query("SELECT * FROM execution_record_table ORDER BY createdAt DESC")
    List<ExecutionRecordBean> loadAll();

    @Query("SELECT * FROM execution_record_table WHERE date = :date ORDER BY createdAt DESC")
    List<ExecutionRecordBean> loadForDate(String date);

    @Query("SELECT * FROM execution_record_table WHERE id = :id LIMIT 1")
    ExecutionRecordBean findById(int id);

    @Insert
    long insert(ExecutionRecordBean bean);

    @Query("DELETE FROM execution_record_table")
    void deleteAll();
}
