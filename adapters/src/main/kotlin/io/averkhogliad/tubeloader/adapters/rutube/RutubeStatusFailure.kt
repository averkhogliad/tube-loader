package io.averkhogliad.tubeloader.adapters.rutube

/**
 * The status the source refused the media with, carried as the cause of a failed outcome: a refused
 * status is a verdict of the source, not a hiccup of the transport, and the caller reads the status
 * from the cause. The retry loop never sees this type: it is built after the loop has stopped.
 */
internal class RutubeStatusFailure(val status: Int) : Exception("the source answered HTTP $status")
