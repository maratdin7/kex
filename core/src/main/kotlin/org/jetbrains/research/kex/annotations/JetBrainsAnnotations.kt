package org.jetbrains.research.kex.annotations

import org.jetbrains.research.kex.asm.manager.MethodManager
import org.jetbrains.research.kex.config.kexConfig
import org.jetbrains.research.kex.ktype.KexBool
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.StateBuilder
import org.jetbrains.research.kex.state.predicate.CallPredicate
import org.jetbrains.research.kex.state.predicate.assume
import org.jetbrains.research.kex.state.predicate.path
import org.jetbrains.research.kex.state.predicate.state
import org.jetbrains.research.kex.state.term.CallTerm
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.term
import org.jetbrains.research.kex.state.wrap
import org.jetbrains.research.kex.util.unreachable

@AnnotationFunctionality("org.jetbrains.annotations.Range")
class Range(val from: Long, val to: Long) : AnnotationInfo() {
    override fun preciseValue(value: Term) =
            assume { (value ge from) equality true }.wrap() + assume { (value le to) equality true }
}

@AnnotationFunctionality("org.jetbrains.annotations.NotNull")
class NotNull : AnnotationInfo() {
    override fun preciseValue(value: Term) = assume { value inequality null }.wrap()
}

@AnnotationFunctionality("org.jetbrains.annotations.Nullable")
class Nullable : AnnotationInfo()

@AnnotationFunctionality("org.jetbrains.annotations.Contract")
class Contract(val value: String = ""/*, pure: Boolean = false*/) : AnnotationInfo() {

    private enum class Constraints(val literal: String) {
        Any("_"), Null("null"), NotNull("!null"),
        True("true"), False("false"), Fail("fail"),
        New("new"), This("this"), Param("param");

        companion object {
            private val byLiteral = values().associateBy { it.literal }
            operator fun get(literal: String) = byLiteral[literal]
                    ?: throw AnnotationParserException("Unsupported value constraint \"$literal\"")
        }
    }

    private class Record(val params: List<Constraints>, val result: Constraints, val meta: Int = -1)

    private val records = mutableListOf<Record>()

    override fun initialize(n: Int) {
        val paramN = call.params.size
        if (value.isNotBlank()) {
            for (clause in value.split(';')) {
                val (argsStr, result) = clause.split("->")
                val args = when {
                    argsStr.isBlank() -> emptyList()
                    else -> argsStr.split(',').map { Constraints[it.trim()] }
                }
                check(args.size == paramN) {
                    "Parameters count ${args.size} in contract requires to be the same as in the call $paramN"
                }
                val resultLiteral = result.trim()
                records += when {
                    resultLiteral.startsWith("param") -> {
                        val i = resultLiteral.substring(5).toInt()
                        Record(args, Constraints.Param, i - 1)
                    }
                    else -> Record(args, Constraints[resultLiteral])
                }
            }
        }
        // Find errors
        for (record in records) {
            if (record.result == Constraints.Any)
                throw IllegalStateException("Contract effect should be specified")
            for (param in record.params) {
                when (param) {
                    in effects2, Constraints.New, Constraints.Fail ->
                        throw IllegalStateException("Constraint ${param.literal} is an effect")
                    else -> Unit
                }
            }
        }
    }

    private fun getTermByConstraint(constraint: Constraints, arg: Term) = term {
        when (constraint) {
            Constraints.Any -> const(true)
            Constraints.Null -> arg eq null
            Constraints.NotNull -> arg neq null
            Constraints.True -> arg eq true
            Constraints.False -> arg eq false
            Constraints.Fail, Constraints.New, Constraints.This, Constraints.Param ->
                throw IllegalStateException("The ${constraint.literal} constraint value may be" +
                        " interpreted as effect only")
        }
    }

    override fun preciseBeforeCall(predicate: CallPredicate): PredicateState? {
        val builder = StateBuilder()
        val call = predicate.call as CallTerm
        val args = call.arguments
        for (record in records.asSequence().filter { it.result == Constraints.Fail }) {
            val params = record.params
            for (i in 0 until params.size) {
                if (params[i] == Constraints.Any)
                    continue
                builder += assume { getTermByConstraint(params[i], args[i]) equality false }
            }
        }
        return builder.apply()
    }

    private companion object {
        private var count = 0
        val id get() = count++
        val effects1 = arrayOf(Constraints.Null, Constraints.NotNull, Constraints.True, Constraints.False)
        val effects2 = arrayOf(Constraints.This, Constraints.Param)
    }

    override fun preciseAfterCall(predicate: CallPredicate): PredicateState? {
        val call = predicate.call as CallTerm
        val args = call.arguments
        val returnTerm = predicate.lhvUnsafe ?: return null
        val id = id.toString()
        val result = StateBuilder()

        val inlineEnabled = kexConfig.getBooleanValue("smt", "ps-inlining", true)
                && MethodManager.InlineManager.isInlinable(call.method)

        // New statement insertion
        if (!inlineEnabled && records.any { it.result == Constraints.New }) {
            for ((i, record) in records.asSequence().withIndex().filter { it.value.result == Constraints.New }) {
                val params = record.params
                val argUnion = term { value(KexBool(), "%contract$id.$i.args") }
                result += state {
                    var accumulator: Term = const(true)
                    for (j in 0 until params.size) {
                        if (params[j] != Constraints.Any)
                            accumulator = accumulator and getTermByConstraint(params[j], args[j])
                    }
                    argUnion equality accumulator
                }
                result += listOf(
                        path { argUnion equality true }.wrap() + state { returnTerm.new() },
                        path { argUnion equality false }.wrap()
                )
            }
        }

        // Make boolean functions for same cases
        if (records.all { it.result !in effects1 }) {
            for (record in records.filter { it.result in effects1 || inlineEnabled && it.result == Constraints.New }) {
                val params = record.params

                result += assume {
                    val effect = when (record.result) {
                        Constraints.Null -> returnTerm eq const(null)
                        Constraints.NotNull, Constraints.New -> returnTerm neq const(null)
                        Constraints.True -> returnTerm eq const(true)
                        Constraints.False -> returnTerm eq const(false)
                        else -> unreachable { record.result }
                    }
                    var argsCheck: Term? = null
                    for (i in 0 until params.size) {
                        if (params[i] == Constraints.Any)
                            continue
                        if (argsCheck == null)
                            argsCheck = getTermByConstraint(params[i], args[i])
                        else
                            argsCheck = argsCheck and getTermByConstraint(params[i], args[i])
                    }
                    val check = if (argsCheck != null)
                        !argsCheck or effect
                    else
                        effect
                    check equality true
                }
            }
        }
        if (records.any { it.result in effects2 }) {
            for ((i, record) in records.withIndex().filter { it.value.result in effects2 }) {
                val params = record.params
                val argUnion = term { value(KexBool(), "%contract$id.$i.args") }
                result += assume {
                    var accumulator: Term = const(true)
                    for (j in 0 until params.size) {
                        if (params[j] != Constraints.Any)
                            accumulator = accumulator and getTermByConstraint(params[j], args[j])
                    }
                    argUnion equality accumulator
                }
                result += listOf(
                        path { argUnion equality true }.wrap() + assume {
                            returnTerm equality when (record.result) {
                                Constraints.This -> call.owner
                                Constraints.Param -> args[record.meta]
                                else -> unreachable { record.result }
                            }
                        },
                        path { argUnion equality false }.wrap()
                )
            }
        }
        return result.apply()
    }
}
