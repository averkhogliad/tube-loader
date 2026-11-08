package io.averkhogliad.tubeloader.core.domain

/**
 * A source of media: the code is its permanent identifier, the name is for the reader.
 *
 * The code does not depend on the order of the registration and does not change with a rename of
 * the name, so a link to a source keeps working. It is lowercase and not empty: the core compares
 * the codes exactly, so two spellings of one code would be two sources.
 */
data class Source(val code: String, val displayName: String) {

    init {
        require(code.isNotBlank()) { "a source code must not be blank" }
        require(code == code.lowercase()) { "a source code must be lowercase: '$code'" }
    }
}
