package org.jetbrains.research.kex.trace.runner

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.parser.ParseException
import org.jetbrains.research.kex.asm.transform.TraceInstrumenter
import org.jetbrains.research.kex.config.kexConfig
import org.jetbrains.research.kex.trace.ActionParseException
import org.jetbrains.research.kex.trace.ActionParser
import org.jetbrains.research.kex.trace.Trace
import org.jetbrains.research.kex.util.getMethod
import org.jetbrains.research.kex.util.log
import org.jetbrains.research.kex.util.tryOrNull
import org.jetbrains.research.kfg.ir.Method
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files

private val timeout = kexConfig.getLongValue("runner", "timeout", 1000L)
private val traceLimit = kexConfig.getIntValue("runner", "trace-limit", 0)

class TraceParseError : Exception()
class TimeoutException : Exception()

private fun runWithTimeout(timeout: Long, body: () -> Unit) {
    val thread = Thread(body)

    thread.start()
    thread.join(timeout)
    if (thread.isAlive) {
        @Suppress("DEPRECATION")
        thread.stop()
        throw TimeoutException()
    }
}

abstract class AbstractRunner(val method: Method, protected val loader: ClassLoader) {
    protected val javaClass = loader.loadClass(method.`class`.canonicalDesc)
    protected val javaMethod = javaClass.getMethod(method, loader)

    class InvocationResult {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        var returnValue: Any? = null
        var exception: Throwable? = null
        lateinit var trace: List<String>

        operator fun component1() = output
        operator fun component2() = error
        operator fun component3() = exception
    }

    protected fun parse(result: InvocationResult): Trace {
        val lines = result.trace.filter { it.isNotBlank() }

        val parser = ActionParser(method.cm)

        if (traceLimit > 0 && lines.size > traceLimit) {
            log.warn("Trace size exceeds the limit of $traceLimit lines, skipping it")
            throw TraceParseError()
        }

        val actions = lines
                .mapNotNull {
                    try {
                        parser.parseToEnd(it)
                    } catch (e: ParseException) {
                        log.error("Failed to parse $method output: $e")
                        log.error("Failed line: $it")
                        null
                    } catch (e: ActionParseException) {
                        log.error("Failed to parse $method output: $e")
                        log.error("Failed line: $it")
                        null
                    }
                }

        return Trace.parse(actions, result.exception)
    }

    protected fun invoke(method: java.lang.reflect.Method, instance: Any?, args: Array<Any?>): Trace {
        tryOrNull {
            log.debug("Running $method")
            log.debug("Instance: $instance")
            log.debug("Args: ${args.map { it.toString() }}")
        }

        Files.deleteIfExists(TraceInstrumenter.getTraceFile(this.method).toPath())

        val result = InvocationResult()
        if (!method.isAccessible) method.isAccessible = true

        val oldOut = System.out
        val oldErr = System.err

        try {
            System.setOut(PrintStream(result.output))
            System.setErr(PrintStream(result.error))

            runWithTimeout(timeout) {
                try {
                    result.returnValue = method.invoke(instance, *args)
                } catch (e: InvocationTargetException) {
                    result.exception = e.targetException
                }
            }
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }


        if (result.output.size() != 0) log.debug("Invocation output:\n${result.output}")
        if (result.error.size() != 0) log.debug("Invocation error:\n${result.error}")
        if (result.exception != null)
            log.debug("Invocation exception: ${result.exception}")

        val traceFile = TraceInstrumenter.getTraceFile(this.method)
        result.trace = traceFile.readText().split(";").map { it.trim() }
        return parse(result)
    }

    open fun run(instance: Any?, args: Array<Any?>) = invoke(javaMethod, instance, args)
    operator fun invoke(instance: Any?, args: Array<Any?>) = run(instance, args)
    open fun invokeStatic(args: Array<Any?>) = invoke(null, args)
}

class SimpleRunner(method: Method, loader: ClassLoader) : AbstractRunner(method, loader)