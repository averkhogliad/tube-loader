package io.averkhogliad.tubeloader.tools.smoke

/**
 * One row of the live table: the planned target, the file it lands in, and the width of the name
 * column shared by every row of the run. The key is the index of the target in the command, so two
 * targets with the same name keep rows of their own.
 */
internal data class TargetRow(
    val key: String,
    val name: String,
    val fileName: String,
    val width: Int,
)
