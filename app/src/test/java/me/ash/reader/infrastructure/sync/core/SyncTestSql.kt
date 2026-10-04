package me.ash.reader.infrastructure.sync.core

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** 为没有签名日志/字段行的隔离测试接入新增的标量 SQL 查询，不改变生产实现。 */
internal fun stubEmptySyncSql(database: AndroidDatabase): SupportSQLiteDatabase {
    val helper: SupportSQLiteOpenHelper = mock()
    val sql: SupportSQLiteDatabase = mock()
    whenever(database.openHelper).thenReturn(helper)
    whenever(helper.writableDatabase).thenReturn(sql)
    whenever(sql.query(any<String>(), any<Array<out Any?>>())).thenAnswer { mock<Cursor>() }
    whenever(sql.query(any<String>())).thenAnswer { mock<Cursor>() }
    return sql
}
