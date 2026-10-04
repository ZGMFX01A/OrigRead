package me.ash.reader.infrastructure.sync.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 控制面独立连接与文件，状态查询不等待页面索引和业务安装写事务。 */
internal class SyncSnapshotJobsDatabase(context: Context) :
    SQLiteOpenHelper(context, "sync-snapshot-jobs.db", null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE snapshot_job(space TEXT PRIMARY KEY,peer TEXT NOT NULL,bundle TEXT NOT NULL,
            root TEXT NOT NULL,scope TEXT NOT NULL,pipeline INTEGER NOT NULL,generation INTEGER NOT NULL,
            state TEXT NOT NULL,phase TEXT NOT NULL,error TEXT,updated_at INTEGER NOT NULL)""")
        createSpaceOwner(db)
        createInstallFence(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSpaceOwner(db)
        if (oldVersion < 3) createInstallFence(db)
    }

    /** 独立空间拥有权覆盖本地捕获、合并和远程安装，不依赖网络请求对象。 */
    private fun createSpaceOwner(db: SQLiteDatabase) = db.execSQL("""CREATE TABLE snapshot_space_owner(
        space TEXT PRIMARY KEY,identity TEXT NOT NULL,phase TEXT NOT NULL,generation INTEGER NOT NULL,state TEXT NOT NULL)""")

    /** 围栏在失败及进程重启后保留，直到同一 root 的完整安装真实完成。 */
    private fun createInstallFence(db: SQLiteDatabase) = db.execSQL("""CREATE TABLE snapshot_install_fence(
        space TEXT PRIMARY KEY,account INTEGER NOT NULL,root TEXT NOT NULL,scope TEXT NOT NULL,generation INTEGER NOT NULL)""")

    companion object {
        /** 首次持久化控制面结构版本。 */
        private const val DATABASE_VERSION = 3
    }
}
