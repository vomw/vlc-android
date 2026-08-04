/*****************************************************************************
 * FileBrowserProvider.kt
 *****************************************************************************
 * Copyright © 2018 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.providers

import android.content.Context
import android.hardware.usb.UsbDevice
import androidx.core.net.toUri
import androidx.lifecycle.Observer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.libvlc.util.MediaBrowser
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.DummyItem
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.AndroidDevices
import org.videolan.tools.livedata.LiveDataset
import org.videolan.vlc.ExternalMonitor
import org.videolan.vlc.R
import org.videolan.vlc.VlcMigrationHelper
import org.videolan.vlc.gui.helpers.hf.OTG_SCHEME
import org.videolan.vlc.gui.helpers.hf.StoragePermissionsDelegate
import org.videolan.vlc.gui.helpers.hf.getDocumentFiles
import org.videolan.vlc.repository.DirectoryRepository
import org.videolan.vlc.util.FileUtils
import java.io.File

open class FileBrowserProvider(
        context: Context,
        dataset: LiveDataset<MediaLibraryItem>,
        url: String?, private val filePicker: Boolean = false,
        private val showDummyCategory: Boolean = true, sort:Int, desc:Boolean) : BrowserProvider(context, dataset,
        url, sort, desc), Observer<MutableList<UsbDevice>> {

    init {
        fetch()
    }

    private lateinit var storageObserver : Observer<Boolean>

    // list of mounted storage devices
    private var cachedStorage: List<String> = ArrayList()

    override suspend fun browseRootImpl() {
        loading.postValue(true)
        var storageAccess = false
        val internalmemoryTitle = context.getString(R.string.internal_memory)
        val browserStorage = context.getString(R.string.browser_storages)
        val storages = DirectoryRepository.getInstance(context).getMediaDirectories()
        val devices = mutableListOf<MediaLibraryItem>()
        cachedStorage = storages.toList()
        // list of readable device keys, that would not go through OTG
        val readableDeviceKeys = storages.filter { File(it).canRead() }
                .map { it.substringAfterLast('/') }.toList()
        if (!filePicker && showDummyCategory) devices.add(DummyItem(browserStorage))
        for (mediaDirLocation in storages) {
            val file = File(mediaDirLocation)
            // a genuine removable device is exposed as a raw /mnt/media_rw/<uuid> mount,
            val isRemovableRawMount = mediaDirLocation.startsWith("/mnt/media_rw/")
            if ((!file.exists() || !file.canRead()) && !isRemovableRawMount) continue
            storageAccess = true
            val directory = MLServiceLocator.getAbstractMediaWrapper(AndroidUtil.PathToUri(mediaDirLocation))
            directory.type = MediaWrapper.TYPE_DIR
            if (AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY == mediaDirLocation) {
                directory.setDisplayTitle(internalmemoryTitle)
                devices.add(directory)
            } else {
                val deviceName = FileUtils.getStorageTag(directory.title)
                if (deviceName != null) directory.setDisplayTitle(deviceName)
                directory.addStateFlags(MediaLibraryItem.FLAG_STORAGE)
                val deviceKey = mediaDirLocation.substringAfterLast('/')
                when {
                    // readable: mount it
                    file.canRead() -> devices.add(directory)
                    // unreadable, fallback to OTG
                    isRemovableRawMount && deviceKey !in readableDeviceKeys
                            && VlcMigrationHelper.isLolliPopOrLater
                            && !ExternalMonitor.devices.isEmpty() -> {
                        val otg = MLServiceLocator.getAbstractMediaWrapper("$OTG_SCHEME://$deviceKey".toUri()).apply {
                            title = deviceName?.let { "OTG - $it" } ?: context.getString(R.string.otg_device_title)
                            type = MediaWrapper.TYPE_DIR
                        }
                        devices.add(otg)
                    }
                }
            }
        }
        if (AndroidUtil.isMarshMallowOrLater && !storageAccess) {
            storageObserver = Observer { if (it == true) launch { browseRoot() } }
            StoragePermissionsDelegate.storageAccessGranted.observeForever(storageObserver)
        }
        // For first launch, storage access may not already be granted
        if (!storageAccess) {
            loading.postValue(false)
            dataset.value = arrayListOf()
            return
        }
        dataset.value = devices
        // observe devices & favorites
        ExternalMonitor.devices.observeForever(this@FileBrowserProvider)
        loading.postValue(false)
        //no headers in root
        headers.clear()
    }

    override suspend fun requestBrowsing(url: String?, eventListener: MediaBrowser.EventListener, interact : Boolean) = withContext(coroutineContextProvider.IO) {
        initBrowser()
        mediabrowser?.let {
            it.changeEventListener(eventListener)
            if (url != null) it.browse(url.toUri(), getFlags(interact))
        }
    }

    override fun browse(url: String?) {
        val uri = url?.toUri()
        when {
            uri?.scheme == OTG_SCHEME || uri?.scheme == "content" -> launch {
                loading.postValue(true)
                dataset.value = withContext(coroutineContextProvider.IO) {
                    @Suppress("UNCHECKED_CAST")
                    getDocumentFiles(context, uri!!) as? MutableList<MediaLibraryItem> ?: mutableListOf()
                }
                loading.postValue(false)
            }
            url == "root" -> launch { browseRootImpl() }
            else -> super.browse(url)
        }
    }

    suspend fun browseByUrl(url: String): List<MediaWrapper> {
        val uri = url.toUri()
        return when {
            uri.scheme == OTG_SCHEME || uri.scheme == "content" -> {
                val result = ArrayList<MediaWrapper>()
                launch {
                    val files = withContext(coroutineContextProvider.IO) {
                        @Suppress("UNCHECKED_CAST")
                        getDocumentFiles(context, uri) as? MutableList<MediaLibraryItem> ?: mutableListOf()
                    }.map { it as MediaWrapper }

                    result.addAll(files.filter { it.itemType == MediaWrapper.TYPE_MEDIA })
                    files.filter { it.itemType == MediaWrapper.TYPE_DIR }.forEach {
                        result.addAll(browseByUrl(it.uri.toString()))
                    }
                }
                result.toList()
            }

            else -> super.browseUrl(url).toList().map { it as MediaWrapper }
        }
    }

    override fun release() {
        if (url == null) {
            ExternalMonitor.devices.removeObserver(this)
            if (this::storageObserver.isInitialized) {
                StoragePermissionsDelegate.storageAccessGranted.removeObserver(storageObserver)
            }
        }
        super.release()
    }

    override fun onChanged(list: MutableList<UsbDevice>) {
        // there may be time between usb detection and mount
        launch {
            repeat(20) {
                val storages = DirectoryRepository.getInstance(context).getMediaDirectories()
                if (cachedStorage.size == storages.size) {
                    delay(500)
                } else {
                    browseRoot()
                    return@launch
                }
            }
        }
    }
}
