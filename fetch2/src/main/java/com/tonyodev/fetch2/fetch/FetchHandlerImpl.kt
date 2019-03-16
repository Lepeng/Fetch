package com.tonyodev.fetch2.fetch

import android.os.Handler
import android.os.Looper
import com.tonyodev.fetch2.*
import com.tonyodev.fetch2.database.DatabaseManager
import com.tonyodev.fetch2.database.DownloadInfo
import com.tonyodev.fetch2.downloader.DownloadManager
import com.tonyodev.fetch2.exception.FetchException
import com.tonyodev.fetch2.helper.PriorityListProcessor
import com.tonyodev.fetch2.util.*
import com.tonyodev.fetch2core.*
import java.io.IOException
import java.util.*

/**
 * This handlerWrapper class handles all tasks and operations of Fetch.
 * */
class FetchHandlerImpl(private val namespace: String,
                       private val databaseManager: DatabaseManager,
                       private val downloadManager: DownloadManager,
                       private val priorityListProcessor: PriorityListProcessor<Download>,
                       private val logger: Logger,
                       private val autoStart: Boolean,
                       private val httpDownloader: Downloader<*, *>,
                       private val fileServerDownloader: FileServerDownloader,
                       private val listenerCoordinator: ListenerCoordinator,
                       private val uiHandler: Handler,
                       private val storageResolver: StorageResolver,
                       private val fetchNotificationManager: FetchNotificationManager?) : FetchHandler {

    private val listenerId = UUID.randomUUID().hashCode()
    private val listenerSet = mutableSetOf<FetchListener>()

    @Volatile
    private var isTerminating = false

    override fun init() {
        if (fetchNotificationManager != null) {
            listenerCoordinator.addNotificationManager(fetchNotificationManager)
        }
        databaseManager.sanitizeOnFirstEntry()
        if (autoStart) {
            priorityListProcessor.start()
        }
    }

    override fun enqueue(request: Request): Pair<Download, Error> {
        return enqueueRequests(listOf(request)).first()
    }

    override fun enqueue(requests: List<Request>): List<Pair<Download, Error>> {
        return enqueueRequests(requests)
    }

    private fun enqueueRequests(requests: List<Request>): List<Pair<Download, Error>> {
        val results = mutableListOf<Pair<Download, Error>>()
        requests.forEach {
            val downloadInfo = it.toDownloadInfo()
            downloadInfo.namespace = namespace
            try {
                val existing = prepareDownloadInfoForEnqueue(downloadInfo)
                if (downloadInfo.status != Status.COMPLETED) {
                    downloadInfo.status = if (it.downloadOnEnqueue) {
                        Status.QUEUED
                    } else {
                        Status.ADDED
                    }
                    if (!existing) {
                        val downloadPair = databaseManager.insert(downloadInfo)
                        logger.d("Enqueued download ${downloadPair.first}")
                        results.add(Pair(downloadPair.first, Error.NONE))
                    } else {
                        databaseManager.update(downloadInfo)
                        logger.d("Updated download $downloadInfo")
                        results.add(Pair(downloadInfo, Error.NONE))
                    }
                } else {
                    results.add(Pair(downloadInfo, Error.NONE))
                }
            } catch (e: Exception) {
                val error = getErrorFromThrowable(e)
                error.throwable = e
                results.add(Pair(downloadInfo, error))
            }
        }
        startPriorityQueueIfNotStarted()
        return results
    }

    private fun prepareDownloadInfoForEnqueue(downloadInfo: DownloadInfo): Boolean {
        cancelDownloadsIfDownloading(listOf(downloadInfo))
        var existingDownload = databaseManager.getByFile(downloadInfo.file)
        if (existingDownload == null) {
            if (downloadInfo.enqueueAction != EnqueueAction.INCREMENT_FILE_NAME) {
                storageResolver.createFile(downloadInfo.file)
            }
        } else {
            cancelDownloadsIfDownloading(listOf(existingDownload))
            existingDownload = databaseManager.getByFile(downloadInfo.file)
            if (existingDownload != null && existingDownload.status == Status.DOWNLOADING) {
                existingDownload.status = Status.QUEUED
                try {
                    databaseManager.update(existingDownload)
                } catch (e: Exception) {

                }
            } else if (existingDownload?.status == Status.COMPLETED
                    && downloadInfo.enqueueAction == EnqueueAction.UPDATE_ACCORDINGLY) {
                if (!storageResolver.fileExists(existingDownload.file)) {
                    try {
                        databaseManager.delete(existingDownload)
                    } catch (e: Exception) {
                    }
                    existingDownload = null
                    if (downloadInfo.enqueueAction != EnqueueAction.INCREMENT_FILE_NAME) {
                        storageResolver.createFile(downloadInfo.file)
                    }
                }
            }
        }
        return when (downloadInfo.enqueueAction) {
            EnqueueAction.UPDATE_ACCORDINGLY -> {
                if (existingDownload != null) {
                    downloadInfo.downloaded = existingDownload.downloaded
                    downloadInfo.total = existingDownload.total
                    downloadInfo.error = existingDownload.error
                    downloadInfo.status = existingDownload.status
                    if (downloadInfo.status != Status.COMPLETED) {
                        downloadInfo.status = Status.QUEUED
                        downloadInfo.error = defaultNoError
                    }
                    true
                } else {
                    false
                }
            }
            EnqueueAction.DO_NOT_ENQUEUE_IF_EXISTING -> {
                if (existingDownload != null) {
                    throw FetchException(REQUEST_WITH_FILE_PATH_ALREADY_EXIST)
                } else {
                    false
                }
            }
            EnqueueAction.REPLACE_EXISTING -> {
                if (existingDownload != null) {
                    deleteDownloads(listOf(existingDownload))
                }
                deleteDownloads(listOf(downloadInfo))
                return false
            }
            EnqueueAction.INCREMENT_FILE_NAME -> {
                val file = storageResolver.createFile(downloadInfo.file, true)
                downloadInfo.file = file
                downloadInfo.id = getUniqueId(downloadInfo.url, downloadInfo.file)
                false
            }
        }
    }

    override fun enqueueCompletedDownload(completedDownload: CompletedDownload): Download {
        return enqueueCompletedDownloads(listOf(completedDownload)).first()
    }

    override fun enqueueCompletedDownloads(completedDownloads: List<CompletedDownload>): List<Download> {
        return completedDownloads.map {
            val downloadInfo = it.toDownloadInfo()
            downloadInfo.namespace = namespace
            downloadInfo.status = Status.COMPLETED
            prepareCompletedDownloadInfoForEnqueue(downloadInfo)
            val downloadPair = databaseManager.insert(downloadInfo)
            logger.d("Enqueued CompletedDownload ${downloadPair.first}")
            downloadPair.first
        }
    }

    private fun prepareCompletedDownloadInfoForEnqueue(downloadInfo: DownloadInfo) {
        val existingDownload = databaseManager.getByFile(downloadInfo.file)
        if (existingDownload != null) {
            deleteDownloads(listOf(downloadInfo))
        }
    }

    override fun pause(ids: List<Int>): List<Download> {
        return pauseDownloads(databaseManager.get(ids).filterNotNull())
    }

    override fun pausedGroup(id: Int): List<Download> {
        return pauseDownloads(databaseManager.getByGroup(id))
    }

    private fun pauseDownloads(downloads: List<DownloadInfo>): List<Download> {
        cancelDownloadsIfDownloading(downloads)
        val pausedDownloads = mutableListOf<DownloadInfo>()
        downloads.forEach {
            if (canPauseDownload(it)) {
                it.status = Status.PAUSED
                pausedDownloads.add(it)
            }
        }
        databaseManager.update(pausedDownloads)
        return pausedDownloads
    }

    override fun freeze() {
        priorityListProcessor.pause()
        downloadManager.cancelAll()
    }

    override fun unfreeze() {
        priorityListProcessor.resume()
    }

    override fun resume(ids: List<Int>): List<Download> {
        return resumeDownloads(ids)
    }

    override fun resumeGroup(id: Int): List<Download> {
        return resumeDownloads(databaseManager.getByGroup(id).map { it.id })
    }

    private fun resumeDownloads(downloadIds: List<Int>): List<Download> {
        val downloads = databaseManager.get(downloadIds).filterNotNull()
        val resumedDownloads = mutableListOf<DownloadInfo>()
        downloads.forEach {
            if (!downloadManager.contains(it.id) && canResumeDownload(it)) {
                it.status = Status.QUEUED
                resumedDownloads.add(it)
            }
        }
        databaseManager.update(resumedDownloads)
        startPriorityQueueIfNotStarted()
        return resumedDownloads
    }

    override fun remove(ids: List<Int>): List<Download> {
        return removeDownloads(databaseManager.get(ids).filterNotNull())
    }

    override fun removeGroup(id: Int): List<Download> {
        return removeDownloads(databaseManager.getByGroup(id))
    }

    override fun removeAll(): List<Download> {
        return removeDownloads(databaseManager.get())
    }

    override fun removeAllWithStatus(status: Status): List<Download> {
        return removeDownloads(databaseManager.getByStatus(status))
    }

    override fun removeAllInGroupWithStatus(groupId: Int, statuses: List<Status>): List<Download> {
        return removeDownloads(databaseManager.getDownloadsInGroupWithStatus(groupId, statuses))
    }

    private fun removeDownloads(downloads: List<DownloadInfo>): List<Download> {
        cancelDownloadsIfDownloading(downloads)
        databaseManager.delete(downloads)
        downloads.forEach {
            it.status = Status.REMOVED
            databaseManager.delegate?.deleteTempFilesForDownload(it)
        }
        return downloads
    }

    override fun delete(ids: List<Int>): List<Download> {
        return deleteDownloads(databaseManager.get(ids).filterNotNull())
    }

    override fun deleteGroup(id: Int): List<Download> {
        return deleteDownloads(databaseManager.getByGroup(id))
    }

    override fun deleteAll(): List<Download> {
        return deleteDownloads(databaseManager.get())
    }

    override fun deleteAllWithStatus(status: Status): List<Download> {
        return deleteDownloads(databaseManager.getByStatus(status))
    }

    override fun deleteAllInGroupWithStatus(groupId: Int, statuses: List<Status>): List<Download> {
        return deleteDownloads(databaseManager.getDownloadsInGroupWithStatus(groupId, statuses))
    }

    private fun deleteDownloads(downloads: List<DownloadInfo>): List<Download> {
        cancelDownloadsIfDownloading(downloads)
        databaseManager.delete(downloads)
        downloads.forEach {
            it.status = Status.DELETED
            storageResolver.deleteFile(it.file)
            databaseManager.delegate?.deleteTempFilesForDownload(it)
        }
        return downloads
    }

    override fun cancel(ids: List<Int>): List<Download> {
        return cancelDownloads(databaseManager.get(ids).filterNotNull())
    }

    override fun cancelGroup(id: Int): List<Download> {
        return cancelDownloads(databaseManager.getByGroup(id))
    }

    override fun cancelAll(): List<Download> {
        return cancelDownloads(databaseManager.get())
    }

    private fun cancelDownloads(downloads: List<DownloadInfo>): List<Download> {
        cancelDownloadsIfDownloading(downloads)
        val cancelledDownloads = mutableListOf<DownloadInfo>()
        downloads.forEach {
            if (canCancelDownload(it)) {
                it.status = Status.CANCELLED
                it.error = defaultNoError
                cancelledDownloads.add(it)
            }
        }
        databaseManager.update(cancelledDownloads)
        return cancelledDownloads
    }

    override fun retry(ids: List<Int>): List<Download> {
        val downloadInfoList = databaseManager.get(ids).filterNotNull()
        val retryDownloads = mutableListOf<DownloadInfo>()
        downloadInfoList.forEach {
            if (canRetryDownload(it)) {
                it.status = Status.QUEUED
                it.error = defaultNoError
                retryDownloads.add(it)
            }
        }
        databaseManager.update(retryDownloads)
        startPriorityQueueIfNotStarted()
        return retryDownloads
    }

    override fun updateRequest(requestId: Int, newRequest: Request): Pair<Download, Boolean> {
        var oldDownloadInfo = databaseManager.get(requestId)
        if (oldDownloadInfo != null) {
            cancelDownloadsIfDownloading(listOf(oldDownloadInfo))
            oldDownloadInfo = databaseManager.get(requestId)
        }
        return if (oldDownloadInfo != null) {
            if (newRequest.file == oldDownloadInfo.file) {
                val newDownloadInfo = newRequest.toDownloadInfo()
                newDownloadInfo.namespace = namespace
                newDownloadInfo.downloaded = oldDownloadInfo.downloaded
                newDownloadInfo.total = oldDownloadInfo.total
                if (oldDownloadInfo.status == Status.DOWNLOADING) {
                    newDownloadInfo.status = Status.QUEUED
                    newDownloadInfo.error = defaultNoError
                } else {
                    newDownloadInfo.status = oldDownloadInfo.status
                    newDownloadInfo.error = oldDownloadInfo.error
                }
                databaseManager.delete(oldDownloadInfo)
                databaseManager.insert(newDownloadInfo)
                startPriorityQueueIfNotStarted()
                return Pair(newDownloadInfo, true)
            } else {
                delete(listOf(requestId))
                val enqueuePair = enqueue(newRequest)
                Pair(enqueuePair.first, enqueuePair.second == Error.NONE)
            }
        } else {
            throw FetchException(REQUEST_DOES_NOT_EXIST)
        }
    }

    override fun replaceExtras(id: Int, extras: Extras): Download {
        var downloadInfo = databaseManager.get(id)
        if (downloadInfo != null) {
            cancelDownloadsIfDownloading(listOf(downloadInfo))
            downloadInfo = databaseManager.get(id)
        }
        return if (downloadInfo != null) {
            val download = databaseManager.updateExtras(id, extras)
            download ?: throw FetchException(REQUEST_DOES_NOT_EXIST)
        } else {
            throw FetchException(REQUEST_DOES_NOT_EXIST)
        }
    }

    override fun getDownloads(): List<Download> {
        return databaseManager.get()
    }

    override fun getDownload(id: Int): Download? {
        return databaseManager.get(id)
    }

    override fun getDownloads(idList: List<Int>): List<Download> {
        return databaseManager.get(idList).filterNotNull()
    }

    override fun getDownloadByFile(file: String): Download? {
        return databaseManager.getByFile(file)
    }

    override fun getDownloadsInGroup(id: Int): List<Download> {
        return databaseManager.getByGroup(id)
    }

    override fun getDownloadsWithStatus(status: Status): List<Download> {
        return databaseManager.getByStatus(status)
    }

    override fun getDownloadsInGroupWithStatus(groupId: Int, statuses: List<Status>): List<Download> {
        return databaseManager.getDownloadsInGroupWithStatus(groupId, statuses)
    }

    override fun getDownloadsByRequestIdentifier(identifier: Long): List<Download> {
        return databaseManager.getDownloadsByRequestIdentifier(identifier)
    }

    override fun getDownloadBlocks(id: Int): List<DownloadBlock> {
        val download = databaseManager.get(id)
        return if (download != null) {
            val fileTempDir = downloadManager.getDownloadFileTempDir(download)
            val fileSliceInfo = getFileSliceInfo(getPreviousSliceCount(download.id, fileTempDir), download.total)
            when {
                download.total < 1 -> listOf()
                fileSliceInfo.slicingCount < 2 -> {
                    val downloadBlockInfo = DownloadBlockInfo()
                    downloadBlockInfo.downloadId = download.id
                    downloadBlockInfo.blockPosition = 1
                    downloadBlockInfo.startByte = 0
                    downloadBlockInfo.endByte = download.total
                    downloadBlockInfo.downloadedBytes = download.downloaded
                    listOf(downloadBlockInfo)
                }
                else -> {
                    var counterBytes = 0L
                    val downloadBlocksList = mutableListOf<DownloadBlockInfo>()
                    for (position in 1..fileSliceInfo.slicingCount) {
                        val startBytes = counterBytes
                        val endBytes = if (fileSliceInfo.slicingCount == position) {
                            download.total
                        } else {
                            counterBytes + fileSliceInfo.bytesPerFileSlice
                        }
                        counterBytes = endBytes
                        val downloadBlockInfo = DownloadBlockInfo()
                        downloadBlockInfo.downloadId = download.id
                        downloadBlockInfo.blockPosition = position
                        downloadBlockInfo.startByte = startBytes
                        downloadBlockInfo.endByte = endBytes
                        downloadBlockInfo.downloadedBytes = getSavedDownloadedInfo(download.id, position, fileTempDir)
                        downloadBlocksList.add(downloadBlockInfo)
                    }
                    downloadBlocksList
                }
            }
        } else {
            return emptyList()
        }
    }

    override fun getContentLengthForRequest(request: Request, fromServer: Boolean): Long {
        val download = databaseManager.get(request.id)
        if (download != null && download.total > 0) {
            return download.total
        }
        return if (fromServer) {
            if (isFetchFileServerUrl(request.url)) {
                fileServerDownloader.getRequestContentLength(getServerRequestFromRequest(request))
            } else {
                httpDownloader.getRequestContentLength(getServerRequestFromRequest(request))
            }
        } else {
            -1L
        }
    }

    override fun getServerResponse(url: String, header: Map<String, String>?): Downloader.Response {
        val request = Request(url, "")
        header?.forEach {
            request.addHeader(it.key, it.value)
        }
        val serverRequest = getServerRequestFromRequest(request)
        val interruptMonitor = object : InterruptMonitor {
            override val isInterrupted: Boolean
                get() = false
        }
        if (isFetchFileServerUrl(request.url)) {
            val response = fileServerDownloader.execute(serverRequest, interruptMonitor)
            if (response != null) {
                val copy = copyDownloadResponseNoStream(response)
                fileServerDownloader.disconnect(response)
                return copy
            }
        } else {
            val response = httpDownloader.execute(serverRequest, interruptMonitor)
            if (response != null) {
                val copy = copyDownloadResponseNoStream(response)
                httpDownloader.disconnect(response)
                return copy
            }
        }
        throw IOException(RESPONSE_NOT_SUCCESSFUL)
    }

    override fun getFetchFileServerCatalog(request: Request): List<FileResource> {
        return fileServerDownloader.getFetchFileServerCatalog(getCatalogServerRequestFromRequest(request))
    }

    override fun close() {
        if (isTerminating) {
            return
        }
        isTerminating = true
        synchronized(listenerSet) {
            listenerSet.iterator().forEach {
                listenerCoordinator.removeListener(listenerId, it)
            }
            listenerSet.clear()
        }
        if (fetchNotificationManager != null) {
            listenerCoordinator.removeNotificationManager(fetchNotificationManager)
            listenerCoordinator.cancelOnGoingNotifications(fetchNotificationManager)
        }
        priorityListProcessor.stop()
        priorityListProcessor.close()
        downloadManager.close()
        FetchModulesBuilder.removeNamespaceInstanceReference(namespace)
    }

    override fun setGlobalNetworkType(networkType: NetworkType) {
        priorityListProcessor.stop()
        priorityListProcessor.globalNetworkType = networkType
        val ids = downloadManager.getActiveDownloadsIds()
        if (ids.isNotEmpty()) {
            var downloads = databaseManager.get(ids).filterNotNull()
            if (downloads.isNotEmpty()) {
                cancelDownloadsIfDownloading(downloads)
                downloads = databaseManager.get(ids).filterNotNull()
                downloads.forEach {
                    if (it.status == Status.DOWNLOADING) {
                        it.status = Status.QUEUED
                        it.error = defaultNoError
                    }
                }
                databaseManager.update(downloads)
            }
        }
        priorityListProcessor.start()
    }

    override fun setDownloadConcurrentLimit(downloadConcurrentLimit: Int) {
        priorityListProcessor.stop()
        val ids = downloadManager.getActiveDownloadsIds()
        if (ids.isNotEmpty()) {
            var downloads = databaseManager.get(ids).filterNotNull()
            if (downloads.isNotEmpty()) {
                cancelDownloadsIfDownloading(downloads)
                downloads = databaseManager.get(ids).filterNotNull()
                downloadManager.concurrentLimit = downloadConcurrentLimit
                priorityListProcessor.downloadConcurrentLimit = downloadConcurrentLimit
                downloads.forEach {
                    if (it.status == Status.DOWNLOADING) {
                        it.status = Status.QUEUED
                        it.error = defaultNoError
                    }
                }
                databaseManager.update(downloads)
            }
        }
        priorityListProcessor.start()
    }

    override fun enableLogging(enabled: Boolean) {
        logger.d("Enable logging - $enabled")
        logger.enabled = enabled
    }

    override fun addListener(listener: FetchListener, notify: Boolean, autoStart: Boolean) {
        synchronized(listenerSet) {
            listenerSet.add(listener)
        }
        listenerCoordinator.addListener(listenerId, listener)
        if (notify) {
            val downloads = databaseManager.get()
            downloads.forEach {
                uiHandler.post {
                    when (it.status) {
                        Status.COMPLETED -> {
                            listener.onCompleted(it)
                        }
                        Status.FAILED -> {
                            listener.onError(it, it.error, null)
                        }
                        Status.CANCELLED -> {
                            listener.onCancelled(it)
                        }
                        Status.DELETED -> {
                            listener.onDeleted(it)
                        }
                        Status.PAUSED -> {
                            listener.onPaused(it)
                        }
                        Status.QUEUED -> {
                            listener.onQueued(it, false)
                        }
                        Status.REMOVED -> {
                            listener.onRemoved(it)
                        }
                        Status.DOWNLOADING -> {
                        }
                        Status.ADDED -> {
                            listener.onAdded(it)
                        }
                        Status.NONE -> {
                        }
                    }
                }
            }
        }
        logger.d("Added listener $listener")
        if (autoStart) {
            startPriorityQueueIfNotStarted()
        }
    }

    override fun removeListener(listener: FetchListener) {
        synchronized(listenerSet) {
            val iterator = listenerSet.iterator()
            while (iterator.hasNext()) {
                val fetchListener = iterator.next()
                if (fetchListener == listener) {
                    iterator.remove()
                    logger.d("Removed listener $listener")
                    break
                }
            }
            listenerCoordinator.removeListener(listenerId, listener)
        }
    }

    override fun getListenerSet(): Set<FetchListener> {
        return synchronized(listenerSet) {
            listenerSet.toSet()
        }
    }

    override fun hasActiveDownloads(): Boolean {
        if (Thread.currentThread() == Looper.getMainLooper().thread) {
            throw FetchException(BLOCKING_CALL_ON_UI_THREAD)
        }
        return databaseManager.getPendingCount() > 0
    }

    override fun getPendingCount(): Long {
        return databaseManager.getPendingCount()
    }

    private fun cancelDownloadsIfDownloading(downloads: List<DownloadInfo>) {
        for (download in downloads) {
            if (downloadManager.contains(download.id)) {
                downloadManager.cancel(download.id)
            }
        }
    }

    private fun startPriorityQueueIfNotStarted() {
        priorityListProcessor.sendBackOffResetSignal()
        if (priorityListProcessor.isStopped && !isTerminating) {
            priorityListProcessor.start()
        }
        if (priorityListProcessor.isPaused && !isTerminating) {
            priorityListProcessor.resume()
        }
    }

}