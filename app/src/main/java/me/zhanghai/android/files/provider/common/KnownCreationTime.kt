package me.zhanghai.android.files.provider.common

/** Opt in only when the provider actually received birth time, never a modification fallback. */
interface KnownCreationTime {
    val hasCreationTime: Boolean
}
