package com.justnels.agenticdroid.env

/** A resolved package pool entry from a Debian/Termux apt index: download path plus checksum metadata. */
data class PackageArtifact(
    val filename: String,
    val sha256: String? = null,
    val size: Long? = null
)
