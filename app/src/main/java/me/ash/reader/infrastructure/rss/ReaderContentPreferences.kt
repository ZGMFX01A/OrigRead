package me.ash.reader.infrastructure.rss

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

private val Context.readerContentDataStore by preferencesDataStore(name = "reader_content")

/** 阅读页按钮的本机来源级状态，不改变订阅解析配置或后台全文预取策略。 */
@Singleton
class ReaderContentPreferences internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.readerContentDataStore)

    // 同一来源在不同阅读页间共享保存顺序，重新进入也会等待先前点击落盘。
    private val mutex = Mutex()

    suspend fun isFullContentEnabled(accountId: Int, feedId: String): Boolean = mutex.withLock {
        store.data.catch { error ->
            if (error !is IOException) throw error
            Timber.w(error, "Could not read source full-content selection")
            emit(emptyPreferences())
        }.first()[key(accountId, feedId)] ?: false
    }

    suspend fun setFullContentEnabled(accountId: Int, feedId: String, enabled: Boolean) = mutex.withLock {
        store.edit { preferences -> preferences[key(accountId, feedId)] = enabled }
        Unit
    }

    private fun key(accountId: Int, feedId: String) = booleanPreferencesKey("full_content:$accountId:$feedId")
}
