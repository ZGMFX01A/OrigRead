package me.ash.reader.domain.service

import kotlinx.coroutines.CancellationException
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.ofFeedStreamIdToId
import me.ash.reader.infrastructure.source.SourceUrlNormalizer
import me.ash.reader.ui.ext.spacerDollar

/** 固定账户和 API；重试先核对远端已创建的来源，避免 quickAdd 部分成功后重复创建。 */
internal suspend fun GoogleReaderRssService.saveGoogleReaderSubscription(request: SubscriptionRequest): SubscriptionResult =
    withSubscriptionLock(request.accountId) {
        require(request.sourceType == SourceType.RSS) { "远端账户只支持 RSS 订阅" }
        require(groupDao.queryById(request.groupId)?.accountId == request.accountId) { "订阅分组不属于目标账户" }
        findExistingFeed(request.accountId, request.feedLink)?.let {
            return@withSubscriptionLock SubscriptionResult(it.id, created = false)
        }
        val account = requireNotNull(accountService.getAccountById(request.accountId)) { "订阅账户不存在" }
        val api = getGoogleReaderAPI(account)
        val key = SourceUrlNormalizer.comparisonKey(request.feedLink)
        val subscriptions = api.getSubscriptionList()
        val subscriptionItems = requireNotNull(subscriptions.subscriptions) { "Google Reader subscriptions 缺失" }
        val existingRemote = subscriptionItems.firstOrNull {
            (it.url ?: it.htmlUrl)?.let(SourceUrlNormalizer::comparisonKey) == key
        }
        val remoteId = existingRemote?.id?.ofFeedStreamIdToId()
            ?: api.subscriptionQuickAdd(request.feedLink).streamId?.ofFeedStreamIdToId()
            ?: error("远端添加来源没有返回 ID")
        try {
            // 编辑分类和本地保存都沿用同一个账户、API 和真实远端 ID。
            api.subscriptionEdit(
                destFeedId = remoteId, destFeedName = request.searchedFeed.title,
                destCategoryId = resolveGoogleReaderRemoteCategoryId(
                    request.groupId, accountService.getDefaultGroup(request.accountId).id,
                ),
            )
            val feed = request.remoteFeed(remoteId)
            require(groupDao.queryById(request.groupId)?.accountId == request.accountId) { "订阅分组已失效" }
            feedDao.insert(feed)
            SubscriptionResult(feed.id, created = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 远端可能已经创建；明确告知部分完成，下一次提交会复用远端来源而非再次 quickAdd。
            throw java.io.IOException("远端来源已存在，但分类或本地保存未完成；重试会继续完成：${error.message}", error)
        }
    }

/** 远端文章身份由服务器提供，首次订阅仅保存服务器确认的来源信息。 */
private fun SubscriptionRequest.remoteFeed(remoteId: String) = Feed(
    id = accountId.spacerDollar(remoteId), name = searchedFeed.title,
    url = feedLink, groupId = groupId, accountId = accountId,
    isNotification = notification, isFullContent = fullContent, isBrowser = browser,
)
