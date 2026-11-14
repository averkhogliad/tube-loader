package io.averkhogliad.tubeloader.infra.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * The logger of the class the delegate is declared in: the name of the logger is the name of the
 * instance's class, so the backend can filter the diagnostics by the place they come from.
 *
 * The facade is all a module knows: the backend is chosen by the composition root, and the modules
 * keep logging through this delegate.
 */
fun slf4j(): ReadOnlyProperty<Any, Logger> = Slf4jDelegate(name = null, type = null)

/** The logger of the explicit [name]. */
fun slf4j(name: String): ReadOnlyProperty<Any, Logger> = Slf4jDelegate(name = name, type = null)

/** The logger of [type]: the name of the logger is the name of that class. */
fun slf4j(type: KClass<*>): ReadOnlyProperty<Any, Logger> = Slf4jDelegate(name = null, type = type)

private class Slf4jDelegate(private val name: String?, private val type: KClass<*>?) :
    ReadOnlyProperty<Any, Logger> {

    override fun getValue(thisRef: Any, property: KProperty<*>): Logger =
        when {
            name != null -> LoggerFactory.getLogger(name)
            type != null -> LoggerFactory.getLogger(type.java)
            else -> LoggerFactory.getLogger(thisRef.javaClass)
        }
}
