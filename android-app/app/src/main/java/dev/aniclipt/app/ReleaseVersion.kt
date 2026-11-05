package dev.aniclipt.app

internal fun compareReleaseVersions(first: String, second: String): Int {
    val a = Regex("\\d+").findAll(first).map { it.value.toIntOrNull() ?: 0 }.toList()
    val b = Regex("\\d+").findAll(second).map { it.value.toIntOrNull() ?: 0 }.toList()
    for (index in 0 until maxOf(a.size, b.size)) {
        val comparison = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

internal fun <T> isCompatibleUpdate(
    installedPackage: String,
    archivePackage: String,
    installedVersionCode: Long,
    archiveVersionCode: Long,
    installedSignatures: Set<T>,
    archiveSignatures: Set<T>,
): Boolean = installedPackage == archivePackage && archiveVersionCode > installedVersionCode &&
    installedSignatures.isNotEmpty() && archiveSignatures.isNotEmpty() && installedSignatures == archiveSignatures
