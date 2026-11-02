/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.Path
import java.text.Collator
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.filelist.getCollationKeyForFileName
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.root.RootFileSystemProvider
import me.zhanghai.android.files.provider.root.RootableFileSystemProvider

object LinuxFileSystemProvider : RootableFileSystemProvider(
    { LocalLinuxFileSystemProvider(it as LinuxFileSystemProvider) },
    { RootFileSystemProvider(LocalLinuxFileSystemProvider.SCHEME) }
) {
    override val localProvider: LocalLinuxFileSystemProvider
        get() = super.localProvider as LocalLinuxFileSystemProvider

    override val rootProvider: RootFileSystemProvider
        get() = super.rootProvider as RootFileSystemProvider

    /** Index scans must never start the root service for stale or protected paths. */
    internal fun loadLocalRegularFileItem(path: Path, collator: Collator): FileItem? {
        val attributes = LocalLinuxFileAttributeView((path as LinuxPath).toByteString(), true)
            .readAttributes()
        if (!attributes.isRegularFile || attributes.isSymbolicLink) return null
        return FileItem(
            path, collator.getCollationKeyForFileName(path.name), attributes,
            null, null, localProvider.isHidden(path),
            AndroidFileTypeDetector.getMimeType(path, attributes).asMimeType()
        )
    }

    internal val fileSystem: LinuxFileSystem
        get() = localProvider.fileSystem

    internal fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        LocalLinuxFileSystemProvider.supportsFileAttributeView(type)
}
